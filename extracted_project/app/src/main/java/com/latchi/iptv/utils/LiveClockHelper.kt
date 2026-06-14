package com.latchi.iptv.utils

import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.*

/**
 * Simple live clock helper (Priority 5).
 * Call startClock(textView) to update every second.
 */
object LiveClockHelper {

    private var clockRunnable: Runnable? = null
    private var clockHandler: android.os.Handler? = null

    fun startClock(textView: TextView, format: String = "HH:mm:ss") {
        stopClock()
        clockHandler = android.os.Handler(android.os.Looper.getMainLooper())
        val sdf = SimpleDateFormat(format, Locale.getDefault())

        clockRunnable = object : Runnable {
            override fun run() {
                textView.text = sdf.format(Date())
                clockHandler?.postDelayed(this, 1000)
            }
        }
        clockHandler?.post(clockRunnable!!)
    }

    fun stopClock() {
        clockRunnable?.let { clockHandler?.removeCallbacks(it) }
        clockRunnable = null
        clockHandler = null
    }
}
