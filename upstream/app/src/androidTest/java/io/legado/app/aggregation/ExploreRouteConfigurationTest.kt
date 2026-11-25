package io.legado.app.aggregation

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.rule.ExploreRule
import io.legado.app.help.source.clearExploreKindsCache
import io.legado.app.model.aggregation.ExploreAggregation
import io.legado.app.model.aggregation.ExploreRoute
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Actual Room invalidations and preference overrides; a single owned, non-network source. */
@RunWith(AndroidJUnit4::class)
class ExploreRouteConfigurationTest {
    @Test(timeout = 30_000)
    fun cachedRoutesRejectRuleOnlyEditsDisabledSourcesAndOldManualCategories() = runBlocking {
        check(appDb.bookSourceDao.allPart.isEmpty()) { "Dedicated empty source installation required" }
        val context = ApplicationProvider.getApplicationContext<Context>()
        val preferences = context.getSharedPreferences("aggregation_categories", Context.MODE_PRIVATE)
        val hadCursor = preferences.contains("route_cursor_v1")
        val oldCursor = preferences.getInt("route_cursor_v1", 0)
        val token = UUID.randomUUID().toString()
        val label = "自行編寫分類 $token"
        val source = BookSource(bookSourceUrl = "http://127.0.0.1/owned-route-$token",
            bookSourceName = "Owned route configuration $token", customOrder = 0,
            enabled = true, enabledExplore = true, exploreUrl = "$label::/owned-list",
            ruleExplore = ExploreRule(bookList = ".before"))
        var ownedRoute: ExploreRoute? = null
        var inserted = false
        var primaryFailure: Throwable? = null
        try {
            appDb.bookSourceDao.insert(source)
            inserted = true
            val categories = ArrayList<List<String>>()
            val first = ExploreAggregation.routes(context, onCategories = { categories.add(it) }).single()
            ownedRoute = first
            assertEquals(label, first.category)
            assertEquals(label, first.label)
            assertTrue(categories.any { label in it })
            assertEquals(first.fingerprint, ExploreAggregation.routes(context).single().fingerprint)

            // lastUpdateTime and all BookSourcePart metadata stay identical; only full rules change.
            source.ruleExplore!!.bookList = ".after"
            appDb.bookSourceDao.update(source)
            val edited = ExploreAggregation.routes(context).single()
            assertEquals(0L, source.lastUpdateTime)
            assertNotEquals(first.fingerprint, edited.fingerprint)
            // Equal-length edits must also invalidate; row lengths are not configuration identities.
            source.ruleExplore!!.bookList = ".other"
            appDb.bookSourceDao.update(source)
            assertNotEquals(edited.fingerprint, ExploreAggregation.routes(context).single().fingerprint)

            source.enabledExplore = false
            appDb.bookSourceDao.update(source)
            assertTrue(ExploreAggregation.routes(context).isEmpty())
            source.enabledExplore = true
            source.enabled = false
            appDb.bookSourceDao.update(source)
            assertTrue(ExploreAggregation.routes(context).isEmpty())
            source.enabled = true
            appDb.bookSourceDao.update(source)
            val enabled = ExploreAggregation.routes(context).single()

            val corrected = "言情"
            ExploreAggregation.setCategory(context, enabled, corrected)
            assertEquals("Retired unified-category overrides must not rename the source's original label",
                label, ExploreAggregation.categoryFor(context, source.bookSourceUrl, label))
            assertEquals(label, ExploreAggregation.routes(context).single().category)
            ExploreAggregation.setCategory(context, enabled, "")
            assertEquals(label, ExploreAggregation.routes(context).single().category)
        } catch (failure: Throwable) {
            primaryFailure = failure
            throw failure
        } finally {
            val failures = ArrayList<Throwable>()
            fun cleanup(action: () -> Unit) { runCatching(action).onFailure { failures.add(it) } }
            cleanup { ownedRoute?.let { ExploreAggregation.setCategory(context, it, "") } }
            if (inserted) {
                try { source.clearExploreKindsCache() } catch (failure: Throwable) { failures.add(failure) }
                cleanup { appDb.bookSourceDao.delete(source) }
            }
            cleanup {
                val edit = preferences.edit()
                if (hadCursor) edit.putInt("route_cursor_v1", oldCursor) else edit.remove("route_cursor_v1")
                check(edit.commit())
            }
            val original = primaryFailure
            if (original != null) failures.forEach(original::addSuppressed)
            else if (failures.isNotEmpty()) throw AssertionError("Owned route fixture cleanup failed").also {
                failures.forEach(it::addSuppressed)
            }
        }
    }
}
