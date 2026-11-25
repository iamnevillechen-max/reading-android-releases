package io.legado.app.multisource

import android.content.Context
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import fi.iki.elonen.NanoHTTPD
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.SearchBook
import io.legado.app.data.entities.rule.ContentRule
import io.legado.app.help.book.BookHelp
import io.legado.app.help.source.BookSourceAutoRemoval
import io.legado.app.help.source.BookSourceRemovalStore
import io.legado.app.model.ReadBook
import io.legado.app.model.multisource.MultiSourceManager
import io.legado.app.model.webBook.WebBook
import kotlinx.coroutines.Dispatchers.Main
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class BookSourceAutoRemovalTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test fun onlyIndependentOriginal410RemovesSourceAndKeepsReaderData() = runBlocking {
        check(Build.HARDWARE in setOf("ranchu", "goldfish") && ReadBook.book == null &&
            appDb.bookDao.allBookCount == 0 && appDb.bookSourceDao.allCount() == 0)
        val probes = AtomicInteger()
        val previousEnabled = MultiSourceManager.enabled
        val previousIndex = ReadBook.durChapterIndex
        val server = object : NanoHTTPD("127.0.0.1", 0) {
            override fun serve(session: IHTTPSession): Response {
                if (session.uri.startsWith("/entry/")) probes.incrementAndGet()
                val status = if (session.uri == "/entry/live") Response.Status.OK else Response.Status.GONE
                return newFixedLengthResponse(status, "text/plain", "Owned source retirement fixture")
            }
        }
        server.start()
        try {
            MultiSourceManager.enabled = false
            for (remove in listOf(false, true)) {
                val base = "http://127.0.0.1:${server.listeningPort}"
                val id = "$base/entry/${if (remove) "gone" else "live"}"
                val source = BookSource(bookSourceUrl = id, bookSourceName = "Owned retirement",
                    ruleContent = ContentRule(content = "body@text"))
                val book = Book(bookUrl = "$base/book/$remove", origin = id, name = "自寫來源移除$remove",
                    author = "測試作者", durChapterIndex = 0, durChapterPos = 31, totalChapterNum = 1)
                val chapter = BookChapter(url = "$base/chapter/$remove", bookUrl = book.bookUrl,
                    baseUrl = base, title = "自寫章節", index = 0)
                val search = SearchBook(bookUrl = "$base/search/$remove", origin = id, name = book.name)
                val archive = archiveFor(id)
                check(!archive.exists())
                val recordCount = appDb.readRecordDao.all.size
                try {
                    appDb.bookSourceDao.insert(source)
                    appDb.bookDao.insert(book)
                    appDb.bookChapterDao.insert(chapter)
                    appDb.searchBookDao.insert(search)
                    BookHelp.saveText(book, chapter, "這是保留在本機的自寫原文。")
                    withContext(Main) {
                        ReadBook.book = book
                        ReadBook.bookSource = source
                        ReadBook.durChapterIndex = chapter.index
                    }
                    val originalJson = BookSourceAutoRemoval.snapshot(source)
                    try {
                        WebBook.getContentAwait(source, book, chapter)
                        fail("The original 410 should carry its proof")
                    } catch (_: BookSourceAutoRemoval.Gone) { }
                    assertEquals(!remove, appDb.bookSourceDao.getBookSource(id) != null)
                    assertEquals(!remove, appDb.searchBookDao.getSearchBook(search.bookUrl) != null)
                    assertEquals(remove, archive.isFile)
                    assertEquals(recordCount, appDb.readRecordDao.all.size)
                    val retained = appDb.bookDao.getBook(book.bookUrl)!!
                    assertEquals(31, retained.durChapterPos)
                    assertEquals(0, retained.durChapterIndex)
                    assertEquals(chapter.url, appDb.bookChapterDao.getChapter(book.bookUrl, 0)?.url)
                    assertEquals("這是保留在本機的自寫原文。", BookHelp.getContent(book, chapter, false))
                    if (remove) {
                        val saved = com.google.gson.JsonParser.parseString(archive.readText()).asJsonObject
                        assertEquals(originalJson, saved["sourceJson"].asString)
                        assertNull(BookSourceAutoRemoval.currentSource(source))
                        // An explicit user import restores this exact ID through the existing safe DAO.
                        appDb.bookSourceDao.insert(source)
                        assertNotNull(BookSourceAutoRemoval.currentSource(source))
                    }
                } finally {
                    withContext(Main) { ReadBook.book = null; ReadBook.bookSource = null }
                    BookHelp.delContent(book, chapter)
                    appDb.bookDao.delete(book)
                    appDb.bookSourceDao.delete(id)
                    archive.delete()
                }
            }
            assertEquals(2, probes.get())
        } finally {
            MultiSourceManager.enabled = previousEnabled
            withContext(Main) { ReadBook.durChapterIndex = previousIndex }
            server.stop()
        }
    }

    private fun archiveFor(id: String): File {
        val hash = MessageDigest.getInstance("SHA-256").digest(id.toByteArray()).joinToString("") {
            "%02x".format(it.toInt() and 255)
        }
        return File(context.filesDir, "removed-book-sources/$hash.json")
    }

    @Test fun missingEntryAndChapterOfferReversibleRemovalAndAHealthyReadClearsTheSuggestion() = runBlocking {
        check(Build.HARDWARE in setOf("ranchu", "goldfish") && ReadBook.book == null &&
            appDb.bookDao.allBookCount == 0 && appDb.bookSourceDao.allCount() == 0)
        val liveChapter = java.util.concurrent.atomic.AtomicBoolean(false)
        val permanentlyGone = java.util.concurrent.atomic.AtomicBoolean(false)
        val server = object : NanoHTTPD("127.0.0.1", 0) {
            override fun serve(session: IHTTPSession): Response {
                if (session.uri == "/chapter" && liveChapter.get()) {
                    return newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", "<p>门外的脚步声渐渐远去。</p>")
                }
                val status = if (permanentlyGone.get()) Response.Status.GONE else Response.Status.NOT_FOUND
                return newFixedLengthResponse(status, "text/plain", "Missing")
            }
        }
        server.start()
        val base = "http://127.0.0.1:${server.listeningPort}"
        val id = "$base/entry"
        val source = BookSource(bookSourceUrl = id, bookSourceName = "自写书源",
            searchUrl = "$base/search?key={{key}}", ruleContent = ContentRule(content = "body@text"))
        val book = Book(bookUrl = "$base/book", origin = id, name = "自写失效复核",
            author = "测试作者", durChapterPos = 12, totalChapterNum = 1)
        val chapter = BookChapter(url = "$base/chapter", bookUrl = book.bookUrl, baseUrl = base, title = "第一章 归来")
        val oldEnabled = MultiSourceManager.enabled
        val oldIndex = ReadBook.durChapterIndex
        val oldSource = ReadBook.bookSource
        suspend fun missingRead(): BookSourceRemovalStore.Choice {
            try {
                WebBook.getContentAwait(source, book, chapter)
                fail("Foreground chapter should be missing")
            } catch (e: BookSourceAutoRemoval.Gone) { assertEquals(404, e.status) }
            assertNotNull(appDb.bookSourceDao.getBookSource(id))
            return BookSourceRemovalStore.reviews().single { it.record.sourceId == id }
        }
        try {
            MultiSourceManager.enabled = false
            appDb.bookSourceDao.insert(source)
            appDb.bookDao.insert(book)
            appDb.bookChapterDao.insert(chapter)
            withContext(Main) { ReadBook.book = book; ReadBook.bookSource = source; ReadBook.durChapterIndex = 0 }
            val initial = missingRead()
            assertEquals(404, initial.record.entryStatus)
            liveChapter.set(true)
            val readable = WebBook.getContentAwait(source, book, chapter)
            assertTrue(readable.contains("脚步声"))
            assertFalse(BookSourceRemovalStore.confirmedRemoval(initial))

            liveChapter.set(false)
            source.bookSourceName = "修正前"
            appDb.bookSourceDao.update(source)
            val previous = missingRead()
            source.bookSourceName = "修正后"
            appDb.bookSourceDao.update(source)
            assertFalse("A stale dialog cannot remove an edited rule", BookSourceRemovalStore.confirmedRemoval(previous))
            val current = missingRead()
            assertTrue(BookSourceRemovalStore.confirmedRemoval(current))
            assertNull(appDb.bookSourceDao.getBookSource(id))
            assertEquals(12, appDb.bookDao.getBook(book.bookUrl)?.durChapterPos)
            assertEquals(readable, BookHelp.getContent(book, chapter, false))
            val removed = BookSourceRemovalStore.removed().single { it.record.sourceId == id }
            appDb.bookSourceDao.insert(source.copy(bookSourceName = "新匯入版本"))
            assertFalse("Restore never replaces a newer import", BookSourceRemovalStore.restore(removed))
            assertEquals("新匯入版本", appDb.bookSourceDao.getBookSource(id)?.bookSourceName)
            appDb.bookSourceDao.delete(id)
            assertTrue(BookSourceRemovalStore.restore(removed))
            assertEquals(BookSourceAutoRemoval.snapshot(source), BookSourceAutoRemoval.snapshot(appDb.bookSourceDao.getBookSource(id)!!))
            assertFalse(archiveFor(id).exists())
            permanentlyGone.set(true)
            try {
                WebBook.getContentAwait(source, book, chapter)
                fail("The restored source still serves a missing chapter")
            } catch (e: BookSourceAutoRemoval.Gone) { assertEquals(410, e.status) }
            assertNotNull("An explicitly restored rule requires confirmation again", appDb.bookSourceDao.getBookSource(id))
            assertTrue(BookSourceRemovalStore.confirmedRemoval(BookSourceRemovalStore.reviews().single { it.record.sourceId == id }))
        } finally {
            withContext(Main) { ReadBook.book = null; ReadBook.bookSource = oldSource; ReadBook.durChapterIndex = oldIndex }
            MultiSourceManager.enabled = oldEnabled
            BookHelp.delContent(book, chapter)
            appDb.bookDao.delete(book)
            appDb.bookSourceDao.delete(id)
            BookSourceRemovalStore.forgetReview(id)
            archiveFor(id).delete()
            server.stop()
        }
    }
}
