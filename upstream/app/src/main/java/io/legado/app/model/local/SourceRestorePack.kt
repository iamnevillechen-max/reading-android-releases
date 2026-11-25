package io.legado.app.model.local

import android.content.Context
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.RssSource
import io.legado.app.help.book.ContentProcessor
import io.legado.app.help.http.newCallResponseBody
import io.legado.app.help.http.okHttpClient
import io.legado.app.model.cloud.CloudReader
import io.legado.app.model.cloud.LocalSourceRestoreRefresh
import io.legado.app.utils.GSON
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okio.ByteString.Companion.decodeBase64
import java.io.File
import java.util.UUID

object SourceRestorePack {
    class Link(val url: HttpUrl, val key: ByteArray) { override fun toString() = "Private original-source restore link" }
    class Prepared internal constructor(val file: File, val summary: SourceRestorePackCodec.Summary,
        val zipSha256: String = SourceRestorePackCodec.fileSha256(file)) {
        fun close() { file.delete() }
    }
    data class Result(val processed: Int, val restored: Int, val keptLocal: Int)
    fun recognizes(value: String) = value.trim().substringBefore('#').substringBefore('?').endsWith(".readerpack", true)
    fun parseLink(value: String): Link {
        val url = value.trim().toHttpUrl()
        require(url.isHttps) { "還原包需要 HTTPS 連結" }
        val fragment = url.fragment.orEmpty()
        val keyText = Regex("(?:^|&)key=([A-Za-z0-9_-]{43})(?:&|$)").find(fragment)?.groupValues?.get(1)
            ?: error("請貼上含私人金鑰的完整還原連結")
        val key = keyText.decodeBase64()?.toByteArray() ?: error("還原連結金鑰不正確")
        require(key.size == 32) { "還原連結金鑰不完整" }
        return Link(url.newBuilder().fragment(null).build(), key)
    }
    suspend fun prepare(context: Context, value: String, progress: (Int) -> Unit): Prepared = withContext(Dispatchers.IO) {
        val link = parseLink(value)
        val target = File(context.cacheDir, "source-restore-${UUID.randomUUID()}.zip")
        val job = currentCoroutineContext()[Job]
        try {
            val declaredRecords = okHttpClient.newCallResponseBody { url(link.url) }.use { body ->
                require(body.contentLength() <= SourceRestorePackCodec.MAX_ZIP_BYTES + 80) { "還原包過大" }
                body.byteStream().use { SourceRestorePackCodec.decrypt(it, link.key, target) { job?.ensureActive() } }
            }
            var checked = 0
            val summary = SourceRestorePackCodec.scan(target) { record ->
                job?.ensureActive(); validateModel(record); checked++
                if (checked % 100 == 0) progress(checked)
            }
            require(summary.total == declaredRecords) { "還原包筆數不符" }
            job?.ensureActive()
            require(target.setReadOnly()) { "無法保護已校驗的還原包" }
            Prepared(target, summary)
        } catch (failure: Throwable) {
            target.delete(); throw failure
        } finally { link.key.fill(0) }
    }
    private fun validateModel(record: SourceRestorePackCodec.Record) {
        if (record.kind == "book") require(GSON.fromJson(record.json, BookSource::class.java).bookSourceUrl == record.id)
        else require(GSON.fromJson(record.json, RssSource::class.java).sourceUrl == record.id)
    }
    suspend fun restore(prepared: Prepared, progress: (Int) -> Unit): Result = withContext(Dispatchers.IO) {
        val job = currentCoroutineContext()[Job]
        require(SourceRestorePackCodec.fileSha256(prepared.file) == prepared.zipSha256) { "還原包在確認後已變更" }
        val preflight = SourceRestorePackCodec.scan(prepared.file) { job?.ensureActive(); validateModel(it) }
        require(preflight == prepared.summary) { "還原包在確認後已變更" }
        val batch = ArrayList<SourceRestorePackCodec.Record>(100)
        var processed = 0; var restored = 0; var kept = 0
        fun flush() {
            if (batch.isEmpty()) return
            job?.ensureActive()
            var batchRestored = 0; var batchKept = 0
            appDb.runInTransaction {
                batch.forEach { record ->
                    job?.ensureActive()
                    if (record.kind == "book") {
                        val original = GSON.fromJson(record.json, BookSource::class.java)
                        val existing = appDb.bookSourceDao.getBookSource(record.id)
                        if (existing != null && !CloudReader.isRemote(existing)) { batchKept++; return@forEach }
                        existing?.let { original.bookSourceName = it.bookSourceName; original.bookSourceGroup = it.bookSourceGroup; original.customOrder = it.customOrder }
                        // Dedicated authenticated data restore: no rule execution, renumbering, or source filtering.
                        appDb.bookSourceDao.insert(original)
                    } else {
                        val original = GSON.fromJson(record.json, RssSource::class.java)
                        val existing = appDb.rssSourceDao.getByKey(record.id)
                        if (existing != null && !CloudReader.isRemote(existing)) { batchKept++; return@forEach }
                        existing?.let { original.sourceName = it.sourceName; original.sourceGroup = it.sourceGroup; original.customOrder = it.customOrder }
                        appDb.rssSourceDao.insert(original)
                    }
                    batchRestored++
                }
            }
            processed += batch.size; restored += batchRestored; kept += batchKept
            batch.clear(); progress(processed)
        }
        val checked = SourceRestorePackCodec.scan(prepared.file) { record ->
            job?.ensureActive(); batch.add(record); if (batch.size == 100) flush()
        }
        require(checked == prepared.summary) { "還原包在導入期間已變更" }
        flush(); job?.ensureActive()
        ContentProcessor.upReplaceRules()
        LocalSourceRestoreRefresh.refreshCurrentSources()
        Result(processed, restored, kept)
    }
}
