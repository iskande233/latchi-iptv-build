package com.latchi.iptv.screens

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.source.DefaultMediaSourceFactory
import com.google.android.exoplayer2.ui.AspectRatioFrameLayout
import com.google.android.exoplayer2.ui.PlayerView
import com.google.android.exoplayer2.upstream.DefaultHttpDataSource
import com.latchi.iptv.R
import com.latchi.iptv.model.Channel
import com.latchi.iptv.utils.ChannelCache
import com.latchi.iptv.utils.DigitNormalizer
import com.latchi.iptv.utils.SourcePrefs
import com.latchi.iptv.utils.ThemeManager
import com.latchi.iptv.utils.TvFocusHelper

/**
 * 📺 TvLivePreviewActivity — الواجهة الموحدة الاحترافية للتلفاز
 *
 * الهيكل (RTL — من اليمين لليسار بالعربية):
 * ┌─────────────────────────────────────────────────────────────┐
 * │  الأيمن (0.26f)  │  الأوسط (0.36f)  │  الأيسر (0.38f)   │
 * │  📁 الفئات       │  📺 القنوات       │  ▶ الفيديو         │
 * │  + حروف فئات    │  + حروف قنوات    │  + تفاصيل           │
 * └─────────────────────────────────────────────────────────────┘
 *
 * الحروف الأبجدية:
 * - عند الفوكس على عمود الفئات → الحروف تفلتر الفئات
 * - عند الفوكس على عمود القنوات → الحروف تفلتر القنوات
 *
 * ميزات:
 * 1. تكبير داخلي (In-place Fullscreen) — البث لا ينقطع أبداً
 * 2. تصدر beIN Sports أوتوماتيكياً
 * 3. لا كراش — كل شيء محاط بـ try-catch
 */
class TvLivePreviewActivity : AppCompatActivity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(com.latchi.iptv.utils.LocaleHelper.wrap(newBase))
    }

    // ── State ────────────────────────────────────────────────────
    private var selected: Channel? = null
    private var allLiveChannels: List<Channel> = emptyList()
    private var currentCategories: List<String> = emptyList()
    private var selectedCategoryName: String = ""
    private var currentCategoryChannels: List<Channel> = emptyList()

    // ── تتبع الفوكس — أي عمود نشط حالياً
    private enum class ActivePanel { CATEGORIES, CHANNELS }
    private var activePanel: ActivePanel = ActivePanel.CATEGORIES

    // ── الحرف النشط لكل عمود
    private var activeCatLetter: String? = null
    private var activeChLetter: String? = null

    // ── ExoPlayer ────────────────────────────────────────────────
    private var player: ExoPlayer? = null
    private lateinit var playerView: PlayerView
    private lateinit var videoFrame: FrameLayout
    private var currentPlayingUrl: String? = null
    private var isFullscreenMode = false

    // ── Views ─────────────────────────────────────────────────────
    private lateinit var mainContainer: LinearLayout
    private lateinit var categoriesPanel: LinearLayout
    private lateinit var channelsPanel: LinearLayout
    private lateinit var playerPanel: LinearLayout

    private lateinit var categoriesRecycler: RecyclerView
    private lateinit var categoriesAdapter: CategoriesAdapter
    private lateinit var channelsRecycler: RecyclerView
    private lateinit var channelsAdapter: ChannelsAdapter

    private lateinit var titleText: TextView
    private lateinit var catSubtitleText: TextView
    private lateinit var epgText: TextView
    private lateinit var detailsText: TextView
    private lateinit var hintText: TextView

    // شريط الحروف المشترك
    private lateinit var alphabetContainer: LinearLayout
    private var letterButtons: List<TextView> = emptyList()

    companion object {
        fun start(context: Context, channel: Channel, category: String) {
            context.startActivity(Intent(context, TvLivePreviewActivity::class.java).apply {
                putExtra("channel", channel)
                putExtra("category", category)
            })
        }

        fun startWithChannels(
            context: Context,
            channel: Channel,
            channels: List<Channel>,
            categoryLabel: String
        ) {
            context.startActivity(Intent(context, TvLivePreviewActivity::class.java).apply {
                putExtra("channel", channel)
                putExtra("extra_channels", ArrayList(channels))
                putExtra("category", categoryLabel)
            })
        }

        /** يُستدعى من HomeFragment على TV بكل القنوات الحية */
        fun startAllChannels(context: Context, channels: List<Channel>) {
            // نفتح الشاشة دائماً — حتى لو القنوات فارغة ستُحمل من الكاش داخلياً
            val liveChannels = channels.filter { it.contentType == "live" }.ifEmpty { channels }
            val first = liveChannels.firstOrNull()

            context.startActivity(Intent(context, TvLivePreviewActivity::class.java).apply {
                if (first != null) putExtra("channel", first)
                putExtra("extra_channels", ArrayList(liveChannels))
                putExtra("category", "")
                putExtra("load_all", true)
            })
        }
    }

    // ─────────────────────────────────────────────────────────────
    // onCreate
    // ─────────────────────────────────────────────────────────────
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        try { ThemeManager.apply(this) } catch (_: Exception) {}

        // جلب البيانات
        selected = intent.getParcelableExtra("channel")

        val customList = intent.getParcelableArrayListExtra<Channel>("extra_channels")
        val active = SourcePrefs.getActiveProfile(this)

        // نحمل القنوات: من الـ intent أولاً، ثم الكاش، ثم القناة الواحدة
        val fromIntent = customList?.filter { it.contentType == "live" }?.ifEmpty { customList }
        val fromCache = if (active != null) {
            ChannelCache.load(this, active.id).filter { it.contentType == "live" }
        } else emptyList()

        allLiveChannels = when {
            !fromIntent.isNullOrEmpty() -> fromIntent
            fromCache.isNotEmpty()      -> fromCache
            selected != null            -> listOf(selected!!)
            else                        -> emptyList()
        }

        // إذا ما في قنوات خالص → نخرج
        if (allLiveChannels.isEmpty()) {
            Toast.makeText(this, "⏳ القنوات لم تُحمل بعد، افتح التطبيق وانتظر لحظة", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        // نختار أول قناة إذا لم تُحدد
        if (selected == null) selected = allLiveChannels.first()

        // تحضير الفئات
        prepareSmartCategories()

        // بناء الواجهة
        buildUi()

        // معالج زر Back
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (isFullscreenMode) toggleFullscreen(false)
                else finish()
            }
        })
    }

    // ─────────────────────────────────────────────────────────────
    // تحضير الفئات الذكي
    // ─────────────────────────────────────────────────────────────
    private fun getHiddenSet(): Set<String> {
        return try {
            val active = SourcePrefs.getActiveProfile(this) ?: return emptySet()
            val s = getSharedPreferences("server_sync_prefs", Context.MODE_PRIVATE)
                .getString("hidden_categories_${active.id}", "") ?: ""
            s.split(",").map { it.trim().lowercase() }.filter { it.isNotBlank() }.toSet()
        } catch (_: Exception) { emptySet() }
    }

    private fun prepareSmartCategories() {
        try {
            val hiddenSet = getHiddenSet()
            val rawCats = allLiveChannels
                .filter { !hiddenSet.contains(it.category.trim().lowercase()) }
                .map { it.category }
                .distinct()
                .filter { it.isNotBlank() }

            // تصدر beIN Sports أوتوماتيكياً
            val beinCat = rawCats.firstOrNull { cat ->
                val l = cat.lowercase()
                l.contains("sport ar") || l.contains("bein") || l.contains("sports")
            } ?: rawCats.firstOrNull() ?: ""

            val others = rawCats.filter { !it.equals(beinCat, true) }.sorted()
            currentCategories = if (beinCat.isNotBlank()) listOf(beinCat) + others else others

            selectedCategoryName = beinCat.ifBlank { currentCategories.firstOrNull() ?: "" }
            loadCategoryChannels(selectedCategoryName, null)
        } catch (_: Exception) {
            currentCategories = allLiveChannels.map { it.category }.distinct().sorted()
            selectedCategoryName = currentCategories.firstOrNull() ?: ""
            currentCategoryChannels = allLiveChannels
        }
    }

    private fun loadCategoryChannels(catName: String, filterLetter: String?) {
        try {
            selectedCategoryName = catName
            activeCatLetter = null // reset حرف الفئات

            var channels = if (catName.isBlank()) allLiveChannels
            else allLiveChannels.filter { it.category.equals(catName, true) }
            if (channels.isEmpty()) channels = allLiveChannels

            // ذكاء beIN — تصدر MAX
            val isBein = catName.lowercase().let {
                it.contains("sport ar") || it.contains("bein") || it.contains("sports")
            }
            if (isBein) {
                val pureBein = channels.filter { it.name.lowercase().contains("bein") }
                val base = if (pureBein.isNotEmpty()) pureBein else channels
                channels = base.sortedWith(
                    compareByDescending<Channel> {
                        it.name.lowercase().let { n -> n.contains("max") || n.contains("beinmax") }
                    }.thenBy { it.name }
                )
            }

            // تطبيق فلتر الحرف إن وجد
            currentCategoryChannels = if (filterLetter != null && filterLetter != "All")
                channels.filter { it.name.startsWith(filterLetter, ignoreCase = true) }
            else channels

            // تحديث UI إذا جاهز
            if (::channelsAdapter.isInitialized) {
                channelsAdapter.update(currentCategoryChannels)
                if (::channelsRecycler.isInitialized)
                    channelsRecycler.scrollToPosition(0)
            }
            if (::categoriesAdapter.isInitialized)
                categoriesAdapter.setSelected(catName)

        } catch (_: Exception) {}
    }

    private fun filterChannelsByLetter(letter: String?) {
        try {
            activeChLetter = letter
            val channels = if (selectedCategoryName.isBlank()) allLiveChannels
            else allLiveChannels.filter { it.category.equals(selectedCategoryName, true) }

            val filtered = if (letter != null && letter != "All")
                channels.filter { it.name.startsWith(letter, ignoreCase = true) }
            else channels

            if (::channelsAdapter.isInitialized) {
                channelsAdapter.update(filtered)
                if (::channelsRecycler.isInitialized) channelsRecycler.scrollToPosition(0)
            }
        } catch (_: Exception) {}
    }

    private fun filterCategoriesByLetter(letter: String?) {
        try {
            activeCatLetter = letter
            val filtered = if (letter != null && letter != "All")
                currentCategories.filter { it.startsWith(letter, ignoreCase = true) }
            else currentCategories

            if (::categoriesAdapter.isInitialized) categoriesAdapter.updateList(filtered)
        } catch (_: Exception) {}
    }

    // ─────────────────────────────────────────────────────────────
    // بناء الواجهة
    // ─────────────────────────────────────────────────────────────
    private fun buildUi() {
        // الحاوية الرئيسية الأفقية
        mainContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundResource(R.drawable.bg_app)
        }
        setContentView(mainContainer)

        buildCategoriesPanel()   // الأيمن
        buildChannelsPanel()     // الأوسط
        buildPlayerPanel()       // الأيسر

        // فوكس تلقائي على أول فئة
        categoriesRecycler.post {
            try {
                categoriesRecycler.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus()
                activePanel = ActivePanel.CATEGORIES
                updateAlphabetForPanel(ActivePanel.CATEGORIES)
            } catch (_: Exception) {}
        }

        // تشغيل أول قناة تلقائياً
        val firstCh = currentCategoryChannels.firstOrNull() ?: selected
        firstCh?.let { playChannel(it) }
    }

    private fun buildCategoriesPanel() {
        categoriesPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(6), dp(8), dp(6), dp(8))
        }
        mainContainer.addView(
            categoriesPanel,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 0.22f)
        )

        // عنوان العمود
        categoriesPanel.addView(buildColumnTitle("📁 الفئات"))

        // شريط الحروف
        buildAlphabetBar()
        categoriesPanel.addView(buildAlphabetScrollView())

        // قائمة الفئات
        categoriesRecycler = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@TvLivePreviewActivity)
            clipToPadding = false
        }
        categoriesAdapter = CategoriesAdapter(currentCategories, selectedCategoryName) { cat ->
            loadCategoryChannels(cat, null)
            // انتقل الفوكس لأول قناة في العمود الأوسط
            channelsRecycler.postDelayed({
                channelsRecycler.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus()
                activePanel = ActivePanel.CHANNELS
                updateAlphabetForPanel(ActivePanel.CHANNELS)
            }, 80)
        }
        categoriesRecycler.adapter = categoriesAdapter
        TvFocusHelper.setupRecycler(categoriesRecycler)

        categoriesRecycler.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                activePanel = ActivePanel.CATEGORIES
                updateAlphabetForPanel(ActivePanel.CATEGORIES)
            }
        }

        categoriesPanel.addView(
            categoriesRecycler,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        )
    }

    private fun buildChannelsPanel() {
        channelsPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(8), dp(6), dp(8))
        }
        mainContainer.addView(
            channelsPanel,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 0.33f)
        )

        channelsPanel.addView(buildColumnTitle("📺 القنوات"))

        // spacer محاذاة شريط الحروف
        channelsPanel.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(36)
            ).apply { bottomMargin = dp(6) }
        })

        channelsRecycler = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@TvLivePreviewActivity)
            clipToPadding = false
        }
        channelsAdapter = ChannelsAdapter(currentCategoryChannels) { ch ->
            if (currentPlayingUrl == ch.streamUrl && player?.isPlaying == true) {
                // نفس القناة شغالة → كبّر الفيديو
                toggleFullscreen(true)
            } else {
                // قناة جديدة → شغّلها
                playChannel(ch)
            }
        }
        channelsRecycler.adapter = channelsAdapter
        TvFocusHelper.setupRecycler(channelsRecycler)

        channelsRecycler.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                activePanel = ActivePanel.CHANNELS
                updateAlphabetForPanel(ActivePanel.CHANNELS)
            }
        }

        channelsPanel.addView(
            channelsRecycler,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        )
    }

    private fun buildPlayerPanel() {
        playerPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(6), dp(8), dp(8), dp(8))
        }
        mainContainer.addView(
            playerPanel,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 0.45f)
        )

        titleText = TextView(this).apply {
            text = selected?.name ?: ""
            setTextColor(Color.parseColor("#FFD700"))
            textSize = 13f
            gravity = Gravity.CENTER
            setTypeface(null, Typeface.BOLD)
            maxLines = 1
            setPadding(0, 0, 0, dp(2))
        }
        playerPanel.addView(titleText, lp())

        catSubtitleText = TextView(this).apply {
            text = "📂 $selectedCategoryName"
            setTextColor(Color.parseColor("#7FE6FF"))
            textSize = 10f
            gravity = Gravity.CENTER
            maxLines = 1
            setPadding(0, 0, 0, dp(2))
        }
        playerPanel.addView(catSubtitleText, lp())

        epgText = TextView(this).apply {
            text = "⏳ جاري التحميل..."
            setTextColor(Color.parseColor("#A5B4FC"))
            textSize = 10f
            gravity = Gravity.CENTER
            maxLines = 1
            setPadding(0, 0, 0, dp(4))
        }
        playerPanel.addView(epgText, lp())

        // مشغل الفيديو
        videoFrame = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            isFocusable = true
            isClickable = true
            background = GradientDrawable().apply {
                setColor(Color.BLACK)
                cornerRadius = dp(10).toFloat()
                setStroke(dp(2), Color.parseColor("#FFD700"))
            }
            clipToOutline = true
            setOnClickListener { toggleFullscreen(true) }
            setOnKeyListener { _, keyCode, event ->
                if (event.action == KeyEvent.ACTION_DOWN &&
                    (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)
                ) { toggleFullscreen(true); true } else false
            }
        }
        playerView = PlayerView(this).apply {
            useController = false
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            setShutterBackgroundColor(Color.BLACK)
            setBackgroundColor(Color.BLACK)
            isFocusable = false
            isClickable = false
        }
        videoFrame.addView(
            playerView,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        )
        playerPanel.addView(
            videoFrame,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        )

        detailsText = TextView(this).apply {
            text = ""
            setTextColor(Color.parseColor("#39FF8B"))
            textSize = 10f
            gravity = Gravity.CENTER
            setTypeface(null, Typeface.BOLD)
            maxLines = 1
            setPadding(0, dp(4), 0, dp(2))
        }
        playerPanel.addView(detailsText, lp())

        hintText = TextView(this).apply {
            text = "OK = ملء الشاشة  |  Back = رجوع"
            setTextColor(Color.parseColor("#555A7A"))
            textSize = 9f
            gravity = Gravity.CENTER
        }
        playerPanel.addView(hintText, lp())
    }

    // ── شريط الحروف المشترك ───────────────────────────────────────
    private lateinit var alphabetScrollView: HorizontalScrollView

    private fun buildAlphabetBar() {
        alphabetContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val letters = listOf("الكل", "A","B","C","D","E","F","G","H","I","J","K","L","M",
                             "N","O","P","Q","R","S","T","U","V","W","X","Y","Z")
        val btns = mutableListOf<TextView>()
        letters.forEach { letter ->
            val btn = TextView(this).apply {
                text = letter
                setTextColor(Color.parseColor("#A5B4FC"))
                textSize = 11f
                setTypeface(null, Typeface.BOLD)
                gravity = Gravity.CENTER
                isClickable = true
                isFocusable = true
                setPadding(dp(10), dp(4), dp(10), dp(4))
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#121228"))
                    cornerRadius = dp(10).toFloat()
                    setStroke(dp(1), Color.parseColor("#3d3d5c"))
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, dp(32)
                ).apply { marginEnd = dp(5) }

                setOnClickListener {
                    val rawLetter = if (letter == "الكل") null else letter
                    when (activePanel) {
                        ActivePanel.CATEGORIES -> filterCategoriesByLetter(rawLetter)
                        ActivePanel.CHANNELS   -> filterChannelsByLetter(rawLetter)
                    }
                    // تحديث اللون النشط
                    btns.forEach { b ->
                        (b.background as? GradientDrawable)?.setColor(Color.parseColor("#121228"))
                        b.setTextColor(Color.parseColor("#A5B4FC"))
                    }
                    (background as? GradientDrawable)?.setColor(Color.parseColor("#FFD700"))
                    setTextColor(Color.parseColor("#050A1A"))
                }
                setOnFocusChangeListener { v, has ->
                    v.animate().scaleX(if (has) 1.1f else 1f)
                        .scaleY(if (has) 1.1f else 1f).setDuration(80).start()
                }
            }
            btns.add(btn)
            alphabetContainer.addView(btn)
        }
        letterButtons = btns
    }

    private fun buildAlphabetScrollView(): HorizontalScrollView {
        alphabetScrollView = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(44)
            ).apply { bottomMargin = dp(8) }
        }
        alphabetScrollView.addView(alphabetContainer)
        return alphabetScrollView
    }

    /** يحدّث تلميح شريط الحروف حسب العمود النشط */
    private fun updateAlphabetForPanel(panel: ActivePanel) {
        try {
            val hint = when (panel) {
                ActivePanel.CATEGORIES -> "🔤 الحروف تفلتر الفئات"
                ActivePanel.CHANNELS   -> "🔤 الحروف تفلتر القنوات"
            }
            // reset الألوان
            letterButtons.forEach { b ->
                (b.background as? GradientDrawable)?.setColor(Color.parseColor("#121228"))
                b.setTextColor(Color.parseColor("#A5B4FC"))
            }
            // لون مميز للزر "الكل"
            letterButtons.firstOrNull()?.apply {
                (background as? GradientDrawable)?.setColor(Color.parseColor("#1A2A4A"))
                setTextColor(Color.parseColor("#7FE6FF"))
            }
        } catch (_: Exception) {}
    }

    // ─────────────────────────────────────────────────────────────
    // Fullscreen داخلي
    // ─────────────────────────────────────────────────────────────
    private fun toggleFullscreen(enable: Boolean) {
        if (isFullscreenMode == enable) return  // منع الاستدعاء المزدوج
        isFullscreenMode = enable
        try {
            if (enable) {
                // إخفاء عموديّ الفئات والقنوات
                categoriesPanel.visibility = View.GONE
                channelsPanel.visibility   = View.GONE
                // إخفاء نصوص العمود الأيسر
                titleText.visibility       = View.GONE
                catSubtitleText.visibility = View.GONE
                epgText.visibility         = View.GONE
                detailsText.visibility     = View.GONE
                hintText.visibility        = View.GONE
                // تمديد عمود الفيديو
                playerPanel.layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.MATCH_PARENT
                )
                videoFrame.layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.MATCH_PARENT
                )
                mainContainer.requestLayout()
                videoFrame.postDelayed({ videoFrame.requestFocus() }, 100)

            } else {
                // إعادة الأعمدة
                categoriesPanel.visibility = View.VISIBLE
                channelsPanel.visibility   = View.VISIBLE
                titleText.visibility       = View.VISIBLE
                catSubtitleText.visibility = View.VISIBLE
                epgText.visibility         = View.VISIBLE
                detailsText.visibility     = View.VISIBLE
                hintText.visibility        = View.VISIBLE
                playerPanel.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 0.45f)
                videoFrame.layoutParams  = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
                mainContainer.requestLayout()
                // الفوكس يرجع للقناة الحالية في القائمة
                channelsRecycler.postDelayed({
                    val pos = channelsAdapter.playingPosition()
                    if (pos >= 0) {
                        (channelsRecycler.layoutManager as? LinearLayoutManager)
                            ?.scrollToPositionWithOffset(pos, 0)
                        channelsRecycler.findViewHolderForAdapterPosition(pos)?.itemView?.requestFocus()
                    } else {
                        channelsRecycler.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus()
                    }
                }, 150)
            }
        } catch (_: Exception) {}
    }

    // ─────────────────────────────────────────────────────────────
    // تشغيل القناة
    // ─────────────────────────────────────────────────────────────
    private fun playChannel(ch: Channel) {
        try {
            selected = ch
            currentPlayingUrl = ch.streamUrl
            titleText.text = ch.name
            catSubtitleText.text = "📂 ${ch.category.ifBlank { selectedCategoryName }}"
            epgText.text = "⏳ جاري جلب البرنامج..."
            detailsText.text = "▶ ${ch.name}"
            channelsAdapter.setPlayingUrl(ch.streamUrl)
            loadEpg(ch)

            val url = ch.streamUrl.trim().replace("&amp;", "&")
            val dsf = DefaultHttpDataSource.Factory()
                .setUserAgent("Mozilla/5.0 (Linux; Android TV) AppleWebKit/537.36")
                .setAllowCrossProtocolRedirects(true)
            player?.release()
            player = ExoPlayer.Builder(this)
                .setMediaSourceFactory(DefaultMediaSourceFactory(dsf))
                .setLoadControl(
                    com.google.android.exoplayer2.DefaultLoadControl.Builder()
                        .setBufferDurationsMs(15000, 30000, 2500, 5000)
                        .build()
                )
                .build().also { exo ->
                    playerView.player = exo
                    exo.volume = 0.9f
                    exo.setMediaItem(MediaItem.fromUri(Uri.parse(url)))
                    exo.playWhenReady = true
                    exo.prepare()
                }
        } catch (e: Exception) {
            epgText.text = "❌ خطأ: ${e.localizedMessage?.take(60)}"
        }
    }

    private fun loadEpg(ch: Channel) {
        try {
            val active = SourcePrefs.getActiveProfile(this)
            val creds = active?.let { com.latchi.iptv.utils.XtreamHelper.parseCreds(it.m3uUrl) }
            val streamId = com.latchi.iptv.utils.XtreamHelper.liveStreamId(ch.streamUrl)
            if (creds == null || streamId == null) { epgText.text = "EPG: غير متوفر"; return }
            val expectedUrl = ch.streamUrl
            Thread {
                val items = try {
                    com.latchi.iptv.utils.XtreamHelper.fetchShortEpg(creds, streamId, 2)
                } catch (_: Exception) { emptyList() }
                runOnUiThread {
                    if (currentPlayingUrl != expectedUrl) return@runOnUiThread
                    epgText.text = when {
                        items.isEmpty() -> "EPG: لا توجد تفاصيل"
                        else -> "📺 الآن: ${DigitNormalizer.normalizeDigits(items[0].title)}"
                    }
                }
            }.start()
        } catch (_: Exception) { epgText.text = "EPG: غير متوفر" }
    }

    // ─────────────────────────────────────────────────────────────
    // Lifecycle
    // ─────────────────────────────────────────────────────────────
    override fun onResume()  { super.onResume();  player?.playWhenReady = true  }
    override fun onPause()   { super.onPause();   player?.playWhenReady = false }
    override fun onDestroy() { player?.release(); player = null; super.onDestroy() }

    // ─────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────
    private fun buildColumnTitle(text: String) = TextView(this).apply {
        this.text = text
        setTextColor(Color.parseColor("#FFD700"))
        textSize = 13f
        setTypeface(null, Typeface.BOLD)
        gravity = Gravity.CENTER
        setPadding(0, dp(4), 0, dp(8))
    }

    private fun lp() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
    )

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    // ─────────────────────────────────────────────────────────────
    // Adapters
    // ─────────────────────────────────────────────────────────────

    private inner class CategoriesAdapter(
        private var items: List<String>,
        private var selectedCat: String,
        private val onClick: (String) -> Unit
    ) : RecyclerView.Adapter<CategoriesAdapter.VH>() {

        fun setSelected(cat: String) { selectedCat = cat; notifyDataSetChanged() }
        fun updateList(list: List<String>) { items = list; notifyDataSetChanged() }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val tv = TextView(parent.context).apply {
                setTextColor(Color.WHITE)
                textSize = 13f
                setTypeface(null, Typeface.BOLD)
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(10), dp(12), dp(10))
                isClickable = true; isFocusable = true
                maxLines = 1
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#1A1B3A"))
                    cornerRadius = dp(10).toFloat()
                    setStroke(dp(1), Color.parseColor("#3d3d5c"))
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(6) }
            }
            return VH(tv)
        }

        override fun onBindViewHolder(h: VH, pos: Int) = h.bind(items[pos])
        override fun getItemCount() = items.size

        inner class VH(private val tv: TextView) : RecyclerView.ViewHolder(tv) {
            fun bind(cat: String) {
                val isSel = cat.equals(selectedCat, true)
                val label = if (cat.lowercase().let {
                    it.contains("sport ar") || it.contains("bein") || it.contains("sports")
                }) "👑 $cat" else cat

                tv.text = label
                tv.setTextColor(if (isSel) Color.parseColor("#FFD700") else Color.WHITE)
                (tv.background as? GradientDrawable)?.apply {
                    setColor(if (isSel) Color.parseColor("#2A2C5A") else Color.parseColor("#1A1B3A"))
                    setStroke(dp(if (isSel) 2 else 1), if (isSel) Color.parseColor("#FFD700") else Color.parseColor("#3d3d5c"))
                }
                tv.setOnClickListener { onClick(cat) }
                tv.setOnFocusChangeListener { v, has ->
                    v.animate().scaleX(if (has) 1.04f else 1f).scaleY(if (has) 1.04f else 1f).setDuration(80).start()
                    if (!isSel) {
                        (tv.background as? GradientDrawable)?.setStroke(
                            dp(if (has) 2 else 1),
                            if (has) Color.parseColor("#00E5FF") else Color.parseColor("#3d3d5c")
                        )
                    }
                    // الفوكس على فئة → الحروف تتحكم في الفئات
                    if (has) {
                        activePanel = ActivePanel.CATEGORIES
                        updateAlphabetForPanel(ActivePanel.CATEGORIES)
                    }
                }
            }
        }
    }

    private inner class ChannelsAdapter(
        private var items: List<Channel>,
        private val onClick: (Channel) -> Unit
    ) : RecyclerView.Adapter<ChannelsAdapter.VH>() {

        private var playingUrl: String? = null

        fun update(list: List<Channel>) { items = list; notifyDataSetChanged() }
        fun setPlayingUrl(url: String)  { playingUrl = url; notifyDataSetChanged() }
        fun playingPosition(): Int = items.indexOfFirst { it.streamUrl == playingUrl }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val row = LinearLayout(parent.context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(10), dp(8), dp(10), dp(8))
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#1A1B3A"))
                    cornerRadius = dp(10).toFloat()
                    setStroke(dp(1), Color.parseColor("#3d3d5c"))
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(6) }
                isFocusable = true; isClickable = true
            }
            val logo = ImageView(parent.context).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
            }
            row.addView(logo, LinearLayout.LayoutParams(dp(38), dp(38)).apply { marginEnd = dp(10) })
            val name = TextView(parent.context).apply {
                setTextColor(Color.WHITE); textSize = 13f
                setTypeface(null, Typeface.BOLD); maxLines = 1
            }
            row.addView(name, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            return VH(row, logo, name)
        }

        override fun onBindViewHolder(h: VH, pos: Int) = h.bind(items[pos])
        override fun getItemCount() = items.size

        inner class VH(
            private val row: LinearLayout,
            private val logo: ImageView,
            private val name: TextView
        ) : RecyclerView.ViewHolder(row) {
            fun bind(ch: Channel) {
                val isPlaying = ch.streamUrl == playingUrl
                name.text = if (isPlaying) "▶ ${ch.name}" else ch.name
                name.setTextColor(if (isPlaying) Color.parseColor("#39FF8B") else Color.WHITE)
                (row.background as? GradientDrawable)?.apply {
                    setColor(if (isPlaying) Color.parseColor("#0D2A1A") else Color.parseColor("#1A1B3A"))
                    setStroke(dp(if (isPlaying) 2 else 1),
                        if (isPlaying) Color.parseColor("#39FF8B") else Color.parseColor("#3d3d5c"))
                }
                try {
                    Glide.with(row.context)
                        .load(ch.logoUrl.ifBlank { null })
                        .placeholder(R.drawable.ic_tv).error(R.drawable.ic_tv)
                        .into(logo)
                } catch (_: Exception) { logo.setImageResource(R.drawable.ic_tv) }

                row.setOnClickListener { onClick(ch) }
                row.setOnFocusChangeListener { v, has ->
                    v.animate().scaleX(if (has) 1.03f else 1f).scaleY(if (has) 1.03f else 1f).setDuration(80).start()
                    if (!isPlaying) {
                        (row.background as? GradientDrawable)?.setStroke(
                            dp(if (has) 2 else 1),
                            if (has) Color.parseColor("#00E5FF") else Color.parseColor("#3d3d5c")
                        )
                    }
                    // الفوكس على قناة → الحروف تتحكم في القنوات
                    if (has) {
                        activePanel = ActivePanel.CHANNELS
                        updateAlphabetForPanel(ActivePanel.CHANNELS)
                    }
                }
            }
        }
    }
}
