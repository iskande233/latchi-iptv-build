package com.latchi.iptv.utils

import android.util.Base64
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.util.concurrent.TimeUnit

/**
 * Lightweight Xtream Codes helper used for Series episodes and EPG (now/next).
 * It parses server/username/password from a standard get.php playlist URL.
 */
object XtreamHelper {

    data class Creds(val server: String, val username: String, val password: String)

    data class Episode(
        val id: String,
        val title: String,
        val ext: String,
        val season: Int,
        val episodeNum: Int = 0
    )

    data class EpgItem(val title: String, val start: String, val end: String)

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .build()

    fun parseCreds(playlistUrl: String): Creds? {
        return try {
            val clean = playlistUrl.trim().replace("&amp;", "&")
            val uri = URI(clean)
            val server = "${uri.scheme}://${uri.host}${if (uri.port != -1) ":${uri.port}" else ""}"
            val query = uri.query ?: return null
            val params = query.split("&").mapNotNull {
                val p = it.split("=", limit = 2)
                if (p.size == 2) p[0] to p[1] else null
            }.toMap()
            val u = params["username"] ?: return null
            val p = params["password"] ?: return null
            Creds(server, u, p)
        } catch (e: Exception) {
            null
        }
    }

    private fun download(url: String): String {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 10) IPTVPlayer/1.0")
            .build()
        client.newCall(req).execute().use { res ->
            if (!res.isSuccessful) throw Exception("HTTP ${res.code}")
            return res.body?.string() ?: throw Exception("Empty")
        }
    }

    /** Extract the numeric series id from a "series://<id>" marker url. */
    fun seriesIdFromMarker(streamUrl: String): String? {
        if (!streamUrl.startsWith("series://")) return null
        return streamUrl.removePrefix("series://").ifBlank { null }
    }

    /** Fetch all episodes for a given series id (flattened across seasons). */
    fun fetchEpisodes(creds: Creds, seriesId: String): List<Episode> {
        val url = "${creds.server}/player_api.php?username=${creds.username}&password=${creds.password}&action=get_series_info&series_id=$seriesId"
        val root = JSONObject(download(url))
        val episodesObj = root.optJSONObject("episodes") ?: return emptyList()
        val out = mutableListOf<Episode>()
        val seasonKeys = episodesObj.keys()
        while (seasonKeys.hasNext()) {
            val seasonKey = seasonKeys.next()
            val season = seasonKey.toIntOrNull() ?: 0
            val arr: JSONArray = episodesObj.optJSONArray(seasonKey) ?: continue
            for (i in 0 until arr.length()) {
                val e = arr.getJSONObject(i)
                val id = e.optString("id")
                val epNum = e.optString("episode_num", "0").toIntOrNull() ?: 0
                val title = e.optString("title", "Episode $epNum")
                val ext = e.optString("container_extension", "mp4").ifBlank { "mp4" }
                if (id.isNotBlank()) out.add(Episode(id, title, ext, season, epNum))
            }
        }
        return out.sortedWith(compareBy({ it.season }, { it.episodeNum }, { it.title }))
    }

    /** Build a playable URL for a series episode. */
    fun episodeUrl(creds: Creds, episode: Episode): String =
        "${creds.server}/series/${creds.username}/${creds.password}/${episode.id}.${episode.ext}"

    /** Extract stream id from a live url like .../live/user/pass/12345.ts */
    fun liveStreamId(streamUrl: String): String? {
        return try {
            val last = streamUrl.substringAfterLast("/")
            val id = last.substringBefore(".")
            if (id.all { it.isDigit() } && id.isNotBlank()) id else null
        } catch (e: Exception) {
            null
        }
    }

    /** Fetch now/next EPG for a live stream (returns up to 2 items). */
    fun fetchShortEpg(creds: Creds, streamId: String, limit: Int = 2): List<EpgItem> {
        val url = "${creds.server}/player_api.php?username=${creds.username}&password=${creds.password}&action=get_short_epg&stream_id=$streamId&limit=$limit"
        val root = JSONObject(download(url))
        val arr = root.optJSONArray("epg_listings") ?: return emptyList()
        val out = mutableListOf<EpgItem>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val title = decodeMaybeBase64(o.optString("title"))
            val start = o.optString("start")
            val end = o.optString("end")
            out.add(EpgItem(title, start, end))
        }
        return out
    }

    private fun decodeMaybeBase64(value: String): String {
        if (value.isBlank()) return ""
        return try {
            val decoded = Base64.decode(value, Base64.DEFAULT)
            val text = String(decoded, Charsets.UTF_8)
            if (text.isNotBlank()) text else value
        } catch (e: Exception) {
            value
        }
    }
}
