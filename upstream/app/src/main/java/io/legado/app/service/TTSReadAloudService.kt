package io.legado.app.service

import android.app.PendingIntent
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import io.legado.app.R
import io.legado.app.constant.AppConst
import io.legado.app.constant.AppLog
import io.legado.app.constant.AppPattern
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.MediaHelp
import io.legado.app.help.config.AppConfig
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.lib.dialogs.SelectItem
import io.legado.app.model.ReadAloud
import io.legado.app.model.ReadBook
import io.legado.app.model.multisource.core.SpeechQueue
import io.legado.app.ui.book.read.page.entities.TextChapter
import io.legado.app.utils.GSON
import io.legado.app.utils.LogUtils
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.servicePendingIntent
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.CancellationException

/**
 * 本地朗读
 */
class TTSReadAloudService : BaseReadAloudService(), TextToSpeech.OnInitListener {

    private var textToSpeech: TextToSpeech? = null
    private var ttsInitFinish = false
    private val ttsUtteranceListener = TTSUtteranceListener()
    private var speakJob: Coroutine<*>? = null
    private val speechQueue = SpeechQueue(AppConst.APP_TAG)
    private data class Snapshot(val chapter: TextChapter?, val contents: List<String>)
    private var snapshot: Snapshot? = null
    private var initialization = 0L
    private val TAG = "TTSReadAloudService"

    override fun onCreate() {
        super.onCreate()
        kotlin.runCatching {
            initTts()
        }.onFailure {
            AppLog.put("${getString(R.string.tts_init_failed)}\n$it", it, true)
        }
    }

    override fun onDestroy() {
        clearTTS()
        super.onDestroy()
    }

    @Synchronized
    private fun initTts() {
        ttsInitFinish = false
        val generation = ++initialization
        val listener = TextToSpeech.OnInitListener { status ->
            synchronized(this) {
                if (generation == initialization) onInit(status)
            }
        }
        val engine = GSON.fromJsonObject<SelectItem<String>>(ReadAloud.ttsEngine).getOrNull()?.value
        LogUtils.d(TAG, "initTts engine:$engine")
        textToSpeech = if (engine.isNullOrBlank()) {
            TextToSpeech(this, listener)
        } else {
            TextToSpeech(this, listener, engine)
        }
        upSpeechRate()
    }

    @Synchronized
    fun clearTTS() {
        initialization++
        playStop()
        textToSpeech?.runCatching {
            setOnUtteranceProgressListener(null)
            shutdown()
        }
        textToSpeech = null
        ttsInitFinish = false
    }

    @Synchronized
    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            textToSpeech?.let {
                it.setOnUtteranceProgressListener(ttsUtteranceListener)
                ttsInitFinish = true
                if (!BaseReadAloudService.pause) play()
            }
        } else {
            toastOnUi(R.string.tts_init_failed)
        }
    }

    @Synchronized
    override fun play() {
        if (!ttsInitFinish || preparingSpeech) return
        if (contentList.isEmpty()) {
            AppLog.putDebug("朗读列表为空")
            return
        }
        if (!requestFocus()) return
        playStop()
        // BaseReadAloudService creates a fresh immutable list for each reading snapshot.
        val queueSnapshot = Snapshot(textChapter, contentList)
        snapshot = queueSnapshot
        while (nowSpeak in contentList.indices && contentList[nowSpeak].matches(AppPattern.notReadAloudRegex)) {
            readAloudNumber += contentList[nowSpeak].length + 1 - paragraphStartPos
            paragraphStartPos = 0
            nowSpeak++
        }
        val firstIndex = nowSpeak
        val firstOffset = paragraphStartPos
        val token = speechQueue.begin((firstIndex until contentList.size)
            .filterNot { contentList[it].matches(AppPattern.notReadAloudRegex) })
        super.play()
        MediaHelp.playSilentSound(this@TTSReadAloudService)
        speakJob = execute {
            for ((index, utteranceId) in token.utterances) {
                ensureActive()
                synchronized(this@TTSReadAloudService) {
                    // Cancellation alone cannot stop a coroutine already entering speak().
                    // The same lock protects queue replacement, validation and the IPC call.
                    if (!isCurrent(token, queueSnapshot)) return@execute
                    val tts = textToSpeech ?: throw NoStackTraceException("tts is null")
                    val text = queueSnapshot.contents[index].let {
                        if (index == firstIndex && firstOffset > 0) it.substring(firstOffset) else it
                    }
                    val mode = if (index == token.utterances.first().index) TextToSpeech.QUEUE_FLUSH
                        else TextToSpeech.QUEUE_ADD
                    val result = tts.runCatching { speak(text, mode, null, utteranceId) }
                        .getOrElse {
                            AppLog.put("tts出错\n${it.localizedMessage}", it, true)
                            TextToSpeech.ERROR
                        }
                    if (result == TextToSpeech.ERROR) {
                        AppLog.put("tts出错 尝试重新初始化")
                        clearTTS()
                        initTts()
                        return@execute
                    }
                }
            }
            if (token.utterances.isEmpty()) {
                delay(1000)
                synchronized(this@TTSReadAloudService) {
                    if (isCurrent(token, queueSnapshot)) nextChapter()
                }
            }
        }.onError {
            if (it !is CancellationException) synchronized(this@TTSReadAloudService) {
                if (isCurrent(token, queueSnapshot)) {
                    AppLog.put("tts朗读出错\n${it.localizedMessage}", it, true)
                }
            }
        }
    }

    private fun isCurrent(token: SpeechQueue.Token, expected: Snapshot): Boolean =
        speechQueue.isCurrent(token) && snapshot === expected &&
            textChapter === expected.chapter && contentList === expected.contents &&
            !BaseReadAloudService.pause && ttsInitFinish

    private fun currentParagraph(utteranceId: String?): Int? {
        val expected = snapshot ?: return null
        if (textChapter !== expected.chapter || contentList !== expected.contents || BaseReadAloudService.pause || !ttsInitFinish) return null
        return speechQueue.current(utteranceId)?.takeIf { it == nowSpeak && it in contentList.indices }
    }

    private fun pendingParagraph(utteranceId: String?): Boolean {
        val expected = snapshot ?: return false
        return textChapter === expected.chapter && contentList === expected.contents &&
            !BaseReadAloudService.pause && ttsInitFinish && speechQueue.isPending(utteranceId)
    }

    @Synchronized
    override fun playStop() {
        val hadPendingSpeech = speechQueue.invalidate()
        snapshot = null
        speakJob?.cancel()
        speakJob = null
        if (hadPendingSpeech) {
            textToSpeech?.runCatching { stop() }
        }
    }

    /**
     * 更新朗读速度
     */
    override fun upSpeechRate(reset: Boolean) {
        if (AppConfig.ttsFlowSys) {
            if (reset) {
                clearTTS()
                initTts()
            }
        } else {
            val speechRate = (AppConfig.ttsSpeechRate + 5) / 10f
            textToSpeech?.setSpeechRate(speechRate)
        }
    }

    /**
     * 暂停朗读
     */
    @Synchronized
    override fun pauseReadAloud(abandonFocus: Boolean) {
        playStop()
        super.pauseReadAloud(abandonFocus)
    }

    /**
     * 恢复朗读
     */
    @Synchronized
    override fun resumeReadAloud() {
        super.resumeReadAloud()
        if (preparingSpeech) return
        if (textChapter === ReadBook.curTextChapter) play()
        else ReadBook.readAloud()
    }

    @Synchronized
    override fun prevChapter() {
        playStop()
        super.prevChapter()
    }

    @Synchronized
    override fun nextChapter() {
        playStop()
        super.nextChapter()
    }

    /**
     * 朗读监听
     */
    private inner class TTSUtteranceListener : UtteranceProgressListener() {

        private val TAG = "TTSUtteranceListener"

        override fun onStart(s: String) {
            synchronized(this@TTSReadAloudService) {
                if (currentParagraph(s) == null) return@synchronized
                LogUtils.d(TAG, "onStart nowSpeak:$nowSpeak pageIndex:$pageIndex utteranceId:$s")
                textChapter?.let {
                    if (pageIndex + 1 < it.pageSize
                        && readAloudNumber + 1 > it.getReadLength(pageIndex + 1)
                    ) {
                        pageIndex++
                        ReadBook.moveToNextPage()
                    }
                    upTtsProgress(readAloudNumber + 1)
                }
            }
        }

        override fun onDone(s: String) {
            synchronized(this@TTSReadAloudService) {
                if (!pendingParagraph(s)) return@synchronized
                val completed = speechQueue.complete(s)
                LogUtils.d(TAG, "onDone utteranceId:$s")
                completeParagraphs(completed)
            }
        }

        override fun onRangeStart(utteranceId: String?, start: Int, end: Int, frame: Int) {
            synchronized(this@TTSReadAloudService) {
                if (currentParagraph(utteranceId) == null) return@synchronized
                super.onRangeStart(utteranceId, start, end, frame)
                val msg = "onRangeStart nowSpeak:$nowSpeak pageIndex:$pageIndex utteranceId:$utteranceId start:$start end:$end frame:$frame"
                LogUtils.d(TAG, msg)
                textChapter?.let {
                    if (pageIndex + 1 < it.pageSize
                        && readAloudNumber + start > it.getReadLength(pageIndex + 1)
                    ) {
                        pageIndex++
                        ReadBook.moveToNextPage()
                        upTtsProgress(readAloudNumber + start)
                    }
                }
            }
        }

        override fun onError(utteranceId: String?, errorCode: Int) {
            synchronized(this@TTSReadAloudService) {
                if (!pendingParagraph(utteranceId)) return@synchronized
                val completed = speechQueue.fail(utteranceId)
                LogUtils.d(TAG, "onError nowSpeak:$nowSpeak pageIndex:$pageIndex utteranceId:$utteranceId errorCode:$errorCode")
                completeParagraphs(completed)
            }
        }

        private fun completeParagraphs(completed: List<Int>) {
            for (index in completed) {
                if (index != nowSpeak) return
                nextParagraph()
            }
        }

        private fun nextParagraph() {
            // Skip punctuation-only paragraphs after the accepted completion, exactly once.
            do {
                readAloudNumber += contentList[nowSpeak].length + 1 - paragraphStartPos
                paragraphStartPos = 0
                nowSpeak++
                if (nowSpeak >= contentList.size) {
                    nextChapter()
                    return
                }
            } while (contentList[nowSpeak].matches(AppPattern.notReadAloudRegex))
        }

        @Deprecated("Deprecated in Java")
        override fun onError(s: String) {
            onError(s, TextToSpeech.ERROR)
        }
    }

    override fun aloudServicePendingIntent(actionStr: String): PendingIntent? {
        return servicePendingIntent<TTSReadAloudService>(actionStr)
    }

}
