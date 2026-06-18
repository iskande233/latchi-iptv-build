package com.latchi.iptv

import android.app.Application
import com.latchi.iptv.utils.LiveMasterController

class IptvApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        try {
            LiveMasterController.initialize(this)
        } catch (_: Exception) {}
    }
}
