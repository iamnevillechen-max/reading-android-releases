package io.legado.app.model.multisource.core

import org.junit.Assert.*
import org.junit.Test

class ChapterContentPolicyTest {
    private val title = "第十章 河岸"

    @Test fun emptyShellAndFailureTemplatesAreMissing() {
        for (body in listOf("", "\u200b\ufeff\u2060", "$title\n上一章\n下一章", "加载正文失败\nHTTP 404",
            "$title\n本章暂无内容", "404 Not Found", "请先登录后阅读")) {
            assertTrue(body, ChapterContentPolicy.missing(body, title))
        }
    }

    @Test fun shortNarrativeAndFailureWordsInsideNarrativeRemainReadable() {
        for (body in listOf("門開了。", "$title\n門開了。", "他說，章节不存在也許只是誤會。",
            "加载正文失败的告示被旅客撕下，門後終於傳來了腳步聲。")) {
            assertFalse(body, ChapterContentPolicy.missing(body, title))
        }
    }

    @Test fun promptsAreWholeLinesAndAMarkedGapDoesNotDiscardRealNarrative() {
        for (line in listOf("本章为VIP章节，请订阅后继续阅读。", "正文加载失败，请刷新重试", "試讀已結束。", "���")) {
            assertTrue(line, ChapterContentPolicy.isRepairNotice(line))
            assertTrue(line, ChapterContentPolicy.missing(line, title))
            assertFalse(ChapterContentPolicy.missing("$line\n河面上漸漸亮起漁火。", title))
            assertFalse(ChapterContentPolicy.isRepairNotice("他讀著「$line」，突然笑了。"))
        }
    }
}
