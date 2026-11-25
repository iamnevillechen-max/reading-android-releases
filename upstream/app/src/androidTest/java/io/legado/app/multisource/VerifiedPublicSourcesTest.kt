package io.legado.app.multisource

import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.reflect.TypeToken
import io.legado.app.data.entities.BookSource
import io.legado.app.help.source.exploreKinds
import io.legado.app.model.webBook.WebBook
import io.legado.app.utils.GSON
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.security.MessageDigest
import java.io.File

/** Explicit live verification of normally public book pages, never a default network test. */
@RunWith(AndroidJUnit4::class)
class VerifiedPublicSourcesTest {
    @Test fun publicClassicSourceParsesThroughNativeSearchDetailsTocAndContent() = runBlocking {
        val privatePack = InstrumentationRegistry.getArguments().getString("privateSourcePack")
        assumeTrue("Explicit public or runtime private verification required",
            InstrumentationRegistry.getArguments().getString("verifiedPublicSources") == "true" || privatePack != null)
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        check(context.packageName == "io.multiread.reader.debug" && Build.HARDWARE in setOf("ranchu", "goldfish"))
        val arguments = InstrumentationRegistry.getArguments()
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        val pack = arguments.getString("publicSourcePack") ?: "verified-public-sources.json"
        val cases = arguments.getString("publicSourceCases") ?: "verified-public-source-cases.json"
        fun runtimeFile(path: String, limit: Long): File = File(path).also {
            check(it.canonicalPath.startsWith("/data/local/tmp/") && it.isFile && it.length() in 1..limit)
        }
        val sources: List<BookSource> = (privatePack?.let { runtimeFile(it, 1_000_000).reader() }
            ?: assets.open(pack).bufferedReader()).use {
            GSON.fromJson(it, object : TypeToken<List<BookSource>>() {}.type)
        }
        val privateCases = arguments.getString("privateSourceCases")
        check(privatePack == null || privateCases != null)
        val expectations = (privateCases?.let { runtimeFile(it, 256_000).reader() }
            ?: assets.open(cases).bufferedReader()).use {
            GSON.fromJson(it, com.google.gson.JsonObject::class.java).getAsJsonArray("verification")
        }
        val sourceFilter = arguments.getString("publicSourceFilter")
        val selectedCases = expectations.filter { sourceFilter == null || it.asJsonObject["sourceUrl"].asString == sourceFilter }
        check(selectedCases.isNotEmpty()) { "No matching source verification case" }
        for (item in selectedCases) withTimeout(90_000) {
            val expected = item.asJsonObject
            val source = sources.single { it.bookSourceUrl == expected["sourceUrl"].asString }
            val kind = if (source.enabledExplore) source.exploreKinds().first { !it.url.isNullOrBlank() } else null
            val discoveries = kind?.let { WebBook.exploreBookAwait(source, it.url!!) }.orEmpty()
            if (kind != null) assertTrue("The advertised discovery category must list real books", discoveries.isNotEmpty())
            assertTrue(discoveries.all { it.name.isNotBlank() && it.bookUrl.startsWith("https://") })
            val keyword = expected["keyword"].asString
            val results = WebBook.searchBookAwait(source, keyword)
            val selected = results.firstOrNull { it.name == expected["expectedBookName"].asString }
            assertNotNull("Native search must find the declared public book: ${source.bookSourceName}", selected)
            val book = selected!!.toBook()
            WebBook.getBookInfoAwait(source, book, canReName = false)
            assertEquals(expected["expectedBookName"].asString, book.name)
            assertEquals(expected["expectedAuthor"].asString, book.author)
            val chapters = WebBook.getChapterListAwait(source, book).getOrThrow()
            expected["expectedTocCount"]?.asInt?.let { assertEquals(it, chapters.size) }
            assertTrue(chapters.size >= (expected["expectedTocMinCount"]?.asInt ?: 1))
            assertTrue(chapters.first().title.startsWith(expected["expectedFirstChapterName"].asString))
            expected["excludePaidChapterUrlSuffix"]?.asString?.let { suffix ->
                assertTrue("Paid chapter links must be excluded by this free-only source", chapters.none { it.url.endsWith(suffix) })
            }
            expected["excludePaidChapterUrlSubstring"]?.asString?.let { value ->
                assertTrue("Paid chapter links must be excluded by this free-only source", chapters.none { value in it.url })
            }
            val first = expected["contentChapterTitlePrefix"]?.asString?.let { prefix ->
                chapters.first { it.title.startsWith(prefix) }
            } ?: chapters[expected["contentChapterIndex"]?.asInt ?: 0]
            assertFalse("Live verification may read only normally public free chapters", first.isVip && !first.isPay)
            // Reader reopens reconstruct Book from persisted fields, including source variables.
            val reopenedBook = GSON.fromJson(GSON.toJson(book), io.legado.app.data.entities.Book::class.java)
            val content = WebBook.getContentAwait(source, reopenedBook, first,
                chapters.getOrNull(chapters.indexOf(first) + 1)?.url, needSave = false)
            assertTrue("Public chapter extraction is too short", content.length >= expected["chapterMinCharacters"].asInt)
            expected["chapterMaxCharacters"]?.asInt?.let {
                assertTrue("Chapter extraction must not duplicate desktop and mobile body copies", content.length <= it)
            }
            expected["expectedFirstChapterContains"]?.asString?.let {
                assertTrue("The public chapter must retain its verse as well as prose", content.contains(it))
            }
            assertFalse(content.contains("<html", true))
            val sha = MessageDigest.getInstance("SHA-256").digest(content.toByteArray())
                .joinToString("") { "%02x".format(it.toInt() and 255) }
            // Only public metadata and a body hash; never copy chapter prose into reports.
            val details = mapOf("sourceUrl" to source.bookSourceUrl,
                "book" to book.name, "author" to book.author, "chapters" to chapters.size,
                "firstChapter" to first.title, "characters" to content.length, "sha256" to sha,
                "exploreCategory" to kind?.title, "exploreBooks" to discoveries.size)
            if (privatePack == null) println("PUBLIC_SOURCE_VERIFIED " + GSON.toJson(details))
            else {
                File(context.filesDir, "private-source-verification").apply { mkdirs() }
                    .resolve("results.json").writeText(GSON.toJson(details))
                println("PRIVATE_SOURCE_VERIFIED characters=${content.length} chapters=${chapters.size}")
            }
        }
    }
}
