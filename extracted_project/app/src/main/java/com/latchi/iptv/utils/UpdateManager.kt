package com.latchi.iptv.utils

import android.app.Activity
import android.app.DownloadManager
import android.content.*
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.view.LayoutInflater
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import com.latchi.iptv.BuildConfig
import com.latchi.iptv.R
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Full Automated Update System (Priority from client 2026-06-14)
 * - Checks remote update.json on GitHub raw
 * - Shows professional VVIP dialog (multi-lang)
 * - Downloads via DownloadManager (with notification progress)
 * - Installs via FileProvider (Android 7-14 safe)
 */
object UpdateManager {

    // === CHANGE THIS to your actual raw GitHub URL for update.json ===
    private const val UPDATE_JSON_URL =
        "https://raw.githubusercontent.com/iskande233/latchi-iptv-build/main/update.json"

    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    data class UpdateInfo(
        val versionCode: Int,
        val versionName: String,
        val apkUrl: String,
        val releaseNotes: String
    )

    fun checkForUpdate(activity: Activity) {
        Thread {
            try {
                val req = Request.Builder().url(UPDATE_JSON_URL).build()
                val response = client.newCall(req).execute()
                if (!response.isSuccessful) return@Thread

                val jsonStr = response.body?.string() ?: return@Thread
                val json = JSONObject(jsonStr)

                val remoteCode = json.optInt("versionCode", 0)
                val remoteName = json.optString("versionName", "")
                val apkUrl = json.optString("apkUrl", "")
                val notes = getLocalizedNotes(activity, json)

                if (remoteCode > BuildConfig.VERSION_CODE && apkUrl.isNotBlank()) {
                    activity.runOnUiThread {
                        if (!activity.isFinishing) {
                            showUpdateDialog(activity, remoteName, notes, apkUrl)
                        }
                    }
                }
            } catch (_: Exception) {
                // Silent fail - no update or network issue
            }
        }.start()
    }

    private fun getLocalizedNotes(context: android.content.Context, json: JSONObject): String {
        val notesObj = json.optJSONObject("releaseNotes") ?: return json.optString("releaseNotes", "")
        val lang = LanguagePrefs.getLanguage(context) // reuse existing helper
        return when (lang) {
            "ar" -> notesObj.optString("ar", notesObj.optString("en", ""))
            "fr" -> notesObj.optString("fr", notesObj.optString("en", ""))
            else -> notesObj.optString("en", "")
        }
    }

    private fun showUpdateDialog(activity: Activity, versionName: String, notes: String, apkUrl: String) {
        val dialogView = LayoutInflater.from(activity).inflate(R.layout.dialog_update, null)

        val title = dialogView.findViewById<TextView>(R.id.updateTitle)
        val message = dialogView.findViewById<TextView>(R.id.updateMessage)
        val progressBar = dialogView.findViewById<ProgressBar>(R.id.updateProgress)
        val btnUpdate = dialogView.findViewById<Button>(R.id.btnUpdateNow)
        val btnLater = dialogView.findViewById<Button>(R.id.btnLater)

        title.text = activity.getString(R.string.update_available) + " ($versionName)"
        message.text = if (notes.isNotBlank()) notes else activity.getString(R.string.update_message_default)

        val dialog = AlertDialog.Builder(activity)
            .setView(dialogView)
            .setCancelable(false)
            .create()

        btnLater.setOnClickListener { dialog.dismiss() }

        btnUpdate.setOnClickListener {
            btnUpdate.isEnabled = false
            progressBar.visibility = android.view.View.VISIBLE
            startDownloadAndInstall(activity, apkUrl, dialog, progressBar, btnUpdate)
        }

        dialog.show()
    }

    private fun startDownloadAndInstall(
        activity: Activity,
        url: String,
        dialog: AlertDialog,
        progressBar: ProgressBar,
        btnUpdate: Button
    ) {
        try {
            val fileName = "latchi-vip-latest.apk"
            val request = DownloadManager.Request(Uri.parse(url)).apply {
                setTitle(activity.getString(R.string.downloading_update))
                setDescription(activity.getString(R.string.downloading_update_desc))
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setAllowedNetworkTypes(DownloadManager.Request.NETWORK_WIFI or DownloadManager.Request.NETWORK_MOBILE)
            }

            val dm = activity.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val downloadId = dm.enqueue(request)

            // Listen for completion
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    if (intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1) == downloadId) {
                        activity.unregisterReceiver(this)
                        dialog.dismiss()

                        val downloadedFile = File(
                            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                            fileName
                        )
                        if (downloadedFile.exists()) {
                            installApk(activity, downloadedFile)
                        }
                    }
                }
            }
            activity.registerReceiver(receiver, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE))

        } catch (e: Exception) {
            btnUpdate.isEnabled = true
            progressBar.visibility = android.view.View.GONE
            Toast.makeText(activity, activity.getString(R.string.update_download_failed), Toast.LENGTH_LONG).show()
        }
    }

    private fun installApk(activity: Activity, apkFile: File) {
        try {
            val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                FileProvider.getUriForFile(
                    activity,
                    "${BuildConfig.APPLICATION_ID}.fileprovider",
                    apkFile
                )
            } else {
                Uri.fromFile(apkFile)
            }

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            activity.startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(activity, activity.getString(R.string.update_install_failed), Toast.LENGTH_LONG).show()
        }
    }
}
