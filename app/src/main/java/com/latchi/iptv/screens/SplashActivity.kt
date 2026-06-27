package com.latchi.iptv.screens

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.latchi.iptv.MainActivity
import com.latchi.iptv.R
import com.latchi.iptv.utils.AppModeManager
import com.latchi.iptv.utils.LocaleHelper
import com.latchi.iptv.utils.SourcePrefs
import com.latchi.iptv.utils.TvUtils
import com.latchi.iptv.utils.UpdateChecker

class SplashActivity : AppCompatActivity() {

    private val splashHandler = Handler(Looper.getMainLooper())
    private var isNavigationPending = true

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            TvUtils.applyOrientation(this)
            hideSystemUi()
            setContentView(R.layout.activity_splash)
            setupSplashImage()

            // ✅ الانتقال للشاشة التالية أولاً — لا شيء يوقفه أبداً
            if (needsPermissions()) {
                requestAppPermissions()
            } else {
                scheduleNavigation(2500)
            }

            // فحص التحديث في الخلفية بصمت — لا يوقف الانتقال
            try {
                UpdateChecker.checkInBackground(this, object : UpdateChecker.OnUpdateListener {
                    override fun onUpdateAvailable(info: UpdateChecker.UpdateInfo) {
                        try {
                            if (!isFinishing) {
                                UpdateChecker.showUpdateDialog(this@SplashActivity, info)
                            }
                        } catch (_: Throwable) {}
                    }
                })
            } catch (_: Throwable) {}

        } catch (t: Throwable) {
            android.util.Log.e("SplashActivity", "onCreate error", t)
            scheduleNavigation(500)
        }
    }

    private fun setupSplashImage() {
        val splashImage = findViewById<ImageView>(R.id.splashImage)
        val isLandscape = resources.displayMetrics.widthPixels > resources.displayMetrics.heightPixels

        val prefs = getSharedPreferences("splash_prefs", MODE_PRIVATE)
        val lastWasIslamic = prefs.getBoolean("last_islamic", false)
        val useIslamic = !lastWasIslamic
        prefs.edit().putBoolean("last_islamic", useIslamic).apply()

        val splashRes = when {
            useIslamic && isLandscape  -> R.drawable.splash_islamic_tv
            useIslamic && !isLandscape -> R.drawable.splash_islamic_phone
            isLandscape                -> R.drawable.splash_tv
            else                       -> R.drawable.splash_phone
        }
        splashImage.setImageResource(splashRes)
    }

    private fun needsPermissions(): Boolean {
        return requiredFirstRunPermissions().any {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestAppPermissions() {
        val permissions = requiredFirstRunPermissions().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (permissions.isEmpty()) {
            scheduleNavigation(1000)
            return
        }
        requestPermissions(permissions.toTypedArray(), PERMISSION_REQUEST_CODE)
    }

    private fun requiredFirstRunPermissions(): List<String> {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
            permissions.add(Manifest.permission.READ_MEDIA_IMAGES)
            permissions.add(Manifest.permission.READ_MEDIA_VIDEO)
        } else {
            permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }
        permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        permissions.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        return permissions.distinct()
    }

    companion object {
        private const val PERMISSION_REQUEST_CODE = 2026
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            scheduleNavigation(1000)
        }
    }

    private fun scheduleNavigation(delay: Long) {
        splashHandler.postDelayed({
            if (isNavigationPending) {
                isNavigationPending = false
                navigateToNextScreen()
            }
        }, delay)
    }

    private fun navigateToNextScreen() {
        try {
            Thread {
                val config = AppModeManager.fetchConfigBlocking()
                runOnUiThread {
                    try {
                        val intent = if (config.success && config.appMode == AppModeManager.MODE_FREE && config.masterUrl.isNotBlank()) {
                            AppModeManager.ensureFreeProfile(this, config)
                            Intent(this, MainActivity::class.java)
                        } else {
                            val active = SourcePrefs.getActiveProfile(this)
                            if (AppModeManager.isFreeProfile(active) && config.success && config.appMode != AppModeManager.MODE_FREE) {
                                SourcePrefs.deleteProfile(this, active!!.id)
                                Intent(this, UserListActivity::class.java)
                            } else {
                                val verified = active?.let {
                                    getSharedPreferences("verification_prefs", MODE_PRIVATE)
                                        .getBoolean("is_verified_${it.id}", false)
                                } ?: false
                                when {
                                    active == null -> Intent(this, UserListActivity::class.java)
                                    active.activationCode == "MANUAL" || verified || AppModeManager.isFreeProfile(active) -> Intent(this, MainActivity::class.java)
                                    else -> Intent(this, VerificationActivity::class.java)
                                }
                            }
                        }
                        startActivity(intent)
                        finish()
                    } catch (t: Throwable) {
                        android.util.Log.e("SplashActivity", "navigate UI error", t)
                        startActivity(Intent(this, UserListActivity::class.java)); finish()
                    }
                }
            }.start()
        } catch (t: Throwable) {
            android.util.Log.e("SplashActivity", "navigateToNextScreen error", t)
            try { startActivity(Intent(this, UserListActivity::class.java)); finish() } catch (_: Throwable) {}
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemUi()
    }

    private fun hideSystemUi() {
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
    }

    override fun onDestroy() {
        super.onDestroy()
        splashHandler.removeCallbacksAndMessages(null)
    }
}
