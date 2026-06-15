package com.latchi.iptv.utils

object ActivationConfig {
    const val ACTIVATION_API_URL = "https://script.google.com/macros/s/AKfycbzlzc-Ipjq7E9KPjpioJcNSV2OMle7Ma17GruKxqBJxk0k7ktNoM5C3Ko9st7yMS1p1/exec"

    // When Google validates a code successfully, use the locked provider endpoint below.
    // It is intentionally assembled at runtime so it is not shown in the UI or stored in plain settings before activation.
    const val USE_LOCKED_PROVIDER_AFTER_GOOGLE_CODE = false

    fun lockedProviderPlaylistUrl(): String {
        val reversed = listOf(
            "st=tuptuo&sulp_u3m=epyt&As",
            "46EYp3XO=drowssap&CDX4R1",
            "HPAWHYFPC=emanresu?php.teg/0",
            "8:zyx.zreyalpnedlog//:ptth"
        ).joinToString(separator = "")
        return reversed.reversed()
    }

    fun resolvePlaylistUrlFromGoogle(rawPlaylistUrl: String): String {
        val cleaned = rawPlaylistUrl.trim().replace("&amp;", "&")
        return if (USE_LOCKED_PROVIDER_AFTER_GOOGLE_CODE) lockedProviderPlaylistUrl() else cleaned
    }

    /**
     * تقبل أكثر من نوع مصدر من Google Sheet:
     * - M3U: playlist_url / m3u_url / url
     * - Xtream: server + username + password
     * - MAC/Stalker: source_type=mac + portal_url + mac_address
     *
     * ملاحظة: MAC يرجع داخلياً كرابط mac:// باش التطبيق يعرف يحمّل Stalker.
     */
    fun extractPlaylistUrl(json: org.json.JSONObject, fallback: String = ""): String {
        val sourceType = firstString(json, listOf("source_type", "sourceType", "type", "source", "account_type")).lowercase()
        val portal = firstString(json, listOf("portal_url", "portalUrl", "portal", "stalker_portal", "mag_portal"))
        val mac = firstString(json, listOf("mac_address", "macAddress", "mac", "mac_code", "device_mac")).uppercase()

        if ((sourceType == "mac" || sourceType == "stalker" || sourceType == "portal" || sourceType == "mag") && portal.isNotBlank() && mac.isNotBlank()) {
            return "mac://stalker?portal=${urlEncode(portal)}&mac=${urlEncode(mac)}"
        }

        var raw = firstString(json, listOf("playlist_url", "m3u_url", "m3u", "url", "playlist", "link", "server_link"))
        if (raw.isBlank()) {
            val server = firstString(json, listOf("server", "server_url", "host", "portal_host"))
            val username = firstString(json, listOf("username", "user", "login"))
            val password = firstString(json, listOf("password", "pass"))
            if (server.isNotBlank() && username.isNotBlank() && password.isNotBlank()) {
                var cleanServer = server.trim().replace("&amp;", "&")
                if (!cleanServer.startsWith("http://") && !cleanServer.startsWith("https://")) cleanServer = "http://$cleanServer"
                while (cleanServer.endsWith("/")) cleanServer = cleanServer.dropLast(1)
                raw = "$cleanServer/get.php?username=${urlEncode(username)}&password=${urlEncode(password)}&type=m3u_plus&output=ts"
            }
        }
        return resolvePlaylistUrlFromGoogle(raw.ifBlank { fallback })
    }

    fun isMacSource(url: String): Boolean = url.trim().startsWith("mac://", ignoreCase = true)

    fun extractSuccess(json: org.json.JSONObject): Boolean {
        if (json.has("success")) return json.optBoolean("success", false)
        val status = firstString(json, listOf("status", "state", "active", "valid")).lowercase()
        return status == "ok" || status == "success" || status == "active" || status == "true" || status == "1"
    }

    fun extractName(json: org.json.JSONObject, fallback: String = ""): String =
        firstString(json, listOf("name", "user_name", "client", "customer", "title")).ifBlank { fallback }

    fun extractExpiry(json: org.json.JSONObject, fallback: String = ""): String =
        firstString(json, listOf("expires_at", "expiry", "expire", "end_date", "valid_until")).ifBlank { fallback }

    fun extractMaxDevices(json: org.json.JSONObject, fallback: Int = 1): Int {
        val keys = listOf("max_devices", "maxDevices", "devices_limit", "limit")
        for (key in keys) {
            if (!json.has(key)) continue
            val value = json.optString(key, "").trim().toIntOrNull()
            if (value != null && value > 0) return value
            val intValue = json.optInt(key, -1)
            if (intValue > 0) return intValue
        }
        return fallback
    }

    private fun firstString(json: org.json.JSONObject, keys: List<String>): String {
        for (key in keys) {
            val value = json.optString(key, "").trim().replace("&amp;", "&")
            if (value.isNotBlank() && value.lowercase() != "null") return value
        }
        return ""
    }

    private fun urlEncode(value: String): String = java.net.URLEncoder.encode(value.trim(), "UTF-8")
}
