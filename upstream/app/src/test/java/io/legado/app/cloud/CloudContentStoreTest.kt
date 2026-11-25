package io.legado.app.cloud

import io.legado.app.model.cloud.CloudContentStore
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class CloudContentStoreTest {
    private val identity = CloudContentStore.Identity("configuration", "https://source.test", "https://source.test/book/1", "https://source.test/chapter/1")
    private fun fixture(block: (CloudContentStore, File) -> Unit) {
        val directory = Files.createTempDirectory("cloud-content-contract-").toFile()
        try { block(CloudContentStore(File(directory, "private")), directory) } finally { directory.deleteRecursively() }
    }
    private fun install(store: CloudContentStore, body: String = "自行編寫完整正文。第二段仍屬同一章。", source: String = identity.sourceId,
                        chapter: String = identity.chapterUrl, sha: String = CloudContentStore.sha256(body),
                        complete: Boolean = true, version: String = CloudContentStore.CONTRACT) =
        store.install(identity, body, complete, version, source, identity.bookUrl, chapter, sha)

    @Test fun originalIsPreservedWithoutBecomingCompleteAndFailuresNeverReplaceIt() = fixture { store, dir ->
        val original = File(dir, "00001-chapter.nb").apply { writeText("自行編寫的舊原文，供離線回退繼續閱讀。") }
        val bytes = original.readBytes()
        store.preserve(identity, original)
        assertNull(store.complete(identity, CloudContentStore.CONTRACT))
        assertEquals(original.readText(), store.fallback(identity))
        assertTrue(runCatching { install(store, complete = false) }.isFailure)
        assertArrayEquals(bytes, original.readBytes())
        assertEquals(original.readText(), store.fallback(identity))
    }
    @Test fun completeSameVersionIsReusableAndOtherVersionsOrConfigurationsAreNotComplete() = fixture { store, _ ->
        install(store)
        var requests = 0
        val cached = store.complete(identity, CloudContentStore.CONTRACT) ?: run { requests++; error("Unexpected fetch") }
        assertTrue(cached.contains("完整正文")); assertEquals(0, requests)
        assertNull(store.complete(identity, null))
        assertNull(store.complete(identity, "future-contract"))
        assertNull(store.complete(identity.copy(configuration = "other"), CloudContentStore.CONTRACT))
        assertEquals(setOf(identity.chapterUrl),store.completeChapterUrls(identity.configuration,identity.sourceId,identity.bookUrl,CloudContentStore.CONTRACT))
        assertTrue(store.completeChapterUrls("other",identity.sourceId,identity.bookUrl,CloudContentStore.CONTRACT).isEmpty())
    }
    @Test fun wrongSourceChapterAndBodyDigestCannotReplaceTheLastCompleteBody() = fixture { store, _ ->
        install(store)
        val before = store.complete(identity, CloudContentStore.CONTRACT)
        assertTrue(runCatching { install(store, source = "https://other.test") }.isFailure)
        assertTrue(runCatching { install(store, chapter = "https://source.test/chapter/2") }.isFailure)
        assertTrue(runCatching { install(store, sha = "0".repeat(64)) }.isFailure)
        assertTrue(runCatching { install(store, version = "old") }.isFailure)
        assertEquals(before, store.complete(identity, CloudContentStore.CONTRACT))
    }
    @Test fun damagedReceiptOrBodyIsRejectedInsteadOfReturningAnotherChapter() = fixture { store, _ ->
        install(store)
        val target = store.file(identity, true)
        val valid = target.readText()
        target.writeText(valid.replace("自行編寫完整正文", "被更改的片段"))
        assertNull(store.complete(identity, CloudContentStore.CONTRACT))
        assertTrue(store.completeChapterUrls(identity.configuration,identity.sourceId,identity.bookUrl,CloudContentStore.CONTRACT).isEmpty())
        target.writeText(valid.replace("https://source.test/chapter/1", "https://source.test/chapter/2"))
        assertNull(store.complete(identity, CloudContentStore.CONTRACT))
        target.writeText("{\"partial\":")
        assertNull(store.complete(identity, CloudContentStore.CONTRACT))
    }
    @Test fun collidingIndexFilesStayWithTheirOriginalUrlsAfterTocSeal() = fixture { store, dir ->
        val native = File(dir, "00000-same-title.nb").apply { writeText("這是舊網址原文，必須保留。") }
        val newer = identity.copy(chapterUrl = "https://source.test/chapter/new")
        store.preserve(identity, native)
        store.sealToc(identity)
        store.preserve(newer, native) // Old flat file cannot be relabelled with the new TOC URL.
        assertNull(store.fallback(newer))
        store.install(newer, "新網址的完整章節正文。", true, CloudContentStore.CONTRACT,
            newer.sourceId, newer.bookUrl, newer.chapterUrl, CloudContentStore.sha256("新網址的完整章節正文。"))
        assertEquals("這是舊網址原文，必須保留。", store.fallback(identity))
        assertEquals("這是舊網址原文，必須保留。", native.readText())
        assertEquals("新網址的完整章節正文。", store.complete(newer, CloudContentStore.CONTRACT))
    }
    @Test fun deliberateLocalEditIsSeparateFromCompleteAndRefreshKeepsItAsFallback() = fixture { store, _ ->
        install(store)
        store.saveEdit(identity, "使用者明確修改的本機正文。")
        assertEquals("使用者明確修改的本機正文。", store.edited(identity))
        assertNotEquals(store.edited(identity), store.complete(identity, CloudContentStore.CONTRACT))
        store.invalidate(identity)
        assertNull(store.edited(identity)); assertNull(store.complete(identity, CloudContentStore.CONTRACT))
        assertEquals("使用者明確修改的本機正文。", store.fallback(identity))
        assertTrue(runCatching { install(store, complete = false) }.isFailure)
        assertEquals("使用者明確修改的本機正文。", store.fallback(identity))
    }

    @Test fun clearingBodiesKeepsTheTocSealIfNativeDeletionFailsOrTheProcessStops() = fixture { store, dir ->
        val original=File(dir,"00000-collision.nb").apply { writeText("舊目錄索引的原文仍在磁碟上。") }
        store.preserve(identity,original);store.sealToc(identity);install(store)
        store.clear()
        assertTrue(store.isSealed(identity))
        assertNull(store.complete(identity,CloudContentStore.CONTRACT))
        assertNull(store.fallback(identity))
        val differentChapter=identity.copy(chapterUrl="https://source.test/chapter/reindexed")
        store.preserve(differentChapter,original)
        assertNull("A cache clear must never relabel old index files under the new TOC",store.fallback(differentChapter))
        assertEquals("舊目錄索引的原文仍在磁碟上。",original.readText())
    }

    @Test fun authRotationAndAliasChangesCannotReopenTheOldNativeIndexNamespace() = fixture { store, dir ->
        val original=File(dir,"00000-collision.nb").apply { writeText("原生舊索引原文，不屬於重排後的新章節。") }
        store.preserve(identity,original);store.sealToc(identity)
        val rotated=identity.copy(configuration="new-token",sourceId="https://source.test/alias",
            chapterUrl="https://source.test/chapter/new-index-zero")
        assertTrue(store.isSealed(rotated))
        store.preserve(rotated,original)
        assertNull(store.fallback(rotated))
        assertEquals(original.readText(),store.fallback(identity))
    }

}
