package io.legado.app.cloud

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import fi.iki.elonen.NanoHTTPD
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.ReadRecord
import io.legado.app.data.entities.RssArticle
import io.legado.app.data.entities.SearchBook
import io.legado.app.help.book.BookHelp
import io.legado.app.help.source.VerifiedSourceBootstrap
import io.legado.app.model.ReadBook
import io.legado.app.model.cloud.CloudContentCache
import io.legado.app.model.cloud.CloudCatalogue
import io.legado.app.model.cloud.CloudMigrationPolicy
import io.legado.app.model.cloud.CloudReader
import io.legado.app.model.multisource.MultiSourceManager
import io.legado.app.model.webBook.WebBook
import io.legado.app.utils.GSON
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/** Native reading and merger consume server text; no external source or rule executes. */
@RunWith(AndroidJUnit4::class)
class CloudReaderIntegrationTest {
    @Test(timeout = 30_000)
    fun serverCandidatesFillTheActualReadingPipelineWithoutUploadingOrMovingLocalProgress() = runBlocking {
        CloudIntegrationFixture().use { fixture ->
            val book = fixture.readingBook()
            val chapter = fixture.chapter(book)
            val history = ReadRecord("owned-cloud-device", book.name, 987_654L, 1_700_000_000_123L)
            appDb.bookDao.insert(book); appDb.bookChapterDao.insert(chapter); appDb.readRecordDao.insert(history)
            val before = appDb.bookDao.getBook(book.bookUrl)!!.copy()
            try {
                val alternate = WebBook.searchBookAwait(fixture.sources[1], book.name).single().toBook()
                WebBook.getBookInfoAwait(fixture.sources[1], alternate, false)
                val alternateToc = WebBook.getChapterListAwait(fixture.sources[1], alternate).getOrThrow()
                assertEquals(fixture.complete, WebBook.getContentAwait(fixture.sources[1], alternate, alternateToc.single(), needSave = false))
                assertFalse("Candidate fetches must not create original book cache files", BookHelp.hasContent(alternate, alternateToc.single()))

                val displayed = WebBook.getContentAwait(fixture.sources[0], book, chapter)
                assertTrue("The production multi-source merger must insert the confirmed omitted paragraph", displayed.contains(fixture.missing))
                assertEquals(fixture.partial, BookHelp.getContent(book, chapter, allowMultiSource = false))
                assertEquals(displayed, BookHelp.getContent(book, chapter))
                assertTrue(fixture.gateway.calls.any { it.path == "/v1/books/search" && it.body["sourceId"].asString == fixture.sources[1].bookSourceUrl })
                assertTrue(fixture.gateway.calls.any { it.path == "/v1/books/toc" })
                assertTrue(fixture.gateway.calls.any { it.path == "/v1/books/content" && it.body["sourceId"].asString == fixture.sources[1].bookSourceUrl })
                val count = fixture.gateway.calls.size
                assertEquals(displayed, MultiSourceManager.enrich(fixture.sources[0],book,chapter,fixture.partial))
                assertEquals("A completed derived cache must not repeat cloud requests",count,fixture.gateway.calls.size)
                val after = appDb.bookDao.getBook(book.bookUrl)!!
                assertEquals(before.durChapterIndex,after.durChapterIndex); assertEquals(before.durChapterPos,after.durChapterPos)
                assertEquals(before.durChapterTime,after.durChapterTime); assertEquals(before.durChapterTitle,after.durChapterTitle)
                assertEquals(before.readConfig,after.readConfig); assertEquals(listOf(history),appDb.readRecordDao.all)
                fixture.assertPrivateStateNeverSent()
            } finally {
                appDb.readRecordDao.delete(history); BookHelp.delContent(book,chapter); appDb.bookDao.delete(book)
            }
        }
    }

    @Test(timeout = 30_000)
    fun aFailedRemotePrimaryAutomaticallyRescuesAndKeepsReadableOfflineContentSeparate() = runBlocking {
        CloudIntegrationFixture().use { fixture ->
            fixture.gateway.failPrimary = true
            val book = fixture.readingBook(); val chapter=fixture.chapter(book)
            appDb.bookDao.insert(book); appDb.bookChapterDao.insert(chapter)
            try {
                val rescued=WebBook.getContentAwait(fixture.sources[0],book,chapter)
                assertEquals(fixture.complete,rescued)
                assertFalse("A rescue is not an original-source file",BookHelp.hasContent(book,chapter))
                assertNull(BookHelp.getContent(book,chapter,allowMultiSource=false))
                val count=fixture.gateway.calls.size
                fixture.gateway.offline=true
                assertNull("Old derived results cannot bypass the cloud chapter refresh gate",BookHelp.getContent(book,chapter))
                assertEquals(rescued,WebBook.getContentAwait(fixture.sources[0],book,chapter))
                assertTrue(fixture.gateway.calls.size >= count)
                assertEquals(73,appDb.bookDao.getBook(book.bookUrl)!!.durChapterPos)
                fixture.assertPrivateStateNeverSent()
            } finally { BookHelp.delContent(book,chapter); appDb.bookDao.delete(book) }
        }
    }
}

/** Shared self-written HTTP contract. Only this task's empty emulator may use it. */
internal class CloudIntegrationFixture(private val sameBook: Boolean = true, private val includeRss: Boolean = false) : AutoCloseable {
    val application=ApplicationProvider.getApplicationContext<Application>()
    val token=UUID.randomUUID().toString()
    val before="夜色降臨之後，林舟沿著古老的長廊慢慢前進，窗外傳來有節奏的雨聲，石牆上的燈火照亮前方陌生的道路。"
    val missing="他在門後找到一封封存多年的信，信紙上記錄著村莊真正的名字，以及只有守門人才知道的秘密。"
    val after="走過最後一道拱門時，遠處的鐘聲忽然響起，所有人停下腳步仰望高塔，等待那位遲來的旅客平安歸來。"
    val partial="$before\n\n$after"
    val complete="$before\n\n$missing\n\n$after"
    val title="Owned cloud reading $token"
    val author="Owned cloud fixture author"
    val feedId="https://owned-cloud-feed.invalid/$token/rss"
    val articleTitle="Owned cloud article $token"
    val sources=(0..1).map { index -> BookSource(
        bookSourceUrl="https://owned-cloud-${if(index==0) "primary" else "alternate"}.invalid/$token/source",
        bookSourceName="Owned cloud source $index",enabled=true,enabledExplore=true,customOrder=index,
        searchUrl=CloudMigrationPolicy.STUB,exploreUrl="全部::reader-cloud://category/all",
        // This deliberately unsupported local program must never execute on the client.
        jsLib="throw new Error('Owned local parsing must not execute');") }
    val gateway=Gateway()
    private val preferences=application.getSharedPreferences("reader_cloud_v1",Context.MODE_PRIVATE)
    private val bootstrap=application.getSharedPreferences(VerifiedSourceBootstrap.PREFS_NAME,Context.MODE_PRIVATE)
    private val originalPreferences=preferences.all.toMap()
    private val originalBootstrap=bootstrap.all.toMap()
    private val enabled=MultiSourceManager.enabled
    private val limit=MultiSourceManager.candidateLimit
    private val directory=File(application.filesDir,"multisource-v1")
    private val oldFiles=directory.listFiles().orEmpty().mapTo(hashSetOf()){it.name}
    private val contentDirectory=File(application.filesDir,"cloud-content-v1")
    private val oldContentFiles=contentDirectory.listFiles().orEmpty().mapTo(hashSetOf()){it.name}

    init {
        check(Build.HARDWARE in setOf("ranchu","goldfish") && application.packageName=="io.multiread.reader.debug")
        check(appDb.bookSourceDao.allCount()==0 && appDb.bookDao.allBookCount==0 && appDb.rssSourceDao.size==0 &&
            appDb.readRecordDao.all.isEmpty() && ReadBook.book==null && !CloudReader.isConfigured(application)) {
            "Cloud integration fixtures require the dedicated empty emulator"
        }
        bootstrap.edit().putInt(VerifiedSourceBootstrap.VERSION_KEY,VerifiedSourceBootstrap.PACK_VERSION).commit()
        gateway.start(NanoHTTPD.SOCKET_READ_TIMEOUT,false)
        CloudReader.configure(application,"http://127.0.0.1:${gateway.listeningPort}","owned-fixed-contract-token")
        preferences.edit().putBoolean("enabled",true).commit()
        MultiSourceManager.enabled=true; MultiSourceManager.candidateLimit=1
        if(sameBook) appDb.bookSourceDao.insert(*sources.toTypedArray())
        CloudCatalogue.clear()
    }
    fun bookName(index:Int)=if(sameBook) title else "Owned ${if(index==0) "urban" else "fantasy"} book $token"
    fun bookUrl(index:Int)="https://owned-cloud-${if(index==0) "primary" else "alternate"}.invalid/$token/book"
    fun readingBook()=Book(bookUrl=bookUrl(0),tocUrl=bookUrl(0)+"/toc",origin=sources[0].bookSourceUrl,
        originName=sources[0].bookSourceName,name=title,author=author,canUpdate=false,durChapterIndex=0,durChapterPos=73,
        durChapterTitle="第一章 迴廊",durChapterTime=1_700_000_001_000L,
        readConfig=Book.ReadConfig(multiSourcePositionAnchor="owned-local-position-anchor"))
    fun chapter(book:Book)=BookChapter(url=book.bookUrl+"/chapter/0",title="第一章 迴廊",bookUrl=book.bookUrl,baseUrl=book.tocUrl,index=0)
    fun assertPrivateStateNeverSent() {
        gateway.calls.forEach { call ->
            call.body["book"]?.takeIf{it.isJsonObject}?.asJsonObject?.let { payload ->
                for(key in listOf("durChapterIndex","durChapterPos","durChapterTitle","durChapterTime","readConfig","readRecord","bookGroup","customTag"))
                    assertFalse("Device reading state must not enter the cloud book payload: $key",payload.has(key))
                assertFalse(payload.toString().contains("owned-local-position-anchor"))
            }
        }
    }
    override fun close() {
        gateway.stop(); CloudCatalogue.clear()
        MultiSourceManager.clearCandidateHistory(readingBook())
        sources.forEach { source ->
            appDb.searchBookDao.clear(bookName(if(source==sources[0])0 else 1),author)
            appDb.bookSourceDao.delete(source.bookSourceUrl)
        }
        appDb.rssArticleDao.delete(feedId);appDb.rssSourceDao.delete(feedId)
        directory.listFiles().orEmpty().filter{it.isFile && it.name !in oldFiles}.forEach{it.delete()}
        contentDirectory.listFiles().orEmpty().filter{it.isFile && it.name !in oldContentFiles}.forEach{it.delete()}
        restoreCloudPreferences(preferences,originalPreferences);restoreCloudPreferences(bootstrap,originalBootstrap)
        MultiSourceManager.enabled=enabled;MultiSourceManager.candidateLimit=limit
    }
    data class Call(val path:String,val body:JsonObject,val query:Map<String,String>)
    inner class Gateway : NanoHTTPD(0) {
        val calls=CopyOnWriteArrayList<Call>()
        @Volatile var failPrimary=false
        @Volatile var offline=false
        @Volatile var listNameOverride:String?=null
        @Volatile var listAuthorOverride:String?=null
        @Volatile var infoMetadata:Map<String,String> = emptyMap()
        @Volatile var contentReplyOverrides:Map<String,Any> = emptyMap()
        @Volatile var contentContractVersion:String = CloudContentCache.CONTRACT
        @Volatile var statusEntered:java.util.concurrent.CountDownLatch?=null
        @Volatile var statusRelease:java.util.concurrent.CountDownLatch?=null
        private val sha="a".repeat(64)
        override fun serve(session:IHTTPSession):Response {
            if(session.headers["authorization"]!="Bearer owned-fixed-contract-token")return newFixedLengthResponse(Response.Status.FORBIDDEN,"text/plain","Owned denied")
            if(offline)return newFixedLengthResponse(Response.Status.SERVICE_UNAVAILABLE,"text/plain","Owned offline")
            val files=hashMapOf<String,String>()
            if(session.method==Method.POST)session.parseBody(files)
            val body=files["postData"]?.let{JsonParser.parseString(it).asJsonObject}?:JsonObject()
            val query=session.parameters.mapValues{it.value.firstOrNull().orEmpty()}
            calls.add(Call(session.uri,body,query))
            val index=sources.indexOfFirst{it.bookSourceUrl==body["sourceId"]?.asString}.coerceAtLeast(0)
            fun response(data:Any):Response=newFixedLengthResponse(Response.Status.OK,"application/json; charset=utf-8",GSON.toJson(mapOf("data" to data)))
            fun row(i:Int)=SearchBook(bookUrl=bookUrl(i),origin=sources[i].bookSourceUrl,originName=sources[i].bookSourceName,
                name=listNameOverride?:bookName(i),author=listAuthorOverride?:author,tocUrl=bookUrl(i)+"/toc")
            fun summary(i:Int)=mapOf("id" to sources[i].bookSourceUrl,"name" to sources[i].bookSourceName,"sha256" to sha,
                "stored" to true,"supported" to true,"status" to "healthy","enabled" to true,"enabledExplore" to true)
            fun feedSummary()=mapOf("id" to feedId,"name" to "Owned cloud feed","sha256" to sha,
                "stored" to true,"supported" to true,"status" to "healthy","enabled" to true,"enabledExplore" to true)
            return when(session.uri) {
                "/v1/status"->{
                    val announced=contentContractVersion
                    statusEntered?.countDown();statusRelease?.await(10,java.util.concurrent.TimeUnit.SECONDS)
                    response(mapOf("schemaVersion" to 1,"features" to mapOf("contentContractVersion" to announced)))
                }
                "/v1/books/search","/v1/books/explore"->response(mapOf("books" to if((body["page"]?.asInt?:1)>1) emptyList<SearchBook>() else listOf(row(index))))
                "/v1/books/info"->response(mapOf("book" to mapOf("bookUrl" to bookUrl(index),"tocUrl" to bookUrl(index)+"/toc",
                    "name" to bookName(index),"author" to author,"durChapterIndex" to 99,"durChapterPos" to 99999,
                    "readConfig" to mapOf("multiSourcePositionAnchor" to "owned-forbidden-server-anchor")).plus(infoMetadata)))
                "/v1/books/toc"->response(mapOf("complete" to true,"contractVersion" to CloudContentCache.CONTRACT,
                    "sourceId" to sources[index].bookSourceUrl,"bookUrl" to bookUrl(index),"book" to mapOf("variable" to "{\"ownedServerContext\":\"verified\"}"),
                    "chapters" to listOf(BookChapter(url=bookUrl(index)+"/chapter/0",title="第一章 迴廊",bookUrl=bookUrl(index),baseUrl=bookUrl(index)+"/toc",index=123))))
                "/v1/books/content"->if(index==0 && failPrimary)newFixedLengthResponse(Response.Status.INTERNAL_ERROR,"text/plain","Owned failed original")
                    else {
                        val text=if(index==0)partial else complete
                        val digest=java.security.MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
                            .joinToString(""){"%02x".format(it)}
                        response(mapOf("content" to text,"complete" to true,"contractVersion" to CloudContentCache.CONTRACT,
                            "sourceId" to body["sourceId"].asString,"bookUrl" to body["book"].asJsonObject["bookUrl"].asString,
                            "chapterUrl" to body["chapter"].asJsonObject["url"].asString,"bodySha256" to digest).plus(contentReplyOverrides))
                    }
                "/v1/sources"->if(query["kind"]=="rss")response(mapOf("sources" to if(includeRss)listOf(feedSummary())else emptyList<Any>(),"nextCursor" to null))
                    else { val i=if(query["cursor"]=="second")1 else 0;response(mapOf("sources" to listOf(summary(i)),"nextCursor" to if(i==0)"second" else null)) }
                "/v1/source-receipt"->response(if(query["kind"]=="rss")feedSummary()else summary(sources.indexOfFirst{it.bookSourceUrl==query["id"]}.coerceAtLeast(0)))
                "/v1/rss/articles"->response(mapOf("articles" to if((body["page"]?.asInt?:1)==1)listOf(RssArticle(origin=feedId,link=feedId+"/article/1",title=articleTitle,description=complete))else emptyList<RssArticle>(),
                    "nextUrl" to if((body["page"]?.asInt?:1)==1)"reader-cloud://rss-next/2" else null))
                "/v1/rss/content"->response(mapOf("content" to complete))
                "/v1/catalogue"->{
                    val i=if(query["cursor"]=="second")1 else 0
                    if(query["kind"]=="rss")response(mapOf("contentContractVersion" to contentContractVersion,"revision" to token,"checkedSources" to if(includeRss)1 else 0,"totalSources" to if(includeRss)1 else 0,
                        "categories" to listOf(mapOf("id" to "news","label" to "新聞／資訊")),
                        "routes" to if(includeRss)listOf(mapOf("sourceId" to feedId,"sourceName" to "Owned cloud feed","label" to "資訊",
                            "categoryId" to "news","url" to "reader-cloud://articles","fingerprint" to sha))else emptyList<Any>(),"nextCursor" to null))
                    else response(mapOf("contentContractVersion" to contentContractVersion,"revision" to token,"checkedSources" to 2,"totalSources" to 2,
                        "categories" to listOf(mapOf("id" to "urban","label" to "都市"),mapOf("id" to "fantasy","label" to "玄幻／奇幻")),
                        "routes" to listOf(mapOf("sourceId" to sources[i].bookSourceUrl,"sourceName" to sources[i].bookSourceName,
                            "label" to if(i==0)"都市生活" else "玄幻","categoryId" to if(i==0)"urban" else "fantasy",
                            "url" to "https://owned-cloud.invalid/$token/category/$i","fingerprint" to sha)),
                        "nextCursor" to if(i==0)"second" else null))
                }
                else->newFixedLengthResponse(Response.Status.NOT_FOUND,"text/plain","Owned unknown route")
            }
        }
    }
}

internal fun restoreCloudPreferences(preferences:SharedPreferences,values:Map<String,*>) {
    check(preferences.edit().clear().apply { values.forEach{(key,value)->when(value){
        is Boolean->putBoolean(key,value);is String->putString(key,value);is Int->putInt(key,value)
        is Long->putLong(key,value);is Float->putFloat(key,value)
        is Set<*>->{@Suppress("UNCHECKED_CAST")putStringSet(key,value as Set<String>)}
    }} }.commit())
}
