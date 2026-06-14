package com.latchi.iptv.utils

import com.latchi.iptv.model.Channel

/**
 * 🎙️ محلل أوامر صوتية سريع يدعم الدارجة الجزائرية + العربية + الفرنسية + الإنجليزية.
 * يعمل محلياً حتى إذا Gemini API غير متاح.
 */
object VoiceCommandParser {

    private val NAVIGATION_KEYWORDS = mapOf(
        listOf("إعدادات", "settings", "paramètres", "parametres", "اعدادات", "الإعدادات", "الاعدادات", "سيتينغ") to VoiceCommand.Screen.SETTINGS,
        listOf("مباريات", "matches", "matchs", "المباريات", "مباراة", "كورة", "كرة", "ماتش", "ماتشات") to VoiceCommand.Screen.MATCHES,
        listOf("أفلام", "movies", "movie", "films", "film", "الأفلام", "افلام", "الافلام") to VoiceCommand.Screen.MOVIES,
        listOf("مسلسلات", "series", "séries", "serie", "مسلسل", "المسلسلات", "الحلقات") to VoiceCommand.Screen.SERIES,
        listOf("قنوات", "قناة", "مباشر", "البث", "live", "direct", "channels", "channel", "tv", "تلفزيون") to VoiceCommand.Screen.LIVE,
        listOf("مستخدم", "users", "user", "utilisateur", "utilisateurs", "المستخدمين", "تغيير", "حساب", "account", "بدل الحساب") to VoiceCommand.Screen.USERS,
        listOf("تسعير", "pricing", "prix", "الأسعار", "الاسعار", "سعر", "اشتراك") to VoiceCommand.Screen.PRICING,
        listOf("صلاة", "صلا", "prayer", "prayers", "مواقيت", "أذان", "اذان") to VoiceCommand.Screen.PRAYER,
        listOf("حول", "عن", "about", "معلومات", "info") to VoiceCommand.Screen.ABOUT
    )

    private val PLAY_PREFIXES = listOf(
        "شغل", "شغلي", "تشغيل", "افتح", "افتحلي", "فتح", "دير", "ديرلي", "حط", "حطلي", "وريني", "شوف", "عرض",
        "play", "open", "watch", "mets", "ouvre", "lance"
    )

    private val SEARCH_PREFIXES = listOf("حوس", "حوسلي", "ابحث", "بحث", "دور", "لقالي", "وين", "search", "cherche", "recherche")
    private val FAVORITE_WORDS = listOf("مفضلة", "المفضلة", "favorites", "favoris", "fav")
    private val HOME_WORDS = listOf("الرئيسية", "الصفحة الرئيسية", "home", "accueil")
    private val CATEGORY_PREFIXES = listOf("فئة", "الفئة", "كاتيغوري", "category", "catégorie", "categorie", "روح للفئة", "غير الفئة", "وريني فئة")

    private val PLAYER_CONTROL_PATTERNS = listOf(
        listOf("القناة الجاية", "لاشين الجاية", "اقلب لاشين", "أقلب لاشين", "جوز هذي", "بدل لاشين", "next channel", "chaine suivante") to "next_channel",
        listOf("القناة اللي قبل", "رجع لاشين", "previous channel", "chaine précédente", "chaine precedente") to "previous_channel",
        listOf("دير بوز", "حبس", "وقف", "pause", "pausa", "حبس دقيقة", "طفي عليا") to "pause",
        listOf("كمل", "اطلقو", "اطلق", "resume", "play", "واصل") to "resume",
        listOf("زيد الصوت", "زيدلو", "قويلو", "volume up", "ارفع الصوت") to "volume_up",
        listOf("نقص الصوت", "هبط الصوت", "volume down", "baisse le son") to "volume_down",
        listOf("كوبي الصوت", "كتم", "mute", "المييت", "ديرلو المييت") to "mute",
        listOf("قدم", "زيد القدام", "seek forward", "forward") to "seek_forward",
        listOf("رجع الفيديو", "رجع اللور", "seek back", "rewind") to "seek_back"
    )

    fun parse(voiceText: String): VoiceCommand {
        val original = voiceText.trim()
        val text = original.lowercase().trim()
        if (text.isBlank()) return VoiceCommand.Unknown

        for ((keywords, target) in PLAYER_CONTROL_PATTERNS) {
            if (keywords.any { text.contains(it.lowercase()) }) {
                val seconds = Regex("\\d+").find(text)?.value ?: if (target.startsWith("seek_")) "10" else ""
                return VoiceCommand.PlayerControl(target, seconds)
            }
        }

        if (HOME_WORDS.any { text.contains(it) }) return VoiceCommand.Home
        if (FAVORITE_WORDS.any { text.contains(it) } && (text.contains("افتح") || text.contains("روح") || text.contains("open") || text.contains("ouvre"))) return VoiceCommand.Favorites

        for ((keywords, screen) in NAVIGATION_KEYWORDS) {
            if (keywords.any { text.contains(it.lowercase()) }) {
                val wantsPlay = PLAY_PREFIXES.any { text.startsWith(it.lowercase()) || text.contains(" ${it.lowercase()} ") }
                if (!wantsPlay || screen != VoiceCommand.Screen.LIVE) return VoiceCommand.Navigate(screen)
            }
        }

        CATEGORY_PREFIXES.firstOrNull { text.contains(it.lowercase()) }?.let { prefix ->
            val cat = cleanupTarget(text.replace(prefix.lowercase(), ""))
            if (cat.isNotBlank()) return VoiceCommand.Category(cat)
        }

        SEARCH_PREFIXES.firstOrNull { text.startsWith(it.lowercase()) || text.contains(" ${it.lowercase()} ") }?.let { prefix ->
            val query = cleanupTarget(text.replace(prefix.lowercase(), ""))
            if (query.isNotBlank()) return VoiceCommand.Search(query, guessType(text))
        }

        PLAY_PREFIXES.firstOrNull { text.startsWith(it.lowercase()) || text.contains(" ${it.lowercase()} ") }?.let { prefix ->
            val targetName = cleanupTarget(text.replace(prefix.lowercase(), ""))
            if (targetName.isNotBlank()) return VoiceCommand.Play(targetName)
        }

        if (text.length <= 40 && text.split(" ").size <= 6) return VoiceCommand.Play(text)
        return VoiceCommand.Search(text, guessType(text))
    }

    private fun guessType(text: String): String? = when {
        listOf("فيلم", "افلام", "أفلام", "movie", "film").any { text.contains(it.lowercase()) } -> "movie"
        listOf("مسلسل", "مسلسلات", "series", "serie", "série").any { text.contains(it.lowercase()) } -> "series"
        listOf("قناة", "قنوات", "live", "direct", "tv").any { text.contains(it.lowercase()) } -> "live"
        else -> null
    }

    private fun cleanupTarget(raw: String): String {
        return raw
            .replace("قناة", "")
            .replace("فيلم", "")
            .replace("مسلسل", "")
            .replace("على", "")
            .replace("عن", "")
            .replace("  ", " ")
            .trim('-', ' ', ':')
    }
}

/**
 * 📋 On-Demand Voice Matching Engine
 * Fully RAM-efficient. Zero heap locks.
 */
object VoiceIndex {
    private var channels: List<Channel> = emptyList()

    @Synchronized
    fun update(newChannels: List<Channel>) {
        channels = newChannels
    }

    fun findChannel(query: String, preferredType: String? = null): Channel? {
        val normalized = normalize(query)
        val list = if (preferredType.isNullOrBlank()) channels else channels.filter { it.contentType.equals(preferredType, true) }
        if (list.isEmpty() || normalized.isEmpty()) return null

        list.firstOrNull { normalize(it.name) == normalized }?.let { return it }

        list.firstOrNull { ch ->
            val lower = normalize(ch.name)
            lower.contains(normalized) || normalized.contains(lower)
        }?.let { return it }

        val queryWords = normalized.split(" ", "-", "_", ".").filter { it.length >= 3 }
        val scored = list.map { ch ->
            val name = normalize(ch.name)
            val score = queryWords.count { name.contains(it) }
            ch to score
        }.filter { it.second > 0 }.sortedByDescending { it.second }
        if (scored.isNotEmpty()) return scored.first().first

        if (normalized.length >= 3) {
            val prefix = normalized.take(3)
            list.firstOrNull { normalize(it.name).startsWith(prefix) }?.let { return it }
        }

        return null
    }

    fun search(query: String, preferredType: String? = null, limit: Int = 20): List<Channel> {
        val normalized = normalize(query)
        if (normalized.isBlank()) return emptyList()
        val list = if (preferredType.isNullOrBlank()) channels else channels.filter { it.contentType.equals(preferredType, true) }
        val words = normalized.split(" ", "-", "_", ".").filter { it.length >= 2 }
        return list.map { ch ->
            val name = normalize(ch.name)
            val score = (if (name.contains(normalized)) 10 else 0) + words.count { name.contains(it) }
            ch to score
        }.filter { it.second > 0 }.sortedByDescending { it.second }.take(limit).map { it.first }
    }

    private fun normalize(raw: String): String {
        return raw.lowercase()
            .replace("أ", "ا").replace("إ", "ا").replace("آ", "ا")
            .replace("بي إن", "bein").replace("بي ان", "bein").replace("بيين", "bein")
            .replace("سبورت", "sport").replace("سپور", "sport")
            .replace("ال", "")
            .trim()
    }

    fun getAll(): List<Channel> = channels
    fun isEmpty(): Boolean = channels.isEmpty()
}
