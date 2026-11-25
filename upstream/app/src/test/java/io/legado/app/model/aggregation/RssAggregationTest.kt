package io.legado.app.model.aggregation

import io.legado.app.data.entities.RssArticle
import io.legado.app.data.entities.RssSource
import io.legado.app.data.entities.RssCategorySpec
import org.junit.Assert.assertEquals
import org.junit.Test

class RssAggregationTest {
    @Test fun lightweightCatalogueRetains443FeedsAndLiteralCategoriesWithoutRunningScripts() {
        val feeds = (0 until 443).map {
            RssSource(sourceUrl = "https://fixture/$it", sourceGroup = "组${it % 7}",
                sortUrl = if (it % 5 == 0) "@js:throw new Error('must not run')" else "分类$it::/one&&分类$it::/two")
        } + RssSource(sourceUrl = "https://fixture/unclassified")
        val lightweight = feeds.map { RssCategorySpec(it.sourceGroup, it.sortUrl) }
        val names = RssCategories.namesFromMetadata(lightweight)
        assertEquals(RssCategories.names(feeds), names)
        assertEquals(names.size, names.distinct().size)
        assertEquals(RssCategories.UNCLASSIFIED, names.last())
        assertEquals(true, names.contains("分类442"))
        assertEquals(true, names.contains("组1"))
        assertEquals(false, names.contains("其他"))
        assertEquals("组1", feeds[1].sourceGroup)
        assertEquals("分类442::/one&&分类442::/two", feeds[442].sortUrl)
        assertEquals(false, names.any { it.contains("throw") })
    }

    @Test fun resolvedDynamicSortsBecomeExactCategoriesWithoutRunningScriptsAgain() {
        val source = RssSource(sourceUrl = "https://owned.example/feed", sourceGroup = "作者原分組", sortUrl = "@js:mustNotRun()")
        val resolved = listOf("赛博 修仙", "奇幻", "作者原分組", "奇幻")
        val names = RssCategories.namesFromMetadata(listOf(RssCategorySpec(source.sourceGroup, source.sortUrl)), resolved)
        assertEquals(listOf("作者原分組", "赛博 修仙", "奇幻"), names)
        assertEquals(true, RssCategories.includesSource(source, "赛博 修仙", resolved))
        assertEquals(false, RssCategories.includesSource(source, "赛博 修仙"))
        assertEquals(true, RssCategories.includesSort(source, "赛博 修仙", "赛博 修仙"))
        assertEquals(false, RssCategories.includesSort(source, "赛博修仙", "赛博 修仙"))
        assertEquals("作者原分組", source.sourceGroup)
        assertEquals("@js:mustNotRun()", source.sortUrl)
    }

    @Test fun aResolvedNamedSortDoesNotLeakIntoUnclassifiedArticles() {
        val source = RssSource(sourceUrl = "https://owned.example/feed", sortUrl = "@js:mustNotRun()")
        assertEquals(true, RssCategories.includesSort(source, "", RssCategories.UNCLASSIFIED))
        assertEquals(false, RssCategories.includesSort(source, "網站原類", RssCategories.UNCLASSIFIED))
        assertEquals(false, RssCategories.includesSource(source, RssCategories.UNCLASSIFIED, listOf("網站原類")))
    }

    @Test fun identicalLinksDeduplicateButSimilarTitlesKeepBothArticles() {
        val first = RssArticle(origin = "https://a.example/feed", link = "/article?id=1#part", title = "相同標題")
        val copy = RssArticle(origin = "https://b.example/feed", link = "https://a.example/article?id=1", title = "相同標題")
        val different = RssArticle(origin = "https://a.example/feed", link = "/article?id=2", title = "相同標題")
        assertEquals(listOf(first, different), RssAggregation.merge(listOf(listOf(first, different), listOf(copy))))
    }

    @Test fun knownDatesSortNewestFirstUnknownDatesKeepFairOrder() {
        val old = RssArticle(origin = "https://a", link = "/old", pubDate = "2026-01-01T00:00:00Z")
        val recent = RssArticle(origin = "https://b", link = "/new", pubDate = "Mon, 26 Oct 2026 08:00:00 +0800")
        val firstUnknown = RssArticle(origin = "https://a", link = "/u", pubDate = "昨天")
        val secondUnknown = RssArticle(origin = "https://b", link = "/u")
        assertEquals(listOf(recent, old, firstUnknown, secondUnknown),
            RssAggregation.merge(listOf(listOf(old, firstUnknown), listOf(recent, secondUnknown))))
    }
}
