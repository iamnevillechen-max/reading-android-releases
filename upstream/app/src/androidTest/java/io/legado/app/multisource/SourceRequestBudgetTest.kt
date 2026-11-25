package io.legado.app.multisource

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.legado.app.model.multisource.SourceRequestGate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

/** Admission is synchronous up to the first suspension: no timing guesses or external requests. */
@RunWith(AndroidJUnit4::class)
class SourceRequestBudgetTest {
    @Test fun totalBudgetIsBoundedAndCancellationReleasesItsSlot() = runBlocking {
        val release = CompletableDeferred<Unit>()
        val active = AtomicInteger()
        val maximum = AtomicInteger()
        val entered = List(5) { CompletableDeferred<Unit>() }
        val jobs = List(5) { index ->
            async(start = CoroutineStart.UNDISPATCHED) {
                SourceRequestGate.request("https://budget-$index.invalid/chapter") {
                    val current = active.incrementAndGet()
                    while (true) {
                        val observed = maximum.get()
                        if (current <= observed || maximum.compareAndSet(observed, current)) break
                    }
                    entered[index].complete(Unit)
                    try { release.await() } finally { active.decrementAndGet() }
                }
            }
        }
        try {
            assertEquals(4, active.get())
            assertFalse("The fifth foreground request must wait", entered[4].isCompleted)
            jobs[0].cancelAndJoin()
            withTimeout(2_000) { entered[4].await() }
            assertEquals(4, active.get())
            assertEquals(4, maximum.get())
        } finally {
            release.complete(Unit)
            jobs.forEach { it.cancelAndJoin() }
        }
        assertEquals(0, active.get())
    }

    @Test fun backgroundCannotConsumeTheReadersReservedSlots() = runBlocking {
        val release = CompletableDeferred<Unit>()
        val backgroundEntered = List(3) { CompletableDeferred<Unit>() }
        val jobs = (0..2).map { index ->
            async(start = CoroutineStart.UNDISPATCHED) {
                SourceRequestGate.request("https://preload-$index.invalid/chapter", foreground = false) {
                    backgroundEntered[index].complete(Unit)
                    release.await()
                }
            }
        }
        val foregroundEntered = CompletableDeferred<Unit>()
        val reader = async(start = CoroutineStart.UNDISPATCHED) {
            SourceRequestGate.request("https://reader.invalid/chapter") {
                foregroundEntered.complete(Unit)
                release.await()
            }
        }
        try {
            assertEquals(true, backgroundEntered[0].isCompleted)
            assertEquals(true, backgroundEntered[1].isCompleted)
            assertFalse(backgroundEntered[2].isCompleted)
            assertEquals("A reader must enter while two background jobs are held", true,
                foregroundEntered.isCompleted)
            jobs[0].cancelAndJoin()
            withTimeout(2_000) { backgroundEntered[2].await() }
        } finally {
            release.complete(Unit)
            (jobs + reader).forEach { it.cancelAndJoin() }
        }
    }

    @Test fun oneHostIsSerializedAcrossPortsWhileOtherHostsCanProceed() = runBlocking {
        val release = CompletableDeferred<Unit>()
        val entered = List(3) { CompletableDeferred<Unit>() }
        val urls = listOf("https://same.invalid:443/first", "http://same.invalid:8080/second",
            "https://another.invalid/chapter")
        val jobs = urls.mapIndexed { index, url ->
            async(start = CoroutineStart.UNDISPATCHED) {
                SourceRequestGate.request(url) {
                    entered[index].complete(Unit)
                    release.await()
                }
            }
        }
        try {
            assertEquals(true, entered[0].isCompleted)
            assertFalse("Different ports do not create additional host slots", entered[1].isCompleted)
            assertEquals(true, entered[2].isCompleted)
            jobs[0].cancelAndJoin()
            withTimeout(2_000) { entered[1].await() }
        } finally {
            release.complete(Unit)
            jobs.forEach { it.cancelAndJoin() }
        }
    }
}
