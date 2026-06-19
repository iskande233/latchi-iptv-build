package com.latchi.iptv.utils

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.LocationManager
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import kotlin.concurrent.thread

data class HomePrayerSummary(
    val region: String,
    val nextPrayerName: String,
    val nextPrayerTime: String,
    val loadedFromCache: Boolean = false
)

object HomePrayerSummaryHelper {
    private const val PREFS = "home_prayer_summary_prefs"
    private const val CACHE_TTL_MS = 15 * 60 * 1000L

    fun load(context: Context, callback: (HomePrayerSummary) -> Unit) {
        val appContext = context.applicationContext
        val cached = readCache(appContext)
        if (cached != null) callback(cached.copy(loadedFromCache = true))

        thread(name = "HomePrayerSummary") {
            try {
                val coords = detectCoordinates(appContext)
                val region = detectRegion(appContext, coords.first, coords.second)
                val summary = fetchPrayerSummary(coords.first, coords.second, region)
                writeCache(appContext, summary)
                android.os.Handler(android.os.Looper.getMainLooper()).post { callback(summary) }
            } catch (_: Exception) {
                if (cached == null) {
                    val fallback = HomePrayerSummary("الجزائر", "الفجر", "05:35")
                    android.os.Handler(android.os.Looper.getMainLooper()).post { callback(fallback) }
                }
            }
        }
    }

    private fun readCache(context: Context): HomePrayerSummary? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val ts = prefs.getLong("ts", 0L)
        if (ts == 0L || System.currentTimeMillis() - ts > CACHE_TTL_MS) return null
        val region = prefs.getString("region", "") ?: ""
        val prayer = prefs.getString("prayer", "") ?: ""
        val time = prefs.getString("time", "") ?: ""
        if (region.isBlank() || prayer.isBlank() || time.isBlank()) return null
        return HomePrayerSummary(region, prayer, time)
    }

    private fun writeCache(context: Context, summary: HomePrayerSummary) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong("ts", System.currentTimeMillis())
            .putString("region", summary.region)
            .putString("prayer", summary.nextPrayerName)
            .putString("time", summary.nextPrayerTime)
            .apply()
    }

    private fun detectCoordinates(context: Context): Pair<Double, Double> {
        val hasLocation = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

        if (hasLocation) {
            try {
                val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
                val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
                for (provider in providers) {
                    val loc = runCatching { lm.getLastKnownLocation(provider) }.getOrNull()
                    if (loc != null) return loc.latitude to loc.longitude
                }
            } catch (_: Exception) {}
        }

        return detectCoordinatesByIp()
    }

    private fun detectCoordinatesByIp(): Pair<Double, Double> {
        return try {
            val url = URL("https://ipapi.co/json/")
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 10000
            conn.readTimeout = 10000
            conn.setRequestProperty("User-Agent", "Mozilla/5.0")
            val body = conn.inputStream.bufferedReader().readText()
            val json = JSONObject(body)
            json.optDouble("latitude", 36.7538) to json.optDouble("longitude", 3.0588)
        } catch (_: Exception) {
            36.7538 to 3.0588
        }
    }

    private fun detectRegion(context: Context, lat: Double, lon: Double): String {
        return try {
            val geocoder = Geocoder(context, Locale.getDefault())
            val addresses = geocoder.getFromLocation(lat, lon, 1)
            val a = addresses?.firstOrNull()
            val wilaya = a?.subAdminArea?.takeIf { it.isNotBlank() }
            val city = a?.locality?.takeIf { it.isNotBlank() }
            wilaya ?: city ?: "الجزائر"
        } catch (_: Exception) {
            "الجزائر"
        }
    }

    private fun fetchPrayerSummary(lat: Double, lon: Double, region: String): HomePrayerSummary {
        val timestamp = System.currentTimeMillis() / 1000
        val url = URL("https://api.aladhan.com/v1/timings/$timestamp?latitude=$lat&longitude=$lon&method=3")
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = 10000
        conn.readTimeout = 10000
        val body = conn.inputStream.bufferedReader().readText()
        val json = JSONObject(body)
        val timings = json.getJSONObject("data").getJSONObject("timings")

        val arabicNames = linkedMapOf(
            "Fajr" to "الفجر",
            "Dhuhr" to "الظهر",
            "Asr" to "العصر",
            "Maghrib" to "المغرب",
            "Isha" to "العشاء"
        )

        val now = Calendar.getInstance()
        val currentMinutes = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        var nextPrayerName = "الفجر"
        var nextPrayerTime = sanitizeTime(timings.optString("Fajr", "05:35"))
        var bestDelta = Int.MAX_VALUE

        arabicNames.forEach { (apiName, arabic) ->
            val time = sanitizeTime(timings.optString(apiName, ""))
            val minutes = toMinutes(time)
            if (minutes > currentMinutes && minutes - currentMinutes < bestDelta) {
                bestDelta = minutes - currentMinutes
                nextPrayerName = arabic
                nextPrayerTime = time
            }
        }

        return HomePrayerSummary(region, nextPrayerName, nextPrayerTime)
    }

    private fun sanitizeTime(raw: String): String {
        return raw.substringBefore(" ").trim().take(5).ifBlank { "05:35" }
    }

    private fun toMinutes(time: String): Int {
        return try {
            val parts = time.split(":")
            parts[0].toInt() * 60 + parts[1].toInt()
        } catch (_: Exception) { 0 }
    }
}
