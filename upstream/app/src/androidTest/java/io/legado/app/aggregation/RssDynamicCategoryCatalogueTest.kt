package io.legado.app.aggregation

import android.app.Application
import android.os.Build
import android.os.Bundle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import fi.iki.elonen.NanoHTTPD
import io.legado.app.data.appDb
import io.legado.app.data.entities.RssReadRecord
import io.legado.app.data.entities.RssSource
import io.legado.app.help.source.removeSortCache
import io.legado.app.model.aggregation.AggregationPolicy
import io.legado.app.model.aggregation.RssCategoryCatalogue
import io.legado.app.ui.rss.article.RssArticlesViewModel
import io.legado.app.utils.GSON
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/** Native menu execution is a self-written string expression; its only HTTP target is loopback. */
@RunWith(AndroidJUnit4::class)
class RssDynamicCategoryCatalogueTest {
    @Test(timeout = 30_000) fun everyDynamicOriginalSortIsPublishedAndTheTailCategoryReadsOnlyItsOwnArticle() = runBlocking {
        val application = ApplicationProvider.getApplicationContext<Application>()
        check(application.packageName == "io.multiread.reader.debug" && Build.HARDWARE in setOf("ranchu", "goldfish"))
        check(appDb.rssSourceDao.all.all { it.singleUrl }) { "Dedicated installation without user article feeds required" }
        val requests = CopyOnWriteArrayList<String>()
        val server = object : NanoHTTPD("127.0.0.1", 0) {
            override fun serve(session: IHTTPSession): Response {
                requests.add(session.uri)
                return newFixedLengthResponse(Response.Status.OK, "text/html", "<article class='owned'><h2>Owned tail article</h2><a href='/item/15'>Read</a></article>")
            }
        }.apply { start(NanoHTTPD.SOCKET_READ_TIMEOUT, false) }
        val base = "http://127.0.0.1:${server.listeningPort}"
        val token = UUID.randomUUID().toString()
        val labels = (0..15).map { "Owned original $token $it" }
        val menu = labels.mapIndexed { index, label -> "$label::$base/feed/$index" }.joinToString("&&")
        val source = RssSource(sourceUrl = "$base/owned/$token", sourceName = "Owned dynamic source", sourceGroup = "Owned original group $token",
            sortUrl = "  @js:" + GSON.toJson(menu), ruleArticles = ".owned", ruleTitle = "h2@text", ruleLink = "a@href", ruleDescription = "h2@text")
        val history = RssReadRecord(record = "$base/item/15", title = "Owned tail article", readTime = 4_321, read = true)
        val store = ViewModelStore()
        try {
            appDb.rssSourceDao.insert(source); appDb.rssReadRecordDao.insertRecord(history)
            RssCategoryCatalogue.synchronize(appDb.rssSourceDao.categorySpecs())
            val sorts = RssCategoryCatalogue.resolve(source)
            assertEquals(16, sorts.size)
            assertTrue(RssCategoryCatalogue.snapshots.value.names.containsAll(labels))
            assertTrue(requests.isEmpty()) // Discovering this self-written menu never requests an article.
            val model = withContext(Dispatchers.Main) {
                ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory.getInstance(application))[RssArticlesViewModel::class.java].apply {
                    init(Bundle().apply { putString("sortUrl", AggregationPolicy.ALL_SOURCES); putString("aggregateCategory", labels.last()) })
                    ensureAggregate()
                }
            }
            withTimeout(12_000) {
                while (!withContext(Dispatchers.Main) { !model.isLoading && model.aggregateArticles.value?.size == 1 }) delay(20)
            }
            assertEquals(listOf("/feed/15"), requests.toList())
            val article = withContext(Dispatchers.Main) { model.aggregateArticles.value!!.single() }
            assertEquals(labels.last(), article.sort); assertEquals("Owned tail article", article.title); assertTrue(article.read)
            assertEquals(source.sourceGroup, appDb.rssSourceDao.getByKey(source.sourceUrl)!!.sourceGroup)
            assertEquals(GSON.toJson(history), GSON.toJson(appDb.rssReadRecordDao.getRecords().single { it.record == history.record }))
            assertEquals(source.sortUrl, appDb.rssSourceDao.getByKey(source.sourceUrl)!!.sortUrl)
        } finally {
            withContext(Dispatchers.Main) { store.clear() }
            server.stop()
            source.removeSortCache()
            appDb.rssArticleDao.delete(source.sourceUrl); appDb.rssSourceDao.delete(source.sourceUrl)
            appDb.openHelper.writableDatabase.execSQL("DELETE FROM rssReadRecords WHERE record = ?", arrayOf(history.record))
            RssCategoryCatalogue.synchronize(appDb.rssSourceDao.categorySpecs())
        }
    }
}
