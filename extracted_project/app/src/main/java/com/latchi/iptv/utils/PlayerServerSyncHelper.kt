package com.latchi.iptv.utils

import android.app.Activity
import android.content.Context
import android.content.Intent
import com.latchi.iptv.MainActivity
import com.latchi.iptv.screens.PlayerActivity

/**
 * Safe server sync helper for use inside PlayerActivity (Priority 1).
 * 
 * Call this periodically or on resume from PlayerActivity.
 * If server changed:
 *   - Shows the nice ServerUpdateOverlay for ~3 seconds.
 *   - Then returns user to Home (MainActivity) so they get fresh channels.
 * 
 * IMPORTANT: Never reload the current stream here. We let the user finish watching.
 * The overlay + return is the only side effect.
 */
object PlayerServerSyncHelper {

    fun checkDuringPlayback(
        activity: Activity,
        force: Boolean = false,
        onNoChange: (() -> Unit)? = null
    ) {
        ServerSyncManager.checkForServerUpdate(activity, force = force) { result ->
            if (!activity.isFinishing && result.changed) {
                // Show professional overlay then return to home
                ServerUpdateOverlayHelper.show(activity) {
                    if (!activity.isFinishing) {
                        val intent = Intent(activity, MainActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                        }
                        activity.startActivity(intent)
                        // Do NOT call finish() aggressively — let the player activity close naturally
                        // or user can press back.
                    }
                }
            } else {
                onNoChange?.invoke()
            }
        }
    }
}
