package com.latchi.iptv.provider

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.latchi.iptv.model.Channel
import com.latchi.iptv.utils.StalkerHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.net.URI
import java.net.URLEncoder
import java.net.URLDecoder
import java.util.concurrent.TimeUnit

data class ChannelCategory(
    val id: String,
    val name: String,
    val contentType: String,
    val count: Int = -1
)

private data class XtreamInfo(
    val server: String,
    val username: String,
    val password: String
)

class ChannelsProvider : ViewModel() {
    private val _channels = MutableLiveData<List<Channel>>()
    val channels: LiveData<List<Channel>> get() = _channels

    private val _filteredChannels = MutableLiveData<List<Channel>>()
    val filteredChannels: LiveData<List<Channel>> get() = _filteredChannels

    private val _categories = MutableLiveData<List<ChannelCategory>>()
    val categories: LiveData<List<ChannelCategory>> get() = _categories

    private val _error = MutableLiveData<String?>()
    val error: LiveData<String?> get() = _error

    private var fetchJob: Job? = null

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    fun setLocalChannels(list: List<Channel>) {
        val curated = curateForArabicAudience(list)
        _channels.value = curated
        // لا نضع curated مباشرة في filteredChannels هنا.
        // شاشة ChannelList تطبق contentType/category/search بعد وصول channels.
        // هذا يمنع سباق DiffUtil الذي كان يرجّع كل القنوات بعد اختيار فئة.
        _error.value = null
    }

    fun fetchM3UFile(sourceUrl: String) {
        fetchJob?.cancel()
        fetchJob = viewModelScope.launch(Dispatchers.IO) {
            var lastError: String? = null
            try {
                if (StalkerHelper.isMacSource(sourceUrl)) {
                    val macChannels = StalkerHelper.fetchChannels(sourceUrl)
                    withContext(Dispatchers.Main) {
                        if (macChannels.isNotEmpty()) {
                            _channels.value = curateForArabicAudience(macChannels)
                            _error.value = null
                        } else {
                            _error.value = "فشل تحميل قنوات MAC/Stalker أو الحساب غير مدعوم"
                        }
                    }
                    return@launch
                }

                try {
                    val apiChannels = tryXtreamApi(sourceUrl)
                    if (apiChannels.isNotEmpty()) {
                        val curated = curateForArabicAudience(apiChannels)
                        withContext(Dispatchers.Main) {
                            _channels.value = curated
                            _error.value = null
                        }
                        return@launch
                    }
                } catch (e: Exception) {
                    lastError = e.localizedMessage
                }

                val candidates = buildCandidateUrls(sourceUrl)
                for (url in candidates) {
                    try {
                        val m3uChannels = downloadAndParseM3uStream(url)
                        if (m3uChannels.isNotEmpty()) {
                            val curated = curateForArabicAudience(m3uChannels)
                            withContext(Dispatchers.Main) {
                                _channels.value = curated
                                _error.value = null
                            }
                            return@launch
                        } else {
                            lastError = "Playlist is empty"
                        }
                    } catch (e: Exception) {
                        lastError = e.localizedMessage ?: "Connection failed"
                    }
                }

                withContext(Dispatchers.Main) {
                    _error.value = "Failed to fetch playlist: ${lastError ?: "Unknown error"}"
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    _error.value = "Failed to fetch playlist: ${e.localizedMessage ?: "Unknown error"}"
                }
            }
        }
    }


    fun isXtreamSource(sourceUrl: String): Boolean = !StalkerHelper.isMacSource(sourceUrl) && parseXtreamInfo(sourceUrl) != null

    fun fetchXtreamCategoriesAndFirst(sourceUrl: String, contentType: String) {
        fetchJob?.cancel()
        fetchJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val info = parseXtreamInfo(sourceUrl) ?: throw IllegalArgumentException("Xtream source not detected")
                val cats = fetchXtreamCategoriesList(info, contentType)
                if (cats.isEmpty()) {
                    withContext(Dispatchers.Main) { _error.value = "لا توجد فئات متاحة لهذا القسم" }
                    return@launch
                }
                val first = cats.first()
                val channels = fetchXtreamChannelsForCategory(info, contentType, first.id, first.name)
                withContext(Dispatchers.Main) {
                    _categories.value = cats
                    _channels.value = curateForArabicAudience(channels)
                    _error.value = null
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    _error.value = "فشل تحميل فئات Xtream: ${e.localizedMessage ?: "Unknown error"}"
                }
            }
        }
    }

    fun fetchXtreamCategoryChannels(sourceUrl: String, contentType: String, categoryId: String, categoryName: String) {
        fetchJob?.cancel()
        fetchJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val info = parseXtreamInfo(sourceUrl) ?: throw IllegalArgumentException("Xtream source not detected")
                val channels = fetchXtreamChannelsForCategory(info, contentType, categoryId, categoryName)
                withContext(Dispatchers.Main) {
                    _channels.value = curateForArabicAudience(channels)
                    _error.value = null
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    _error.value = "فشل تحميل الفئة: ${e.localizedMessage ?: "Unknown error"}"
                }
            }
        }
    }

    private fun parseXtreamInfo(sourceUrl: String): XtreamInfo? {
        return try {
            val clean = cleanUrl(sourceUrl)
            if (!clean.contains("get.php", ignoreCase = true)) return null
            val uri = URI(clean)
            val server = "${uri.scheme}://${uri.host}${if (uri.port != -1) ":${uri.port}" else ""}"
            val query = uri.rawQuery ?: return null
            val params = query.split("&").mapNotNull {
                val p = it.split("=", limit = 2)
                if (p.size == 2) {
                    val key = URLDecoder.decode(p[0], "UTF-8")
                    val value = URLDecoder.decode(p[1], "UTF-8")
                    key to value
                } else null
            }.toMap()
            val username = params["username"] ?: return null
            val password = params["password"] ?: return null
            XtreamInfo(server, username, password)
        } catch (_: Exception) {
            null
        }
    }

    private fun fetchXtreamCategoriesList(info: XtreamInfo, contentType: String): List<ChannelCategory> {
        val action = when (contentType) {
            "movie" -> "get_vod_categories"
            "series" -> "get_series_categories"
            else -> "get_live_categories"
        }
        val url = "${info.server}/player_api.php?username=${urlEncode(info.username)}&password=${urlEncode(info.password)}&action=$action"
        val request = Request.Builder()
            .url(cleanUrl(url))
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36")
            .get()
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return emptyList()
            val stream = response.body?.byteStream() ?: return emptyList()
            val reader = android.util.JsonReader(stream.bufferedReader(Charsets.UTF_8))
            val list = mutableListOf<ChannelCategory>()
            reader.use { r ->
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
                    if (id.isNotBlank() && name.isNotBlank()) {
                        list.add(ChannelCategory(id, normalizeCategory(name), contentType, -1))
                    }
                }
                r.endArray()
            }
            return sortXtreamCategories(list)
        }
    }

    private fun fetchXtreamChannelsForCategory(info: XtreamInfo, contentType: String, categoryId: String, categoryName: String): List<Channel> {
        val action = when (contentType) {
            "movie" -> "get_vod_streams"
            "series" -> "get_series"
            else -> "get_live_streams"
        }
        val url = "${info.server}/player_api.php?username=${urlEncode(info.username)}&password=${urlEncode(info.password)}&action=$action&category_id=${urlEncode(categoryId)}"
        val request = Request.Builder()
            .url(cleanUrl(url))
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36")
            .get()
            .build()

        val list = mutableListOf<Channel>()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return emptyList()
            val stream = response.body?.byteStream() ?: return emptyList()
            val reader = android.util.JsonReader(stream.bufferedReader(Charsets.UTF_8))
            reader.use { r ->
                r.beginArray()
                while (r.hasNext()) {
                    var id = ""
                    var name = ""
                    var icon = ""
                    var ext = "mp4"

                    r.beginObject()
                    while (r.hasNext()) {
                        when (r.nextName()) {
                            "stream_id", "series_id" -> id = readJsonStringSafe(r)
                            "name" -> name = readJsonStringSafe(r).ifBlank { "Stream" }
                            "stream_icon", "cover" -> icon = readJsonStringSafe(r)
                            "container_extension" -> ext = readJsonStringSafe(r).ifBlank { "mp4" }
                            else -> r.skipValue()
                        }
                    }
                    r.endObject()

                    if (id.isNotBlank() && name.isNotBlank()) {
                        val streamUrl = when (contentType) {
                            "live" -> "${info.server}/live/${info.username}/${info.password}/$id.ts"
                            "movie" -> "${info.server}/movie/${info.username}/${info.password}/$id.$ext"
                            else -> "series://$id"
                        }
                        list.add(Channel(name, icon, streamUrl, categoryName.ifBlank { "Other" }, contentType))
                    }
                }
                r.endArray()
            }
        }
        return list
    }

    private fun readJsonStringSafe(r: android.util.JsonReader): String {
        return try {
            r.nextString()
        } catch (_: Exception) {
            try { r.nextInt().toString() } catch (_: Exception) { r.skipValue(); "" }
        }
    }

    private fun sortXtreamCategories(cats: List<ChannelCategory>): List<ChannelCategory> {
        fun score(cat: String): Int {
            val l = cat.lowercase()
            return when {
                l.contains("world cup") || l.contains("كأس العالم") -> 1
                l.contains("bein sport") || l.contains("bein sports") || l.contains("bein") -> 2
                l.contains("sport") || l.contains("ssc") || l.contains("alkass") || l.contains("ad sport") -> 3
                l.contains("movie") || l.contains("film") || l.contains("أفلام") || l.contains("افلام") -> 4
                l.contains("series") || l.contains("مسلسل") || l.contains("مسلسلات") -> 5
                l.contains("arab") || l.contains("عرب") || l.contains("عربي") -> 6
                l.contains("kid") || l.contains("أطفال") || l.contains("اطفال") || l.contains("cartoon") -> 7
                l.contains("news") || l.contains("أخبار") || l.contains("اخبار") -> 8
                l.contains("islam") || l.contains("quran") || l.contains("إسلام") || l.contains("قرآن") -> 9
                else -> 20
            }
        }
        return cats.sortedWith(compareBy<ChannelCategory> { score(it.name) }.thenBy { it.name.lowercase() })
    }

    private fun urlEncode(value: String): String = URLEncoder.encode(value, "UTF-8")

    private fun downloadAndParseM3uStream(url: String): List<Channel> {
        val list = mutableListOf<Channel>()
        try {
            val request = Request.Builder()
                .url(cleanUrl(url))
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36")
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return emptyList()
                val stream = response.body?.byteStream() ?: return emptyList()
                
                var name: String? = null
                var logoUrl = ""
                var category = "Other"

                stream.bufferedReader(Charsets.UTF_8).useLines { lines ->
                    for (raw in lines) {
                        val line = cleanUrl(raw.trim())
                        when {
                            line.startsWith("#EXTINF") -> {
                                name = extractChannelName(line)
                                logoUrl = extractLogoUrl(line) ?: ""
                                category = normalizeCategory(extractGroupTitle(line).ifBlank { "Other" })
                            }
                            line.isNotBlank() && !line.startsWith("#") && isValidUrl(line) -> {
                                if (!name.isNullOrEmpty()) {
                                    list.add(Channel(name ?: "Unknown", logoUrl, line, category, detectContentType(line, category, name ?: "")))
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
            android.util.Log.e("ChannelsProvider", "M3U Stream Error: ${e.message}")
        }
        return list
    }

    private suspend fun tryXtreamApi(sourceUrl: String): List<Channel> = coroutineScope {
        val clean = cleanUrl(sourceUrl)
        if (!clean.contains("get.php", ignoreCase = true)) return@coroutineScope emptyList()
        val uri = URI(clean)
        val server = "${uri.scheme}://${uri.host}${if (uri.port != -1) ":${uri.port}" else ""}"
        val query = uri.query ?: return@coroutineScope emptyList()
        val params = query.split("&").mapNotNull {
            val p = it.split("=", limit = 2)
            if (p.size == 2) p[0] to p[1] else null
        }.toMap()
        val username = params["username"] ?: return@coroutineScope emptyList()
        val password = params["password"] ?: return@coroutineScope emptyList()

        val liveUrl = "$server/player_api.php?username=$username&password=$password&action=get_live_streams"
        val vodUrl = "$server/player_api.php?username=$username&password=$password&action=get_vod_streams"
        val seriesUrl = "$server/player_api.php?username=$username&password=$password&action=get_series"

        // تحميل خرائط التصنيفات بالتوازي بدل 3 طلبات متسلسلة.
        val liveCatDeferred = async { fetchCategoryMap("$server/player_api.php?username=$username&password=$password&action=get_live_categories") }
        val vodCatDeferred = async { fetchCategoryMap("$server/player_api.php?username=$username&password=$password&action=get_vod_categories") }
        val seriesCatDeferred = async { fetchCategoryMap("$server/player_api.php?username=$username&password=$password&action=get_series_categories") }

        val liveCatMap = liveCatDeferred.await()
        val vodCatMap = vodCatDeferred.await()
        val seriesCatMap = seriesCatDeferred.await()

        // تحميل Live/VOD/Series بالتوازي لتقليل وقت أول تحديث.
        val liveDeferred = async { parseXtreamStreamsUsingJsonReader(liveUrl, "live", liveCatMap, server, username, password) }
        val vodDeferred = async { parseXtreamStreamsUsingJsonReader(vodUrl, "movie", vodCatMap, server, username, password) }
        val seriesDeferred = async { parseXtreamStreamsUsingJsonReader(seriesUrl, "series", seriesCatMap, server, username, password) }

        liveDeferred.await() + vodDeferred.await() + seriesDeferred.await()
    }

    private fun parseXtreamStreamsUsingJsonReader(url: String, type: String, catMap: Map<String, String>, server: String, username: String, password: String): List<Channel> {
        val list = mutableListOf<Channel>()
        try {
            val request = Request.Builder()
                .url(cleanUrl(url))
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36")
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return emptyList()
                val stream = response.body?.byteStream() ?: return emptyList()
                
                val reader = android.util.JsonReader(stream.bufferedReader(Charsets.UTF_8))
                reader.use { r ->
                    r.beginArray()
                    while (r.hasNext()) {
                        var id = ""
                        var name = ""
                        var icon = ""
                        var catId = ""
                        var ext = "mp4"

                        r.beginObject()
                        while (r.hasNext()) {
                            when (r.nextName()) {
                                "stream_id", "series_id" -> {
                                    id = try { r.nextString() } catch (_: Exception) { try { r.nextInt().toString() } catch (_: Exception) { r.skipValue(); "" } }
                                }
                                "name" -> {
                                    name = try { r.nextString() } catch (_: Exception) { r.skipValue(); "Stream" }
                                }
                                "stream_icon", "cover" -> {
                                    icon = try { r.nextString() } catch (_: Exception) { r.skipValue(); "" }
                                }
                                "category_id" -> {
                                    catId = try { r.nextString() } catch (_: Exception) { try { r.nextInt().toString() } catch (_: Exception) { r.skipValue(); "" } }
                                }
                                "container_extension" -> {
                                    ext = try { r.nextString() } catch (_: Exception) { r.skipValue(); "mp4" }
                                }
                                else -> r.skipValue()
                            }
                        }
                        r.endObject()

                        if (id.isNotBlank() && name.isNotBlank()) {
                            val catName = catMap[catId] ?: catId.ifBlank { if (type == "live") "Live" else if (type == "movie") "Movies" else "Series" }
                            val cat = normalizeCategory(catName)
                            val streamUrl = when (type) {
                                "live" -> "$server/live/$username/$password/$id.ts"
                                "movie" -> "$server/movie/$username/$password/$id.$ext"
                                else -> "series://$id"
                            }
                            list.add(Channel(name, icon, streamUrl, cat, type))
                        }
                    }
                    r.endArray()
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("ChannelsProvider", "JsonReader Error for $type: ${e.message}")
        }
        return list
    }

    private fun fetchCategoryMap(url: String): Map<String, String> {
        return try {
            val request = Request.Builder()
                .url(cleanUrl(url))
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36")
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return emptyMap()
                val stream = response.body?.byteStream() ?: return emptyMap()
                val reader = android.util.JsonReader(stream.bufferedReader(Charsets.UTF_8))
                val map = HashMap<String, String>()
                reader.use { r ->
                    r.beginArray()
                    while (r.hasNext()) {
                        var id = ""
                        var name = ""
                        r.beginObject()
                        while (r.hasNext()) {
                            when (r.nextName()) {
                                "category_id" -> id = try { r.nextString() } catch (_: Exception) { try { r.nextInt().toString() } catch (_: Exception) { r.skipValue(); "" } }
                                "category_name" -> name = try { r.nextString() } catch (_: Exception) { r.skipValue(); "" }
                                else -> r.skipValue()
                            }
                        }
                        r.endObject()
                        if (id.isNotBlank() && name.isNotBlank()) map[id] = name
                    }
                    r.endArray()
                }
                map
            }
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private fun curateForArabicAudience(input: List<Channel>): List<Channel> {
        if (input.isEmpty()) return input
        val curated = input.filter { shouldKeepArabicCurated(it) }.distinctBy { it.streamUrl }
        // Safety fallback: never return an empty list if the provider uses unusual names.
        return if (curated.isNotEmpty()) curated else input
    }

    private fun shouldKeepArabicCurated(channel: Channel): Boolean {
        val text = "${channel.name} ${channel.category}".lowercase()
        val adultBlocked = listOf("xxx", "adult", "porn", "sex", "18+", "for adults", "erotic", "hot").any { text.contains(it) }
        if (adultBlocked) return false

        val hasArToken = Regex("""(^|[\s\-|_\[\]():/])ar($|[\s\-|_\[\]():/])""", RegexOption.IGNORE_CASE).containsMatchIn("${channel.name} ${channel.category}")
        val arabicSignals = listOf(
            "arab", "arabic", "عربي", "عربية", "العربية", "عرب", "mena", "middle east", "maghreb",
            "dz", "algeria", "algerie", "algérie", "الجزائر", "جزائر", "تونس", "tunisia", "tunisie",
            "مصر", "مصري", "egy", "egypt", "المغرب", "morocco", "maroc", "ليبيا", "libya",
            "سعود", "السعودية", "ksa", "saudi", "قطر", "qatar", "امارات", "الإمارات", "uae",
            "لبنان", "lebanon", "سوريا", "syria", "العراق", "iraq", "فلسطين", "palestine",
            "الأردن", "jordan", "الكويت", "kuwait", "البحرين", "bahrain", "عمان", "oman", "اليمن", "yemen", "رمضان"
        ).any { text.contains(it.lowercase()) }

        val knownArabicBrands = listOf(
            "mbc", "rotana", "روتانا", "osn", "bein", "be in", "بي ان", "بي إن", "ssc", "alkass", "الكاس", "الكأس", "art", "shahid", "شاهد", "aljazeera", "الجزيرة", "alarabiya", "العربية", "dubai", "دبي", "abu dhabi", "abudhabi", "أبوظبي", "ابوظبي", "ksa", "saudi", "السعودية", "qatar", "قطر", "majid", "ماجد", "spacetoon", "سبيستون", "cn arabia", "cartoon network arabic", "quran", "قرآن", "islam", "اسلام", "إسلام", "makkah", "mecca", "مكة", "madinah", "المدينة"
        ).any { text.contains(it.lowercase()) }

        val sportImportant = listOf(
            "sport", "sports", "رياض", "رياضي", "bein", "ssc", "alkass", "الكاس", "الكأس", "ontime", "on time", "ad sport", "abu dhabi sport", "dubai sport", "ksa sport", "قنوات رياضية"
        ).any { text.contains(it.lowercase()) }

        val translatedVod = (channel.contentType == "movie" || channel.contentType == "series") && listOf(
            "مترجم", "ترجمة", "subbed", "sub", "arsub", "ar sub", "arabic sub", "translated", "vostfr", "vost", "multi sub", "مدبلج", "dubbed", "vfq"
        ).any { text.contains(it.lowercase()) }

        val arabicVod = (channel.contentType == "movie" || channel.contentType == "series") && listOf(
            "arab", "arabic", "عربي", "عربية", "افلام", "أفلام", "film arab", "movie arab", "مسلسل", "مسلسلات", "series arab", "ramadan", "رمضان", "egy", "egypt", "مصر"
        ).any { text.contains(it.lowercase()) }

        return hasArToken || arabicSignals || knownArabicBrands || sportImportant || arabicVod || translatedVod
    }

    private fun buildCandidateUrls(original: String): List<String> {
        val clean = cleanUrl(original)
        val list = linkedSetOf<String>()
        list.add(clean)

        if (clean.contains("get.php", ignoreCase = true)) {
            var plus = clean
            plus = plus.replace("type=m3u_plus", "type=m3u_plus", ignoreCase = true)
            plus = plus.replace("type=m3u", "type=m3u_plus", ignoreCase = true)
            if (!plus.contains("type=", ignoreCase = true)) plus += if (plus.contains("?")) "&type=m3u_plus" else "?type=m3u_plus"
            if (!plus.contains("output=", ignoreCase = true)) plus += "&output=ts"
            list.add(plus)

            val m3u8 = plus.replace("output=ts", "output=m3u8", ignoreCase = true)
            list.add(m3u8)
        }
        return list.toList()
    }

    private fun detectContentType(url: String, group: String, name: String): String {
        val lowerUrl = url.lowercase()
        val lowerGroup = group.lowercase()
        val lowerName = name.lowercase()
        return when {
            lowerUrl.contains("/movie/") || lowerUrl.contains("/vod/") || lowerGroup.contains("movie") || lowerGroup.contains("movies") || lowerGroup.contains("vod") || lowerGroup.contains("film") || lowerGroup.contains("films") || lowerGroup.contains("cinema") || lowerGroup.contains("أفلام") || lowerGroup.contains("افلام") -> "movie"
            lowerUrl.contains("/series/") || lowerGroup.contains("series") || lowerGroup.contains("مسلسلات") || lowerGroup.contains("مسلسل") || lowerName.contains("s01") || lowerName.contains("e01") -> "series"
            else -> "live"
        }
    }

    private fun normalizeCategory(raw: String): String {
        var s = raw.trim()
            .replace(Regex("""\s+"""), " ")
            .trim()

        if (!s.matches(Regex("""\d+"""))) {
            s = s.replace(Regex("""^\d+\s+"""), "").trim()
        }

        return s.ifBlank { "Other" }
    }

    private fun cleanUrl(value: String): String = value.trim().replace("&amp;", "&")
    private fun extractChannelName(line: String): String = line.substringAfterLast(",", "Unknown").replace("[", "").replace("]", "").trim().ifEmpty { "Unknown" }
    private fun extractLogoUrl(line: String): String? = Regex("tvg-logo=\"([^\"]*)\"").find(line)?.groupValues?.getOrNull(1)
    private fun extractGroupTitle(line: String): String = Regex("group-title=\"([^\"]*)\"").find(line)?.groupValues?.getOrNull(1) ?: ""
    private fun isValidUrl(url: String): Boolean = url.startsWith("http://") || url.startsWith("https://")

    fun filterChannels(query: String, contentType: String, category: String, favoriteUrls: Set<String>) {
        val allChannels = _channels.value ?: emptyList()
        val searching = query.isNotBlank()
        _filteredChannels.value = allChannels.filter { c ->
            val typeOk = contentType == "all" || c.contentType == contentType
            val catOk = when {
                category == "All" -> true
                category == "Favorites" -> favoriteUrls.contains(c.streamUrl)
                else -> c.category.equals(category, ignoreCase = true)
            }
            val searchOk = !searching || c.name.contains(query, true) || c.category.contains(query, true)
            typeOk && catOk && searchOk
        }
    }

    override fun onCleared() {
        super.onCleared()
        fetchJob?.cancel()
    }
}
