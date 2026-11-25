package io.legado.app.multisource

import android.content.Context
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.gson.JsonParser
import fi.iki.elonen.NanoHTTPD
import io.legado.app.constant.BookType
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.Bookmark
import io.legado.app.data.entities.ReadRecord
import io.legado.app.data.entities.rule.BookInfoRule
import io.legado.app.data.entities.rule.ContentRule
import io.legado.app.data.entities.rule.SearchRule
import io.legado.app.data.entities.rule.TocRule
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.isOnLineTxt
import io.legado.app.help.config.AppConfig
import io.legado.app.model.ReadBook
import io.legado.app.model.multisource.MultiSourceManager
import io.legado.app.model.webBook.WebBook
import io.legado.app.service.BaseReadAloudService
import io.legado.app.ui.book.read.page.provider.ChapterProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn

/** Deterministic loopback fixtures exercise the actual rule parser and original/derived caches. */
@RunWith(AndroidJUnit4::class)
class MultiSourceIntegrationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val prefs = context.getSharedPreferences("multisource", Context.MODE_PRIVATE)
    private val sourcePack = LoopbackSourcePackGuard(context)
    private var previousEnabled = true
    private var previousLimit = 3
    private lateinit var server: FixtureServer
    private lateinit var primary: BookSource
    private lateinit var alternate: BookSource
    private lateinit var book: Book
    private lateinit var chapter: BookChapter
    private val extraSources = mutableListOf<BookSource>()
    private val extraChapters = mutableListOf<BookChapter>()

    private val before = "夜色降臨之後，林舟沿著古老的長廊慢慢前進，窗外傳來有節奏的雨聲，石牆上的燈火照亮前方陌生的道路。"
    private val missing = "他在門後找到一封封存多年的信，信紙上記錄著村莊真正的名字，以及只有守門人才知道的秘密。"
    private val after = "走過最後一道拱門時，遠處的鐘聲忽然響起，所有人停下腳步仰望高塔，等待那位遲來的旅客平安歸來。"
    private val secondBefore = "第二天清晨，林舟來到河邊的小屋，老船夫正仔細檢查繫在木柱上的纜繩，準備迎接第一班渡船。"
    private val secondMissing = "船艙裡擺著一只上了鎖的木箱，箱蓋刻有三道月牙形的記號，那正是信中提到的約定。"
    private val secondAfter = "岸上的人們依次踏上甲板，林舟回頭望向漸漸遠去的小屋，終於明白那場夜雨並不是偶然的相遇。"

    @Before fun prepare() {
        previousEnabled = MultiSourceManager.enabled
        previousLimit = MultiSourceManager.candidateLimit
        MultiSourceManager.enabled = true
        MultiSourceManager.candidateLimit = 1
        server = FixtureServer().also { it.start(1_000, false) }
        val base = "http://127.0.0.1:${server.listeningPort}"
        val token = System.nanoTime().toString()
        server.bookName = "多源本機驗證$token"
        primary = source("$base/primary", "本機原源")
        alternate = source("$base/alternate", "本機補充源")
        book = Book(bookUrl = "$base/primary/book", tocUrl = "$base/primary/toc",
            origin = primary.bookSourceUrl, originName = primary.bookSourceName,
            name = server.bookName, author = "測試寫手甲", canUpdate = false)
        chapter = BookChapter(url = "$base/primary/chapter/0", title = "第一章 迴廊",
            baseUrl = book.bookUrl, bookUrl = book.bookUrl, index = 0)
        check(appDb.bookSourceDao.allEnabled.isEmpty()) {
            "Run local integration fixtures in the dedicated empty test installation; external sources are forbidden"
        }
        sourcePack.mark()
        appDb.bookSourceDao.insert(primary, alternate)
        appDb.bookDao.insert(book)
        appDb.bookChapterDao.insert(chapter)
    }

    @After fun cleanup() {
        try {
            if (::book.isInitialized) {
                BookHelp.delContent(book, chapter)
                extraChapters.forEach { BookHelp.delContent(book, it) }
                MultiSourceManager.clearCandidateHistory(book)
                appDb.searchBookDao.clear(book.name, book.author)
                appDb.bookChapterDao.delByBook(book.bookUrl)
                appDb.bookDao.delete(book)
                val sources = listOf(primary, alternate) + extraSources
                appDb.bookSourceDao.delete(*sources.toTypedArray())
                sources.forEach { source ->
                    val key = sha(book.bookUrl + source.bookSourceUrl)
                    File(context.filesDir, "multisource-v1/relation-$key.json").delete()
                    prefs.edit().remove("retry:$key").apply()
                }
            }
            if (::server.isInitialized) server.stop()
            MultiSourceManager.enabled = previousEnabled
            MultiSourceManager.candidateLimit = previousLimit
        } finally { sourcePack.restore() }
    }

    @Test fun realRulesMergeSeparatelyAndReuseCacheWithoutRequests() = runBlocking {
        val raw = rawContent()
        assertFalse(raw.contains(missing))
        assertEquals(raw, BookHelp.getContent(book, chapter, allowMultiSource = false))
        val merged = MultiSourceManager.enrich(primary, book, chapter, raw)
        assertTrue("A confirmed internal omission should be filled", merged.contains(missing))
        assertTrue(server.count("/search") > 0)
        assertTrue(server.count("/alternate/toc") > 0)
        assertTrue(server.count("/alternate/chapter/0") > 0)
        assertEquals("The original cache must remain untouched", raw,
            BookHelp.getContent(book, chapter, allowMultiSource = false))
        assertEquals(merged, BookHelp.getContent(book, chapter))
        val requests = server.total.get()
        assertEquals(merged, MultiSourceManager.enrich(primary, book, chapter, raw))
        assertEquals("Cache reuse must make no new HTTP request", requests, server.total.get())
        MultiSourceManager.enabled = false
        assertEquals(raw, BookHelp.getContent(book, chapter))
        assertNull(MultiSourceManager.cached(book, chapter, raw))
        MultiSourceManager.enabled = true
        assertEquals(merged, BookHelp.getContent(book, chapter))
        alternate.enabled = false
        appDb.bookSourceDao.update(alternate)
        assertNull("Disabling a participating source must invalidate derived text",
            MultiSourceManager.cached(book, chapter, raw))
        assertEquals(raw, BookHelp.getContent(book, chapter))
    }

    @Test fun normalWebBookPipelineAutomaticallyEnrichesButKeepsRawText() = runBlocking {
        val displayed = WebBook.getContentAwait(primary, book, chapter)
        assertTrue("Normal WebBook content loading must enter the integrated path", displayed.contains(missing))
        val raw = BookHelp.getContent(book, chapter, allowMultiSource = false)
        assertNotNull(raw)
        assertFalse(raw!!.contains(missing))
        assertTrue(raw.contains(before) && raw.contains(after))
        assertEquals(displayed, BookHelp.getContent(book, chapter))
    }

    @Test fun paidPreviewUsesAnOpenEditionAndDoesNotFetchAnUnpurchasedCandidateChapter() = runBlocking {
        val base = "http://127.0.0.1:${server.listeningPort}"
        val locked = source("$base/aaa-locked", "本機付費版").apply {
            searchUrl = "$base/blocked-search?key={{key}}"
            ruleToc!!.isVip = "text"
        }
        extraSources += locked
        alternate.customOrder = Int.MIN_VALUE + 1
        appDb.bookSourceDao.update(alternate)
        appDb.bookSourceDao.insert(locked)
        chapter.isVip = true
        appDb.bookChapterDao.insert(chapter)
        server.mode = "primary-preview"
        val displayed = WebBook.getContentAwait(primary, book, chapter)
        assertTrue(displayed.contains(before) && displayed.contains(missing) && displayed.contains(after))
        assertFalse(displayed.contains("订阅后阅读"))
        assertEquals(1, server.count("/blocked/toc"))
        assertEquals(0, server.count("/blocked/chapter/0"))
        assertEquals(1, server.count("/alternate/chapter/0"))
        val original = BookHelp.getContent(book, chapter, false)!!
        assertTrue(original.contains(before) && original.contains("订阅后阅读"))
        assertEquals(displayed, BookHelp.getContent(book, chapter))
    }

    @Test fun cachedChapterWithAnErrorInTheMiddleIsRepairedBetweenItsOriginalParagraphs() = runBlocking {
        check(ReadBook.book == null && ReadBook.callBack == null && !BaseReadAloudService.isRun)
        val raw = "$before\n正文加载失败，请刷新重试\n$after"
        BookHelp.saveText(book, chapter, raw)
        val oldSize = ReadBook.chapterSize
        val oldSource = ReadBook.bookSource
        val alreadyDownloaded = 0 in ReadBook.downloadedChapters
        try {
            ReadBook.book = book
            ReadBook.bookSource = primary
            ReadBook.chapterSize = 1
            preloadIndex(0)
            val repaired = BookHelp.getContent(book, chapter)!!
            assertTrue(repaired.contains(before) && repaired.contains(missing) && repaired.contains(after))
            assertFalse(repaired.contains("刷新重试"))
            assertEquals(raw, BookHelp.getContent(book, chapter, false))
            assertEquals(0, server.count("/primary/chapter/0"))
        } finally {
            ReadBook.book = null
            ReadBook.bookSource = oldSource
            ReadBook.chapterSize = oldSize
            if (!alreadyDownloaded) ReadBook.downloadedChapters.remove(0)
        }
    }

    @Test(timeout = 30_000)
    fun failedPrimaryRescueRemainsReadableOfflineWithoutCreatingAnOriginalFile() = runBlocking {
        check(Build.HARDWARE in setOf("ranchu", "goldfish") && ReadBook.book == null &&
            ReadBook.callBack == null && !BaseReadAloudService.isRun && ReadBook.preDownloadTask?.isActive != true) {
            "Dedicated idle emulator required for native reader rescue fixture"
        }
        server.mode = "primary-failure"
        val rescued = WebBook.getContentAwait(primary, book, chapter)
        assertTrue(rescued.contains(before) && rescued.contains(missing) && rescued.contains(after))
        assertTrue(server.count("/primary/chapter/0") > 0 && server.count("/alternate/chapter/0") > 0)
        assertFalse("Rescue must not masquerade as an original source file", BookHelp.hasContent(book, chapter))
        assertNull(BookHelp.getContent(book, chapter, allowMultiSource = false))

        // A successful single-source rescue is positive content, not a one-minute cached miss.
        val saved = File(context.filesDir, "multisource-v1/${sha(book.bookUrl + "\n" + chapter.url)}.json")
        val json = JsonParser.parseString(saved.readText()).asJsonObject
        json.addProperty("created", System.currentTimeMillis() - 120_000L)
        saved.writeText(json.toString())
        server.mode = "offline"
        val requests = server.total.get()
        assertEquals(rescued, BookHelp.getContent(book, chapter))
        assertEquals(requests, server.total.get())

        val oldRecordEnabled = AppConfig.enableReadRecord
        val oldPreload = AppConfig.preDownloadNum
        val oldSource = ReadBook.bookSource
        val oldSize = ReadBook.chapterSize
        val oldSimulatedSize = ReadBook.simulatedChapterSize
        val oldIndex = ReadBook.durChapterIndex
        val oldPosition = ReadBook.durChapterPos
        val oldIsLocal = ReadBook.isLocalBook
        val oldTask = ReadBook.preDownloadTask
        val oldDownloaded = ReadBook.downloadedChapters.toSet()
        val bookmark = Bookmark(time = System.nanoTime(), bookName = book.name, bookAuthor = book.author,
            chapterIndex = 0, chapterPos = 13, chapterName = chapter.title, bookText = before.take(24), content = "自寫救援測試書籤")
        val record = ReadRecord(deviceId = "owned-rescue-fixture", bookName = book.name,
            readTime = 123_456L, lastRead = 1_700_000_000_000L)
        var ownsBookmark = false
        var ownsRecord = false
        try {
            appDb.bookmarkDao.insert(bookmark)
            ownsBookmark = true
            appDb.readRecordDao.insert(record)
            ownsRecord = true
            AppConfig.enableReadRecord = false
            AppConfig.preDownloadNum = 0
            withContext(Dispatchers.Main) { ChapterProvider.upViewSize(1080, 1920) }
            ReadBook.resetData(book)
            withTimeout(5_000) { ReadBook.loadContentAwait(0, resetPageOffset = true) }
            ReadBook.executor.submit { }.get(5, TimeUnit.SECONDS)
            val rendered = ReadBook.curTextChapter!!
            assertTrue(rendered.isCompleted && rendered.getContent().contains(missing))
            val renderedText = rendered.getContent()
            assertEquals("Cached rescue must not try the unavailable primary again", requests, server.total.get())

            // Exercise the actual preload branch: an already prepared rescue needs no request
            // and must not be fed back to the merger as though it were original source text.
            preloadIndex(0)
            assertSame(rendered, ReadBook.curTextChapter)
            assertTrue(0 in ReadBook.downloadedChapters)
            assertEquals(requests, server.total.get())

            val position = renderedText.indexOf(after.take(20))
            assertTrue(position > 0)
            ReadBook.durChapterPos = position
            ReadBook.saveRead()
            ReadBook.executor.submit { }.get(5, TimeUnit.SECONDS)
            val persisted = appDb.bookDao.getBook(book.bookUrl)!!
            assertEquals(position, persisted.durChapterPos)
            assertNotNull(persisted.readConfig?.multiSourcePositionAnchor)
            server.stop() // Now disconnect the real transport, in addition to the counting 503 phase.
            ReadBook.resetData(persisted)
            withTimeout(5_000) { ReadBook.loadContentAwait(0, resetPageOffset = true) }
            ReadBook.executor.submit { }.get(5, TimeUnit.SECONDS)
            assertEquals(renderedText, ReadBook.curTextChapter!!.getContent())
            assertEquals(position, ReadBook.durChapterPos)
            assertEquals(listOf(bookmark), appDb.bookmarkDao.getByBook(book.name, book.author))
            assertEquals(listOf(record), appDb.readRecordDao.all.filter { it.bookName == book.name })
            assertFalse(BookHelp.hasContent(book, chapter))
            assertNull(BookHelp.getContent(book, chapter, allowMultiSource = false))
            assertEquals(rescued, BookHelp.getContent(book, chapter))
            MultiSourceManager.enabled = false
            assertNull(BookHelp.getContent(book, chapter))
            MultiSourceManager.enabled = true
            assertEquals(rescued, BookHelp.getContent(book, chapter))
            alternate.enabled = false
            appDb.bookSourceDao.update(alternate)
            assertNull("Disabled rescue sources must invalidate their saved content", BookHelp.getContent(book, chapter))
        } finally {
            ReadBook.preDownloadTask?.takeIf { it !== oldTask }?.cancel()
            ReadBook.curTextChapter?.cancelLayout()
            ReadBook.nextTextChapter?.cancelLayout()
            ReadBook.prevTextChapter?.cancelLayout()
            ReadBook.clearTextChapter()
            ReadBook.executor.submit { }.get(5, TimeUnit.SECONDS)
            ReadBook.book = null
            ReadBook.bookSource = oldSource
            ReadBook.chapterSize = oldSize
            ReadBook.simulatedChapterSize = oldSimulatedSize
            ReadBook.durChapterIndex = oldIndex
            ReadBook.durChapterPos = oldPosition
            ReadBook.isLocalBook = oldIsLocal
            ReadBook.preDownloadTask = oldTask
            ReadBook.downloadedChapters.clear()
            ReadBook.downloadedChapters.addAll(oldDownloaded)
            AppConfig.enableReadRecord = oldRecordEnabled
            AppConfig.preDownloadNum = oldPreload
            if (ownsBookmark) appDb.bookmarkDao.delete(bookmark)
            if (ownsRecord) appDb.readRecordDao.delete(record)
        }
    }

    @Test(timeout = 30_000)
    fun failedRescueThenNewManualKnownResultRecoversWithoutChangingSourceRules() = runBlocking {
        alternate.bookSourceGroup = null // Normal ungrouped imported sources must remain eligible.
        appDb.bookSourceDao.update(alternate)
        server.mode = "primary-failure-search-miss"
        assertTrue(MultiSourceManager.enrich(primary, book, chapter, "").isBlank())
        val file = File(context.filesDir, "multisource-v1/${sha(book.bookUrl + "\n" + chapter.url)}.json")
        val miss = JsonParser.parseString(file.readText()).asJsonObject
        assertTrue(miss["result"].asJsonObject["text"].asString.isBlank())
        assertNull("A negative rescue is never readable cached content", MultiSourceManager.cached(book, chapter, ""))
        assertNull(BookHelp.getContent(book, chapter))
        val configuration = miss["configuration"].asString
        val originalRules = io.legado.app.utils.GSON.toJson(appDb.bookSourceDao.getBookSource(alternate.bookSourceUrl))

        // This is the same native search parser used by a manual change-source search.
        // Only its metadata changes; the source rule configuration stays identical.
        server.mode = "primary-failure"
        val manual = WebBook.searchBookAwait(alternate, book.name).single()
        assertEquals(book.name, manual.name)
        assertEquals(book.author, manual.author)
        appDb.searchBookDao.insert(manual)
        assertTrue(appDb.searchBookDao.changeSourceByGroup(book.name, book.author, "")
            .any { it.origin == alternate.bookSourceUrl })
        verifyResumedReaderRescue(removePrimary = false, asynchronous = false)
        val recovered = JsonParser.parseString(file.readText()).asJsonObject
        assertEquals("New search metadata must not need a source-rule edit", configuration, recovered["configuration"].asString)
        assertTrue(recovered["result"].asJsonObject["text"].asString.contains(missing))
        assertEquals(originalRules, io.legado.app.utils.GSON.toJson(appDb.bookSourceDao.getBookSource(alternate.bookSourceUrl)))
        assertEquals("The automatic retry reuses the newly found book relation", 2, server.count("/search"))
    }

    @Test fun blankDerivedMissAndEmptyOriginalFileAreNotReadableOrDownloaded() = runBlocking {
        server.mode = "primary-failure-search-miss"
        assertTrue(MultiSourceManager.enrich(primary, book, chapter, "").isBlank())
        assertNull(MultiSourceManager.cached(book, chapter, ""))
        assertNull(BookHelp.getContent(book, chapter))
        val requests = server.total.get()
        assertTrue(MultiSourceManager.enrich(primary, book, chapter, "").isBlank())
        assertEquals("Existing source failure backoff must still avoid duplicate requests", requests, server.total.get())
        val original = File(context.getExternalFilesDir(null), "book_cache/${book.getFolderName()}/${chapter.getFileName()}")
        original.parentFile!!.mkdirs()
        original.writeText("")
        assertFalse("An empty original file is not a downloaded chapter", BookHelp.hasContent(book, chapter))
        assertNull(BookHelp.getContent(book, chapter, allowMultiSource = false))
        assertNull(BookHelp.getContent(book, chapter))
        original.writeText(" \n\t")
        assertNull("Whitespace-only original text must not prevent rescue", BookHelp.getContent(book, chapter, allowMultiSource = false))
        assertNull(BookHelp.getContent(book, chapter))
    }

    @Test(timeout = 30_000)
    fun missingPrimaryRuleAutomaticallyRecoversInNativeReader() = runBlocking {
        verifyResumedReaderRescue(removePrimary = true, asynchronous = true)
    }

    @Test(timeout = 30_000)
    fun unavailablePrimaryRecoversDespiteLegacySavedPosition() = runBlocking {
        verifyResumedReaderRescue(removePrimary = false, asynchronous = false)
    }

    @Test(timeout = 30_000)
    fun removedPrimaryRecoversUntypedLegacyBookWithoutRewritingItsType() = runBlocking {
        book.type = 0
        assertTrue("An untyped legacy network novel must enter automatic rescue", book.isOnLineTxt)
        verifyResumedReaderRescue(removePrimary = true, asynchronous = false)
        assertEquals("Reading compatibility must not migrate the stored book type", 0,
            appDb.bookDao.getBook(book.bookUrl)!!.type)
    }

    @Test(timeout = 30_000)
    fun removedPrimaryRecoversLegacyBookWithOnlyAnUpdateErrorFlag() = runBlocking {
        book.type = BookType.updateError
        assertTrue("An update-failure flag must not disable legacy network novel recovery", book.isOnLineTxt)
        verifyResumedReaderRescue(removePrimary = true, asynchronous = false)
        assertEquals("Compatibility must preserve the stored type and its existing error flag", BookType.updateError,
            appDb.bookDao.getBook(book.bookUrl)!!.type)
    }

    @Test(timeout = 40_000)
    fun coldFourStageRescueCanFinishAfterTheOldForegroundDeadline() = runBlocking {
        verifyResumedReaderRescue(removePrimary = false, asynchronous = false, coldDiscovery = true)
    }

    private suspend fun verifyResumedReaderRescue(removePrimary: Boolean, asynchronous: Boolean,
        coldDiscovery: Boolean = false) {
        check(Build.HARDWARE in setOf("ranchu", "goldfish") && ReadBook.book == null &&
            ReadBook.callBack == null && !BaseReadAloudService.isRun && ReadBook.preDownloadTask?.isActive != true) {
            "Dedicated idle emulator required for native reader rescue fixture"
        }
        server.mode = if (coldDiscovery) "cold-rescue" else "primary-failure"
        if (removePrimary) appDb.bookSourceDao.delete(primary)
        book.durChapterPos = 73 // Legacy saved offset, deliberately without a derived-text anchor.
        appDb.bookDao.update(book)
        val oldRecord = AppConfig.enableReadRecord
        val oldPreload = AppConfig.preDownloadNum
        val oldSource = ReadBook.bookSource
        val oldSize = ReadBook.chapterSize
        val oldSimulatedSize = ReadBook.simulatedChapterSize
        val oldIndex = ReadBook.durChapterIndex
        val oldPosition = ReadBook.durChapterPos
        val oldIsLocal = ReadBook.isLocalBook
        val oldTask = ReadBook.preDownloadTask
        val oldDownloaded = ReadBook.downloadedChapters.toSet()
        val record = ReadRecord(deviceId = "owned-missing-primary-fixture", bookName = book.name,
            readTime = 123_456L, lastRead = 1_700_000_000_000L)
        var ownsRecord = false
        try {
            appDb.readRecordDao.insert(record)
            ownsRecord = true
            AppConfig.enableReadRecord = false
            AppConfig.preDownloadNum = 0
            withContext(Dispatchers.Main) { ChapterProvider.upViewSize(1080, 1920) }
            ReadBook.resetData(book)
            assertEquals(73, ReadBook.durChapterPos)
            val started = System.nanoTime()
            if (asynchronous) {
                ReadBook.loadContent(0, resetPageOffset = false)
                val jobs = ReadBook::class.java.getDeclaredField("chapterLoadingJobs").apply { isAccessible = true }
                    .get(ReadBook) as Map<*, *>
                withTimeout(10_000) {
                    while (ReadBook.curTextChapter?.isCompleted != true ||
                        (jobs[0] as? io.legado.app.help.coroutine.Coroutine<*>)?.isCompleted != true) delay(25)
                }
            } else {
                withTimeout(if (coldDiscovery) 28_000L else 10_000L) {
                    ReadBook.loadContentAwait(0, resetPageOffset = false)
                }
            }
            val elapsed = (System.nanoTime() - started) / 1_000_000
            if (coldDiscovery) {
                assertTrue("The cold fixture must exceed the former 4.5 second rescue deadline", elapsed >= 4_500)
                assertTrue("Foreground rescue remains bounded", elapsed < 25_000)
                for (path in listOf("/search", "/alternate/book", "/alternate/toc", "/alternate/chapter/0"))
                    assertEquals("Each normal public stage must complete once", 1, server.count(path))
            }
            ReadBook.executor.submit { }.get(5, TimeUnit.SECONDS)
            val rendered = ReadBook.curTextChapter!!
            assertTrue("An unavailable original must automatically display verified alternate content",
                rendered.isCompleted && rendered.getContent().contains(missing))
            assertEquals("Recovery must retain a legacy numeric offset", 73, ReadBook.durChapterPos)
            assertEquals(book.bookUrl, ReadBook.book!!.bookUrl)
            assertEquals(1, appDb.bookChapterDao.getChapterCount(book.bookUrl))
            assertEquals(listOf(record), appDb.readRecordDao.all.filter { it.bookName == book.name })
            assertFalse("Recovered text must remain separate from original cache", BookHelp.hasContent(book, chapter))
            assertNull(BookHelp.getContent(book, chapter, allowMultiSource = false))
            assertTrue(BookHelp.getContent(book, chapter)!!.contains(missing))
            if (removePrimary) {
                assertNull("Recovery must not invent or reinsert a source rule", appDb.bookSourceDao.getBookSource(book.origin))
                assertEquals(0, server.count("/primary/chapter/0"))
            }
        } finally {
            ReadBook.preDownloadTask?.takeIf { it !== oldTask }?.cancel()
            ReadBook.curTextChapter?.cancelLayout()
            ReadBook.nextTextChapter?.cancelLayout()
            ReadBook.prevTextChapter?.cancelLayout()
            ReadBook.clearTextChapter()
            ReadBook.executor.submit { }.get(5, TimeUnit.SECONDS)
            ReadBook.book = null
            ReadBook.bookSource = oldSource
            ReadBook.chapterSize = oldSize
            ReadBook.simulatedChapterSize = oldSimulatedSize
            ReadBook.durChapterIndex = oldIndex
            ReadBook.durChapterPos = oldPosition
            ReadBook.isLocalBook = oldIsLocal
            ReadBook.preDownloadTask = oldTask
            ReadBook.downloadedChapters.clear()
            ReadBook.downloadedChapters.addAll(oldDownloaded)
            AppConfig.enableReadRecord = oldRecord
            AppConfig.preDownloadNum = oldPreload
            if (ownsRecord) appDb.readRecordDao.delete(record)
        }
    }

    @Test(timeout = 20_000)
    fun stalledPrimaryIsBoundedBeforeAutomaticRecovery() = runBlocking {
        server.mode = "primary-slow"
        val started = System.nanoTime()
        val rescued = WebBook.getContentAwait(primary, book, chapter)
        val elapsed = (System.nanoTime() - started) / 1_000_000
        assertTrue("Primary transport must time out before its full HTTP read timeout", elapsed < 8_000)
        assertTrue(rescued.contains(before) && rescued.contains(missing) && rescued.contains(after))
        assertNull(BookHelp.getContent(book, chapter, allowMultiSource = false))
        assertEquals(rescued, BookHelp.getContent(book, chapter))
    }

    @Test fun missingOriginalAdvancesPastQuickUnmatchedCandidatesWithinOneLoad() = runBlocking {
        val base = "http://127.0.0.1:${server.listeningPort}"
        val irrelevant = source("$base/aaa-unmatched", "本機不匹配源").apply {
            searchUrl = "$base/bad-search?key={{key}}"
        }
        extraSources += irrelevant
        alternate.customOrder = Int.MIN_VALUE + 1
        appDb.bookSourceDao.update(alternate)
        appDb.bookSourceDao.insert(irrelevant)
        val rescued = MultiSourceManager.enrich(null, book, chapter, "")
        assertTrue("An immediate miss must allow the next bounded rescue wave", rescued.contains(missing))
        assertEquals(1, server.count("/bad-search"))
        assertEquals(1, server.count("/search"))
        assertEquals(0, server.count("/primary/chapter/0"))
        assertFalse(BookHelp.hasContent(book, chapter))
    }

    @Test fun accessPageCannotStopMissingOriginalRecoveryWaves() = runBlocking {
        val base = "http://127.0.0.1:${server.listeningPort}"
        val blocked = source("$base/aaa-blocked", "本機登入頁來源").apply {
            searchUrl = "$base/blocked-search?key={{key}}"
        }
        extraSources += blocked
        alternate.customOrder = Int.MIN_VALUE + 1
        appDb.bookSourceDao.update(alternate)
        appDb.bookSourceDao.insert(blocked)
        val rescued = MultiSourceManager.enrich(null, book, chapter, "")
        assertTrue("A rejected access page must allow a later valid rescue candidate", rescued.contains(missing))
        assertFalse(rescued.contains("请先登录"))
        assertEquals(1, server.count("/blocked/chapter/0"))
        assertEquals(1, server.count("/alternate/chapter/0"))
        assertFalse(BookHelp.hasContent(book, chapter))
    }

    @Test fun accessPageCannotStopSupplementingReadableOriginalText() = runBlocking {
        val base = "http://127.0.0.1:${server.listeningPort}"
        val blocked = source("$base/aaa-blocked", "本機登入頁來源").apply {
            searchUrl = "$base/blocked-search?key={{key}}"
        }
        extraSources += blocked
        alternate.customOrder = Int.MIN_VALUE + 1
        appDb.bookSourceDao.update(alternate)
        appDb.bookSourceDao.insert(blocked)
        val raw = rawContent()
        val merged = MultiSourceManager.enrich(primary, book, chapter, raw)
        assertTrue("An access page must not consume the only supplement attempt", merged.contains(missing))
        assertFalse(merged.contains("请先登录"))
        assertEquals(1, server.count("/blocked/chapter/0"))
        assertEquals(1, server.count("/alternate/chapter/0"))
        assertEquals(raw, BookHelp.getContent(book, chapter, allowMultiSource = false))
    }

    @Test fun irrelevantPrefixYieldsWithinTheSameLoadAndMergedCacheDoesNotRepeatRequests() = runBlocking {
        val base = "http://127.0.0.1:${server.listeningPort}"
        val irrelevant = source("$base/aaa-unmatched", "本機不匹配源").apply {
            searchUrl = "$base/bad-search?key={{key}}"
        }
        extraSources += irrelevant
        alternate.customOrder = Int.MIN_VALUE + 1
        appDb.bookSourceDao.update(alternate)
        appDb.bookSourceDao.insert(irrelevant)
        val second = BookChapter(url = "$base/primary/chapter/1", title = "第二章 高塔",
            baseUrl = book.bookUrl, bookUrl = book.bookUrl, index = 1)
        extraChapters += second
        appDb.bookChapterDao.insert(second)

        val firstRaw = rawContent()
        val first = MultiSourceManager.enrich(primary, book, chapter, firstRaw)
        assertTrue("The same load must continue past a quick wrong-author result", first.contains(missing))
        assertEquals(1, server.count("/bad-search"))
        assertEquals(1, server.count("/search"))
        val requestsAfterMerge = server.total.get()
        assertEquals(first, MultiSourceManager.enrich(primary, book, chapter, firstRaw))
        assertEquals("The prepared supplement must be reusable without new requests", requestsAfterMerge, server.total.get())

        val secondRaw = rawContent(second)
        val mergedSecond = MultiSourceManager.enrich(primary, book, second, secondRaw)
        assertTrue("The next chapter must advance beyond the irrelevant prefix", mergedSecond.contains(secondMissing))
        assertEquals(1, server.count("/bad-search"))
        assertEquals(1, server.count("/search"))
        assertEquals(1, server.count("/alternate/chapter/1"))
        assertEquals(secondRaw, BookHelp.getContent(book, second, allowMultiSource = false))

        MultiSourceManager.invalidate(book, chapter)
        val recoveredFirst = MultiSourceManager.enrich(primary, book, chapter, firstRaw)
        assertTrue("The discovered verified source must also repair the earlier chapter", recoveredFirst.contains(missing))
        assertEquals("The wrong-author prefix stays deferred", 1, server.count("/bad-search"))
        assertEquals("The confirmed book relation is reused", 1, server.count("/search"))
        assertEquals(firstRaw, BookHelp.getContent(book, chapter, allowMultiSource = false))
    }

    @Test fun backgroundSingleSourceMissGetsOneForegroundAttemptBeforeCacheReuse() = runBlocking {
        check(ReadBook.book == null && ReadBook.callBack == null && !BaseReadAloudService.isRun)
        val base = "http://127.0.0.1:${server.listeningPort}"
        for (index in 0 until 4) {
            val irrelevant = source("$base/unmatched-$index", "本機不匹配源$index").apply {
                customOrder = Int.MIN_VALUE + index
                searchUrl = "$base/bad-search?key={{key}}"
            }
            extraSources += irrelevant
            appDb.bookSourceDao.insert(irrelevant)
        }
        alternate.customOrder = Int.MIN_VALUE + 4
        appDb.bookSourceDao.update(alternate)
        val raw = rawContent()
        assertFalse(MultiSourceManager.enrich(primary, book, chapter, raw).contains(missing))
        assertEquals(4, server.count("/bad-search"))
        val oldIndex = ReadBook.durChapterIndex
        val oldPosition = ReadBook.durChapterPos
        try {
            ReadBook.book = book
            ReadBook.durChapterIndex = chapter.index
            ReadBook.durChapterPos = 0
            assertNull("A background single-source miss must not finish the foreground attempt",
                MultiSourceManager.cached(book, chapter, raw))
            val merged = MultiSourceManager.enrich(primary, book, chapter, raw)
            assertTrue(merged.contains(missing))
            assertEquals("Previously attempted misses remain deferred", 4, server.count("/bad-search"))
            assertEquals(1, server.count("/search"))
            val requests = server.total.get()
            assertEquals(merged, MultiSourceManager.enrich(primary, book, chapter, raw))
            assertEquals("A foreground attempt must not trigger repeated immediate network work", requests, server.total.get())
            assertEquals(raw, BookHelp.getContent(book, chapter, allowMultiSource = false))
        } finally {
            ReadBook.book = null
            ReadBook.durChapterIndex = oldIndex
            ReadBook.durChapterPos = oldPosition
        }
    }

    @Test fun candidateFetchDoesNotReenterMergeOrWriteOriginalCache() = runBlocking {
        val candidateText = WebBook.getContentAwait(primary, book, chapter, needSave = false)
        assertTrue(candidateText.contains(before) && candidateText.contains(after))
        assertFalse(candidateText.contains(missing))
        assertEquals("A raw candidate fetch must not recursively search other sources", 0, server.count("/search"))
        assertNull(BookHelp.getContent(book, chapter, allowMultiSource = false))
        assertNull(MultiSourceManager.cached(book, chapter, candidateText))
    }

    @Test fun ruleChangesAndManualRefreshInvalidateOnlyDerivedChapter() = runBlocking {
        val raw = rawContent()
        assertTrue(MultiSourceManager.enrich(primary, book, chapter, raw).contains(missing))
        alternate.ruleContent = ContentRule(content = "#missing-element@text")
        appDb.bookSourceDao.update(alternate)
        assertNull("Changing a rule must make the old result unavailable",
            MultiSourceManager.cached(book, chapter, raw))
        assertEquals(raw, BookHelp.getContent(book, chapter))
        val fallback = MultiSourceManager.enrichSafely(primary, book, chapter, raw)
        assertFalse(fallback.contains(missing))
        assertEquals(raw, BookHelp.getContent(book, chapter, allowMultiSource = false))
        BookHelp.delContent(book, chapter)
        assertNull(BookHelp.getContent(book, chapter, allowMultiSource = false))
        assertNull(MultiSourceManager.cached(book, chapter, raw))
        assertNotNull("Refreshing content must preserve the original chapter metadata",
            appDb.bookChapterDao.getChapter(book.bookUrl, chapter.index))
    }

    @Test fun alternateFailureCannotDamageReadableOriginal() = runBlocking {
        val raw = rawContent()
        server.mode = "failure"
        val result = MultiSourceManager.enrichSafely(primary, book, chapter, raw)
        assertTrue(result.contains(before))
        assertTrue(result.contains(after))
        assertFalse(result.contains(missing))
        assertEquals(raw, BookHelp.getContent(book, chapter, allowMultiSource = false))
        assertTrue(MultiSourceManager.details(book, chapter).contains("來源失敗"))
        val requests = server.total.get()
        MultiSourceManager.invalidate(book, chapter)
        server.mode = "normal"
        assertFalse(MultiSourceManager.enrichSafely(primary, book, chapter, raw).contains(missing))
        assertEquals("A failing alternate must respect retry backoff", requests, server.total.get())
    }

    @Test fun cancellationNeverPublishesPartialDerivedCache() = runBlocking {
        val raw = rawContent()
        server.mode = "slow"
        val work = async { MultiSourceManager.enrich(primary, book, chapter, raw) }
        repeat(100) {
            if (server.count("/alternate/chapter/0") == 0) delay(20)
        }
        assertTrue("Fixture must reach the slow content request", server.count("/alternate/chapter/0") > 0)
        work.cancel()
        try { work.await(); fail("Cancellation must propagate") }
        catch (_: CancellationException) { }
        assertNull(MultiSourceManager.cached(book, chapter, raw))
        assertEquals(raw, BookHelp.getContent(book, chapter, allowMultiSource = false))
    }

    @Test fun slowAlternateTimesOutWithoutReplacingOriginal() = runBlocking {
        val raw = rawContent()
        server.mode = "slow"
        val started = System.nanoTime()
        val result = MultiSourceManager.enrichSafely(primary, book, chapter, raw)
        val elapsed = (System.nanoTime() - started) / 1_000_000
        assertTrue("Request must respect the manager's bounded timeout", elapsed < 8_000)
        assertFalse(result.contains(missing))
        assertEquals(raw, BookHelp.getContent(book, chapter, allowMultiSource = false))
        assertTrue(MultiSourceManager.details(book, chapter).contains("同章正文"))
    }

    private fun source(url: String, name: String) = BookSource(
        bookSourceUrl = url, bookSourceName = name, bookSourceGroup = "local-multisource-test",
        customOrder = Int.MIN_VALUE, enabledExplore = false,
        searchUrl = "http://127.0.0.1:${server.listeningPort}/search?key={{key}}",
        ruleSearch = SearchRule(bookList = ".book", name = ".name@text", author = ".author@text", bookUrl = "a@href"),
        ruleBookInfo = BookInfoRule(name = "#name@text", author = "#author@text", tocUrl = "#toc@href"),
        ruleToc = TocRule(chapterList = "a.chapter", chapterName = "text", chapterUrl = "href"),
        ruleContent = ContentRule(content = "#content@html")
    )

    private suspend fun rawContent(target: BookChapter = chapter): String {
        val wasEnabled = MultiSourceManager.enabled
        MultiSourceManager.enabled = false
        return try { WebBook.getContentAwait(primary, book, target) }
        finally { MultiSourceManager.enabled = wasEnabled }
    }

    private fun sha(text: String) = MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    private suspend fun preloadIndex(index: Int): Unit = suspendCoroutineUninterceptedOrReturn { continuation ->
        ReadBook::class.java.getDeclaredMethod("downloadIndex", Int::class.javaPrimitiveType, Continuation::class.java)
            .apply { isAccessible = true }.invoke(ReadBook, index, continuation)
    }

    private inner class FixtureServer : NanoHTTPD("127.0.0.1", 0) {
        lateinit var bookName: String
        @Volatile var mode = "normal"
        val total = AtomicInteger()
        private val requests = ConcurrentHashMap<String, AtomicInteger>()
        fun count(path: String) = requests[path]?.get() ?: 0
        override fun serve(session: IHTTPSession): Response {
            total.incrementAndGet()
            requests.getOrPut(session.uri) { AtomicInteger() }.incrementAndGet()
            if (mode == "offline") return newFixedLengthResponse(Response.Status.SERVICE_UNAVAILABLE, "text/plain", "owned offline fixture")
            if (mode == "primary-slow" && session.uri.startsWith("/primary/chapter/")) Thread.sleep(9_000)
            if (mode in setOf("primary-failure", "cold-rescue", "primary-failure-search-miss") && session.uri.startsWith("/primary/chapter/"))
                return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "owned primary unavailable")
            if (mode == "cold-rescue" && session.uri in setOf("/search", "/alternate/book", "/alternate/toc", "/alternate/chapter/0"))
                Thread.sleep(1_300)
            val html = when (session.uri) {
                "/search" -> if (mode == "primary-failure-search-miss")
                    "<div class='book'><a class='name' href='/unrelated/book'>$bookName</a><span class='author'>另一位寫手</span></div>"
                    else "<div class='book'><a class='name' href='/alternate/book'>$bookName</a><span class='author'>測試寫手甲</span></div>"
                "/blocked-search" -> "<div class='book'><a class='name' href='/blocked/book'>$bookName</a><span class='author'>測試寫手甲</span></div>"
                "/blocked/book" -> "<h1 id='name'>$bookName</h1><span id='author'>測試寫手甲</span><a id='toc' href='/blocked/toc'>目錄</a>"
                "/blocked/toc" -> "<a class='chapter' href='/blocked/chapter/0'>第一章 迴廊</a>"
                "/blocked/chapter/0" -> "<div id='content'>请先登录后阅读</div>"
                "/bad-search" -> "<div class='book'><a class='name' href='/unrelated/book'>$bookName</a><span class='author'>另一位寫手</span></div>"
                "/alternate/book" -> "<h1 id='name'>$bookName</h1><span id='author'>測試寫手甲</span><a id='toc' href='/alternate/toc'>目錄</a>"
                "/alternate/toc" -> "<a class='chapter' href='/alternate/chapter/0'>第一章 迴廊</a>" +
                    "<a class='chapter' href='/alternate/chapter/1'>第二章 高塔</a>"
                "/primary/chapter/0" -> if (mode == "primary-preview")
                    "<div id='content'><p>$before</p><p>本章为VIP章节，请订阅后阅读</p></div>"
                else "<div id='content'><p>$before</p><p>$after</p></div>"
                "/primary/chapter/1" -> "<div id='content'><p>$secondBefore</p><p>$secondAfter</p></div>"
                "/alternate/chapter/1" -> "<div id='content'><p>$secondBefore</p><p>$secondMissing</p><p>$secondAfter</p></div>"
                "/alternate/chapter/0" -> {
                    if (mode == "failure") return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "fixture failure")
                    if (mode == "slow") Thread.sleep(5_500)
                    "<div id='content'><p>$before</p><p>$missing</p><p>$after</p></div>"
                }
                else -> return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "fixture route not found")
            }
            return newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", "<html><meta charset='utf-8'><body>$html</body></html>")
        }
    }
}
