package com.latchi.iptv.utils

import android.content.Context

object PlayerPrefs {
    private const val PREFS_NAME = "player_prefs"
    private const val KEY_MODE = "player_mode"

    const val MODE_AUTO = "auto"
    const val MODE_HLS = "hls"
    const val MODE_PROGRESSIVE = "progressive"

    fun getMode(context: Context): String {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_MODE, MODE_AUTO) ?: MODE_AUTO
    }

    fun setMode(context: Context, mode: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_MODE, mode).apply()
    }

    fun getModeLabel(context: Context): String {
        return when (getMode(context)) {
            MODE_HLS -> "HLS"
            MODE_PROGRESSIVE -> "Progressive"
            else -> "Auto"
        }
    }
}
