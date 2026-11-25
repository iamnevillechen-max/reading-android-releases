package io.legado.app.cloud

import io.legado.app.model.cloud.CloudWebEntryPolicy
import org.junit.Assert.*
import org.junit.Test

class CloudWebEntryPolicyTest {
    @Test fun staticPublicWebsitePreservesItsQueryAndFragment() {
        val url = "https://website.example.org/news?q=public#section"
        assertEquals(url, CloudWebEntryPolicy.requireUrl(url))
        assertEquals("http://website.example.org/", CloudWebEntryPolicy.requireUrl("http://website.example.org/"))
    }

    @Test fun programsPrivateHostsCredentialsAndNonHttpSchemesFailClosed() {
        for (value in listOf("@js:java.ajax('https://website.example.org/')", "javascript:alert(1)",
            "https://website.example.org/{{page}}", "file:///sdcard/private", "intent://app", "http://localhost/",
            "http://127.0.0.1/", "http://0177.0.0.1/", "http://10.0.0.1/", "http://169.254.169.254/", "http://192.168.1.1/",
            "https://user:password@website.example.org/", "https://website.example.org:8443/", "https://secret.internal/",
            "https://website.example.org/\nheader")) {
            assertTrue(value, runCatching { CloudWebEntryPolicy.requireUrl(value) }.isFailure)
        }
    }
}
