package io.legado.app.accessibility

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.UiAutomation
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.viewbinding.ViewBinding
import io.legado.app.R
import io.legado.app.data.entities.RssArticle
import io.legado.app.data.entities.SearchBook
import io.legado.app.ui.about.AboutActivity
import io.legado.app.ui.book.explore.ExploreShowAdapter
import io.legado.app.ui.book.search.SearchAdapter
import io.legado.app.ui.rss.article.BaseRssArticlesAdapter
import io.legado.app.ui.rss.article.RssArticlesAdapter
import io.legado.app.ui.rss.article.RssArticlesAdapter1
import io.legado.app.ui.rss.article.RssArticlesAdapter2
import io.legado.app.utils.GSON
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Real production adapters and framework nodes, with self-written data and no database writes. */
@RunWith(AndroidJUnit4::class)
class ContentRowAccessibilityTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val token = System.nanoTime().toString()
    private var observingAutomation: UiAutomation? = null
    private var originalAutomationFlags: Int? = null

    @Test fun discoveryExposesOneNodePerBookAndOneMoveOpensTheNextBookEvenWithAHeader() = withList { scenario, list ->
        val books = (1..3).map { book(it) }
        var clicked: String? = null
        var held: String? = null
        lateinit var adapter: ExploreShowAdapter
        scenario.onActivity { activity ->
            adapter = ExploreShowAdapter(activity, object : ExploreShowAdapter.CallBack {
                override fun isInBookshelf(book: SearchBook) = false
                override fun showBookInfo(book: SearchBook) { clicked = book.bookUrl }
            })
            adapter.addHeaderView { parent ->
                val view = TextView(parent.context).apply {
                    text = "Owned list header"
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }
                object : ViewBinding { override fun getRoot() = view }
            }
            adapter.setOnItemLongClickListener { _, item -> held = item.bookUrl; true }
            adapter.setItems(books)
            list.adapter = adapter
        }
        await(scenario) { list.findViewHolderForAdapterPosition(3) != null }
        holdForTalkBackIfRequested(scenario, list)
        val tree = activeNodes()
        books.forEach { book ->
            val node = tree.filter { it.description.startsWith(book.name + "，") }.single()
            assertEquals("Cover, title, author, categories and introduction must be one leaf", 0, node.children)
            assertTrue(node.clickable)
        }
        scenario.onActivity {
            val first = list.findViewHolderForAdapterPosition(1)!!.itemView
            assertSingleRow(first)
            assertTrue(first.requestFocusFromTouch())
        }
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN)
        await(scenario) { it.currentFocus === list.findViewHolderForAdapterPosition(2)?.itemView }
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
        await(scenario) { clicked == books[1].bookUrl }
        scenario.onActivity {
            val second = list.findViewHolderForAdapterPosition(2)!!.itemView
            assertTrue(second.performAccessibilityAction(AccessibilityNodeInfo.ACTION_LONG_CLICK, null))
            assertEquals(books[1].bookUrl, held)
            val first = list.findViewHolderForAdapterPosition(1)!!.itemView
            assertTrue(first.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null))
            assertEquals("Header offsets must not open the neighbouring book", books[0].bookUrl, clicked)
        }
    }

    @Test fun searchPayloadsRefreshTheSingleDescriptionAndKeepTheCurrentBookAction() = withList { scenario, list ->
        val old = book(1).apply { addOrigin("https://owned-second.invalid") }
        val replacement = old.copy(bookUrl = old.bookUrl + "/new", originName = "Updated source $token",
            latestChapterTitle = null, intro = null, kind = null)
        var shelf = true
        var clicked: String? = null
        lateinit var adapter: SearchAdapter
        scenario.onActivity { activity ->
            adapter = SearchAdapter(activity, object : SearchAdapter.CallBack {
                override fun isInBookshelf(book: SearchBook) = shelf
                override fun showBookInfo(name: String, author: String, bookUrl: String) { clicked = bookUrl }
            })
            adapter.setItems(listOf(old))
            list.adapter = adapter
        }
        await(scenario) { list.findViewHolderForAdapterPosition(0) != null }
        assertTrue(activeNodes().any { it.description.startsWith(old.name + "，") && it.children == 0 })
        scenario.onActivity {
            val first = list.findViewHolderForAdapterPosition(0)!!.itemView
            assertSingleRow(first)
            val text = first.contentDescription.toString()
            assertTrue(text.contains(old.originName))
            assertTrue(text.contains(context.getString(R.string.content_row_source_count, 2)))
            assertTrue(text.contains(context.getString(R.string.content_row_in_bookshelf)))
            assertFalse("Very long introductions must not slow every list announcement", text.contains("Unannounced ending"))
            adapter.setItems(listOf(replacement))
        }
        await(scenario) {
            list.findViewHolderForAdapterPosition(0)?.itemView?.contentDescription?.contains(replacement.originName) == true
        }
        scenario.onActivity {
            shelf = false
            adapter.updateItem(0, Bundle().apply { putBoolean("isInBookshelf", false) })
        }
        await(scenario) {
            list.findViewHolderForAdapterPosition(0)?.itemView?.contentDescription?.contains(
                context.getString(R.string.content_row_in_bookshelf)) == false
        }
        scenario.onActivity {
            val row = list.findViewHolderForAdapterPosition(0)!!.itemView
            assertSingleRow(row)
            val text = row.contentDescription.toString()
            assertFalse(text.contains(old.originName))
            assertFalse(text.contains(old.latestChapterTitle!!))
            assertFalse(text.contains("Owned introduction"))
            assertFalse(text.contains(context.getString(R.string.content_row_source_count, 2)))
            assertTrue(row.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null))
            assertEquals(replacement.bookUrl, clicked)
        }
    }

    @Test fun allSubscriptionStylesExposeOneArticleAndClearRecycledAggregateMetadata() = withList { scenario, list ->
        var aggregate = true
        var clicked: String? = null
        var held: String? = null
        val source = "Owned feed $token"
        val callback = object : BaseRssArticlesAdapter.CallBack {
            override val isGridLayout = true
            override val aggregateMode get() = aggregate
            override fun sourceLabel(origin: String) = source
            override fun readRss(rssArticle: RssArticle) { clicked = rssArticle.link }
        }
        for (style in 0..2) {
            val old = RssArticle(origin = "https://owned-feed.invalid/$token", title = "Article $style $token",
                link = "https://owned-feed.invalid/$token/old/$style", pubDate = "Owned date", read = true)
            val replacement = old.copy(title = "Replacement $style $token", link = old.link + "/new", pubDate = null, read = false)
            lateinit var adapter: BaseRssArticlesAdapter<*>
            scenario.onActivity { activity ->
                aggregate = true
                adapter = when (style) {
                    1 -> RssArticlesAdapter1(activity, callback)
                    2 -> RssArticlesAdapter2(activity, callback)
                    else -> RssArticlesAdapter(activity, callback)
                }
                adapter.setOnItemLongClickListener { _, item -> held = item.link; true }
                adapter.setItems(listOf(old))
                list.adapter = adapter
            }
            await(scenario) { list.findViewHolderForAdapterPosition(0)?.itemView?.contentDescription?.contains(old.title) == true }
            assertTrue(activeNodes().any { it.description.startsWith(old.title + "，") && it.children == 0 })
            scenario.onActivity {
                val row = list.findViewHolderForAdapterPosition(0)!!.itemView
                assertSingleRow(row)
                assertTrue(row.contentDescription.toString().contains(source))
                assertTrue(row.contentDescription.toString().contains("Owned date"))
                assertTrue(row.contentDescription.toString().contains(context.getString(R.string.content_row_read)))
                aggregate = false
                adapter.setItems(listOf(replacement))
            }
            await(scenario) { list.findViewHolderForAdapterPosition(0)?.itemView?.contentDescription?.toString() == replacement.title }
            scenario.onActivity {
                val row = list.findViewHolderForAdapterPosition(0)!!.itemView
                assertSingleRow(row)
                assertTrue(row.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null))
                assertEquals(replacement.link, clicked)
                assertTrue(row.performAccessibilityAction(AccessibilityNodeInfo.ACTION_LONG_CLICK, null))
                assertEquals(replacement.link, held)
            }
        }
    }

    private fun book(index: Int) = SearchBook(bookUrl = "https://owned-book.invalid/$token/$index",
        origin = "https://owned-source.invalid/$token", originName = "Owned source $index $token",
        name = "Owned book $index $token", author = "Owned writer", kind = "Fantasy,Adventure",
        latestChapterTitle = "Owned latest $index", intro = "Owned introduction " + "测试😀".repeat(100) + "Unannounced ending")

    private fun withList(action: (ActivityScenario<AboutActivity>, RecyclerView) -> Unit) {
        check(Build.HARDWARE in setOf("ranchu", "goldfish") && context.packageName == "io.multiread.reader.debug")
        ActivityScenario.launch(AboutActivity::class.java).use { scenario ->
            instrumentation.waitForIdleSync()
            lateinit var list: RecyclerView
            scenario.onActivity { activity ->
                activity.supportFragmentManager.executePendingTransactions()
                list = RecyclerView(activity).apply {
                    layoutManager = LinearLayoutManager(activity)
                    itemAnimator = null
                }
                activity.setContentView(list)
            }
            try { action(scenario, list) }
            finally {
                scenario.onActivity { list.adapter = null }
                observingAutomation?.let { automation ->
                    originalAutomationFlags?.let { flags ->
                        automation.serviceInfo = automation.serviceInfo.apply { this.flags = flags }
                    }
                }
                observingAutomation = null
                originalAutomationFlags = null
            }
        }
    }

    private fun assertSingleRow(row: View) {
        val node = row.createAccessibilityNodeInfo()
        try {
            assertEquals(row.contentDescription, node.contentDescription)
            assertEquals("Decorative and text descendants must not add focus stops", 0, node.childCount)
            assertTrue(node.isClickable)
            assertTrue(node.isFocusable)
            assertTrue(ViewCompat.isScreenReaderFocusable(row))
            assertEquals(ViewGroup.FOCUS_BLOCK_DESCENDANTS, (row as ViewGroup).descendantFocusability)
            for (index in 0 until row.childCount) {
                assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS,
                    row.getChildAt(index).importantForAccessibility)
            }
        } finally { node.recycle() }
    }

    private data class Node(val description: String, val children: Int, val clickable: Boolean)

    /** Optional bounded window for real TalkBack keyboard/gesture review before UiAutomation connects. */
    private fun holdForTalkBackIfRequested(scenario: ActivityScenario<AboutActivity>, list: RecyclerView) {
        val millis = InstrumentationRegistry.getArguments().getString("holdBookRowsMillis")?.toLongOrNull() ?: return
        check(millis in 1..60_000)
        val directory = File(context.filesDir, "accessibility-test").apply { mkdirs() }
        val state = File(directory, "book-row-state.json")
        val started = SystemClock.elapsedRealtime()
        val events = arrayListOf<Map<String, Long>>()
        fun persist(holding: Boolean, focused: Int) {
            val temporary = File(directory, "book-row-state.tmp")
            temporary.writeText(GSON.toJson(mapOf("ready" to true, "holding" to holding,
                "fixtureToken" to token, "bookCount" to 3, "firstAdapterPosition" to 1,
                "readyAtUptimeMillis" to started, "holdMillis" to millis,
                "latestFocusedAdapterPosition" to focused, "focusEvents" to events)))
            check(temporary.renameTo(state))
        }
        persist(holding = true, focused = -1)
        println("TALKBACK_BOOK_ROWS_READY books=3 firstAdapterPosition=1")
        val deadline = started + millis
        var lastFocused = -1
        try {
            while (SystemClock.elapsedRealtime() < deadline) {
                var focused = -1
                scenario.onActivity {
                    focused = (1..3).firstOrNull { position ->
                        val node = list.findViewHolderForAdapterPosition(position)?.itemView?.createAccessibilityNodeInfo()
                        try { node?.isAccessibilityFocused == true } finally { node?.recycle() }
                    } ?: -1
                }
                if (focused != lastFocused) {
                    println("TALKBACK_BOOK_ROW_FOCUS adapterPosition=$focused")
                    events.add(mapOf("elapsedMillis" to SystemClock.elapsedRealtime() - started,
                        "adapterPosition" to focused.toLong()))
                    lastFocused = focused
                    persist(holding = true, focused = focused)
                }
                SystemClock.sleep(50)
            }
        } finally { persist(holding = false, focused = lastFocused) }
    }

    private fun activeNodes(): List<Node> {
        val nodes = arrayListOf<Node>()
        fun visit(node: AccessibilityNodeInfo) {
            nodes.add(Node(node.contentDescription?.toString().orEmpty(), node.childCount, node.isClickable))
            for (index in 0 until node.childCount) node.getChild(index)?.let { child ->
                try { visit(child) } finally { child.recycle() }
            }
        }
        val automation = instrumentation.uiAutomation
        if (observingAutomation == null) {
            observingAutomation = automation
            originalAutomationFlags = automation.serviceInfo.flags
        }
        // UiAutomation includes explicitly hidden decorative views by default. TalkBack's
        // service configuration does not: inspect the same importance-filtered tree, without
        // dropping/filtering any returned child nodes in the test itself.
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags and AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS.inv()
        }
        instrumentation.waitForIdleSync()
        // Service connection/flag changes are asynchronous even after the app is idle.
        // Wait for this fixture's actual window, then inspect its complete returned tree.
        val deadline = SystemClock.elapsedRealtime() + 5_000
        do {
            if (Build.VERSION.SDK_INT >= 34) automation.clearCache()
            val root = automation.rootInActiveWindow
            if (root != null) {
                try {
                    if (root.packageName?.toString() == context.packageName) {
                        visit(root)
                        return nodes
                    }
                } finally { root.recycle() }
            }
            SystemClock.sleep(25)
        } while (SystemClock.elapsedRealtime() < deadline)
        error("Owned activity must have an accessible window for ${context.packageName}")
    }

    private fun await(scenario: ActivityScenario<AboutActivity>, condition: (AboutActivity) -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 5_000
        do {
            instrumentation.waitForIdleSync()
            var ready = false
            scenario.onActivity { ready = condition(it) }
            if (ready) return
            SystemClock.sleep(25)
        } while (SystemClock.elapsedRealtime() < deadline)
        fail("Owned list did not reach the expected bound state")
    }
}
