package io.legado.app.model.aggregation

import android.content.Context
import io.legado.app.data.entities.RssArticle
import io.legado.app.data.entities.RssSource
import io.legado.app.model.multisource.core.StreamingHash
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonArray
import java.io.File

object RssAggregation {
    fun merge(lists: List<List<RssArticle>>): List<RssArticle> {
        val fair = AggregationPolicy.fairMerge(lists) {
            AggregationPolicy.articleIdentity(it.origin, it.link)
        }
        // Known publication dates first, newest first; ties/unknown dates retain fair source order.
        if (fair.size < 2) return fair
        return fair.map { it to (AggregationPolicy.publicationTime(it.pubDate) ?: Long.MIN_VALUE) }
            .sortedByDescending { it.second }
            .map { it.first }
    }

    private fun cache(context: Context, sources: List<RssSource>, category: String? = null): File {
        // Hash complete configured rules, never put credentials or headers in filenames.
        val key = StreamingHash.sha256 { writer ->
            GSON.toJson(sources, writer)
            if (category != null) StreamingHash.field(writer, category)
        }
        return File(context.cacheDir, "aggregate-rss/$key.json")
    }

    fun readCache(context: Context, sources: List<RssSource>, category: String? = null): List<RssArticle> = try {
        val file = cache(context, sources, category)
        if (file.isFile && file.length() < 4_000_000) {
            GSON.fromJsonArray<RssArticle>(file.readText()).getOrNull().orEmpty()
        } else emptyList()
    } catch (_: Exception) { emptyList() }

    fun writeCache(context: Context, sources: List<RssSource>, articles: List<RssArticle>,
        category: String? = null,
        canCommit: () -> Boolean = { true }) {
        var temporary: File? = null
        try {
            if (!canCommit()) return
            val file = cache(context, sources, category)
            file.parentFile?.mkdirs()
            temporary = File.createTempFile("aggregate-", ".tmp", file.parentFile)
            temporary.writeText(GSON.toJson(articles.take(600)))
            if (canCommit() && temporary.renameTo(file)) {
                file.parentFile?.listFiles()?.filter { it.isFile && it.extension == "json" && it != file }
                    ?.sortedByDescending { it.lastModified() }?.drop(7)?.forEach { it.delete() }
            }
        } catch (_: Exception) { /* Cache failure must not break a successful feed load. */ }
        finally { temporary?.delete() }
    }
}
