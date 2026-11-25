package io.legado.app.model.multisource

import android.content.Context
import android.os.BatteryManager
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.BookSourcePart
import io.legado.app.help.book.isOnLineTxt
import io.legado.app.model.ReadBook
import io.legado.app.model.multisource.core.BookIdentity
import io.legado.app.model.multisource.core.CandidateScheduler
import io.legado.app.model.multisource.core.ChapterCandidate
import io.legado.app.model.multisource.core.ChapterIdentity
import io.legado.app.model.multisource.core.ChapterMerger
import io.legado.app.model.multisource.core.ChapterContentPolicy
import io.legado.app.model.multisource.core.IdentityMatcher
import io.legado.app.model.multisource.core.MergeConfidence
import io.legado.app.model.multisource.core.MergeResult
import io.legado.app.model.multisource.core.StreamingHash
import io.legado.app.model.webBook.WebBook
import io.legado.app.utils.ChineseUtils
import io.legado.app.utils.GSON
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import splitties.init.appCtx
import java.io.File
import java.net.URI
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.coroutineContext

/** Keeps reconstructed text separate from Legado's original single-source cache and database. */
object MultiSourceManager {
    private const val VERSION = 3
    private const val MAX_AGE = 24 * 60 * 60 * 1000L
    private val prefs by lazy { appCtx.getSharedPreferences("multisource", Context.MODE_PRIVATE) }
    private val directory by lazy { File(appCtx.filesDir, "multisource-v1").apply { mkdirs() } }
    private val healthDirectory by lazy { File(appCtx.filesDir, "multisource-health-v1").apply { mkdirs() } }
    private val healthLock = Any()
    private val locks = Array(32) { Mutex() }
    private val configurationLock = Any()
    private var trackerEpoch = 0L
    private data class SourceRevision(val epoch: Long, val changes: Long)
    private data class Configuration(val revision: SourceRevision, val limit: Int, val hash: String)
    private var savedConfiguration: Configuration? = null
    private val merger by lazy { ChapterMerger(ChineseUtils::t2s) }
    private data class Saved(val version: Int, val created: Long, val configuration: String,
        val originalHash: String, val result: MergeResult, val failures: List<String>,
        val foregroundAttempted: Boolean = false)
    private data class Relation(val configuration: String, val created: Long, val book: Book)
    private data class Toc(val created: Long, val chapters: List<BookChapter>)
    private val tocs = object : LinkedHashMap<String, Toc>(16, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Toc>?) = size > 16
    }

    var enabled: Boolean
        get() = prefs.getBoolean("enabled", true)
        set(value) { prefs.edit().putBoolean("enabled", value).apply() }
    var candidateLimit: Int
        get() = prefs.getInt("candidates", 3).coerceIn(1, 6)
        set(value) { prefs.edit().putInt("candidates", value.coerceIn(1, 6)).apply() }

    private fun hash(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
    private fun id(book: Book, chapter: BookChapter) = hash(book.bookUrl + "\n" + chapter.url)
    private fun file(book: Book, chapter: BookChapter) = File(directory, id(book, chapter) + ".json")
    private fun healthFile(book: Book) = File(healthDirectory, hash(book.bookUrl) + ".json")

    private fun readHealth(book: Book, configuration: String): CandidateScheduler.State = runCatching {
        val file = healthFile(book)
        if (!file.isFile || file.length() > 1_000_000) return@runCatching CandidateScheduler.State()
        val json = GSON.fromJson(file.readText(), JsonObject::class.java)
        if (json.get("version")?.asInt != 1 || json.get("configuration")?.asString != configuration) {
            return@runCatching CandidateScheduler.State()
        }
        val history = linkedMapOf<String, CandidateScheduler.Health>()
        val entries = json.getAsJsonArray("sources")
        val maximumRetry = System.currentTimeMillis() + CandidateScheduler.RETRY_DELAY
        for (index in maxOf(0, entries.size() - CandidateScheduler.MAX_HISTORY) until entries.size()) {
            val entry = entries[index].asJsonArray
            history[entry[0].asString] = CandidateScheduler.Health(entry[1].asLong.coerceAtMost(maximumRetry),
                entry[2].asBoolean, if (entry.size() > 3) entry[3].asLong else 0)
        }
        val known = json.getAsJsonArray("knownThrough")?.let {
            CandidateScheduler.KnownPosition(it[0].asLong, it[1].asInt, it[2].asString)
        }
        CandidateScheduler.State(json.get("cursor")?.takeUnless { it.isJsonNull }?.asString, history, known)
    }.getOrDefault(CandidateScheduler.State())

    private fun saveHealth(book: Book, configuration: String, state: CandidateScheduler.State) {
        val json = JsonObject().apply {
            addProperty("version", 1)
            addProperty("configuration", configuration)
            addProperty("cursor", state.cursor)
            state.knownThrough?.let { known -> add("knownThrough", JsonArray().apply {
                add(known.time); add(known.order); add(known.id)
            }) }
            add("sources", JsonArray().apply {
                state.history.entries.toList().takeLast(CandidateScheduler.MAX_HISTORY).forEach { (id, health) ->
                    add(JsonArray().apply { add(id); add(health.retryUntil); add(health.successful); add(health.knownAt) })
                }
            })
        }
        write(healthFile(book), json)
        healthDirectory.listFiles()?.filter { it.extension == "json" }
            ?.sortedByDescending { it.lastModified() }?.drop(64)?.forEach { it.delete() }
    }

    /** Manual retry and owned test cleanup affect only this book's discovery history. */
    fun clearCandidateHistory(book: Book) = synchronized(healthLock) {
        healthFile(book).delete()
        Unit
    }

    private fun selectCandidates(book: Book, primarySource: String, configuration: String,
        known: Map<String, Long>, now: Long, parts: List<BookSourcePart>,
        excluded: Set<String> = emptySet(), rescue: Boolean = false): List<BookSource> {
        val available = parts.filter { it.bookSourceUrl != primarySource && it.bookSourceUrl !in excluded }
        val ids = synchronized(healthLock) {
            val plan = CandidateScheduler.choose(available.map {
                val priority = if (!rescue) 0 else when {
                    it.weight > 0 -> 0
                    it.respondTime in 1L until 15_000L -> 1
                    else -> 2
                }
                CandidateScheduler.Candidate(it.bookSourceUrl, it.bookSourceUrl in known, it.customOrder, priority,
                    known[it.bookSourceUrl] ?: 0)
            }, readHealth(book, configuration), now, candidateLimit)
            // Advance immediately: concurrent preloads do not all retry the same unknown prefix.
            // No failure is recorded until an attempt completes; caller cancellation is harmless.
            saveHealth(book, configuration, plan.state)
            plan.candidates.map { it.id }
        }
        return ids.mapNotNull { id -> appDb.bookSourceDao.getBookSource(id)
            ?.takeIf { it.enabled && it.bookSourceType == 0 } }
    }

    private fun recordHealth(book: Book, configuration: String,
        outcomes: Map<String, CandidateScheduler.Outcome>, now: Long, knownAt: Map<String, Long>) = synchronized(healthLock) {
        var state = readHealth(book, configuration)
        outcomes.forEach { (source, outcome) ->
            state = CandidateScheduler.record(state, source, outcome, now, knownAt[source] ?: 0)
        }
        saveHealth(book, configuration, state)
    }
    /**
     * Room notifications are asynchronous, including refreshVersionsSync when another refresh
     * has already claimed the work. A TEMP counter observes committed rule changes directly.
     * Reading it on the primary writer connection also covers equal-length rule-only edits.
     * The counter/triggers are connection-local and never change the user's database schema.
     */
    private fun databaseSourceRevision(): SourceRevision {
        val database = appDb.openHelper.writableDatabase
        database.beginTransactionNonExclusive()
        try {
            val installed = database.query("select count(*) from sqlite_temp_master " +
                "where type = 'table' and name = 'reader_multisource_revision_v1'").use {
                it.moveToFirst() && it.getInt(0) == 1
            }
            if (!installed) {
                database.execSQL("create temp table reader_multisource_revision_v1 " +
                    "(id integer primary key, changes integer not null)")
                database.execSQL("insert into reader_multisource_revision_v1 values (1, 0)")
                trackerEpoch++ // A reopened SQLite connection must not reuse an older counter.
            }
            listOf("insert", "update", "delete").forEach { operation ->
                database.execSQL("create temp trigger if not exists reader_multisource_revision_$operation " +
                    "after $operation on main.book_sources begin " +
                    "update reader_multisource_revision_v1 set changes = changes + 1 where id = 1; end")
            }
            val changes = database.query("select changes from reader_multisource_revision_v1 where id = 1").use {
                check(it.moveToFirst())
                it.getLong(0)
            }
            database.setTransactionSuccessful()
            return SourceRevision(trackerEpoch, changes)
        } finally { database.endTransaction() }
    }

    private fun configuration(): String = synchronized(configurationLock) {
        repeat(3) {
            val revision = databaseSourceRevision()
            val limit = candidateLimit
            savedConfiguration?.takeIf { it.revision == revision && it.limit == limit }
                ?.let { return@synchronized it.hash }
            // Stream the rules only after a real source edit, never materialize all full models.
            val fingerprint = fingerprint(limit)
            if (databaseSourceRevision() == revision && candidateLimit == limit) {
                savedConfiguration = Configuration(revision, limit, fingerprint)
                return@synchronized fingerprint
            }
        }
        // Concurrent imports must not make an older cache appear valid. Callers retain raw text.
        error("Source configuration changed during comparison")
    }
    private fun fingerprint(limit: Int, cacheVersion: Int = VERSION): String = StreamingHash.sha256 { writer ->
        StreamingHash.field(writer, "database-config-v2:$cacheVersion:$limit")
        // A large imported library may contain tens of thousands of full rule sets. A
        // windowed cursor keeps only a small database window, rather than all models,
        // all JSON strings and their concatenated copy alive at the same time.
        appDb.openHelper.readableDatabase.query(
            "select * from book_sources where enabled = 1 and bookSourceType = 0 " +
                "order by customOrder, bookSourceUrl"
        ).use { cursor ->
            cursor.columnNames.forEach { StreamingHash.field(writer, it) }
            while (cursor.moveToNext()) {
                writer.write("row:")
                for (column in 0 until cursor.columnCount) {
                    StreamingHash.field(writer, if (cursor.isNull(column)) null else cursor.getString(column))
                }
            }
        }
    }
    private fun read(file: File): Saved? = runCatching {
        if (!file.isFile || file.length() > 4_000_000) null
        else GSON.fromJson(file.readText(), Saved::class.java)
    }.getOrNull()
    private fun write(file: File, value: Any) {
        val temp = File.createTempFile(file.name, ".pending", file.parentFile)
        try {
            temp.writeText(GSON.toJson(value))
            check(temp.renameTo(file)) { "Unable to store multi-source result" }
        } finally { temp.delete() }
    }
    private fun valid(saved: Saved, raw: String, configuration: String, foreground: Boolean = false) =
        saved.version == VERSION && saved.configuration == configuration && saved.originalHash == hash(raw) &&
            saved.result.let { result ->
                val confirmed = (result.usedSourceIds.size > 1 || raw.isEmpty() && result.text.isNotBlank()) &&
                    !ChapterContentPolicy.hasRepairNotice(result.text)
                CandidateScheduler.cacheReusable(saved.created, System.currentTimeMillis(), confirmed) &&
                    (!foreground || saved.foregroundAttempted || confirmed || raw.isEmpty())
            }

    /** Read only: a background result never sends a content replacement to the visible page/TTS. */
    fun cached(book: Book, chapter: BookChapter, raw: String): String? {
        if (!enabled || !book.isOnLineTxt || chapter.isVolume) return null
        val saved = read(file(book, chapter)) ?: return null
        val foreground = ReadBook.book?.bookUrl == book.bookUrl && ReadBook.durChapterIndex == chapter.index
        return if (saved.result.text.isNotBlank() && valid(saved, raw, configuration(), foreground)) saved.result.text else null
    }

    /**
     * Read-only migration aid for an unmarked saved offset. Ordinary cache validity is unchanged.
     * Previous versions used the same bounded cursor fingerprint. Preserve their previous
     * readable text only while its original text and ALL currently enabled source rules still match.
     */
    fun readableForInitialPosition(book: Book, chapter: BookChapter, raw: String): String? = runCatching {
        if (!enabled || !book.isOnLineTxt || chapter.isVolume) return@runCatching null
        val saved = read(file(book, chapter)) ?: return@runCatching null
        if (saved.result.text.isBlank() || saved.result.text.length > 250_000) return@runCatching null
        if (saved.version == VERSION) {
            return@runCatching saved.takeIf { valid(it, raw, configuration()) }?.result?.text
        }
        if (saved.version !in 1 until VERSION || saved.originalHash != hash(raw) ||
            System.currentTimeMillis() - saved.created !in 0..MAX_AGE) return@runCatching null
        val compatible = synchronized(configurationLock) {
            val revision = databaseSourceRevision()
            val limit = candidateLimit
            val matches = saved.configuration == fingerprint(limit, cacheVersion = saved.version)
            matches && revision == databaseSourceRevision() && limit == candidateLimit && enabled
        }
        saved.result.text.takeIf { compatible }
    }.getOrNull()

    fun invalidate(book: Book, chapter: BookChapter) { file(book, chapter).delete() }

    private fun chapterIdentity(chapter: BookChapter, toc: List<BookChapter>): ChapterIdentity {
        val position = toc.indexOfFirst { it.url == chapter.url }
        return ChapterIdentity(chapter.title, chapter.index,
            toc.getOrNull(position - 1)?.title, toc.getOrNull(position + 1)?.title)
    }
    private fun bookIdentity(book: Book) = BookIdentity(book.name, book.author)
    private fun family(source: BookSource): String {
        val host = runCatching { URI(source.bookSourceUrl).host }.getOrNull()
            ?.lowercase()?.removePrefix("www.") ?: return "unknown"
        // Same registrable host is one vote. Multi-label country suffixes keep three labels.
        val mirrorName = ChineseUtils.t2s(source.bookSourceName).lowercase()
        if (mirrorName.contains("镜像") || mirrorName.contains("mirror")) {
            val stem = mirrorName.replace(Regex("镜像|mirror|[0-9\\s:：()（）._-]"), "")
            if (stem.isNotBlank()) return "mirror:$stem"
        }
        val labels = host.split('.')
        val count = if (labels.takeLast(2).joinToString(".") in setOf("com.cn", "com.hk", "co.uk", "com.tw")) 3 else 2
        return labels.takeLast(count).joinToString(".")
    }

    private data class LocatedChapter(val book: Book, val chapters: List<BookChapter>,
        val matches: List<ChapterIdentity>, val candidate: ChapterCandidate)

    /** One verified lookup contract for both opening recovery and ordinary chapter stitching. */
    private suspend fun locateCandidate(candidateSource: BookSource, book: Book, identity: ChapterIdentity?,
        known: Map<String, io.legado.app.data.entities.SearchBook>, now: Long,
        attemptedKnownAt: ConcurrentHashMap<String, Long>): LocatedChapter? {
        val relationFile = File(directory, "relation-" + hash(book.bookUrl + candidateSource.bookSourceUrl) + ".json")
        val relationConfig = StreamingHash.sha256 { writer ->
            GSON.toJson(candidateSource, writer)
            GSON.toJson(bookIdentity(book), writer)
        }
        val relation = runCatching { GSON.fromJson(relationFile.readText(), Relation::class.java) }.getOrNull()
        var candidateBook = relation?.takeIf { it.configuration == relationConfig && now - it.created in 0..MAX_AGE }?.book
            ?: known[candidateSource.bookSourceUrl]?.toBook()
        var infoLoaded = false
        if (candidateBook == null) {
            val results = WebBook.searchBookAwait(candidateSource, book.name,
                filter = { name, author ->
                    val target = bookIdentity(book)
                    val result = BookIdentity(name, author)
                    IdentityMatcher.sameBook(target, result, ChineseUtils::t2s) ||
                        (IdentityMatcher.bookKey(target, ChineseUtils::t2s) != null &&
                            IdentityMatcher.bookKey(result, ChineseUtils::t2s) == null &&
                            IdentityMatcher.normalize(name, ChineseUtils::t2s) ==
                            IdentityMatcher.normalize(book.name, ChineseUtils::t2s))
                }, shouldBreak = { it >= 3 })
            // Some sources omit authors in search results. Inspect only
            // exact-title candidates and require the actual detail author
            // before admitting a book; title alone never permits stitching.
            for (result in results.take(3)) {
                val inspected = result.toBook()
                WebBook.getBookInfoAwait(candidateSource, inspected, canReName = false)
                if (IdentityMatcher.sameBook(bookIdentity(book), bookIdentity(inspected), ChineseUtils::t2s)) {
                    val discovered = inspected.toSearchBook()
                    appDb.searchBookDao.insert(discovered)
                    attemptedKnownAt[candidateSource.bookSourceUrl] = discovered.time
                    candidateBook = inspected
                    infoLoaded = true
                    break
                }
            }
        }
        val cb = candidateBook ?: return null
        if (!IdentityMatcher.sameBook(bookIdentity(book), bookIdentity(cb), ChineseUtils::t2s)) return null
        val tocKey = hash(candidateSource.bookSourceUrl + cb.bookUrl + relationConfig)
        var toc = synchronized(tocs) { tocs[tocKey]?.takeIf { now - it.created < 60 * 60 * 1000L }?.chapters }
        if (toc == null) {
            if (!infoLoaded) WebBook.getBookInfoAwait(candidateSource, cb, canReName = false)
            if (!IdentityMatcher.sameBook(bookIdentity(book), bookIdentity(cb), ChineseUtils::t2s)) return null
            toc = WebBook.getChapterListAwait(candidateSource, cb).getOrThrow()
            synchronized(tocs) { tocs[tocKey] = Toc(now, toc) }
            write(relationFile, Relation(relationConfig, now, cb))
        }
        val chapters = toc
        val identities = chapters.mapIndexed { i, c -> ChapterIdentity(c.title, c.index,
            chapters.getOrNull(i - 1)?.title, chapters.getOrNull(i + 1)?.title) }
        val matches = if (identity == null) identities.firstOrNull { !chapters[it.index].isVolume }?.let(::listOf).orEmpty()
            else IdentityMatcher.matchChapterParts(identity, identities, ChineseUtils::t2s)
        if (matches.isEmpty()) return null
        val byIndex = chapters.associateBy { it.index }
        val texts = matches.map { match ->
            val matched = byIndex[match.index]?.takeIf { it.title == match.title }
                ?: return null
            if (matched.isVip && !matched.isPay) return null
            val next = byIndex[matched.index + 1]?.url
            val part = WebBook.getContentAwait(candidateSource, cb, matched, next, needSave = false)
            if (ChapterContentPolicy.missing(part, matched.title) ||
                matches.size > 1 && ChapterContentPolicy.hasRepairNotice(part)) return null
            if (matches.size == 1) part else {
                // Only a verified table-of-contents split may be concatenated. Remove
                // its leading heading so the central next-chapter guard remains strict.
                part.lineSequence().dropWhile { line -> line.isBlank() ||
                    IdentityMatcher.normalize(line, ChineseUtils::t2s) ==
                    IdentityMatcher.normalize(matched.title, ChineseUtils::t2s) }
                    .joinToString("\n")
            }
        }
        val matchedIdentity = identity ?: identities.firstOrNull { it.index == matches.first().index } ?: return null
        val candidate = ChapterCandidate(candidateSource.bookSourceUrl, candidateSource.bookSourceName,
            family(candidateSource), bookIdentity(cb), matchedIdentity, texts.joinToString("\n\n"))
        return LocatedChapter(cb, chapters, matches, candidate)
    }

    data class OpeningRecovery(val book: Book, val chapters: List<BookChapter>,
        val chapter: BookChapter, val text: String, val source: BookSource)

    /** Recover a lost opening catalogue, never extend an otherwise working catalogue. */
    suspend fun recoverOpening(book: Book): OpeningRecovery? = withContext(Dispatchers.IO) {
        if (!enabled || !book.isOnLineTxt) return@withContext null
        val oldToc = appDb.bookChapterDao.getChapterList(book.bookUrl)
        if (oldToc.isNotEmpty()) return@withContext null
        val current = oldToc.firstOrNull { it.index == book.durChapterIndex }
        val identity = current?.let { chapterIdentity(it, oldToc) }
            ?: book.durChapterTitle.orEmpty().takeIf { it.isNotBlank() }?.let {
                ChapterIdentity(it, book.durChapterIndex)
            }
        // An unread favourite may start at its first chapter. A saved position without
        // a recognisable chapter identity must never be guessed from a numeric index.
        if (identity == null && (book.durChapterIndex != 0 || book.durChapterPos != 0)) return@withContext null
        io.legado.app.help.source.VerifiedSourceBootstrap.ensureSeeded()
        val config = configuration()
        val known = appDb.searchBookDao.changeSourceByGroup(book.name, book.author, "")
            .filter { IdentityMatcher.sameBook(bookIdentity(book), BookIdentity(it.name, it.author), ChineseUtils::t2s) }
            .associateBy { it.origin }
        val knownAt = ConcurrentHashMap(known.mapValues { it.value.time })
        val parts = appDb.bookSourceDao.allTextEnabledPart
        val attempted = hashSetOf<String>()
        val outcomes = ConcurrentHashMap<String, CandidateScheduler.Outcome>()
        val now = System.currentTimeMillis()
        var recovered: OpeningRecovery? = null
        withTimeoutOrNull(25_000L) {
            for (wave in 0 until 4) {
                val batch = selectCandidates(book, book.origin, config, known.mapValues { it.value.time },
                    now, parts, attempted, true)
                if (batch.isEmpty()) break
                attempted += batch.map { it.bookSourceUrl }
                val results = supervisorScope {
                    batch.map { source -> async {
                        try {
                            val located = withTimeoutOrNull(22_000L) {
                                SourceRequestGate.request(source.bookSourceUrl, true) {
                                    locateCandidate(source, book, identity, known, now, knownAt)
                                }
                            }
                            // A split target cannot safely inherit an old whole-chapter offset.
                            val chapter = located?.matches?.singleOrNull()?.let { match ->
                                located.chapters.firstOrNull { it.index == match.index }
                            }
                            val text = located?.candidate?.let { candidate ->
                                merger.readableBody(candidate)
                            }.orEmpty()
                            if (chapter == null || text.isBlank() || located?.book?.bookUrl == book.bookUrl) {
                                outcomes[source.bookSourceUrl] = CandidateScheduler.Outcome.NO_MATCH
                                null
                            } else {
                                outcomes[source.bookSourceUrl] = CandidateScheduler.Outcome.SUCCESS
                                OpeningRecovery(located!!.book, located.chapters, chapter, text, source)
                            }
                        } catch (e: CancellationException) { throw e
                        } catch (_: Exception) {
                            outcomes[source.bookSourceUrl] = CandidateScheduler.Outcome.FAILURE
                            null
                        }
                    } }.awaitAll()
                }
                recovered = results.firstOrNull { it != null }
                if (recovered != null) break
            }
        }
        coroutineContext.ensureActive()
        if (!enabled || config != configuration()) return@withContext null
        recordHealth(book, config, outcomes, System.currentTimeMillis(), knownAt)
        recovered
    }

    suspend fun enrichSafely(source: BookSource?, book: Book, chapter: BookChapter, raw: String): String =
        try { enrich(source, book, chapter, raw) }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { raw }

    suspend fun enrich(source: BookSource?, book: Book, chapter: BookChapter, original: String): String =
        withContext(Dispatchers.IO) {
            val raw = if (ChapterContentPolicy.missing(original, chapter.title)) "" else original
            // Access metadata belongs to its source. locateCandidate checks each prospective
            // edition's own TOC and chapter body before admitting it.
            if (!enabled || !book.isOnLineTxt || chapter.isVolume) return@withContext raw
            // Direct reader restores can race Main startup. This local transaction only
            // adds missing bundled identifiers and never changes existing user rules.
            io.legado.app.help.source.VerifiedSourceBootstrap.ensureSeeded()
            // There is no readable legacy version to protect when the original file is absent.
            // Keep the saved numeric position, but still allow a verified replacement to load.
            if (raw.isNotBlank() && ReadBook.holdUnmarkedInitialPosition(book, chapter)) return@withContext raw
            val key = id(book, chapter)
            locks[(key.hashCode() and Int.MAX_VALUE) % locks.size].withLock {
                val config = configuration()
                val foreground = ReadBook.book?.bookUrl == book.bookUrl && ReadBook.durChapterIndex == chapter.index
                // A failed rescue is diagnostic metadata, never readable content. New manual
                // search results can become available without editing any source rule.
                read(file(book, chapter))?.takeIf { it.result.text.isNotBlank() && valid(it, raw, config, foreground) }
                    ?.let { return@withLock it.result.text }
                val battery = appCtx.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
                if (!foreground && (battery?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: 100) in 1..15) return@withLock raw
                val primaryToc = appDb.bookChapterDao.getChapterList(book.bookUrl)
                val identity = chapterIdentity(chapter, primaryToc)
                val primarySource = source ?: BookSource(bookSourceUrl = book.origin, bookSourceName = book.originName)
                val primary = ChapterCandidate(primarySource.bookSourceUrl, primarySource.bookSourceName,
                    family(primarySource), bookIdentity(book), identity, raw)
                val found = ConcurrentHashMap<String, ChapterCandidate>()
                val failures = ConcurrentHashMap<String, String>()
                val outcomes = ConcurrentHashMap<String, CandidateScheduler.Outcome>()
                val known = appDb.searchBookDao.changeSourceByGroup(book.name, book.author, "")
                    .filter { IdentityMatcher.sameBook(bookIdentity(book), BookIdentity(it.name, it.author), ChineseUtils::t2s) }
                    .associateBy { it.origin }
                val attemptedKnownAt = ConcurrentHashMap(known.mapValues { it.value.time })
                val now = System.currentTimeMillis()
                val parts = appDb.bookSourceDao.allTextEnabledPart
                val candidates = mutableListOf<BookSource>()
                val rescue = raw.isBlank()
                val hasNotice = ChapterContentPolicy.hasRepairNotice(raw)
                val needsRecovery = rescue || hasNotice || chapter.isVip && !chapter.isPay
                // A cold rescue must finish search, details, TOC and public chapter loading.
                // Its reading-page deadline is separate from opportunistic enrichment/preload.
                val rescueInForeground = foreground && needsRecovery
                val candidateTimeout = if (rescueInForeground) 22_000L else 4_000L
                val totalTimeout = if (rescueInForeground) 25_000L else if (foreground) 4500L else 6500L
                var selectedResult: MergeResult? = null
                var batchMerged = true
                // If two marked gaps need different editions, keep the contributors that
                // actually repaired earlier gaps. Reserve room for the next batch.
                val contributors = linkedMapOf<String, ChapterCandidate>()
                fun mergeBatch(): Boolean {
                    val batch = found.values.sortedBy { it.sourceId }
                    val merged = merger.merge(primary, contributors.values + batch)
                    batchMerged = true
                    found.keys.filterNot { it in merged.usedSourceIds }.forEach {
                        outcomes[it] = CandidateScheduler.Outcome.NO_MATCH
                    }
                    if (merged.conflictingVersions) {
                        selectedResult = merged
                        return true
                    }
                    if (merged.usedSourceIds.none { it != primary.sourceId }) return false
                    if (selectedResult == null || merged.merged || !selectedResult!!.merged) selectedResult = merged
                    if (hasNotice && ChapterContentPolicy.hasRepairNotice(merged.text)) {
                        if (merged.merged) {
                            batch.filter { it.sourceId in merged.usedSourceIds }.forEach {
                                if (contributors.size < 7 - candidateLimit) contributors[it.sourceId] = it
                            }
                        }
                        return false
                    }
                    return rescue || merged.merged || merged.confidence == MergeConfidence.HIGH
                }
                withTimeoutOrNull(totalTimeout) {
                    // A dead prefix must not stop an otherwise quick supplement. Additional waves
                    // share one deadline and retain the configured concurrency, never fan out
                    // across an imported library. Each merge contains at most one bounded batch,
                    // so unusable earlier candidates cannot crowd out later matching content.
                    for (wave in 0 until 4) {
                        val batch = selectCandidates(book, primarySource.bookSourceUrl, config, known.mapValues { it.value.time },
                            now, parts, candidates.mapTo(hashSetOf()) { it.bookSourceUrl }, needsRecovery)
                        if (batch.isEmpty()) break
                        candidates += batch
                        found.clear()
                        batchMerged = false
                        supervisorScope {
                            batch.forEach { candidateSource -> launch {
                                try {
                                    var completed = false
                                    val candidate = withTimeoutOrNull(candidateTimeout) {
                                        SourceRequestGate.request(candidateSource.bookSourceUrl, foreground) {
                                            locateCandidate(candidateSource, book, identity, known, now,
                                                attemptedKnownAt)?.candidate
                                        }.also { completed = true }
                                    }
                                    val readable = candidate?.let { merger.readableBody(it) }
                                    if (candidate != null && readable != null &&
                                        !ChapterContentPolicy.missing(readable, chapter.title)) {
                                        found[candidate.sourceId] = candidate
                                        outcomes[candidateSource.bookSourceUrl] = CandidateScheduler.Outcome.SUCCESS
                                    } else {
                                        outcomes[candidateSource.bookSourceUrl] = if (completed) CandidateScheduler.Outcome.NO_MATCH
                                            else CandidateScheduler.Outcome.TIMEOUT
                                        failures[candidateSource.bookSourceUrl] = "${candidateSource.bookSourceName}：" +
                                            if (completed) "未取得可確認的同章正文" else "來源超時，未取得可確認的同章正文"
                                    }
                                } catch (e: CancellationException) { throw e
                                } catch (_: Exception) {
                                    failures[candidateSource.bookSourceUrl] = "${candidateSource.bookSourceName}：來源失敗，已隔離"
                                    outcomes[candidateSource.bookSourceUrl] = CandidateScheduler.Outcome.FAILURE
                                }
                            } }
                        }
                        if (found.isEmpty()) continue
                        if (mergeBatch()) break
                    }
                }
                coroutineContext.ensureActive()
                // A slow peer can exhaust the shared deadline after another candidate has
                // already finished. Keep that completed content without repeating normal merges.
                if (!batchMerged && found.isNotEmpty()) mergeBatch()
                candidates.filterNot { outcomes.containsKey(it.bookSourceUrl) }.forEach {
                    failures[it.bookSourceUrl] = "${it.bookSourceName}：超時，已取消"
                    outcomes[it.bookSourceUrl] = CandidateScheduler.Outcome.TIMEOUT
                }
                val result = selectedResult ?: merger.merge(primary, emptyList())
                if (enabled && config == configuration()) {
                    recordHealth(book, config, outcomes, System.currentTimeMillis(), attemptedKnownAt)
                    write(file(book, chapter), Saved(VERSION, now, config, hash(raw), result,
                        failures.values.toList(), foregroundAttempted = foreground))
                    // Bound disk usage without touching the original book cache.
                    directory.listFiles()?.filter { it.extension == "json" }?.sortedByDescending { it.lastModified() }
                        ?.drop(1200)?.forEach { it.delete() }
                }
                if (enabled && config == configuration()) result.text else raw
            }
        }

    fun details(book: Book, chapter: BookChapter): String {
        val positionNote = if (ReadBook.isSavedPositionHeld(book, chapter))
            "為保留原來閱讀位置，本章暫時沿用已讀版本。重新處理後可選擇使用新正文。\n" else ""
        val saved = read(file(book, chapter)) ?: return positionNote + "本章尚未處理。原書源正文仍可正常閱讀。"
        val currentSources = appDb.bookSourceDao.allTextEnabledPart
        val raw = io.legado.app.help.book.BookHelp.getContent(book, chapter, allowMultiSource = false).orEmpty()
        if (!enabled) return "多源功能已關閉。下次載入章節使用原書源正文；目前閱讀及朗讀維持不變。"
        if (!valid(saved, raw, configuration())) return positionNote + "本章的舊拼接結果已失效。下次載入時重新核對來源；目前正文維持不變。"
        val names = currentSources.associate { it.bookSourceUrl to it.bookSourceName }
        return buildString {
            append(positionNote)
            appendLine(if (saved.result.merged) "本章已使用多源補全" else "本章使用單源正文")
            val confidence = when (saved.result.confidence.name) { "HIGH" -> "較高"; "MEDIUM" -> "需要謹慎"; else -> "單源／證據不足" }
            appendLine("可信程度：$confidence")
            appendLine("參與來源：" + saved.result.usedSourceIds.joinToString("、") { names[it] ?: "已移除來源" })
            saved.result.notes.forEach { appendLine(it) }
            saved.failures.forEach { appendLine(it) }
            append("背景結果只在重新開啟章節時使用；不會替換正在閱讀或朗讀的正文。")
        }
    }
}
