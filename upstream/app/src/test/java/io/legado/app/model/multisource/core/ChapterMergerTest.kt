package io.legado.app.model.multisource.core

import org.junit.Assert.*
import org.junit.Test

/** All narrative is purpose-written test data, with no third-party novel text. */
class ChapterMergerTest {
    private val paragraphs = listOf(
        "清晨的小鎮還沒有醒來，阿青背上竹籃，沿著石板路慢慢走向河邊。",
        "船夫早已解開纜繩，木船隨著水波輕輕搖晃，遠處傳來清脆的鐘聲。",
        "她將那封封好的信藏進衣袋，確認藍色的火漆仍然完整，才踏上船板。",
        "河上的白霧逐漸散去，一群灰色水鳥從蘆葦深處飛起，掠過平靜水面。",
        "老船夫指著東面的山口，說午時之前必須到達對岸，否則就要等待明日。",
        "阿青沒有回答，只把籃子放在腳旁，取出母親留下的地圖仔細看了一遍。",
        "地圖上的紅線繞過一座廢棄磨坊，最後停在山腳那棵分成三叉的老樹旁。",
        "忽然有個戴草帽的少年從岸邊跑來，他揮著一條白手巾，大聲呼喊船夫。",
        "船夫收起長篙，讓少年跳上木船，並吩咐兩人坐穩，前面的水流十分湍急。",
        "日光終於照亮河谷，阿青望著漸漸遠去的小鎮，握緊衣袋裡那封重要的信。"
    )
    private val book = BookIdentity("河谷來信", "測試作者")
    private val chapter = ChapterIdentity("第十章 渡河", 9, "第九章 來信", "第十一章 山路")
    private fun text(parts: List<String>) = parts.joinToString("\n")
    private fun candidate(id: String, parts: List<String>, family: String = id) =
        ChapterCandidate(id, id, family, book, chapter, text(parts))
    private fun normalized(s: String) = IdentityMatcher.normalize(s)
    private fun assertBody(expected: List<String>, result: MergeResult) =
        assertEquals(normalized(text(expected)), normalized(result.text))

    @Test fun tailTruncationUsesCompleteTextWithoutRepeatingOverlap() {
        val result = ChapterMerger().merge(candidate("a", paragraphs.take(5)), listOf(candidate("b", paragraphs)))
        assertBody(paragraphs, result)
        assertTrue(result.merged)
        assertTrue(result.usedSourceIds.containsAll(listOf("a", "b")))
    }

    @Test fun complementarySourcesProduceActualUnionNotJustSourceSwitch() {
        val result = ChapterMerger().merge(candidate("a", paragraphs.take(6)), listOf(candidate("b", paragraphs.drop(3))))
        assertBody(paragraphs, result)
        assertTrue(result.merged)
        assertEquals(MergeConfidence.MEDIUM, result.confidence)
    }

    @Test fun middleGapAndTailRebuildInCorrectOrderFromThreePartialSources() {
        val primary = paragraphs.take(3) + paragraphs.drop(6)
        val result = ChapterMerger().merge(candidate("a", primary), listOf(
            candidate("b", paragraphs.subList(1, 8)), candidate("c", paragraphs.drop(4))))
        assertBody(paragraphs, result)
        assertTrue(result.merged)
    }

    @Test fun markedGapsAreReplacedOnlyWhereMatchingTextBracketsTheDonorPassage() {
        val primary = candidate("a", paragraphs.take(3) + "本段内容缺失" + paragraphs.subList(5, 8) + "試讀已結束。")
        val merger = ChapterMerger()
        assertEquals(primary.text, merger.merge(primary, emptyList()).text)
        val complete = merger.merge(primary, listOf(candidate("b", paragraphs)))
        assertBody(paragraphs, complete)
        assertTrue(complete.merged)
        paragraphs.forEach { assertTrue(complete.text.contains(it)) }

        val partial = merger.merge(primary, listOf(candidate("b", paragraphs.take(8))))
        assertTrue(partial.text.contains(paragraphs[3]))
        assertTrue(partial.text.contains("試讀已結束。"))
        val disputed = paragraphs.toMutableList().apply { this[6] = "真正的情節在不同版本裡向另一條路發展。" }
        assertEquals(primary.text, merger.merge(primary, listOf(candidate("b", disputed))).text)
    }

    @Test fun aDonorThatItselfEndsInAPreviewCannotBecomeTheRecoveredEdition() {
        val preview = candidate("b", paragraphs.take(3) + "本章为付费章节，请订阅后阅读")
        val merger = ChapterMerger()
        assertTrue(merger.readableBody(preview).isEmpty())
        assertBody(paragraphs, merger.merge(candidate("a", listOf("请购买后阅读")), listOf(preview, candidate("c", paragraphs))))
    }

    @Test fun differentPunctuationAndParagraphLayoutStillAlign() {
        val a = candidate("a", paragraphs.take(6))
        val b = candidate("b", paragraphs.drop(3)).copy(text = paragraphs.drop(3).joinToString("").replace('，', ',').replace('。', '.'))
        assertBody(paragraphs, ChapterMerger().merge(a, listOf(b)))
    }

    @Test fun traditionalSimplifiedConversionOnlyChangesComparisonNotOutput() {
        val conversion: (String) -> String = { source -> source.replace('鎮', '镇').replace('籃', '篮').replace('邊', '边').replace('來', '来').replace('聲', '声') }
        val b = candidate("b", paragraphs.drop(3)).let { it.copy(book = BookIdentity(conversion(book.title), book.author), text = conversion(it.text)) }
        val result = ChapterMerger(conversion).merge(candidate("a", paragraphs.take(6)), listOf(b))
        assertEquals(IdentityMatcher.normalize(text(paragraphs), conversion), IdentityMatcher.normalize(result.text, conversion))
        assertTrue(result.text.startsWith(paragraphs.first()))
    }

    @Test fun advertisingLinesAreNotStabilizedIntoMergedBody() {
        val polluted = paragraphs.take(4) + "請收藏本站，下載APP獲取最新章節 https://example.invalid" + paragraphs.drop(4)
        val result = ChapterMerger().merge(candidate("a", paragraphs.take(5)), listOf(candidate("b", polluted)))
        assertBody(paragraphs, result)
        assertFalse(result.text.contains("example.invalid"))
    }

    @Test fun nextChapterHeadingIsHardBoundary() {
        val polluted = paragraphs + "第十一章 山路" + "這是下一章的內容，不能加入渡河的正文。"
        val result = ChapterMerger().merge(candidate("a", paragraphs.take(5)), listOf(candidate("b", polluted)))
        assertBody(paragraphs, result)
        assertFalse(result.text.contains("下一章的內容"))
    }

    @Test fun narrativeBeginningWithChapterCountersNeverTruncatesThePrimaryFallback() {
        val ordinary = listOf(
            "一回到家，他便將濕外衣掛在門邊。",
            "一卷書讀完，他又將燈芯撥亮了一些。",
            "十回合之後，少年終於收住腳步，將木劍放回竹架。",
            "第十回合結束時，林舟終於看見門外的朋友。",
            "第三節車廂裡的燈忽然亮了起來。"
        )
        val body = paragraphs.take(2) + ordinary + paragraphs.drop(2)
        val result = ChapterMerger().merge(candidate("a", body), emptyList())
        assertEquals(text(body), result.text)
        assertFalse(result.merged)

        val opening = listOf("她回來了。") + paragraphs
        val matchingTitle = candidate("a", opening).copy(chapter = chapter.copy(title = "第十章 她回來了"))
        assertEquals(text(opening), ChapterMerger().merge(matchingTitle, emptyList()).text)
    }

    @Test fun narrativeCountersRemainIntactWhileAutomaticMergeCompletesTheTail() {
        val body = paragraphs.take(2) + listOf(
            "一回頭，街角的那盞燈已經熄滅。",
            "第十回合結束時，林舟終於看見門外的朋友。"
        ) + paragraphs.drop(2)
        val result = ChapterMerger().merge(candidate("a", body.take(5)), listOf(candidate("b", body)))
        assertBody(body, result)
        assertTrue(result.merged)
        assertTrue(result.text.contains("一回頭，街角的那盞燈已經熄滅。"))
        assertTrue(result.text.contains("第十回合結束時，林舟終於看見門外的朋友。"))
        assertTrue(result.text.contains(paragraphs.last()))
    }

    @Test fun unprefixedKnownNeighborHeadingStillStopsAtTheTocBoundary() {
        val polluted = listOf("第十章渡河") + paragraphs + "十一章山路" + "相鄰章的這段測試文字不能進入本章。"
        val result = ChapterMerger().merge(candidate("a", paragraphs.take(5)), listOf(candidate("b", polluted)))
        assertBody(paragraphs, result)
        assertFalse(result.text.contains("相鄰章的這段"))
        assertFalse(result.text.contains("第十章渡河"))
    }

    @Test fun wrongChapterIsRejectedEvenWithSameOrdinal() {
        val b = candidate("b", paragraphs).copy(chapter = chapter.copy(title = "第十章 荒原"))
        val result = ChapterMerger().merge(candidate("a", paragraphs.take(5)), listOf(b))
        assertBody(paragraphs.take(5), result)
        assertFalse(result.merged)
    }

    @Test fun sameTitleDifferentAuthorIsRejected() {
        val b = candidate("b", paragraphs).copy(book = book.copy(author = "另一位作者"))
        val result = ChapterMerger().merge(candidate("a", paragraphs.take(5)), listOf(b))
        assertBody(paragraphs.take(5), result)
        assertFalse(result.merged)
    }

    @Test fun conflictingVersionsNeverCombineBothAlternatives() {
        val alternative = paragraphs.toMutableList().also { it[4] = "旅店的大門忽然打開，一位陌生商人邀請眾人吃飯，他們全都離開了河岸。" }
        val result = ChapterMerger().merge(candidate("a", paragraphs), listOf(candidate("b", alternative)))
        assertBody(paragraphs, result)
        assertFalse(result.merged)
        assertTrue(result.notes.any { it.contains("衝突") })
    }

    @Test fun conflictingSupplementRollsBackEarlierArrivalInsteadOfWinningByTiming() {
        val alternative = paragraphs.toMutableList().also { it[7] = "夜幕籠罩了陌生的森林，他們放棄木船，各自騎上快馬奔向遠方的王城。" }
        val primary = paragraphs.take(5)
        val result = ChapterMerger().merge(candidate("a", primary), listOf(candidate("b", paragraphs), candidate("c", alternative)))
        assertBody(primary, result)
        assertFalse(result.merged)
        assertEquals(MergeConfidence.LOW, result.confidence)
    }

    @Test fun mirrorFamilyCannotBecomeHighestConfidenceByVoteCount() {
        val result = ChapterMerger().merge(candidate("a", paragraphs, "mirror"), listOf(
            candidate("b", paragraphs, "mirror"), candidate("c", paragraphs, "mirror")))
        assertEquals(MergeConfidence.MEDIUM, result.confidence)
        assertTrue(result.segments.all { it.independentFamilies == 1 })
    }

    @Test fun independentConsistentSourcesIncreaseConfidence() {
        val result = ChapterMerger().merge(candidate("a", paragraphs), listOf(candidate("b", paragraphs)))
        assertEquals(MergeConfidence.HIGH, result.confidence)
        assertFalse(result.merged)
    }

    @Test fun unanchoredMaterialCannotBeAppendedJustBecauseTitleMatches() {
        val b = candidate("b", listOf("這份文字記錄的是完全不同的旅程，所有角色與事件都沒有前後關係，因此不能拼接進去。"))
        val result = ChapterMerger().merge(candidate("a", paragraphs.take(5)), listOf(b))
        assertBody(paragraphs.take(5), result)
        assertFalse(result.merged)
    }

    @Test fun reorderedBlocksDoNotScrambleOriginal() {
        val shuffled = paragraphs.take(2) + paragraphs.subList(4, 6) + paragraphs.subList(2, 4) + paragraphs.drop(6)
        val result = ChapterMerger().merge(candidate("a", paragraphs), listOf(candidate("b", shuffled)))
        assertBody(paragraphs, result)
    }

    @Test fun unavailablePrimaryUsesMatchedAvailableBody() {
        val result = ChapterMerger().merge(candidate("a", emptyList()), listOf(candidate("b", paragraphs)))
        assertBody(paragraphs, result)
        assertEquals(listOf("b"), result.usedSourceIds)
        assertFalse(result.merged)
        assertTrue(result.notes.any { it.contains("原來源無正文") })
    }

    @Test fun accessControlPageCannotBecomeBody() {
        val b = candidate("b", listOf("請先登入後閱讀，輸入驗證碼繼續。"))
        val result = ChapterMerger().merge(candidate("a", paragraphs.take(5)), listOf(b))
        assertBody(paragraphs.take(5), result)
        assertFalse(result.text.contains("驗證碼"))
    }

    @Test fun pathologicalLengthSafelyReturnsOriginalWithoutQuadraticAllocation() {
        val original = candidate("a", listOf("原".repeat(200_001)))
        val result = ChapterMerger().merge(original, emptyList())
        assertEquals(original.text, result.text)
        assertFalse(result.merged)
        assertEquals(MergeConfidence.LOW, result.confidence)
    }

    @Test fun splitPrimaryRejectsFullAndOppositePartBeforeTextReconstruction() {
        val upper = candidate("a", paragraphs.take(5)).copy(chapter = chapter.copy(title = "第十章 渡河（上）"))
        val complete = candidate("b", paragraphs)
        val lower = candidate("c", paragraphs.drop(3)).copy(chapter = chapter.copy(title = "第十章 渡河（下）"))
        val result = ChapterMerger().merge(upper, listOf(complete, lower))
        assertBody(paragraphs.take(5), result)
        assertFalse(result.merged)
        assertEquals(listOf("a"), result.usedSourceIds)
    }

    @Test fun validatedCompositeKeepsOriginalPunctuationOfBothParts() {
        val composite = candidate("b", paragraphs.subList(3, 7)).let {
            it.copy(text = it.text + "\n" + text(paragraphs.drop(7)))
        }
        val result = ChapterMerger().merge(candidate("a", paragraphs.take(6)), listOf(composite))
        assertBody(paragraphs, result)
        paragraphs.forEach { assertTrue(result.text.contains(it)) }
    }

    @Test fun validatedSplitCompositeStripsOnlyItsOwnPartHeadings() {
        for (separator in listOf(" ", "")) {
            val composite = candidate("b", emptyList()).copy(text = "第10章${separator}渡河（上）\n" +
                text(paragraphs.take(7)) + "\n第十章${separator}渡河（下）\n" + text(paragraphs.drop(7)))
            val result = ChapterMerger().merge(candidate("a", paragraphs.take(6)), listOf(composite))
            assertBody(paragraphs, result)
            assertFalse(result.text.contains("（上）"))
            assertFalse(result.text.contains("（下）"))
        }
    }

    @Test fun numericSplitCompositeKeepsBothPartsAndTheirPunctuation() {
        val numeric = chapter.copy(title = "第十章")
        val a = candidate("a", paragraphs.take(6)).copy(chapter = numeric)
        val b = candidate("b", emptyList()).copy(chapter = numeric,
            text = "第10章（上）\n" + text(paragraphs.take(7)) + "\n第十章（下）\n" + text(paragraphs.drop(7)))
        val result = ChapterMerger().merge(a, listOf(b))
        assertBody(paragraphs, result)
        paragraphs.forEach { assertTrue(result.text.contains(it)) }
    }

    @Test fun narrativeMentioningNavigationAndDownloadTermsIsPreserved() {
        val narrative = paragraphs.take(2) + listOf(
            "他翻開書，看見下一章記載著河谷的往事，便繼續讀了下去。",
            "她想起上一章寫過的地圖，終於明白船夫為何要提早出發。",
            "少年提到下載APP的經過，卻沒有說出那個神秘軟件的名字。",
            "下載APP的經過實在漫長，他決定先去街角買一杯熱茶。",
            "加入書籤後，他才注意到書頁背面還藏著另一段文字。",
            "紙上記下網址 https://example.invalid/story，旁邊還畫著一隻小船。") + paragraphs.drop(2)
        val original = candidate("a", narrative)
        val result = ChapterMerger().merge(original, emptyList())
        assertEquals(original.text, result.text)
    }

    @Test fun narrativeMentioningVerificationCodesIsNotMistakenForAccessPage() {
        val narrative = listOf("請輸入驗證碼的人站在門外，手裡還拿著一支細長的鉛筆。",
            "阿青在紙上寫下驗證碼，船夫看過之後便把字條交還給她。",
            "「請輸入驗證碼。」少年模仿那個陌生人的口氣，說完忍不住笑了。")
        val original = candidate("a", narrative)
        val result = ChapterMerger().merge(original, emptyList())
        assertEquals(original.text, result.text)
        assertFalse(result.notes.any { it.contains("返回登入") })
    }

    @Test fun standaloneNavigationLinesAreRemovedWithoutDroppingNarrative() {
        val polluted = paragraphs.take(3) + listOf("上一章 | 目录 | 下一章") + paragraphs.drop(3)
        val result = ChapterMerger().merge(candidate("a", paragraphs.take(5)), listOf(candidate("b", polluted)))
        assertBody(paragraphs, result)
        assertFalse(result.text.contains("上一章"))
    }

    @Test fun insertedAndFollowingDialogueKeepOpeningAndClosingQuotes() {
        val quoted = paragraphs.toMutableList().also {
            it[3] = "「我們要趕在日落之前到達對岸。」阿青把母親的話又說了一遍。"
            it[4] = "「前面的山路不好走，你們千萬不要離開石階。」老船夫認真地提醒兩人。"
            it[9] = "「下次再見。」少年朝漸漸遠去的船影揮手，然後沿著河岸慢慢走回小鎮。"
        }
        val primary = quoted.take(3) + quoted.subList(4, 9)
        val result = ChapterMerger().merge(candidate("a", primary), listOf(candidate("b", quoted)))
        assertBody(quoted, result)
        quoted.forEach { assertTrue("Original punctuation lost: $it", result.text.contains(it)) }
    }

    @Test fun duplicateSourceIdIsNotAnotherVote() {
        val result = ChapterMerger().merge(candidate("a", paragraphs), listOf(candidate("a", paragraphs)))
        assertEquals(1, result.usedSourceIds.size)
        assertEquals(MergeConfidence.LOW, result.confidence)
    }
}
