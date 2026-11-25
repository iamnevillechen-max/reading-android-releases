package io.legado.app.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HttpParagraphQueueTest {
    @Test
    fun appendsInOrderWithinTheMovingDownloadWindow() {
        val queue = HttpParagraphQueue(2, 8)
        assertEquals(2, queue.playingIndex)
        assertEquals(5, queue.downloadThrough)
        assertFalse(queue.append(3))
        for (index in 2..5) assertTrue(queue.append(index))
        assertFalse(queue.append(5))
        assertFalse(queue.append(6))

        assertFalse(queue.transition(4))
        assertTrue(queue.transition(3))
        assertFalse(queue.transition(3))
        assertEquals(6, queue.downloadThrough)
        assertTrue(queue.append(6))
    }

    @Test
    fun temporaryEndWaitsForLateAudioAndDoesNotAdvanceOnItsOwn() {
        val queue = HttpParagraphQueue(4, 6)
        assertEquals(HttpParagraphQueue.End.IGNORE, queue.end(4))
        assertTrue(queue.append(4))
        assertEquals(HttpParagraphQueue.End.WAIT, queue.end(4))
        assertEquals(HttpParagraphQueue.End.IGNORE, queue.end(4))
        assertTrue(queue.waitingForNext)
        assertNull(queue.takeContinuation())
        assertFalse(queue.transition(5))
        assertEquals(4, queue.playingIndex)

        assertTrue(queue.append(5))
        assertEquals(5, queue.takeContinuation())
        assertNull(queue.takeContinuation())
        assertTrue(queue.transition(5))
        assertFalse(queue.waitingForNext)
        assertEquals(HttpParagraphQueue.End.WAIT, queue.end(5))
        assertTrue(queue.append(6))
        assertEquals(6, queue.takeContinuation())
    }

    @Test
    fun bufferedNextAudioStillIssuesOnlyOneContinuationAfterEnd() {
        val queue = HttpParagraphQueue(0, 2)
        assertTrue(queue.append(0))
        assertTrue(queue.append(1))
        assertNull(queue.takeContinuation())
        assertEquals(HttpParagraphQueue.End.IGNORE, queue.end(1))

        assertEquals(HttpParagraphQueue.End.WAIT, queue.end(0))
        assertEquals(1, queue.takeContinuation())
        assertEquals(HttpParagraphQueue.End.IGNORE, queue.end(0))
        assertNull(queue.takeContinuation())
        assertTrue(queue.transition(1))
        assertFalse(queue.waitingForNext)
        assertNull(queue.takeContinuation())
    }

    @Test
    fun onlyThePlayedTailCompletesAndItCompletesOnce() {
        val queue = HttpParagraphQueue(0, 1)
        assertEquals(1, queue.downloadThrough)
        assertTrue(queue.append(0))
        assertTrue(queue.append(1))
        assertTrue(queue.transition(1))
        assertEquals(HttpParagraphQueue.End.IGNORE, queue.end(0))
        assertEquals(HttpParagraphQueue.End.COMPLETE, queue.end(1))
        assertEquals(HttpParagraphQueue.End.IGNORE, queue.end(1))
        assertFalse(queue.append(2))
        assertFalse(queue.transition(2))
        assertFalse(queue.waitingForNext)
        assertNull(queue.takeContinuation())
    }
}
