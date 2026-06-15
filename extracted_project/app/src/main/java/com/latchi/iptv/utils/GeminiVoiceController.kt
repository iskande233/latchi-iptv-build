package com.latchi.iptv.utils

import android.content.Context

/**
 * نسخة آمنة بدون API Keys.
 * ترجع تحليل محلي سريع بدل استدعاء Gemini خارجي حتى لا ينكسر البناء ولا تنكشف مفاتيح.
 */
object GeminiVoiceController {
    data class VoiceAction(
        val actionType: String,
        val target: String = "",
        val screen: String? = null,
        val extra: String = "",
        val confidence: Double = 0.86
    ) {
        fun isConfident(): Boolean = confidence >= 0.45
    }

    fun processVoiceCommand(
        context: Context,
        text: String,
        onResult: (VoiceAction) -> Unit,
        onError: (String) -> Unit
    ) {
        try {
            val command = VoiceCommandParser.parse(text)
            onResult(command.toVoiceAction(text))
        } catch (e: Exception) {
            onError(e.localizedMessage ?: "تعذر تحليل الأمر الصوتي")
        }
    }

    private fun VoiceCommand.toVoiceAction(originalText: String): VoiceAction {
        return when (this) {
            is VoiceCommand.Play -> VoiceAction("play", target = channelName)
            is VoiceCommand.Navigate -> VoiceAction("navigate", screen = screen.name.lowercase())
            is VoiceCommand.Search -> VoiceAction("search", target = query, extra = contentType ?: "")
            is VoiceCommand.Category -> VoiceAction("category", target = category)
            is VoiceCommand.PlayerControl -> VoiceAction("player_control", target = target, extra = extra)
            VoiceCommand.Favorites -> VoiceAction("favorites", target = "Favorites")
            VoiceCommand.Home -> VoiceAction("home", screen = "home")
            VoiceCommand.Unknown -> VoiceAction("search", target = originalText, confidence = 0.50)
        }
    }
}
