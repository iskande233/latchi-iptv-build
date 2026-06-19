package com.latchi.iptv.utils

import android.content.Context
import android.util.JsonReader
import androidx.lifecycle.Observer
import com.latchi.iptv.model.Channel
import com.latchi.iptv.provider.ChannelsProvider
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * يضمن أن شاشات التلفاز لا تعتمد على كاش قديم بعد تحديث السيرفر.
 * وعند onlyLive=true نستعمل مسار سريع جداً يحمّل القنوات الحية فقط
 * بدل جلب Live + VOD + Series كما يحصل في الهاتف.
 */
data class ChannelRefreshResult(
    val channels: List<Channel>,
    val refreshedFromServer: Boolean,
    val usedCacheFallback: Boolean,
    val message: String = ""
)

object ChannelRefreshHelper {
    private const val FETCH_TIMEOUT_MS = 45_000L

    private val quickClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .followRedirects(true)
        .followSslRedirects(true)
        .addInterceptor(AntiBlockInterceptor)
        .build()

    fun ensureFreshChannels(
        context: Context,
        onlyLive: Boolean = false,
        onResult: (ChannelRefreshResult) -> Unit
    ) {
        val active = SourcePrefs.getActiveProfile(context.applicationContext)
        if (active == null) {
            onMain { onResult(ChannelRefreshResult(emptyList(), refreshedFromServer = false, usedCacheFallback = false, message = "no_active_profile")) }
            return
        }
        ensureFreshChannels(context, active, onlyLive, onResult)
    }

    fun ensureFreshChannels(
        context: Context,
        profile: IptvProfile,
        onlyLive: Boolean = false,
        onResult: (ChannelRefreshResult) -> Unit
    ) {
        val appContext = context.applicationContext
        val cached = filterChannels(ChannelCache.load(appContext, profile.id), onlyLive)
        val pendingRefresh = SourcePrefs.isPendingServerRefresh(appContext, profile.id)
        val cacheRevision = ChannelCache.revision(appContext, profile.id)
        val expectedRevision = profile.serverRevision
        val revisionMismatch = expectedRevision > 0L && cacheRevision != expectedRevision

        val shouldRefresh = pendingRefresh || cached.isEmpty() || revisionMismatch
        if (!shouldRefresh) {
            onMain {
                onResult(
                    ChannelRefreshResult(
                        channels = cached,
                        refreshedFromServer = false,
                        usedCacheFallback = false
                    )
                )
            }
            return
        }

        fetchAndCache(appContext, profile, cached, onlyLive, onResult)
    }

    private fun fetchAndCache(
        appContext: Context,
        profile: IptvProfile,
        cachedFallback: List<Channel>,
        onlyLive: Boolean,
        onResult: (ChannelRefreshResult) -> Unit
    ) {
        if (onlyLive) {
            fetchAndCacheLiveFast(appContext, profile, cachedFallback, onResult)
            return
        }

        val provider = ChannelsProvider()
        val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

        var finished = false
        lateinit var channelsObserver: Observer<List<Channel>>
        lateinit var errorObserver: Observer<String?>

        fun complete(result: ChannelRefreshResult) {
            if (finished) return
            finished = true
            try { provider.channels.removeObserver(channelsObserver) } catch (_: Exception) {}
            try { provider.error.removeObserver(errorObserver) } catch (_: Exception) {}
            mainHandler.removeCallbacksAndMessages(null)
            onMain { onResult(result) }
        }

        channelsObserver = Observer { data ->
            if (finished || data.isNullOrEmpty()) return@Observer
            Thread {
                try {
                    ChannelCache.save(appContext, profile.id, data)
                    val latestRevision = SourcePrefs.getActiveProfile(appContext)
                        ?.takeIf { it.id == profile.id }
                        ?.serverRevision
                        ?: profile.serverRevision
                    ChannelCache.markRevision(appContext, profile.id, latestRevision)
                    SourcePrefs.setPendingServerRefresh(appContext, profile.id, false)
                } catch (_: Exception) {}
                complete(
                    ChannelRefreshResult(
                        channels = data,
                        refreshedFromServer = true,
                        usedCacheFallback = false
                    )
                )
            }.start()
        }

        errorObserver = Observer { error ->
            if (finished || error.isNullOrBlank()) return@Observer
            if (cachedFallback.isNotEmpty()) {
                complete(
                    ChannelRefreshResult(
                        channels = cachedFallback,
                        refreshedFromServer = false,
                        usedCacheFallback = true,
                        message = error
                    )
                )
            } else {
                complete(
                    ChannelRefreshResult(
                        channels = emptyList(),
                        refreshedFromServer = false,
                        usedCacheFallback = false,
                        message = error
                    )
                )
            }
        }

        provider.channels.observeForever(channelsObserver)
        provider.error.observeForever(errorObserver)

        mainHandler.postDelayed({
            if (finished) return@postDelayed
            if (cachedFallback.isNotEmpty()) {
                complete(
                    ChannelRefreshResult(
                        channels = cachedFallback,
                        refreshedFromServer = false,
                        usedCacheFallback = true,
                        message = "fetch_timeout"
                    )
                )
            } else {
                complete(
                    ChannelRefreshResult(
                        channels = emptyList(),
                        refreshedFromServer = false,
                        usedCacheFallback = false,
                        message = "fetch_timeout"
                    )
                )
            }
        }, FETCH_TIMEOUT_MS)

        provider.fetchM3UFile(profile.m3uUrl)
    }

    private fun fetchAndCacheLiveFast(
        appContext: Context,
        profile: IptvProfile,
        cachedFallback: List<Channel>,
        onResult: (ChannelRefreshResult) -> Unit
    ) {
        thread(name = "LatchiFastLiveRefresh") {
            try {
                val liveChannels = fetchLiveOnly(profile.m3uUrl)
                if (liveChannels.isNotEmpty()) {
                    try {
                        ChannelCache.saveLiveOnly(appContext, profile.id, liveChannels)
                        val latestRevision = SourcePrefs.getActiveProfile(appContext)
                            ?.takeIf { it.id == profile.id }
                            ?.serverRevision
                            ?: profile.serverRevision
                        ChannelCache.markRevision(appContext, profile.id, latestRevision)
                        SourcePrefs.setPendingServerRefresh(appContext, profile.id, false)
                    } catch (_: Exception) {}

                    onMain {
                        onResult(
                            ChannelRefreshResult(
                                channels = liveChannels,
                                refreshedFromServer = true,
                                usedCacheFallback = false
                            )
                        )
                    }
                } else if (cachedFallback.isNotEmpty()) {
                    onMain {
                        onResult(
                            ChannelRefreshResult(
                                channels = cachedFallback,
                                refreshedFromServer = false,
                                usedCacheFallback = true,
                                message = "empty_live"
                            )
                        )
                    }
                } else {
                    onMain {
                        onResult(
                            ChannelRefreshResult(
                                channels = emptyList(),
                                refreshedFromServer = true,
                                usedCacheFallback = false,
                                message = "empty_live"
                            )
                        )
                    }
                }
            } catch (e: Exception) {
                if (cachedFallback.isNotEmpty()) {
                    onMain {
                        onResult(
                            ChannelRefreshResult(
                                channels = cachedFallback,
                                refreshedFromServer = false,
                                usedCacheFallback = true,
                                message = e.localizedMessage ?: "fast_live_error"
                            )
                        )
                    }
                } else {
                    onMain {
                        onResult(
                            ChannelRefreshResult(
                                channels = emptyList(),
                                refreshedFromServer = false,
                                usedCacheFallback = false,
                                message = e.localizedMessage ?: "fast_live_error"
                            )
                        )
                    }
                }
            }
        }
    }

    private fun fetchLiveOnly(sourceUrl: String): List<Channel> {
        val clean = sourceUrl.trim().replace("&amp;", "&")
        return when {
            StalkerHelper.isMacSource(clean) -> StalkerHelper.fetchChannels(clean).filter { it.contentType == "live" }
            XtreamHelper.parseCreds(clean) != null || clean.contains("get.php", ignoreCase = true) -> fetchXtreamLiveOnly(clean)
            else -> parseM3uLiveOnly(clean)
        }
    }

    private fun fetchXtreamLiveOnly(sourceUrl: String): List<Channel> {
        val uri = URI(sourceUrl)
        val server = "${uri.scheme}://${uri.host}${if (uri.port != -1) ":${uri.port}" else ""}"
        val query = uri.rawQuery ?: return emptyList()
        val params = query.split("&").mapNotNull {
            val p = it.split("=", limit = 2)
            if (p.size == 2) URLDecoder.decode(p[0], "UTF-8") to URLDecoder.decode(p[1], "UTF-8") else null
        }.toMap()
        val username = params["username"] ?: return emptyList()
        val password = params["password"] ?: return emptyList()

        val categoryMap = fetchXtreamLiveCategoryMap(server, username, password)
        val url = "$server/player_api.php?username=${urlEncode(username)}&password=${urlEncode(password)}&action=get_live_streams"
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36")
            .get()
            .build()

        val list = mutableListOf<Channel>()
        quickClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return emptyList()
            val stream = response.body?.byteStream() ?: return emptyList()
            JsonReader(stream.bufferedReader(Charsets.UTF_8)).use { reader ->
                reader.beginArray()
                while (reader.hasNext()) {
                    var id = ""
                    var name = ""
                    var icon = ""
                    var categoryId = ""

                    reader.beginObject()
                    while (reader.hasNext()) {
                        when (reader.nextName()) {
                            "stream_id" -> id = readJsonStringSafe(reader)
                            "name" -> name = readJsonStringSafe(reader)
                            "stream_icon" -> icon = readJsonStringSafe(reader)
                            "category_id" -> categoryId = readJsonStringSafe(reader)
                            else -> reader.skipValue()
                        }
                    }
                    reader.endObject()

                    if (id.isNotBlank() && name.isNotBlank()) {
                        val category = normalizeCategory(categoryMap[categoryId].orEmpty().ifBlank { "Live TV" })
                        list.add(Channel(name, icon, "$server/live/$username/$password/$id.ts", category, "live"))
                    }
                }
                reader.endArray()
            }
        }
        return list
    }

    private fun fetchXtreamLiveCategoryMap(server: String, username: String, password: String): Map<String, String> {
        val url = "$server/player_api.php?username=${urlEncode(username)}&password=${urlEncode(password)}&action=get_live_categories"
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36")
            .get()
            .build()

        val out = HashMap<String, String>()
        return try {
            quickClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return emptyMap()
                val stream = response.body?.byteStream() ?: return emptyMap()
                JsonReader(stream.bufferedReader(Charsets.UTF_8)).use { reader ->
                    reader.beginArray()
                    while (reader.hasNext()) {
                        var id = ""
                        var name = ""
                        reader.beginObject()
                        while (reader.hasNext()) {
                            when (reader.nextName()) {
                                "category_id" -> id = readJsonStringSafe(reader)
                                "category_name" -> name = readJsonStringSafe(reader)
                                else -> reader.skipValue()
                            }
                        }
                        reader.endObject()
                        if (id.isNotBlank() && name.isNotBlank()) out[id] = name
                    }
                    reader.endArray()
                }
            }
            out
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private fun parseM3uLiveOnly(sourceUrl: String): List<Channel> {
        for (candidate in buildCandidateUrls(sourceUrl)) {
            try {
                val request = Request.Builder()
                    .url(candidate)
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36")
                    .get()
                    .build()

                val list = mutableListOf<Channel>()
                quickClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use
                    val stream = response.body?.byteStream() ?: return@use
                    var name: String? = null
                    var logoUrl = ""
                    var category = "Other"

                    stream.bufferedReader(Charsets.UTF_8).useLines { lines ->
                        for (raw in lines) {
                            val line = raw.trim().replace("&amp;", "&")
                            when {
                                line.startsWith("#EXTINF") -> {
                                    name = line.substringAfterLast(",", "Unknown")
                                        .replace("[", "")
                                        .replace("]", "")
                                        .trim()
                                        .ifEmpty { "Unknown" }
                                    logoUrl = Regex("tvg-logo=\"([^\"]*)\"").find(line)?.groupValues?.getOrNull(1) ?: ""
                                    category = normalizeCategory(
                                        Regex("group-title=\"([^\"]*)\"").find(line)?.groupValues?.getOrNull(1)
                                            ?.trim()
                                            .orEmpty()
                                            .ifBlank { "Other" }
                                    )
                                }
                                line.isNotBlank() && !line.startsWith("#") && (line.startsWith("http://") || line.startsWith("https://")) -> {
                                    if (!name.isNullOrBlank() && !isVodOrSeriesUrl(line)) {
                                        list.add(Channel(name ?: "Unknown", logoUrl, line, category, "live"))
                                    }
                                    name = null
                                    logoUrl = ""
                                    category = "Other"
                                }
                            }
                        }
                    }
                }
                if (list.isNotEmpty()) return list
            } catch (_: Exception) {
            }
        }
        return emptyList()
    }

    private fun buildCandidateUrls(original: String): List<String> {
        val clean = original.trim().replace("&amp;", "&")
        val list = linkedSetOf<String>()
        list.add(clean)

        if (clean.contains("get.php", ignoreCase = true)) {
            var plus = clean.replace("type=m3u", "type=m3u_plus", ignoreCase = true)
            if (!plus.contains("type=", ignoreCase = true)) plus += if (plus.contains("?")) "&type=m3u_plus" else "?type=m3u_plus"
            if (!plus.contains("output=", ignoreCase = true)) plus += "&output=ts"
            list.add(plus)
            list.add(plus.replace("output=ts", "output=m3u8", ignoreCase = true))
        }
        return list.toList()
    }

    private fun isVodOrSeriesUrl(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("/movie/") || lower.contains("/vod/") || lower.contains("/series/")
    }

    private fun normalizeCategory(raw: String): String {
        var text = raw.trim().replace(Regex("\\s+"), " ").trim()
        if (!text.matches(Regex("\\d+"))) {
            text = text.replace(Regex("^\\d+\\s+"), "").trim()
        }
        return text.ifBlank { "Other" }
    }

    private fun readJsonStringSafe(reader: JsonReader): String {
        return try {
            reader.nextString()
        } catch (_: Exception) {
            try {
                reader.nextInt().toString()
            } catch (_: Exception) {
                reader.skipValue()
                ""
            }
        }
    }

    private fun urlEncode(value: String): String = URLEncoder.encode(value, "UTF-8")

    private fun filterChannels(channels: List<Channel>, onlyLive: Boolean): List<Channel> {
        return if (onlyLive) channels.filter { it.contentType == "live" } else channels
    }

    private fun onMain(block: () -> Unit) {
        android.os.Handler(android.os.Looper.getMainLooper()).post(block)
    }
}
