package io.legado.app.model.cloud

import com.google.gson.Gson
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest

/** Atomic URL-addressed cache. Legacy copies are readable fallbacks, never completeness evidence. */
internal class CloudContentStore(private val root: File) {
    data class Identity(val configuration: String, val sourceId: String, val bookUrl: String, val chapterUrl: String)
    internal data class Record(val identity: Identity, val body: String, val bodySha256: String,
                               val complete: Boolean, val contractVersion: String?, val localEdit: Boolean = false)
    private data class Index(val identity: Identity, val contractVersion: String, val recordLength: Long, val recordModified: Long)
    private val gson = Gson()

    companion object {
        const val CONTRACT = "reader-rules-paging-xpath-1"
        const val MAX_BODY_BYTES = 16 * 1024 * 1024
        fun sha256(text: String): String = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    private fun bookKey(identity: Identity) = sha256(listOf(identity.configuration, identity.sourceId,
        identity.bookUrl).joinToString("\u0000"))
    private fun key(identity: Identity) = bookKey(identity) + "-" + sha256(identity.chapterUrl)
    internal fun file(identity: Identity, complete: Boolean) = File(root, key(identity) + if (complete) ".complete.json" else ".legacy.json")
    // Native flat files predate cloud configuration and source aliases. Once their
    // index namespace is sealed, auth rotation or alias changes cannot re-label it.
    private fun seal(identity: Identity) = File(root, sha256("native-book\u0000" + identity.bookUrl) + ".toc-sealed")
    @Synchronized fun isSealed(identity: Identity) = seal(identity).isFile
    @Synchronized fun sealToc(identity: Identity) = atomic(seal(identity), "1")

    @Synchronized fun complete(identity: Identity, expectedVersion: String?): String? {
        if (expectedVersion != CONTRACT) return null
        val record = read(identity, true)?.takeIf { it.complete && it.contractVersion == expectedVersion } ?: return null
        ensureIndex(record)
        return record.body
    }
    /** UI hint only: never use this lightweight index as permission to read or skip verification. */
    @Synchronized fun completeChapterUrls(configuration: String, sourceId: String, bookUrl: String, expectedVersion: String?): Set<String> {
        if (expectedVersion != CONTRACT) return emptySet()
        val prefix = bookKey(Identity(configuration, sourceId, bookUrl, "")) + "-"
        return root.listFiles().orEmpty().asSequence().filter { it.name.startsWith(prefix) && it.name.endsWith(".complete-index.json") }
            .mapNotNull { input -> runCatching {
                if (input.length() !in 1..8192L) return@runCatching null
                val index = gson.fromJson(input.readText(), Index::class.java) ?: return@runCatching null
                val identity = index.identity
                val bodyFile = file(identity, true)
                if (identity.configuration != configuration || identity.sourceId != sourceId || identity.bookUrl != bookUrl ||
                    index.contractVersion != expectedVersion || !bodyFile.isFile || bodyFile.length() != index.recordLength ||
                    bodyFile.lastModified() != index.recordModified) null else identity.chapterUrl
            }.getOrNull() }.toSet()
    }
    private fun indexFile(identity: Identity) = File(root, key(identity) + ".complete-index.json")
    private fun ensureIndex(record: Record) {
        // The authoritative body+receipt is already atomic and verified. A missing UI index is harmless.
        runCatching {
            val bodyFile = file(record.identity, true)
            val index = Index(record.identity, record.contractVersion!!, bodyFile.length(), bodyFile.lastModified())
            val encoded = gson.toJson(index)
            val target = indexFile(record.identity)
            if (!target.isFile || target.length() > 8192 || target.readText() != encoded) atomic(target, encoded)
        }
    }
    @Synchronized fun edited(identity: Identity): String? = read(identity, false, edited = true)?.body
    @Synchronized fun fallback(identity: Identity): String? = edited(identity) ?: read(identity, true)?.body ?: read(identity, false)?.body
    private fun editFile(identity: Identity) = File(root, key(identity) + ".edited.json")
    private fun refreshFile(identity: Identity) = File(root, key(identity) + ".local-refresh")

    /** A requested native refresh must not reuse the preserved failure fallback as a cache hit. */
    @Synchronized fun locallyReadable(identity: Identity): String? =
        if (refreshFile(identity).isFile) null else fallback(identity)

    private fun finishLocalRefresh(identity: Identity) {
        val marker = refreshFile(identity)
        check(!marker.exists() || marker.delete()) { "無法完成本機正文快取更新" }
    }

    private fun read(identity: Identity, complete: Boolean, edited: Boolean = false): Record? = runCatching {
        val input = if (edited) editFile(identity) else file(identity, complete)
        if (!input.isFile || input.length() !in 1..(MAX_BODY_BYTES * 2L)) return null
        val record = gson.fromJson(input.readText(), Record::class.java) ?: return null
        if (record.identity != identity || record.body.isBlank() ||
            record.body.toByteArray(Charsets.UTF_8).size > MAX_BODY_BYTES ||
            record.bodySha256 != sha256(record.body) || record.complete != complete || record.localEdit != edited ||
            (complete && record.contractVersion.isNullOrBlank())) return null
        record
    }.getOrNull()

    /** No existing original is modified; failure cannot turn a partial body into a receipt. */
    @Synchronized fun preserve(identity: Identity, original: File, ignoreSeal: Boolean = false) {
        if (!ignoreSeal && isSealed(identity) || !original.isFile) return
        if (file(identity, false).exists()) {
            check(read(identity, false) != null) { "舊正文保全記錄無法核對，保留原目錄" }
            return
        }
        check(original.length() in 1..MAX_BODY_BYTES.toLong()) { "舊正文過大，保留原目錄" }
        val body = original.readText()
        if (body.isBlank()) return
        write(identity, body, false, null)
    }

    @Synchronized fun saveEdit(identity: Identity, body: String) {
        check(body.isNotBlank()) { "不能保存空白正文" }
        write(identity, body, false, null, edited = true)
        finishLocalRefresh(identity)
    }

    /** An explicit refresh retries the server while keeping the last readable body for failure. */
    @Synchronized fun invalidate(identity: Identity) {
        fallback(identity)?.let { write(identity, it, false, null) }
        atomic(refreshFile(identity), "1")
        for (target in listOf(file(identity, true), indexFile(identity), editFile(identity))) {
            check(!target.exists() || target.delete()) { "無法重新整理正文核驗記錄" }
        }
    }

    @Synchronized fun invalidateBook(configuration: String, sourceId: String, bookUrl: String) {
        val prefix = bookKey(Identity(configuration, sourceId, bookUrl, "")) + "-"
        val identities = root.listFiles().orEmpty().filter { it.name.startsWith(prefix) && it.name.endsWith(".json") }.mapNotNull { file ->
            runCatching { if (file.length() > MAX_BODY_BYTES * 2L) null else gson.fromJson(file.readText(), Record::class.java)?.identity }.getOrNull()
        }.filter { it.configuration == configuration && it.sourceId == sourceId && it.bookUrl == bookUrl }.distinct()
        identities.forEach { invalidate(it) }
    }

    @Synchronized fun clear() {
        // The seal is an identity guard, not readable cache. Keep it even if a later
        // native-cache deletion fails or the process stops between the two operations.
        root.listFiles().orEmpty().filter { it.isFile && !it.name.endsWith(".toc-sealed") }.forEach {
            check(it.delete()) { "無法清理雲端正文快取" }
        }
    }

    @Synchronized fun install(identity: Identity, body: String, complete: Boolean, contractVersion: String,
                              sourceId: String, bookUrl: String, chapterUrl: String, bodySha256: String) {
        check(complete && contractVersion == CONTRACT) { "雲端正文尚未完成完整核驗" }
        check(sourceId == identity.sourceId && bookUrl == identity.bookUrl && chapterUrl == identity.chapterUrl) {
            "雲端正文與目前書籍章節不符"
        }
        check(bodySha256.matches(Regex("[a-f0-9]{64}")) && bodySha256 == sha256(body)) { "雲端正文校驗不符" }
        write(identity, body, true, contractVersion)
        finishLocalRefresh(identity)
    }

    private fun write(identity: Identity, body: String, complete: Boolean, version: String?, edited: Boolean = false) {
        check(body.isNotBlank() && body.toByteArray(Charsets.UTF_8).size <= MAX_BODY_BYTES) { "正文為空或過大" }
        val encoded = gson.toJson(Record(identity, body, sha256(body), complete, version, edited))
        check(encoded.toByteArray(Charsets.UTF_8).size <= MAX_BODY_BYTES * 2) { "正文核驗記錄過大" }
        atomic(if (edited) editFile(identity) else file(identity, complete), encoded)
        if (complete) ensureIndex(Record(identity, body, sha256(body), true, version))
    }

    /** Body and receipt share one atomic file, so a crash never pairs a new receipt with old text. */
    private fun atomic(target: File, value: String) {
        if (!root.isDirectory && !root.mkdirs()) throw IOException("無法建立正文保全目錄")
        val temporary = File.createTempFile("chapter-", ".tmp", root)
        try {
            FileOutputStream(temporary).use { output -> output.write(value.toByteArray(Charsets.UTF_8)); output.fd.sync() }
            if (!temporary.renameTo(target)) throw IOException("無法原子保存正文與核驗記錄")
        } finally { temporary.delete() }
    }
}
