package io.legado.app.cloud

import io.legado.app.model.cloud.CloudBookMetadataPolicy
import org.junit.Assert.*
import org.junit.Test

class CloudBookMetadataPolicyTest {
    private val broken = "\" width=\"120\" height=\"168\" alt=\"Owned title\"/>"

    @Test fun markupMustNotBecomeAnIdentityButNormalPunctuationIsAllowed() {
        for (value in listOf(broken, "<img alt='Owned title'/>", "<span>Owned author</span>",
                "&lt;img alt=&quot;Owned title&quot;/&gt;")) {
            assertTrue(CloudBookMetadataPolicy.hasMarkup(value))
            assertFalse(CloudBookMetadataPolicy.isPlain(value))
        }
        for (value in listOf("Owned title", "A < B and 3 > 2", "HTML and CSS", "第120章：width=120")) {
            assertTrue(CloudBookMetadataPolicy.isPlain(value))
        }
        assertFalse(CloudBookMetadataPolicy.isPlain("\u0000Owned title"))
        assertFalse(CloudBookMetadataPolicy.isPlain("  "))
    }

    @Test fun onlyExactSourceAndBookMayRepairAnExistingMalformedIdentity() {
        val url = "https://fixture.invalid/book/7?edition=1"
        val origin = "https://fixture.invalid/#owned-source"
        assertTrue(CloudBookMetadataPolicy.sameBook(url, origin, origin, url, null))
        assertFalse(CloudBookMetadataPolicy.sameBook(url, origin, origin, url + "0", origin))
        assertFalse(CloudBookMetadataPolicy.sameBook(url, origin, origin + "0", url, origin))
        assertFalse(CloudBookMetadataPolicy.sameBook(url, origin, origin, url, origin + "0"))
        assertFalse(CloudBookMetadataPolicy.sameBook(url, origin, origin, null, null))
        assertEquals("Owned title", CloudBookMetadataPolicy.identityValue(broken, "Owned title", false, true))
        assertEquals(broken, CloudBookMetadataPolicy.identityValue(broken, "Owned title", false, false))
        assertEquals(broken, CloudBookMetadataPolicy.identityValue(broken, "Owned title", true, false))
        assertEquals("My title", CloudBookMetadataPolicy.identityValue("My title", "Server title", false, true))
        assertEquals("Server title", CloudBookMetadataPolicy.identityValue("My title", "Server title", true, true))
    }

    @Test fun emptyOrMalformedServerValuesNeverOverwriteAnExistingValue() {
        for (candidate in listOf(null, "", " ", broken, "<b>Owned title</b>")) {
            assertEquals(broken, CloudBookMetadataPolicy.identityValue(broken, candidate, false, true))
            assertEquals("Owned title", CloudBookMetadataPolicy.identityValue("Owned title", candidate, true, true))
        }
    }
}
