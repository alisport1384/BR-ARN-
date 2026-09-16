package com.bigrocket.service

import android.content.Context

/**
 * Persistent user trust scores for the two physical paths.
 *
 * Scores are user configuration, not session cache. They survive a full BigRocket
 * disconnect and are reapplied at the next connection.
 */
object NetworkPreferenceStore {
    private const val PREFS = "bigrocket_network_preferences"
    private const val WIFI_SCORE_KEY = "wifi_score"
    private const val CELLULAR_SCORE_KEY = "cellular_score"

    private const val DEFAULT_SCORE = 1

    fun wifiScore(context: Context): Int =
        prefs(context).getInt(WIFI_SCORE_KEY, DEFAULT_SCORE).coerceIn(1, 5)

    fun cellularScore(context: Context): Int =
        prefs(context).getInt(CELLULAR_SCORE_KEY, DEFAULT_SCORE).coerceIn(1, 5)

    fun setWifiScore(context: Context, score: Int) {
        prefs(context).edit().putInt(WIFI_SCORE_KEY, score.coerceIn(1, 5)).apply()
    }

    fun setCellularScore(context: Context, score: Int) {
        prefs(context).edit().putInt(CELLULAR_SCORE_KEY, score.coerceIn(1, 5)).apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
