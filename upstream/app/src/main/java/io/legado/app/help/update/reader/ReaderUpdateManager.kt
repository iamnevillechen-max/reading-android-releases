package io.legado.app.help.update.reader

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

object ReaderUpdateManager {
    private val work = Executors.newSingleThreadExecutor { Thread(it, "reader-update-check").apply { isDaemon = true } }
    private val checking = AtomicBoolean()
    private val validating = AtomicInteger()
    @Volatile internal var manifestLoaderForTests: ((String) -> ReaderRelease)? = null
    private val main = Handler(Looper.getMainLooper())

    fun checking() = checking.get()
    fun validating() = validating.get() > 0

    fun check(context: Context, done: () -> Unit = {}): Boolean {
        val app = context.applicationContext
        if (ReaderUpdateService.isRunning() || validating() || !checking.compareAndSet(false, true)) return false
        val store = ReaderUpdateStore(app)
        try { store.checked(null, "checking") }
        catch (_: Exception) { checking.set(false); main.post(done); return false }
        work.execute {
            try {
                val release = manifestLoaderForTests?.invoke(app.packageName) ?: ReaderTransfer.fetchManifest(app.packageName)
                val installedCode = ReaderApkVerifier.versionCode(ReaderApkVerifier.installed(app))
                val state = when {
                    !release.newerThan(installedCode) -> "current"
                    !release.supports(Build.VERSION.SDK_INT, Build.SUPPORTED_ABIS) || Build.VERSION.SDK_INT < 24 -> "incompatible"
                    else -> "available"
                }
                store.checked(release, state)
            } catch (_: Exception) { store.failure("check_failed") }
            finally { checking.set(false); main.post(done) }
        }
        return true
    }

    fun validateReady(context: Context, done: (java.io.File?) -> Unit) {
        val app = context.applicationContext
        validating.incrementAndGet()
        work.execute {
            var file: java.io.File? = null
            try {
                val store = ReaderUpdateStore(app)
                val release = store.snapshot().ready
                file = try {
                    requireNotNull(release)
                    store.readyFile(release).also { ReaderApkVerifier.validate(app, it, release) }
                } catch (_: Exception) {
                    if (release != null) store.revokeReady(release.sha256) else store.failure("verify_failed")
                    null
                }
            } catch (_: Exception) { /* A storage failure keeps files and cannot hold the busy flag. */ }
            finally {
                validating.decrementAndGet()
                main.post { done(file) }
            }
        }
    }
}
