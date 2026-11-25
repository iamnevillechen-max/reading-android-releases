package io.legado.app.ui.aggregation

import android.content.Context
import android.graphics.Typeface
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import android.view.ViewGroup
import androidx.appcompat.widget.AppCompatTextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.primaryTextColor
import io.legado.app.model.aggregation.AggregationPolicy.ALL_SOURCES
import io.legado.app.model.aggregation.RssCategories
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A horizontal category strip whose attached views stay bounded even with thousands of labels. */
class HorizontalCategoryView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : RecyclerView(context, attrs) {
    private val manager = LinearLayoutManager(context, HORIZONTAL, false)
    private val categoryAdapter = CategoryAdapter()
    private var rows = CategoryRows.empty
    private var selectedKey = ALL_SOURCES
    private var lastQuery = ""
    private var lastNames: List<String>? = null
    private var lastComplete = false
    private var rendering: Job? = null
    private lateinit var scope: CoroutineScope
    private lateinit var allTitle: String
    private var unclassifiedTitle = ""
    private lateinit var selectionChanged: (String) -> Unit
    private var displayed: (String, Int) -> Unit = { _, _ -> }

    val selectedPosition get() = rows.position(selectedKey)
    fun positionOf(key: String) = rows.position(key)

    init {
        layoutManager = manager
        adapter = categoryAdapter
        itemAnimator = null
    }

    fun bind(scope: CoroutineScope, allTitle: String, selected: String, unclassifiedTitle: String = "",
             onDisplayed: (String, Int) -> Unit = { _, _ -> }, onSelected: (String) -> Unit) {
        this.scope = scope
        this.allTitle = allTitle
        this.unclassifiedTitle = unclassifiedTitle
        selectedKey = selected
        displayed = onDisplayed
        selectionChanged = onSelected
    }

    fun submit(names: List<String>, query: String, complete: Boolean) {
        val filter = query.trim()
        if (lastNames === names && lastQuery == filter && lastComplete == complete) return
        lastNames = names
        lastQuery = filter
        lastComplete = complete
        render(names, filter, complete)
    }

    private fun render(names: List<String>, query: String, complete: Boolean) {
        rendering?.cancel()
        val selected = selectedKey
        val old = rows
        rendering = scope.launch {
            val (next, edits) = withContext(Dispatchers.Default) {
                val next = CategoryRows.build(names, query, selected, complete, unclassifiedTitle)
                next to next.changesFrom(old)
            }
            val anchor = manager.findFirstVisibleItemPosition()
            val anchorKey = old.keys.getOrNull(anchor)
            val offset = manager.findViewByPosition(anchor)?.let {
                if (layoutDirection == View.LAYOUT_DIRECTION_RTL) width - paddingRight - manager.getDecoratedRight(it)
                else manager.getDecoratedLeft(it) - paddingLeft
            }
            rows = next
            if (!next.retainSelection) selectedKey = ALL_SOURCES
            if (edits == null) {
                categoryAdapter.notifyDataSetChanged()
                val position = anchorKey?.let(next::position) ?: -1
                if (position >= 0 && offset != null) manager.scrollToPositionWithOffset(position, offset)
            } else {
                for (edit in edits) {
                    if (edit.insert) categoryAdapter.notifyItemRangeInserted(edit.start, edit.count)
                    else categoryAdapter.notifyItemRangeRemoved(edit.start, edit.count)
                }
            }
            if (selected != selectedKey) {
                categoryAdapter.notifyItemChanged(selectedPosition)
                selectionChanged(selectedKey)
            }
            // Bring a restored or newly visible selection into reach. Ordinary updates leave
            // the viewport alone, including when the user has scrolled past the selected row.
            if (selectedPosition >= 0 && (old.position(selectedKey) < 0 || selected != selectedKey)) {
                scrollToPosition(selectedPosition)
            }
            displayed(query, next.matches)
        }
    }

    fun selectCategory(key: String) {
        val index = rows.position(key)
        if (index < 0 || selectedKey == key) return
        val oldIndex = selectedPosition
        selectedKey = key
        if (oldIndex >= 0) categoryAdapter.notifyItemChanged(oldIndex)
        categoryAdapter.notifyItemChanged(index)
        scrollToPosition(index)
        if (rendering?.isActive == true) lastNames?.let { render(it, lastQuery, lastComplete) }
        selectionChanged(key)
    }

    private inner class CategoryAdapter : Adapter<CategoryHolder>() {
        private val ids = hashMapOf<String, Long>()
        init { setHasStableIds(true) }
        override fun getItemId(position: Int): Long =
            ids.getOrPut(rows.keys[position]) { ids.size.toLong() }
        override fun getItemCount() = rows.keys.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CategoryHolder {
            val density = resources.displayMetrics.density
            return CategoryHolder(AppCompatTextView(context).apply {
                layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
                minimumHeight = (48 * density).toInt()
                gravity = Gravity.CENTER
                setPadding((16 * density).toInt(), (8 * density).toInt(), (16 * density).toInt(), (8 * density).toInt())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                isFocusable = true
                ViewCompat.setScreenReaderFocusable(this, true)
                ViewCompat.setAccessibilityDelegate(this, object : AccessibilityDelegateCompat() {
                    override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
                        super.onInitializeAccessibilityNodeInfo(host, info)
                        info.className = "android.widget.Button"
                        info.isSelected = host.isSelected
                    }
                })
                val ripple = TypedValue()
                if (context.theme.resolveAttribute(android.R.attr.selectableItemBackground, ripple, true))
                    setBackgroundResource(ripple.resourceId)
            })
        }
        override fun onBindViewHolder(holder: CategoryHolder, position: Int) {
            val key = rows.keys[position]
            val selected = key == selectedKey
            holder.label.apply {
                text = when (key) {
                    ALL_SOURCES -> allTitle
                    RssCategories.UNCLASSIFIED -> unclassifiedTitle
                    else -> key
                }
                tag = key
                isSelected = selected
                setTextColor(if (selected) context.accentColor else context.primaryTextColor)
                setTypeface(null, if (selected) Typeface.BOLD else Typeface.NORMAL)
                setOnClickListener { selectCategory(key) }
            }
        }
    }
    private class CategoryHolder(val label: AppCompatTextView) : ViewHolder(label)
}
