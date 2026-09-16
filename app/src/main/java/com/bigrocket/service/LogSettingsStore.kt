package com.bigrocket.service

import android.content.Context

/** Persistent on/off switch for [AppLogger]. Default is off - logging only ever runs after the
 *  user explicitly turns it on from the menu (never on by default, never turned on silently). */
object LogSettingsStore {
    private const val PREFS = "bigrocket_log_preferences"
    private const val ENABLED_KEY = "logging_enabled"

    fun isEnabled(context: Context): Boolean =
        prefs(context).getBoolean(ENABLED_KEY, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(ENABLED_KEY, enabled).apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
