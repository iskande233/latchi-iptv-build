package com.latchi.iptv.utils

import android.content.Context

object FavoritesPrefs {
    private const val PREFS_NAME = "iptv_favorites"

    fun isFavorite(context: Context, profileId: String, streamUrl: String): Boolean {
        return getFavorites(context, profileId).contains(streamUrl)
    }

    fun toggle(context: Context, profileId: String, streamUrl: String) {
        val set = getFavorites(context, profileId).toMutableSet()
        if (set.contains(streamUrl)) set.remove(streamUrl) else set.add(streamUrl)
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putStringSet("fav_$profileId", set).apply()
    }

    fun getFavorites(context: Context, profileId: String): Set<String> {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getStringSet("fav_$profileId", emptySet()) ?: emptySet()
    }

    fun clearProfile(context: Context, profileId: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().remove("fav_$profileId").apply()
    }
}
