package io.legado.app.update.reader

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.view.View
import android.widget.Button
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.google.gson.JsonObject
import io.legado.app.R
import io.legado.app.constant.AppConst.appInfo
import io.legado.app.data.appDb
import io.legado.app.help.config.LocalConfig
import io.legado.app.help.update.reader.ReaderApkVerifier
import io.legado.app.help.update.reader.ReaderRelease
import io.legado.app.help.update.reader.ReaderStartupUpdates
import io.legado.app.help.update.reader.ReaderTransfer
import io.legado.app.help.update.reader.ReaderUpdateManager
import io.legado.app.help.update.reader.ReaderUpdatePolicy
import io.legado.app.help.update.reader.ReaderUpdateService
import io.legado.app.help.update.reader.ReaderUpdateStore
import io.legado.app.model.ReadBook
import io.legado.app.service.BaseReadAloudService
import io.legado.app.ui.update.reader.ReaderUpdateActivity
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.InputStream
import java.io.InterruptedIOException
import java.net.HttpURLConnection
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Real service/UI lifecycle with an in-memory socket; no HTTP, APK completion or installer. */
@RunWith(AndroidJUnit4::class)
class ReaderUpdateDownloadUiTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val preferences get() = context.getSharedPreferences("reader_update_v1", Context.MODE_PRIVATE)
    private val store get() = ReaderUpdateStore(context)
    private val before = arrayListOf<Pair<SharedPreferences, Map<String, *>>>()
    private val scenarios = arrayListOf<ActivityScenario<ReaderUpdateActivity>>()
    private val payload = ("Owned download ${UUID.randomUUID()} ".repeat(4096)).toByteArray()
    private val prefix = payload.copyOf(8192)
    private val opened = AtomicInteger()
    private val manifestCalls = AtomicInteger()
    private val reading = CountDownLatch(1)
    private val released = CountDownLatch(1)
    private val inputClosed = AtomicBoolean()
    private lateinit var release: ReaderRelease
    private var ownsFixture = false

    @Before fun prepare() {
        check(Build.HARDWARE in setOf("ranchu", "goldfish") && context.packageName == "io.multiread.reader.debug" &&
            appDb.bookSourceDao.allCount() == 0 && appDb.bookDao.allBookCount == 0 && ReadBook.book == null && !BaseReadAloudService.isRun)
        check(!ReaderUpdateService.isRunning() && !ReaderUpdateManager.checking() && !ReaderUpdateManager.validating())
        check(ReaderUpdateService.connectionsForTests == null && ReaderUpdateManager.manifestLoaderForTests == null)
        check(!store.directory.exists() || store.directory.walkTopDown().none { it.isFile }) {
            "Dedicated idle update directory required; existing update files must not be removed"
        }
        if (Build.VERSION.SDK_INT >= 33) check(context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            "Grant POST_NOTIFICATIONS to the dedicated test app before this suite"
        }
        listOf(preferences, LocalConfig).forEach { before.add(it to it.all.toMap()) }
        ownsFixture = true
        check(LocalConfig.edit().putBoolean("privacyPolicyOk", true).putLong("appVersionCode", appInfo.versionCode)
            .putString("password", "").putBoolean("appCrash", false).putLong("lastBackup", System.currentTimeMillis()).commit())
        check(preferences.edit().clear().commit())
        release = metadata()
        store.checked(release, "available")
        ReaderUpdateManager.manifestLoaderForTests = { manifestCalls.incrementAndGet(); release }
        ReaderUpdateService.connectionsForTests = ReaderTransfer.Connections { uri ->
            check(uri.toASCIIString() == release.apkUrl)
            opened.incrementAndGet()
            object : HttpURLConnection(uri.toURL()) {
                override fun connect() = Unit
                override fun disconnect() { inputClosed.set(true); released.countDown() }
                override fun usingProxy() = false
                override fun getResponseCode() = HTTP_OK
                override fun getHeaderField(name: String?): String? = when (name) {
                    "Content-Length" -> payload.size.toString()
                    "ETag" -> "\"owned-ui-download\""
                    else -> null
                }
                override fun getInputStream(): InputStream = object : InputStream() {
                    private var offset = 0
                    override fun read(): Int = throw UnsupportedOperationException("Bulk reads required")
                    override fun read(buffer: ByteArray, start: Int, length: Int): Int {
                        if (offset < prefix.size) {
                            val count = minOf(length, prefix.size - offset)
                            prefix.copyInto(buffer, start, offset, offset + count); offset += count
                            return count
                        }
                        reading.countDown()
                        try { released.await(60, TimeUnit.SECONDS) }
                        catch (_: InterruptedException) { Thread.currentThread().interrupt() }
                        throw InterruptedIOException("Owned cancelled or closed input")
                    }
                    override fun close() { inputClosed.set(true); released.countDown() }
                }
            }
        }
        instrumentation.runOnMainSync { ReaderStartupUpdates.resetForTests() }
    }

    @After fun finish() {
        if (!ownsFixture) return
        if (::release.isInitialized) ReaderUpdateService.cancel(context, store.snapshot().jobId)
        released.countDown()
        val deadline = SystemClock.uptimeMillis() + 10_000
        while ((ReaderUpdateService.isRunning() || ReaderUpdateManager.checking() || ReaderUpdateManager.validating()) &&
            SystemClock.uptimeMillis() < deadline) SystemClock.sleep(20)
        check(!ReaderUpdateService.isRunning() && !ReaderUpdateManager.checking() && !ReaderUpdateManager.validating()) {
            "Stop the owned update workers before restoring preferences or files"
        }
        scenarios.forEach { it.close() }
        instrumentation.runOnMainSync { ReaderStartupUpdates.resetForTests() }
        ReaderUpdateService.connectionsForTests = null
        ReaderUpdateManager.manifestLoaderForTests = null
        if (::release.isInitialized) store.partial(release).delete()
        before.forEach { (target, values) -> restore(target, values) }
    }

    @Test(timeout = 45_000) fun backgroundReopenRotationAndRepeatedStartKeepOneTransferUntilCancellation() {
        val first = ActivityScenario.launch<ReaderUpdateActivity>(ReaderUpdateActivity.downloadIntent(context, release)).also { scenarios.add(it) }
        assertTrue(reading.await(8, TimeUnit.SECONDS))
        await { ReaderUpdateService.isRunning() && store.snapshot().state == "downloading" && store.partial(release).length() == prefix.size.toLong() }
        val job = store.snapshot().jobId
        assertTrue(job.isNotEmpty())
        first.onActivity { activity ->
            assertDownloadingActions(activity)
            activity.findViewById<Button>(R.id.background_update).performClick()
        }
        await { ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).none { it is ReaderUpdateActivity } }
        assertTrue(ReaderUpdateService.isRunning())
        assertEquals(job, store.snapshot().jobId)
        assertEquals(1, opened.get())
        assertFalse(inputClosed.get())
        val reopened = ActivityScenario.launch<ReaderUpdateActivity>(Intent(context, ReaderUpdateActivity::class.java)).also { scenarios.add(it) }
        reopened.onActivity { assertDownloadingActions(it) }
        reopened.recreate()
        reopened.onActivity { activity ->
            assertDownloadingActions(activity)
            repeat(3) { ReaderUpdateService.start(activity, release) }
        }
        instrumentation.waitForIdleSync()
        assertEquals(job, store.snapshot().jobId)
        assertEquals(1, opened.get())
        assertEquals(0, manifestCalls.get())
        assertFalse(inputClosed.get())
        reopened.onActivity { it.findViewById<Button>(R.id.cancel_update).performClick() }
        await { store.snapshot().state == "cancelled" && !ReaderUpdateService.isRunning() && inputClosed.get() }
        assertEquals(job, store.snapshot().jobId)
        assertArrayEquals(prefix, store.partial(release).readBytes())
        assertNull(store.snapshot().ready)
        assertFalse(store.readyFile(release).exists())
        assertEquals(1, opened.get())
    }

    private fun assertDownloadingActions(activity: ReaderUpdateActivity) {
        val actions = listOf(R.id.check_update, R.id.download_update, R.id.cancel_update,
            R.id.background_update, R.id.allow_install, R.id.install_update)
        assertEquals(setOf(R.id.cancel_update, R.id.background_update), actions.filter {
            activity.findViewById<Button>(it).visibility == View.VISIBLE
        }.toSet())
        assertTrue(activity.findViewById<Button>(R.id.cancel_update).isEnabled)
        assertTrue(activity.findViewById<Button>(R.id.background_update).isEnabled)
    }
    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (SystemClock.uptimeMillis() < deadline) {
            var ready = false
            instrumentation.runOnMainSync { ready = condition() }
            if (ready) return
            SystemClock.sleep(25)
        }
        fail("Owned update lifecycle condition did not settle")
    }
    private fun metadata(): ReaderRelease = ReaderRelease.parse(JsonObject().apply {
        addProperty("packageName", context.packageName)
        addProperty("versionCode", ReaderApkVerifier.versionCode(ReaderApkVerifier.installed(context)) + 7)
        addProperty("versionName", "owned-background-download")
        addProperty("minSdk", 24)
        addProperty("bytes", payload.size)
        addProperty("sha256", MessageDigest.getInstance("SHA-256").digest(payload).joinToString("") { "%02x".format(it.toInt() and 255) })
        addProperty("apkUrl", "https://github.com${ReaderUpdatePolicy.REPOSITORY}/releases/download/owned-ui/Reader.apk")
        addProperty("releasePageUrl", ReaderUpdatePolicy.RELEASE_PAGE)
    }.toString(), context.packageName)
    private fun restore(target: SharedPreferences, values: Map<String, *>) {
        check(target.edit().clear().apply { values.forEach { (key, value) ->
            when (value) {
                is String -> putString(key, value)
                is Boolean -> putBoolean(key, value)
                is Int -> putInt(key, value)
                is Long -> putLong(key, value)
                is Float -> putFloat(key, value)
                is Set<*> -> { @Suppress("UNCHECKED_CAST") putStringSet(key, value as Set<String>) }
            }
        } }.commit())
    }
}
