package io.legado.app.multisource

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.legado.app.constant.BookType
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.help.config.AppConfig
import io.legado.app.model.ReadBook
import io.legado.app.service.BaseReadAloudService
import io.legado.app.ui.book.read.page.entities.TextChapter
import kotlinx.coroutines.Dispatchers.Main
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class PromotedChapterSpeechTest {
    @Test fun promotedLayoutResumesOnceOnlyAfterCompletion() = withPromotedLayout { layout ->
        var playCalls = 0
        ReadBook.resumePromotedChapterSpeech(layout) { playCalls++ }
        assertEquals(0, playCalls)
        layout.onLayoutCompleted()
        ReadBook.resumePromotedChapterSpeech(layout) { playCalls++ }
        ReadBook.resumePromotedChapterSpeech(layout) { playCalls++ }
        assertEquals(1, playCalls)
    }

    @Test fun pauseAndStopSuppressLateLayoutCompletion() {
        for ((isRun, pause) in listOf(true to true, false to false)) {
            withPromotedLayout { layout ->
                setSpeechState(isRun, pause)
                layout.onLayoutCompleted()
                var playCalls = 0
                ReadBook.resumePromotedChapterSpeech(layout) { playCalls++ }
                assertEquals(0, playCalls)
            }
        }
    }

    @Test fun staleLayoutCannotResumeAfterReplacementAtTheSameChapter() = withPromotedLayout { layout ->
        ReadBook.curTextChapter = layout.copy()
        layout.onLayoutCompleted()
        var playCalls = 0
        ReadBook.resumePromotedChapterSpeech(layout) { playCalls++ }
        assertEquals(0, playCalls)
    }

    private fun withPromotedLayout(verify: (TextChapter) -> Unit) = runBlocking {
        withContext(Main) {
            check(Build.HARDWARE in setOf("ranchu", "goldfish") && ReadBook.book == null &&
                ReadBook.callBack == null && !BaseReadAloudService.isRun &&
                ReadBook.curTextChapter == null && ReadBook.nextTextChapter == null &&
                ReadBook.prevTextChapter == null) { "A dedicated idle test reader is required" }
            val oldPause = BaseReadAloudService.pause
            val oldHistory = AppConfig.enableReadRecord
            val oldSimulatedSize = ReadBook.simulatedChapterSize
            val oldIndex = ReadBook.durChapterIndex
            val oldPosition = ReadBook.durChapterPos
            val fixture = Book(bookUrl = "/owned-speech-layout-${System.nanoTime()}", type = BookType.local)
            val chapter = BookChapter(bookUrl = fixture.bookUrl, index = 1, title = "自寫換章測試")
            val layout = TextChapter.emptyTextChapter.copy(chapter = chapter, title = chapter.title)
            try {
                AppConfig.enableReadRecord = false
                ReadBook.book = fixture
                ReadBook.simulatedChapterSize = 2
                ReadBook.durChapterIndex = 0
                ReadBook.durChapterPos = 0
                ReadBook.nextTextChapter = layout
                setSpeechState(isRun = true, pause = false)
                assertTrue(ReadBook.moveToNextChapter(upContent = false))
                assertSame(layout, ReadBook.curTextChapter)
                verify(layout)
            } finally {
                setSpeechState(isRun = false, pause = oldPause)
                ReadBook.executor.submit { }.get(5, TimeUnit.SECONDS)
                ReadBook.clearTextChapter()
                ReadBook.book = null
                ReadBook.simulatedChapterSize = oldSimulatedSize
                ReadBook.durChapterIndex = oldIndex
                ReadBook.durChapterPos = oldPosition
                AppConfig.enableReadRecord = oldHistory
            }
        }
    }

    private fun setSpeechState(isRun: Boolean, pause: Boolean) {
        for ((name, value) in listOf("isRun" to isRun, "pause" to pause)) {
            BaseReadAloudService::class.java.getDeclaredField(name).apply { isAccessible = true }
                .setBoolean(null, value)
        }
    }
}
