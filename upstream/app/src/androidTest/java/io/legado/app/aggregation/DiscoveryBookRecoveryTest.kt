package io.legado.app.aggregation

import android.app.Application
import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.onData
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleCallback
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import fi.iki.elonen.NanoHTTPD
import io.legado.app.R
import io.legado.app.constant.AppConst.appInfo
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.rule.ExploreRule
import io.legado.app.help.config.LocalConfig
import io.legado.app.help.source.VerifiedSourceBootstrap
import io.legado.app.help.update.reader.ReaderStartupUpdates
import io.legado.app.help.update.reader.ReaderUpdateManager
import io.legado.app.model.aggregation.AggregationPolicy
import io.legado.app.model.aggregation.ExploreAggregation
import io.legado.app.ui.aggregation.HorizontalCategoryView
import io.legado.app.ui.book.explore.ExploreShowViewModel
import io.legado.app.ui.book.explore.ExploreShowActivity
import io.legado.app.ui.book.explore.ExploreResultsFragment
import io.legado.app.ui.main.MainActivity
import io.legado.app.ui.main.MainViewModel
import io.legado.app.ui.main.explore.ExploreFragment
import io.legado.app.ui.widget.anima.RotateLoading
import io.legado.app.ui.widget.recycler.LoadMoreView
import io.legado.app.utils.defaultSharedPreferences
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.hamcrest.Matchers.allOf
import org.hamcrest.Matchers.equalTo
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Owned loopback sources exercise real discovery, including Main's all/category navigation. */
@RunWith(AndroidJUnit4::class)
class DiscoveryBookRecoveryTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val application = ApplicationProvider.getApplicationContext<Application>()
    private val token = UUID.randomUUID().toString()
    private val genre = "Owned recovery $token"
    private val otherGenre = "Owned other $token"
    private val author = "Owned recovery author"
    private val sources = arrayListOf<BookSource>()
    private val stores = arrayListOf<ViewModelStore>()
    private val models = arrayListOf<ExploreShowViewModel>()
    private val scenarios = arrayListOf<ActivityScenario<*>>()
    private val activities = linkedSetOf<Activity>()
    private val resumedMain = AtomicReference<MainActivity?>()
    private val lifecycle = ActivityLifecycleCallback { activity, stage ->
        if (stage == Stage.CREATED && (activity is MainActivity || activity is ExploreShowActivity)) activities.add(activity)
        if (stage == Stage.RESUMED && activity is MainActivity) resumedMain.set(activity)
    }
    private val server = BookServer()

    private fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)
    private fun await(timeout: Long = 8_000, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeout
        while (SystemClock.elapsedRealtime() < deadline) {
            var ready = false
            main { ready = condition() }
            if (ready) return
            SystemClock.sleep(20)
        }
        fail("Owned discovery recovery did not reach its expected state")
    }

    /** Main can be RESUMED/idle while framework startActivitySync still waits for its monitor. */
    private fun launchMain(): MainActivity {
        main {
            resumedMain.set(null)
            application.startActivity(Intent(application, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        }
        await { resumedMain.get()?.let { !it.isFinishing && !it.isDestroyed } == true }
        return requireNotNull(resumedMain.get())
    }

    private fun openDiscovery(activity: MainActivity) = main {
        val menu = requireNotNull(activity.findViewById<View>(R.id.menu_discovery))
        assertTrue("Discovery navigation must be visible", menu.isShown)
        assertTrue("Discovery navigation must accept its real click", menu.performClick())
    }

    private fun inlineResultsFinished(activity: MainActivity): Boolean {
        val parent = activity.supportFragmentManager.fragments.filterIsInstance<ExploreFragment>().firstOrNull()
            ?: return false
        val child = parent.childFragmentManager.fragments.filterIsInstance<ExploreResultsFragment>()
            .firstOrNull { !it.isHidden && it.view?.isShown == true } ?: return false
        return ViewModelProvider(child)[ExploreShowViewModel::class.java].loadingLiveData.value == false
    }

    /** Join cancelled scanner/result writers before deleting their sources or restoring settings. */
    private fun finishActivities(targets: List<Activity>) {
        val jobs = arrayListOf<Job>()
        main {
            targets.filterNot { it.isDestroyed }.forEach { activity ->
                when (activity) {
                    is MainActivity -> {
                        activity.lifecycleScope.coroutineContext[Job]?.let(jobs::add)
                        ViewModelProvider(activity)[MainViewModel::class.java]
                            .viewModelScope.coroutineContext[Job]?.let(jobs::add)
                        activity.supportFragmentManager.fragments.filterIsInstance<ExploreFragment>().forEach { parent ->
                            parent.viewLifecycleOwnerLiveData.value?.lifecycleScope?.coroutineContext?.get(Job)?.let(jobs::add)
                            parent.viewModel.viewModelScope.coroutineContext[Job]?.let(jobs::add)
                            parent.childFragmentManager.fragments.filterIsInstance<ExploreResultsFragment>().forEach { child ->
                                ViewModelProvider(child)[ExploreShowViewModel::class.java]
                                    .viewModelScope.coroutineContext[Job]?.let(jobs::add)
                            }
                        }
                    }
                    is ExploreShowActivity -> {
                        activity.lifecycleScope.coroutineContext[Job]?.let(jobs::add)
                        ViewModelProvider(activity)[ExploreShowViewModel::class.java]
                            .viewModelScope.coroutineContext[Job]?.let(jobs::add)
                    }
                }
                if (!activity.isFinishing) activity.finish()
            }
        }
        await { targets.all { it.isDestroyed } }
        runBlocking { withTimeout(10_000) { jobs.distinct().joinAll() } }
    }
    private fun owned(action: () -> Unit) {
        check(Build.HARDWARE in setOf("ranchu", "goldfish") && application.packageName == "io.multiread.reader.debug")
        check(appDb.bookSourceDao.allCount() == 0 && appDb.bookDao.allBookCount == 0)
        val preferenceSets = listOf(application.defaultSharedPreferences, LocalConfig,
            application.getSharedPreferences(VerifiedSourceBootstrap.PREFS_NAME, 0),
            application.getSharedPreferences("reader_update_v1", 0),
            application.getSharedPreferences("aggregation_categories", 0))
        val before = preferenceSets.associateWith { it.all.toMap() }
        val loader = ReaderUpdateManager.manifestLoaderForTests
        check(!ReaderUpdateManager.checking())
        ReaderUpdateManager.manifestLoaderForTests = { throw IOException("Owned update metadata disabled") }
        check(preferenceSets[2].edit().putInt(VerifiedSourceBootstrap.VERSION_KEY, VerifiedSourceBootstrap.PACK_VERSION).commit())
        application.defaultSharedPreferences.edit().putString(PreferKey.defaultHomePage, "bookshelf")
            .putBoolean(PreferKey.showDiscovery, true)
            .putBoolean(PreferKey.autoRefresh, false).putBoolean(PreferKey.autoCheckNewBackup, false).commit()
        LocalConfig.edit().putBoolean("privacyPolicyOk", true).putLong("appVersionCode", appInfo.versionCode)
            .putString("password", "").putBoolean("appCrash", false).putLong("lastBackup", System.currentTimeMillis()).commit()
        main { ActivityLifecycleMonitorRegistry.getInstance().addLifecycleCallback(lifecycle) }
        server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
        try { action() }
        finally {
            try {
                // A blocked HTTP fixture must be released before joining cancellation.
                server.release.countDown()
                val jobs = arrayListOf<Job>()
                var ownedActivities = emptyList<Activity>()
                main {
                    models.mapNotNullTo(jobs) { it.viewModelScope.coroutineContext[Job] }
                    models.forEach { it.cancelLoading() }; stores.forEach { it.clear() }
                    ownedActivities = activities.toList()
                }
                finishActivities(ownedActivities)
                scenarios.forEach { it.close() }
                runBlocking { withTimeout(10_000) { jobs.distinct().joinAll() } }
                appDb.bookSourceDao.delete(*sources.toTypedArray())
                (0..20).forEach { appDb.searchBookDao.clear(name(it), author) }
                val deadline = SystemClock.elapsedRealtime() + 5_000
                while (ReaderUpdateManager.checking() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(20)
            } finally {
                main {
                    ReaderStartupUpdates.resetForTests()
                    ActivityLifecycleMonitorRegistry.getInstance().removeLifecycleCallback(lifecycle)
                }
                server.stop()
                ReaderUpdateManager.manifestLoaderForTests = loader
                before.forEach { (preferences, values) -> restore(preferences, values) }
            }
        }
    }
    private fun name(index: Int) = "Owned recovered book $token $index"
    private fun source(index: Int, category: String = genre) = BookSource(
        bookSourceUrl = "${server.base(index)}/source/$token/$index", bookSourceName = "Owned recovery source $index",
        bookSourceGroup = "owned-recovery-$token", customOrder = index, enabled = true, enabledExplore = true,
        exploreUrl = "$category::${server.base(index)}/books/$index?page={{page}}",
        ruleExplore = ExploreRule(bookList = ".book", name = ".title@text", author = ".author@text", bookUrl = ".title@href"))
    private fun install(vararg added: BookSource) { sources.addAll(added); appDb.bookSourceDao.insert(*added) }
    private fun model(category: String?): ExploreShowViewModel {
        lateinit var vm: ExploreShowViewModel
        main {
            val store = ViewModelStore().also { stores.add(it) }
            vm = ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory.getInstance(application))[ExploreShowViewModel::class.java]
            models.add(vm)
            val intent = Intent().putExtra("sourceUrl", AggregationPolicy.ALL_SOURCES)
                .putExtra("exploreUrl", category ?: AggregationPolicy.ALL_SOURCES)
            if (category != null) intent.putExtra("aggregateCategory", category)
            vm.initData(intent)
        }
        return vm
    }

    @Test(timeout = 35_000)
    fun nullAndExplicitAllAndGenreAutomaticallyPassThirteenBadSourcesToReadableBooks() = owned {
        server.bad.addAll(0..12)
        install(*((0..13).map { source(it) } + source(14, otherGenre)).toTypedArray())
        for (category in listOf(null, AggregationPolicy.ALL_SOURCES, genre)) {
            val vm = model(category)
            await { vm.booksData.value.orEmpty().any { it.name == name(13) } && vm.loadingLiveData.value == false }
            if (category == genre) assertFalse(vm.booksData.value.orEmpty().any { it.name == name(14) })
            assertTrue("Healthy books must not be replaced by a partial-source error", vm.errorLiveData.value.isNullOrEmpty())
            main { vm.cancelLoading() }
        }
        assertTrue("Automatic recovery must not turn a successful source to page2", server.requests.all { it.second == 1 })
        assertTrue("The bad prefix must be bypassed without a footer click", server.requests.any { it.first == 13 })
    }

    @Test(timeout = 20_000)
    fun onlyBadSourcesFinishOnceAndDoNotSpinWhenTheCatalogueIsComplete() = owned {
        server.bad.addAll(0..12)
        install(*(0..12).map { source(it) }.toTypedArray())
        val vm = model(null)
        await { ExploreAggregation.catalogueProgress.complete && vm.loadingLiveData.value == false && vm.aggregateHasMore.value == false }
        assertTrue(vm.booksData.value.isNullOrEmpty())
        val count = server.requests.size
        assertEquals(13, server.requests.map { it.first }.toSet().size)
        SystemClock.sleep(200)
        assertEquals("There must be no automatic retry cycle over a dead catalogue", count, server.requests.size)
    }

    @Test(timeout = 20_000)
    fun leavingAggregateCancelsLateBooksAndReopeningRetriesItsFirstPage() = owned {
        install(source(0)); server.block = true
        val vm = model(null)
        assertTrue(server.started.await(4, TimeUnit.SECONDS))
        main { vm.cancelLoading() }
        server.block = false; server.release.countDown()
        SystemClock.sleep(150)
        assertTrue(vm.booksData.value.isNullOrEmpty())
        assertTrue(vm.errorLiveData.value.isNullOrEmpty())
        main { vm.resumeLoading() }
        await { vm.loadingLiveData.value == false && vm.booksData.value.orEmpty().any { it.name == name(0) } }
        assertTrue(server.requests.all { it.second == 1 })
    }

    @Test(timeout = 20_000)
    fun laterMetadataAddsCandidatesWithoutClearingBooksOrAutoTurningTheirPage() = owned {
        install(source(0))
        val vm = model(null)
        await { ExploreAggregation.catalogueProgress.complete && vm.booksData.value?.size == 1 && vm.loadingLiveData.value == false }
        val first = vm.booksData.value!!.single().bookUrl
        install(source(1))
        runBlocking { ExploreAggregation.discoverCategories(application) }
        await { vm.booksData.value?.size == 2 && vm.loadingLiveData.value == false }
        assertEquals(first, vm.booksData.value!!.first().bookUrl)
        assertEquals(1, server.requests.count { it.first == 0 })
        assertTrue(server.requests.all { it.second == 1 })
        val count = server.requests.size
        SystemClock.sleep(150)
        assertEquals(count, server.requests.size)
    }

    @Test(timeout = 35_000)
    fun mainAllToGenreToAllAndReopeningDisplayActualBooksWithoutContinuationControl() = owned {
        server.bad.addAll(0..12)
        install(*((0..13).map { source(it) } + source(14, otherGenre)).toTypedArray())
        fun open(): MainActivity {
            val activity = launchMain()
            openDiscovery(activity)
            await { visibleTexts(activity.window.decorView).contains(name(13)) }
            return activity
        }
        fun select(activity: MainActivity, key: String) = main {
            val fragment = activity.supportFragmentManager.fragments.filterIsInstance<ExploreFragment>().first()
            val tabs = fragment.requireView().findViewById<HorizontalCategoryView>(R.id.aggregation_categories)
            check(tabs.positionOf(key) >= 0)
            tabs.selectCategory(key)
        }
        val activity = open()
        select(activity, genre)
        await { visibleTexts(activity.window.decorView).contains(name(13)) && !visibleTexts(activity.window.decorView).contains(name(14)) }
        select(activity, AggregationPolicy.ALL_SOURCES)
        await { visibleTexts(activity.window.decorView).contains(name(13)) }
        main { assertTrue(visibleTexts(activity.window.decorView).none { it.contains("繼續載入分類") || it.contains("继续载入分类") }) }
        finishActivities(listOf(activity))
        val reopened = open()
        main { assertTrue(visibleTexts(reopened.window.decorView).contains(name(13))) }
    }

    @Test(timeout = 30_000)
    fun bothDiscoveryEntrypointsLeaveAShortFirstPageUntilTheReaderScrolls() = owned {
        install(source(0))
        val home = launchMain()
        // Assert the real preloaded footer before any discovery navigation/request. An
        // unconditional spinner here keeps drawing even while Main shows the bookshelf.
        await {
            val parent = home.supportFragmentManager.fragments.filterIsInstance<ExploreFragment>().firstOrNull()
                ?: return@await false
            val child = parent.childFragmentManager.fragments.filterIsInstance<ExploreResultsFragment>().firstOrNull()
                ?: return@await false
            val spinner = child.view?.findViewById<RotateLoading>(R.id.rotate_loading) ?: return@await false
            val footer = spinner.parent as? LoadMoreView ?: error("Expected the actual preloaded footer")
            assertFalse("Preloaded discovery has no request to mark as loading", footer.isLoading)
            assertFalse("Preloaded discovery must not keep Main drawing an animation", spinner.isStarted)
            assertTrue("Preloading discovery must not fetch book pages", server.requests.isEmpty())
            true
        }
        openDiscovery(home)
        await { visibleTexts(home.window.decorView).contains(name(0)) &&
            ExploreAggregation.catalogueProgress.complete && inlineResultsFinished(home) }
        SystemClock.sleep(300)
        assertEquals("Inline discovery layout must not request page2", listOf(0 to 1), server.requests.toList())
        finishActivities(listOf(home))

        lateinit var standalone: ExploreShowActivity
        ActivityScenario.launch<ExploreShowActivity>(Intent(application, ExploreShowActivity::class.java).apply {
            putExtra("sourceUrl", AggregationPolicy.ALL_SOURCES)
            putExtra("exploreUrl", AggregationPolicy.ALL_SOURCES)
        }).also { scenarios.add(it) }.onActivity { standalone = it }
        await { visibleTexts(standalone.window.decorView).contains(name(0)) &&
            ExploreAggregation.catalogueProgress.complete &&
            ViewModelProvider(standalone)[ExploreShowViewModel::class.java].loadingLiveData.value == false }
        SystemClock.sleep(300)
        assertEquals("Standalone discovery layout must not request page2", listOf(0 to 1, 0 to 1), server.requests.toList())
    }

    @Test(timeout = 25_000)
    fun choosingSourceFromItsSecondCategoryKeepsThatCategoryAndItsBooks() = owned {
        val second = server.base(0) + "/books/1?page={{page}}"
        val configured = source(0).copy(exploreUrl = """[{"title":"$genre","url":"${server.base(0)}/books/0?page={{page}}"},{"title":"$otherGenre","url":"$second"}]""")
        install(configured)
        runBlocking { ExploreAggregation.discoverCategories(application) }
        lateinit var first: ExploreShowActivity
        val scenario = ActivityScenario.launch<ExploreShowActivity>(Intent(application, ExploreShowActivity::class.java).apply {
            putExtra("sourceUrl", AggregationPolicy.ALL_SOURCES)
            putExtra("exploreUrl", otherGenre)
            putExtra("aggregateCategory", otherGenre)
        }).also { scenarios.add(it) }
        scenario.onActivity { first = it }
        await { visibleTexts(first.window.decorView).contains(name(1)) }
        onView(allOf(withId(R.id.mode_single), isDisplayed())).perform(click())
        onData(equalTo(configured.bookSourceName)).inRoot(isDialog()).perform(click())
        await {
            ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                .filterIsInstance<ExploreShowActivity>().any { activity ->
                    activity !== first && activity.intent.getStringExtra("sourceUrl") == configured.bookSourceUrl &&
                        activity.intent.getStringExtra("exploreUrl") == second &&
                        visibleTexts(activity.window.decorView).contains(name(1)) &&
                        !visibleTexts(activity.window.decorView).contains(name(0))
                }
        }
    }

    private fun visibleTexts(view: View): List<String> {
        if (!view.isShown) return emptyList()
        return (view as? TextView)?.text?.toString()?.let(::listOf).orEmpty() +
            (view as? ViewGroup)?.let { parent -> (0 until parent.childCount).flatMap { visibleTexts(parent.getChildAt(it)) } }.orEmpty()
    }
    private fun restore(preferences: SharedPreferences, values: Map<String, *>) {
        preferences.edit().clear().apply { values.forEach { (key, value) -> when (value) {
            is String -> putString(key, value)
            is Boolean -> putBoolean(key, value)
            is Long -> putLong(key, value)
            is Int -> putInt(key, value)
            is Float -> putFloat(key, value)
            is Set<*> -> { @Suppress("UNCHECKED_CAST") putStringSet(key, value as Set<String>) }
        } } }.commit()
    }
    private inner class BookServer : NanoHTTPD(0) {
        val bad = ConcurrentHashMap.newKeySet<Int>()
        val requests = CopyOnWriteArrayList<Pair<Int, Int>>()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        @Volatile var block = false
        fun base(index: Int) = "http://127.0.0.${index + 1}:$listeningPort"
        override fun serve(session: IHTTPSession): Response {
            if (!session.uri.startsWith("/books/")) return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Owned not found")
            val index = session.uri.substringAfterLast('/').toInt()
            val page = session.parameters["page"]?.firstOrNull()?.toIntOrNull() ?: 1
            requests.add(index to page)
            if (block) { started.countDown(); release.await(15, TimeUnit.SECONDS) }
            if (index in bad) return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Owned failed source")
            val body = if (page == 1) "<div class='book'><a class='title' href='/owned-book/$token/$index'>${name(index)}</a><span class='author'>$author</span></div>" else ""
            return newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", "<html><meta charset='utf-8'><body>$body</body></html>")
        }
    }
}
