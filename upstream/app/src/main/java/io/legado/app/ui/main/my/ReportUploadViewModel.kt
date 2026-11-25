package io.legado.app.ui.main.my

import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.legado.app.help.ReaderDiagnostics
import io.legado.app.help.ReaderReportUpload
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

class ReportUploadViewModel : ViewModel() {
    enum class Status { READY, SENDING, SENT, FAILED }
    val status = MutableLiveData(Status.READY)
    val lastReceipt get() = ReaderDiagnostics.preferences.getString("receipt", null)?.substringBefore('-')

    fun send() {
        if (status.value == Status.SENDING) return
        status.value = Status.SENDING
        viewModelScope.launch {
            try {
                ReaderReportUpload.send()
                status.value = Status.SENT
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                status.value = Status.FAILED
            }
        }
    }
}
