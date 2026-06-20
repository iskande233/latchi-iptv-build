package com.latchi.iptv.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

@Dao
interface CatalogDao {

    @Query("SELECT * FROM catalog_items WHERE profileId = :profileId AND contentType = :contentType ORDER BY name COLLATE NOCASE ASC")
    suspend fun getItemsByType(profileId: String, contentType: String): List<CatalogItemEntity>

    @Query("SELECT * FROM catalog_items WHERE profileId = :profileId AND contentType = :contentType AND category = :category ORDER BY name COLLATE NOCASE ASC")
    suspend fun getItemsByCategory(profileId: String, contentType: String, category: String): List<CatalogItemEntity>

    @Query("SELECT DISTINCT category FROM catalog_items WHERE profileId = :profileId AND contentType = :contentType ORDER BY category COLLATE NOCASE ASC")
    suspend fun getCategoriesByType(profileId: String, contentType: String): List<String>

    @Query("SELECT COUNT(*) FROM catalog_items WHERE profileId = :profileId AND contentType = :contentType")
    suspend fun countByType(profileId: String, contentType: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<CatalogItemEntity>)

    @Query("DELETE FROM catalog_items WHERE profileId = :profileId")
    suspend fun deleteProfile(profileId: String)

    @Query("DELETE FROM catalog_items WHERE profileId = :profileId AND contentType = :contentType")
    suspend fun deleteByType(profileId: String, contentType: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSyncState(state: CatalogSyncStateEntity)

    @Query("SELECT * FROM catalog_sync_state WHERE profileId = :profileId LIMIT 1")
    suspend fun getSyncState(profileId: String): CatalogSyncStateEntity?

    @Transaction
    suspend fun replaceProfileData(profileId: String, items: List<CatalogItemEntity>, state: CatalogSyncStateEntity) {
        deleteProfile(profileId)
        if (items.isNotEmpty()) insertAll(items)
        upsertSyncState(state)
    }

    @Transaction
    suspend fun replaceTypeData(profileId: String, contentType: String, items: List<CatalogItemEntity>, state: CatalogSyncStateEntity) {
        deleteByType(profileId, contentType)
        if (items.isNotEmpty()) insertAll(items)
        upsertSyncState(state)
    }
}
