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
import com.latchi.iptv.utils.LocaleHelper
import com.latchi.iptv.utils.SourcePrefs
import com.latchi.iptv.utils.TvUtils
import com.latchi.iptv.utils.UpdateChecker

class SplashActivity : AppCompatActivity() {

    private val splashHandler = Handler(Looper.getMainLooper())
    private var isNavigationPending = true
    private var isUpdating = false

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
        } catch (_: Exception) {}

        // 👑 انتقال ملكي صاروخي مباشر ومضمون 100% بدون أي حوار صلاحيات أو حظر تحديثات
        scheduleNavigation(150L)
    }

    private fun setupSplashImage() {
        val splashImage = findViewById<ImageView>(R.id.splashImage)
        val isLandscape = resources.displayMetrics.widthPixels > resources.displayMetrics.heightPixels

        val prefs = getSharedPreferences("splash_prefs", MODE_PRIVATE)
        val lastWasIslamic = prefs.getBoolean("last_islamic", false)
        val useIslamic = !lastWasIslamic
        prefs.edit().putBoolean("last_islamic", useIslamic).apply()

        val splashRes = when {
            useIslamic && isLandscape -> R.drawable.splash_islamic_tv
            useIslamic && !isLandscape -> R.drawable.splash_islamic_phone
            isLandscape -> R.drawable.splash_tv
            else -> R.drawable.splash_phone
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

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
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
        // 👑 الإصلاح الملكي الجذري: إزالة الحظر الإجباري لضمان انتقال التطبيق دائماً وعدم خروجه أو إغلاقه أبداً
        val active = SourcePrefs.getActiveProfile(this)
        val verified = active?.let {
            getSharedPreferences("verification_prefs", MODE_PRIVATE).getBoolean("is_verified_${it.id}", false)
        } ?: false
        val intent = when {
            active == null -> Intent(this, UserListActivity::class.java)
            active.activationCode == "MANUAL" || verified -> Intent(this, com.latchi.iptv.MainActivity::class.java)
            else -> Intent(this, VerificationActivity::class.java)
        }
        startActivity(intent)
        finish()
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
