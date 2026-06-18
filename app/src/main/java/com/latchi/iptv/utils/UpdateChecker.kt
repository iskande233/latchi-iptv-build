package com.latchi.iptv.utils

import android.app.Activity
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.widget.Toast
import com.latchi.iptv.BuildConfig
import com.latchi.iptv.screens.UpdatePromptActivity
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * تحديث التطبيق عبر Google Apps Script بدل GitHub raw.
 * Dashboard/Codemagic يحدث Config في Google Script برابط APK النهائي.
 */
object UpdateChecker {
    private const val UPDATE_URL =
        "https://script.google.com/macros/s/AKfycbwoxD7eNi6AVvhw9l_hPzaUkVt1F9U6trUXs28QYuNld_Ip15ZoefcTAdkd4B_DqoGO/exec?action=get_app_update"

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    data class UpdateInfo(
        val versionCode: Int,
        val versionName: String,
        val apkUrl: String,
        val notes: String,
        val forceUpdate: Boolean
    )

    interface OnUpdateListener {
        fun onUpdateAvailable(info: UpdateInfo)
    }

    fun checkInBackground(activity: Activity, listener: OnUpdateListener? = null) {
        Thread {
            try {
                val info = fetchUpdateInfo() ?: return@Thread
                activity.runOnUiThread {
                    if (activity.isFinishing) return@runOnUiThread
                    if (listener != null) {
                        listener.onUpdateAvailable(info)
                    } else {
                        showUpdateDialog(activity, info)
                    }
                }
            } catch (_: Exception) {
                // silent
            }
        }.start()
    }

    fun fetchUpdateInfo(): UpdateInfo? {
        val ts = System.currentTimeMillis().toString()
        val url = "$UPDATE_URL&version_code=${BuildConfig.VERSION_CODE}&_t=$ts"
        val req = Request.Builder().url(url).header("Accept", "application/json").build()
        val body = client.newCall(req).execute().use { res ->
            if (!res.isSuccessful) return null
            res.body?.string() ?: return null
        }
        val json = JSONObject(body)
        val available = json.optBoolean("update_available", false)
        val apkUrl = json.optString("apkUrl", json.optString("url", ""))
        if (!available || apkUrl.isBlank()) return null
        val notesObj = json.optJSONObject("notes") ?: json.optJSONObject("releaseNotes")
        val notes = notesObj?.optString("ar")?.takeIf { it.isNotBlank() }
            ?: json.optString("notes", "تحديث جديد متوفر لتطبيق LATCHI IPTV.")
        return UpdateInfo(
            versionCode = json.optInt("versionCode", 0),
            versionName = json.optString("versionName", ""),
            apkUrl = apkUrl,
            notes = notes,
            forceUpdate = json.optBoolean("forceUpdate", json.optBoolean("force_update", false))
        )
    }

    fun showUpdateDialog(activity: Activity, info: UpdateInfo) {
        UpdatePromptActivity.start(
            activity,
            info.versionName,
            info.versionCode,
            info.apkUrl,
            info.notes,
            info.forceUpdate
        )
    }

    fun downloadAndInstall(activity: Activity, apkUrl: String, versionName: String = "") {
        try {
            val uri = Uri.parse(apkUrl)
            val fileName = "Latchi-IPTV-${versionName.ifBlank { "update" }}.apk".replace("/", "-")
            val request = DownloadManager.Request(uri)
                .setTitle("LATCHI IPTV")
                .setDescription("تحميل التحديث...")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
                .setAllowedOverMetered(true)
                .setAllowedOverRoaming(true)

            val dm = activity.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val id = dm.enqueue(request)
            Toast.makeText(activity, "بدأ تحميل التحديث...", Toast.LENGTH_LONG).show()

            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    val downloadId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
                    if (downloadId != id) return
                    try { context.unregisterReceiver(this) } catch (_: Exception) {}
                    val apkUri = dm.getUriForDownloadedFile(id) ?: return
                    val installIntent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(apkUri, "application/vnd.android.package-archive")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    try { context.startActivity(installIntent) } catch (_: Exception) {
                        Toast.makeText(context, "تم التحميل. افتح ملف APK من التنزيلات.", Toast.LENGTH_LONG).show()
                    }
                }
            }
            val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                activity.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                @Suppress("DEPRECATION") activity.registerReceiver(receiver, filter)
            }
        } catch (e: Exception) {
            try { activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(apkUrl))) } catch (_: Exception) {}
        }
    }
}
