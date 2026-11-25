package io.legado.app.model.aggregation

/** A table notification is not evidence that every source changed. */
internal object SourceSnapshotPolicy {
    fun retained(previous: Set<String>, current: Set<String>, changed: Set<String>?): Set<String> =
        if (changed == null) emptySet() else (previous intersect current) - changed
}
