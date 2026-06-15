package com.latchi.iptv.utils

import android.app.Activity
import android.content.Intent
import com.latchi.iptv.MainActivity

object PlayerServerSyncHelper {
    fun checkDuringPlayback(activity: Activity) {
        ServerSyncManager.checkForServerUpdate(activity, force = false) { result ->
            if (!result.changed || activity.isFinishing) return@checkForServerUpdate
            ServerUpdateOverlayHelper.show(activity) {
                val intent = Intent(activity, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                }
                activity.startActivity(intent)
                activity.finish()
            }
        }
    }
}
