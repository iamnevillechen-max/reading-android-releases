package io.legado.app.multisource

import android.content.Context
import android.database.Cursor
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookSource
import io.legado.app.help.DefaultData
import io.legado.app.help.http.CookieStore
import io.legado.app.model.ReadBook
import io.legado.app.model.webBook.WebBook
import io.legado.app.service.BaseReadAloudService
import io.legado.app.utils.GSON
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.URI
import java.security.MessageDigest

/** Runtime-only private input. Never bundle user sources/cases or print their content. */
@RunWith(AndroidJUnit4::class)
class PrivateSourceReadOnlyAuditTest {
    private data class AuditCase(val id: String, val keywords: List<String>, val resultsLimit: Int,
        val attempts: Int, val timeoutMs: Long)
    private data class StageResult(val keywordAttempt: Int, var stage: String = "SEARCH",
        var outcome: String = "inconclusive", var exceptionClass: String? = null)
    private data class RoundResult(val idSha256: String, val round: Int, val outcome: String,
        val attempts: List<StageResult>)

    @Test fun auditNormallyPublicSourcesWithoutImportingOrSavingReadingData() = runBlocking {
        val arguments = InstrumentationRegistry.getArguments()
        val inputPath = arguments.getString("privateSourceAuditInput")
        assumeTrue("Runtime private-source input is required", inputPath != null)
        val context = ApplicationProvider.getApplicationContext<Context>()
        check(context.packageName == "io.multiread.reader.debug" &&
            Build.HARDWARE in setOf("ranchu", "goldfish")) { "Dedicated emulator required" }
        requireEmptyInstallation()
        val before = databaseFingerprint()
        val output = File(context.filesDir, "private-source-audit/results.json")
        val results = arrayListOf<RoundResult>()
        var databaseUnchanged = true
        try {
            val inputFile = File(requireNotNull(inputPath))
            val sourcesJson = readArray(inputFile, 512_000)
            val casePath = arguments.getString("privateSourceAuditCases")
                ?: File(inputFile.parentFile, "reader-private-source-audit-cases.json").path
            val casesJson = readArray(File(casePath), 64_000)
            check(sourcesJson.size() in 1..12 && casesJson.size() == sourcesJson.size()) { "Invalid private audit count" }
            val cases = casesJson.map { parseCase(it.asJsonObject) }
            val sources = sourcesJson.map { parseSource(it.asJsonObject) }
            check(cases.map { it.id }.toSet().size == cases.size &&
                sources.map { it.bookSourceUrl }.toSet() == cases.map { it.id }.toSet()) { "Private audit IDs do not match" }
            val sourcesById = sources.associateBy { it.bookSourceUrl }
            writeResults(output, results, complete = false, databaseUnchanged = true)
            repeat(2) { roundIndex ->
                val permits = Semaphore(2)
                val round = coroutineScope {
                    cases.map { case -> async(Dispatchers.IO) {
                        permits.withPermit { probe(sourcesById.getValue(case.id), case, roundIndex + 1) }
                    } }.awaitAll()
                }
                results.addAll(round)
                databaseUnchanged = before == databaseFingerprint()
                writeResults(output, results, complete = false, databaseUnchanged = databaseUnchanged)
                check(databaseUnchanged) { "Private audit changed persistent reading data" }
            }
            writeResults(output, results, complete = true, databaseUnchanged = true)
            println("PRIVATE_SOURCE_AUDIT sources=${sources.size} rounds=2 " +
                "passed=${results.count { it.outcome == "passed" }} " +
                "failed=${results.count { it.outcome == "failed" }} " +
                "inconclusive=${results.count { it.outcome == "inconclusive" }}")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            databaseUnchanged = before == databaseFingerprint()
            writeResults(output, results, complete = false, databaseUnchanged = databaseUnchanged)
            // Exceptions may contain private URLs. The instrumentation failure carries only a class.
            throw AssertionError("Private audit stopped: ${safeClass(error)}")
        }
    }

    private fun requireEmptyInstallation() {
        check(appDb.bookSourceDao.allCount() == 0 && appDb.bookDao.allBookCount == 0 &&
            ReadBook.book == null && !BaseReadAloudService.isRun) { "Empty idle Reader installation required" }
        val bundled = DefaultData.rssSources.filter { it.singleUrl }.associateBy { it.sourceUrl }
        check(appDb.rssSourceDao.all.all { actual ->
            actual.singleUrl && bundled[actual.sourceUrl]?.let { GSON.toJsonTree(it) == GSON.toJsonTree(actual) } == true
        }) { "Only unmodified bundled web shortcuts may exist" }
        for (table in listOf("cookies", "chapters", "readRecord", "bookmarks", "rssArticles", "rssReadRecords")) {
            appDb.openHelper.readableDatabase.query("select count(*) from \"$table\"").use {
                check(it.moveToFirst() && it.getLong(0) == 0L) { "Empty private reading and cookie tables required" }
            }
        }
    }

    private fun readArray(file: File, maxBytes: Long): JsonArray {
        check(file.canonicalPath.startsWith("/data/local/tmp/") && file.isFile && file.length() in 1..maxBytes) {
            "Bounded runtime private audit file required"
        }
        return try { file.reader().use { GSON.fromJson(it, JsonArray::class.java) } }
        catch (_: Exception) { throw IllegalArgumentException("Private audit JSON rejected") }
    }

    private fun parseCase(item: JsonObject): AuditCase {
        check(item["requiredRounds"].asInt == 2 && item["firstPublicFreeChapterOnly"].asBoolean &&
            item["skipVipUnpaid"].asBoolean && item["mustUseNativeSearchDetailTocContent"].asBoolean &&
            item["mustNotSendAuthOrImportedCookies"].asBoolean && item["markEmptySearchAsInconclusive"].asBoolean) {
            "Private audit constraints rejected"
        }
        val keywords = item.getAsJsonArray("searchKeywords").map { it.asString.trim() }
        val attempts = item["maxAttemptsPerSourcePerRound"].asInt
        val limit = item["maximumSearchResults"].asInt
        val timeout = item["requestTimeoutMs"].asLong
        check(keywords.size in 1..2 && keywords.all { it.length in 1..80 } && attempts in 1..2 &&
            limit in 1..3 && timeout in 1_000..8_000) { "Private audit bounds rejected" }
        return AuditCase(item["id"].asString, keywords, limit, attempts, timeout)
    }

    private fun parseSource(item: JsonObject): BookSource {
        val json = item.deepCopy()
        val serialized = GSON.toJson(json)
        val lower = serialized.lowercase()
        check(listOf("@js:", "<js>", "<script", "javascript:", "eval(", "packages.", "java.",
            "authorization", "\"cookie\"", "\"headers\"").none { it in lower }) { "Executable or authenticated source rejected" }
        check(Regex("\\{\\{(.*?)\\}\\}", RegexOption.DOT_MATCHES_ALL).findAll(serialized)
            .all { it.groupValues[1].trim() in setOf("key", "page") }) { "Executable source template rejected" }
        for (key in listOf("jsLib", "header", "loginUrl", "loginUi", "loginCheckJs", "coverDecodeJs")) {
            check(json[key] == null || json[key].isJsonNull || json[key].asString.isBlank()) { "Authenticated source rejected" }
        }
        for (key in listOf("enabled", "enabledExplore", "enabledCookieJar")) {
            json[key]?.takeUnless { it.isJsonNull }?.asJsonPrimitive?.let {
                if (!it.isBoolean) {
                    check(it.asInt in 0..1) { "Invalid private boolean" }
                    json.addProperty(key, it.asInt == 1)
                }
            }
        }
        // Native parsing without imported or response-cookie persistence; originals stay untouched.
        json.addProperty("enabledCookieJar", false)
        val source = GSON.fromJson(json, BookSource::class.java)
        val uri = URI(source.bookSourceUrl)
        check(uri.scheme in setOf("http", "https") && uri.host != null && uri.userInfo == null &&
            !source.searchUrl.isNullOrBlank()) { "Normal public source URL required" }
        return source
    }

    private suspend fun probe(source: BookSource, case: AuditCase, round: Int): RoundResult {
        val attempts = arrayListOf<StageResult>()
        var passed = false
        try {
            withTimeout(case.timeoutMs) {
                repeat(minOf(case.attempts, case.keywords.size)) { index ->
                    if (passed) return@repeat
                    val result = StageResult(index + 1)
                    attempts.add(result)
                    try {
                        check(CookieStore.getCookie(source.bookSourceUrl).isEmpty()) { "Anonymous request required" }
                        val keyword = case.keywords[(index + round - 1) % case.keywords.size]
                        val found = WebBook.searchBookAwait(source, keyword, shouldBreak = { it >= case.resultsLimit })
                        if (found.isEmpty()) return@repeat
                        for (candidate in found.take(case.resultsLimit)) {
                            if (candidate.name.isBlank()) continue
                            val book = candidate.toBook()
                            result.stage = "DETAIL"
                            WebBook.getBookInfoAwait(source, book, canReName = false)
                            if (book.name.isBlank() || book.author.isBlank() ||
                                book.author.trim().lowercase() in setOf("未知", "unknown", "作者不详", "作者不詳")) continue
                            result.stage = "TOC"
                            val chapters = WebBook.getChapterListAwait(source, book).getOrThrow()
                            val first = chapters.firstOrNull { !it.isVolume && !it.isVip && it.url.isNotBlank() } ?: continue
                            result.stage = "CONTENT"
                            val body = WebBook.getContentAwait(source, book, first,
                                chapters.getOrNull(chapters.indexOf(first) + 1)?.url, needSave = false)
                            val blocked = body.length < 500 && listOf("验证码", "驗證碼", "请登录", "請登入", "购买章节", "購買章節")
                                .any { it in body }
                            if (body.length >= 80 && !blocked && body.trim() != first.url.trim() && !body.contains("<html", true)) {
                                result.outcome = "passed"
                                passed = true
                                break
                            }
                        }
                    } catch (cancelled: CancellationException) { throw cancelled
                    } catch (error: Exception) {
                        result.outcome = "failed"
                        result.exceptionClass = safeClass(error)
                    }
                }
            }
        } catch (timeout: TimeoutCancellationException) {
            val result = attempts.lastOrNull() ?: StageResult(1).also { attempts.add(it) }
            result.outcome = "failed"
            result.exceptionClass = safeClass(timeout)
        }
        val outcome = if (passed) "passed" else if (attempts.isNotEmpty() && attempts.all { it.outcome == "failed" }) "failed" else "inconclusive"
        return RoundResult(sha256(case.id), round, outcome, attempts)
    }

    private fun databaseFingerprint(): Map<String, String> {
        val db = appDb.openHelper.readableDatabase
        val tables = db.query("select name from sqlite_master where type='table' and name not like 'sqlite_%' order by name").use {
            buildList { while (it.moveToNext()) add(it.getString(0)) }
        }
        return tables.associateWith { table ->
            check(table.matches(Regex("[A-Za-z0-9_]+"))) { "Unexpected database table" }
            val digest = MessageDigest.getInstance("SHA-256")
            db.query("select * from \"$table\" order by rowid").use { rows ->
                while (rows.moveToNext()) for (column in 0 until rows.columnCount) {
                    digest.update(rows.getType(column).toByte())
                    val value = when (rows.getType(column)) {
                        Cursor.FIELD_TYPE_NULL -> byteArrayOf()
                        Cursor.FIELD_TYPE_BLOB -> rows.getBlob(column)
                        else -> rows.getString(column).toByteArray(Charsets.UTF_8)
                    }
                    digest.update(value.size.toString().toByteArray(Charsets.UTF_8))
                    digest.update(0.toByte())
                    digest.update(value)
                    digest.update(0.toByte())
                }
            }
            digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        }
    }

    private fun writeResults(file: File, results: List<RoundResult>, complete: Boolean, databaseUnchanged: Boolean) {
        file.parentFile!!.mkdirs()
        val temporary = File(file.parentFile, "results.json.tmp")
        temporary.writeText(GSON.toJson(mapOf("complete" to complete, "rounds" to 2,
            "databaseUnchanged" to databaseUnchanged, "results" to results)))
        check(temporary.renameTo(file)) { "Private audit output could not be saved" }
    }
    private fun safeClass(error: Throwable) = error.javaClass.simpleName.replace(Regex("[^A-Za-z0-9_$]"), "").take(96)
    private fun sha256(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 255) }
}
