package io.legado.app.model.multisource

import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

/** Shared request budget. Background work cannot consume the reader's reserved slots. */
object SourceRequestGate {
    private val all = Semaphore(4)
    private val background = Semaphore(2)
    private val hosts = ConcurrentHashMap<String, Semaphore>()

    suspend fun <T> request(url: String, foreground: Boolean = true, block: suspend () -> T): T {
        suspend fun run(): T {
            val host = runCatching { URI(url).host?.lowercase() }.getOrNull() ?: url
            val gate = hosts.getOrPut(host) { Semaphore(1) }
            return gate.withPermit { all.withPermit { block() } }
        }
        return if (foreground) run() else background.withPermit { run() }
    }
}
