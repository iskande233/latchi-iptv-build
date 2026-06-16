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
 * Automatically synchronizes exactly 6 rotating daily backdrops. Deletes leftovers
 * to consume zero leftover storage. Offers Instant DPAD selection or Custom Gallery.
 */
class ThemeSettingsActivity : AppCompatActivity() {
    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    private lateinit var rootLayout: LinearLayout
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
        rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#050A1A"))
        }
        setContentView(rootLayout)
        FloatingBackHelper.setup(this)

        // Set the active background instantly on load
        val activeBg = DailyWallpaperManager.loadActiveWallpaperDrawable(this)
        if (activeBg != null) {
            rootLayout.background = activeBg
        }

        // Top bar
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(16))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#CC121228"))
                setStroke(dp(1), Color.parseColor("#3d3d5c"))
                cornerRadius = dp(12).toFloat()
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
            setOnFocusChangeListener { v, has ->
                v.animate().scaleX(if (has) 1.05f else 1f).scaleY(if (has) 1.05f else 1f).setDuration(120).start()
            }
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
            text = "6 خلفيات يومية متجددة أوتوماتيكياً • صفر استهلاك للمساحة"
            setTextColor(Color.parseColor("#A5B4FC"))
            textSize = 12f
            setPadding(0, dp(2), 0, 0)
        })
        topBar.addView(titles, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        rootLayout.addView(topBar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            setMargins(dp(20), dp(16), dp(20), 0)
        })

        progressBarRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(20), dp(40), dp(20), dp(40))
            addView(ProgressBar(this@ThemeSettingsActivity).apply { isIndeterminate = true })
            addView(TextView(this@ThemeSettingsActivity).apply {
                text = "جاري مزامنة 6 خلفيات سينمائية جديدة لهذا اليوم...\nسيتم مسح الخلفيات القديمة صمتاً للحفاظ على المساحة ⚡"
                setTextColor(Color.WHITE)
                textSize = 14f
                setPadding(dp(16), 0, 0, 0)
            })
        }
        rootLayout.addView(progressBarRow)

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            setPadding(dp(20), dp(16), dp(20), dp(24))
        }
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        scroll.addView(content)
        rootLayout.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
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

        // Horizontal Row for Custom & Refresh Options (To keep them compact at the top)
        val actionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(16)
            }
        }

        // Custom Option
        val customCard = createWallpaperCard(
            title = "🖼️ اختيار صورة خاصة",
            subtitle = "تحديد صورة من معرض جهازك",
            isActive = ThemeManager.getTheme(this) == "custom",
            imageFile = null
        ) {
            pickCustomImage()
        }
        actionRow.addView(customCard, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginEnd = dp(6)
        })

        // Refresh Option
        val refreshCard = createWallpaperCard(
            title = "🔄 مزامنة 6 خلفيات جديدة",
            subtitle = "تحميل وتحديث الخلفيات الآن",
            isActive = false,
            imageFile = null
        ) {
            progressBarRow.visibility = View.VISIBLE
            DailyWallpaperManager.syncDailyWallpapers(this, force = true) { updated ->
                progressBarRow.visibility = View.GONE
                renderWallpapers(updated)
                CustomOverlayHelper.show(this, "مزامنة", "تم جلب 6 خلفيات جديدة بنجاح ✓", true)
            }
        }
        actionRow.addView(refreshCard, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = dp(6)
        })

        content.addView(actionRow)

        // Section Title
        val titleView = TextView(this).apply {
            text = "✨ خلفيات اليوم السينمائية (تطبيق فوري في جزء من الثانية ⚡)"
            setTextColor(Color.parseColor("#FFD700"))
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            setPadding(0, dp(12), 0, dp(10))
        }
        content.addView(titleView)

        // 3-Column Grid for the 6 wallpapers
        val grid = android.widget.GridLayout(this).apply {
            columnCount = 3
            alignmentMode = android.widget.GridLayout.ALIGN_BOUNDS
            useDefaultMargins = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(6)
                bottomMargin = dp(30)
            }
        }

        files.forEachIndexed { index, file ->
            val isCurrent = file.absolutePath == activePath && ThemeManager.getTheme(this) != "custom"
            val card = createGridWallpaperCard(
                title = "خلفية سينمائية — ${index + 1}",
                isActive = isCurrent,
                imageFile = file
            ) {
                DailyWallpaperManager.setActiveWallpaper(this, file.absolutePath)
                ThemeManager.setTheme(this, "daily")
                
                // Set the active background instantly!
                val drawable = DailyWallpaperManager.loadActiveWallpaperDrawable(this)
                if (drawable != null) {
                    rootLayout.background = drawable
                }
                
                // Re-render the grid to show the new active border instantly!
                renderWallpapers(files)
                
                Toast.makeText(this, "✨ تم تطبيق الخلفية ${index + 1} بنجاح", Toast.LENGTH_SHORT).show()
            }
            grid.addView(card)
        }
        content.addView(grid)
    }

    private fun createWallpaperCard(
        title: String,
        subtitle: String,
        isActive: Boolean,
        imageFile: File?,
        onClick: () -> Unit
    ): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            isClickable = true
            isFocusable = true
            background = GradientDrawable().apply {
                setColor(if (isActive) Color.parseColor("#331B1B3A") else Color.parseColor("#80121228"))
                cornerRadius = dp(12).toFloat()
                setStroke(dp(2), if (isActive) Color.parseColor("#FFD700") else Color.parseColor("#3d3d5c"))
            }

            setOnClickListener { onClick() }
            setOnFocusChangeListener { v, has ->
                v.animate().scaleX(if (has) 1.03f else 1f).scaleY(if (has) 1.03f else 1f).setDuration(120).start()
                if (has) {
                    (v.background as? GradientDrawable)?.setStroke(dp(2), Color.parseColor("#7FE6FF"))
                } else {
                    (v.background as? GradientDrawable)?.setStroke(dp(2), if (isActive) Color.parseColor("#FFD700") else Color.parseColor("#3d3d5c"))
                }
            }

            addView(TextView(this@ThemeSettingsActivity).apply {
                text = title
                setTextColor(if (isActive) Color.parseColor("#FFD700") else Color.WHITE)
                textSize = 14f
                setTypeface(null, Typeface.BOLD)
                gravity = Gravity.CENTER
            })
            addView(TextView(this@ThemeSettingsActivity).apply {
                text = subtitle
                setTextColor(Color.parseColor("#A5B4FC"))
                textSize = 11f
                gravity = Gravity.CENTER
                setPadding(0, dp(4), 0, 0)
            })
        }
    }

    private fun createGridWallpaperCard(
        title: String,
        isActive: Boolean,
        imageFile: File?,
        onClick: () -> Unit
    ): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(8), dp(8), dp(8))
            isClickable = true
            isFocusable = true
            background = GradientDrawable().apply {
                setColor(if (isActive) Color.parseColor("#4D1B1B3A") else Color.parseColor("#B3121228"))
                cornerRadius = dp(12).toFloat()
                setStroke(dp(2), if (isActive) Color.parseColor("#FFD700") else Color.parseColor("#3d3d5c"))
            }
            
            // Grid layout params for equal column widths
            val specRow = android.widget.GridLayout.spec(android.widget.GridLayout.UNDEFINED, 1f)
            val specCol = android.widget.GridLayout.spec(android.widget.GridLayout.UNDEFINED, 1f)
            layoutParams = android.widget.GridLayout.LayoutParams(specRow, specCol).apply {
                width = 0
                height = LinearLayout.LayoutParams.WRAP_CONTENT
                setMargins(dp(6), dp(6), dp(6), dp(6))
            }

            setOnClickListener { onClick() }
            setOnFocusChangeListener { v, has ->
                v.animate().scaleX(if (has) 1.05f else 1f).scaleY(if (has) 1.05f else 1f).setDuration(120).start()
                if (has) {
                    (v.background as? GradientDrawable)?.setStroke(dp(2), Color.parseColor("#7FE6FF"))
                } else {
                    (v.background as? GradientDrawable)?.setStroke(dp(2), if (isActive) Color.parseColor("#FFD700") else Color.parseColor("#3d3d5c"))
                }
            }
        }

        // Preview ImageView
        val imageView = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(84)).apply {
                bottomMargin = dp(6)
            }
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = GradientDrawable().apply {
                setColor(Color.BLACK)
                cornerRadius = dp(8).toFloat()
            }
            clipToOutline = true
        }

        if (imageFile != null && imageFile.exists()) {
            try {
                val bitmap = BitmapFactory.decodeFile(imageFile.absolutePath)
                imageView.setImageBitmap(bitmap)
            } catch (_: Exception) {}
        } else {
            imageView.setImageResource(R.drawable.tv_logo)
        }
        card.addView(imageView)

        // Text
        card.addView(TextView(this).apply {
            text = if (isActive) "✓ $title" else title
            setTextColor(if (isActive) Color.parseColor("#FFD700") else Color.WHITE)
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
        })

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
        // Native android focus search handles grid perfectly, we bypass custom manual key handling if it locks up D-pad
        return super.onKeyDown(keyCode, event)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
