package io.legado.app.cloud

import io.legado.app.model.cloud.CloudTocPolicy as Policy
import org.junit.Assert.*
import org.junit.Test

class CloudTocPolicyTest {
    private fun toc(vararg values: Pair<String, String>) = values.mapIndexed { i, p -> Policy.Chapter(p.first, p.second, i) }
    @Test fun insertedFrontPageAndVipKeepTheExactCurrentChapterEvenAtIndexZero() {
        val old = toc("/free-a" to "第一章", "/free-b" to "第三章")
        val fresh = toc("/front" to "前言", "/free-a" to "第一章", "/vip" to "第二章", "/free-b" to "第三章")
        assertEquals(1, Policy.current(0, "第一章", old, fresh))
        assertEquals(3, Policy.current(1, "第三章", old, fresh))
        assertEquals(mapOf(0 to 1, 1 to 3), Policy.mapping(old, fresh))
    }
    @Test fun changedUrlMayUseOnlyAUniqueExactTitle() {
        assertEquals(1, Policy.current(0, "章題", toc("/old" to "章題"), toc("/front" to "序", "/new" to "章題")))
        assertTrue(runCatching { Policy.current(0, "正文", toc("/old" to "正文"), toc("/a" to "正文", "/b" to "正文")) }.isFailure)
        assertTrue(runCatching { Policy.current(0, "第三章 A", toc("/old" to "第三章 A"), toc("/new" to "第三章 B")) }.isFailure)
    }
    @Test fun missingCurrentIdentityNeverFallsBackToNumericIndexOrAChapterNumber() {
        assertTrue(runCatching { Policy.current(1, "第二章", toc("/a" to "第一章", "/gone" to "第二章"), toc("/new" to "別書章節")) }.isFailure)
        assertTrue(runCatching { Policy.current(1, "", emptyList(), toc("/new" to "第二章")) }.isFailure)
        assertEquals(0, Policy.current(0, "", emptyList(), toc("/a" to "第一章")))
    }
    @Test fun duplicateOldIndexesOrDuplicateCanonicalUrlsNeverProveAChapter() {
        val fresh=toc("/a" to "第一章", "/b" to "第二章")
        assertTrue(runCatching { Policy.mapping(listOf(Policy.Chapter("/a","第一章",0),Policy.Chapter("/b","第二章",0)),fresh) }.isFailure)
        assertTrue(runCatching { Policy.mapping(toc("/a" to "第一章","/a" to "第一章"),fresh) }.isFailure)
    }
}
