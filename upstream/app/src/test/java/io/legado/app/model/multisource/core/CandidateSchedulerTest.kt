package io.legado.app.model.multisource.core

import org.junit.Assert.*
import org.junit.Test

class CandidateSchedulerTest {
    private fun sources(count: Int, known: Set<Int> = emptySet()) = (0 until count).map {
        CandidateScheduler.Candidate("source-$it", it in known, it)
    }

    @Test fun irrelevantPrefixCannotStarveLaterSourceEvenAfterBackoffExpires() {
        val sources = sources(9)
        var state = CandidateScheduler.State()
        val first = CandidateScheduler.choose(sources, state, 0, 3)
        assertEquals(listOf("source-0", "source-1", "source-2"), first.candidates.map { it.id })
        state = first.state
        first.candidates.forEach { state = CandidateScheduler.record(state, it.id, CandidateScheduler.Outcome.NO_MATCH, 0) }
        val later = CandidateScheduler.choose(sources, state, CandidateScheduler.RETRY_DELAY + 1, 3)
        assertEquals(listOf("source-3", "source-4", "source-5"), later.candidates.map { it.id })
    }

    @Test fun timedOutKnownPrefixYieldsToUntriedSources() {
        val sources = sources(7, setOf(0, 1, 2))
        var state = CandidateScheduler.choose(sources, CandidateScheduler.State(), 0, 3).state
        for (id in 0..2) state = CandidateScheduler.record(state, "source-$id", CandidateScheduler.Outcome.TIMEOUT, 0)
        assertEquals(listOf("source-3", "source-4", "source-5"),
            CandidateScheduler.choose(sources, state, 1, 3).candidates.map { it.id })
        assertEquals(listOf("source-3", "source-4", "source-5"),
            CandidateScheduler.choose(sources, state, CandidateScheduler.RETRY_DELAY + 1, 3).candidates.map { it.id })
    }

    @Test fun verifiedSuccessfulSourceRetainsPriority() {
        val sources = sources(12)
        val state = CandidateScheduler.record(CandidateScheduler.State(cursor = "source-8"),
            "source-6", CandidateScheduler.Outcome.SUCCESS, 0)
        val selected = CandidateScheduler.choose(sources, state, 1, 3).candidates.map { it.id }
        assertEquals(listOf("source-6", "source-9", "source-10"), selected)
    }

    @Test fun boundedHistoryDoesNotRestartLargeLibraryDiscovery() {
        val sources = sources(20_689, (0 until 20_689).toSet())
        var state = CandidateScheduler.State()
        val seen = hashSetOf<String>()
        repeat(100) { round ->
            val plan = CandidateScheduler.choose(sources, state, round * 120_000L, 3)
            state = plan.state
            plan.candidates.forEach {
                assertTrue("A discarded old record must not reset the discovery cursor", seen.add(it.id))
                state = CandidateScheduler.record(state, it.id, CandidateScheduler.Outcome.NO_MATCH, round * 120_000L)
            }
        }
        assertEquals(300, seen.size)
        assertEquals(CandidateScheduler.MAX_HISTORY, state.history.size)
    }

    @Test fun configurationResetAndRemovedCursorRemainSafeAndBounded() {
        val sources = sources(5)
        val selected = CandidateScheduler.choose(sources, CandidateScheduler.State(cursor = "removed"), 0, 99)
        assertEquals(5, selected.candidates.size)
        assertEquals(sources, selected.candidates)
        assertEquals("source-4", selected.state.cursor)
    }

    @Test fun selectingWithoutOutcomeDoesNotCreateAFailureOrBackoff() {
        val sources = sources(5, setOf(0))
        val plan = CandidateScheduler.choose(sources, CandidateScheduler.State(), 0, 3)
        assertTrue(plan.state.history.isEmpty())
        assertTrue(CandidateScheduler.choose(sources, plan.state, 0, 6).candidates.any { it.id == "source-0" })
    }

    @Test fun rescuePrioritiesStillRotateAndKnownMatchesRetainPriority() {
        val sources = listOf(
            CandidateScheduler.Candidate("unmeasured", false, 0, 2),
            CandidateScheduler.Candidate("responsive", false, 1, 1),
            CandidateScheduler.Candidate("known", true, 2, 2),
            CandidateScheduler.Candidate("weighted", false, 3, 0)
        )
        val first = CandidateScheduler.choose(sources, CandidateScheduler.State(), 0, 1)
        assertEquals("known", first.candidates.single().id)
        val second = CandidateScheduler.choose(sources.filterNot { it.id == "known" }, first.state, 0, 1)
        assertEquals("weighted", second.candidates.single().id)
        val third = CandidateScheduler.choose(sources.filterNot { it.id in setOf("known", "weighted") }, second.state, 0, 1)
        assertEquals("responsive", third.candidates.single().id)
    }

    @Test fun cachedMissReusesBrieflyThenAllowsDiscoveryToContinue() {
        val sources = sources(8)
        val first = CandidateScheduler.choose(sources, CandidateScheduler.State(), 0, 3)
        var state = first.state
        first.candidates.forEach { state = CandidateScheduler.record(state, it.id, CandidateScheduler.Outcome.NO_MATCH, 0) }
        assertTrue(CandidateScheduler.cacheReusable(0, 1_000, corroborated = false))
        assertFalse(CandidateScheduler.cacheReusable(0, CandidateScheduler.RETRY_DELAY, corroborated = false))
        assertEquals("source-3", CandidateScheduler.choose(sources, state,
            CandidateScheduler.RETRY_DELAY, 3).candidates.first().id)
        assertTrue(CandidateScheduler.cacheReusable(0, CandidateScheduler.RETRY_DELAY, corroborated = true))
        assertFalse(CandidateScheduler.cacheReusable(0, 24 * 60 * 60 * 1000L, corroborated = true))
    }

    @Test fun newManualKnownResultOutranksAnAdvancedUnknownCursor() {
        val sources = sources(12).map {
            if (it.id == "source-1") it.copy(known = true, knownAt = 100) else it
        }
        val state = CandidateScheduler.State(cursor = "source-8")
        val selected = CandidateScheduler.choose(sources, state, 1_000, 1)
        assertEquals("source-1", selected.candidates.single().id)
        assertEquals(100L, selected.state.knownThrough!!.time)
        assertEquals("source-2", CandidateScheduler.choose(sources, selected.state, 1_001, 1).candidates.single().id)
    }

    @Test fun freshManualMetadataReplacesOldUnknownFailureOnceButRetainsNewFailureBackoff() {
        val sources = sources(12).map {
            if (it.id == "source-1") it.copy(known = true, knownAt = 100) else it
        }
        val oldFailure = CandidateScheduler.record(CandidateScheduler.State(cursor = "source-8"),
            "source-1", CandidateScheduler.Outcome.NO_MATCH, 1_000, knownAt = 0)
        val selected = CandidateScheduler.choose(sources, oldFailure, 1_001, 1)
        assertEquals("New manual metadata can replace the earlier unknown lookup failure",
            "source-1", selected.candidates.single().id)
        assertEquals("An in-flight preload must not repeat the same fresh-known exception", "source-2",
            CandidateScheduler.choose(sources, selected.state, 1_002, 1).candidates.single().id)
        val failed = CandidateScheduler.record(selected.state, "source-1", CandidateScheduler.Outcome.FAILURE,
            1_003, knownAt = 100)
        assertEquals("source-2", CandidateScheduler.choose(sources, failed, 1_004, 1).candidates.single().id)
        val later = failed.copy(cursor = "source-7")
        assertEquals("An expired failed known generation must not jump back before the cursor", "source-8",
            CandidateScheduler.choose(sources, later, 100_000, 1).candidates.single().id)
    }

    @Test fun automaticDiscoveryTimestampAlreadyRecordedAsAttemptedIsNotFreshAgain() {
        val sources = sources(12).map {
            if (it.id == "source-1") it.copy(known = true, knownAt = 100) else it
        }
        val state = CandidateScheduler.record(CandidateScheduler.State(cursor = "source-8"),
            "source-1", CandidateScheduler.Outcome.FAILURE, 1_000, knownAt = 100)
        assertEquals("source-9", CandidateScheduler.choose(sources, state, 1_001, 1).candidates.single().id)
        assertEquals("source-9", CandidateScheduler.choose(sources, state, 100_000, 1).candidates.single().id)
    }

    @Test fun knownResultsFromOneManualSearchRemainQueuedWhenTimestampsAreEqual() {
        val sources = sources(12, setOf(0, 1, 2)).map { it.copy(knownAt = if (it.known) 100 else 0) }
        var state = CandidateScheduler.State(cursor = "source-8")
        repeat(3) { index ->
            val selected = CandidateScheduler.choose(sources, state, 1_000, 1)
            assertEquals("source-$index", selected.candidates.single().id)
            state = selected.state
        }
        assertEquals("source-3", CandidateScheduler.choose(sources, state, 1_001, 1).candidates.single().id)
    }

    @Test fun concurrentSelectionsAdvanceWithoutWaitingForFailureOrPenalizingCancellation() {
        val sources = sources(12, setOf(0, 1, 2))
        val first = CandidateScheduler.choose(sources, CandidateScheduler.State(), 0, 3)
        val second = CandidateScheduler.choose(sources, first.state, 0, 3)
        assertEquals(listOf("source-3", "source-4", "source-5"), second.candidates.map { it.id })
        assertTrue(second.state.history.isEmpty())
        assertTrue(CandidateScheduler.choose(sources, second.state, 0, 6).candidates.isNotEmpty())
    }
}
