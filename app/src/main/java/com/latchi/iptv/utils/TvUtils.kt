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
        // 1. فحص UiModeManager — الأدق
        val uiModeManager = context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
        if (uiModeManager?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION) return true

        val pm = context.packageManager

        // 2. فحص Leanback (Android TV رسمي)
        if (pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK)) return true
        if (pm.hasSystemFeature("android.software.leanback")) return true
        if (pm.hasSystemFeature("android.hardware.type.television")) return true

        // 3. بدون شاشة لمس = تلفاز أو TV Box
        if (!pm.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)) return true

        // 4. فحص إضافي: الـ layout-television نشط؟
        // إذا الشاشة عريضة جداً ولا توجد كاميرا أمامية = TV Box محتمل
        val config = context.resources.configuration
        val isLandscapeOnly = config.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
            && config.screenWidthDp > 800
            && !pm.hasSystemFeature(PackageManager.FEATURE_CAMERA_FRONT)
        if (isLandscapeOnly) return true

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
