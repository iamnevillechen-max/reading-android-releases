package io.legado.app.cloud

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.legado.app.model.cloud.CloudReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class CloudConfigurationReceiptRaceTest {
    @Test(timeout=30_000) fun anOldServersInFlightAnnouncementCannotBeSavedUnderTheNewConfiguration() = runBlocking {
        CloudIntegrationFixture().use { fixture ->
            val entered=CountDownLatch(1);val release=CountDownLatch(1)
            fixture.gateway.statusEntered=entered;fixture.gateway.statusRelease=release
            val request=async(Dispatchers.IO) { runCatching { CloudReader.request("/v1/status") } }
            try {
                assertTrue(entered.await(10,TimeUnit.SECONDS))
                CloudReader.configure(fixture.application,CloudReader.endpoint(fixture.application),"owned-new-configuration-token")
                release.countDown()
                assertTrue("The old-server response must be rejected after a configuration change",request.await().isFailure)
                val preferences=fixture.application.getSharedPreferences("reader_cloud_v1",0)
                assertNull(preferences.getString("content_contract_revision",null))
                assertNull(preferences.getString("content_contract_version",null))
            } finally { release.countDown();request.await() }
        }
    }
}
