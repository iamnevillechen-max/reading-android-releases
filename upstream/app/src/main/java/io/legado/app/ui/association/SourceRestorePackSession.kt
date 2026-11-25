package io.legado.app.ui.association

import android.content.Context
import androidx.lifecycle.MutableLiveData
import io.legado.app.model.local.SourceRestorePack
import io.legado.app.model.local.SourceRestorePackCodec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/** One summary and a progress counter, never a 20k-row full-rule preview. */
class SourceRestorePackSession(private val context: Context, private val scope: CoroutineScope) {
    sealed class State {
        data class Preparing(val checked: Int = 0) : State()
        data class Ready(val summary: SourceRestorePackCodec.Summary) : State()
        data class Importing(val processed: Int, val total: Int) : State()
        data class Done(val result: SourceRestorePack.Result) : State()
        data class Failed(val message: String) : State()
    }
    val state = MutableLiveData<State>()
    var active = false; private set
    private var prepared: SourceRestorePack.Prepared? = null
    private var job: Job? = null
    fun prepare(value: String): Boolean {
        if (!SourceRestorePack.recognizes(value)) return false
        active = true; state.value = State.Preparing()
        job = scope.launch(Dispatchers.IO) {
            var owned: SourceRestorePack.Prepared? = null
            try {
                val pack = SourceRestorePack.prepare(context, value) { state.postValue(State.Preparing(it)) }
                owned = pack
                ensureActive(); prepared = pack; state.postValue(State.Ready(pack.summary))
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) { state.postValue(State.Failed("還原包下載或校驗失敗，未寫入來源。請重新貼上完整連結。"))
            } finally { if (prepared !== owned) owned?.close() }
        }
        return true
    }
    fun importPrepared() {
        if (job?.isActive == true) return
        val pack = prepared ?: return
        state.value = State.Importing(0, pack.summary.total)
        job = scope.launch(Dispatchers.IO) {
            try {
                val result = SourceRestorePack.restore(pack) { state.postValue(State.Importing(it, pack.summary.total)) }
                state.postValue(State.Done(result))
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) { state.postValue(State.Failed("導入未完成；已完成批次會保留，書架和進度不變。可重新導入同一包繼續還原。"))
            } finally { pack.close(); prepared = null }
        }
    }
    fun close() { job?.cancel(); prepared?.close(); prepared = null }
}
