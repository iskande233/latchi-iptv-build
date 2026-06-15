package com.latchi.iptv.utils

import com.latchi.iptv.model.Channel

/**
 * ⚡ Maps a match channel name (from Yacine TV API or known list) to an actual Channel object
 * from the loaded IPTV playlist. Uses ultra-intelligent fuzzy keyword and number matching.
 */
object MatchChannelMapper {

    private val knownMappings = mapOf(
        "bein premium 1" to listOf("bein premium 1", "bein sports 1 premium", "bein sports premium 1", "bein sport 1 premium", "bein premium1", "bein 1 premium"),
        "bein premium 2" to listOf("bein premium 2", "bein sports 2 premium", "bein sports premium 2", "bein sport 2 premium", "bein premium2", "bein 2 premium"),
        "bein premium 3" to listOf("bein premium 3", "bein sports 3 premium", "bein sports premium 3", "bein sport 3 premium", "bein premium3", "bein 3 premium"),
        "bein sports 1" to listOf("bein sports 1", "bein 1", "bein sport 1", "bein sport hd1", "beinsport 1"),
        "bein sports 2" to listOf("bein sports 2", "bein 2", "bein sport 2", "bein sport hd2", "beinsport 2"),
        "bein sports 3" to listOf("bein sports 3", "bein 3", "bein sport 3", "bein sport hd3", "beinsport 3"),
        "bein sports 4" to listOf("bein sports 4", "bein 4", "bein sport 4", "bein sport hd4", "beinsport 4"),
        "bein sports 5" to listOf("bein sports 5", "bein 5", "bein sport 5", "bein sport hd5", "beinsport 5"),
        "bein sports 6" to listOf("bein sports 6", "bein 6", "bein sport 6", "bein sport hd6", "beinsport 6"),
        "bein sports 7" to listOf("bein sports 7", "bein 7", "bein sport 7", "bein sport hd7", "beinsport 7"),
        "bein sports 8" to listOf("bein sports 8", "bein 8", "bein sport 8", "bein sport hd8", "beinsport 8"),
        "bein sports 9" to listOf("bein sports 9", "bein 9", "bein sport 9", "bein sport hd9", "beinsport 9"),
        "bein sports 10" to listOf("bein sports 10", "bein 10", "bein sport 10", "bein sport hd10", "beinsport 10"),
        "ssc extra 1" to listOf("ssc extra 1", "ssc extra1", "ssc 1 extra"),
        "ssc extra 2" to listOf("ssc extra 2", "ssc extra2", "ssc 2 extra"),
        "ssc extra 3" to listOf("ssc extra 3", "ssc extra3", "ssc 3 extra"),
        "ssc 1" to listOf("ssc 1", "ssc1", "ssc sports 1", "ssc 1 hd"),
        "ssc 2" to listOf("ssc 2", "ssc2", "ssc sports 2", "ssc 2 hd"),
        "ssc 3" to listOf("ssc 3", "ssc3", "ssc sports 3", "ssc 3 hd"),
        "ssc 4" to listOf("ssc 4", "ssc4", "ssc sports 4", "ssc 4 hd"),
        "ssc 5" to listOf("ssc 5", "ssc5", "ssc sports 5", "ssc 5 hd"),
        "abu dhabi 1" to listOf("abu dhabi sports 1", "ad sports 1", "abudhabi sports 1", "ad sport 1", "abu dhabi 1"),
        "abu dhabi 2" to listOf("abu dhabi sports 2", "ad sports 2", "abudhabi sports 2", "ad sport 2", "abu dhabi 2"),
        "alkass 1" to listOf("alkass 1", "alkass one", "al kass 1", "kass 1"),
        "alkass 2" to listOf("alkass 2", "alkass two", "al kass 2", "kass 2"),
        "alkass 3" to listOf("alkass 3", "alkass three", "al kass 3", "kass 3"),
        "alkass 4" to listOf("alkass 4", "alkass four", "al kass 4", "kass 4"),
        "alkass 5" to listOf("alkass 5", "alkass five", "al kass 5", "kass 5"),
        "ontime sports 1" to listOf("on time sports 1", "ontime sports 1", "on time 1"),
        "ontime sports 2" to listOf("on time sports 2", "ontime sports 2", "on time 2")
    )

    fun findChannelInSportsGroups(channelName: String?, allChannels: List<Channel>): Channel? {
        if (channelName.isNullOrBlank()) return null
        val normalizedQuery = channelName.trim().lowercase().replace(Regex("""\s+"""), " ")

        val liveChannels = allChannels.filter { it.contentType == "live" }

        // 1. Direct known mappings check
        val mappedList = knownMappings[normalizedQuery]
        if (mappedList != null) {
            for (candidate in mappedList) {
                liveChannels.firstOrNull { it.name.lowercase().contains(candidate) }?.let { return it }
            }
        }

        // 2. Direct exact or substring check
        liveChannels.firstOrNull { it.name.lowercase().trim() == normalizedQuery }?.let { return it }
        liveChannels.firstOrNull { it.name.lowercase().contains(normalizedQuery) || normalizedQuery.contains(it.name.lowercase()) }?.let { return it }

        // 3. Ultra-Intelligent Keyword & Number Match
        val queryWords = normalizedQuery.split(" ", "-", "_", ".").filter { it.isNotBlank() }
        val numbersInQuery = queryWords.filter { it.matches(Regex("""\d+""")) }
        val textsInQuery = queryWords.filter { !it.matches(Regex("""\d+""")) && it.length >= 2 }

        if (textsInQuery.isNotEmpty()) {
            for (ch in liveChannels) {
                val chNameLower = ch.name.lowercase()
                
                // Numbers must match exactly as distinct digits (e.g. "1" matches "HD1" or " 1 " but not "10")
                val numbersOk = numbersInQuery.isEmpty() || numbersInQuery.all { num ->
                    chNameLower.matches(Regex(""".*(^|\D)$num(\D|$).*"""))
                }

                // Important texts must match (like "bein", "ssc", "alkass", "abu")
                val textOk = textsInQuery.any { kw -> chNameLower.contains(kw) }

                if (numbersOk && textOk) {
                    return ch
                }
            }
        }

        // 4. Fallback: match any channel containing the first major text keyword
        val firstKw = textsInQuery.firstOrNull { it.length >= 3 }
        if (firstKw != null) {
            liveChannels.firstOrNull { it.name.lowercase().contains(firstKw) }?.let { return it }
        }

        return null
    }

    fun findChannel(channelName: String?, allChannels: List<Channel>): Channel? {
        return findChannelInSportsGroups(channelName, allChannels)
    }
}
