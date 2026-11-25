package io.legado.app.accessibility

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.graphics.drawable.toBitmap
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.R
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookGroup
import io.legado.app.help.config.AppConfig
import io.legado.app.ui.about.AboutActivity
import io.legado.app.ui.widget.image.CoverImageView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import io.legado.app.ui.main.bookshelf.style1.books.BaseBooksAdapter as ShelfOneCallback
import io.legado.app.ui.main.bookshelf.style1.books.BooksAdapterGrid as ShelfOneGrid
import io.legado.app.ui.main.bookshelf.style1.books.BooksAdapterList as ShelfOneList
import io.legado.app.ui.main.bookshelf.style2.BaseBooksAdapter as ShelfTwoCallback
import io.legado.app.ui.main.bookshelf.style2.BooksAdapterGrid as ShelfTwoGrid
import io.legado.app.ui.main.bookshelf.style2.BooksAdapterList as ShelfTwoList

/** Production shelf layouts/adapters; self-written metadata and a generated local image only. */
@RunWith(AndroidJUnit4::class)
class BookshelfCoverAccessibilityTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val token = System.nanoTime().toString()

    @Test fun everyShelfLayoutKeepsItsImageButOmitsTheCoverStopAfterImageLoadsAndReuse() = owned { scenario, image ->
        val layouts = listOf(R.layout.item_bookshelf_list, R.layout.item_bookshelf_grid,
            R.layout.item_bookshelf_list_group, R.layout.item_bookshelf_grid_group)
        for (layout in layouts) {
            lateinit var row: View
            lateinit var cover: CoverImageView
            val title = "Owned shelf title $layout $token"
            scenario.onActivity { activity ->
                row = activity.layoutInflater.inflate(layout, FrameLayout(activity), false)
                cover = row.findViewById(R.id.iv_cover)
                row.findViewById<TextView>(R.id.tv_name).text = title
                activity.setContentView(row)
            }
            await { row.isLaidOut && row.width > 0 }
            val completed = CountDownLatch(1)
            // The production String loader accepts absolute file paths; a file: URI would
            // become a literal File("file:/...") and exercise the fallback instead.
            scenario.onActivity { cover.load(image.absolutePath, title, "Owned author", onLoadFinish = completed::countDown) }
            assertTrue("Generated local cover must complete loading", completed.await(5, TimeUnit.SECONDS))
            await { runCatching { cover.drawable?.toBitmap(2, 2)?.getPixel(0, 0) == Color.MAGENTA }.getOrDefault(false) }
            assertDecorativeCover(scenario, cover)
            assertReadableTitleWithoutCover(title)

            // Reuse the same inflated view and exercise the error/placeholder callback too.
            val updated = "Owned replacement $layout $token"
            val failed = CountDownLatch(1)
            scenario.onActivity {
                row.findViewById<TextView>(R.id.tv_name).text = updated
                cover.load(File(image.parentFile, "missing-$layout-$token.png").absolutePath, updated,
                    "Owned replacement author", onLoadFinish = failed::countDown)
            }
            assertTrue("Missing cover must complete with its ordinary fallback", failed.await(5, TimeUnit.SECONDS))
            instrumentation.waitForIdleSync()
            assertDecorativeCover(scenario, cover)
            assertReadableTitleWithoutCover(updated)
        }
    }

    @Test fun bothShelfStylesKeepBookAndGroupActionsAndNamesAfterPartialRebinding() = owned { scenario, image ->
        var opened: Any? = null
        var held: Any? = null
        lateinit var list: RecyclerView
        scenario.onActivity { activity ->
            list = RecyclerView(activity).apply {
                layoutManager = LinearLayoutManager(activity)
                itemAnimator = null
            }
            activity.setContentView(list)
        }
        val book = Book(bookUrl = "https://owned-shelf.invalid/$token", name = "Owned book $token",
            author = "Owned shelf author", coverUrl = image.absolutePath, totalChapterNum = 12,
            durChapterIndex = 3, durChapterPos = 21, durChapterTitle = "Owned current chapter")
        val firstCallback = object : ShelfOneCallback.CallBack {
            override fun open(book: Book) { opened = book }
            override fun openBookInfo(book: Book) { held = book }
            override fun isUpdate(bookUrl: String) = false
        }
        for (grid in listOf(false, true)) {
            lateinit var adapter: ShelfOneCallback<*>
            scenario.onActivity { activity ->
                val fragment = activity.supportFragmentManager.findFragmentByTag("aboutFragment")!!
                adapter = if (grid) ShelfOneGrid(activity, firstCallback)
                    else ShelfOneList(activity, fragment, firstCallback, activity.lifecycle)
                adapter.setItems(listOf(book))
                list.adapter = adapter
            }
            await { list.findViewHolderForAdapterPosition(0)?.itemView?.findViewById<TextView>(R.id.tv_name)?.text == book.name }
            assertRowActions(scenario, list, book.name, { opened }, { held }, book)
            scenario.onActivity { adapter.notifyItemChanged(0, Bundle().apply { putString("cover", image.absolutePath) }) }
            instrumentation.waitForIdleSync()
            assertRowActions(scenario, list, book.name, { opened }, { held }, book)
        }

        var items: List<Any> = listOf(book)
        val secondCallback = object : ShelfTwoCallback.CallBack {
            override fun onItemClick(item: Any) { opened = item }
            override fun onItemLongClick(item: Any) { held = item }
            override fun isUpdate(bookUrl: String) = false
            override fun getItems() = items
        }
        for (grid in listOf(false, true)) {
            lateinit var adapter: ShelfTwoCallback<*>
            scenario.onActivity { activity ->
                items = listOf(book)
                adapter = if (grid) ShelfTwoGrid(activity, secondCallback) else ShelfTwoList(activity, secondCallback)
                adapter.updateItems()
                list.adapter = adapter
            }
            await { list.findViewHolderForAdapterPosition(0)?.itemView?.findViewById<TextView>(R.id.tv_name)?.text == book.name }
            assertRowActions(scenario, list, book.name, { opened }, { held }, book)
            scenario.onActivity { adapter.notifyItemChanged(0, Bundle().apply { putString("cover", image.absolutePath) }) }
            instrumentation.waitForIdleSync()
            assertRowActions(scenario, list, book.name, { opened }, { held }, book)

            val group = BookGroup(groupId = 1_048_576, groupName = "Owned group $grid $token", cover = image.absolutePath)
            scenario.onActivity { items = listOf(group); adapter.updateItems() }
            await { list.findViewHolderForAdapterPosition(0)?.itemView?.findViewById<TextView>(R.id.tv_name)?.text == group.groupName }
            assertRowActions(scenario, list, group.groupName, { opened }, { held }, group)
            val changed = group.copy(groupName = "Owned renamed group $grid $token",
                cover = File(image.parentFile, "missing-group-$grid-$token.png").absolutePath)
            scenario.onActivity { items = listOf(changed); adapter.updateItems() }
            await { list.findViewHolderForAdapterPosition(0)?.itemView?.findViewById<TextView>(R.id.tv_name)?.text == changed.groupName }
            assertRowActions(scenario, list, changed.groupName, { opened }, { held }, changed)
        }
        scenario.onActivity { list.adapter = null }
        assertEquals("Adapter navigation must not alter reading position", 3, book.durChapterIndex)
        assertEquals(21, book.durChapterPos)
    }

    private fun assertRowActions(scenario: ActivityScenario<AboutActivity>, list: RecyclerView, name: String,
        opened: () -> Any?, held: () -> Any?, expected: Any) {
        scenario.onActivity {
            val row = list.findViewHolderForAdapterPosition(0)!!.itemView
            val cover = row.findViewById<CoverImageView>(R.id.iv_cover)
            assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO, cover.importantForAccessibility)
            assertNull(cover.contentDescription)
            assertFalse(cover.isFocusable)
            assertEquals(View.VISIBLE, cover.visibility)
            assertTrue(row.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null))
            assertEquals(expected, opened())
            assertTrue(row.performAccessibilityAction(AccessibilityNodeInfo.ACTION_LONG_CLICK, null))
            assertEquals(expected, held())
        }
        assertReadableTitleWithoutCover(name)
    }

    private fun assertDecorativeCover(scenario: ActivityScenario<AboutActivity>, cover: CoverImageView) = scenario.onActivity {
        assertEquals(View.VISIBLE, cover.visibility)
        assertNotNull("Ordinary image/fallback remains visible", cover.drawable)
        assertNull(cover.contentDescription)
        assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO, cover.importantForAccessibility)
        assertFalse(cover.isFocusable)
    }

    private fun assertReadableTitleWithoutCover(name: String) {
        val automation = instrumentation.uiAutomation
        val before = automation.serviceInfo.flags
        try {
            automation.serviceInfo = automation.serviceInfo.apply {
                flags = (flags and AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS.inv()) or
                    AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
            }
            val texts = arrayListOf<String>()
            val ids = arrayListOf<String?>()
            fun visit(node: AccessibilityNodeInfo) {
                texts.add(node.text?.toString().orEmpty()); texts.add(node.contentDescription?.toString().orEmpty())
                ids.add(node.viewIdResourceName)
                for (index in 0 until node.childCount) node.getChild(index)?.let { child ->
                    try { visit(child) } finally { child.recycle() }
                }
            }
            // UiAutomation connection/flag updates and accessibility cache invalidation
            // arrive asynchronously, independently of the app's main-thread idle state.
            val until = SystemClock.uptimeMillis() + 5_000
            do {
                if (Build.VERSION.SDK_INT >= 34) automation.clearCache()
                texts.clear(); ids.clear()
                val root = automation.rootInActiveWindow
                if (root != null) {
                    try {
                        if (root.packageName?.toString() == context.packageName) visit(root)
                    } finally { root.recycle() }
                }
                if (name in texts) break
                SystemClock.sleep(25)
            } while (SystemClock.uptimeMillis() < until)
            assertTrue("Book/group name must remain readable", name in texts)
            assertFalse("A decorative cover must not be a separate accessibility stop",
                context.resources.getResourceName(R.id.iv_cover) in ids)
            assertFalse("No cover prefix may precede the book name", context.getString(R.string.img_cover) in texts)
        } finally {
            automation.serviceInfo = automation.serviceInfo.apply { flags = before }
        }
    }

    private fun await(condition: () -> Boolean) {
        val until = SystemClock.uptimeMillis() + 5_000
        do {
            instrumentation.waitForIdleSync()
            var ready = false
            instrumentation.runOnMainSync { ready = condition() }
            if (ready) return
            SystemClock.sleep(25)
        } while (SystemClock.uptimeMillis() < until)
        fail("Owned shelf did not finish binding")
    }

    private fun owned(action: (ActivityScenario<AboutActivity>, File) -> Unit) {
        check(context.packageName == "io.multiread.reader.debug" && Build.HARDWARE in setOf("ranchu", "goldfish"))
        val useDefault = AppConfig.useDefaultCover
        val image = File(context.cacheDir, "owned-shelf-cover-$token.png")
        check(!image.exists())
        val bitmap = Bitmap.createBitmap(3, 5, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.MAGENTA) }
        image.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        try {
            AppConfig.useDefaultCover = false
            ActivityScenario.launch(AboutActivity::class.java).use { scenario ->
                instrumentation.waitForIdleSync()
                scenario.onActivity { it.supportFragmentManager.executePendingTransactions() }
                action(scenario, image)
            }
        } finally {
            AppConfig.useDefaultCover = useDefault
            image.delete()
        }
    }
}
