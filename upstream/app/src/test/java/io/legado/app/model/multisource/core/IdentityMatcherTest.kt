package io.legado.app.model.multisource.core

import org.junit.Assert.*
import org.junit.Test

class IdentityMatcherTest {
    @Test fun bookFormattingDifferencesMatchButAuthorsMustAgree() {
        assertTrue(IdentityMatcher.sameBook(BookIdentity("《河谷來信》", "作者：阿青 著"), BookIdentity("河谷來信", "阿青")))
        assertFalse(IdentityMatcher.sameBook(BookIdentity("河谷來信", "阿青"), BookIdentity("河谷來信", "小明")))
        assertFalse(IdentityMatcher.sameBook(BookIdentity("河谷來信", ""), BookIdentity("河谷來信", "")))
        assertNull(IdentityMatcher.bookKey(BookIdentity("河谷來信", "佚名")))
        assertFalse(IdentityMatcher.sameBook(BookIdentity("河谷來信", "林著"), BookIdentity("河谷來信", "林")))
        listOf("不詳", "不详", "暫無作者", "暂无作者", "未提供", "無名氏", "无名氏").forEach { unknown ->
            assertFalse(IdentityMatcher.sameBook(BookIdentity("河谷來信", unknown), BookIdentity("河谷來信", unknown)))
            assertNull(IdentityMatcher.bookKey(BookIdentity("河谷來信", "作者：$unknown")))
        }
    }

    @Test fun insertedAnnouncementDoesNotShiftChapterMatch() {
        val target = ChapterIdentity("第十章 渡河", 9, "第九章 來信", "第十一章 山路")
        val shifted = ChapterIdentity("第十章：渡河", 10, "第九章 來信", "第十一章 山路")
        val candidates = listOf(ChapterIdentity("公告", 0), shifted, ChapterIdentity("第十一章 山路", 11))
        assertEquals(shifted, IdentityMatcher.matchChapter(target, candidates))
    }

    @Test fun ordinalOnlyAndSameIndexCannotProveIdentity() {
        val target = ChapterIdentity("第十章", 9)
        assertFalse(IdentityMatcher.sameChapter(target, ChapterIdentity("第十章", 9)))
        assertNull(IdentityMatcher.matchChapter(target, listOf(ChapterIdentity("第十章", 9))))
    }

    @Test fun ordinalOnlyTitlesRequireRealNeighborContext() {
        val target = ChapterIdentity("第十章", 9, "第九章 來信", "第十一章 山路")
        val shifted = ChapterIdentity("第十章", 10, "第九章 來信", "第十一章 山路")
        assertTrue(IdentityMatcher.sameChapter(target, shifted))
    }

    @Test fun duplicateTitlesRequireNeighborDisambiguation() {
        val target = ChapterIdentity("第十章 歸途", 9, "第九章 渡河", "第十一章 山路")
        val wrong = ChapterIdentity("第五章 歸途", 5, "第四章 迷路", "第六章 火光")
        val right = ChapterIdentity("第十章 歸途", 10, "第九章 渡河", "第十一章 山路")
        assertEquals(right, IdentityMatcher.matchChapter(target, listOf(wrong, right)))
        assertNull(IdentityMatcher.matchChapter(target.copy(previousTitle = null, nextTitle = null), listOf(wrong, right)))
    }

    @Test fun splitChapterPartsShareSemanticTitle() {
        assertFalse(IdentityMatcher.sameChapter(ChapterIdentity("第一百章 渡河", 99), ChapterIdentity("第100章 渡河（上）", 109)))
        assertEquals("雨中", IdentityMatcher.chapterTitle("第一章 雨中"))
        assertFalse(IdentityMatcher.sameChapter(ChapterIdentity("第一章 雨中", 0), ChapterIdentity("第一章 雨", 0)))
    }

    @Test fun numberedOnlyChapterFormatsNeedCanonicalOrdinalAndContext() {
        val target = ChapterIdentity("第一百章", 99, "第九十九章 來信", "第一百零一章 山路")
        val candidate = ChapterIdentity("第100章", 120, "第99章 來信", "第101章 山路")
        assertTrue(IdentityMatcher.sameChapter(target, candidate))
        assertFalse(IdentityMatcher.sameChapter(target, candidate.copy(title = "第101章")))
        assertFalse(IdentityMatcher.sameChapter(target.copy(previousTitle = null, nextTitle = null), candidate))
    }

    @Test fun completeTargetAcceptsOnlyCompleteExplicitAdjacentParts() {
        val target = ChapterIdentity("第十章 渡河", 9, "第九章 來信", "第十一章 山路")
        val upper = ChapterIdentity("第10章 渡河（上）", 10, "第九章 來信", "第十章 渡河（下）")
        val lower = ChapterIdentity("第十章 渡河（下）", 11, "第10章 渡河（上）", "第十一章 山路")
        assertEquals(listOf(upper, lower), IdentityMatcher.matchChapterParts(target, listOf(upper, lower)))
        assertEquals("第10章 渡河（上）", IdentityMatcher.matchChapterParts(target, listOf(upper, lower)).first().title)
        assertTrue(IdentityMatcher.matchChapterParts(target, listOf(upper)).isEmpty())
        assertTrue(IdentityMatcher.matchChapterParts(target, listOf(lower, upper)).isEmpty())
        assertTrue(IdentityMatcher.matchChapterParts(target, listOf(upper, lower.copy(index = 12))).isEmpty())
    }

    @Test fun threePartSourceMustContainUpperMiddleLowerWithOneOrdinal() {
        val target = ChapterIdentity("第一百章 渡河", 99)
        val upper = ChapterIdentity("第100章 渡河（上）", 120)
        val middle = ChapterIdentity("第一百章 渡河（中）", 121)
        val lower = ChapterIdentity("第100章 渡河（下）", 122)
        assertEquals(listOf(upper, middle, lower), IdentityMatcher.matchChapterParts(target, listOf(upper, middle, lower)))
        assertTrue(IdentityMatcher.matchChapterParts(target, listOf(upper, middle, lower.copy(title = "第101章 渡河（下）"))).isEmpty())
    }

    @Test fun splitPrimaryNeverConsumesFullChapterOrOppositePart() {
        val upper = ChapterIdentity("第十章 渡河（上）", 10)
        val lower = ChapterIdentity("第十章 渡河（下）", 11)
        val complete = ChapterIdentity("第十章 渡河", 9)
        assertFalse(IdentityMatcher.sameChapter(upper, lower))
        assertFalse(IdentityMatcher.sameChapter(upper, complete))
        assertTrue(IdentityMatcher.matchChapterParts(upper, listOf(complete, lower)).isEmpty())
        assertEquals(listOf(upper), IdentityMatcher.matchChapterParts(upper, listOf(complete, upper, lower)))
    }

    @Test fun repeatedPartGroupsAreAmbiguousUnlessOutsideNeighborsIdentifyThem() {
        val target = ChapterIdentity("第十章 渡河", 9)
        val first = listOf(ChapterIdentity("第十章 渡河（上）", 10), ChapterIdentity("第十章 渡河（下）", 11))
        val second = listOf(ChapterIdentity("第十章 渡河（上）", 20, "第九章 來信"), ChapterIdentity("第十章 渡河（下）", 21, nextTitle = "第十一章 山路"))
        assertTrue(IdentityMatcher.matchChapterParts(target, first + second).isEmpty())
        assertEquals(second, IdentityMatcher.matchChapterParts(target.copy(previousTitle = "第九章 來信", nextTitle = "第十一章 山路"), first + second))
    }

    @Test fun numberedOnlyPartsNeedTwoSemanticOutsideNeighbors() {
        val target = ChapterIdentity("第十章", 9, "第九章 來信", "第十一章 山路")
        val upper = ChapterIdentity("第10章（上）", 10, "第九章 來信", "第十章（下）")
        val lower = ChapterIdentity("第十章（下）", 11, "第10章（上）", "第十一章 山路")
        assertEquals(listOf(upper, lower), IdentityMatcher.matchChapterParts(target, listOf(upper, lower)))
        assertTrue(IdentityMatcher.matchChapterParts(target.copy(nextTitle = null), listOf(upper, lower)).isEmpty())
        assertTrue(IdentityMatcher.matchChapterParts(target, listOf(upper, lower.copy(nextTitle = "第十一章 荒原"))).isEmpty())
    }

    @Test fun unrelatedAdjacentChaptersAreNeverGuessedToBeOneChapter() {
        val target = ChapterIdentity("第十章 渡河", 9)
        val unrelated = listOf(ChapterIdentity("第十章 渡河", 10), ChapterIdentity("第十一章 山路", 11))
        assertEquals(listOf(unrelated.first()), IdentityMatcher.matchChapterParts(target, unrelated))
        assertTrue(IdentityMatcher.matchChapterParts(target, listOf(ChapterIdentity("第十章 渡河（上）", 10), ChapterIdentity("第十章 山路（下）", 11))).isEmpty())
    }
}
