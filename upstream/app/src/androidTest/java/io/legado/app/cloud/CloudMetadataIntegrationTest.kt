package io.legado.app.cloud

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.legado.app.data.appDb
import io.legado.app.data.entities.ReadRecord
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.getFolderNameNoCache
import io.legado.app.model.cloud.CloudReader
import io.legado.app.model.webBook.WebBook
import io.legado.app.utils.GSON
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Exact same-source/same-book recovery; no guessed titles or history migration. */
@RunWith(AndroidJUnit4::class)
class CloudMetadataIntegrationTest {
    private val brokenTitle = "\" width=\"120\" height=\"168\" alt=\"Owned self-written title\"/>"
    private val brokenAuthor = "<span>Owned self-written author</span>"

    @Test(timeout = 30_000)
    fun cloudListsRejectMarkupInsteadOfPersistingOrAnnouncingItAsATitleOrAuthor() = runBlocking {
        CloudIntegrationFixture().use { fixture ->
            val source = fixture.sources.first()
            for ((name, author) in listOf(brokenTitle to fixture.author, fixture.title to brokenAuthor)) {
                fixture.gateway.listNameOverride = name; fixture.gateway.listAuthorOverride = author
                assertTrue(runCatching { CloudReader.search(source, "Owned", 1) }.exceptionOrNull() is IllegalArgumentException)
                assertTrue(runCatching { CloudReader.explore(source, "reader-cloud://category/all", 1) }.exceptionOrNull() is IllegalArgumentException)
                assertNull(appDb.searchBookDao.getSearchBook(fixture.bookUrl(0)))
            }
            fixture.gateway.listNameOverride = null; fixture.gateway.listAuthorOverride = null
            assertEquals(fixture.title, CloudReader.search(source, "Owned", 1).single().name)
        }
    }

    @Test(timeout = 30_000)
    fun matchingFreshDetailRepairsMalformedIdentityAndKeepsRowsProgressHistoryAndBothCaches() = runBlocking {
        CloudIntegrationFixture().use { fixture ->
            val source = fixture.sources.first()
            val book = fixture.readingBook().apply { name = brokenTitle; author = brokenAuthor; group = 64 }
            val before = book.copy()
            val history = ReadRecord("owned-cloud-metadata", book.name, 87_654, 1_700_000_000_555)
            val chapter = fixture.chapter(book)
            val repairedDirectory = File(BookHelp.cachePath, before.copy(name = fixture.title).getFolderNameNoCache())
            check(repairedDirectory.mkdirs())
            val existing = File(repairedDirectory, "99999-0123456789abcdef.nb").apply { writeText("Owned existing target cache stays unchanged") }
            appDb.bookDao.insert(book); appDb.bookChapterDao.insert(chapter); appDb.readRecordDao.insert(history)
            try {
                BookHelp.saveContent(source, book, chapter, fixture.complete)
                // Cache setup legitimately fills chapter.wordCount; snapshot after
                // setup so this assertion measures only the metadata repair.
                val persistedChapterBefore = GSON.toJson(appDb.bookChapterDao.getChapter(before.bookUrl, chapter.index))
                val repaired = WebBook.getBookInfoAwait(source, book, canReName = false)
                assertEquals(fixture.title, repaired.name); assertEquals(fixture.author, repaired.author)
                assertEquals(before.bookUrl, repaired.bookUrl); assertEquals(before.origin, repaired.origin)
                assertEquals(before.durChapterIndex, repaired.durChapterIndex); assertEquals(before.durChapterPos, repaired.durChapterPos)
                assertEquals(before.durChapterTitle, repaired.durChapterTitle); assertEquals(before.durChapterTime, repaired.durChapterTime)
                assertEquals(before.readConfig, repaired.readConfig); assertEquals(before.group, repaired.group)
                appDb.bookDao.update(repaired)
                val reloaded = requireNotNull(appDb.bookDao.getBook(before.bookUrl))
                assertEquals(fixture.title, reloaded.name)
                assertEquals(listOf(history), appDb.readRecordDao.all)
                assertEquals(persistedChapterBefore, GSON.toJson(appDb.bookChapterDao.getChapter(before.bookUrl, chapter.index)))
                assertEquals(fixture.complete, BookHelp.getContent(before, chapter, allowMultiSource = false))
                assertEquals(fixture.complete, BookHelp.getContent(reloaded, chapter, allowMultiSource = false))
                assertEquals("Owned existing target cache stays unchanged", existing.readText())
                fixture.assertPrivateStateNeverSent()
            } finally {
                appDb.readRecordDao.delete(history); appDb.bookChapterDao.delByBook(before.bookUrl); appDb.bookDao.delete(before)
                for (candidate in listOf(before, before.copy(name = fixture.title, author = fixture.author))) {
                    val directory = File(BookHelp.cachePath, candidate.getFolderNameNoCache())
                    check(directory.canonicalFile.parentFile == File(BookHelp.cachePath).canonicalFile)
                    directory.deleteRecursively()
                }
            }
        }
    }

    @Test(timeout = 30_000)
    fun readingDoesNotRenameNormalIdentityAndBadOrWrongBookRepliesCannotRepairIt() = runBlocking {
        CloudIntegrationFixture().use { fixture ->
            val source = fixture.sources.first()
            val normal = fixture.readingBook().apply { name = "Owned custom title"; author = "Owned established author" }
            WebBook.getBookInfoAwait(source, normal, canReName = false)
            assertEquals("Owned custom title", normal.name); assertEquals("Owned established author", normal.author)
            for (reply in listOf(
                mapOf("name" to "", "author" to ""),
                mapOf("name" to brokenTitle, "author" to brokenAuthor),
                mapOf("name" to fixture.title, "author" to fixture.author, "bookUrl" to fixture.bookUrl(1)),
                mapOf("name" to fixture.title, "author" to fixture.author, "origin" to fixture.sources[1].bookSourceUrl)
            )) {
                val damaged = fixture.readingBook().apply { name = brokenTitle; author = brokenAuthor }
                fixture.gateway.infoMetadata = reply
                WebBook.getBookInfoAwait(source, damaged, canReName = false)
                assertEquals(brokenTitle, damaged.name); assertEquals(brokenAuthor, damaged.author)
                assertEquals(73, damaged.durChapterPos)
            }
            fixture.gateway.infoMetadata = emptyMap()
            val missingAuthor = fixture.readingBook().apply { author = "" }
            WebBook.getBookInfoAwait(source, missingAuthor, canReName = false)
            assertEquals(fixture.author, missingAuthor.author)
        }
    }

    @Test(timeout = 30_000)
    fun aRepairNeverMergesAnotherShelfRowWithTheSameCorrectedNameAndAuthor() = runBlocking {
        CloudIntegrationFixture().use { fixture ->
            val damaged = fixture.readingBook().apply { name = brokenTitle; author = brokenAuthor }
            val other = fixture.readingBook().copy(bookUrl = fixture.bookUrl(1))
            appDb.bookDao.insert(damaged, other)
            try {
                WebBook.getBookInfoAwait(fixture.sources.first(), damaged, canReName = false)
                assertEquals(brokenTitle, damaged.name); assertEquals(brokenAuthor, damaged.author)
                assertEquals(2, appDb.bookDao.allBookCount)
                assertEquals(GSON.toJson(other), GSON.toJson(appDb.bookDao.getBook(other.bookUrl)))
            } finally { appDb.bookDao.delete(damaged, other) }
        }
    }
}
