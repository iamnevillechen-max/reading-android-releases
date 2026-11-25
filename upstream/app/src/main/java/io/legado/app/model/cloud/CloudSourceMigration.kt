package io.legado.app.model.cloud

import android.content.Context
import android.os.SystemClock
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.RssSource
import io.legado.app.utils.GSON
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID
import java.net.URI

/** Archive -> verify stored bytes -> verify readable chain -> atomically replace rules by stubs. */
object CloudSourceMigration {
    data class Progress(val kind: String, val processed: Int, val total: Int, val converted: Int,
                        val archived: Int, val message: String, val verified: Int = 0,
                        val archiveCleaned: Int = 0, val preserved: Int = 0)
    data class Report(val uploaded: Int, val converted: Int, val archived: Int, val skipped: Int,
                      val archiveCleaned: Int = 0, val preserved: Int = archived)
    private val lock = Mutex()
    private const val ARCHIVE_PREFIX = "雲端存檔（未核驗） SHA-256 "
    private data class Entry(val kind: String, val id: String, val json: String, val sha256: String)
    private data class References(val books: Set<String>, val rss: Set<String>, val complete: Boolean) {
        fun protects(kind: String, id: String) = !complete || id in if (kind == "book") books else rss
    }

    private fun json(entry: Entry) = JsonObject().apply {
        addProperty("kind", entry.kind); addProperty("id", entry.id)
        addProperty("json", entry.json); addProperty("sha256", entry.sha256)
    }
    private fun parse(line: String): Entry = JsonParser.parseString(line).asJsonObject.let {
        Entry(it["kind"].asString, it["id"].asString, it["json"].asString, it["sha256"].asString)
    }
    private fun sourceEntry(kind: String, id: String, source: Any): Entry {
        val data = GSON.toJson(source)
        return Entry(kind, id, data, CloudMigrationPolicy.sha256(data))
    }

    private data class Acknowledgements(val entries: Map<String, JsonObject>, val metadataExactIds: Set<String>)

    /** Validate the complete ID set before using any receipt, including opaque/empty IDs. */
    private fun receiptSet(batch: List<Entry>, data: JsonObject, metadata: Boolean): Map<String, JsonObject> {
        val array = data.get("receipts")
        require(array != null && array.isJsonArray && array.asJsonArray.size() == batch.size) { "雲端批次回執數量不完整，原資料已保留" }
        val expected = batch.mapTo(hashSetOf()) { it.id }
        val result = linkedMapOf<String, JsonObject>()
        for (element in array.asJsonArray) {
            require(element.isJsonObject) { "雲端批次回執格式不完整，原資料已保留" }
            val value = element.asJsonObject
            val id = value.get("id")
            require(id != null && id.isJsonPrimitive && id.asJsonPrimitive.isString) { "雲端批次回執識別不完整，原資料已保留" }
            val key = id.asString
            require(key in expected && key !in result) { "雲端批次回執重複或含非預期來源，原資料已保留" }
            val stored = value.get("stored")
            require(stored != null && stored.isJsonPrimitive && stored.asJsonPrimitive.isBoolean) { "雲端存檔狀態不完整，原資料已保留" }
            require(metadata || stored.asBoolean) { "雲端尚未完整存檔，原資料已保留" }
            if (stored.asBoolean) {
                val hash = value.get("sha256")
                require(hash != null && hash.isJsonPrimitive && hash.asJsonPrimitive.isString &&
                    hash.asString.matches(Regex("[0-9a-fA-F]{64}"))) { "雲端存檔雜湊格式不完整，原資料已保留" }
                val supported = value.get("supported")
                require(supported != null && supported.isJsonPrimitive && supported.asJsonPrimitive.isBoolean) { "雲端來源核驗狀態不完整，原資料已保留" }
                if (metadata) {
                    val status = value.get("status")
                    require(status != null && status.isJsonPrimitive && status.asJsonPrimitive.isString && status.asString.isNotBlank()) { "雲端來源狀態不完整，原資料已保留" }
                }
            }
            result[key] = value
        }
        require(result.keys == expected) { "雲端批次回執缺少來源，原資料已保留" }
        return result
    }

    private suspend fun importEntries(entries: List<Entry>, context: Context): Map<String, JsonObject> {
        val acknowledgements = linkedMapOf<String, JsonObject>()
        var start = 0
        while (start < entries.size) {
            currentCoroutineContext().ensureActive()
            var end = start + 1
            var bytes = entries[start].json.toByteArray(Charsets.UTF_8).size
            while (end < entries.size && end - start < 50) {
                val size = entries[end].json.toByteArray(Charsets.UTF_8).size
                if (bytes + size > 256 * 1024) break
                bytes += size; end++
            }
            val batch = entries.subList(start, end)
            val body = JsonObject().apply {
                addProperty("kind", batch.first().kind)
                add("sources", JsonArray().apply { batch.forEach { add(json(it).apply { remove("kind") }) } })
            }
            val replies = receiptSet(batch, CloudReader.request("/v1/import", body, context = context).asJsonObject, metadata = false)
            batch.forEach { verifyReceipt(it, replies.getValue(it.id)) }
            acknowledgements.putAll(replies)
            start = end
        }
        return acknowledgements
    }

    private suspend fun acknowledge(batch: List<Entry>, metadata: Boolean, context: Context): Acknowledgements {
        if (!metadata) return Acknowledgements(importEntries(batch, context), emptySet())
        val body = JsonObject().apply {
            addProperty("kind", batch.first().kind)
            add("ids", JsonArray().apply { batch.forEach { add(it.id) } })
        }
        val replies = receiptSet(batch, CloudReader.request("/v1/source-receipts", body, context = context).asJsonObject, metadata = true)
        val exact = batch.filter { entry -> replies.getValue(entry.id).let { it["stored"].asBoolean && it["sha256"].asString == entry.sha256 } }
            .mapTo(hashSetOf()) { it.id }
        val missing = batch.filter { it.id !in exact }
        val final = replies.toMutableMap()
        if (missing.isNotEmpty()) final.putAll(importEntries(missing, context))
        // No caller plans a local rule change until all rows have exact durable receipts.
        batch.forEach { verifyReceipt(it, final.getValue(it.id)) }
        return Acknowledgements(final, exact)
    }

    suspend fun migrate(context: Context, onProgress: (Progress) -> Unit = {}): Report = withContext(Dispatchers.IO) {
        lock.withLock {
            check(CloudReader.isEnabled(context)) { CloudReader.LOCAL_SOURCE_REQUIRED }
            val owner = currentCoroutineContext()
            val configuration = CloudReader.configurationRevision(context)
            var lastProgress = 0L
            fun progress(value: Progress) {
                val now = SystemClock.elapsedRealtime()
                if (lastProgress == 0L || now - lastProgress >= 100 || value.processed == value.total || value.kind == "done") {
                    lastProgress = now; onProgress(value)
                }
            }
            val total = appDb.bookSourceDao.allCount() + appDb.rssSourceDao.size
            val directory = File(context.filesDir, "reader-cloud-migration").apply { mkdirs() }
            val operation = UUID.randomUUID().toString()
            val temporary = File(directory, "$operation.backup.pending")
            val backup = File(directory, "$operation.backup.jsonl")
            var captured = 0
            // A durable private copy precedes every upload and every source-rule mutation.
            FileOutputStream(temporary).use { stream ->
                val writer = stream.bufferedWriter(Charsets.UTF_8)
                suspend fun capture(entry: Entry) {
                    owner.ensureActive()
                    writer.write(json(entry).toString()); writer.newLine(); captured++
                    progress(Progress("backup", captured, total, 0, 0, "正在保存書源原始規則"))
                }
                var bookCursor: String? = null
                while (true) {
                    val page = appDb.bookSourceDao.cloudMigrationPage(bookCursor, 16)
                    if (page.isEmpty()) break
                    for (source in page) if (!CloudReader.isRemote(source)) capture(sourceEntry("book", source.bookSourceUrl, source))
                    bookCursor = page.last().bookSourceUrl
                }
                var rssCursor: String? = null
                while (true) {
                    val page = appDb.rssSourceDao.cloudMigrationPage(rssCursor, 16)
                    if (page.isEmpty()) break
                    for (source in page) if (!CloudReader.isRemote(source)) capture(sourceEntry("rss", source.sourceUrl, source))
                    rssCursor = page.last().sourceUrl
                }
                writer.flush(); stream.fd.sync()
            }
            check(temporary.renameTo(backup)) { "無法保存書源回復檔，未修改原資料" }
            val plan = File(directory, "$operation.plan.jsonl")
            val references = appDb.bookDao.webBooks.groupBy { it.origin }
            val protection = protectedOrigins(context)
            val metadataReceipts = CloudReader.supportsBatchedSourceReceipts(context)
            val knownHealthy = healthySourceIds(context)
            var uploaded = 0
            var eligible = 0
            // Bounded upload batches avoid two network round trips for every archived rule.
            plan.bufferedWriter().use { writer ->
                backup.bufferedReader().useLines { lines ->
                    val iterator = lines.iterator()
                    var pending: Entry? = null
                    while (pending != null || iterator.hasNext()) {
                        owner.ensureActive()
                        check(configuration == CloudReader.configurationRevision(context)) { "伺服器設定在遷移期間已變更，原資料已保留" }
                        val first = pending ?: parse(iterator.next())
                        pending = null
                        val batch = arrayListOf(first)
                        var bytes = first.json.toByteArray(Charsets.UTF_8).size
                        val batchLimit = if (metadataReceipts) 100 else 50
                        val byteLimit = if (metadataReceipts) 1024 * 1024 else 256 * 1024
                        while (batch.size < batchLimit && iterator.hasNext()) {
                            val next = parse(iterator.next())
                            val nextSize = next.json.toByteArray(Charsets.UTF_8).size
                            if (next.kind != first.kind || bytes + nextSize > byteLimit) {
                                pending = next; break
                            }
                            batch.add(next); bytes += nextSize
                        }
                        val acknowledgements = acknowledge(batch, metadataReceipts, context)
                        for (entry in batch) {
                            owner.ensureActive()
                            val ack = acknowledgements.entries.getValue(entry.id)
                            verifyReceipt(entry, ack)
                            uploaded++
                            val protected = protection.protects(entry.kind, entry.id)
                            var active = false
                            var validatedWebUrl: String? = null
                            // Probe known working sites and actual shelf/feed use. Search and
                            // health-only references keep their rules without a bulk site scan.
                            val needsShelfPilot = entry.kind == "book" && !references[entry.id].isNullOrEmpty()
                            val needsFeedPilot = entry.kind == "rss" && entry.id in protection.rss
                            if (ack.get("supported")?.asBoolean == true &&
                                (needsShelfPilot || needsFeedPilot || (entry.kind to entry.id) in knownHealthy)) {
                                var receipt = if (entry.id in acknowledgements.metadataExactIds) ack else
                                    CloudReader.request("/v1/source-receipt", query = mapOf("kind" to entry.kind, "id" to entry.id), context = context).asJsonObject
                                verifyReceipt(entry, receipt)
                                if (receipt.get("supported")?.asBoolean == true && receipt.get("status")?.asString != "healthy") {
                                    try {
                                        val pilot = references[entry.id]?.maxByOrNull { it.durChapterTime }
                                        CloudReader.request("/v1/verify", CloudReader.payload(entry.id, "kind" to entry.kind).apply {
                                            if (entry.kind == "book" && pilot != null) {
                                                addProperty("bookUrl", pilot.bookUrl); addProperty("key", pilot.name)
                                            }
                                        }, context = context)
                                        receipt = CloudReader.request("/v1/source-receipt", query = mapOf("kind" to entry.kind, "id" to entry.id), context = context).asJsonObject
                                        verifyReceipt(entry, receipt)
                                    } catch (cancelled: CancellationException) { throw cancelled
                                    } catch (_: IOException) { /* Failed sites retain their original local rules. */ }
                                }
                                if (CloudMigrationPolicy.canConvert(receipt.get("supported")?.asBoolean == true, receipt.get("status")?.asString.orEmpty()) &&
                                    verifyReferencedBooks(entry, references[entry.id].orEmpty(), context)) {
                                    active = true; eligible++
                                    validatedWebUrl = webUrl(receipt)
                                }
                            }
                            if (active || !protected) {
                                writer.write(JsonObject().apply {
                                    addProperty("kind", entry.kind); addProperty("id", entry.id); addProperty("sha256", entry.sha256)
                                    addProperty("mode", if (active) "active" else "archive")
                                    validatedWebUrl?.let { addProperty("webUrl", it) }
                                }.toString()); writer.newLine()
                            }
                            progress(Progress(entry.kind, uploaded, captured, 0, uploaded - eligible, "已保存並核對書源，正在驗證可讀性", eligible))
                        }
                    }
                }
            }
            owner.ensureActive()
            check(configuration == CloudReader.configurationRevision(context)) { "伺服器設定在遷移期間已變更，原資料已保留" }
            var converted = 0
            var archiveCleaned = 0
            val activeBookIds = hashSetOf<String>()
            // No deletes or REPLACE: all source IDs and every dependent row survive.
            appDb.runInTransaction {
                // New shelf/search/feed references added during upload must win over cleanup.
                val currentProtection = protectedOrigins(context)
                plan.bufferedReader().useLines { lines ->
                    for (line in lines) {
                        owner.ensureActive()
                        val record = JsonParser.parseString(line).asJsonObject
                        val id = record["id"].asString
                        val expected = record["sha256"].asString
                        val kind = record["kind"].asString
                        val archive = record["mode"].asString == "archive"
                        if (archive && currentProtection.protects(kind, id)) continue
                        when (kind) {
                            "book" -> {
                                val current = appDb.bookSourceDao.getBookSource(id) ?: error("書源在遷移期間已變更，原資料已保留")
                                check(CloudMigrationPolicy.sha256(GSON.toJson(current)) == expected) { "書源在遷移期間已變更，原資料已保留" }
                                val next = bookStub(current, expected).apply {
                                    if (archive) { enabled = false; enabledExplore = false; exploreUrl = null; bookSourceComment = ARCHIVE_PREFIX + expected }
                                }
                                check(appDb.bookSourceDao.updateCloudSource(next) == 1)
                            }
                            "rss" -> {
                                val current = appDb.rssSourceDao.getByKey(id) ?: error("訂閱源在遷移期間已變更，原資料已保留")
                                check(CloudMigrationPolicy.sha256(GSON.toJson(current)) == expected) { "訂閱源在遷移期間已變更，原資料已保留" }
                                val next = rssStub(current, expected, record.get("webUrl")?.asString).apply {
                                    if (archive) { enabled = false; sortUrl = null; sourceComment = ARCHIVE_PREFIX + expected }
                                }
                                check(appDb.rssSourceDao.updateCloudSource(next) == 1)
                            }
                            else -> error("遷移回復資料不完整")
                        }
                        if (archive) archiveCleaned++ else {
                            converted++
                            if (kind == "book") activeBookIds.add(id)
                        }
                    }
                }
            }
            if (converted > 0) {
                CloudReader.approveMigrationSources(context, configuration, activeBookIds)
                CloudReader.markVerified(context)
            }
            val preserved = uploaded - converted - archiveCleaned
            progress(Progress("done", uploaded, captured, converted, uploaded - converted, "書源存檔與可讀性核對完成",
                archiveCleaned = archiveCleaned, preserved = preserved))
            Report(uploaded, converted, uploaded - converted, total - captured, archiveCleaned, preserved)
        }
    }

    private fun verifyReceipt(entry: Entry, receipt: JsonObject) = CloudMigrationPolicy.requireReceipt(entry.id, entry.sha256,
        receipt.get("id")?.asString.orEmpty(), receipt.get("sha256")?.asString.orEmpty(), receipt.get("stored")?.asBoolean == true)

    private fun webUrl(metadata: JsonObject): String? {
        if (metadata.getAsJsonArray("capabilities")?.any { it.asString == "web" } != true) return null
        val url = metadata.get("webUrl")?.takeUnless { it.isJsonNull }?.asString ?: error("雲端網頁入口尚未核驗")
        val uri = URI(url)
        check(uri.scheme in setOf("http", "https") && !uri.host.isNullOrBlank() && uri.userInfo == null) { "雲端網頁入口格式不正確" }
        return url
    }

    /** Immutable import acknowledgements protect bytes; these IDs additionally protect use. */
    private fun protectedOrigins(context: Context): References {
        val books = appDb.bookSourceDao.cloudProtectedOrigins().toMutableSet()
        val rss = appDb.rssSourceDao.cloudProtectedOrigins().toSet()
        var complete = true
        var bytes = 0L
        for ((directoryName, relation) in listOf("multisource-v1" to true, "multisource-health-v1" to false)) {
            val directory = File(context.filesDir, directoryName)
            if (!directory.exists()) continue
            val files = directory.listFiles()?.filter { it.extension == "json" && (!relation || it.name.startsWith("relation-")) }
            if (files == null || files.size > 2_000) { complete = false; continue }
            for (file in files) try {
                bytes += file.length()
                check(file.isFile && file.canonicalFile.parentFile == directory.canonicalFile && file.length() <= 1_000_000 && bytes <= 16_000_000)
                val data = JsonParser.parseString(file.readText()).asJsonObject
                if (relation) {
                    books.add(data.getAsJsonObject("book")["origin"].asString)
                } else {
                    check(data.get("version")?.asInt == 1)
                    data.getAsJsonArray("sources").forEach { books.add(it.asJsonArray[0].asString) }
                    data.get("cursor")?.takeUnless { it.isJsonNull }?.asString?.let { books.add(it) }
                    data.getAsJsonArray("knownThrough")?.get(2)?.asString?.let { books.add(it) }
                }
            } catch (_: Exception) { complete = false }
        }
        return References(books, rss, complete)
    }

    private suspend fun healthySourceIds(context: Context): Set<Pair<String, String>> {
        val result = hashSetOf<Pair<String, String>>()
        for (kind in listOf("book", "rss")) {
            var cursor: String? = null
            val cursors = hashSetOf<String>()
            do {
                val query = mutableMapOf("kind" to kind, "active" to "true").apply { cursor?.let { put("cursor", it) } }
                val data = CloudReader.request("/v1/sources", query = query, context = context).asJsonObject
                for (element in data.getAsJsonArray("sources") ?: JsonArray()) {
                    val source = element.asJsonObject
                    if (source.get("status")?.asString == "healthy" && source.get("supported")?.asBoolean == true) result.add(kind to source["id"].asString)
                }
                cursor = data.get("nextCursor")?.takeUnless { it.isJsonNull }?.asString?.takeIf { it.isNotBlank() }
                check(cursor == null || cursors.add(cursor)) { "雲端書源目錄游標重複" }
            } while (cursor != null)
        }
        return result
    }

    /** Existing shelf positions are never changed while probing their current chapters. */
    private suspend fun verifyReferencedBooks(entry: Entry, books: List<Book>, context: Context): Boolean {
        if (entry.kind != "book") return true
        for (book in books) {
            currentCoroutineContext().ensureActive()
            try {
                val chapter = appDb.bookChapterDao.getChapter(book.bookUrl, book.durChapterIndex) ?: return false
                val data = CloudReader.request("/v1/books/content", CloudReader.payload(entry.id, "book" to CloudReader.bookPayload(book),
                    "chapter" to GSON.toJsonTree(chapter)), context = context).asJsonObject
                if (!CloudMigrationPolicy.canMigrateContent(data.get("content")?.takeUnless { it.isJsonNull }?.asString)) return false
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) { return false }
        }
        return true
    }

    private data class ChapterProof(val index: Int, val bookContext: String, val chapter: String)

    private fun chapterProofs(books: List<Book>): Map<String, ChapterProof>? {
        val result = hashMapOf<String, ChapterProof>()
        for (book in books) {
            val chapter = appDb.bookChapterDao.getChapter(book.bookUrl, book.durChapterIndex) ?: return null
            result[book.bookUrl] = ChapterProof(book.durChapterIndex,
                CloudMigrationPolicy.sha256(GSON.toJson(CloudReader.bookPayload(book))),
                CloudMigrationPolicy.sha256(GSON.toJson(chapter)))
        }
        return result
    }

    /** A later server repair can safely reconnect a previously protected rule. */
    private suspend fun restoreStoredLegacy(context: Context, configuration: String, entry: Entry,
                                            summary: JsonObject, receipt: JsonObject): Boolean {
        val referenced = if (entry.kind == "book") appDb.bookDao.webBooks.filter { it.origin == entry.id } else emptyList()
        val proven = chapterProofs(referenced) ?: return false
        if (!verifyReferencedBooks(entry, referenced, context)) return false
        currentCoroutineContext().ensureActive()
        var restored = false
        appDb.runInTransaction {
            // Recheck both opaque rule bytes and all current shelf references.
            // A new book, a moved chapter, or a local edit after HTTP must win.
            if (configuration != CloudReader.configurationRevision(context) || !protectedOrigins(context).complete) return@runInTransaction
            val currentBooks = if (entry.kind == "book") appDb.bookDao.webBooks.filter { it.origin == entry.id } else emptyList()
            val latest = chapterProofs(currentBooks) ?: return@runInTransaction
            if (latest.any { (id, proof) -> proven[id] != proof }) return@runInTransaction
            val enabled = summary.get("enabled")?.asBoolean != false
            val name = summary.get("name")?.asString ?: entry.id
            if (entry.kind == "book") {
                val current = appDb.bookSourceDao.getBookSource(entry.id) ?: return@runInTransaction
                if (CloudReader.isRemote(current) || sourceEntry("book", entry.id, current).sha256 != entry.sha256) return@runInTransaction
                val explore = current.enabledExplore && summary.get("enabledExplore")?.asBoolean == true
                val next = bookStub(current.copy(bookSourceName = name, enabled = current.enabled && enabled,
                    enabledExplore = explore, exploreUrl = if (explore) "全部::reader-cloud://category/all" else null), entry.sha256)
                restored = appDb.bookSourceDao.updateCloudSource(next) == 1
            } else {
                val current = appDb.rssSourceDao.getByKey(entry.id) ?: return@runInTransaction
                if (CloudReader.isRemote(current) || sourceEntry("rss", entry.id, current).sha256 != entry.sha256) return@runInTransaction
                val next = rssStub(current.copy(sourceName = name, enabled = current.enabled && enabled), entry.sha256, webUrl(receipt))
                restored = appDb.rssSourceDao.updateCloudSource(next) == 1
            }
        }
        return restored
    }

    internal fun bookStub(source: BookSource, sha256: String): BookSource = BookSource(
        bookSourceUrl = source.bookSourceUrl, bookSourceName = source.bookSourceName, bookSourceGroup = source.bookSourceGroup,
        bookSourceType = source.bookSourceType, customOrder = source.customOrder, enabled = source.enabled,
        enabledExplore = source.enabledExplore, lastUpdateTime = source.lastUpdateTime, respondTime = source.respondTime,
        weight = source.weight, searchUrl = CloudMigrationPolicy.STUB,
        exploreUrl = source.exploreUrl?.takeIf { it.isNotBlank() }?.let { "全部::reader-cloud://category/all" },
        bookSourceComment = "雲端規則 SHA-256 $sha256"
    )

    internal fun rssStub(source: RssSource, sha256: String, webUrl: String? = null): RssSource = RssSource(
        sourceUrl = source.sourceUrl, sourceName = source.sourceName, sourceIcon = source.sourceIcon,
        sourceGroup = source.sourceGroup, enabled = source.enabled, customOrder = source.customOrder,
        lastUpdateTime = source.lastUpdateTime, articleStyle = source.articleStyle,
        sourceComment = "雲端規則 SHA-256 $sha256", sortUrl = webUrl ?: "全部::reader-cloud://articles",
        singleUrl = webUrl != null, ruleArticles = CloudMigrationPolicy.STUB,
        ruleContent = if (webUrl != null) null else CloudMigrationPolicy.STUB, enableJs = false
    )

    suspend fun syncCatalogue(context: Context): Int = withContext(Dispatchers.IO) {
        if (!CloudReader.isEnabled(context)) return@withContext 0
        lock.withLock {
            val configuration = CloudReader.configurationRevision(context)
            val approved = mutableMapOf<String, Set<String>>()
            var cursor: String? = null
            val cursors = hashSetOf<String>()
            var changed = 0
            var healthy = 0
            for (kind in listOf("book", "rss")) {
                val ids = hashSetOf<String>()
                cursor = null; cursors.clear()
                do {
                    currentCoroutineContext().ensureActive()
                    val query = mutableMapOf("kind" to kind, "active" to "true").apply { cursor?.let { put("cursor", it) } }
                    val data = CloudReader.request("/v1/sources", query = query, context = context).asJsonObject
                    for (element in data.getAsJsonArray("sources") ?: JsonArray()) {
                        val summary = element.asJsonObject
                        if (summary.get("status")?.asString != "healthy" || summary.get("supported")?.asBoolean != true) continue
                        val id = summary["id"].asString
                        ids.add(id)
                        val name = summary.get("name")?.asString ?: id
                        val hash = summary["sha256"].asString
                        val validatedWebUrl = if (kind == "rss") webUrl(summary) else null
                        val currentBook = if (kind == "book") appDb.bookSourceDao.getBookSource(id) else null
                        val currentRss = if (kind == "rss") appDb.rssSourceDao.getByKey(id) else null
                        val legacy = when {
                            currentBook != null && !CloudReader.isRemote(currentBook) -> sourceEntry("book", id, currentBook)
                            currentRss != null && !CloudReader.isRemote(currentRss) -> sourceEntry("rss", id, currentRss)
                            else -> null
                        }
                        // A modified or not-yet-archived local rule is never
                        // overwritten by a server catalogue with different bytes.
                        if (legacy != null && legacy.sha256 != hash) continue
                        // Already verified immutable rule bytes need neither another receipt
                        // round trip nor a Room invalidation on every metadata refresh.
                        if (currentBook != null && currentBook.bookSourceComment == "雲端規則 SHA-256 $hash" && currentBook.bookSourceName == name) {
                            healthy++; continue
                        }
                        if (currentRss != null && currentRss.sourceComment == "雲端規則 SHA-256 $hash" && currentRss.sourceName == name &&
                            currentRss.singleUrl == (validatedWebUrl != null) && (validatedWebUrl == null || currentRss.sortUrl == validatedWebUrl)) {
                            healthy++; continue
                        }
                        val receipt = CloudReader.request("/v1/source-receipt", query = mapOf("kind" to kind, "id" to id), context = context).asJsonObject
                        CloudMigrationPolicy.requireReceipt(id, summary["sha256"].asString, receipt["id"].asString,
                            receipt["sha256"].asString, receipt.get("stored")?.asBoolean == true)
                        if (!CloudMigrationPolicy.canConvert(receipt.get("supported")?.asBoolean == true, receipt.get("status")?.asString.orEmpty())) continue
                        if (legacy != null) {
                            if (restoreStoredLegacy(context, configuration, legacy, summary, receipt)) { changed++; healthy++ }
                            continue
                        }
                        val enabled = summary.get("enabled")?.asBoolean != false
                        if (kind == "book") {
                            val current = currentBook
                            if (current == null) {
                                val source = BookSource(bookSourceUrl = id, bookSourceName = name, enabled = enabled,
                                    enabledExplore = summary.get("enabledExplore")?.asBoolean == true,
                                    exploreUrl = if (summary.get("enabledExplore")?.asBoolean == true) "全部::reader-cloud://category/all" else null)
                                if (appDb.bookSourceDao.insertCloudNew(bookStub(source, hash)).single() != -1L) changed++
                                healthy++
                            } else if (CloudReader.isRemote(current)) {
                                val restoring = current.bookSourceComment?.startsWith(ARCHIVE_PREFIX) == true
                                val next = bookStub(current.copy(bookSourceName = name,
                                    enabled = if (restoring) enabled else current.enabled,
                                    enabledExplore = if (restoring) summary.get("enabledExplore")?.asBoolean == true else current.enabledExplore,
                                    exploreUrl = if (restoring && summary.get("enabledExplore")?.asBoolean == true) "全部::reader-cloud://category/all" else current.exploreUrl), hash)
                                if (GSON.toJson(current) != GSON.toJson(next)) changed += appDb.bookSourceDao.updateCloudSource(next)
                                healthy++
                            }
                        } else {
                            val current = currentRss
                            if (current == null) {
                                if (appDb.rssSourceDao.insertCloudNew(rssStub(RssSource(sourceUrl = id, sourceName = name, enabled = enabled), hash, validatedWebUrl)).single() != -1L) changed++
                                healthy++
                            } else if (CloudReader.isRemote(current)) {
                                val restoring = current.sourceComment?.startsWith(ARCHIVE_PREFIX) == true
                                val next = rssStub(current.copy(sourceName = name, enabled = if (restoring) enabled else current.enabled), hash, validatedWebUrl)
                                if (GSON.toJson(current) != GSON.toJson(next)) changed += appDb.rssSourceDao.updateCloudSource(next)
                                healthy++
                            }
                        }
                    }
                    cursor = data.get("nextCursor")?.takeUnless { it.isJsonNull }?.asString?.takeIf { it.isNotBlank() }
                    check(cursor == null || cursors.add(cursor)) { "雲端書源目錄游標重複" }
                } while (cursor != null)
                approved[kind] = ids
            }
            CloudReader.approveCatalogue(context, configuration, approved)
            if (healthy > 0) CloudReader.markVerified(context)
            changed
        }
    }
}
