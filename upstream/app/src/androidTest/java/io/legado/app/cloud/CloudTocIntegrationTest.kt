package io.legado.app.cloud

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.Bookmark
import io.legado.app.data.entities.ReadRecord
import io.legado.app.help.book.BookHelp
import io.legado.app.model.ReadBook
import io.legado.app.model.cloud.CloudReader
import io.legado.app.model.cloud.CloudTocResult
import io.legado.app.model.cloud.CloudTocStore
import io.legado.app.model.cloud.CloudContentCache
import io.legado.app.model.cloud.CloudMigrationPolicy
import io.legado.app.model.multisource.MultiSourceManager
import io.legado.app.help.config.AppConfig
import io.legado.app.ui.book.read.page.provider.ChapterProvider
import io.legado.app.utils.GSON
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class CloudTocIntegrationTest {
    private fun CloudIntegrationFixture.proof(rows: List<BookChapter>) = CloudTocResult(rows,sources[0].bookSourceUrl,
        GSON.toJson(sources[0]),CloudReader.configurationRevision(application))
    @Test(timeout=30_000) fun restoredFrontPagesAndVipMetadataKeepTheSameChapterOffsetBookmarksAndOriginalFiles() = runBlocking {
        CloudIntegrationFixture().use { fixture ->
            val book=fixture.readingBook().apply { durChapterIndex=1;durChapterTitle="第三章";totalChapterNum=2;group=64 }
            val first=fixture.chapter(book).copy(title="第一章")
            val current=first.copy(url=book.bookUrl+"/chapter/2",title="第三章",index=1)
            val bookmark=Bookmark(time=1_700_000_001_234,bookName=book.name,bookAuthor=book.author,
                chapterIndex=0,chapterPos=11,chapterName=first.title,bookText="Owned quote",content="Owned annotation")
            val history=ReadRecord("owned-toc",book.name,12345,1_700_000_001_234)
            appDb.bookDao.insert(book);appDb.bookChapterDao.insert(first,current)
            appDb.bookmarkDao.insert(bookmark);appDb.readRecordDao.insert(history)
            val original=File(File(BookHelp.cachePath,book.getFolderName()),current.getFileName())
            original.parentFile!!.mkdirs();original.writeText(fixture.partial)
            val bytes=original.readBytes();val config=GSON.toJson(book.readConfig)
            try {
                val fresh=listOf(first.copy(url=book.bookUrl+"/preface",title="序章",index=0),first.copy(index=1),
                    first.copy(url=book.bookUrl+"/vip",title="第二章",index=2,isVip=true,isPay=false),current.copy(index=3))
                assertTrue(CloudTocStore.installIfCloud(book.copy(),fixture.proof(fresh)))
                val after=appDb.bookDao.getBook(book.bookUrl)!!
                assertEquals(3,after.durChapterIndex);assertEquals(73,after.durChapterPos);assertEquals(64L,after.group)
                assertEquals(config,GSON.toJson(after.readConfig));assertEquals(listOf(history),appDb.readRecordDao.all)
                val saved=appDb.bookmarkDao.getByBook(book.name,book.author).single()
                assertEquals(bookmark.copy(chapterIndex=1),saved)
                assertEquals(4,appDb.bookChapterDao.getChapterList(book.bookUrl).size)
                assertTrue(appDb.bookChapterDao.getChapter(book.bookUrl,2)!!.isVip)
                assertArrayEquals(bytes,original.readBytes())
                assertTrue("Installing VIP metadata must not fetch paid text",fixture.gateway.calls.isEmpty())
            } finally {
                original.delete();appDb.bookmarkDao.delete(bookmark);appDb.readRecordDao.delete(history);appDb.bookDao.delete(book)
            }
        }
    }

    @Test(timeout=30_000) fun removingABookmarkedNonCurrentChapterRejectsTheEntireTocBeforeCacheSealing() = runBlocking {
        CloudIntegrationFixture().use { fixture ->
            val book=fixture.readingBook().apply { totalChapterNum=2 }
            val current=fixture.chapter(book)
            val other=current.copy(url=book.bookUrl+"/bookmarked",title="第二章 被書籤引用",index=1)
            val bookmark=Bookmark(bookName=book.name,bookAuthor=book.author,chapterIndex=1,chapterName=other.title,chapterPos=29)
            appDb.bookDao.insert(book);appDb.bookChapterDao.insert(current,other);appDb.bookmarkDao.insert(bookmark)
            val original=File(File(BookHelp.cachePath,book.getFolderName()),other.getFileName())
            original.parentFile!!.mkdirs();original.writeText(fixture.partial)
            val beforeBook=GSON.toJson(appDb.bookDao.getBook(book.bookUrl))
            val beforeToc=GSON.toJson(appDb.bookChapterDao.getChapterList(book.bookUrl))
            val directory=File(fixture.application.filesDir,"cloud-content-v1")
            val oldPrivate=directory.listFiles().orEmpty().map { it.name }.toSet()
            try {
                assertTrue(runCatching { CloudTocStore.installIfCloud(book.copy(),fixture.proof(listOf(current))) }.isFailure)
                assertEquals(beforeBook,GSON.toJson(appDb.bookDao.getBook(book.bookUrl)))
                assertEquals(beforeToc,GSON.toJson(appDb.bookChapterDao.getChapterList(book.bookUrl)))
                assertEquals(listOf(bookmark),appDb.bookmarkDao.getByBook(book.name,book.author))
                assertEquals(fixture.partial,original.readText())
                assertEquals(oldPrivate,directory.listFiles().orEmpty().map { it.name }.toSet())
                assertFalse(ReadBook.isCloudTocUpdating)
            } finally { original.delete();appDb.bookmarkDao.delete(bookmark);appDb.bookDao.delete(book) }
        }
    }

    @Test(timeout=30_000) fun anAmbiguousCurrentTitleNeverFallsBackToTheOldNumericIndex() = runBlocking {
        CloudIntegrationFixture().use { fixture ->
            val book=fixture.readingBook().apply { durChapterTitle="正文" }
            val old=fixture.chapter(book).copy(title="正文")
            appDb.bookDao.insert(book);appDb.bookChapterDao.insert(old)
            val before=GSON.toJson(appDb.bookDao.getBook(book.bookUrl))
            try {
                val fresh=listOf(old.copy(url=old.url+"/other",index=0),old.copy(url=old.url+"/again",index=1))
                assertTrue(runCatching { CloudTocStore.installIfCloud(book.copy(),fixture.proof(fresh)) }.isFailure)
                assertEquals(before,GSON.toJson(appDb.bookDao.getBook(book.bookUrl)))
                assertEquals(listOf(old),appDb.bookChapterDao.getChapterList(book.bookUrl))
            } finally { appDb.bookDao.delete(book) }
        }
    }

    @Test(timeout=30_000) fun multipleSyntheticVolumeEntriesKeepTheirSeparateIdentities() = runBlocking {
        CloudIntegrationFixture().use { fixture ->
            val book=fixture.readingBook().apply { durChapterIndex=1;totalChapterNum=2 }
            val chapter=fixture.chapter(book).copy(index=1)
            val volume=chapter.copy(url="卷一0",title="卷一",index=0,isVolume=true)
            appDb.bookDao.insert(book);appDb.bookChapterDao.insert(volume,chapter)
            try {
                val fresh=listOf(volume.copy(),chapter.copy(url=chapter.url+"/new",title="前置章",index=1),
                    chapter.copy(index=2),volume.copy(url="卷二3",title="卷二",index=3))
                assertTrue(CloudTocStore.installIfCloud(book.copy(),fixture.proof(fresh)))
                assertEquals(2,appDb.bookDao.getBook(book.bookUrl)!!.durChapterIndex)
                assertEquals(2,appDb.bookChapterDao.getChapterList(book.bookUrl).count { it.isVolume })
            } finally { appDb.bookDao.delete(book) }
        }
    }

    @Test(timeout=30_000) fun aResponseCannotOutliveItsExactSourceRuleOrConfigurationAndMainCallsFailBeforeLocking() = runBlocking {
        CloudIntegrationFixture().use { fixture ->
            val book=fixture.readingBook();val chapter=fixture.chapter(book)
            appDb.bookDao.insert(book);appDb.bookChapterDao.insert(chapter)
            try {
                val toc=CloudReader.toc(fixture.sources[0],book.copy())
                assertEquals("reader-toc-identity-1",fixture.gateway.calls.last { it.path=="/v1/books/toc" }.body["tocInstallContract"].asString)
                fixture.sources[0].customOrder=99;appDb.bookSourceDao.insert(fixture.sources[0])
                assertTrue(runCatching { CloudTocStore.installIfCloud(book.copy(),toc) }.isFailure)
                withContext(Dispatchers.Main) {
                    assertTrue(runCatching { CloudTocStore.installIfCloud(book.copy(),toc) }.isFailure)
                }
                assertEquals(listOf(chapter),appDb.bookChapterDao.getChapterList(book.bookUrl))
                fixture.assertPrivateStateNeverSent()
            } finally { appDb.bookDao.delete(book) }
        }
    }

    @Test(timeout=30_000) fun oldBookAndBookmarkDraftsCannotRestoreOldIndexesButKeepLegitimateEdits() = runBlocking {
        CloudIntegrationFixture().use { fixture ->
            val book=fixture.readingBook();val chapter=fixture.chapter(book)
            appDb.bookDao.insert(book);appDb.bookChapterDao.insert(chapter)
            val bookmark=Bookmark(bookName=book.name,bookAuthor=book.author,chapterName=chapter.title,chapterPos=19)
            appDb.bookmarkDao.insert(bookmark)
            val bookDraft=book.copy(group=128,customCoverUrl="https://owned-cover.invalid/user.png")
            val bookmarkDraft=bookmark.copy(content="Owned note edited after TOC update")
            val newDraft=bookmark.copy(time=bookmark.time+1,content="Owned newly created draft").apply {
                referenceBookUrl=book.bookUrl;referenceSource=book.origin;referenceChapterUrl=chapter.getAbsoluteURL()
            }
            try {
                val fresh=listOf(chapter.copy(url=chapter.url+"/preface",title="前言",index=0),chapter.copy(index=1))
                CloudTocStore.installIfCloud(book.copy(),fixture.proof(fresh))
                bookDraft.save()
                appDb.bookmarkDao.insert(bookmarkDraft,newDraft)
                val saved=appDb.bookDao.getBook(book.bookUrl)!!
                assertEquals(1,saved.durChapterIndex);assertEquals(73,saved.durChapterPos)
                assertEquals(2,saved.totalChapterNum);assertEquals(128L,saved.group)
                assertEquals(bookDraft.customCoverUrl,saved.customCoverUrl)
                val marks=appDb.bookmarkDao.getByBook(book.name,book.author)
                assertEquals(2,marks.size);assertTrue(marks.all { it.chapterIndex==1 && it.chapterPos==19 })
                assertEquals(bookmarkDraft.content,marks.single { it.time==bookmark.time }.content)
            } finally { appDb.bookmarkDao.delete(bookmark,newDraft);appDb.bookDao.delete(book) }
        }
    }

    @Test(timeout=40_000) fun anActualRenderedCloudChapterKeepsItsWordsWhenTocAndCompleteBodyExpand() = runBlocking {
        CloudIntegrationFixture().use { fixture ->
            val book=fixture.readingBook().apply { totalChapterNum=1;setUseReplaceRule(false) }
            val chapter=fixture.chapter(book)
            appDb.bookDao.insert(book);appDb.bookChapterDao.insert(chapter)
            val original=File(File(BookHelp.cachePath,book.getFolderName()),chapter.getFileName())
            original.parentFile!!.mkdirs();original.writeText(fixture.partial)
            val bytes=original.readBytes();val oldRecord=AppConfig.enableReadRecord;val oldPreload=AppConfig.preDownloadNum;val oldChinese=AppConfig.chineseConverterType
            MultiSourceManager.enabled=false;AppConfig.enableReadRecord=false;AppConfig.preDownloadNum=0;AppConfig.chineseConverterType=0
            try {
                fixture.gateway.failPrimary=true
                withContext(Dispatchers.Main) { ChapterProvider.upViewSize(1080,1920);ReadBook.resetData(book) }
                ReadBook.loadContentAwait(0,resetPageOffset=false)
                val oldText=requireNotNull(ReadBook.curTextChapter).getContent()
                val position=oldText.indexOf("旅客")
                assertTrue(position>0);assertNull(CloudContentCache.complete(book,chapter))
                withContext(Dispatchers.Main) { ReadBook.durChapterPos=position;ReadBook.saveRead() }
                ReadBook.executor.submit { }.get(10,TimeUnit.SECONDS)
                val working=requireNotNull(appDb.bookDao.getBook(book.bookUrl))
                val fresh=listOf(chapter.copy(url=chapter.url+"/preface",title="前言",index=0),chapter.copy(index=1))
                CloudTocStore.installIfCloud(working,fixture.proof(fresh))
                withContext(Dispatchers.Main) { ReadBook.onChapterListUpdated(working,loadContent=false) }
                assertEquals(1,ReadBook.durChapterIndex);assertEquals(position,ReadBook.durChapterPos)
                fixture.gateway.failPrimary=false
                fixture.gateway.contentReplyOverrides=mapOf("content" to fixture.complete,"bodySha256" to CloudMigrationPolicy.sha256(fixture.complete))
                ReadBook.loadContentAwait(1,resetPageOffset=false)
                ReadBook.executor.submit { }.get(10,TimeUnit.SECONDS)
                val full=requireNotNull(ReadBook.curTextChapter).getContent()
                assertTrue(full.contains(fixture.missing));assertTrue(ReadBook.durChapterPos>position)
                assertTrue(full.substring(ReadBook.durChapterPos).startsWith("旅客"))
                assertEquals(ReadBook.durChapterPos,appDb.bookDao.getBook(book.bookUrl)!!.durChapterPos)
                assertEquals(1,appDb.bookDao.getBook(book.bookUrl)!!.durChapterIndex)
                assertArrayEquals(bytes,original.readBytes())
                assertEquals(fixture.complete,CloudContentCache.complete(working,fresh[1]))
                fixture.assertPrivateStateNeverSent()
            } finally {
                withContext(Dispatchers.Main) { ReadBook.clearTextChapter();ReadBook.book=null;ReadBook.bookSource=null }
                AppConfig.enableReadRecord=oldRecord;AppConfig.preDownloadNum=oldPreload;AppConfig.chineseConverterType=oldChinese
                original.delete();appDb.bookDao.delete(book)
            }
        }
    }
}
