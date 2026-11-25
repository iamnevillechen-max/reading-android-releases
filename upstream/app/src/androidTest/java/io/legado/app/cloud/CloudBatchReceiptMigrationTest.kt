package io.legado.app.cloud

import android.content.Context
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.gson.JsonArray
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import fi.iki.elonen.NanoHTTPD
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.RssSource
import io.legado.app.model.ReadBook
import io.legado.app.model.cloud.CloudMigrationPolicy
import io.legado.app.model.cloud.CloudReader
import io.legado.app.utils.GSON
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Self-written loopback fixtures only; refuses any nonempty or physical device. */
@RunWith(AndroidJUnit4::class)
class CloudBatchReceiptMigrationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val token = UUID.randomUUID().toString()
    private val prefix = "https://owned-batch.invalid/$token/"
    private val books = arrayListOf<BookSource>()
    private val feeds = arrayListOf<RssSource>()
    private data class Key(val kind: String, val id: String)
    private data class Rule(val json: String, val hash: String = CloudMigrationPolicy.sha256(json))
    private data class Seen(val path: String, val body: JsonObject)
    private enum class Malformed { NONE, DUPLICATE, UNEXPECTED, MISSING, OMIT_STORED }

    private fun guard() {
        check(context.packageName == "io.multiread.reader.debug" && Build.HARDWARE in setOf("ranchu", "goldfish"))
        check(appDb.bookSourceDao.allCount() == 0 && appDb.rssSourceDao.size == 0 && appDb.bookDao.allBookCount == 0 &&
            appDb.readRecordDao.all.isEmpty() && ReadBook.book == null && !CloudReader.isConfigured(context)) {
            "Batch-receipt fixtures require an empty owned emulator"
        }
    }

    private suspend fun fixture(action: suspend (Gateway) -> Unit) {
        guard()
        val directory = File(context.filesDir, "reader-cloud-migration")
        val originalFiles = directory.listFiles().orEmpty().mapTo(hashSetOf()) { it.name }
        val gateway = Gateway().apply { start(NanoHTTPD.SOCKET_READ_TIMEOUT, false) }
        CloudReader.configure(context, "http://127.0.0.1:${gateway.listeningPort}", token)
        try { action(gateway); assertTrue("The fake gateway rejected an unexpected request", gateway.serverFailures.isEmpty()) }
        finally {
            gateway.release.countDown()
            gateway.stop()
            books.forEach { appDb.bookSourceDao.delete(it.bookSourceUrl) }
            feeds.forEach { appDb.rssSourceDao.delete(it.sourceUrl) }
            books.clear(); feeds.clear()
            check(context.getSharedPreferences("reader_cloud_v1", Context.MODE_PRIVATE).edit().clear().commit())
            directory.listFiles().orEmpty().filter { it.name !in originalFiles }.forEach {
                check(it.isFile && it.canonicalFile.parentFile == directory.canonicalFile)
                it.delete()
            }
        }
    }

    private fun book(index: Int, id: String = prefix + "book/" + index.toString().padStart(4, '0'), payload: Int = 0) =
        BookSource(bookSourceUrl = id, bookSourceName = "Owned batch book $index", customOrder = index,
            enabled = true, enabledExplore = true, searchUrl = prefix + "search/{{key}}",
            exploreUrl = "Owned category::${prefix}explore", jsLib = "Self-written archived rule." + "x".repeat(payload))
            .also { books.add(it); appDb.bookSourceDao.insert(it) }

    private fun feed(index: Int, id: String = prefix + "rss/$index") = RssSource(
        sourceUrl = id, sourceName = "Owned batch RSS $index", customOrder = index, enabled = true,
        ruleArticles = "article", ruleTitle = "h1@text", ruleContent = "main@html")
        .also { feeds.add(it); appDb.rssSourceDao.insert(it) }

    private fun originals(): Map<Key, Rule> = buildMap {
        books.forEach { source -> put(Key("book", source.bookSourceUrl), Rule(GSON.toJson(requireNotNull(appDb.bookSourceDao.getBookSource(source.bookSourceUrl))))) }
        feeds.forEach { source -> put(Key("rss", source.sourceUrl), Rule(GSON.toJson(requireNotNull(appDb.rssSourceDao.getByKey(source.sourceUrl))))) }
    }

    private fun assertUnchanged(before: Map<Key, Rule>) {
        assertEquals("Every original rule and metadata field must remain exact", before.mapValues { it.value.hash }, originals().mapValues { it.value.hash })
        assertEquals(0, appDb.bookDao.allBookCount); assertTrue(appDb.readRecordDao.all.isEmpty())
    }

    @Test(timeout = 60_000) fun allPrearchivedRulesUseOnlyBoundedIdsAndPreserveOpaqueBookAndRssIdentities() = runBlocking {
        fixture { gateway ->
            repeat(121) { book(it) }
            book(121, ""); book(122, prefix + "opaque/\u0000\n")
            feed(0); feed(1, ""); feed(2, prefix + "rss/\tcontrol")
            val before = originals()
            gateway.stored.putAll(before)
            val report = CloudReader.migrate(context)
            assertEquals(before.size, report.uploaded); assertEquals(before.size, report.archiveCleaned)
            assertEquals(0, report.converted); assertEquals(0, report.skipped)
            assertTrue("Already archived source bytes must never be reposted", gateway.requests.none { it.path == "/v1/import" })
            val batches = gateway.requests.filter { it.path == "/v1/source-receipts" }
            assertTrue(batches.any { it.body.getAsJsonArray("ids").size() == 100 })
            val ids = batches.flatMap { request ->
                assertEquals(setOf("kind", "ids"), request.body.keySet())
                val kind = request.body["kind"].asString
                val values = request.body.getAsJsonArray("ids")
                assertTrue(values.size() in 1..100)
                val keys = values.map { Key(kind, it.asString) }
                assertTrue(keys.sumOf { before.getValue(it).json.toByteArray(Charsets.UTF_8).size } <= 1024 * 1024)
                keys
            }
            assertEquals(before.keys, ids.toSet()); assertEquals(before.size, ids.size)
            assertEquals(books.size, appDb.bookSourceDao.allCount()); assertEquals(feeds.size, appDb.rssSourceDao.size)
            books.forEach { original ->
                val current = requireNotNull(appDb.bookSourceDao.getBookSource(original.bookSourceUrl))
                assertEquals(original.bookSourceUrl, current.bookSourceUrl)
                assertTrue(CloudReader.isRemote(current)); assertFalse(current.enabled); assertFalse(current.enabledExplore)
                assertNull(current.jsLib)
            }
            feeds.forEach { original ->
                val current = requireNotNull(appDb.rssSourceDao.getByKey(original.sourceUrl))
                assertEquals(original.sourceUrl, current.sourceUrl)
                assertTrue(CloudReader.isRemote(current)); assertFalse(current.enabled)
                assertNull(current.jsLib)
            }
        }
    }

    @Test(timeout = 60_000) fun mixedCurrentMissingAndOldValidHashesUploadOnlyNeededRulesWithinRawBounds() = runBlocking {
        fixture { gateway ->
            repeat(100) { book(it, payload = if (it >= 60) 10_000 else 0) }
            val before = originals()
            val ordered = books.map { Key("book", it.bookSourceUrl) }
            ordered.take(20).forEach { gateway.stored[it] = before.getValue(it) }
            ordered.drop(60).forEach { key ->
                val old = JsonParser.parseString(before.getValue(key).json).asJsonObject.apply { addProperty("bookSourceComment", "Older self-written remote revision") }
                gateway.stored[key] = Rule(GSON.toJson(old))
            }
            val needed = ordered.drop(20).toSet()
            CloudReader.migrate(context)
            val uploads = gateway.requests.filter { it.path == "/v1/import" }
            val uploaded = uploads.flatMap { request ->
                val rows = request.body.getAsJsonArray("sources")
                assertTrue(rows.size() in 1..50)
                assertTrue(rows.sumOf { it.asJsonObject["json"].asString.toByteArray(Charsets.UTF_8).size } <= 256 * 1024)
                rows.map { row -> Key(request.body["kind"].asString, row.asJsonObject["id"].asString) }
            }
            assertEquals(needed, uploaded.toSet()); assertEquals(needed.size, uploaded.size)
            assertTrue("The fixture exercises the 50-source raw batch bound", uploads.any { it.body.getAsJsonArray("sources").size() == 50 })
            assertTrue("Large changed rules exercise the byte bound too", uploads.size >= 3)
            before.forEach { (key, rule) -> assertEquals(rule, gateway.stored[key]) }
        }
    }

    @Test(timeout = 60_000) fun malformedBatchedMetadataStopsBeforeImportOrAnyLocalRuleChange() = runBlocking {
        for (mode in listOf(Malformed.DUPLICATE, Malformed.UNEXPECTED, Malformed.MISSING, Malformed.OMIT_STORED)) {
            fixture { gateway ->
                book(0); book(1)
                val before = originals(); gateway.stored.putAll(before); gateway.malformed = mode
                var rejected = false
                try { CloudReader.migrate(context) } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { rejected = true }
                assertTrue("Advertised batched metadata must fail closed: $mode", rejected)
                assertUnchanged(before)
                assertTrue(gateway.requests.none { it.path == "/v1/import" })
                assertEquals(1, gateway.requests.count { it.path == "/v1/source-receipts" })
            }
        }
    }

    @Test(timeout = 35_000) fun aWrongRawImportHashAbortsWithoutConvertingEvenAlreadyMatchedRules() = runBlocking {
        fixture { gateway ->
            book(0); book(1)
            val before = originals()
            gateway.stored[before.keys.first()] = before.values.first()
            gateway.mismatchImportAck = true
            var rejected = false
            try { CloudReader.migrate(context) } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { rejected = true }
            assertTrue("A valid-format but wrong raw hash must reject the migration", rejected)
            assertEquals(1, gateway.requests.count { it.path == "/v1/import" })
            assertUnchanged(before)
        }
    }

    @Test(timeout = 45_000) fun cancellationDuringMetadataOrAfterRawBodyBeforeAckPreservesEveryOriginalWithoutRetry() = runBlocking {
        for (stage in listOf("/v1/source-receipts", "/v1/import")) {
            fixture { gateway ->
                book(0); book(1)
                val before = originals()
                if (stage == "/v1/source-receipts") gateway.stored.putAll(before)
                gateway.pauseAt = stage
                val job = launch(Dispatchers.IO) { CloudReader.migrate(context) }
                try {
                    assertTrue("The request must reach the requested cancellation point", withContext(Dispatchers.IO) { gateway.entered.await(5, TimeUnit.SECONDS) })
                    assertUnchanged(before)
                    job.cancel(); gateway.release.countDown()
                    withTimeout(5_000) { job.join() }
                    assertTrue(job.isCancelled)
                    delay(100)
                    assertEquals("Cancellation must not retry the interrupted stage", 1, gateway.requests.count { it.path == stage })
                    if (stage == "/v1/import") before.forEach { (key, value) -> assertEquals("The server read raw bytes before the cancelled ACK", value, gateway.stored[key]) }
                    else assertTrue(gateway.requests.none { it.path == "/v1/import" })
                    assertUnchanged(before)
                } finally { gateway.release.countDown(); job.cancelAndJoin() }
            }
        }
    }

    private inner class Gateway : NanoHTTPD("127.0.0.1", 0) {
        val stored = ConcurrentHashMap<Key, Rule>()
        val requests = CopyOnWriteArrayList<Seen>()
        val serverFailures = CopyOnWriteArrayList<String>()
        var malformed = Malformed.NONE
        var mismatchImportAck = false
        var pauseAt: String? = null
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)

        private fun receipt(kind: String, id: String): JsonObject = JsonObject().apply {
            addProperty("id", id)
            val rule = stored[Key(kind, id)]
            addProperty("stored", rule != null)
            if (rule != null) {
                addProperty("sha256", rule.hash); addProperty("supported", false); addProperty("status", "unsupported")
                add("capabilities", JsonArray()); add("webUrl", JsonNull.INSTANCE)
            }
        }

        override fun serve(session: IHTTPSession): Response {
            if (session.headers["authorization"] != "Bearer $token") return newFixedLengthResponse(Response.Status.UNAUTHORIZED, "application/json", "{}")
            return try {
                val files = hashMapOf<String, String>()
                if (session.method == Method.POST) session.parseBody(files)
                val body = files["postData"]?.let { JsonParser.parseString(it).asJsonObject } ?: JsonObject()
                requests.add(Seen(session.uri, body.deepCopy()))
                val data = when (session.uri) {
                    "/v1/status" -> JsonObject().apply { add("features", JsonObject().apply { addProperty("batchedSourceReceipts", true) }) }
                    "/v1/sources" -> JsonObject().apply { add("sources", JsonArray()); add("nextCursor", JsonNull.INSTANCE) }
                    "/v1/source-receipts" -> {
                        check(body.keySet() == setOf("kind", "ids"))
                        val kind = body["kind"].asString
                        val ids = body.getAsJsonArray("ids"); check(ids.size() in 1..100)
                        val receipts = JsonArray().apply { ids.forEach { add(receipt(kind, it.asString)) } }
                        when (malformed) {
                            Malformed.DUPLICATE -> receipts.set(receipts.size() - 1, receipts[0].deepCopy())
                            Malformed.UNEXPECTED -> receipts[receipts.size() - 1].asJsonObject.addProperty("id", prefix + "unexpected")
                            Malformed.MISSING -> receipts.remove(receipts.size() - 1)
                            Malformed.OMIT_STORED -> receipts[0].asJsonObject.remove("stored")
                            Malformed.NONE -> Unit
                        }
                        JsonObject().apply { add("receipts", receipts) }
                    }
                    "/v1/import" -> {
                        val kind = body["kind"].asString
                        val rows = body.getAsJsonArray("sources"); check(rows.size() in 1..50)
                        check(rows.sumOf { it.asJsonObject["json"].asString.toByteArray(Charsets.UTF_8).size } <= 256 * 1024)
                        val receipts = JsonArray()
                        for (element in rows) {
                            val row = element.asJsonObject; val id = row["id"].asString
                            val raw = row["json"].asString
                            check(CloudMigrationPolicy.sha256(raw) == row["sha256"].asString)
                            stored[Key(kind, id)] = Rule(raw)
                            receipts.add(receipt(kind, id).apply { if (mismatchImportAck) addProperty("sha256", "f".repeat(64)) })
                        }
                        JsonObject().apply { add("receipts", receipts) }
                    }
                    "/v1/source-receipt" -> receipt(requireNotNull(session.parameters["kind"]?.firstOrNull()), requireNotNull(session.parameters["id"]?.firstOrNull()))
                    else -> error("Unexpected fixture endpoint")
                }
                if (session.uri == pauseAt) { entered.countDown(); check(release.await(10, TimeUnit.SECONDS)) }
                newFixedLengthResponse(Response.Status.OK, "application/json", GSON.toJson(JsonObject().apply { add("data", data) }))
            } catch (error: Throwable) {
                serverFailures.add(error.javaClass.simpleName)
                newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "application/json", "{}")
            }
        }
    }
}
