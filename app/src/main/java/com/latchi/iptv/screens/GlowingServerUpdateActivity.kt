package com.latchi.iptv.screens

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.animation.AnimationUtils
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.latchi.iptv.MainActivity
import com.latchi.iptv.R
import com.latchi.iptv.utils.ChannelCache
import com.latchi.iptv.utils.LiveMasterController
import com.latchi.iptv.utils.LocaleHelper
import com.latchi.iptv.utils.SourcePrefs
import com.latchi.iptv.utils.TvUtils

/**
 * 🌟 Glowing Server Update Activity 🌟
 *
 * Full-screen glowing Islamic decorative screen optimized for TV and Phone interfaces.
 * Appears instantly when the Admin forces a Server Revision update.
 * Halts all previous activities, displays glowing text "تم تحديث السيرفر" (NO emojis),
 * clears channel cache, and returns exactly to fresh channels after 3.5 seconds.
 */
class GlowingServerUpdateActivity : AppCompatActivity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    companion object {
        private const val EXTRA_REVISION = "extra_server_revision"

        fun start(context: Context, revision: Long) {
            val intent = Intent(context, GlowingServerUpdateActivity::class.java).apply {
                putExtra(EXTRA_REVISION, revision)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }
            context.startActivity(intent)
        }
    }

    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        TvUtils.applyOrientation(this)
        hideSystemUi()

        val revision = intent.getLongExtra(EXTRA_REVISION, 0L)
        if (revision > 0L) {
            LiveMasterController.syncCurrentRevision(this, revision)
        }

        buildGlowingUi()
        executeCacheRefreshAndProceed()
    }

    private fun buildGlowingUi() {
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#020617")) // Extreme deep night
        }
        setContentView(root)

        // Add a beautiful pulsing glowing orb in the center background
        val glowingOrb = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                colors = intArrayOf(Color.parseColor("#3300E5FF"), Color.parseColor("#11FFD700"), Color.TRANSPARENT)
                gradientType = GradientDrawable.RADIAL_GRADIENT
                setGradientCenter(0.5f, 0.5f)
                gradientRadius = dp(400).toFloat()
            }
        }
        root.addView(glowingOrb, FrameLayout.LayoutParams(dp(700), dp(700), Gravity.CENTER))

        try {
            glowingOrb.startAnimation(AnimationUtils.loadAnimation(this, R.anim.voice_pulse))
        } catch (_: Exception) {}

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(40), dp(40), dp(40), dp(40))
        }

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(48), dp(40), dp(48), dp(40))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#141E33"))
                cornerRadius = dp(24).toFloat()
                setStroke(dp(3), Color.parseColor("#FFD700")) // Golden Islamic glowing border
            }
            elevation = dp(30).toFloat()
        }

        // Title in gorgeous decorative typography (NO emojis)
        val titleAr = TextView(this).apply {
            text = "تم تحديث السيرفر"
            setTextColor(Color.parseColor("#FFD700"))
            textSize = if (TvUtils.isTv(this@GlowingServerUpdateActivity)) 46f else 32f
            setTypeface(null, Typeface.BOLD) // Calligraphy Islamic vibe
            gravity = Gravity.CENTER
            setShadowLayer(16f, 0f, 0f, Color.parseColor("#FFD700")) // Golden Glow
        }
        card.addView(titleAr)

        // English Translation
        val titleEn = TextView(this).apply {
            text = "Server Updated Successfully"
            setTextColor(Color.parseColor("#00E5FF"))
            textSize = if (TvUtils.isTv(this@GlowingServerUpdateActivity)) 28f else 20f
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, 0)
            setShadowLayer(12f, 0f, 0f, Color.parseColor("#00E5FF")) // Cyan Glow
        }
        card.addView(titleEn)

        val desc = TextView(this).apply {
            text = "تم جلب أحدث قائمة قنوات وإعدادات استقرار البث بنجاح.\nجاري توجيهك للمتابعة بأعلى جودة..."
            setTextColor(Color.WHITE)
            textSize = if (TvUtils.isTv(this@GlowingServerUpdateActivity)) 20f else 15f
            gravity = Gravity.CENTER
            setLineSpacing(8f, 1.1f)
            setPadding(0, dp(24), 0, dp(28))
        }
        card.addView(desc)

        val progressRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        progressRow.addView(ProgressBar(this).apply {
            isIndeterminate = true
            layoutParams = LinearLayout.LayoutParams(dp(36), dp(36))
            try {
                indeterminateDrawable.setColorFilter(Color.parseColor("#FFD700"), android.graphics.PorterDuff.Mode.SRC_IN)
            } catch (_: Exception) {}
        })

        progressRow.addView(TextView(this).apply {
            text = "تجهيز القنوات / Loading Channels..."
            setTextColor(Color.parseColor("#A5B4FC"))
            textSize = if (TvUtils.isTv(this@GlowingServerUpdateActivity)) 18f else 14f
            setTypeface(null, Typeface.BOLD)
            setPadding(dp(16), 0, dp(16), 0)
        })

        card.addView(progressRow)
        container.addView(card, LinearLayout.LayoutParams(if (TvUtils.isTv(this@GlowingServerUpdateActivity)) dp(800) else LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        root.addView(container, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
    }

    private fun executeCacheRefreshAndProceed() {
        // ✅ الإصلاح الجذري:
        // 1. نمسح الكاش فوراً بدون انتظار
        val appContext = applicationContext
        val active = com.latchi.iptv.utils.SourcePrefs.getActiveProfile(appContext)
        if (active != null) {
            com.latchi.iptv.utils.ChannelCache.clear(appContext, active.id)
        }

        // 2. نعيد التحقق من السيرفر لجلب الرابط الجديد وحفظه
        com.latchi.iptv.utils.ServerSyncManager.checkForServerUpdate(this, force = true) { _ ->
            // 3. نضع علامة "يحتاج تحديث قنوات" حتى HomeFragment يعيد التحميل عند العودة
            val freshActive = com.latchi.iptv.utils.SourcePrefs.getActiveProfile(appContext)
            if (freshActive != null) {
                com.latchi.iptv.utils.SourcePrefs.setPendingServerRefresh(appContext, freshActive.id, true)
            }
            // 🛡️ v5.2+: نتأكد من تحميل القنوات فعلياً قبل إغلاق الشاشة
            // بدل الانتظار الثابت 1500ms، ننتظر حتى CatalogRepository يكتمل
            waitForChannelsToLoad()
        }
    }

    /**
     * 🛡️ v5.2+: ينتظر حتى يكتمل تحميل القنوات فعلياً قبل إغلاق الشاشة.
     * هذا يضمن أن المستخدم يرى القنوات الجديدة بعد إغلاق popup "تم تحديث السيرفر"
     * بدون popup إضافي "تم جلب القنوات".
     */
    private fun waitForChannelsToLoad() {
        val appContext = applicationContext
        val active = com.latchi.iptv.utils.SourcePrefs.getActiveProfile(appContext) ?: run {
            // إذا لا يوجد profile → انتقل مباشرة
            handler.postDelayed({ navigateToMain() }, 800L)
            return
        }

        // مراقبة تحميل القنوات
        var attempts = 0
        val maxAttempts = 30  // 30 * 500ms = 15 ثانية كحد أقصى
        val checkRunnable = object : Runnable {
            override fun run() {
                attempts++
                val hasData = runCatching {
                    com.latchi.iptv.utils.CatalogRepository.hasTypeDataBlocking(appContext, active.id, "live") ||
                    com.latchi.iptv.utils.CatalogRepository.hasTypeDataBlocking(appContext, active.id, "movie") ||
                    com.latchi.iptv.utils.CatalogRepository.hasTypeDataBlocking(appContext, active.id, "series")
                }.getOrDefault(false)

                if (hasData || attempts >= maxAttempts) {
                    // القنوات تم تحميلها (أو انتهى الوقت المخصص)
                    handler.postDelayed({ navigateToMain() }, 600L)
                } else {
                    handler.postDelayed(this, 500L)
                }
            }
        }
        handler.postDelayed(checkRunnable, 600L)
    }

    private fun navigateToMain() {
        // ✅ FLAG_ACTIVITY_CLEAR_TASK يضمن أن MainActivity تُنشأ من جديد
        // وHomeFragment يستدعي onResume → يكتشف isPendingServerRefresh → يعيد التحميل
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            // علامة إضافية لإجبار HomeFragment على إعادة تحميل القنوات فوراً
            putExtra("force_channel_reload", true)
        }
        startActivity(intent)
        finish()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        // Block all back/navigation buttons to ensure absolute synchronization
        return true
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
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
