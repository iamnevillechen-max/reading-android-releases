package io.legado.app.cloud

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.legado.app.data.appDb
import io.legado.app.help.book.BookHelp
import io.legado.app.model.cloud.CloudContentCache
import io.legado.app.model.cloud.CloudReader
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Real gateway requests and real private cache writes; all chapter text is authored by this test. */
@RunWith(AndroidJUnit4::class)
class CloudContentReceiptIntegrationTest {
    @Test(timeout = 30_000) fun anOldFlatCacheRefreshesOnceAndFailureKeepsTheExactOriginalAndFallback() = runBlocking {
        CloudIntegrationFixture().use { fixture ->
            val book=fixture.readingBook(); val chapter=fixture.chapter(book)
            appDb.bookDao.insert(book);appDb.bookChapterDao.insert(chapter)
            val original=File(File(BookHelp.cachePath,book.getFolderName()),chapter.getFileName())
            original.parentFile!!.mkdirs();original.writeText(fixture.before)
            val originalBytes=original.readBytes()
            try {
                assertNull(BookHelp.getContent(book,chapter,allowMultiSource=false))
                assertFalse(BookHelp.hasContent(book,chapter))
                assertEquals(fixture.partial,CloudReader.content(fixture.sources[0],book,chapter,null,true))
                assertArrayEquals(originalBytes,original.readBytes())
                assertTrue(BookHelp.hasContent(book,chapter))
                val count=fixture.gateway.calls.size
                assertEquals(fixture.partial,CloudReader.content(fixture.sources[0],book,chapter,null,true))
                assertEquals("Same-contract receipt must not repeat HTTP",count,fixture.gateway.calls.size)
                BookHelp.delContent(book,chapter)
                fixture.gateway.failPrimary=true
                assertTrue(runCatching { CloudReader.content(fixture.sources[0],book,chapter,null,true) }.isFailure)
                assertArrayEquals(originalBytes,original.readBytes())
                assertFalse(BookHelp.hasContent(book,chapter))
                assertEquals(fixture.partial,CloudContentCache.fallback(book,chapter))
            } finally { original.delete();appDb.bookDao.delete(book) }
        }
    }

    @Test(timeout = 30_000) fun forgedBodyOrChapterEvidenceAndANewContractNeverBecomeComplete() = runBlocking {
        CloudIntegrationFixture().use { fixture ->
            val book=fixture.readingBook();val chapter=fixture.chapter(book)
            appDb.bookDao.insert(book);appDb.bookChapterDao.insert(chapter)
            try {
                for (change in listOf(mapOf("bodySha256" to "0".repeat(64)),mapOf("chapterUrl" to chapter.url+"/other"),
                    mapOf("sourceId" to "https://other.invalid"),mapOf("complete" to false))) {
                    fixture.gateway.contentReplyOverrides=change
                    assertTrue(runCatching { CloudReader.content(fixture.sources[0],book,chapter,null,true) }.isFailure)
                    assertNull(CloudContentCache.complete(book,chapter))
                }
                fixture.gateway.contentReplyOverrides=emptyMap()
                CloudReader.content(fixture.sources[0],book,chapter,null,true)
                fixture.gateway.contentContractVersion="future-contract"
                CloudReader.request("/v1/status")
                assertNull(CloudContentCache.complete(book,chapter))
                val count=fixture.gateway.calls.count{it.path=="/v1/books/content"}
                assertTrue(runCatching { CloudReader.content(fixture.sources[0],book,chapter,null,true) }.isFailure)
                assertEquals(count,fixture.gateway.calls.count{it.path=="/v1/books/content"})
                assertEquals(fixture.partial,CloudContentCache.fallback(book,chapter))
            } finally { appDb.bookDao.delete(book) }
        }
    }
    @Test(timeout = 30_000) fun aRelativeOldChapterKeepsItsFallbackWhenTheCompleteTocUsesAbsoluteUrls() = runBlocking {
        CloudIntegrationFixture().use { fixture ->
            val book=fixture.readingBook()
            val absolute=fixture.chapter(book)
            val old=absolute.copy(url=java.net.URI(absolute.url).rawPath)
            val fresh=absolute.copy(index=1)
            appDb.bookDao.insert(book);appDb.bookChapterDao.insert(old)
            val original=File(File(BookHelp.cachePath,book.getFolderName()),old.getFileName())
            original.parentFile!!.mkdirs();original.writeText(fixture.before)
            try {
                assertEquals(fresh.getAbsoluteURL(),old.getAbsoluteURL())
                CloudContentCache.preserveToc(book,listOf(old),listOf(fresh))
                assertEquals(fixture.before,CloudContentCache.fallback(book,fresh))
                assertNull(CloudContentCache.complete(book,fresh))
                assertEquals(fixture.before,original.readText())
            } finally { original.delete();appDb.bookDao.delete(book) }
        }
    }

    @Test(timeout=30_000) fun deliberateOfflineEditsAreAvailableWithoutRepeatedPreloadRequests() = runBlocking {
        CloudIntegrationFixture().use { fixture ->
            val book=fixture.readingBook();val chapter=fixture.chapter(book)
            appDb.bookDao.insert(book);appDb.bookChapterDao.insert(chapter)
            val native=File(File(BookHelp.cachePath,book.getFolderName()),chapter.getFileName())
            try {
                fixture.gateway.offline=true
                BookHelp.saveText(book,chapter,fixture.complete)
                assertNull("A local edit does not impersonate a server completeness receipt",CloudContentCache.complete(book,chapter))
                assertTrue("Preloading must recognize an explicit local edit as available",BookHelp.hasContent(book,chapter))
                assertTrue("The downloader can skip a fully available edited text chapter",BookHelp.hasImageContent(book,chapter))
                assertEquals(fixture.complete,BookHelp.getContent(book,chapter,allowMultiSource=false))
                val count=fixture.gateway.calls.size
                repeat(2) { assertEquals(fixture.complete,CloudReader.content(fixture.sources[0],book,chapter,null,true)) }
                assertEquals("Available local edits must not repeat HTTP requests",count,fixture.gateway.calls.size)
            } finally { native.delete();appDb.bookDao.delete(book) }
        }
    }

}
