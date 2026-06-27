package com.latchi.iptv.utils

import android.content.Context
import android.provider.Settings
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL

data class ActivationValidationResult(
    val success: Boolean,
    val message: String,
    val name: String = "",
    val playlistUrl: String = "",
    val expiresAt: String = "",
    val maxDevices: Int = 1,
    val serverRevision: Long = 0L,
    val hiddenCategories: String = "",
    val beinKeywords: String = "",
    val beinMaxKeywords: String = "",
    val alwanKeywords: String = "",
    val preparedLiveUrl: String = "",
    val preparedBeinUrl: String = "",
    val preparedMoviesUrl: String = "",
    val preparedSeriesUrl: String = "",
    val customNamesJson: String = "",
    val customOrderJson: String = ""
)

object ActivationValidator {
    fun validate(context: Context, profile: IptvProfile): ActivationValidationResult {
        if (profile.activationCode == "MANUAL") {
            return ActivationValidationResult(true, "OK", profile.name, profile.m3uUrl, profile.expiresAt, profile.maxDevices, profile.serverRevision)
        }
        if (AppModeManager.isFreeProfile(profile)) {
            val cfg = AppModeManager.fetchConfigBlocking()
            if (cfg.success && cfg.appMode == AppModeManager.MODE_FREE && cfg.masterUrl.isNotBlank()) {
                return ActivationValidationResult(true, "FREE", "LATCHI FREE", cfg.masterUrl, "FREE", 1, cfg.serverRevision, hiddenCategories = cfg.hiddenCategories)
            }
            return ActivationValidationResult(false, "Free mode disabled")
        }
        return validateCode(context, profile.activationCode, profile)
    }

    fun validateCode(context: Context, activationCode: String, existingProfile: IptvProfile? = null): ActivationValidationResult {
        val apiUrl = ActivationConfig.ACTIVATION_API_URL
        if (apiUrl.contains("PASTE_GOOGLE")) {
            return ActivationValidationResult(false, "Activation API not configured")
        }

        val deviceId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown"
        val code = URLEncoder.encode(activationCode.trim(), "UTF-8")
        val device = URLEncoder.encode(deviceId, "UTF-8")
        val ts = System.currentTimeMillis().toString()
        val requestUrl = "$apiUrl?action=verify_code&code=$code&device_id=$device&_t=$ts"

        val connection = URL(requestUrl).openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = 15000
        connection.readTimeout = 25000
        connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36")
        connection.setRequestProperty("Accept", "application/json,text/plain,*/*")

        val responseCode = connection.responseCode
        val stream = if (responseCode in 200..299) connection.inputStream else connection.errorStream
        val response = stream?.bufferedReader()?.use(BufferedReader::readText).orEmpty()
        if (responseCode !in 200..299) {
            throw IllegalStateException("Activation HTTP $responseCode: ${response.take(160)}")
        }
        if (response.isBlank()) {
            throw IllegalStateException("Empty activation response")
        }

        val json = try {
            JSONObject(response)
        } catch (_: Exception) {
            throw IllegalStateException("Invalid activation JSON: ${response.take(160)}")
        }

        val success = ActivationConfig.extractSuccess(json)
        if (!success) {
            return ActivationValidationResult(false, json.optString("message", json.optString("error", "Activation failed")))
        }

        val playlist = ActivationConfig.extractPlaylistUrl(json, existingProfile?.m3uUrl ?: "")
        if (playlist.isBlank()) {
            return ActivationValidationResult(false, "Empty source from Google Sheet")
        }

        return ActivationValidationResult(
            success = true,
            message = "OK",
            name = ActivationConfig.extractName(json, existingProfile?.name ?: "User ${activationCode.trim()}"),
            playlistUrl = playlist,
            expiresAt = ActivationConfig.extractExpiry(json, existingProfile?.expiresAt ?: ""),
            maxDevices = ActivationConfig.extractMaxDevices(json, existingProfile?.maxDevices ?: 1),
            serverRevision = ActivationConfig.extractRevision(json, existingProfile?.serverRevision ?: 0L),
            hiddenCategories = json.optString("hidden_categories", json.optString("hiddenCategories", "")),
            beinKeywords = json.optString("bein_keywords", json.optString("beinKeywords", "")),
            beinMaxKeywords = json.optString("bein_max_keywords", json.optString("beinMaxKeywords", "")),
            alwanKeywords = json.optString("alwan_keywords", json.optString("alwanKeywords", "")),
            preparedLiveUrl = json.optString("prepared_live_url", json.optString("preparedLiveUrl", "")),
            preparedBeinUrl = json.optString("prepared_bein_url", json.optString("preparedBeinUrl", "")),
            preparedMoviesUrl = json.optString("prepared_movies_url", json.optString("preparedMoviesUrl", "")),
            preparedSeriesUrl = json.optString("prepared_series_url", json.optString("preparedSeriesUrl", "")),
            customNamesJson = json.optString("custom_names", json.optString("customNames", "")),
            customOrderJson = json.optString("custom_order", json.optString("customOrder", ""))
        )
    }
}
