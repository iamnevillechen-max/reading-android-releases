package io.legado.app.ui.aggregation

import io.legado.app.model.aggregation.AggregationPolicy.ALL_SOURCES
import io.legado.app.model.aggregation.RssCategories

/** Immutable category identities and visible positions, prepared off the UI thread. */
internal class CategoryRows private constructor(
    val keys: List<String>,
    private val positions: Map<String, Int>,
    val retainSelection: Boolean,
    val matches: Int
) {
    fun position(key: String) = positions[key] ?: -1

    companion object {
        val empty = CategoryRows(emptyList(), emptyMap(), true, 0)

        fun build(names: List<String>, query: String, selected: String, complete: Boolean,
                  unclassifiedLabel: String): CategoryRows {
            val visible = linkedSetOf(ALL_SOURCES)
            var found = selected == ALL_SOURCES
            for (name in names) {
                val key = name.trim()
                if (key.isEmpty() || key == ALL_SOURCES) continue
                if (key == selected) found = true
                val title = if (key == RssCategories.UNCLASSIFIED) unclassifiedLabel else key
                if (title.contains(query, ignoreCase = true)) visible.add(key)
            }
            val matches = visible.size - 1
            val keys = visible.toMutableList()
            // A partial catalogue cannot disprove the selected category restored after recreation.
            if (!found && !complete && query.isEmpty()) keys.add(1, selected)
            val positions = HashMap<String, Int>(keys.size)
            keys.forEachIndexed { index, key -> positions[key] = index }
            return CategoryRows(keys, positions, found || !complete, matches)
        }
    }

    /** Shared keys normally keep their order. Remove/insert runs around them in linear time,
     * including when a source reorders its menu. Highly fragmented changes use one stable-ID refresh. */
    data class Edit(val start: Int, val count: Int, val insert: Boolean)

    fun changesFrom(old: CategoryRows): List<Edit>? {
        val kept = BooleanArray(old.keys.size)
        val insertions = mutableListOf<Edit>()
        var previous = -1
        var start = 0
        for ((index, key) in keys.withIndex()) {
            val oldIndex = old.position(key)
            if (oldIndex > previous) {
                if (index > start) insertions.add(Edit(start, index - start, true))
                if (insertions.size > 64) return null
                kept[oldIndex] = true
                previous = oldIndex
                start = index + 1
            }
        }
        if (start < keys.size) insertions.add(Edit(start, keys.size - start, true))
        val edits = mutableListOf<Edit>()
        var end = kept.size
        for (index in kept.indices.reversed()) {
            if (kept[index]) {
                if (index + 1 < end) edits.add(Edit(index + 1, end - index - 1, false))
                if (edits.size + insertions.size > 64) return null
                end = index
            }
        }
        if (end > 0) edits.add(Edit(0, end, false))
        return edits + insertions
    }
}
