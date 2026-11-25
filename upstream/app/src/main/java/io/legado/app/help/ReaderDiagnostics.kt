package io.legado.app.help

import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import io.legado.app.BuildConfig
import io.legado.app.service.BaseReadAloudService
import org.json.JSONArray
import org.json.JSONObject
import splitties.init.appCtx
import java.util.UUID
import java.util.concurrent.TimeUnit

/** A small, private crash-surviving journal. Only fixed event names and code symbols leave the device. */
object ReaderDiagnostics {
    private const val PREFS = "reader_diagnostics_v1"
    private const val MAX_EVENTS = 48
    private val keepMillis = TimeUnit.DAYS.toMillis(7)
    private val events = setOf("app_started", "reader_resumed", "reader_paused",
        "narration_started", "narration_stopped", "screen_off", "screen_on",
        "discovery_opened", "subscriptions_opened", "source_archived", "source_restored")
    private val codeName = Regex("""(?:io\.legado\.app|android|androidx|java|kotlin|kotlinx|okhttp3|org\.chromium)\.[A-Za-z0-9_.$]+""")
    private val methodName = Regex("""[A-Za-z0-9_$<>-]{1,100}""")
    internal val preferences get() = appCtx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun start(context: Context) {
        record("app_started")
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        context.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (!BaseReadAloudService.isRun) return
                when (intent.action) {
                    Intent.ACTION_SCREEN_OFF -> record("screen_off")
                    Intent.ACTION_SCREEN_ON -> record("screen_on")
                }
            }
        }, filter)
    }

    @Synchronized
    fun record(event: String) {
        if (event !in events) return
        val now = System.currentTimeMillis()
        val recent = recentEvents(now, MAX_EVENTS - 1)
        recent.put(JSONArray().put(now).put(event))
        preferences.edit().putString("events", recent.toString()).apply()
    }

    private fun recentEvents(now: Long, limit: Int): JSONArray {
        val previous = runCatching { JSONArray(preferences.getString("events", "[]")) }.getOrNull()
            ?: JSONArray()
        val recent = JSONArray()
        for (i in (previous.length() - limit).coerceAtLeast(0) until previous.length()) {
            val row = previous.optJSONArray(i) ?: continue
            if (now - row.optLong(0) in 0..keepMillis && row.optString(1) in events) recent.put(row)
        }
        return recent
    }

    internal fun crashSummary(error: Throwable): JSONObject {
        val types = JSONArray()
        val frames = JSONArray()
        var cause: Throwable? = error
        repeat(4) {
            val current = cause ?: return@repeat
            val type = current.javaClass.name
            types.put(type.takeIf { it.length <= 180 && codeName.matches(it) } ?: "java.lang.Throwable")
            current.stackTrace.asSequence().filter {
                it.className.length <= 180 && codeName.matches(it.className) && methodName.matches(it.methodName)
            }.take(5).forEach {
                frames.put("${it.className}.${it.methodName}:${it.lineNumber.coerceIn(-1, 999999)}")
            }
            cause = current.cause?.takeIf { it !== current }
        }
        return JSONObject().put("at", System.currentTimeMillis()).put("version", BuildConfig.VERSION_NAME)
            .put("types", types).put("frames", frames)
    }

    fun recordCrash(error: Throwable) {
        // This is the final chance to persist before the platform terminates this process.
        runCatching { preferences.edit().putString("crash", crashSummary(error).toString()).commit() }
    }

    internal fun snapshot(): JSONObject {
        val now = System.currentTimeMillis()
        val exits = JSONArray()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching {
                val manager = appCtx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                manager.getHistoricalProcessExitReasons(appCtx.packageName, 0, 8)
                    .filter { it.processName == appCtx.packageName && now - it.timestamp in 0..keepMillis }
                    .take(4).forEach { exits.put(JSONArray().put(it.timestamp).put(it.reason)) }
            }
        }
        val result = JSONObject().put("schema", 1).put("source", "android")
            .put("id", UUID.randomUUID().toString()).put("version", BuildConfig.VERSION_NAME)
            .put("api", Build.VERSION.SDK_INT).put("events", recentEvents(now, MAX_EVENTS)).put("exits", exits)
        preferences.getString("crash", null)?.let { raw ->
            val crash = runCatching { JSONObject(raw) }.getOrNull() ?: return@let
            if (now - crash.optLong("at") in 0..keepMillis) result.put("crash", crash)
        }
        return result
    }
}
