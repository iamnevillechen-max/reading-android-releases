package io.legado.app.multisource

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
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
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.LocalConfig
import io.legado.app.lib.dialogs.SelectItem
import io.legado.app.lib.permission.PermissionActivity
import io.legado.app.lib.permission.Permissions
import io.legado.app.model.ReadAloud
import io.legado.app.model.ReadBook
import io.legado.app.help.source.VerifiedSourceBootstrap
import io.legado.app.model.multisource.MultiSourceManager
import io.legado.app.model.webBook.WebBook
import io.legado.app.service.BaseReadAloudService
import io.legado.app.ui.book.read.ReadBookActivity
import io.legado.app.utils.GSON
import io.legado.app.utils.LogUtils
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord

/**
 * Opt-in real engine test. Run only in the dedicated empty reader test installation:
 * -e class io.legado.app.multisource.ReaderTtsIntegrationTest -e ttsEngine hk.neville.shengjing
 * It does not change the system default engine, the engine app, its models, or other apps.
 */
@RunWith(AndroidJUnit4::class)
class ReaderTtsIntegrationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val instrument = InstrumentationRegistry.getInstrumentation()
    private val before = "早晨，林舟沿著河岸走到小橋，停下腳步看向遠處的山坡。"
    private val missing = "他在橋下找到一封信，於是把信仔細收好。"
    private val after = "午後，小船慢慢靠岸，旅人帶著行李回到熟悉的村莊。"
    private val later = "這是稍後補全的句子，正在朗讀的版本不應突然加入。"

    @Test(timeout = 120_000) fun realReaderKeepsItsSnapshotAndContinuesAcrossChaptersInBackground() = runBlocking {
        val engine = InstrumentationRegistry.getArguments().getString("ttsEngine")
        assumeTrue("An explicit installed engine is required; this is not a mock TTS pass", !engine.isNullOrBlank())
        check(appDb.bookDao.allBookCount == 0 && appDb.bookSourceDao.allEnabledPart.isEmpty()) {
            "Use the dedicated empty reader test installation; never run this fixture on the user's library"
        }
        check(!BaseReadAloudService.isRun) { "Do not replace an existing reading session" }
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(engine!!, 0)
        val oldEnabled = MultiSourceManager.enabled
        val oldLimit = MultiSourceManager.candidateLimit
        val oldEngine = AppConfig.ttsEngine
        val oldFollow = AppConfig.ttsFlowSys
        val oldSpeed = AppConfig.ttsSpeechRate
        val oldPreload = AppConfig.preDownloadNum
        val oldTimer = AppConfig.ttsTimer
        val sourceMigration = context.getSharedPreferences(VerifiedSourceBootstrap.PREFS_NAME, Context.MODE_PRIVATE)
        val hadPackVersion = sourceMigration.contains(VerifiedSourceBootstrap.VERSION_KEY)
        val oldPackVersion = sourceMigration.getInt(VerifiedSourceBootstrap.VERSION_KEY, 0)
        val speechEvents = CopyOnWriteArrayList<String>()
        val logger = LogUtils.logger
        val oldLevel = logger.level
        val eventHandler = object : Handler() {
            override fun publish(record: LogRecord) {
                if (record.message.startsWith("TTS") || record.message.startsWith("AppLog tts")) {
                    speechEvents.add(record.message)
                }
            }
            override fun flush() = Unit
            override fun close() = Unit
        }
        fun starts() = speechEvents.count { it.startsWith("TTSUtteranceListener onStart ") }
        var scenario: ActivityScenario<ReadBookActivity>? = null
        val server = SpeechFixture().also { it.start(1_000, false) }
        val base = "http://127.0.0.1:${server.listeningPort}"
        val name = "朗讀整合驗證${System.nanoTime()}"
        server.bookName = name
        val primary = source("$base/primary", "本機朗讀原源", base, 0)
        val alternate = source("$base/alternate", "本機朗讀補充源", base, 1)
        val selection = GSON.toJson(SelectItem("Explicit integration engine", engine))
        val book = Book(bookUrl = "$base/primary/book", tocUrl = "$base/primary/toc",
            origin = primary.bookSourceUrl, originName = primary.bookSourceName,
            name = name, author = "本機測試寫手", totalChapterNum = 2, canUpdate = false).apply {
            setTtsEngine(selection)
        }
        val chapters = (0..1).map { i ->
            BookChapter(url = "$base/primary/chapter/$i", title = "第${i + 1}章 河岸$i",
                baseUrl = book.bookUrl, bookUrl = book.bookUrl, index = i)
        }
        var primaryFailure: Throwable? = null
        try {
            check(sourceMigration.edit().putInt(VerifiedSourceBootstrap.VERSION_KEY, VerifiedSourceBootstrap.PACK_VERSION).commit())
            logger.level = Level.INFO
            logger.addHandler(eventHandler)
            MultiSourceManager.enabled = true
            MultiSourceManager.candidateLimit = 1
            AppConfig.ttsEngine = selection
            AppConfig.ttsFlowSys = false
            AppConfig.ttsSpeechRate = 15
            AppConfig.preDownloadNum = 0
            AppConfig.ttsTimer = 0
            // Mark only the test installation's first-read help as seen before launching.
            LocalConfig.readHelpVersionIsLast
            if (Build.VERSION.SDK_INT >= 33) {
                instrument.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
            }
            if (Build.VERSION.SDK_INT >= 23) {
                instrument.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.READ_PHONE_STATE)
            }
            appDb.bookSourceDao.insert(primary, alternate)
            appDb.bookDao.insert(book)
            appDb.bookChapterDao.insert(*chapters.toTypedArray())
            for (chapter in chapters) {
                val text = WebBook.getContentAwait(primary, book, chapter)
                assertTrue("The normal reader loading path must reconstruct the missing sentence", text.contains(missing))
                assertFalse(BookHelp.getContent(book, chapter, allowMultiSource = false)!!.contains(missing))
            }
            scenario = ActivityScenario.launch(Intent(context, ReadBookActivity::class.java)
                .putExtra("bookUrl", book.bookUrl).putExtra("inBookshelf", true))
            waitUntil("rendered first reconstructed chapter", 30_000) {
                ReadBook.book?.bookUrl == book.bookUrl && ReadBook.durChapterIndex == 0 &&
                    ReadBook.curTextChapter?.isCompleted == true
            }
            val snapshot = ReadBook.curTextChapter!!
            val spoken = snapshot.getNeedReadAloud(0, false, 0)
            assertTrue("Original ChapterProvider TTS text must include the repaired sentence", spoken.contains(missing))
            assertEquals(1, Regex(Regex.escape(missing)).findAll(spoken).count())
            instrument.runOnMainSync { ReadAloud.upReadAloudClass(); ReadBook.readAloud() }
            waitUntil("real Android TTS engine onStart callback", 45_000) {
                BaseReadAloudService.isPlay() && starts() > 0
            }
            dismissOptionalBatteryRationale()
            // Recompute on disk while playback owns a stable rendered text snapshot.
            server.extra = later
            MultiSourceManager.invalidate(book, chapters[0])
            val raw = BookHelp.getContent(book, chapters[0], allowMultiSource = false)!!
            assertTrue(MultiSourceManager.enrich(primary, book, chapters[0], raw).contains(later))
            if (ReadBook.durChapterIndex == 0) {
                assertSame(snapshot, ReadBook.curTextChapter)
                assertEquals(spoken, snapshot.getNeedReadAloud(0, false, 0))
            } else {
                fail("Fixture finished before its active snapshot could be checked")
            }
            instrument.runOnMainSync { ReadAloud.pause(context) }
            waitUntil("paused", 5_000) { BaseReadAloudService.pause }
            val pausedChapter = ReadBook.durChapterIndex
            val pausedPosition = ReadBook.durChapterPos
            val pausedStarts = starts()
            SystemClock.sleep(800)
            assertEquals(pausedChapter, ReadBook.durChapterIndex)
            assertEquals(pausedPosition, ReadBook.durChapterPos)
            assertEquals("No queued speech starts after pause", pausedStarts, starts())
            instrument.runOnMainSync { ReadAloud.resume(context) }
            waitUntil("resumed", 5_000) { BaseReadAloudService.isPlay() }
            scenario.moveToState(Lifecycle.State.CREATED)
            waitUntil("automatic cross-chapter playback while activity is stopped", 60_000) {
                ReadBook.durChapterIndex == 1 && ReadBook.curTextChapter?.isCompleted == true
            }
            scenario.moveToState(Lifecycle.State.RESUMED)
            assertTrue(ReadBook.curTextChapter!!.getNeedReadAloud(0, false, 0).contains(missing))
            assertTrue("The real engine must finish the first reconstructed chapter",
                speechEvents.any { it.startsWith("TTSUtteranceListener onDone ") })
            assertFalse("Engine errors cannot stand in for successful speech completion",
                speechEvents.any { it.contains("onError ") || it.startsWith("AppLog tts") })
            instrument.runOnMainSync { ReadAloud.stop(context) }
            waitUntil("stopped", 10_000) { !BaseReadAloudService.isRun }
            val stoppedChapter = ReadBook.durChapterIndex
            val stoppedPosition = ReadBook.durChapterPos
            val stoppedStarts = starts()
            SystemClock.sleep(1_000)
            assertEquals(stoppedChapter, ReadBook.durChapterIndex)
            assertEquals(stoppedPosition, ReadBook.durChapterPos)
            assertEquals("No queued speech starts after stop", stoppedStarts, starts())
        } catch (failure: Throwable) {
            primaryFailure = failure
            throw failure
        } finally {
            val cleanupFailures = arrayListOf<Throwable>()
            fun cleanup(label: String, action: () -> Unit) {
                runCatching(action).onFailure { cleanupFailures.add(AssertionError("Cleanup: $label", it)) }
            }
            cleanup("stop reading service") { instrument.runOnMainSync { ReadAloud.stop(context) } }
            cleanup("await reading service stop") { waitUntil("cleanup stopped", 10_000) { !BaseReadAloudService.isRun } }
            cleanup("close activity scenario") { scenario?.close() }
            cleanup("clear owned reader snapshot") {
                ReadBook.clearTextChapter()
                ReadBook.book = null
                ReadBook.bookSource = null
            }
            chapters.forEach { chapter -> cleanup("remove owned chapter cache") { BookHelp.delContent(book, chapter) } }
            cleanup("remove owned chapter records") { appDb.bookChapterDao.delByBook(book.bookUrl) }
            cleanup("remove owned search relations") { appDb.searchBookDao.clear(book.name, book.author) }
            cleanup("remove owned reading record") { appDb.readRecordDao.deleteByName(book.name) }
            cleanup("remove owned book") { appDb.bookDao.delete(book) }
            cleanup("remove owned sources") { appDb.bookSourceDao.delete(primary, alternate) }
            cleanup("restore reader configuration") {
                MultiSourceManager.enabled = oldEnabled
                MultiSourceManager.candidateLimit = oldLimit
                AppConfig.ttsEngine = oldEngine
                AppConfig.ttsFlowSys = oldFollow
                AppConfig.ttsSpeechRate = oldSpeed
                AppConfig.preDownloadNum = oldPreload
                AppConfig.ttsTimer = oldTimer
            }
            cleanup("restore source migration state") {
                check(sourceMigration.edit().apply {
                    if (hadPackVersion) putInt(VerifiedSourceBootstrap.VERSION_KEY, oldPackVersion)
                    else remove(VerifiedSourceBootstrap.VERSION_KEY)
                }.commit())
            }
            cleanup("restore log handler") { logger.removeHandler(eventHandler); logger.level = oldLevel }
            cleanup("stop owned loopback server") { server.stop() }
            val original = primaryFailure
            if (original != null) cleanupFailures.forEach(original::addSuppressed)
            else if (cleanupFailures.isNotEmpty()) {
                throw AssertionError("Reader TTS fixture cleanup failed").also { error ->
                    cleanupFailures.forEach(error::addSuppressed)
                }
            }
        }
    }

    private fun dismissOptionalBatteryRationale() {
        var permissions: Array<String>? = null
        instrument.runOnMainSync {
            val activity = ActivityLifecycleMonitorRegistry.getInstance()
                .getActivitiesInStage(Stage.RESUMED).filterIsInstance<PermissionActivity>().singleOrNull()
            permissions = activity?.intent?.getStringArrayExtra(PermissionActivity.KEY_INPUT_PERMISSIONS)
        }
        permissions?.let {
            check(it.contentEquals(arrayOf(Permissions.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS))) {
                "Unexpected permission rationale in dedicated TTS fixture: ${it.joinToString()}"
            }
            // Exercise the normal 'Cancel' path; do not whitelist the app or change system policy.
            onView(withId(android.R.id.button2)).inRoot(isDialog()).perform(click())
            LogUtils.d("ReaderTtsIntegrationFixture", "Declined optional battery rationale through its Cancel button")
        }
    }

    private fun waitUntil(label: String, timeout: Long, check: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeout
        while (!check()) {
            assertTrue("Timed out waiting for $label", SystemClock.elapsedRealtime() < deadline)
            SystemClock.sleep(50)
        }
    }

    private fun source(url: String, name: String, base: String, order: Int) = BookSource(
        bookSourceUrl = url, bookSourceName = name, enabledExplore = false,
        customOrder = order, searchUrl = "$base/search?key={{key}}",
        ruleSearch = SearchRule(bookList = ".book", name = ".name@text", author = ".author@text", bookUrl = "a@href"),
        ruleBookInfo = BookInfoRule(name = "#name@text", author = "#author@text", tocUrl = "#toc@href"),
        ruleToc = TocRule(chapterList = "a.chapter", chapterName = "text", chapterUrl = "href"),
        ruleContent = ContentRule(content = "#content@html")
    )

    private inner class SpeechFixture : NanoHTTPD("127.0.0.1", 0) {
        lateinit var bookName: String
        @Volatile var extra = ""
        override fun serve(session: IHTTPSession): Response {
            val html = when {
                session.uri == "/search" -> "<div class='book'><a class='name' href='/alternate/book'>$bookName</a><span class='author'>本機測試寫手</span></div>"
                session.uri == "/alternate/book" -> "<h1 id='name'>$bookName</h1><span id='author'>本機測試寫手</span><a id='toc' href='/alternate/toc'>目錄</a>"
                session.uri.endsWith("/toc") -> (0..1).joinToString("") { "<a class='chapter' href='/alternate/chapter/$it'>第${it + 1}章 河岸$it</a>" }
                session.uri.startsWith("/primary/chapter/") -> "<div id='content'><p>$before</p><p>$after</p></div>"
                session.uri.startsWith("/alternate/chapter/") -> "<div id='content'><p>$before</p><p>$missing</p><p>$after</p><p>$extra</p></div>"
                else -> return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "No fixture route")
            }
            return newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", "<html><meta charset='utf-8'><body>$html</body></html>")
        }
    }
}
