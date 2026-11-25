package io.legado.app.model.cloud

import io.legado.app.data.appDb
import io.legado.app.data.entities.BookSourcePart
import io.legado.app.model.aggregation.AggregationPolicy
import io.legado.app.model.aggregation.ExploreRoute
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import android.os.SystemClock

/** Only small, server-approved metadata reaches the discovery UI. No source rule is parsed here. */
object CloudCatalogue {
    data class Category(val id: String, val label: String)
    data class Route(val sourceId: String, val sourceName: String, val label: String,
        val url: String, val categoryId: String, val categoryLabel: String, val fingerprint: String)
    data class WebEntry(val sourceId: String, val name: String, val url: String,
        val categoryId: String, val categoryLabel: String, val fingerprint: String)
    data class Snapshot(val revision: String = "", val categories: List<Category> = emptyList(),
        val routes: List<Route> = emptyList(), val checked: Int = 0, val total: Int = 0,
        val webEntries: List<WebEntry> = emptyList())
    private val lock = Mutex()
    private val snapshots = mutableMapOf<String, Pair<Long, Snapshot>>()
    fun clear() = synchronized(snapshots) { snapshots.clear() }
    fun snapshot(kind: String): Snapshot = synchronized(snapshots) { snapshots[kind]?.second ?: Snapshot() }

    suspend fun refresh(kind: String, force: Boolean = false,
                        onUpdate: (suspend (Snapshot) -> Unit)? = null): Snapshot = lock.withLock {
        val now = SystemClock.elapsedRealtime()
        synchronized(snapshots) { snapshots[kind] }?.let {
            if (!force && it.first > 0 && now - it.first < 120_000) {
                onUpdate?.invoke(it.second)
                return@withLock it.second
            }
        }
        CloudReader.syncCatalogue()
        val categoryMap = linkedMapOf<String, Category>()
        val routeMap = linkedMapOf<String, Route>()
        val webMap = linkedMapOf<String, WebEntry>()
        val cursors = hashSetOf<String>()
        var cursor: String? = null
        var result = Snapshot()
        do {
        val query = mutableMapOf("kind" to kind)
        cursor?.let { query["cursor"] = it }
        val value = CloudReader.request("/v1/catalogue", query = query).asJsonObject
        val categories = value.getAsJsonArray("categories").map {
            val item = it.asJsonObject
            Category(item.get("id").asString, item.get("label").asString)
        }
        require(categories.size <= 11 && categories.map { it.id }.distinct().size == categories.size) {
            "服务器分类数据无效"
        }
        categories.forEach { categoryMap[it.id] = it }
        require(categoryMap.size <= 11) { "服务器分类数据无效" }
        val known = categories.associate { it.id to it.label }
        val routes = value.getAsJsonArray("routes").map {
            val item = it.asJsonObject
            val categoryId = item.get("categoryId").asString
            Route(item.get("sourceId").asString, item.get("sourceName").asString,
                item.get("label").asString, item.get("url").asString, categoryId,
                known[categoryId] ?: error("服务器分类数据无效"), item.get("fingerprint").asString)
        }
        routes.forEach { routeMap["${it.sourceId}\u0000${it.categoryId}"] = it }
        value.getAsJsonArray("webEntries")?.forEach {
            val item = it.asJsonObject
            val categoryId = item.get("categoryId").asString
            val entry = WebEntry(item.get("sourceId").asString, item.get("name").asString,
                item.get("url").asString, categoryId, known[categoryId] ?: error("服务器分类数据无效"),
                item.get("fingerprint").asString)
            require(entry.url.startsWith("https://") || entry.url.startsWith("http://")) { "服务器网站入口无效" }
            webMap[entry.sourceId] = entry
        }
        result = Snapshot(value.get("revision").asString, categoryMap.values.toList(), routeMap.values.toList(),
            value.get("checkedSources")?.asInt ?: 0, value.get("totalSources")?.asInt ?: 0, webMap.values.toList())
        // A cancelled pagination pass remains retryable; only a fully read catalogue is warm.
        synchronized(snapshots) { snapshots[kind] = 0L to result }
        onUpdate?.invoke(result)
        cursor = value.get("nextCursor")?.takeUnless { it.isJsonNull }?.asString
        require(cursor == null || cursors.add(cursor!!)) { "服务器分类分页无效" }
        } while (cursor != null)
        synchronized(snapshots) { snapshots[kind] = SystemClock.elapsedRealtime() to result }
        result
    }

    suspend fun categoryOptions(): List<Category> {
        val options = CloudReader.request("/v1/status").asJsonObject.getAsJsonArray("categories").map {
            val item = it.asJsonObject
            Category(item.get("id").asString, item.get("label").asString)
        }
        require(options.size in 1..11 && options.distinctBy { it.id }.size == options.size) { "服务器分类数据无效" }
        return options
    }

    suspend fun reclassify(route: ExploreRoute, categoryId: String) {
        val original = snapshot("books").categories.firstOrNull { it.label == route.category }
            ?: error("分类目录已更新，请刷新后重试")
        CloudReader.request("/v1/category-override", CloudReader.payload(route.source.bookSourceUrl,
            "kind" to "book", "fromCategoryId" to original.id, "categoryId" to categoryId))
        clear()
    }

    fun exploreRoutes(category: String?): List<ExploreRoute> {
        val state = snapshot("books")
        val selected = state.categories.firstOrNull { it.label == category || it.id == category }
        if (category != null && category != AggregationPolicy.ALL_SOURCES && selected == null) return emptyList()
        val relevant = state.routes.filter { selected == null || it.categoryId == selected.id }
        val managed = CloudReader.activeApprovedBookIds()
        // A server category route rotates all matching raw routes for this source over pages.
        // Choosing the first raw URL here would permanently hide its other merged subcategories.
        return relevant.groupBy { it.sourceId }.mapNotNull { (sourceId, mappings) ->
            if (sourceId !in managed) return@mapNotNull null
            val route = mappings.first()
            val source = appDb.bookSourceDao.getBookSourcePart(sourceId) ?: return@mapNotNull null
            // Health timestamps and other sources' updates must not reset displayed books.
            val mapping = mappings.map { "${it.categoryId}\u0000${it.url}\u0000${it.fingerprint}" }.sorted().joinToString("\u0001")
            ExploreRoute(source, selected?.label ?: route.label,
                "reader-cloud://category/${selected?.id ?: "all"}", selected?.label ?: route.categoryLabel,
                CloudMigrationPolicy.sha256("$sourceId\u0000${selected?.id ?: "all"}\u0000$mapping"))
        }
    }

    fun sourceRoutes(source: String): List<ExploreRoute> {
        if (source !in CloudReader.activeApprovedBookIds()) return emptyList()
        return snapshot("books").routes.filter { it.sourceId == source }.mapNotNull { route ->
            val part = appDb.bookSourceDao.getBookSourcePart(source) ?: return@mapNotNull null
            ExploreRoute(part, route.label, route.url, route.categoryLabel, route.fingerprint)
        }
    }
}
