package io.legado.app.model.aggregation

import org.junit.Assert.*
import org.junit.Test

class AggregationPolicyTest {
    @Test fun originalCategoryNamesOnlyTrimAndNeverCollapseIntoBroadGenres() {
        assertEquals("玄幻", AggregationPolicy.category(" 玄幻 "))
        assertNotEquals(AggregationPolicy.category("玄幻"), AggregationPolicy.category("奇幻"))
        assertNotEquals(AggregationPolicy.category("都市"), AggregationPolicy.category("都市生活"))
        assertNotEquals(AggregationPolicy.category("现代言情"), AggregationPolicy.category("古代言情"))
        assertEquals("奇幻", AggregationPolicy.category("奇幻", "獨立分類"))
    }

    @Test fun booksRequireKnownMatchingAuthor() {
        fun key(author: String, source: String = "a") =
            AggregationPolicy.bookIdentity("同一本書", author, source, "https://$source/book")
        assertNotEquals(key("甲"), key("乙"))
        assertEquals(key("作者：甲"), key("甲 著", "b"))
        assertNotEquals(key("", "a"), key("", "b"))
        assertNotEquals(key("未知", "a"), key("未知", "b"))
        assertNotEquals(key("佚名", "a"), key("佚名", "b"))
    }

    @Test fun fairMergeMakesProgressAcrossHundredSourcesWithoutDuplicates() {
        val sources = (0 until 100).map { s -> (0 until 30).map { "$s:$it" } }
        val output = AggregationPolicy.fairMerge(sources) { it }
        assertEquals(3000, output.size)
        assertEquals((0 until 100).map { "$it:0" }, output.take(100))
        assertEquals(listOf("same", "a", "b"),
            AggregationPolicy.fairMerge(listOf(listOf("same", "a"), listOf("same", "b"))) { it })
    }

    @Test fun articleIdentityResolvesUrlsAndKeepsDistinctQueryParameters() {
        assertEquals(AggregationPolicy.articleIdentity("https://a.example/feed", "/article?id=1#section"),
            AggregationPolicy.articleIdentity("https://b.example/feed", "https://a.example/article?id=1"))
        assertNotEquals(AggregationPolicy.articleIdentity("https://a.example", "/article?id=1"),
            AggregationPolicy.articleIdentity("https://a.example", "/article?id=2"))
        assertNotEquals(AggregationPolicy.articleIdentity("https://a.example", "/x"),
            AggregationPolicy.articleIdentity("https://b.example", "/x"))
    }

    @Test fun publicationDatesUseExplicitTimesAndLeaveUnknownTextUnknown() {
        assertEquals(AggregationPolicy.publicationTime("2026-10-26T00:00:00Z"),
            AggregationPolicy.publicationTime("Mon, 26 Oct 2026 08:00:00 +0800"))
        assertTrue(AggregationPolicy.publicationTime("2026-10-26T00:00:00Z")!! >
            AggregationPolicy.publicationTime("2026-01-01T00:00:00Z")!!)
        assertNull(AggregationPolicy.publicationTime("昨天"))
        assertNull(AggregationPolicy.publicationTime(null))
    }
}
