package io.legado.app.help.update

import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.coroutine.Coroutine
import kotlinx.coroutines.CoroutineScope

/** Legacy interface retained for source compatibility; all visible entries use ReaderUpdateActivity. */
object AppUpdateGitHub : AppUpdate.AppUpdateInterface {
    override fun check(scope: CoroutineScope): Coroutine<AppUpdate.UpdateInfo> = Coroutine.async(scope) {
        throw NoStackTraceException("请在「我的 → 检查更新」中检查阅读的新版本")
    }
}
