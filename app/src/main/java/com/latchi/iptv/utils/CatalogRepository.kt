package com.latchi.iptv.utils

import android.content.Context
import com.latchi.iptv.data.local.CatalogDatabase
import com.latchi.iptv.data.local.CatalogItemEntity
import com.latchi.iptv.data.local.CatalogSyncStateEntity
import com.latchi.iptv.data.local.TypeFreshness
import com.latchi.iptv.model.Channel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * 🛡️ Freshness-aware catalog repository.
 *
 * المسار الجديد:
 * 1. عند فتح UI → نُرجع Room فوراً (Offline-First)
 * 2. في الخلفية → نفحص freshness عبر get_catalog_meta
 * 3. إذا قديم → re-sync صامت وتحديث Room
 * 4. إذا طازج → لا شيء
 *
 * الأهم: نقارن per-type revision/hash وليس serverRevision الشامل.
 * هذا يحل مشكلة "السيرفر لا يتغير" و "القنوات قديمة لا تشتغل".
 */
object CatalogRepository {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // ─────────────────────────────────────────────────────────────
    // Types
    // ─────────────────────────────────────────────────────────────
    enum class CatalogType(val scriptType: String, val internalType: String) {
        LIVE("live", "live"),
        BEIN("bein", "live"),
        MOVIES("movies", "movie"),
        SERIES("series", "series");

        companion object {
            fun fromInternal(internal: String): CatalogType? =
                values().firstOrNull { it.internalType.equals(internal, true) }

            fun fromScript(script: String): CatalogType? =
                values().firstOrNull { it.scriptType.equals(script, true) }
        }
    }

    data class FreshnessResult(
        val isFresh: Boolean,
        val reason: String,
        val localRevision: Long,
        val serverRevision: Long,
        val localHash: String,
        val serverHash: String,
        val serverUrl: String,
        val networkReachable: Boolean = true
    )

    // ─────────────────────────────────────────────────────────────
    // Public API (legacy) — للـ backwards compatibility
    // ─────────────────────────────────────────────────────────────
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

    /**
     * Blocking version of hasTypeData - safe to call from non-coroutine contexts
     * (e.g., Runnable, Handler callbacks).
     */
    fun hasTypeDataBlocking(context: Context, profileId: String, contentType: String): Boolean =
        runBlocking { hasTypeData(context, profileId, contentType) }

    fun syncSilently(context: Context, onlyType: String? = null) {
        val profile = SourcePrefs.getActiveProfile(context.applicationContext) ?: return
        scope.launch {
            syncNow(context, profile, onlyType)
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Public API (الجديد) — freshness-aware
    // ─────────────────────────────────────────────────────────────

    /**
     * 🛡️ فحص freshness لنوع معين.
     * يقارن per-type revision/hash المحلي مع server.
     *
     * Returns:
     *  - isFresh=true إذا الـ revision/hash المحلي مطابق للسيرفر
     *  - isFresh=false إذا مختلف أو لا يوجد محلي
     *  - networkReachable=false إذا تعذر الوصول للسيرفر (نعتبره stale لتحفيز re-sync)
     */
    suspend fun isCatalogFresh(
        context: Context,
        profileId: String,
        catalogType: CatalogType
    ): FreshnessResult {
        val dao = CatalogDatabase.get(context).catalogDao()
        val state = dao.getSyncState(profileId)
        val local = state?.freshnessFor(catalogType.internalType)
            ?: TypeFreshness(0L, "", "", 0L)
        val localCount = dao.countByType(profileId, catalogType.internalType)

        // إذا لا توجد بيانات أصلاً → ليست طازجة
        if (localCount == 0 || local.revision == 0L) {
            return FreshnessResult(
                isFresh = false,
                reason = "no_local_data",
                localRevision = local.revision,
                serverRevision = 0L,
                localHash = local.hash,
                serverHash = "",
                serverUrl = ""
            )
        }

        return try {
            val meta = CatalogApiClient.fetchMeta(
                type = catalogType.scriptType,
                revision = local.revision,
                hash = local.hash
            )
            FreshnessResult(
                isFresh = meta.success && meta.notModified,
                reason = when {
                    !meta.success -> "meta_unavailable:${meta.message}"
                    meta.notModified -> "match"
                    meta.revision != local.revision -> "revision_mismatch"
                    !meta.hash.isNullOrBlank() && meta.hash != local.hash -> "hash_mismatch"
                    else -> "stale"
                },
                localRevision = local.revision,
                serverRevision = meta.revision,
                localHash = local.hash,
                serverHash = meta.hash,
                serverUrl = meta.url,
                networkReachable = meta.success
            )
        } catch (e: Exception) {
            // لا يمكن الوصول → لا نُسقط البيانات، نعتبرها stale لتأكيد re-sync لاحقاً
            FreshnessResult(
                isFresh = false,
                reason = "network_error:${e.message}",
                localRevision = local.revision,
                serverRevision = 0L,
                localHash = local.hash,
                serverHash = "",
                serverUrl = "",
                networkReachable = false
            )
        }
    }

    /**
     * 🛡️ Getter ذكي يراعي freshness.
     *
     * السلوك:
     *  1. يُرجع Room فوراً (Offline-First سريع)
     *  2. في الخلفية → يفحص freshness
     *  3. إذا stale → يعيد sync صامت
     *  4. إذا حصل تحديث → يستدعي onUpdated بالقائمة الجديدة
     *
     * @param onUpdated callback اختيارية عند تحديث البيانات بصمت
     */
    fun getChannelsByTypeSmart(
        context: Context,
        profileId: String,
        catalogType: CatalogType,
        onUpdated: ((List<Channel>) -> Unit)? = null
    ): List<Channel> {
        // 1. إرجاع Room فوراً (Fast path)
        val cached = getChannelsByTypeBlocking(context, profileId, catalogType.internalType)

        // 2. فحص freshness في الخلفية
        scope.launch {
            try {
                val appContext = context.applicationContext
                val freshness = isCatalogFresh(appContext, profileId, catalogType)

                if (freshness.isFresh) {
                    return@launch // البيانات طازجة — لا شيء
                }

                // 3. إذا stale → re-sync صامت
                val profile = SourcePrefs.getActiveProfile(appContext) ?: return@launch
                val synced = syncNow(appContext, profile, onlyType = catalogType.internalType)

                if (synced) {
                    val refreshed = getChannelsByType(appContext, profileId, catalogType.internalType)
                    if (refreshed.isNotEmpty()) {
                        onUpdated?.invoke(refreshed)
                    }
                }
            } catch (_: Exception) {
                // Silent failure — لا نُعطل UI
            }
        }

        return cached
    }

    /**
     * 🛡️ مسح كل catalogs لـ profile (يُستدعى عند تغيّر server_revision أو URL).
     * يجبر re-sync في كل واجهة لاحقاً.
     */
    suspend fun invalidateAllCatalogs(context: Context, profileId: String) {
        CatalogDatabase.get(context).catalogDao().deleteProfile(profileId)
    }

    fun invalidateAllCatalogsBlocking(context: Context, profileId: String) {
        runBlocking { invalidateAllCatalogs(context, profileId) }
    }

    /**
     * مسح نوع واحد فقط.
     */
    suspend fun invalidateCatalog(context: Context, profileId: String, contentType: String) {
        CatalogDatabase.get(context).catalogDao().deleteByType(profileId, contentType)
    }

    /**
     * مسح نوع واحد من Room مع محاولة sync فوري.
     */
    fun invalidateAndResyncType(context: Context, profileId: String, catalogType: CatalogType) {
        scope.launch {
            val appContext = context.applicationContext
            invalidateCatalog(appContext, profileId, catalogType.internalType)
            val profile = SourcePrefs.getActiveProfile(appContext) ?: return@launch
            syncNow(appContext, profile, onlyType = catalogType.internalType)
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Sync logic — مُحدّث ليستخدم per-type revision
    // ─────────────────────────────────────────────────────────────
    suspend fun syncNow(context: Context, profile: IptvProfile, onlyType: String? = null): Boolean {
        val appContext = context.applicationContext
        val remoteConfig = RemoteViewConfigPrefs.getFilterConfig(appContext, profile.id)

        if (onlyType != null) {
            if (syncTypeFromApi(appContext, profile, onlyType, remoteConfig)) return true
        } else {
            var any = false
            listOf("live", "movie", "series").forEach { type ->
                if (syncTypeFromApi(appContext, profile, type, remoteConfig)) any = true
            }
            if (any) return true
        }

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
        saveChannels(appContext, profile.id, channels, profile.serverRevision, remoteConfig, replaceTypeFallback = onlyType)
        return true
    }

    fun syncNowBlocking(context: Context, profile: IptvProfile, onlyType: String? = null): Boolean =
        runBlocking { syncNow(context, profile, onlyType) }

    /**
     * حفظ القنوات في Room مع تحديث per-type state.
     */
    suspend fun saveChannels(
        context: Context,
        profileId: String,
        channels: List<Channel>,
        revision: Long,
        remoteConfig: RemoteViewConfigPrefs.FilterConfig? = null,
        replaceAll: Boolean = true,
        replaceTypeFallback: String? = null,
        typeRevision: Long? = null,
        typeHash: String? = null,
        typeUrl: String? = null
    ) {
        val dao = CatalogDatabase.get(context).catalogDao()
        val rc = remoteConfig ?: RemoteViewConfigPrefs.getFilterConfig(context, profileId)
        val now = System.currentTimeMillis()
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
        // تحميل state الحالي لنحافظ على القيم الأخرى
        val currentState = dao.getSyncState(profileId)
        val type = channels.firstOrNull()?.contentType ?: replaceTypeFallback ?: return
        val catalogType = CatalogType.fromInternal(type)

        val updatedState = CatalogSyncStateEntity(
            profileId = profileId,
            serverRevision = revision,
            lastSyncAt = now,
            // Live
            liveRevision = if (catalogType == CatalogType.LIVE) (typeRevision ?: revision) else (currentState?.liveRevision ?: 0L),
            liveHash = if (catalogType == CatalogType.LIVE) (typeHash ?: "") else (currentState?.liveHash ?: ""),
            liveUrl = if (catalogType == CatalogType.LIVE) (typeUrl ?: "") else (currentState?.liveUrl ?: ""),
            liveLastSyncAt = if (catalogType == CatalogType.LIVE) now else (currentState?.liveLastSyncAt ?: 0L),
            // beIN
            beinRevision = if (catalogType == CatalogType.BEIN) (typeRevision ?: revision) else (currentState?.beinRevision ?: 0L),
            beinHash = if (catalogType == CatalogType.BEIN) (typeHash ?: "") else (currentState?.beinHash ?: ""),
            beinUrl = if (catalogType == CatalogType.BEIN) (typeUrl ?: "") else (currentState?.beinUrl ?: ""),
            beinLastSyncAt = if (catalogType == CatalogType.BEIN) now else (currentState?.beinLastSyncAt ?: 0L),
            // Movies
            moviesRevision = if (catalogType == CatalogType.MOVIES) (typeRevision ?: revision) else (currentState?.moviesRevision ?: 0L),
            moviesHash = if (catalogType == CatalogType.MOVIES) (typeHash ?: "") else (currentState?.moviesHash ?: ""),
            moviesUrl = if (catalogType == CatalogType.MOVIES) (typeUrl ?: "") else (currentState?.moviesUrl ?: ""),
            moviesLastSyncAt = if (catalogType == CatalogType.MOVIES) now else (currentState?.moviesLastSyncAt ?: 0L),
            // Series
            seriesRevision = if (catalogType == CatalogType.SERIES) (typeRevision ?: revision) else (currentState?.seriesRevision ?: 0L),
            seriesHash = if (catalogType == CatalogType.SERIES) (typeHash ?: "") else (currentState?.seriesHash ?: ""),
            seriesUrl = if (catalogType == CatalogType.SERIES) (typeUrl ?: "") else (currentState?.seriesUrl ?: ""),
            seriesLastSyncAt = if (catalogType == CatalogType.SERIES) now else (currentState?.seriesLastSyncAt ?: 0L),
            // Prepared URLs (تُحدّث دائماً من config)
            livePreparedUrl = rc.preparedLiveUrl,
            beinPreparedUrl = rc.preparedBeinUrl,
            moviesPreparedUrl = rc.preparedMoviesUrl,
            seriesPreparedUrl = rc.preparedSeriesUrl
        )

        if (replaceAll) {
            dao.replaceProfileData(profileId, entities, updatedState)
        } else if (replaceTypeFallback != null) {
            dao.replaceTypeData(profileId, replaceTypeFallback, entities, updatedState)
        } else {
            dao.replaceTypeData(profileId, type, entities, updatedState)
        }
    }

    fun saveChannelsBlocking(
        context: Context,
        profileId: String,
        channels: List<Channel>,
        revision: Long,
        remoteConfig: RemoteViewConfigPrefs.FilterConfig? = null,
        replaceAll: Boolean = true,
        replaceTypeFallback: String? = null,
        typeRevision: Long? = null,
        typeHash: String? = null,
        typeUrl: String? = null
    ) = runBlocking {
        saveChannels(context, profileId, channels, revision, remoteConfig, replaceAll, replaceTypeFallback, typeRevision, typeHash, typeUrl)
    }

    /**
     * Sync نوع واحد من الـ API مع احترام per-type revision.
     */
    private suspend fun syncTypeFromApi(
        context: Context,
        profile: IptvProfile,
        contentType: String,
        remoteConfig: RemoteViewConfigPrefs.FilterConfig
    ): Boolean {
        val remoteType = when (contentType) {
            "movie" -> "movies"
            else -> contentType
        }
        val catalogType = CatalogType.fromInternal(contentType) ?: return false

        return try {
            val dao = CatalogDatabase.get(context).catalogDao()
            val existingCount = dao.countByType(profile.id, contentType)
            val localState = dao.getSyncState(profile.id)
            val localFreshness = localState?.freshnessFor(contentType)
                ?: TypeFreshness(0L, "", "", 0L)

            // 🛡️ فحص freshness أولاً — نقارن per-type revision/hash وليس serverRevision الشامل
            val meta = CatalogApiClient.fetchMeta(
                type = remoteType,
                revision = localFreshness.revision,
                hash = localFreshness.hash
            )
            if (!meta.success) return false

            // إذا match ولا توجد بيانات أصلاً → لا نعمل شيء
            if (meta.notModified && existingCount > 0) return true

            val channels = if (contentType == "live") {
                val categories = CatalogApiClient.fetchCategories(remoteType)
                if (categories.isNotEmpty()) {
                    categories.flatMap { category -> CatalogApiClient.fetchItemsByCategory(remoteType, category) }
                } else {
                    CatalogApiClient.fetchItemsPaged(remoteType)
                }
            } else {
                CatalogApiClient.fetchItemsPaged(remoteType)
            }
            if (channels.isEmpty()) return false

            // 🛡️ حفظ مع per-type revision/hash/url
            saveChannels(
                context = context,
                profileId = profile.id,
                channels = channels,
                revision = meta.revision.takeIf { it > 0L } ?: profile.serverRevision,
                remoteConfig = remoteConfig,
                replaceAll = false,
                replaceTypeFallback = contentType,
                typeRevision = meta.revision,
                typeHash = meta.hash,
                typeUrl = meta.url
            )
            true
        } catch (_: Exception) {
            false
        }
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
        saveChannels(context, profile.id, channels, profile.serverRevision, replaceAll = false, replaceTypeFallback = contentType)
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
