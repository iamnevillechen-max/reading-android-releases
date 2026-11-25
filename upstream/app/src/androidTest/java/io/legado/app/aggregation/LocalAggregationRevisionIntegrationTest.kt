package io.legado.app.aggregation

import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.BookSourcePart
import io.legado.app.model.aggregation.AggregationDatabaseRevision
import io.legado.app.model.aggregation.ExploreCatalogue
import io.legado.app.model.aggregation.ExploreRoute
import io.legado.app.model.aggregation.SourceSnapshotPolicy
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Owned local rows only; no source script, HTTP request, activity, or user-data reset. */
@RunWith(AndroidJUnit4::class)
class LocalAggregationRevisionIntegrationTest {
    private fun requireDedicated(): Context {
        val context = ApplicationProvider.getApplicationContext<Context>()
        check(Build.HARDWARE in setOf("ranchu", "goldfish") && context.packageName == "io.multiread.reader.debug") {
            "Only the dedicated test installation may run this fixture"
        }
        return context
    }

    private fun source(token: String, name: String) = BookSource(
        bookSourceUrl = "https://owned-aggregation.invalid/$token/$name",
        bookSourceName = "Owned $name", exploreUrl = "Owned route::/owned", jsLib = "owned-revision-a")

    @Test(timeout = 30_000) fun repeatedSameIdUpdatesInvalidateOnlyThatSourceAndRetainOtherCheckedSources() {
        requireDedicated()
        val token = UUID.randomUUID().toString()
        val first = source(token, "first")
        val second = source(token, "second")
        try {
            AggregationDatabaseRevision.bookSources() // Install on the real Room writer connection.
            appDb.bookSourceDao.insert(first, second)
            val before = AggregationDatabaseRevision.bookSources()
            appDb.bookSourceDao.update(first.copy(jsLib = "owned-revision-b"))
            appDb.bookSourceDao.update(first.copy(jsLib = "owned-revision-c"))
            val changes = AggregationDatabaseRevision.bookChanges(before)
            assertEquals(setOf(first.bookSourceUrl), changes.sources)
            assertNotEquals(before, changes.revision)
            val owned = setOf(first.bookSourceUrl, second.bookSourceUrl)
            assertEquals(setOf(second.bookSourceUrl), SourceSnapshotPolicy.retained(owned, owned, changes.sources))
            assertEquals("owned-revision-c", appDb.bookSourceDao.getBookSource(first.bookSourceUrl)!!.jsLib)
            assertEquals("owned-revision-a", appDb.bookSourceDao.getBookSource(second.bookSourceUrl)!!.jsLib)
        } finally { appDb.bookSourceDao.delete(first, second) }
    }

    private class OwnedRollback : RuntimeException()

    @Test(timeout = 30_000) fun noOpRuntimeOnlyAndRolledBackWritesDoNotInvalidateTheCatalogue() {
        requireDedicated()
        val owned = source(UUID.randomUUID().toString(), "runtime")
        try {
            AggregationDatabaseRevision.bookSources()
            appDb.bookSourceDao.insert(owned)
            val stored = requireNotNull(appDb.bookSourceDao.getBookSource(owned.bookSourceUrl))
            val before = AggregationDatabaseRevision.bookSources()
            appDb.bookSourceDao.update(stored.copy()) // Whole-row Room UPDATE OR ABORT, same values.
            assertEquals(before, AggregationDatabaseRevision.bookSources())
            appDb.bookSourceDao.update(stored.copy(respondTime = stored.respondTime + 17, weight = stored.weight + 1))
            assertEquals(before, AggregationDatabaseRevision.bookSources())
            assertEquals(emptySet<String>(), AggregationDatabaseRevision.bookChanges(before).sources)
            try {
                appDb.runInTransaction {
                    appDb.bookSourceDao.update(stored.copy(jsLib = "owned-must-roll-back"))
                    throw OwnedRollback()
                }
                fail("The owned transaction must roll back")
            } catch (_: OwnedRollback) { /* Only our explicit fixture exception is accepted. */ }
            assertEquals(before, AggregationDatabaseRevision.bookSources())
            assertEquals(emptySet<String>(), AggregationDatabaseRevision.bookChanges(before).sources)
            assertEquals(stored.jsLib, appDb.bookSourceDao.getBookSource(owned.bookSourceUrl)!!.jsLib)
        } finally { appDb.bookSourceDao.delete(owned) }
    }

    @Test(timeout = 30_000) fun anOldMetadataMapCannotPoisonTheNewCatalogueSnapshotCache() {
        val context = requireDedicated()
        val directory = File(context.cacheDir, "owned-catalogue-${UUID.randomUUID()}").apply { check(mkdirs()) }
        val isolated = object : ContextWrapper(context) { override fun getCacheDir(): File = directory }
        val old = BookSourcePart(bookSourceUrl = "https://owned-aggregation.invalid/cache",
            bookSourceName = "Owned before", bookSourceGroup = "Owned original group")
        val fresh = old.copy(bookSourceName = "Owned after", bookSourceGroup = "Owned changed group")
        val oldMap = mapOf(old.bookSourceUrl to old)
        val freshMap = mapOf(fresh.bookSourceUrl to fresh)
        try {
            ExploreCatalogue(isolated).use { catalogue ->
                val route = ExploreRoute(old, "Owned route", "/owned", "其他", "a".repeat(64))
                catalogue.replace(old, listOf(route))
                assertEquals("Owned before", catalogue.snapshot(oldMap, null).single().source.bookSourceName)
                catalogue.removeSources(setOf(old.bookSourceUrl))
                catalogue.replace(fresh, listOf(route.copy(source = fresh, fingerprint = "b".repeat(64))))
                // A suspended caller can arrive after replacement with its earlier captured map.
                catalogue.snapshot(oldMap, null)
                repeat(2) {
                    val snapshot = catalogue.snapshot(freshMap, null).single()
                    assertSame(fresh, snapshot.source)
                    assertEquals("Owned after", snapshot.source.bookSourceName)
                    assertEquals("Owned changed group", snapshot.source.bookSourceGroup)
                    assertEquals("b".repeat(64), snapshot.fingerprint)
                    assertTrue("An empty category must not reuse the All cache key", catalogue.snapshot(freshMap, "").isEmpty())
                    catalogue.snapshot(oldMap, null)
                }
            }
        } finally { check(directory.deleteRecursively()) }
    }
}
