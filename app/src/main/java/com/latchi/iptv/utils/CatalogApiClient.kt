package com.latchi.iptv.utils

import com.latchi.iptv.model.Channel
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

data class RemoteCatalogMeta(
    val success: Boolean,
    val revision: Long,
    val hash: String,
    val count: Int,
    val notModified: Boolean,
    val url: String,
    val message: String = ""
)

object CatalogApiClient {
    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private fun baseUrl(): String = ActivationConfig.ACTIVATION_API_URL

    fun fetchMeta(type: String, revision: Long = 0L, hash: String = ""): RemoteCatalogMeta {
        val url = buildString {
            append(baseUrl())
            append("?action=get_catalog_meta")
            append("&type=").append(enc(type))
            if (revision > 0) append("&revision=").append(revision)
            if (hash.isNotBlank()) append("&hash=").append(enc(hash))
        }
        val json = getJson(url)
        return RemoteCatalogMeta(
            success = json.optBoolean("success", false),
            revision = json.optLong("revision", json.optLong("server_revision", 0L)),
            hash = json.optString("hash", ""),
            count = json.optInt("count", 0),
            notModified = json.optBoolean("not_modified", false),
            url = json.optString("url", ""),
            message = json.optString("message", "")
        )
    }

    fun fetchCategories(type: String): List<String> {
        val url = "${baseUrl()}?action=get_categories&type=${enc(type)}"
        val json = getJson(url)
        if (!json.optBoolean("success", false)) return emptyList()
        val arr = json.optJSONArray("categories") ?: JSONArray()
        val out = mutableListOf<String>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val name = o.optString("name", o.optString("id", ""))
            if (name.isNotBlank()) out.add(name)
        }
        return out
    }

    fun fetchItemsByCategory(type: String, category: String, pageSize: Int = 300): List<Channel> {
        val out = mutableListOf<Channel>()
        var page = 1
        while (true) {
            val url = buildString {
                append(baseUrl())
                append("?action=get_items_by_category")
                append("&type=").append(enc(type))
                append("&category=").append(enc(category))
                append("&page=").append(page)
                append("&page_size=").append(pageSize)
            }
            val json = getJson(url)
            if (!json.optBoolean("success", false)) break
            val items = parseItems(json.optJSONArray("items") ?: JSONArray(), type)
            if (items.isEmpty()) break
            out.addAll(items)
            if (!json.optBoolean("has_more", false)) break
            page++
        }
        return out
    }

    fun fetchItemsPaged(type: String, pageSize: Int = 300): List<Channel> {
        val out = mutableListOf<Channel>()
        var page = 1
        while (true) {
            val url = buildString {
                append(baseUrl())
                append("?action=get_items_page")
                append("&type=").append(enc(type))
                append("&page=").append(page)
                append("&page_size=").append(pageSize)
            }
            val json = getJson(url)
            if (!json.optBoolean("success", false)) break
            val items = parseItems(json.optJSONArray("items") ?: JSONArray(), type)
            if (items.isEmpty()) break
            out.addAll(items)
            if (!json.optBoolean("has_more", false)) break
            page++
        }
        return out
    }

    private fun parseItems(arr: JSONArray, fallbackType: String): List<Channel> {
        val list = mutableListOf<Channel>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val name = o.optString("name").trim()
            val streamUrl = o.optString("streamUrl", o.optString("url", o.optString("stream_url", ""))).trim()
            if (name.isBlank() || streamUrl.isBlank()) continue
            val logo = o.optString("logoUrl", o.optString("logo", "")).trim()
            val category = o.optString("category", o.optString("group", "Other")).trim().ifBlank { "Other" }
            val type = when (fallbackType) {
                "movies" -> "movie"
                else -> o.optString("contentType", fallbackType)
            }.trim().ifBlank {
                when (fallbackType) {
                    "movies" -> "movie"
                    else -> fallbackType
                }
            }
            list.add(Channel(name, logo, streamUrl, category, type))
        }
        return list
    }

    private fun getJson(url: String): JSONObject {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 10)")
            .get()
            .build()
        return client.newCall(request).execute().use { res ->
            val body = res.body?.string().orEmpty()
            if (!res.isSuccessful) return JSONObject().put("success", false).put("message", "HTTP ${res.code}")
            try { JSONObject(body) } catch (_: Exception) { JSONObject().put("success", false).put("message", "Invalid JSON") }
        }
    }

    private fun enc(v: String): String = URLEncoder.encode(v, "UTF-8")
}
