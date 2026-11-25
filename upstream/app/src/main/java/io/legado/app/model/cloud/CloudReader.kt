package io.legado.app.model.cloud

import android.content.Context
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.BookSourcePart
import io.legado.app.data.entities.RssArticle
import io.legado.app.data.entities.RssSource
import io.legado.app.data.entities.SearchBook
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.getFolderNameNoCache
import io.legado.app.help.book.isOnLineTxt
import io.legado.app.help.source.getBookType
import io.legado.app.utils.GSON
import splitties.init.appCtx
import java.io.IOException
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/** Retained cache compatibility only. All parsing and source imports now run locally. */
object CloudReader {
    private const val PREFERENCES = "reader_cloud_v1"
    private const val SOURCE_CONTEXT = "__reader_cloud_source_v1"
    private val configurationLock = Any()
    internal const val STUB = CloudMigrationPolicy.STUB
    private fun preferences(context: Context) = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    const val LOCAL_SOURCE_REQUIRED = "已改為本機解析，請在書源管理或訂閱源管理導入完整來源包"

    /** Persist the old cache namespace before removing retired connection credentials. */
    fun retire(context: Context = appCtx) = synchronized(configurationLock) {
        val stored = preferences(context)
        if (!stored.contains("retired_cache_revision")) {
            val revision = configurationRevision(context)
            stored.edit().putString("retired_cache_revision", revision)
                .putBoolean("enabled", false).remove("url").remove("token")
                .remove("approved_revision").remove("approved_book").remove("approved_rss").commit()
        }
        CloudCatalogue.clear()
    }

    fun isConfigured(context: Context = appCtx): Boolean = false
    fun hasCredential(context: Context = appCtx): Boolean = false
    fun endpoint(context: Context = appCtx): String = ""
    fun isEnabled(context: Context = appCtx): Boolean = false
    fun isRemote(source: BookSource): Boolean = source.searchUrl == STUB
    fun isRemote(source: RssSource): Boolean = source.ruleArticles == STUB
    fun isRemote(source: BookSource, book: Book): Boolean = isRemote(source) ||
        (isEnabled() && book.variableMap[SOURCE_CONTEXT] == source.bookSourceUrl)
    fun isRemote(source: RssSource, article: RssArticle): Boolean = isRemote(source) ||
        (isEnabled() && article.variableMap[SOURCE_CONTEXT] == source.sourceUrl)
    fun isCloudBook(book: Book): Boolean = book.isOnLineTxt &&
        ((book.variableMap[SOURCE_CONTEXT] == book.origin) || CloudContentCache.isSealed(book) ||
            appDb.bookSourceDao.getBookSource(book.origin)?.let { isRemote(it) } == true)

    internal fun contentContractVersion(context: Context = appCtx): String? = preferences(context).let {
        if (it.getString("content_contract_revision", null) != configurationRevision(context)) null
        else it.getString("content_contract_version", null)
    }

    fun isRemoteRoute(url: String): Boolean = url.startsWith("reader-cloud://")

    /** An old proxy has no native rules; full sources remain eligible for local searches. */
    fun newSearchParts(parts: List<BookSourcePart>): List<BookSourcePart> {
        val placeholders = appDb.bookSourceDao.placeholderSourceIds(STUB).toHashSet()
        return parts.filterNot { it.bookSourceUrl in placeholders }
    }

    internal fun activeApprovedBookIds(): Set<String> = emptySet()

    private fun approvedSources(context: Context, kind: String): Set<String>? = preferences(context).let {
        if (it.getString("approved_revision", null) != configurationRevision(context)) null
        else it.getStringSet("approved_$kind", null)?.toSet()
    }

    internal fun approveMigrationSources(context: Context, revision: String, bookIds: Set<String>) {
        check(configurationRevision(context) == revision) { "伺服器設定已變更，請重新整理雲端目錄" }
        val existing = approvedSources(context, "book").orEmpty()
        check(preferences(context).edit().putString("approved_revision", revision)
            .putStringSet("approved_book", existing + bookIds).commit()) { "無法保存已核驗書源清單" }
    }

    internal fun approveCatalogue(context: Context, revision: String, sources: Map<String, Set<String>>) {
        check(configurationRevision(context) == revision) { "伺服器設定已變更，請重新整理雲端目錄" }
        // Commit only after every page of both manifests succeeded, never a partial page.
        check(preferences(context).edit().apply {
            putString("approved_revision", revision)
            sources.forEach { (kind, ids) -> putStringSet("approved_$kind", ids) }
        }.commit()) { "無法保存已核驗書源清單" }
    }

    internal fun configurationRevision(context: Context): String = synchronized(configurationLock) {
        val values = preferences(context).all
        (values["retired_cache_revision"] as? String)?.let { return@synchronized it }
        CloudMigrationPolicy.sha256((values["url"] as? String).orEmpty() + "\u0000" + (values["token"] as? String).orEmpty())
    }

    internal suspend fun supportsBatchedSourceReceipts(context: Context): Boolean = false

    fun configure(context: Context, url: String, token: String): Nothing =
        throw IllegalStateException(LOCAL_SOURCE_REQUIRED)

    internal fun markVerified(context: Context): Nothing = throw IllegalStateException(LOCAL_SOURCE_REQUIRED)

    /** Hard stop at the shared transport boundary, including direct and stale callers. */
    suspend fun request(path: String, payload: JsonObject? = null, query: Map<String, String> = emptyMap(),
                        context: Context = appCtx): JsonElement = throw IOException(LOCAL_SOURCE_REQUIRED)

    internal fun payload(sourceId: String, vararg values: Pair<String, Any?>): JsonObject = JsonObject().apply {
        addProperty("sourceId", sourceId)
        values.forEach { (key, value) -> add(key, if (value is JsonElement) value else GSON.toJsonTree(value)) }
    }

    /** Only parsing context, excluding reading position, custom settings, groups, or history. */
    internal fun bookPayload(book: Book): JsonObject = JsonObject().apply {
        addProperty("bookUrl", book.bookUrl); addProperty("tocUrl", book.tocUrl)
        addProperty("name", book.name); addProperty("author", book.author)
        addProperty("kind", book.kind)
        addProperty("variable", GSON.toJson(book.variableMap.filterKeys { it != SOURCE_CONTEXT }))
        addProperty("origin", book.origin)
    }

    internal fun metadata(book: Book, value: JsonElement?, canRename: Boolean = true,
                          requestedSource: String = book.origin): Book {
        if (value == null || !value.isJsonObject) return book
        val data = value.asJsonObject
        fun string(key: String) = data.get(key)?.takeIf {
            it.isJsonPrimitive && it.asJsonPrimitive.isString
        }?.asString
        // A response for another URL/source must not change even secondary
        // metadata. Missing identity is allowed for partial toc/content replies,
        // but cannot authorize the narrow malformed-name repair exception.
        if (string("bookUrl")?.let { it != book.bookUrl } == true ||
            string("origin")?.let { it.isNotBlank() && it != book.origin } == true ||
            requestedSource != book.origin) return book
        val exactBook = CloudBookMetadataPolicy.sameBook(book.bookUrl, book.origin, requestedSource,
            string("bookUrl"), string("origin"))
        var name = CloudBookMetadataPolicy.identityValue(book.name, string("name"), canRename, exactBook)
        // Search may fill a genuinely missing author from matching detail data;
        // an established author remains unchanged while reading.
        var author = CloudBookMetadataPolicy.identityValue(book.author, string("author"),
            canRename || book.author.isBlank() && exactBook, exactBook)
        val repairing = (name != book.name && CloudBookMetadataPolicy.hasMarkup(book.name)) ||
            (author != book.author && CloudBookMetadataPolicy.hasMarkup(book.author))
        if (repairing) {
            // (name, author) is unique. Never turn a repair into merging or
            // replacing another shelf row, and never rewrite history keys.
            val duplicate = appDb.bookDao.getBook(name, author)
            if ((duplicate != null && duplicate.bookUrl != book.bookUrl) ||
                (name != book.name && !preserveRepairCache(book, name))) {
                name = book.name; author = book.author
            }
        }
        book.name = name; book.author = author
        string("tocUrl")?.takeIf { it.isNotBlank() }?.let { book.tocUrl = it }
        string("kind")?.let { book.kind = it }; string("coverUrl")?.let { book.coverUrl = it }
        string("intro")?.let { book.intro = it }; string("wordCount")?.let { book.wordCount = it }
        string("variable")?.let { raw ->
            val sourceContext = book.variableMap[SOURCE_CONTEXT]
            val values = if (raw.isBlank()) emptyMap() else JsonParser.parseString(raw).asJsonObject.entrySet()
                .associate { it.key to it.value.takeUnless { value -> value.isJsonNull }?.asString.orEmpty() }
            // Book.variableMap is lazy and may already have been used by the current reader.
            book.variableMap.clear(); book.variableMap.putAll(values)
            if (sourceContext != null) book.variableMap[SOURCE_CONTEXT] = sourceContext
            book.variable = GSON.toJson(book.variableMap)
        }
        return book
    }

    private fun preserveRepairCache(book: Book, correctedName: String): Boolean {
        val before = File(BookHelp.cachePath, book.getFolderNameNoCache())
        val after = File(BookHelp.cachePath, book.copy(name = correctedName).getFolderNameNoCache())
        if (before == after || !before.exists()) return true
        if (!before.isDirectory) return false
        val started = System.nanoTime()
        val limit = 750_000_000L
        var copied = 0L
        // The native cache is flat: chapter text, title-removal flags, and fonts.
        // Do not walk unrelated/deep trees or delay an interactive read forever.
        val chapterFile = Regex("\\d+-[a-fA-F0-9]{16}\\.(?:nb|nr|ttf)")
        val entries = before.listFiles() ?: return false
        if (entries.size > 20_000 || System.nanoTime() - started >= limit) return false
        if (!after.isDirectory && !after.mkdirs()) return false
        // Keep both complete old chapters and already-existing new-name files.
        // A cancelled/failed attempt leaves the old identity fully usable;
        // successfully copied missing chapters can be reused on the next try.
        return try {
            for (source in entries) {
                check(System.nanoTime() - started < limit)
                if (!chapterFile.matches(source.name)) continue
                check(source.isFile)
                val destination = File(after, source.name)
                if (destination.exists()) {
                    check(destination.isFile && (source.length() == 0L || destination.length() > 0L))
                    continue
                }
                val descriptor = try {
                    // Atomic exclusive creation also protects files created by
                    // another reader between exists() and this open operation.
                    Os.open(destination.path, OsConstants.O_WRONLY or OsConstants.O_CREAT or OsConstants.O_EXCL, 384)
                } catch (error: ErrnoException) {
                    if (error.errno == OsConstants.EEXIST) continue
                    throw error
                }
                var complete = false
                try {
                    val length = source.length()
                    val modified = source.lastModified()
                    FileOutputStream(descriptor).use { output ->
                        FileInputStream(source).use { input ->
                            val buffer = ByteArray(16 * 1024)
                            while (true) {
                                check(System.nanoTime() - started < limit)
                                val count = input.read(buffer)
                                if (count < 0) break
                                copied += count
                                check(copied <= 64L * 1024 * 1024)
                                output.write(buffer, 0, count)
                            }
                        }
                    }
                    check(source.length() == length && source.lastModified() == modified && destination.length() == length)
                    complete = true
                } finally {
                    // This exact destination was exclusively created above.
                    if (!complete) destination.delete()
                }
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun books(value: JsonElement, source: BookSource): ArrayList<SearchBook> {
        val rows = if (value.isJsonArray) value.asJsonArray else value.asJsonObject.getAsJsonArray("books") ?: JsonArray()
        require(rows.size() <= 500) { "雲端書列過大" }
        return rows.mapTo(arrayListOf()) { row ->
            GSON.fromJson(row, SearchBook::class.java).apply {
                require(bookUrl.isNotBlank() && name.isNotBlank()) { "雲端書籍資料不完整" }
                require(CloudBookMetadataPolicy.isPlain(name) &&
                    (author.isBlank() || CloudBookMetadataPolicy.isPlain(author))) { "雲端書名或作者解析錯誤，請重新整理" }
                origin = source.bookSourceUrl; originName = source.bookSourceName; originOrder = source.customOrder
                type = source.getBookType()
                putVariable(SOURCE_CONTEXT, source.bookSourceUrl)
            }
        }
    }

    suspend fun search(source: BookSource, key: String, page: Int?): ArrayList<SearchBook> =
        books(request("/v1/books/search", payload(source.bookSourceUrl, "key" to key, "page" to (page ?: 1))), source)

    suspend fun explore(source: BookSource, url: String, page: Int?): ArrayList<SearchBook> =
        books(request("/v1/books/explore", payload(source.bookSourceUrl, "url" to url, "page" to (page ?: 1))), source)

    suspend fun info(source: BookSource, book: Book, canRename: Boolean): Book =
        metadata(book, request("/v1/books/info", payload(source.bookSourceUrl, "book" to bookPayload(book))).asJsonObject.get("book"), canRename, source.bookSourceUrl)

    suspend fun toc(source: BookSource, book: Book): List<BookChapter> {
        require(source.bookSourceUrl == book.origin) { "雲端書源與書籍不符" }
        val configuration = configurationRevision(appCtx)
        val sourceJson = GSON.toJson(source)
        val data = request("/v1/books/toc", payload(source.bookSourceUrl, "book" to bookPayload(book),
            "tocInstallContract" to CloudTocPolicy.CONTRACT)).asJsonObject
        require(data["complete"]?.let { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean && it.asBoolean } == true &&
            data["contractVersion"]?.let { it.isJsonPrimitive && it.asJsonPrimitive.isString && it.asString == CloudContentCache.CONTRACT } == true &&
            data["sourceId"]?.let { it.isJsonPrimitive && it.asJsonPrimitive.isString && it.asString == source.bookSourceUrl } == true &&
            data["bookUrl"]?.let { it.isJsonPrimitive && it.asJsonPrimitive.isString && it.asString == book.bookUrl } == true) {
            "伺服器尚未提供相容的完整目錄，已保留原目錄，請稍後重試"
        }
        metadata(book, data.get("book"), false, source.bookSourceUrl)
        val rows = data.getAsJsonArray("chapters") ?: throw IOException("雲端目錄資料不完整")
        require(rows.size() in 1..100_000) { "雲端目錄為空或過大" }
        val ordered = if (book.getReverseToc()) rows.reversed() else rows.toList()
        val chapters = ordered.mapIndexed { index, row -> GSON.fromJson(row, BookChapter::class.java).apply {
            require(url.isNotBlank() && title.isNotBlank()) { "雲端章節資料不完整" }
            require(bookUrl.isBlank() || bookUrl == book.bookUrl) { "雲端章節與書籍不符" }
            bookUrl = book.bookUrl; this.index = index
            if (baseUrl.isBlank()) baseUrl = book.tocUrl.ifBlank { book.bookUrl }
        } }
        require(chapters.map { it.url }.toSet().size == chapters.size) { "雲端目錄有重複章節網址" }
        if (book.totalChapterNum < chapters.size) {
            book.lastCheckCount = chapters.size - book.totalChapterNum
            book.latestChapterTime = System.currentTimeMillis()
        }
        book.totalChapterNum = chapters.size
        book.lastCheckTime = System.currentTimeMillis()
        book.latestChapterTitle = chapters.last().title
        // durChapterIndex/Pos/Title and anchors always belong to the local reader.
        return CloudTocResult(chapters, source.bookSourceUrl, sourceJson, configuration)
    }

    suspend fun content(source: BookSource, book: Book, chapter: BookChapter, nextChapterUrl: String?, needSave: Boolean): String {
        require(source.bookSourceUrl == book.origin && chapter.bookUrl == book.bookUrl) { "正文身份不符" }
        CloudContentCache.preserveChapter(book, chapter)
        return CloudContentCache.retained(book, chapter) ?: throw IOException(LOCAL_SOURCE_REQUIRED)
    }

    suspend fun rssArticles(source: RssSource, sortName: String, url: String, page: Int): Pair<MutableList<RssArticle>, String?> {
        val data = request("/v1/rss/articles", payload(source.sourceUrl, "url" to url, "page" to page)).asJsonObject
        val rows = data.getAsJsonArray("articles") ?: throw IOException("雲端訂閱資料不完整")
        require(rows.size() <= 500) { "雲端訂閱列過大" }
        val articles = rows.mapTo(arrayListOf()) { row -> GSON.fromJson(row, RssArticle::class.java).apply {
            require(link.isNotBlank() && title.isNotBlank()) { "雲端訂閱資料不完整" }
            origin = source.sourceUrl; sort = sortName
            variableMap[SOURCE_CONTEXT] = source.sourceUrl
            variable = GSON.toJson(variableMap)
        } }
        return articles to data.get("nextUrl")?.takeUnless { it.isJsonNull }?.asString
    }

    suspend fun rssContent(source: RssSource, article: RssArticle): String = request("/v1/rss/content",
        payload(source.sourceUrl, "article" to JsonObject().apply {
            addProperty("origin", article.origin); addProperty("link", article.link)
            addProperty("title", article.title)
            addProperty("sort", article.sort); addProperty("pubDate", article.pubDate)
            addProperty("description", article.description); addProperty("content", article.content)
            addProperty("image", article.image)
            addProperty("variable", GSON.toJson(article.variableMap.filterKeys { it != SOURCE_CONTEXT }))
        })).asJsonObject.get("content")?.takeUnless { it.isJsonNull }?.asString ?: throw IOException("雲端訂閱正文資料不完整")

    suspend fun migrate(context: Context = appCtx, onProgress: (CloudSourceMigration.Progress) -> Unit = {}): CloudSourceMigration.Report =
        throw IllegalStateException(LOCAL_SOURCE_REQUIRED)

    suspend fun syncCatalogue(context: Context = appCtx): Int = 0
}
