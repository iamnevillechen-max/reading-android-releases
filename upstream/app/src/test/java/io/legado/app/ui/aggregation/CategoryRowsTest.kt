package io.legado.app.ui.aggregation

import io.legado.app.model.aggregation.AggregationPolicy.ALL_SOURCES
import io.legado.app.model.aggregation.RssCategories.UNCLASSIFIED
import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class CategoryRowsTest {
    private fun rows(names: List<String>, query: String = "", selected: String = ALL_SOURCES, complete: Boolean = true) =
        CategoryRows.build(names, query, selected, complete, "未分类")

    @Test fun filteringPreservesTheCurrentDestinationAndOnlyDeduplicatesExactKeys() {
        val names = listOf(" 小说 ", "小说", "小說", "", "AbC", "abc", UNCLASSIFIED)
        val full = rows(names)
        assertEquals(listOf(ALL_SOURCES, "小说", "小說", "AbC", "abc", UNCLASSIFIED), full.keys)
        val filter = rows(names, "aB", "小說")
        assertEquals(listOf(ALL_SOURCES, "AbC", "abc"), filter.keys)
        assertTrue(filter.retainSelection)
        assertEquals(-1, filter.position("小說"))
        assertEquals(listOf(ALL_SOURCES, UNCLASSIFIED), rows(names, "未分").keys)
        assertEquals("暂存", rows(names, selected = "暂存", complete = false).keys[1])
        assertFalse(rows(names, selected = "暂存").retainSelection)
    }

    @Test fun progressivelyFilteringAndReorderingUniqueKeysProducesValidRangeNotifications() {
        val random = Random(29)
        val all = List(80) { "分类$it" }
        var old = CategoryRows.empty
        repeat(300) {
            val next = rows(all.shuffled(random).take(random.nextInt(all.size)))
            val applied = old.keys.toMutableList()
            for (edit in requireNotNull(next.changesFrom(old))) {
                if (edit.insert) applied.addAll(edit.start, next.keys.subList(edit.start, edit.start + edit.count))
                else applied.subList(edit.start, edit.start + edit.count).clear()
            }
            assertEquals(next.keys, applied)
            old = next
        }
    }

    @Test fun addingSourcesOrChoosingALateCategoryDoesNotInvalidateTheEntireLargeStrip() {
        val names = List(21_149) { "原分类$it" }
        val old = rows(names)
        val added = rows(names + "新增")
        assertEquals(listOf(CategoryRows.Edit(old.keys.size, 1, true)), added.changesFrom(old))
        assertEquals(21_149, added.position(names.last()))
        assertEquals(emptyList<CategoryRows.Edit>(), rows(names, selected = names.last()).changesFrom(old))
        val fragmented = rows(names.filterIndexed { index, _ -> index % 2 == 0 })
        assertNull("Thousands of separated edits must not flood the main thread", fragmented.changesFrom(old))
    }
}
