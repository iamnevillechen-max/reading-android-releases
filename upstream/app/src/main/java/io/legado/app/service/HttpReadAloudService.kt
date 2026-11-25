package io.legado.app.service

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.net.Uri
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.cache.CacheDataSink
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.offline.DefaultDownloaderFactory
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.Downloader
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import com.script.ScriptException
import io.legado.app.R
import io.legado.app.constant.AppLog
import io.legado.app.constant.AppPattern
import io.legado.app.data.entities.HttpTTS
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.config.AppConfig
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.exoplayer.InputStreamDataSource
import io.legado.app.help.http.okHttpClient
import io.legado.app.model.ReadAloud
import io.legado.app.model.ReadBook
import io.legado.app.model.analyzeRule.AnalyzeUrl
import io.legado.app.ui.book.read.page.entities.TextChapter
import io.legado.app.utils.FileUtils
import io.legado.app.utils.MD5Utils
import io.legado.app.utils.printOnDebug
import io.legado.app.utils.servicePendingIntent
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers.Main
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Response
import org.mozilla.javascript.WrappedException
import splitties.init.appCtx
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import kotlin.time.Duration.Companion.seconds

/**
 * 在线朗读
 */
@SuppressLint("UnsafeOptInUsageError")
class HttpReadAloudService : BaseReadAloudService(),
    Player.Listener {
    private val exoPlayer: ExoPlayer by lazy {
        ExoPlayer.Builder(this).setLoadControl(
            DefaultLoadControl.Builder().setBufferDurationsMs(
                1800_000_000,
                1800_000_000,
                DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_MS,
                DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS
            ).build()
        ).build()
    }
    private val ttsFolderPath: String by lazy {
        cacheDir.absolutePath + File.separator + "httpTTS" + File.separator
    }
    private val cache by lazy {
        SimpleCache(
            File(cacheDir, "httpTTS_cache"),
            LeastRecentlyUsedCacheEvictor(128 * 1024 * 1024),
            StandaloneDatabaseProvider(appCtx)
        )
    }
    private val cacheDataSinkFactory by lazy {
        CacheDataSink.Factory()
            .setCache(cache)
    }
    private val loadErrorHandlingPolicy by lazy {
        CustomLoadErrorHandlingPolicy()
    }
    private var speechRate: Int = AppConfig.speechRatePlay + 5
    private var downloadTask: Coroutine<*>? = null
    private var playIndexJob: Job? = null
    private var downloadErrorNo: Int = 0
    private var playErrorNo = 0
    private val downloadTaskActiveLock = Mutex()
    private val loadingState = MutableStateFlow(false)
    private var fileGeneration = 0L
    private var filePlayback: FilePlayback? = null

    private class FilePlayback(
        val generation: Long,
        val chapter: TextChapter?,
        val paragraphs: List<String>,
        val firstIndex: Int,
        val firstOffset: Int,
        val speed: Int,
        val byPage: Boolean,
        val tts: HttpTTS
    ) {
        val queue = HttpParagraphQueue(firstIndex, paragraphs.lastIndex, FILE_LOOK_AHEAD)
        val playingIndex = MutableStateFlow(firstIndex)
        val initialBuffer = minOf(2, paragraphs.size - firstIndex)
        var startupWait: Job? = null
        var prepared = false
        var failedIndex: Int? = null

        fun mediaId(index: Int) = "http-tts-file-$generation:$index"

        fun paragraphIndex(item: MediaItem?): Int? {
            val id = item?.mediaId ?: return null
            val index = id.substringAfterLast(':').toIntOrNull() ?: return null
            return index.takeIf { id == mediaId(it) }
        }
    }

    override fun onCreate() {
        super.onCreate()
        exoPlayer.addListener(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        discardFilePlayback()
        downloadTask?.cancel()
        exoPlayer.release()
        cache.release()
        Coroutine.async {
            removeCacheFile()
        }
    }

    override fun play() {
        pageChanged = false
        if (filePlayback != null) {
            discardFilePlayback()
            downloadTask?.cancel()
        }
        exoPlayer.stop()
        if (!requestFocus()) return
        if (contentList.isEmpty()) {
            AppLog.putDebug("朗读列表为空")
            ReadBook.readAloud()
        } else {
            super.play()
            if (AppConfig.streamReadAloudAudio) {
                downloadAndPlayAudiosStream()
            } else {
                downloadAndPlayAudios()
            }
        }
    }

    override fun playStop() {
        discardFilePlayback()
        downloadTask?.cancel()
        exoPlayer.stop()
        playIndexJob?.cancel()
    }

    private fun updateNextPos() {
        readAloudNumber += contentList[nowSpeak].length + 1 - paragraphStartPos
        paragraphStartPos = 0
        if (nowSpeak < contentList.lastIndex) {
            nowSpeak++
        } else {
            nextChapter()
        }
    }

    private fun downloadAndPlayAudios() {
        downloadTask?.cancel()
        discardFilePlayback()
        val httpTts = ReadAloud.httpTTS ?: return
        if (nowSpeak !in contentList.indices) return
        val playback = FilePlayback(++fileGeneration, textChapter, contentList, nowSpeak,
            paragraphStartPos, speechRate, readAloudByPage, httpTts)
        filePlayback = playback
        exoPlayer.stop()
        exoPlayer.clearMediaItems()
        downloadTask = execute {
            for (index in playback.firstIndex..playback.paragraphs.lastIndex) {
                playback.playingIndex.first { index - it <= FILE_LOOK_AHEAD }
                var paragraph = playback.paragraphs[index]
                if (index == playback.firstIndex) {
                    paragraph = paragraph.drop(playback.firstOffset.coerceIn(0, paragraph.length))
                }
                val file = try {
                    prepareSpeakFile(playback, paragraph, playback.chapter)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    withContext(Main) {
                        if (isCurrent(playback)) {
                            AppLog.put("朗读下载出错\n${error.localizedMessage}", error)
                            markFileFailure(playback, index)
                        }
                    }
                    return@execute
                }
                withContext(Main) {
                    if (!isCurrent(playback) || !playback.queue.append(index)) {
                        throw CancellationException("Expired HTTP TTS paragraph queue")
                    }
                    exoPlayer.addMediaItem(MediaItem.Builder().setUri(Uri.fromFile(file))
                        .setMediaId(playback.mediaId(index)).build())
                    prepareInitialFilePlayback(playback)
                    continueFilePlayback(playback)
                }
            }
            preDownloadAudios(playback)
        }.onError {
            AppLog.put("朗读下载出错\n${it.localizedMessage}", it, true)
        }
    }

    private fun isCurrent(playback: FilePlayback) = filePlayback === playback &&
        contentList === playback.paragraphs && textChapter === playback.chapter &&
        ReadBook.curTextChapter === playback.chapter

    private fun discardFilePlayback() {
        filePlayback?.startupWait?.cancel()
        filePlayback = null
    }

    private fun prepareInitialFilePlayback(playback: FilePlayback, waitElapsed: Boolean = false) {
        if (!isCurrent(playback) || playback.prepared || exoPlayer.mediaItemCount == 0) return
        if (exoPlayer.mediaItemCount >= playback.initialBuffer || waitElapsed || playback.failedIndex != null) {
            playback.prepared = true
            if (!waitElapsed) playback.startupWait?.cancel()
            exoPlayer.prepare()
        } else if (playback.startupWait == null) {
            playback.startupWait = lifecycleScope.launch {
                delay(FILE_START_WAIT_MS)
                prepareInitialFilePlayback(playback, waitElapsed = true)
            }
        }
    }

    private fun continueFilePlayback(playback: FilePlayback) {
        if (pause || !isCurrent(playback)) return
        val index = playback.queue.takeContinuation() ?: return
        exoPlayer.seekTo(index - playback.firstIndex, 0)
    }

    private fun markFileFailure(playback: FilePlayback, index: Int) {
        playback.failedIndex = index
        prepareInitialFilePlayback(playback)
        if (index == nowSpeak || (playback.queue.waitingForNext && index == nowSpeak + 1)) {
            if (!pause) pauseReadAloud()
        }
    }

    private suspend fun preDownloadAudios(playback: FilePlayback) {
        val textChapter = withContext(Main) {
            ReadBook.nextTextChapter.takeIf { isCurrent(playback) }
        } ?: return
        val nextParagraphs = textChapter.getNeedReadAloud(0, playback.byPage, 0, 1)
            .splitToSequence("\n")
            .filter { it.isNotEmpty() }
            .take(FILE_LOOK_AHEAD)
            .toList()
        for ((index, paragraph) in nextParagraphs.withIndex()) {
            playback.playingIndex.first { playback.paragraphs.lastIndex - it + index < FILE_LOOK_AHEAD }
            try {
                prepareSpeakFile(playback, paragraph, textChapter)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                return
            }
        }
    }

    private fun downloadAndPlayAudiosStream() {
        discardFilePlayback()
        exoPlayer.clearMediaItems()
        downloadTask?.cancel()
        downloadTask = execute {
            downloadTaskActiveLock.withLock {
                ensureActive()
                val httpTts = ReadAloud.httpTTS ?: throw NoStackTraceException("tts is null")
                val downloaderChannel = Channel<Downloader>()
                launch {
                    for (downloader in downloaderChannel) {
                        downloader.download(null)
                    }
                }
                contentList.forEachIndexed { index, content ->
                    ensureActive()
                    if (index < nowSpeak) return@forEachIndexed
                    var text = content
                    if (paragraphStartPos > 0 && index == nowSpeak) {
                        text = text.substring(paragraphStartPos)
                    }
                    val speakText = text.replace(AppPattern.notReadAloudRegex, "")
                    if (speakText.isEmpty()) {
                        AppLog.put("阅读段落内容为空，使用无声音频代替。\n朗读文本：$speakText")
                    }
                    val fileName = md5SpeakFileName(text)
                    val dataSourceFactory = createDataSourceFactory(httpTts, speakText)
                    val mediaSource = createMediaSource(dataSourceFactory, fileName)
                    launch(Main) {
                        exoPlayer.addMediaSource(mediaSource)
                    }
                }
                preDownloadAudiosStream(httpTts, downloaderChannel)
            }
        }.onError {
            AppLog.put("朗读下载出错\n${it.localizedMessage}", it, true)
        }
    }

    @OptIn(FlowPreview::class)
    private suspend fun preDownloadAudiosStream(
        httpTts: HttpTTS,
        downloaderChannel: Channel<Downloader>
    ) {
        val textChapter = ReadBook.nextTextChapter ?: return
        val contentList = textChapter.getNeedReadAloud(0, readAloudByPage, 0, 1)
            .splitToSequence("\n")
            .filter { it.isNotEmpty() }
            .toList()
        val flow = loadingState.debounce(1.seconds)
        contentList.forEach { content ->
            currentCoroutineContext().ensureActive()
            val fileName = md5SpeakFileName(content, textChapter)
            val speakText = content.replace(AppPattern.notReadAloudRegex, "")
            val dataSourceFactory = createDataSourceFactory(httpTts, speakText)
            val downloader = createDownloader(dataSourceFactory, fileName)
            downloaderChannel.send(downloader)
            flow.first { !it }
        }
    }

    private fun createDataSourceFactory(
        httpTts: HttpTTS,
        speakText: String
    ): CacheDataSource.Factory {
        val upstreamFactory = DataSource.Factory {
            InputStreamDataSource {
                if (speakText.isEmpty()) {
                    null
                } else {
                    kotlin.runCatching {
                        runBlocking(lifecycleScope.coroutineContext[Job]!!) {
                            getSpeakStream(httpTts, speakText)
                        }
                    }.onFailure {
                        when (it) {
                            is InterruptedException,
                            is CancellationException -> Unit

                            else -> pauseReadAloud()
                        }
                    }.getOrThrow()
                } ?: resources.openRawResource(R.raw.silent_sound)
            }
        }
        val factory = CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(upstreamFactory)
            .setCacheWriteDataSinkFactory(cacheDataSinkFactory)
        return factory
    }

    private fun createDownloader(factory: CacheDataSource.Factory, fileName: String): Downloader {
        val uri = fileName.toUri()
        val request = DownloadRequest.Builder(fileName, uri).build()
        return DefaultDownloaderFactory(factory, okHttpClient.dispatcher.executorService)
            .createDownloader(request)
    }

    private fun createMediaSource(factory: DataSource.Factory, fileName: String): MediaSource {
        return DefaultMediaSourceFactory(this)
            .setDataSourceFactory(factory)
            .setLoadErrorHandlingPolicy(loadErrorHandlingPolicy)
            .createMediaSource(MediaItem.fromUri(fileName))
    }

    private suspend fun getSpeakStream(
        httpTts: HttpTTS,
        speakText: String,
        fileRate: Int? = null
    ): InputStream? {
        var retriedFileRequest = false
        while (true) {
            try {
                val analyzeUrl = AnalyzeUrl(
                    httpTts.url,
                    speakText = speakText,
                    speakSpeed = fileRate ?: speechRate,
                    source = httpTts,
                    readTimeout = 300 * 1000L,
                    coroutineContext = currentCoroutineContext()
                )
                var response = analyzeUrl.getResponseAwait()
                try {
                    currentCoroutineContext().ensureActive()
                    val checkJs = httpTts.loginCheckJs
                    if (checkJs?.isNotBlank() == true) {
                        response = analyzeUrl.evalJS(checkJs, response) as Response
                    }
                    if (fileRate != null && !response.isSuccessful) {
                        if (response.code in 500..599 || response.code == 408) {
                            throw TemporarySpeechResponse(response.code)
                        }
                        throw IOException("HTTP ${response.code}")
                    }
                    response.headers["Content-Type"]?.let { contentType ->
                        val contentType = contentType.substringBefore(";")
                        val ct = httpTts.contentType
                        if (contentType == "application/json" || contentType.startsWith("text/")) {
                            throw NoStackTraceException(response.body.string())
                        } else if (ct?.isNotBlank() == true) {
                            if (!contentType.matches(ct.toRegex())) {
                                throw NoStackTraceException(
                                    "TTS服务器返回错误：" + response.body.string()
                                )
                            }
                        }
                    }
                    currentCoroutineContext().ensureActive()
                    if (fileRate == null) downloadErrorNo = 0
                    return response.body.byteStream()
                } catch (error: Exception) {
                    if (fileRate != null) runCatching { response.close() }
                    throw error
                }
            } catch (e: Exception) {
                if (fileRate != null) {
                    val temporary = e is TemporarySpeechResponse ||
                        e is SocketException || e is SocketTimeoutException
                    if (!temporary || retriedFileRequest) throw e
                    retriedFileRequest = true
                    delay(250)
                    continue
                }
                when (e) {
                    is CancellationException -> throw e
                    is ScriptException, is WrappedException -> {
                        AppLog.put("js错误\n${e.localizedMessage}", e, true)
                        e.printOnDebug()
                        throw e
                    }

                    is SocketTimeoutException, is ConnectException -> {
                        downloadErrorNo++
                        if (downloadErrorNo > 5) {
                            val msg = "tts超时或连接错误超过5次\n${e.localizedMessage}"
                            AppLog.put(msg, e, true)
                            throw e
                        }
                    }

                    else -> {
                        downloadErrorNo++
                        val msg = "tts下载错误\n${e.localizedMessage}"
                        AppLog.put(msg, e)
                        e.printOnDebug()
                        if (downloadErrorNo > 5) {
                            val msg1 = "TTS服务器连续5次错误，已暂停阅读。"
                            AppLog.put(msg1, e, true)
                            throw e
                        } else {
                            AppLog.put("TTS下载音频出错，使用无声音频代替。\n朗读文本：$speakText")
                            break
                        }
                    }
                }
            }
        }
        return null
    }

    private class TemporarySpeechResponse(code: Int) : IOException("HTTP $code")

    private fun md5SpeakFileName(
        content: String,
        textChapter: TextChapter? = this.textChapter
    ): String {
        return MD5Utils.md5Encode16(textChapter?.title ?: "") + "_" +
                MD5Utils.md5Encode16("${ReadAloud.httpTTS?.url}-|-$speechRate-|-$content")
    }

    private suspend fun prepareSpeakFile(playback: FilePlayback, paragraph: String, chapter: TextChapter?): File {
        val title = MD5Utils.md5Encode16(chapter?.title.orEmpty())
        val hash = MD5Utils.md5Encode16("${playback.tts.id}-|-${playback.tts.url}-|-${playback.speed}-|-$paragraph")
        val directory = File(ttsFolderPath)
        // Older cache entries may contain silent fallbacks or files interrupted during writes.
        val target = File(directory, "${title}_complete1_$hash.mp3")
        if (target.isFile && target.length() > 0L) return target
        if (!directory.isDirectory && !directory.mkdirs() && !directory.isDirectory) {
            throw IOException("Cannot create HTTP TTS cache directory")
        }
        val temporary = File.createTempFile("speech-", ".part", directory)
        try {
            val text = paragraph.replace(AppPattern.notReadAloudRegex, "")
            val stream = if (text.isEmpty()) resources.openRawResource(R.raw.silent_sound)
                else getSpeakStream(playback.tts, text, playback.speed) ?: throw IOException("Missing audio")
            val bytes = stream.use { input ->
                runInterruptible { temporary.outputStream().use { output -> input.copyTo(output) } }
            }
            currentCoroutineContext().ensureActive()
            if (bytes <= 0L || !temporary.renameTo(target)) {
                throw IOException("Incomplete HTTP TTS audio")
            }
            return target
        } finally {
            temporary.delete()
        }
    }

    /**
     * 移除缓存文件
     */
    private fun removeCacheFile() {
        val titleMd5 = MD5Utils.md5Encode16(textChapter?.title ?: "")
        FileUtils.listDirsAndFiles(ttsFolderPath)?.forEach {
            val isSilentSound = it.length() == 2160L
            if ((!it.name.startsWith(titleMd5)
                        && System.currentTimeMillis() - it.lastModified() > 600000)
                || isSilentSound
            ) {
                FileUtils.delete(it.absolutePath)
            }
        }
    }


    override fun pauseReadAloud(abandonFocus: Boolean) {
        super.pauseReadAloud(abandonFocus)
        kotlin.runCatching {
            playIndexJob?.cancel()
            exoPlayer.pause()
        }
    }

    override fun resumeReadAloud() {
        if (preparingSpeech) {
            super.resumeReadAloud()
            return
        }
        if (textChapter !== ReadBook.curTextChapter) {
            super.resumeReadAloud()
            ReadBook.readAloud()
            return
        }
        val playback = filePlayback
        if (playback != null && !pageChanged) {
            if (!isCurrent(playback) || !requestFocus()) return
            val failed = playback.failedIndex
            if (failed == nowSpeak || (playback.queue.waitingForNext && failed == nowSpeak + 1)) {
                // A finished segment must not be played again when its successor failed to download.
                if (failed != nowSpeak) updateNextPos()
                super.play()
                downloadAndPlayAudios()
            } else {
                super.resumeReadAloud()
                continueFilePlayback(playback)
                exoPlayer.play()
                if (exoPlayer.playbackState == Player.STATE_READY) upPlayPos()
            }
            return
        }
        super.resumeReadAloud()
        kotlin.runCatching {
            if (pageChanged) {
                play()
            } else {
                exoPlayer.play()
                upPlayPos()
            }
        }
    }

    private fun upPlayPos() {
        playIndexJob?.cancel()
        val textChapter = textChapter ?: return
        playIndexJob = lifecycleScope.launch {
            upTtsProgress(readAloudNumber + 1)
            if (exoPlayer.duration <= 0) {
                return@launch
            }
            val speakTextLength = contentList.getOrNull(nowSpeak)?.length ?: return@launch
            if (speakTextLength <= 0) {
                return@launch
            }
            val sleep = exoPlayer.duration / speakTextLength
            val start = speakTextLength * exoPlayer.currentPosition / exoPlayer.duration
            for (i in start..speakTextLength) {
                if (pageIndex + 1 < textChapter.pageSize
                    && readAloudNumber + i > textChapter.getReadLength(pageIndex + 1)
                ) {
                    pageIndex++
                    ReadBook.moveToNextPage()
                    upTtsProgress(readAloudNumber + i.toInt())
                }
                delay(sleep)
            }
        }
    }

    /**
     * 更新朗读速度
     */
    override fun upSpeechRate(reset: Boolean) {
        downloadTask?.cancel()
        discardFilePlayback()
        exoPlayer.stop()
        speechRate = AppConfig.speechRatePlay + 5
        if (AppConfig.streamReadAloudAudio) {
            downloadAndPlayAudiosStream()
        } else {
            downloadAndPlayAudios()
        }
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        super.onPlaybackStateChanged(playbackState)
        filePlayback?.let {
            onFilePlaybackStateChanged(it, playbackState)
            return
        }
        when (playbackState) {
            Player.STATE_IDLE -> {
                // 空闲
            }

            Player.STATE_BUFFERING -> {
                // 缓冲中
            }

            Player.STATE_READY -> {
                // 准备好
                if (pause) return
                exoPlayer.play()
                upPlayPos()
            }

            Player.STATE_ENDED -> {
                // 结束
                playErrorNo = 0
                updateNextPos()
                exoPlayer.stop()
                exoPlayer.clearMediaItems()
            }
        }
    }

    private fun onFilePlaybackStateChanged(playback: FilePlayback, state: Int) {
        if (!isCurrent(playback) || exoPlayer.playbackState != state) return
        val index = playback.paragraphIndex(exoPlayer.currentMediaItem) ?: return
        when (state) {
            Player.STATE_READY -> {
                adoptFileParagraph(playback, index)
                if (!pause && index == nowSpeak) {
                    exoPlayer.play()
                    upPlayPos()
                }
            }
            Player.STATE_ENDED -> when (playback.queue.end(index)) {
                HttpParagraphQueue.End.IGNORE -> Unit
                HttpParagraphQueue.End.WAIT -> {
                    playIndexJob?.cancel()
                    if (playback.failedIndex == index + 1) {
                        if (!pause) pauseReadAloud()
                    } else {
                        continueFilePlayback(playback)
                    }
                }
                HttpParagraphQueue.End.COMPLETE -> {
                    playIndexJob?.cancel()
                    discardFilePlayback()
                    exoPlayer.stop()
                    exoPlayer.clearMediaItems()
                    updateNextPos()
                }
            }
        }
    }

    private fun adoptFileParagraph(playback: FilePlayback, index: Int) {
        if (!isCurrent(playback) || !playback.queue.transition(index)) return
        updateNextPos()
        playback.playingIndex.value = playback.queue.playingIndex
        if (!pause) upPlayPos()
    }

    override fun onTimelineChanged(timeline: Timeline, reason: Int) {
        if (filePlayback != null) return
        when (reason) {
            Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED -> {
                if (!timeline.isEmpty && exoPlayer.playbackState == Player.STATE_IDLE) {
                    exoPlayer.prepare()
                }
            }

            else -> {}
        }
    }

    override fun onIsLoadingChanged(isLoading: Boolean) {
        loadingState.value = isLoading
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        filePlayback?.let { playback ->
            playback.paragraphIndex(mediaItem)?.let { adoptFileParagraph(playback, it) }
            return
        }
        if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) return
        if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
            playErrorNo = 0
        }
        updateNextPos()
        upPlayPos()
    }

    override fun onPlayerError(error: PlaybackException) {
        super.onPlayerError(error)
        filePlayback?.let { playback ->
            if (!isCurrent(playback)) return
            val item = exoPlayer.currentMediaItem
            val index = playback.paragraphIndex(item) ?: return
            if (index != nowSpeak) return
            AppLog.put("朗读错误\n${error.localizedMessage}", error)
            downloadTask?.cancel()
            item?.localConfiguration?.uri?.path?.let { File(it).delete() }
            markFileFailure(playback, index)
            return
        }
        AppLog.put("朗读错误\n${contentList[nowSpeak]}", error)
        deleteCurrentSpeakFile()
        playErrorNo++
        if (playErrorNo >= 5) {
            toastOnUi("朗读连续5次错误, 最后一次错误代码(${error.localizedMessage})")
            AppLog.put("朗读连续5次错误, 最后一次错误代码(${error.localizedMessage})", error)
            pauseReadAloud()
        } else {
            if (exoPlayer.hasNextMediaItem()) {
                exoPlayer.seekToNextMediaItem()
                exoPlayer.prepare()
            } else {
                exoPlayer.clearMediaItems()
                updateNextPos()
            }
        }
    }

    private fun deleteCurrentSpeakFile() {
        if (AppConfig.streamReadAloudAudio) {
            return
        }
        val mediaItem = exoPlayer.currentMediaItem ?: return
        val filePath = mediaItem.localConfiguration!!.uri.path!!
        File(filePath).delete()
    }

    override fun aloudServicePendingIntent(actionStr: String): PendingIntent? {
        return servicePendingIntent<HttpReadAloudService>(actionStr)
    }

    class CustomLoadErrorHandlingPolicy : DefaultLoadErrorHandlingPolicy(0) {
        override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
            return C.TIME_UNSET
        }
    }

    private companion object {
        const val FILE_LOOK_AHEAD = 3
        const val FILE_START_WAIT_MS = 1_500L
    }

}
