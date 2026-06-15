package com.latchi.iptv.utils

import android.app.Activity
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.latchi.iptv.R

object ServerUpdateOverlayHelper {
    private const val TAG = "latchi_server_update_overlay"

    fun show(activity: Activity, onFinished: () -> Unit) {
        val root = activity.findViewById<FrameLayout?>(android.R.id.content) ?: run {
            onFinished()
            return
        }
        root.findViewWithTag<View>(TAG)?.let { root.removeView(it) }

        val overlay = FrameLayout(activity).apply {
            tag = TAG
            setBackgroundColor(Color.parseColor("#CC000000"))
            isClickable = true
            isFocusable = true
            alpha = 0f
        }

        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(activity, 28), dp(activity, 24), dp(activity, 28), dp(activity, 24))
            setBackgroundResource(R.drawable.bg_success_dialog)
            elevation = dp(activity, 18).toFloat()
            scaleX = 0.94f
            scaleY = 0.94f
        }

        card.addView(TextView(activity).apply {
            text = "✅ تم تحديث السيرفر"
            setTextColor(Color.parseColor("#FFD700"))
            textSize = 22f
            setTypeface(null, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
        })
        card.addView(TextView(activity).apply {
            text = "تم ربط التطبيق بالسيرفر الجديد\nجاري تجهيز القنوات الجديدة..."
            setTextColor(Color.WHITE)
            textSize = 15f
            gravity = Gravity.CENTER
            setLineSpacing(6f, 1.05f)
            setPadding(0, dp(activity, 14), 0, 0)
        })

        overlay.addView(card, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.CENTER
            marginStart = dp(activity, 28)
            marginEnd = dp(activity, 28)
        })

        root.addView(overlay, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))

        overlay.animate().alpha(1f).setDuration(180).start()
        card.animate().scaleX(1f).scaleY(1f).setDuration(220).start()
        Handler(Looper.getMainLooper()).postDelayed({
            overlay.animate().alpha(0f).setDuration(180).withEndAction {
                try { root.removeView(overlay) } catch (_: Exception) {}
                onFinished()
            }.start()
        }, 2600L)
    }

    private fun dp(activity: Activity, value: Int): Int = (value * activity.resources.displayMetrics.density).toInt()
}
