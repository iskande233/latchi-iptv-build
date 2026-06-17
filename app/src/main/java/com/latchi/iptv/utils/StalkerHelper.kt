package com.latchi.iptv.utils

import com.latchi.iptv.model.Channel
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * MAC/Stalker Portal Helper — نسخة مُصلحة شاملة
 *
 * الإصلاحات:
 * 1. getAllChannels يدعم الآن JSONObject{data:[]} + JSONArray مباشرة + pagination
 * 2. getGenres يدعم JSONObject{} + JSONArray[]
 * 3. handshake يجرب مسارات متعددة (/portal.php + /stalker_portal/server/load.php)
 * 4. createPlayableLink يتعامل مع ffrt + ffmpeg + روابط مباشرة
 * 5. timezone مضبوطة على الجزائر
 */
object StalkerHelper {
    data class MacSource(val portal: String, val mac: String)

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .retryOnConnectionFailure(true)
        .build()

    fun isMacSource(sourceUrl: String): Boolean =
        sourceUrl.trim().startsWith("mac://", ignoreCase = true)

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

    // ─── Portal URL helpers ───────────────────────────────────────────────────

    private fun basePortal(portal: String): String {
        var p = portal.trimEnd('/')
        // إذا انتهى بـ /c نحذفه
        if (p.endsWith("/c", ignoreCase = true)) p = p.dropLast(2)
        // إذا انتهى بـ /stalker_portal نتركه كما هو
        return p
    }

    private fun portalPhp(source: MacSource): String =
        basePortal(source.portal) + "/portal.php"

    // بعض السيرفرات تستخدم /stalker_portal/server/load.php
    private fun loadPhp(source: MacSource): String =
        basePortal(source.portal) + "/stalker_portal/server/load.php"

    // ─── Headers ─────────────────────────────────────────────────────────────

    private fun commonRequest(source: MacSource, url: String, token: String? = null): Request.Builder {
        val cookie = "mac=${source.mac}; stb_lang=ar; timezone=Africa/Algiers"
        val auth = token?.let { "Bearer $it" }
        return Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (QtEmbedded; U; Linux; MAG 254; ar)")
            .header("Referer", source.portal.trimEnd('/') + "/c/")
            .header("Cookie", cookie)
            .header("X-User-Agent", "Model: MAG254; Link: Ethernet")
            .header("Accept", "application/json, text/javascript, */*; q=0.01")
            .header("X-Requested-With", "XMLHttpRequest")
            .apply { if (auth != null) header("Authorization", auth) }
    }

    // ─── Handshake ───────────────────────────────────────────────────────────

    private fun handshake(source: MacSource): String? {
        // نجرب /portal.php أولاً ثم /stalker_portal/server/load.php
        val candidates = listOf(
            portalPhp(source) + "?type=stb&action=handshake&token=&JsHttpRequest=1-xml",
            loadPhp(source) + "?type=stb&action=handshake&token=&JsHttpRequest=1-xml"
        )
        for (url in candidates) {
            try {
                val body = client.newCall(commonRequest(source, url).get().build())
                    .execute().use { res ->
                        if (!res.isSuccessful) return@use ""
                        res.body?.string().orEmpty()
                    }
                if (body.isBlank()) continue
                val token = JSONObject(body)
                    .optJSONObject("js")
                    ?.optString("token")
                    ?.takeIf { it.isNotBlank() }
                if (token != null) return token
            } catch (_: Exception) { continue }
        }
        return null
    }

    // ─── Profile ─────────────────────────────────────────────────────────────

    private fun getProfile(source: MacSource, token: String) {
        val candidates = listOf(
            portalPhp(source) + "?type=stb&action=get_profile&JsHttpRequest=1-xml",
            loadPhp(source) + "?type=stb&action=get_profile&JsHttpRequest=1-xml"
        )
        for (url in candidates) {
            try {
                client.newCall(commonRequest(source, url, token).get().build())
                    .execute().close()
                break
            } catch (_: Exception) { continue }
        }
    }

    // ─── Genres ──────────────────────────────────────────────────────────────

    private fun getGenres(source: MacSource, token: String): Map<String, String> {
        val candidates = listOf(
            portalPhp(source) + "?type=itv&action=get_genres&JsHttpRequest=1-xml",
            loadPhp(source) + "?type=itv&action=get_genres&JsHttpRequest=1-xml"
        )
        for (url in candidates) {
            try {
                val body = client.newCall(commonRequest(source, url, token).get().build())
                    .execute().use { res ->
                        if (!res.isSuccessful) return@use ""
                        res.body?.string().orEmpty()
                    }
                if (body.isBlank()) continue
                val out = parseGenres(body)
                if (out.isNotEmpty()) return out
            } catch (_: Exception) { continue }
        }
        return emptyMap()
    }

    /**
     * يدعم صيغتين:
     * {"js": [...]}          ← JSONArray مباشر
     * {"js": {"0":{}, "1":{}}} ← JSONObject بمفاتيح رقمية
     */
    private fun parseGenres(body: String): Map<String, String> {
        val out = mutableMapOf<String, String>()
        try {
            val root = JSONObject(body)
            val jsVal = root.opt("js") ?: return out

            val arr: JSONArray? = when (jsVal) {
                is JSONArray -> jsVal
                is JSONObject -> {
                    // حوّل JSONObject إلى JSONArray
                    val tmp = JSONArray()
                    for (key in jsVal.keys()) {
                        val v = jsVal.optJSONObject(key)
                        if (v != null) tmp.put(v)
                    }
                    tmp
                }
                else -> null
            }

            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val id = o.optString("id", o.optString("genre_id", ""))
                    val title = o.optString("title", o.optString("name", ""))
                    if (id.isNotBlank() && title.isNotBlank()) out[id] = title
                }
            }
        } catch (_: Exception) {}
        return out
    }

    // ─── Get All Channels (الإصلاح الرئيسي) ──────────────────────────────────

    private fun getAllChannels(
        source: MacSource,
        token: String,
        genres: Map<String, String>
    ): List<Channel> {
        val list = mutableListOf<Channel>()

        // نجرب pagination من 0 إلى ما تنتهي القنوات
        // بعض السيرفرات تستخدم p=0، بعضها p=1
        for (startPage in listOf(0, 1)) {
            val pageResult = fetchChannelsWithPagination(source, token, genres, startPage)
            if (pageResult.isNotEmpty()) {
                list.addAll(pageResult)
                break
            }
        }

        // إذا فارغة نجرب الـ endpoint البديل
        if (list.isEmpty()) {
            val fallback = fetchChannelsFallback(source, token, genres)
            list.addAll(fallback)
        }

        return list
    }

    private fun fetchChannelsWithPagination(
        source: MacSource,
        token: String,
        genres: Map<String, String>,
        startPage: Int
    ): List<Channel> {
        val list = mutableListOf<Channel>()
        var page = startPage

        while (true) {
            val url = portalPhp(source) +
                    "?type=itv&action=get_all_channels&genre=*&force_ch_link_check=&fav=0&sortby=number&hd=0&p=$page&JsHttpRequest=1-xml"
            try {
                val body = client.newCall(commonRequest(source, url, token).get().build())
                    .execute().use { res ->
                        if (!res.isSuccessful) return list
                        res.body?.string().orEmpty()
                    }

                if (body.isBlank()) break

                val parsed = parseChannelsBody(body, source, token, genres)
                if (parsed.isEmpty()) break

                list.addAll(parsed)

                // إذا أقل من 100 قناة في الصفحة = آخر صفحة
                if (parsed.size < 100) break
                page++

                // حماية من loop لا نهائي
                if (page > 50) break

            } catch (_: Exception) { break }
        }
        return list
    }

    // endpoint بديل: get_ordered_list
    private fun fetchChannelsFallback(
        source: MacSource,
        token: String,
        genres: Map<String, String>
    ): List<Channel> {
        return try {
            val url = portalPhp(source) +
                    "?type=itv&action=get_ordered_list&genre=*&force_ch_link_check=&fav=0&sortby=number&p=0&JsHttpRequest=1-xml"
            val body = client.newCall(commonRequest(source, url, token).get().build())
                .execute().use { res ->
                    if (!res.isSuccessful) return emptyList()
                    res.body?.string().orEmpty()
                }
            parseChannelsBody(body, source, token, genres)
        } catch (_: Exception) { emptyList() }
    }

    /**
     * يدعم 3 صيغ لـ JSON:
     *
     * صيغة 1 — JSONArray مباشر:
     * {"js": [{...}, {...}]}
     *
     * صيغة 2 — JSONObject بـ data:
     * {"js": {"total_items": 100, "max_page_items": 14, "data": [{...}]}}
     *
     * صيغة 3 — JSONObject بمفاتيح رقمية:
     * {"js": {"0": {...}, "1": {...}}}
     */
    private fun parseChannelsBody(
        body: String,
        source: MacSource,
        token: String,
        genres: Map<String, String>
    ): List<Channel> {
        val list = mutableListOf<Channel>()
        if (body.isBlank()) return list

        try {
            val root = JSONObject(body)
            val jsVal = root.opt("js") ?: return list

            val arr: JSONArray = when (jsVal) {
                is JSONArray -> jsVal

                is JSONObject -> {
                    // صيغة 2: يحتوي على "data"
                    val dataArr = jsVal.optJSONArray("data")
                    if (dataArr != null) {
                        dataArr
                    } else {
                        // صيغة 3: مفاتيح رقمية
                        val tmp = JSONArray()
                        for (key in jsVal.keys()) {
                            val v = jsVal.optJSONObject(key)
                            if (v != null) tmp.put(v)
                        }
                        tmp
                    }
                }

                else -> return list
            }

            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue

                val name = o.optString("name", o.optString("title", "")).trim()
                if (name.isBlank()) continue

                val logo = o.optString("logo", o.optString("icon", ""))
                val genreId = o.optString("tv_genre_id", o.optString("genre_id", ""))
                val category = genres[genreId]?.takeIf { it.isNotBlank() } ?: "MAC / Stalker"

                // cmd يمكن أن يكون "ffrt http://..." أو رابط مباشر أو stream id فقط
                val cmd = o.optString("cmd", o.optString("stream_url", o.optString("url", "")))

                val streamUrl = createPlayableLink(source, token, cmd)
                if (streamUrl.isNotBlank()) {
                    list.add(Channel(name, logo, streamUrl, category, "live"))
                }
            }
        } catch (_: Exception) {}

        return list
    }

    // ─── Create Playable Link ─────────────────────────────────────────────────

    private fun createPlayableLink(source: MacSource, token: String, cmd: String): String {
        val cleanCmd = cmd.trim()
        if (cleanCmd.isBlank()) return ""

        // إذا الرابط مباشر بدون حاجة لـ create_link
        val directUrl = extractDirectUrl(cleanCmd)
        if (directUrl != null) return directUrl

        // نستدعي create_link للحصول على الرابط الحقيقي
        return try {
            val encoded = URLEncoder.encode(cleanCmd, "UTF-8")
            val candidates = listOf(
                portalPhp(source) + "?type=itv&action=create_link&cmd=$encoded&JsHttpRequest=1-xml",
                loadPhp(source) + "?type=itv&action=create_link&cmd=$encoded&JsHttpRequest=1-xml"
            )

            for (url in candidates) {
                try {
                    val body = client.newCall(commonRequest(source, url, token).get().build())
                        .execute().use { res ->
                            if (!res.isSuccessful) return@use ""
                            res.body?.string().orEmpty()
                        }

                    if (body.isBlank()) continue

                    val link = JSONObject(body)
                        .optJSONObject("js")
                        ?.optString("cmd")
                        .orEmpty()
                        .trim()

                    val resolved = extractDirectUrl(link)
                    if (resolved != null) return resolved

                } catch (_: Exception) { continue }
            }

            // fallback: إرجاع cmd الأصلي إذا كان يبدو كرابط
            extractDirectUrl(cleanCmd) ?: ""

        } catch (_: Exception) {
            extractDirectUrl(cleanCmd) ?: ""
        }
    }

    /**
     * يستخرج الرابط القابل للتشغيل من أي صيغة:
     * "ffrt http://..." → "http://..."
     * "ffmpeg http://..." → "http://..."
     * "http://..." → "http://..."
     * غير ذلك → null
     */
    private fun extractDirectUrl(raw: String): String? {
        val s = raw.trim()
        return when {
            s.startsWith("ffrt ", ignoreCase = true) -> {
                val after = s.removePrefix("ffrt ").removePrefix("ffrt\t").trim()
                if (after.startsWith("http://") || after.startsWith("https://")) after else null
            }
            s.startsWith("ffmpeg ", ignoreCase = true) -> {
                val after = s.removePrefix("ffmpeg ").trim()
                if (after.startsWith("http://") || after.startsWith("https://")) after else null
            }
            s.startsWith("http://") || s.startsWith("https://") || s.startsWith("rtsp://") || s.startsWith("rtp://") -> s
            else -> null
        }
    }
}
