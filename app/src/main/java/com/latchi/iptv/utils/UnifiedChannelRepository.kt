package com.latchi.iptv.utils

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.latchi.iptv.model.Channel
import java.util.concurrent.TimeUnit
import java.net.URLEncoder
import okhttp3.Request
import okhttp3.OkHttpClient
import android.util.JsonReader

/**
 * مصدر واحد للقنوات لكل الواجهات.
 *
 * Phone و TV و beIN يستعملو نفس الداتا ونفس الجلب ونفس الفلترة.
 * الاختلاف يبقى في الواجهة فقط: List/Grid للهاتف و Focus/Overlay للتلفاز.
 */
object UnifiedChannelRepository {
    data class Result(
        val channels: List<Channel>,
        val source: String,
        val message: String = ""
    )

    private val main = Handler(Looper.getMainLooper())
    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(35, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private data class XCat(val id: String, val name: String)

    fun loadLive(
        context: Context,
        profile: IptvProfile,
        onResult: (Result) -> Unit
    ) {
        val appContext = context.applicationContext
        ChannelRefreshHelper.ensureFreshChannels(appContext, profile, onlyLive = true) { result ->
            val live = result.channels.filter { it.contentType == "live" }.ifEmpty { result.channels }
            val source = when {
                result.refreshedFromServer -> "server"
                result.usedCacheFallback -> "cache_fallback"
                ChannelCache.isTypeCacheFresh(appContext, profile, "live") -> "cache"
                else -> "room_or_cache"
            }
            onMain { onResult(Result(live, source, result.message)) }
        }
    }

    fun loadBein(
        context: Context,
        profile: IptvProfile,
        onResult: (Result) -> Unit
    ) {
        val appContext = context.applicationContext
        val creds = XtreamHelper.parseCreds(profile.m3uUrl)
        if (creds != null) {
            Thread {
                val smart = runCatching { fetchBeinFromXtreamCategories(appContext, profile.id, creds) }.getOrDefault(emptyList())
                if (smart.isNotEmpty()) {
                    onMain { onResult(Result(smart, "smart_bein_lazy", "")) }
                } else {
                    BeinChannelResolver.resolve(appContext, profile) { bein ->
                        onMain { onResult(Result(bein, if (bein.isNotEmpty()) "smart_bein_fallback" else "empty", "")) }
                    }
                }
            }.start()
            return
        }
        BeinChannelResolver.resolve(context, profile) { bein ->
            onMain { onResult(Result(bein, if (bein.isNotEmpty()) "smart_bein" else "empty", "")) }
        }
    }

    private fun fetchBeinFromXtreamCategories(context: Context, profileId: String, creds: XtreamHelper.Creds): List<Channel> {
        val cats = fetchLiveCategories(creds).filter { isBeinCategory(it.name) }
        if (cats.isEmpty()) return emptyList()
        val out = mutableListOf<Channel>()
        cats.take(24).forEach { cat ->
            out.addAll(fetchLiveStreamsForCategory(creds, cat))
        }
        return smartBeinFilter(context, profileId, out.ifEmpty { emptyList() })
    }

    private fun fetchLiveCategories(creds: XtreamHelper.Creds): List<XCat> {
        val url = "${creds.server}/player_api.php?username=${enc(creds.username)}&password=${enc(creds.password)}&action=get_live_categories"
        val req = Request.Builder().url(url).header("User-Agent", "Mozilla/5.0 (Linux; Android 10)").get().build()
        client.newCall(req).execute().use { res ->
            if (!res.isSuccessful) return emptyList()
            val stream = res.body?.byteStream() ?: return emptyList()
            val out = mutableListOf<XCat>()
            JsonReader(stream.bufferedReader(Charsets.UTF_8)).use { r ->
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
                    if (id.isNotBlank() && name.isNotBlank()) out.add(XCat(id, name))
                }
                r.endArray()
            }
            return out
        }
    }

    private fun fetchLiveStreamsForCategory(creds: XtreamHelper.Creds, cat: XCat): List<Channel> {
        val url = "${creds.server}/player_api.php?username=${enc(creds.username)}&password=${enc(creds.password)}&action=get_live_streams&category_id=${enc(cat.id)}"
        val req = Request.Builder().url(url).header("User-Agent", "Mozilla/5.0 (Linux; Android 10)").get().build()
        val out = mutableListOf<Channel>()
        client.newCall(req).execute().use { res ->
            if (!res.isSuccessful) return emptyList()
            val stream = res.body?.byteStream() ?: return emptyList()
            JsonReader(stream.bufferedReader(Charsets.UTF_8)).use { r ->
                r.beginArray()
                while (r.hasNext()) {
                    var id = ""
                    var name = ""
                    var icon = ""
                    r.beginObject()
                    while (r.hasNext()) {
                        when (r.nextName()) {
                            "stream_id" -> id = readJsonStringSafe(r)
                            "name" -> name = readJsonStringSafe(r).ifBlank { "Live Stream" }
                            "stream_icon" -> icon = readJsonStringSafe(r)
                            else -> r.skipValue()
                        }
                    }
                    r.endObject()
                    if (id.isNotBlank() && name.isNotBlank()) {
                        out.add(Channel(name, icon, "${creds.server}/live/${creds.username}/${creds.password}/$id.ts", cat.name, "live"))
                    }
                }
                r.endArray()
            }
        }
        return out
    }

    private fun isBeinCategory(name: String): Boolean {
        val l = DigitNormalizer.normalizeDigits(name).lowercase()
        return listOf("bein", "be in", "beinsport", "بي ان", "بي إن", "alwan", "الوان", "world cup", "كأس العالم", "كاس العالم").any { l.contains(it.lowercase()) }
    }

    private fun readJsonStringSafe(r: JsonReader): String {
        return try {
            r.nextString()
        } catch (_: Exception) {
            try { r.nextInt().toString() } catch (_: Exception) { r.skipValue(); "" }
        }
    }

    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")

    fun sameCategory(a: String, b: String): Boolean {
        fun norm(v: String): String = DigitNormalizer.normalizeDigits(v)
            .lowercase()
            .replace("بي إن", "بي ان")
            .replace(Regex("[^a-z0-9\\u0600-\\u06FF]+"), "")
        return a.equals(b, ignoreCase = true) || norm(a) == norm(b)
    }

    fun smartBeinFilter(context: Context, profileId: String, channels: List<Channel>): List<Channel> {
        val remoteConfig = RemoteViewConfigPrefs.getFilterConfig(context.applicationContext, profileId)
        val beinTokens = remoteConfig.beinKeywords.ifEmpty { listOf("bein", "be in", "بي ان", "بي إن") }
        val beinMaxTokens = remoteConfig.beinMaxKeywords.ifEmpty { listOf("max", "max1", "max2", "bein max") }
        val alwanTokens = remoteConfig.alwanKeywords.ifEmpty { listOf("alwan", "الوان") }

        fun normalized(ch: Channel): String = DigitNormalizer.normalizeDigits("${ch.name} ${ch.category}").lowercase()
        fun hasAny(text: String, tokens: List<String>): Boolean = tokens.any { token -> text.contains(token.lowercase()) }
        fun isBein(text: String): Boolean = hasAny(text, beinTokens)
        fun isBeinMax(text: String): Boolean = isBein(text) && hasAny(text, beinMaxTokens)
        fun isAlwan(text: String): Boolean = hasAny(text, alwanTokens)
        fun isWorldCup(text: String): Boolean =
            text.contains("world cup") || text.contains("كاس العالم") || text.contains("كأس العالم") ||
                text.contains("fifa") || text.contains("wc ")
        fun firstNumber(text: String): Int = Regex("\\d+").find(text)?.value?.toIntOrNull() ?: 999

        return channels
            .filter { ch ->
                val text = normalized(ch)
                isBein(text) || isAlwan(text) || isWorldCup(text)
            }
            .distinctBy { it.streamUrl.ifBlank { it.name } }
            .sortedWith(
                compareBy<Channel> { ch ->
                    val text = normalized(ch)
                    when {
                        isWorldCup(text) -> 0
                        isBeinMax(text) -> 1
                        isBein(text) -> 2
                        isAlwan(text) -> 3
                        else -> 4
                    }
                }.thenBy { ch -> firstNumber(normalized(ch)) }
                    .thenBy { ch -> normalized(ch) }
            )
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }
}
