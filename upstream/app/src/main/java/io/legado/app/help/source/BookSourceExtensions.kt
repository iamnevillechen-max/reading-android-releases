package io.legado.app.help.source

import com.script.rhino.runScriptWithContext
import io.legado.app.constant.BookSourceType
import io.legado.app.constant.BookType
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.BookSourcePart
import io.legado.app.data.entities.rule.ExploreKind
import io.legado.app.utils.ACache
import io.legado.app.utils.GSON
import io.legado.app.utils.MD5Utils
import io.legado.app.utils.fromJsonArray
import io.legado.app.utils.isJsonArray
import io.legado.app.utils.printOnDebug
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * 采用md5作为key可以在分类修改后自动重新计算,不需要手动刷新
 */

private val mutexMap by lazy { ConcurrentHashMap<String, Mutex>() }
private val exploreKindsMap by lazy { ConcurrentHashMap<String, List<ExploreKind>>() }
private val aCache by lazy { ACache.get("explore") }

private fun BookSource.getExploreKindsKey(): String {
    // Libraries/headers/rules can change while the menu URL stays identical. Old
    // in-flight evaluations keep their own key; successful current menus are shared.
    return MD5Utils.md5Encode("explore-v2\u0000" + GSON.toJson(copy(respondTime = 0L, weight = 0)))
}

private fun BookSourcePart.getExploreKindsKey(): String {
    return getBookSource()!!.getExploreKindsKey()
}

suspend fun BookSourcePart.exploreKinds(): List<ExploreKind> {
    return getBookSource()!!.exploreKinds()
}

suspend fun BookSource.exploreKinds(refresh: Boolean = false): List<ExploreKind> {
    currentCoroutineContext().ensureActive()
    val exploreKindsKey = getExploreKindsKey()
    if (!refresh) exploreKindsMap[exploreKindsKey]?.let { return it }
    val exploreUrl = exploreUrl?.trim()
    if (exploreUrl.isNullOrBlank()) {
        return emptyList()
    }
    val mutex = mutexMap.getOrPut(bookSourceUrl) { Mutex() }
    mutex.withLock {
        if (refresh) {
            aCache.remove(exploreKindsKey)
            exploreKindsMap.remove(exploreKindsKey)
        } else exploreKindsMap[exploreKindsKey]?.let { return it }
        val kinds = arrayListOf<ExploreKind>()
        withContext(Dispatchers.IO) {
            kotlin.runCatching {
                var ruleStr = exploreUrl
                var freshScriptResult: String? = null
                if (exploreUrl.startsWith("<js>", true)
                    || exploreUrl.startsWith("@js:", true)
                ) {
                    ruleStr = aCache.getAsString(exploreKindsKey)
                    if (ruleStr.isNullOrBlank()) {
                        val jsStr = if (exploreUrl.startsWith("@")) {
                            exploreUrl.substring(4)
                        } else {
                            exploreUrl.substring(4, exploreUrl.lastIndexOf("<"))
                        }
                        ruleStr = runScriptWithContext {
                            evalJS(jsStr).toString().trim()
                        }
                        freshScriptResult = ruleStr
                    }
                }
                if (ruleStr.isJsonArray()) {
                    GSON.fromJsonArray<ExploreKind>(ruleStr).getOrThrow().let {
                        kinds.addAll(it)
                    }
                } else {
                    ruleStr.split("(&&|\n)+".toRegex()).forEach { kindStr ->
                        val kindCfg = kindStr.split("::", limit = 2)
                        kinds.add(ExploreKind(kindCfg.first(), kindCfg.getOrNull(1)))
                    }
                }
                currentCoroutineContext().ensureActive()
                if (kinds.any { !it.url.isNullOrBlank() }) freshScriptResult?.let { aCache.put(exploreKindsKey, it) }
            }.onFailure {
                currentCoroutineContext().ensureActive()
                kinds.add(ExploreKind("ERROR:${it.localizedMessage}", it.stackTraceToString()))
                it.printOnDebug()
            }
        }
        currentCoroutineContext().ensureActive()
        if (kinds.none { it.title.startsWith("ERROR:") } && kinds.any { !it.url.isNullOrBlank() }) {
            exploreKindsMap[exploreKindsKey] = kinds
        }
        return kinds
    }
}

suspend fun BookSourcePart.clearExploreKindsCache() {
    withContext(Dispatchers.IO) {
        val exploreKindsKey = getExploreKindsKey()
        aCache.remove(exploreKindsKey)
        exploreKindsMap.remove(exploreKindsKey)
    }
}

suspend fun BookSource.clearExploreKindsCache() {
    withContext(Dispatchers.IO) {
        val exploreKindsKey = getExploreKindsKey()
        aCache.remove(exploreKindsKey)
        exploreKindsMap.remove(exploreKindsKey)
    }
}

fun BookSource.exploreKindsJson(): String {
    val exploreKindsKey = getExploreKindsKey()
    return aCache.getAsString(exploreKindsKey)?.takeIf { it.isJsonArray() }
        ?: exploreUrl.takeIf { it.isJsonArray() }
        ?: ""
}

fun BookSource.getBookType(): Int {
    return when (bookSourceType) {
        BookSourceType.file -> BookType.text or BookType.webFile
        BookSourceType.image -> BookType.image
        BookSourceType.audio -> BookType.audio
        else -> BookType.text
    }
}
