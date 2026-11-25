package io.legado.app.cloud

import io.legado.app.model.cloud.CloudTocPolicy as Policy
import org.junit.Assert.*
import org.junit.Test

class CloudTocPeerPolicyTest {
    @Test fun anAmbiguousStoredIndexOrNonSequentialNewIndexNeverProducesAnIdentityMapping() {
        val duplicateOld=listOf(Policy.Chapter("/a","甲",0),Policy.Chapter("/b","乙",0))
        val fresh=listOf(Policy.Chapter("/a","甲",0),Policy.Chapter("/b","乙",1))
        assertTrue(runCatching { Policy.current(0,"甲",duplicateOld,fresh) }.isFailure)
        assertTrue(runCatching { Policy.mapping(fresh,listOf(Policy.Chapter("/a","甲",1))) }.isFailure)
    }
    @Test fun multipleNativeVolumeIdentitiesStayDistinctAroundInsertedPaidEntries() {
        val old=listOf(Policy.Chapter("volume:第一卷0","第一卷",0),Policy.Chapter("/free","正文",1))
        val fresh=listOf(Policy.Chapter("volume:第一卷0","第一卷",0),Policy.Chapter("/vip","付費章",1),
            Policy.Chapter("volume:第二卷2","第二卷",2),Policy.Chapter("/free","正文",3))
        assertEquals(mapOf(0 to 0,1 to 3),Policy.mapping(old,fresh))
        assertEquals(3,Policy.current(1,"正文",old,fresh))
    }
    @Test fun aSharedChapterNumberCannotResolveTwoDifferentExactTitlesOrUrls() {
        val old=listOf(Policy.Chapter("/gone","第十章 正文",0))
        val fresh=listOf(Policy.Chapter("/part-one","第十章 正文",0),Policy.Chapter("/part-two","第十章 正文",1))
        assertTrue(runCatching { Policy.current(0,"第十章 正文",old,fresh) }.isFailure)
    }
}
