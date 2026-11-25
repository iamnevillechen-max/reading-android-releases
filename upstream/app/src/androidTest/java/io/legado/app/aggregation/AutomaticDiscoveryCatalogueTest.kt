package io.legado.app.aggregation

import android.content.Context
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import fi.iki.elonen.NanoHTTPD
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.rule.ExploreRule
import io.legado.app.model.aggregation.AggregationPolicy
import io.legado.app.model.aggregation.ExploreAggregation
import io.legado.app.model.multisource.SourceRequestGate
import io.legado.app.model.webBook.WebBook
import io.legado.app.utils.GSON
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Owned menus only: literal discovery is offline; bounded foreground JS uses loopback HTTP. */
@RunWith(AndroidJUnit4::class)
class AutomaticDiscoveryCatalogueTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val token = UUID.randomUUID().toString()
    private val sources = arrayListOf<BookSource>()
    private val server = CatalogueServer()
    private val base get() = "http://127.0.0.1:${server.listeningPort}"

    private suspend fun owned(action: suspend () -> Unit) {
        check(context.packageName == "io.multiread.reader.debug" && Build.HARDWARE in setOf("ranchu", "goldfish"))
        check(appDb.bookSourceDao.allCount() == 0 && appDb.bookDao.allBookCount == 0)
        val preferences = context.getSharedPreferences("aggregation_categories", Context.MODE_PRIVATE)
        val hadCursor = preferences.contains("route_cursor_v1")
        val cursor = preferences.getInt("route_cursor_v1", 0)
        check(preferences.edit().putInt("route_cursor_v1", 0).commit())
        server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
        try { action() }
        finally {
            server.releaseSlow.countDown()
            appDb.runInTransaction { sources.chunked(128).forEach { appDb.bookSourceDao.delete(*it.toTypedArray()) } }
            server.stop()
            check(preferences.edit().apply {
                if (hadCursor) putInt("route_cursor_v1", cursor) else remove("route_cursor_v1")
            }.commit())
        }
    }

    private fun source(index: Int, kinds: String): BookSource = BookSource(
        bookSourceUrl = "$base/owned-catalogue/$token/$index", bookSourceName = "Owned catalogue $index $token",
        customOrder = index, enabled = true, enabledExplore = true, exploreUrl = kinds,
        jsLib = "java.ajax('$base/library')"
    ).also { sources.add(it) }
    private fun genre(index: Int) = "來源原有細類 $index"

    @Test fun automaticallyFinishesPast128AndRetainsEarlyCategoriesPast256SourcesAnd64PerSource() = runBlocking {
        owned {
            source(0, (0 until 128).joinToString("\n") { "${genre(it)} First category $it $token::/first/$it" })
            for (index in 1 until 300) source(index, "${genre(index)} Tail category $index $token::/list/$index")
            // These precede every literal source in user order. Their category programs
            // execute only after every literal menu has been published in the foreground.
            source(300, "@js:throw new Error(java.ajax('$base/bad'))").apply { customOrder = -2; jsLib = null }
            source(301, "<js>java.ajax('$base/timeout')</js>").apply { customOrder = -1; jsLib = null }
            appDb.runInTransaction { sources.chunked(128).forEach { appDb.bookSourceDao.insert(*it.toTypedArray()) } }
            val before = GSON.toJson(appDb.bookSourceDao.all)
            val publications = arrayListOf<List<String>>()
            val firstAvailable = CompletableDeferred<List<String>>()
            val releasePublication = CompletableDeferred<Unit>()
            val scan = launch(Dispatchers.IO) {
                withTimeout(30_000) {
                    ExploreAggregation.discoverCategories(context, onCategories = { categories ->
                        publications.add(categories)
                        if (categories.isNotEmpty() && firstAvailable.complete(categories)) releasePublication.await()
                    })
                }
            }
            try {
                val first = withTimeout(10_000) { firstAvailable.await() }
                assertTrue("Usable literal categories must appear before all source metadata is scanned",
                    ExploreAggregation.catalogueProgress.checkedSources in 1 until sources.size)
                assertTrue("A hundred original menu labels must not collapse into eleven genres", first.size > 11)
                assertTrue("Published categories are queryable without waiting for the scanner lock",
                    ExploreAggregation.snapshotRoutesForCategory(context, first.first()).isNotEmpty())
                assertEquals("All literal menus must be published before dynamic HTTP begins", 0, server.calls.get())
                releasePublication.complete(Unit)
                withTimeout(30_000) { scan.join() }
            } finally {
                releasePublication.complete(Unit)
                scan.cancelAndJoin()
            }
            assertTrue(ExploreAggregation.catalogueProgress.complete)
            assertEquals(302, ExploreAggregation.catalogueProgress.checkedSources)
            assertEquals(302, ExploreAggregation.catalogueProgress.totalSources)
            val expected = (0 until 128).map { "${genre(it)} First category $it $token" }.toSet() +
                (1 until 300).map { "${genre(it)} Tail category $it $token" }
            assertEquals(427, expected.size)
            assertEquals(expected, publications.last().toSet())
            val firstRoutes = ExploreAggregation.sourceRoutes(context, sources[0].bookSourceUrl)
            assertEquals("All original routes and their separate labels remain available", 128, firstRoutes.size)
            val lastEarly = firstRoutes.single { it.url == "/first/127" }
            assertEquals("${genre(127)} First category 127 $token", lastEarly.label)
            assertEquals(lastEarly.label, lastEarly.category)
            assertEquals(lastEarly.label, ExploreAggregation.categoryForRoute(context, sources[0].bookSourceUrl, "/first/127"))
            assertEquals(expected, ExploreAggregation.categoryLabels(context).toSet())
            assertEquals(listOf(lastEarly), ExploreAggregation.snapshotRoutesForCategory(context, lastEarly.label))
            val tail = ExploreAggregation.sourceRoutes(context, sources[299].bookSourceUrl).single()
            assertEquals("${genre(299)} Tail category 299 $token", tail.label)
            assertEquals("/list/299", tail.url)
            for (dynamic in sources.takeLast(2)) {
                assertTrue("A failed menu must not invent a category from its source name",
                    ExploreAggregation.sourceRoutes(context, dynamic.bookSourceUrl).isEmpty())
                assertFalse(dynamic.bookSourceName in ExploreAggregation.categoryLabels(context))
            }
            val all = ExploreAggregation.snapshotRoutesForCategory(context, AggregationPolicy.ALL_SOURCES)
            assertEquals("Every literal source is represented; failed menus do not fabricate routes", 300, all.size)
            assertEquals(sources.take(300).map { it.bookSourceUrl }.toSet(), all.map { it.source.bookSourceUrl }.toSet())
            assertEquals(all, ExploreAggregation.snapshotRoutesForCategory(context, null))
            val settledCalls = server.calls.get()
            withTimeout(2_000) { ExploreAggregation.discoverCategories(context) }
            assertEquals("Reopening the settled catalogue does not repeat failed menus", settledCalls, server.calls.get())
            assertEquals("Literal parsing libraries never run while listing categories", 0, server.libraryCalls.get())
            assertEquals("Classification never changes configured groups, rules or source fields", before, GSON.toJson(appDb.bookSourceDao.all))
        }
    }

    @Test fun cancellationKeepsSettledProgressAndNextForegroundScanResumesWithoutRetryingBadSources() = runBlocking {
        owned {
            source(0, "@js:throw new Error(java.ajax('$base/bad'))").jsLib = null
            source(1, "都市 First usable $token::/first")
            source(2, "@js:java.ajax('$base/slow')").jsLib = null
            for (index in 3 until 260) source(index, "${genre(index)} Rest $index $token::/rest/$index")
            appDb.runInTransaction { sources.chunked(128).forEach { appDb.bookSourceDao.insert(*it.toTypedArray()) } }
            val firstAvailable = CompletableDeferred<List<String>>()
            val holdPublication = CompletableDeferred<Unit>()
            val scan = launch(Dispatchers.IO) {
                ExploreAggregation.discoverCategories(context, onCategories = { categories ->
                    if (categories.isNotEmpty() && firstAvailable.complete(categories)) holdPublication.await()
                })
            }
            try {
                val firstCategories = withTimeout(10_000) { firstAvailable.await() }
                val beforePause = ExploreAggregation.snapshotRoutesForCategory(context, firstCategories.first())
                assertTrue(beforePause.isNotEmpty())
                withTimeout(3_000) { scan.cancelAndJoin() }
                val paused = ExploreAggregation.catalogueProgress
                assertFalse(paused.complete)
                assertTrue(paused.checkedSources in 1 until paused.totalSources)
                assertEquals(beforePause, ExploreAggregation.snapshotRoutesForCategory(context, firstCategories.first()))
                assertEquals(0, server.calls.get())
                withTimeout(30_000) { ExploreAggregation.discoverCategories(context) }
                assertTrue(ExploreAggregation.catalogueProgress.complete)
                assertEquals(260, ExploreAggregation.catalogueProgress.checkedSources)
                assertEquals(260, ExploreAggregation.catalogueProgress.totalSources)
                val all = ExploreAggregation.snapshotRoutesForCategory(context, null)
                assertEquals(sources.filterIndexed { index, _ -> index !in listOf(0, 2) }.map { it.bookSourceUrl }.toSet(),
                    all.map { it.source.bookSourceUrl }.toSet())
                for (early in beforePause) {
                    val retained = ExploreAggregation.sourceRoutes(context, early.source.bookSourceUrl).single { it.url == early.url }
                    assertEquals("Already committed literal metadata survives cancellation and resumption", early, retained)
                }
                assertEquals("/first", ExploreAggregation.sourceRoutes(context, sources[1].bookSourceUrl).single().url)
                for (index in listOf(0, 2)) assertTrue(
                    ExploreAggregation.sourceRoutes(context, sources[index].bookSourceUrl).isEmpty())
                assertEquals(258, ExploreAggregation.categoryLabels(context).size)
                assertEquals("Only the explicit foreground dynamic round may fetch its two menus", 2, server.calls.get())
                assertEquals(0, server.libraryCalls.get())
            } finally {
                holdPublication.complete(Unit)
                scan.cancelAndJoin()
            }
        }
    }

    @Test fun dynamicForegroundMenusPublishAllOriginalLabelsFilterAndResumeAfterCancellation() = runBlocking {
        owned {
            val labels = (0 until 36).map { "動態原有細類 $it $token" }
            val resumedLabel = "取消後恢復的原分類 $token"
            server.dynamicMenu = GSON.toJson(labels.mapIndexed { index, label ->
                mapOf("title" to label, "url" to "$base/dynamic-books/$index")
            })
            server.resumableMenu = GSON.toJson(listOf(mapOf("title" to resumedLabel, "url" to "$base/resumed-books")))
            val dynamic = source(0, "@js:java.ajax('$base/dynamic-menu')").apply {
                jsLib = null
                ruleExplore = ExploreRule(bookList = ".book", name = ".name@text", author = ".author@text", bookUrl = ".name@href")
            }
            source(1, "@js:java.ajax('$base/resumable-menu')").apply {
                jsLib = null
                // Independent providers must not compete for the production per-host
                // single slot while this fixture deliberately blocks one provider.
                bookSourceUrl = bookSourceUrl.replace("127.0.0.1", "localhost")
            }
            appDb.bookSourceDao.insert(*sources.toTypedArray())
            val original = GSON.toJson(appDb.bookSourceDao.all)
            val firstMenu = CompletableDeferred<Unit>()
            val holdPublication = CompletableDeferred<Unit>()
            val scan = launch(Dispatchers.IO) {
                ExploreAggregation.discoverCategories(context, onCategories = { categories ->
                    if (categories.containsAll(labels) && firstMenu.complete(Unit)) holdPublication.await()
                })
            }
            try {
                withTimeout(10_000) { firstMenu.await() }
                withTimeout(3_000) { server.slowStarted.await() }
                val committed = ExploreAggregation.sourceRoutes(context, dynamic.bookSourceUrl)
                assertEquals(labels.toSet(), committed.map { it.category }.toSet())
                assertEquals(36, committed.size)
                val published = ExploreAggregation.categoryLabels(context)
                assertEquals(labels.toSet(), published.toSet())
                assertFalse(sources.any { it.bookSourceName in published })
                scan.cancel()
                server.releaseSlow.countDown()
                withTimeout(5_000) { scan.join() }
                assertFalse(ExploreAggregation.catalogueProgress.complete)
                assertTrue(ExploreAggregation.catalogueProgress.pendingDynamicMenus > 0)
                assertEquals("Cancellation keeps the complete previously committed menu", committed,
                    ExploreAggregation.sourceRoutes(context, dynamic.bookSourceUrl))
                withTimeout(10_000) { ExploreAggregation.discoverCategories(context) }
                assertTrue(ExploreAggregation.catalogueProgress.complete)
                assertEquals(0, ExploreAggregation.catalogueProgress.pendingDynamicMenus)
                assertEquals(labels.toSet() + resumedLabel, ExploreAggregation.categoryLabels(context).toSet())
                assertEquals("A completed source menu must not be re-evaluated on resume", 1, server.dynamicMenuCalls.get())
                assertEquals("The cancelled source request resumes on the next foreground visit", 2, server.resumableMenuCalls.get())
                labels.forEachIndexed { index, label ->
                    val selected = ExploreAggregation.snapshotRoutesForCategory(context, label).single()
                    assertEquals(dynamic.bookSourceUrl, selected.source.bookSourceUrl)
                    assertEquals("$base/dynamic-books/$index", selected.url)
                    assertEquals(label, selected.label)
                }
                val selected = ExploreAggregation.snapshotRoutesForCategory(context, labels.last()).single()
                val parsed = WebBook.exploreBookAwait(dynamic, selected.url, 1)
                assertEquals(listOf("Owned dynamic book 35"), parsed.map { it.name })
                assertEquals(listOf(35), server.requestedBookCategories.toList())
                server.dynamicMenu = "Owned response without a valid category route"
                withTimeout(10_000) { ExploreAggregation.discoverCategories(context, refresh = true) }
                assertEquals("A later failed/empty dynamic menu does not erase known successful routes", committed,
                    ExploreAggregation.sourceRoutes(context, dynamic.bookSourceUrl))
                assertEquals(labels.toSet() + resumedLabel, ExploreAggregation.categoryLabels(context).toSet())
                assertEquals("Discovery and category filtering never rewrite the user's source rows", original,
                    GSON.toJson(appDb.bookSourceDao.all))
                assertEquals(0, appDb.bookDao.allBookCount)

                // A queued source has not attempted its menu. Crossing the two-second
                // per-source deadline while its host slot is busy must not exhaust it.
                val gateHeld = CompletableDeferred<Unit>()
                val releaseGate = CompletableDeferred<Unit>()
                val holder = launch(Dispatchers.IO) {
                    SourceRequestGate.request("$base/owned-held-slot") {
                        gateHeld.complete(Unit)
                        releaseGate.await()
                    }
                }
                try {
                    withTimeout(3_000) { gateHeld.await() }
                    val queuedLabel = "排隊後正常載入的原分類 $token"
                    server.queuedMenu = GSON.toJson(listOf(mapOf("title" to queuedLabel, "url" to "$base/queued-books")))
                    source(2, "@js:java.ajax('$base/queued-menu')").apply {
                        jsLib = null
                        appDb.bookSourceDao.insert(this)
                    }
                    val beforeQueuedLoad = GSON.toJson(appDb.bookSourceDao.all)
                    val queuedMetadataReady = CompletableDeferred<Unit>()
                    val queuedScan = launch(Dispatchers.IO) {
                        ExploreAggregation.discoverCategories(context, onCategories = {
                            if (ExploreAggregation.catalogueProgress.checkedSources == 3 &&
                                ExploreAggregation.catalogueProgress.pendingDynamicMenus > 0) queuedMetadataReady.complete(Unit)
                        })
                    }
                    try {
                        withTimeout(3_000) { queuedMetadataReady.await() }
                        delay(2_500)
                        assertEquals("A waiting host slot is not a source HTTP attempt", 0, server.queuedMenuCalls.get())
                        releaseGate.complete(Unit)
                        withTimeout(10_000) { queuedScan.join() }
                        assertTrue(ExploreAggregation.catalogueProgress.complete)
                        assertTrue("An unattempted queued menu must remain eligible after the bounded round",
                            queuedLabel in ExploreAggregation.categoryLabels(context))
                        assertEquals(1, server.queuedMenuCalls.get())
                        assertEquals(beforeQueuedLoad, GSON.toJson(appDb.bookSourceDao.all))
                    } finally {
                        releaseGate.complete(Unit)
                        queuedScan.cancelAndJoin()
                    }
                } finally {
                    releaseGate.complete(Unit)
                    holder.cancelAndJoin()
                }
            } finally {
                server.releaseSlow.countDown()
                holdPublication.complete(Unit)
                scan.cancelAndJoin()
            }
        }
    }

    private class CatalogueServer : NanoHTTPD("127.0.0.1", 0) {
        val calls = AtomicInteger()
        val libraryCalls = AtomicInteger()
        val dynamicMenuCalls = AtomicInteger()
        val resumableMenuCalls = AtomicInteger()
        val queuedMenuCalls = AtomicInteger()
        val slowStarted = CompletableDeferred<Unit>()
        val releaseSlow = CountDownLatch(1)
        val requestedBookCategories = java.util.concurrent.CopyOnWriteArrayList<Int>()
        @Volatile var dynamicMenu = "[]"
        @Volatile var resumableMenu = "[]"
        @Volatile var queuedMenu = "[]"
        override fun serve(session: IHTTPSession): Response {
            calls.incrementAndGet()
            when {
                session.uri == "/library" -> libraryCalls.incrementAndGet()
                session.uri == "/dynamic-menu" -> {
                    dynamicMenuCalls.incrementAndGet()
                    return newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", dynamicMenu)
                }
                session.uri == "/resumable-menu" -> {
                    if (resumableMenuCalls.incrementAndGet() == 1) {
                        slowStarted.complete(Unit)
                        releaseSlow.await(5, TimeUnit.SECONDS)
                    }
                    return newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", resumableMenu)
                }
                session.uri == "/queued-menu" -> {
                    queuedMenuCalls.incrementAndGet()
                    return newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", queuedMenu)
                }
                session.uri.startsWith("/dynamic-books/") -> {
                    val category = session.uri.substringAfterLast('/').toInt()
                    requestedBookCategories.add(category)
                    return newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8",
                        "<html><body><div class='book'><a class='name' href='/owned-book/$category'>Owned dynamic book $category</a>" +
                            "<span class='author'>Owned fixture author</span></div></body></html>")
                }
            }
            return newFixedLengthResponse(Response.Status.OK, "text/plain", "Owned HTTP trap: metadata must not request ${session.uri}")
        }
    }
}
