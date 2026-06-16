package com.latchi.iptv.utils

import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException

object AntiBlockInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        try {
            return chain.proceed(request)
        } catch (e: IOException) {
            val urlStr = request.url.toString()
            if (urlStr.contains("std.visiolinks.com")) {
                val newUrlStr = urlStr.replace("std.visiolinks.com", "45.159.92.78")
                val newRequest = request.newBuilder()
                    .url(newUrlStr)
                    .header("Host", "std.visiolinks.com")
                    .build()
                return chain.proceed(newRequest)
            }
            throw e
        }
    }
}
