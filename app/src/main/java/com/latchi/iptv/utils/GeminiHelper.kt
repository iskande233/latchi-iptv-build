package com.latchi.iptv.utils

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Gemini AI helper to detect which Arabic channel is broadcasting a match.
 */
object GeminiHelper {
    private const val API_KEY = ""
    private const val MODEL = "gemini-2.0-flash"

    fun askChannelForMatch(homeTeam: String, awayTeam: String, league: String): String? {
        val prompt = "حدد القناة العربية الناقلة الرسمية لهذه المباراة: $homeTeam ضد $awayTeam في الدوري $league. أعطني اسم القناة فقط في كلمة واحدة بدون مقدمات ولا نقاط (مثال: beIN Sports 1 أو SSC 1 أو ON Time Sports أو Abu Dhabi Sports)."

        val url = URL("https://generativelanguage.googleapis.com/v1beta/models/$MODEL:generateContent?key=$API_KEY")
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.setRequestProperty("Content-Type", "application/json")
        connection.doOutput = true
        connection.connectTimeout = 15000
        connection.readTimeout = 15000

        val requestBody = JSONObject().apply {
            put("contents", JSONArray().put(JSONObject().apply {
                put("parts", JSONArray().put(JSONObject().apply {
                    put("text", prompt)
                }))
            }))
            put("generationConfig", JSONObject().apply {
                put("maxOutputTokens", 50)
                put("temperature", 0.1)
            })
        }

        connection.outputStream.write(requestBody.toString().toByteArray())

        val responseCode = connection.responseCode
        if (responseCode != HttpURLConnection.HTTP_OK) {
            val error = connection.errorStream?.bufferedReader()?.readText() ?: "HTTP $responseCode"
            throw Exception("Gemini API error: $error")
        }

        val response = connection.inputStream.bufferedReader().readText()
        val json = JSONObject(response)
        val candidates = json.optJSONArray("candidates") ?: return null
        if (candidates.length() == 0) return null
        val content = candidates.getJSONObject(0).optJSONObject("content") ?: return null
        val parts = content.optJSONArray("parts") ?: return null
        if (parts.length() == 0) return null
        return parts.getJSONObject(0).optString("text", "").trim().replace(".", "").replace("،", "")
    }
}
