package io.legado.app.help.source

import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.book.isOnLineTxt
import io.legado.app.help.http.StrResponse
import io.legado.app.help.http.await
import io.legado.app.help.http.okHttpClient
import io.legado.app.model.ReadBook
import io.legado.app.model.multisource.core.ChapterContentPolicy
import io.legado.app.utils.GSON
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Dispatchers.Main
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.Response
import java.util.concurrent.TimeUnit

/** Check only the active chapter and an independent same-origin entry after a natural missing response. */
object BookSourceAutoRemoval {
    private val checks = object : LinkedHashMap<String, Long>(16, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?) = size > 128
    }
    private val scripted = Regex("@js:|<js>|\\{\\{|webView", RegexOption.IGNORE_CASE)
    private val publicHeaders = setOf("user-agent", "accept", "accept-language", "accept-encoding",
        "cache-control", "pragma", "connection", "keep-alive", "referer", "cookiejar", "host")
    private val restricted = Regex("login|sign.?in|captcha|paywall|验证码|驗證碼|登录|登入|付费|付費", RegexOption.IGNORE_CASE)

    internal class Gone internal constructor(
        val sourceId: String, val sourceJson: String, val chapterUrl: HttpUrl,
        val entryUrl: HttpUrl, val book: Book, val chapter: BookChapter, val status: Int
    ) : NoStackTraceException("原书源正文返回 HTTP $status") {
        var readerIdentity: Book? = null
    }

    fun isArchived(id: String): Boolean = BookSourceRemovalStore.isArchived(id)

    internal fun currentSource(source: BookSource): BookSource? =
        if (isArchived(source.bookSourceUrl)) appDb.bookSourceDao.getBookSource(source.bookSourceUrl) else source

    internal fun snapshot(source: BookSource): String? = BookSourceRemovalStore.snapshot(source)

    internal fun entryFor(sourceId: String, chapter: String): HttpUrl? {
        val entry = sourceId.toHttpUrlOrNull()?.newBuilder()?.fragment(null)?.build() ?: return null
        val content = chapter.toHttpUrlOrNull() ?: return null
        if (entry.username.isNotEmpty() || entry.password.isNotEmpty() || entry.query != null ||
            content.username.isNotEmpty() || content.password.isNotEmpty() ||
            entry.scheme != content.scheme || entry.host != content.host || entry.port != content.port ||
            entry.encodedPath == content.encodedPath) return null
        return entry
    }

    internal fun originalMissing(response: Response, expected: HttpUrl): Boolean {
        val network = response.networkResponse ?: return false
        return (response.code == 404 || response.code == 410) && network.code == response.code && response.priorResponse == null &&
            response.cacheResponse == null && response.request.method == "GET" &&
            response.header("WWW-Authenticate") == null && response.header("Location") == null &&
            network.header("WWW-Authenticate") == null && network.header("Location") == null &&
            network.cacheResponse == null &&
            response.request.url == expected && network.request.url == expected &&
            network.request.method == "GET" && network.priorResponse == null &&
            response.request.headers.names().all { it.lowercase() in publicHeaders } &&
            network.request.headers.names().all { it.lowercase() in publicHeaders }
    }

    private fun stillReading(gone: Gone): Boolean {
        val active = ReadBook.book ?: return false
        return (gone.readerIdentity == null || active === gone.readerIdentity) &&
            active.bookUrl == gone.book.bookUrl && active.origin == gone.sourceId &&
            gone.book.origin == gone.sourceId && gone.chapter.bookUrl == active.bookUrl &&
            ReadBook.bookSource?.bookSourceUrl == gone.sourceId && ReadBook.durChapterIndex == gone.chapter.index
    }

    internal suspend fun checkOriginalResponse(source: BookSource, book: Book, chapter: BookChapter,
        originalJson: String?, response: StrResponse) {
        if (originalJson == null || !source.enabled || source.bookSourceType != 0 || !book.isOnLineTxt ||
            chapter.isVolume || chapter.isVip || chapter.isPay ||
            !source.loginUrl.isNullOrBlank() || !source.loginUi.isNullOrBlank() ||
            !source.loginCheckJs.isNullOrBlank() || !source.getLoginHeader().isNullOrBlank() ||
            !source.jsLib.isNullOrBlank() || !source.ruleContent?.webJs.isNullOrBlank() ||
            !source.ruleContent?.sourceRegex.isNullOrBlank() ||
            scripted.containsMatchIn(GSON.toJson(source.ruleContent)) || scripted.containsMatchIn(source.header.orEmpty()) ||
            !source.ruleContent?.payAction.isNullOrBlank() ||
            restricted.containsMatchIn(response.body.orEmpty().take(4096))) return
        val chapterUrl = chapter.getAbsoluteURL().toHttpUrlOrNull() ?: return
        val entry = entryFor(source.bookSourceUrl, chapterUrl.toString()) ?: return
        if (!originalMissing(response.raw, chapterUrl)) return
        val gone = Gone(source.bookSourceUrl, originalJson, chapterUrl, entry, book.copy(), chapter.copy(), response.code())
        if (withContext(Main) {
                if (!stillReading(gone)) false else {
                    gone.readerIdentity = ReadBook.book
                    true
                }
            }) throw gone
    }

    /** Natural foreground failures only. No catalogue scan, retry loop, or other provider request. */
    internal suspend fun afterPrimaryFailure(error: Throwable?) {
        val gone = error as? Gone ?: return
        currentCoroutineContext().ensureActive()
        if (!withContext(Main) { stillReading(gone) }) return
        val hash = BookSourceRemovalStore.digest(gone.sourceJson)
        val key = gone.status.toString() + hash
        val now = android.os.SystemClock.elapsedRealtime()
        synchronized(checks) {
            if (checks[key]?.let { now - it < 60_000L } == true) return
            checks[key] = now
        }
        try {
            val entryStatus = withTimeoutOrNull(1_200L) {
                val client = okHttpClient.newBuilder().followRedirects(false).followSslRedirects(false)
                    .retryOnConnectionFailure(false).cache(null).callTimeout(1_100, TimeUnit.MILLISECONDS).build()
                client.newCall(Request.Builder().url(gone.entryUrl).get().build()).await().use {
                    if (originalMissing(it, gone.entryUrl) && !restricted.containsMatchIn(it.peekBody(4096).string())) it.code else null
                }
            } ?: return
            val context = currentCoroutineContext()
            context.ensureActive()
            if (!withContext(Main) { stillReading(gone) }) return
            val record = BookSourceRemovalStore.Record(sourceId = gone.sourceId, sourceSha256 = hash,
                chapterUrl = gone.chapterUrl.toString(), entryUrl = gone.entryUrl.toString(),
                chapterStatus = gone.status, entryStatus = entryStatus, confirmedAt = System.currentTimeMillis())
            withContext(IO) {
                BookSourceRemovalStore.observeMissing(record) {
                    context.ensureActive()
                    stillReading(gone) && appDb.bookChapterDao.getChapter(gone.book.bookUrl, gone.chapter.index)
                        ?.getAbsoluteURL()?.toHttpUrlOrNull() == gone.chapterUrl
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            AppLog.put("书源自动移除未完成，原规则已保留", error)
        }
    }

    internal fun onReadableResponse(source: BookSource, chapter: BookChapter, content: String) {
        if (BookSourceRemovalStore.hasReview(source.bookSourceUrl) && !chapter.isVolume &&
            !ChapterContentPolicy.missing(content, chapter.title) && !ChapterContentPolicy.hasRepairNotice(content)) {
            BookSourceRemovalStore.forgetReview(source.bookSourceUrl)
        }
    }
}
