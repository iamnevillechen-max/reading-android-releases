package io.legado.app.model.aggregation

/** Small source rounds keep scrolling responsive while retaining normal mobile request time. */
object AggregateLoadingPolicy {
    const val SOURCES_PER_ROUND = 8
    const val SOURCE_TIMEOUT_MILLIS = 8_000L
    const val ROUND_BUDGET_MILLIS = 16_000L
    const val SINGLE_SOURCE_TIMEOUT_MILLIS = 15_000L
}
