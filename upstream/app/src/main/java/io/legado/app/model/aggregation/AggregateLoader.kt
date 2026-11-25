package io.legado.app.model.aggregation

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicInteger

/** Four workers, one attempt per input, bounded wall time, structured cancellation. */
object AggregateLoader {
    fun interface Claims { fun next(): Int? }

    /** One owner runs successive rounds. Claiming advances before fetch, including on cancellation. */
    class Cursor(initialPosition: Int = 0) {
        private var position = initialPosition.coerceAtLeast(0)
        @Synchronized fun position(): Int = position
        fun round(size: Int, maxClaims: Int = size, eligible: (Int) -> Boolean = { true }): Claims {
            var claimed = 0
            var inspected = 0
            return Claims {
                synchronized(this) {
                    while (size > 0 && inspected < size && claimed < minOf(size, maxClaims.coerceAtLeast(0))) {
                        val index = position % size
                        position = (index + 1) % size
                        inspected++
                        if (eligible(index)) {
                            claimed++
                            return@synchronized index
                        }
                    }
                    null
                }
            }
        }
    }

    suspend fun <S, T> load(
        sources: List<S>,
        timeoutMillis: Long = 8_000,
        budgetMillis: Long = 24_000,
        claims: Claims? = null,
        fetch: suspend (S) -> T,
        onResult: suspend (Int, T?) -> Unit
    ) = withContext(Dispatchers.IO) {
        val cursor = AtomicInteger()
        val delivery = Mutex()
        withTimeoutOrNull(budgetMillis) {
            coroutineScope {
                repeat(minOf(4, sources.size)) {
                    launch {
                        while (true) {
                            coroutineContext.ensureActive()
                            val index = if (claims != null) claims.next() ?: break else cursor.getAndIncrement()
                            val source = sources.getOrNull(index) ?: break
                            val result = try { withTimeoutOrNull(timeoutMillis) { fetch(source) } }
                            catch (e: CancellationException) { throw e }
                            catch (_: Exception) { null }
                            coroutineContext.ensureActive()
                            delivery.withLock {
                                coroutineContext.ensureActive()
                                onResult(index, result)
                            }
                        }
                    }
                }
            }
        }
        Unit
    }
}
