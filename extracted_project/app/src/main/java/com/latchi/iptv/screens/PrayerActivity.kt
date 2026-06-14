package com.latchi.iptv.screens

import android.Manifest
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.latchi.iptv.R
import com.latchi.iptv.utils.ErrorOverlayHelper
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import kotlin.concurrent.thread

class PrayerActivity : AppCompatActivity() {
    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(com.latchi.iptv.utils.LocaleHelper.wrap(newBase))
    }

    private lateinit var progressBar: ProgressBar
    private lateinit var dateText: TextView
    private lateinit var locationText: TextView
    private lateinit var backButton: TextView
    private lateinit var detectLocationButton: TextView

    data class PrayerView(
        val rowId: Int,
        val nameId: Int,
        val timeId: Int,
        val apiName: String
    )

    private val prayerViews = listOf(
        PrayerView(R.id.fajrRow, R.id.fajrName, R.id.fajrTime, "Fajr"),
        PrayerView(R.id.dhuhrRow, R.id.dhuhrName, R.id.dhuhrTime, "Dhuhr"),
        PrayerView(R.id.asrRow, R.id.asrName, R.id.asrTime, "Asr"),
        PrayerView(R.id.maghribRow, R.id.maghribName, R.id.maghribTime, "Maghrib"),
        PrayerView(R.id.ishaRow, R.id.ishaName, R.id.ishaTime, "Isha")
    )

    private val arabicNames = mapOf(
        "Fajr" to "الفجر",
        "Dhuhr" to "الظهر",
        "Asr" to "العصر",
        "Maghrib" to "المغرب",
        "Isha" to "العشاء"
    )

    private var detectedCity: String = ""
    private var detectedCountry: String = ""
    private var lat: Double = 36.7538
    private var lon: Double = 3.0588

    companion object {
        private const val LOCATION_PERMISSION_CODE = 1001
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        com.latchi.iptv.utils.TvUtils.applyOrientation(this)
        com.latchi.iptv.utils.ThemeManager.apply(this)
        setContentView(R.layout.activity_prayer)
        com.latchi.iptv.utils.FloatingBackHelper.setup(this)

        progressBar = findViewById(R.id.progressBar)
        dateText = findViewById(R.id.dateText)
        locationText = findViewById(R.id.locationText)
        backButton = findViewById(R.id.backButton)
        detectLocationButton = findViewById(R.id.detectLocationButton)

        backButton.setOnClickListener { finish() }
        detectLocationButton.setOnClickListener { requestLocationPermission() }

        val cal = Calendar.getInstance()
        val hijri = SimpleDateFormat("dd MMMM yyyy", Locale("ar")).format(cal.time)
        dateText.text = hijri

        detectLocationByIP()
    }

    private fun requestLocationPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            detectLocationByGPS()
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), LOCATION_PERMISSION_CODE)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == LOCATION_PERMISSION_CODE && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            detectLocationByGPS()
        } else {
            ErrorOverlayHelper.show(this, "تنبيه", getString(R.string.location_permission_denied))
        }
    }

    private fun detectLocationByGPS() {
        progressBar.visibility = View.VISIBLE
        try {
            val locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
            var location = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            if (location != null) {
                updateLocation(location)
            } else if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                locationManager.requestSingleUpdate(LocationManager.GPS_PROVIDER, object : android.location.LocationListener {
                    override fun onLocationChanged(loc: Location) {
                        updateLocation(loc)
                        locationManager.removeUpdates(this)
                    }
                    override fun onProviderEnabled(provider: String) {}
                    override fun onProviderDisabled(provider: String) {}
                    @Suppress("DEPRECATION")
                    @Deprecated("Deprecated in Java")
                    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
                }, android.os.Looper.getMainLooper())
                // timeout fallback
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    if (progressBar.visibility == View.VISIBLE) {
                        progressBar.visibility = View.GONE
                        ErrorOverlayHelper.show(this, "تنبيه", getString(R.string.location_not_found))
                    }
                }, 10000)
            } else {
                progressBar.visibility = View.GONE
                ErrorOverlayHelper.show(this, "تنبيه", getString(R.string.location_not_found))
            }
        } catch (e: Exception) {
            progressBar.visibility = View.GONE
            ErrorOverlayHelper.show(this, "تنبيه", getString(R.string.location_error))
        }
    }

    private fun updateLocation(location: Location) {
        lat = location.latitude
        lon = location.longitude
        val geocoder = Geocoder(this, Locale.getDefault())
        val addresses = geocoder.getFromLocation(lat, lon, 1)
        if (addresses != null && addresses.isNotEmpty()) {
            detectedCity = addresses[0].locality ?: addresses[0].subAdminArea ?: ""
            detectedCountry = addresses[0].countryName ?: ""
        }
        locationText.text = if (detectedCity.isNotBlank()) "$detectedCity, $detectedCountry" else "${String.format("%.4f", lat)}, ${String.format("%.4f", lon)}"
        loadPrayerTimes(lat, lon)
    }

    private fun detectLocationByIP() {
        progressBar.visibility = View.VISIBLE
        thread {
            try {
                val url = URL("https://ipapi.co/json/")
                val connection = url.openConnection() as HttpURLConnection
                connection.connectTimeout = 15000
                connection.readTimeout = 15000
                connection.setRequestProperty("User-Agent", "Mozilla/5.0")
                val response = connection.inputStream.bufferedReader().readText()
                val json = JSONObject(response)
                detectedCity = json.optString("city", "Algiers")
                detectedCountry = json.optString("country_name", "Algeria")
                lat = json.optDouble("latitude", 36.7538)
                lon = json.optDouble("longitude", 3.0588)
                runOnUiThread {
                    locationText.text = "$detectedCity, $detectedCountry"
                    loadPrayerTimes(lat, lon)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    locationText.text = "Algiers, Algeria"
                    loadPrayerTimes(36.7538, 3.0588)
                }
            }
        }
    }

    private fun loadPrayerTimes(latitude: Double, longitude: Double) {
        thread {
            try {
                val timestamp = System.currentTimeMillis() / 1000
                val url = URL("https://api.aladhan.com/v1/timings/$timestamp?latitude=$latitude&longitude=$longitude&method=3")
                val connection = url.openConnection() as HttpURLConnection
                connection.connectTimeout = 15000
                connection.readTimeout = 15000
                val response = connection.inputStream.bufferedReader().readText()
                val json = JSONObject(response)
                val data = json.getJSONObject("data")
                val timings = data.getJSONObject("timings")
                val meta = data.optJSONObject("meta")
                val timezone = meta?.optString("timezone", "Africa/Algiers") ?: "Africa/Algiers"

                val times = mapOf(
                    "Fajr" to timings.getString("Fajr"),
                    "Dhuhr" to timings.getString("Dhuhr"),
                    "Asr" to timings.getString("Asr"),
                    "Maghrib" to timings.getString("Maghrib"),
                    "Isha" to timings.getString("Isha")
                )

                runOnUiThread {
                    progressBar.visibility = View.GONE
                    displayPrayerTimes(times, timezone)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    progressBar.visibility = View.GONE
                    Toast.makeText(this, getString(R.string.prayer_load_failed), Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun displayPrayerTimes(times: Map<String, String>, timezone: String) {
        val now = Calendar.getInstance()
        val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
        sdf.timeZone = java.util.TimeZone.getTimeZone(timezone)
        val currentTime = sdf.format(now.time)
        val currentMinutes = timeToMinutes(currentTime)

        var nextPrayerName = ""
        var nextPrayerMinutes = Int.MAX_VALUE

        for (pv in prayerViews) {
            val time24 = times[pv.apiName] ?: "--:--"
            val timeMinutes = timeToMinutes(time24)

            val row = findViewById<LinearLayout>(pv.rowId)
            val nameView = findViewById<TextView>(pv.nameId)
            val timeView = findViewById<TextView>(pv.timeId)

            nameView.text = arabicNames[pv.apiName] ?: pv.apiName
            timeView.text = time24

            if (timeMinutes > currentMinutes && (timeMinutes - currentMinutes) < nextPrayerMinutes) {
                nextPrayerMinutes = timeMinutes - currentMinutes
                nextPrayerName = arabicNames[pv.apiName] ?: pv.apiName
                row.setBackgroundResource(R.drawable.bg_button_primary)
            } else {
                row.setBackgroundResource(R.drawable.bg_panel)
            }
        }

        val nextText = findViewById<TextView>(R.id.nextPrayerText)
        if (nextPrayerName.isNotBlank()) {
            nextText.text = "🕌 ${getString(R.string.prayer_next)}: $nextPrayerName (بعد ${nextPrayerMinutes} دقيقة)"
            nextText.visibility = View.VISIBLE
        } else {
            nextText.text = "🕌 ${getString(R.string.prayer_next)}: الفجر (غداً)"
            nextText.visibility = View.VISIBLE
        }
    }

    private fun timeToMinutes(time24: String): Int {
        return try {
            val parts = time24.split(":")
            parts[0].toInt() * 60 + parts[1].toInt()
        } catch (e: Exception) { 0 }
    }

    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent?): Boolean {
        if (com.latchi.iptv.utils.TvFocusHelper.handleKey(this, keyCode, event)) return true
        return super.onKeyDown(keyCode, event)
    }

}
