package io.legado.app.model.multisource.core

import org.junit.Assert.*
import org.junit.Test

class ReadingProgressAnchorTest {
    private val book = "https://fixture.invalid/book"
    private val chapter = "$book/chapter/3"
    private val raw = "第三章 河岸\n　　林舟沿著河邊的石路慢慢前進，遠方的鐘樓傳來清晰而平穩的聲音。\n" +
        "　　木橋上的旅客收起雨傘，將一封藍色信件交給站在門邊等候多時的朋友。\n" +
        "　　晨光穿過雲層照亮高牆，守夜人正在整理昨晚收到的消息。\n"
    private val added = "　　這一段補回失去的經過，說明鑰匙為何被留在古老倉庫的窗前。\n"
    private val derived = raw.replace("　　木橋", added + "　　木橋")

    @Test fun knownRenderedRawAnchorMovesToTheSameWordsAfterAnInsertion() {
        val position = raw.indexOf("藍色信件")
        val saved = ReadingProgressAnchors.capture(book, chapter, 3, raw, position)!!
        assertEquals(derived.indexOf("藍色信件"),
            ReadingProgressAnchors.restore(saved, book, chapter, 3, derived, position))
    }

    @Test fun reopeningTheSameDerivedVersionDoesNotMapFromRawAgain() {
        val position = derived.indexOf("藍色信件")
        val saved = ReadingProgressAnchors.capture(book, chapter, 3, derived, position)!!
        repeat(3) {
            assertEquals(position, ReadingProgressAnchors.restore(saved, book, chapter, 3, derived, position))
        }
    }

    @Test fun aLaterVersionAndDisablingDerivedContentBothUseTheSavedWords() {
        val position = derived.indexOf("藍色信件")
        val saved = ReadingProgressAnchors.capture(book, chapter, 3, derived, position)!!
        val newer = "新增的前言交代了旅程開始前的準備。\n" + derived
        assertEquals(newer.indexOf("藍色信件"),
            ReadingProgressAnchors.restore(saved, book, chapter, 3, newer, position))
        assertEquals(raw.indexOf("藍色信件"),
            ReadingProgressAnchors.restore(saved, book, chapter, 3, raw, position))
    }

    @Test fun changedChapterBookOrExplicitPositionCannotReuseTheMarker() {
        val position = raw.indexOf("藍色信件")
        val saved = ReadingProgressAnchors.capture(book, chapter, 3, raw, position)!!
        assertFalse(ReadingProgressAnchors.matches(saved, "$book/other", chapter, 3, position))
        assertFalse(ReadingProgressAnchors.matches(saved, book, "$chapter/other", 3, position))
        assertFalse(ReadingProgressAnchors.matches(saved, book, chapter, 4, position))
        assertFalse(ReadingProgressAnchors.matches(saved, book, chapter, 3, position + 1))
        assertEquals(position + 1,
            ReadingProgressAnchors.restore(saved, book, chapter, 3, derived, position + 1))
    }

    @Test fun missingOrRepeatedQuoteKeepsTheAbsoluteOffsetNotTheQuoteLocalOffset() {
        val longText = "最早的一段只用來建立足夠長的前文。".repeat(20) + raw
        val position = longText.indexOf("藍色信件")
        val saved = ReadingProgressAnchors.capture(book, chapter, 3, longText, position)!!
        assertTrue(saved.quotePosition < position)
        assertEquals(position, ReadingProgressAnchors.restore(saved, book, chapter, 3,
            "完全不同的長內容需要保留原本數字位置。".repeat(100), position))
        val duplicated = longText + longText
        assertEquals(position, ReadingProgressAnchors.restore(saved, book, chapter, 3, duplicated, position))
    }

    @Test fun quotesAndTextAreBoundedAndInvalidVersionsAreRejected() {
        val text = raw.repeat(100)
        val saved = ReadingProgressAnchors.capture(book, chapter, 3, text, 1_000)!!
        assertTrue(saved.quote.length <= 162)
        assertFalse(ReadingProgressAnchors.matches(saved.copy(version = 99), book, chapter, 3, 1_000))
        assertFalse(ReadingProgressAnchors.matches(saved.copy(quote = "x".repeat(163)), book, chapter, 3, 1_000))
        assertNull(ReadingProgressAnchors.capture(book, chapter, 3, "x".repeat(250_001), 1))
        assertNull(ReadingProgressAnchors.capture(book, chapter, 3, raw, raw.length + 1))
    }

    @Test fun supplementaryCharactersDoNotSplitAtTheSavedPositionOrQuoteBoundary() {
        val text = "河岸晨光".repeat(30) + "𠮷" + raw
        val position = text.indexOf("𠮷") + 1
        val saved = ReadingProgressAnchors.capture(book, chapter, 3, text, position)!!
        assertEquals(position - 1, saved.position)
        assertFalse(saved.quote.first().isLowSurrogate())
        assertFalse(saved.quote.last().isHighSurrogate())
    }
}
