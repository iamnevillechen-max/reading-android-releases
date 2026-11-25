package io.legado.app.help

import io.legado.app.help.http.await
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

object ReaderReportUpload {
    private const val ENDPOINT = "https://reading-feedback.iamnevillechen.workers.dev/api/v1/reports"
    private val lock = Mutex()
    private val client by lazy {
        OkHttpClient.Builder().callTimeout(20, TimeUnit.SECONDS).followRedirects(false).build()
    }

    /** The exact same body is retried after a lost reply, including after an app restart. */
    suspend fun send(): String = sendTo(ENDPOINT)

    internal suspend fun sendTo(endpoint: String): String = withContext(Dispatchers.IO) {
        lock.withLock {
            val prefs = ReaderDiagnostics.preferences
            val pending = prefs.getString("pending", null) ?: ReaderDiagnostics.snapshot().toString().also {
                if (!prefs.edit().putString("pending", it).commit()) throw IOException()
            }
            val id = JSONObject(pending).getString("id")
            val request = Request.Builder().url(endpoint)
                .post(pending.toRequestBody("application/json; charset=utf-8".toMediaType())).build()
            client.newCall(request).await().use { response ->
                if (response.code == 400 || response.code == 409) {
                    prefs.edit().remove("pending").commit()
                }
                if (response.code != 200 && response.code != 201) throw IOException()
                val reply = JSONObject(response.peekBody(1024).string())
                if (reply.optString("id") != id) throw IOException()
            }
            if (!prefs.edit().remove("pending").putString("receipt", id).commit()) throw IOException()
            id
        }
    }
}
