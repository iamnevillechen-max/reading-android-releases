package io.legado.app.cloud

import android.content.Context
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import fi.iki.elonen.NanoHTTPD
import io.legado.app.data.appDb
import io.legado.app.data.entities.*
import io.legado.app.data.entities.rule.ContentRule
import io.legado.app.data.entities.rule.SearchRule
import io.legado.app.help.book.BookHelp
import io.legado.app.model.ReadBook
import io.legado.app.model.cloud.CloudMigrationPolicy
import io.legado.app.model.cloud.CloudReader
import io.legado.app.model.cloud.CloudContentCache
import io.legado.app.model.multisource.MultiSourceManager
import io.legado.app.model.rss.Rss
import io.legado.app.model.webBook.WebBook
import io.legado.app.utils.GSON
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/** Empty owned emulator only: private rules and stored reading data never leave loopback. */
@RunWith(AndroidJUnit4::class)
class CloudReaderMigrationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val token = UUID.randomUUID().toString()
    private val prefix = "https://owned-cloud.invalid/$token/"
    private val readableBody = ("Owned readable body. This self-written fixture contains normal prose and verifies a complete current chapter. ").repeat(2).trim()
    private val sources = arrayListOf<BookSource>()
    private val feeds = arrayListOf<RssSource>()
    private val books = arrayListOf<Book>()
    private val searches = arrayListOf<SearchBook>()
    private val histories = arrayListOf<ReadRecord>()
    private val ownedCacheFiles = arrayListOf<File>()

    private fun guard() {
        check(context.packageName == "io.multiread.reader.debug" && Build.HARDWARE in setOf("ranchu", "goldfish"))
        check(appDb.bookSourceDao.allCount() == 0 && appDb.rssSourceDao.size == 0 && appDb.bookDao.allBookCount == 0 &&
            appDb.readRecordDao.all.isEmpty() && ReadBook.book == null && !CloudReader.isConfigured(context)) {
            "Cloud migration fixtures require an empty owned emulator"
        }
    }

    private suspend fun fixture(action: suspend (Gateway) -> Unit) {
        guard()
        val backupDirectory = File(context.filesDir, "reader-cloud-migration")
        val originalFiles = backupDirectory.listFiles().orEmpty().mapTo(hashSetOf()) { it.name }
        val oldMerge = MultiSourceManager.enabled
        val server = Gateway().apply { start(NanoHTTPD.SOCKET_READ_TIMEOUT, false) }
        CloudReader.configure(context, "http://127.0.0.1:${server.listeningPort}", token)
        MultiSourceManager.enabled = false
        try { action(server) } finally {
            server.stop()
            books.forEach { appDb.bookDao.delete(it) }
            searches.forEach { appDb.searchBookDao.delete(it) }
            histories.forEach { appDb.readRecordDao.delete(it) }
            feeds.forEach { appDb.rssArticleDao.delete(it.sourceUrl); appDb.rssStarDao.delete(it.sourceUrl); appDb.rssSourceDao.delete(it.sourceUrl) }
            sources.forEach { appDb.bookSourceDao.delete(it.bookSourceUrl) }
            ownedCacheFiles.forEach { file -> check(file.name.contains(token)); file.delete() }
            context.getSharedPreferences("reader_cloud_v1", Context.MODE_PRIVATE).edit().clear().commit()
            MultiSourceManager.enabled = oldMerge
            // Only files created by this isolated fixture are disposable.
            backupDirectory.listFiles().orEmpty().filter { it.name !in originalFiles }.forEach { file ->
                check(file.isFile && file.canonicalFile.parentFile == backupDirectory.canonicalFile)
                file.delete()
            }
        }
    }

    private fun source(suffix: String, enabled: Boolean = true) = BookSource(
        bookSourceUrl = prefix + suffix, bookSourceName = "Owned cloud $suffix", bookSourceGroup = "Owned group",
        customOrder = 85, enabled = enabled, enabledExplore = true, searchUrl = prefix + "search?q={{key}}",
        exploreUrl = "玄幻::${prefix}explore", jsLib = "var ownedFixture = true;",
        ruleSearch = SearchRule(bookList = ".owned", name = ".title@text", bookUrl = "a@href"),
        ruleContent = ContentRule(content = "#self-written-body")
    ).also { sources.add(it); appDb.bookSourceDao.insert(it) }

    private fun book(source: BookSource): Book = Book(bookUrl = prefix + "book", tocUrl = prefix + "toc",
        origin = source.bookSourceUrl, originName = source.bookSourceName, name = "Owned cloud book $token", author = "Owned author",
        durChapterIndex = 7, durChapterPos = 321, durChapterTime = 1_700_000_000_123,
        durChapterTitle = "第八章 自寫正文", totalChapterNum = 8, canUpdate = false,
        readConfig = Book.ReadConfig(multiSourcePositionAnchor = "owned-anchor")).also {
        books.add(it); appDb.bookDao.insert(it)
        appDb.bookChapterDao.insert(chapter(it))
    }
    private fun chapter(book: Book) = BookChapter(bookUrl = book.bookUrl, url = prefix + "chapter/7?edition=1#part",
        title = "第八章 自寫正文", baseUrl = book.tocUrl, index = 7)

    @Test(timeout = 35_000) fun migrationKeepsForeignKeysReadingStateAndRssKeys() = runBlocking {
        fixture { server ->
            val good = source("a-good", enabled = false)
            val unsupported = source("z-unsupported")
            val originalUnsupported = GSON.toJson(unsupported)
            SearchBook(bookUrl = prefix + "protected-candidate", name = "Owned protected candidate", author = "Owned author",
                origin = unsupported.bookSourceUrl).also { searches.add(it); appDb.searchBookDao.insert(it) }
            val book = book(good)
            val originalBook = GSON.toJson(book)
            val candidate = SearchBook(bookUrl = prefix + "candidate", name = book.name, author = book.author,
                origin = good.bookSourceUrl, originName = good.bookSourceName).also { searches.add(it); appDb.searchBookDao.insert(it) }
            val originalCandidate = GSON.toJson(candidate)
            val record = ReadRecord(deviceId = token, bookName = book.name, readTime = 777, lastRead = 123)
                .also { histories.add(it); appDb.readRecordDao.insert(it) }
            val feed = RssSource(sourceUrl = prefix + "rss-good", sourceName = "Owned feed", sourceGroup = "科技",
                customOrder = 17, ruleArticles = "item", ruleTitle = "title@text", ruleLink = "link@text", ruleContent = "article@html")
                .also { feeds.add(it); appDb.rssSourceDao.insert(it) }
            val article = RssArticle(origin = feed.sourceUrl, link = prefix + "article?id=1", title = "Owned saved article", read = true)
            appDb.rssArticleDao.insert(article); appDb.rssStarDao.insert(article.toStar())
            val report = CloudReader.migrate(context)
            assertEquals(3, report.uploaded); assertEquals(2, report.converted); assertEquals(1, report.archived)
            assertEquals(0, report.archiveCleaned); assertEquals(1, report.preserved)
            assertTrue(CloudReader.isEnabled(context))
            val stub = requireNotNull(appDb.bookSourceDao.getBookSource(good.bookSourceUrl))
            assertTrue(CloudReader.isRemote(stub)); assertFalse(stub.enabled); assertEquals(85, stub.customOrder)
            assertNull(stub.jsLib); assertNull(stub.ruleSearch); assertNull(stub.ruleContent)
            assertEquals(originalUnsupported, GSON.toJson(appDb.bookSourceDao.getBookSource(unsupported.bookSourceUrl)))
            assertEquals(originalBook, GSON.toJson(appDb.bookDao.getBook(book.bookUrl)))
            assertEquals(originalCandidate, GSON.toJson(appDb.searchBookDao.getSearchBook(candidate.bookUrl)))
            assertEquals(GSON.toJson(record), GSON.toJson(appDb.readRecordDao.all.single()))
            assertEquals(GSON.toJson(chapter(book)), GSON.toJson(appDb.bookChapterDao.getChapter(book.bookUrl, 7)))
            assertEquals(GSON.toJson(article), GSON.toJson(appDb.rssArticleDao.get(article.origin, article.link)))
            assertNotNull(appDb.rssStarDao.get(article.origin, article.link))
            assertTrue(CloudReader.isRemote(requireNotNull(appDb.rssSourceDao.getByKey(feed.sourceUrl))))
            assertTrue(server.requests.any { it.first == "/v1/books/content" })
            val originals = File(context.filesDir, "reader-cloud-migration").listFiles().orEmpty()
                .filter { it.name.endsWith(".backup.jsonl") }.flatMap { it.readLines() }
            assertTrue(originals.any { JsonParser.parseString(it).asJsonObject["json"].asString == GSON.toJson(good) })
            val second = CloudReader.migrate(context)
            assertEquals(0, second.converted); assertEquals(1, second.uploaded)
            assertEquals(originalCandidate, GSON.toJson(appDb.searchBookDao.getSearchBook(candidate.bookUrl)))
        }
    }

    @Test(timeout = 25_000) fun mismatchedReceiptPreventsEveryRuleMutation() = runBlocking {
        fixture { server ->
            val first = source("a-good")
            val second = source("z-mismatch")
            val before = sources.associate { it.bookSourceUrl to GSON.toJson(it) }
            server.mismatchId = second.bookSourceUrl
            var failed = false
            try { CloudReader.migrate(context) } catch (_: IllegalArgumentException) { failed = true }
            assertTrue(failed)
            sources.forEach { assertEquals(before[it.bookSourceUrl], GSON.toJson(appDb.bookSourceDao.getBookSource(it.bookSourceUrl))) }
            assertFalse(CloudReader.isEnabled(context))
            assertTrue(server.stored.containsKey("book" to first.bookSourceUrl))
        }
    }

    @Test(timeout = 25_000) fun cancellationAfterVerificationPreservesAllOriginalRules() = runBlocking {
        fixture { _ ->
            source("a-good"); source("z-good")
            val before = sources.associate { it.bookSourceUrl to GSON.toJson(it) }
            var cancelled = false
            try { CloudReader.migrate(context) { progress ->
                if (progress.verified > 0) throw CancellationException("Owned test cancellation before mutation")
            } } catch (_: CancellationException) { cancelled = true }
            assertTrue(cancelled)
            sources.forEach { assertEquals(before[it.bookSourceUrl], GSON.toJson(appDb.bookSourceDao.getBookSource(it.bookSourceUrl))) }
            assertFalse(CloudReader.isEnabled(context))
        }
    }

    @Test(timeout = 30_000) fun nativeRoutingPreservesNeedSaveAndLocalPositionAndVariableMap() = runBlocking {
        fixture { server ->
            val original = source("a-good")
            val book = book(original)
            CloudReader.migrate(context)
            val source = requireNotNull(appDb.bookSourceDao.getBookSource(original.bookSourceUrl))
            val chapter = chapter(book)
            val originalStored = GSON.toJson(appDb.bookDao.getBook(book.bookUrl))
            val results = WebBook.searchBookAwait(source, "Owned", filter = { name, _ -> name == "Owned second" }, shouldBreak = { it == 1 })
            assertEquals(1, results.size); assertEquals("Owned second", results.single().name)
            assertEquals(source.bookSourceUrl, results.single().origin)
            assertEquals(2, WebBook.exploreBookAwait(source, "reader-cloud://explore").size)
            book.variableMap["old"] = "stale"
            WebBook.getBookInfoAwait(source, book, false)
            assertEquals("Owned cloud book $token", book.name)
            assertEquals("new", book.getVariable("session")); assertEquals("", book.getVariable("old"))
            assertEquals(321, book.durChapterPos); assertEquals(7, book.durChapterIndex)
            assertEquals(8, WebBook.getChapterListAwait(source, book).getOrThrow().size)
            book.setReverseToc(true)
            assertEquals(chapter.url, WebBook.getChapterListAwait(source, book).getOrThrow().first().url)
            book.setReverseToc(false)
            assertNull(BookHelp.getContent(book, chapter, false))
            assertEquals(readableBody, WebBook.getContentAwait(source, book, chapter, needSave = false))
            assertNull(BookHelp.getContent(book, chapter, false))
            assertEquals(readableBody, WebBook.getContentAwait(source, book, chapter, needSave = true))
            assertEquals(readableBody, BookHelp.getContent(book, chapter, false))
            assertEquals(originalStored, GSON.toJson(appDb.bookDao.getBook(book.bookUrl)))
            for ((path, request) in server.requests) if (path.startsWith("/v1/books/") && request.has("book")) {
                val payload = request.getAsJsonObject("book")
                for (privateKey in listOf("durChapterPos", "durChapterIndex", "readConfig", "group", "durChapterTime")) assertFalse(payload.has(privateKey))
            }
        }
    }

    @Test(timeout = 25_000) fun rssCallsUseGatewayAndPreserveOriginalArticleIdentity() = runBlocking {
        fixture { _ ->
            val feed = RssSource(sourceUrl = prefix + "rss-good", sourceName = "Owned feed", ruleArticles = "item", ruleContent = "article")
                .also { feeds.add(it); appDb.rssSourceDao.insert(it) }
            CloudReader.migrate(context)
            val stub = requireNotNull(appDb.rssSourceDao.getByKey(feed.sourceUrl))
            val (articles, next) = Rss.getArticlesAwait("科技", "reader-cloud://articles", stub, 1)
            assertEquals(feed.sourceUrl, articles.single().origin)
            assertEquals(prefix + "article?id=2&edition=1#fragment", articles.single().link)
            assertEquals("科技", articles.single().sort); assertEquals("owned-next-page", next)
            assertEquals("Owned RSS body", Rss.getContentAwait(articles.single(), "reader-cloud://source", stub))
        }
    }

    @Test(timeout = 30_000) fun cloudResultsCarryModeWithoutChangingUnmigratedShelfBooks() = runBlocking {
        fixture { _ ->
            source("a-good")
            val legacy = source("z-unsupported")
            val legacyShelfBook = book(legacy)
            CloudReader.migrate(context)
            val original = requireNotNull(appDb.bookSourceDao.getBookSource(legacy.bookSourceUrl))
            assertFalse(CloudReader.isRemote(original))
            assertFalse(CloudReader.isRemote(original, legacyShelfBook))
            val result = WebBook.exploreBookAwait(original, "reader-cloud://category/fantasy").first().toBook()
            assertTrue(CloudReader.isRemote(original, result))
            WebBook.getBookInfoAwait(original, result, false)
            assertTrue(CloudReader.isRemote(original, result))
            assertFalse(CloudReader.bookPayload(result)["variable"].asString.contains("__reader_cloud_source_v1"))
            val chapter = BookChapter(bookUrl = result.bookUrl, url = prefix + "chapter/0", title = "Owned first", index = 0)
            assertEquals(readableBody, WebBook.getContentAwait(original, result, chapter, needSave = false))
            assertFalse(CloudReader.isRemote(original, legacyShelfBook))
        }
    }

    @Test(timeout = 35_000) fun unreferencedRulesBecomeDisabledArchivesWhileEveryReferenceSurvives() = runBlocking {
        fixture { server ->
            source("a-good")
            val orphan = source("orphan-unsupported")
            val unverified = source("orphan-unverified")
            val candidateSource = source("protected-unsupported")
            val relationSource = source("relation-unsupported")
            val healthSource = source("health-unverified")
            SearchBook(bookUrl = prefix + "protected-candidate", name = "Owned candidate", author = "Owned author",
                origin = candidateSource.bookSourceUrl).also { searches.add(it); appDb.searchBookDao.insert(it) }
            val relation = File(context.filesDir, "multisource-v1/relation-owned-$token.json").apply {
                parentFile!!.mkdirs(); writeText(JsonObject().apply { add("book", GSON.toJsonTree(Book(origin = relationSource.bookSourceUrl))) }.toString())
            }.also { ownedCacheFiles.add(it) }
            File(context.filesDir, "multisource-health-v1/owned-$token.json").apply {
                parentFile!!.mkdirs(); writeText(JsonObject().apply {
                    addProperty("version", 1); add("sources", JsonArray().apply {
                        add(JsonArray().apply { add(healthSource.bookSourceUrl); add(0); add(true); add(0) })
                    })
                }.toString())
            }.also { ownedCacheFiles.add(it) }
            val orphanFeed = RssSource(sourceUrl = prefix + "rss-orphan-unsupported", sourceName = "Owned archive RSS", ruleArticles = "item")
                .also { feeds.add(it); appDb.rssSourceDao.insert(it) }
            val protectedFeed = RssSource(sourceUrl = prefix + "rss-protected-unsupported", sourceName = "Owned protected RSS", ruleArticles = "item")
                .also { feeds.add(it); appDb.rssSourceDao.insert(it) }
            val article = RssArticle(origin = protectedFeed.sourceUrl, link = prefix + "saved-article", title = "Owned saved RSS")
            appDb.rssArticleDao.insert(article); appDb.rssStarDao.insert(article.toStar())
            val protectedBefore = listOf(candidateSource, relationSource, healthSource).associate { it.bookSourceUrl to GSON.toJson(it) }
            val report = CloudReader.migrate(context)
            assertEquals(8, report.uploaded); assertEquals(1, report.converted)
            assertEquals(3, report.archiveCleaned); assertEquals(4, report.preserved)
            for (source in listOf(orphan, unverified)) {
                val stub = requireNotNull(appDb.bookSourceDao.getBookSource(source.bookSourceUrl))
                assertTrue(CloudReader.isRemote(stub)); assertFalse(stub.enabled); assertFalse(stub.enabledExplore)
                assertNull(stub.ruleSearch); assertNull(stub.ruleContent); assertNull(stub.jsLib)
                assertEquals(GSON.toJson(source), server.savedJson["book" to source.bookSourceUrl])
            }
            assertFalse(requireNotNull(appDb.rssSourceDao.getByKey(orphanFeed.sourceUrl)).enabled)
            for ((id, original) in protectedBefore) assertEquals(original, GSON.toJson(appDb.bookSourceDao.getBookSource(id)))
            assertEquals(GSON.toJson(protectedFeed), GSON.toJson(appDb.rssSourceDao.getByKey(protectedFeed.sourceUrl)))
            assertEquals(GSON.toJson(article), GSON.toJson(appDb.rssArticleDao.get(article.origin, article.link)))
            assertTrue(relation.isFile)
            val mixedScope = sources.mapNotNull { appDb.bookSourceDao.getBookSourcePart(it.bookSourceUrl) }
            assertEquals(listOf(sources.first().bookSourceUrl), CloudReader.newSearchParts(mixedScope).map { it.bookSourceUrl })
            assertFalse(server.requests.any { it.first == "/v1/verify" && it.second.get("sourceId")?.asString == unverified.bookSourceUrl })
            assertFalse(server.requests.any { it.first == "/v1/verify" && it.second.get("sourceId")?.asString == healthSource.bookSourceUrl })
            // A later server verification activates the exact archived ID without re-importing rules.
            server.promotedIds.add(unverified.bookSourceUrl)
            server.stored.getValue("book" to unverified.bookSourceUrl).addProperty("status", "healthy")
            assertEquals(1, CloudReader.syncCatalogue(context))
            val restored = requireNotNull(appDb.bookSourceDao.getBookSource(unverified.bookSourceUrl))
            assertTrue(restored.enabled); assertTrue(restored.enabledExplore); assertTrue(CloudReader.isRemote(restored))
            assertNull(restored.jsLib); assertNotNull(appDb.searchBookDao.getSearchBook(prefix + "protected-candidate"))
            assertEquals(setOf(sources.first().bookSourceUrl, unverified.bookSourceUrl), CloudReader.newSearchParts(
                sources.mapNotNull { appDb.bookSourceDao.getBookSourcePart(it.bookSourceUrl) }).map { it.bookSourceUrl }.toSet())
            server.hiddenIds.add(sources.first().bookSourceUrl)
            assertEquals(0, CloudReader.syncCatalogue(context))
            assertEquals(listOf(unverified.bookSourceUrl), CloudReader.newSearchParts(
                sources.mapNotNull { appDb.bookSourceDao.getBookSourcePart(it.bookSourceUrl) }).map { it.bookSourceUrl })
            assertTrue(requireNotNull(appDb.bookSourceDao.getBookSource(sources.first().bookSourceUrl)).enabled)
            server.hiddenIds.clear()
            assertEquals(0, CloudReader.syncCatalogue(context))
            assertEquals(2, CloudReader.newSearchParts(sources.mapNotNull { appDb.bookSourceDao.getBookSourcePart(it.bookSourceUrl) }).size)
        }
    }

    @Test(timeout = 35_000) fun healthyCloudPrimaryMayUseFiveSecondsWithoutPrematureRescue() = runBlocking {
        fixture { server ->
            val original = source("a-good")
            val book = book(original)
            CloudReader.migrate(context)
            val source = requireNotNull(appDb.bookSourceDao.getBookSource(original.bookSourceUrl))
            server.contentDelayMillis = 5_000
            MultiSourceManager.enabled = true
            assertEquals(readableBody, WebBook.getContentAwait(source, book, chapter(book), needSave = true))
            assertEquals(7, book.durChapterIndex); assertEquals(321, book.durChapterPos)
        }
    }

    private fun promote(server: Gateway, source: BookSource) {
        server.promotedIds.add(source.bookSourceUrl)
        server.stored.getValue("book" to source.bookSourceUrl).apply {
            addProperty("supported", true); addProperty("status", "healthy")
        }
    }

    @Test(timeout = 35_000) fun aLaterHealthyServerRepairReconnectsAProtectedFullRuleWithExactCurrentChapterProof() = runBlocking {
        fixture { server ->
            source("a-good")
            val legacy = source("protected-unsupported")
            val book = book(legacy)
            val before = GSON.toJson(book)
            val history = ReadRecord(token, book.name, 900, 700).also { histories.add(it); appDb.readRecordDao.insert(it) }
            val candidate = SearchBook(bookUrl = prefix + "retained-search", origin = legacy.bookSourceUrl, name = "Owned retained search")
                .also { searches.add(it); appDb.searchBookDao.insert(it) }
            CloudReader.migrate(context)
            assertFalse(CloudReader.isRemote(requireNotNull(appDb.bookSourceDao.getBookSource(legacy.bookSourceUrl))))
            promote(server, legacy)
            assertEquals(1, CloudReader.syncCatalogue(context))
            val restored = requireNotNull(appDb.bookSourceDao.getBookSource(legacy.bookSourceUrl))
            assertTrue(CloudReader.isRemote(restored)); assertTrue(restored.enabled); assertTrue(restored.enabledExplore)
            assertNull(restored.jsLib); assertNull(restored.ruleSearch)
            assertTrue(CloudReader.newSearchParts(sources.mapNotNull { appDb.bookSourceDao.getBookSourcePart(it.bookSourceUrl) })
                .any { it.bookSourceUrl == legacy.bookSourceUrl })
            assertEquals(2, WebBook.exploreBookAwait(restored, "reader-cloud://category/all").size)
            assertTrue(server.requests.any { it.first == "/v1/books/content" && it.second["sourceId"]?.asString == legacy.bookSourceUrl })
            assertEquals(before, GSON.toJson(appDb.bookDao.getBook(book.bookUrl)))
            assertEquals(listOf(history), appDb.readRecordDao.all)
            assertEquals(GSON.toJson(candidate), GSON.toJson(appDb.searchBookDao.getSearchBook(candidate.bookUrl)))
            assertEquals(GSON.toJson(legacy), server.savedJson["book" to legacy.bookSourceUrl])
        }
    }

    @Test(timeout = 35_000) fun aHealthySourceWhoseCurrentShelfChapterIsGatedKeepsItsProtectedFullRule() = runBlocking {
        fixture { server ->
            val good = source("a-good")
            val legacy = source("protected-unsupported")
            val book = book(legacy)
            CloudReader.migrate(context)
            promote(server, legacy)
            server.contentByIndex[book.durChapterIndex] = "请先登录后阅读"
            val before = GSON.toJson(appDb.bookSourceDao.getBookSource(legacy.bookSourceUrl))
            assertEquals(0, CloudReader.syncCatalogue(context))
            assertEquals(before, GSON.toJson(appDb.bookSourceDao.getBookSource(legacy.bookSourceUrl)))
            assertEquals(listOf(good.bookSourceUrl), CloudReader.newSearchParts(sources.mapNotNull {
                appDb.bookSourceDao.getBookSourcePart(it.bookSourceUrl) }).map { it.bookSourceUrl })
            assertEquals(7, appDb.bookDao.getBook(book.bookUrl)!!.durChapterIndex)
        }
    }

    @Test(timeout = 35_000) fun aLocalRuleWhoseBytesDifferFromTheArchivedReceiptIsNotReplacedByTheHealthyCatalogue() = runBlocking {
        fixture { server ->
            source("a-good")
            val legacy = source("protected-unsupported")
            book(legacy)
            CloudReader.migrate(context)
            promote(server, legacy)
            val edited = requireNotNull(appDb.bookSourceDao.getBookSource(legacy.bookSourceUrl)).apply { jsLib = "var ownedLocalEdit = true;" }
            appDb.bookSourceDao.updateCloudSource(edited)
            val calls = server.requests.size
            assertEquals(0, CloudReader.syncCatalogue(context))
            assertEquals(GSON.toJson(edited), GSON.toJson(appDb.bookSourceDao.getBookSource(legacy.bookSourceUrl)))
            assertFalse(server.requests.drop(calls).any { it.first == "/v1/books/content" })
            assertTrue(CloudReader.newSearchParts(sources.mapNotNull { appDb.bookSourceDao.getBookSourcePart(it.bookSourceUrl) })
                .none { it.bookSourceUrl == legacy.bookSourceUrl })
        }
    }

    @Test(timeout = 35_000) fun aReadingChapterMoveDuringTheCloudProofPreventsFullRuleConversion() = runBlocking {
        fixture { server ->
            source("a-good")
            val legacy = source("protected-unsupported")
            val book = book(legacy)
            CloudReader.migrate(context)
            promote(server, legacy)
            val before = GSON.toJson(appDb.bookSourceDao.getBookSource(legacy.bookSourceUrl))
            server.afterContentProof = {
                appDb.bookDao.getBook(book.bookUrl)!!.let { moved ->
                    moved.durChapterIndex = 6; moved.durChapterPos = 52
                    appDb.bookDao.update(moved)
                    appDb.bookChapterDao.insert(chapter(moved).copy(index = 6, url = prefix + "chapter/6"))
                }
            }
            assertEquals(0, CloudReader.syncCatalogue(context))
            assertEquals(before, GSON.toJson(appDb.bookSourceDao.getBookSource(legacy.bookSourceUrl)))
            assertEquals(6, appDb.bookDao.getBook(book.bookUrl)!!.durChapterIndex)
            assertEquals(52, appDb.bookDao.getBook(book.bookUrl)!!.durChapterPos)
            appDb.bookChapterDao.delByBook(book.bookUrl)
        }
    }

    @Test(timeout = 35_000) fun aLocalRuleEditDuringTheCloudProofWinsTheFinalSameIdCompareAndSet() = runBlocking {
        fixture { server ->
            source("a-good")
            val legacy = source("protected-unsupported")
            book(legacy)
            CloudReader.migrate(context)
            promote(server, legacy)
            server.afterContentProof = {
                appDb.bookSourceDao.getBookSource(legacy.bookSourceUrl)!!.let { edited ->
                    edited.header = "{\"User-Agent\":\"Owned local edit\"}"
                    appDb.bookSourceDao.updateCloudSource(edited)
                }
            }
            assertEquals(0, CloudReader.syncCatalogue(context))
            val edited = requireNotNull(appDb.bookSourceDao.getBookSource(legacy.bookSourceUrl))
            assertFalse(CloudReader.isRemote(edited)); assertNotNull(edited.jsLib)
            assertEquals("{\"User-Agent\":\"Owned local edit\"}", edited.header)
        }
    }

    @Test(timeout = 25_000) fun verifiedWebSubscriptionRemainsAStaticWebEntry() = runBlocking {
        fixture { server ->
            val url = "https://owned-web-fixture.invalid/start?category=all"
            val source = RssSource(sourceUrl = "owned-web:$token", sourceName = "Owned web entry", singleUrl = true,
                sortUrl = url, enableJs = true).also { feeds.add(it); appDb.rssSourceDao.insert(it) }
            assertEquals(1, CloudReader.migrate(context).converted)
            val stub = requireNotNull(appDb.rssSourceDao.getByKey(source.sourceUrl))
            assertTrue(CloudReader.isRemote(stub)); assertTrue(stub.singleUrl); assertFalse(stub.enableJs)
            assertEquals(url, stub.sortUrl); assertNull(stub.ruleContent)
            assertFalse(server.requests.any { it.first == "/v1/rss/articles" || it.first == "/v1/rss/content" })
        }
    }

    @Test(timeout = 25_000) fun readableFirstChapterDoesNotPermitMigratingAGatedCurrentChapter() = runBlocking {
        fixture { server ->
            val source = source("a-good")
            val book = book(source)
            val originalSource = GSON.toJson(source)
            val originalBook = GSON.toJson(book)
            server.contentByIndex[7] = "第八章 自寫測試　" + "請先登入後閱讀本章，購買章節後可繼續閱讀。".repeat(8)
            val first = chapter(book).copy(index = 0, url = prefix + "chapter/0")
            val sample = CloudReader.request("/v1/books/content", CloudReader.payload(source.bookSourceUrl,
                "book" to CloudReader.bookPayload(book), "chapter" to GSON.toJsonTree(first)), context = context)
            assertEquals(readableBody, sample.asJsonObject["content"].asString)
            val report = CloudReader.migrate(context)
            assertEquals(1, report.uploaded); assertEquals(0, report.converted)
            assertEquals(0, report.archiveCleaned); assertEquals(1, report.preserved)
            assertEquals(originalSource, GSON.toJson(appDb.bookSourceDao.getBookSource(source.bookSourceUrl)))
            assertEquals(originalBook, GSON.toJson(appDb.bookDao.getBook(book.bookUrl)))
            assertEquals("healthy", server.stored.getValue("book" to source.bookSourceUrl)["status"].asString)
            assertTrue(server.requests.any { it.first == "/v1/books/content" && it.second.getAsJsonObject("chapter")["index"].asInt == 7 })
        }
    }

    private inner class Gateway : NanoHTTPD("127.0.0.1", 0) {
        val stored = ConcurrentHashMap<Pair<String, String>, JsonObject>()
        val savedJson = ConcurrentHashMap<Pair<String, String>, String>()
        val promotedIds = ConcurrentHashMap.newKeySet<String>()
        val contentByIndex = ConcurrentHashMap<Int, String>()
        val hiddenIds = ConcurrentHashMap.newKeySet<String>()
        val requests = CopyOnWriteArrayList<Pair<String, JsonObject>>()
        var mismatchId: String? = null
        var contentDelayMillis = 0L
        var afterContentProof: (() -> Unit)? = null
        override fun serve(session: IHTTPSession): Response {
            if (session.headers["authorization"] != "Bearer $token") return newFixedLengthResponse(Response.Status.UNAUTHORIZED, "application/json", "{}")
            val files = hashMapOf<String, String>()
            if (session.method == Method.POST) session.parseBody(files)
            val body = files["postData"]?.let { JsonParser.parseString(it).asJsonObject } ?: JsonObject()
            requests.add(session.uri to body.deepCopy())
            val data = when (session.uri) {
                "/v1/status" -> JsonObject().apply { addProperty("contentContractVersion", CloudContentCache.CONTRACT) }
                "/v1/sources" -> {
                    assertEquals("true", session.parms["active"])
                    val kind = session.parms.getValue("kind")
                    val metadata = JsonArray()
                    val list = if (kind == "book") sources.map { it.bookSourceUrl to it.bookSourceName } else feeds.map { it.sourceUrl to it.sourceName }
                    for ((id, name) in list) if (id !in hiddenIds && ((!id.contains("unsupported") && !id.contains("unverified")) || id in promotedIds)) metadata.add(JsonObject().apply {
                        addProperty("id", id); addProperty("name", name); addProperty("supported", true); addProperty("status", "healthy")
                        addProperty("enabled", true); addProperty("enabledExplore", true)
                        val original = if (kind == "book") sources.first { it.bookSourceUrl == id } else feeds.first { it.sourceUrl == id }
                        addProperty("sha256", CloudMigrationPolicy.sha256(GSON.toJson(original)))
                        if (original is RssSource && original.singleUrl) {
                            add("capabilities", JsonArray().apply { add("web") }); addProperty("webUrl", original.sortUrl)
                        }
                    })
                    JsonObject().apply { add("sources", metadata) }
                }
                "/v1/import" -> {
                    val kind = body["kind"].asString
                    val receipts = JsonArray()
                    for (element in body.getAsJsonArray("sources")) {
                        val entry = element.asJsonObject
                        assertEquals(CloudMigrationPolicy.sha256(entry["json"].asString), entry["sha256"].asString)
                        val id = entry["id"].asString
                        val receipt = JsonObject().apply {
                            addProperty("id", id); addProperty("sha256", if (id == mismatchId) "wrong" else entry["sha256"].asString)
                            addProperty("stored", true); addProperty("supported", !id.contains("unsupported"))
                            addProperty("status", if (id.contains("unsupported")) "unsupported" else "pending")
                            val source = JsonParser.parseString(entry["json"].asString).asJsonObject
                            if (kind == "rss" && source.get("singleUrl")?.asBoolean == true) {
                                add("capabilities", JsonArray().apply { add("web") }); add("webUrl", source["sortUrl"])
                            }
                        }
                        stored[kind to id] = receipt
                        savedJson[kind to id] = entry["json"].asString
                        receipts.add(receipt)
                    }
                    JsonObject().apply { add("receipts", receipts) }
                }
                "/v1/source-receipt" -> stored.getValue(session.parms.getValue("kind") to session.parms.getValue("id"))
                "/v1/verify" -> stored.getValue(body["kind"].asString to body["sourceId"].asString).apply { addProperty("status", "healthy") }
                "/v1/books/search", "/v1/books/explore" -> JsonObject().apply {
                    add("books", JsonArray().apply { for (name in listOf("Owned first", "Owned second")) add(JsonObject().apply {
                        addProperty("name", name); addProperty("author", "Owned author"); addProperty("bookUrl", prefix + name.substringAfter(' '))
                    }) })
                }
                "/v1/books/info" -> JsonObject().apply { add("book", JsonObject().apply {
                    addProperty("name", "Renamed cloud title"); addProperty("author", "Owned author")
                    addProperty("tocUrl", prefix + "toc"); addProperty("variable", "{\"session\":\"new\"}")
                    addProperty("durChapterPos", 0)
                }) }
                "/v1/books/toc" -> JsonObject().apply {
                    addProperty("complete", true); addProperty("contractVersion", CloudContentCache.CONTRACT)
                    add("sourceId", body["sourceId"]); add("bookUrl", body.getAsJsonObject("book")["bookUrl"])
                    add("book", body.getAsJsonObject("book")); add("chapters", JsonArray().apply {
                        for (index in 0..7) add(GSON.toJsonTree(BookChapter(bookUrl = prefix + "book", url = if (index == 7) prefix + "chapter/7?edition=1#part" else prefix + "chapter/$index",
                            title = "Owned chapter $index", index = index)))
                    })
                }
                "/v1/books/content" -> {
                    if (contentDelayMillis > 0) Thread.sleep(contentDelayMillis)
                    val after = afterContentProof; afterContentProof = null; after?.invoke()
                    val text = contentByIndex[body.getAsJsonObject("chapter")["index"].asInt] ?: readableBody
                    JsonObject().apply {
                        addProperty("content", text); addProperty("complete", true); addProperty("contractVersion", CloudContentCache.CONTRACT)
                        add("sourceId", body["sourceId"]); add("bookUrl", body.getAsJsonObject("book")["bookUrl"])
                        add("chapterUrl", body.getAsJsonObject("chapter")["url"])
                        addProperty("bodySha256", CloudMigrationPolicy.sha256(text))
                    }
                }
                "/v1/rss/articles" -> JsonObject().apply {
                    add("articles", JsonArray().apply { add(GSON.toJsonTree(RssArticle(origin = "ignored-wrong-origin", link = prefix + "article?id=2&edition=1#fragment", title = "Owned RSS article", description = "Owned RSS body"))) })
                    addProperty("nextUrl", "owned-next-page")
                }
                "/v1/rss/content" -> JsonObject().apply { addProperty("content", body.getAsJsonObject("article")["description"].asString) }
                else -> return newFixedLengthResponse(Response.Status.NOT_FOUND, "application/json", "{}")
            }
            return newFixedLengthResponse(Response.Status.OK, "application/json", GSON.toJson(JsonObject().apply { add("data", data) }))
        }
    }
}
