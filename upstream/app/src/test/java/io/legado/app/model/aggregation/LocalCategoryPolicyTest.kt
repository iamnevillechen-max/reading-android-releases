package io.legado.app.model.aggregation

import org.junit.Assert.*
import org.junit.Test

class LocalCategoryPolicyTest {
    @Test fun originalSiteNamesStayDistinctRatherThanBeingMappedToGenres() {
        val labels = listOf("玄幻", "奇幻", "玄幻／奇幻", "都市", "都市生活", "都市／現實",
            "現代言情", "古代言情", "站點獨有分類", "其他", "新聞", "全部")
        assertEquals(labels, labels.map { LocalCategoryPolicy.category(it) })
        assertEquals(labels, LocalCategoryPolicy.mergeLabels(labels))
    }

    @Test fun onlySurroundingWhitespaceIsCleanedAndEmptyInputDoesNotSynthesizeOther() {
        assertEquals("原分類", LocalCategoryPolicy.category(" \t原分類\r\n"))
        assertEquals("原分類", LocalCategoryPolicy.category("\u3000原分類\u3000"))
        for (label in listOf(null, "", "   ", "\r\n\t", "\u3000")) {
            assertEquals("", LocalCategoryPolicy.category(label))
        }
        assertTrue(LocalCategoryPolicy.mergeLabels(listOf("", " ", "\t\n")).isEmpty())
    }

    @Test fun sourceGroupsAndRetiredFixedOverridesCannotReplaceSiteLabels() {
        assertEquals("古言", LocalCategoryPolicy.category(" 古言 ", "玄幻奇幻", "romance"))
        assertEquals("排行榜", LocalCategoryPolicy.category("排行榜", "男頻都市生活", "都市／現實"))
        assertEquals("站點自訂", LocalCategoryPolicy.category("站點自訂", "原分組", "其他"))
        assertEquals("", LocalCategoryPolicy.category(null, "原分組", "其他"))
        assertEquals("", LocalCategoryPolicy.category(" \n", "玄幻", "fantasy"))
    }

    @Test fun caseWidthAndTraditionalSimplifiedDifferencesAreNotNormalized() {
        val labels = listOf("都市", "都市生活", "现实", "現實", "Fantasy", "fantasy", "ＦＡＮＴＡＳＹ",
            "SCI-FI", "sci-fi", "科幻／未來", "科幻/未來")
        assertEquals(labels, LocalCategoryPolicy.mergeLabels(labels))
        labels.forEach { assertEquals(it, LocalCategoryPolicy.category(it)) }
    }

    @Test fun internalWhitespaceAndUnicodeCompositionRemainPartOfTheOriginalName() {
        val labels = listOf("科 幻", "科  幻", "科\t幻", "科\n幻", "é", "e\u0301")
        assertEquals(labels, LocalCategoryPolicy.mergeLabels(labels))
        labels.forEach { assertEquals(it, LocalCategoryPolicy.category(" $it ")) }
    }

    @Test fun onlyTrimmedExactDuplicatesAreRemovedInFirstSeenOrder() {
        assertEquals(listOf("分類乙", "分類甲", "分类甲", "分類丙"), LocalCategoryPolicy.mergeLabels(
            listOf(" 分類乙 ", "分類甲", "分類乙", "分类甲", "\t分類甲\n", "", "分類丙", "分類乙")))
        assertEquals(listOf("乙", "甲"), LocalCategoryPolicy.mergeLabels(listOf("乙", "甲")))
        assertEquals(listOf("甲", "乙"), LocalCategoryPolicy.mergeLabels(listOf("甲", "乙")))
    }

    @Test fun oneThousandDifferentLabelsAreRetainedWithoutACategoryCap() {
        val labels = (0 until 1_000).map { "網站分類$it" }
        assertEquals(labels, LocalCategoryPolicy.mergeLabels(labels + labels.take(100)))
    }

    @Test fun allTwentyOneThousandLabelsAndTheirInputOrderArePreserved() {
        val labels = (0 until 21_149).map { "站點獨有分類$it" }
        val before = labels.toList()
        val wrapped = labels.map { " $it " } + labels.reversed() + listOf("", " ")
        assertEquals(labels, LocalCategoryPolicy.mergeLabels(wrapped))
        assertEquals(before, labels)
        assertEquals(labels.reversed(), LocalCategoryPolicy.mergeLabels(labels.reversed()))
    }

    @Test fun cleaningAndExactMergingAreIdempotentWithoutChangingOriginalInputs() {
        val original = listOf(" 甲 ", "甲", " 乙", "乙 ", "ＦＯＯ", "foo", "")
        val before = original.toList()
        val merged = LocalCategoryPolicy.mergeLabels(original)
        assertEquals(listOf("甲", "乙", "ＦＯＯ", "foo"), merged)
        assertEquals(merged, LocalCategoryPolicy.mergeLabels(merged))
        assertEquals(before, original)
        original.forEach { label ->
            val cleaned = LocalCategoryPolicy.category(label)
            assertEquals(cleaned, LocalCategoryPolicy.category(cleaned))
        }
    }
}
