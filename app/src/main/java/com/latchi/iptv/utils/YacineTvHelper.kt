package com.latchi.iptv.utils

import android.util.Base64
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object YacineTvHelper {
    private const val API_URL = "http://ver3.yacinelive.com"
    private const val KEY = "c!xZj+N9&G@Ev@vw"

    data class YacineMatch(
        val id: Long,
        val startTime: Long,
        val endTime: Long,
        val champions: String,
        val commentary: String,
        val team1Name: String,
        val team1Logo: String,
        val team2Name: String,
        val team2Logo: String,
        val channelName: String
    )

    data class YacineChannel(val id: Long, val name: String, val logo: String)
    data class YacineStream(val name: String, val url: String, val userAgent: String = "", val referer: String = "")

    private fun decrypt(encoded: String, key: String): String {
        val decoded = String(Base64.decode(encoded, Base64.DEFAULT), Charsets.ISO_8859_1)
        val sb = StringBuilder(decoded.length)
        for (i in decoded.indices) sb.append((decoded[i].code xor key[i % key.length].code).toChar())
        return sb.toString()
    }

    private fun requestJson(path: String): JSONObject {
        val conn = (URL("$API_URL$path").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15000
            readTimeout = 15000
            setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 10)")
        }
        if (conn.responseCode != 200) throw Exception("HTTP ${conn.responseCode}")
        val ts = conn.getHeaderField("t") ?: (System.currentTimeMillis() / 1000).toString()
        return JSONObject(decrypt(conn.inputStream.bufferedReader().readText(), KEY + ts))
    }

    fun fetchMatches(): List<YacineMatch> {
        val json = requestJson("/api/events")
        val arr = json.optJSONArray("data") ?: return emptyList()
        val list = mutableListOf<YacineMatch>()
        for (i in 0 until arr.length()) {
            val it = arr.getJSONObject(i)
            val t1 = it.optJSONObject("team_1") ?: continue
            val t2 = it.optJSONObject("team_2") ?: continue
            list.add(
                YacineMatch(
                    it.optLong("id"), it.optLong("start_time"), it.optLong("end_time"),
                    it.optString("champions", ""), it.optString("commentary", ""),
                    t1.optString("name", "?"), t1.optString("logo", ""),
                    t2.optString("name", "?"), t2.optString("logo", ""),
                    it.optString("channel", "")
                )
            )
        }
        return list
    }

    fun fetchChannelsForCategory(categoryId: Long): List<YacineChannel> {
        val json = requestJson("/api/categories/$categoryId/channels")
        val arr = json.optJSONArray("data") ?: return emptyList()
        val out = mutableListOf<YacineChannel>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optLong("id", 0L)
            val name = o.optString("name", "")
            if (id > 0L && name.isNotBlank()) out.add(YacineChannel(id, name, o.optString("logo", "")))
        }
        return out
    }

    fun findYacineChannel(channelName: String): YacineChannel? {
        val q = normalize(channelName)
        if (q.isBlank()) return null
        // أهم فئات beIN بالجودات المختلفة حسب API الرسمي من yacinetv-api.
        val candidateCategories = listOf(4L, 5L, 6L, 7L, 89L, 9L)
        for (catId in candidateCategories) {
            val channels = runCatching { fetchChannelsForCategory(catId) }.getOrDefault(emptyList())
            channels.firstOrNull { normalize(it.name) == q }?.let { return it }
            channels.firstOrNull { sameSportsChannel(q, normalize(it.name)) }?.let { return it }
        }
        return null
    }

    fun fetchChannelStreams(channelId: Long): List<YacineStream> {
        val json = requestJson("/api/channel/$channelId")
        val arr = json.optJSONArray("data") ?: return emptyList()
        val out = mutableListOf<YacineStream>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val url = o.optString("url", "")
            if (url.isBlank()) continue
            val headers = o.optJSONObject("headers")
            out.add(
                YacineStream(
                    name = o.optString("name", "Yacine"),
                    url = url,
                    userAgent = o.optString("user_agent", headers?.optString("User-Agent", "") ?: ""),
                    referer = o.optString("referer", "")
                )
            )
        }
        return out
    }

    fun resolveStreamForChannelName(channelName: String): YacineStream? {
        val ch = findYacineChannel(channelName) ?: return null
        return fetchChannelStreams(ch.id).firstOrNull()
    }

    private fun sameSportsChannel(a: String, b: String): Boolean {
        fun nums(x: String) = Regex("\\d+").findAll(x).map { it.value }.toList()
        val an = nums(a); val bn = nums(b)
        val numberOk = an.isEmpty() || bn.isEmpty() || an.any { it in bn }
        val brandOk = listOf("bein", "ssc", "alkass", "kass", "ad", "abu").any { a.contains(it) && b.contains(it) }
        val maxOk = !a.contains("max") || b.contains("max")
        return numberOk && brandOk && maxOk
    }

    private fun normalize(value: String): String = DigitNormalizer.normalizeDigits(value)
        .lowercase()
        .replace("بي إن", "bein")
        .replace("بي ان", "bein")
        .replace("be in", "bein")
        .replace("sports", "sport")
        .replace(Regex("[^a-z0-9]+"), " ")
        .trim()
        .replace(Regex("\\s+"), " ")

    fun getMatchStatus(m: YacineMatch): String {
        val now = System.currentTimeMillis() / 1000
        return when {
            now < m.startTime -> "قادمة"
            now in m.startTime..m.endTime -> "🔴 مباشر"
            else -> "انتهت"
        }
    }

    fun formatMatchTime(m: YacineMatch): String {
        val now = System.currentTimeMillis() / 1000
        val raw = when {
            now < m.startTime -> {
                val sdf = java.text.SimpleDateFormat("HH:mm", java.util.Locale.US)
                sdf.format(java.util.Date(m.startTime * 1000))
            }
            now in m.startTime..m.endTime -> {
                val e = ((now - m.startTime) / 60).toInt()
                if (e <= 45) "${e}'" else if (e <= 60) "HT" else "${e - 15}'"
            }
            else -> "FT"
        }
        return DigitNormalizer.normalizeDigits(raw)
    }
}
