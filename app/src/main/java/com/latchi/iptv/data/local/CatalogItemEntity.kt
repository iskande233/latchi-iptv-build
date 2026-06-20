package com.latchi.iptv.data.local

import androidx.room.Entity

@Entity(
    tableName = "catalog_items",
    primaryKeys = ["profileId", "streamUrl"]
)
data class CatalogItemEntity(
    val profileId: String,
    val streamUrl: String,
    val name: String,
    val logoUrl: String,
    val category: String,
    val contentType: String,
    val updatedAt: Long = System.currentTimeMillis()
)
