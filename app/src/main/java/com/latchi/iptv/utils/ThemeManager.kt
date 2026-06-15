package com.latchi.iptv.utils

import android.app.Activity
import android.content.Context
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.widget.FrameLayout
import com.latchi.iptv.R

/**
 * Theme Manager with image-based backgrounds.
 * Supports: default, dark, space, nature themes + custom image from gallery.
 */
object ThemeManager {
    private const val PREFS_NAME = "theme_prefs"
    private const val KEY_THEME = "theme_name"
    private const val KEY_CUSTOM_URI = "custom_theme_uri"

    fun apply(activity: Activity) {
        val themeName = getTheme(activity)
        val root = activity.findViewById<FrameLayout?>(android.R.id.content) ?: return
        val bg: Drawable? = when (themeName) {
            "dark" -> activity.getDrawable(R.drawable.bg_app)
            "space" -> activity.getDrawable(R.drawable.bg_app) // fallback
            "nature" -> activity.getDrawable(R.drawable.bg_app) // fallback
            "custom" -> {
                val uri = getCustomUri(activity)
                if (uri != null) {
                    try {
                        val inputStream = activity.contentResolver.openInputStream(android.net.Uri.parse(uri))
                        val bitmap = android.graphics.BitmapFactory.decodeStream(inputStream)
                        inputStream?.close()
                        BitmapDrawable(activity.resources, bitmap)
                    } catch (e: Exception) { null }
                } else null
            }
            else -> activity.getDrawable(R.drawable.bg_app)
        }
        root.background = bg ?: activity.getDrawable(R.drawable.bg_app)
    }

    fun setTheme(context: Context, name: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_THEME, name).apply()
    }

    fun getTheme(context: Context): String {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_THEME, "default") ?: "default"
    }

    fun setCustomTheme(context: Context, uri: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_THEME, "custom").putString(KEY_CUSTOM_URI, uri).apply()
    }

    fun getCustomUri(context: Context): String? {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_CUSTOM_URI, null)
    }

    fun getThemeNameRes(context: Context): Int = R.string.theme_default
}
