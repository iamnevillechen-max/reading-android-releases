package io.legado.app.model

import io.legado.app.model.cloud.CloudTocStore
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.legado.app.model.multisource.core.ReadingProgressAnchor
import io.legado.app.model.multisource.core.ReadingProgressAnchors
import io.legado.app.model.multisource.core.ReadingPosition
import io.legado.app.model.multisource.MultiSourceManager
import io.legado.app.model.cloud.CloudReader

import io.legado.app.constant.AppLog
import io.legado.app.constant.EventBus
import io.legado.app.constant.PageAnim.scrollPageAnim
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookProgress
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.ReadRecord
import io.legado.app.help.AppWebDav
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.ContentProcessor
import io.legado.app.help.book.isImage
import io.legado.app.help.book.isLocal
import io.legado.app.help.book.isOnLineTxt
import io.legado.app.help.book.isPdf
import io.legado.app.help.book.isSameNameAuthor
import io.legado.app.help.book.readSimulating
import io.legado.app.help.book.simulatedTotalChapterNum
import io.legado.app.help.book.update
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.globalExecutor
import io.legado.app.model.localBook.TextFile
import io.legado.app.model.webBook.WebBook
import io.legado.app.service.BaseReadAloudService
import io.legado.app.service.CacheBookService
import io.legado.app.ui.book.read.page.entities.TextChapter
import io.legado.app.ui.book.read.page.provider.ChapterProvider
import io.legado.app.ui.book.read.page.provider.LayoutProgressListener
import io.legado.app.utils.postEvent
import io.legado.app.utils.stackTraceStr
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Dispatchers.Main
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import splitties.init.appCtx
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.max
import kotlin.math.min


@Suppress("MemberVisibilityCanBePrivate")
object ReadBook : CoroutineScope by MainScope() {
    private const val MAX_POSITION_TEXT = 250_000
    var book: Book? = null
    var callBack: CallBack? = null
    var inBookshelf = false
    var chapterSize = 0
    var simulatedChapterSize = 0
    var durChapterIndex = 0
        set(value) { if (!cloudNavigationBlocked()) field = value }
    var durChapterPos = 0
        set(value) {
            field = value
            cloudTocPending?.takeIf { !it.finishing && book?.bookUrl == it.bookUrl && book?.origin == it.origin }
                ?.latestPosition = value
        }
    var isLocalBook = true
    var chapterChanged = false
    var prevTextChapter: TextChapter? = null
    var curTextChapter: TextChapter? = null
    var nextTextChapter: TextChapter? = null
    var bookSource: BookSource? = null
    private data class PendingChapterSpeech(val epoch: Long, val bookUrl: String, val origin: String,
        val layout: TextChapter)

    private val pendingChapterSpeech = AtomicReference<PendingChapterSpeech?>(null)
    var msg: String? = null
    private val loadingChapters = arrayListOf<Int>()
    private val readRecord = ReadRecord()
    private val chapterLoadingJobs = ConcurrentHashMap<Int, Coroutine<*>>()
    private val prevChapterLoadingLock = Mutex()
    private val curChapterLoadingLock = Mutex()
    private val nextChapterLoadingLock = Mutex()
    @Volatile private var cloudTocEpoch = 0L
    @Volatile private var cloudTocPending: CloudTocSnapshot? = null
    private val cloudMutationLock = Any()
    private val cloudMutations = hashMapOf<String, Int>()
    private val cloudMutationRevision = hashMapOf<String, Long>()
    private var cloudTocSerial = 0L
    private val cloudTocGenerations = ConcurrentHashMap<String, Long>()
    private fun cloudBookKey(url: String, origin: String) = url + "\n" + origin
    val isCloudTocUpdating get() = cloudTocPending != null
    data class CloudTocSnapshot(val bookUrl: String, val origin: String, val name: String, val author: String, val book: Book?,
        val index: Int, val position: Int, val rendered: TextChapter?, val epoch: Long,
        val generation: Long,
        @Volatile var latestPosition: Int = position, var finishing: Boolean = false, var cancelNotified: Boolean = false)

    private fun cloudNavigationBlocked(): Boolean {
        val pending = cloudTocPending ?: return false
        if (pending.finishing || book?.bookUrl != pending.bookUrl || book?.origin != pending.origin) return false
        if (!pending.cancelNotified) {
            pending.cancelNotified = true
            appCtx.toastOnUi("目錄正在安全更新，本次跳章已取消，請稍後重試")
        }
        return true
    }
    private fun requireCloudOpenReady(candidate: Book) {
        if (cloudTocPending?.let { !it.finishing && candidate.bookUrl == it.bookUrl } == true) {
            appCtx.toastOnUi("目錄正在安全更新，本次開書已取消，請稍後重試")
            error("目錄正在安全更新，請稍後重新開書")
        }
    }
    fun requireCloudBookMutation(vararg bookUrls: String) {
        if (cloudTocPending?.let { it.bookUrl in bookUrls } == true) {
            appCtx.toastOnUi("目錄正在安全更新，本次書籍變更已取消，請稍後重試")
            error("目錄正在安全更新，請稍後重新變更書籍")
        }
    }
    fun requireCloudBookmarkMutation(name: String, author: String) {
        if (cloudTocPending?.let { ((it.name == name && it.author == author) ||
                (it.book?.name == name && it.book.author == author)) } == true) {
            appCtx.toastOnUi("目錄正在安全更新，本次書籤變更已取消，請稍後重試")
            error("目錄正在安全更新，請稍後重新儲存書籤")
        }
    }
    fun <T> withCloudBookMutation(vararg urls: String, action: () -> T): T {
        val ids = urls.toSet()
        synchronized(cloudMutationLock) {
            requireCloudBookMutation(*urls)
            ids.forEach { cloudMutations[it] = (cloudMutations[it] ?: 0) + 1 }
        }
        return try { action() } finally {
            synchronized(cloudMutationLock) {
                ids.forEach {
                    cloudMutations[it] = (cloudMutations[it] ?: 1) - 1
                    cloudMutationRevision[it] = (cloudMutationRevision[it] ?: 0) + 1
                }
            }
        }
    }
    fun cloudTocGenerationFor(target: Book): Long = cloudTocGenerations[cloudBookKey(target.bookUrl, target.origin)] ?: 0L

    /** Main creates the gate before IO writes; IO never awaits Main while holding Room. */
    fun beginCloudToc(target: Book): CloudTocSnapshot {
        val revision = synchronized(cloudMutationLock) {
            check(cloudTocPending == null && (cloudMutations[target.bookUrl] ?: 0) == 0) { "書籍正在變更，稍後重試目錄更新" }
            cloudMutationRevision[target.bookUrl] ?: 0
        }
        // Never hold the state monitor while accessing Room.
        val latestBook = appDb.bookDao.getBook(target.bookUrl)
        check(latestBook?.origin == target.origin) { "書籍或書源已變更，保留舊目錄" }
        val active = book?.takeIf { it.bookUrl == target.bookUrl }
        check(active == null || active.origin == target.origin) { "書源已變更，保留舊目錄" }
        val snapshot = synchronized(cloudMutationLock) {
            check(cloudTocPending == null && (cloudMutations[target.bookUrl] ?: 0) == 0 &&
                (cloudMutationRevision[target.bookUrl] ?: 0) == revision) { "書籍正在變更，稍後重試目錄更新" }
            if (active != null) cloudTocEpoch++
            val generation = ++cloudTocSerial
            cloudTocGenerations[cloudBookKey(target.bookUrl, target.origin)] = generation
            if (cloudTocGenerations.size > 1024) cloudTocGenerations.entries.minByOrNull { it.value }?.let { cloudTocGenerations.remove(it.key, it.value) }
            CloudTocSnapshot(target.bookUrl, target.origin, target.name, target.author, active,
                if (active != null) durChapterIndex else target.durChapterIndex,
                if (active != null) durChapterPos else target.durChapterPos,
                if (active != null) curTextChapter else null, cloudTocEpoch, generation).also { cloudTocPending = it }
        }
        if (active != null) {
            clearExpiredChapterLoadingJob(true); preDownloadTask?.cancel(); downloadScope.coroutineContext.cancelChildren()
        }
        return snapshot
    }

    /** Main flushes the latest same-chapter offset after the background TOC transaction commits. */
    fun finishCloudToc(snapshot: CloudTocSnapshot, installed: Book, currentChapter: BookChapter,
        mapping: Map<Int, Int>): Book {
        check(cloudTocPending === snapshot && cloudTocGenerations[cloudBookKey(snapshot.bookUrl, snapshot.origin)] == snapshot.generation) { "閱讀狀態已變更" }
        snapshot.finishing = true
        var result = installed
        try {
            appDb.runInTransaction {
                val latest = appDb.bookDao.getBook(installed.bookUrl) ?: error("書籍已移除")
                check(latest.origin == installed.origin) { "書源已變更" }
                // External progress writes win. Only our unchanged transaction result may
                // receive the later offset accumulated while this same chapter stayed open.
                result = latest.copy(readConfig = latest.readConfig?.copy()).apply {
                    if (snapshot.book != null && latest.durChapterIndex == installed.durChapterIndex &&
                        latest.durChapterPos == installed.durChapterPos) durChapterPos = snapshot.latestPosition
                    cloudTocInstalled = true
                    cloudTocGeneration = snapshot.generation
                }
                val rendered = curTextChapter?.takeIf { book?.bookUrl == snapshot.bookUrl && book?.origin == snapshot.origin &&
                    it.chapter.bookUrl == snapshot.bookUrl && it.chapter.index == snapshot.index && it.isCompleted &&
                    it.chapter.getAbsoluteURL() == currentChapter.getAbsoluteURL() && result.durChapterIndex == installed.durChapterIndex }
                rendered?.let { old -> boundedRenderedText(old)?.let { text ->
                    ReadingProgressAnchors.capture(result.bookUrl, currentChapter.url, result.durChapterIndex,
                        text, result.durChapterPos)?.let { result.config.multiSourcePositionAnchor = encodePositionAnchor(it) }
                } }
                appDb.bookDao.updateForCloudToc(result)
            }
            if (book?.bookUrl == snapshot.bookUrl && book?.origin == snapshot.origin) {
                val retained = curTextChapter?.takeIf { it.chapter.bookUrl == result.bookUrl && it.chapter.index == snapshot.index &&
                    it.chapter.getAbsoluteURL() == currentChapter.getAbsoluteURL() &&
                    result.durChapterIndex == installed.durChapterIndex }
                book = result; durChapterIndex = result.durChapterIndex; durChapterPos = result.durChapterPos
                chapterSize = result.totalChapterNum; simulatedChapterSize = result.simulatedTotalChapterNum()
                prevTextChapter = null; nextTextChapter = null; curTextChapter = retained
                retained?.chapter?.index = result.durChapterIndex
                initialPosition = InitialPosition(result.bookUrl, result.durChapterIndex, result.durChapterPos,
                    result.readConfig?.multiSourcePositionAnchor, retained)
                heldPosition = null
                bookmarkAnchor = bookmarkAnchor?.takeIf { it.first == result.bookUrl }?.let { value ->
                    mapping[value.second]?.let { Triple(value.first, it, value.third) }
                }
                lastBookProgress = lastBookProgress?.let { value -> mapping[value.durChapterIndex]?.let { value.copy(durChapterIndex = it) } }
                webBookProgress = webBookProgress?.let { value -> mapping[value.durChapterIndex]?.let { value.copy(durChapterIndex = it) } }
                synchronized(this) { loadingChapters.clear(); downloadedChapters.clear(); downloadFailChapters.clear() }
            }
            return result
        } finally {
            cloudTocPending = null
            if (book?.bookUrl == snapshot.bookUrl && book?.origin == snapshot.origin) cloudTocEpoch++
        }
    }

    fun abortCloudToc(snapshot: CloudTocSnapshot) {
        if (cloudTocPending !== snapshot) return
        cloudTocPending = null
        if (book?.bookUrl == snapshot.bookUrl && book?.origin == snapshot.origin) cloudTocEpoch++
        if (book?.bookUrl == snapshot.bookUrl && book?.origin == snapshot.origin) saveRead()
    }

    private data class InitialPosition(val bookUrl: String, val index: Int, val position: Int,
        val anchorJson: String?, val rendered: TextChapter? = null)
    private data class PositionPlan(val prelaid: Boolean = false, val position: Int? = null,
        val anchorJson: String? = null, val bookmark: Triple<String, Int, String>? = null,
        val expectedPosition: Int? = null, val previous: TextChapter? = null)
    @Volatile private var initialPosition: InitialPosition? = null
    @Volatile private var heldPosition: Pair<String, Int>? = null
    var readStartTime: Long = System.currentTimeMillis()

    /* 跳转进度前进度记录 */
    var lastBookProgress: BookProgress? = null

    /* web端阅读进度记录 */
    var webBookProgress: BookProgress? = null

    var preDownloadTask: Job? = null
    val downloadedChapters = hashSetOf<Int>()
    val downloadFailChapters = hashMapOf<Int, Int>()
    var contentProcessor: ContentProcessor? = null
    val downloadScope = CoroutineScope(SupervisorJob() + IO)
    val preDownloadSemaphore = Semaphore(2)
    val executor = globalExecutor

    internal val readingEpoch: Long get() = cloudTocEpoch

    fun resetData(book: Book) {
        requireCloudOpenReady(book)
        cloudTocEpoch++
        releaseAndCancel()
        ReadBook.book = book
        initialPosition = InitialPosition(book.bookUrl, book.durChapterIndex, book.durChapterPos,
            book.readConfig?.multiSourcePositionAnchor)
        readRecord.bookName = book.name
        readRecord.readTime = appDb.readRecordDao.getReadTime(book.name) ?: 0
        chapterSize = appDb.bookChapterDao.getChapterCount(book.bookUrl)
        simulatedChapterSize = if (book.readSimulating()) {
            book.simulatedTotalChapterNum()
        } else {
            chapterSize
        }
        contentProcessor = ContentProcessor.get(book)
        durChapterIndex = book.durChapterIndex
        durChapterPos = book.durChapterPos
        isLocalBook = book.isLocal
        clearTextChapter()
        callBack?.upContent()
        callBack?.upMenuView()
        callBack?.upPageAnim()
        upWebBook(book)
        lastBookProgress = null
        webBookProgress = null
        TextFile.clear()
        synchronized(this) {
            loadingChapters.clear()
            downloadedChapters.clear()
            downloadFailChapters.clear()
        }
    }

    fun upData(book: Book) {
        requireCloudOpenReady(book)
        cloudTocEpoch++
        releaseAndCancel()
        ReadBook.book = book
        chapterSize = appDb.bookChapterDao.getChapterCount(book.bookUrl)
        simulatedChapterSize = if (book.readSimulating()) {
            book.simulatedTotalChapterNum()
        } else {
            chapterSize
        }
        if (durChapterIndex != book.durChapterIndex) {
            durChapterIndex = book.durChapterIndex
            durChapterPos = book.durChapterPos
            clearTextChapter()
        }
        if (curTextChapter?.isCompleted == false) {
            curTextChapter = null
        }
        if (nextTextChapter?.isCompleted == false) {
            nextTextChapter = null
        }
        if (curTextChapter == null) {
            initialPosition = InitialPosition(book.bookUrl, book.durChapterIndex, book.durChapterPos,
                book.readConfig?.multiSourcePositionAnchor)
        }
        if (prevTextChapter?.isCompleted == false) {
            prevTextChapter = null
        }
        callBack?.upMenuView()
        upWebBook(book)
        synchronized(this) {
            loadingChapters.clear()
            downloadedChapters.clear()
            downloadFailChapters.clear()
        }
    }

    fun upWebBook(book: Book) {
        if (book.isLocal) {
            bookSource = null
            if (book.getImageStyle().isNullOrBlank() && (book.isImage || book.isPdf)) {
                book.setImageStyle(Book.imgStyleFull)
            }
        } else {
            appDb.bookSourceDao.getBookSource(book.origin)?.let {
                bookSource = it
                if (book.getImageStyle().isNullOrBlank()) {
                    var imageStyle = it.getContentRule().imageStyle
                    if (imageStyle.isNullOrBlank() && (book.isImage || book.isPdf)) {
                        imageStyle = Book.imgStyleFull
                    }
                    book.setImageStyle(imageStyle)
                    if (imageStyle.equals(Book.imgStyleSingle, true)) {
                        book.setPageAnim(0)
                    }
                }
            } ?: let {
                bookSource = null
            }
        }
    }

    fun upReadBookConfig(book: Book) {
        val oldIndex = ReadBookConfig.styleSelect
        ReadBookConfig.isComic = book.isImage
        if (oldIndex != ReadBookConfig.styleSelect) {
            postEvent(EventBus.UP_CONFIG, arrayListOf(1, 2, 5))
            if (AppConfig.readBarStyleFollowPage) {
                postEvent(EventBus.UPDATE_READ_ACTION_BAR, true)
            }
        }
    }

    fun setProgress(progress: BookProgress) {
        if (cloudNavigationBlocked()) return
        if (progress.durChapterIndex < chapterSize &&
            (durChapterIndex != progress.durChapterIndex
                    || durChapterPos != progress.durChapterPos)
        ) {
            initialPosition = null
            heldPosition = null
            durChapterIndex = progress.durChapterIndex
            durChapterPos = progress.durChapterPos
            saveRead()
            clearTextChapter()
            callBack?.upContent()
            loadContent(resetPageOffset = true)
        }
    }

    //暂时保存跳转前进度
    fun saveCurrentBookProgress() {
        if (lastBookProgress != null) return //避免进度条连续跳转不能覆盖最初的进度记录
        lastBookProgress = book?.let { BookProgress(it) }
    }

    //恢复跳转前进度
    fun restoreLastBookProgress() {
        lastBookProgress?.let {
            setProgress(it)
            lastBookProgress = null
        }
    }

    fun clearTextChapter() {
        pendingChapterSpeech.set(null)
        clearExpiredChapterLoadingJob(true)
        prevTextChapter = null
        curTextChapter = null
        nextTextChapter = null
    }

    fun clearSearchResult() {
        curTextChapter?.clearSearchResult()
        prevTextChapter?.clearSearchResult()
        nextTextChapter?.clearSearchResult()
    }

    fun uploadProgress(toast: Boolean = false, successAction: (() -> Unit)? = null) {
        book?.let {
            launch(IO) {
                AppWebDav.uploadBookProgress(it, toast) {
                    successAction?.invoke()
                }
                ensureActive()
                it.update()
            }
        }
    }

    /**
     * 同步阅读进度
     * 如果当前进度快于服务器进度或者没有进度进行上传，如果慢与服务器进度则执行传入动作
     */
    fun syncProgress(
        newProgressAction: ((progress: BookProgress) -> Unit)? = null,
        uploadSuccessAction: (() -> Unit)? = null,
        syncSuccessAction: (() -> Unit)? = null
    ) {
        if (!AppConfig.syncBookProgress) return
        val book = book ?: return
        Coroutine.async {
            AppWebDav.getBookProgress(book)
        }.onError {
            AppLog.put("拉取阅读进度失败", it)
        }.onSuccess { progress ->
            if (progress == null || progress.durChapterIndex < book.durChapterIndex ||
                (progress.durChapterIndex == book.durChapterIndex
                        && progress.durChapterPos < book.durChapterPos)
            ) {
                // 服务器没有进度或者进度比服务器快，上传现有进度
                Coroutine.async {
                    AppWebDav.uploadBookProgress(BookProgress(book), uploadSuccessAction)
                    book.update()
                }
            } else if (progress.durChapterIndex > book.durChapterIndex ||
                progress.durChapterPos > book.durChapterPos
            ) {
                // 进度比服务器慢，执行传入动作
                newProgressAction?.invoke(progress)
            } else {
                syncSuccessAction?.invoke()
            }
        }
    }

    fun upReadTime() {
        executor.execute {
            if (!AppConfig.enableReadRecord) {
                return@execute
            }
            readRecord.readTime = readRecord.readTime + System.currentTimeMillis() - readStartTime
            readStartTime = System.currentTimeMillis()
            readRecord.lastRead = System.currentTimeMillis()
            appDb.readRecordDao.insert(readRecord)
        }
    }

    fun upMsg(msg: String?) {
        if (ReadBook.msg != msg) {
            ReadBook.msg = msg
            callBack?.upContent()
        }
    }

    fun moveToNextPage(): Boolean {
        var hasNextPage = false
        curTextChapter?.let {
            val nextPagePos = it.getNextPageLength(durChapterPos)
            if (nextPagePos >= 0) {
                hasNextPage = true
                it.getPage(durPageIndex)?.removePageAloudSpan()
                durChapterPos = nextPagePos
                callBack?.cancelSelect()
                callBack?.upContent()
                saveRead(true)
            }
        }
        return hasNextPage
    }

    fun moveToPrevPage(): Boolean {
        var hasPrevPage = false
        curTextChapter?.let {
            val prevPagePos = it.getPrevPageLength(durChapterPos)
            if (prevPagePos >= 0) {
                hasPrevPage = true
                durChapterPos = prevPagePos
                callBack?.upContent()
                saveRead(true)
            }
        }
        return hasPrevPage
    }

    fun moveToNextChapter(upContent: Boolean, upContentInPlace: Boolean = true): Boolean {
        if (cloudNavigationBlocked()) return false
        if (durChapterIndex < simulatedChapterSize - 1) {
            durChapterPos = 0
            durChapterIndex++
            clearExpiredChapterLoadingJob()
            prevTextChapter = curTextChapter
            curTextChapter = nextTextChapter
            nextTextChapter = null
            if (curTextChapter == null) {
                AppLog.putDebug("moveToNextChapter-章节未加载,开始加载")
                if (upContentInPlace) callBack?.upContent()
                loadContent(durChapterIndex, upContent, resetPageOffset = false)
            } else if (upContent && upContentInPlace) {
                AppLog.putDebug("moveToNextChapter-章节已加载,刷新视图")
                callBack?.upContent()
            }
            loadContent(durChapterIndex.plus(1), upContent, false)
            saveRead()
            callBack?.upMenuView()
            AppLog.putDebug("moveToNextChapter-curPageChanged()")
            curPageChanged(advancedChapter = true)
            return true
        } else {
            AppLog.putDebug("跳转下一章失败,没有下一章")
            return false
        }
    }

    suspend fun moveToNextChapterAwait(
        upContent: Boolean,
        upContentInPlace: Boolean = true
    ): Boolean {
        if (cloudNavigationBlocked()) return false
        if (durChapterIndex < simulatedChapterSize - 1) {
            durChapterPos = 0
            durChapterIndex++
            clearExpiredChapterLoadingJob()
            prevTextChapter = curTextChapter
            curTextChapter = nextTextChapter
            nextTextChapter = null
            if (curTextChapter == null) {
                AppLog.putDebug("moveToNextChapter-章节未加载,开始加载")
                if (upContentInPlace) callBack?.upContentAwait()
                loadContentAwait(durChapterIndex, upContent, resetPageOffset = false)
            } else if (upContent && upContentInPlace) {
                AppLog.putDebug("moveToNextChapter-章节已加载,刷新视图")
                callBack?.upContentAwait()
            }
            loadContent(durChapterIndex.plus(1), upContent, false)
            saveRead()
            callBack?.upMenuView()
            AppLog.putDebug("moveToNextChapter-curPageChanged()")
            curPageChanged(advancedChapter = true)
            return true
        } else {
            AppLog.putDebug("跳转下一章失败,没有下一章")
            return false
        }
    }

    fun moveToPrevChapter(
        upContent: Boolean,
        toLast: Boolean = true,
        upContentInPlace: Boolean = true
    ): Boolean {
        if (cloudNavigationBlocked()) return false
        if (durChapterIndex > 0) {
            durChapterPos = if (toLast) prevTextChapter?.lastReadLength ?: Int.MAX_VALUE else 0
            durChapterIndex--
            clearExpiredChapterLoadingJob()
            nextTextChapter = curTextChapter
            curTextChapter = prevTextChapter
            prevTextChapter = null
            if (curTextChapter == null) {
                if (upContentInPlace) callBack?.upContent()
                loadContent(durChapterIndex, upContent, resetPageOffset = false)
            } else if (upContent && upContentInPlace) {
                callBack?.upContent()
            }
            loadContent(durChapterIndex.minus(1), upContent, false)
            saveRead()
            callBack?.upMenuView()
            curPageChanged()
            return true
        } else {
            return false
        }
    }

    fun skipToPage(index: Int, success: (() -> Unit)? = null) {
        durChapterPos = curTextChapter?.getReadLength(index) ?: index
        callBack?.upContent {
            success?.invoke()
        }
        curPageChanged()
        saveRead(true)
    }

    fun setPageIndex(index: Int) {
        recycleRecorders(durPageIndex, index)
        durChapterPos = curTextChapter?.getReadLength(index) ?: index
        saveRead(true)
        curPageChanged(true)
    }

    fun recycleRecorders(beforeIndex: Int, afterIndex: Int) {
        if (!AppConfig.optimizeRender) {
            return
        }
        executor.execute {
            val textChapter = curTextChapter ?: return@execute
            if (afterIndex > beforeIndex) {
                textChapter.getPage(afterIndex - 2)?.recycleRecorders()
            }
            if (afterIndex < beforeIndex) {
                textChapter.getPage(afterIndex + 3)?.recycleRecorders()
            }
        }
    }

    @Volatile private var bookmarkAnchor: Triple<String, Int, String>? = null

    /** Old app versions did not record whether an offset referred to raw or derived text. */
    fun holdUnmarkedInitialPosition(readingBook: Book, chapter: BookChapter): Boolean {
        val pending = initialPosition ?: return false
        if (!MultiSourceManager.enabled || !readingBook.isOnLineTxt || pending.position <= 0 ||
            pending.bookUrl != readingBook.bookUrl || pending.index != chapter.index ||
            book?.bookUrl != pending.bookUrl || durChapterIndex != pending.index ||
            durChapterPos != pending.position || bookmarkAnchor?.let {
                it.first == readingBook.bookUrl && it.second == chapter.index
            } == true) return false
        val saved = decodePositionAnchor(pending.anchorJson)
        if (ReadingProgressAnchors.matches(saved, readingBook.bookUrl, chapter.url,
                chapter.index, pending.position)) return false
        heldPosition = readingBook.bookUrl to chapter.index
        return true
    }

    fun isSavedPositionHeld(readingBook: Book, chapter: BookChapter): Boolean =
        heldPosition == (readingBook.bookUrl to chapter.index) && book?.bookUrl == readingBook.bookUrl &&
            durChapterIndex == chapter.index

    private fun decodePositionAnchor(json: String?): ReadingProgressAnchor? = runCatching {
        if (json == null || json.length > 2_048) return@runCatching null
        val value = JsonParser.parseString(json).asJsonObject
        ReadingProgressAnchor(value["version"].asInt, value["bookId"].asString,
            value["chapterId"].asString, value["chapterIndex"].asInt, value["position"].asInt,
            value["renderedHash"].asString, value["quote"].asString, value["quotePosition"].asInt)
    }.getOrNull()

    private fun encodePositionAnchor(anchor: ReadingProgressAnchor): String = JsonObject().apply {
        addProperty("version", anchor.version)
        addProperty("bookId", anchor.bookId)
        addProperty("chapterId", anchor.chapterId)
        addProperty("chapterIndex", anchor.chapterIndex)
        addProperty("position", anchor.position)
        addProperty("renderedHash", anchor.renderedHash)
        addProperty("quote", anchor.quote)
        addProperty("quotePosition", anchor.quotePosition)
    }.toString()

    fun setBookmarkAnchor(index: Int, quote: String?) {
        bookmarkAnchor = book?.bookUrl?.let { url ->
            quote?.takeIf { it.isNotBlank() }?.let { Triple(url, index, it) }
        }
    }

    /** Resolve anchors before swapping a replacement into the reader; never mutate the old page. */
    private suspend fun preserveMultiSourcePosition(
        readingBook: Book, chapter: BookChapter, replacement: TextChapter, resetPageOffset: Boolean
    ): PositionPlan {
        val originalPosition = durChapterPos
        val originalReference = curTextChapter
        val anchor = bookmarkAnchor?.takeIf { it.first == readingBook.bookUrl && it.second == chapter.index }
        val remote = bookSource?.let { CloudReader.isRemote(it, readingBook) } == true
        val previous = originalReference?.takeIf { it.chapter.bookUrl == readingBook.bookUrl &&
            it.chapter.index == chapter.index && it.isCompleted && !resetPageOffset &&
            (MultiSourceManager.enabled || remote) && (!remote || it.chapter.getAbsoluteURL() == chapter.getAbsoluteURL()) }
        val pending = initialPosition?.takeIf { it.bookUrl == readingBook.bookUrl &&
            it.index == chapter.index && it.position == originalPosition }
        val saved = if (readingBook.isOnLineTxt && previous == null)
            decodePositionAnchor(pending?.anchorJson ?: readingBook.readConfig?.multiSourcePositionAnchor)
                ?.takeIf { ReadingProgressAnchors.matches(it, readingBook.bookUrl, chapter.url,
                    chapter.index, originalPosition) } else null
        if (anchor == null && previous == null && saved == null) return PositionPlan()
        // Resolve against the actual fully rendered text, including title, replacement rules,
        // indentation and Unicode offsets. No background cache update enters this path.
        for (ignored in replacement.layoutChannel) { currentCoroutineContext().ensureActive() }
        val newText = boundedRenderedText(replacement) ?: return PositionPlan(prelaid = true)
        val position = when {
            anchor != null -> ReadingPosition.locateBookmark(newText, anchor.third, originalPosition)
            previous != null -> boundedRenderedText(previous)?.let {
                ReadingPosition.relocate(it, newText, originalPosition)
            } ?: ReadingPosition.relocate(newText, newText, originalPosition)
            else -> ReadingProgressAnchors.restore(saved, readingBook.bookUrl, chapter.url,
                chapter.index, newText, originalPosition)
        }
        val replacementAnchor = if (readingBook.isOnLineTxt)
            ReadingProgressAnchors.capture(readingBook.bookUrl, chapter.url, chapter.index, newText, position)
                ?.let(::encodePositionAnchor) else null
        return PositionPlan(true, position, replacementAnchor, anchor, originalPosition, originalReference)
    }

    /** Called in the same main-thread block that installs the replacement chapter. */
    private fun applyPositionPlan(readingBook: Book, chapter: BookChapter, replacement: TextChapter,
        plan: PositionPlan): Boolean {
        if (plan.expectedPosition != null &&
            (durChapterPos != plan.expectedPosition || curTextChapter !== plan.previous)) return false
        plan.position?.let { position ->
            if (plan.anchorJson != null) {
                initialPosition = InitialPosition(readingBook.bookUrl, chapter.index, position,
                    plan.anchorJson, replacement)
            }
            durChapterPos = position
        }
        if (plan.bookmark != null && bookmarkAnchor == plan.bookmark) bookmarkAnchor = null
        return true
    }

    fun openChapter(
        index: Int,
        durChapterPos: Int = 0,
        upContent: Boolean = true,
        success: (() -> Unit)? = null
    ) {
        if (cloudNavigationBlocked()) return
        if (index < chapterSize) {
            initialPosition = null
            heldPosition = null
            clearTextChapter()
            if (upContent) callBack?.upContent()
            durChapterIndex = index
            ReadBook.durChapterPos = durChapterPos
            saveRead()
            loadContent(resetPageOffset = true) {
                success?.invoke()
            }
        }
    }

    /**
     * 当前页面变化
     */
    private fun curPageChanged(pageChanged: Boolean = false, advancedChapter: Boolean = false) {
        if (advancedChapter) pendingChapterSpeech.set(null)
        callBack?.pageChanged()
        curTextChapter?.let {
            if (!BaseReadAloudService.isRun) return@let
            if (it.isCompleted) {
                pendingChapterSpeech.get()?.takeIf { pending -> pending.layout === it }?.let { pending ->
                    pendingChapterSpeech.compareAndSet(pending, null)
                }
                if (pageAnim() == 3 && pageChanged) ReadAloud.pause(appCtx)
                else readAloud(!BaseReadAloudService.pause)
            } else if (advancedChapter && !BaseReadAloudService.pause) {
                val readingBook = book ?: return@let
                pendingChapterSpeech.set(PendingChapterSpeech(cloudTocEpoch, readingBook.bookUrl,
                    readingBook.origin, it))
                // The preloader can finish between the initial check and recording the handoff.
                if (it.isCompleted) launch(Main) { resumePromotedChapterSpeech(it) }
            }
        }
        upReadTime()
        preDownload()
    }

    /** Resume at most once, and only if this exact promoted layout still belongs to the reader. */
    internal fun resumePromotedChapterSpeech(layout: TextChapter, play: () -> Unit = { readAloud() }) {
        val pending = pendingChapterSpeech.get() ?: return
        if (pending.layout !== layout || !layout.isCompleted ||
            !pendingChapterSpeech.compareAndSet(pending, null)) return
        if (cloudTocEpoch == pending.epoch && cloudTocPending?.bookUrl != pending.bookUrl &&
            book?.bookUrl == pending.bookUrl && book?.origin == pending.origin &&
            durChapterIndex == layout.chapter.index && curTextChapter === layout &&
            BaseReadAloudService.isRun && !BaseReadAloudService.pause) {
            play()
        }
    }

    /**
     * 朗读
     */
    fun readAloud(play: Boolean = true, startPos: Int = 0) {
        book ?: return
        val textChapter = curTextChapter ?: return
        if (textChapter.isCompleted) {
            ReadAloud.play(appCtx, play, startPos = startPos)
        }
    }

    /**
     * 当前页数
     */
    val durPageIndex: Int
        get() {
            return curTextChapter?.getPageIndexByCharIndex(durChapterPos) ?: durChapterPos
        }

    /**
     * 是否排版到了当前阅读位置
     */
    val isLayoutAvailable inline get() = durPageIndex >= 0

    val isScroll inline get() = pageAnim() == scrollPageAnim

    val contentLoadFinish get() = curTextChapter != null || msg != null

    /**
     * chapterOnDur: 0为当前页,1为下一页,-1为上一页
     */
    fun textChapter(chapterOnDur: Int = 0): TextChapter? {
        return when (chapterOnDur) {
            0 -> curTextChapter
            1 -> nextTextChapter
            -1 -> prevTextChapter
            else -> null
        }
    }

    /**
     * 加载当前章节和前后一章内容
     * @param resetPageOffset 滚动阅读是否重置滚动位置
     * @param success 当前章节加载完成回调
     */
    fun loadContent(
        resetPageOffset: Boolean,
        success: (() -> Unit)? = null
    ) {
        loadContent(durChapterIndex, resetPageOffset = resetPageOffset) {
            success?.invoke()
        }
        loadContent(durChapterIndex + 1, resetPageOffset = resetPageOffset)
        loadContent(durChapterIndex - 1, resetPageOffset = resetPageOffset)
    }

    fun loadOrUpContent() {
        if (curTextChapter == null) {
            loadContent(durChapterIndex)
        } else {
            callBack?.upContent()
        }
        if (nextTextChapter == null) {
            loadContent(durChapterIndex + 1)
        }
        if (prevTextChapter == null) {
            loadContent(durChapterIndex - 1)
        }
    }

    /**
     * 加载章节内容
     * @param index 章节序号
     * @param upContent 是否更新视图
     * @param resetPageOffset 滚动阅读是否重置滚动位置
     * @param success 加载完成回调
     */
    fun loadContent(
        index: Int,
        upContent: Boolean = true,
        resetPageOffset: Boolean = false,
        success: (() -> Unit)? = null
    ) {
        Coroutine.async {
            val book = book!!
            val chapter = appDb.bookChapterDao.getChapter(book.bookUrl, index) ?: return@async
            if (addLoading(index)) {
                val raw = BookHelp.getContent(book, chapter, allowMultiSource = false)
                cachedChapterContent(book, chapter, raw)?.let { text ->
                    if (ReadBook.book?.bookUrl != book.bookUrl) return@async
                    contentLoadFinish(
                        book,
                        chapter,
                        text,
                        upContent,
                        resetPageOffset,
                        success = success
                    )
                } ?: download(
                    downloadScope,
                    chapter,
                    resetPageOffset
                )
            }
        }.onError {
            AppLog.put("加载正文出错\n${it.localizedMessage}")
        }
    }

    suspend fun loadContentAwait(
        index: Int,
        upContent: Boolean = true,
        resetPageOffset: Boolean = false,
        success: (() -> Unit)? = null
    ) = withContext(IO) {
        if (addLoading(index)) {
            try {
                val book = book!!
                val chapter = appDb.bookChapterDao.getChapter(book.bookUrl, index)!!
                val raw = BookHelp.getContent(book, chapter, allowMultiSource = false)
                val content = cachedChapterContent(book, chapter, raw) ?: downloadAwait(chapter)
                if (ReadBook.book?.bookUrl != book.bookUrl) return@withContext
                contentLoadFinishAwait(book, chapter, content, upContent, resetPageOffset)
                success?.invoke()
            } catch (e: Exception) {
                AppLog.put("加载正文出错\n${e.localizedMessage}")
            } finally {
                removeLoading(index)
            }
        }
    }

    /**
     * 下载正文
     */
    private suspend fun cachedChapterContent(readingBook: Book, chapter: BookChapter, raw: String?): String? {
        if (raw == null && bookSource?.let { CloudReader.isRemote(it, readingBook) } == true) return null
        if (raw == null) {
            if (!readingBook.isOnLineTxt) return null
            // This is an already processed rescue, not new raw input for the merger. A legacy
            // saved position can also retain its matching older readable version here.
            if (holdUnmarkedInitialPosition(readingBook, chapter)) {
                return MultiSourceManager.readableForInitialPosition(readingBook, chapter, "")
                    ?: BookHelp.getContent(readingBook, chapter)
            }
            if (readingBook.bookUrl == book?.bookUrl && chapter.index == durChapterIndex) heldPosition = null
            return BookHelp.getContent(readingBook, chapter)
        }
        if (holdUnmarkedInitialPosition(readingBook, chapter)) {
            // Keep the same pre-enrichment readable version for an offset of unknown origin.
            // It may already be a derived version from an older release; never guess raw.
            return MultiSourceManager.readableForInitialPosition(readingBook, chapter, raw) ?: raw
        }
        if (readingBook.bookUrl == book?.bookUrl && chapter.index == durChapterIndex) heldPosition = null
        return MultiSourceManager.enrichSafely(bookSource, readingBook, chapter, raw)
    }

    private suspend fun downloadIndex(index: Int) {
        if (index < 0) return
        if (index > chapterSize - 1) {
            upToc()
            return
        }
        val book = book ?: return
        val chapter = appDb.bookChapterDao.getChapter(book.bookUrl, index) ?: return
        val original = if (book.isOnLineTxt) BookHelp.getContent(book, chapter, allowMultiSource = false) else null
        val hasOriginal = if (book.isOnLineTxt) !original.isNullOrBlank() else BookHelp.hasContent(book, chapter)
        val savedRescue = if (!hasOriginal && MultiSourceManager.enabled && book.isOnLineTxt)
            BookHelp.getContent(book, chapter)?.takeIf { it.isNotBlank() } else null
        if (hasOriginal || savedRescue != null) {
            if (hasOriginal && MultiSourceManager.enabled && book.isOnLineTxt) {
                val source = bookSource?.takeIf { it.bookSourceUrl == book.origin }
                // An original chapter on disk is not necessarily a prepared multi-source
                // chapter. Reuse the existing cancellable preload job and background limit;
                // only its derived disk cache is updated, never the active page or TTS.
                preDownloadSemaphore.withPermit {
                    currentCoroutineContext().ensureActive()
                    if (ReadBook.book?.bookUrl != book.bookUrl) return@withPermit
                    original?.let { raw -> MultiSourceManager.enrichSafely(source, book, chapter, raw) }
                }
                currentCoroutineContext().ensureActive()
            }
            if (ReadBook.book?.bookUrl == book.bookUrl) downloadedChapters.add(chapter.index)
        } else {
            delay(1000)
            if (addLoading(index)) {
                download(downloadScope, chapter, false, preDownloadSemaphore)
            }
        }
    }

    /**
     * 下载正文
     */
    private fun download(
        scope: CoroutineScope,
        chapter: BookChapter,
        resetPageOffset: Boolean,
        semaphore: Semaphore? = null,
        success: (() -> Unit)? = null
    ) {
        val book = book ?: return removeLoading(chapter.index)
        val bookSource = bookSource
        if (bookSource != null) {
            CacheBook.getOrCreate(bookSource, book).download(scope, chapter, semaphore)
        } else {
            // Retained books may outlive their original source rule. Reuse their saved
            // identity/TOC for verified recovery instead of stopping before the merger.
            Coroutine.async(scope, IO, start = CoroutineStart.LAZY, executeContext = IO, semaphore = semaphore) {
                missingSourceContent(book, chapter)
            }.onSuccess { content ->
                if (ReadBook.book?.bookUrl == book.bookUrl) contentLoadFinish(
                    book, chapter, content, resetPageOffset = resetPageOffset, success = success)
            }.onError {
                if (ReadBook.book?.bookUrl == book.bookUrl) contentLoadFinish(
                    book, chapter, "加载正文失败\n暂未找到可用的同章正文，请稍后重试或手动换源。", resetPageOffset = resetPageOffset, success = success)
            }.onCancel {
                if (ReadBook.book?.bookUrl == book.bookUrl) removeLoading(chapter.index)
            }.start()
        }
    }

    private suspend fun missingSourceContent(book: Book, chapter: BookChapter): String {
        val rescued = MultiSourceManager.enrichSafely(null, book, chapter, "")
        if (rescued.isNotBlank()) return rescued
        val msg = if (book.isLocal) "无内容" else "暂未找到可用的同章正文，请稍后重试或手动换源。"
        return "加载正文失败\n$msg"
    }

    private suspend fun downloadAwait(chapter: BookChapter): String {
        val book = book!!
        val bookSource = bookSource
        if (bookSource != null) {
            return CacheBook.getOrCreate(bookSource, book).downloadAwait(chapter)
        } else {
            return missingSourceContent(book, chapter)
        }
    }

    @Synchronized
    private fun addLoading(index: Int): Boolean {
        if (loadingChapters.contains(index)) return false
        loadingChapters.add(index)
        return true
    }

    @Synchronized
    fun removeLoading(index: Int) {
        loadingChapters.remove(index)
    }

    /**
     * 内容加载完成
     */
    private fun currentLoader(epoch: Long, target: Book, chapter: BookChapter, offset: Int): Boolean =
        cloudTocEpoch == epoch && cloudTocPending?.bookUrl != target.bookUrl &&
            book?.bookUrl == target.bookUrl && book?.origin == target.origin && chapter.index - durChapterIndex == offset

    @Synchronized
    fun contentLoadFinish(
        book: Book,
        chapter: BookChapter,
        content: String,
        upContent: Boolean = true,
        resetPageOffset: Boolean,
        canceled: Boolean = false,
        success: (() -> Unit)? = null
    ) {
        removeLoading(chapter.index)
        if (cloudTocPending?.bookUrl == book.bookUrl) return
        if (canceled || chapter.index !in durChapterIndex - 1..durChapterIndex + 1) {
            return
        }
        chapterLoadingJobs[chapter.index]?.cancel()
        val tocEpoch = cloudTocEpoch
        val job = Coroutine.async(this, start = CoroutineStart.LAZY) {
            if (bookSource?.let { CloudReader.isRemote(it, book) } == true &&
                appDb.bookChapterDao.getChapter(book.bookUrl, chapter.index)?.url != chapter.url) return@async
            val contentProcessor = ContentProcessor.get(book.name, book.origin)
            val displayTitle = chapter.getDisplayTitle(
                contentProcessor.getTitleReplaceRules(),
                book.getUseReplaceRule()
            )
            val contents = contentProcessor
                .getContent(book, chapter, content, includeTitle = false)
            ensureActive()
            val textChapter = ChapterProvider.getTextChapterAsync(
                this, book, chapter, displayTitle, contents, simulatedChapterSize
            )
            when (val offset = chapter.index - durChapterIndex) {
                0 -> curChapterLoadingLock.withLock {
                    val positionPlan = preserveMultiSourcePosition(book, chapter, textChapter, resetPageOffset)
                    if (ReadBook.book?.bookUrl != book.bookUrl || durChapterIndex != chapter.index) return@withLock
                    val installed = withContext(Main) {
                        ensureActive()
                        if (!currentLoader(tocEpoch, book, chapter, 0)) return@withContext false
                        if (!applyPositionPlan(book, chapter, textChapter, positionPlan)) return@withContext false
                        curTextChapter = textChapter
                        true
                    }
                    if (!installed) return@withLock
                    if (positionPlan.prelaid) textChapter.pages.forEach { callBack?.onLayoutPageCompleted(it.index, it) }
                    callBack?.upMenuView()
                    var available = false
                    for (page in textChapter.layoutChannel) {
                        val index = page.index
                        if (!available && page.containPos(durChapterPos)) {
                            if (upContent) {
                                callBack?.upContent(offset, resetPageOffset)
                            }
                            available = true
                        }
                        if (upContent && isScroll) {
                            if (max(index - 3, 0) < durPageIndex) {
                                callBack?.upContent(offset, false)
                            }
                        }
                        callBack?.onLayoutPageCompleted(index, page)
                    }
                    if (upContent) callBack?.upContent(offset, !available && resetPageOffset)
                    curPageChanged()
                    rememberRenderedPosition(book, chapter, textChapter)
                    callBack?.contentLoadFinish()
                }

                -1 -> prevChapterLoadingLock.withLock {
                    val installed = withContext(Main) {
                        ensureActive()
                        if (!currentLoader(tocEpoch, book, chapter, -1)) return@withContext false
                        prevTextChapter = textChapter
                        true
                    }
                    if (!installed) return@withLock
                    textChapter.layoutChannel.receiveAsFlow().collect()
                    if (upContent) callBack?.upContent(offset, resetPageOffset)
                }

                1 -> nextChapterLoadingLock.withLock {
                    val installed = withContext(Main) {
                        ensureActive()
                        if (!currentLoader(tocEpoch, book, chapter, 1)) return@withContext false
                        nextTextChapter = textChapter
                        true
                    }
                    if (!installed) return@withLock
                    for (page in textChapter.layoutChannel) {
                        if (page.index > 1) {
                            continue
                        }
                        if (upContent) callBack?.upContent(offset, resetPageOffset)
                    }
                    withContext(Main) { resumePromotedChapterSpeech(textChapter) }
                }
            }

            return@async
        }.onError {
            if (it is CancellationException) {
                return@onError
            }
            AppLog.put("ChapterProvider ERROR", it)
            appCtx.toastOnUi("ChapterProvider ERROR:\n${it.stackTraceStr}")
        }.onSuccess {
            success?.invoke()
        }
        chapterLoadingJobs[chapter.index] = job
        job.start()
    }

    suspend fun contentLoadFinishAwait(
        book: Book,
        chapter: BookChapter,
        content: String,
        upContent: Boolean = true,
        resetPageOffset: Boolean
    ) {
        removeLoading(chapter.index)
        if (cloudTocPending?.bookUrl == book.bookUrl) return
        val tocEpoch = cloudTocEpoch
        if (bookSource?.let { CloudReader.isRemote(it, book) } == true &&
            withContext(IO) { appDb.bookChapterDao.getChapter(book.bookUrl, chapter.index)?.url } != chapter.url) return
        if (chapter.index !in durChapterIndex - 1..durChapterIndex + 1) {
            return
        }
        kotlin.runCatching {
            val contentProcessor = ContentProcessor.get(book.name, book.origin)
            val displayTitle = chapter.getDisplayTitle(
                contentProcessor.getTitleReplaceRules(),
                book.getUseReplaceRule()
            )
            val contents = contentProcessor
                .getContent(book, chapter, content, includeTitle = false)
            val textChapter = ChapterProvider.getTextChapterAsync(
                this@ReadBook, book, chapter, displayTitle, contents, simulatedChapterSize
            )
            when (val offset = chapter.index - durChapterIndex) {
                0 -> {
                    val positionPlan = preserveMultiSourcePosition(book, chapter, textChapter, resetPageOffset)
                    if (ReadBook.book?.bookUrl != book.bookUrl || durChapterIndex != chapter.index) return@runCatching
                    val installed = withContext(Main) {
                        ensureActive()
                        if (!currentLoader(tocEpoch, book, chapter, 0)) return@withContext false
                        if (!applyPositionPlan(book, chapter, textChapter, positionPlan)) return@withContext false
                        curTextChapter?.cancelLayout()
                        curTextChapter = textChapter
                        true
                    }
                    if (!installed) return@runCatching
                    if (positionPlan.prelaid) textChapter.pages.forEach { callBack?.onLayoutPageCompleted(it.index, it) }
                    callBack?.upMenuView()
                    var available = false
                    for (page in textChapter.layoutChannel) {
                        val index = page.index
                        if (!available && page.containPos(durChapterPos)) {
                            if (upContent) {
                                callBack?.upContent(offset, resetPageOffset)
                            }
                            available = true
                        }
                        if (upContent && isScroll) {
                            if (max(index - 3, 0) < durPageIndex) {
                                callBack?.upContent(offset, false)
                            }
                        }
                        callBack?.onLayoutPageCompleted(index, page)
                    }
                    if (upContent) callBack?.upContent(offset, !available && resetPageOffset)
                    curPageChanged()
                    rememberRenderedPosition(book, chapter, textChapter)
                    callBack?.contentLoadFinish()
                }

                -1 -> {
                    prevTextChapter?.cancelLayout()
                    val installed = withContext(Main) {
                        ensureActive()
                        if (!currentLoader(tocEpoch, book, chapter, -1)) return@withContext false
                        prevTextChapter = textChapter
                        true
                    }
                    if (!installed) return@runCatching
                    textChapter.layoutChannel.receiveAsFlow().collect()
                    if (upContent) callBack?.upContent(offset, resetPageOffset)
                }

                1 -> {
                    nextTextChapter?.cancelLayout()
                    val installed = withContext(Main) {
                        ensureActive()
                        if (!currentLoader(tocEpoch, book, chapter, 1)) return@withContext false
                        nextTextChapter = textChapter
                        true
                    }
                    if (!installed) return@runCatching
                    for (page in textChapter.layoutChannel) {
                        if (page.index > 1) {
                            continue
                        }
                        if (upContent) callBack?.upContent(offset, resetPageOffset)
                    }
                    withContext(Main) { resumePromotedChapterSpeech(textChapter) }
                }
            }
        }.onFailure {
            if (it is CancellationException) {
                return@onFailure
            }
            AppLog.put("ChapterProvider ERROR", it)
            appCtx.toastOnUi("ChapterProvider ERROR:\n${it.stackTraceStr}")
        }
    }

    @Synchronized
    fun upToc() {
        val bookSource = bookSource ?: return
        val book = book ?: return
        if (!book.canUpdate) return
        if (chapterSize - durChapterIndex - 1 >= 3) return
        if (System.currentTimeMillis() - book.lastCheckTime < 600000) return
        book.lastCheckTime = System.currentTimeMillis()
        val oldBook = book.copy()
        WebBook.getChapterList(this, bookSource, book).onSuccess(IO) { cList ->
            ensureActive()
            if (cList.size > chapterSize) {
                if (!CloudTocStore.installIfCloud(book, cList)) {
                    if (oldBook.bookUrl == book.bookUrl) {
                        appDb.bookDao.update(book)
                    } else {
                        appDb.bookDao.replace(oldBook, book)
                        BookHelp.updateCacheFolder(oldBook, book)
                    }
                    appDb.bookChapterDao.delByBook(oldBook.bookUrl)
                    appDb.bookChapterDao.insert(*cList.toTypedArray())
                }
                onChapterListUpdated(book, false)
                nextTextChapter ?: loadContent(durChapterIndex + 1)
            }
        }
    }

    fun pageAnim(): Int {
        return book?.getPageAnim() ?: ReadBookConfig.pageAnim
    }

    fun setCharset(charset: String) {
        book?.let {
            it.charset = charset
            callBack?.loadChapterList(it)
        }
        saveRead()
    }

    fun saveRead(pageChanged: Boolean = false) {
        executor.execute {
            kotlin.runCatching {
                val readingBook = book ?: return@execute
                if (cloudTocPending?.let { !it.finishing && it.bookUrl == readingBook.bookUrl && it.origin == readingBook.origin } == true) return@execute
                val tocEpoch = cloudTocEpoch
                val rendered = curTextChapter
                val index = durChapterIndex
                val position = durChapterPos
                val chapterChanged = readingBook.durChapterIndex != index
                val updated = readingBook.copy(readConfig = readingBook.readConfig?.copy()).apply {
                    lastCheckCount = 0
                    durChapterTime = System.currentTimeMillis()
                    durChapterIndex = index
                    durChapterPos = position
                }
                if (readingBook.isOnLineTxt) {
                    val chapter = appDb.bookChapterDao.getChapter(readingBook.bookUrl, index)
                    val pendingReplacement = initialPosition?.takeIf { it.bookUrl == readingBook.bookUrl &&
                        it.index == index && it.position == position }
                    val currentRendered = rendered?.takeIf { text -> chapter != null && text.isCompleted &&
                        text.chapter.bookUrl == readingBook.bookUrl && text.chapter.url == chapter.url &&
                        text.chapter.index == index && book === readingBook && curTextChapter === text &&
                        durChapterIndex == index && durChapterPos == position &&
                        (pendingReplacement?.rendered == null || pendingReplacement.rendered === text) }
                    val anchor = currentRendered?.let { text ->
                        boundedRenderedText(text)?.let { content ->
                            ReadingProgressAnchors.capture(readingBook.bookUrl, chapter!!.url, index, content, position)
                                ?.takeIf { captured -> captured.position == position }
                        }
                    }
                    // A fully laid-out replacement can have resolved the offset just before
                    // curTextChapter is swapped. Carry that exact version through early saves.
                    val oldJson = initialPosition?.takeIf { it.bookUrl == readingBook.bookUrl &&
                        it.index == index && it.position == position }?.anchorJson
                        ?: readingBook.readConfig?.multiSourcePositionAnchor
                    val json = when {
                        anchor != null && (MultiSourceManager.enabled || oldJson != null) -> encodePositionAnchor(anchor)
                        currentRendered == null && chapter != null && ReadingProgressAnchors.matches(decodePositionAnchor(oldJson),
                            readingBook.bookUrl, chapter.url, index, position) -> oldJson
                        else -> null
                    }
                    if (updated.readConfig != null || json != null) {
                        updated.config.multiSourcePositionAnchor = json
                    }
                }
                if (!pageChanged || chapterChanged) {
                    appDb.bookChapterDao.getChapter(readingBook.bookUrl, index)?.let {
                        updated.durChapterTitle = it.getDisplayTitle(
                            ContentProcessor.get(readingBook.name, readingBook.origin).getTitleReplaceRules(),
                            readingBook.getUseReplaceRule()
                        )
                    }
                }
                // Offset and its rendered-text marker are one Room row update. A crash cannot
                // persist a new derived offset paired with an old raw/derived version marker.
                var written = false
                appDb.runInTransaction {
                    if (cloudTocEpoch != tocEpoch || book !== readingBook) return@runInTransaction
                    appDb.bookDao.updateForCloudToc(io.legado.app.model.cloud.CloudTocStore.readerProgress(updated))
                    written = true
                }
                if (!written) return@execute
                if (book === readingBook) {
                    readingBook.lastCheckCount = updated.lastCheckCount
                    readingBook.durChapterTime = updated.durChapterTime
                    readingBook.durChapterIndex = updated.durChapterIndex
                    readingBook.durChapterPos = updated.durChapterPos
                    readingBook.durChapterTitle = updated.durChapterTitle
                    readingBook.readConfig = updated.readConfig
                }
            }.onFailure {
                AppLog.put("保存书籍阅读进度信息出错\n$it", it)
            }
        }
    }

    private suspend fun rememberRenderedPosition(readingBook: Book, chapter: BookChapter, rendered: TextChapter) {
        if (!rendered.isCompleted || book?.bookUrl != readingBook.bookUrl || durChapterIndex != chapter.index ||
            curTextChapter !== rendered) return
        initialPosition = null
        if (readingBook.isOnLineTxt &&
            (MultiSourceManager.enabled || readingBook.readConfig?.multiSourcePositionAnchor != null)) {
            saveRead()
        }
    }

    private fun boundedRenderedText(rendered: TextChapter): String? {
        var length = 0
        for (page in rendered.pages) {
            if (page.text.length > MAX_POSITION_TEXT - length) return null
            length += page.text.length
        }
        return rendered.getContent()
    }

    /**
     * 预下载
     */
    private fun preDownload() {
        if (book?.isLocal == true) return
        executor.execute {
            if (AppConfig.preDownloadNum < 2) {
                upToc()
                return@execute
            }
            preDownloadTask?.cancel()
            val prepareDerived = book?.isOnLineTxt == true && MultiSourceManager.enabled
            preDownloadTask = launch(IO) {
                //预下载
                launch {
                    val maxChapterIndex =
                        min(durChapterIndex + AppConfig.preDownloadNum, chapterSize)
                    for (i in durChapterIndex.plus(2)..maxChapterIndex) {
                        if (downloadedChapters.contains(i) && !prepareDerived) continue
                        if ((downloadFailChapters[i] ?: 0) >= 3) continue
                        downloadIndex(i)
                    }
                }
                launch {
                    val minChapterIndex = durChapterIndex - min(5, AppConfig.preDownloadNum)
                    for (i in durChapterIndex.minus(2) downTo minChapterIndex) {
                        if (downloadedChapters.contains(i) && !prepareDerived) continue
                        if ((downloadFailChapters[i] ?: 0) >= 3) continue
                        downloadIndex(i)
                    }
                }
            }
        }
    }

    fun cancelPreDownloadTask() {
        // The narration service still needs current/adjacent downloads after the screen is locked.
        if (!BaseReadAloudService.isRun && contentLoadFinish) {
            preDownloadTask?.cancel()
            downloadScope.coroutineContext.cancelChildren()
        }
    }

    fun onChapterListUpdated(newBook: Book, loadContent: Boolean = true) {
        if (cloudTocPending?.bookUrl == newBook.bookUrl) return
        val active = book
        if (active != null && cloudTocGenerations.containsKey(cloudBookKey(active.bookUrl, active.origin)) &&
            (active.bookUrl != newBook.bookUrl || active.origin != newBook.origin)) return
        val latestCloudGeneration = cloudTocGenerations[cloudBookKey(newBook.bookUrl, newBook.origin)]
        if (latestCloudGeneration != null && newBook.cloudTocGeneration != latestCloudGeneration) return
        if (newBook.cloudTocInstalled && (newBook.bookUrl != book?.bookUrl || newBook.origin != book?.origin)) return
        if (newBook.cloudTocInstalled && (cloudTocPending?.bookUrl == newBook.bookUrl ||
                newBook.cloudTocGeneration == 0L || cloudTocGenerations[cloudBookKey(newBook.bookUrl, newBook.origin)] != newBook.cloudTocGeneration)) return
        if (newBook.isSameNameAuthor(book)) {
            book = newBook
            chapterSize = newBook.totalChapterNum
            simulatedChapterSize = newBook.simulatedTotalChapterNum()
            if (simulatedChapterSize > 0 && durChapterIndex > simulatedChapterSize - 1) {
                durChapterIndex = simulatedChapterSize - 1
            }
            callBack?.upMenuView()
            if (callBack == null && !BaseReadAloudService.isRun) {
                clearTextChapter()
            } else if (loadContent) {
                loadContent(true)
            }
        }
    }

    private fun clearExpiredChapterLoadingJob(clearAll: Boolean = false) {
        val iterator = chapterLoadingJobs.iterator()
        while (iterator.hasNext()) {
            val (index, job) = iterator.next()
            if (clearAll || index !in durChapterIndex - 1..durChapterIndex + 1) {
                job.cancel()
                iterator.remove()
            }
        }
    }

    /**
     * 注册回调
     */
    fun register(cb: CallBack) {
        callBack?.notifyBookChanged()
        callBack = cb
    }

    /**
     * 取消注册回调
     */
    fun unregister(cb: CallBack) {
        if (callBack !== cb) return
        callBack = null
        releaseIfIdle()
    }

    fun releaseIfIdle() {
        if (callBack == null && !BaseReadAloudService.isRun) releaseAndCancel()
    }

    private fun releaseAndCancel() {
        msg = null
        initialPosition = null
        heldPosition = null
        bookmarkAnchor = null
        preDownloadTask?.cancel()
        downloadScope.coroutineContext.cancelChildren()
        coroutineContext.cancelChildren()
        ImageProvider.clear()
        clearExpiredChapterLoadingJob(true)
        if (!CacheBookService.isRun) {
            CacheBook.close()
        }
    }

    interface CallBack : LayoutProgressListener {
        fun upMenuView()

        fun loadChapterList(book: Book)

        fun upContent(
            relativePosition: Int = 0,
            resetPageOffset: Boolean = true,
            success: (() -> Unit)? = null
        )

        suspend fun upContentAwait(
            relativePosition: Int = 0,
            resetPageOffset: Boolean = true,
            success: (() -> Unit)? = null
        )

        fun pageChanged()

        fun contentLoadFinish()

        fun upPageAnim(upRecorder: Boolean = false)

        fun notifyBookChanged()

        fun sureNewProgress(progress: BookProgress)

        fun cancelSelect()
    }

}
