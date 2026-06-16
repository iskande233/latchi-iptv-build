package com.latchi.iptv.utils

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.JsonReader
import android.util.Log
import com.latchi.iptv.model.Channel
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

object BeinChannelResolver {
    private const val TAG = "BeinChannelResolver"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .addInterceptor(AntiBlockInterceptor)
        .build()

    private val BEIN_KEYWORDS = listOf(
        "bein", "be in", "beinsport", "bein sports", "bein sport",
        "bein max", "max", "xtra", "extra", "4k", "uhd", "news",
        "بي ان", "بي إن", "بي ان سبورت", "بي إن سبورت", "بين", "سبورت", "alkass", "ssc", "الكاس", "sports", "sport", "كأس"
    )

    fun resolve(
        context: Context,
        profile: IptvProfile,
        onResolved: (List<Channel>) -> Unit
    ) {
        val appContext = context.applicationContext
        thread(name = "LatchiBeinResolver") {
            try {
                // 1. Try Cache First
                val cached = ChannelCache.load(appContext, profile.id).filter { it.contentType == "live" }
                val cachedBein = filterBein(cached)

                // If cache has any beIN channels, return them immediately
                if (cachedBein.isNotEmpty()) {
                    Log.d(TAG, "Resolved ${cachedBein.size} beIN channels from Cache")
                    onMain { onResolved(cachedBein) }
                    return@thread
                }

                // 2. Cache is empty -> Fetch from Server directly
                Log.d(TAG, "Cache empty. Fetching live channels from server...")
                val fetched = fetchFromServer(profile.m3uUrl)
                val finalBein = filterBein(fetched.ifEmpty { cached })
                
                Log.d(TAG, "Resolved ${finalBein.size} beIN channels from Server")
                onMain { onResolved(finalBein) }
            } catch (e: Exception) {
                Log.e(TAG, "Resolver Error: ${e.message}")
                onMain { onResolved(emptyList()) }
            }
        }
    }

    private fun filterBein(channels: List<Channel>): List<Channel> {
        return channels.filter { ch ->
            val normName = DigitNormalizer.normalizeDigits(ch.name).lowercase()
            val normCat = DigitNormalizer.normalizeDigits(ch.category).lowercase()
            val text = "$normName $normCat"
            
            BEIN_KEYWORDS.any { keyword -> text.contains(keyword.lowercase()) }
        }.distinctBy { it.streamUrl }
    }

    private fun fetchFromServer(sourceUrl: String): List<Channel> {
        val clean = sourceUrl.trim().replace("&amp;", "&")
        if (clean.contains("get.php", ignoreCase = true)) {
            val xtream = parseXtreamLive(clean)
            if (xtream.isNotEmpty()) return xtream
        }
        return parseM3uLive(clean)
    }

    private fun parseXtreamLive(sourceUrl: String): List<Channel> {
        val list = mutableListOf<Channel>()
        try {
            val uri = URI(sourceUrl)
            val server = "${uri.scheme}://${uri.host}${if (uri.port != -1) ":${uri.port}" else ""}"
            val query = uri.rawQuery ?: return emptyList()
            val params = query.split("&").mapNotNull {
                val p = it.split("=", limit = 2)
                if (p.size == 2) URLDecoder.decode(p[0], "UTF-8") to URLDecoder.decode(p[1], "UTF-8") else null
            }.toMap()
            val username = params["username"] ?: return emptyList()
            val password = params["password"] ?: return emptyList()

            // Fetch live categories map
            val catUrl = "$server/player_api.php?username=${URLEncoder.encode(username, "UTF-8")}&password=${URLEncoder.encode(password, "UTF-8")}&action=get_live_categories"
            val catReq = Request.Builder().url(catUrl).header("User-Agent", "Mozilla/5.0").get().build()
            val catMap = HashMap<String, String>()
            try {
                client.newCall(catReq).execute().use { res ->
                    if (res.isSuccessful) {
                        res.body?.byteStream()?.let { stream ->
                            JsonReader(stream.bufferedReader()).use { r ->
                                r.beginArray()
                                while (r.hasNext()) {
                                    var id = ""
                                    var name = ""
                                    r.beginObject()
                                    while (r.hasNext()) {
                                        when (r.nextName()) {
                                            "category_id" -> id = readJsonStringSafe(r)
                                            "category_name" -> name = readJsonStringSafe(r)
                                            else -> r.skipValue()
                                        }
                                    }
                                    r.endObject()
                                    if (id.isNotBlank() && name.isNotBlank()) catMap[id] = name
                                }
                                r.endArray()
                            }
                        }
                    }
                }
            } catch (_: Exception) {}

            // Fetch live streams
            val liveUrl = "$server/player_api.php?username=${URLEncoder.encode(username, "UTF-8")}&password=${URLEncoder.encode(password, "UTF-8")}&action=get_live_streams"
            val liveReq = Request.Builder().url(liveUrl).header("User-Agent", "Mozilla/5.0").get().build()
            client.newCall(liveReq).execute().use { res ->
                if (!res.isSuccessful) return emptyList()
                val stream = res.body?.byteStream() ?: return emptyList()
                JsonReader(stream.bufferedReader()).use { r ->
                    r.beginArray()
                    while (r.hasNext()) {
                        var id = ""
                        var name = ""
                        var icon = ""
                        var catId = ""

                        r.beginObject()
                        while (r.hasNext()) {
                            when (r.nextName()) {
                                "stream_id" -> id = readJsonStringSafe(r)
                                "name" -> name = readJsonStringSafe(r).ifBlank { "Live Stream" }
                                "stream_icon" -> icon = readJsonStringSafe(r)
                                "category_id" -> catId = readJsonStringSafe(r)
                                else -> r.skipValue()
                            }
                        }
                        r.endObject()

                        if (id.isNotBlank() && name.isNotBlank()) {
                            val catName = catMap[catId] ?: "Live TV"
                            val streamUrl = "$server/live/$username/$password/$id.ts"
                            list.add(Channel(name, icon, streamUrl, catName, "live"))
                        }
                    }
                    r.endArray()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Parse Xtream Live Error: ${e.message}")
        }
        return list
    }

    private fun parseM3uLive(sourceUrl: String): List<Channel> {
        val list = mutableListOf<Channel>()
        try {
            val request = Request.Builder().url(sourceUrl).header("User-Agent", "Mozilla/5.0").get().build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return emptyList()
                val stream = response.body?.byteStream() ?: return emptyList()

                var name: String? = null
                var logoUrl = ""
                var category = "Other"

                stream.bufferedReader(Charsets.UTF_8).useLines { lines ->
                    for (raw in lines) {
                        val line = raw.trim()
                        when {
                            line.startsWith("#EXTINF") -> {
                                name = line.substringAfterLast(",", "Unknown").replace("[", "").replace("]", "").trim().ifEmpty { "Unknown" }
                                logoUrl = Regex("tvg-logo=\"([^\"]*)\"").find(line)?.groupValues?.getOrNull(1) ?: ""
                                category = Regex("group-title=\"([^\"]*)\"").find(line)?.groupValues?.getOrNull(1)?.trim()?.ifBlank { "Other" } ?: "Other"
                            }
                            line.isNotBlank() && !line.startsWith("#") && (line.startsWith("http://") || line.startsWith("https://")) -> {
                                if (!name.isNullOrEmpty()) {
                                    // Keep only live streams
                                    if (!line.contains("/movie/") && !line.contains("/vod/") && !line.contains("/series/")) {
                                        list.add(Channel(name ?: "Unknown", logoUrl, line, category, "live"))
                                    }
                                }
                                name = null
                                logoUrl = ""
                                category = "Other"
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Parse M3U Live Error: ${e.message}")
        }
        return list
    }

    private fun readJsonStringSafe(r: JsonReader): String {
        return try {
            r.nextString()
        } catch (_: Exception) {
            try { r.nextInt().toString() } catch (_: Exception) { r.skipValue(); "" }
        }
    }

    private fun onMain(block: () -> Unit) {
        Handler(Looper.getMainLooper()).post(block)
    }
}
