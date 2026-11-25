package io.legado.app.model.aggregation

import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class AggregateLoaderTest {
    @Test fun fairClaimsVisitEachInputOncePerRoundAndChangedSourceCountUsesTheSavedIndexSafely() {
        val cursor = AggregateLoader.Cursor(Int.MAX_VALUE)
        val round = cursor.round(11)
        val claimed = generateSequence { round.next() }.toList()
        assertEquals((0 until 11).toSet(), claimed.toSet())
        assertEquals(11, claimed.size)
        val resumed = AggregateLoader.Cursor(cursor.position())
        val smaller = resumed.round(3)
        assertEquals((0 until 3).toSet(), generateSequence { smaller.next() }.toSet())
        assertNull(resumed.round(0).next())
    }


    @Test fun largeCatalogueRoundsRemainBoundedAndResumeWithoutStarvingLaterSources() {
        val cursor = AggregateLoader.Cursor()
        val visited = linkedSetOf<Int>()
        repeat(162) {
            val round = cursor.round(20_689, maxClaims = 128)
            val batch = generateSequence { round.next() }.toList()
            assertTrue(batch.size <= 128)
            assertEquals(batch.size, batch.distinct().size)
            visited.addAll(batch)
        }
        assertEquals((0 until 20_689).toSet(), visited)
        assertTrue(cursor.position() in 0 until 20_689)
        assertNull(cursor.round(20_689, maxClaims = 0).next())
    }

    @Test fun failedAndSlowSourcesDoNotSuppressSuccessfulSources() = runBlocking {
        val output = mutableMapOf<Int, String?>()
        AggregateLoader.load(listOf("good", "throws", "slow", "also good"),
            timeoutMillis = 100, budgetMillis = 2_000,
            fetch = {
                when (it) {
                    "throws" -> error("failure fixture")
                    "slow" -> { delay(10_000); it }
                    else -> it
                }
            }, onResult = { index, result -> output[index] = result })
        assertEquals("good", output[0])
        assertEquals("also good", output[3])
        assertNull(output[1])
        assertNull(output[2])
        assertEquals(4, output.size)
    }

    @Test fun workersAreBoundedAndEverySourceHasAtMostOneAttempt() = runBlocking {
        val active = AtomicInteger()
        val peak = AtomicInteger()
        val calls = AtomicInteger()
        val output = mutableListOf<Int>()
        AggregateLoader.load((0 until 100).toList(), timeoutMillis = 500, budgetMillis = 5_000,
            fetch = { source ->
                calls.incrementAndGet()
                val concurrent = active.incrementAndGet()
                peak.updateAndGet { maxOf(it, concurrent) }
                try { delay(2); source } finally { active.decrementAndGet() }
            }, onResult = { _, result -> result?.let { output.add(it) } })
        assertEquals(100, calls.get())
        assertEquals((0 until 100).toSet(), output.toSet())
        assertTrue(peak.get() <= 4)
        assertEquals(0, active.get())
    }

    @Test fun leavingThePageCancelsOutstandingChildren() = runBlocking {
        val active = AtomicInteger()
        val job = launch {
            AggregateLoader.load((0 until 100).toList(), fetch = {
                active.incrementAndGet()
                try { delay(10_000); it } finally { active.decrementAndGet() }
            }, onResult = { _, _ -> fail("Cancelled request must not publish a result") })
        }
        while (active.get() == 0) delay(5)
        job.cancelAndJoin()
        assertEquals(0, active.get())
    }
    @Test fun resultRoundsSkipExhaustedSourcesWithoutResettingToTheSlowPrefix() = runBlocking {
        val cursor = AggregateLoader.Cursor()
        val exhausted = hashSetOf<Int>()
        val attempted = arrayListOf<Int>()
        val failures = arrayListOf<Int>()
        val results = arrayListOf<Int>()
        suspend fun round() = AggregateLoader.load((0 until 20).toList(),
            timeoutMillis = 100, budgetMillis = 1_000,
            claims = cursor.round(20, AggregateLoadingPolicy.SOURCES_PER_ROUND) { it !in exhausted },
            fetch = { source ->
                synchronized(attempted) { attempted.add(source) }
                if (source < 4) delay(10_000) else delay(2)
                source
            }, onResult = { index, result ->
                if (result == null) { failures.add(index); exhausted.add(index) }
                else results.add(result)
            })
        round()
        assertEquals((0 until 8).toSet(), attempted.toSet())
        assertEquals((0 until 4).toList(), failures.sorted())
        assertEquals((4 until 8).toSet(), results.toSet())
        round()
        assertEquals((0 until 16).toSet(), attempted.toSet())
        assertEquals("Untouched later sources must not be reported as failures", 4, failures.size)
        assertTrue(results.containsAll((8 until 16).toList()))
        repeat(3) { round() }
        assertTrue("Following scroll rounds must reach every healthy later source", results.containsAll((4 until 20).toList()))
        assertEquals("Failed sources are not retried by list layout", 4, attempted.count { it < 4 })
    }

    @Test fun anEntirelyExhaustedCatalogueStopsAfterOneBoundedInspection() {
        val cursor = AggregateLoader.Cursor(11)
        val round = cursor.round(20_689, maxClaims = 8) { false }
        assertNull(round.next())
        assertNull(round.next())
        assertEquals(11, cursor.position())
    }

}
