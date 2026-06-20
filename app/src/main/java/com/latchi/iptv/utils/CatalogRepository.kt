package com.latchi.iptv.utils

import android.content.Context
import com.latchi.iptv.data.local.CatalogDatabase
import com.latchi.iptv.data.local.CatalogItemEntity
import com.latchi.iptv.data.local.CatalogSyncStateEntity
import com.latchi.iptv.model.Channel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

object CatalogRepository {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    suspend fun getChannelsByType(context: Context, profileId: String, contentType: String): List<Channel> {
        return CatalogDatabase.get(context).catalogDao().getItemsByType(profileId, contentType).map { it.toChannel() }
    }

    fun getChannelsByTypeBlocking(context: Context, profileId: String, contentType: String): List<Channel> =
        runBlocking { getChannelsByType(context, profileId, contentType) }

    suspend fun getChannelsByCategory(context: Context, profileId: String, contentType: String, category: String): List<Channel> {
        return CatalogDatabase.get(context).catalogDao().getItemsByCategory(profileId, contentType, category).map { it.toChannel() }
    }

    fun getChannelsByCategoryBlocking(context: Context, profileId: String, contentType: String, category: String): List<Channel> =
        runBlocking { getChannelsByCategory(context, profileId, contentType, category) }

    suspend fun getCategoriesByType(context: Context, profileId: String, contentType: String): List<String> {
        return CatalogDatabase.get(context).catalogDao().getCategoriesByType(profileId, contentType)
    }

    fun getCategoriesByTypeBlocking(context: Context, profileId: String, contentType: String): List<String> =
        runBlocking { getCategoriesByType(context, profileId, contentType) }

    suspend fun hasTypeData(context: Context, profileId: String, contentType: String): Boolean {
        return CatalogDatabase.get(context).catalogDao().countByType(profileId, contentType) > 0
    }

    fun syncSilently(context: Context, onlyType: String? = null) {
        val profile = SourcePrefs.getActiveProfile(context.applicationContext) ?: return
        scope.launch {
            syncNow(context, profile, onlyType)
        }
    }

    suspend fun syncNow(context: Context, profile: IptvProfile, onlyType: String? = null): Boolean {
        val appContext = context.applicationContext
        val dao = CatalogDatabase.get(appContext).catalogDao()
        val remoteConfig = RemoteViewConfigPrefs.getFilterConfig(appContext, profile.id)
        val preparedUsed = when (onlyType) {
            "live" -> syncPreparedType(appContext, profile, "live", remoteConfig.preparedLiveUrl)
            "movie" -> syncPreparedType(appContext, profile, "movie", remoteConfig.preparedMoviesUrl)
            "series" -> syncPreparedType(appContext, profile, "series", remoteConfig.preparedSeriesUrl)
            else -> false
        }
        if (preparedUsed) return true

        val result = awaitRefresh(appContext, profile, onlyLive = onlyType == "live")
        if (result.channels.isEmpty()) return false

        val channels = if (onlyType != null) {
            result.channels.filter { it.contentType == onlyType }
        } else result.channels

        if (channels.isEmpty()) return false
        saveChannels(appContext, profile.id, channels, profile.serverRevision, remoteConfig)
        return true
    }

    suspend fun saveChannels(
        context: Context,
        profileId: String,
        channels: List<Channel>,
        revision: Long,
        remoteConfig: RemoteViewConfigPrefs.FilterConfig? = null,
        replaceAll: Boolean = true
    ) {
        val dao = CatalogDatabase.get(context).catalogDao()
        val rc = remoteConfig ?: RemoteViewConfigPrefs.getFilterConfig(context, profileId)
        val entities = channels.map {
            CatalogItemEntity(
                profileId = profileId,
                streamUrl = it.streamUrl,
                name = it.name,
                logoUrl = it.logoUrl,
                category = it.category,
                contentType = it.contentType
            )
        }
        val state = CatalogSyncStateEntity(
            profileId = profileId,
            serverRevision = revision,
            lastSyncAt = System.currentTimeMillis(),
            livePreparedUrl = rc.preparedLiveUrl,
            beinPreparedUrl = rc.preparedBeinUrl,
            moviesPreparedUrl = rc.preparedMoviesUrl,
            seriesPreparedUrl = rc.preparedSeriesUrl
        )
        if (replaceAll) {
            dao.replaceProfileData(profileId, entities, state)
        } else {
            val type = channels.firstOrNull()?.contentType ?: return
            dao.replaceTypeData(profileId, type, entities, state)
        }
    }

    fun saveChannelsBlocking(
        context: Context,
        profileId: String,
        channels: List<Channel>,
        revision: Long,
        remoteConfig: RemoteViewConfigPrefs.FilterConfig? = null,
        replaceAll: Boolean = true
    ) = runBlocking {
        saveChannels(context, profileId, channels, revision, remoteConfig, replaceAll)
    }

    private suspend fun syncPreparedType(
        context: Context,
        profile: IptvProfile,
        contentType: String,
        url: String
    ): Boolean {
        if (url.isBlank()) return false
        val channels = PreparedCatalogHelper.fetch(url, contentType)
        if (channels.isEmpty()) return false
        saveChannels(context, profile.id, channels, profile.serverRevision, replaceAll = false)
        return true
    }

    private suspend fun awaitRefresh(
        context: Context,
        profile: IptvProfile,
        onlyLive: Boolean
    ): ChannelRefreshResult = suspendCancellableCoroutine { cont ->
        ChannelRefreshHelper.ensureFreshChannels(context, profile, onlyLive = onlyLive) {
            if (cont.isActive) cont.resume(it)
        }
    }

    private fun CatalogItemEntity.toChannel(): Channel =
        Channel(name = name, logoUrl = logoUrl, streamUrl = streamUrl, category = category, contentType = contentType)
}
