package com.latchi.iptv.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * يخزن حالة المزامنة لكل profileId، بما في ذلك:
 *  - serverRevision: رقم المراجعة الشامل (يزداد عند أي تغيير admin)
 *  - per-type revision/hash/url لكل catalog (live, bein, movies, series)
 *    حتى نتمكن من اكتشاف ما إذا كانت بيانات نوع معين قديمة
 *    بدون الاعتماد على الرقم الشامل فقط.
 *
 * مثال:
 *   - الأدمن رفع live JSON جديد → catalog_revision_live يرتفع
 *   - لكن movies قد تبقى كما هي → نكتشف ذلك من moviesRevision المحلي
 */
@Entity(tableName = "catalog_sync_state")
data class CatalogSyncStateEntity(
    @PrimaryKey val profileId: String,

    // الرقم الشامل (يزداد عند أي تغيير)
    val serverRevision: Long,
    val lastSyncAt: Long,

    // ========== Per-type freshness state ==========
    // Live
    val liveRevision: Long = 0L,
    val liveHash: String = "",
    val liveUrl: String = "",
    val liveLastSyncAt: Long = 0L,

    // beIN (catalog مستقل عن live لأنه مُصفّى)
    val beinRevision: Long = 0L,
    val beinHash: String = "",
    val beinUrl: String = "",
    val beinLastSyncAt: Long = 0L,

    // Movies
    val moviesRevision: Long = 0L,
    val moviesHash: String = "",
    val moviesUrl: String = "",
    val moviesLastSyncAt: Long = 0L,

    // Series
    val seriesRevision: Long = 0L,
    val seriesHash: String = "",
    val seriesUrl: String = "",
    val seriesLastSyncAt: Long = 0L,

    // ========== Prepared URLs (مرتبطة بـ config السكريبت) ==========
    val livePreparedUrl: String = "",
    val beinPreparedUrl: String = "",
    val moviesPreparedUrl: String = "",
    val seriesPreparedUrl: String = ""
) {
    /**
     * يُرجع (revision, hash, url, lastSyncAt) لنوع معين.
     * إذا لم يكن النوع معروفاً → جميعها 0/false.
     */
    fun freshnessFor(type: String): TypeFreshness {
        return when (type.lowercase()) {
            "live" -> TypeFreshness(liveRevision, liveHash, liveUrl, liveLastSyncAt)
            "bein", "bein_sports" -> TypeFreshness(beinRevision, beinHash, beinUrl, beinLastSyncAt)
            "movies", "movie" -> TypeFreshness(moviesRevision, moviesHash, moviesUrl, moviesLastSyncAt)
            "series" -> TypeFreshness(seriesRevision, seriesHash, seriesUrl, seriesLastSyncAt)
            else -> TypeFreshness(0L, "", "", 0L)
        }
    }
}

data class TypeFreshness(
    val revision: Long,
    val hash: String,
    val url: String,
    val lastSyncAt: Long
)
