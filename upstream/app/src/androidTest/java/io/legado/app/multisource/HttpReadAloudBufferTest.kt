package io.legado.app.multisource

import android.Manifest
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RectF
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.os.SystemClock
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.Observer
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import fi.iki.elonen.NanoHTTPD
import io.legado.app.constant.EventBus
import io.legado.app.constant.IntentAction
import io.legado.app.constant.PreferKey
import io.legado.app.constant.Status
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.HttpTTS
import io.legado.app.help.book.BookHelp
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.LocalConfig
import io.legado.app.lib.permission.PermissionActivity
import io.legado.app.lib.permission.Permissions
import io.legado.app.model.ReadAloud
import io.legado.app.model.ReadBook
import io.legado.app.model.multisource.MultiSourceManager
import io.legado.app.service.BaseReadAloudService
import io.legado.app.service.HttpReadAloudService
import io.legado.app.ui.book.read.ReadBookActivity
import io.legado.app.ui.book.read.page.ContentTextView
import io.legado.app.ui.book.read.page.provider.ChapterProvider
import io.legado.app.utils.defaultSharedPreferences
import io.legado.app.utils.eventObservable
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Real foreground service, reader layout, and Media3 playback with only self-written local data. */
@RunWith(AndroidJUnit4::class)
class HttpReadAloudBufferTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val instrument = InstrumentationRegistry.getInstrumentation()

    @Test(timeout = 55_000)
    fun delayedAudioKeepsParagraphOrderAndHonorsPauseWhileRefilling() = runBlocking {
        check(Build.HARDWARE in setOf("ranchu", "goldfish") && ReadBook.book == null)
        check(appDb.bookDao.allBookCount == 0 && appDb.bookSourceDao.allCount() == 0 &&
            !BaseReadAloudService.isRun)
        val releaseSecond = CountDownLatch(1)
        val releaseThird = CountDownLatch(1)
        val requests = AtomicInteger()
        val requestedText = CopyOnWriteArrayList<String>()
        val wav = wave()
        val server = object : NanoHTTPD("127.0.0.1", 0) {
            override fun serve(session: IHTTPSession): Response {
                if (session.uri != "/speak") {
                    return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Owned fixture")
                }
                requestedText.add(session.parameters["q"]?.firstOrNull().orEmpty())
                val request = requests.incrementAndGet()
                val gate = when (request) {
                    2 -> releaseSecond
                    3 -> releaseThird
                    else -> null
                }
                if (gate != null && !gate.await(12, TimeUnit.SECONDS)) {
                    return newFixedLengthResponse(Response.Status.SERVICE_UNAVAILABLE, "text/plain", "Owned timeout")
                }
                if (request == 3) {
                    return newFixedLengthResponse(Response.Status.SERVICE_UNAVAILABLE, "text/plain", "Owned transient failure")
                }
                return newFixedLengthResponse(Response.Status.OK, "audio/wav", wav.inputStream(), wav.size.toLong())
            }
        }.also { it.start(1_000, false) }
        val base = "http://127.0.0.1:${server.listeningPort}"
        val source = BookSource(bookSourceUrl = base, bookSourceName = "Owned narration", enabled = true)
        val engine = HttpTTS(name = "Owned local audio fixture", url = "$base/speak?q={{java.encodeURI(speakText)}}",
            contentType = "audio/wav")
        val book = Book(bookUrl = "$base/book", tocUrl = "$base/toc", origin = base, originName = source.bookSourceName,
            name = "自寫朗讀緩衝驗證", author = "測試作者", totalChapterNum = 2, canUpdate = false).apply {
            setTtsEngine(engine.id.toString())
        }
        val chapters = (0..1).map { i ->
            BookChapter(url = "$base/chapter/$i", bookUrl = book.bookUrl, title = "第${i + 1}章 自寫段落", index = i)
        }
        val oldEnabled = MultiSourceManager.enabled
        val oldPreload = AppConfig.preDownloadNum
        val oldTimer = AppConfig.ttsTimer
        val prefs = context.defaultSharedPreferences
        val previousStream = prefs.all[PreferKey.streamReadAloudAudio] as? Boolean
        val previousPageSplit = prefs.all[PreferKey.readAloudByPage] as? Boolean
        val progress = CopyOnWriteArrayList<Pair<Int, Int>>()
        val observable = eventObservable<Int>(EventBus.TTS_PROGRESS)
        val observer = Observer<Int> { progress.add(ReadBook.durChapterIndex to it) }
        val guard = LoopbackSourcePackGuard(context)
        var reader: ReadBookActivity? = null
        try {
            guard.mark()
            grantNotifications()
            MultiSourceManager.enabled = false
            AppConfig.preDownloadNum = 0
            AppConfig.ttsTimer = 0
            prefs.edit().putBoolean(PreferKey.streamReadAloudAudio, false)
                .putBoolean(PreferKey.readAloudByPage, false).commit()
            LocalConfig.readHelpVersionIsLast
            appDb.httpTTSDao.insert(engine)
            appDb.bookSourceDao.insert(source)
            appDb.bookDao.insert(book)
            appDb.bookChapterDao.insert(*chapters.toTypedArray())
            BookHelp.saveText(book, chapters[0], "旅客沿河走向木橋。\n守橋人打開藍色的信封。\n她把地圖交給等待的人。\n傍晚他們抵達遠方的小鎮。")
            BookHelp.saveText(book, chapters[1], "第二天清晨，城門又打開了。")
            reader = launchReader(book)
            waitUntil { ReadBook.curTextChapter?.isCompleted == true && ReadBook.nextTextChapter?.isCompleted == true }
            val paragraphs = ReadBook.curTextChapter!!.getNeedReadAloud(0, false, 0).split('\n').filter { it.isNotEmpty() }
            val starts = paragraphs.runningFold(1) { position, paragraph -> position + paragraph.length + 1 }.dropLast(1)
            assertTrue(starts.size >= 4)
            instrument.runOnMainSync {
                observable.observeForever(observer)
                ReadAloud.upReadAloudClass()
                ReadBook.readAloud()
            }
            waitUntil { requests.get() >= 2 }
            delay(300)
            assertTrue("A short delay in the second audio should be absorbed before starting", progress.isEmpty())
            releaseSecond.countDown()
            waitUntil { requests.get() >= 3 && progress.contains(0 to starts[1]) }
            delay(850)
            assertEquals("Buffer depletion must not advance the chapter", 0, ReadBook.durChapterIndex)
            instrument.runOnMainSync { ReadAloud.pause(context) }
            waitUntil { BaseReadAloudService.pause }
            val before = progress.toList()
            releaseThird.countDown()
            delay(650)
            assertEquals("Incoming audio must not restart paused narration", before, progress.toList())
            instrument.runOnMainSync { ReadAloud.resume(context) }
            waitUntil { progress.any { it.first == 1 } }
            assertTrue("The fixture must request real paragraphs", requestedText[2].isNotBlank())
            assertEquals("A transient response must retry the same paragraph", requestedText[2], requestedText[3])
            assertEquals("All original paragraphs must play once in order", starts,
                progress.filter { it.first == 0 }.map { it.second }.distinct())
        } finally {
            releaseSecond.countDown()
            releaseThird.countDown()
            instrument.runOnMainSync {
                observable.removeObserver(observer)
                ReadAloud.stop(context)
            }
            waitUntil { !BaseReadAloudService.isRun }
            finishReader(reader)
            chapters.forEach { BookHelp.delContent(book, it) }
            appDb.bookDao.delete(book)
            appDb.bookSourceDao.delete(source)
            appDb.httpTTSDao.delete(engine)
            ReadBook.clearTextChapter()
            ReadBook.book = null
            MultiSourceManager.enabled = oldEnabled
            AppConfig.preDownloadNum = oldPreload
            AppConfig.ttsTimer = oldTimer
            prefs.edit().apply {
                if (previousStream == null) remove(PreferKey.streamReadAloudAudio)
                else putBoolean(PreferKey.streamReadAloudAudio, previousStream)
                if (previousPageSplit == null) remove(PreferKey.readAloudByPage)
                else putBoolean(PreferKey.readAloudByPage, previousPageSplit)
            }.commit()
            guard.restore()
            server.stop()
        }
    }

    private suspend fun waitUntil(condition: () -> Boolean) {
        withTimeout(12_000) { while (!condition()) delay(20) }
    }

    @Test(timeout = 90_000)
    fun lockWakeAndRecreateFollowThePlayingChapter() {
        check(Build.HARDWARE in setOf("ranchu", "goldfish") && ReadBook.book == null &&
            appDb.bookDao.allBookCount == 0 && appDb.bookSourceDao.allCount() == 0 &&
            !BaseReadAloudService.isRun)
        val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val allowThirdParagraph = CountDownLatch(1)
        val requests = AtomicInteger()
        val wav = wave()
        val server = object : NanoHTTPD("127.0.0.1", 0) {
            override fun serve(session: IHTTPSession): Response {
                if (session.uri != "/speak") {
                    return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Owned fixture")
                }
                if (requests.incrementAndGet() == 3 && !allowThirdParagraph.await(45, TimeUnit.SECONDS)) {
                    return newFixedLengthResponse(Response.Status.SERVICE_UNAVAILABLE, "text/plain", "Fixture timed out")
                }
                return newFixedLengthResponse(Response.Status.OK, "audio/wav", wav.inputStream(), wav.size.toLong())
            }
        }.also { it.start(1_000, false) }
        val base = "http://127.0.0.1:${server.listeningPort}"
        val source = BookSource(bookSourceUrl = base, bookSourceName = "Owned screen lifecycle")
        val engine = HttpTTS(name = "Owned screen audio", url = "$base/speak?q={{java.encodeURI(speakText)}}",
            contentType = "audio/wav")
        val book = Book(bookUrl = "$base/book", tocUrl = "$base/toc", origin = base,
            originName = source.bookSourceName, name = "锁屏朗读测试", author = "测试", totalChapterNum = 2,
            canUpdate = false).apply { setTtsEngine(engine.id.toString()) }
        val chapters = (0..1).map { index ->
            BookChapter(url = "$base/chapter/$index", bookUrl = book.bookUrl, index = index, title = "第${index + 1}章")
        }
        val prefs = context.defaultSharedPreferences
        val stream = prefs.all[PreferKey.streamReadAloudAudio] as? Boolean
        val oldTimer = AppConfig.ttsTimer
        val oldEnabled = MultiSourceManager.enabled
        val guard = LoopbackSourcePackGuard(context)
        var failure: Throwable? = null
        try {
            guard.mark()
            LocalConfig.readHelpVersionIsLast
            AppConfig.ttsTimer = 0
            MultiSourceManager.enabled = false
            prefs.edit().putBoolean(PreferKey.streamReadAloudAudio, false).commit()
            grantNotifications()
            appDb.httpTTSDao.insert(engine)
            appDb.bookSourceDao.insert(source)
            appDb.bookDao.insert(book)
            appDb.bookChapterDao.insert(*chapters.toTypedArray())
            BookHelp.saveText(book, chapters[0], "清晨，旅客沿着石板路走进花园。\n守门人把钥匙交给她。\n她用钥匙打开树下的小木箱。\n木箱里放着一本旧日记。")
            BookHelp.saveText(book, chapters[1], (1..48).joinToString("\n") { "第${it}棵树下有一张手绘地图。" })
            val reader = launchReader(book)
            awaitStage("both fixture chapters laid out") {
                ReadBook.curTextChapter?.isCompleted == true && ReadBook.nextTextChapter?.isCompleted == true
            }
            val epoch = ReadBook.readingEpoch
            instrument.runOnMainSync {
                ReadAloud.upReadAloudClass()
                ReadBook.readAloud()
            }
            awaitStage("first chapter playing in the foreground") {
                dismissOptionalBatteryPrompt()
                requests.get() >= 3 && BaseReadAloudService.isPlay() && ReadBook.durChapterPos > 0 &&
                    onMain { reader.lifecycle.currentState == Lifecycle.State.RESUMED && reader.hasWindowFocus() }
            }
            shell("input keyevent KEYCODE_SLEEP")
            awaitStage("display asleep and reader paused") {
                !power.isInteractive && onMain { !reader.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) }
            }
            assertEquals("The held paragraph must keep us in chapter one until the screen is off", 0, ReadBook.durChapterIndex)
            allowThirdParagraph.countDown()
            awaitStage("service entered chapter two with the screen still off") {
                !power.isInteractive && BaseReadAloudService.isPlay() &&
                    ReadBook.durChapterIndex == 1 && ReadBook.durChapterPos > 3
            }
            val lockedPosition = ReadBook.durChapterPos
            wakeDevice()
            awaitStage("display and reader resumed") {
                power.isInteractive && onMain { reader.lifecycle.currentState == Lifecycle.State.RESUMED && reader.hasWindowFocus() }
            }
            onMain { reader.recreate() }
            awaitStage("new reader attached to chapter two") {
                onMain {
                    val restored = ReadBook.callBack as? ReadBookActivity
                    restored != null && restored !== reader && restored.isInitFinish &&
                        restored.lifecycle.currentState == Lifecycle.State.RESUMED
                }
            }
            val restoredPosition = ReadBook.durChapterPos
            assertTrue("Reattaching must retain locked-screen progress", restoredPosition >= lockedPosition)
            awaitStage("playback advanced after the recreated reader resumed") { ReadBook.durChapterPos > restoredPosition }
            assertEquals(book.bookUrl, ReadBook.book?.bookUrl)
            assertEquals(1, ReadBook.durChapterIndex)
            assertEquals("Reattaching the window must keep the active chapter-loading session", epoch, ReadBook.readingEpoch)
            assertTrue(BaseReadAloudService.isPlay())
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            val cleanupFailures = mutableListOf<Throwable>()
            fun clean(action: () -> Unit) {
                runCatching(action).onFailure { cleanupFailures.add(it) }
            }
            allowThirdParagraph.countDown()
            clean { if (!power.isInteractive) wakeDevice() }
            clean {
                context.stopService(Intent(context, HttpReadAloudService::class.java))
                finishReader(onMain { ReadBook.callBack as? ReadBookActivity })
                awaitStage("fixture service and reader closed") { !BaseReadAloudService.isRun && ReadBook.callBack == null }
                ReadBook.executor.submit { }.get(5, TimeUnit.SECONDS)
            }
            clean { chapters.forEach { BookHelp.delContent(book, it) } }
            clean { appDb.bookDao.delete(book) }
            clean { appDb.bookSourceDao.delete(source) }
            clean { appDb.httpTTSDao.delete(engine) }
            clean { appDb.readRecordDao.deleteByName(book.name) }
            clean { ReadBook.clearTextChapter(); ReadBook.book = null; ReadBook.bookSource = null }
            clean { AppConfig.ttsTimer = oldTimer; MultiSourceManager.enabled = oldEnabled }
            clean {
                prefs.edit().apply {
                    if (stream == null) remove(PreferKey.streamReadAloudAudio)
                    else putBoolean(PreferKey.streamReadAloudAudio, stream)
                }.commit()
            }
            clean(guard::restore)
            clean(server::stop)
            if (cleanupFailures.isNotEmpty()) {
                val reported = failure ?: AssertionError("Lock/wake fixture cleanup failed")
                cleanupFailures.forEach(reported::addSuppressed)
                if (failure == null) throw reported
            }
        }
    }

    private fun <T> onMain(action: () -> T): T {
        val task = FutureTask<T> { action() }
        instrument.runOnMainSync(task)
        return task.get()
    }

    private fun launchReader(book: Book? = null): ReadBookActivity {
        val intent = Intent(context, ReadBookActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (book != null) intent.putExtra("bookUrl", book.bookUrl).putExtra("inBookshelf", true)
        return instrument.startActivitySync(intent) as ReadBookActivity
    }

    private fun grantNotifications() {
        if (Build.VERSION.SDK_INT >= 33) {
            instrument.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun finishReader(reader: ReadBookActivity?) {
        onMain {
            for (activity in ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)) {
                if (activity is PermissionActivity && activity.intent.getStringArrayExtra(PermissionActivity.KEY_INPUT_PERMISSIONS)
                        ?.contentEquals(arrayOf(Permissions.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)) == true) {
                    activity.finish()
                }
            }
            if (reader != null && !reader.isDestroyed) reader.finish()
        }
        instrument.waitForIdleSync()
    }

    private fun awaitStage(label: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 15_000
        while (!condition()) {
            if (SystemClock.elapsedRealtime() >= deadline) {
                val state = onMain { (ReadBook.callBack as? ReadBookActivity)?.lifecycle?.currentState }
                fail("Timed out waiting for $label (reader=$state, chapter=${ReadBook.durChapterIndex}, " +
                    "position=${ReadBook.durChapterPos}, playing=${BaseReadAloudService.isPlay()})")
            }
            SystemClock.sleep(40)
        }
    }

    private fun dismissOptionalBatteryPrompt() {
        val showingBatteryPrompt = onMain {
            ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                .filterIsInstance<PermissionActivity>().any {
                    it.intent.getStringArrayExtra(PermissionActivity.KEY_INPUT_PERMISSIONS)
                        ?.contentEquals(arrayOf(Permissions.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)) == true
                }
        }
        if (showingBatteryPrompt) onView(withId(android.R.id.button2)).inRoot(isDialog()).perform(click())
    }

    @Test(timeout = 25_000)
    fun restoredViewportSkipsEmptyFramesAndOutdatedResizes() = runBlocking<Unit> {
        check(Build.HARDWARE in setOf("ranchu", "goldfish") && appDb.bookDao.allBookCount == 0 &&
            ReadBook.book == null && !BaseReadAloudService.isRun)
        val guard = LoopbackSourcePackGuard(context)
        try {
            guard.mark()
            LocalConfig.readHelpVersionIsLast
            ActivityScenario.launch<ReadBookActivity>(Intent(context, ReadBookActivity::class.java)).use { scenario ->
                waitUntil { ChapterProvider.viewWidth > 100 && ChapterProvider.viewHeight > 100 }
                var height = 0
                scenario.onActivity { activity ->
                    val view = ContentTextView(activity, null)
                    val original = RectF(ChapterProvider.visibleRect)
                    val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
                    try {
                        ChapterProvider.visibleRect.setEmpty()
                        view.draw(Canvas(bitmap))
                    } finally {
                        ChapterProvider.visibleRect.set(original)
                        bitmap.recycle()
                    }
                    val width = ChapterProvider.viewWidth
                    height = ChapterProvider.viewHeight
                    ChapterProvider.upViewSize(width, height - 10)
                    ChapterProvider.upViewSize(width, height - 20)
                    ChapterProvider.upViewSize(width, height)
                }
                delay(450)
                scenario.onActivity { assertEquals(height, ChapterProvider.viewHeight) }
            }
        } finally {
            guard.restore()
        }
    }

    @Test(timeout = 25_000)
    fun mediaResumeWithoutAnActiveBookReturnsToIdle() {
        check(Build.HARDWARE in setOf("ranchu", "goldfish") && !BaseReadAloudService.isRun &&
            ReadBook.book == null && appDb.bookDao.allBookCount == 0)
        val guard = LoopbackSourcePackGuard(context)
        val intent = Intent(context, HttpReadAloudService::class.java).setAction(IntentAction.resume)
        val observable = eventObservable<Int>(EventBus.ALOUD_STATE)
        val stopped = CountDownLatch(1)
        val observer = Observer<Int> { if (it == Status.STOP) stopped.countDown() }
        var reader: ReadBookActivity? = null
        try {
            guard.mark()
            grantNotifications()
            LocalConfig.readHelpVersionIsLast
            reader = launchReader()
            instrument.runOnMainSync {
                observable.observeForever(observer)
                context.startService(intent)
            }
            assertTrue("A stale media command must not leave a silent playing service", stopped.await(8, TimeUnit.SECONDS))
            assertFalse(BaseReadAloudService.isRun)
        } finally {
            instrument.runOnMainSync {
                observable.removeObserver(observer)
                context.stopService(intent)
            }
            finishReader(reader)
            guard.restore()
        }
    }

    private fun shell(command: String) {
        ParcelFileDescriptor.AutoCloseInputStream(instrument.uiAutomation.executeShellCommand(command)).use { it.readBytes() }
    }

    private fun wakeDevice() {
        shell("input keyevent KEYCODE_WAKEUP")
        shell("wm dismiss-keyguard")
    }

    private fun wave(): ByteArray {
        val samples = 5_200
        val dataSize = samples * 2
        val out = ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN)
        out.put("RIFF".toByteArray()).putInt(36 + dataSize).put("WAVEfmt ".toByteArray())
        out.putInt(16).putShort(1).putShort(1).putInt(8_000).putInt(16_000).putShort(2).putShort(16)
        out.put("data".toByteArray()).putInt(dataSize)
        repeat(samples) { out.putShort(if (it % 40 < 20) 200.toShort() else (-200).toShort()) }
        return out.array()
    }
}
