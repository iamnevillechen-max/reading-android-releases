package io.legado.app.aggregation

import android.app.Application
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import fi.iki.elonen.NanoHTTPD
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.RssSource
import io.legado.app.data.entities.rule.ExploreRule
import io.legado.app.help.source.removeSortCache
import io.legado.app.help.source.sortUrls
import io.legado.app.model.aggregation.AggregationPolicy
import io.legado.app.model.multisource.core.StreamingHash
import io.legado.app.ui.book.explore.ExploreShowViewModel
import io.legado.app.ui.rss.article.RssArticlesViewModel
import io.legado.app.utils.GSON
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Real native VMs/parser and owned local HTTP responses; no phone or public-site dependency. */
@RunWith(AndroidJUnit4::class)
class DiscoveryLoadingTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val application = ApplicationProvider.getApplicationContext<Application>()
    private val token = UUID.randomUUID().toString()
    private val category = "Owned loading $token"
    private val author = "Owned author $token"
    private val stores = arrayListOf<ViewModelStore>()
    private val bookSources = arrayListOf<BookSource>()
    private val feeds = arrayListOf<RssSource>()
    private val feedSnapshots = arrayListOf<List<RssSource>>()
    private val fixture = LoadingServer()

    private fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)
    private fun <T : ViewModel> model(type: Class<T>): T {
        lateinit var result: T
        main {
            val store = ViewModelStore().also { stores.add(it) }
            result = ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory.getInstance(application))[type]
        }
        return result
    }
    private fun await(timeout: Long = 6_000, predicate: () -> Boolean) {
        val until = SystemClock.uptimeMillis() + timeout
        while (SystemClock.uptimeMillis() < until) {
            var ready = false
            main { ready = predicate() }
            if (ready) return
            SystemClock.sleep(10)
        }
        fail("Owned loading fixture did not reach its expected state")
    }
    private fun owned(action: () -> Unit) {
        check(application.packageName == "io.multiread.reader.debug" && Build.HARDWARE in setOf("ranchu", "goldfish"))
        check(appDb.bookDao.allBookCount == 0 && appDb.bookSourceDao.allCount() == 0 && appDb.rssSourceDao.size == 0)
        fixture.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
        try { action() }
        finally {
            main { stores.forEach { it.clear() } }
            fixture.release.countDown()
            val until = SystemClock.uptimeMillis() + 3_000
            while (fixture.active.get() != 0 && SystemClock.uptimeMillis() < until) SystemClock.sleep(10)
            bookSources.forEach { source -> appDb.bookSourceDao.delete(source.bookSourceUrl) }
            (0 until 20).forEach { appDb.searchBookDao.clear("Owned book $token $it", author) }
            feeds.forEach { source -> appDb.rssArticleDao.delete(source.sourceUrl); appDb.rssSourceDao.delete(source.sourceUrl) }
            runBlocking { feeds.forEach { it.removeSortCache() } }
            feedSnapshots.forEach { sources ->
                val key = StreamingHash.sha256 { writer -> GSON.toJson(sources, writer); StreamingHash.field(writer, category) }
                File(application.cacheDir, "aggregate-rss/$key.json").delete()
            }
            fixture.stop()
        }
    }
    private fun bookSource(index: Int) = BookSource(bookSourceUrl = fixture.base(index) + "/source/$token/$index",
        bookSourceName = "Owned source $token $index", customOrder = index,
        exploreUrl = "$category::${fixture.base(index)}/explore/$index?page={{page}}",
        ruleExplore = ExploreRule(bookList = ".book", name = ".name@text", author = ".author@text", bookUrl = ".name@href"))
    private fun feed(index: Int, sort: String) = RssSource(sourceUrl = fixture.base(index) + "/feed/$token/$index",
        sourceName = "Owned feed $token $index", sourceGroup = category, sortUrl = sort,
        customOrder = index, enabledCookieJar = false)
    private fun installFeeds(vararg sources: RssSource) {
        feeds.addAll(sources); appDb.rssSourceDao.insert(*sources)
        feedSnapshots.add(sources.sortedBy { it.customOrder }.map { it.copy() })
    }
    private fun rssModel(): RssArticlesViewModel = model(RssArticlesViewModel::class.java).also { vm ->
        main { vm.init(Bundle().apply { putString("aggregateCategory", category) }) }
    }

    @Test(timeout = 25_000)
    fun bookScrollRoundsFetchEightThenReachTheNextSourcesWithoutRefetchingTheFirstPage() = owned {
        bookSources.addAll((0 until 20).map { bookSource(it) })
        appDb.bookSourceDao.insert(*bookSources.toTypedArray())
        val vm = model(ExploreShowViewModel::class.java)
        main { vm.initData(Intent().putExtra("sourceUrl", AggregationPolicy.ALL_SOURCES)
            .putExtra("aggregateCategory", category).putExtra("exploreUrl", category)) }
        await { vm.loadingLiveData.value == false && vm.booksData.value?.size == 8 }
        assertEquals((0 until 8).toSet(), fixture.bookRequests.map { it.first }.toSet())
        main { vm.explore() }
        await { vm.loadingLiveData.value == false && vm.booksData.value?.size == 16 }
        assertEquals((0 until 16).toSet(), fixture.bookRequests.map { it.first }.toSet())
        assertTrue("The second scroll round must discover new sources before requesting page2", fixture.bookRequests.all { it.second == 1 })
        assertTrue("Untouched later sources are not failures", vm.errorLiveData.value.isNullOrEmpty())
        assertTrue(fixture.peak.get() <= 4)
    }

    @Test(timeout = 20_000)
    fun leavingSingleSourceCancelsItsLateResultAndReopeningRetriesTheSamePage() = owned {
        val source = bookSource(0).also { bookSources.add(it) }; appDb.bookSourceDao.insert(source)
        fixture.blockBooks = true
        val vm = model(ExploreShowViewModel::class.java)
        main { vm.initData(Intent().putExtra("sourceUrl", source.bookSourceUrl)
            .putExtra("exploreUrl", fixture.base(0) + "/explore/0?page={{page}}")) }
        assertTrue(fixture.started.await(4, TimeUnit.SECONDS))
        main { vm.cancelLoading() }
        fixture.blockBooks = false
        fixture.release.countDown()
        await { vm.loadingLiveData.value == false }
        assertTrue(vm.booksData.value.isNullOrEmpty())
        assertTrue(vm.errorLiveData.value.isNullOrEmpty())
        main { vm.resumeLoading() }
        await { vm.loadingLiveData.value == false && vm.booksData.value?.size == 1 }
        assertEquals(listOf(1, 1), fixture.bookRequests.map { it.second })
    }

    @Test(timeout = 35_000)
    fun debugSingleSourceStopsWaitingAtItsDeadlineAndKeepsThePageRetryable() = owned {
        val source = bookSource(0).also { bookSources.add(it) }; appDb.bookSourceDao.insert(source)
        fixture.blockBooks = true
        val vm = model(ExploreShowViewModel::class.java)
        val before = SystemClock.elapsedRealtime()
        main { vm.initData(Intent().putExtra("sourceUrl", source.bookSourceUrl)
            .putExtra("exploreUrl", fixture.base(0) + "/explore/0?page={{page}}")) }
        await(19_000) { vm.loadingLiveData.value == false && !vm.errorLiveData.value.isNullOrEmpty() }
        assertTrue("DEBUG deliveries must not disable request deadlines", SystemClock.elapsedRealtime() - before < 19_000)
        assertTrue(vm.booksData.value.isNullOrEmpty())
        fixture.blockBooks = false; fixture.release.countDown()
        main { vm.explore() }
        await { vm.loadingLiveData.value == false && vm.booksData.value?.size == 1 }
        assertTrue(fixture.bookRequests.all { it.second == 1 })
    }

    @Test(timeout = 20_000)
    fun readyFeedPublishesWhileAnotherSourcesDynamicCategoriesAreStillBlockedAndCancellationIsQuiet() = owned {
        val blockedHost = fixture.base(1)
        val waiting = (0 until 4).map { index ->
            feed(index, "$category::$blockedHost/blocked-feed/$index.xml").copy(
                sourceUrl = "$blockedHost/feed/$token/$index")
        }
        val first = feed(4, "$category::${fixture.base(4)}/rss/fast.xml")
        val slow = feed(5, "@js:java.ajax('${fixture.base(5)}/slow-sort')")
        installFeeds(*(waiting + first + slow).toTypedArray())
        val vm = rssModel()
        val startedAt = SystemClock.elapsedRealtime()
        main { vm.ensureAggregate() }
        assertTrue(fixture.started.await(4, TimeUnit.SECONDS))
        await(3_000) { vm.aggregateArticles.value.orEmpty().any { it.title == "Owned article $token fast" } }
        val firstArticleMillis = SystemClock.elapsedRealtime() - startedAt
        println("DISCOVERY_LOADING readyFeedMillis=$firstArticleMillis")
        // Record early delivery first; a worker need not have claimed the following JS source
        // at the exact moment LiveData publishes the fast article.
        assertTrue("The unrelated dynamic classification must also have started", fixture.dynamicStarted.await(6, TimeUnit.SECONDS))
        assertTrue(vm.aggregateArticles.value.orEmpty().any { it.title == "Owned article $token fast" })
        assertEquals("Waiting on one host must not consume every work slot or delay another ready feed", 1L, fixture.release.count)
        val before = vm.aggregateArticles.value.orEmpty().map { it.link }
        main { vm.cancelAggregate() }
        fixture.release.countDown()
        SystemClock.sleep(200)
        assertEquals(before, vm.aggregateArticles.value.orEmpty().map { it.link })
        assertTrue("Leaving the page must not turn cancellation or untouched sources into errors", vm.loadErrorLiveData.value.isNullOrEmpty())
        assertTrue(fixture.peak.get() <= 4)
    }

    @Test(timeout = 20_000)
    fun ninthSourcesEditedDynamicLibraryInvalidatesItsOldSortCacheAcrossScrollRounds() = owned {
        val initial = (0 until 8).map { index -> feed(index, "$category::${fixture.base(index)}/rss/filler-$index.xml") }
        val source = feed(8, "@js:routeConfig").apply {
            jsLib = "var routeConfig=" + GSON.toJson("$category::${fixture.base(8)}/rss/old.xml") + ";"
        }
        installFeeds(*(initial + source).toTypedArray())
        // This older result has the same upstream cache key: source URL + sortUrl. Changing
        // only its library must invalidate it even though source9 is not in the first round.
        runBlocking { assertTrue(source.sortUrls().single().second.endsWith("/rss/old.xml")) }
        val changed = source.copy(jsLib = "var routeConfig=" + GSON.toJson("$category::${fixture.base(8)}/rss/new.xml") + ";")
        appDb.rssSourceDao.insert(changed)
        feedSnapshots.add(initial + changed.copy())
        val vm = rssModel()
        main { vm.ensureAggregate() }
        await { !vm.isLoading && vm.aggregateArticles.value.orEmpty().size == 8 }
        assertFalse(vm.aggregateArticles.value.orEmpty().any { it.title == "Owned article $token new" })
        main { vm.loadAggregate() }
        await { !vm.isLoading && vm.aggregateArticles.value.orEmpty().any { it.title == "Owned article $token new" } }
        assertFalse("A later batch cannot reuse the pre-edit dynamic route", vm.aggregateArticles.value.orEmpty().any { it.title == "Owned article $token old" })
    }

    private inner class LoadingServer : NanoHTTPD(0) {
        val release = CountDownLatch(1)
        val started = CountDownLatch(1)
        val dynamicStarted = CountDownLatch(1)
        val active = AtomicInteger()
        val peak = AtomicInteger()
        val bookRequests = CopyOnWriteArrayList<Pair<Int, Int>>()
        @Volatile var blockBooks = false
        fun base(index: Int) = "http://127.0.0.${index + 1}:$listeningPort"
        override fun serve(session: IHTTPSession): Response {
            val concurrent = active.incrementAndGet()
            peak.updateAndGet { maxOf(it, concurrent) }
            try {
                if (session.uri == "/slow-sort") {
                    started.countDown(); dynamicStarted.countDown(); release.await(25, TimeUnit.SECONDS)
                    return newFixedLengthResponse("$category::${base(5)}/rss/slow.xml")
                }
                if (session.uri.startsWith("/explore/")) {
                    val index = session.uri.substringAfterLast('/').toInt()
                    val page = session.parameters["page"]?.firstOrNull()?.toIntOrNull() ?: 1
                    bookRequests.add(index to page)
                    if (blockBooks) { started.countDown(); release.await(25, TimeUnit.SECONDS) }
                    val body = if (page != 1) "" else "<div class='book'><a class='name' href='/book/$token/$index'>Owned book $token $index</a><span class='author'>$author</span></div>"
                    return newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", "<html><meta charset='utf-8'><body>$body</body></html>")
                }
                if (session.uri.startsWith("/blocked-feed/")) {
                    started.countDown(); release.await(25, TimeUnit.SECONDS)
                    return rssResponse("blocked-" + session.uri.substringAfterLast('/'))
                }
                if (session.uri.startsWith("/rss/")) {
                    val label = session.uri.substringAfterLast('/').substringBefore('.')
                    return rssResponse(label)
                }
                return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Owned fixture not found")
            } finally { active.decrementAndGet() }
        }
        private fun rssResponse(label: String) = newFixedLengthResponse(Response.Status.OK, "application/rss+xml; charset=utf-8",
            """<?xml version="1.0" encoding="UTF-8"?><rss version="2.0"><channel><title>Owned feed</title><link>${base(0)}</link><description>Owned feed</description><item><title>Owned article $token $label</title><link>${base(0)}/article/$token/$label</link><description>Owned article</description></item></channel></rss>""")
    }
}
