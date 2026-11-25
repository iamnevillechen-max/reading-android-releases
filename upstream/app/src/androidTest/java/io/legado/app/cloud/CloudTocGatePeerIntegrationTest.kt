package io.legado.app.cloud

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.legado.app.data.appDb
import io.legado.app.model.ReadBook
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Independent peer cases for real pending-gate state. No external site or device data. */
@RunWith(AndroidJUnit4::class)
class CloudTocGatePeerIntegrationTest {
    @Test(timeout=30_000) fun sameUrlAliasSourceChangeCannotCrossThePendingTocGate() = runBlocking {
        CloudIntegrationFixture().use { fixture ->
            val book=fixture.readingBook();appDb.bookDao.insert(book)
            val snapshot=withContext(Dispatchers.Main) {
                ReadBook.book=book;ReadBook.durChapterIndex=0;ReadBook.durChapterPos=73
                ReadBook.beginCloudToc(book)
            }
            try {
                withContext(Dispatchers.Main) {
                    val alias=book.copy(origin=fixture.sources[1].bookSourceUrl)
                    assertTrue("Same-URL source changes must be explicitly rejected while its old-source TOC is pending",
                        runCatching { ReadBook.resetData(alias) }.isFailure)
                    assertSame(book,ReadBook.book)
                }
            } finally {
                withContext(Dispatchers.Main) { ReadBook.book=null;ReadBook.abortCloudToc(snapshot);ReadBook.curTextChapter=null }
                appDb.bookDao.delete(book)
            }
        }
    }

    @Test(timeout=30_000) fun delayedChapterListCallbacksCannotReplaceTheActiveBookDuringTheGate() = runBlocking {
        CloudIntegrationFixture().use { fixture ->
            val book=fixture.readingBook();appDb.bookDao.insert(book)
            val snapshot=withContext(Dispatchers.Main) {
                ReadBook.book=book;ReadBook.durChapterIndex=0;ReadBook.durChapterPos=73
                ReadBook.beginCloudToc(book)
            }
            try {
                withContext(Dispatchers.Main) {
                    val stale=book.copy(totalChapterNum=999).apply { cloudTocInstalled=true }
                    ReadBook.onChapterListUpdated(stale,loadContent=false)
                    assertSame("A stale callback must be rejected or deferred until the install boundary finishes",book,ReadBook.book)
                }
            } finally {
                withContext(Dispatchers.Main) { ReadBook.book=null;ReadBook.abortCloudToc(snapshot);ReadBook.curTextChapter=null }
                appDb.bookDao.delete(book)
            }
        }
    }

    @Test(timeout=30_000) fun theLatestSameChapterOffsetIsFlushedAfterTheBackgroundTransaction() = runBlocking {
        CloudIntegrationFixture().use { fixture ->
            val book=fixture.readingBook();val chapter=fixture.chapter(book)
            appDb.bookDao.insert(book);appDb.bookChapterDao.insert(chapter)
            val snapshot=withContext(Dispatchers.Main) {
                ReadBook.book=book;ReadBook.durChapterIndex=0;ReadBook.durChapterPos=73
                ReadBook.beginCloudToc(book)
            }
            try {
                val installed=book.copy(durChapterIndex=1,totalChapterNum=2)
                appDb.bookDao.updateForCloudToc(installed)
                withContext(Dispatchers.Main) {
                    ReadBook.durChapterPos=101
                    ReadBook.finishCloudToc(snapshot,installed,chapter.copy(index=1),mapOf(0 to 1))
                    assertEquals(1,ReadBook.durChapterIndex);assertEquals(101,ReadBook.durChapterPos)
                }
                assertEquals(101,appDb.bookDao.getBook(book.bookUrl)!!.durChapterPos)
            } finally {
                withContext(Dispatchers.Main) { ReadBook.book=null;ReadBook.abortCloudToc(snapshot);ReadBook.curTextChapter=null }
                appDb.bookDao.delete(book)
            }
        }
    }
    @Test(timeout=30_000) fun aDifferentBooksBackgroundTocDoesNotInvalidateTheForegroundLoaderEpoch() = runBlocking {
        CloudIntegrationFixture().use { fixture ->
            val foreground=fixture.readingBook()
            val background=foreground.copy(bookUrl=foreground.bookUrl+"/other",name=foreground.name+" other")
            appDb.bookDao.insert(foreground);appDb.bookDao.insert(background)
            val epochField=ReadBook::class.java.getDeclaredField("cloudTocEpoch").apply { isAccessible=true }
            var epoch=0L
            val snapshot=withContext(Dispatchers.Main) {
                ReadBook.book=foreground;ReadBook.durChapterIndex=0;ReadBook.durChapterPos=73
                epoch=epochField.getLong(ReadBook)
                ReadBook.beginCloudToc(background)
            }
            try {
                val installed=background.copy(totalChapterNum=1)
                appDb.bookDao.updateForCloudToc(installed)
                withContext(Dispatchers.Main) {
                    assertEquals("Unrelated background TOCs must not reject an in-flight foreground layout",epoch,epochField.getLong(ReadBook))
                    ReadBook.finishCloudToc(snapshot,installed,fixture.chapter(background),emptyMap())
                    assertSame(foreground,ReadBook.book)
                    assertEquals(epoch,epochField.getLong(ReadBook))
                }
            } finally {
                withContext(Dispatchers.Main) { ReadBook.book=null;ReadBook.abortCloudToc(snapshot);ReadBook.curTextChapter=null }
                appDb.bookDao.delete(foreground);appDb.bookDao.delete(background)
            }
        }
    }

}
