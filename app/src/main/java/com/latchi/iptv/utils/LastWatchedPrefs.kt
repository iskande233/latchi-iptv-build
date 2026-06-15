package com.latchi.iptv.utils

import android.content.Context
import com.latchi.iptv.model.Channel

object LastWatchedPrefs {
    private const val PREFS_NAME = "last_watched"

    fun save(context: Context, profileId: String, channel: Channel) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString("name_$profileId", channel.name)
            .putString("logo_$profileId", channel.logoUrl)
            .putString("url_$profileId", channel.streamUrl)
            .putString("cat_$profileId", channel.category)
            .putString("type_$profileId", channel.contentType)
            .apply()
    }

    fun load(context: Context, profileId: String): Channel? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val url = prefs.getString("url_$profileId", "") ?: ""
        if (url.isBlank()) return null
        return Channel(
            name = prefs.getString("name_$profileId", "Last watched") ?: "Last watched",
            logoUrl = prefs.getString("logo_$profileId", "") ?: "",
            streamUrl = url,
            category = prefs.getString("cat_$profileId", "Other") ?: "Other",
            contentType = prefs.getString("type_$profileId", "live") ?: "live"
        )
    }
}
