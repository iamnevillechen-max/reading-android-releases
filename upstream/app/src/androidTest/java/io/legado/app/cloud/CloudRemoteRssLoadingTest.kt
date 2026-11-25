package io.legado.app.cloud

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import fi.iki.elonen.NanoHTTPD
import io.legado.app.data.appDb
import io.legado.app.data.entities.RssArticle
import io.legado.app.data.entities.RssSource
import io.legado.app.model.cloud.CloudCatalogue
import io.legado.app.model.cloud.CloudReader
import io.legado.app.model.cloud.CloudWebEntryPolicy
import io.legado.app.ui.rss.article.RssArticlesViewModel
import io.legado.app.ui.rss.read.ReadRssViewModel
import io.legado.app.utils.GSON
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Real native RSS view model using a private loopback cloud contract, never a phone. */
@RunWith(AndroidJUnit4::class)
class CloudRemoteRssLoadingTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val application = ApplicationProvider.getApplicationContext<Application>()
    private val token = UUID.randomUUID().toString()
    private val sourcePrefix = "https://owned-cloud-rss.invalid/$token/"
    private val stores = arrayListOf<ViewModelStore>()
    private fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)
    private fun await(timeout: Long = 6_000, predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeout
        while (SystemClock.elapsedRealtime() < deadline) {
            var ready = false
            main { ready = predicate() }
            if (ready) return
            SystemClock.sleep(10)
        }
        fail("Owned cloud RSS fixture did not reach its expected state")
    }
    private fun model(startAggregate: Boolean = true): RssArticlesViewModel {
        lateinit var vm: RssArticlesViewModel
        main {
            val store = ViewModelStore().also { stores.add(it) }
            vm = ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory.getInstance(application))[RssArticlesViewModel::class.java]
            vm.init(Bundle())
            if (startAggregate) vm.ensureAggregate()
        }
        return vm
    }
    private fun owned(count: Int, action: (Gateway) -> Unit) {
        check(application.packageName == "io.multiread.reader.debug" && Build.HARDWARE in setOf("ranchu", "goldfish"))
        check(appDb.bookDao.allBookCount == 0 && appDb.bookSourceDao.allCount() == 0 && appDb.rssSourceDao.size == 0 && !CloudReader.isConfigured(application))
        val cacheDirectory = File(application.cacheDir, "aggregate-rss")
        val previousCache = cacheDirectory.listFiles().orEmpty().mapTo(hashSetOf()) { it.name }
        val gateway = Gateway(count).apply { start(NanoHTTPD.SOCKET_READ_TIMEOUT, false) }
        CloudReader.configure(application, "http://127.0.0.1:${gateway.listeningPort}", token)
        application.getSharedPreferences("reader_cloud_v1", Context.MODE_PRIVATE).edit().putBoolean("enabled", true).commit()
        CloudCatalogue.clear()
        try { action(gateway) } finally {
            main { stores.forEach { it.clear() } }
            gateway.release.countDown()
            gateway.stop()
            for (index in 0 until count) {
                val id = gateway.id(index)
                appDb.rssArticleDao.delete(id); appDb.rssSourceDao.delete(id)
            }
            application.getSharedPreferences("reader_cloud_v1", Context.MODE_PRIVATE).edit().clear().commit()
            CloudCatalogue.clear()
            cacheDirectory.listFiles().orEmpty().filter { it.name !in previousCache }.forEach {
                check(it.isFile && it.canonicalFile.parentFile == cacheDirectory.canonicalFile)
                it.delete()
            }
        }
    }

    @Test(timeout = 25_000)
    fun badFirstWaveContinuesAndResumeKeepsExistingPageUntilExplicitScroll() = owned(14) { gateway ->
        gateway.badPrefix = 13
        val vm = model()
        await { !vm.isLoading && vm.aggregateArticles.value?.size == 1 }
        assertEquals(14, gateway.articleRequests.size)
        assertTrue(gateway.articleRequests.all { it.second == 1 })
        val first = vm.aggregateArticles.value!!.single().link
        main { repeat(3) { vm.resumeAggregate() } }
        SystemClock.sleep(250)
        assertEquals("Returning to the list must not request the next page", 14, gateway.articleRequests.size)
        assertEquals(first, vm.aggregateArticles.value!!.single().link)
        main { vm.loadAggregate() }
        await { !vm.isLoading && vm.aggregateArticles.value?.size == 2 }
        assertEquals(15, gateway.articleRequests.size)
        assertEquals(2, gateway.articleRequests.last().second)
        assertTrue(vm.aggregateArticles.value!!.any { it.link == first })
    }

    @Test(timeout = 28_000)
    fun expiredEmptyRoundSettlesItsClaimOnceInsteadOfSpinningForever() = owned(1) { gateway ->
        gateway.blockArticles = true
        val vm = model()
        assertTrue(gateway.started.await(4, TimeUnit.SECONDS))
        await(19_000) { !vm.isLoading }
        assertTrue(vm.aggregateArticles.value.isNullOrEmpty())
        assertEquals(1, gateway.articleRequests.size)
        SystemClock.sleep(250)
        assertEquals("An expired round must not immediately retry the same hanging feed", 1, gateway.articleRequests.size)
    }

    @Test(timeout = 18_000)
    fun lifecycleCancellationIsQuietAndReentryRetriesTheUnfinishedFirstPage() = owned(1) { gateway ->
        gateway.blockArticles = true
        val vm = model()
        assertTrue(gateway.started.await(4, TimeUnit.SECONDS))
        main { vm.cancelAggregate() }
        gateway.blockArticles = false
        gateway.release.countDown()
        await { !vm.isLoading }
        assertTrue(vm.aggregateArticles.value.isNullOrEmpty())
        assertTrue(vm.loadErrorLiveData.value.isNullOrEmpty())
        main { vm.resumeAggregate() }
        await { !vm.isLoading && vm.aggregateArticles.value?.size == 1 }
        assertTrue(gateway.articleRequests.all { it.second == 1 })
        assertEquals(2, gateway.articleRequests.size)
    }

    @Test(timeout = 15_000)
    fun singleSourcePaginationUsesServerNextUrlAfterLocalPaginationRuleWasRemoved() = owned(1) { gateway ->
        val source = RssSource(sourceUrl = gateway.id(0), sourceName = "Owned single cloud feed",
            ruleArticles = "reader-cloud://source", ruleContent = "reader-cloud://source", ruleNextPage = null)
        appDb.rssSourceDao.insert(source)
        val vm = model(startAggregate = false)
        main { vm.sortUrl = "reader-cloud://articles"; vm.loadArticles(source) }
        await { !vm.isLoading && vm.loadFinallyLiveData.value == true }
        main { vm.loadMore(source) }
        await { !vm.isLoading && vm.loadFinallyLiveData.value == false }
        assertEquals(listOf(1, 2), gateway.articleRequests.map { it.second })
        assertNotNull(appDb.rssArticleDao.get(source.sourceUrl, source.sourceUrl + "/article/1"))
        assertNotNull(appDb.rssArticleDao.get(source.sourceUrl, source.sourceUrl + "/article/2"))
    }

    @Test(timeout = 12_000)
    fun cloudWebsitePreservesSourceIdentityAndBypassesLegacyLocalPrograms() = owned(1) { gateway ->
        val target = "https://website.example.org/news?edition=1#section"
        val original = RssSource(sourceUrl = gateway.id(0), sourceName = "Owned website", singleUrl = true,
            sortUrl = target, ruleArticles = "reader-cloud://source", ruleContent = "@js:throw 'must not execute'",
            header = "@js:throw 'must not execute'", jsLib = "throw 'must not execute'", injectJs = "throw 'must not execute'",
            shouldOverrideUrlLoading = "throw 'must not execute'", enableJs = true)
        appDb.rssSourceDao.insert(original)
        val before = GSON.toJson(original)
        lateinit var vm: ReadRssViewModel
        main {
            val store = ViewModelStore().also { stores.add(it) }
            vm = ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory.getInstance(application))[ReadRssViewModel::class.java]
            vm.initData(Intent().putExtra("origin", original.sourceUrl)
                .putExtra(CloudWebEntryPolicy.EXTRA_CLOUD_WEBSITE, true).putExtra(CloudWebEntryPolicy.EXTRA_URL, target))
        }
        await { vm.urlLiveData.value != null }
        assertTrue(vm.isCloudWebsite)
        assertEquals(target, vm.urlLiveData.value!!.url)
        assertEquals(original.sourceUrl, vm.rssSource!!.sourceUrl)
        assertFalse(vm.rssSource!!.enableJs)
        assertNull(vm.rssSource!!.injectJs); assertNull(vm.rssSource!!.jsLib)
        assertNull(vm.rssSource!!.ruleContent); assertNull(vm.rssSource!!.shouldOverrideUrlLoading)
        assertTrue(vm.headerMap.isEmpty())
        assertEquals(before, GSON.toJson(appDb.rssSourceDao.getByKey(original.sourceUrl)))
        assertTrue(gateway.articleRequests.isEmpty())
    }

    private inner class Gateway(private val count: Int) : NanoHTTPD("127.0.0.1", 0) {
        val articleRequests = CopyOnWriteArrayList<Pair<String, Int>>()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        @Volatile var blockArticles = false
        var badPrefix = 0
        private val hash = "a".repeat(64)
        fun id(index: Int) = sourcePrefix + index.toString().padStart(3, '0')
        override fun serve(session: IHTTPSession): Response {
            if (session.headers["authorization"] != "Bearer $token") return newFixedLengthResponse(Response.Status.UNAUTHORIZED, "application/json", "{}")
            val files = hashMapOf<String, String>()
            if (session.method == Method.POST) session.parseBody(files)
            val body = files["postData"]?.let { JsonParser.parseString(it).asJsonObject } ?: JsonObject()
            val data = when (session.uri) {
                "/v1/sources" -> JsonObject().apply {
                    add("sources", JsonArray().apply { if (session.parms["kind"] == "rss") for (index in 0 until count) add(JsonObject().apply {
                        addProperty("id", id(index)); addProperty("name", "Owned cloud RSS $index")
                        addProperty("sha256", hash); addProperty("supported", true); addProperty("status", "healthy")
                        addProperty("enabled", true); addProperty("enabledExplore", true)
                    }) })
                }
                "/v1/source-receipt" -> JsonObject().apply {
                    addProperty("id", session.parms["id"]); addProperty("sha256", hash)
                    addProperty("stored", true); addProperty("supported", true); addProperty("status", "healthy")
                }
                "/v1/catalogue" -> JsonObject().apply {
                    addProperty("revision", "owned-1"); addProperty("checkedSources", count); addProperty("totalSources", count)
                    add("categories", JsonArray().apply { add(JsonObject().apply { addProperty("id", "other"); addProperty("label", "其他") }) })
                    add("routes", JsonArray().apply { for (index in 0 until count) add(JsonObject().apply {
                        addProperty("sourceId", id(index)); addProperty("sourceName", "Owned cloud RSS $index")
                        addProperty("label", "Owned feed $index"); addProperty("url", id(index) + "/feed")
                        addProperty("categoryId", "other"); addProperty("fingerprint", hash)
                    }) })
                }
                "/v1/rss/articles" -> {
                    val source = body["sourceId"].asString
                    val currentPage = body["page"].asInt
                    articleRequests.add(source to currentPage); started.countDown()
                    if (blockArticles) release.await(40, TimeUnit.SECONDS)
                    if (source.substringAfterLast('/').toInt() < badPrefix) return newFixedLengthResponse(Response.Status.SERVICE_UNAVAILABLE, "application/json", "{}")
                    JsonObject().apply {
                        add("articles", JsonArray().apply { add(GSON.toJsonTree(RssArticle(origin = source,
                            link = source + "/article/$currentPage", title = "Owned article $currentPage"))) })
                        if (currentPage == 1) addProperty("nextUrl", source + "/feed?page=2")
                    }
                }
                else -> return newFixedLengthResponse(Response.Status.NOT_FOUND, "application/json", "{}")
            }
            return newFixedLengthResponse(Response.Status.OK, "application/json", GSON.toJson(JsonObject().apply { add("data", data) }))
        }
    }
}
