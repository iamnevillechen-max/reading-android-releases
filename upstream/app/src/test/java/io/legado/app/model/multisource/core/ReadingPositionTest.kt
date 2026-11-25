package io.legado.app.model.multisource.core

import org.junit.Assert.*
import org.junit.Test

class ReadingPositionTest {
    private val chapter = "第一章 木門。\n街道兩旁的樹影慢慢搖動，旅人收起雨傘走進屋內。\n" +
        "他推開木門，看見桌上的藍色信封，便停下腳步仔細端詳。\n遠處鐘聲響起，窗外的船隻向港口緩緩駛去。"

    @Test fun insertionBeforeCurrentTextKeepsTheSameWords() {
        val position = chapter.indexOf("藍色信封")
        val updated = chapter.replace("街道", "這是補回的開頭段落，交代了此前未見的經過。\n街道")
        assertEquals(updated.indexOf("藍色信封"), ReadingPosition.relocate(chapter, updated, position))
    }

    @Test fun insertionExactlyAtCurrentPositionLandsAfterInsertedText() {
        val position = chapter.indexOf("藍色信封")
        val updated = chapter.substring(0, position) + "補回的文字先交代來歷。" + chapter.substring(position)
        assertEquals(updated.indexOf("藍色信封"), ReadingPosition.relocate(chapter, updated, position))
    }

    @Test fun deletionBeforeTheCurrentTextMovesBackToTheSameWords() {
        val position = chapter.indexOf("藍色信封")
        val updated = chapter.replace("街道兩旁的樹影慢慢搖動，旅人收起雨傘走進屋內。\n", "")
        assertEquals(updated.indexOf("藍色信封"), ReadingPosition.relocate(chapter, updated, position))
    }

    @Test fun whitespaceAndPunctuationDoNotChangeTheAnchor() {
        val position = chapter.indexOf("藍色信封")
        val updated = "新補的序言。\n" + chapter.replace("，", "；  ").replace("。", "！\n")
        assertEquals(updated.indexOf("藍色信封"), ReadingPosition.relocate(chapter, updated, position))
    }

    @Test fun missingOrRepeatedAnchorsKeepAClampedOffset() {
        assertEquals(5, ReadingPosition.relocate(chapter, "完全不同的內容", 5))
        assertEquals(4, ReadingPosition.relocate(chapter, "短段文字", 900))
        val repeated = chapter.repeat(3)
        val position = chapter.length + chapter.indexOf("藍色信封")
        assertEquals(position, ReadingPosition.relocate(repeated, "新增開頭。" + repeated, position))
    }

    @Test fun deletingTheTextBeingReadDoesNotInventAReplacementAnchor() {
        val position = chapter.indexOf("藍色信封")
        val updated = chapter.replace("藍色信封", "紅色木盒")
        assertEquals(position.coerceAtMost(updated.length), ReadingPosition.relocate(chapter, updated, position))
    }

    @Test fun emptyAndOutOfRangeInputsAreSafe() {
        assertEquals(0, ReadingPosition.relocate("", "", Int.MAX_VALUE))
        assertEquals(0, ReadingPosition.relocate(chapter, "x", -99))
        assertEquals(1, ReadingPosition.relocate(chapter, "x", Int.MAX_VALUE))
        assertEquals(chapter.length, ReadingPosition.relocate(chapter, chapter, Int.MAX_VALUE))
    }

    @Test fun bookmarkUsesItsSavedQuoteDespitePunctuationChanges() {
        val quote = "他推開木門，看見桌上的藍色信封，便停下腳步仔細端詳。"
        val updated = "前面增加了一段。\n" + chapter.replace("，", " ； ")
        assertEquals(updated.indexOf("他推開木門"), ReadingPosition.locateBookmark(updated, quote, 0))
    }

    @Test fun bookmarkCanFindAnUnchangedPrefixWhenThePageGainsAnInsertedParagraph() {
        val quote = chapter.substring(chapter.indexOf("他推開木門"))
        val updated = chapter.replace("遠處鐘聲響起", "補回中間的事情，人物正在收拾行裝。\n遠處鐘聲響起")
        assertEquals(updated.indexOf("他推開木門"), ReadingPosition.locateBookmark(updated, quote, 0))
    }

    @Test fun repeatedMissingOrTooShortBookmarkQuotesKeepTheSavedOffset() {
        val quote = chapter.substring(chapter.indexOf("他推開木門"))
        assertEquals(9, ReadingPosition.locateBookmark(chapter + chapter, quote, 9))
        assertEquals(7, ReadingPosition.locateBookmark(chapter, "不存在的一段很長的獨特原文內容", 7))
        assertEquals(6, ReadingPosition.locateBookmark(chapter, "木門", 6))
        assertEquals(0, ReadingPosition.locateBookmark("", quote, 500))
    }

    @Test fun compatibilityWidthAndSupplementaryCharactersMapToOriginalUtf16Offsets() {
        val original = "序言𠀀Thetravelleropenedthewoodendoorandfoundtheblueletterinside."
        val position = original.indexOf("found")
        val updated = "前面補字。" + original.replace("The", "Ｔｈｅ")
        assertEquals(updated.indexOf("found"), ReadingPosition.relocate(original, updated, position))
        assertEquals(0, ReadingPosition.relocate("a", "𠀀", 1))
    }

    @Test fun oversizedInputsUseBoundedFallback() {
        val enormous = "abcd".repeat(70_000)
        assertEquals(42, ReadingPosition.relocate(enormous, "前綴" + enormous, 42))
        assertEquals(3, ReadingPosition.locateBookmark(chapter, enormous, 3))
    }
}
