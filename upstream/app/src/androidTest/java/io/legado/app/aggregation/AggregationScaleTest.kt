package io.legado.app.aggregation

import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.data.appDb
import io.legado.app.constant.PreferKey
import io.legado.app.constant.AppConst.appInfo
import io.legado.app.help.config.LocalConfig
import io.legado.app.help.source.VerifiedSourceBootstrap
import io.legado.app.utils.defaultSharedPreferences
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.RssSource
import io.legado.app.model.aggregation.AggregationPolicy.ALL_SOURCES
import io.legado.app.model.aggregation.AggregationDatabaseRevision
import io.legado.app.model.aggregation.ExploreAggregation
import io.legado.app.model.aggregation.RssCategories
import io.legado.app.ui.aggregation.HorizontalCategoryView
import io.legado.app.ui.main.MainActivity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Owned synthetic data only. Never run against a user's populated phone installation. */
@RunWith(AndroidJUnit4::class)
class AggregationScaleTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private fun requireDedicated() {
        check(appDb.bookSourceDao.allCount() == 0 && appDb.bookDao.allBookCount == 0) {
            "Dedicated installation with no user sources or books required"
        }
    }

    private fun withMainScenario(action: (ActivityScenario<MainActivity>) -> Unit) {
        requireDedicated()
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val defaults = context.defaultSharedPreferences
        val sourceMigration = context.getSharedPreferences(VerifiedSourceBootstrap.PREFS_NAME, android.content.Context.MODE_PRIVATE)
        val defaultKeys = listOf(PreferKey.defaultHomePage, PreferKey.showDiscovery, PreferKey.showRss,
            PreferKey.autoRefresh, PreferKey.autoCheckNewBackup)
        val localKeys = listOf("privacyPolicyOk", "appVersionCode", "password", "appCrash", "lastBackup")
        val before = listOf(defaults to defaultKeys.associateWith { defaults.all[it] },
            LocalConfig to localKeys.associateWith { LocalConfig.all[it] },
            sourceMigration to mapOf(VerifiedSourceBootstrap.VERSION_KEY to sourceMigration.all[VerifiedSourceBootstrap.VERSION_KEY]))
        check(defaults.edit().putString(PreferKey.defaultHomePage, "bookshelf")
            .putBoolean(PreferKey.showDiscovery, true).putBoolean(PreferKey.showRss, true)
            .putBoolean(PreferKey.autoRefresh, false).putBoolean(PreferKey.autoCheckNewBackup, false).commit())
        check(LocalConfig.edit().putBoolean("privacyPolicyOk", true).putLong("appVersionCode", appInfo.versionCode)
            .putString("password", "").putBoolean("appCrash", false).putLong("lastBackup", System.currentTimeMillis()).commit())
        // This fixture represents an installation whose bundled-source migration already ran.
        check(sourceMigration.edit().putInt(VerifiedSourceBootstrap.VERSION_KEY, VerifiedSourceBootstrap.PACK_VERSION).commit())
        try { ActivityScenario.launch(MainActivity::class.java).use(action) }
        finally {
            before.forEach { (preferences, values) ->
                preferences.edit().apply {
                    values.forEach { (key, value) ->
                        when (value) {
                            null -> remove(key)
                            is String -> putString(key, value)
                            is Boolean -> putBoolean(key, value)
                            is Long -> putLong(key, value)
                            is Int -> putInt(key, value)
                            is Float -> putFloat(key, value)
                            else -> error("Unexpected test preference type")
                        }
                    }
                }.commit()
            }
        }
    }

    @Test fun twentyThousandSourceCatalogueUsesBoundedRoundsAndReusesVerifiedSnapshot() = runBlocking {
        requireDedicated()
        val token = System.nanoTime().toString()
        val sources = (0 until 20_689).map { index ->
            BookSource(bookSourceUrl = "https://owned-scale-$token.example/$index",
                bookSourceName = "Owned source $index", customOrder = index,
                exploreUrl = "分类${index % 3}::https://owned-scale-$token.example/list/$index",
                jsLib = "/* unused parsing library ${"x".repeat(512)} */")
        }
        try {
            appDb.runInTransaction { sources.chunked(256).forEach { appDb.bookSourceDao.insert(*it.toTypedArray()) } }
            val context = ApplicationProvider.getApplicationContext<android.content.Context>()
            val before = SystemClock.elapsedRealtime()
            val routes = ExploreAggregation.routes(context, refresh = true)
            val elapsed = SystemClock.elapsedRealtime() - before
            assertTrue("A large configured library must publish usable categories", routes.isNotEmpty())
            assertTrue("One category round must materialize at most128 sources", routes.map { it.source.bookSourceUrl }.distinct().size <= 128)
            assertTrue("Category work exceeded its bounded budget: $elapsed ms", elapsed < 7_000)
            val warmBefore = SystemClock.elapsedRealtime()
            val warm = ExploreAggregation.routes(context)
            val warmElapsed = SystemClock.elapsedRealtime() - warmBefore
            assertEquals(routes, warm)
            assertFalse("A bounded first batch must not claim all20689 source categories were loaded",
                ExploreAggregation.catalogueProgress.complete)
            assertEquals(20_689, ExploreAggregation.catalogueProgress.totalSources)
            assertTrue(ExploreAggregation.catalogueProgress.checkedSources in 1..128)
            assertTrue("Reopening should reuse metadata rather than reparse full rules: $warmElapsed ms", warmElapsed < 2_000)
            val pinned = routes.first()
            ExploreAggregation.routesForCategory(context, pinned.category)
            repeat(3) { ExploreAggregation.routes(context, refresh = true) }
            repeat(2) {
                assertTrue("Continuing the bounded catalogue and repeated resume must retain the original selected source",
                    ExploreAggregation.routesForCategory(context, pinned.category).any { it.source.bookSourceUrl == pinned.source.bookSourceUrl })
            }
            val revision = AggregationDatabaseRevision.bookSources()
            sources.first().jsLib = "/* equal-order edited library */"
            appDb.bookSourceDao.update(sources.first())
            assertNotEquals("Rule-only edits must synchronously invalidate the metadata snapshot", revision,
                AggregationDatabaseRevision.bookSources())
            println("AGGREGATION_SCALE sources=20689 firstMs=$elapsed warmMs=$warmElapsed routes=${routes.size}")
        } finally {
            appDb.runInTransaction { sources.chunked(256).forEach { appDb.bookSourceDao.delete(*it.toTypedArray()) } }
        }
    }

    @Test fun rssProjectionKeeps443FeedCategoriesWithoutMaterializingParsingLibraries() = runBlocking {
        requireDedicated()
        val token = System.nanoTime().toString()
        val sources = (0 until 443).map { index ->
            RssSource(sourceUrl = "https://owned-rss-scale-$token.example/$index",
                sourceGroup = "Owned group $token", sortUrl = "Owned category $token $index::/articles",
                jsLib = "/* a parsing library is irrelevant to category labels */".repeat(64))
        }
        try {
            appDb.runInTransaction { appDb.rssSourceDao.insert(*sources.toTypedArray()) }
            val started = SystemClock.elapsedRealtime()
            val names = RssCategories.namesFromMetadata(appDb.rssSourceDao.flowCategorySpecs().first())
            assertEquals("The configured group and all 443 distinct sort labels remain available", 444, names.size)
            assertEquals(setOf("Owned group $token") + (0 until 443).map { "Owned category $token $it" }, names.toSet())
            assertEquals(443, appDb.rssSourceDao.all.count { it.sourceGroup == "Owned group $token" &&
                it.sortUrl?.startsWith("Owned category $token ") == true })
            assertTrue(SystemClock.elapsedRealtime() - started < 2_000)
        } finally {
            appDb.runInTransaction { appDb.rssSourceDao.delete(*sources.toTypedArray()) }
        }
    }

    private fun awaitUi(condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 8_000
        while (true) {
            var ready = false
            instrumentation.runOnMainSync { ready = condition() }
            if (ready) return
            check(SystemClock.elapsedRealtime() < deadline) { "Category update timed out" }
            SystemClock.sleep(20)
        }
    }

    @Test fun categoryStripRecyclesTwentyThousandLabelsAndKeepsKeyboardAndAccessibleSelection() {
        withMainScenario { scenario ->
            lateinit var strip: HorizontalCategoryView
            val names = object : AbstractList<String>() {
                override val size = 20_688
                override fun get(index: Int): String {
                    check(Looper.myLooper() != Looper.getMainLooper()) { "The UI thread read the source catalogue" }
                    return "类别$index"
                }
            }
            scenario.onActivity { activity ->
                strip = HorizontalCategoryView(activity).apply { layoutDirection = View.LAYOUT_DIRECTION_LTR }
                (activity.window.decorView as ViewGroup).addView(strip,
                    FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))
                strip.bind(activity.lifecycleScope, "全部", ALL_SOURCES) { }
                strip.submit(names, "", true)
            }
            awaitUi { strip.adapter!!.itemCount == 20_689 && strip.childCount > 0 }
            instrumentation.waitForIdleSync()
            scenario.onActivity {
                assertEquals(20_689, strip.adapter!!.itemCount)
                assertTrue("Category views must remain bounded by the viewport", strip.childCount in 1..32)
                val first = strip.findViewHolderForAdapterPosition(0)!!.itemView as TextView
                val node = first.createAccessibilityNodeInfo()
                assertEquals("android.widget.Button", node.className.toString())
                assertTrue(node.isSelected)
                node.recycle()
                first.textSize = 60f
                first.requestLayout()
            }
            instrumentation.waitForIdleSync()
            scenario.onActivity {
                val first = strip.findViewHolderForAdapterPosition(0)!!.itemView as TextView
                assertTrue("Large text must fit instead of clipping in a fixed48dp tab", first.height >= first.lineHeight + first.paddingTop + first.paddingBottom)
                strip.selectCategory("类别20687")
            }
            instrumentation.waitForIdleSync()
            scenario.onActivity {
                assertTrue(strip.findViewHolderForAdapterPosition(20_688)!!.itemView.requestFocusFromTouch())
                assertTrue(strip.childCount <= 32)
            }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_LEFT)
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
            instrumentation.waitForIdleSync()
            scenario.onActivity {
                assertEquals("Keyboard must reach and select an offscreen neighbouring category", 20_687, strip.selectedPosition)
                val chosen = strip.findViewHolderForAdapterPosition(20_687)!!.itemView.createAccessibilityNodeInfo()
                assertTrue(chosen.isSelected)
                chosen.recycle()
                (strip.parent as ViewGroup).removeView(strip)
            }
        }
    }

    @Test fun categoryStripKeepsAdapterSnapshotsConsistentWhileFlingingAndEditing() {
        withMainScenario { scenario ->
            lateinit var strip: HorizontalCategoryView
            var names = List(5_000) { "分类$it" }
            var chosen = "分类2500"
            var fullRefreshes = 0
            scenario.onActivity { activity ->
                strip = HorizontalCategoryView(activity)
                (activity.window.decorView as ViewGroup).addView(strip,
                    FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))
                strip.bind(activity.lifecycleScope, "全部", chosen) { chosen = it }
                strip.adapter!!.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
                    override fun onChanged() { fullRefreshes++ }
                })
                strip.submit(names, "", true)
            }
            awaitUi { strip.positionOf(chosen) >= 0 }
            repeat(24) { round ->
                names = names.drop(5) + List(7) { "新增$round-$it" }
                scenario.onActivity {
                    strip.fling(if (round % 2 == 0) 15_000 else -15_000, 0)
                    val committedCount = strip.adapter!!.itemCount
                    strip.submit(names, "", true)
                    assertEquals("The main-thread adapter keeps the committed snapshot during an update",
                        committedCount, strip.adapter!!.itemCount)
                    if (round % 5 == 0) strip.selectCategory("分类${1000 + round}")
                }
            }
            awaitUi { strip.positionOf(names.last()) >= 0 }
            scenario.onActivity {
                strip.stopScroll()
                assertEquals(names.size + 1, strip.adapter!!.itemCount)
                val ids = (0 until strip.adapter!!.itemCount).map { strip.adapter!!.getItemId(it) }
                assertEquals(ids.size, ids.toSet().size)
                assertEquals("Append and trim only require range changes", 0, fullRefreshes)
                assertTrue(strip.childCount in 1..32)
                assertEquals("A late snapshot must preserve a selection made while loading", "分类1020", chosen)
                strip.submit(names, "已过时的搜索", true)
                strip.submit(names, names.last(), true)
            }
            awaitUi { strip.adapter!!.itemCount == 2 && strip.positionOf(names.last()) == 1 }
            scenario.onActivity {
                assertEquals("Filtering the strip keeps the current article or book category", "分类1020", chosen)
                assertEquals(-1, strip.selectedPosition)
                strip.submit(names, "", true)
            }
            awaitUi { strip.selectedPosition > 0 && strip.findViewHolderForAdapterPosition(strip.selectedPosition) != null }
            scenario.onActivity {
                assertTrue(strip.findViewHolderForAdapterPosition(strip.selectedPosition)!!.itemView.isSelected)
                strip.submit(names - chosen, "", true)
            }
            awaitUi { chosen == ALL_SOURCES && strip.selectedPosition == 0 }
            scenario.onActivity { (strip.parent as ViewGroup).removeView(strip) }
        }
    }
}
