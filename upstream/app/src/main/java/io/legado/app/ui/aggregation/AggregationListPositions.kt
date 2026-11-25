package io.legado.app.ui.aggregation

import android.os.Bundle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.legado.app.utils.MD5Utils

/** Small viewport anchors only; this cache never retains rules, articles, or book content. */
object AggregationListPositions {
    data class Anchor(val item: String?, val position: Int, val offset: Int) {
        fun bundle() = Bundle().apply {
            putString("item", item)
            putInt("position", position)
            putInt("offset", offset)
        }
    }

    private val recent = object : LinkedHashMap<String, Anchor>(24, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Anchor>?) = size > 24
    }

    fun route(kind: String, source: String?, category: String?): String =
        MD5Utils.md5Encode("$kind\u0000$source\u0000$category")

    @Synchronized fun remember(route: String, anchor: Anchor?) {
        if (anchor != null) recent[route] = anchor
    }

    @Synchronized fun recalled(route: String): Anchor? = recent[route]

    fun from(bundle: Bundle?): Anchor? = bundle?.let {
        Anchor(it.getString("item"), it.getInt("position").coerceAtLeast(0), it.getInt("offset"))
    }

    fun capture(recycler: RecyclerView, itemKey: (Int) -> String?): Anchor? {
        val manager = recycler.layoutManager as? LinearLayoutManager ?: return null
        val position = manager.findFirstVisibleItemPosition()
        if (position < 0) return null
        val child = manager.findViewByPosition(position) ?: return null
        return Anchor(itemKey(position), position, manager.getDecoratedTop(child) - recycler.paddingTop)
    }

    /** Wait for a gradually arriving identity; clamp to a nearby row only after this load ends. */
    fun restore(recycler: RecyclerView, anchor: Anchor, keys: List<String?>, complete: Boolean): Boolean {
        if (keys.isEmpty()) return false
        val identity = anchor.item?.let { keys.indexOf(it) } ?: -1
        if (identity < 0 && !complete) return false
        val manager = recycler.layoutManager as? LinearLayoutManager ?: return false
        manager.scrollToPositionWithOffset(if (identity >= 0) identity else anchor.position.coerceAtMost(keys.lastIndex), anchor.offset)
        return true
    }
}
