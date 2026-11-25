package io.legado.app.model.cloud

/** Local imports can replace an obsolete proxy even when the original rules have an older timestamp. */
internal object LocalSourceRestorePolicy {
    fun restoresPlaceholder(currentPlaceholder: Boolean, incomingPlaceholder: Boolean): Boolean =
        currentPlaceholder && !incomingPlaceholder

    fun select(existingTime: Long?, incomingTime: Long, restoringPlaceholder: Boolean): Boolean =
        existingTime == null || restoringPlaceholder || existingTime < incomingTime
}
