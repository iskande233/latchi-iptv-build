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

/**
 * Reusable professional overlay for errors and warnings.
 * Mirrors the style of ServerUpdateOverlayHelper for consistency (Priority 2/5).
 */
object ErrorOverlayHelper {

    private const val TAG = "latchi_error_overlay"

    fun show(activity: Activity, title: String = "⚠️ خطأ", message: String, onFinished: (() -> Unit)? = null) {
        val root = activity.findViewById<FrameLayout?>(android.R.id.content) ?: run {
            onFinished?.invoke()
            return
        }
        root.findViewWithTag<View>(TAG)?.let { root.removeView(it) }

        val overlay = FrameLayout(activity).apply {
            tag = TAG
            setBackgroundColor(Color.parseColor("#AA000000"))
            isClickable = true
            isFocusable = true
            alpha = 0f
        }

        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(activity, 28), dp(activity, 22), dp(activity, 28), dp(activity, 22))
            setBackgroundResource(R.drawable.bg_success_dialog) // reuse the nice gradient card
            elevation = dp(activity, 16).toFloat()
            scaleX = 0.95f
            scaleY = 0.95f
        }

        card.addView(TextView(activity).apply {
            text = title
            setTextColor(Color.parseColor("#FF6B6B"))
            textSize = 20f
            setTypeface(null, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
        })

        card.addView(TextView(activity).apply {
            text = message
            setTextColor(Color.WHITE)
            textSize = 14f
            gravity = Gravity.CENTER
            setLineSpacing(4f, 1.0f)
            setPadding(0, dp(activity, 10), 0, 0)
        })

        overlay.addView(card, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.CENTER
            marginStart = dp(activity, 24)
            marginEnd = dp(activity, 24)
        })

        root.addView(overlay, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))

        overlay.animate().alpha(1f).setDuration(160).start()
        card.animate().scaleX(1f).scaleY(1f).setDuration(200).start()

        val dismissAfter = 2800L
        Handler(Looper.getMainLooper()).postDelayed({
            overlay.animate().alpha(0f).setDuration(160).withEndAction {
                try { root.removeView(overlay) } catch (_: Exception) {}
                onFinished?.invoke()
            }.start()
        }, dismissAfter)
    }

    private fun dp(activity: Activity, value: Int): Int =
        (value * activity.resources.displayMetrics.density).toInt()
}
