package com.latchi.iptv.utils

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.latchi.iptv.BuildConfig
import com.latchi.iptv.screens.GlowingServerUpdateActivity
import com.latchi.iptv.screens.UpdatePromptActivity
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * 🚀 Live Master Controller 🚀
 *
 * Poller engine that actively checks the Master Google Apps Script endpoint
 * every 15 seconds in the background. If the Admin publishes an App Update or
 * increments the Server Revision, this instantly interrupts whatever the user is doing.
 */
object LiveMasterController {
    private const val TAG = "LiveMasterController"
    private const val MASTER_CHECK_URL =
        "https://script.google.com/macros/s/AKfycbxThygspXN6eB8cDUfY7XavKmhXZfewEUfQqd3vARScZ5y7adterInsbXshNkgPgfiF/exec?action=get_live_master_state"

    private const val PREFS = "latchi_live_master_prefs"
    private const val KEY_LAST_REVISION = "last_applied_server_revision"
    private const val POLL_INTERVAL_MS = 15_000L

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private var isStarted = false
    private val handler = Handler(Looper.getMainLooper())
    private var currentActivity: Activity? = null

    /**
     * Initializes the Master Live Polling engine and Activity lifecycle tracking.
     */
    fun initialize(application: Application) {
        if (isStarted) return
        isStarted = true

        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityResumed(activity: Activity) {
                currentActivity = activity
            }
            override fun onActivityPaused(activity: Activity) {
                if (currentActivity == activity) {
                    currentActivity = null
                }
            }
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })

        startPolling(application)
    }

    private fun startPolling(context: Context) {
        handler.post(object : Runnable {
            override fun run() {
                pollNow(context)
                handler.postDelayed(this, POLL_INTERVAL_MS)
            }
        })
    }

    private fun pollNow(context: Context) {
        val appContext = context.applicationContext
        thread(name = "LatchiLiveMasterPoll") {
            try {
                val activeProfile = try { SourcePrefs.getActiveProfile(appContext) } catch (t: Throwable) { null }
                val ts = System.currentTimeMillis().toString()
                val req = Request.Builder()
                    .url("$MASTER_CHECK_URL&profile_id=${activeProfile?.id ?: ""}&version_code=${BuildConfig.VERSION_CODE}&_t=$ts")
                    .get()
                    .build()

                client.newCall(req).execute().use { response ->
                    if (!response.isSuccessful) return@thread
                    val body = try { response.body?.string() } catch (t: Throwable) { null } ?: return@thread
                    val json = try { JSONObject(body) } catch (t: Throwable) { null } ?: return@thread
                    if (!json.optBoolean("success", false)) return@thread
                    // ...

                    val serverRevision = json.optLong("server_revision", 0L)
                    val updateVersionCode = json.optInt("app_update_version_code", 0)
                    val updateVersionName = json.optString("app_update_version_name", "")
                    val updateApkUrl = json.optString("app_update_apk_url", "")
                    val updateForce = json.optBoolean("app_update_force", false)
                    val updateNotes = json.optString("app_update_notes_ar", "تحديث جديد متوفر لتطبيق LATCHI IPTV.")

                    val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    val localRevision = prefs.getLong(KEY_LAST_REVISION, 0L)

                    // 1. Mandatory App Update Priority
                    if (updateVersionCode > BuildConfig.VERSION_CODE && updateForce && updateApkUrl.isNotBlank()) {
                        Log.d(TAG, "Instant App Update Detected: $updateVersionCode > ${BuildConfig.VERSION_CODE}")
                        onMain {
                            val activity = currentActivity
                            if (activity != null && activity !is UpdatePromptActivity) {
                                UpdatePromptActivity.start(
                                    activity,
                                    updateVersionName,
                                    updateVersionCode,
                                    updateApkUrl,
                                    updateNotes,
                                    true
                                )
                            }
                        }
                        return@thread
                    }

                    // 2. Server Sync Pro Priority
                    // لا نعرض شاشة "تم تحديث السيرفر" بمجرد ملاحظة revision من الـ poller.
                    // أولاً نُجري المزامنة الحقيقية عبر ServerSyncManager، وبعد حفظ البروفايل
                    // ومسح الكاش/Room بنجاح فقط نظهر الرسالة. هذا يمنع الظهور المسبق أو العشوائي.
                    if (serverRevision > localRevision && localRevision > 0L && activeProfile != null) {
                        Log.d(TAG, "Server Revision Detected, waiting for real sync: $serverRevision > $localRevision")
                        ServerSyncManager.checkForServerUpdate(appContext, force = true) { result ->
                            if (result.changed) {
                                prefs.edit().putLong(KEY_LAST_REVISION, result.serverRevision.takeIf { it > 0L } ?: serverRevision).apply()
                                val activity = currentActivity
                                if (activity != null && activity !is GlowingServerUpdateActivity) {
                                    GlowingServerUpdateActivity.start(
                                        activity,
                                        result.serverRevision.takeIf { it > 0L } ?: serverRevision
                                    )
                                }
                            } else {
                                // لا توجد مزامنة فعلية مطبقة، نحدّث آخر revision فقط حتى لا تتكرر رسالة وهمية.
                                prefs.edit().putLong(KEY_LAST_REVISION, serverRevision).apply()
                                Log.d(TAG, "Server revision ignored without applied sync: ${result.message}")
                            }
                        }
                    } else if (localRevision == 0L && serverRevision > 0L) {
                        // First run initialization
                        prefs.edit().putLong(KEY_LAST_REVISION, serverRevision).apply()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Master Poller Error: ${e.message}")
            }
        }
    }

    fun syncCurrentRevision(context: Context, newRevision: Long) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_LAST_REVISION, newRevision)
            .apply()
    }

    private fun onMain(block: () -> Unit) {
        Handler(Looper.getMainLooper()).post(block)
    }
}
