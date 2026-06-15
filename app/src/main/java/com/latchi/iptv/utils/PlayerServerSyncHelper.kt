package com.latchi.iptv.utils

import android.app.Activity

object PlayerServerSyncHelper {
    fun checkDuringPlayback(activity: Activity) {
        ServerSyncManager.checkForServerUpdate(activity, force = false) { result ->
            if (!result.changed || activity.isFinishing) return@checkForServerUpdate
            ServerUpdateInteractiveOverlayHelper.show(activity, result.profileId)
        }
    }
}
