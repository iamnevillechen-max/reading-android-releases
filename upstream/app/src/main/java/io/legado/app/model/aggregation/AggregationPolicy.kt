package io.legado.app.model.aggregation

import java.net.URI
import java.text.Normalizer
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import io.legado.app.model.multisource.core.BookIdentity
import io.legado.app.model.multisource.core.IdentityMatcher

/** Conservative identities: a similar title alone never proves two works/articles are the same. */
object AggregationPolicy {
    const val ALL_SOURCES = "legado:all-configured-sources"

    fun normalized(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKC)
        .lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }

    fun bookIdentity(title: String, author: String, source: String, url: String): String {
        return IdentityMatcher.bookKey(BookIdentity(title, author))?.let { "book:$it" }
            ?: "source:$source\u0000$url"
    }

    fun category(label: String, override: String? = null): String = LocalCategoryPolicy.category(label, override = override)

    /** Interleave sources rather than allowing a fast/large source to fill the whole first page. */
    fun <T> fairMerge(lists: List<List<T>>, identity: (T) -> String): List<T> {
        val result = ArrayList<T>()
        val seen = HashSet<String>()
        val max = lists.maxOfOrNull { it.size } ?: 0
        for (i in 0 until max) for (list in lists) {
            val item = list.getOrNull(i) ?: continue
            if (seen.add(identity(item))) result.add(item)
        }
        return result
    }

    /** Keep query parameters: removing them can conflate distinct articles on the same endpoint. */
    fun articleIdentity(origin: String, link: String): String = try {
        val uri = URI(origin).resolve(link).normalize()
        if (uri.scheme?.lowercase(Locale.ROOT) in setOf("http", "https") && uri.host != null) {
            URI(uri.scheme.lowercase(Locale.ROOT), uri.userInfo,
                uri.host.lowercase(Locale.ROOT), uri.port, uri.path.ifEmpty { "/" }, uri.query, null)
                .toASCIIString()
        } else "$origin\u0000$link"
    } catch (_: Exception) { "$origin\u0000$link" }

    fun publicationTime(value: String?): Long? {
        if (value.isNullOrBlank()) return null
        return try { Instant.parse(value).toEpochMilli() } catch (_: Exception) {
            try { ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() }
            catch (_: Exception) { null }
        }
    }
}
