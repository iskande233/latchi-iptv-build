package com.latchi.iptv.utils

import android.content.Context
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * Simple server health checker for Priority 1.
 * Before adopting a new playlist_url, we try a lightweight HEAD or small GET
 * to see if the server is reachable and returns something that looks like M3U.
 */
object ServerHealthChecker {

    data class HealthResult(
        val isOnline: Boolean,
        val message: String = "",
        val responseTimeMs: Long = 0
    )

    fun checkPlaylistHealth(
        context: Context,
        playlistUrl: String,
        onResult: (HealthResult) -> Unit
    ) {
        if (playlistUrl.isBlank()) {
            onResult(HealthResult(false, "empty_url"))
            return
        }

        thread(name = "LatchiHealthCheck") {
            val start = System.currentTimeMillis()
            try {
                val url = URL(playlistUrl)
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "HEAD"
                    connectTimeout = 6000
                    readTimeout = 6000
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "LatchiIPTV/1.1")
                }

                val code = conn.responseCode
                val elapsed = System.currentTimeMillis() - start
                conn.disconnect()

                val online = code in 200..399
                onResult(
                    HealthResult(
                        isOnline = online,
                        message = if (online) "ok" else "http_$code",
                        responseTimeMs = elapsed
                    )
                )
            } catch (e: Exception) {
                val elapsed = System.currentTimeMillis() - start
                onResult(
                    HealthResult(
                        isOnline = false,
                        message = "error: ${e.message?.take(60)}",
                        responseTimeMs = elapsed
                    )
                )
            }
        }
    }
}
