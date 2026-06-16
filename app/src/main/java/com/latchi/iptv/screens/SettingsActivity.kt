package com.latchi.iptv.screens

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.latchi.iptv.MainActivity
import com.latchi.iptv.R
import com.latchi.iptv.utils.ChannelCache
import com.latchi.iptv.utils.LanguagePrefs
import com.latchi.iptv.utils.LocaleHelper
import com.latchi.iptv.utils.PlayerPrefs
import com.latchi.iptv.utils.SourcePrefs
import com.latchi.iptv.utils.ServerSyncManager
import com.latchi.iptv.utils.ServerUpdateOverlayHelper
import com.latchi.iptv.utils.ServerHealthChecker
import com.latchi.iptv.utils.ThemeManager
import com.latchi.iptv.utils.TvUtils

class SettingsActivity : AppCompatActivity() {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    private lateinit var languageText: TextView
    private lateinit var playerModeText: TextView
    private lateinit var expiryInfoText: TextView
    private var lastSyncText: TextView? = null
    private var serverStatusText: TextView? = null

    private val tvPrefs by lazy { getSharedPreferences("latchi_tv_settings", Context.MODE_PRIVATE) }
    private val tvCategoryViews = mutableListOf<TextView>()
    private lateinit var tvDetailsContainer: LinearLayout
    private lateinit var tvDetailsTitle: TextView
    private var selectedTvCategory = 0

    private data class TvOption(
        val key: String,
        val title: String,
        val subtitle: String,
        val values: List<String>,
        val defaultValue: String,
        val action: String? = null
    )

    private data class TvCategory(
        val title: String,
        val options: List<TvOption>
    )

    private val tvCategories: List<TvCategory> by lazy {
        listOf(
            TvCategory(
                "🎬 البث",
                listOf(
                    TvOption("stream_auto_quality", "Auto Quality", "اختيار الجودة تلقائياً حسب سرعة الإنترنت", listOf("ON", "OFF"), "ON"),
                    TvOption("stream_manual_quality", "Manual Quality", "اختيار الجودة اليدوية عند تعطيل Auto", listOf("SD", "HD", "FHD", "4K"), "HD"),
                    TvOption("stream_buffer_size", "Buffer Size", "حجم التحميل المسبق قبل التشغيل", listOf("Small", "Medium", "Large"), "Medium")
                )
            ),
            TvCategory(
                "🔊 الصوت",
                listOf(
                    TvOption("audio_track", "Audio Track", "اختيار لغة الصوت المفضلة", listOf("العربية", "Français", "English"), "العربية"),
                    TvOption("audio_sync", "Audio Sync", "مزامنة الصوت مع الصورة", listOf("-500ms", "-250ms", "0ms", "+250ms", "+500ms"), "0ms"),
                    TvOption("volume_boost", "Volume Boost", "تضخيم الصوت للتلفازات القديمة", listOf("OFF", "Low", "Medium", "High"), "OFF")
                )
            ),
            TvCategory(
                "🖥️ الشاشة",
                listOf(
                    TvOption("display_aspect_ratio", "Aspect Ratio", "نسبة عرض الفيديو", listOf("Auto", "16:9", "4:3", "Zoom", "Full"), "Auto"),
                    TvOption("display_screen_fit", "Screen Fit", "ملاءمة الشاشة بدون أشرطة سوداء", listOf("OFF", "ON"), "OFF")
                )
            ),
            TvCategory(
                "▶️ المشغل",
                listOf(
                    TvOption("player_engine", "Player Engine", "اختيار محرك التشغيل للتلفاز", listOf("ExoPlayer", "VLC"), "ExoPlayer"),
                    TvOption("tv_player_mode", "Current Player Mode", "الإعداد الحالي القديم محفوظ كما هو", listOf("Auto", "HLS", "Progressive"), PlayerPrefs.getModeLabel(this), "player_mode"),
                    TvOption("hardware_acceleration", "Hardware Acceleration", "تفعيل GPU لتحسين تشغيل الفيديو", listOf("ON", "OFF"), "ON"),
                    TvOption("auto_play_next", "Auto Play Next", "تشغيل القناة التالية تلقائياً", listOf("OFF", "ON"), "OFF"),
                    TvOption("connection_timeout", "Connection Timeout", "مدة الانتظار قبل إلغاء الاتصال", listOf("5s", "10s", "15s", "30s"), "10s"),
                    TvOption("retry_on_error", "Retry On Error", "عدد المحاولات عند انقطاع البث", listOf("1", "3", "5"), "3")
                )
            ),
            TvCategory(
                "📅 EPG",
                listOf(
                    TvOption("epg_source_url", "EPG Source URL", "رابط مصدر دليل البرامج", listOf("اضغط OK للإدخال"), "", "epg_url"),
                    TvOption("epg_auto_update", "Auto Update EPG", "تحديث دليل البرامج تلقائياً", listOf("OFF", "Daily", "Weekly"), "Daily"),
                    TvOption("epg_language", "EPG Language", "لغة دليل البرامج", listOf("العربية", "Français", "English"), "العربية")
                )
            ),
            TvCategory(
                "🌐 التطبيق",
                listOf(
                    TvOption("app_language", "Language", "الإعداد القديم للغة محفوظ ومتاح هنا", listOf("العربية", "Français", "English"), LanguagePrefs.getLanguageName(this), "language"),
                    TvOption("clear_cache", "Clear Cache", "مسح التخزين المؤقت مع تأكيد", listOf("اضغط OK"), "اضغط OK", "clear_cache"),
                    TvOption("auto_update_app", "Auto Update App", "تحديث تلقائي للتطبيق", listOf("ON", "OFF"), "ON"),
                    TvOption("server_sync", "Server Sync", "تحديث السيرفر الآن + عرض آخر تحقق", listOf("اضغط OK"), "اضغط OK", "server_sync"),
                    TvOption("parental_control", "Parental Control", "تفعيل رقابة أولياء الأمور", listOf("OFF", "ON"), "OFF"),
                    TvOption("parental_pin", "Parental PIN", "تعيين أو تغيير PIN", listOf("اضغط OK"), "اضغط OK", "parental_pin"),
                    TvOption("support", "WhatsApp Support", "الإعداد/الزر القديم للدعم محفوظ", listOf("اضغط OK"), "اضغط OK", "support"),
                    TvOption("change_user", "Change User", "الإعداد/الزر القديم لتغيير المستخدم محفوظ", listOf("اضغط OK"), "اضغط OK", "change_user"),
                    TvOption("manual_source", "Manual Source", "الإعداد/الزر القديم لإضافة مصدر يدوي محفوظ", listOf("اضغط OK"), "اضغط OK", "manual_source")
                )
            ),
            TvCategory(
                "📺 التلفاز",
                listOf(
                    TvOption("focus_animation_speed", "Focus Animation Speed", "سرعة انتقال مؤشر التحديد", listOf("Slow", "Medium", "Fast"), "Medium"),
                    TvOption("screen_saver", "Screen Saver", "تشغيل شاشة التوقف بعد عدم التفاعل", listOf("OFF", "5 min", "10 min", "15 min", "30 min"), "10 min"),
                    TvOption("cec_control", "CEC Control", "التحكم عبر ريموت التلفاز الأصلي", listOf("OFF", "ON"), "OFF"),
                    TvOption("hdr_4k_support", "4K/HDR Support", "دعم الدقة العالية", listOf("ON", "OFF"), "ON"),
                    TvOption("remote_sensitivity", "Remote Sensitivity", "حساسية الريموت", listOf("Low", "Medium", "High"), "Medium")
                )
            )
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        TvUtils.applyOrientation(this)
        ThemeManager.apply(this)

        if (TvUtils.isTv(this)) {
            setupTvSettings()
        } else {
            setupPhoneSettings()
        }
    }

    private fun setupPhoneSettings() {
        setContentView(R.layout.activity_settings)
        com.latchi.iptv.utils.FloatingBackHelper.setup(this)

        languageText = findViewById(R.id.languageText)
        playerModeText = findViewById(R.id.playerModeText)
        expiryInfoText = findViewById(R.id.expiryInfoText)

        findViewById<TextView>(R.id.backButton).setOnClickListener { finish() }
        findViewById<TextView>(R.id.langArabic).setOnClickListener { setLang("ar") }
        findViewById<TextView>(R.id.langFrench).setOnClickListener { setLang("fr") }
        findViewById<TextView>(R.id.langEnglish).setOnClickListener { setLang("en") }
        findViewById<TextView>(R.id.playerAuto).setOnClickListener { setPlayerMode(PlayerPrefs.MODE_AUTO) }
        findViewById<TextView>(R.id.playerHls).setOnClickListener { setPlayerMode(PlayerPrefs.MODE_HLS) }
        findViewById<TextView>(R.id.playerProgressive).setOnClickListener { setPlayerMode(PlayerPrefs.MODE_PROGRESSIVE) }

        findViewById<TextView>(R.id.supportButton).setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/213798712450")))
        }
        findViewById<TextView>(R.id.changeUserButton).setOnClickListener {
            startActivity(Intent(this, UserListActivity::class.java).putExtra("show_settings", true))
            finish()
        }
        findViewById<TextView>(R.id.manualButton).setOnClickListener {
            startActivity(Intent(this, ManualSourceActivity::class.java))
        }
        findViewById<TextView>(R.id.clearCacheButton).setOnClickListener {
            clearActiveProfileCache(showConfirm = false)
        }
        addServerSyncSection()
        updateLanguageText()
        updatePlayerText()
        updateExpiryInfo()
        updatePhoneOptionMarks()
        refreshServerInfoTexts()
    }

    private fun setupTvSettings() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(18), dp(24), dp(18))
            setBackgroundResource(R.drawable.bg_app)
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val back = tvText("←  ${getString(R.string.settings)}", 24f, true).apply {
            gravity = Gravity.CENTER_VERTICAL
            isFocusable = true
            isClickable = true
            setPadding(dp(18), 0, dp(18), 0)
            background = rounded(0x663B2752, 0xFFFFD700.toInt(), dp(1))
            setOnClickListener { finish() }
            setOnKeyListener { _, keyCode, event ->
                if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_ESCAPE) {
                    finish()
                    true
                } else false
            }
            applyTvFocusEffect(this)
        }
        header.addView(back, LinearLayout.LayoutParams(0, dp(58), 1f))

        val info = tvText(activeProfileInfoForTv(), 14f, false).apply {
            gravity = Gravity.CENTER
            setTextColor(0xFFFFD700.toInt())
            background = rounded(0xAA101024.toInt(), 0x44FFD700, dp(1))
            setPadding(dp(12), dp(4), dp(12), dp(4))
        }
        val infoParams = LinearLayout.LayoutParams(0, dp(58), 1f).apply { marginStart = dp(14) }
        header.addView(info, infoParams)
        root.addView(header, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(58)))

        val body = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val bodyParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(16) }
        root.addView(body, bodyParams)

        val categoryScroll = ScrollView(this).apply { isFillViewport = true }
        val categoryContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(10), dp(10), dp(10))
            background = rounded(0xAA140B22.toInt(), 0x44FFD700, dp(1))
        }
        categoryScroll.addView(categoryContainer, android.widget.FrameLayout.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT))
        body.addView(categoryScroll, LinearLayout.LayoutParams(dp(260), LinearLayout.LayoutParams.MATCH_PARENT))

        val detailPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(16))
            background = rounded(0xAA1A102C.toInt(), 0x55FFFFFF, dp(1))
        }
        val detailParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply { marginStart = dp(16) }
        body.addView(detailPanel, detailParams)

        tvDetailsTitle = tvText("", 22f, true).apply {
            setTextColor(0xFFFFD700.toInt())
            gravity = Gravity.CENTER_VERTICAL
        }
        detailPanel.addView(tvDetailsTitle, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(46)))

        val hint = tvText("↑↓ تنقل  •  ←→ تغيير القيمة  •  OK تفعيل/تعديل  •  BACK رجوع", 13f, false).apply {
            gravity = Gravity.CENTER_VERTICAL
            setTextColor(0xFFDDDDDD.toInt())
        }
        detailPanel.addView(hint, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(32)))

        val detailScroll = ScrollView(this).apply { isFillViewport = true }
        tvDetailsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(10), 0, dp(10))
        }
        detailScroll.addView(tvDetailsContainer, android.widget.FrameLayout.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT))
        detailPanel.addView(detailScroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        tvCategoryViews.clear()
        tvCategories.forEachIndexed { index, category ->
            val view = tvText(category.title, 18f, true).apply {
                gravity = Gravity.CENTER_VERTICAL
                isFocusable = true
                isClickable = true
                setPadding(dp(18), 0, dp(10), 0)
                setOnClickListener { selectTvCategory(index, focusDetails = true) }
                setOnKeyListener { _, keyCode, event ->
                    if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                    when (keyCode) {
                        KeyEvent.KEYCODE_DPAD_CENTER,
                        KeyEvent.KEYCODE_ENTER,
                        KeyEvent.KEYCODE_NUMPAD_ENTER,
                        KeyEvent.KEYCODE_DPAD_RIGHT -> {
                            selectTvCategory(index, focusDetails = true)
                            true
                        }
                        KeyEvent.KEYCODE_BACK,
                        KeyEvent.KEYCODE_ESCAPE -> {
                            finish()
                            true
                        }
                        else -> false
                    }
                }
                applyTvFocusEffect(this)
            }
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(58)).apply { bottomMargin = dp(10) }
            categoryContainer.addView(view, lp)
            tvCategoryViews.add(view)
        }

        setContentView(root)
        com.latchi.iptv.utils.FloatingBackHelper.setup(this)
        selectTvCategory(0, focusDetails = false)
        tvCategoryViews.firstOrNull()?.requestFocus()
    }

    private fun selectTvCategory(index: Int, focusDetails: Boolean) {
        selectedTvCategory = index
        tvCategoryViews.forEachIndexed { i, textView ->
            textView.background = if (i == index) {
                rounded(0xFF8B5CF6.toInt(), 0xFFFFD700.toInt(), dp(2))
            } else {
                rounded(0x663B2752, 0x33FFFFFF, dp(1))
            }
        }

        val category = tvCategories[index]
        tvDetailsTitle.text = "⚙️ ${category.title}"
        tvDetailsContainer.removeAllViews()

        category.options.forEach { option ->
            val row = createTvOptionRow(option)
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(74)).apply { bottomMargin = dp(10) }
            tvDetailsContainer.addView(row, lp)
        }
        if (focusDetails) {
            tvDetailsContainer.getChildAt(0)?.requestFocus()
        }
    }

    private fun createTvOptionRow(option: TvOption): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isFocusable = true
            isClickable = true
            setPadding(dp(16), dp(8), dp(16), dp(8))
            background = rounded(0x663B2752, 0x22FFFFFF, dp(1))
        }

        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val title = tvText(option.title, 16f, true).apply { gravity = Gravity.CENTER_VERTICAL }
        val sub = tvText(option.subtitle, 12f, false).apply {
            setTextColor(0xFFD7CCE8.toInt())
            gravity = Gravity.CENTER_VERTICAL
        }
        texts.addView(title, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        texts.addView(sub, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        row.addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))

        val value = tvText(displayValue(option), 15f, true).apply {
            gravity = Gravity.CENTER
            setTextColor(0xFFFFD700.toInt())
            background = rounded(0xAA080812.toInt(), 0x44FFD700, dp(1))
            setPadding(dp(10), 0, dp(10), 0)
        }
        row.addView(value, LinearLayout.LayoutParams(dp(190), dp(46)))

        row.setOnClickListener { handleTvOptionOk(option, value) }
        row.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    changeTvOption(option, value, -1)
                    true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    changeTvOption(option, value, 1)
                    true
                }
                KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                    handleTvOptionOk(option, value)
                    true
                }
                KeyEvent.KEYCODE_TAB -> {
                    row.focusSearch(View.FOCUS_DOWN)?.requestFocus()
                    true
                }
                KeyEvent.KEYCODE_BACK,
                KeyEvent.KEYCODE_ESCAPE -> {
                    finish()
                    true
                }
                else -> false
            }
        }
        applyTvFocusEffect(row)
        return row
    }

    private fun handleTvOptionOk(option: TvOption, valueView: TextView) {
        when (option.action) {
            "epg_url" -> showTextInputDialog("EPG Source URL", tvPrefs.getString(option.key, "") ?: "", false) { text ->
                tvPrefs.edit().putString(option.key, text).apply()
                valueView.text = displayValue(option)
                toastSaved()
            }
            "parental_pin" -> showTextInputDialog("Parental PIN", tvPrefs.getString(option.key, "") ?: "", true) { text ->
                tvPrefs.edit().putString(option.key, text).apply()
                valueView.text = if (text.isBlank()) "غير محدد" else "••••"
                toastSaved()
            }
            "clear_cache" -> clearActiveProfileCache(showConfirm = true)
            "support" -> startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/213798712450")))
            "change_user" -> {
                startActivity(Intent(this, UserListActivity::class.java).putExtra("show_settings", true))
                finish()
            }
            "manual_source" -> startActivity(Intent(this, ManualSourceActivity::class.java))
            "server_sync" -> forceServerSyncFromSettings()
            else -> changeTvOption(option, valueView, 1)
        }
    }

    private fun changeTvOption(option: TvOption, valueView: TextView, direction: Int) {
        if (option.values.size <= 1) {
            handleTvOptionOk(option, valueView)
            return
        }
        val current = displayValue(option)
        val currentIndex = option.values.indexOf(current).let { if (it >= 0) it else option.values.indexOf(option.defaultValue).coerceAtLeast(0) }
        val nextIndex = (currentIndex + direction + option.values.size) % option.values.size
        val next = option.values[nextIndex]

        when (option.action) {
            "language" -> {
                val lang = when (next) {
                    "Français" -> "fr"
                    "English" -> "en"
                    else -> "ar"
                }
                LanguagePrefs.setLanguage(this, lang)
            }
            "player_mode" -> {
                val mode = when (next) {
                    "HLS" -> PlayerPrefs.MODE_HLS
                    "Progressive" -> PlayerPrefs.MODE_PROGRESSIVE
                    else -> PlayerPrefs.MODE_AUTO
                }
                PlayerPrefs.setMode(this, mode)
            }
            "theme" -> {
                tvPrefs.edit().putString(option.key, next).apply()
                when (next) {
                    "Dark" -> ThemeManager.setTheme(this, "dark")
                    "Default" -> ThemeManager.setTheme(this, "default")
                }
            }
            else -> tvPrefs.edit().putString(option.key, next).apply()
        }
        valueView.text = displayValue(option)
        toastSaved()
    }

    private fun displayValue(option: TvOption): String {
        val valStr = when (option.action) {
            "language" -> LanguagePrefs.getLanguageName(this)
            "player_mode" -> PlayerPrefs.getModeLabel(this)
            "epg_url" -> {
                val v = tvPrefs.getString(option.key, "") ?: ""
                if (v.isBlank()) "غير محدد" else "تم الإدخال"
            }
            "parental_pin" -> {
                val v = tvPrefs.getString(option.key, "") ?: ""
                if (v.isBlank()) "غير محدد" else "••••"
            }
            else -> tvPrefs.getString(option.key, option.defaultValue) ?: option.defaultValue
        }
        // إضافة علامة صح للتأكيد أن هذه هي القيمة النشطة حالياً
        return if (valStr.isBlank()) valStr else "✓ $valStr"
    }

    private fun showTextInputDialog(title: String, currentValue: String, numeric: Boolean, onSave: (String) -> Unit) {
        val input = EditText(this).apply {
            setText(currentValue)
            setSingleLine(true)
            inputType = if (numeric) InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setTextColor(Color.WHITE)
            setHintTextColor(0xFFBBBBBB.toInt())
            hint = title
        }
        val wrapper = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(input, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(wrapper)
            .setPositiveButton("OK") { _, _ -> onSave(input.text.toString().trim()) }
            .setNegativeButton(getString(R.string.close), null)
            .show()
        input.requestFocus()
    }

    private fun clearActiveProfileCache(showConfirm: Boolean) {
        val active = SourcePrefs.getActiveProfile(this)
        if (active == null) {
            Toast.makeText(this, getString(R.string.logged_in) + ": -", Toast.LENGTH_SHORT).show()
            return
        }
        val action = {
            ChannelCache.clear(this, active.id)
            com.latchi.iptv.utils.CustomOverlayHelper.show(this, "ذاكرة", getString(R.string.cache_cleared), true)
        }
        if (showConfirm) {
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.clear_cache))
                .setMessage("هل تريد مسح التخزين المؤقت؟")
                .setPositiveButton("OK") { _, _ -> action() }
                .setNegativeButton(getString(R.string.close), null)
                .show()
        } else {
            action()
        }
    }

    private fun activeProfileInfoForTv(): String {
        val active = SourcePrefs.getActiveProfile(this) ?: return "${getString(R.string.logged_in)}: -"
        return buildString {
            append("${getString(R.string.logged_in)}: ${active.name}")
            if (active.activationCode.isNotBlank() && active.activationCode != "MANUAL") {
                append("   •   ${getString(R.string.expiry)}: ${active.expiresAt}")
            }
        }
    }

    private fun tvText(text: String, sp: Float, bold: Boolean): TextView {
        return TextView(this).apply {
            this.text = text
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
            typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            includeFontPadding = false
        }
    }

    private fun applyTvFocusEffect(view: View) {
        view.setOnFocusChangeListener { v, hasFocus ->
            val speed = tvPrefs.getString("focus_animation_speed", "Medium") ?: "Medium"
            val duration = when (speed) {
                "Slow" -> 220L
                "Fast" -> 80L
                else -> 140L
            }
            if (hasFocus) {
                v.animate().scaleX(1.035f).scaleY(1.035f).setDuration(duration).start()
                v.background = rounded(0x995A35A0.toInt(), 0xFFFFD700.toInt(), dp(3))
            } else {
                v.animate().scaleX(1f).scaleY(1f).setDuration(duration).start()
                val categoryIndex = tvCategoryViews.indexOfFirst { it === v }
                if (categoryIndex == -1) {
                    v.background = rounded(0x663B2752, 0x22FFFFFF, dp(1))
                } else {
                    v.background = if (categoryIndex == selectedTvCategory) rounded(0xFF8B5CF6.toInt(), 0xFFFFD700.toInt(), dp(2)) else rounded(0x663B2752, 0x33FFFFFF, dp(1))
                }
            }
        }
    }

    private fun rounded(color: Int, strokeColor: Int, strokeWidth: Int): GradientDrawable {
        return GradientDrawable().apply {
            cornerRadius = dp(18).toFloat()
            setColor(color)
            setStroke(strokeWidth, strokeColor)
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun toastSaved() {
        com.latchi.iptv.utils.CustomOverlayHelper.show(this, "حفظ", getString(R.string.player_saved), true)
    }

    private fun addServerSyncSection() {
        val content = findViewById<android.view.ViewGroup>(android.R.id.content)
        val root = ((content.getChildAt(0) as? ScrollView)?.getChildAt(0) as? LinearLayout) ?: return

        serverStatusText = TextView(this).apply {
            text = "حالة السيرفر: --"
            setTextColor(Color.WHITE)
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = rounded(0xAA101024.toInt(), 0x44FFD700, dp(1))
        }
        root.addView(serverStatusText, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })

        lastSyncText = TextView(this).apply {
            text = "آخر تحقق من السيرفر: --"
            setTextColor(0xFFFFD700.toInt())
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = rounded(0x663B2752, 0x33FFFFFF, dp(1))
        }
        root.addView(lastSyncText, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })

        val syncButton = TextView(this).apply {
            text = "⚡ تحديث السيرفر الآن"
            setTextColor(Color.WHITE)
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            foreground = getDrawable(R.drawable.focus_selector)
            setPadding(dp(12), 0, dp(12), 0)
            background = rounded(0xFF8B5CF6.toInt(), 0xFFFFD700.toInt(), dp(2))
            setOnClickListener { forceServerSyncFromSettings() }
        }
        root.addView(syncButton, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(56)).apply { topMargin = dp(10) })
    }

    private fun forceServerSyncFromSettings() {
        Toast.makeText(this, "جاري التحقق من السيرفر...", Toast.LENGTH_SHORT).show()
        ServerSyncManager.checkForServerUpdate(this, force = true) { result ->
            refreshServerInfoTexts()
            if (result.changed) {
                ServerUpdateOverlayHelper.show(this) {
                    val intent = Intent(this, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK }
                    startActivity(intent)
                    finish()
                }
            } else {
                val msg = if (result.message.startsWith("server_offline")) "السيرفر الجديد غير متاح، لم يتم التبديل" else "السيرفر محدث بالفعل ✓"
                com.latchi.iptv.utils.CustomOverlayHelper.show(this, "تحديث", msg, result.message.startsWith("server_offline").not())
            }
        }
    }

    private fun refreshServerInfoTexts() {
        val last = ServerSyncManager.lastSyncAt(this)
        lastSyncText?.text = "آخر تحقق من السيرفر: " + if (last == 0L) "--" else java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(last))
        val active = SourcePrefs.getActiveProfile(this)
        if (active == null || active.m3uUrl.isBlank()) {
            serverStatusText?.text = "حالة السيرفر: --"
            return
        }
        Thread {
            val h = ServerHealthChecker.check(active.m3uUrl)
            runOnUiThread { serverStatusText?.text = if (h.online) "حالة السيرفر: ✅ أونلاين ${if (h.responseMs > 0) "(${h.responseMs}ms)" else ""}" else "حالة السيرفر: ❌ غير متاح" }
        }.start()
    }

    private fun updatePhoneOptionMarks() {
        val lang = LanguagePrefs.getLanguage(this)
        markText(findViewById(R.id.langArabic), lang == "ar", "✓ العربية", "العربية")
        markText(findViewById(R.id.langFrench), lang == "fr", "✓ Français", "Français")
        markText(findViewById(R.id.langEnglish), lang == "en", "✓ English", "English")
        val mode = PlayerPrefs.getMode(this)
        markText(findViewById(R.id.playerAuto), mode == PlayerPrefs.MODE_AUTO, "✓ Auto", "Auto")
        markText(findViewById(R.id.playerHls), mode == PlayerPrefs.MODE_HLS, "✓ HLS", "HLS")
        markText(findViewById(R.id.playerProgressive), mode == PlayerPrefs.MODE_PROGRESSIVE, "✓ Prog", "Prog")
    }

    private fun markText(view: TextView, active: Boolean, activeText: String, normalText: String) {
        view.text = if (active) activeText else normalText
        view.setTextColor(if (active) 0xFFFFD700.toInt() else Color.WHITE)
        view.setBackgroundResource(if (active) R.drawable.bg_button_primary else R.drawable.bg_panel)
    }

    private fun setLang(lang: String) {
        if (LanguagePrefs.getLanguage(this) == lang) return
        LanguagePrefs.setLanguage(this, lang)
        com.latchi.iptv.utils.CustomOverlayHelper.show(this, "لغة", getString(R.string.language_saved), true)
        val intent = Intent(this, MainActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        finish()
    }

    private fun updateLanguageText() {
        languageText.text = "${getString(R.string.language)}: ${LanguagePrefs.getLanguageName(this)}"
    }

    private fun setPlayerMode(mode: String) {
        PlayerPrefs.setMode(this, mode)
        updatePlayerText()
        updatePhoneOptionMarks()
        Toast.makeText(this, getString(R.string.player_saved), Toast.LENGTH_SHORT).show()
    }

    private fun updatePlayerText() {
        playerModeText.text = "${getString(R.string.player_mode)}: ${PlayerPrefs.getModeLabel(this)}"
    }

    private fun updateExpiryInfo() {
        val active = SourcePrefs.getActiveProfile(this)
        if (active == null) {
            expiryInfoText.text = getString(R.string.logged_in) + ": -"
            return
        }
        val expiry = active.expiresAt
        val code = active.activationCode
        val name = active.name
        val info = buildString {
            append("${getString(R.string.logged_in)}: $name\n")
            if (code.isNotBlank() && code != "MANUAL") {
                append("${getString(R.string.expiry)}: $expiry\n")
                append("Code: $code")
            }
        }
        expiryInfoText.text = info
    }

    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent?): Boolean {
        if (com.latchi.iptv.utils.TvFocusHelper.handleKey(this, keyCode, event)) return true
        return super.onKeyDown(keyCode, event)
    }

}
