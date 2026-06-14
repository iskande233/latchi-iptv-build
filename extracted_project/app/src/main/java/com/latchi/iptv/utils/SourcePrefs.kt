package com.latchi.iptv.utils

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class IptvProfile(
    val id: String,
    val name: String,
    val activationCode: String,
    val m3uUrl: String,
    val expiresAt: String = "",
    val maxDevices: Int = 1
)

object SourcePrefs {
    private const val PREFS_NAME = "iptv_profiles_prefs"
    private const val KEY_PROFILES = "profiles_json"
    private const val KEY_PROFILES_ENC = "profiles_json_enc"
    private const val KEY_ACTIVE_ID = "active_profile_id"

    // 🔐 تشفير AES بمفتاح مشتق من معرف الجهاز — يمنع استخراج روابط M3U من التخزين
    private fun deviceKey(context: Context): javax.crypto.spec.SecretKeySpec {
        val androidId = android.provider.Settings.Secure.getString(
            context.contentResolver, android.provider.Settings.Secure.ANDROID_ID
        ) ?: "latchi_fallback"
        val seed = "LatchiIPTV::$androidId::VIP2026"
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(seed.toByteArray(Charsets.UTF_8))
        return javax.crypto.spec.SecretKeySpec(digest, "AES")
    }

    private fun encrypt(context: Context, plain: String): String {
        val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, deviceKey(context))
        val iv = cipher.iv
        val enc = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        val combined = iv + enc
        return android.util.Base64.encodeToString(combined, android.util.Base64.NO_WRAP)
    }

    private fun decrypt(context: Context, encoded: String): String? {
        return try {
            val combined = android.util.Base64.decode(encoded, android.util.Base64.NO_WRAP)
            val iv = combined.copyOfRange(0, 12)
            val enc = combined.copyOfRange(12, combined.size)
            val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(javax.crypto.Cipher.DECRYPT_MODE, deviceKey(context), javax.crypto.spec.GCMParameterSpec(128, iv))
            String(cipher.doFinal(enc), Charsets.UTF_8)
        } catch (_: Exception) { null }
    }

    fun getProfiles(context: Context): MutableList<IptvProfile> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        // أولاً: المخزن المشفر الجديد، وإلا الترحيل من القديم (نص واضح)
        val encRaw = prefs.getString(KEY_PROFILES_ENC, null)?.let { decrypt(context, it) }
        val raw = encRaw ?: prefs.getString(KEY_PROFILES, "[]") ?: "[]"
        // ترحيل تلقائي: بيانات قديمة غير مشفرة → تشفير فوري وحذف القديمة
        if (encRaw == null && raw != "[]") {
            try {
                prefs.edit()
                    .putString(KEY_PROFILES_ENC, encrypt(context, raw))
                    .remove(KEY_PROFILES)
                    .apply()
            } catch (_: Exception) { }
        }
        val arr = JSONArray(raw)
        val list = mutableListOf<IptvProfile>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            list.add(
                IptvProfile(
                    id = o.optString("id"),
                    name = o.optString("name", "User"),
                    activationCode = o.optString("activationCode", ""),
                    m3uUrl = o.optString("m3uUrl", ""),
                    expiresAt = o.optString("expiresAt", ""),
                    maxDevices = o.optInt("maxDevices", 1)
                )
            )
        }
        return list
    }

    fun getActiveProfile(context: Context): IptvProfile? {
        val activeId = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_ACTIVE_ID, "") ?: ""
        return getProfiles(context).firstOrNull { it.id == activeId }
    }

    fun hasActiveProfile(context: Context): Boolean = getActiveProfile(context) != null
    fun getSourceUrl(context: Context): String = getActiveProfile(context)?.m3uUrl ?: ""

    fun setActiveProfile(context: Context, id: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_ACTIVE_ID, id).apply()
    }

    fun saveActivatedProfile(
        context: Context,
        code: String,
        name: String,
        playlistUrl: String,
        expiresAt: String,
        maxDevices: Int
    ) {
        val id = code.trim()
        val profile = IptvProfile(
            id = id,
            name = name.ifBlank { "User $code" },
            activationCode = code,
            m3uUrl = playlistUrl.trim().replace("&amp;", "&"),
            expiresAt = expiresAt,
            maxDevices = maxDevices
        )
        val profiles = getProfiles(context)
        val index = profiles.indexOfFirst { it.id == id }
        if (index >= 0) profiles[index] = profile else profiles.add(profile)
        saveProfiles(context, profiles)
        setActiveProfile(context, id)
    }

    fun deleteProfile(context: Context, id: String) {
        val profiles = getProfiles(context).filter { it.id != id }.toMutableList()
        saveProfiles(context, profiles)
        ChannelCache.clear(context, id)
        FavoritesPrefs.clearProfile(context, id)
        context.getSharedPreferences("verification_prefs", Context.MODE_PRIVATE).edit().remove("is_verified_$id").apply()
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val activeId = prefs.getString(KEY_ACTIVE_ID, "") ?: ""
        if (activeId == id) prefs.edit().putString(KEY_ACTIVE_ID, profiles.firstOrNull()?.id ?: "").apply()
    }


    fun saveManualM3u(context: Context, name: String, url: String) {
        val id = "manual_" + System.currentTimeMillis().toString()
        val profile = IptvProfile(
            id = id,
            name = name.ifBlank { "Manual M3U" },
            activationCode = "MANUAL",
            m3uUrl = url.trim().replace("&amp;", "&"),
            expiresAt = "Manual",
            maxDevices = 1
        )
        val profiles = getProfiles(context)
        profiles.add(profile)
        saveProfiles(context, profiles)
        setActiveProfile(context, id)
    }

    fun saveManualXtream(context: Context, name: String, serverUrl: String, username: String, password: String) {
        var server = serverUrl.trim()
        if (!server.startsWith("http://") && !server.startsWith("https://")) server = "http://$server"
        while (server.endsWith("/")) server = server.dropLast(1)
        val m3uUrl = "$server/get.php?username=${username.trim()}&password=${password.trim()}&type=m3u_plus&output=ts"
        saveManualM3u(context, name.ifBlank { username.trim().ifBlank { "Xtream User" } }, m3uUrl)
    }

    private fun saveProfiles(context: Context, profiles: List<IptvProfile>) {
        val arr = JSONArray()
        profiles.forEach { p ->
            arr.put(JSONObject().apply {
                put("id", p.id)
                put("name", p.name)
                put("activationCode", p.activationCode)
                put("m3uUrl", p.m3uUrl)
                put("expiresAt", p.expiresAt)
                put("maxDevices", p.maxDevices)
            })
        }
        // 🔐 حفظ مشفر + حذف النسخة القديمة غير المشفرة
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_PROFILES_ENC, encrypt(context, arr.toString()))
            .remove(KEY_PROFILES)
            .apply()
    }
}
