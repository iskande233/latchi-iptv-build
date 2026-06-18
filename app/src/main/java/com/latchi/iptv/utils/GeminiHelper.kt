package com.latchi.iptv.utils

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Gemini AI helper
 * المفتاح يُقرأ من SharedPreferences فقط — لا يُخزن في الكود نهائياً
 * تم تحديثه ليدعم مفتاحين مع خاصية التبديل التلقائي (Fallback)
 */
object GeminiHelper {

    private const val PREFS_NAME = "gemini_prefs"
    private const val KEY_API_1  = "gemini_api_key"
    private const val KEY_API_2  = "gemini_api_key_2"
    private const val MODEL      = "gemini-2.0-flash"

    // ─── حفظ المفاتيح من لوحة التحكم ────────────────────────────
    fun saveKeys(context: Context, key1: String, key2: String = "") {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_API_1, key1.trim())
            .putString(KEY_API_2, key2.trim())
            .apply()
    }

    fun getApiKey1(context: Context): String =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_API_1, "") ?: ""

    fun getApiKey2(context: Context): String =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_API_2, "") ?: ""

    fun hasApiKey(context: Context): Boolean = getApiKey1(context).isNotBlank()

    // ─── استدعاء Gemini (دعم التبديل التلقائي) ───────────────────
    fun ask(context: Context, prompt: String, maxTokens: Int = 200): String? {
        val keys = listOf(getApiKey1(context), getApiKey2(context)).filter { it.isNotBlank() }
        if (keys.isEmpty()) return null

        for (key in keys) {
            try {
                val result = callGeminiApi(key, prompt, maxTokens)
                if (result != null) return result
            } catch (_: Exception) { continue }
        }
        return null
    }

    private fun callGeminiApi(apiKey: String, prompt: String, maxTokens: Int): String? {
        return try {
            val url = URL("https://generativelanguage.googleapis.com/v1beta/models/$MODEL:generateContent?key=$apiKey")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod  = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput       = true
            conn.connectTimeout = 15_000
            conn.readTimeout    = 20_000

            val body = JSONObject().apply {
                put("contents", JSONArray().put(JSONObject().apply {
                    put("parts", JSONArray().put(JSONObject().put("text", prompt)))
                }))
                put("generationConfig", JSONObject().apply {
                    put("maxOutputTokens", maxTokens)
                    put("temperature", 0.2)
                })
            }

            conn.outputStream.write(body.toString().toByteArray(Charsets.UTF_8))

            if (conn.responseCode != HttpURLConnection.HTTP_OK) return null

            val resp = conn.inputStream.bufferedReader().readText()
            JSONObject(resp)
                .optJSONArray("candidates")
                ?.getJSONObject(0)
                ?.optJSONObject("content")
                ?.optJSONArray("parts")
                ?.getJSONObject(0)
                ?.optString("text", "")
                ?.trim()
        } catch (_: Exception) { null }
    }

    // ─── وظيفة خاصة: اكتشاف القناة الناقلة للمباراة ────────────
    fun askChannelForMatch(
        context: Context,
        homeTeam: String,
        awayTeam: String,
        league: String
    ): String? {
        val prompt = "حدد القناة العربية الناقلة الرسمية لهذه المباراة: $homeTeam ضد $awayTeam في الدوري $league. أعطني اسم القناة فقط في كلمة واحدة بدون مقدمات (مثال: beIN Sports 1)."
        return ask(context, prompt, maxTokens = 50)
            ?.replace(".", "")
            ?.replace("،", "")
    }
}
