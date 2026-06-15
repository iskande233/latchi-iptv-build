package com.latchi.iptv.utils

import android.content.Context

object LanguagePrefs {
    private const val PREFS_NAME = "language_prefs"
    private const val KEY_LANG = "language"

    fun setLanguage(context: Context, lang: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_LANG, lang).apply()
    }

    fun getLanguage(context: Context): String {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_LANG, "ar") ?: "ar"
    }

    fun getLanguageName(context: Context): String {
        return when (getLanguage(context)) {
            "fr" -> "Français"
            "en" -> "English"
            else -> "العربية"
        }
    }
}
