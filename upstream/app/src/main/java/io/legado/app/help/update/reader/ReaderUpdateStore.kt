package io.legado.app.help.update.reader

import android.content.Context
import android.content.SharedPreferences
import java.io.File
import java.io.IOException
import java.util.UUID

/** Small private control records only. Reader databases, sources and preferences are untouched. */
class ReaderUpdateStore internal constructor(
    private val packageName: String,
    private val preferences: SharedPreferences,
    val directory: File
) {
    constructor(context: Context) : this(context.packageName,
        context.getSharedPreferences("reader_update_v1", Context.MODE_PRIVATE),
        File(context.filesDir, "reader-updates"))

    data class Snapshot(val release: ReaderRelease?, val ready: ReaderRelease?, val state: String,
        val downloaded: Long, val validator: String, val jobId: String, val error: String)

    fun partial(release: ReaderRelease) = File(directory, "partial/${release.sha256}.part")
    fun readyFile(release: ReaderRelease) = File(directory, "ready/${release.sha256}.apk")
    private fun decode(key: String) = preferences.getString(key, null)?.let {
        try { ReaderRelease.parse(it, packageName) } catch (_: Exception) { null }
    }

    fun snapshot(): Snapshot = synchronized(LOCK) {
        val release = decode("release")
        val ready = decode("ready")?.takeIf { readyFile(it).isFile && readyFile(it).length() == it.bytes }
        val partialBytes = release?.let {
            if (ready?.sha256 == it.sha256) it.bytes else partial(it).length().coerceIn(0, it.bytes)
        } ?: 0
        Snapshot(release, ready, preferences.getString("state", "idle") ?: "idle",
            partialBytes,
            preferences.getString("validator", "") ?: "", preferences.getString("job", "") ?: "",
            preferences.getString("error", "") ?: "")
    }

    fun checked(release: ReaderRelease?, state: String) = synchronized(LOCK) {
        val edit = preferences.edit().putString("state", state).putString("error", "")
        if (release != null) {
            if (decode("release")?.sha256 != release.sha256) edit.putLong("downloaded", 0).putString("validator", "")
            edit.putString("release", release.json)
        }
        commit(edit)
    }

    fun begin(release: ReaderRelease): String = synchronized(LOCK) {
        val id = UUID.randomUUID().toString()
        val old = snapshot()
        commit(preferences.edit().putString("release", release.json).putString("job", id)
            .putString("state", "queued").putString("error", "")
            .putLong("downloaded", partial(release).length().coerceIn(0, release.bytes))
            .putString("validator", if (old.release?.sha256 == release.sha256) old.validator else ""))
        directory.resolve("partial").listFiles()?.filter {
            it.name.matches(Regex("[0-9a-f]{64}\\.part")) && it.name != "${release.sha256}.part"
        }?.sortedByDescending { it.lastModified() }?.drop(1)?.forEach { it.delete() }
        id
    }

    fun update(id: String, state: String, downloaded: Long? = null, validator: String? = null,
        error: String = ""): Boolean = synchronized(LOCK) {
        if (preferences.getString("job", "") != id || preferences.getString("state", "") == "cancelled") return false
        val edit = preferences.edit().putString("state", state).putString("error", error.take(80))
        downloaded?.let { edit.putLong("downloaded", it) }
        validator?.let { edit.putString("validator", it.take(512)) }
        commit(edit)
        true
    }

    fun cancel(id: String) = synchronized(LOCK) {
        if (preferences.getString("job", "") == id)
            commit(preferences.edit().putString("state", "cancelled").putString("error", ""))
    }

    fun failure(reason: String) = synchronized(LOCK) {
        commit(preferences.edit().putString("state", "error").putString("error", reason.take(80)))
    }

    /** Revoke only this failed candidate's eligibility; keep files and other generations intact. */
    fun revokeReady(sha256: String): Boolean = synchronized(LOCK) {
        if (decode("ready")?.sha256 != sha256) return false
        commit(preferences.edit().remove("ready").putString("state", "error").putString("error", "verify_failed"))
        true
    }

    /** Only called after both byte verification and PackageManager/signature checks succeed. */
    fun promote(id: String, release: ReaderRelease): Boolean = synchronized(LOCK) {
        if (preferences.getString("job", "") != id || preferences.getString("state", "") == "cancelled") return false
        val candidate = partial(release)
        val ready = readyFile(release)
        ReaderTransfer.verifyHash(candidate, release.bytes, release.sha256)
        if (!ready.parentFile!!.isDirectory && !ready.parentFile!!.mkdirs()) throw IOException("ready_directory")
        if (ready.isFile) {
            val reusable = try { ReaderTransfer.verifyHash(ready, release.bytes, release.sha256); true }
                catch (_: SecurityException) { false }
            if (reusable) candidate.delete()
            // Same-filesystem rename replaces a damaged same-SHA target atomically on Android.
            // Do not delete it first: a failed rename must leave both files recoverable.
            else if (!candidate.renameTo(ready)) throw IOException("ready_replace")
        } else if (!candidate.renameTo(ready)) throw IOException("ready_move")
        commit(preferences.edit().putString("ready", release.json).putString("state", "ready")
            .putLong("downloaded", release.bytes).putString("error", ""))
        // Keep the preceding verified APK until its replacement is safely stored and usable.
        ready.parentFile?.listFiles()?.filter { it.name.matches(Regex("[0-9a-f]{64}\\.apk")) }
            ?.sortedByDescending { if (it == ready) Long.MAX_VALUE else it.lastModified() }
            ?.drop(2)?.forEach { it.delete() }
        directory.resolve("partial").listFiles()?.filter {
            it.name.matches(Regex("[0-9a-f]{64}\\.part")) && it.name != "${release.sha256}.part"
        }?.forEach { it.delete() }
        true
    }

    fun reconcile(running: Boolean) = synchronized(LOCK) {
        val state = preferences.getString("state", "idle")
        if (!running && state in setOf("queued", "downloading", "retrying", "verifying")) {
            commit(preferences.edit().putString("state", "paused").putString("error", "process_interrupted"))
        } else if (state == "checking") {
            commit(preferences.edit().putString("state", "error").putString("error", "check_interrupted"))
        }
    }

    private fun commit(edit: SharedPreferences.Editor) {
        if (!edit.commit()) throw IOException("update_state_storage")
    }

    companion object { private val LOCK = Any() }
}
