package com.latchi.iptv.screens

import android.animation.ObjectAnimator
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.graphics.drawable.ColorDrawable
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.latchi.iptv.MainActivity
import com.latchi.iptv.R
import com.latchi.iptv.utils.ActivationValidator
import com.latchi.iptv.utils.ChannelCache
import com.latchi.iptv.utils.LocaleHelper
import com.latchi.iptv.utils.SourcePrefs
import com.latchi.iptv.utils.TvUtils
import kotlin.concurrent.thread

class VerificationActivity : AppCompatActivity() {

    private var progressBar: ProgressBar? = null
    private var percentText: TextView? = null
    private var statusText: TextView? = null
    private var progressHandler: Handler? = null
    private var fakeProgress = 0
    private var silentVerify = false

    override fun attachBaseContext(context: Context) {
        super.attachBaseContext(LocaleHelper.wrap(context))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        TvUtils.applyOrientation(this)
        com.latchi.iptv.utils.ThemeManager.apply(this)
        silentVerify = intent.getBooleanExtra("silent_verify", false)
        
        val profile = SourcePrefs.getActiveProfile(this)
        if (profile == null) {
            goToUsers()
            return
        }
        if (profile.activationCode == "MANUAL") {
            goToMain()
            return
        }
        val alreadyVerified = getSharedPreferences("verification_prefs", MODE_PRIVATE).getBoolean("is_verified_${profile.id}", false)
        if (alreadyVerified && !isExpired(profile.expiresAt)) {
            goToMain()
            return
        }
        
        showCheckingScreen()

        thread {
            try {
                val result = ActivationValidator.validate(this, profile)
                runOnUiThread {
                    if (result.success) {
                        val oldUrl = profile.m3uUrl
                        val newUrl = result.playlistUrl.ifBlank { oldUrl }
                        if (newUrl != oldUrl) ChannelCache.clear(this, profile.id)
                        SourcePrefs.saveActivatedProfile(
                            context = this,
                            code = profile.activationCode,
                            name = result.name,
                            playlistUrl = newUrl,
                            expiresAt = result.expiresAt,
                            maxDevices = result.maxDevices
                        )
                        statusText?.text = getString(R.string.subscription_verified)
                        finishProgressThen {
                            if (silentVerify) goToMain() else showVerificationSummaryDialog(profile, result)
                        }
                    } else {
                        SourcePrefs.deleteProfile(this, profile.id)
                        goExpired(result.message)
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    val hasCache = ChannelCache.load(this, profile.id).isNotEmpty()
                    if (hasCache) {
                        ErrorOverlayHelper.show(this, "تنبيه", getString(R.string.offline_mode))
                        finishProgressThen {
                            if (silentVerify) {
                                goToMain()
                            } else {
                                val emptyResult = com.latchi.iptv.utils.ActivationValidationResult(true, "Offline", profile.name, profile.m3uUrl, profile.expiresAt, profile.maxDevices)
                                showVerificationSummaryDialog(profile, emptyResult)
                            }
                        }
                    } else {
                        showNoConnectionScreen()
                    }
                }
            }
        }
    }

    private fun showVerificationSummaryDialog(profile: com.latchi.iptv.utils.IptvProfile, result: com.latchi.iptv.utils.ActivationValidationResult) {
        val name = result.name.ifBlank { profile.name }
        val expiry = cleanDisplayDate(result.expiresAt.ifBlank { profile.expiresAt }.ifBlank { "غير محدد" })
        val phoneInfo = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}"

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(34, 30, 34, 28)
            setBackgroundResource(R.drawable.bg_verification_glass)
        }
        container.addView(TextView(this).apply {
            text = "تم التحقق من الاشتراك"
            setTextColor(Color.parseColor("#FFD700"))
            textSize = 21f
            setTypeface(null, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            letterSpacing = 0.03f
        })
        container.addView(TextView(this).apply {
            text = "\nالجهاز\n$phoneInfo\n\nالمشترك\n$name\n\nالصلاحية\n$expiry"
            setTextColor(Color.WHITE)
            textSize = 15.5f
            gravity = Gravity.CENTER
            setLineSpacing(7f, 1.08f)
        })
        val okButton = TextView(this).apply {
            text = "دخول"
            setTextColor(Color.BLACK)
            textSize = 17f
            setTypeface(null, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            setBackgroundResource(R.drawable.bg_gold_btn)
            isClickable = true
            isFocusable = true
        }
        container.addView(okButton, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 52).apply { topMargin = 24 })

        val dialog = androidx.appcompat.app.AlertDialog.Builder(this).setView(container).setCancelable(false).create()
        okButton.setOnClickListener {
            SourcePrefs.getActiveProfile(this)?.let { active ->
                getSharedPreferences("verification_prefs", MODE_PRIVATE).edit()
                    .putBoolean("is_verified_${active.id}", true)
                    .apply()
            }
            dialog.dismiss()
            goToMain()
        }
        dialog.setOnShowListener {
            dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            container.alpha = 0f
            container.scaleX = 0.96f
            container.scaleY = 0.96f
            container.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(220).start()
        }
        dialog.show()
    }

    private fun cleanDisplayDate(raw: String): String {
        val clean = raw.trim()
        return when {
            clean.contains(" GMT") -> clean.substringBefore(" GMT").trim()
            clean.contains(" heure", ignoreCase = true) -> clean.substringBefore(" heure").trim()
            clean.length > 24 && clean.contains("+") -> clean.substringBefore("+").trim()
            else -> clean
        }
    }

    private fun isExpired(expiresAt: String): Boolean {
        return try {
            val clean = expiresAt.take(10)
            val sdf = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
            val date = sdf.parse(clean) ?: return false
            System.currentTimeMillis() > date.time + 86_400_000L
        } catch (_: Exception) { false }
    }

    private fun showCheckingScreen() {
        setContentView(R.layout.activity_loading)
        progressBar = findViewById(R.id.loadingProgress)
        percentText = findViewById(R.id.loadingPercent)
        statusText = findViewById(R.id.loadingText)
        statusText?.text = getString(R.string.identifying_subscription)
        startFakeProgress()
    }

    private fun startFakeProgress() {
        progressHandler = Handler(Looper.getMainLooper())
        val step = object : Runnable {
            override fun run() {
                if (fakeProgress < 90) {
                    fakeProgress += 2
                    updateProgressUi(fakeProgress)
                    progressHandler?.postDelayed(this, 60)
                }
            }
        }
        progressHandler?.post(step)
    }

    private fun finishProgressThen(action: () -> Unit) {
        progressHandler?.removeCallbacksAndMessages(null)
        val anim = ObjectAnimator.ofInt(progressBar ?: return action(), "progress", fakeProgress, 100)
        anim.duration = 400
        anim.addUpdateListener { percentText?.text = "${it.animatedValue}%" }
        anim.start()
        Handler(Looper.getMainLooper()).postDelayed({ action() }, 450)
    }

    private fun updateProgressUi(value: Int) {
        progressBar?.progress = value
        percentText?.text = "$value%"
    }

    private fun showNoConnectionScreen() {
        progressHandler?.removeCallbacksAndMessages(null)
        setContentView(R.layout.activity_no_connection)
        findViewById<TextView>(R.id.retryButton).setOnClickListener { recreate() }
    }

    private fun goToMain() {
        SourcePrefs.getActiveProfile(this)?.let { profile ->
            getSharedPreferences("verification_prefs", MODE_PRIVATE).edit()
                .putBoolean("is_verified_${profile.id}", true)
                .apply()
        }
        val intent = Intent(this, MainActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        finish()
    }

    private fun goToUsers() {
        val intent = Intent(this, UserListActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        finish()
    }

    private fun goExpired(message: String) {
        val intent = Intent(this, ExpiredActivity::class.java).apply { putExtra("message", message) }
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        progressHandler?.removeCallbacksAndMessages(null)
    }

    private var backPressedTime = 0L

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (System.currentTimeMillis() - backPressedTime < 2000) {
            finishAffinity()
        } else {
            backPressedTime = System.currentTimeMillis()
            ErrorOverlayHelper.show(this, "تنبيه", "اضغط مرة أخرى للخروج")
        }
    }
}
