package io.legado.app.cloud

import io.legado.app.model.cloud.CloudContentStore
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class LocalCacheRetirementTest {
    @Test fun aSealedOldNumericFileCannotBecomeAnotherUrlAfterFullRulesAreRestored() {
        val root = Files.createTempDirectory("owned-local-cache-").toFile()
        try {
            val store = CloudContentStore(root.resolve("records"))
            val previous = CloudContentStore.Identity("owned-retained-namespace", "owned-source", "owned-book", "owned-chapter-a")
            val shifted = previous.copy(chapterUrl = "owned-chapter-b")
            val native = root.resolve("old-index.nb").apply { writeText("Owned old chapter A") }
            store.preserve(previous, native)
            store.sealToc(previous)
            native.writeText("Owned unrelated numeric-index collision")
            store.preserve(shifted, native)
            assertEquals("Owned old chapter A", store.fallback(previous))
            assertNull(store.fallback(shifted))
            store.saveEdit(shifted, "Owned chapter B parsed locally")
            assertEquals("Owned chapter B parsed locally", store.fallback(shifted))
            assertEquals("Owned old chapter A", store.fallback(previous))
            assertEquals("Owned unrelated numeric-index collision", native.readText())
        } finally { root.deleteRecursively() }
    }

    @Test fun anExistingVerifiedBodyStaysReadableOfflineAndANewLocalBodyKeepsItsUrlIdentity() {
        val root = Files.createTempDirectory("owned-retired-body-").toFile()
        try {
            val store = CloudContentStore(root)
            val identity = CloudContentStore.Identity("owned-retained-namespace", "owned-source", "owned-book", "owned-chapter")
            val body = "Owned cached body retained when the service is removed"
            store.install(identity, body, true, CloudContentStore.CONTRACT, identity.sourceId,
                identity.bookUrl, identity.chapterUrl, CloudContentStore.sha256(body))
            assertNull(store.complete(identity, null))
            assertEquals(body, store.fallback(identity))
            assertNull(store.fallback(identity.copy(sourceId = "another-source")))
            store.saveEdit(identity, "Owned replacement fetched by the native rules")
            assertEquals("Owned replacement fetched by the native rules", store.fallback(identity))
            assertEquals(body, store.complete(identity, CloudContentStore.CONTRACT))
        } finally { root.deleteRecursively() }
    }
    @Test fun anExplicitRefreshFetchesLocallyWhileItsOldBodyRemainsAvailableOnFailure() {
        val root = Files.createTempDirectory("owned-local-refresh-").toFile()
        try {
            val store = CloudContentStore(root)
            val identity = CloudContentStore.Identity("owned-retained-namespace", "owned-source", "owned-book", "owned-chapter")
            store.saveEdit(identity, "Owned previous readable chapter")
            store.invalidate(identity)
            assertNull(store.locallyReadable(identity))
            assertEquals("Owned previous readable chapter", store.fallback(identity))
            // The marker survives a process restart and cannot suppress the requested native fetch.
            assertNull(CloudContentStore(root).locallyReadable(identity))
            store.saveEdit(identity, "Owned freshly parsed local chapter")
            assertEquals("Owned freshly parsed local chapter", store.locallyReadable(identity))
            assertEquals("Owned freshly parsed local chapter", store.fallback(identity))
        } finally { root.deleteRecursively() }
    }

}
