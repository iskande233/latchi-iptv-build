package com.latchi.iptv.utils

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.latchi.iptv.MainActivity
import com.latchi.iptv.R

object ServerUpdateInteractiveOverlayHelper {
    private const val TAG = "latchi_interactive_server_update"

    fun show(activity: Activity, profileId: String) {
        val root = activity.findViewById<FrameLayout?>(android.R.id.content) ?: return
        root.findViewWithTag<View>(TAG)?.let { root.removeView(it) }

        val overlay = FrameLayout(activity).apply {
            tag = TAG
            setBackgroundColor(Color.parseColor("#E6000000"))
            isClickable = true
            isFocusable = true
            alpha = 0f
        }

        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(activity, 32), dp(activity, 28), dp(activity, 32), dp(activity, 28))
            setBackgroundResource(R.drawable.bg_success_dialog)
            elevation = dp(activity, 20).toFloat()
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.CENTER
                marginStart = dp(activity, 36)
                marginEnd = dp(activity, 36)
            }
        }

        card.addView(TextView(activity).apply {
            text = "✅ تم تحديث السيرفر / Server Updated"
            setTextColor(Color.parseColor("#FFD700"))
            textSize = 22f
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
        })

        card.addView(TextView(activity).apply {
            text = "تم تحديث قائمة القنوات والسيرفر بنجاح.\nThe channel server has been updated successfully.\n\nيمكنك تطبيق التحديث الآن أو متابعة المشاهدة.\nYou can apply now or continue watching."
            setTextColor(Color.WHITE)
            textSize = 16f
            gravity = Gravity.CENTER
            setLineSpacing(6f, 1.1f)
            setPadding(0, dp(activity, 16), 0, dp(activity, 24))
        })

        val btnContainer = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val btnApply = Button(activity).apply {
            text = "تطبيق الآن / Apply Now"
            setTextColor(Color.WHITE)
            setBackgroundResource(R.drawable.bg_btn_gold)
            textSize = 15f
            setTypeface(null, Typeface.BOLD)
            isFocusable = true
            isFocusableInTouchMode = false
            setPadding(dp(activity, 20), dp(activity, 12), dp(activity, 20), dp(activity, 12))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginEnd = dp(activity, 16)
            }
        }

        val btnLater = Button(activity).apply {
            text = "لاحقاً / Later"
            setTextColor(Color.WHITE)
            setBackgroundResource(R.drawable.bg_purple_panel)
            textSize = 15f
            setTypeface(null, Typeface.BOLD)
            isFocusable = true
            isFocusableInTouchMode = false
            setPadding(dp(activity, 20), dp(activity, 12), dp(activity, 20), dp(activity, 12))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        btnApply.setOnClickListener {
            root.removeView(overlay)
            SourcePrefs.setPendingServerRefresh(activity, profileId, false)
            val intent = Intent(activity, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }
            activity.startActivity(intent)
            activity.finish()
        }

        btnLater.setOnClickListener {
            SourcePrefs.setPendingServerRefresh(activity, profileId, true)
            root.removeView(overlay)
        }

        btnContainer.addView(btnApply)
        btnContainer.addView(btnLater)
        card.addView(btnContainer)
        overlay.addView(card)
        root.addView(overlay)

        overlay.animate().alpha(1f).setDuration(200).start()

        // Give DPAD focus to the Apply button
        btnApply.postDelayed({
            btnApply.requestFocus()
        }, 100L)
    }

    private fun dp(activity: Activity, value: Int): Int = (value * activity.resources.displayMetrics.density).toInt()
}
