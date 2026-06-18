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
import com.latchi.iptv.utils.PlayerServerSyncHelper
import com.latchi.iptv.utils.SourcePrefs
import com.latchi.iptv.utils.ThemeManager
import com.latchi.iptv.utils.TvFocusHelper

/**
 * Android TV VIP Unified All-in-One Live TV Dashboard (v3.0 Royal).
 * 
 * الميزات الملكية الجذرية:
 * 1. واجهة ثلاثية موحدة (الفئات على اليمين، القنوات في الوسط، والفيديو والتفاصيل على اليسار).
 * 2. التخلص من صفحة الفئات القديمة المنفصلة نهائياً.
 * 3. استمرار البث المباشر (Seamless Return): تكبير الفيديو داخلياً، وعند الرجوع يتقلص وتظل المباراة شغالة بدون انقطاع.
 * 4. الذكاء الاصطناعي في التعرف على فئة beIN Sports وتصدرها تلقائياً عند الدخول.
 * 5. تنظيف قسم beIN Sports الصافي وتصدر قنوات beIN MAX في المرتبة الأولى.
 * 6. تفعيل شريط الحروف الأبجدية الذكي للبحث السريع في القنوات والفئات.
 */
class TvLivePreviewActivity : AppCompatActivity() {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(com.latchi.iptv.utils.LocaleHelper.wrap(newBase))
    }

    private lateinit var selected: Channel
    private var allLiveChannels: List<Channel> = emptyList()
    private var currentCategories: List<String> = emptyList()
    private var selectedCategoryName: String = "All"
    private var currentCategoryChannels: List<Channel> = emptyList()

    // ExoPlayer & Views
    private var player: ExoPlayer? = null
    private lateinit var playerView: PlayerView
    private lateinit var videoFrame: FrameLayout
    private lateinit var titleText: TextView
    private lateinit var catSubtitleText: TextView
    private lateinit var epgText: TextView
    private lateinit var detailsText: TextView
    private lateinit var hintText: TextView

    // Layout Panels
    private lateinit var mainContainer: LinearLayout
    private lateinit var categoriesPanel: LinearLayout
    private lateinit var channelsPanel: LinearLayout
    private lateinit var playerPanel: LinearLayout

    // Adapters
    private lateinit var categoriesRecycler: RecyclerView
    private lateinit var categoriesAdapter: CategoriesAdapter
    private lateinit var channelsRecycler: RecyclerView
    private lateinit var channelsAdapter: ChannelsAdapter

    private var currentPlayingUrl: String? = null
    private var isFullscreenMode = false
    private var activeFilterLetter: String? = null

    companion object {
        fun start(context: Context, channel: Channel, category: String) {
            context.startActivity(Intent(context, TvLivePreviewActivity::class.java).apply {
                putExtra("channel", channel)
                putExtra("category", category)
            })
        }

        fun startWithChannels(context: Context, channel: Channel, channels: List<Channel>, categoryLabel: String) {
            context.startActivity(Intent(context, TvLivePreviewActivity::class.java).apply {
                putExtra("channel", channel)
                putExtra("category", categoryLabel)
                putParcelableArrayListExtra("extra_channels", ArrayList(channels))
            })
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        ThemeManager.apply(this)

        selected = intent.getParcelableExtra("channel") ?: run { finish(); return }
        
        // جلب كل القنوات المباشرة
        val customList = intent.getParcelableArrayListExtra<Channel>("extra_channels")
        val active = SourcePrefs.getActiveProfile(this)
        val cached = if (!customList.isNullOrEmpty()) customList else (active?.let { ChannelCache.load(this, it.id) }.orEmpty())
        allLiveChannels = cached.filter { it.contentType == "live" }.ifEmpty { listOf(selected) }

        // تحضير الفئات الذكي
        prepareSmartCategories()

        buildRoyalUi()

        // اعتراض زر الرجوع للتكبير الداخلي
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (isFullscreenMode) {
                    toggleFullscreen(false)
                } else {
                    finish()
                }
            }
        })
    }

    /**
     * 🧠 محرك الذكاء الاصطناعي الملكي:
     * 1. يستخرج كل الفئات
     * 2. يعثر على فئة beIN Sports (حتى لو كان اسمها sport ar)
     * 3. يجعلها الفئة الأولى في القائمة والمحددة تلقائياً عند الدخول
     */
    private fun getHiddenSet(): Set<String> {
        val active = SourcePrefs.getActiveProfile(this) ?: return emptySet()
        val hiddenStr = getSharedPreferences("server_sync_prefs", Context.MODE_PRIVATE)
            .getString("hidden_categories_${active.id}", "") ?: ""
        return hiddenStr.split(",").map { it.trim().lowercase() }.filter { it.isNotBlank() }.toSet()
    }

    private fun prepareSmartCategories() {
        val hiddenSet = getHiddenSet()
        val rawCats = allLiveChannels.filter { !hiddenSet.contains(it.category.trim().lowercase()) }
            .map { it.category }.distinct().filter { it.isNotBlank() }
        
        val beinCat = rawCats.firstOrNull { cat ->
            val lower = cat.lowercase()
            lower.contains("sport ar") || lower.contains("bein") || lower.contains("sports")
        } ?: (if (rawCats.isNotEmpty()) rawCats.first() else selected.category)

        val otherCats = rawCats.filter { !it.equals(beinCat, true) }.sorted()
        
        currentCategories = listOf(beinCat) + otherCats
        selectedCategoryName = beinCat

        loadCategoryChannels(beinCat)
    }

    /**
     * 🧹 محرك تنظيف قسم beIN Sports وتصدر قنوات MAX:
     * 1. يصفي القنوات ليعرض فقط قنوات beIN
     * 2. يفرز القنوات لكي يضع قنوات beIN MAX هي الأولى في الواجهة
     */
    private fun loadCategoryChannels(catName: String, filterLetter: String? = null) {
        selectedCategoryName = catName
        activeFilterLetter = filterLetter

        var rawChannels = allLiveChannels.filter { it.category.equals(catName, true) }
        if (rawChannels.isEmpty()) rawChannels = allLiveChannels

        // تطبيق التنظيف وترتيب الماكس إذا كانت الفئة هي beIN
        val isBeinSection = catName.lowercase().let { it.contains("sport ar") || it.contains("bein") || it.contains("sports") }
        
        if (isBeinSection) {
            // تصفية صارمة: فقط قنوات beIN
            val pureBein = rawChannels.filter { it.name.lowercase().contains("bein") }
            val baseList = if (pureBein.isNotEmpty()) pureBein else rawChannels

            // قاعدة تصدر الماكس (MAX First Rule)
            rawChannels = baseList.sortedWith(
                compareByDescending<Channel> { ch ->
                    val n = ch.name.lowercase()
                    n.contains("max") || n.contains("max1") || n.contains("max2") || n.contains("beinmax")
                }.thenBy { it.name }
            )
        }

        // تطبيق الفرز الأبجدي إذا اختار المستخدم حرفاً
        if (!filterLetter.isNullOrBlank()) {
            rawChannels = rawChannels.filter { it.name.trim().startsWith(filterLetter, true) }
        }

        currentCategoryChannels = rawChannels.ifEmpty { allLiveChannels.take(5) }

        // تحديث الواجهة
        if (::channelsAdapter.isInitialized) {
            channelsAdapter.update(currentCategoryChannels)
        }
        if (::catSubtitleText.isInitialized) {
            val filterSuffix = if (filterLetter != null) " (حرف $filterLetter)" else ""
            catSubtitleText.text = "📂 الفئة: $catName$filterSuffix"
        }

        // تشغيل أول قناة تلقائياً إذا تغيرت الفئة ولم يكن المشغل شغالاً
        if (currentCategoryChannels.isNotEmpty() && currentPlayingUrl == null) {
            playRoyalPreview(currentCategoryChannels.first())
        }
    }

    // ─────────────────────────────────────────────────────────────
    // بناء الواجهة الملكية (Royal Tri-Panel UI)
    // ─────────────────────────────────────────────────────────────
    private fun buildRoyalUi() {
        mainContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            setBackgroundResource(R.drawable.bg_app)
        }
        setContentView(mainContainer)

        // ══════════════════════════════════════════════════════════
        // 1. العمود الأيمن (قائمة الفئات Categories) — Width: 0.28f
        // ══════════════════════════════════════════════════════════
        categoriesPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, dp(12), 0)
        }
        mainContainer.addView(categoriesPanel, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 0.28f))

        categoriesPanel.addView(TextView(this).apply {
            text = "📁 الفئات / Categories"
            setTextColor(Color.parseColor("#FFD700"))
            textSize = 15f
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(0, dp(6), 0, dp(12))
        })

        categoriesRecycler = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@TvLivePreviewActivity)
            clipToPadding = false
        }
        categoriesAdapter = CategoriesAdapter(currentCategories) { cat ->
            loadCategoryChannels(cat, null)
            channelsRecycler.scrollToPosition(0)
        }
        categoriesRecycler.adapter = categoriesAdapter
        TvFocusHelper.setupRecycler(categoriesRecycler)
        categoriesPanel.addView(categoriesRecycler, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        // ══════════════════════════════════════════════════════════
        // 2. العمود الأوسط (قائمة القنوات + الحروف) — Width: 0.36f
        // ══════════════════════════════════════════════════════════
        channelsPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, dp(12), 0)
        }
        mainContainer.addView(channelsPanel, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 0.36f))

        // شريط الحروف الأبجدية الأفقي الأنيق (A, B, C...)
        val alphabetScroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(44)).apply { bottomMargin = dp(8) }
            isHorizontalScrollBarEnabled = false
        }
        val alphabetContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        alphabetScroll.addView(alphabetContainer)

        val letters = listOf("All", "A", "B", "C", "D", "E", "F", "G", "H", "I", "J", "K", "L", "M", "N", "O", "P", "Q", "R", "S", "T", "U", "V", "W", "X", "Y", "Z")
        letters.forEach { letter ->
            val btnLetter = TextView(this).apply {
                text = letter
                setTextColor(Color.parseColor("#A5B4FC"))
                textSize = 12f
                setTypeface(null, Typeface.BOLD)
                gravity = Gravity.CENTER
                isClickable = true
                isFocusable = true
                setPadding(dp(12), dp(6), dp(12), dp(6))
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#121228"))
                    cornerRadius = dp(12).toFloat()
                    setStroke(dp(1), Color.parseColor("#3d3d5c"))
                }
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(34)).apply { marginEnd = dp(6) }
                
                setOnClickListener {
                    if (letter == "All") loadCategoryChannels(selectedCategoryName, null)
                    else loadCategoryChannels(selectedCategoryName, letter)
                }
                setOnFocusChangeListener { v, has ->
                    v.animate().scaleX(if (has) 1.1f else 1f).scaleY(if (has) 1.1f else 1f).setDuration(100).start()
                    if (has) {
                        (v.background as? GradientDrawable)?.setColor(Color.parseColor("#FFD700"))
                        setTextColor(Color.parseColor("#050A1A"))
                    } else {
                        (v.background as? GradientDrawable)?.setColor(Color.parseColor("#121228"))
                        setTextColor(Color.parseColor("#A5B4FC"))
                    }
                }
            }
            alphabetContainer.addView(btnLetter)
        }
        channelsPanel.addView(alphabetScroll)

        // قائمة القنوات
        channelsRecycler = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@TvLivePreviewActivity)
            clipToPadding = false
        }
        channelsAdapter = ChannelsAdapter(currentCategoryChannels) { ch ->
            if (currentPlayingUrl == ch.streamUrl) {
                toggleFullscreen(true)
            } else {
                playRoyalPreview(ch)
            }
        }
        channelsRecycler.adapter = channelsAdapter
        TvFocusHelper.setupRecycler(channelsRecycler)
        channelsPanel.addView(channelsRecycler, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        // ══════════════════════════════════════════════════════════
        // 3. العمود الأيسر (الفيديو والمشغل والتفاصيل) — Width: 0.36f
        // ══════════════════════════════════════════════════════════
        playerPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        mainContainer.addView(playerPanel, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 0.36f))

        titleText = TextView(this).apply {
            text = selected.name
            setTextColor(Color.parseColor("#FFD700"))
            textSize = 20f
            gravity = Gravity.CENTER
            setTypeface(null, Typeface.BOLD)
            maxLines = 1
            setPadding(0, 0, 0, dp(4))
        }
        playerPanel.addView(titleText, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        catSubtitleText = TextView(this).apply {
            text = "📂 الفئة: $selectedCategoryName"
            setTextColor(Color.parseColor("#7FE6FF"))
            textSize = 13f
            gravity = Gravity.CENTER
            maxLines = 1
            setPadding(0, 0, 0, dp(4))
        }
        playerPanel.addView(catSubtitleText, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        epgText = TextView(this).apply {
            text = "EPG: جاري جلب تفاصيل البرنامج..."
            setTextColor(Color.parseColor("#A5B4FC"))
            textSize = 12f
            gravity = Gravity.CENTER
            maxLines = 1
            setPadding(0, 0, 0, dp(8))
        }
        playerPanel.addView(epgText, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        // مشغل الفيديو الملكي (In-place video frame)
        videoFrame = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            isFocusable = true
            isClickable = true
            foreground = getDrawable(R.drawable.focus_ring_vip)
            background = GradientDrawable().apply {
                setColor(Color.BLACK)
                cornerRadius = dp(14).toFloat()
                setStroke(dp(2), Color.parseColor("#FFD700"))
            }
            clipToOutline = true

            setOnClickListener { toggleFullscreen(true) }
            setOnKeyListener { _, keyCode, event ->
                if (event.action == KeyEvent.ACTION_DOWN && (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)) {
                    toggleFullscreen(true); true
                } else false
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
        videoFrame.addView(playerView, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        playerPanel.addView(videoFrame, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        detailsText = TextView(this).apply {
            setTextColor(Color.parseColor("#39FF8B"))
            textSize = 12f
            gravity = Gravity.CENTER
            setTypeface(null, Typeface.BOLD)
            maxLines = 1
            setPadding(0, dp(8), 0, dp(4))
        }
        playerPanel.addView(detailsText, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        hintText = TextView(this).apply {
            text = "💡 اضغط OK على المشغل = تكبير الشاشة (Fullscreen)"
            setTextColor(Color.parseColor("#8891B8"))
            textSize = 11f
            gravity = Gravity.CENTER
        }
        playerPanel.addView(hintText, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        // تحديد أول فئة في اليمين
        categoriesRecycler.post {
            categoriesRecycler.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus()
        }
    }

    // ─────────────────────────────────────────────────────────────
    // استمرار البث المباشر والتكبير الداخلي (In-place Fullscreen)
    // ─────────────────────────────────────────────────────────────
    private fun toggleFullscreen(enable: Boolean) {
        isFullscreenMode = enable
        if (enable) {
            // تكبير المشغل ليملا الشاشة بالكامل
            categoriesPanel.visibility = View.GONE
            channelsPanel.visibility = View.GONE
            titleText.visibility = View.GONE
            catSubtitleText.visibility = View.GONE
            epgText.visibility = View.GONE
            detailsText.visibility = View.GONE
            hintText.visibility = View.GONE

            playerPanel.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT)
            videoFrame.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT)
            (videoFrame.background as? GradientDrawable)?.setStroke(0, Color.TRANSPARENT)
            
            Toast.makeText(this, "وضع الشاشة الكامله • اضغط Back للعودة", Toast.LENGTH_SHORT).show()
        } else {
            // العودة للشاشة المصغرة مع استمرار البث المباشر بدون أي انقطاع
            categoriesPanel.visibility = View.VISIBLE
            channelsPanel.visibility = View.VISIBLE
            titleText.visibility = View.VISIBLE
            catSubtitleText.visibility = View.VISIBLE
            epgText.visibility = View.VISIBLE
            detailsText.visibility = View.VISIBLE
            hintText.visibility = View.VISIBLE

            playerPanel.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 0.36f)
            videoFrame.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            (videoFrame.background as? GradientDrawable)?.setStroke(dp(2), Color.parseColor("#FFD700"))
        }
    }

    // ─────────────────────────────────────────────────────────────
    // تشغيل القناة في المشغل (ExoPlayer)
    // ─────────────────────────────────────────────────────────────
    private fun playRoyalPreview(ch: Channel) {
        selected = ch
        currentPlayingUrl = ch.streamUrl
        titleText.text = ch.name
        epgText.text = "EPG: جاري جلب تفاصيل البرنامج..."
        detailsText.text = "▶ البث المباشر: ${ch.name}"
        
        channelsAdapter.setPlayingUrl(ch.streamUrl)
        loadPreviewEpg(ch)

        val dataSourceFactory = DefaultHttpDataSource.Factory()
            .setUserAgent("Mozilla/5.0 (Linux; Android TV) AppleWebKit/537.36")
            .setAllowCrossProtocolRedirects(true)
        val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory)
        val streamUrl = ch.streamUrl.trim().replace("&amp;", "&")

        player?.release()
        val loadControl = com.google.android.exoplayer2.DefaultLoadControl.Builder()
            .setBufferDurationsMs(15000, 30000, 2500, 5000)
            .build()
        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(mediaSourceFactory)
            .setLoadControl(loadControl)
            .build()
            .also { exo ->
                playerView.player = exo
                exo.volume = 0.85f
                exo.setMediaItem(MediaItem.fromUri(Uri.parse(streamUrl)))
                exo.playWhenReady = true
                exo.prepare()
            }
    }

    private fun loadPreviewEpg(ch: Channel) {
        val active = SourcePrefs.getActiveProfile(this)
        val creds = active?.let { com.latchi.iptv.utils.XtreamHelper.parseCreds(it.m3uUrl) }
        val streamId = com.latchi.iptv.utils.XtreamHelper.liveStreamId(ch.streamUrl)
        if (creds == null || streamId == null) {
            epgText.text = "EPG: غير متوفر لهذا المصدر"
            return
        }
        val expectedUrl = ch.streamUrl
        Thread {
            val items = try {
                com.latchi.iptv.utils.XtreamHelper.fetchShortEpg(creds, streamId, 2)
            } catch (_: Exception) { emptyList() }
            
            runOnUiThread {
                if (currentPlayingUrl != expectedUrl) return@runOnUiThread
                epgText.text = when {
                    items.isEmpty() -> "EPG: لا توجد تفاصيل برنامج متاحة الآن"
                    items.size == 1 -> "EPG الآن: ${DigitNormalizer.normalizeDigits(items[0].title)}"
                    else -> "EPG الآن: ${DigitNormalizer.normalizeDigits(items[0].title)}"
                }
            }
        }.start()
    }

    override fun onResume() {
        super.onResume()
        player?.playWhenReady = true
    }

    override fun onPause() {
        super.onPause()
        player?.playWhenReady = false
    }

    override fun onDestroy() {
        player?.release()
        player = null
        super.onDestroy()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    // ─────────────────────────────────────────────────────────────
    // المحولات الملكية (Adapters)
    // ─────────────────────────────────────────────────────────────
    private inner class CategoriesAdapter(
        private val items: List<String>,
        private val onClick: (String) -> Unit
    ) : RecyclerView.Adapter<CategoriesAdapter.VH>() {
        
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val tv = TextView(parent.context).apply {
                setTextColor(Color.WHITE); textSize = 14f; setTypeface(null, Typeface.BOLD)
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(12), dp(14), dp(12))
                isClickable = true; isFocusable = true
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#1A1B3A"))
                    cornerRadius = dp(12).toFloat()
                    setStroke(dp(1), Color.parseColor("#3d3d5c"))
                }
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = dp(8)
                }
            }
            return VH(tv)
        }

        override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position])
        override fun getItemCount() = items.size

        inner class VH(private val tv: TextView) : RecyclerView.ViewHolder(tv) {
            fun bind(cat: String) {
                val isSelectedCat = cat.equals(selectedCategoryName, true)
                val label = if (cat.lowercase().let { it.contains("sport ar") || it.contains("bein") }) "👑 beIN Sports" else cat
                tv.text = if (isSelectedCat) "● $label" else label
                tv.setTextColor(if (isSelectedCat) Color.parseColor("#FFD700") else Color.WHITE)
                
                (tv.background as? GradientDrawable)?.setColor(
                    if (isSelectedCat) Color.parseColor("#2A2C5A") else Color.parseColor("#1A1B3A")
                )

                tv.setOnClickListener { onClick(cat); notifyDataSetChanged() }
                tv.setOnFocusChangeListener { v, has ->
                    v.animate().scaleX(if (has) 1.05f else 1f).scaleY(if (has) 1.05f else 1f).setDuration(100).start()
                    if (has) {
                        (tv.background as? GradientDrawable)?.setStroke(dp(2), Color.parseColor("#FFD700"))
                    } else {
                        (tv.background as? GradientDrawable)?.setStroke(dp(1), Color.parseColor("#3d3d5c"))
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

        fun update(newItems: List<Channel>) {
            this.items = newItems
            notifyDataSetChanged()
        }

        fun setPlayingUrl(url: String) {
            this.playingUrl = url
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val row = LinearLayout(parent.context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(10), dp(12), dp(10))
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#1A1B3A"))
                    cornerRadius = dp(12).toFloat()
                    setStroke(dp(1), Color.parseColor("#3d3d5c"))
                }
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) }
                isFocusable = true; isClickable = true
            }
            val logo = ImageView(parent.context).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
            row.addView(logo, LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginEnd = dp(12) })
            val name = TextView(parent.context).apply {
                setTextColor(Color.WHITE); textSize = 14f; setTypeface(null, Typeface.BOLD); maxLines = 1
            }
            row.addView(name, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            return VH(row, logo, name)
        }

        override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position])
        override fun getItemCount() = items.size

        inner class VH(private val row: LinearLayout, private val logo: ImageView, private val name: TextView) : RecyclerView.ViewHolder(row) {
            fun bind(ch: Channel) {
                val isPlaying = ch.streamUrl == playingUrl
                name.text = if (isPlaying) "▶ ${ch.name}" else ch.name
                name.setTextColor(if (isPlaying) Color.parseColor("#39FF8B") else Color.WHITE)
                
                (row.background as? GradientDrawable)?.setColor(
                    if (isPlaying) Color.parseColor("#2A3A4A") else Color.parseColor("#1A1B3A")
                )

                Glide.with(row.context).load(ch.logoUrl.ifBlank { null }).placeholder(R.drawable.ic_tv).error(R.drawable.ic_tv).into(logo)

                row.setOnClickListener { onClick(ch) }
                row.setOnFocusChangeListener { v, has ->
                    v.animate().scaleX(if (has) 1.04f else 1f).scaleY(if (has) 1.04f else 1f).setDuration(100).start()
                    if (has) {
                        (row.background as? GradientDrawable)?.setStroke(dp(2), Color.parseColor("#00E5FF"))
                    } else {
                        (row.background as? GradientDrawable)?.setStroke(dp(1), Color.parseColor("#3d3d5c"))
                    }
                }
            }
        }
    }
}
