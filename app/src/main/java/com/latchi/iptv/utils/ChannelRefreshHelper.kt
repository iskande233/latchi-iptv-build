package com.latchi.iptv.utils

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.Observer
import com.latchi.iptv.model.Channel
import com.latchi.iptv.provider.ChannelsProvider

/**
 * يضمن أن شاشات التلفاز لا تعتمد على كاش قديم بعد تحديث السيرفر.
 * إذا وُجد pending refresh أو كان الكاش فارغاً أو الـ revision لا يطابق الحساب الحالي،
 * نقوم بجلب القنوات من المصدر النشط ثم نحفظها في الكاش قبل إرجاعها للشاشة.
 */
data class ChannelRefreshResult(
    val channels: List<Channel>,
    val refreshedFromServer: Boolean,
    val usedCacheFallback: Boolean,
    val message: String = ""
)

object ChannelRefreshHelper {
    private const val FETCH_TIMEOUT_MS = 45_000L

    fun ensureFreshChannels(
        context: Context,
        onlyLive: Boolean = false,
        onResult: (ChannelRefreshResult) -> Unit
    ) {
        val active = SourcePrefs.getActiveProfile(context.applicationContext)
        if (active == null) {
            onMain { onResult(ChannelRefreshResult(emptyList(), refreshedFromServer = false, usedCacheFallback = false, message = "no_active_profile")) }
            return
        }
        ensureFreshChannels(context, active, onlyLive, onResult)
    }

    fun ensureFreshChannels(
        context: Context,
        profile: IptvProfile,
        onlyLive: Boolean = false,
        onResult: (ChannelRefreshResult) -> Unit
    ) {
        val appContext = context.applicationContext
        val cached = ChannelCache.load(appContext, profile.id)
        val pendingRefresh = SourcePrefs.isPendingServerRefresh(appContext, profile.id)
        val cacheRevision = ChannelCache.revision(appContext, profile.id)
        val expectedRevision = profile.serverRevision
        val revisionMismatch = expectedRevision > 0L && cacheRevision != expectedRevision

        val shouldRefresh = pendingRefresh || cached.isEmpty() || revisionMismatch
        if (!shouldRefresh) {
            onMain {
                onResult(
                    ChannelRefreshResult(
                        channels = filterChannels(cached, onlyLive),
                        refreshedFromServer = false,
                        usedCacheFallback = false
                    )
                )
            }
            return
        }

        fetchAndCache(appContext, profile, cached, onlyLive, onResult)
    }

    private fun fetchAndCache(
        appContext: Context,
        profile: IptvProfile,
        cachedFallback: List<Channel>,
        onlyLive: Boolean,
        onResult: (ChannelRefreshResult) -> Unit
    ) {
        val provider = ChannelsProvider()
        val mainHandler = Handler(Looper.getMainLooper())

        var finished = false
        lateinit var channelsObserver: Observer<List<Channel>>
        lateinit var errorObserver: Observer<String?>

        fun complete(result: ChannelRefreshResult) {
            if (finished) return
            finished = true
            try { provider.channels.removeObserver(channelsObserver) } catch (_: Exception) {}
            try { provider.error.removeObserver(errorObserver) } catch (_: Exception) {}
            mainHandler.removeCallbacksAndMessages(null)
            onMain { onResult(result) }
        }

        channelsObserver = Observer { data ->
            if (finished || data.isNullOrEmpty()) return@Observer
            Thread {
                try {
                    ChannelCache.save(appContext, profile.id, data)
                    val latestRevision = SourcePrefs.getActiveProfile(appContext)
                        ?.takeIf { it.id == profile.id }
                        ?.serverRevision
                        ?: profile.serverRevision
                    ChannelCache.markRevision(appContext, profile.id, latestRevision)
                    SourcePrefs.setPendingServerRefresh(appContext, profile.id, false)
                } catch (_: Exception) {}
                complete(
                    ChannelRefreshResult(
                        channels = filterChannels(data, onlyLive),
                        refreshedFromServer = true,
                        usedCacheFallback = false
                    )
                )
            }.start()
        }

        errorObserver = Observer { error ->
            if (finished || error.isNullOrBlank()) return@Observer
            if (cachedFallback.isNotEmpty()) {
                complete(
                    ChannelRefreshResult(
                        channels = filterChannels(cachedFallback, onlyLive),
                        refreshedFromServer = false,
                        usedCacheFallback = true,
                        message = error
                    )
                )
            } else {
                complete(
                    ChannelRefreshResult(
                        channels = emptyList(),
                        refreshedFromServer = false,
                        usedCacheFallback = false,
                        message = error
                    )
                )
            }
        }

        provider.channels.observeForever(channelsObserver)
        provider.error.observeForever(errorObserver)

        mainHandler.postDelayed({
            if (finished) return@postDelayed
            if (cachedFallback.isNotEmpty()) {
                complete(
                    ChannelRefreshResult(
                        channels = filterChannels(cachedFallback, onlyLive),
                        refreshedFromServer = false,
                        usedCacheFallback = true,
                        message = "fetch_timeout"
                    )
                )
            } else {
                complete(
                    ChannelRefreshResult(
                        channels = emptyList(),
                        refreshedFromServer = false,
                        usedCacheFallback = false,
                        message = "fetch_timeout"
                    )
                )
            }
        }, FETCH_TIMEOUT_MS)

        provider.fetchM3UFile(profile.m3uUrl)
    }

    private fun filterChannels(channels: List<Channel>, onlyLive: Boolean): List<Channel> {
        return if (onlyLive) channels.filter { it.contentType == "live" } else channels
    }

    private fun onMain(block: () -> Unit) {
        Handler(Looper.getMainLooper()).post(block)
    }
}
