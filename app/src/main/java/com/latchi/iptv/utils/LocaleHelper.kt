package com.latchi.iptv.utils

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

/**
 * Applies the saved language to any Context. Call from attachBaseContext in every Activity.
 */
object LocaleHelper {

    fun wrap(context: Context): Context {
        val lang = LanguagePrefs.getLanguage(context)
        return setLocale(context, lang)
    }

    fun setLocale(context: Context, language: String): Context {
        val locale = Locale(language)
        Locale.setDefault(locale)
        val config = Configuration(context.resources.configuration)
        config.setLocale(locale)
        config.setLayoutDirection(locale)
        return context.createConfigurationContext(config)
    }
}
