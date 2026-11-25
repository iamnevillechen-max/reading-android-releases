package io.legado.app.cloud

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.Gson
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.RssSource
import io.legado.app.data.entities.SearchBook
import io.legado.app.model.cloud.CloudMigrationPolicy
import io.legado.app.model.local.SourceRestorePack
import io.legado.app.model.local.SourceRestorePackCodec
import io.legado.app.utils.GSON
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Restore service and real Room, only unique owned IDs; never clears the database or contacts a site. */
@RunWith(AndroidJUnit4::class)
class SourceRestorePackIntegrationTest {
    private class Owned : AutoCloseable {
        private val lineEncoder = Gson() // App GSON pretty-prints; JSONL wrappers must occupy one physical line.
        val prefix = "https://owned-pack.invalid/" + UUID.randomUUID()
        val books = arrayListOf<String>(); val feeds = arrayListOf<String>(); val shelf = arrayListOf<String>(); val searches = arrayListOf<SearchBook>()
        val files = arrayListOf<File>()
        fun book(label: String, enabled: Boolean = true) = BookSource(bookSourceUrl = "$prefix/$label", bookSourceName = "Owned $label", enabled = enabled,
            searchUrl = "/owned-search").also { books.add(it.bookSourceUrl) }
        fun feed(label: String, enabled: Boolean = true) = RssSource(sourceUrl = "$prefix/$label", sourceName = "Owned $label", enabled = enabled,
            ruleArticles = "item", ruleTitle = "title@text", ruleLink = "link@text").also { feeds.add(it.sourceUrl) }
        fun prepared(records: List<SourceRestorePackCodec.Record>, badHash: Boolean = false): SourceRestorePack.Prepared {
            val file = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "owned-restore-${UUID.randomUUID()}.zip")
            files.add(file); write(file, records, badHash)
            return SourceRestorePack.Prepared(file, SourceRestorePackCodec.scan(file))
        }
        fun write(file: File, records: List<SourceRestorePackCodec.Record>, badHash: Boolean = false) {
            val bytes = records.joinToString("\n", postfix = "\n") { record ->
                lineEncoder.toJson(record).also { require('\n' !in it && '\r' !in it) }
            }.toByteArray(Charsets.UTF_8)
            val manifest = mapOf("format" to "reader-local-source-restore-1", "total" to records.size, "books" to records.count { it.kind == "book" },
                "rss" to records.count { it.kind == "rss" }, "archiveBytes" to bytes.size,
                "archiveSha256" to if (badHash) "0".repeat(64) else SourceRestorePackCodec.sha256(bytes))
            ZipOutputStream(file.outputStream()).use {
                it.putNextEntry(ZipEntry("manifest.json")); it.write(lineEncoder.toJson(manifest).toByteArray()); it.closeEntry()
                it.putNextEntry(ZipEntry("sources.jsonl")); it.write(bytes); it.closeEntry()
            }
        }
        override fun close() {
            searches.forEach { appDb.searchBookDao.delete(it) }
            shelf.forEach { url -> appDb.bookDao.getBook(url)?.let { appDb.bookDao.delete(it) } }
            books.forEach { appDb.bookSourceDao.delete(it) }; feeds.forEach { appDb.rssSourceDao.delete(it) }
            files.forEach { it.delete() }
        }
    }
    private fun record(source: BookSource) = record("book", source.bookSourceUrl, source)
    private fun record(source: RssSource) = record("rss", source.sourceUrl, source)
    private fun record(kind: String, id: String, source: Any): SourceRestorePackCodec.Record {
        val raw = GSON.toJson(source)
        return SourceRestorePackCodec.Record(kind, id, raw, SourceRestorePackCodec.sha256(raw.toByteArray(Charsets.UTF_8)))
    }

    @Test(timeout = 30_000) fun mixedRestoreKeepsFullLocalRulesAndReadingReferencesWhileRestoringOriginalEnabledFlags() = runBlocking {
        Owned().use { owned ->
            val original = owned.book("thin", enabled = true)
            val thin = original.copy(bookSourceName = "Owned custom label", bookSourceGroup = "Owned group", customOrder = 87,
                enabled = false, searchUrl = CloudMigrationPolicy.STUB)
            val incomingFull = owned.book("full", enabled = false)
            val localFull = incomingFull.copy(bookSourceName = "Owned newer local rules", lastUpdateTime = Long.MAX_VALUE, searchUrl = "/owned-newer", enabled = true)
            val missing = owned.book("missing", enabled = false)
            val feed = owned.feed("thin-feed", enabled = true)
            val thinFeed = feed.copy(sourceName = "Owned custom feed", sourceGroup = "Owned feeds", customOrder = 88, enabled = false, ruleArticles = CloudMigrationPolicy.STUB)
            val missingFeed = owned.feed("missing-feed", enabled = false)
            appDb.bookSourceDao.insert(thin, localFull); appDb.rssSourceDao.insert(thinFeed)
            val candidate = SearchBook(bookUrl = "${owned.prefix}/candidate", origin = original.bookSourceUrl, name = "Owned candidate")
            appDb.searchBookDao.insert(candidate); owned.searches.add(candidate)
            val book = Book(bookUrl = "${owned.prefix}/reading", origin = original.bookSourceUrl, name = "Owned reading book", author = "Owned author",
                durChapterIndex = 17, durChapterPos = 4321, durChapterTime = 1234)
            appDb.bookDao.insertRaw(book); owned.shelf.add(book.bookUrl)
            val result = SourceRestorePack.restore(owned.prepared(listOf(record(original), record(incomingFull), record(missing), record(feed), record(missingFeed)))) {}
            assertEquals(SourceRestorePack.Result(5, 4, 1), result)
            val restored = requireNotNull(appDb.bookSourceDao.getBookSource(original.bookSourceUrl))
            assertEquals(original.searchUrl, restored.searchUrl); assertTrue(restored.enabled)
            assertEquals(thin.bookSourceName, restored.bookSourceName); assertEquals(thin.bookSourceGroup, restored.bookSourceGroup); assertEquals(thin.customOrder, restored.customOrder)
            assertEquals(GSON.toJson(localFull), GSON.toJson(appDb.bookSourceDao.getBookSource(localFull.bookSourceUrl)))
            assertFalse(requireNotNull(appDb.bookSourceDao.getBookSource(missing.bookSourceUrl)).enabled)
            val restoredFeed = requireNotNull(appDb.rssSourceDao.getByKey(feed.sourceUrl))
            assertTrue(restoredFeed.enabled); assertEquals(thinFeed.sourceName, restoredFeed.sourceName); assertEquals(88, restoredFeed.customOrder)
            assertFalse(requireNotNull(appDb.rssSourceDao.getByKey(missingFeed.sourceUrl)).enabled)
            assertEquals(GSON.toJson(candidate), GSON.toJson(appDb.searchBookDao.getSearchBook(candidate.bookUrl)))
            assertEquals(GSON.toJson(book), GSON.toJson(appDb.bookDao.getBook(book.bookUrl)))
        }
    }

    @Test(timeout = 30_000) fun changedOrInvalidPreparedPackageWritesNoRowsEvenPastTheFirstBatchBoundary() = runBlocking {
        Owned().use { owned ->
            val records = (0..100).map { record(owned.book("invalid-$it")) }
            val prepared = owned.prepared(records)
            owned.write(prepared.file, records, badHash = true)
            assertTrue(runCatching { SourceRestorePack.restore(prepared) {} }.isFailure)
            // An invalid package with a fresh file hash still fails the full preflight, before its first 100 rows.
            val forged = SourceRestorePack.Prepared(prepared.file, prepared.summary)
            assertTrue(runCatching { SourceRestorePack.restore(forged) {} }.isFailure)
            owned.books.forEach { assertNull(appDb.bookSourceDao.getBookSource(it)) }
        }
    }

    @Test(timeout = 30_000) fun cancellationAtABatchBoundaryKeepsTheCommittedHundredAndDoesNotStartTheNextBatch() = runBlocking {
        Owned().use { owned ->
            val sources = (0..101).map { owned.book("cancel-$it") }
            val prepared = owned.prepared(sources.map(::record))
            lateinit var operation: Job
            operation = launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
                SourceRestorePack.restore(prepared) { processed -> if (processed == 100) operation.cancel() }
            }
            operation.start(); operation.join()
            assertTrue(operation.isCancelled)
            sources.take(100).forEach { assertNotNull(appDb.bookSourceDao.getBookSource(it.bookSourceUrl)) }
            sources.drop(100).forEach { assertNull(appDb.bookSourceDao.getBookSource(it.bookSourceUrl)) }
        }
    }
}
