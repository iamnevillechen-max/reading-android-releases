package io.legado.app.model.aggregation

import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.rule.ExploreRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ExploreAggregationTest {
    @Test fun parsingRuleEditsInvalidateRoutesWithoutChangingSourceMetadata() {
        val source = BookSource(bookSourceUrl = "https://a.example", bookSourceName = "A",
            exploreUrl = "都市::/city", ruleExplore = ExploreRule(bookList = ".old"))
        val before = ExploreAggregation.routeFingerprint(ExploreAggregation.sourceFingerprint(source), "/city")
        source.ruleExplore!!.bookList = ".new"
        val after = ExploreAggregation.routeFingerprint(ExploreAggregation.sourceFingerprint(source), "/city")
        assertEquals(0L, source.lastUpdateTime)
        assertNotEquals(before, after)
    }

    @Test fun differentExploreUrlsRetainIndependentFingerprints() {
        val source = BookSource(bookSourceUrl = "https://a.example", bookSourceName = "A")
        val configuration = ExploreAggregation.sourceFingerprint(source)
        assertEquals(ExploreAggregation.routeFingerprint(configuration, "/city"),
            ExploreAggregation.routeFingerprint(configuration, "/city"))
        assertNotEquals(ExploreAggregation.routeFingerprint(configuration, "/city"),
            ExploreAggregation.routeFingerprint(configuration, "/history"))
    }
    @Test fun aSuccessfulMenuPublishesAllOriginalCategoriesInsteadOfOnlyItsFirstRoute() {
        val part = io.legado.app.data.entities.BookSourcePart(bookSourceUrl = "https://owned.example/source", bookSourceGroup = "舊大類")
        val kinds = (0 until 1000).map { io.legado.app.data.entities.rule.ExploreKind("站點獨有細類 $it", "/list/$it") }
        val routes = ExploreAggregation.menuRoutes(part, kinds, "owned-revision")
        assertEquals(1000, routes.size)
        assertEquals(kinds.map { it.title }, routes.map { it.category })
        assertEquals("/list/999", routes.last().url)
        assertEquals("舊大類", part.bookSourceGroup)
    }

    @Test fun menuRouteCleanupOnlyDeduplicatesTheSameTrimmedLabelAndUrl() {
        val part = io.legado.app.data.entities.BookSourcePart(bookSourceUrl = "https://owned.example/source")
        fun kind(title: String, url: String) = io.legado.app.data.entities.rule.ExploreKind(title, url)
        val routes = ExploreAggregation.menuRoutes(part, listOf(kind(" 玄幻 ", "/a"), kind("玄幻", "/a"),
            kind("奇幻", "/b"), kind("玄幻", "/other"), kind(" ", "/blank")), "owned-revision")
        assertEquals(listOf("玄幻", "奇幻", "玄幻"), routes.map { it.category })
        assertEquals(listOf("/a", "/b", "/other"), routes.map { it.url })
        assertNotEquals(routes[0].fingerprint, routes[2].fingerprint)
    }

}
