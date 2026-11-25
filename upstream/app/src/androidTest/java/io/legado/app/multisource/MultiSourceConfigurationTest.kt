package io.legado.app.multisource

import android.content.Context
import android.os.BatteryManager
import android.os.Build
import androidx.room.InvalidationTracker
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.gson.JsonParser
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.rule.ContentRule
import io.legado.app.model.ReadBook
import io.legado.app.help.source.VerifiedSourceBootstrap
import io.legado.app.model.multisource.MultiSourceManager
import io.legado.app.service.BaseReadAloudService
import io.legado.app.utils.GSON
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Immediate cache validity is independent of Room's asynchronous observer delivery. */
@RunWith(AndroidJUnit4::class)
class MultiSourceConfigurationTest {
    @Test(timeout = 30_000)
    fun equalLengthRuleEditInvalidatesEvenWhileRoomNotificationIsBlocked() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        check(context.packageName == "io.multiread.reader.debug" &&
            Build.HARDWARE in setOf("ranchu", "goldfish")) { "Dedicated emulator installation required" }
        check(appDb.bookSourceDao.allPart.isEmpty() && appDb.bookDao.allBookCount == 0 &&
            ReadBook.book == null && !BaseReadAloudService.isRun) { "Dedicated idle source/book fixture required" }
        val battery = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        assumeTrue((battery?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: 100) !in 1..15)
        val preferences = context.getSharedPreferences("multisource", Context.MODE_PRIVATE)
        val oldEnabled = preferences.getBoolean("enabled", true)
        val hadEnabled = preferences.contains("enabled")
        val sourceMigration = context.getSharedPreferences(VerifiedSourceBootstrap.PREFS_NAME, Context.MODE_PRIVATE)
        val hadPackVersion = sourceMigration.contains(VerifiedSourceBootstrap.VERSION_KEY)
        val oldPackVersion = sourceMigration.getInt(VerifiedSourceBootstrap.VERSION_KEY, 0)
        val token = UUID.randomUUID().toString()
        val base = "http://127.0.0.1/owned-config-$token"
        val source = BookSource(bookSourceUrl = base, bookSourceName = "Owned configuration $token",
            enabled = true, bookSourceType = 0, ruleContent = ContentRule(content = ".alpha"))
        val extra = source.copy(bookSourceUrl = "$base/extra", bookSourceName = "Owned inserted source $token")
        val book = Book(bookUrl = "$base/book", origin = base, name = "自行編寫來源設定測試 $token", author = "自寫測試作者")
        val chapter = BookChapter(bookUrl = book.bookUrl, url = "$base/chapter", title = "第一章 夜雨", index = 0)
        val raw = "夜雨沿著石階緩緩落下，旅客帶著封好的信走到城門旁。他停下來看清石碑的文字，才繼續前行。\n\n" +
            "守門人點亮桌上的油燈，照出信封上早已模糊的印章。這是自行編寫的測試正文，沒有第三方小說內容。"
        val cacheFile = File(context.filesDir, "multisource-v1/${sha(book.bookUrl + "\n" + chapter.url)}.json")
        val armed = AtomicBoolean()
        val initialNotification = CountDownLatch(1)
        val observerEntered = CountDownLatch(1)
        val releaseObserver = CountDownLatch(1)
        val observer = object : InvalidationTracker.Observer("book_sources") {
            override fun onInvalidated(tables: Set<String>) {
                if (armed.compareAndSet(true, false)) {
                    observerEntered.countDown()
                    releaseObserver.await(10, TimeUnit.SECONDS)
                } else initialNotification.countDown()
            }
        }
        var inserted = false
        var extraInserted = false
        var observing = false
        var primaryFailure: Throwable? = null
        try {
            check(sourceMigration.edit().putInt(VerifiedSourceBootstrap.VERSION_KEY, VerifiedSourceBootstrap.PACK_VERSION).commit())
            appDb.invalidationTracker.addObserver(observer)
            observing = true
            appDb.bookSourceDao.insert(source)
            inserted = true
            assertTrue("Initial fixture insertion must finish its notification before arming the edit gate",
                initialNotification.await(5, TimeUnit.SECONDS))
            val nativeSchema = schema()
            MultiSourceManager.enabled = true
            assertEquals(raw, MultiSourceManager.enrich(source, book, chapter, raw))
            assertEquals(raw, MultiSourceManager.cached(book, chapter, raw))
            assertEquals("The revision tracker must not add persistent schema objects", nativeSchema, schema())
            val beforeFingerprint = configuration(cacheFile)
            val beforePart = GSON.toJson(appDb.bookSourceDao.getBookSourcePart(base))

            // Keep every lightweight field and string length identical; timestamps cannot help.
            assertEquals(source.ruleContent!!.content!!.length, ".bravo".length)
            source.ruleContent!!.content = ".bravo"
            armed.set(true)
            appDb.bookSourceDao.update(source)
            assertTrue("Room callback must be held while cache validity is checked",
                observerEntered.await(5, TimeUnit.SECONDS))
            assertEquals(beforePart, GSON.toJson(appDb.bookSourceDao.getBookSourcePart(base)))
            assertNull("Completed rule edits must invalidate before observer delivery",
                MultiSourceManager.cached(book, chapter, raw))
            releaseObserver.countDown()
            assertEquals(raw, MultiSourceManager.enrich(source, book, chapter, raw))
            assertNotEquals(beforeFingerprint, configuration(cacheFile))
            assertEquals(raw, MultiSourceManager.cached(book, chapter, raw))

            appDb.bookSourceDao.insert(extra)
            extraInserted = true
            assertNull("An inserted enabled source changes the merge configuration",
                MultiSourceManager.cached(book, chapter, raw))
            appDb.bookSourceDao.delete(extra)
            extraInserted = false
            assertEquals(raw, MultiSourceManager.cached(book, chapter, raw))
            source.enabled = false
            appDb.bookSourceDao.update(source)
            assertNull("Disabled sources must not keep an old result valid",
                MultiSourceManager.cached(book, chapter, raw))
            source.enabled = true
            appDb.bookSourceDao.update(source)
            assertEquals(raw, MultiSourceManager.cached(book, chapter, raw))
            appDb.bookSourceDao.delete(source)
            inserted = false
            assertNull("A deleted source must invalidate immediately", MultiSourceManager.cached(book, chapter, raw))
            assertEquals(nativeSchema, schema())
            assertEquals(0, appDb.bookDao.allBookCount)
        } catch (failure: Throwable) {
            primaryFailure = failure
            throw failure
        } finally {
            releaseObserver.countDown()
            val failures = ArrayList<Throwable>()
            fun cleanup(action: () -> Unit) { runCatching(action).onFailure(failures::add) }
            if (observing) cleanup { appDb.invalidationTracker.removeObserver(observer) }
            if (extraInserted) cleanup { appDb.bookSourceDao.delete(extra) }
            if (inserted) cleanup { appDb.bookSourceDao.delete(source) }
            cleanup { MultiSourceManager.invalidate(book, chapter) }
            cleanup { MultiSourceManager.clearCandidateHistory(book) }
            cleanup {
                val edit = preferences.edit()
                if (hadEnabled) edit.putBoolean("enabled", oldEnabled) else edit.remove("enabled")
                check(edit.commit())
            }
            cleanup {
                check(sourceMigration.edit().apply {
                    if (hadPackVersion) putInt(VerifiedSourceBootstrap.VERSION_KEY, oldPackVersion)
                    else remove(VerifiedSourceBootstrap.VERSION_KEY)
                }.commit())
            }
            val original = primaryFailure
            if (original != null) failures.forEach(original::addSuppressed)
            else if (failures.isNotEmpty()) throw AssertionError("Owned configuration cleanup failed").also {
                failures.forEach(it::addSuppressed)
            }
        }
    }

    private fun configuration(file: File) = JsonParser.parseString(file.readText()).asJsonObject["configuration"].asString

    private fun schema(): List<String> = appDb.openHelper.readableDatabase.query(
        "select type, name, sql from sqlite_master order by type, name").use { cursor ->
        buildList {
            while (cursor.moveToNext()) add((0..2).joinToString("\u0000") { cursor.getString(it).orEmpty() })
        }
    }

    private fun sha(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        .joinToString("") { "%02x".format(it.toInt() and 255) }
}
