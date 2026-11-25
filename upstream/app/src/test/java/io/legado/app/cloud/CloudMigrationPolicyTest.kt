package io.legado.app.cloud

import io.legado.app.model.cloud.CloudMigrationPolicy
import org.junit.Assert.*
import org.junit.Test

class CloudMigrationPolicyTest {
    @Test fun exactUtf8BytesDetermineReceipt() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", CloudMigrationPolicy.sha256("abc"))
        assertNotEquals(CloudMigrationPolicy.sha256("{\"正文\":1}"), CloudMigrationPolicy.sha256("{ \"正文\":1}"))
        assertNotEquals(CloudMigrationPolicy.sha256("簡"), CloudMigrationPolicy.sha256("简"))
    }

    @Test fun receiptPreservesOpaqueIdentityAndStoredBytes() {
        val id = "https://fixture.invalid/?edition=1#reader-source-a"
        val hash = CloudMigrationPolicy.sha256("self-written rule")
        CloudMigrationPolicy.requireReceipt(id, hash, id, hash, true)
        for (wrong in listOf("https://fixture.invalid/", "https://fixture.invalid/?edition=1#reader-source-b")) {
            assertThrows(IllegalArgumentException::class.java) { CloudMigrationPolicy.requireReceipt(id, hash, wrong, hash, true) }
        }
        assertThrows(IllegalArgumentException::class.java) { CloudMigrationPolicy.requireReceipt(id, hash, id, hash, false) }
        assertThrows(IllegalArgumentException::class.java) { CloudMigrationPolicy.requireReceipt(id, hash, id, "other", true) }
    }

    @Test fun pendingUnsupportedAndUnverifiedSourcesKeepOriginalRules() {
        assertTrue(CloudMigrationPolicy.canConvert(true, "healthy"))
        for (status in listOf("pending", "unsupported", "failed", "unhealthy", "", "HEALTHY")) {
            assertFalse(CloudMigrationPolicy.canConvert(true, status))
            assertFalse(CloudMigrationPolicy.canConvert(false, status))
        }
        assertFalse(CloudMigrationPolicy.canConvert(false, "healthy"))
    }
}
