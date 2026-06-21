package com.latchi.iptv.utils

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.latchi.iptv.model.Channel

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
        BeinChannelResolver.resolve(context, profile) { bein ->
            onMain { onResult(Result(bein, if (bein.isNotEmpty()) "smart_bein" else "empty", "")) }
        }
    }

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
