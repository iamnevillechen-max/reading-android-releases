package io.legado.app.ui.cloud

import android.os.Bundle
import android.view.View
import android.view.WindowManager
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.base.BaseActivity
import io.legado.app.databinding.ActivityReaderCloudBinding
import io.legado.app.model.cloud.CloudCatalogue
import io.legado.app.model.cloud.CloudReader
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Native labelled controls; access codes never become saved view state or status text. */
class ReaderCloudActivity : BaseActivity<ActivityReaderCloudBinding>(fullScreen = false) {
    override val binding by viewBinding(ActivityReaderCloudBinding::inflate)
    private var work: Job? = null
    override fun onActivityCreated(savedInstanceState: Bundle?) {
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        binding.cloudUrl.setText(CloudReader.endpoint(this))
        if (CloudReader.hasCredential(this)) binding.cloudToken.setHint(R.string.reader_cloud_token_saved)
        binding.cloudStatus.setText(when {
            CloudReader.isEnabled(this) -> R.string.reader_cloud_active
            CloudReader.isConfigured(this) -> R.string.reader_cloud_local
            else -> R.string.reader_cloud_idle
        })
        binding.cloudConnect.setOnClickListener { run(migrate = false) }
        binding.cloudMigrate.setOnClickListener { run(migrate = true) }
        binding.cloudCancel.setOnClickListener { work?.cancel() }
    }

    private fun run(migrate: Boolean) {
        if (work?.isActive == true) return
        work = lifecycleScope.launch {
            busy(true)
            try {
                val url = binding.cloudUrl.text.toString().trim()
                val token = binding.cloudToken.text.toString().trim()
                if (token.isNotEmpty()) {
                    CloudReader.configure(this@ReaderCloudActivity, url, token)
                    CloudCatalogue.clear()
                    binding.cloudToken.text.clear()
                    binding.cloudToken.setHint(R.string.reader_cloud_token_saved)
                } else require(CloudReader.hasCredential(this@ReaderCloudActivity) && url == CloudReader.endpoint(this@ReaderCloudActivity)) {
                    getString(R.string.reader_cloud_bad_token)
                }
                binding.cloudStatus.setText(R.string.reader_cloud_connecting)
                CloudReader.request("/v1/status")
                binding.cloudStatus.setText(R.string.reader_cloud_connected)
                if (migrate) {
                    val report = withContext(Dispatchers.IO) {
                        CloudReader.migrate(this@ReaderCloudActivity) { progress -> runOnUiThread {
                            if (!isDestroyed) binding.cloudStatus.text = getString(R.string.reader_cloud_progress,
                                progress.kind, progress.processed, progress.total, progress.converted, progress.archived)
                        } }
                    }
                    CloudCatalogue.clear()
                    binding.cloudStatus.text = getString(R.string.reader_cloud_done, report.uploaded, report.converted, report.archiveCleaned, report.preserved)
                }
            } catch (cancelled: CancellationException) {
                binding.cloudStatus.setText(R.string.reader_cloud_stopped)
                throw cancelled
            } catch (error: Exception) {
                binding.cloudStatus.text = getString(R.string.reader_cloud_failed, error.localizedMessage ?: "连接失败")
            } finally { busy(false) }
        }
    }

    private fun busy(value: Boolean) {
        binding.cloudUrl.isEnabled = !value
        binding.cloudToken.isEnabled = !value
        binding.cloudConnect.isEnabled = !value
        binding.cloudMigrate.isEnabled = !value
        binding.cloudCancel.visibility = if (value) View.VISIBLE else View.GONE
    }
}
