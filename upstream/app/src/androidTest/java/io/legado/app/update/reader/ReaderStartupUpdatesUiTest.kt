package io.legado.app.update.reader

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.SystemClock
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.doesNotExist
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleCallback
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.google.gson.JsonObject
import io.legado.app.R
import io.legado.app.constant.AppConst.appInfo
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.help.config.LocalConfig
import io.legado.app.help.source.VerifiedSourceBootstrap
import io.legado.app.help.update.reader.ReaderApkVerifier
import io.legado.app.help.update.reader.ReaderRelease
import io.legado.app.help.update.reader.ReaderStartupUpdates
import io.legado.app.help.update.reader.ReaderUpdateManager
import io.legado.app.help.update.reader.ReaderUpdatePolicy
import io.legado.app.help.update.reader.ReaderUpdateStore
import io.legado.app.model.ReadBook
import io.legado.app.service.BaseReadAloudService
import io.legado.app.ui.about.AboutActivity
import io.legado.app.ui.main.MainActivity
import io.legado.app.ui.update.reader.ReaderUpdateActivity
import io.legado.app.utils.defaultSharedPreferences
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** Synthetic manifests only: no external network, download, installer or user reading data. */
@RunWith(AndroidJUnit4::class)
class ReaderStartupUpdatesUiTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val calls = AtomicInteger()
    private val now = AtomicLong(1_000)
    private val store get() = ReaderUpdateStore(context)
    private val updatePreferences get() = context.getSharedPreferences("reader_update_v1", Context.MODE_PRIVATE)
    private val before = arrayListOf<Pair<SharedPreferences, Map<String, *>>>()
    private val activities = linkedSetOf<Activity>()
    private val scenarios = arrayListOf<ActivityScenario<*>>()
    private val ownedFiles = arrayListOf<File>()
    private val pending = arrayListOf<CountDownLatch>()
    private val installed get() = ReaderApkVerifier.versionCode(ReaderApkVerifier.installed(context))
    private val lifecycle = ActivityLifecycleCallback { activity, stage ->
        if (stage == Stage.CREATED && (activity is MainActivity || activity is ReaderUpdateActivity || activity is AboutActivity))
            activities.add(activity)
    }

    @Before fun prepare() {
        check(Build.HARDWARE in setOf("ranchu", "goldfish") && appDb.bookSourceDao.allCount() == 0 &&
            appDb.bookDao.allBookCount == 0 && ReadBook.book == null && !BaseReadAloudService.isRun) {
            "Dedicated idle emulator required"
        }
        check(!ReaderUpdateManager.checking() && !ReaderUpdateManager.validating()) { "Idle update worker required" }
        val defaults = context.defaultSharedPreferences
        val sourceMigration = context.getSharedPreferences(VerifiedSourceBootstrap.PREFS_NAME, Context.MODE_PRIVATE)
        listOf(defaults, LocalConfig, updatePreferences, sourceMigration).forEach { before.add(it to it.all.toMap()) }
        check(sourceMigration.edit().putInt(VerifiedSourceBootstrap.VERSION_KEY, VerifiedSourceBootstrap.PACK_VERSION).commit())
        defaults.edit().putString(PreferKey.defaultHomePage, "bookshelf").putBoolean(PreferKey.autoRefresh, false)
            .putBoolean(PreferKey.autoCheckNewBackup, false).commit()
        LocalConfig.edit().putBoolean("privacyPolicyOk", true).putLong("appVersionCode", appInfo.versionCode)
            .putString("password", "").putBoolean("appCrash", false).putLong("lastBackup", System.currentTimeMillis()).commit()
        instrumentation.runOnMainSync {
            ReaderStartupUpdates.resetForTests()
            ReaderStartupUpdates.clock = now::get
            ActivityLifecycleMonitorRegistry.getInstance().addLifecycleCallback(lifecycle)
        }
        ReaderUpdateManager.manifestLoaderForTests = { calls.incrementAndGet(); metadata(2, "owned-new-version") }
    }

    @After fun finish() {
        pending.forEach { it.countDown() }
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (ReaderUpdateManager.checking() && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(20)
        instrumentation.runOnMainSync {
            ReaderStartupUpdates.resetForTests()
            activities.forEach { it.finish() }
            ActivityLifecycleMonitorRegistry.getInstance().removeLifecycleCallback(lifecycle)
        }
        scenarios.forEach { it.close() }
        ReaderUpdateManager.manifestLoaderForTests = null
        ownedFiles.forEach { it.delete() }
        before.forEach { (preferences, values) -> restore(preferences, values) }
    }

    @Test fun openingPromptsOnceRotationAndReturningDoNotRecheckAndForegroundChecksAgain() {
        val scenario = ActivityScenario.launch(MainActivity::class.java).also { scenarios.add(it) }
        await { !ReaderUpdateManager.checking() && calls.get() == 1 && store.snapshot().state == "available" }
        onView(withText(R.string.reader_update_startup_title)).check(matches(isDisplayed()))
        onView(withId(android.R.id.message)).check(matches(withText(context.getString(
            R.string.reader_update_startup_message, "owned-new-version") + "\n\n" +
            context.getString(R.string.reader_update_changes) + "\nOwned fix one\nOwned fix two")))
        onView(withId(android.R.id.button1)).check(matches(withText(R.string.reader_update_download)))
        onView(withId(android.R.id.button2)).check(matches(withText(R.string.reader_update_cancel)))
        onView(withId(android.R.id.button3)).check(matches(org.hamcrest.Matchers.not(isDisplayed())))
        onView(withText(R.string.reader_update_cancel)).perform(click())
        scenario.recreate()
        instrumentation.waitForIdleSync()
        assertEquals(1, calls.get())
        onView(withText(R.string.reader_update_startup_title)).check(doesNotExist())
        scenario.moveToState(Lifecycle.State.CREATED)
        now.addAndGet(61_000)
        scenario.moveToState(Lifecycle.State.RESUMED)
        await { !ReaderUpdateManager.checking() && calls.get() == 2 }
        onView(withText(R.string.reader_update_startup_title)).check(matches(isDisplayed()))
        onView(withText(R.string.reader_update_cancel)).perform(click())
        assertEquals(2, calls.get())
    }

    @Test fun openingUpdatePageRefreshesOldReadyRetainsFilesAndOffersOnlyDownloadOrCancel() {
        val oldBytes = "owned old rendering fixture ${System.nanoTime()}".toByteArray()
        val old = metadata(1, "owned-old-version", oldBytes)
        val newestBytes = "owned new rendering fixture ${System.nanoTime()}".toByteArray()
        val newest = metadata(2, "owned-new-version", newestBytes)
        writeOwned(store.readyFile(old), oldBytes)
        val partial = store.partial(newest)
        writeOwned(partial, newestBytes.copyOf(5))
        updatePreferences.edit().putString("release", old.json).putString("ready", old.json).putString("state", "ready").commit()
        ReaderUpdateManager.manifestLoaderForTests = { calls.incrementAndGet(); newest }
        val scenario = ActivityScenario.launch(ReaderUpdateActivity::class.java).also { scenarios.add(it) }
        await { calls.get() == 1 && !ReaderUpdateManager.checking() && store.snapshot().release?.sha256 == newest.sha256 }
        scenario.onActivity { activity ->
            assertTrue(activity.findViewById<TextView>(R.id.release_version).text.contains("owned-new-version"))
            assertEquals(View.GONE, activity.findViewById<Button>(R.id.install_update).visibility)
            assertTrue(activity.findViewById<Button>(R.id.download_update).isEnabled)
            assertEquals(context.getString(R.string.reader_update_download), activity.findViewById<Button>(R.id.download_update).text)
            assertEquals(View.VISIBLE, activity.findViewById<Button>(R.id.cancel_update).visibility)
            assertEquals(View.GONE, activity.findViewById<Button>(R.id.check_update).visibility)
            assertEquals(View.GONE, activity.findViewById<Button>(R.id.background_update).visibility)
        }
        scenario.recreate()
        await { calls.get() == 2 && !ReaderUpdateManager.checking() }
        assertArrayEquals(oldBytes, store.readyFile(old).readBytes())
        assertArrayEquals(newestBytes.copyOf(5), partial.readBytes())
        writeOwned(store.readyFile(newest), newestBytes)
        updatePreferences.edit().putString("ready", newest.json).putString("state", "ready").commit()
        await {
            val activity = resumed(ReaderUpdateActivity::class.java) as? ReaderUpdateActivity
            activity?.findViewById<Button>(R.id.install_update)?.let { it.visibility == View.VISIBLE && it.text.contains("owned-new-version") } == true
        }
    }

    @Test fun delayedResultDoesNotInterruptAnotherPageAndIsAnnouncedOnReturningToMain() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1).also { pending.add(it) }
        ReaderUpdateManager.manifestLoaderForTests = {
            calls.incrementAndGet(); entered.countDown()
            check(release.await(8, TimeUnit.SECONDS)) { "Owned manifest gate timed out" }
            metadata(2, "owned-deferred-version")
        }
        val main = ActivityScenario.launch(MainActivity::class.java).also { scenarios.add(it) }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        main.onActivity { it.startActivity(Intent(it, AboutActivity::class.java)) }
        await { resumed(AboutActivity::class.java) != null }
        release.countDown()
        await { !ReaderUpdateManager.checking() && store.snapshot().state == "available" }
        onView(withText(R.string.reader_update_startup_title)).check(doesNotExist())
        instrumentation.runOnMainSync { resumed(AboutActivity::class.java)!!.finish() }
        await { resumed(MainActivity::class.java) != null }
        onView(withText(R.string.reader_update_startup_title)).check(matches(isDisplayed()))
        assertEquals(1, calls.get())
    }

    @Test fun startupWaitsForPrivacyAndNetworkFailureRemainsSilent() {
        LocalConfig.edit().putBoolean("privacyPolicyOk", false).commit()
        val first = ActivityScenario.launch(MainActivity::class.java).also { scenarios.add(it) }
        onView(withText(R.string.multiread_start)).check(matches(isDisplayed()))
        assertEquals(0, calls.get())
        onView(withText(R.string.multiread_close)).perform(click())
        first.close()
        LocalConfig.edit().putBoolean("privacyPolicyOk", true).commit()
        ReaderUpdateManager.manifestLoaderForTests = { calls.incrementAndGet(); throw IOException("owned offline fixture") }
        ActivityScenario.launch(MainActivity::class.java).also { scenarios.add(it) }
        await { calls.get() == 1 && !ReaderUpdateManager.checking() && store.snapshot().state == "error" }
        onView(withText(R.string.reader_update_startup_title)).check(doesNotExist())
        await { resumed(MainActivity::class.java) != null }
    }

    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (SystemClock.uptimeMillis() < deadline) {
            var satisfied = false
            instrumentation.runOnMainSync { satisfied = condition() }
            if (satisfied) return
            SystemClock.sleep(25)
        }
        fail("Owned update UI condition did not become ready")
    }
    private fun resumed(type: Class<out Activity>): Activity? =
        ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).firstOrNull { type.isInstance(it) }

    private fun writeOwned(file: File, bytes: ByteArray) {
        check(!file.exists()) { "Owned fixture would replace an existing update file" }
        file.parentFile!!.mkdirs(); file.writeBytes(bytes); ownedFiles.add(file)
    }
    private fun metadata(delta: Long, name: String, bytes: ByteArray = "owned synthetic metadata".toByteArray()): ReaderRelease {
        val json = JsonObject().apply {
            addProperty("packageName", context.packageName)
            addProperty("versionCode", installed + delta)
            addProperty("versionName", name)
            addProperty("minSdk", 24)
            addProperty("bytes", bytes.size)
            addProperty("sha256", MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) })
            addProperty("apkUrl", "https://github.com${ReaderUpdatePolicy.REPOSITORY}/releases/download/owned-test/Reader.apk")
            addProperty("releasePageUrl", ReaderUpdatePolicy.RELEASE_PAGE)
            addProperty("notes", "Owned fix one\nOwned fix two")
        }
        return ReaderRelease.parse(json.toString(), context.packageName)
    }
    private fun restore(preferences: SharedPreferences, values: Map<String, *>) {
        preferences.edit().clear().apply {
            values.forEach { (key, value) ->
                when (value) {
                    is String -> putString(key, value)
                    is Boolean -> putBoolean(key, value)
                    is Int -> putInt(key, value)
                    is Long -> putLong(key, value)
                    is Float -> putFloat(key, value)
                    is Set<*> -> {
                        @Suppress("UNCHECKED_CAST")
                        putStringSet(key, value as Set<String>)
                    }
                }
            }
        }.commit()
    }
}
