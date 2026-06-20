package com.latchi.iptv.utils

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

object CatalogSyncScheduler {
    private const val PERIODIC_NAME = "latchi_periodic_catalog_sync"

    fun enqueueImmediate(context: Context) {
        val request = OneTimeWorkRequestBuilder<CatalogSyncWorker>().build()
        WorkManager.getInstance(context.applicationContext).enqueue(request)
    }

    fun ensurePeriodic(context: Context) {
        val request = PeriodicWorkRequestBuilder<CatalogSyncWorker>(30, TimeUnit.MINUTES).build()
        WorkManager.getInstance(context.applicationContext)
            .enqueueUniquePeriodicWork(PERIODIC_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
    }
}
