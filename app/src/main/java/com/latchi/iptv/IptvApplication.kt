package com.latchi.iptv

import android.app.Application
import com.latchi.iptv.utils.CatalogSyncScheduler
import com.latchi.iptv.utils.LiveMasterController

class IptvApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        try {
            // تهيئة المحرك السحابي بأمان تام
            LiveMasterController.initialize(this)
        } catch (t: Throwable) {
            android.util.Log.e("IptvApplication", "Critical: LiveMasterController initialization failed", t)
        }
        try {
            CatalogSyncScheduler.ensurePeriodic(this)
        } catch (t: Throwable) {
            android.util.Log.e("IptvApplication", "Catalog periodic sync init failed", t)
        }
    }
}
