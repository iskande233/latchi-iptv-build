package com.latchi.iptv.utils

import android.app.Activity
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.latchi.iptv.R

object FloatingBackHelper {
    private const val OVERLAY_TAG = "latchi_professional_floating_back_overlay"
    private const val IDLE_ALPHA = 0.30f
    private const val ACTIVE_ALPHA = 0.72f
    private const val HIDE_DELAY_MS = 3000L

    fun setup(activity: Activity) {
        try {
            val regularBack = activity.findViewById<View?>(R.id.backButton)
            if (regularBack != null) {
                styleRegularBackButton(activity, regularBack)
                TvFocusHelper.setup(activity)
                return
            }

            activity.findViewById<View?>(R.id.fabBackTopRight)?.let { top ->
                styleFloatingButton(activity, top)
                top.setOnClickListener { goBack(activity) }
            }

            val bottomFab = activity.findViewById<View?>(R.id.fabBackBottomRight)
            if (bottomFab != null) {
                styleFloatingButton(activity, bottomFab)
                bottomFab.setOnClickListener { goBack(activity) }
            } else {
                addProfessionalBottomBack(activity)
            }
            TvFocusHelper.setup(activity)
        } catch (_: Exception) {}
    }

    private fun goBack(activity: Activity) {
        try {
            if (!activity.isFinishing) activity.onBackPressed()
        } catch (_: Exception) {
            if (!activity.isFinishing) activity.finish()
        }
    }

    private fun styleRegularBackButton(activity: Activity, view: View) {
        view.setOnClickListener { goBack(activity) }
        view.isClickable = true
        view.isFocusable = true
        view.setBackgroundResource(R.drawable.bg_back_professional)
        view.elevation = dp(activity, 10).toFloat()
        view.setOnFocusChangeListener { v, hasFocus -> animateFocus(v, hasFocus) }

        if (view is TextView) {
            view.text = "  رجوع"
            view.setTextColor(Color.parseColor("#FFD700"))
            view.textSize = 15f
            view.gravity = Gravity.CENTER
            view.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_back_professional, 0, 0, 0)
            view.compoundDrawablePadding = dp(activity, 6)
        }
    }

    private fun styleFloatingButton(activity: Activity, view: View) {
        view.setBackgroundResource(R.drawable.bg_back_professional)
        view.elevation = dp(activity, 14).toFloat()
        view.alpha = ACTIVE_ALPHA
        view.isClickable = true
        view.isFocusable = true
        val handler = Handler(Looper.getMainLooper())
        val hideRunnable = Runnable { view.animate().alpha(IDLE_ALPHA).setDuration(280).start() }
        fun showTemporarily() {
            view.animate().alpha(ACTIVE_ALPHA).setDuration(180).start()
            handler.removeCallbacks(hideRunnable)
            handler.postDelayed(hideRunnable, HIDE_DELAY_MS)
        }
        view.setOnFocusChangeListener { v, hasFocus ->
            animateFocus(v, hasFocus)
            showTemporarily()
        }
        view.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN || event.action == MotionEvent.ACTION_MOVE) showTemporarily()
            false
        }
        showTemporarily()
    }

    private fun addProfessionalBottomBack(activity: Activity) {
        val root = activity.findViewById<FrameLayout?>(android.R.id.content) ?: return
        if (root.findViewWithTag<View>(OVERLAY_TAG) != null) return

        val button = LinearLayout(activity).apply {
            tag = OVERLAY_TAG
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            setBackgroundResource(R.drawable.bg_back_professional)
            elevation = dp(activity, 14).toFloat()
            alpha = ACTIVE_ALPHA
            setPadding(dp(activity, 10), 0, dp(activity, 10), 0)
            setOnClickListener { goBack(activity) }

            addView(ImageView(activity).apply {
                setImageResource(R.drawable.ic_back_professional)
                contentDescription = "رجوع"
            }, LinearLayout.LayoutParams(dp(activity, 28), dp(activity, 28)))

            addView(TextView(activity).apply {
                text = "رجوع"
                setTextColor(Color.parseColor("#FFD700"))
                textSize = 13f
                setTypeface(null, android.graphics.Typeface.BOLD)
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(activity, 6) })
        }
        styleFloatingButton(activity, button)

        val params = FrameLayout.LayoutParams(dp(activity, 92), dp(activity, 48)).apply {
            gravity = Gravity.TOP or Gravity.END
            marginEnd = dp(activity, 24)
            topMargin = dp(activity, 24)
        }
        root.addView(button, params)
    }

    private fun animateFocus(v: View, hasFocus: Boolean) {
        v.animate()
            .scaleX(if (hasFocus) 1.10f else 1f)
            .scaleY(if (hasFocus) 1.10f else 1f)
            .setDuration(120)
            .start()
    }

    private fun dp(activity: Activity, value: Int): Int = (value * activity.resources.displayMetrics.density).toInt()
}
