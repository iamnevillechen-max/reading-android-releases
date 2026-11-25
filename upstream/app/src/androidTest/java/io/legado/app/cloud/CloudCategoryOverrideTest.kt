package io.legado.app.cloud

import android.content.Context
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import fi.iki.elonen.NanoHTTPD
import io.legado.app.data.appDb
import io.legado.app.model.cloud.CloudCatalogue
import io.legado.app.model.cloud.CloudReader
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class CloudCategoryOverrideTest {
    @Test fun mergedCategoryCorrectionPersistsOnServerWithoutRewritingRules() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        check(Build.HARDWARE in setOf("ranchu", "goldfish") && appDb.bookSourceDao.allCount() == 0 &&
            appDb.rssSourceDao.size == 0 && appDb.bookDao.allBookCount == 0 && !CloudReader.isConfigured(context))
        val id = "https://owned-category.invalid/${UUID.randomUUID()}"
        val hash = "a".repeat(64)
        val urban = mapOf("id" to "urban", "label" to "都市／現實")
        val scifi = mapOf("id" to "scifi", "label" to "科幻")
        var category = urban
        var revision = 1
        var submitted: JsonObject? = null
        fun envelope(value: Any) = NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, "application/json", io.legado.app.utils.GSON.toJson(mapOf("data" to value)))
        val server = object : NanoHTTPD(0) {
            override fun serve(session: IHTTPSession): Response = when (session.uri) {
                "/v1/status" -> envelope(mapOf("categories" to listOf(urban, scifi)))
                "/v1/sources" -> envelope(mapOf("sources" to if (session.parameters["kind"]?.first() == "book") listOf(mapOf(
                    "id" to id, "name" to "Owned category source", "sha256" to hash, "supported" to true,
                    "enabled" to true, "enabledExplore" to true, "status" to "healthy")) else emptyList<Any>(), "nextCursor" to null))
                "/v1/source-receipt" -> envelope(mapOf("id" to id, "sha256" to hash, "stored" to true, "supported" to true, "status" to "healthy"))
                "/v1/catalogue" -> envelope(mapOf("revision" to revision.toString(), "categories" to listOf(category),
                    "routes" to listOf(mapOf("sourceId" to id, "sourceName" to "Owned category source", "label" to "合併分類",
                        "url" to "reader-cloud://category/${category["id"]}", "categoryId" to category["id"],
                        "categoryLabel" to category["label"], "fingerprint" to hash)),
                    "checkedSources" to 1, "totalSources" to 1, "nextCursor" to null))
                "/v1/category-override" -> {
                    val body = hashMapOf<String, String>(); session.parseBody(body)
                    submitted = JsonParser.parseString(body["postData"]).asJsonObject
                    category = scifi; revision++
                    envelope(mapOf("updated" to true))
                }
                else -> newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Owned fixture route missing")
            }
        }
        server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
        try {
            CloudReader.configure(context, "http://127.0.0.1:${server.listeningPort}", UUID.randomUUID().toString())
            CloudCatalogue.refresh("books")
            val original = requireNotNull(appDb.bookSourceDao.getBookSource(id))
            val route = CloudCatalogue.sourceRoutes(id).single()
            val fingerprint = CloudCatalogue.exploreRoutes(null).single().fingerprint
            revision++ // A periodic health check changes catalogue metadata, not source rules.
            CloudCatalogue.refresh("books", force = true)
            assertEquals(fingerprint, CloudCatalogue.exploreRoutes(null).single().fingerprint)
            assertEquals(listOf("urban", "scifi"), CloudCatalogue.categoryOptions().map { it.id })
            CloudCatalogue.reclassify(route, "scifi")
            assertEquals(id, submitted!!.get("sourceId").asString)
            assertEquals("book", submitted!!.get("kind").asString)
            assertEquals("urban", submitted!!.get("fromCategoryId").asString)
            assertEquals("scifi", submitted!!.get("categoryId").asString)
            assertTrue(CloudCatalogue.snapshot("books").routes.isEmpty())
            assertEquals("科幻", CloudCatalogue.refresh("books").categories.single().label)
            assertNotEquals(fingerprint, CloudCatalogue.exploreRoutes(null).single().fingerprint)
            assertEquals(io.legado.app.utils.GSON.toJson(original), io.legado.app.utils.GSON.toJson(appDb.bookSourceDao.getBookSource(id)))
        } finally {
            server.stop()
            appDb.bookSourceDao.delete(id)
            context.getSharedPreferences("reader_cloud_v1", 0).edit().clear().commit()
            CloudCatalogue.clear()
        }
    }
}
