package io.legado.app.help.update.reader

import android.app.Activity
import android.app.Application
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import io.legado.app.R
import io.legado.app.ui.main.MainActivity
import io.legado.app.service.BaseReadAloudService
import io.legado.app.ui.update.reader.ReaderUpdateActivity
import java.lang.ref.WeakReference
import java.util.Collections
import java.util.IdentityHashMap

/** Uses the same native Activity lifecycle callbacks as LifecycleHelp; no foreground service. */
object ReaderStartupUpdates : Application.ActivityLifecycleCallbacks {
    private val main = Handler(Looper.getMainLooper())
    private val started = Collections.newSetFromMap(IdentityHashMap<Activity, Boolean>())
    private var registered = false
    private var ready = false
    private var resumed = WeakReference<Activity>(null)
    private var policy = ReaderStartupPolicy()
    private var prompt: AlertDialog? = null
    private var promptOwner = WeakReference<Activity>(null)
    internal var clock: () -> Long = SystemClock::elapsedRealtime
    private val pendingCheck = Runnable { checkOrPrompt() }

    fun register(application: Application) {
        ready = false
        main.removeCallbacks(pendingCheck)
        if (!registered) { registered = true; application.registerActivityLifecycleCallbacks(this) }
    }

    /** Called only after the existing privacy and local-password startup flow has completed. */
    fun ready() { ready = true; checkOrPrompt() }

    fun updatePageViewed(code: Long?) {
        if (code != null) policy.prompted(code)
        if (!ReaderUpdateManager.checking()) policy.completed()
    }

    private fun checkOrPrompt() {
        try { checkOrPromptInternal() }
        catch (_: Exception) { /* Startup checks must remain silent and leave reading available. */ }
    }

    private fun checkOrPromptInternal() {
        main.removeCallbacks(pendingCheck)
        val activity = resumed.get() as? AppCompatActivity ?: return
        if (!ready || activity.isFinishing || activity.isDestroyed ||
            !activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) ||
            activity.supportFragmentManager.isStateSaved) return
        if (activity is ReaderUpdateActivity) return
        if (ReaderUpdateService.isRunning() || ReaderUpdateManager.validating()) return
        if (ReaderUpdateManager.checking()) return
        if (policy.needsCheck()) {
            val delay = policy.delayUntilCheck(clock())
            if (delay > 0) { main.postDelayed(pendingCheck, delay); return }
            if (ReaderUpdateManager.check(activity) {
                policy.completed()
                checkOrPrompt()
            }) policy.attempted(clock())
            return
        }
        if (!ReaderUpdateUiPolicy.mayPrompt(activity is MainActivity, BaseReadAloudService.isRun)) return
        val snapshot = ReaderUpdateStore(activity).snapshot()
        val installed = ReaderApkVerifier.versionCode(ReaderApkVerifier.installed(activity))
        val release = snapshot.release
        if (snapshot.state != "available" ||
            !ReaderUpdateUiPolicy.compatibleNew(release, installed, Build.VERSION.SDK_INT, Build.SUPPORTED_ABIS) ||
            !policy.mayPrompt(release!!.versionCode) || prompt?.isShowing == true) return
        policy.prompted(release.versionCode)
        promptOwner = WeakReference(activity)
        prompt = AlertDialog.Builder(activity).setTitle(R.string.reader_update_startup_title)
            .setMessage(ReaderUpdateUiPolicy.description(
                activity.getString(R.string.reader_update_startup_message, release.versionName),
                activity.getString(R.string.reader_update_changes), release.notes,
                activity.getString(R.string.reader_update_notes_missing)))
            .setPositiveButton(R.string.reader_update_download) { _, _ ->
                activity.startActivity(ReaderUpdateActivity.downloadIntent(activity, release))
            }.setNegativeButton(R.string.reader_update_cancel, null)
            .create().also { dialog ->
                dialog.setOnDismissListener { if (prompt === dialog) prompt = null }
                dialog.show()
            }
    }

    override fun onActivityStarted(activity: Activity) {
        if (started.add(activity)) policy.started()
    }
    override fun onActivityStopped(activity: Activity) {
        if (started.remove(activity)) policy.stopped(activity.isChangingConfigurations)
    }
    override fun onActivityResumed(activity: Activity) {
        resumed = WeakReference(activity)
        main.post(pendingCheck)
    }
    override fun onActivityPaused(activity: Activity) {
        if (resumed.get() === activity) { resumed.clear(); main.removeCallbacks(pendingCheck) }
        if (promptOwner.get() === activity) { prompt?.dismiss(); prompt = null; promptOwner.clear() }
    }
    override fun onActivityDestroyed(activity: Activity) {
        if (promptOwner.get() === activity) { prompt?.dismiss(); prompt = null; promptOwner.clear() }
    }
    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

    internal fun resetForTests() {
        main.removeCallbacks(pendingCheck)
        prompt?.dismiss(); prompt = null; promptOwner.clear(); resumed.clear()
        started.clear(); ready = false; policy = ReaderStartupPolicy()
        clock = SystemClock::elapsedRealtime
    }
}
