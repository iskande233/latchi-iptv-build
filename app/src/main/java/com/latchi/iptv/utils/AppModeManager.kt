package com.latchi.iptv.utils

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object AppModeManager {
    const val MODE_FREE = "free"
    const val MODE_VIP = "vip"
    const val FREE_CODE = "FREE_MODE"
    const val FREE_PROFILE_ID = "FREE_MODE"

    data class RemoteConfig(
        val success: Boolean,
        val appMode: String = MODE_VIP,
        val masterUrl: String = "",
        val serverRevision: Long = 0L,
        val hiddenCategories: String = "",
        val message: String = ""
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    fun fetchConfigBlocking(): RemoteConfig {
        return try {
            val url = "${ActivationConfig.ACTIVATION_API_URL}?action=get_config&_t=${System.currentTimeMillis()}"
            val req = Request.Builder().url(url).header("Accept", "application/json").get().build()
            val body = client.newCall(req).execute().use { res ->
                if (!res.isSuccessful) return RemoteConfig(false, message = "HTTP ${res.code}")
                res.body?.string().orEmpty()
            }
            val json = JSONObject(body)
            val ok = json.optBoolean("success", json.optString("status") == "success")
            RemoteConfig(
                success = ok,
                appMode = json.optString("app_mode", MODE_VIP).ifBlank { MODE_VIP }.lowercase(),
                masterUrl = json.optString("master_url", json.optString("playlist_url", "")).replace("&amp;", "&").trim(),
                serverRevision = json.optLong("server_revision", 0L),
                hiddenCategories = json.optString("hidden_categories", json.optString("hiddenCategories", "")),
                message = json.optString("message", "")
            )
        } catch (t: Throwable) {
            RemoteConfig(false, message = t.localizedMessage ?: "network_error")
        }
    }

    fun ensureFreeProfile(context: Context, config: RemoteConfig): IptvProfile? {
        if (config.appMode != MODE_FREE || config.masterUrl.isBlank()) return null
        SourcePrefs.saveActivatedProfile(
            context = context.applicationContext,
            code = FREE_CODE,
            name = "LATCHI FREE",
            playlistUrl = config.masterUrl,
            expiresAt = "FREE",
            maxDevices = 1,
            serverRevision = config.serverRevision
        )
        RemoteViewConfigPrefs.saveFromValidationResult(
            context.applicationContext,
            FREE_PROFILE_ID,
            ActivationValidationResult(
                success = true,
                message = "FREE",
                name = "LATCHI FREE",
                playlistUrl = config.masterUrl,
                expiresAt = "FREE",
                maxDevices = 1,
                serverRevision = config.serverRevision,
                hiddenCategories = config.hiddenCategories
            )
        )
        return SourcePrefs.getActiveProfile(context.applicationContext)
    }

    fun isFreeProfile(profile: IptvProfile?): Boolean =
        profile?.id == FREE_PROFILE_ID || profile?.activationCode == FREE_CODE
}
