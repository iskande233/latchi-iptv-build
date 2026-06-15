package com.latchi.iptv.utils

import com.latchi.iptv.model.Channel
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * دعم أولي لـ MAC/Stalker Portal.
 * البروتوكولات تختلف من سيرفر لسيرفر، لذلك هذا helper يحاول الطريقة الشائعة:
 * handshake -> profile -> genres -> get_all_channels.
 */
object StalkerHelper {
    data class MacSource(val portal: String, val mac: String)

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .retryOnConnectionFailure(true)
        .build()

    fun isMacSource(sourceUrl: String): Boolean = sourceUrl.trim().startsWith("mac://", ignoreCase = true)

    fun parse(sourceUrl: String): MacSource? {
        return try {
            val uri = URI(sourceUrl)
            val params = (uri.rawQuery ?: "").split("&").mapNotNull {
                val p = it.split("=", limit = 2)
                if (p.size == 2) URLDecoder.decode(p[0], "UTF-8") to URLDecoder.decode(p[1], "UTF-8") else null
            }.toMap()
            val portal = params["portal"]?.trim().orEmpty().trimEnd('/')
            val mac = params["mac"]?.trim().orEmpty().uppercase()
            if (portal.isBlank() || mac.isBlank()) null else MacSource(portal, mac)
        } catch (_: Exception) { null }
    }

    fun fetchChannels(sourceUrl: String): List<Channel> {
        val source = parse(sourceUrl) ?: return emptyList()
        val token = handshake(source) ?: return emptyList()
        runCatching { getProfile(source, token) }
        val genres = runCatching { getGenres(source, token) }.getOrDefault(emptyMap())
        return getAllChannels(source, token, genres)
    }

    private fun basePortal(portal: String): String {
        val p = portal.trimEnd('/')
        return if (p.endsWith("/c", ignoreCase = true)) p.dropLast(2) else p
    }

    private fun portalPhp(source: MacSource): String = basePortal(source.portal) + "/portal.php"

    private fun commonRequest(source: MacSource, url: String, token: String? = null): Request.Builder {
        val cookie = "mac=${source.mac}; stb_lang=en; timezone=Europe/Paris"
        val auth = token?.let { "Bearer $it" }
        return Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (QtEmbedded; U; Linux; MAG 254; en)")
            .header("Referer", source.portal.trimEnd('/') + "/")
            .header("Cookie", cookie)
            .header("X-User-Agent", "Model: MAG254; Link: Ethernet")
            .apply { if (auth != null) header("Authorization", auth) }
    }

    private fun handshake(source: MacSource): String? {
        val url = portalPhp(source) + "?type=stb&action=handshake&token=&JsHttpRequest=1-xml"
        val body = client.newCall(commonRequest(source, url).get().build()).execute().use { res ->
            if (!res.isSuccessful) return null
            res.body?.string().orEmpty()
        }
        val js = JSONObject(body)
        return js.optJSONObject("js")?.optString("token")?.takeIf { it.isNotBlank() }
    }

    private fun getProfile(source: MacSource, token: String) {
        val url = portalPhp(source) + "?type=stb&action=get_profile&JsHttpRequest=1-xml"
        client.newCall(commonRequest(source, url, token).get().build()).execute().close()
    }

    private fun getGenres(source: MacSource, token: String): Map<String, String> {
        val url = portalPhp(source) + "?type=itv&action=get_genres&JsHttpRequest=1-xml"
        val body = client.newCall(commonRequest(source, url, token).get().build()).execute().use { res ->
            if (!res.isSuccessful) return emptyMap()
            res.body?.string().orEmpty()
        }
        val out = mutableMapOf<String, String>()
        val arr = JSONObject(body).optJSONArray("js") ?: return emptyMap()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id")
            val title = o.optString("title", o.optString("name", "Other"))
            if (id.isNotBlank()) out[id] = title
        }
        return out
    }

    private fun getAllChannels(source: MacSource, token: String, genres: Map<String, String>): List<Channel> {
        val url = portalPhp(source) + "?type=itv&action=get_all_channels&JsHttpRequest=1-xml"
        val body = client.newCall(commonRequest(source, url, token).get().build()).execute().use { res ->
            if (!res.isSuccessful) return emptyList()
            res.body?.string().orEmpty()
        }
        val js = JSONObject(body).optJSONArray("js") ?: return emptyList()
        val list = mutableListOf<Channel>()
        for (i in 0 until js.length()) {
            val o = js.optJSONObject(i) ?: continue
            val name = o.optString("name", o.optString("title", "Channel ${i + 1}"))
            val logo = o.optString("logo", "")
            val genreId = o.optString("tv_genre_id", o.optString("genre_id", ""))
            val category = genres[genreId] ?: "MAC / Stalker"
            val cmd = o.optString("cmd", o.optString("stream_url", ""))
            val streamUrl = createPlayableLink(source, token, cmd)
            if (streamUrl.isNotBlank()) list.add(Channel(name, logo, streamUrl, category, "live"))
        }
        return list
    }

    private fun createPlayableLink(source: MacSource, token: String, cmd: String): String {
        val cleanCmd = cmd.trim()
        if (cleanCmd.isBlank()) return ""
        return try {
            val encoded = URLEncoder.encode(cleanCmd, "UTF-8")
            val url = portalPhp(source) + "?type=itv&action=create_link&cmd=$encoded&JsHttpRequest=1-xml"
            val body = client.newCall(commonRequest(source, url, token).get().build()).execute().use { res ->
                if (!res.isSuccessful) return cleanCmd.takeIf { it.startsWith("http") }.orEmpty()
                res.body?.string().orEmpty()
            }
            val link = JSONObject(body).optJSONObject("js")?.optString("cmd").orEmpty().trim()
            when {
                link.startsWith("ffmpeg ", ignoreCase = true) -> link.removePrefix("ffmpeg ").trim()
                link.startsWith("http://") || link.startsWith("https://") -> link
                cleanCmd.startsWith("http://") || cleanCmd.startsWith("https://") -> cleanCmd
                else -> link
            }
        } catch (_: Exception) {
            cleanCmd.takeIf { it.startsWith("http") }.orEmpty()
        }
    }
}
