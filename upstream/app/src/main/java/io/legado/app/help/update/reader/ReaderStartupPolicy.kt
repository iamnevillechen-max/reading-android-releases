package io.legado.app.help.update.reader

/** Process-local foreground sessions. Manual checks never pass through this throttle. */
internal class ReaderStartupPolicy(private val minimumGapMillis: Long = 60_000) {
    private var startedActivities = 0
    private var foreground = false
    private var session = 0L
    private var checkedSession = -1L
    private var lastAttempt: Long? = null
    private var promptedSession = -1L
    private var promptedCode = -1L

    fun started() {
        if (startedActivities++ == 0 && !foreground) { foreground = true; session++ }
    }
    fun stopped(changingConfigurations: Boolean) {
        startedActivities = (startedActivities - 1).coerceAtLeast(0)
        if (startedActivities == 0 && !changingConfigurations) foreground = false
    }
    fun needsCheck() = foreground && checkedSession != session
    fun delayUntilCheck(now: Long): Long {
        val previous = lastAttempt ?: return 0
        return if (now < previous) 0 else (minimumGapMillis - (now - previous)).coerceAtLeast(0)
    }
    fun attempted(now: Long) { lastAttempt = now; checkedSession = session }
    fun completed() { if (foreground) checkedSession = session }
    fun mayPrompt(code: Long) = foreground && (promptedSession != session || promptedCode != code)
    fun prompted(code: Long) { promptedSession = session; promptedCode = code }
}

internal object ReaderUpdateUiPolicy {
    fun mayPrompt(onMain: Boolean, readingAloud: Boolean) = onMain && !readingAloud

    fun compatibleNew(release: ReaderRelease?, installed: Long, sdk: Int, abis: Array<String>) =
        release != null && sdk >= 24 && release.newerThan(installed) && release.supports(sdk, abis)

    /** A delayed permission result must not download a different release than the user selected. */
    fun requestedDownload(latest: ReaderRelease?, selectedSha: String?, installed: Long,
        sdk: Int, abis: Array<String>): ReaderRelease? = latest?.takeIf {
        selectedSha == it.sha256 && compatibleNew(it, installed, sdk, abis)
    }

    fun description(version: String, heading: String, notes: String, missingNotes: String) =
        "$version\n\n$heading\n${notes.trim().ifEmpty { missingNotes }}"

    fun installableReady(latest: ReaderRelease?, ready: ReaderRelease?, installed: Long,
        sdk: Int, abis: Array<String>): ReaderRelease? = ready?.takeIf {
        compatibleNew(it, installed, sdk, abis) &&
            !(compatibleNew(latest, installed, sdk, abis) && latest!!.versionCode > it.versionCode)
    }
}
