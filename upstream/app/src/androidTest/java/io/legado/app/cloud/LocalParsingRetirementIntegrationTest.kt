package io.legado.app.cloud

import android.content.SharedPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.RssSource
import io.legado.app.model.cloud.CloudMigrationPolicy
import io.legado.app.model.cloud.CloudReader
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException

/** Stale upgrade preferences cannot revive the removed service or turn proxies into local rules. */
@RunWith(AndroidJUnit4::class)
class LocalParsingRetirementIntegrationTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun restore(preferences: SharedPreferences, values: Map<String, *>) {
        val edit = preferences.edit().clear()
        values.forEach { (key, value) -> when (value) {
            is String -> edit.putString(key, value)
            is Boolean -> edit.putBoolean(key, value)
            is Int -> edit.putInt(key, value)
            is Long -> edit.putLong(key, value)
            is Float -> edit.putFloat(key, value)
            is Set<*> -> edit.putStringSet(key, value.filterIsInstance<String>().toSet())
        } }
        check(edit.commit())
    }

    @Test(timeout = 15_000) fun staleEnabledConfigurationCannotUploadParseOrMigrateAndKeepsTheOldCacheNamespace() = runBlocking {
        val preferences = context.getSharedPreferences("reader_cloud_v1", 0)
        val before = preferences.all.toMap()
        try {
            check(preferences.edit().clear().putString("url", "https://owned.example.invalid")
                .putString("token", "owned-test-token").putBoolean("enabled", true).commit())
            val oldRevision = CloudReader.configurationRevision(context)
            assertFalse(CloudReader.isEnabled(context))
            CloudReader.retire(context)
            assertEquals(oldRevision, CloudReader.configurationRevision(context))
            assertFalse(preferences.contains("token"))
            assertFalse(preferences.contains("url"))
            assertFalse(CloudReader.isConfigured(context))
            assertFalse(CloudReader.hasCredential(context))
            assertEquals("", CloudReader.endpoint(context))
            for (path in listOf("/v1/import", "/v1/books/content", "/v1/status")) {
                val failure = runCatching { CloudReader.request(path, context = context) }.exceptionOrNull()
                assertTrue("The common transport must stop stale direct callers", failure is IOException)
                assertEquals(CloudReader.LOCAL_SOURCE_REQUIRED, failure!!.message)
            }
            assertTrue(runCatching { CloudReader.configure(context, "https://other.example.invalid", "owned-new-token") }.isFailure)
            var progress = 0
            assertTrue(runCatching { CloudReader.migrate(context) { progress++ } }.isFailure)
            assertEquals(0, progress)
            assertEquals(0, CloudReader.syncCatalogue(context))
            assertEquals(oldRevision, CloudReader.configurationRevision(context))
        } finally { restore(preferences, before) }
    }

    @Test fun importingSameIdFullRulesRemovesProxyRoutingEvenForBooksWithAnOldRemoteContext() {
        val id = "https://owned.example.invalid"
        val proxy = BookSource(bookSourceUrl = id, bookSourceName = "Owned", searchUrl = CloudMigrationPolicy.STUB)
        val local = proxy.copy(searchUrl = "/search?q={{key}}")
        val book = Book(bookUrl = "$id/book/1", origin = id, name = "Owned Book", author = "Owned Author")
        book.putVariable("__reader_cloud_source_v1", id)
        assertTrue(CloudReader.isRemote(proxy, book))
        assertFalse(CloudReader.isRemote(local, book))
        assertTrue(CloudReader.isRemote(RssSource(sourceUrl = id, sourceName = "Owned", ruleArticles = CloudMigrationPolicy.STUB)))
        assertFalse(CloudReader.isRemote(RssSource(sourceUrl = id, sourceName = "Owned", ruleArticles = ".row")))
    }
}
