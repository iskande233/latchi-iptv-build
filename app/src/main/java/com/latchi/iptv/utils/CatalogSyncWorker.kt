package com.latchi.iptv.utils

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

class CatalogSyncWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val profile = SourcePrefs.getActiveProfile(applicationContext) ?: return Result.success()
        return try {
            CatalogRepository.syncNow(applicationContext, profile, onlyType = null)
            Result.success()
        } catch (_: Exception) {
            Result.retry()
        }
    }
}
