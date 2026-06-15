package com.latchi.iptv.utils

import android.app.Activity
import android.app.UiModeManager
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration

/**
 * Helpers to detect Android TV / Google TV and apply the right screen orientation:
 * - TV: do NOT force orientation (avoid recreation loop on cheap boxes).
 *   TVs are naturally landscape; let them stay that way.
 * - Phone: lock portrait so the home UI stays upright.
 */
object TvUtils {

    fun isTv(context: Context): Boolean {
        val uiModeManager = context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
        if (uiModeManager?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION) return true

        val pm = context.packageManager
        if (pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK)) return true
        if (pm.hasSystemFeature("android.software.leanback")) return true
        if (pm.hasSystemFeature("android.hardware.type.television")) return true

        // Extra heuristic: no touchscreen + large landscape screen = very likely a TV box
        if (!pm.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)) return true

        return false
    }

    /** Lock portrait on phones only. TV boxes are left alone to avoid orientation loops. */
    fun applyOrientation(activity: Activity) {
        if (isTv(activity)) {
            // TV: do nothing. Forcing landscape on some TV boxes triggers endless recreation loops.
            return
        }
        // Phone: lock portrait
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
    }
}
