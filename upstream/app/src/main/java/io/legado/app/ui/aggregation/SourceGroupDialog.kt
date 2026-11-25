package io.legado.app.ui.aggregation

import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.constant.AppLog
import io.legado.app.lib.dialogs.alert
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Open the native, recycled choices only when the user requests a source group. */
internal fun Fragment.showSourceGroups(groups: Flow<List<String>>, onSelected: (String) -> Unit) =
    viewLifecycleOwner.lifecycleScope.launch {
        val names = try {
            groups.first()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            AppLog.put("来源分组载入失败", error)
            if (isResumed) toastOnUi(R.string.load_error_retry)
            return@launch
        }
        if (!isResumed) return@launch
        alert(R.string.group) {
            if (names.isEmpty()) setMessage(R.string.empty)
            else items(names) { _, index -> onSelected(names[index]) }
            cancelButton()
        }
    }
