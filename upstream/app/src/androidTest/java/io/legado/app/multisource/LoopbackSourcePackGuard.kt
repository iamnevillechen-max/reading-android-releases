package io.legado.app.multisource

import android.content.Context
import io.legado.app.help.source.VerifiedSourceBootstrap

/** Fixture preferences only: deterministic loopback cases never seed real network sources. */
internal class LoopbackSourcePackGuard(context: Context) {
    private val preferences = context.getSharedPreferences(VerifiedSourceBootstrap.PREFS_NAME, Context.MODE_PRIVATE)
    private var captured = false
    var previousVersion: Int? = null
        private set

    fun mark() {
        check(!captured)
        previousVersion = if (preferences.contains(VerifiedSourceBootstrap.VERSION_KEY))
            preferences.getInt(VerifiedSourceBootstrap.VERSION_KEY, 0) else null
        captured = true
        check(preferences.edit().putInt(VerifiedSourceBootstrap.VERSION_KEY, VerifiedSourceBootstrap.PACK_VERSION).commit())
    }

    fun restore() {
        if (!captured) return
        restore(preferences, previousVersion)
        captured = false
    }

    companion object {
        fun restore(context: Context, previous: Int?) = restore(
            context.getSharedPreferences(VerifiedSourceBootstrap.PREFS_NAME, Context.MODE_PRIVATE), previous)

        private fun restore(preferences: android.content.SharedPreferences, previous: Int?) {
            check(preferences.edit().apply {
                if (previous == null) remove(VerifiedSourceBootstrap.VERSION_KEY)
                else putInt(VerifiedSourceBootstrap.VERSION_KEY, previous)
            }.commit())
        }
    }
}
