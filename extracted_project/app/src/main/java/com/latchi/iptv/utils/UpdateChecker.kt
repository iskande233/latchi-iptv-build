package com.latchi.iptv.utils

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.appcompat.app.AlertDialog
import com.latchi.iptv.BuildConfig
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Checks a small remote JSON for a newer version and prompts the user.
 *
 * Host this file on your GitHub repo (raw) and keep it updated:
 * {
 *   "versionCode": 11,
 *   "versionName": "1.1.0",
 *   "url": "https://your-download-link/app.apk",
 *   "notes": "What's new..."
 * }
 *
 * Fails silently if the file is missing or offline.
 */
object UpdateChecker {

    // 🔧 CHANGE THIS to your own raw GitHub URL where you host update_info.json
    private const val UPDATE_URL =
        "https://raw.githubusercontent.com/iskande233/Tv-/main/update_info.json"

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    interface OnUpdateListener {
        fun onUpdateAvailable(versionName: String, notes: String, url: String)
    }

    fun checkInBackground(activity: Activity, listener: OnUpdateListener? = null) {
        Thread {
            try {
                val req = Request.Builder().url(UPDATE_URL).build()
                val body = client.newCall(req).execute().use { res ->
                    if (!res.isSuccessful) return@Thread
                    res.body?.string() ?: return@Thread
                }
                val json = JSONObject(body)
                val latestCode = json.optInt("versionCode", 0)
                val latestName = json.optString("versionName", "")
                val url = json.optString("url", "")
                val notes = json.optString("notes", "")

                if (latestCode > BuildConfig.VERSION_CODE && url.isNotBlank()) {
                    activity.runOnUiThread {
                        if (activity.isFinishing) return@runOnUiThread
                        if (listener != null) {
                            listener.onUpdateAvailable(latestName, notes, url)
                        } else {
                            AlertDialog.Builder(activity)
                                .setTitle("${activity.getString(com.latchi.iptv.R.string.update_available)}  ($latestName)")
                                .setMessage(if (notes.isBlank()) "A new version is available." else notes)
                                .setCancelable(false)
                                .setPositiveButton(activity.getString(com.latchi.iptv.R.string.update_now)) { _, _ ->
                                    try {
                                        activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                                    } catch (_: Exception) { }
                                }
                                
                                .show()
                        }
                    }
                }
            } catch (_: Exception) {
                // silent
            }
        }.start()
    }
}
