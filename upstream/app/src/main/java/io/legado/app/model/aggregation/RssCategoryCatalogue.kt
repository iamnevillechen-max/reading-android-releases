package io.legado.app.model.aggregation

import io.legado.app.data.appDb
import io.legado.app.data.entities.RssCategorySpec
import io.legado.app.data.entities.RssSource
import io.legado.app.help.source.removeSortCache
import io.legado.app.help.source.sortUrls
import io.legado.app.model.cloud.CloudReader
import io.legado.app.model.multisource.SourceRequestGate
import io.legado.app.utils.GSON
import io.legado.app.utils.MD5Utils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** Literal labels are immediate. Successful native menus are shared by tabs and article preparation. */
object RssCategoryCatalogue {
    data class Snapshot(val names: List<String> = emptyList(), val loaded: Boolean = false,
                        val pending: Boolean = false, val revision: Long = 0)
    private val lock = Any()
    private val synchronizeLock = Mutex()
    private val menus = RssCategoryMenuStore()
    private val sourceLocks = ConcurrentHashMap<String, Mutex>()
    private var metadata = emptyMap<String, RssCategorySpec>()
    private var dynamicIds = emptyList<String>()
    private val namesBySource = hashMapOf<String, List<String>>()
    private var namesChanged = false
    private var databaseRevision: String? = null
    private val fingerprints = hashMapOf<String, String>()
    private val attempts = hashMapOf<String, Long>()
    private val invalidated = hashSetOf<String>()
    private val mutableSnapshots = MutableStateFlow(Snapshot())
    val snapshots = mutableSnapshots.asStateFlow()
    private const val RETRY_DELAY = 60_000L
    private fun fingerprint(source: RssSource) = MD5Utils.md5Encode(GSON.toJson(source))
    private fun needsResolution(spec: RssCategorySpec) = spec.dynamicSort ||
        ExploreLiteralRules.dynamic(spec.sortUrl.orEmpty()) || (spec.sortLength > 0 && spec.sortUrl == null)
    private fun tokenKey(id: String, token: String) = id + "\u0000" + token

    suspend fun synchronize(specs: List<RssCategorySpec>) = withContext(Dispatchers.IO) {
        synchronizeLock.withLock {
            val changes = AggregationDatabaseRevision.rssChanges(databaseRevision)
            synchronized(lock) {
                val previous = metadata
                val changedIds = hashSetOf<String>()
                val tokens = specs.associate { spec ->
                    val old = menus.token(spec.sourceId)
                    val changed = changes.sources?.contains(spec.sourceId) != false || previous[spec.sourceId] != spec
                    if (old == null || changed) changedIds.add(spec.sourceId)
                    if (old != null && changed) invalidated.add(spec.sourceId)
                    spec.sourceId to if (old == null || changed) "${changes.revision}\u0000${spec.sourceId}" else old
                }
                menus.synchronize(tokens)
                metadata = specs.associateBy { it.sourceId }
                dynamicIds = specs.filter(::needsResolution).map { it.sourceId }
                namesBySource.keys.retainAll(tokens.keys)
                changedIds.forEach { id -> updateNames(metadata.getValue(id)) }
                namesChanged = true // The source order can change even if its labels do not.
                databaseRevision = changes.revision
                fingerprints.keys.retainAll(tokens.keys)
                invalidated.retainAll(tokens.keys)
                attempts.keys.retainAll(tokens.mapTo(hashSetOf()) { tokenKey(it.key, it.value) })
                publishSnapshot()
            }
        }
    }

    private fun updateNames(spec: RssCategorySpec) {
        val resolved = menus.menu(spec.sourceId).orEmpty().map { it.first }
        val names = RssCategories.namesFromMetadata(listOf(spec), resolved)
        val visible = if (resolved.any(String::isNotBlank)) names.filterNot { it == RssCategories.UNCLASSIFIED } else names
        if (namesBySource.put(spec.sourceId, visible) != visible) namesChanged = true
    }

    private fun pending(id: String, now: Long): Boolean {
        val token = menus.token(id) ?: return false
        return menus.menu(id) == null && now - (attempts[tokenKey(id, token)] ?: 0L) >= RETRY_DELAY
    }

    /** An attempt can change progress without changing a label. Only reparse the menu that changed. */
    private fun publishSnapshot() {
        val now = System.currentTimeMillis()
        val names = if (namesChanged) {
            namesChanged = false
            val ordered = linkedSetOf<String>()
            for (id in metadata.keys) ordered.addAll(namesBySource[id].orEmpty())
            ordered.toList()
        } else mutableSnapshots.value.names
        mutableSnapshots.value = Snapshot(names, loaded = true, pending = dynamicIds.any { pending(it, now) },
            revision = menus.revision)
    }

    fun resolvedSortNames(source: RssSource): List<String>? {
        synchronized(lock) { if (source.sourceUrl !in fingerprints) return null }
        val version = fingerprint(source)
        return synchronized(lock) {
            if (fingerprints[source.sourceUrl] == version) menus.menu(source.sourceUrl)?.map { it.first } else null
        }
    }
    fun includesSource(source: RssSource, category: String?): Boolean {
        if (category == null) return true
        val resolved = resolvedSortNames(source)
        return RssCategories.includesSource(source, category, resolved) ||
            (resolved == null && ExploreLiteralRules.dynamic(source.sortUrl.orEmpty()))
    }

    /** One source mutex and the existing native sortUrls cache prevent a tab/article double evaluation. */
    suspend fun resolve(source: RssSource, refresh: Boolean = false, foreground: Boolean = true): List<Pair<String, String>> =
        withContext(Dispatchers.IO) {
            if (!source.enabled || CloudReader.isRemote(source)) return@withContext emptyList()
            if (synchronized(lock) { menus.token(source.sourceUrl) } == null) synchronize(appDb.rssSourceDao.categorySpecs())
            sourceLocks.getOrPut(source.sourceUrl) { Mutex() }.withLock {
                val sourceFingerprint = fingerprint(source)
                val token = synchronized(lock) { menus.token(source.sourceUrl) } ?: return@withLock emptyList()
                val cached = synchronized(lock) {
                    if (!refresh && fingerprints[source.sourceUrl] == sourceFingerprint) menus.menu(source.sourceUrl) else null
                }
                if (cached != null) return@withLock cached
                try {
                    val result = withTimeoutOrNull(AggregateLoadingPolicy.SOURCE_TIMEOUT_MILLIS) {
                        SourceRequestGate.request(source.sourceUrl, foreground) {
                            val invalidate = synchronized(lock) { source.sourceUrl in invalidated }
                            if (refresh || invalidate) source.removeSortCache()
                            val sorts = source.sortUrls().filter { it.second.isNotBlank() }
                                .map { it.first.trim() to it.second }.distinct()
                            coroutineContext.ensureActive()
                            // A completed old parser cannot publish over a source edited during its request.
                            val current = appDb.rssSourceDao.getByKey(source.sourceUrl)
                            if (current == null || !current.enabled || fingerprint(current) != sourceFingerprint) return@request emptyList()
                            synchronized(lock) {
                                if (menus.token(source.sourceUrl) != token) return@synchronized false
                                val published = menus.publish(source.sourceUrl, token, sorts)
                                if (sorts.isNotEmpty()) { fingerprints[source.sourceUrl] = sourceFingerprint; invalidated.remove(source.sourceUrl) }
                                if (published) metadata[source.sourceUrl]?.let(::updateNames)
                                true
                            }.let { accepted -> if (accepted) sorts else emptyList() }
                        }
                    }
                    result.orEmpty()
                } finally {
                    synchronized(lock) {
                        if (coroutineContext.isActive) attempts[tokenKey(source.sourceUrl, token)] = System.currentTimeMillis()
                        publishSnapshot()
                    }
                }
            }
        }

    /** Runs only in the visible parent's lifecycle; each unresolved source is attempted at most once per pass. */
    suspend fun resolvePending() = withContext(Dispatchers.IO) {
        val candidates = synchronized(lock) {
            val now = System.currentTimeMillis()
            dynamicIds.filter { pending(it, now) }
        }
        val cursor = AtomicInteger()
        coroutineScope {
            repeat(minOf(2, candidates.size)) {
                launch {
                    while (true) {
                        coroutineContext.ensureActive()
                        val id = candidates.getOrNull(cursor.getAndIncrement()) ?: break
                        try { appDb.rssSourceDao.getByKey(id)?.let { resolve(it, foreground = false) } }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { /* Keep earlier successful menus and literal tabs. */ }
                    }
                }
            }
        }
        synchronized(lock) { publishSnapshot() }
    }
}
