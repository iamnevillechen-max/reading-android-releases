package io.legado.app.ui.association

import io.legado.app.databinding.DialogRecyclerViewBinding
import io.legado.app.utils.gone
import io.legado.app.utils.visible

internal object SourceRestorePackDialog {
    fun render(binding: DialogRecyclerViewBinding, state: SourceRestorePackSession.State) {
        binding.recyclerView.gone()
        binding.tvFooterLeft.gone()
        binding.toolBar.menu.clear()
        binding.tvMsg.visible()
        binding.tvOk.isEnabled = state is SourceRestorePackSession.State.Ready || state is SourceRestorePackSession.State.Done
        when (state) {
            is SourceRestorePackSession.State.Preparing -> {
                binding.rotateLoading.visible(); binding.tvMsg.text = if (state.checked == 0) "下載並校驗完整原來源包…" else "已校驗 ${state.checked} 條原來源…"
            }
            is SourceRestorePackSession.State.Ready -> {
                binding.rotateLoading.gone(); binding.tvOk.text = "全部導入"
                binding.tvMsg.text = "書源 ${state.summary.books} 條，訂閱 ${state.summary.rss} 條，共 ${state.summary.total} 條。\n保留本機已有完整規則，還原舊代理及缺少的來源；書架、閱讀進度和歷史不變。"
            }
            is SourceRestorePackSession.State.Importing -> {
                binding.rotateLoading.visible(); binding.tvMsg.text = "正在導入 ${state.processed} / ${state.total}…"
            }
            is SourceRestorePackSession.State.Done -> {
                binding.rotateLoading.gone(); binding.tvOk.text = "完成"
                binding.tvMsg.text = "已處理 ${state.result.processed} 條來源。\n還原 ${state.result.restored} 條，保留 ${state.result.keptLocal} 條本機完整規則。"
            }
            is SourceRestorePackSession.State.Failed -> { binding.rotateLoading.gone(); binding.tvMsg.text = state.message }
        }
    }
}
