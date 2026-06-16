package com.latchi.iptv.screens

import android.app.Activity
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.latchi.iptv.MainActivity
import com.latchi.iptv.R
import com.latchi.iptv.utils.CustomOverlayHelper
import com.latchi.iptv.utils.DailyWallpaperManager
import com.latchi.iptv.utils.FloatingBackHelper
import com.latchi.iptv.utils.LocaleHelper
import com.latchi.iptv.utils.ThemeManager
import com.latchi.iptv.utils.TvUtils
import java.io.File

/**
 * 🌟 TV Fullscreen Majestic Backdrop Wallpaper Gallery (`THEME` / `Shape`) 🌟
 *
 * Full-screen VIP cinematic wallpaper backdrop gallery.
 * Automatically synchronizes exactly 5 rotating daily backdrops. Deletes leftovers
 * to consume zero leftover storage. Offers Instant DPAD selection or Custom Gallery.
 */
class ThemeSettingsActivity : AppCompatActivity() {
    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    private lateinit var content: LinearLayout
    private lateinit var progressBarRow: View

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        TvUtils.applyOrientation(this)
        ThemeManager.apply(this)

        buildMajesticUi()
        loadWallpapers()
    }

    private fun buildMajesticUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#050A1A"))
        }
        setContentView(root)
        FloatingBackHelper.setup(this)

        // Top bar
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(16))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#121228"))
                setStroke(dp(1), Color.parseColor("#3d3d5c"))
            }
        }

        val back = TextView(this).apply {
            text = "←  رجوع / Back"
            setTextColor(Color.parseColor("#7FE6FF"))
            textSize = 15f
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            setBackgroundResource(R.drawable.bg_panel_premium)
            isClickable = true
            isFocusable = true
            setOnClickListener { finish() }
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        topBar.addView(back, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(44)))

        val titles = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), 0, 0, 0)
        }
        titles.addView(TextView(this).apply {
            text = "🎨 THEME • خلفيات الشاشة السينمائية"
            setTextColor(Color.parseColor("#FFD700"))
            textSize = 20f
            setTypeface(null, Typeface.BOLD)
        })
        titles.addView(TextView(this).apply {
            text = "5 خلفيات يومية متجددة أوتوماتيكياً • صفر استهلاك للمساحة"
            setTextColor(Color.parseColor("#A5B4FC"))
            textSize = 12f
            setPadding(0, dp(2), 0, 0)
        })
        topBar.addView(titles, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(topBar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        progressBarRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(20), dp(40), dp(20), dp(40))
            addView(ProgressBar(this@ThemeSettingsActivity).apply { isIndeterminate = true })
            addView(TextView(this@ThemeSettingsActivity).apply {
                text = "جاري مزامنة 5 خلفيات سينمائية جديدة لهذا اليوم...\nسيتم مسح الخلفيات القديمة صمتاً للحفاظ على المساحة ⚡"
                setTextColor(Color.WHITE)
                textSize = 14f
                setPadding(dp(16), 0, 0, 0)
            })
        }
        root.addView(progressBarRow)

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            setPadding(dp(20), dp(16), dp(20), dp(24))
        }
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
    }

    private fun loadWallpapers() {
        progressBarRow.visibility = View.VISIBLE
        content.removeAllViews()

        DailyWallpaperManager.syncDailyWallpapers(this, force = false) { files ->
            progressBarRow.visibility = View.GONE
            renderWallpapers(files)
        }
    }

    private fun renderWallpapers(files: List<File>) {
        content.removeAllViews()

        val activePath = DailyWallpaperManager.getActiveWallpaper(this)

        // Custom Option
        val customCard = createWallpaperCard(
            title = "🖼️ اختيار صورة من المعرض (Custom Wallpaper)",
            subtitle = "تحديد صورة خاصة من هاتفك أو جهازك",
            isActive = ThemeManager.getTheme(this) == "custom",
            imageFile = null
        ) {
            pickCustomImage()
        }
        content.addView(customCard)

        // Refresh Option
        val refreshCard = createWallpaperCard(
            title = "🔄 مزامنة 5 خلفيات سينمائية جديدة الآن",
            subtitle = "تحميل 5 خلفيات مذهلة فوراً ومسح الخلفيات الحالية",
            isActive = false,
            imageFile = null
        ) {
            progressBarRow.visibility = View.VISIBLE
            DailyWallpaperManager.syncDailyWallpapers(this, force = true) { updated ->
                progressBarRow.visibility = View.GONE
                renderWallpapers(updated)
                CustomOverlayHelper.show(this, "مزامنة", "تم جلب 5 خلفيات جديدة بنجاح ✓", true)
            }
        }
        content.addView(refreshCard)

        // Section Title
        val titleView = TextView(this).apply {
            text = "✨ خلفيات اليوم الأوتوماتيكية (اضغط للتطبيق الفوري)"
            setTextColor(Color.parseColor("#FFD700"))
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            setPadding(0, dp(12), 0, dp(10))
        }
        content.addView(titleView)

        files.forEachIndexed { index, file ->
            val isCurrent = file.absolutePath == activePath && ThemeManager.getTheme(this) != "custom"
            val card = createWallpaperCard(
                title = "🌌 خلفية سينمائية — المظهر ${index + 1}",
                subtitle = "المسار: ${file.name}",
                isActive = isCurrent,
                imageFile = file
            ) {
                DailyWallpaperManager.setActiveWallpaper(this, file.absolutePath)
                ThemeManager.setTheme(this, "daily")
                CustomOverlayHelper.show(this, "خلفية الشاشة", "✨ تم تطبيق خلفية الشاشة بنجاح", true)
                recreate()
            }
            content.addView(card)
        }

        CustomOverlayHelper.show(this, "خلفيات", "تم تحميل 5 خلفيات جاهزة ✓", true)
    }

    private fun createWallpaperCard(
        title: String,
        subtitle: String,
        isActive: Boolean,
        imageFile: File?,
        onClick: () -> Unit
    ): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            isClickable = true
            isFocusable = true
            background = GradientDrawable().apply {
                setColor(if (isActive) Color.parseColor("#1B1B3A") else Color.parseColor("#121228"))
                cornerRadius = dp(16).toFloat()
                setStroke(dp(2), if (isActive) Color.parseColor("#FFD700") else Color.parseColor("#3d3d5c"))
            }
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(12) }

            setOnClickListener { onClick() }
            setOnFocusChangeListener { v, has ->
                v.animate().scaleX(if (has) 1.03f else 1f).scaleY(if (has) 1.03f else 1f).setDuration(120).start()
            }
        }

        // Preview ImageView
        val imageView = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(80), dp(50)).apply { marginEnd = dp(16) }
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = GradientDrawable().apply {
                setColor(Color.BLACK)
                cornerRadius = dp(8).toFloat()
            }
            clipToOutline = true
        }

        if (imageFile != null && imageFile.exists()) {
            try {
                val bitmap = android.graphics.BitmapFactory.decodeFile(imageFile.absolutePath)
                imageView.setImageBitmap(bitmap)
            } catch (_: Exception) {}
        } else {
            imageView.setImageResource(R.drawable.tv_logo)
        }
        card.addView(imageView)

        val textCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        textCol.addView(TextView(this).apply {
            text = if (isActive) "✓ $title (مطبق حالياً)" else title
            setTextColor(if (isActive) Color.parseColor("#FFD700") else Color.WHITE)
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
        })
        textCol.addView(TextView(this).apply {
            text = subtitle
            setTextColor(Color.parseColor("#A5B4FC"))
            textSize = 12f
            setPadding(0, dp(4), 0, 0)
        })
        card.addView(textCol)

        return card
    }

    private fun pickCustomImage() {
        val intent = Intent(Intent.ACTION_PICK)
        intent.type = "image/*"
        startActivityForResult(intent, 2001)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 2001 && resultCode == RESULT_OK && data?.data != null) {
            val uri = data.data!!.toString()
            ThemeManager.setCustomTheme(this, uri)
            DailyWallpaperManager.setActiveWallpaper(this, "")
            Toast.makeText(this, getString(R.string.theme_saved), Toast.LENGTH_SHORT).show()
            recreate()
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (com.latchi.iptv.utils.TvFocusHelper.handleKey(this, keyCode, event)) return true
        return super.onKeyDown(keyCode, event)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
