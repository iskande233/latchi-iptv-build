package com.latchi.iptv.utils

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.lifecycle.Observer
import com.latchi.iptv.model.Channel
import com.latchi.iptv.provider.ChannelsProvider

/**
 * مدير مركزي موحّد لتحديث القنوات.
 * يمنع تشغيل أكثر من fetch لنفس البروفايل في نفس اللحظة،
 * حتى لا تتصارع Home + TV Live + beIN + Matches على نفس السيرفر.
 */
data class ChannelRefreshResult(
    val channels: List<Channel>,
    val refreshedFromServer: Boolean,
    val usedCacheFallback: Boolean,
    val message: String = ""
)

object ChannelRefreshHelper {
    private const val FETCH_TIMEOUT_MS = 45_000L

    private data class PendingCallback(
        val onlyLive: Boolean,
        val callback: (ChannelRefreshResult) -> Unit
    )

    private val mainHandler = Handler(Looper.getMainLooper())
    private val lock = Any()
    private val runningRequests = mutableMapOf<String, MutableList<PendingCallback>>()

    fun ensureFreshChannels(
        context: Context,
        onlyLive: Boolean = false,
        onResult: (ChannelRefreshResult) -> Unit
    ) {
        val active = SourcePrefs.getActiveProfile(context.applicationContext)
        if (active == null) {
            onMain {
                onResult(
                    ChannelRefreshResult(
                        emptyList(),
                        refreshedFromServer = false,
                        usedCacheFallback = false,
                        message = "no_active_profile"
                    )
                )
            }
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
        val cachedAll = ChannelCache.load(appContext, profile.id)
        val pendingRefresh = SourcePrefs.isPendingServerRefresh(appContext, profile.id)
        val cacheRevision = ChannelCache.revision(appContext, profile.id)
        val expectedRevision = profile.serverRevision
        val revisionMismatch = expectedRevision > 0L && cacheRevision != expectedRevision
        val shouldRefresh = pendingRefresh || cachedAll.isEmpty() || revisionMismatch

        if (!shouldRefresh) {
            onMain {
                onResult(
                    ChannelRefreshResult(
                        channels = filterChannels(cachedAll, onlyLive),
                        refreshedFromServer = false,
                        usedCacheFallback = false
                    )
                )
            }
            return
        }

        val shouldStartFetch = synchronized(lock) {
            val callbacks = runningRequests.getOrPut(profile.id) { mutableListOf() }
            callbacks.add(PendingCallback(onlyLive, onResult))
            callbacks.size == 1
        }

        if (shouldStartFetch) {
            fetchAndCache(appContext, profile, cachedAll)
        }
    }

    private fun fetchAndCache(appContext: Context, profile: IptvProfile, cachedFallbackAll: List<Channel>) {
        val provider = ChannelsProvider()
        var finished = false
        lateinit var channelsObserver: Observer<List<Channel>>
        lateinit var errorObserver: Observer<String?>

        fun complete(rawResult: ChannelRefreshResult) {
            if (finished) return
            finished = true
            try { provider.channels.removeObserver(channelsObserver) } catch (_: Exception) {}
            try { provider.error.removeObserver(errorObserver) } catch (_: Exception) {}
            mainHandler.removeCallbacksAndMessages(profile.id)
            deliver(profile.id, rawResult)
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
                        channels = data,
                        refreshedFromServer = true,
                        usedCacheFallback = false
                    )
                )
            }.start()
        }

        errorObserver = Observer { error ->
            if (finished || error.isNullOrBlank()) return@Observer
            val result = if (cachedFallbackAll.isNotEmpty()) {
                ChannelRefreshResult(
                    channels = cachedFallbackAll,
                    refreshedFromServer = false,
                    usedCacheFallback = true,
                    message = error
                )
            } else {
                ChannelRefreshResult(
                    channels = emptyList(),
                    refreshedFromServer = false,
                    usedCacheFallback = false,
                    message = error
                )
            }
            complete(result)
        }

        provider.channels.observeForever(channelsObserver)
        provider.error.observeForever(errorObserver)

        mainHandler.postAtTime({
            if (finished) return@postAtTime
            val result = if (cachedFallbackAll.isNotEmpty()) {
                ChannelRefreshResult(
                    channels = cachedFallbackAll,
                    refreshedFromServer = false,
                    usedCacheFallback = true,
                    message = "fetch_timeout"
                )
            } else {
                ChannelRefreshResult(
                    channels = emptyList(),
                    refreshedFromServer = false,
                    usedCacheFallback = false,
                    message = "fetch_timeout"
                )
            }
            complete(result)
        }, profile.id, SystemClock.uptimeMillis() + FETCH_TIMEOUT_MS)

        provider.fetchM3UFile(profile.m3uUrl)
    }

    private fun deliver(profileId: String, rawResult: ChannelRefreshResult) {
        val callbacks = synchronized(lock) {
            runningRequests.remove(profileId)?.toList().orEmpty()
        }

        callbacks.forEach { pending ->
            onMain {
                pending.callback(
                    rawResult.copy(channels = filterChannels(rawResult.channels, pending.onlyLive))
                )
            }
        }
    }

    private fun filterChannels(channels: List<Channel>, onlyLive: Boolean): List<Channel> {
        return if (onlyLive) channels.filter { it.contentType == "live" } else channels
    }

    private fun onMain(block: () -> Unit) {
        mainHandler.post(block)
    }
}
