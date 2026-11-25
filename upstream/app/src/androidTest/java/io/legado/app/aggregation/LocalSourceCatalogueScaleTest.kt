package io.legado.app.aggregation

import android.content.Context
import android.content.ContextWrapper
import android.os.SystemClock
import android.util.Log
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.legado.app.data.AppDatabase
import io.legado.app.data.entities.BookSource
import io.legado.app.model.aggregation.AggregationPolicy
import io.legado.app.model.aggregation.ExploreCatalogue
import io.legado.app.model.aggregation.ExploreLiteralRules
import io.legado.app.model.aggregation.ExploreRoute
import io.legado.app.model.aggregation.LocalCategoryPolicy
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** The full original row count, using owned inert data and disposable databases only. */
@RunWith(AndroidJUnit4::class)
class LocalSourceCatalogueScaleTest {
    @Test(timeout = 120_000) fun all21149SourcesReachTheLocalCatalogueWithoutReadingOrExecutingTheirLibraries() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val directory = File(context.cacheDir, "owned-scale-${UUID.randomUUID()}").apply { check(mkdirs()) }
        val isolated = object : ContextWrapper(context) { override fun getCacheDir() = directory }
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val library = "throw new Error('Owned parsing library must not run during classification');".repeat(16)
        val started = SystemClock.elapsedRealtime()
        try {
            database.runInTransaction {
                (0 until 21_149).chunked(100).forEach { indices ->
                    database.bookSourceDao.insert(*indices.map { index ->
                        BookSource(bookSourceUrl = "https://owned-scale.invalid/$index", bookSourceName = "Owned $index",
                            bookSourceGroup = "Owned original group $index", customOrder = index,
                            searchUrl = "/search", exploreUrl = "Owned original category $index::/list/$index", jsLib = library)
                    }.toTypedArray())
                }
            }
            val seeded = SystemClock.elapsedRealtime()
            val metadata = database.bookSourceDao.aggregateExploreSpecs
            assertEquals(21_149, metadata.size)
            assertEquals("https://owned-scale.invalid/0", metadata.first().source.bookSourceUrl)
            assertEquals("https://owned-scale.invalid/21148", metadata.last().source.bookSourceUrl)
            val parts = metadata.associate { it.source.bookSourceUrl to it.source }
            ExploreCatalogue(isolated).use { store ->
                metadata.chunked(100).forEach { batch ->
                    store.replaceAll(batch.map { spec ->
                        spec.source to ExploreLiteralRules.parse(spec.menu.orEmpty()).map { kind ->
                            ExploreRoute(spec.source, kind.title, requireNotNull(kind.url),
                                LocalCategoryPolicy.category(kind.title, spec.source.bookSourceGroup), "owned-${spec.source.bookSourceUrl}")
                        }
                    })
                }
                val all = store.snapshot(parts, AggregationPolicy.ALL_SOURCES)
                assertEquals(21_149, all.size)
                assertEquals(parts.keys, all.mapTo(hashSetOf()) { it.source.bookSourceUrl })
                assertEquals("Every original fine category survives the full source count", 21_149, store.categories().size)
                assertEquals((0 until 21_149).mapTo(hashSetOf()) { "Owned original category $it" }, store.categories().toSet())
                val selected = store.snapshot(parts, "Owned original category 21148")
                assertEquals(1, selected.size)
                assertEquals("https://owned-scale.invalid/21148", selected.single().source.bookSourceUrl)
                assertEquals("/list/21148", selected.single().url)
                assertEquals("/list/21148", all.last().url)
                assertSame("Unchanged snapshots should not allocate another full route list", all, store.snapshot(parts, null))
            }
            assertEquals(21_149, database.bookSourceDao.allCount())
            assertEquals(library, database.bookSourceDao.getBookSource("https://owned-scale.invalid/21148")!!.jsLib)
            assertEquals("Owned original group 21148", database.bookSourceDao.getBookSource("https://owned-scale.invalid/21148")!!.bookSourceGroup)
            assertEquals("Owned original category 21148::/list/21148", database.bookSourceDao.getBookSource("https://owned-scale.invalid/21148")!!.exploreUrl)
            Log.i("LocalCatalogueScale", "rows=21149 seedMs=${seeded-started} catalogueMs=${SystemClock.elapsedRealtime()-seeded}")
        } finally { database.close(); directory.deleteRecursively() }
    }
}
