package io.legado.app.model.aggregation

import android.content.Context
import android.os.SystemClock
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.ExploreSourceSpec
import io.legado.app.data.entities.BookSourcePart
import io.legado.app.data.entities.rule.ExploreKind
import io.legado.app.help.source.exploreKinds
import io.legado.app.model.multisource.SourceRequestGate
import io.legado.app.model.cloud.CloudReader
import io.legado.app.model.cloud.CloudCatalogue
import io.legado.app.model.multisource.core.StreamingHash
import io.legado.app.utils.GSON
import io.legado.app.utils.MD5Utils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/** A route retains source metadata, never the potentially large set of parsing rules. */
data class ExploreRoute(
    val source: BookSourcePart,
    val label: String,
    val url: String,
    val category: String,
    val fingerprint: String
)

object ExploreAggregation {
    const val DEFERRED_MENU = "reader-local-category://deferred-menu"
    private const val CURSOR_KEY = "route_cursor_v1"
    private const val CATEGORY_BUDGET_MILLIS = 4_000L
    private const val MAX_CATEGORY_SOURCES_PER_ROUND = 128
    private const val DYNAMIC_MENU_TIMEOUT_MILLIS = 2_000L
    private const val MAX_DYNAMIC_MENUS_PER_ROUND = 16
    private const val CATALOGUE_REUSE_MILLIS = 5 * 60_000L
    private val loadLock = Mutex()
    private val observerLock = Any()
    private val changes = MutableStateFlow(0L)
    /** Throttled committed metadata/progress notifications; consumers read a non-scanning snapshot. */
    val catalogueUpdates: StateFlow<Long> = changes
    @Volatile private var databaseRevision = ""
    @Volatile private var catalogueLoadedAt = 0L
    @Volatile private var scanConfiguration: String? = null
    private var catalogue: ExploreCatalogue? = null
    private var sourceParts: Map<String, BookSourcePart> = emptyMap()
    private var orderedSources: List<BookSourcePart> = emptyList()
    private var orderedSpecs: List<ExploreSourceSpec> = emptyList()
    private var catalogueDatabaseRevision = ""
    private val sourceVersions = hashMapOf<String, String>()
    private val checkedSources = ConcurrentHashMap.newKeySet<String>()
    private var dynamicSourceIds: Set<String> = emptySet()
    private val settledDynamicMenus = ConcurrentHashMap.newKeySet<String>()
    private val pendingDynamicRefresh = ConcurrentHashMap.newKeySet<String>()
    private val dynamicCursor = AggregateLoader.Cursor()
    private var cursor: AggregateLoader.Cursor? = null
    data class CatalogueProgress(val checkedSources: Int = 0, val totalSources: Int = 0, val complete: Boolean = false,
        val pendingDynamicMenus: Int = 0)
    @Volatile var catalogueProgress = CatalogueProgress()
        private set
    private fun key(source: String, label: String) = MD5Utils.md5Encode("$source\u0000$label")
    private fun preferences(context: Context) = context.getSharedPreferences("aggregation_categories", Context.MODE_PRIVATE)

    fun setCategory(context: Context, route: ExploreRoute, category: String) {
        val preferences = preferences(context)
        preferences.edit().apply {
            if (category.isBlank()) remove(key(route.source.bookSourceUrl, route.label))
            else putString(key(route.source.bookSourceUrl, route.label), category.trim())
        }.apply()
    }

    fun categoryFor(context: Context, source: String, label: String): String =
        AggregationPolicy.category(label, preferences(context).getString(key(source, label), null))

    /** One bounded round remains useful for explicit metadata callers and regression fixtures. */
    suspend fun routes(context: Context, refresh: Boolean = false,
        onCategories: (suspend (List<String>) -> Unit)? = null): List<ExploreRoute> = withContext(Dispatchers.IO) {
        if (CloudReader.isEnabled(context)) {
            loadCloud(refresh, onCategories)
            return@withContext CloudCatalogue.exploreRoutes(null)
        }
        val configuration = revision()
        if (!refresh && scanConfiguration == configuration && checkedSources.isNotEmpty()) {
            return@withContext snapshot(context, null)
        }
        loadRound(context, refresh, onCategories)
        snapshot(context, null)
    }

    /** Automatically finish every source once, without a user-facing continuation button. */
    suspend fun discoverCategories(context: Context, refresh: Boolean = false,
        onCategories: (suspend (List<String>) -> Unit)? = null) = withContext(Dispatchers.IO) {
        if (CloudReader.isEnabled(context)) {
            loadCloud(refresh, onCategories)
            return@withContext
        }
        var force = refresh
        do {
            currentCoroutineContext().ensureActive()
            loadRound(context, force, onCategories)
            force = false
            if (checkedSources.size == orderedSources.size) resolveDynamicRound(onCategories)
            if (catalogueProgress.complete && scanConfiguration == revision()) break
            // Give foreground book work and lifecycle cancellation a turn between bounded rounds.
            delay(25)
        } while (currentCoroutineContext().isActive)
    }

    /** A cheap current-generation view: no full rules, category fetch, or scan mutex acquisition. */
    suspend fun snapshotRoutesForCategory(context: Context, category: String?): List<ExploreRoute> =
        withContext(Dispatchers.IO) {
            if (CloudReader.isEnabled(context)) CloudCatalogue.exploreRoutes(category)
            else snapshot(context, category)
        }

    /** Narrow menu queries retain all labels without materializing every catalogue route. */
    suspend fun categoryLabels(context: Context): List<String> = withContext(Dispatchers.IO) {
        if (CloudReader.isEnabled(context)) return@withContext CloudCatalogue.snapshot("books").categories.map { it.label }
        val configuration = revision()
        val store = synchronized(observerLock) { catalogue.takeIf { scanConfiguration == configuration } }
            ?: return@withContext emptyList()
        store.categories().takeIf { configuration == revision() }.orEmpty()
    }

    suspend fun sourceRoutes(context: Context, source: String): List<ExploreRoute> = withContext(Dispatchers.IO) {
        if (CloudReader.isEnabled(context)) return@withContext CloudCatalogue.sourceRoutes(source)
        val configuration = revision()
        val state = synchronized(observerLock) {
            if (scanConfiguration == configuration) catalogue?.let { it to sourceParts } else null
        } ?: return@withContext emptyList()
        state.first.sourceRoutes(state.second, source).takeIf { configuration == revision() }.orEmpty()
    }

    suspend fun categoryForRoute(context: Context, source: String, url: String): String? = withContext(Dispatchers.IO) {
        if (CloudReader.isEnabled(context)) return@withContext CloudCatalogue.sourceRoutes(source).firstOrNull { it.url == url }?.category
        val configuration = revision()
        val store = synchronized(observerLock) { catalogue.takeIf { scanConfiguration == configuration } }
            ?: return@withContext null
        store.categoryForRoute(source, url).takeIf { configuration == revision() }
    }

    /** A first bounded round can discover source representatives; existing metadata is not rescanned. */
    suspend fun routesForCategory(context: Context, category: String?, refresh: Boolean = false): List<ExploreRoute> =
        withContext(Dispatchers.IO) {
            if (CloudReader.isEnabled(context)) {
                loadCloud(refresh, null)
                return@withContext CloudCatalogue.exploreRoutes(category)
            }
            routes(context, refresh)
            snapshot(context, category)
        }

    private suspend fun loadCloud(refresh: Boolean, onCategories: (suspend (List<String>) -> Unit)?) {
        val state = CloudCatalogue.refresh("books", refresh) { update ->
            catalogueProgress = CatalogueProgress(update.checked, update.total, false)
            onCategories?.invoke(update.categories.map { it.label })
            synchronized(observerLock) { changes.value = changes.value + 1 }
        }
        catalogueProgress = CatalogueProgress(state.checked, state.total, true)
        onCategories?.invoke(state.categories.map { it.label })
        synchronized(observerLock) { changes.value = changes.value + 1 }
    }

    private fun snapshot(context: Context, category: String?): List<ExploreRoute> {
        val configuration = revision()
        val state = synchronized(observerLock) {
            if (scanConfiguration != configuration) return emptyList()
            (catalogue ?: return emptyList()) to sourceParts
        }
        val selected = category?.takeUnless { it == AggregationPolicy.ALL_SOURCES }?.let { LocalCategoryPolicy.category(it) }
        val routes = state.first.snapshot(state.second, selected)
        return routes.takeIf { configuration == revision() }.orEmpty()
    }

    private suspend fun loadRound(context: Context, refresh: Boolean,
        onCategories: (suspend (List<String>) -> Unit)?) = loadLock.withLock {
        val preferences = preferences(context.applicationContext)
        val position = cursor ?: AggregateLoader.Cursor(preferences.getInt(CURSOR_KEY, 0)).also { cursor = it }
        try {
            val stable = stableSources() ?: return@withLock
            val (configuration, specs) = stable
            val sources = if (scanConfiguration == configuration) orderedSources else specs.map { it.source }
            val mutations = AggregationDatabaseRevision.bookChanges(catalogueDatabaseRevision)
            if (mutations.revision != databaseRevision) return@withLock
            val store = synchronized(observerLock) {
                val value = catalogue ?: ExploreCatalogue(context.applicationContext).also { catalogue = it }
                if (scanConfiguration != configuration) {
                    val ids = sources.mapTo(hashSetOf()) { it.bookSourceUrl }
                    val changed = mutations.sources
                    if (changed == null) { sourceVersions.clear(); settledDynamicMenus.clear(); pendingDynamicRefresh.clear() }
                    dynamicSourceIds = specs.filter { ExploreLiteralRules.dynamic(it.menu.orEmpty()) }
                        .mapTo(hashSetOf()) { it.source.bookSourceUrl }
                    settledDynamicMenus.retainAll(dynamicSourceIds)
                    pendingDynamicRefresh.retainAll(dynamicSourceIds)
                    if (refresh) pendingDynamicRefresh.addAll(dynamicSourceIds)
                    changed?.let { settledDynamicMenus.removeAll(it) }
                    sourceVersions.keys.retainAll(ids)
                    for (source in ids) if (source !in sourceVersions || changed?.contains(source) == true) {
                        sourceVersions[source] = mutations.revision
                    }
                    if (changed == null) {
                        value.clear()
                        checkedSources.clear()
                    } else {
                        value.removeSources(changed + (sourceParts.keys - ids))
                        checkedSources.retainAll(SourceSnapshotPolicy.retained(checkedSources, ids, changed))
                    }
                    sourceParts = sources.associateBy { it.bookSourceUrl }
                    orderedSources = sources
                    orderedSpecs = specs
                    scanConfiguration = configuration
                    catalogueDatabaseRevision = mutations.revision
                    catalogueLoadedAt = 0
                } else if (refresh || catalogueProgress.complete && !catalogueRecent()) {
                    checkedSources.clear()
                    settledDynamicMenus.clear()
                    pendingDynamicRefresh.addAll(dynamicSourceIds)
                    catalogueLoadedAt = 0
                }
                updateProgress(sources.size)
                value
            }
            val owner = currentCoroutineContext()
            fun current() = owner.isActive && configuration == revisionToken()
            var lastPublished = 0L
            var lastRevisionRead = SystemClock.elapsedRealtime()
            fun checkRevision(force: Boolean = false) {
                val now = SystemClock.elapsedRealtime()
                if (force || now - lastRevisionRead >= 250) { revision(); lastRevisionRead = now }
            }
            var published: List<String>? = null
            var firstRoutes = checkedSources.isEmpty()
            suspend fun publish(force: Boolean = false) {
                owner.ensureActive()
                if (!current()) return
                val now = SystemClock.elapsedRealtime()
                if (!force && lastPublished > 0 && now - lastPublished < 250) return
                lastPublished = now
                if (onCategories != null) {
                    val labels = store.categories()
                    if (labels != published) { published = labels; onCategories(labels) }
                }
                synchronized(observerLock) { changes.value = changes.value + 1 }
            }
            val pending = arrayListOf<Pair<BookSourcePart, List<ExploreRoute>?>>()
            suspend fun commitBatch() {
                owner.ensureActive()
                if (!current() || pending.isEmpty()) return
                store.replaceAll(pending.mapNotNull { (part, routes) -> routes?.let { part to it } })
                checkedSources.addAll(pending.map { it.first.bookSourceUrl })
                val hasRoutes = pending.any { !it.second.isNullOrEmpty() }
                pending.clear()
                updateProgress(sources.size)
                publish(force = firstRoutes && hasRoutes)
                if (hasRoutes) firstRoutes = false
            }
            publish(force = true)
            if (checkedSources.size == sources.size) return@withLock
            val claims = position.round(sources.size, MAX_CATEGORY_SOURCES_PER_ROUND) {
                sources[it].bookSourceUrl !in checkedSources
            }
            AggregateLoader.load(specs, timeoutMillis = CATEGORY_BUDGET_MILLIS * 2,
                budgetMillis = CATEGORY_BUDGET_MILLIS,
                claims = AggregateLoader.Claims { if (current()) claims.next() else null },
                fetch = { spec ->
                    val part = spec.source
                    val rule = spec.menu.orEmpty()
                    owner.ensureActive()
                    if (!current()) return@load emptyList<ExploreRoute>()
                    // Literal menus stay independent of parsing libraries/network. Dynamic
                    // menus get a separate foreground round after this lightweight pass.
                    if (ExploreLiteralRules.dynamic(rule)) return@load null
                    val fingerprint = routeFingerprint(sourceVersions.getValue(part.bookSourceUrl), part.bookSourceUrl + "\u0000" + rule)
                    menuRoutes(part, ExploreLiteralRules.parse(rule), fingerprint)
                }, onResult = { index, fetched ->
                    owner.ensureActive()
                    checkRevision()
                    if (current()) {
                        val part = sources[index]
                        pending.add(part to if (part.bookSourceUrl in dynamicSourceIds) null else fetched.orEmpty())
                        if (pending.size >= 16) commitBatch()
                    }
                })
            owner.ensureActive()
            checkRevision(force = true)
            if (current()) {
                commitBatch()
                if (catalogueProgress.complete) catalogueLoadedAt = SystemClock.elapsedRealtime()
                publish(force = true)
            }
        } finally {
            val next = position.position()
            if (preferences.getInt(CURSOR_KEY, 0) != next) preferences.edit().putInt(CURSOR_KEY, next).commit()
        }
    }

    private fun updateProgress(total: Int = orderedSources.size) {
        val pending = (dynamicSourceIds.size - settledDynamicMenus.size).coerceAtLeast(0)
        catalogueProgress = CatalogueProgress(checkedSources.size, total,
            checkedSources.size == total && pending == 0, pending)
    }

    internal fun menuRoutes(part: BookSourcePart, kinds: List<ExploreKind>, fingerprint: String): List<ExploreRoute> =
        kinds.asSequence().filter { !it.title.startsWith("ERROR:") && !it.url.isNullOrBlank() }
            .map { kind -> ExploreRoute(part, kind.title.trim(), kind.url!!, LocalCategoryPolicy.category(kind.title),
                routeFingerprint(fingerprint, kind.url)) }
            .filter { it.category.isNotEmpty() }.distinctBy { it.label to it.url }.toList()

    /** Foreground discovery only: source scripts are bounded and commit each successful full menu. */
    private suspend fun resolveDynamicRound(onCategories: (suspend (List<String>) -> Unit)?) = loadLock.withLock {
        if (checkedSources.size != orderedSources.size || settledDynamicMenus.size == dynamicSourceIds.size) return@withLock
        val configuration = revision()
        if (scanConfiguration != configuration) return@withLock
        val state = synchronized(observerLock) { catalogue?.let { it to sourceParts } } ?: return@withLock
        val sources = orderedSources
        val owner = currentCoroutineContext()
        fun current() = owner.isActive && configuration == revisionToken()
        var lastPublished = 0L
        suspend fun publish(force: Boolean = false) {
            owner.ensureActive()
            if (!current()) return
            val now = SystemClock.elapsedRealtime()
            if (!force && lastPublished > 0 && now - lastPublished < 250) return
            lastPublished = now
            onCategories?.invoke(state.first.categories())
            synchronized(observerLock) { changes.value = changes.value + 1 }
        }
        val actualAttempted = ConcurrentHashMap.newKeySet<String>()
        val claims = dynamicCursor.round(sources.size, MAX_DYNAMIC_MENUS_PER_ROUND) {
            sources[it].bookSourceUrl in dynamicSourceIds && sources[it].bookSourceUrl !in settledDynamicMenus
        }
        AggregateLoader.load(orderedSpecs, timeoutMillis = DYNAMIC_MENU_TIMEOUT_MILLIS,
            budgetMillis = CATEGORY_BUDGET_MILLIS,
            claims = AggregateLoader.Claims { if (current()) claims.next() else null }, fetch = { spec ->
                val part = spec.source
                SourceRequestGate.request(part.bookSourceUrl) {
                    actualAttempted.add(part.bookSourceUrl)
                    val source = part.getBookSource()?.takeIf { it.enabled && it.enabledExplore }
                        ?: error("來源已變更")
                    val menu = spec.menu
                    check(source.exploreUrl == menu) { "來源選單已變更" }
                    val forceRefresh = part.bookSourceUrl in pendingDynamicRefresh
                    val kinds = source.exploreKinds(refresh = forceRefresh)
                    if (forceRefresh) pendingDynamicRefresh.remove(part.bookSourceUrl)
                    check(kinds.none { it.title.startsWith("ERROR:") }) { "來源分類暫時無法載入" }
                    val fingerprint = routeFingerprint(sourceVersions.getValue(part.bookSourceUrl), part.bookSourceUrl + "\u0000" + menu.orEmpty())
                    menuRoutes(part, kinds, fingerprint)
                }
            }, onResult = { index, fetched ->
                owner.ensureActive()
                revision()
                if (current()) {
                    val part = sources[index]
                    // A failed/timeout menu cannot erase an earlier successful menu.
                    if (fetched != null && fetched.isNotEmpty()) state.first.replace(part, fetched)
                    if (part.bookSourceUrl in actualAttempted) settledDynamicMenus.add(part.bookSourceUrl)
                    updateProgress(sources.size)
                    publish()
                }
            })
        owner.ensureActive()
        revision()
        if (current()) {
            // Waiting for another request's host/global slot is not a menu attempt.
            // Only actual requests may time out as a bounded skip; lifecycle cancellation
            // throws above and leaves undelivered work for the next foreground visit.
            settledDynamicMenus.addAll(actualAttempted)
            updateProgress(sources.size)
            if (catalogueProgress.complete) catalogueLoadedAt = SystemClock.elapsedRealtime()
            publish(force = true)
        }
    }

    private fun catalogueRecent() = catalogueLoadedAt > 0 &&
        SystemClock.elapsedRealtime() - catalogueLoadedAt < CATALOGUE_REUSE_MILLIS

    @Synchronized private fun revision(): String {
        val next = AggregationDatabaseRevision.bookSources()
        if (next != databaseRevision) catalogueProgress = catalogueProgress.copy(complete = false)
        databaseRevision = next
        return revisionToken()
    }
    private fun revisionToken() = databaseRevision
    private fun stableSources(): Pair<String, List<ExploreSourceSpec>>? {
        repeat(3) {
            val before = revision()
            val sources = synchronized(observerLock) {
                orderedSpecs.takeIf { scanConfiguration == before }
            } ?: appDb.bookSourceDao.aggregateExploreSpecs
            if (before == revision()) return before to sources
        }
        return null
    }

    internal fun sourceFingerprint(source: BookSource): String = StreamingHash.sha256 { writer -> GSON.toJson(source, writer) }
    internal fun routeFingerprint(sourceFingerprint: String, url: String?): String = StreamingHash.sha256 { writer ->
        StreamingHash.field(writer, sourceFingerprint); StreamingHash.field(writer, url)
    }
}
