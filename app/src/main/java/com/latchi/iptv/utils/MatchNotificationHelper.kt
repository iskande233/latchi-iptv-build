package com.latchi.iptv.utils

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationCompat
import com.latchi.iptv.R
import kotlin.concurrent.thread

object MatchNotificationHelper {
    private const val CHANNEL_ID = "latchi_matches"
    private const val CHANNEL_NAME = "Match Alerts"
    private var handler: Handler? = null
    private var checkRunnable: Runnable? = null
    private val notifiedIds = mutableSetOf<String>()

    fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Live match notifications"
                enableVibration(true)
            }
            (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        }
    }

    fun startChecking(context: Context) {
        createChannel(context)
        stopChecking()
        handler = Handler(Looper.getMainLooper())
        checkRunnable = object : Runnable {
            override fun run() {
                checkMatches(context)
                handler?.postDelayed(this, 300000) // Check every 5 min
            }
        }
        handler?.postDelayed(checkRunnable!!, 10000) // First check after 10s
    }

    fun stopChecking() {
        handler?.removeCallbacksAndMessages(null)
        handler = null; checkRunnable = null
    }

    private fun checkMatches(context: Context) {
        thread {
            try {
                val matches = YacineTvHelper.fetchMatches()
                val now = System.currentTimeMillis() / 1000
                for (m in matches) {
                    val minsBefore = (m.startTime - now) / 60
                    val minsAfter = (now - m.startTime) / 60
                    val id = "${m.id}"
                    // 15 min before
                    if (minsBefore in 10..20 && notifiedIds.add("${id}_pre")) {
                        notify(context, id.hashCode(), "⚽ ${m.team1Name} vs ${m.team2Name}", "بعد 15 دقيقة على ${m.channelName}", m.champions)
                    }
                    // Match started (first half)
                    if (minsAfter in 0..5 && notifiedIds.add("${id}_1h")) {
                        notify(context, id.hashCode() + 1, "🔴 بدأت المباراة!", "${m.team1Name} vs ${m.team2Name} على ${m.channelName}", m.champions)
                    }
                    // Second half (~50 min after start)
                    if (minsAfter in 48..55 && notifiedIds.add("${id}_2h")) {
                        notify(context, id.hashCode() + 2, "⚽ بداية الشوط الثاني", "${m.team1Name} vs ${m.team2Name}", m.champions)
                    }
                    // Match ended (~95 min after start)
                    if (minsAfter in 93..100 && notifiedIds.add("${id}_ft")) {
                        notify(context, id.hashCode() + 3, "✅ انتهت المباراة", "${m.team1Name} vs ${m.team2Name}", m.champions)
                    }
                }
            } catch (_: Exception) {}
        }
    }

    private fun notify(context: Context, id: Int, title: String, text: String, subText: String) {
        try {
            val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            val pending = PendingIntent.getActivity(context, id, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_tv)
                .setContentTitle(title)
                .setContentText(text)
                .setSubText(subText)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(pending)
                .build()
            (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(id, notification)
        } catch (_: Exception) {}
    }
}
