package io.legado.app.model.aggregation

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import io.legado.app.data.entities.BookSourcePart
import java.io.File

/** Disposable lightweight metadata only; the user's Room database and complete rules are untouched. */
internal class ExploreCatalogue(context: Context) : AutoCloseable {
    private var labels: List<String>? = null
    private var snapshotSources: Map<String, BookSourcePart>? = null
    private val snapshots = object : LinkedHashMap<String, List<ExploreRoute>>(4, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<ExploreRoute>>) = size > 3
    }
    private val database = SQLiteDatabase.openOrCreateDatabase(File(context.cacheDir, "explore-catalogue-v1.db"), null).apply {
        execSQL("create table if not exists routes (source text not null, ordinal integer not null, source_order integer not null, label text not null, url text not null, category text not null, fingerprint text not null, primary key(source, ordinal))")
        execSQL("create index if not exists routes_category on routes(category, source, ordinal)")
        // Revision tokens are process-local. Never trust metadata from an earlier process.
        delete("routes", null, null)
    }

    @Synchronized override fun close() { labels = null; snapshots.clear(); snapshotSources = null; database.close() }

    @Synchronized fun clear() { labels = null; snapshots.clear(); database.delete("routes", null, null) }

    @Synchronized fun removeSources(sources: Set<String>) {
        if (sources.isEmpty()) return
        labels = null
        snapshots.clear()
        database.beginTransaction()
        try {
            database.compileStatement("delete from routes where source = ?").use { statement ->
                for (source in sources) { statement.bindString(1, source); statement.executeUpdateDelete() }
            }
            database.setTransactionSuccessful()
        } finally { database.endTransaction() }
    }

    @Synchronized fun replace(source: BookSourcePart, routes: List<ExploreRoute>) = replaceAll(listOf(source to routes))

    @Synchronized fun replaceAll(batch: List<Pair<BookSourcePart, List<ExploreRoute>>>) {
        if (batch.isEmpty()) return
        labels = null
        snapshots.clear()
        database.beginTransaction()
        try {
            database.compileStatement("insert into routes values (?, ?, ?, ?, ?, ?, ?)").use { statement ->
                for ((source, routes) in batch) {
                    database.delete("routes", "source = ?", arrayOf(source.bookSourceUrl))
                    routes.forEachIndexed { index, route ->
                        statement.clearBindings()
                        statement.bindString(1, source.bookSourceUrl)
                        statement.bindLong(2, index.toLong())
                        statement.bindLong(3, source.customOrder.toLong())
                        statement.bindString(4, route.label)
                        statement.bindString(5, route.url)
                        statement.bindString(6, route.category)
                        statement.bindString(7, route.fingerprint)
                        statement.executeInsert()
                    }
                }
            }
            database.setTransactionSuccessful()
        } finally { database.endTransaction() }
    }

    @Synchronized fun categories(): List<String> = labels ?: database.rawQuery(
        "select distinct category from routes order by category", null
    ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getString(0)) } }.also { labels = it }

    @Synchronized fun sourceRoutes(sources: Map<String, BookSourcePart>, source: String): List<ExploreRoute> {
        val metadata = sources[source] ?: return emptyList()
        return database.rawQuery("select label,url,category,fingerprint from routes where source = ? order by ordinal", arrayOf(source)).use { cursor ->
            buildList { while (cursor.moveToNext()) add(ExploreRoute(metadata,
                cursor.getString(0), cursor.getString(1), cursor.getString(2), cursor.getString(3))) }
        }
    }

    @Synchronized fun categoryForRoute(source: String, url: String): String? = database.rawQuery(
        "select category from routes where source = ? and url = ? limit 1", arrayOf(source, url)
    ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }

    /** SQL chooses one route before mapping, so an all-sources screen never builds millions of rows. */
    @Synchronized fun snapshot(sources: Map<String, BookSourcePart>, category: String?,
        onePerSource: Boolean = true): List<ExploreRoute> {
        if (snapshotSources !== sources) { snapshots.clear(); snapshotSources = sources }
        val filtered = category?.takeUnless { it == AggregationPolicy.ALL_SOURCES }
        val cacheKey = "$onePerSource\u0000${filtered?.let { "category:$it" } ?: "all"}"
        snapshots[cacheKey]?.let { return it }
        val where = if (filtered == null) "" else "where category = ?"
        val query = if (onePerSource) {
            "select r.source,r.label,r.url,r.category,r.fingerprint from routes r join " +
                "(select source,min(ordinal) first_ordinal from routes $where group by source) chosen " +
                "on r.source=chosen.source and r.ordinal=chosen.first_ordinal order by r.source_order,r.source"
        } else "select source,label,url,category,fingerprint from routes $where order by source_order,source,ordinal"
        val result = database.rawQuery(query, filtered?.let { arrayOf(it) }).use { cursor ->
            buildList {
                while (cursor.moveToNext()) sources[cursor.getString(0)]?.let { source ->
                    add(ExploreRoute(source, cursor.getString(1), cursor.getString(2), cursor.getString(3), cursor.getString(4)))
                }
            }
        }
        snapshots[cacheKey] = result
        return result
    }
}
