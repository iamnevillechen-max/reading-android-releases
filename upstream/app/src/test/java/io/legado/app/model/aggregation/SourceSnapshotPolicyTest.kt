package io.legado.app.model.aggregation

import org.junit.Assert.*
import org.junit.Test

class SourceSnapshotPolicyTest {
    @Test fun oneRuleEditDoesNotRescanTwentyThousandUnchangedSources() {
        val sources = (0 until 21_149).mapTo(hashSetOf()) { "owned-$it" }
        val retained = SourceSnapshotPolicy.retained(sources, sources, setOf("owned-10"))
        assertEquals(21_148, retained.size)
        assertFalse("owned-10" in retained)
        assertTrue("owned-21148" in retained)
    }
    @Test fun additionsAndRemovalKeepOnlyEstablishedUnchangedRoutes() {
        assertEquals(setOf("b"), SourceSnapshotPolicy.retained(setOf("a", "b"), setOf("b", "c"), setOf("c")))
    }
    @Test fun aLostConnectionEpochCannotReuseUnprovenOldRules() {
        assertTrue(SourceSnapshotPolicy.retained(setOf("a"), setOf("a"), null).isEmpty())
    }
    @Test fun aNotificationWithoutChangedSourceIdsRetainsEveryCurrentPage() {
        assertEquals(setOf("a", "b"), SourceSnapshotPolicy.retained(setOf("a", "b"), setOf("a", "b"), emptySet()))
    }
}
