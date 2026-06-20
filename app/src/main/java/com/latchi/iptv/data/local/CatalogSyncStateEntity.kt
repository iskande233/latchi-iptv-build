package com.latchi.iptv.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "catalog_sync_state")
data class CatalogSyncStateEntity(
    @PrimaryKey val profileId: String,
    val serverRevision: Long,
    val lastSyncAt: Long,
    val livePreparedUrl: String = "",
    val beinPreparedUrl: String = "",
    val moviesPreparedUrl: String = "",
    val seriesPreparedUrl: String = ""
)
