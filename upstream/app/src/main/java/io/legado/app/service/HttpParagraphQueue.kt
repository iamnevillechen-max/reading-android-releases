package io.legado.app.service

internal class HttpParagraphQueue(
    firstIndex: Int,
    val lastIndex: Int,
    private val lookAhead: Int = 3
) {
    init {
        require(firstIndex >= 0 && firstIndex <= lastIndex)
        require(lookAhead >= 0)
    }

    enum class End { IGNORE, WAIT, COMPLETE }

    var playingIndex: Int = firstIndex
        private set

    val downloadThrough: Int
        get() = playingIndex + minOf(lookAhead, lastIndex - playingIndex)

    var waitingForNext: Boolean = false
        private set

    private var appendedThrough = firstIndex - 1
    private var continuationTaken = false
    private var finished = false

    fun append(index: Int): Boolean {
        if (finished || index > downloadThrough || index <= appendedThrough ||
            index != appendedThrough + 1
        ) return false
        appendedThrough = index
        return true
    }

    fun transition(index: Int): Boolean {
        if (finished || index > appendedThrough || index <= playingIndex ||
            index != playingIndex + 1
        ) return false
        playingIndex = index
        waitingForNext = false
        continuationTaken = false
        return true
    }

    fun end(index: Int): End {
        if (finished || waitingForNext || index != playingIndex || index > appendedThrough) {
            return End.IGNORE
        }
        if (index == lastIndex) {
            finished = true
            return End.COMPLETE
        }
        waitingForNext = true
        return End.WAIT
    }

    fun takeContinuation(): Int? {
        if (!waitingForNext || continuationTaken || playingIndex >= appendedThrough) return null
        continuationTaken = true
        return playingIndex + 1
    }
}
