package com.latchi.iptv.utils

import android.content.Context
import com.latchi.iptv.model.Channel
import org.json.JSONObject

object EmbeddedChannelsLoader {
    @Volatile private var memoryCache: List<Channel>? = null

    fun load(context: Context): List<Channel> {
        memoryCache?.let { return it }
        return try {
            val json = context.assets.open("channels.json").bufferedReader(Charsets.UTF_8).use { it.readText() }
            val root = JSONObject(json)
            val arr = root.optJSONArray("channels") ?: return emptyList()
            val list = ArrayList<Channel>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val name = o.optString("name", "").trim()
                val url = o.optString("url", "").trim().replace("&amp;", "&")
                if (name.isBlank() || url.isBlank()) continue
                list.add(
                    Channel(
                        name = name,
                        logoUrl = o.optString("logo", ""),
                        streamUrl = url,
                        category = o.optString("category", "Other").ifBlank { "Other" },
                        contentType = o.optString("type", "live").ifBlank { "live" }
                    )
                )
            }
            val result = list.distinctBy { it.streamUrl }
            memoryCache = result
            result
        } catch (_: Exception) {
            emptyList()
        }
    }
}
