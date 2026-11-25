package io.legado.app.multisource

import android.app.Application
import android.content.Intent
import android.os.Build
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import fi.iki.elonen.NanoHTTPD
import io.legado.app.data.appDb
import io.legado.app.data.entities.*
import io.legado.app.data.entities.rule.*
import io.legado.app.help.book.BookHelp
import io.legado.app.help.config.AppConfig
import io.legado.app.model.ReadBook
import io.legado.app.model.multisource.MultiSourceManager
import io.legado.app.model.multisource.BookshelfOpeningRecovery
import io.legado.app.ui.book.read.ReadBookViewModel
import io.legado.app.ui.book.read.page.provider.ChapterProvider
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Enter through the same ViewModel as a shelf click, rather than calling the merger directly. */
@RunWith(AndroidJUnit4::class)
class BookshelfOpeningRecoveryTest {
    private val application = ApplicationProvider.getApplicationContext<Application>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val guard = LoopbackSourcePackGuard(application)
    private val store = ViewModelStore()
    private lateinit var model: ReadBookViewModel
    private lateinit var primary: BookSource
    private lateinit var alternate: BookSource
    private lateinit var book: Book
    private lateinit var chapter: BookChapter
    private val title = "第十章 河岸"
    private val body = "清晨的雨停了，旅客沿著河岸走到木橋旁。守橋人取出一封藍色的信，指向遠處剛剛亮起的燈火。\n" +
        "他打開信封，終於明白多年以前留下的約定。這些文字只為本機回歸測試自行編寫，並非第三方小說。"
    private var wrongChapter = false
    private var oldEnabled = true
    private var oldLimit = 3
    private var oldPreload = 0
    private var oldRecord = false
    private val primaryRequests = AtomicInteger()
    private val contentRequests = AtomicInteger()
    private val server = object : NanoHTTPD("127.0.0.1", 0) {
        override fun serve(session: IHTTPSession): Response {
            if (session.uri == "/primary") {
                return newFixedLengthResponse(Response.Status.OK, "text/plain", "Owned source entry still active")
            }
            if (session.uri.startsWith("/primary")) {
                primaryRequests.incrementAndGet()
                return newFixedLengthResponse(Response.Status.GONE, "text/plain", "Owned expired source")
            }
            val html = when (session.uri) {
                "/search" -> "<div class='book'><a href='/alternate/book'>${book.name}</a><span>自寫作者</span></div>"
                "/alternate/book" -> "<h1>${book.name}</h1><span id='author'>自寫作者</span><a id='toc' href='/alternate/toc'>目錄</a>"
                "/alternate/toc" -> "<a class='chapter' href='/alternate/notice'>公告</a><a class='chapter' href='/alternate/chapter'>${if (wrongChapter) "第十章 另一個故事" else title}</a>"
                "/alternate/notice" -> "<div id='content'>這是本站公告。</div>"
                "/alternate/chapter" -> { contentRequests.incrementAndGet(); "<div id='content'>$body</div>" }
                else -> return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Owned missing route")
            }
            return newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", html)
        }
    }

    @Before fun prepare() = runBlocking {
        check(Build.HARDWARE in setOf("ranchu", "goldfish") && ReadBook.book == null && ReadBook.callBack == null)
        check(appDb.bookDao.allBookCount == 0 && appDb.bookSourceDao.allEnabled.isEmpty())
        guard.mark()
        oldEnabled = MultiSourceManager.enabled; oldLimit = MultiSourceManager.candidateLimit
        oldPreload = AppConfig.preDownloadNum; oldRecord = AppConfig.enableReadRecord
        MultiSourceManager.enabled = true; MultiSourceManager.candidateLimit = 1
        AppConfig.preDownloadNum = 0; AppConfig.enableReadRecord = false
        server.start(1_000, false)
        val base = "http://127.0.0.1:${server.listeningPort}"
        fun source(path: String) = BookSource(bookSourceUrl = "$base/$path", bookSourceName = "Owned $path",
            enabled = true, bookSourceType = 0, searchUrl = "$base/search",
            ruleSearch = SearchRule(bookList = "div.book", name = "a@text", author = "span@text", bookUrl = "a@href"),
            ruleBookInfo = BookInfoRule(name = "h1@text", author = "#author@text", tocUrl = "#toc@href"),
            ruleToc = TocRule(chapterList = "a.chapter", chapterName = "text", chapterUrl = "href"),
            ruleContent = ContentRule(content = "#content@text"))
        primary = source("primary"); alternate = source("alternate")
        book = Book(bookUrl = "$base/primary/book", origin = primary.bookSourceUrl, originName = primary.bookSourceName,
            name = "自寫開書回歸${UUID.randomUUID()}", author = "自寫作者", tocUrl = "", totalChapterNum = 10,
            durChapterIndex = 9, durChapterTitle = title, durChapterPos = 17, group = 8, order = 12,
            customTag = "保留自訂", canUpdate = false)
        chapter = BookChapter(bookUrl = book.bookUrl, url = "$base/primary/chapter", title = title, index = 9)
        appDb.bookSourceDao.insert(primary, alternate); appDb.bookDao.insert(book)
        withContext(Dispatchers.Main) {
            ChapterProvider.upViewSize(1080, 1920)
            model = ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory(application))[ReadBookViewModel::class.java]
        }
    }

    private suspend fun open(expectContent: Boolean = true) {
        val finished = CountDownLatch(1)
        withContext(Dispatchers.Main) {
            model.initData(Intent().putExtra("bookUrl", book.bookUrl).putExtra("inBookshelf", true)) { finished.countDown() }
        }
        withContext(Dispatchers.IO) { assertTrue("Shelf initialization timed out", finished.await(35, TimeUnit.SECONDS)) }
        if (expectContent) withTimeout(10_000) {
            while (ReadBook.curTextChapter?.isCompleted != true) delay(20)
        }
        ReadBook.executor.submit { }.get(5, TimeUnit.SECONDS)
    }

    @Test fun deadDetailDoesNotBlockAnExistingChapterFromTheBookshelf() = runBlocking {
        appDb.bookChapterDao.insert(chapter)
        open()
        assertTrue(ReadBook.curTextChapter!!.getContent().contains("清晨的雨停了"))
        assertEquals(book.bookUrl, ReadBook.book!!.bookUrl)
        assertEquals(1, primaryRequests.get()) // Only the failed body; dead detail is not a prerequisite.
        assertEquals(9, ReadBook.durChapterIndex)
    }

    @Test fun lostCatalogueFindsSameChapterRatherThanGuessingItsIndexAndKeepsUserData() = runBlocking {
        val mark = Bookmark(time = System.nanoTime(), bookName = book.name, bookAuthor = book.author,
            chapterIndex = 9, chapterPos = 17, chapterName = title, content = "自寫書籤")
        val record = ReadRecord(deviceId = "owned-opening", bookName = book.name, readTime = 1234, lastRead = 1700000000000)
        appDb.bookmarkDao.insert(mark); appDb.readRecordDao.insert(record)
        BookHelp.saveText(book, chapter, "原來的快取必須保留")
        open()
        val current = ReadBook.book!!
        assertNotEquals(book.bookUrl, current.bookUrl)
        assertEquals(1, current.durChapterIndex) // Candidate inserted a notice, not old index 9 or first index 0.
        assertEquals(title, current.durChapterTitle)
        assertEquals(17, ReadBook.durChapterPos)
        assertEquals(8L, current.group); assertEquals(12, current.order); assertEquals("保留自訂", current.customTag)
        assertEquals(1, appDb.bookDao.allBookCount)
        assertEquals(mark.copy(chapterIndex = 1), appDb.bookmarkDao.getByBook(book.name, book.author).single())
        assertEquals(listOf(record), appDb.readRecordDao.all.filter { it.bookName == book.name })
        assertEquals("原來的快取必須保留", BookHelp.getContent(book, chapter, allowMultiSource = false))
        assertTrue(ReadBook.curTextChapter!!.getContent().contains("清晨的雨停了"))
    }

    @Test fun unrelatedChapterCannotReplaceTheSavedBookEvenWithTheSameIndexNumber() = runBlocking {
        wrongChapter = true
        open(expectContent = false)
        assertEquals(book.bookUrl, ReadBook.book!!.bookUrl)
        assertEquals(9, appDb.bookDao.getBook(book.bookUrl)!!.durChapterIndex)
        assertEquals(0, contentRequests.get())
    }

    @Test fun cachedEmptyShellIsRecoveredDespiteAnUnmarkedSavedPosition() = runBlocking {
        appDb.bookChapterDao.insert(chapter)
        BookHelp.saveText(book, chapter, "$title\n\u200b\n下一章")
        open()
        assertTrue(ReadBook.curTextChapter!!.getContent().contains("清晨的雨停了"))
        assertEquals(book.bookUrl, ReadBook.book!!.bookUrl)
        assertEquals(17, ReadBook.durChapterPos)
    }

    @Test fun numberOnlySavedChapterWithoutContextNeverGuessesAnIndex() = runBlocking {
        book.durChapterTitle = "第十章"
        appDb.bookDao.update(book)
        open(expectContent = false)
        assertEquals(book.bookUrl, ReadBook.book!!.bookUrl)
        assertEquals(0, contentRequests.get())
    }

    @Test fun pendingOpeningCannotInstallAfterTheSameBookIsReopened() = runBlocking {
        ReadBook.resetData(book)
        val epoch = ReadBook.readingEpoch
        val found = MultiSourceManager.recoverOpening(book)!!
        ReadBook.resetData(book) // Same URL and position, different reading session.
        assertNull(BookshelfOpeningRecovery.install(book, found, epoch))
        assertEquals(book.bookUrl, appDb.bookDao.all.single().bookUrl)
        assertEquals(0, appDb.bookChapterDao.getChapterCount(book.bookUrl))
        assertNull(BookHelp.getContent(found.book, found.chapter, allowMultiSource = false))
    }

    @After fun cleanup() = runBlocking {
        withContext(Dispatchers.Main) { store.clear() }
        ReadBook.preDownloadTask?.cancelAndJoin()
        ReadBook.curTextChapter?.cancelLayout(); ReadBook.nextTextChapter?.cancelLayout(); ReadBook.prevTextChapter?.cancelLayout()
        ReadBook.clearTextChapter(); ReadBook.executor.submit { }.get(5, TimeUnit.SECONDS)
        ReadBook.book = null; ReadBook.bookSource = null; ReadBook.chapterSize = 0
        if (::book.isInitialized) {
            val ownedBooks = appDb.bookDao.all.filter { it.name == book.name }
            ownedBooks.forEach { owned ->
                appDb.bookChapterDao.getChapterList(owned.bookUrl).forEach { BookHelp.delContent(owned, it) }
                appDb.bookChapterDao.delByBook(owned.bookUrl); appDb.bookDao.delete(owned)
            }
            BookHelp.delContent(book, chapter)
            appDb.bookChapterDao.delByBook(book.bookUrl)
            MultiSourceManager.clearCandidateHistory(book)
            appDb.searchBookDao.clear(book.name, book.author)
            appDb.bookmarkDao.getByBook(book.name, book.author).forEach { appDb.bookmarkDao.delete(it) }
            appDb.readRecordDao.all.filter { it.bookName == book.name }.forEach { appDb.readRecordDao.delete(it) }
            appDb.bookSourceDao.delete(primary, alternate)
        }
        server.stop()
        MultiSourceManager.enabled = oldEnabled; MultiSourceManager.candidateLimit = oldLimit
        AppConfig.preDownloadNum = oldPreload; AppConfig.enableReadRecord = oldRecord
        guard.restore()
    }
}
