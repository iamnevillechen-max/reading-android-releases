package io.legado.app.model.multisource.core

import org.junit.Assert.*
import org.junit.Test

class SpeechQueueTest {
    @Test fun replacementRejectsLateCallbacksEvenForTheSameParagraph() {
        val queue = SpeechQueue("reader")
        val old = queue.begin(listOf(3, 4))
        val next = queue.begin(listOf(3, 4))
        assertNotEquals(old.utterances[0].id, next.utterances[0].id)
        assertFalse(queue.isCurrent(old))
        assertNull(queue.current(old.utterances[0].id))
        assertTrue(queue.complete(old.utterances[0].id).isEmpty())
        assertEquals(3, queue.current(next.utterances[0].id))
    }

    @Test fun duplicateDoneOrErrorCannotAdvanceTwice() {
        val queue = SpeechQueue("reader")
        val token = queue.begin(listOf(2, 5, 8))
        assertEquals(listOf(2), queue.complete(token.utterances[0].id))
        assertTrue(queue.complete(token.utterances[0].id).isEmpty())
        assertNull(queue.current(token.utterances[0].id))
        assertEquals(5, queue.current(token.utterances[1].id))
        assertEquals(listOf(5), queue.complete(token.utterances[1].id))
        assertEquals(listOf(8), queue.complete(token.utterances[2].id))
        assertTrue(queue.complete(token.utterances[2].id).isEmpty())
    }

    @Test fun outOfOrderCallbackCannotSkipUnreadParagraphs() {
        val queue = SpeechQueue("reader")
        val token = queue.begin(listOf(0, 1))
        assertNull(queue.current(token.utterances[1].id))
        assertTrue(queue.complete(token.utterances[1].id).isEmpty())
        assertEquals(0, queue.current(token.utterances[0].id))
        assertEquals(listOf(0, 1), queue.complete(token.utterances[0].id))
        assertTrue(queue.complete(token.utterances[1].id).isEmpty())
    }

    @Test fun pauseStopOrClearInvalidatesQueuedWorkAndCallbacks() {
        val queue = SpeechQueue("reader")
        val token = queue.begin(listOf(7))
        assertTrue(queue.invalidate())
        assertFalse(queue.isCurrent(token))
        assertNull(queue.current(token.utterances.single().id))
        assertTrue(queue.complete(token.utterances.single().id).isEmpty())
        val resumed = queue.begin(listOf(7))
        assertTrue(queue.isCurrent(resumed))
        assertEquals(listOf(7), queue.complete(resumed.utterances.single().id))
        assertFalse("A naturally drained queue needs no stop call to the engine", queue.invalidate())
        assertFalse("Preparing a new chapter must not stop an already idle engine", queue.invalidate())
    }

    @Test fun nullUnknownAndEmptyQueuesCannotMovePosition() {
        val queue = SpeechQueue("reader")
        val token = queue.begin(emptyList())
        assertTrue(queue.isCurrent(token))
        assertNull(queue.current(null))
        assertTrue(queue.complete("reader:1:0").isEmpty())
        assertTrue(queue.complete("different-service:1:0").isEmpty())
    }

    @Test fun futureSynthesisErrorWaitsForEarlierAudioAndIsConsumedOnce() {
        val queue = SpeechQueue("reader")
        val token = queue.begin(listOf(0, 1, 2))
        assertTrue(queue.fail(token.utterances[1].id).isEmpty())
        assertTrue(queue.fail(token.utterances[1].id).isEmpty())
        assertEquals(0, queue.current(token.utterances[0].id))
        assertEquals(listOf(0, 1), queue.complete(token.utterances[0].id))
        assertNull(queue.current(token.utterances[1].id))
        assertTrue(queue.complete(token.utterances[1].id).isEmpty())
        assertEquals(2, queue.current(token.utterances[2].id))
        assertEquals(listOf(2), queue.complete(token.utterances[2].id))
    }

    @Test fun oldFutureErrorsCannotAffectAReplacementOrResumedQueue() {
        val queue = SpeechQueue("reader")
        val old = queue.begin(listOf(0, 1, 2))
        queue.fail(old.utterances[2].id)
        val replacement = queue.begin(listOf(0, 1, 2))
        assertFalse(queue.isPending(old.utterances[1].id))
        assertTrue(queue.fail(old.utterances[1].id).isEmpty())
        assertEquals(listOf(0), queue.complete(replacement.utterances[0].id))
        assertEquals(listOf(1), queue.complete(replacement.utterances[1].id))
        assertEquals(listOf(2), queue.complete(replacement.utterances[2].id))
    }

    @Test(expected = IllegalArgumentException::class)
    fun duplicateParagraphsAreRejectedBeforeEnqueueing() {
        SpeechQueue("reader").begin(listOf(1, 1))
    }
}
