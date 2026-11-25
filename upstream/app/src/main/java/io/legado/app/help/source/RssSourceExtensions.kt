package io.legado.app.help.source

import io.legado.app.data.entities.RssSource
import io.legado.app.utils.ACache
import io.legado.app.utils.GSON
import io.legado.app.utils.MD5Utils
import io.legado.app.utils.NetworkUtils
import com.script.rhino.runScriptWithContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.withContext

private val aCache by lazy { ACache.get("rssSortUrl") }
private val sortLocks = ConcurrentHashMap<String, Mutex>()

private fun RssSource.getSortUrlsKey(): String {
    // A header/library edit may change a dynamic menu without changing sortUrl.
    return MD5Utils.md5Encode("rss-sort-v2\u0000" + GSON.toJson(this))
}

suspend fun RssSource.sortUrls(): List<Pair<String, String>> = withContext(Dispatchers.IO) {
    val sortUrlsKey = getSortUrlsKey()
    sortLocks.getOrPut(sortUrlsKey) { Mutex() }.withLock {
        val result = arrayListOf<Pair<String, String>>()
        try {
            val rule = sortUrl.orEmpty().trim()
            var str: String? = rule
            if (rule.startsWith("<js>", true) || rule.startsWith("@js:", true)) {
                str = aCache.getAsString(sortUrlsKey)
                if (str.isNullOrBlank()) {
                    val js = if (rule.startsWith("@js:", true)) rule.substring(4)
                        else rule.substring(4, rule.lastIndexOf("<"))
                    str = runScriptWithContext { evalJS(js).toString() }
                    coroutineContext.ensureActive()
                    aCache.put(sortUrlsKey, str)
                }
            }
            str?.split("(&&|\\n)+".toRegex())?.forEach { sort ->
                val name = sort.substringBefore("::").trim()
                val url = sort.substringAfter("::", "")
                if (url.isNotEmpty()) result.add(name to if (url.startsWith("{{")) url else NetworkUtils.getAbsoluteURL(sourceUrl, url))
            }
            if (result.isEmpty()) result.add("" to sourceUrl)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { /* A failed menu stays retryable and never overwrites a successful catalogue. */ }
        result
    }
}

suspend fun RssSource.removeSortCache() {
    withContext(Dispatchers.IO) {
        val key = getSortUrlsKey()
        sortLocks.getOrPut(key) { Mutex() }.withLock { aCache.remove(key) }
    }
}
