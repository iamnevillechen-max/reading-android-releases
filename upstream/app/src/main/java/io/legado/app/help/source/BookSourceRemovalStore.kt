package io.legado.app.help.source

import android.content.Context
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookSource
import io.legado.app.help.ReaderDiagnostics
import io.legado.app.utils.GSON
import splitties.init.appCtx
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.Callable

/** The archive format is compatible with 1.2.8. Reviews retain only recent evidence and a rule hash. */
internal object BookSourceRemovalStore {
    private const val MAX_BYTES = 1_048_576
    private const val REVIEW_AGE = 7 * 24 * 60 * 60 * 1000L
    private const val MAX_REVIEWS = 128
    private val reviewPrefs by lazy { appCtx.getSharedPreferences("book-source-removal-reviews", Context.MODE_PRIVATE) }
    private val restoredPrefs by lazy { appCtx.getSharedPreferences("book-source-owner-restores", Context.MODE_PRIVATE) }
    private val directory get() = File(appCtx.filesDir, "removed-book-sources")

    data class Record(val schema: Int = 1, val sourceId: String, val sourceSha256: String,
        val chapterUrl: String, val entryUrl: String, val chapterStatus: Int, val entryStatus: Int,
        val confirmedAt: Long, val sourceJson: String? = null)

    data class Choice(val name: String, val record: Record)

    fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }

    fun snapshot(source: BookSource): String? = GSON.toJson(source)
        .takeIf { it.toByteArray(Charsets.UTF_8).size <= MAX_BYTES / 4 }

    private fun archiveFile(id: String) = File(directory, "${digest(id)}.json")
    fun isArchived(id: String): Boolean = archiveFile(id).isFile

    private fun decode(json: String?): Record? = runCatching {
        GSON.fromJson(json, Record::class.java)?.takeIf {
            it.schema == 1 && it.sourceId.isNotBlank() && it.sourceSha256.length == 64 &&
                it.chapterStatus in setOf(404, 410) && it.entryStatus in setOf(404, 410)
        }
    }.getOrNull()

    private fun isRecent(record: Record): Boolean =
        System.currentTimeMillis() - record.confirmedAt in 0..REVIEW_AGE

    @Synchronized
    fun observeMissing(record: Record, stillCurrent: () -> Boolean) {
        if (!stillCurrent()) return
        if (record.chapterStatus == 410 && record.entryStatus == 410 &&
            restoredPrefs.getString(record.sourceId, null) != record.sourceSha256) {
            archiveAndRemove(record, stillCurrent)
            return
        }
        val current = appDb.bookSourceDao.getBookSource(record.sourceId) ?: return
        if (digest(GSON.toJson(current)) != record.sourceSha256) return
        val existing = reviewPrefs.all
        val editor = reviewPrefs.edit()
        if (record.sourceId !in existing && existing.size >= MAX_REVIEWS) {
            existing.minByOrNull { decode(it.value as? String)?.confirmedAt ?: 0L }?.let { editor.remove(it.key) }
        }
        editor.putString(record.sourceId, GSON.toJson(record)).apply()
    }

    fun hasReview(id: String): Boolean = reviewPrefs.contains(id)

    @Synchronized
    fun forgetReview(id: String) {
        if (hasReview(id)) reviewPrefs.edit().remove(id).apply()
    }

    @Synchronized
    fun reviews(): List<Choice> = reviewPrefs.all.mapNotNull { (id, json) ->
        val record = decode(json as? String)
        val source = appDb.bookSourceDao.getBookSource(id)
        if (record != null && record.sourceId == id && isRecent(record) && source != null &&
            digest(GSON.toJson(source)) == record.sourceSha256) Choice(source.bookSourceName, record)
        else { forgetReview(id); null }
    }.sortedByDescending { it.record.confirmedAt }

    @Synchronized
    fun confirmedRemoval(choice: Choice): Boolean {
        val record = choice.record
        if (!isRecent(record) || decode(reviewPrefs.getString(record.sourceId, null)) != record) return false
        return archiveAndRemove(record) { true }
    }

    /** Never delete the only rule copy. A crash after the rename can leave a harmless extra archive. */
    private fun archiveAndRemove(record: Record, stillCurrent: () -> Boolean): Boolean {
        val removed = appDb.runInTransaction(Callable {
            val source = appDb.bookSourceDao.getBookSource(record.sourceId) ?: return@Callable false
            val json = snapshot(source) ?: return@Callable false
            if (!stillCurrent() || digest(json) != record.sourceSha256) return@Callable false
            val bytes = GSON.toJson(record.copy(sourceJson = json)).toByteArray(Charsets.UTF_8)
            if (bytes.size > MAX_BYTES) return@Callable false
            val dir = directory
            if (!dir.isDirectory && !dir.mkdirs() && !dir.isDirectory) return@Callable false
            val pending = File.createTempFile("source-", ".part", dir)
            try {
                FileOutputStream(pending).use { it.write(bytes); it.fd.sync() }
                if (!stillCurrent()) return@Callable false
                check(pending.renameTo(archiveFile(record.sourceId))) { "Cannot save removed source snapshot" }
                appDb.bookSourceDao.delete(record.sourceId)
                true
            } finally {
                pending.delete()
            }
        })
        if (removed) {
            forgetReview(record.sourceId)
            restoredPrefs.edit().remove(record.sourceId).apply()
            ReaderDiagnostics.record("source_archived")
        }
        return removed
    }

    private fun archived(file: File): Pair<Record, BookSource>? = runCatching {
        if (!file.isFile || file.length() !in 1..MAX_BYTES.toLong()) return null
        val record = decode(file.readText()) ?: return null
        val json = record.sourceJson ?: return null
        if (file != archiveFile(record.sourceId) || digest(json) != record.sourceSha256) return null
        val source = GSON.fromJson(json, BookSource::class.java)
        if (source.bookSourceUrl == record.sourceId) record.copy(sourceJson = null) to source else null
    }.getOrNull()

    @Synchronized
    fun removed(): List<Choice> = directory.listFiles().orEmpty().asSequence()
        .filter { it.extension == "json" }.mapNotNull(::archived).mapNotNull { (record, source) ->
            if (appDb.bookSourceDao.getBookSource(record.sourceId) == null) Choice(source.bookSourceName, record) else null
        }.sortedByDescending { it.record.confirmedAt }.toList()

    @Synchronized
    fun restore(choice: Choice): Boolean {
        val file = archiveFile(choice.record.sourceId)
        val (record, source) = archived(file) ?: return false
        if (record != choice.record) return false
        val restored = appDb.runInTransaction(Callable {
            if (appDb.bookSourceDao.getBookSource(record.sourceId) != null) return@Callable false
            appDb.bookSourceDao.insertNewLocalSource(source)
            true
        })
        if (restored) {
            forgetReview(record.sourceId)
            restoredPrefs.edit().putString(record.sourceId, record.sourceSha256).apply()
            file.delete()
            ReaderDiagnostics.record("source_restored")
        }
        return restored
    }
}
