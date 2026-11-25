package io.legado.app.base.adapter

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.viewbinding.ViewBinding
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Uses real adapter notifications and bound rows, including the aggregate notice/footer. */
@RunWith(AndroidJUnit4::class)
class RecyclerAdapterDiffTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val identities = object : DiffUtil.ItemCallback<String>() {
        override fun areItemsTheSame(oldItem: String, newItem: String) = oldItem == newItem
        override fun areContentsTheSame(oldItem: String, newItem: String) = oldItem == newItem
    }

    @Test fun insertingNewestArticlePreservesNoticeFooterAndEveryBoundArticle() {
        lateinit var adapter: Rows
        lateinit var parent: RecyclerView
        val committed = CountDownLatch(1)
        val insertions = mutableListOf<Pair<Int, Int>>()
        instrumentation.runOnMainSync {
            parent = RecyclerView(context)
            adapter = Rows(context)
            adapter.addHeaderView { RowBinding(TextView(context).apply { text = "NOTICE" }) }
            adapter.addFooterView { RowBinding(TextView(context).apply { text = "FOOTER" }) }
            parent.adapter = adapter
            adapter.setItems(listOf("A共享", "A上午"))
            adapter.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
                override fun onItemRangeInserted(positionStart: Int, itemCount: Int) {
                    insertions.add(positionStart to itemCount)
                }
            })
            adapter.committed = { committed.countDown() }
            adapter.setItems(listOf("B午後", "A共享", "A上午"), identities)
        }
        assertTrue("Content diff did not commit", committed.await(5, TimeUnit.SECONDS))
        instrumentation.runOnMainSync {
            assertEquals(listOf(1 to 1), insertions)
            assertEquals(listOf("NOTICE", "B午後", "A共享", "A上午", "FOOTER"), bindRows(adapter, parent))
            assertEquals("B午後", adapter.getItemByLayoutPosition(1))
            assertEquals("A上午", adapter.getItemByLayoutPosition(3))
        }
    }

    @Test fun rapidSnapshotsCannotApplyAnOlderQueuedDiffAfterTheFinalSnapshot() {
        lateinit var adapter: Rows
        val committed = CountDownLatch(1)
        val commits = AtomicInteger()
        val finalRows = listOf("B午後", "A共享", "A上午")
        instrumentation.runOnMainSync {
            adapter = Rows(context)
            adapter.addHeaderView { RowBinding(TextView(context).apply { text = "NOTICE" }) }
            adapter.setItems(listOf("A共享"))
            adapter.committed = {
                commits.incrementAndGet()
                if (adapter.getItems() == finalRows) committed.countDown()
            }
            repeat(25) { adapter.setItems(listOf("過渡$it", "A共享"), identities) }
            adapter.setItems(finalRows, identities)
        }
        assertTrue("Final snapshot did not commit", committed.await(5, TimeUnit.SECONDS))
        instrumentation.waitForIdleSync()
        instrumentation.runOnMainSync {
            assertEquals(finalRows, adapter.getItems())
            assertEquals(1, commits.get())
        }
    }

    private fun bindRows(adapter: Rows, parent: RecyclerView): List<String> =
        (0 until adapter.itemCount).map { position ->
            val holder = adapter.createViewHolder(parent, adapter.getItemViewType(position))
            adapter.bindViewHolder(holder, position)
            (holder.itemView as TextView).text.toString()
        }

    private class RowBinding(val label: TextView) : ViewBinding {
        override fun getRoot(): View = label
    }

    private class Rows(context: Context) : RecyclerAdapter<String, RowBinding>(context) {
        var committed: (() -> Unit)? = null
        override fun getViewBinding(parent: ViewGroup) = RowBinding(TextView(context))
        override fun convert(holder: ItemViewHolder, binding: RowBinding, item: String, payloads: MutableList<Any>) {
            binding.label.text = item
        }
        override fun registerListener(holder: ItemViewHolder, binding: RowBinding) = Unit
        override fun onCurrentListChanged() { committed?.invoke() }
    }
}
