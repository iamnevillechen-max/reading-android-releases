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
import io.legado.app.data.entities.rule.BookInfoRule
import io.legado.app.data.entities.rule.ContentRule
import io.legado.app.data.entities.rule.SearchRule
import io.legado.app.data.entities.rule.TocRule
import io.legado.app.help.book.BookHelp
import io.legado.app.model.ReadBook
import io.legado.app.model.multisource.MultiSourceManager
import io.legado.app.model.webBook.WebBook
import io.legado.app.service.BaseReadAloudService
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** Own loopback metadata only: an omitted search author never grants chapter access by itself. */
@RunWith(AndroidJUnit4::class)
class MultiSourceMetadataTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val preferences = context.getSharedPreferences("multisource", Context.MODE_PRIVATE)
    private val sourcePack = LoopbackSourcePackGuard(context)
    private val author = "本機元資料寫手"
    private val before = "雨停之後，阿澄提著竹燈走過門前的石橋，橋下的水面映出最後一抹晚霞，遠處傳來老船夫收起長篙的聲音。"
    private val missing = "她從橋欄的縫隙取出一封藍色信箋，信上畫著三片交疊的樹葉，提醒送信的人在天亮之前抵達北面的木屋。"
    private val after = "木屋的窗戶慢慢亮起，阿澄將竹燈放在門旁，敲響那扇刻有花紋的舊門，等待屋裡的人回答。"
    private lateinit var server: MetadataServer
    private lateinit var primary: BookSource
    private lateinit var alternate: BookSource
    private lateinit var book: Book
    private lateinit var chapter: BookChapter
    private var ownsData = false
    private var hadEnabled = false
    private var hadLimit = false
    private var previousEnabled = true
    private var previousLimit = 3
    private var preferencesCaptured = false

    @Before fun prepare() {
        check(Build.HARDWARE in setOf("ranchu", "goldfish") &&
            context.packageName == "io.multiread.reader.debug" &&
            appDb.bookSourceDao.allPart.isEmpty() && appDb.bookDao.allBookCount == 0 &&
            ReadBook.book == null && ReadBook.callBack == null && !BaseReadAloudService.isRun) {
            "Dedicated idle empty Reader emulator required; never run against a user's library"
        }
        hadEnabled = preferences.contains("enabled")
        hadLimit = preferences.contains("candidates")
        previousEnabled = MultiSourceManager.enabled
        previousLimit = MultiSourceManager.candidateLimit
        preferencesCaptured = true
        sourcePack.mark()
        MultiSourceManager.enabled = true
        MultiSourceManager.candidateLimit = 1
        server = MetadataServer().also { it.start(1_000, false) }
        val base = "http://127.0.0.1:${server.listeningPort}"
        server.bookName = "自行編寫元資料驗證${System.nanoTime()}"
        primary = source("$base/source/primary", "元資料原來源")
        alternate = source("$base/source/alternate", "元資料補充來源")
        book = Book(bookUrl = "$base/primary/book", tocUrl = "$base/primary/toc",
            origin = primary.bookSourceUrl, originName = primary.bookSourceName,
            name = server.bookName, author = author, canUpdate = false)
        chapter = BookChapter(url = "$base/primary/chapter", title = "第一章 石橋",
            baseUrl = book.bookUrl, bookUrl = book.bookUrl, index = 0)
        ownsData = true
        appDb.bookSourceDao.insert(primary, alternate)
        appDb.bookDao.insert(book)
        appDb.bookChapterDao.insert(chapter)
    }

    @After fun cleanup() {
        try {
            if (ownsData) {
                BookHelp.delContent(book, chapter)
                MultiSourceManager.clearCandidateHistory(book)
                appDb.bookChapterDao.delByBook(book.bookUrl)
                appDb.bookDao.delete(book)
                val base = "http://127.0.0.1:${server.listeningPort}"
                listOf("wrong", "correct", "known-wrong").forEach { key ->
                    val url = "$base/$key/book"
                    appDb.searchBookDao.getSearchBook(url)?.takeIf { it.name == book.name }
                        ?.let { appDb.searchBookDao.delete(it) }
                    appDb.bookChapterDao.delByBook(url)
                }
                appDb.bookSourceDao.delete(primary, alternate)
                listOf(primary, alternate).forEach { source ->
                    File(context.filesDir, "multisource-v1/relation-${sha(book.bookUrl + source.bookSourceUrl)}.json").delete()
                }
            }
        } finally {
            if (::server.isInitialized) server.stop()
            sourcePack.restore()
            if (preferencesCaptured) {
                val edit = preferences.edit()
                if (hadEnabled) edit.putBoolean("enabled", previousEnabled) else edit.remove("enabled")
                if (hadLimit) edit.putInt("candidates", previousLimit) else edit.remove("candidates")
                check(edit.commit())
            }
        }
    }

    @Test(timeout = 20_000)
    fun missingSearchAuthorsAreVerifiedInDetailBeforeAutomaticStitching() = runBlocking {
        val displayed = WebBook.getContentAwait(primary, book, chapter)
        assertTrue("The normal content path must complete the missing paragraph", displayed.contains(missing))
        assertTrue(displayed.contains(before) && displayed.contains(after))
        assertEquals(1, server.count("/wrong/book"))
        assertEquals(1, server.count("/correct/book"))
        assertEquals("A known wrong author must be rejected by the search filter", 0, server.count("/known-wrong/book"))
        assertEquals(0, server.count("/wrong/toc"))
        assertEquals(0, server.count("/wrong/chapter"))
        assertEquals(0, server.count("/known-wrong/toc"))
        assertEquals(0, server.count("/known-wrong/chapter"))
        assertEquals(1, server.count("/correct/toc"))
        assertEquals(1, server.count("/correct/chapter"))
        val base = "http://127.0.0.1:${server.listeningPort}"
        val stored = appDb.searchBookDao.getSearchBook("$base/correct/book")
        assertNotNull(stored)
        assertEquals(author, stored!!.author)
        assertNull(appDb.searchBookDao.getSearchBook("$base/wrong/book"))
        val raw = BookHelp.getContent(book, chapter, allowMultiSource = false)
        assertNotNull(raw)
        assertFalse("The original source file must stay unchanged", raw!!.contains(missing))
        assertEquals(displayed, BookHelp.getContent(book, chapter))
    }

    @Test(timeout = 20_000)
    fun unknownAndWrongDetailAuthorsNeverAuthorizeTocOrContentRequests() = runBlocking {
        server.correctDetailHasAuthor = false
        val displayed = WebBook.getContentAwait(primary, book, chapter)
        val raw = BookHelp.getContent(book, chapter, allowMultiSource = false)
        assertNotNull(raw)
        assertEquals(raw!!.trim(), displayed.trim())
        assertFalse(displayed.contains(missing))
        assertEquals(1, server.count("/wrong/book"))
        assertEquals(1, server.count("/correct/book"))
        listOf("wrong", "correct", "known-wrong").forEach { key ->
            assertEquals("Unverified books must never reach a TOC", 0, server.count("/$key/toc"))
            assertEquals("Unverified books must never reach chapter content", 0, server.count("/$key/chapter"))
            val url = "http://127.0.0.1:${server.listeningPort}/$key/book"
            assertNull(appDb.searchBookDao.getSearchBook(url))
        }
        assertEquals(0, server.count("/known-wrong/book"))
    }

    private fun source(url: String, name: String) = BookSource(
        bookSourceUrl = url, bookSourceName = name, bookSourceGroup = "owned-metadata-fixture",
        customOrder = Int.MIN_VALUE, enabledExplore = false,
        searchUrl = "http://127.0.0.1:${server.listeningPort}/search?key={{key}}",
        ruleSearch = SearchRule(bookList = ".book", name = ".name@text", author = ".author@text", bookUrl = "a@href"),
        ruleBookInfo = BookInfoRule(name = "#name@text", author = "#author@text", tocUrl = "#toc@href"),
        ruleToc = TocRule(chapterList = "a.chapter", chapterName = "text", chapterUrl = "href"),
        ruleContent = ContentRule(content = "#content@html")
    )

    private fun sha(value: String) = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

    private inner class MetadataServer : NanoHTTPD("127.0.0.1", 0) {
        lateinit var bookName: String
        @Volatile var correctDetailHasAuthor = true
        private val requests = ConcurrentHashMap<String, AtomicInteger>()
        fun count(path: String) = requests[path]?.get() ?: 0
        override fun serve(session: IHTTPSession): Response {
            requests.getOrPut(session.uri) { AtomicInteger() }.incrementAndGet()
            fun detail(key: String, actualAuthor: String) =
                "<h1 id='name'>$bookName</h1><span id='author'>$actualAuthor</span><a id='toc' href='/$key/toc'>目錄</a>"
            val body = when (session.uri) {
                "/search" -> "<div class='book'><a class='name' href='/wrong/book'>$bookName</a><span class='author'></span></div>" +
                    "<div class='book'><a class='name' href='/correct/book'>$bookName</a><span class='author'></span></div>" +
                    "<div class='book'><a class='name' href='/known-wrong/book'>$bookName</a><span class='author'>另一位明確作者</span></div>"
                "/wrong/book" -> detail("wrong", "另一位明確作者")
                "/correct/book" -> detail("correct", if (correctDetailHasAuthor) author else "")
                "/known-wrong/book" -> detail("known-wrong", "另一位明確作者")
                "/primary/chapter" -> "<div id='content'><p>$before</p><p>$after</p></div>"
                "/wrong/toc", "/correct/toc", "/known-wrong/toc" -> {
                    val key = session.uri.split('/')[1]
                    "<a class='chapter' href='/$key/chapter'>第一章 石橋</a>"
                }
                "/wrong/chapter", "/correct/chapter", "/known-wrong/chapter" ->
                    "<div id='content'><p>$before</p><p>$missing</p><p>$after</p></div>"
                else -> return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "owned fixture route not found")
            }
            return newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8",
                "<html><meta charset='utf-8'><body>$body</body></html>")
        }
    }
}
