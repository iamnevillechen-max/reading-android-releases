package io.legado.app.model.multisource.core

/** Per-book discovery progress: failures may defer a source, but never pin discovery to a prefix. */
object CandidateScheduler {
    const val MAX_HISTORY = 128
    const val RETRY_DELAY = 60_000L
    private const val VERIFIED_CACHE_AGE = 24 * 60 * 60 * 1000L

    data class Candidate(val id: String, val known: Boolean, val order: Int, val priority: Int = 0,
        val knownAt: Long = 0) {
        fun knownPosition() = KnownPosition(knownAt, order, id)
    }
    data class KnownPosition(val time: Long, val order: Int, val id: String) : Comparable<KnownPosition> {
        override fun compareTo(other: KnownPosition) = compareValuesBy(this, other,
            { it.time }, { it.order }, { it.id })
    }
    data class Health(val retryUntil: Long = 0, val successful: Boolean = false, val knownAt: Long = 0)
    data class State(val cursor: String? = null, val history: Map<String, Health> = emptyMap(),
        val knownThrough: KnownPosition? = null)
    data class Selection(val candidates: List<Candidate>, val state: State)
    enum class Outcome { SUCCESS, NO_MATCH, TIMEOUT, FAILURE }

    fun choose(candidates: List<Candidate>, state: State, now: Long, limit: Int): Selection {
        val ordered = candidates.sortedWith(compareBy<Candidate> { it.priority }.thenBy { it.order }.thenBy { it.id })
        val count = limit.coerceIn(1, 6)
        fun freshKnown(candidate: Candidate): Boolean {
            if (!candidate.known || state.knownThrough?.let { candidate.knownPosition() <= it } == true) return false
            val health = state.history[candidate.id]
            return health == null || candidate.knownAt > health.knownAt
        }
        // New manual metadata can recover an earlier failed unknown lookup once. The
        // watermark advances at selection, so in-flight preloads cannot bypass it again.
        fun available(candidate: Candidate) =
            (state.history[candidate.id]?.retryUntil ?: 0) <= now || freshKnown(candidate)
        // A newly found same-book result is useful even after unknown-source discovery
        // advanced. Its separate watermark prevents evicted failures or concurrent preloads
        // from repeatedly treating the original known prefix as newly discovered.
        val successful = ordered.filter { available(it) && state.history[it.id]?.successful == true }
        val newlyKnown = ordered.filter { candidate ->
            freshKnown(candidate) && state.history[candidate.id]?.successful != true
        }.sortedBy { it.knownPosition() }
        val preferred = (successful + newlyKnown).take(count)
        val knownThrough = preferred.asSequence().filter { it in newlyKnown }.map { it.knownPosition() }
            .plus(listOfNotNull(state.knownThrough)).maxOrNull()
        val selected = preferred.toMutableList()
        val selectedIds = preferred.mapTo(hashSetOf()) { it.id }
        var cursor = preferred.lastOrNull { state.history[it.id]?.successful != true }?.id ?: state.cursor
        val cursorIndex = ordered.indexOfFirst { it.id == cursor }
        if (selected.size < count && ordered.isNotEmpty()) {
            for (step in 1..ordered.size) {
                val candidate = ordered[(cursorIndex + step) % ordered.size]
                if (candidate.id in selectedIds || !available(candidate)) continue
                selected += candidate
                selectedIds += candidate.id
                cursor = candidate.id
                if (selected.size == count) break
            }
        }
        return Selection(selected, state.copy(cursor = cursor, knownThrough = knownThrough))
    }

    fun record(state: State, sourceId: String, outcome: Outcome, now: Long, knownAt: Long = 0): State {
        val history = LinkedHashMap(state.history)
        val previous = history.remove(sourceId)
        val attemptedKnownAt = maxOf(knownAt, previous?.knownAt ?: 0)
        history[sourceId] = if (outcome == Outcome.SUCCESS) Health(successful = true, knownAt = attemptedKnownAt)
            else Health(retryUntil = now + RETRY_DELAY, knownAt = attemptedKnownAt)
        while (history.size > MAX_HISTORY) history.remove(history.keys.first())
        // The cursor is independent of the bounded history: evicting old failures must not
        // restart at source one when a library contains thousands of sources.
        return state.copy(history = history)
    }

    /** A cached miss is reusable briefly, rather than preventing further discovery for a day. */
    fun cacheReusable(created: Long, now: Long, corroborated: Boolean): Boolean {
        val age = now - created
        val maximumAge = if (corroborated) VERIFIED_CACHE_AGE else RETRY_DELAY
        return age >= 0 && age < maximumAge
    }
}
