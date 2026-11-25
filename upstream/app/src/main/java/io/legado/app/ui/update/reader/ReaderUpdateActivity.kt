package io.legado.app.ui.update.reader

import android.Manifest
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import io.legado.app.R
import io.legado.app.base.BaseActivity
import io.legado.app.databinding.ActivityReaderUpdateBinding
import io.legado.app.help.update.reader.ReaderApkVerifier
import io.legado.app.help.update.reader.ReaderRelease
import io.legado.app.help.update.reader.ReaderStartupUpdates
import io.legado.app.help.update.reader.ReaderUpdateUiPolicy
import io.legado.app.help.update.reader.ReaderUpdateManager
import io.legado.app.help.update.reader.ReaderUpdateService
import io.legado.app.help.update.reader.ReaderUpdateStore
import io.legado.app.utils.viewbindingdelegate.viewBinding
import java.util.Locale

/** All outcomes remain native text/buttons, including network failure and permission refusal. */
class ReaderUpdateActivity : BaseActivity<ActivityReaderUpdateBinding>(fullScreen = false) {
    override val binding by viewBinding(ActivityReaderUpdateBinding::inflate)
    private val main = Handler(Looper.getMainLooper())
    private val store by lazy { ReaderUpdateStore(this) }
    private var verifying = false
    private var pendingDownloadSha: String? = null
    private var localMessage: String? = null
    private val poll = object : Runnable {
        override fun run() { render(); main.postDelayed(this, 800) }
    }
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        beginDownload()
    }
    private val settingsResult = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        localMessage = getString(if (canInstall()) R.string.reader_update_permission_granted else R.string.reader_update_permission_denied)
        render()
    }
    private val installerResult = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        localMessage = getString(R.string.reader_update_installer_returned)
        render()
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        pendingDownloadSha = savedInstanceState?.getString(PENDING_DOWNLOAD)
        binding.checkUpdate.setOnClickListener {
            localMessage = null
            ReaderUpdateManager.check(this) { if (!isFinishing && !isDestroyed) render() }
            render()
        }
        binding.downloadUpdate.setOnClickListener {
            requestDownload(store.snapshot().release?.sha256)
        }
        binding.cancelUpdate.setOnClickListener {
            pendingDownloadSha = null
            if (ReaderUpdateService.isRunning()) ReaderUpdateService.cancel(this, store.snapshot().jobId)
            finish()
        }
        binding.backgroundUpdate.setOnClickListener { finish() }
        binding.allowInstall.setOnClickListener {
            try { settingsResult.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"))) }
            catch (_: Exception) { localMessage = getString(R.string.reader_update_settings_failed); render() }
        }
        binding.installUpdate.setOnClickListener { install() }
        if (!ReaderUpdateManager.checking()) store.reconcile(ReaderUpdateService.isRunning())
        render()
        val selectedSha = intent.getStringExtra(DOWNLOAD_SHA)
        if (savedInstanceState == null && selectedSha != null) requestDownload(selectedSha)
        // Reopening must fetch the newest manifest even when an older APK is already ready.
        // The manager keeps ready/partial files and skips active download/verification work.
        if (selectedSha == null && pendingDownloadSha == null &&
            !ReaderUpdateService.isRunning() && !ReaderUpdateManager.validating())
            ReaderUpdateManager.check(this) { if (!isFinishing && !isDestroyed) render() }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        pendingDownloadSha?.let { outState.putString(PENDING_DOWNLOAD, it) }
        super.onSaveInstanceState(outState)
    }

    override fun onResume() {
        super.onResume()
        main.removeCallbacks(poll)
        main.post(poll)
    }

    override fun onPause() {
        main.removeCallbacks(poll)
        super.onPause()
    }

    private fun requestDownload(selectedSha: String?) {
        if (pendingDownloadSha != null || ReaderUpdateService.isRunning() || ReaderUpdateManager.checking() ||
            ReaderUpdateManager.validating() || verifying) return
        val snapshot = store.snapshot()
        val release = ReaderUpdateUiPolicy.requestedDownload(snapshot.release, selectedSha,
            ReaderApkVerifier.versionCode(ReaderApkVerifier.installed(this)), Build.VERSION.SDK_INT,
            Build.SUPPORTED_ABIS) ?: return
        if (snapshot.ready?.sha256 == release.sha256) return
        pendingDownloadSha = release.sha256
        render()
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this,
                Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        else beginDownload()
    }

    private fun beginDownload() {
        localMessage = null
        val selectedSha = pendingDownloadSha
        pendingDownloadSha = null
        if (isFinishing || isDestroyed) return
        if (ReaderUpdateService.isRunning() || ReaderUpdateManager.checking() ||
            ReaderUpdateManager.validating() || verifying) { render(); return }
        val release = ReaderUpdateUiPolicy.requestedDownload(store.snapshot().release, selectedSha,
            ReaderApkVerifier.versionCode(ReaderApkVerifier.installed(this)), Build.VERSION.SDK_INT,
            Build.SUPPORTED_ABIS) ?: run { render(); return }
        try { ReaderUpdateService.start(this, release) }
        catch (_: Exception) { store.failure("download_start") }
        render()
    }

    private fun canInstall() = Build.VERSION.SDK_INT < 26 || packageManager.canRequestPackageInstalls()

    private fun install() {
        if (ReaderUpdateManager.checking() || ReaderUpdateManager.validating() || ReaderUpdateService.isRunning()) return
        val snapshot = store.snapshot()
        val installedCode = ReaderApkVerifier.versionCode(ReaderApkVerifier.installed(this))
        if (ReaderUpdateUiPolicy.installableReady(snapshot.release, snapshot.ready, installedCode,
                Build.VERSION.SDK_INT, Build.SUPPORTED_ABIS) == null) { render(); return }
        if (!canInstall()) {
            localMessage = getString(R.string.reader_update_permission_denied)
            render()
            binding.allowInstall.requestFocus()
            return
        }
        verifying = true
        localMessage = getString(R.string.reader_update_verifying)
        render()
        ReaderUpdateManager.validateReady(this) { file ->
            if (isFinishing || isDestroyed) return@validateReady
            verifying = false
            if (file == null) { localMessage = null; render(); return@validateReady }
            try {
                val uri = FileProvider.getUriForFile(this, "$packageName.reader.update", file)
                installerResult.launch(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).apply {
                        clipData = ClipData.newRawUri(getString(R.string.reader_update_title), uri)
                    })
                localMessage = getString(R.string.reader_update_installer_opened)
            } catch (_: Exception) { localMessage = getString(R.string.reader_update_install_failed) }
            render()
        }
    }

    private fun render() {
        val value = store.snapshot()
        val installed = ReaderApkVerifier.installed(this)
        val installedCode = ReaderApkVerifier.versionCode(installed)
        binding.currentVersion.show(getString(R.string.reader_update_current_version, installed.versionName ?: "", installedCode))
        val release = value.release
        val active = ReaderUpdateService.isRunning()
        val checking = ReaderUpdateManager.checking()
        val validating = verifying || ReaderUpdateManager.validating()
        val status = localMessage ?: getString(when (value.state) {
            "checking" -> R.string.reader_update_checking
            "available" -> R.string.reader_update_available
            "current" -> R.string.reader_update_current
            "incompatible" -> R.string.reader_update_incompatible
            "queued", "downloading" -> R.string.reader_update_downloading
            "retrying" -> R.string.reader_update_retrying
            "verifying" -> R.string.reader_update_verifying
            "paused" -> if (value.error == "foreground_denied") R.string.reader_update_foreground_denied else R.string.reader_update_paused
            "cancelled" -> R.string.reader_update_cancelled
            "ready" -> R.string.reader_update_ready
            "error" -> if (value.error == "verify_failed") R.string.reader_update_verify_failed else R.string.reader_update_check_failed
            else -> R.string.reader_update_idle
        })
        binding.updateStatus.show(status)
        binding.releaseVersion.visibility = if (release == null) View.GONE else View.VISIBLE
        binding.releaseNotes.visibility = if (release == null) View.GONE else View.VISIBLE
        if (release != null) {
            binding.releaseVersion.show(getString(R.string.reader_update_release_version, release.versionName, release.versionCode, size(release.bytes)))
            binding.releaseNotes.show(getString(R.string.reader_update_changes) + "\n" +
                release.notes.ifBlank { getString(R.string.reader_update_notes_missing) })
            binding.downloadProgress.max = 100
            binding.downloadProgress.progress = (value.downloaded * 100 / release.bytes).toInt().coerceIn(0, 100)
            binding.progressText.show(getString(R.string.reader_update_progress, binding.downloadProgress.progress, size(value.downloaded), size(release.bytes)))
        }
        val ready = ReaderUpdateUiPolicy.installableReady(release, value.ready, installedCode,
            Build.VERSION.SDK_INT, Build.SUPPORTED_ABIS)
        val offer = ReaderUpdateUiPolicy.compatibleNew(release, installedCode, Build.VERSION.SDK_INT,
            Build.SUPPORTED_ABIS) && value.ready?.sha256 != release?.sha256
        val showProgress = release != null && (active || value.downloaded > 0)
        binding.downloadProgress.visibility = if (showProgress) View.VISIBLE else View.GONE
        binding.progressText.visibility = binding.downloadProgress.visibility
        binding.checkUpdate.visibility = if (!active && !offer && ready == null) View.VISIBLE else View.GONE
        binding.checkUpdate.isEnabled = !checking && !validating && pendingDownloadSha == null
        binding.downloadUpdate.visibility = if (!active && offer) View.VISIBLE else View.GONE
        binding.downloadUpdate.isEnabled = !checking && !validating && pendingDownloadSha == null
        binding.downloadUpdate.show(getString(R.string.reader_update_download))
        binding.cancelUpdate.visibility = View.VISIBLE
        binding.backgroundUpdate.visibility = if (active) View.VISIBLE else View.GONE
        if (ready != null) binding.installUpdate.show(getString(R.string.reader_update_install_version, ready.versionName))
        if (lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED))
            ReaderStartupUpdates.updatePageViewed(release?.versionCode)
        binding.installUpdate.visibility = if (ready == null || active) View.GONE else View.VISIBLE
        binding.installUpdate.isEnabled = !active && !checking && !validating
        binding.permissionInfo.visibility = if (!active && ready != null && !canInstall()) View.VISIBLE else View.GONE
        binding.allowInstall.visibility = binding.permissionInfo.visibility
        binding.allowInstall.isEnabled = !validating
    }

    private fun TextView.show(value: String) { if (text.toString() != value) text = value }
    private fun size(bytes: Long) = String.format(Locale.getDefault(), "%.1f MB", bytes / (1024.0 * 1024.0))

    companion object {
        private const val DOWNLOAD_SHA = "reader_update_download_sha"
        private const val PENDING_DOWNLOAD = "reader_update_pending_download"

        fun downloadIntent(context: Context, release: ReaderRelease) =
            Intent(context, ReaderUpdateActivity::class.java).putExtra(DOWNLOAD_SHA, release.sha256)
    }
}
