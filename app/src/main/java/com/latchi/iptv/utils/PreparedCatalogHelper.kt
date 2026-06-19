package com.latchi.iptv.utils

import com.latchi.iptv.model.Channel
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object PreparedCatalogHelper {
    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    fun fetch(url: String, fallbackType: String): List<Channel> {
        if (url.isBlank()) return emptyList()
        return try {
            val req = Request.Builder()
                .url(url.trim().replace("&amp;", "&"))
                .header("Accept", "application/json")
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 10)")
                .get()
                .build()
            val body = client.newCall(req).execute().use { res ->
                if (!res.isSuccessful) return emptyList()
                res.body?.string().orEmpty()
            }
            parseBody(body, fallbackType)
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun parseBody(body: String, fallbackType: String): List<Channel> {
        val trimmed = body.trim()
        if (trimmed.isBlank()) return emptyList()
        return when {
            trimmed.startsWith("[") -> parseArray(JSONArray(trimmed), fallbackType)
            trimmed.startsWith("{") -> {
                val root = JSONObject(trimmed)
                val arr = root.optJSONArray("channels")
                    ?: root.optJSONArray("items")
                    ?: root.optJSONArray("data")
                    ?: JSONArray()
                parseArray(arr, fallbackType)
            }
            else -> emptyList()
        }
    }

    private fun parseArray(arr: JSONArray, fallbackType: String): List<Channel> {
        val out = mutableListOf<Channel>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val name = o.optString("name").ifBlank { continue }
            val logo = o.optString("logoUrl", o.optString("logo", o.optString("stream_icon", "")))
            val stream = o.optString("streamUrl", o.optString("url", o.optString("stream_url", "")))
            if (stream.isBlank()) continue
            val category = o.optString("category", o.optString("group", "Other"))
            val type = o.optString("contentType", o.optString("type", fallbackType)).ifBlank { fallbackType }
            out.add(Channel(name, logo, stream, category, type))
        }
        return out
    }
}
