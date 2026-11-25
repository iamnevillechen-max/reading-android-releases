package io.legado.app.help

import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import fi.iki.elonen.NanoHTTPD
import io.legado.app.BuildConfig
import io.legado.app.R
import io.legado.app.data.appDb
import io.legado.app.help.config.LocalConfig
import io.legado.app.ui.main.MainActivity
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(AndroidJUnit4::class)
class ReaderDiagnosticsTest {
    @Test
    fun retryReusesThePersistedRedactedCrashReport() = runBlocking {
        val prefs = ReaderDiagnostics.preferences
        prefs.edit().clear().commit()
        val bodies = CopyOnWriteArrayList<String>()
        val server = object : NanoHTTPD("127.0.0.1", 0) {
            override fun serve(session: IHTTPSession): Response {
                val fields = hashMapOf<String, String>()
                session.parseBody(fields)
                val raw = fields.getValue("postData")
                bodies.add(raw)
                val reply = JSONObject().put("id", if (bodies.size == 1) "wrong-receipt" else JSONObject(raw).getString("id"))
                return newFixedLengthResponse(Response.Status.OK, "application/json", reply.toString())
            }
        }
        try {
            ReaderDiagnostics.record("narration_started")
            ReaderDiagnostics.record("private book title")
            val error = IllegalStateException("secret URL and book title", RuntimeException("private username"))
            ReaderDiagnostics.recordCrash(error)
            repeat(52) { ReaderDiagnostics.record("screen_off") }
            assertTrue(prefs.contains("crash"))
            server.start()
            val url = "http://127.0.0.1:${server.listeningPort}/reports"
            assertTrue(runCatching { ReaderReportUpload.sendTo(url) }.isFailure)
            assertFalse(prefs.contains("receipt"))
            val pending = prefs.getString("pending", null)!!
            ReaderDiagnostics.record("screen_on")
            val id = ReaderReportUpload.sendTo(url)
            assertEquals(listOf(pending, pending), bodies.toList())
            val submitted = JSONObject(pending)
            assertEquals(submitted.getString("id"), id)
            assertEquals(48, submitted.getJSONArray("events").length())
            assertFalse(pending.contains("secret")); assertFalse(pending.contains("private"))
            val crash = submitted.getJSONObject("crash")
            assertEquals("java.lang.IllegalStateException", crash.getJSONArray("types").getString(0))
            assertEquals("java.lang.RuntimeException", crash.getJSONArray("types").getString(1))
            assertTrue(crash.getJSONArray("frames").length() > 0)
            assertEquals(id, prefs.getString("receipt", null))
            assertFalse(prefs.contains("pending"))
        } finally {
            server.stop()
            prefs.edit().clear().commit()
        }
    }

    @Test
    fun uploadFromMyTabReturnsLiveReceipt() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveReceipt") == "yes")
        check(Build.HARDWARE in setOf("ranchu", "goldfish") && appDb.bookDao.allBookCount == 0 &&
            appDb.bookSourceDao.allCount() == 0)
        val prefs = ReaderDiagnostics.preferences
        prefs.edit().clear().commit()
        ReaderDiagnostics.record("reader_resumed")
        ReaderDiagnostics.recordCrash(IllegalStateException("Synthetic release check"))
        LocalConfig.edit().putBoolean("privacyPolicyOk", true)
            .putLong("appVersionCode", BuildConfig.VERSION_CODE.toLong())
            .putString("password", "")
            .putBoolean("appCrash", false)
            .putLong("lastBackup", System.currentTimeMillis()).commit()
        ActivityScenario.launch(MainActivity::class.java).use {
            onView(withId(R.id.menu_my_config)).perform(click())
            onView(withText(R.string.reader_diagnostics_title)).perform(click())
            val deadline = SystemClock.elapsedRealtime() + 30_000
            while (!prefs.contains("receipt") && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(100)
            val receipt = requireNotNull(prefs.getString("receipt", null)) { "No receipt from the live upload" }
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            onView(withText(context.getString(R.string.reader_diagnostics_sent, receipt.substringBefore('-'))))
                .check(matches(isDisplayed()))
            Log.i("ReaderReleaseReceipt", receipt)
        }
    }
}
