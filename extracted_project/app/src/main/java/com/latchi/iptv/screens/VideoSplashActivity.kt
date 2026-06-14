package com.latchi.iptv.screens

import android.content.Intent
import android.media.MediaActionSound
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.VideoView
import androidx.appcompat.app.AppCompatActivity
import com.latchi.iptv.R
import com.latchi.iptv.utils.LocaleHelper
import com.latchi.iptv.utils.TvUtils

class VideoSplashActivity : AppCompatActivity() {

    private val splashHandler = Handler(Looper.getMainLooper())
    private var isTransitioned = false
    private var soundHelper: MediaActionSound? = null

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        TvUtils.applyOrientation(this)
        hideSystemUi()
        setContentView(R.layout.activity_video_splash)

        soundHelper = MediaActionSound().apply { load(MediaActionSound.FOCUS_COMPLETE) }

        val videoView = findViewById<VideoView>(R.id.splashVideoView)
        val simulatedOverlay = findViewById<FrameLayout>(R.id.simulatedIntroOverlay)
        val introText = findViewById<TextView>(R.id.introText)
        val centralAura = findViewById<View>(R.id.centralAura)
        val flareShine = findViewById<View>(R.id.flareShine)

        // 1️⃣ Try playing customized device video (TV vs Phone)
        try {
            val isTv = packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_LEANBACK)
            val rawId = if (isTv) R.raw.splash_tv else R.raw.splash_phone
            val videoUri = Uri.parse("android.resource://" + packageName + "/" + rawId)
            
            videoView.setVideoURI(videoUri)
            videoView.setOnPreparedListener { mp ->
                mp.setVolume(1.0f, 1.0f)
                videoView.start()
                simulatedOverlay.visibility = View.GONE
            }
            videoView.setOnErrorListener { _, _, _ ->
                simulatedOverlay.visibility = View.VISIBLE
                runMarvelCinematicPopIntro(introText, centralAura, flareShine)
                true
            }
        } catch (_: Exception) {
            simulatedOverlay.visibility = View.VISIBLE
            runMarvelCinematicPopIntro(introText, centralAura, flareShine)
        }

        splashHandler.postDelayed({ goToNextSplash() }, 5000)
    }

    // 2️⃣ Marvel Cinematic Native Simulated alternative
    private fun runMarvelCinematicPopIntro(textView: TextView, aura: View, flare: View) {
        val script = listOf(
            1000L to "L",
            1300L to "L A",
            1600L to "L A T",
            1900L to "L A T C",
            2200L to "L A T C H",
            2500L to "L A T C H I",
            2800L to "L A T C H I   I",
            3000L to "L A T C H I   I P",
            3200L to "L A T C H I   I P T",
            3400L to "L A T C H I   I P T V",
            3600L to "L A T C H I   I P T V   V",
            3800L to "L A T C H I   I P T V   V I",
            4000L to "L A T C H I   I P T V   V I P"
        )

        aura.animate().scaleX(1.4f).scaleY(1.4f).alpha(0.6f).setDuration(3800).start()

        for (i in 0 until script.size) {
            val (delay, text) = script[i]
            splashHandler.postDelayed({
                textView.text = text
                try { soundHelper?.play(MediaActionSound.FOCUS_COMPLETE) } catch (_: Exception) {}

                textView.scaleX = 0.7f
                textView.scaleY = 0.7f
                textView.animate().scaleX(1.15f).scaleY(1.15f).setDuration(120)
                    .setInterpolator(OvershootInterpolator(4f))
                    .withEndAction {
                        textView.animate().scaleX(1f).scaleY(1f).setDuration(100).start()
                    }.start()

                if (i == script.size - 1) {
                    aura.animate().scaleX(2.5f).scaleY(2.5f).alpha(0f).setDuration(600).start()
                    flareShineEffect(flare)
                }
            }, delay)
        }
    }

    private fun flareShineEffect(flare: View) {
        flare.alpha = 0.8f
        flare.scaleY = 0.2f
        flare.animate().scaleY(3.5f).alpha(0f).setDuration(700).start()
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
        soundHelper?.release()
        soundHelper = null
    }
}
