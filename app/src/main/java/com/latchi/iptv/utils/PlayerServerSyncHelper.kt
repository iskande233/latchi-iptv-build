package com.latchi.iptv.utils

import android.app.Activity
import com.latchi.iptv.screens.GlowingServerUpdateActivity

object PlayerServerSyncHelper {
    fun checkDuringPlayback(activity: Activity) {
        ServerSyncManager.checkForServerUpdate(activity, force = false) { result ->
            if (!result.changed || activity.isFinishing) return@checkForServerUpdate
            // Absolute user requirement: Immediate full-screen glowing execution without asking permission
            GlowingServerUpdateActivity.start(activity, result.serverRevision)
        }
    }
}
