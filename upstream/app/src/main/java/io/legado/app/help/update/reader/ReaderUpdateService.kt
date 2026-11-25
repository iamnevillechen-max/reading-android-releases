package io.legado.app.help.update.reader

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import io.legado.app.R
import io.legado.app.ui.update.reader.ReaderUpdateActivity
import java.io.IOException
import java.io.InterruptedIOException
import java.net.HttpURLConnection
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean

/** One user-started transfer. No Activity, DownloadManager or external-storage dependency. */
class ReaderUpdateService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private var task: Future<*>? = null
    private var jobId = ""
    private val cancelled = AtomicBoolean()
    @Volatile private var socket: HttpURLConnection? = null
    private var lastProgress = 0L
    @Volatile private var completed = false
    private var wake: PowerManager.WakeLock? = null
    private val timeout = Runnable { pause("download_timeout") }

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(NotificationChannel(
            CHANNEL, getString(R.string.reader_update_notification_channel), NotificationManager.IMPORTANCE_LOW))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val store = ReaderUpdateStore(this)
        val id = intent?.getStringExtra("job") ?: ""
        if (intent?.action == CANCEL) {
            if (id == store.snapshot().jobId) { store.cancel(id); cancel(this, id) }
            if (jobId.isEmpty()) stopSelf(startId)
            return START_NOT_STICKY
        }
        if (intent?.action != START || id.isEmpty() || id != store.snapshot().jobId) {
            if (jobId.isEmpty()) stopSelf(startId)
            return START_NOT_STICKY
        }
        if (store.snapshot().state == "cancelled") {
            if (runningId == id) runningId = ""
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (jobId.isNotEmpty()) return START_NOT_STICKY
        jobId = id
        runningId = id
        owner = this
        try {
            val notice = notification(store.snapshot(), R.string.reader_update_downloading)
            if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION_ID, notice, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            else startForeground(NOTIFICATION_ID, notice)
        } catch (_: RuntimeException) {
            store.update(id, "paused", error = "foreground_denied")
            finishJob()
            return START_NOT_STICKY
        }
        try {
            val power = getSystemService(POWER_SERVICE) as PowerManager
            wake = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "reading:update-download").apply {
                setReferenceCounted(false)
                acquire(MAX_RUN_MS + 30_000)
            }
        } catch (_: RuntimeException) { /* The bounded transfer remains manually retryable. */ }
        main.postDelayed(timeout, MAX_RUN_MS)
        task = worker.submit { runTransfer(store, id) }
        return START_NOT_STICKY
    }

    private fun runTransfer(store: ReaderUpdateStore, id: String) {
        try {
            val release = store.snapshot().release ?: throw IOException("missing_release")
            if (!release.newerThan(ReaderApkVerifier.versionCode(ReaderApkVerifier.installed(this))))
                throw SecurityException("not_newer")
            val deadline = SystemClock.elapsedRealtime() + MAX_RUN_MS
            for (attempt in 0..2) {
                if (cancelled.get()) throw InterruptedIOException("cancelled")
                try {
                    store.update(id, "downloading")
                    ReaderTransfer.download(release, store.partial(release), store.snapshot().validator,
                        cancelled = { cancelled.get() || SystemClock.elapsedRealtime() >= deadline },
                        progress = { bytes, validator ->
                            val now = SystemClock.elapsedRealtime()
                            if (now - lastProgress >= 400 || bytes == release.bytes) {
                                if (store.update(id, "downloading", bytes, validator)) {
                                    lastProgress = now
                                    notifyProgress(store.snapshot(), R.string.reader_update_downloading)
                                }
                            }
                        }, connectionChanged = { socket = it },
                        connections = connectionsForTests ?: ReaderTransfer.NETWORK)
                    break
                } catch (offline: IOException) {
                    if (cancelled.get() || Thread.currentThread().isInterrupted || SystemClock.elapsedRealtime() >= deadline)
                        throw InterruptedIOException("cancelled")
                    if (attempt == 2) throw offline
                    if (!store.update(id, "retrying", error = "network_retry")) throw InterruptedIOException("cancelled")
                    notifyProgress(store.snapshot(), R.string.reader_update_retrying)
                    Thread.sleep(1000L shl attempt)
                }
            }
            if (cancelled.get()) throw InterruptedIOException("cancelled")
            if (!store.update(id, "verifying", release.bytes)) throw InterruptedIOException("cancelled")
            notifyProgress(store.snapshot(), R.string.reader_update_verifying)
            ReaderApkVerifier.validate(this, store.partial(release), release)
            if (cancelled.get()) throw InterruptedIOException("cancelled")
            completed = store.promote(id, release)
        } catch (_: InterruptedException) {
            store.update(id, "paused", error = "download_interrupted")
            Thread.currentThread().interrupt()
        } catch (_: InterruptedIOException) {
            store.update(id, "paused", error = "download_interrupted")
        } catch (_: SecurityException) {
            store.update(id, "error", error = "verify_failed")
        } catch (_: Exception) {
            store.update(id, "paused", error = "network_failed")
        } finally { main.post { finishJob() } }
    }

    private fun notifyProgress(snapshot: ReaderUpdateStore.Snapshot, message: Int) {
        if (!cancelled.get()) try {
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .notify(NOTIFICATION_ID, notification(snapshot, message))
        } catch (_: RuntimeException) { /* Notification permission denial does not abandon the transfer. */ }
    }

    private fun notification(snapshot: ReaderUpdateStore.Snapshot, message: Int): Notification {
        val open = PendingIntent.getActivity(this, 4107, Intent(this, ReaderUpdateActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val total = snapshot.release?.bytes ?: 0
        val builder = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download).setContentTitle(getString(R.string.reader_update_title))
            .setContentText(getString(message)).setContentIntent(open).setOnlyAlertOnce(true)
            .setOngoing(true).setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setProgress(100, if (total > 0) (snapshot.downloaded * 100 / total).toInt() else 0, total == 0L)
        if (snapshot.jobId.isNotEmpty()) {
            val cancel = PendingIntent.getService(this, snapshot.jobId.hashCode(), Intent(this, ReaderUpdateService::class.java)
                .setAction(CANCEL).putExtra("job", snapshot.jobId), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, getString(R.string.reader_update_cancel), cancel)
        }
        return builder.build()
    }

    private fun notifyComplete() {
        val open = PendingIntent.getActivity(this, 4108, Intent(this, ReaderUpdateActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notice = NotificationCompat.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(getString(R.string.reader_update_title)).setContentText(getString(R.string.reader_update_ready))
            .setContentIntent(open).setOnlyAlertOnce(true).setAutoCancel(true).build()
        try { (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(4108, notice) }
        catch (_: RuntimeException) { }
    }

    private fun pause(reason: String) {
        if (jobId.isNotEmpty()) ReaderUpdateStore(this).update(jobId, "paused", error = reason)
        cancelled.set(true)
        task?.cancel(true)
        disconnect.execute { try { socket?.disconnect() } catch (_: Exception) { } }
        finishJob()
    }

    @Suppress("DEPRECATION")
    private fun finishJob() {
        main.removeCallbacks(timeout)
        try { wake?.let { if (it.isHeld) it.release() } } catch (_: RuntimeException) { }
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE) else stopForeground(true)
        if (completed) notifyComplete()
        stopSelf()
    }

    override fun onTimeout(startId: Int, fgsType: Int) { pause("download_timeout") }
    override fun onDestroy() {
        if (!completed && jobId.isNotEmpty() && !cancelled.get()) {
            try { ReaderUpdateStore(this).update(jobId, "paused", error = "download_interrupted") } catch (_: Exception) { }
        }
        cancelled.set(true)
        task?.cancel(true)
        disconnect.execute { try { socket?.disconnect() } catch (_: Exception) { } }
        main.removeCallbacks(timeout)
        try { wake?.let { if (it.isHeld) it.release() } } catch (_: RuntimeException) { }
        if (owner === this) { owner = null; runningId = "" }
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val START = "io.legado.app.reader.UPDATE_DOWNLOAD"
        private const val CANCEL = "io.legado.app.reader.UPDATE_CANCEL"
        private const val CHANNEL = "reader-update-downloads"
        private const val NOTIFICATION_ID = 4107
        private const val MAX_RUN_MS = 15L * 60 * 1000
        private val worker = Executors.newSingleThreadExecutor { Thread(it, "reader-update-download").apply { isDaemon = true } }
        private val disconnect = Executors.newSingleThreadExecutor { Thread(it, "reader-update-close").apply { isDaemon = true } }
        @Volatile private var runningId = ""
        @Volatile private var owner: ReaderUpdateService? = null
        @Volatile internal var connectionsForTests: ReaderTransfer.Connections? = null
        fun isRunning() = runningId.isNotEmpty() || owner != null

        @Synchronized fun start(context: Context, release: ReaderRelease) {
            if (isRunning()) return
            val store = ReaderUpdateStore(context)
            val id = store.begin(release)
            runningId = id
            try { ContextCompat.startForegroundService(context, Intent(context, ReaderUpdateService::class.java)
                .setAction(START).putExtra("job", id)) }
            catch (_: RuntimeException) { runningId = ""; store.update(id, "paused", error = "foreground_denied") }
        }

        fun cancel(context: Context, id: String) {
            ReaderUpdateStore(context).cancel(id)
            val service = owner
            if (service != null && service.jobId == id) service.main.post { service.pause("cancelled") }
            else if (runningId == id) runningId = ""
        }
    }
}
