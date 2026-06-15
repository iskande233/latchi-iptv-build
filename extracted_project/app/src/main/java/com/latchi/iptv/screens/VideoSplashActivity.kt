package com.latchi.iptv.screens

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity
import com.latchi.iptv.R
import com.latchi.iptv.utils.LocaleHelper
import com.latchi.iptv.utils.TvUtils

/**
 * Intro splash screen without video.
 *
 * Uses the compressed Picsart artwork supplied by the client:
 * - TV / Android TV: video_splash_tv.webp
 * - Phone: video_splash_phone.webp
 *
 * A slow cinematic fade + zoom animation replaces the old heavy MP4 intro.
 */
class VideoSplashActivity : AppCompatActivity() {

    private val splashHandler = Handler(Looper.getMainLooper())
    private var isTransitioned = false

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        TvUtils.applyOrientation(this)
        hideSystemUi()
        setContentView(R.layout.activity_video_splash)

        val isTv = TvUtils.isTv(this)
        val splashImage = findViewById<ImageView>(R.id.videoSplashImage)
        splashImage.setImageResource(if (isTv) R.drawable.video_splash_tv else R.drawable.video_splash_phone)
        runPremiumSplashAnimation(splashImage, isTv)

        splashHandler.postDelayed({ goToNextSplash() }, if (isTv) 4200L else 3800L)
    }

    private fun runPremiumSplashAnimation(image: ImageView, isTv: Boolean) {
        image.alpha = 0f
        image.scaleX = if (isTv) 1.08f else 1.10f
        image.scaleY = if (isTv) 1.08f else 1.10f
        image.animate()
            .alpha(1f)
            .scaleX(1.0f)
            .scaleY(1.0f)
            .setDuration(if (isTv) 3900L else 3500L)
            .setInterpolator(DecelerateInterpolator(1.6f))
            .start()
    }

    @Synchronized
    private fun goToNextSplash() {
        if (!isTransitioned) {
            isTransitioned = true
            startActivity(Intent(this, SplashActivity::class.java))
            finish()
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
