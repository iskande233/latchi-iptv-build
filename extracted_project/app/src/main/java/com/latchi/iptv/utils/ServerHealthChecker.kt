package com.latchi.iptv.utils

import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

object ServerHealthChecker {
    data class HealthResult(val online: Boolean, val message: String, val responseMs: Long = -1L)

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .retryOnConnectionFailure(true)
        .build()

    fun check(url: String): HealthResult {
        val clean = url.trim().replace("&amp;", "&")
        if (clean.isBlank()) return HealthResult(false, "empty")
        if (StalkerHelper.isMacSource(clean)) return HealthResult(true, "mac_source")
        val start = System.currentTimeMillis()
        return try {
            val req = Request.Builder()
                .url(clean)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 10) LATCHI-IPTV")
                .get()
                .build()
            client.newCall(req).execute().use { res ->
                val ms = System.currentTimeMillis() - start
                val ok = res.isSuccessful || res.code in 300..399 || res.code == 401 || res.code == 403
                HealthResult(ok, "HTTP ${res.code}", ms)
            }
        } catch (e: Exception) {
            HealthResult(false, e.localizedMessage ?: "offline")
        }
    }
}
