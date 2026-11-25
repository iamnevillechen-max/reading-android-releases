package io.legado.app.help.source

import android.content.Context
import com.google.gson.reflect.TypeToken
import io.legado.app.constant.AppLog
import io.legado.app.data.entities.BookSource
import io.legado.app.utils.GSON
import io.legado.app.model.cloud.CloudReader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import splitties.init.appCtx
import java.net.URI

/** Add independent bundled rules once; existing user rules, including disabled ones, always win. */
object VerifiedSourceBootstrap {
    const val PREFS_NAME = "reader_verified_sources_v1"
    const val VERSION_KEY = "packVersion"
    // Bump when adding a new bundled identifier. Existing identifiers must remain stable.
    const val PACK_VERSION = 2026102702
    private const val MAX_SOURCES = 32
    private val lock = Mutex()

    /** Main startup and direct reader recovery share the same bounded, network-free operation. */
    suspend fun ensureSeeded(): Boolean = withContext(IO) {
        if (CloudReader.isEnabled()) return@withContext true
        lock.withLock {
            val preferences = appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            if (preferences.getInt(VERSION_KEY, 0) >= PACK_VERSION) return@withLock true
            try {
                val sources: List<BookSource> = appCtx.assets.open("reader-verified-sources.json")
                    .bufferedReader().use { GSON.fromJson(it, object : TypeToken<List<BookSource>>() {}.type) }
                require(sources.size in 1..MAX_SOURCES && sources.map { it.bookSourceUrl }.toSet().size == sources.size)
                require(sources.all { source ->
                    val uri = URI(source.bookSourceUrl)
                    uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null &&
                        uri.fragment?.startsWith("reader-verified-") == true && source.bookSourceType == 0
                })
                // This native helper checks missing IDs on the writer transaction and
                // retains the ordinary domain filter, without renumbering existing rules.
                val activeSources = sources.filterNot { BookSourceAutoRemoval.isArchived(it.bookSourceUrl) }
                val complete = SourceHelp.insertNewBookSources(*activeSources.toTypedArray())
                complete && preferences.edit().putInt(VERSION_KEY, PACK_VERSION).commit()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                AppLog.put("核验书源自动添加未完成，稍后重试", error)
                false
            }
        }
    }
}
