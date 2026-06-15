package com.latchi.iptv.utils

sealed class VoiceCommand {
    data class Play(val channelName: String) : VoiceCommand()
    data class Navigate(val screen: Screen) : VoiceCommand()
    data class Search(val query: String, val contentType: String? = null) : VoiceCommand()
    data class Category(val category: String) : VoiceCommand()
    data class PlayerControl(val target: String, val extra: String = "") : VoiceCommand()
    object Favorites : VoiceCommand()
    object Home : VoiceCommand()
    object Unknown : VoiceCommand()

    enum class Screen {
        SETTINGS, MATCHES, MOVIES, SERIES, LIVE, USERS, PRICING, PRAYER, ABOUT
    }
}
