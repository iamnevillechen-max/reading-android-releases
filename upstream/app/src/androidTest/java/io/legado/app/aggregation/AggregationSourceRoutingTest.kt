package io.legado.app.aggregation

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ActivityInfo
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.RadioButton
import androidx.lifecycle.Lifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.legado.app.ui.aggregation.HorizontalCategoryView as TabLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onData
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.openActionBarOverflowOrOptionsMenu
import androidx.test.espresso.Espresso.pressBack
import androidx.test.espresso.UiController
import androidx.test.espresso.ViewAction
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleCallback
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import fi.iki.elonen.NanoHTTPD
import io.legado.app.R
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.RssSource
import io.legado.app.data.entities.RssReadRecord
import io.legado.app.data.entities.rule.ExploreRule
import io.legado.app.help.DefaultData
import io.legado.app.help.config.LocalConfig
import io.legado.app.help.source.VerifiedSourceBootstrap
import io.legado.app.constant.AppConst.appInfo
import io.legado.app.constant.PreferKey
import io.legado.app.model.ReadBook
import io.legado.app.model.aggregation.AggregationPolicy
import io.legado.app.model.multisource.core.StreamingHash
import io.legado.app.service.BaseReadAloudService
import io.legado.app.ui.book.explore.ExploreShowActivity
import io.legado.app.ui.main.MainActivity
import io.legado.app.ui.main.explore.ExploreFragment
import io.legado.app.ui.main.rss.RssFragment
import io.legado.app.ui.rss.article.RssArticlesFragment
import io.legado.app.ui.rss.article.RssSortActivity
import io.legado.app.ui.rss.read.ReadRssActivity
import io.legado.app.ui.widget.TitleBar
import io.legado.app.utils.GSON
import io.legado.app.utils.defaultSharedPreferences
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.allOf
import org.hamcrest.Matcher
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/** Real manifest singleTop activities, real menus/dialog choices and bound visible result rows. */
@RunWith(AndroidJUnit4::class)
class AggregationSourceRoutingTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val token = System.nanoTime().toString()
    private val bookNames = listOf("Route book A $token", "Route book B $token")
    private val articleNames = listOf("Feed story A $token", "Feed story B $token")
    private val fantasyNames get() = bookNames.map { "Fantasy $it" }
    private val newsGroup = "都市／現實"
    private val cultureGroup = "出版／經典"
    private val webGroup = "玄幻／奇幻"
    private val author = "Owned Fixture Writer"
    private val activities = linkedSetOf<Activity>()
    private val scenarios = mutableListOf<ActivityScenario<*>>()
    private val lifecycle = ActivityLifecycleCallback { activity, stage ->
        if (stage == Stage.CREATED && (activity is ExploreShowActivity || activity is RssSortActivity ||
            activity is MainActivity || activity is ReadRssActivity)) {
            activities.add(activity)
        }
    }
    private lateinit var server: RoutesServer
    private var books = emptyList<BookSource>()
    private var feeds = emptyList<RssSource>()
    private var ownedWeb: RssSource? = null
    private var registered = false
    private var ownsData = false
    private val preferencesBefore = linkedMapOf<SharedPreferences, Map<String, Any?>>()
    private var ownedRecord: RssReadRecord? = null

    @Before fun prepare() {
        // App.onCreate may seed its four bundled web shortcuts. They are not article feeds,
        // are excluded from aggregation, and remain untouched throughout these tests.
        val bundledWeb = DefaultData.rssSources.filter { it.singleUrl }.associateBy { it.sourceUrl }
        val onlyBundledWeb = appDb.rssSourceDao.all.all { actual ->
            val expected = bundledWeb[actual.sourceUrl]
            actual.singleUrl && expected != null && GSON.toJsonTree(actual) == GSON.toJsonTree(expected)
        }
        check(appDb.bookSourceDao.allPart.isEmpty() && onlyBundledWeb &&
            appDb.bookDao.allBookCount == 0 && ReadBook.book == null && !BaseReadAloudService.isRun) {
            "Dedicated idle installation required; only exact bundled web shortcuts may pre-exist, never user sources or books"
        }
        val sourceMigration = context.getSharedPreferences(VerifiedSourceBootstrap.PREFS_NAME, Context.MODE_PRIVATE)
        preferencesBefore[sourceMigration] = mapOf(VerifiedSourceBootstrap.VERSION_KEY to sourceMigration.all[VerifiedSourceBootstrap.VERSION_KEY])
        check(sourceMigration.edit().putInt(VerifiedSourceBootstrap.VERSION_KEY, VerifiedSourceBootstrap.PACK_VERSION).commit())
        assertEquals(ActivityInfo.LAUNCH_SINGLE_TOP, context.packageManager.getActivityInfo(
            ComponentName(context, ExploreShowActivity::class.java), 0).launchMode)
        assertEquals(ActivityInfo.LAUNCH_SINGLE_TOP, context.packageManager.getActivityInfo(
            ComponentName(context, RssSortActivity::class.java), 0).launchMode)
        instrumentation.runOnMainSync {
            ActivityLifecycleMonitorRegistry.getInstance().addLifecycleCallback(lifecycle)
            registered = true
        }
        server = RoutesServer().also { it.start(1_000, false) }
        val base = "http://127.0.0.1:${server.listeningPort}"
        books = (0..1).map { index ->
            val key = if (index == 0) "a" else "b"
            BookSource(bookSourceUrl = "$base/source/$key", bookSourceName = "Switch source ${key.uppercase()} $token",
                bookSourceGroup = "owned-routing-fixture", customOrder = index, enabled = true, enabledExplore = true,
                exploreUrl = """[{"title":"都市","url":"$base/explore/$key?page={{page}}"},
                    {"title":"奇幻","url":"$base/explore/$key-fantasy?page={{page}}"}]""".trimIndent(),
                ruleExplore = ExploreRule(bookList = ".book", name = ".name@text", author = ".author@text",
                    bookUrl = "a@href", intro = ".intro@text"))
        }
        feeds = (0..1).map { index ->
            val key = if (index == 0) "a" else "b"
            RssSource(sourceUrl = "$base/rss/$key.xml", sourceName = "Switch feed ${key.uppercase()} $token",
                sourceGroup = if (index == 0) newsGroup else cultureGroup, customOrder = index, enabled = true,
                sortUrl = "懸疑::$base/rss/$key.xml",
                singleUrl = false, articleStyle = 0, enableJs = false)
        }
        ownsData = true
        appDb.bookSourceDao.insert(*books.toTypedArray())
        appDb.rssSourceDao.insert(*feeds.toTypedArray())
        ownedWeb = RssSource(sourceUrl = "$base/web-entry", sourceName = "Owned web $token",
            sourceGroup = webGroup, sortUrl = "$base/web-entry", singleUrl = true, enabled = true, customOrder = 2)
            .also { appDb.rssSourceDao.insert(it) }
    }

    @After fun cleanup() {
        if (registered) {
            instrumentation.runOnMainSync {
                activities.forEach { if (!it.isFinishing && !it.isDestroyed) it.finish() }
            }
            scenarios.forEach { it.close() }
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                ActivityLifecycleMonitorRegistry.getInstance().removeLifecycleCallback(lifecycle)
            }
        }
        if (::server.isInitialized) server.stop()
        if (ownsData) {
            appDb.bookSourceDao.delete(*books.toTypedArray())
            appDb.rssSourceDao.delete(*feeds.toTypedArray())
            ownedWeb?.let { appDb.rssSourceDao.delete(it) }
            (bookNames + fantasyNames).forEach { appDb.searchBookDao.clear(it, author) }
            feeds.forEach { appDb.rssArticleDao.delete(it.sourceUrl) }
            val cacheKey = StreamingHash.sha256 { GSON.toJson(feeds, it) }
            File(context.cacheDir, "aggregate-rss/$cacheKey.json").delete()
            listOf(newsGroup, cultureGroup, "懸疑").forEach { category ->
                val selected = feeds.filter { io.legado.app.model.aggregation.RssCategories.includesSource(it, category) }
                val categoryKey = StreamingHash.sha256 { writer ->
                    GSON.toJson(selected, writer)
                    StreamingHash.field(writer, category)
                }
                File(context.cacheDir, "aggregate-rss/$categoryKey.json").delete()
            }
            ownedRecord?.let {
                appDb.openHelper.writableDatabase.execSQL("delete from rssReadRecords where record = ?", arrayOf(it.record))
            }
        }
        preferencesBefore.forEach { (preferences, original) ->
            val editor = preferences.edit()
            original.forEach { (key, value) -> when (value) {
                null -> editor.remove(key)
                is String -> editor.putString(key, value)
                is Boolean -> editor.putBoolean(key, value)
                is Long -> editor.putLong(key, value)
                is Int -> editor.putInt(key, value)
            } }
            check(editor.commit())
        }
    }

    @Test fun mainDiscoveryHasScrollableCategoriesAndChangesBothSourcesInline() {
        prepareMainPreferences()
        val scenario = ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java))
        scenarios.add(scenario)
        lateinit var main: MainActivity
        scenario.onActivity { main = it }
        awaitMain(main) { it.supportFragmentManager.fragments.filterIsInstance<RssFragment>().firstOrNull()?.view != null }
        SystemClock.sleep(350)
        assertEquals("Main's preloaded RSS child must make no request before its tab is visible", 0, server.feedRequests.get())
        onView(withId(R.id.menu_discovery)).perform(click())
        awaitMain(main) { it.supportFragmentManager.fragments.filterIsInstance<ExploreFragment>().firstOrNull()?.view
            ?.findViewById<TabLayout>(R.id.aggregation_categories)?.let { tabs -> tabs.adapter?.itemCount == 3 &&
                tabs.positionOf(AggregationPolicy.ALL_SOURCES) >= 0 } == true }
        selectCategory("都市")
        awaitMain(main) { visibleTexts(it.window.decorView).containsAll(bookNames) }
        instrumentation.runOnMainSync { assertNoUnifiedCategoryCounter(main) }
        val explore = main.supportFragmentManager.fragments.filterIsInstance<ExploreFragment>().first()
        instrumentation.runOnMainSync {
            val categories = explore.requireView().findViewById<TabLayout>(R.id.aggregation_categories)
            assertTrue(categories.layoutManager!!.canScrollHorizontally())
            assertTrue(explore.requireView().findViewById<RadioButton>(R.id.mode_all).isChecked)
            assertTrue(categories.positionOf("奇幻") >= 0)
        }
        selectCategory("奇幻")
        awaitMain(main) { visibleTexts(it.window.decorView).containsAll(fantasyNames) &&
            visibleTexts(it.window.decorView).none { text -> text in bookNames } }
        assertEquals(0, server.feedRequests.get())
        onView(allOf(withId(R.id.mode_single), isDisplayed())).perform(click())
        awaitMain(main) { visibleTexts(it.window.decorView).containsAll(books.map { book -> book.bookSourceName }) }
        onView(allOf(withId(R.id.mode_all), isDisplayed())).perform(click())
        awaitMain(main) { visibleTexts(it.window.decorView).containsAll(fantasyNames) }
        // Process restoration can retain the saved selected category while its transient
        // metadata is still cold; the first empty/progressive batch must not select another.
        instrumentation.runOnMainSync { explore.viewModel.aggregationCategories = emptyList() }
        scenario.recreate()
        scenario.onActivity { main = it }
        awaitMain(main) { visibleTexts(it.window.decorView).containsAll(fantasyNames) }
        assertEquals("Discovery restoration must not fetch the preloaded subscription tab", 0, server.feedRequests.get())
    }

    @Test fun mainSubscriptionsFilterByRealCategoriesInlineAndKeepModeProgressAndReadHistory() {
        prepareMainPreferences()
        ownedRecord = RssReadRecord("http://127.0.0.1:${server.listeningPort}/article/0/0", articleNames[0], 1_700_000_000_000L)
            .also { appDb.rssReadRecordDao.insertRecord(it) }
        val beforeSources = GSON.toJson(appDb.rssSourceDao.all)
        val scenario = ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java))
        scenarios.add(scenario)
        lateinit var main: MainActivity
        scenario.onActivity { main = it }
        onView(withId(R.id.menu_rss)).perform(click())
        awaitMain(main) { visibleTexts(it.window.decorView).containsAll(articleNames) &&
            it.supportFragmentManager.fragments.filterIsInstance<RssFragment>().firstOrNull()?.view
                ?.findViewById<TabLayout>(R.id.aggregation_categories)?.let { tabs ->
                    tabs.positionOf("懸疑") >= 0
                } == true }
        val rss = main.supportFragmentManager.fragments.filterIsInstance<RssFragment>().first()
        instrumentation.runOnMainSync {
            assertNoUnifiedCategoryCounter(main)
            val categories = rss.requireView().findViewById<TabLayout>(R.id.aggregation_categories)
            assertTrue(categories.layoutManager!!.canScrollHorizontally())
            assertTrue(categories.positionOf("懸疑") >= 0)
        }
        selectCategory(webGroup)
        val webEntryText = context.getString(R.string.aggregation_web_entries, 1)
        awaitMain(main) { activeFeed(rss)?.viewModel?.aggregateCategory == webGroup &&
            activeFeed(rss)?.viewModel?.aggregateWebSources?.value?.map { source -> source.sourceUrl } == listOf(ownedWeb!!.sourceUrl) &&
            visibleTexts(it.window.decorView).count { text -> text == webEntryText } == 1 }
        instrumentation.runOnMainSync {
            assertEquals("Only the active category may expose a visible web entry", 1,
                visibleTexts(main.window.decorView).count { it == webEntryText })
        }
        // The previous category is retained with a GONE fragment root; Espresso otherwise
        // matches that hidden header as well as the active category's header.
        onView(allOf(withText(webEntryText), isDisplayed())).perform(click())
        onData(equalTo(ownedWeb!!.sourceName)).inRoot(isDialog()).perform(click())
        awaitActivity(ReadRssActivity::class.java) { it.intent.getStringExtra("origin") == ownedWeb!!.sourceUrl }
        assertEquals("Web entries must not be fetched through the RSS engine", 0, server.webFeedRequests.get())
        pressBack()
        awaitMain(main) { activeFeed(rss)?.viewModel?.aggregateCategory == webGroup }
        // The literal sort name combines both feeds; no per-source category drill-down.
        selectCategory("懸疑")
        awaitMain(main) { visibleTexts(it.window.decorView).containsAll(articleNames) &&
            activeFeed(rss)?.viewModel?.aggregateCategory == "懸疑" }
        selectCategory(newsGroup)
        awaitMain(main) { visibleTexts(it.window.decorView).contains(articleNames[0]) &&
            !visibleTexts(it.window.decorView).contains(articleNames[1]) && activeFeed(rss)?.viewModel?.aggregateCategory == newsGroup }
        selectCategory("懸疑")
        awaitMain(main) { visibleTexts(it.window.decorView).containsAll(articleNames) &&
            activeFeed(rss)?.viewModel?.aggregateCategory == "懸疑" }
        val active = activeFeed(rss)!!
        awaitMain(main) { active.viewModel.aggregateArticles.value.orEmpty().size >= 24 }
        instrumentation.runOnMainSync {
            (active.requireView().findViewById<RecyclerView>(R.id.recycler_view).layoutManager as LinearLayoutManager)
                .scrollToPositionWithOffset(12, 0)
        }
        var previousPosition = -1
        awaitMain(main) {
            previousPosition = (active.requireView().findViewById<RecyclerView>(R.id.recycler_view).layoutManager as LinearLayoutManager)
                .findFirstVisibleItemPosition()
            previousPosition >= 10
        }
        onView(allOf(withId(R.id.mode_single), isDisplayed())).perform(click())
        awaitMain(main) { visibleTexts(it.window.decorView).containsAll(feeds.map { feed -> feed.sourceName }) }
        instrumentation.runOnMainSync {
            assertTrue(active.isHidden)
            assertEquals(Lifecycle.State.STARTED, active.lifecycle.currentState)
            assertFalse(active.viewModel.isLoading)
        }
        scenario.recreate()
        scenario.onActivity { main = it }
        awaitMain(main) { visibleTexts(it.window.decorView).containsAll(feeds.map { feed -> feed.sourceName }) }
        val restoredRss = main.supportFragmentManager.fragments.filterIsInstance<RssFragment>().first()
        instrumentation.runOnMainSync {
            assertTrue(restoredRss.requireView().findViewById<RadioButton>(R.id.mode_single).isChecked)
        }
        onView(allOf(withId(R.id.mode_all), isDisplayed())).perform(click())
        awaitMain(main) {
            val restored = activeFeed(restoredRss) ?: return@awaitMain false
            val manager = restored.view?.findViewById<RecyclerView>(R.id.recycler_view)?.layoutManager as? LinearLayoutManager
            restored.viewModel.aggregateCategory == "懸疑" && manager?.findFirstVisibleItemPosition() == previousPosition
        }
        assertEquals("Source management metadata must remain unchanged", beforeSources, GSON.toJson(appDb.rssSourceDao.all))
        assertEquals(ownedRecord, appDb.rssReadRecordDao.getRecords().first { it.record == ownedRecord!!.record })
        instrumentation.runOnMainSync {
            assertTrue(activeFeed(restoredRss)!!.viewModel.aggregateArticles.value.orEmpty()
                .first { it.link == ownedRecord!!.record }.read)
        }
    }

    private fun activeFeed(rss: RssFragment) = rss.childFragmentManager.fragments
        .filterIsInstance<RssArticlesFragment>().firstOrNull { !it.isHidden }

    private fun selectCategory(label: String) {
        onView(allOf(withId(R.id.aggregation_categories), isDisplayed())).perform(object : ViewAction {
            override fun getDescription() = "Scroll to and click the native category tab $label"
            override fun getConstraints(): Matcher<View> = isAssignableFrom(TabLayout::class.java)
            override fun perform(uiController: UiController, view: View) {
                val tabs = view as TabLayout
                val index = tabs.positionOf(label)
                check(index >= 0) { "Missing category $label" }
                tabs.scrollToPosition(index)
                uiController.loopMainThreadUntilIdle()
                val tabView = tabs.findViewHolderForAdapterPosition(index)?.itemView
                    ?: error("Category row was not attached after scrolling")
                assertTrue(tabView.performClick())
            }
        })
    }

    private fun prepareMainPreferences() {
        fun remember(preferences: SharedPreferences, keys: List<String>) {
            preferencesBefore[preferences] = keys.associateWith { preferences.all[it] }
        }
        val defaults = context.defaultSharedPreferences
        remember(defaults, listOf(PreferKey.defaultHomePage, PreferKey.showDiscovery, PreferKey.showRss,
            PreferKey.autoRefresh, PreferKey.autoCheckNewBackup))
        check(defaults.edit().putString(PreferKey.defaultHomePage, "bookshelf")
            .putBoolean(PreferKey.showDiscovery, true).putBoolean(PreferKey.showRss, true)
            .putBoolean(PreferKey.autoRefresh, false).putBoolean(PreferKey.autoCheckNewBackup, false).commit())
        remember(LocalConfig, listOf("privacyPolicyOk", "appVersionCode", "password", "appCrash", "lastBackup"))
        check(LocalConfig.edit().putBoolean("privacyPolicyOk", true).putLong("appVersionCode", appInfo.versionCode)
            .putString("password", "").putBoolean("appCrash", false).putLong("lastBackup", System.currentTimeMillis()).commit())
    }

    private fun awaitMain(main: MainActivity, condition: (MainActivity) -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 15_000
        var last = ""
        while (SystemClock.uptimeMillis() < deadline) {
            var passed = false
            instrumentation.runOnMainSync {
                val resumed = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                last = "resumed=${resumed.map { it.javaClass.simpleName }}, visible=${visibleTexts(main.window.decorView)}"
                passed = main in resumed && !main.isFinishing && condition(main)
            }
            if (passed) return
            SystemClock.sleep(20)
        }
        fail("Expected same MainActivity with inline content; $last")
    }

    private fun awaitActivity(type: Class<out Activity>, condition: (Activity) -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (SystemClock.uptimeMillis() < deadline) {
            var passed = false
            instrumentation.runOnMainSync {
                passed = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                    .any { type.isInstance(it) && !it.isFinishing && condition(it) }
            }
            if (passed) return
            SystemClock.sleep(20)
        }
        fail("Expected resumed ${type.simpleName} with the selected web entry")
    }

    @Test fun exploreAllToSingleAndBackKeepsAResumedResultsActivityAndCorrectRows() {
        val all = context.getString(R.string.aggregation_all_explore)
        val initialIntent = Intent(context, ExploreShowActivity::class.java).apply {
            putExtra("aggregateCategory", "都市")
            putExtra("sourceUrl", AggregationPolicy.ALL_SOURCES)
            putExtra("exploreUrl", "都市")
            putExtra("exploreName", "$all · 都市")
        }
        scenarios.add(ActivityScenario.launch<ExploreShowActivity>(initialIntent))
        val initial = awaitResults(ExploreShowActivity::class.java, "sourceUrl", AggregationPolicy.ALL_SOURCES,
            "$all · 都市", bookNames)
        chooseSource(books[1].bookSourceName)
        val single = awaitResults(ExploreShowActivity::class.java, "sourceUrl", books[1].bookSourceUrl,
            "${books[1].bookSourceName} · 都市", listOf(bookNames[1]), listOf(bookNames[0]))
        assertNotSame("singleTop must not reuse the instance that is about to finish", initial, single)
        assertFalse(single.isFinishing)
        chooseSource(all)
        val aggregate = awaitResults(ExploreShowActivity::class.java, "sourceUrl", AggregationPolicy.ALL_SOURCES,
            "$all · 都市", bookNames)
        assertNotSame(single, aggregate)
        assertFalse(aggregate.isFinishing)
    }

    @Test fun rssAllToSingleAndBackKeepsAResumedResultsActivityAndCorrectRows() {
        val all = context.getString(R.string.aggregation_all_rss)
        scenarios.add(ActivityScenario.launch<RssSortActivity>(Intent(context, RssSortActivity::class.java).apply {
            putExtra("url", AggregationPolicy.ALL_SOURCES)
        }))
        val initial = awaitResults(RssSortActivity::class.java, "url", AggregationPolicy.ALL_SOURCES,
            all, articleNames)
        chooseSource(feeds[1].sourceName)
        val single = awaitResults(RssSortActivity::class.java, "url", feeds[1].sourceUrl,
            feeds[1].sourceName, listOf(articleNames[1]), listOf(articleNames[0]))
        assertNotSame(initial, single)
        assertFalse(single.isFinishing)
        chooseSource(all)
        val aggregate = awaitResults(RssSortActivity::class.java, "url", AggregationPolicy.ALL_SOURCES,
            all, articleNames)
        assertNotSame(single, aggregate)
        assertFalse(aggregate.isFinishing)
    }

    private fun chooseSource(label: String) {
        // The persistent source switch is available before overflow menu animation settles.
        onView(allOf(withId(R.id.mode_single), isDisplayed())).perform(click())
        onData(equalTo(label)).inRoot(isDialog()).perform(click())
    }

    private fun awaitResults(type: Class<out Activity>, extra: String, source: String, title: String,
        present: List<String>, absent: List<String> = emptyList()): Activity {
        val deadline = SystemClock.uptimeMillis() + 12_000
        var last = "No resumed activity"
        while (SystemClock.uptimeMillis() < deadline) {
            var result: Activity? = null
            instrumentation.runOnMainSync {
                val resumed = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                val activity = resumed.lastOrNull { !it.isFinishing }
                if (activity != null) {
                    val actualTitle = activity.findViewById<TitleBar>(R.id.title_bar)?.title?.toString()
                    val texts = visibleTexts(activity.window.decorView).toSet()
                    last = "top=${activity.javaClass.simpleName}, source=${activity.intent.getStringExtra(extra)}, " +
                        "title=$actualTitle, visible=$texts"
                    if (type.isInstance(activity) && activity.intent.getStringExtra(extra) == source &&
                        actualTitle == title && present.all { it in texts } && absent.none { it in texts }) {
                        result = activity
                    }
                }
            }
            result?.let { return it }
            SystemClock.sleep(20)
        }
        fail("Expected live ${type.simpleName} title=$title and rows=$present; $last")
        throw AssertionError("unreachable")
    }

    private fun assertNoUnifiedCategoryCounter(main: MainActivity) {
        assertFalse("The retired unified-category counter must not be shown",
            visibleTexts(main.window.decorView).any { it.contains("統一分類") || it.contains("统一分类") })
    }

    private fun visibleTexts(view: View): List<String> {
        if (!view.isShown) return emptyList()
        val result = arrayListOf<String>()
        if (view is TextView) result.add(view.text.toString())
        if (view is ViewGroup) for (index in 0 until view.childCount) result.addAll(visibleTexts(view.getChildAt(index)))
        return result
    }

    private inner class RoutesServer : NanoHTTPD("127.0.0.1", 0) {
        val feedRequests = AtomicInteger()
        val webFeedRequests = AtomicInteger()
        override fun serve(session: IHTTPSession): Response {
            val base = "http://127.0.0.1:$listeningPort"
            if (session.uri == "/web-entry") {
                if (session.headers["user-agent"].orEmpty().contains("okhttp", true)) webFeedRequests.incrementAndGet()
                return newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", "<html><body>Owned web entry</body></html>")
            }
            if (session.uri.startsWith("/explore/")) {
                val index = if (session.uri.substringAfterLast('/').startsWith("a")) 0 else 1
                val page = session.parameters["page"]?.firstOrNull()?.toIntOrNull() ?: 1
                val kind = if (session.uri.endsWith("-fantasy")) "fantasy/" else ""
                val body = if (page > 1) "" else "<div class='book'><a class='name' href='$base/book/$kind$index'>" +
                    (if (session.uri.endsWith("-fantasy")) fantasyNames[index] else bookNames[index]) +
                    "</a><span class='author'>$author</span><p class='intro'>Owned route fixture</p></div>"
                return newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8",
                    "<html><meta charset='utf-8'><body>$body</body></html>")
            }
            if (session.uri.startsWith("/rss/")) {
                feedRequests.incrementAndGet()
                val index = if (session.uri.endsWith("a.xml")) 0 else 1
                return newFixedLengthResponse(Response.Status.OK, "application/rss+xml; charset=utf-8",
                    """<?xml version="1.0" encoding="UTF-8"?><rss version="2.0"><channel>
                        <title>Owned feed</title><link>$base</link><description>Local routing fixture</description>
                        ${(0 until 24).joinToString("\n") { item -> """<item><title>${articleNames[index]}${if (item == 0) "" else " #$item"}</title>
                        <link>$base/article/$index/$item</link><pubDate>Mon, 26 Oct 2026 08:00:00 +0800</pubDate>
                        <description>Owned article</description></item>""" }}
                        </channel></rss>""".trimIndent())
            }
            return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Owned route not found")
        }
    }
}
