package io.legado.app.multisource

import android.content.Context
import android.os.BatteryManager
import android.os.Process
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.JsonParser
import fi.iki.elonen.NanoHTTPD
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
import io.legado.app.help.book.ContentProcessor
import io.legado.app.help.config.AppConfig
import io.legado.app.model.ReadBook
import io.legado.app.model.multisource.MultiSourceManager
import io.legado.app.model.multisource.core.StreamingHash
import io.legado.app.service.BaseReadAloudService
import io.legado.app.ui.book.read.page.provider.ChapterProvider
import io.legado.app.utils.GSON
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Own loopback data only. Exercise the original preload dispatcher, not a second test-only path. */
@RunWith(AndroidJUnit4::class)
class MultiSourceLifecycleTest {
    private lateinit var fixture: PreloadFixture

    @Before fun prepare() {
        fixture = PreloadFixture()
        fixture.prepare()
    }

    @After fun cleanup() = runBlocking {
        if (::fixture.isInitialized) fixture.close()
    }

    @Test fun cachedNextTenChaptersArePreparedWithoutChangingReaderOrStoredProgress() = runBlocking {
        val originalPage = ReadBook.curTextChapter
        val originalNextPage = ReadBook.nextTextChapter
        val originalPreviousPage = ReadBook.prevTextChapter
        // Reproduce the regression: the original cache has already marked these as downloaded.
        ReadBook.downloadedChapters.addAll(fixture.cachedChapters.map { it.index })
        fixture.awaitPreload()
        fixture.assertAllDerived()
        assertEquals("Only the nine future chapters beyond the immediate next chapter are fetched",
            9, fixture.server.contentRequests.get())
        assertSame(originalPage, ReadBook.curTextChapter)
        assertSame(originalNextPage, ReadBook.nextTextChapter)
        assertSame(originalPreviousPage, ReadBook.prevTextChapter)
        fixture.assertStoredReadingDataUnchanged()

        val requests = fixture.server.total.get()
        fixture.awaitPreload()
        assertEquals("Valid disk results must be reused on repeated page-change preloads", requests,
            fixture.server.total.get())
        fixture.assertStoredReadingDataUnchanged()
    }

    @Test fun cancellingCachedPreloadPublishesNoPartialResultAndCanResume() = runBlocking {
        fixture.server.holdContent = true
        val work = fixture.startPreload()
        withTimeout(5_000) {
            while (fixture.server.contentRequests.get() == 0) delay(10)
        }
        work.cancelAndJoin()
        assertNull(MultiSourceManager.cached(fixture.book, fixture.cachedChapters.first(), fixture.raw))
        fixture.assertStoredReadingDataUnchanged()

        fixture.server.holdContent = false
        fixture.server.releaseContent.countDown()
        fixture.awaitPreload()
        fixture.assertAllDerived()
        fixture.assertStoredReadingDataUnchanged()
    }

    @Test fun disconnectedPreloadRetainsEveryReadableOriginalAndReadingRecord() = runBlocking {
        fixture.server.stop()
        fixture.awaitPreload()
        fixture.cachedChapters.forEach { chapter ->
            assertEquals(fixture.raw, BookHelp.getContent(fixture.book, chapter))
            assertEquals(fixture.raw, BookHelp.getContent(fixture.book, chapter, allowMultiSource = false))
        }
        fixture.assertStoredReadingDataUnchanged()
    }

    @Test fun damagedDerivedFileFallsBackToOriginalAndIsRebuiltOnTheNextPreload() = runBlocking {
        fixture.awaitPreload()
        fixture.assertAllDerived()
        val chapter = fixture.cachedChapters.first()
        val file = File(fixture.context.filesDir,
            "multisource-v1/${fixtureSha(fixture.book.bookUrl + "\n" + chapter.url)}.json")
        assertTrue(file.isFile)
        file.writeText("{truncated fixture data")
        assertEquals(fixture.raw, BookHelp.getContent(fixture.book, chapter))
        fixture.assertStoredReadingDataUnchanged()
        fixture.awaitPreload()
        fixture.assertAllDerived()
        fixture.assertStoredReadingDataUnchanged()
    }
}

/** Actual ContentProcessor/ChapterProvider plus the native single-row progress writer. */
@RunWith(AndroidJUnit4::class)
class MultiSourcePositionRestoreTest {
    private lateinit var fixture: PreloadFixture
    private var oldRecordEnabled = true
    private var changedSettings = false

    @Before fun prepare() = runBlocking {
        fixture = PreloadFixture()
        fixture.prepare()
        oldRecordEnabled = AppConfig.enableReadRecord
        changedSettings = true
        AppConfig.enableReadRecord = false
        AppConfig.preDownloadNum = 0
        withContext(Dispatchers.Main) { ChapterProvider.upViewSize(1080, 1920) }
    }

    @After fun cleanup() = runBlocking {
        if (changedSettings) flushProgressWriter()
        if (::fixture.isInitialized) fixture.close()
        if (changedSettings) AppConfig.enableReadRecord = oldRecordEnabled
    }

    @Test fun legacyPositionIsHeldThenExplicitApplyAndReopenKeepTheSameRenderedWords() = runBlocking {
        val chapter = currentChapter()
        val original = render(chapter, fixture.raw)
        val oldPosition = original.indexOf("旅客")
        assertTrue(oldPosition > 0)
        fixture.book.durChapterPos = oldPosition
        appDb.bookDao.update(fixture.book)
        ReadBook.resetData(fixture.book)
        ReadBook.loadContentAwait(0, resetPageOffset = true)
        flushProgressWriter()
        assertEquals("An unmarked nonzero position must not guess its original version", 0,
            fixture.server.contentRequests.get())
        assertEquals(original, ReadBook.curTextChapter!!.getContent())
        assertEquals(oldPosition, ReadBook.durChapterPos)
        assertTrue(ReadBook.isSavedPositionHeld(fixture.book, chapter))
        assertAnchorRow(original, oldPosition)

        // Recompute in the background: the visible chapter stays on its exact previous version.
        val merged = MultiSourceManager.enrich(fixture.primary, fixture.book, chapter, fixture.raw)
        assertTrue(merged.contains(fixture.missing))
        assertEquals(original, ReadBook.curTextChapter!!.getContent())
        val replacement = render(chapter, merged)
        val expected = replacement.indexOf("旅客")
        assertTrue(expected > oldPosition)
        ReadBook.loadContentAwait(0, resetPageOffset = false)
        flushProgressWriter()
        assertEquals(replacement, ReadBook.curTextChapter!!.getContent())
        assertEquals(expected, ReadBook.durChapterPos)
        assertAnchorRow(replacement, expected)

        val persisted = appDb.bookDao.getBook(fixture.book.bookUrl)!!
        val beforeReopenRequests = fixture.server.total.get()
        ReadBook.resetData(persisted)
        // initBook also saves before first layout; that must not erase the saved marker.
        ReadBook.saveRead()
        flushProgressWriter()
        assertAnchorRow(replacement, expected)
        ReadBook.loadContentAwait(0, resetPageOffset = true)
        flushProgressWriter()
        assertEquals(expected, ReadBook.durChapterPos)
        assertEquals(replacement, ReadBook.curTextChapter!!.getContent())
        assertEquals(beforeReopenRequests, fixture.server.total.get())
        assertAnchorRow(replacement, expected)
    }

    @Test fun anOlderUnmarkedDerivedOffsetKeepsItsExistingReadableVersion() = runBlocking {
        val chapter = currentChapter()
        val merged = MultiSourceManager.enrich(fixture.primary, fixture.book, chapter, fixture.raw)
        assertTrue(merged.contains(fixture.missing))
        val rendered = render(chapter, merged)
        val position = rendered.indexOf("旅客")
        fixture.book.durChapterPos = position
        fixture.book.readConfig = fixture.book.readConfig?.copy(multiSourcePositionAnchor = null)
        appDb.bookDao.update(fixture.book)
        val requests = fixture.server.total.get()
        ReadBook.resetData(fixture.book)
        ReadBook.loadContentAwait(0, resetPageOffset = true)
        flushProgressWriter()
        assertEquals(position, ReadBook.durChapterPos)
        assertEquals(rendered, ReadBook.curTextChapter!!.getContent())
        assertEquals(requests, fixture.server.total.get())
        assertAnchorRow(rendered, position)
    }

    @Test fun unrelatedBookmarkCannotBypassLegacyHoldButTheSelectedBookmarkStillWorks() = runBlocking {
        val chapter = currentChapter()
        val original = render(chapter, fixture.raw)
        val position = original.indexOf("旅客") - 24
        assertTrue(position > 0)
        val quote = original.substring(position)
        fixture.book.durChapterPos = position
        appDb.bookDao.update(fixture.book)

        ReadBook.book = Book(bookUrl = "https://owned-fixture.invalid/previous-book")
        ReadBook.setBookmarkAnchor(0, "這是前一本書尚未完成的書籤內容。")
        ReadBook.resetData(fixture.book)
        assertTrue("Reset must discard the previous book's pending bookmark",
            ReadBook.holdUnmarkedInitialPosition(fixture.book, chapter))
        ReadBook.loadContentAwait(0, resetPageOffset = true)
        flushProgressWriter()
        assertEquals(original, ReadBook.curTextChapter!!.getContent())
        assertEquals(0, fixture.server.contentRequests.get())

        fixture.book.readConfig = fixture.book.readConfig?.copy(multiSourcePositionAnchor = null)
        appDb.bookDao.update(fixture.book)
        ReadBook.resetData(fixture.book)
        ReadBook.setBookmarkAnchor(2, quote)
        assertTrue("A bookmark for another chapter must not bypass initial progress protection",
            ReadBook.holdUnmarkedInitialPosition(fixture.book, chapter))
        ReadBook.loadContentAwait(0, resetPageOffset = true)
        flushProgressWriter()
        assertEquals(original, ReadBook.curTextChapter!!.getContent())
        assertEquals(position, ReadBook.durChapterPos)
        assertEquals(0, fixture.server.contentRequests.get())

        fixture.book.readConfig = fixture.book.readConfig?.copy(multiSourcePositionAnchor = null)
        appDb.bookDao.update(fixture.book)
        ReadBook.resetData(fixture.book)
        ReadBook.setBookmarkAnchor(0, quote)
        assertFalse("The explicitly selected current bookmark must still be allowed to resolve",
            ReadBook.holdUnmarkedInitialPosition(fixture.book, chapter))
        ReadBook.loadContentAwait(0, resetPageOffset = true)
        flushProgressWriter()
        val replacement = ReadBook.curTextChapter!!.getContent()
        assertNotEquals(original, replacement)
        assertTrue(fixture.server.contentRequests.get() > 0)
        assertEquals(replacement.indexOf(quote), ReadBook.durChapterPos)
        assertAnchorRow(replacement, ReadBook.durChapterPos)
    }

    @Test fun previousVersionReferenceRequiresMatchingRulesRawHashAndAge() = runBlocking {
        val chapter = currentChapter()
        val merged = MultiSourceManager.enrich(fixture.primary, fixture.book, chapter, fixture.raw)
        assertTrue(merged.contains(fixture.missing))
        val file = File(fixture.context.filesDir,
            "multisource-v1/${fixtureSha(fixture.book.bookUrl + "\n" + chapter.url)}.json")
        val saved = JsonParser.parseString(file.readText()).asJsonObject
        assertEquals(3, saved["version"].asInt)
        for (previousVersion in 1..2) {
            saved.addProperty("version", previousVersion)
            saved.addProperty("configuration", previousVersionFingerprint(previousVersion))
            file.writeText(saved.toString())
            assertNull("Ordinary cache policy must reject earlier merge algorithms",
                MultiSourceManager.cached(fixture.book, chapter, fixture.raw))
            assertEquals(merged, MultiSourceManager.readableForInitialPosition(fixture.book, chapter, fixture.raw))
            assertNull(MultiSourceManager.readableForInitialPosition(fixture.book, chapter, fixture.raw + "changed"))
        }

        val rendered = render(chapter, merged)
        val position = rendered.indexOf("旅客")
        fixture.book.durChapterPos = position
        appDb.bookDao.update(fixture.book)
        val requests = fixture.server.total.get()
        ReadBook.resetData(fixture.book)
        ReadBook.loadContentAwait(0, resetPageOffset = true)
        flushProgressWriter()
        assertEquals(position, ReadBook.durChapterPos)
        assertEquals(rendered, ReadBook.curTextChapter!!.getContent())
        assertEquals(requests, fixture.server.total.get())
        assertAnchorRow(rendered, position)

        val created = saved["created"].asLong
        saved.addProperty("created", System.currentTimeMillis() - 24 * 60 * 60 * 1000L - 1)
        file.writeText(saved.toString())
        assertNull(MultiSourceManager.readableForInitialPosition(fixture.book, chapter, fixture.raw))
        saved.addProperty("created", created)
        file.writeText(saved.toString())
        fixture.alternate.enabled = false
        appDb.bookSourceDao.update(fixture.alternate)
        assertNull("Disabling a source must also invalidate the migration-only reference",
            MultiSourceManager.readableForInitialPosition(fixture.book, chapter, fixture.raw))
        fixture.alternate.enabled = true
        fixture.alternate.ruleContent = ContentRule(content = "#different-rule@text")
        appDb.bookSourceDao.update(fixture.alternate)
        assertNull("Editing rules must not resurrect an old reference",
            MultiSourceManager.readableForInitialPosition(fixture.book, chapter, fixture.raw))
    }

    private fun currentChapter(): BookChapter {
        val chapter = appDb.bookChapterDao.getChapter(fixture.book.bookUrl, 0)!!
        BookHelp.saveText(fixture.book, chapter, fixture.raw)
        fixture.cachedChapters = fixture.cachedChapters + chapter
        return chapter
    }

    private suspend fun render(chapter: BookChapter, content: String): String = coroutineScope {
        val processor = ContentProcessor.get(fixture.book.name, fixture.book.origin)
        val title = chapter.getDisplayTitle(processor.getTitleReplaceRules(), fixture.book.getUseReplaceRule())
        val rendered = ChapterProvider.getTextChapterAsync(this, fixture.book, chapter, title,
            processor.getContent(fixture.book, chapter, content, includeTitle = false), 12)
        for (ignored in rendered.layoutChannel) { }
        while (!rendered.isCompleted) delay(1)
        rendered.getContent()
    }

    private fun assertAnchorRow(content: String, position: Int) {
        val persisted = appDb.bookDao.getBook(fixture.book.bookUrl)!!
        assertEquals(position, persisted.durChapterPos)
        val anchor = JsonParser.parseString(persisted.readConfig!!.multiSourcePositionAnchor!!).asJsonObject
        assertEquals(persisted.durChapterPos, anchor["position"].asInt)
        assertEquals(StreamingHash.sha256 { it.write(content) }, anchor["renderedHash"].asString)
        assertEquals(listOf(fixture.bookmark), appDb.bookmarkDao.getByBook(fixture.book.name, fixture.book.author))
        assertEquals(listOf(fixture.record), appDb.readRecordDao.all.filter { it.bookName == fixture.book.name })
    }

    private fun flushProgressWriter() {
        ReadBook.executor.submit { }.get(10, TimeUnit.SECONDS)
    }

    /** Archived field framing, independently built only over two owned fixture sources. */
    private fun previousVersionFingerprint(version: Int): String {
        val framed = StringBuilder()
        fun field(value: String?) {
            if (value == null) framed.append("-1:")
            else framed.append(value.length).append(':').append(value)
        }
        field("database-config-v2:$version:1")
        appDb.openHelper.readableDatabase.query(
            "select * from book_sources where enabled = 1 and bookSourceType = 0 order by customOrder, bookSourceUrl"
        ).use { cursor ->
            cursor.columnNames.forEach(::field)
            while (cursor.moveToNext()) {
                framed.append("row:")
                for (column in 0 until cursor.columnCount) {
                    field(if (cursor.isNull(column)) null else cursor.getString(column))
                }
            }
        }
        return fixtureSha(framed.toString())
    }
}

/**
 * Explicit two-process acceptance. Run only this class with lifecycleStage=prepare, stop the target
 * process, then run lifecycleStage=verify. The default suite skips it rather than pretending an
 * in-process reread establishes process recreation. A cleanup stage removes only its own fixture.
 */
@RunWith(AndroidJUnit4::class)
class MultiSourceProcessRestartTest {
    @Test fun derivedCacheSurvivesARealTargetProcessRestart() = runBlocking {
        val stage = InstrumentationRegistry.getArguments().getString("lifecycleStage")
        assumeTrue("Requires explicit prepare/verify process stages",
            stage in setOf("prepare", "verify", "cleanup"))
        val context = ApplicationProvider.getApplicationContext<Context>()
        val marker = File(context.filesDir, "multisource-process-restart-fixture.json")
        when (stage) {
            "prepare" -> {
                check(!marker.exists()) { "A previous staged fixture exists; run lifecycleStage=cleanup first" }
                val fixture = PreloadFixture()
                var saved = false
                try {
                    fixture.prepare()
                    fixture.awaitPreload()
                    fixture.assertAllDerived()
                    fixture.assertStoredReadingDataUnchanged()
                    val state = RestartState(fixture.book, fixture.cachedChapters,
                        listOf(fixture.primary, fixture.alternate), fixture.bookmark, fixture.record,
                        fixture.raw, fixture.missing, Process.myPid(), fixture.oldEnabled,
                        fixture.oldLimit, fixture.oldPreload, fixture.previousPackVersion)
                    marker.writeText(GSON.toJson(state))
                    check(context.getSharedPreferences("multisource", Context.MODE_PRIVATE)
                        .edit().putBoolean("enabled", true).putInt("candidates", 1).commit())
                    saved = true
                } finally {
                    fixture.close(deleteData = !saved, restoreSettings = !saved)
                }
            }
            "verify", "cleanup" -> {
                check(marker.isFile) { "Run lifecycleStage=prepare in a separate instrumentation process first" }
                val state = GSON.fromJson(marker.readText(), RestartState::class.java)
                try {
                    if (stage == "verify") {
                        assertNotEquals("This must be a new target process", state.preparedPid, Process.myPid())
                        val persistedBook = appDb.bookDao.getBook(state.book.bookUrl)!!
                        state.chapters.forEach { chapter ->
                            assertEquals(state.raw, BookHelp.getContent(persistedBook, chapter, allowMultiSource = false))
                            val cached = MultiSourceManager.cached(persistedBook, chapter, state.raw)
                            assertNotNull("The original loopback server has already stopped; disk reuse must survive", cached)
                            assertTrue(cached!!.contains(state.missing))
                            assertEquals(cached, BookHelp.getContent(persistedBook, chapter))
                        }
                        assertReadingState(state.book, state.bookmark, state.record)
                    }
                } finally {
                    removeFixture(context, state.book, state.chapters, state.sources, state.bookmark, state.record)
                    MultiSourceManager.enabled = state.previousEnabled
                    MultiSourceManager.candidateLimit = state.previousLimit
                    AppConfig.preDownloadNum = state.previousPreload
                    LoopbackSourcePackGuard.restore(context, state.previousPackVersion)
                    marker.delete()
                }
            }
            else -> error("Unknown lifecycle stage")
        }
    }
}

private data class RestartState(
    val book: Book, val chapters: List<BookChapter>, val sources: List<BookSource>,
    val bookmark: Bookmark, val record: ReadRecord, val raw: String, val missing: String,
    val preparedPid: Int, val previousEnabled: Boolean, val previousLimit: Int,
    val previousPreload: Int, val previousPackVersion: Int?
)

private class PreloadFixture {
    val context = ApplicationProvider.getApplicationContext<Context>()
    private val sourcePack = LoopbackSourcePackGuard(context)
    val previousPackVersion get() = sourcePack.previousVersion
    val before = "林舟沿著驛站旁的石路走到河邊，清晨的雨水還留在木橋上，遠處的守夜人正仔細整理昨晚收到的信件。"
    val missing = "他從布袋裡取出一枚銅色鑰匙，交給等候多時的朋友，兩人約好在鐘樓響起第三聲時一同打開舊倉庫。"
    val after = "陽光慢慢穿過雲層照亮高牆，街上的商販收好門前的遮雨布，將新烤好的麵包分給準備遠行的旅客。"
    val raw = "$before\n\n$after"
    val server = PreloadServer(before, missing, after)
    lateinit var primary: BookSource
    lateinit var alternate: BookSource
    lateinit var book: Book
    lateinit var bookmark: Bookmark
    lateinit var record: ReadRecord
    var cachedChapters = emptyList<BookChapter>()
    var oldEnabled = true
    var oldLimit = 3
    var oldPreload = 10
    private var ownsData = false
    private var oldBook: Book? = null
    private var oldSource: BookSource? = null
    private var oldSize = 0
    private var oldIndex = 0
    private var oldPosition = 0
    private var oldTask: Job? = null
    private var oldDownloaded = emptySet<Int>()

    fun prepare() {
        check(appDb.bookSourceDao.allPart.isEmpty()) {
            "Dedicated empty test installation required; existing sources must never be changed"
        }
        check(ReadBook.preDownloadTask?.isActive != true) { "A reader preload is already active" }
        check(ReadBook.book == null && ReadBook.callBack == null && !BaseReadAloudService.isRun) {
            "Run only in an idle dedicated test installation; an active reader must never be replaced"
        }
        val battery = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        assumeTrue("Low-battery background downgrade is intentional",
            battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) !in 1..15)
        sourcePack.mark()
        oldEnabled = MultiSourceManager.enabled
        oldLimit = MultiSourceManager.candidateLimit
        oldPreload = AppConfig.preDownloadNum
        oldBook = ReadBook.book
        oldSource = ReadBook.bookSource
        oldSize = ReadBook.chapterSize
        oldIndex = ReadBook.durChapterIndex
        oldPosition = ReadBook.durChapterPos
        oldTask = ReadBook.preDownloadTask
        oldDownloaded = ReadBook.downloadedChapters.toSet()
        server.start(1_000, false)
        server.bookName = "多源預讀驗證${System.nanoTime()}"
        val base = "http://127.0.0.1:${server.listeningPort}"
        primary = source("$base/primary", "預讀原源")
        alternate = source("$base/alternate", "預讀補充源")
        book = Book(bookUrl = "$base/primary/book", tocUrl = "$base/primary/toc",
            origin = primary.bookSourceUrl, originName = primary.bookSourceName,
            name = server.bookName, author = "本機測試寫手乙", canUpdate = false,
            totalChapterNum = 12, durChapterTitle = preloadTitle(0), durChapterIndex = 0,
            durChapterPos = 17, durChapterTime = 1_700_000_000_000L)
        val chapters = (0..11).map { index ->
            BookChapter(url = "$base/primary/chapter/$index", title = preloadTitle(index),
                baseUrl = book.bookUrl, bookUrl = book.bookUrl, index = index)
        }
        cachedChapters = chapters.filter { it.index in 2..10 }
        bookmark = Bookmark(time = System.nanoTime(), bookName = book.name, bookAuthor = book.author,
            chapterIndex = 2, chapterPos = 23, chapterName = chapters[2].title,
            bookText = before.take(24), content = "本機測試書籤")
        record = ReadRecord(deviceId = "fixture-device", bookName = book.name,
            readTime = 123_456L, lastRead = book.durChapterTime)
        ownsData = true
        appDb.bookSourceDao.insert(primary, alternate)
        appDb.bookDao.insert(book)
        appDb.bookChapterDao.insert(*chapters.toTypedArray())
        appDb.bookmarkDao.insert(bookmark)
        appDb.readRecordDao.insert(record)
        MultiSourceManager.enabled = true
        MultiSourceManager.candidateLimit = 1
        AppConfig.preDownloadNum = 10
        cachedChapters.forEach { BookHelp.saveText(book, it, raw) }
        ReadBook.book = book
        ReadBook.bookSource = primary
        ReadBook.chapterSize = 12
        ReadBook.durChapterIndex = 0
        ReadBook.durChapterPos = 17
        ReadBook.downloadedChapters.clear()
    }

    suspend fun startPreload(): Job {
        val previous = ReadBook.preDownloadTask
        ReadBook::class.java.getDeclaredMethod("preDownload").apply { isAccessible = true }.invoke(ReadBook)
        return withTimeout(5_000) {
            while (ReadBook.preDownloadTask == previous) delay(10)
            ReadBook.preDownloadTask!!
        }
    }

    suspend fun awaitPreload() = withTimeout(30_000) { startPreload().join() }

    fun assertAllDerived() {
        cachedChapters.forEach { chapter ->
            assertEquals(raw, BookHelp.getContent(book, chapter, allowMultiSource = false))
            assertTrue("Already cached chapter ${chapter.index} must have a prepared derived result; " +
                "requests=${server.total.get()}, content=${server.contentRequests.get()}; " +
                "sourceOrder=${appDb.bookSourceDao.getBookSource(primary.bookSourceUrl)?.customOrder}/" +
                "${appDb.bookSourceDao.getBookSource(alternate.bookSourceUrl)?.customOrder}; " +
                MultiSourceManager.details(book, chapter),
                MultiSourceManager.cached(book, chapter, raw)?.contains(missing) == true)
        }
    }

    fun assertStoredReadingDataUnchanged() {
        assertEquals(0, ReadBook.durChapterIndex)
        assertEquals(17, ReadBook.durChapterPos)
        assertReadingState(book, bookmark, record)
    }

    suspend fun close(deleteData: Boolean = true, restoreSettings: Boolean = true) {
        try {
            server.releaseContent.countDown()
            if (!ownsData) {
                server.stop()
                return
            }
            ReadBook.preDownloadTask?.takeIf { it !== oldTask }?.cancelAndJoin()
            ReadBook.setBookmarkAnchor(0, null)
            ReadBook.curTextChapter?.cancelLayout()
            ReadBook.nextTextChapter?.cancelLayout()
            ReadBook.prevTextChapter?.cancelLayout()
            ReadBook.curTextChapter = null
            ReadBook.nextTextChapter = null
            ReadBook.prevTextChapter = null
            server.stop()
            if (ownsData) {
                if (deleteData) removeFixture(context, book, cachedChapters, listOf(primary, alternate), bookmark, record)
                ReadBook.book = oldBook
                ReadBook.bookSource = oldSource
                ReadBook.chapterSize = oldSize
                ReadBook.durChapterIndex = oldIndex
                ReadBook.durChapterPos = oldPosition
                ReadBook.preDownloadTask = oldTask
                ReadBook.downloadedChapters.clear()
                ReadBook.downloadedChapters.addAll(oldDownloaded)
                if (restoreSettings) {
                    MultiSourceManager.enabled = oldEnabled
                    MultiSourceManager.candidateLimit = oldLimit
                    AppConfig.preDownloadNum = oldPreload
                }
                ownsData = false
            }
        } finally { if (restoreSettings) sourcePack.restore() }
    }

    private fun source(url: String, name: String) = BookSource(
        bookSourceUrl = url, bookSourceName = name, bookSourceGroup = "local-preload-test",
        // Realistic stable orders must not trigger App.onCreate's asynchronous order repair.
        customOrder = if (url.endsWith("/primary")) 0 else 1, enabledExplore = false,
        searchUrl = "http://127.0.0.1:${server.listeningPort}/search?key={{key}}",
        ruleSearch = SearchRule(bookList = ".book", name = ".name@text", author = ".author@text", bookUrl = "a@href"),
        ruleBookInfo = BookInfoRule(name = "#name@text", author = "#author@text", tocUrl = "#toc@href"),
        ruleToc = TocRule(chapterList = "a.chapter", chapterName = "text", chapterUrl = "href"),
        ruleContent = ContentRule(content = "#content@html")
    )
}

private fun assertReadingState(book: Book, bookmark: Bookmark, record: ReadRecord) {
    val persisted = appDb.bookDao.getBook(book.bookUrl)!!
    assertEquals(book.durChapterIndex, persisted.durChapterIndex)
    assertEquals(book.durChapterPos, persisted.durChapterPos)
    assertEquals(book.durChapterTime, persisted.durChapterTime)
    assertEquals(book.durChapterTitle, persisted.durChapterTitle)
    assertEquals(listOf(bookmark), appDb.bookmarkDao.getByBook(book.name, book.author))
    assertEquals(listOf(record), appDb.readRecordDao.all.filter { it.bookName == book.name })
}

private fun removeFixture(context: Context, book: Book, chapters: List<BookChapter>,
    sources: List<BookSource>, bookmark: Bookmark, record: ReadRecord) {
    chapters.forEach { BookHelp.delContent(book, it) }
    appDb.bookmarkDao.delete(bookmark)
    appDb.readRecordDao.delete(record)
    appDb.searchBookDao.clear(book.name, book.author)
    appDb.bookChapterDao.delByBook(book.bookUrl)
    appDb.bookDao.delete(book)
    appDb.bookSourceDao.delete(*sources.toTypedArray())
    MultiSourceManager.clearCandidateHistory(book)
    val prefs = context.getSharedPreferences("multisource", Context.MODE_PRIVATE)
    sources.forEach { source ->
        val key = fixtureSha(book.bookUrl + source.bookSourceUrl)
        prefs.edit().remove("retry:$key").apply()
        File(context.filesDir, "multisource-v1/relation-$key.json").delete()
    }
}

private fun fixtureSha(value: String) = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

private val preloadTitles = listOf("河岸", "晨霧", "驛站", "銅鑰", "鐘樓", "木橋",
    "信件", "麵包", "石路", "高牆", "旅客", "遠帆")
private fun preloadTitle(index: Int) = "第${index + 1}章 ${preloadTitles[index]}"

private class PreloadServer(val before: String, val missing: String, val after: String) :
    NanoHTTPD("127.0.0.1", 0) {
    lateinit var bookName: String
    val total = AtomicInteger()
    val contentRequests = AtomicInteger()
    @Volatile var holdContent = false
    val releaseContent = CountDownLatch(1)

    override fun serve(session: IHTTPSession): Response {
        total.incrementAndGet()
        val body = when {
            session.uri == "/search" -> "<div class='book'><a class='name' href='/alternate/book'>$bookName</a><span class='author'>本機測試寫手乙</span></div>"
            session.uri == "/alternate/book" -> "<h1 id='name'>$bookName</h1><span id='author'>本機測試寫手乙</span><a id='toc' href='/alternate/toc'>目錄</a>"
            session.uri == "/alternate/toc" -> (0..11).joinToString("") {
                "<a class='chapter' href='/alternate/chapter/$it'>${preloadTitle(it)}</a>"
            }
            session.uri.startsWith("/alternate/chapter/") -> {
                contentRequests.incrementAndGet()
                if (holdContent) releaseContent.await(2, TimeUnit.SECONDS)
                "<div id='content'><p>$before</p><p>$missing</p><p>$after</p></div>"
            }
            else -> return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "owned fixture not found")
        }
        val bytes = "<html><meta charset='utf-8'><body>$body</body></html>".toByteArray(Charsets.UTF_8)
        // Deliver small chunks with pauses, including splits within UTF-8 characters.
        val stream = object : InputStream() {
            var position = 0
            override fun read(): Int = if (position < bytes.size) bytes[position++].toInt() and 255 else -1
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (position >= bytes.size) return -1
                Thread.sleep(5)
                val count = minOf(37, length, bytes.size - position)
                bytes.copyInto(buffer, offset, position, position + count)
                position += count
                return count
            }
        }
        return newChunkedResponse(Response.Status.OK, "text/html; charset=utf-8", stream)
    }
}
