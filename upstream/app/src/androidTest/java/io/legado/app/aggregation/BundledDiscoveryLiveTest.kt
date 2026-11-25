package io.legado.app.aggregation

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.SystemClock
import android.view.View
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewModelScope
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleCallback
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.google.gson.JsonObject
import com.google.gson.reflect.TypeToken
import io.legado.app.R
import io.legado.app.constant.AppConst.appInfo
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.SearchBook
import io.legado.app.help.config.LocalConfig
import io.legado.app.help.source.VerifiedSourceBootstrap
import io.legado.app.help.update.reader.ReaderApkVerifier
import io.legado.app.help.update.reader.ReaderRelease
import io.legado.app.help.update.reader.ReaderStartupUpdates
import io.legado.app.help.update.reader.ReaderUpdateManager
import io.legado.app.help.update.reader.ReaderUpdatePolicy
import io.legado.app.model.ReadBook
import io.legado.app.model.aggregation.AggregationPolicy
import io.legado.app.model.aggregation.ExploreAggregation
import io.legado.app.service.BaseReadAloudService
import io.legado.app.ui.aggregation.HorizontalCategoryView
import io.legado.app.ui.book.explore.ExploreResultsFragment
import io.legado.app.ui.book.explore.ExploreShowAdapter
import io.legado.app.ui.book.explore.ExploreShowViewModel
import io.legado.app.ui.main.MainActivity
import io.legado.app.ui.main.explore.ExploreFragment
import io.legado.app.utils.GSON
import io.legado.app.utils.defaultSharedPreferences
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Job
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/** Opt-in real bundled rules and Main UI; no imported private sources, no bodies, no Appium. */
@RunWith(AndroidJUnit4::class)
class BundledDiscoveryLiveTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test(timeout = 120_000)
    fun actualBundledSourcesShowBooksInAllAndTheirCategoryAndRetainAllOnReturn() {
        assumeTrue("Explicit liveDiscovery=true required",
            InstrumentationRegistry.getArguments().getString("liveDiscovery") == "true")
        check(context.packageName == "io.multiread.reader.debug" && Build.HARDWARE in setOf("ranchu", "goldfish"))
        check(appDb.bookDao.allBookCount == 0 && appDb.bookSourceDao.allCount() == 0 &&
            appDb.rssSourceDao.size == 0 && ReadBook.book == null && !BaseReadAloudService.isRun)
        check(!ReaderUpdateManager.checking() && !ReaderUpdateManager.validating())
        appDb.openHelper.readableDatabase.query("select count(*) from searchBooks").use {
            check(it.moveToFirst() && it.getInt(0) == 0) { "Empty owned search fixture required" }
        }
        val bundled: List<BookSource> = context.assets.open("reader-verified-sources.json").bufferedReader().use {
            GSON.fromJson(it, object : TypeToken<List<BookSource>>() {}.type)
        }
        val ownedIds = bundled.mapTo(linkedSetOf()) { it.bookSourceUrl }
        check(ownedIds.size == bundled.size && ownedIds.size == 17)
        val migration = context.getSharedPreferences(VerifiedSourceBootstrap.PREFS_NAME, Context.MODE_PRIVATE)
        val preferences = listOf(context.defaultSharedPreferences, LocalConfig, migration,
            context.getSharedPreferences("reader_update_v1", Context.MODE_PRIVATE),
            context.getSharedPreferences("aggregation_categories", Context.MODE_PRIVATE))
        val before = preferences.associateWith { prefs -> prefs.all.mapValues { (_, value) ->
            if (value is Set<*>) value.toSet() else value
        } }
        val oldLoader = ReaderUpdateManager.manifestLoaderForTests
        val oldClock = ReaderStartupUpdates.clock
        val report = linkedMapOf<String, Any?>("version" to appInfo.versionName,
            "bundledSources" to ownedIds.size, "stage" to "starting", "passed" to false)
        val output = File(context.filesDir, "bundled-discovery-live/results.json")
        val started = SystemClock.elapsedRealtime()
        val deadline = started + 90_000
        val mainActivity = AtomicReference<MainActivity>()
        val mainStage = AtomicReference<Stage>()
        val lifecycle = ActivityLifecycleCallback { activity, stage ->
            if (activity is MainActivity) {
                if (stage == Stage.CREATED) mainActivity.set(activity)
                if (activity === mainActivity.get()) mainStage.set(stage)
            }
        }
        var registered = false
        try {
            check(migration.edit().remove(VerifiedSourceBootstrap.VERSION_KEY).commit())
            check(context.defaultSharedPreferences.edit().putString(PreferKey.defaultHomePage, "bookshelf")
                .putBoolean(PreferKey.showDiscovery, true).putBoolean(PreferKey.showRss, true)
                .putBoolean(PreferKey.autoRefresh, false).putBoolean(PreferKey.autoCheckNewBackup, false).commit())
            check(LocalConfig.edit().putBoolean("privacyPolicyOk", true).putLong("appVersionCode", appInfo.versionCode)
                .putString("password", "").putBoolean("appCrash", false)
                .putLong("lastBackup", System.currentTimeMillis()).commit())
            // Keep update policy out of this website/UI test without touching real source APIs.
            ReaderUpdateManager.manifestLoaderForTests = { noNewVersion() }
            instrumentation.runOnMainSync { ReaderStartupUpdates.resetForTests() }
            instrumentation.runOnMainSync {
                ActivityLifecycleMonitorRegistry.getInstance().addLifecycleCallback(lifecycle)
                registered = true
            }
            report["stage"] = "launching-main"
            save(output, report, started)
            // Main can already be RESUMED/idle while startActivitySync waits indefinitely
            // for its internal launch waiter. Observe the real lifecycle with a bound.
            context.startActivity(Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            awaitUntil(minOf(deadline, started + 20_000), "Main activity must reach RESUMED") {
                mainActivity.get() != null && mainStage.get() == Stage.RESUMED
            }
            val main = mainActivity.get()!!
            awaitUntil(minOf(deadline, started + 20_000)) {
                main.findViewById<View>(R.id.menu_discovery)?.isShown == true
            }
            instrumentation.runOnMainSync { assertTrue(main.findViewById<View>(R.id.menu_discovery).performClick()) }
            report["stage"] = "waiting-for-all-books"
            save(output, report, started)
            awaitUntil(minOf(deadline, started + 60_000)) {
                active(main)?.let { (fragment, books) ->
                    fragment.arguments?.getString("aggregateCategory") == AggregationPolicy.ALL_SOURCES && books.isNotEmpty()
                } == true
            }
            val initial = snapshot(main)
            val originalUrls = initial.mapTo(hashSetOf()) { it.bookUrl }
            val workingIds = initial.mapTo(linkedSetOf()) { it.origin }
            assertTrue("All rows must come from the real bundled identifiers", workingIds.all { it in ownedIds })
            report["allBookCount"] = initial.size
            report["allFirstBooksMillis"] = SystemClock.elapsedRealtime() - started
            report["allWorkingSourceIds"] = workingIds.toList()
            val matching = runBlocking { ExploreAggregation.snapshotRoutesForCategory(context, null) }
                .firstOrNull { it.source.bookSourceUrl in workingIds && it.category != AggregationPolicy.ALL_SOURCES }
            assertNotNull("A source that delivered real books must expose its actual category", matching)
            val category = matching!!.category
            awaitUntil(deadline) { tabs(main)?.let { it.positionOf(category) >= 0 } == true }
            report["stage"] = "waiting-for-category-books"
            report["category"] = category
            save(output, report, started)
            select(main, category)
            awaitUntil(deadline) {
                active(main)?.let { (fragment, books) ->
                    fragment.arguments?.getString("aggregateCategory") == category && books.isNotEmpty()
                } == true
            }
            val selected = snapshot(main)
            assertTrue(selected.all { it.origin in ownedIds })
            report["categoryBookCount"] = selected.size
            report["categoryWorkingSourceIds"] = selected.map { it.origin }.distinct()
            select(main, AggregationPolicy.ALL_SOURCES)
            awaitUntil(deadline) {
                active(main)?.let { (fragment, books) ->
                    fragment.arguments?.getString("aggregateCategory") == AggregationPolicy.ALL_SOURCES &&
                        books.any { it.bookUrl in originalUrls }
                } == true
            }
            report["allAfterReturnBookCount"] = snapshot(main).size
            report["allPreviouslyShownBookRetained"] = true
            report["catalogueCheckedSources"] = ExploreAggregation.catalogueProgress.checkedSources
            report["catalogueComplete"] = ExploreAggregation.catalogueProgress.complete
            report["passed"] = true
            report["stage"] = "complete"
        } finally {
            save(output, report, started)
            try {
                // Joining the actual lifecycle/VM jobs also waits for cancelled scanner-finally
                // cursor writes before preference restoration; a sleep would leave a race.
                val jobs = arrayListOf<Job>()
                instrumentation.runOnMainSync {
                    mainActivity.get()?.let { activity ->
                        activity.lifecycleScope.coroutineContext[Job]?.let(jobs::add)
                        activity.supportFragmentManager.fragments.filterIsInstance<ExploreFragment>().forEach { parent ->
                            parent.viewLifecycleOwnerLiveData.value?.lifecycleScope?.coroutineContext?.get(Job)?.let(jobs::add)
                            parent.childFragmentManager.fragments.filterIsInstance<ExploreResultsFragment>().forEach { child ->
                                ViewModelProvider(child)[ExploreShowViewModel::class.java]
                                    .viewModelScope.coroutineContext[Job]?.let(jobs::add)
                            }
                        }
                        if (!activity.isFinishing && !activity.isDestroyed) activity.finish()
                    }
                }
                awaitUntil(SystemClock.elapsedRealtime() + 5_000, "Owned Main must finish before source cleanup") {
                    mainActivity.get()?.isDestroyed != false
                }
                runBlocking { withTimeout(10_000) { jobs.distinct().forEach { it.join() } } }
                val until = SystemClock.elapsedRealtime() + 5_000
                while (ReaderUpdateManager.checking() && SystemClock.elapsedRealtime() < until) SystemClock.sleep(20)
                instrumentation.runOnMainSync { ReaderStartupUpdates.resetForTests(); ReaderStartupUpdates.clock = oldClock }
                ReaderUpdateManager.manifestLoaderForTests = oldLoader
                // Only these exact fresh bundled IDs belong to this fixture. Never clear all data.
                val remaining = appDb.bookSourceDao.allPart.map { it.bookSourceUrl }
                check(remaining.all { it in ownedIds }) { "Unexpected source appeared; refuse broad cleanup" }
                appDb.runInTransaction {
                    val arguments = ownedIds.toTypedArray<Any>()
                    appDb.openHelper.writableDatabase.execSQL("delete from searchBooks where origin in (" +
                        ownedIds.joinToString(",") { "?" } + ")", arguments)
                    ownedIds.forEach { appDb.bookSourceDao.delete(it) }
                }
                assertEquals("Live discovery must not add books to the shelf", 0, appDb.bookDao.allBookCount)
                assertEquals("Live discovery must not import subscription sources", 0, appDb.rssSourceDao.size)
            } finally {
                if (registered) instrumentation.runOnMainSync {
                    ActivityLifecycleMonitorRegistry.getInstance().removeLifecycleCallback(lifecycle)
                }
                ReaderUpdateManager.manifestLoaderForTests = oldLoader
                before.forEach { (prefs, values) -> restore(prefs, values) }
            }
        }
    }

    private fun active(main: MainActivity): Pair<ExploreResultsFragment, List<SearchBook>>? {
        val explore = main.supportFragmentManager.fragments.filterIsInstance<ExploreFragment>().firstOrNull() ?: return null
        val child = explore.childFragmentManager.fragments.filterIsInstance<ExploreResultsFragment>().firstOrNull {
            !it.isHidden && it.view?.isShown == true && it.viewLifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        } ?: return null
        val adapter = child.view?.findViewById<RecyclerView>(R.id.recycler_view)?.adapter as? ExploreShowAdapter ?: return null
        return child to adapter.getItems().toList()
    }
    private fun snapshot(main: MainActivity): List<SearchBook> {
        var result = emptyList<SearchBook>()
        instrumentation.runOnMainSync { result = active(main)?.second.orEmpty() }
        return result
    }
    private fun tabs(main: MainActivity): HorizontalCategoryView? = main.supportFragmentManager.fragments
        .filterIsInstance<ExploreFragment>().firstOrNull()?.view?.findViewById(R.id.aggregation_categories)

    private fun select(main: MainActivity, category: String) = instrumentation.runOnMainSync {
        val tabs = tabs(main) ?: error("Main discovery category strip required")
        check(tabs.positionOf(category) >= 0) { "The real delivered source's category must be selectable" }
        tabs.selectCategory(category)
    }

    private fun awaitUntil(deadline: Long,
        message: String = "Real bundled Main discovery did not show expected books before the shared deadline",
        condition: () -> Boolean) {
        do {
            var ready = false
            instrumentation.runOnMainSync { ready = condition() }
            if (ready) return
            SystemClock.sleep(25)
        } while (SystemClock.elapsedRealtime() < deadline)
        fail(message)
    }
    private fun save(file: File, report: MutableMap<String, Any?>, started: Long) {
        report["elapsedMillis"] = SystemClock.elapsedRealtime() - started
        file.parentFile!!.mkdirs()
        file.writeText(GSON.toJson(report))
    }

    private fun noNewVersion(): ReaderRelease = ReaderRelease.parse(JsonObject().apply {
        addProperty("packageName", context.packageName)
        addProperty("versionCode", ReaderApkVerifier.versionCode(ReaderApkVerifier.installed(context)))
        addProperty("versionName", appInfo.versionName)
        addProperty("minSdk", 21)
        addProperty("bytes", 1)
        addProperty("sha256", "0".repeat(64))
        addProperty("apkUrl", "https://github.com${ReaderUpdatePolicy.REPOSITORY}/releases/download/owned-current/Reader.apk")
        addProperty("releasePageUrl", ReaderUpdatePolicy.RELEASE_PAGE)
    }.toString(), context.packageName)

    private fun restore(preferences: SharedPreferences, values: Map<String, *>) {
        check(preferences.edit().clear().apply {
            values.forEach { (key, value) -> when (value) {
                is String -> putString(key, value)
                is Boolean -> putBoolean(key, value)
                is Int -> putInt(key, value)
                is Long -> putLong(key, value)
                is Float -> putFloat(key, value)
                is Set<*> -> { @Suppress("UNCHECKED_CAST") putStringSet(key, value as Set<String>) }
            } }
        }.commit())
    }
}
