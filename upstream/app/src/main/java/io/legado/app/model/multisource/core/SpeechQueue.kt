package io.legado.app.model.multisource.core

/** Identifies callbacks from one playback queue and accepts each paragraph in order once. */
class SpeechQueue(private val prefix: String) {
    data class Utterance(val index: Int, val id: String)
    data class Token internal constructor(val generation: Long, val utterances: List<Utterance>)

    private var generation = 0L
    private var active: Token? = null
    private var cursor = 0
    private val finished = hashSetOf<Int>()

    @Synchronized
    fun begin(indices: List<Int>): Token {
        require(indices.all { it >= 0 } && indices.zipWithNext().all { it.first < it.second })
        val next = ++generation
        return Token(next, indices.map { Utterance(it, "$prefix:$next:$it") }).also {
            active = it
            cursor = 0
            finished.clear()
        }
    }

    @Synchronized
    fun invalidate(): Boolean {
        val hadPendingSpeech = cursor < (active?.utterances?.size ?: 0)
        generation++
        active = null
        cursor = 0
        finished.clear()
        return hadPendingSpeech
    }

    @Synchronized
    fun isCurrent(token: Token): Boolean = active === token

    @Synchronized
    fun current(utteranceId: String?): Int? = active?.utterances?.getOrNull(cursor)
        ?.takeIf { it.id == utteranceId }?.index

    @Synchronized
    fun isPending(utteranceId: String?): Boolean {
        val position = active?.utterances?.indexOfFirst { it.id == utteranceId } ?: -1
        return position >= cursor && position !in finished
    }

    @Synchronized
    fun complete(utteranceId: String?): List<Int> = finish(utteranceId)

    /** Both early synthesis failures and reordered completion callbacks wait for earlier audio. */
    @Synchronized
    fun fail(utteranceId: String?): List<Int> = finish(utteranceId)

    private fun finish(utteranceId: String?): List<Int> {
        val position = active?.utterances?.indexOfFirst { it.id == utteranceId } ?: -1
        if (position < cursor || !finished.add(position)) return emptyList()
        return if (position == cursor) advance() else emptyList()
    }

    private fun advance(): List<Int> {
        val utterances = active?.utterances ?: return emptyList()
        val completed = arrayListOf<Int>()
        do {
            completed.add(utterances[cursor].index)
            finished.remove(cursor++)
        } while (cursor < utterances.size && cursor in finished)
        return completed
    }
}
