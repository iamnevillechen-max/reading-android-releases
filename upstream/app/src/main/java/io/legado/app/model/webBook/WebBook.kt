package io.legado.app.model.webBook

import io.legado.app.constant.AppLog
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.BookSourcePart
import io.legado.app.data.entities.SearchBook
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.book.addType
import io.legado.app.help.book.removeAllBookType
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.http.StrResponse
import io.legado.app.help.source.getBookType
import io.legado.app.help.source.BookSourceAutoRemoval
import io.legado.app.model.multisource.SourceRequestGate
import io.legado.app.model.multisource.MultiSourceManager
import io.legado.app.model.cloud.CloudReader
import kotlinx.coroutines.CancellationException
import io.legado.app.model.Debug
import io.legado.app.model.analyzeRule.AnalyzeRule
import io.legado.app.model.analyzeRule.AnalyzeRule.Companion.setCoroutineContext
import io.legado.app.model.analyzeRule.AnalyzeUrl
import io.legado.app.model.analyzeRule.RuleData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Semaphore
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext

@Suppress("MemberVisibilityCanBePrivate")
object WebBook {

    /**
     * 搜索
     */
    fun searchBook(
        scope: CoroutineScope,
        bookSource: BookSource,
        key: String,
        page: Int? = 1,
        context: CoroutineContext = Dispatchers.IO,
        start: CoroutineStart = CoroutineStart.DEFAULT,
        executeContext: CoroutineContext = Dispatchers.Main,
    ): Coroutine<ArrayList<SearchBook>> {
        return Coroutine.async(scope, context, start = start, executeContext = executeContext) {
            searchBookAwait(bookSource, key, page)
        }
    }

    suspend fun searchBookAwait(
        bookSource: BookSource,
        key: String,
        page: Int? = 1,
        filter: ((name: String, author: String) -> Boolean)? = null,
        shouldBreak: ((size: Int) -> Boolean)? = null
    ): ArrayList<SearchBook> {
        if (CloudReader.isRemote(bookSource)) {
            val results = CloudReader.search(bookSource, key, page)
            val filtered = arrayListOf<SearchBook>()
            for (result in results) {
                if (filter == null || filter(result.name, result.author)) filtered.add(result)
                if (shouldBreak?.invoke(filtered.size) == true) break
            }
            return filtered
        }
        val searchUrl = bookSource.searchUrl
        if (searchUrl.isNullOrBlank()) {
            throw NoStackTraceException("搜索url不能为空")
        }
        val ruleData = RuleData()
        val analyzeUrl = AnalyzeUrl(
            mUrl = searchUrl,
            key = key,
            page = page,
            baseUrl = bookSource.bookSourceUrl,
            source = bookSource,
            ruleData = ruleData,
            coroutineContext = coroutineContext
        )
        var res = analyzeUrl.getStrResponseAwait()
        //检测书源是否已登录
        bookSource.loginCheckJs?.let { checkJs ->
            if (checkJs.isNotBlank()) {
                res = analyzeUrl.evalJS(checkJs, res) as StrResponse
            }
        }
        checkRedirect(bookSource, res)
        return BookList.analyzeBookList(
            bookSource = bookSource,
            ruleData = ruleData,
            analyzeUrl = analyzeUrl,
            baseUrl = res.url,
            body = res.body,
            isSearch = true,
            isRedirect = res.raw.priorResponse?.isRedirect == true,
            filter = filter,
            shouldBreak = shouldBreak
        )
    }

    /**
     * 发现
     */
    fun exploreBook(
        scope: CoroutineScope,
        bookSource: BookSource,
        url: String,
        page: Int? = 1,
        context: CoroutineContext = Dispatchers.IO,
    ): Coroutine<List<SearchBook>> {
        return Coroutine.async(scope, context) {
            exploreBookAwait(bookSource, url, page)
        }
    }

    suspend fun exploreBookAwait(
        bookSource: BookSource,
        url: String,
        page: Int? = 1,
    ): ArrayList<SearchBook> {
        if (CloudReader.isRemote(bookSource) || CloudReader.isRemoteRoute(url)) return CloudReader.explore(bookSource, url, page)
        val ruleData = RuleData()
        val analyzeUrl = AnalyzeUrl(
            mUrl = url,
            page = page,
            baseUrl = bookSource.bookSourceUrl,
            source = bookSource,
            ruleData = ruleData,
            coroutineContext = coroutineContext
        )
        var res = analyzeUrl.getStrResponseAwait()
        //检测书源是否已登录
        bookSource.loginCheckJs?.let { checkJs ->
            if (checkJs.isNotBlank()) {
                res = analyzeUrl.evalJS(checkJs, result = res) as StrResponse
            }
        }
        checkRedirect(bookSource, res)
        return BookList.analyzeBookList(
            bookSource = LocalSourceExecutionRepair.forExecution(bookSource),
            ruleData = ruleData,
            analyzeUrl = analyzeUrl,
            baseUrl = res.url,
            body = res.body,
            isSearch = false
        )
    }

    /**
     * 书籍信息
     */
    fun getBookInfo(
        scope: CoroutineScope,
        bookSource: BookSource,
        book: Book,
        context: CoroutineContext = Dispatchers.IO,
        canReName: Boolean = true,
    ): Coroutine<Book> {
        return Coroutine.async(scope, context) {
            getBookInfoAwait(bookSource, book, canReName)
        }
    }

    suspend fun getBookInfoAwait(
        bookSource: BookSource,
        book: Book,
        canReName: Boolean = true,
    ): Book {
        if (CloudReader.isRemote(bookSource, book)) return CloudReader.info(bookSource, book, canReName)
        book.removeAllBookType()
        book.addType(bookSource.getBookType())
        if (!book.infoHtml.isNullOrEmpty()) {
            BookInfo.analyzeBookInfo(
                bookSource = bookSource,
                book = book,
                baseUrl = book.bookUrl,
                redirectUrl = book.bookUrl,
                body = book.infoHtml,
                canReName = canReName
            )
        } else {
            val analyzeUrl = AnalyzeUrl(
                mUrl = book.bookUrl,
                baseUrl = bookSource.bookSourceUrl,
                source = bookSource,
                ruleData = book,
                coroutineContext = coroutineContext
            )
            var res = analyzeUrl.getStrResponseAwait()
            //检测书源是否已登录
            bookSource.loginCheckJs?.let { checkJs ->
                if (checkJs.isNotBlank()) {
                    res = analyzeUrl.evalJS(checkJs, result = res) as StrResponse
                }
            }
            checkRedirect(bookSource, res)
            BookInfo.analyzeBookInfo(
                bookSource = bookSource,
                book = book,
                baseUrl = book.bookUrl,
                redirectUrl = res.url,
                body = res.body,
                canReName = canReName
            )
        }
        return book
    }

    /**
     * 目录
     */
    fun getChapterList(
        scope: CoroutineScope,
        bookSource: BookSource,
        book: Book,
        runPerJs: Boolean = false,
        context: CoroutineContext = Dispatchers.IO
    ): Coroutine<List<BookChapter>> {
        return Coroutine.async(scope, context) {
            getChapterListAwait(bookSource, book, runPerJs).getOrThrow()
        }
    }

    suspend fun runPreUpdateJs(bookSource: BookSource, book: Book): Result<Unit> {
        if (CloudReader.isRemote(bookSource, book)) return Result.success(Unit)
        return kotlin.runCatching {
            val preUpdateJs = bookSource.ruleToc?.preUpdateJs
            if (!preUpdateJs.isNullOrBlank()) {
                AnalyzeRule(book, bookSource, true)
                    .setCoroutineContext(coroutineContext)
                    .evalJS(preUpdateJs)
            }
        }.onFailure {
            coroutineContext.ensureActive()
            AppLog.put("执行preUpdateJs规则失败 书源:${bookSource.bookSourceName}", it)
        }
    }

    suspend fun getChapterListAwait(
        bookSource: BookSource,
        book: Book,
        runPerJs: Boolean = false
    ): Result<List<BookChapter>> {
        book.cloudTocGeneration = io.legado.app.model.ReadBook.cloudTocGenerationFor(book)
        if (CloudReader.isRemote(bookSource, book)) return runCatching { CloudReader.toc(bookSource, book) }
            .onFailure { coroutineContext.ensureActive() }
        book.removeAllBookType()
        book.addType(bookSource.getBookType())
        return kotlin.runCatching {
            if (runPerJs) {
                runPreUpdateJs(bookSource, book).getOrThrow()
            }
            if (book.bookUrl == book.tocUrl && !book.tocHtml.isNullOrEmpty()) {
                BookChapterList.analyzeChapterList(
                    bookSource = bookSource,
                    book = book,
                    baseUrl = book.tocUrl,
                    redirectUrl = book.tocUrl,
                    body = book.tocHtml
                )
            } else {
                val analyzeUrl = AnalyzeUrl(
                    mUrl = book.tocUrl,
                    baseUrl = book.bookUrl,
                    source = bookSource,
                    ruleData = book,
                    coroutineContext = coroutineContext
                )
                var res = analyzeUrl.getStrResponseAwait()
                //检测书源是否已登录
                bookSource.loginCheckJs?.let { checkJs ->
                    if (checkJs.isNotBlank()) {
                        res = analyzeUrl.evalJS(checkJs, result = res) as StrResponse
                    }
                }
                checkRedirect(bookSource, res)
                BookChapterList.analyzeChapterList(
                    bookSource = bookSource,
                    book = book,
                    baseUrl = book.tocUrl,
                    redirectUrl = res.url,
                    body = res.body
                )
            }
        }.onFailure {
            coroutineContext.ensureActive()
        }
    }

    /**
     * 章节内容
     */
    fun getContent(
        scope: CoroutineScope,
        bookSource: BookSource,
        book: Book,
        bookChapter: BookChapter,
        nextChapterUrl: String? = null,
        needSave: Boolean = true,
        context: CoroutineContext = Dispatchers.IO,
        start: CoroutineStart = CoroutineStart.DEFAULT,
        executeContext: CoroutineContext = Dispatchers.Main,
        semaphore: Semaphore? = null,
    ): Coroutine<String> {
        return Coroutine.async(
            scope,
            context,
            start = start,
            executeContext = executeContext,
            semaphore = semaphore
        ) {
            getContentAwait(bookSource, book, bookChapter, nextChapterUrl, needSave)
        }
    }

    suspend fun getContentAwait(
        bookSource: BookSource,
        book: Book,
        bookChapter: BookChapter,
        nextChapterUrl: String? = null,
        needSave: Boolean = true
    ): String {
        val source = BookSourceAutoRemoval.currentSource(bookSource)
        if (source == null) {
            if (needSave && MultiSourceManager.enabled) {
                return MultiSourceManager.enrich(null, book, bookChapter, "")
            }
            throw NoStackTraceException("原书源已确认移除，请导入有效书源")
        }
        if (!needSave || !MultiSourceManager.enabled) {
            try {
                return getRawContentAwait(source, book, bookChapter, nextChapterUrl, needSave)
            } catch (error: Exception) {
                if (needSave) BookSourceAutoRemoval.afterPrimaryFailure(error)
                throw error
            }
        }
        val primary = runCatching {
            val readingBook = io.legado.app.model.ReadBook.book
            val foreground = readingBook == null ||
                (readingBook.bookUrl == book.bookUrl &&
                    io.legado.app.model.ReadBook.durChapterIndex == bookChapter.index)
            // A removed site can otherwise consume the HTTP client's full minute
            // before automatic recovery even starts. This timeout only bounds the
            // primary request; cancellation of the reader still propagates.
            val primaryBudget = if (CloudReader.isRemote(source, book)) 10_500L else if (foreground) 4500L else 6500L
            withTimeoutOrNull(primaryBudget) {
                SourceRequestGate.request(source.bookSourceUrl, foreground) {
                    getRawContentAwait(source, book, bookChapter, nextChapterUrl, true)
                }
            } ?: throw NoStackTraceException("原书源响应超时，未取得正文")
        }.onFailure { if (it is CancellationException) throw it }
        BookSourceAutoRemoval.afterPrimaryFailure(primary.exceptionOrNull())
        return try {
            val result = MultiSourceManager.enrich(source, book, bookChapter, primary.getOrDefault(""))
            if (result.isBlank()) primary.getOrThrow() else result
        } catch (e: CancellationException) { throw e
        } catch (_: Exception) { primary.getOrThrow() }
    }

    private suspend fun getRawContentAwait(
        bookSource: BookSource,
        book: Book,
        bookChapter: BookChapter,
        nextChapterUrl: String?,
        needSave: Boolean
    ): String {
        if (CloudReader.isRemote(bookSource, book)) return CloudReader.content(bookSource, book, bookChapter, nextChapterUrl, needSave)
        if (bookSource.getContentRule().content.isNullOrEmpty()) {
            Debug.log(bookSource.bookSourceUrl, "⇒正文规则为空,使用章节链接:${bookChapter.url}")
            return bookChapter.url
        }
        if (bookChapter.isVolume && bookChapter.url.startsWith(bookChapter.title)) {
            Debug.log(bookSource.bookSourceUrl, "⇒一级目录正文不解析规则")
            return bookChapter.tag ?: ""
        }
        return if (bookChapter.url == book.bookUrl && !book.tocHtml.isNullOrEmpty()) {
            BookContent.analyzeContent(
                bookSource = bookSource,
                book = book,
                bookChapter = bookChapter,
                baseUrl = bookChapter.getAbsoluteURL(),
                redirectUrl = bookChapter.getAbsoluteURL(),
                body = book.tocHtml,
                nextChapterUrl = nextChapterUrl,
                needSave = needSave
            )
        } else {
            val analyzeUrl = AnalyzeUrl(
                mUrl = bookChapter.getAbsoluteURL(),
                baseUrl = book.tocUrl,
                source = bookSource,
                ruleData = book,
                chapter = bookChapter,
                coroutineContext = coroutineContext
            )
            var res = analyzeUrl.getStrResponseAwait(
                jsStr = bookSource.getContentRule().webJs,
                sourceRegex = bookSource.getContentRule().sourceRegex
            )
            if (needSave && (res.code() == 404 || res.code() == 410)) {
                BookSourceAutoRemoval.checkOriginalResponse(bookSource, book, bookChapter,
                    BookSourceAutoRemoval.snapshot(bookSource), res)
            }
            //检测书源是否已登录
            bookSource.loginCheckJs?.let { checkJs ->
                if (checkJs.isNotBlank()) {
                    res = analyzeUrl.evalJS(checkJs, result = res) as StrResponse
                }
            }
            checkRedirect(bookSource, res)
            BookContent.analyzeContent(
                bookSource = bookSource,
                book = book,
                bookChapter = bookChapter,
                baseUrl = bookChapter.getAbsoluteURL(),
                redirectUrl = res.url,
                body = res.body,
                nextChapterUrl = nextChapterUrl,
                needSave = needSave
            ).also { text ->
                if (needSave && res.code() in 200..299) BookSourceAutoRemoval.onReadableResponse(bookSource, bookChapter, text)
            }
        }
    }

    /**
     * 精准搜索
     */
    fun preciseSearch(
        scope: CoroutineScope,
        bookSourceParts: List<BookSourcePart>,
        name: String,
        author: String,
        context: CoroutineContext = Dispatchers.IO,
        semaphore: Semaphore? = null,
    ): Coroutine<Pair<Book, BookSource>> {
        return Coroutine.async(scope, context, semaphore = semaphore) {
            for (s in bookSourceParts) {
                val source = s.getBookSource() ?: continue
                val book = preciseSearchAwait(source, name, author).getOrNull()
                if (book != null) {
                    return@async Pair(book, source)
                }
            }
            throw NoStackTraceException("没有搜索到<$name>$author")
        }
    }

    suspend fun preciseSearchAwait(
        bookSource: BookSource,
        name: String,
        author: String,
    ): Result<Book> {
        return kotlin.runCatching {
            coroutineContext.ensureActive()
            searchBookAwait(
                bookSource, name,
                filter = { fName, fAuthor -> fName == name && fAuthor == author },
                shouldBreak = { it > 0 }
            ).firstOrNull()?.let { searchBook ->
                coroutineContext.ensureActive()
                return@runCatching searchBook.toBook()
            }
            throw NoStackTraceException("未搜索到 $name($author) 书籍")
        }.onFailure {
            coroutineContext.ensureActive()
        }
    }

    /**
     * 检测重定向
     */
    private fun checkRedirect(bookSource: BookSource, response: StrResponse) {
        response.raw.priorResponse?.let {
            if (it.isRedirect) {
                Debug.log(bookSource.bookSourceUrl, "≡检测到重定向(${it.code})")
                Debug.log(bookSource.bookSourceUrl, "┌重定向后地址")
                Debug.log(bookSource.bookSourceUrl, "└${response.url}")
            }
        }
    }

}
