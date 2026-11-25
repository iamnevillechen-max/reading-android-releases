package io.legado.app.model.aggregation

import org.junit.Assert.*
import org.junit.Test

class RssCategoryMenuStoreTest {
    @Test fun everyOriginalLabelSurvivesBeyondElevenEvenWhenTwoLabelsUseTheSameUrl() {
        val store = RssCategoryMenuStore()
        store.synchronize(mapOf("owned" to "revision-1"))
        val menu = (0..14).map { "  Owned $it  " to "/same-url" } + ("Owned 14" to "/same-url")
        assertTrue(store.publish("owned", "revision-1", menu))
        assertEquals((0..14).map { "Owned $it" }, store.names())
        assertEquals(15, store.menu("owned")!!.size)
    }

    @Test fun changedOrRemovedSourceRejectsItsLateMenuWithoutDiscardingOtherSources() {
        val store = RssCategoryMenuStore()
        store.synchronize(mapOf("owned\u0000opaque" to "a1", "other" to "b1"))
        store.publish("owned\u0000opaque", "a1", listOf("Before" to "/before"))
        store.publish("other", "b1", listOf("Other original" to "/other"))
        store.synchronize(mapOf("owned\u0000opaque" to "a2", "other" to "b1"))
        assertFalse(store.publish("owned\u0000opaque", "a1", listOf("Late" to "/late")))
        assertNull(store.menu("owned\u0000opaque"))
        assertEquals(listOf("Other original"), store.names())
        store.synchronize(mapOf("other" to "b1"))
        assertFalse(store.publish("owned\u0000opaque", "a2", listOf("Deleted" to "/gone")))
    }

    @Test fun failureEmptyResultAndUnchangedMetadataKeepTheLastSuccessfulMenu() {
        val store = RssCategoryMenuStore()
        store.synchronize(mapOf("owned" to "r"))
        store.publish("owned", "r", listOf("Known original" to "/known"))
        val revision = store.revision
        assertFalse(store.synchronize(mapOf("owned" to "r")))
        assertFalse(store.publish("owned", "r", emptyList()))
        assertFalse(store.publish("owned", "r", listOf("No route" to "   ")))
        assertEquals(revision, store.revision)
        assertEquals(listOf("Known original" to "/known"), store.menu("owned"))
    }

    @Test fun duplicatesMergeExactlyWhileDifferentSpacingAndProviderMembershipStayDistinct() {
        val store = RssCategoryMenuStore()
        store.synchronize(mapOf("one" to "1", "two" to "2"))
        store.publish("one", "1", listOf("  赛博 修仙  " to "/one", "奇幻" to "/fantasy"))
        store.publish("two", "2", listOf("赛博 修仙" to "/two", "赛博修仙" to "/different"))
        assertEquals(listOf("赛博 修仙", "奇幻", "赛博修仙"), store.names())
        assertEquals(listOf("赛博 修仙" to "/two", "赛博修仙" to "/different"), store.menu("two"))
    }
}
