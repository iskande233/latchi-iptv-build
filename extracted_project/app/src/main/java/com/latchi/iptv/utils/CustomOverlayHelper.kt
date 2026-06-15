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

object CustomOverlayHelper {
    private const val TAG = "latchi_custom_overlay"

    fun show(activity: Activity, title: String, message: String, isSuccess: Boolean = true, duration: Long = 2500L, onFinished: (() -> Unit)? = null) {
        val root = activity.findViewById<FrameLayout?>(android.R.id.content) ?: run {
            onFinished?.invoke()
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
            text = if (isSuccess) "✅ $title" else "❌ $title"
            setTextColor(if (isSuccess) Color.parseColor("#FFD700") else Color.parseColor("#FF4C4C"))
            textSize = 22f
            setTypeface(null, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
        })
        card.addView(TextView(activity).apply {
            text = message
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
                onFinished?.invoke()
            }.start()
        }, duration)
    }

    private fun dp(activity: Activity, value: Int): Int = (value * activity.resources.displayMetrics.density).toInt()
}
