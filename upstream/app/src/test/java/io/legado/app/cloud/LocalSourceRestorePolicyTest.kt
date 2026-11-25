package io.legado.app.cloud

import io.legado.app.model.cloud.LocalSourceRestorePolicy
import org.junit.Assert.*
import org.junit.Test

class LocalSourceRestorePolicyTest {
    @Test fun originalOlderOrSameTimestampRulesRestoreAPlaceholder() {
        val restore = LocalSourceRestorePolicy.restoresPlaceholder(true, false)
        assertTrue(restore)
        assertTrue(LocalSourceRestorePolicy.select(400L, 100L, restore))
        assertTrue(LocalSourceRestorePolicy.select(400L, 400L, restore))
    }

    @Test fun ordinaryLocalImportsStillRespectTimestampsAndNeverPromoteAnotherPlaceholder() {
        assertFalse(LocalSourceRestorePolicy.restoresPlaceholder(true, true))
        assertFalse(LocalSourceRestorePolicy.restoresPlaceholder(false, false))
        assertFalse(LocalSourceRestorePolicy.select(400L, 100L, false))
        assertFalse(LocalSourceRestorePolicy.select(400L, 400L, false))
        assertTrue(LocalSourceRestorePolicy.select(400L, 401L, false))
        assertTrue(LocalSourceRestorePolicy.select(null, 0L, false))
    }
}
