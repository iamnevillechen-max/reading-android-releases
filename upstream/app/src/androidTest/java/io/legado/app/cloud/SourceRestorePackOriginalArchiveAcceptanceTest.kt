package io.legado.app.cloud

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.RssSource
import io.legado.app.model.cloud.CloudMigrationPolicy
import io.legado.app.model.local.SourceRestorePack
import io.legado.app.model.local.SourceRestorePackCodec
import io.legado.app.utils.GSON
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking
import okio.ByteString.Companion.decodeBase64
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Explicit opt-in for the private disposable AVD. Imports data only; never executes source rules. */
@RunWith(AndroidJUnit4::class)
class SourceRestorePackOriginalArchiveAcceptanceTest {
    companion object {
        private const val TOTAL = 21_149
        private const val BOOKS = 20_706
        private const val RSS = 443
        private const val ORIGINAL_SHA = "b4bda8b4fde7a77e56d584097e96d8b86183396e9c9c6ef7f3f3c1ca4482d41f"
    }

    private fun readPrivateKey(file: File): ByteArray {
        require(file.isFile && file.length() in 1..128) { "Private acceptance key file is missing or invalid" }
        val bytes = file.readBytes()
        try {
            if (bytes.size == 32) return bytes.copyOf()
            val encoded = bytes.toString(Charsets.US_ASCII).trim()
            require(encoded.matches(Regex("[A-Za-z0-9_-]{43}"))) { "Private acceptance key format is invalid" }
            val key = encoded.decodeBase64()?.toByteArray()
            require(key != null && key.size == 32) { "Private acceptance key format is invalid" }
            return key
        } finally { bytes.fill(0) }
    }

    private fun assertLocalRows(kind: String, ids: List<String>) {
        if (ids.isEmpty()) return
        val table = if (kind == "book") "book_sources" else "rssSources"
        val identity = if (kind == "book") "bookSourceUrl" else "sourceUrl"
        val rule = if (kind == "book") "searchUrl" else "ruleArticles"
        val marks = ids.joinToString(",") { "?" }
        val found = hashSetOf<String>()
        appDb.openHelper.readableDatabase.query("SELECT $identity, $rule FROM $table WHERE $identity IN ($marks)", ids.toTypedArray()).use { cursor ->
            while (cursor.moveToNext()) {
                found.add(cursor.getString(0))
                assertTrue("Restored local rows must not remain thin proxies", cursor.isNull(1) || cursor.getString(1) != CloudMigrationPolicy.STUB)
            }
        }
        assertTrue("Every original identity must exist locally after the complete import", found == ids.toSet())
    }

    @Test(timeout = 900_000) fun privateOriginalArchiveDecryptsValidatesAndRestoresEverySourceLocally() = runBlocking {
        assumeTrue("Requires explicit private-original-archive acceptance opt-in",
            InstrumentationRegistry.getArguments().getString("readerOriginalArchive") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("io.multiread.reader.debug", context.packageName)
        val encrypted = File(context.filesDir, "source-restore-acceptance.readerpack")
        val keyFile = File(context.filesDir, "source-restore-acceptance.key")
        require(encrypted.isFile && encrypted.length() > 64) { "Private original acceptance package is missing" }
        val key = readPrivateKey(keyFile)
        val zip = File(context.cacheDir, "owned-original-acceptance-${UUID.randomUUID()}.zip")
        val job = currentCoroutineContext()[Job]
        try {
            val declared = encrypted.inputStream().buffered().use { input ->
                SourceRestorePackCodec.decrypt(input, key, zip) { job?.ensureActive() }
            }
            assertEquals(TOTAL, declared)
            var parsedBooks = 0; var parsedRss = 0
            val summary = SourceRestorePackCodec.scan(zip) { record ->
                job?.ensureActive()
                if (record.kind == "book") {
                    val source = GSON.fromJson(record.json, BookSource::class.java)
                    assertTrue("Original book identity must survive native model decoding", source.bookSourceUrl == record.id)
                    assertTrue("Original archive must contain native rules rather than thin proxies", source.searchUrl != CloudMigrationPolicy.STUB)
                    parsedBooks++
                } else {
                    val source = GSON.fromJson(record.json, RssSource::class.java)
                    assertTrue("Original RSS identity must survive native model decoding", source.sourceUrl == record.id)
                    assertTrue("Original archive must contain native rules rather than thin proxies", source.ruleArticles != CloudMigrationPolicy.STUB)
                    parsedRss++
                }
            }
            assertEquals(TOTAL, summary.total); assertEquals(BOOKS, summary.books); assertEquals(RSS, summary.rss)
            assertEquals(BOOKS, parsedBooks); assertEquals(RSS, parsedRss)
            assertTrue("Exact original archive checksum must match the retained source archive", summary.payloadSha256 == ORIGINAL_SHA)
            check(zip.setReadOnly()) { "Unable to seal private acceptance staging file" }
            val result = SourceRestorePack.restore(SourceRestorePack.Prepared(zip, summary)) { processed ->
                assertTrue("Import progress must remain within the original count", processed in 0..TOTAL)
            }
            assertEquals(TOTAL, result.processed)
            assertEquals(TOTAL, result.restored + result.keptLocal)
            assertTrue(appDb.bookSourceDao.allCount() >= BOOKS)
            assertTrue(appDb.rssSourceDao.size >= RSS)
            val books = arrayListOf<String>(); val feeds = arrayListOf<String>()
            fun flush() {
                assertLocalRows("book", books); assertLocalRows("rss", feeds)
                books.clear(); feeds.clear()
            }
            val checked = SourceRestorePackCodec.scan(zip) { record ->
                job?.ensureActive()
                if (record.kind == "book") books.add(record.id) else feeds.add(record.id)
                if (books.size + feeds.size == 100) flush()
            }
            flush()
            assertEquals(summary, checked)
            // Imported records deliberately remain in this disposable AVD. Existing full rules were retained.
        } finally {
            key.fill(0)
            zip.delete()
        }
    }
}
