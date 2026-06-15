package com.latchi.iptv.utils

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * API-Football v3 helper. Free tier: 100 requests/day.
 */
object ApiFootballHelper {
    private const val API_KEY = ""
    private const val BASE_URL = "https://v3.football.api-sports.io"

    data class MatchFixture(
        val id: Int,
        val homeTeam: String,
        val homeLogo: String,
        val awayTeam: String,
        val awayLogo: String,
        val league: String,
        val leagueLogo: String,
        val statusShort: String,
        val elapsed: Int,
        val dateTime: String,
        val homeScore: Int?,
        val awayScore: Int?
    )

    fun fetchLiveMatches(): List<MatchFixture> {
        val url = URL("$BASE_URL/fixtures?live=all")
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.setRequestProperty("x-apisports-key", API_KEY)
        connection.setRequestProperty("Accept", "application/json")
        connection.setRequestProperty("User-Agent", "Android/IPTV")
        connection.connectTimeout = 15000
        connection.readTimeout = 15000
        connection.doInput = true

        val responseCode = connection.responseCode
        if (responseCode != HttpURLConnection.HTTP_OK) {
            throw Exception("API-Football HTTP $responseCode")
        }

        val response = connection.inputStream.bufferedReader().readText()
        val json = JSONObject(response)
        val fixtures = json.optJSONArray("response") ?: JSONArray()

        val matches = mutableListOf<MatchFixture>()
        for (i in 0 until fixtures.length()) {
            val fix = fixtures.getJSONObject(i)
            val fixture = fix.optJSONObject("fixture") ?: continue
            val teams = fix.optJSONObject("teams") ?: continue
            val goals = fix.optJSONObject("goals")
            val league = fix.optJSONObject("league") ?: continue
            val status = fixture.optJSONObject("status") ?: continue

            val homeObj = teams.optJSONObject("home")
            val awayObj = teams.optJSONObject("away")

            matches.add(MatchFixture(
                id = fixture.optInt("id"),
                homeTeam = homeObj?.optString("name", "?") ?: "?",
                homeLogo = homeObj?.optString("logo", "") ?: "",
                awayTeam = awayObj?.optString("name", "?") ?: "?",
                awayLogo = awayObj?.optString("logo", "") ?: "",
                league = league.optString("name", "?"),
                leagueLogo = league.optString("logo", ""),
                statusShort = status.optString("short", "NS"),
                elapsed = status.optInt("elapsed", 0),
                dateTime = fixture.optString("date", ""),
                homeScore = goals?.optInt("home"),
                awayScore = goals?.optInt("away")
            ))
        }
        return matches
    }

    fun formatMatchTime(dateTime: String, statusShort: String, elapsed: Int): String {
        return when (statusShort) {
            "NS" -> {
                try {
                    val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.getDefault())
                    sdf.timeZone = TimeZone.getTimeZone("UTC")
                    val date = sdf.parse(dateTime) ?: return "?"
                    val local = SimpleDateFormat("HH:mm", Locale.getDefault())
                    local.timeZone = TimeZone.getTimeZone("Africa/Algiers")
                    local.format(date)
                } catch (e: Exception) { "?" }
            }
            "1H" -> "${elapsed}\' الشوط الأول"
            "HT" -> "بين الشوطين"
            "2H" -> "${elapsed}\' الشوط الثاني"
            "ET" -> "وقت إضافي"
            "P" -> "ركلات ترجيح"
            "FT" -> "انتهت المباراة"
            "AET" -> "انتهت (تمديد)"
            "PEN" -> "انتهت (ترجيح)"
            else -> statusShort
        }
    }
}
