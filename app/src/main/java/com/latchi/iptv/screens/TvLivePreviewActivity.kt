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
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.PlaybackException
import com.google.android.exoplayer2.ui.AspectRatioFrameLayout
import com.google.android.exoplayer2.ui.PlayerView
import com.latchi.iptv.R
import com.latchi.iptv.model.Channel
import com.latchi.iptv.utils.ChannelRefreshHelper
import com.latchi.iptv.utils.DigitNormalizer
import com.latchi.iptv.utils.FavoriteManager
import com.latchi.iptv.utils.SourcePrefs
import com.latchi.iptv.utils.ThemeManager
import com.latchi.iptv.utils.TvFocusHelper

/**
 * 👑 TvLivePreviewActivity — الواجهة الملكية الموحدة للتلفاز (Live TV All-in-One Dashboard v4.0 Ultra Safe)
 *
 * مبنية بالكامل على XML نظيف ومستقر 100% (activity_tv_live_dashboard.xml) لضمان عدم حدوث أي كراش أو خروج.
 * 1. التقسيم الثلاثي: הפئات על اليمين 📁፣ הקنوات والحروف في الوسط 📺، ومشغل Бث المباشר על اليسار ▶.
 * 2. تكبير داخلي (In-place Fullscreen) — البث لا ينقطع أبداً عند الرجوع.
 * 3. آلية التفضيل (0.5s Long Click) مع الإشعار.
 * 4. ذكاء التعرف على beIN Sports الصافية وتصدر قنوات beIN MAX.
 */
class TvLivePreviewActivity : AppCompatActivity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(com.latchi.iptv.utils.LocaleHelper.wrap(newBase))
    }

    // State
    private var selectedChannel: Channel? = null
    private var allLiveChannels: List<Channel> = emptyList()
    private var currentCategories: List<String> = emptyList()
    private var selectedCategoryName: String = "All"
    private var currentCategoryChannels: List<Channel> = emptyList()

    private enum class ActivePanel { CATEGORIES, CHANNELS }
    private var activePanel = ActivePanel.CATEGORIES

    private var activeChLetter: String? = null
    private var isFullscreenMode = false
    private var profileId: String = ""
    private var loadFromCache: Boolean = false
    private var currentPlayingUrl: String? = null
    private var hideCategories: Boolean = false
    private var requestedCategoryName: String = "All"
    private var lastFocusedChannelUrl: String? = null

    // ExoPlayer
    private var player: ExoPlayer? = null
    private lateinit var viewPlayer: PlayerView
    private lateinit var frameVideo: FrameLayout

    // Views
    private lateinit var panelCategories: LinearLayout
    private lateinit var panelChannels: LinearLayout
    private lateinit var panelPlayer: LinearLayout
    private lateinit var recyclerCategories: RecyclerView
    private lateinit var recyclerChannels: RecyclerView
    private lateinit var txtFilterHint: TextView
    private lateinit var alphabetScroller: View
    private lateinit var containerAlphabet: LinearLayout
    private lateinit var txtChannelTitle: TextView
    private lateinit var txtCategorySubtitle: TextView
    private lateinit var txtEpgInfo: TextView
    private lateinit var txtDetailsBottom: TextView

    // Adapters
    private var categoriesAdapter: RoyalCategoriesAdapter? = null
    private var channelsAdapter: RoyalChannelsAdapter? = null
    private var letterButtons: List<TextView> = emptyList()

    companion object {
        fun start(context: Context, channel: Channel, category: String) {
            context.startActivity(Intent(context, TvLivePreviewActivity::class.java).apply {
                putExtra("channel", channel)
                putExtra("category", category)
            })
        }

        fun startWithChannels(context: Context, channel: Channel, channels: List<Channel>, categoryLabel: String, hideCategories: Boolean = false) {
            context.startActivity(Intent(context, TvLivePreviewActivity::class.java).apply {
                putExtra("channel", channel)
                putExtra("category", categoryLabel)
                putExtra("hide_categories", hideCategories)
                putParcelableArrayListExtra("extra_channels", ArrayList(channels))
            })
        }

        fun startAllChannels(context: Context, channels: List<Channel>) {
            val live = channels.filter { it.contentType == "live" }.ifEmpty { channels }
            context.startActivity(Intent(context, TvLivePreviewActivity::class.java).apply {
                if (live.isNotEmpty()) putExtra("channel", live.first())
                // 🛑 لا نمرر كل القنوات عبر Intent (سبب كراش TransactionTooLargeException)
                // TvLivePreviewActivity تجلب القنوات بذكاء من الكاش أو من المصدر عند الحاجة
                putExtra("category", "All")
                putExtra("load_from_cache", true)
            })
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            ThemeManager.apply(this)
            setContentView(R.layout.activity_tv_live_dashboard)

            val active = SourcePrefs.getActiveProfile(this)
            if (active == null) {
                finish()
                return
            }
            profileId = active.id
            hideCategories = intent.getBooleanExtra("hide_categories", false)
            loadFromCache = intent.getBooleanExtra("load_from_cache", false)
            requestedCategoryName = intent.getStringExtra("category")?.takeIf { it.isNotBlank() } ?: "All"

            // جلب القنوات
            val passedChannel = intent.getParcelableExtra<Channel>("channel")
            val customList = intent.getParcelableArrayListExtra<Channel>("extra_channels")
            
            if (customList != null && customList.isNotEmpty()) {
                // القنوات مرسلة مع الـ Intent (beIN Sports / Matches - عدد قليل)
                allLiveChannels = customList.filter { it.contentType == "live" }.ifEmpty { customList }
                selectedChannel = resolveInitialChannel(passedChannel, allLiveChannels)
                initDashboard()
            } else {
                if (loadFromCache) {
                    Toast.makeText(this, "⏳ جاري تحميل القنوات...", Toast.LENGTH_SHORT).show()
                }
                loadLiveChannelsSmart(passedChannel)
                return
            }
        } catch (e: Exception) {
            Log.e("TvLiveDashboard", "onCreate Crash: ${e.message}")
            Toast.makeText(this, "حدث خطأ: ${e.message}", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    private fun loadLiveChannelsSmart(passedChannel: Channel?) {
        val active = SourcePrefs.getActiveProfile(this)
        if (active == null) {
            finish()
            return
        }

        ChannelRefreshHelper.ensureFreshChannels(this, active, onlyLive = true) { result ->
            try {
                val live = result.channels.filter { it.contentType == "live" }.ifEmpty {
                    if (passedChannel != null) listOf(passedChannel) else emptyList()
                }

                if (live.isEmpty()) {
                    val msg = when {
                        result.message.isNotBlank() -> "تعذر تحميل القنوات: ${result.message}"
                        else -> "⏳ لم يتم تحميل القنوات بعد"
                    }
                    Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                    finish()
                    return@ensureFreshChannels
                }

                if (result.usedCacheFallback && result.message.isNotBlank()) {
                    Toast.makeText(this, "⚠️ تعذر تحديث القنوات فورياً، تم فتح آخر كاش متاح", Toast.LENGTH_SHORT).show()
                }

                allLiveChannels = live
                selectedChannel = resolveInitialChannel(passedChannel, allLiveChannels)
                initDashboard()
            } catch (e: Exception) {
                Toast.makeText(this, "خطأ في تحميل القنوات: ${e.message}", Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }

    private fun resolveInitialChannel(passedChannel: Channel?, channels: List<Channel>): Channel? {
        if (channels.isEmpty()) return passedChannel
        if (passedChannel == null) return channels.first()

        return channels.firstOrNull { it.streamUrl == passedChannel.streamUrl }
            ?: channels.firstOrNull {
                it.name.equals(passedChannel.name, true) &&
                    it.category.equals(passedChannel.category, true)
            }
            ?: channels.firstOrNull { it.name.equals(passedChannel.name, true) }
            ?: channels.first()
    }

    private fun initDashboard() {
        setFindViewById()
        if (!hideCategories) buildUniversalAlphabetBar()
        prepareSmartCategories()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (isFullscreenMode) toggleFullscreen(false)
                else finish()
            }
        })
    }

    private fun setFindViewById() {
        panelCategories = findViewById(R.id.panelCategories)
        panelChannels = findViewById(R.id.panelChannels)
        panelPlayer = findViewById(R.id.panelPlayer)
        recyclerCategories = findViewById(R.id.recyclerCategories)
        recyclerChannels = findViewById(R.id.recyclerChannels)
        txtFilterHint = findViewById(R.id.txtFilterHint)
        alphabetScroller = findViewById(R.id.alphabetScroller)
        containerAlphabet = findViewById(R.id.containerAlphabet)
        txtChannelTitle = findViewById(R.id.txtChannelTitle)
        txtCategorySubtitle = findViewById(R.id.txtCategorySubtitle)
        txtEpgInfo = findViewById(R.id.txtEpgInfo)
        frameVideo = findViewById(R.id.frameVideo)
        viewPlayer = findViewById(R.id.viewPlayer)
        txtDetailsBottom = findViewById(R.id.txtDetailsBottom)

        recyclerCategories.layoutManager = LinearLayoutManager(this)
        recyclerChannels.layoutManager = LinearLayoutManager(this)
        recyclerCategories.itemAnimator = null
        recyclerChannels.itemAnimator = null

        TvFocusHelper.setupRecycler(recyclerCategories)
        TvFocusHelper.setupRecycler(recyclerChannels)

        applyDashboardModeLayout()

        frameVideo.setOnClickListener { toggleFullscreen(true) }
        frameVideo.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN && (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)) {
                toggleFullscreen(true); true
            } else false
        }
    }

    private fun applyDashboardModeLayout() {
        try {
            if (hideCategories) {
                panelCategories.visibility = View.GONE
                alphabetScroller.visibility = View.GONE
                updatePanelWeight(panelChannels, 0.46f, marginStart = 0, marginEnd = dp(6))
                updatePanelWeight(panelPlayer, 0.54f, marginStart = dp(6), marginEnd = 0)
            } else {
                panelCategories.visibility = View.VISIBLE
                alphabetScroller.visibility = View.VISIBLE
                updatePanelWeight(panelCategories, 0.20f, marginStart = 0, marginEnd = dp(4))
                updatePanelWeight(panelChannels, 0.38f, marginStart = dp(4), marginEnd = dp(4))
                updatePanelWeight(panelPlayer, 0.28f, marginStart = dp(4), marginEnd = 0)
            }

            txtFilterHint.textSize = 13f
            txtChannelTitle.textSize = if (hideCategories) 18f else 17f
            txtCategorySubtitle.textSize = 12f
            txtEpgInfo.textSize = 11.5f
            txtDetailsBottom.textSize = 10.5f
            applyCompactPlayerCardLayout()
        } catch (_: Exception) {}
    }

    private fun updatePanelWeight(view: View, weight: Float, marginStart: Int, marginEnd: Int) {
        (view.layoutParams as? LinearLayout.LayoutParams)?.let { params ->
            params.width = 0
            params.height = LinearLayout.LayoutParams.MATCH_PARENT
            params.weight = weight
            params.marginStart = marginStart
            params.marginEnd = marginEnd
            view.layoutParams = params
        }
    }

    private fun applyCompactPlayerCardLayout() {
        frameVideo.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(if (hideCategories) 250 else 208)
        ).apply {
            topMargin = dp(4)
            bottomMargin = dp(8)
        }
        viewPlayer.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
    }

    private fun requestChannelFocusByUrl(url: String?, fallbackToFirst: Boolean = false) {
        val targetIndex = when {
            !url.isNullOrBlank() -> currentCategoryChannels.indexOfFirst { it.streamUrl == url }
            fallbackToFirst && currentCategoryChannels.isNotEmpty() -> 0
            else -> -1
        }.let { if (it >= 0) it else if (fallbackToFirst && currentCategoryChannels.isNotEmpty()) 0 else -1 }

        if (targetIndex < 0) return
        lastFocusedChannelUrl = currentCategoryChannels.getOrNull(targetIndex)?.streamUrl
        recyclerChannels.post {
            recyclerChannels.scrollToPosition(targetIndex)
            recyclerChannels.postDelayed({
                recyclerChannels.findViewHolderForAdapterPosition(targetIndex)?.itemView?.requestFocus()
            }, 70)
        }
    }

    private fun requestCategoryFocus(categoryName: String) {
        val index = currentCategories.indexOfFirst { it.equals(categoryName, true) }
        if (index < 0) return
        recyclerCategories.post {
            recyclerCategories.scrollToPosition(index)
            recyclerCategories.postDelayed({
                recyclerCategories.findViewHolderForAdapterPosition(index)?.itemView?.requestFocus()
            }, 70)
        }
    }

    private fun getHiddenSet(): Set<String> {
        return try {
            val s = getSharedPreferences("server_sync_prefs", Context.MODE_PRIVATE)
                .getString("hidden_categories_$profileId", "") ?: ""
            s.split(",").map { it.trim().lowercase() }.filter { it.isNotBlank() }.toSet()
        } catch (_: Exception) { emptySet() }
    }

    private fun prepareSmartCategories() {
        try {
            val hiddenSet = getHiddenSet()
            val favCats = FavoriteManager.getFavoriteCategories(this, profileId)

            val rawCats = allLiveChannels.filter { !hiddenSet.contains(it.category.trim().lowercase()) }
                .map { it.category }
                .distinct()
                .filter { it.isNotBlank() }

            val beinCat = rawCats.firstOrNull { cat ->
                val l = cat.lowercase()
                l.contains("sport ar") || l.contains("bein") || l.contains("sports")
            } ?: (if (rawCats.isNotEmpty()) rawCats.first() else requestedCategoryName)

            val requestedCat = rawCats.firstOrNull { it.equals(requestedCategoryName, true) }
            val preferredCat = requestedCat ?: beinCat
            val others = rawCats.filter { !it.equals(beinCat, true) }.sorted()

            if (hideCategories) {
                selectedCategoryName = requestedCategoryName.ifBlank { preferredCat }
                currentCategories = listOf(selectedCategoryName)
                activePanel = ActivePanel.CHANNELS
                loadCategoryChannels(selectedCategoryName, null)
                requestChannelFocusByUrl(selectedChannel?.streamUrl, fallbackToFirst = true)
                updateAlphabetHint()
                return
            }

            val completeList = mutableListOf<String>()
            completeList.add("⭐ المفضلة")
            completeList.addAll(favCats.map { "📁 $it" })
            completeList.add(beinCat)
            completeList.addAll(others)

            currentCategories = completeList.distinct()
            selectedCategoryName = preferredCat

            categoriesAdapter = RoyalCategoriesAdapter(currentCategories, selectedCategoryName) { chosen ->
                loadCategoryChannels(chosen, null)
                lastFocusedChannelUrl = currentCategoryChannels.firstOrNull()?.streamUrl
                recyclerChannels.postDelayed({
                    requestChannelFocusByUrl(lastFocusedChannelUrl, fallbackToFirst = true)
                    activePanel = ActivePanel.CHANNELS
                    updateAlphabetHint()
                }, 80)
            }
            recyclerCategories.adapter = categoriesAdapter

            loadCategoryChannels(preferredCat, null)
            requestCategoryFocus(preferredCat)
        } catch (e: Exception) {
            Log.e("TvLiveDashboard", "Prepare Cats Crash: ${e.message}")
        }
    }

    private fun loadCategoryChannels(catLabel: String, filterLetter: String?) {
        try {
            selectedCategoryName = catLabel
            activeChLetter = filterLetter

            val isFavSection = catLabel == "⭐ المفضلة"
            val isFavCatSection = catLabel.startsWith("📁 ")
            val realCatName = if (isFavCatSection) catLabel.removePrefix("📁 ") else catLabel
            val keepFocusUrl = lastFocusedChannelUrl ?: selectedChannel?.streamUrl
            val hiddenSet = getHiddenSet()

            var rawList = when {
                hideCategories -> allLiveChannels
                isFavSection -> FavoriteManager.getFavoriteChannels(this, profileId)
                isFavCatSection -> allLiveChannels.filter { it.category.equals(realCatName, true) }
                realCatName == "All" -> allLiveChannels.filter { !hiddenSet.contains(it.category.trim().lowercase()) }
                else -> allLiveChannels.filter { it.category.equals(realCatName, true) && !hiddenSet.contains(it.category.trim().lowercase()) }
            }

            if (rawList.isEmpty() && !isFavSection) {
                rawList = allLiveChannels.filter { !hiddenSet.contains(it.category.trim().lowercase()) }
            }

            val isBein = realCatName.lowercase().let { it.contains("sport ar") || it.contains("bein") || it.contains("sports") }
            if (isBein && !isFavSection) {
                val pureBein = rawList.filter { it.name.lowercase().contains("bein") }
                val base = if (pureBein.isNotEmpty()) pureBein else rawList
                rawList = base.sortedWith(
                    compareByDescending<Channel> { ch ->
                        val n = ch.name.lowercase()
                        n.contains("max") || n.contains("beinmax") || n.contains("max1") || n.contains("max2")
                    }.thenBy { it.name }
                )
            }

            currentCategoryChannels = if (!hideCategories && filterLetter != null && filterLetter != "All") {
                rawList.filter { it.name.trim().startsWith(filterLetter, true) }
            } else {
                rawList
            }

            if (channelsAdapter == null) {
                channelsAdapter = RoyalChannelsAdapter(
                    currentCategoryChannels,
                    selectedChannel?.streamUrl,
                    onClick = { ch ->
                        lastFocusedChannelUrl = ch.streamUrl
                        if (selectedChannel?.streamUrl == ch.streamUrl && player?.isPlaying == true) {
                            requestChannelFocusByUrl(ch.streamUrl, fallbackToFirst = false)
                            toggleFullscreen(true)
                        } else {
                            playRoyalLiveChannel(ch)
                            requestChannelFocusByUrl(ch.streamUrl, fallbackToFirst = false)
                        }
                    },
                    onLongClick = { ch ->
                        lastFocusedChannelUrl = ch.streamUrl
                        performFavoriteChannelToggle(ch)
                    }
                )
                recyclerChannels.adapter = channelsAdapter
            } else {
                channelsAdapter?.update(currentCategoryChannels)
            }

            categoriesAdapter?.setSelectedCat(catLabel)

            val suffix = if (!hideCategories && filterLetter != null) " (حرف $filterLetter)" else ""
            txtFilterHint.text = if (hideCategories) {
                "📺 قنوات ${realCatName.ifBlank { requestedCategoryName }}"
            } else {
                "📺 القنوات: $catLabel$suffix"
            }

            if (currentCategoryChannels.isNotEmpty() && player == null) {
                playRoyalLiveChannel(currentCategoryChannels.first())
            }

            if ((hideCategories || activePanel == ActivePanel.CHANNELS) && currentCategoryChannels.isNotEmpty()) {
                requestChannelFocusByUrl(keepFocusUrl, fallbackToFirst = true)
            }
        } catch (e: Exception) {
            Log.e("TvLiveDashboard", "Load Channels Crash: ${e.message}")
        }
    }

    private fun buildUniversalAlphabetBar() {
        try {
            val letters = listOf("All", "A","B","C","D","E","F","G","H","I","J","K","L","M",
                                 "N","O","P","Q","R","S","T","U","V","W","X","Y","Z")
            val btns = mutableListOf<TextView>()
            letters.forEach { letter ->
                val btn = TextView(this).apply {
                    text = letter
                    setTextColor(Color.parseColor("#A5B4FC"))
                    textSize = 10.5f
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
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(32)).apply { marginEnd = dp(4) }

                    setOnClickListener {
                        val chosenLetter = if (letter == "All") null else letter
                        when (activePanel) {
                            ActivePanel.CATEGORIES -> filterCategoriesByAlphabet(chosenLetter)
                            ActivePanel.CHANNELS -> loadCategoryChannels(selectedCategoryName, chosenLetter)
                        }
                        btns.forEach { b ->
                            (b.background as? GradientDrawable)?.setColor(Color.parseColor("#121228"))
                            b.setTextColor(Color.parseColor("#A5B4FC"))
                        }
                        (background as? GradientDrawable)?.setColor(Color.parseColor("#FFD700"))
                        setTextColor(Color.parseColor("#050A1A"))
                    }
                    setOnFocusChangeListener { v, has ->
                        v.animate().scaleX(if (has) 1.06f else 1f).scaleY(if (has) 1.06f else 1f).setDuration(80).start()
                    }
                }
                btns.add(btn)
                containerAlphabet.addView(btn)
            }
            letterButtons = btns
            updateAlphabetHint()
        } catch (_: Exception) {}
    }

    private fun filterCategoriesByAlphabet(letter: String?) {
        try {
            val filtered = if (letter != null) {
                currentCategories.filter { it.removePrefix("📁 ").startsWith(letter, true) }
            } else {
                currentCategories
            }
            categoriesAdapter?.update(filtered)
        } catch (_: Exception) {}
    }

    private fun updateAlphabetHint() {
        try {
            if (hideCategories) {
                txtFilterHint.text = "📺 قنوات ${selectedCategoryName.ifBlank { requestedCategoryName }}"
                return
            }
            val hint = when (activePanel) {
                ActivePanel.CATEGORIES -> "📁 ${selectedCategoryName.ifBlank { "الفئات" }} • الحروف تفلتر الفئات"
                ActivePanel.CHANNELS -> "📺 ${selectedCategoryName.ifBlank { "القنوات" }} • الحروف تفلتر القنوات"
            }
            txtFilterHint.text = hint
        } catch (_: Exception) {}
    }

    // ─────────────────────────────────────────────────────────────
    // تشغيل القناة في المشغل (ExoPlayer)
    // ─────────────────────────────────────────────────────────────
    private fun playRoyalLiveChannel(ch: Channel) {
        try {
            selectedChannel = ch
            currentPlayingUrl = ch.streamUrl
            txtChannelTitle.text = ch.name
            txtCategorySubtitle.text = "📂 ${ch.category.ifBlank { selectedCategoryName }}"
            txtEpgInfo.text = "⏳ جاري جلب البرنامج..."
            txtDetailsBottom.text = "▶ البث المباشر: ${ch.name}"

            lastFocusedChannelUrl = ch.streamUrl
            channelsAdapter?.setPlayingUrl(ch.streamUrl)
            FavoriteManager.addRecentChannel(this, profileId, ch)
            loadRoyalEpg(ch)

            player?.release()
            player = ExoPlayer.Builder(this)
                .build()
                .also { exo ->
                    viewPlayer.player = exo
                    exo.volume = 0.9f
                    exo.setMediaItem(MediaItem.fromUri(Uri.parse(ch.streamUrl.trim().replace("&amp;", "&"))))
                    exo.playWhenReady = true
                    exo.prepare()
                    exo.addListener(object : Player.Listener {
                        override fun onPlayerError(error: PlaybackException) {
                            txtDetailsBottom.text = "⚠️ البث متوقف أو غير متاح: ${ch.name}"
                        }
                    })
                }
        } catch (e: Exception) {
            txtDetailsBottom.text = "❌ خطأ في التشغيل: ${e.message}"
        }
    }

    private fun loadRoyalEpg(ch: Channel) {
        try {
            val active = SourcePrefs.getActiveProfile(this) ?: return
            val creds = com.latchi.iptv.utils.XtreamHelper.parseCreds(active.m3uUrl) ?: return
            val streamId = com.latchi.iptv.utils.XtreamHelper.liveStreamId(ch.streamUrl) ?: return
            val expectedUrl = ch.streamUrl

            Thread {
                val items = try { com.latchi.iptv.utils.XtreamHelper.fetchShortEpg(creds, streamId, 2) } catch (_: Exception) { emptyList() }
                runOnUiThread {
                    try {
                        if (currentPlayingUrl != expectedUrl) return@runOnUiThread
                        txtEpgInfo.text = when {
                            items.isEmpty() -> "EPG: لا توجد تفاصيل برنامج متاحة الآن"
                            else -> "📺 الآن: ${DigitNormalizer.normalizeDigits(items[0].title)}"
                        }
                    } catch (_: Exception) {}
                }
            }.start()
        } catch (_: Exception) {}
    }

    // ─────────────────────────────────────────────────────────────
    // Fullscreen داخلي واستمرار البث
    // ─────────────────────────────────────────────────────────────
    private fun toggleFullscreen(enable: Boolean) {
        try {
            isFullscreenMode = enable
            if (enable) {
                panelCategories.visibility = View.GONE
                panelChannels.visibility = View.GONE
                txtChannelTitle.visibility = View.GONE
                txtCategorySubtitle.visibility = View.GONE
                txtEpgInfo.visibility = View.GONE
                txtDetailsBottom.visibility = View.GONE
                findViewById<View>(R.id.txtDetailsBottom)?.visibility = View.GONE
                (frameVideo.parent as? ViewGroup)?.findViewById<View>(R.id.txtDetailsBottom)?.visibility = View.GONE

                panelPlayer.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT)
                frameVideo.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT)
                viewPlayer.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                
                Toast.makeText(this, "وضع الشاشة الكامله • اضغط Back للعودة", Toast.LENGTH_SHORT).show()
            } else {
                panelCategories.visibility = if (hideCategories) View.GONE else View.VISIBLE
                panelChannels.visibility = View.VISIBLE
                txtChannelTitle.visibility = View.VISIBLE
                txtCategorySubtitle.visibility = View.VISIBLE
                txtEpgInfo.visibility = View.VISIBLE
                txtDetailsBottom.visibility = View.VISIBLE
                applyDashboardModeLayout()
            }
        } catch (_: Exception) {}
    }

    private fun performFavoriteChannelToggle(ch: Channel) {
        try {
            val isAdded = FavoriteManager.toggleFavoriteChannel(this, profileId, ch)
            val msg = if (isAdded) "⭐ تم حفظ القناة '${ch.name}' في المفضلة ✓" else "إلغاء حفظ القناة '${ch.name}' من المفضلة"
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
            channelsAdapter?.notifyFavoriteChanged(ch.streamUrl)
            requestChannelFocusByUrl(ch.streamUrl, fallbackToFirst = false)
        } catch (_: Exception) {}
    }

    private fun performFavoriteCategoryToggle(catName: String) {
        try {
            val isAdded = FavoriteManager.toggleFavoriteCategory(this, profileId, catName)
            val msg = if (isAdded) "📁 تم حفظ الفئة '$catName' في الفئات المفضلة ✓" else "إلغاء حفظ الفئة '$catName' من المفضلة"
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
            categoriesAdapter?.notifyDataSetChanged()
        } catch (_: Exception) {}
    }

    override fun onResume() {
        super.onResume()
        try { player?.playWhenReady = true } catch (_: Exception) {}
    }

    override fun onPause() {
        super.onPause()
        try { player?.playWhenReady = false } catch (_: Exception) {}
    }

    override fun onDestroy() {
        try { player?.release(); player = null } catch (_: Exception) {}
        super.onDestroy()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    // ─────────────────────────────────────────────────────────────
    // المحولات (Adapters)
    // ─────────────────────────────────────────────────────────────
    private inner class RoyalCategoriesAdapter(
        private var items: List<String>,
        private var selectedCat: String,
        private val onClick: (String) -> Unit
    ) : RecyclerView.Adapter<RoyalCategoriesAdapter.VH>() {

        init { setHasStableIds(true) }

        fun setSelectedCat(cat: String) {
            val oldIndex = items.indexOfFirst { it.equals(selectedCat, true) }
            selectedCat = cat
            val newIndex = items.indexOfFirst { it.equals(selectedCat, true) }
            if (oldIndex >= 0) notifyItemChanged(oldIndex) else notifyDataSetChanged()
            if (newIndex >= 0 && newIndex != oldIndex) notifyItemChanged(newIndex)
        }

        fun update(newList: List<String>) {
            items = newList
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_tv_category_card, parent, false)
            return VH(view)
        }

        override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position])
        override fun getItemId(position: Int): Long = items[position].hashCode().toLong()
        override fun getItemCount() = items.size

        inner class VH(private val view: View) : RecyclerView.ViewHolder(view) {
            private val iconText: TextView = view.findViewById(R.id.catIconText)
            private val nameText: TextView = view.findViewById(R.id.catNameText)

            fun bind(cat: String) {
                try {
                    val isFav = FavoriteManager.isFavoriteCategory(view.context, profileId, cat.removePrefix("📁 "))
                    val isSel = cat.equals(selectedCat, true)
                    val label = if (cat.lowercase().let { it.contains("sport ar") || it.contains("bein") }) "👑 beIN Sports" else cat

                    iconText.text = if (isFav) "⭐" else "📁"
                    iconText.textSize = 17f
                    nameText.text = if (isSel) "● $label" else label
                    nameText.textSize = 13f
                    nameText.setTextColor(if (isSel) Color.parseColor("#FFD700") else Color.WHITE)

                    view.setOnClickListener { onClick(cat) }
                    view.setOnLongClickListener { performFavoriteCategoryToggle(cat.removePrefix("📁 ")); true }
                    view.setOnFocusChangeListener { v, has ->
                        try {
                            v.animate().scaleX(if (has) 1.03f else 1f).scaleY(if (has) 1.03f else 1f).setDuration(90).start()
                            if (has) {
                                activePanel = ActivePanel.CATEGORIES
                                updateAlphabetHint()
                            }
                        } catch (_: Exception) {}
                    }
                } catch (_: Exception) {}
            }
        }
    }

    private inner class RoyalChannelsAdapter(
        private var items: List<Channel>,
        private var playingUrl: String?,
        private val onClick: (Channel) -> Unit,
        private val onLongClick: (Channel) -> Unit
    ) : RecyclerView.Adapter<RoyalChannelsAdapter.VH>() {

        init { setHasStableIds(true) }

        fun update(newList: List<Channel>) {
            items = newList
            notifyDataSetChanged()
        }

        fun setPlayingUrl(url: String) {
            val oldUrl = playingUrl
            playingUrl = url
            val oldIndex = items.indexOfFirst { it.streamUrl == oldUrl }
            val newIndex = items.indexOfFirst { it.streamUrl == url }
            if (oldIndex >= 0) notifyItemChanged(oldIndex) else notifyDataSetChanged()
            if (newIndex >= 0 && newIndex != oldIndex) notifyItemChanged(newIndex)
        }

        fun notifyFavoriteChanged(url: String) {
            val index = items.indexOfFirst { it.streamUrl == url }
            if (index >= 0) notifyItemChanged(index) else notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_tv_channel_card, parent, false)
            return VH(view)
        }

        override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position])
        override fun getItemId(position: Int): Long = (items[position].streamUrl.ifBlank { items[position].name }).hashCode().toLong()
        override fun getItemCount() = items.size

        inner class VH(private val view: View) : RecyclerView.ViewHolder(view) {
            private val logoImg: ImageView = view.findViewById(R.id.chLogoImg)
            private val nameText: TextView = view.findViewById(R.id.chNameText)
            private val favImg: ImageView = view.findViewById(R.id.chFavImg)

            fun bind(ch: Channel) {
                try {
                    val isPlaying = ch.streamUrl == playingUrl
                    val isFav = FavoriteManager.isFavoriteChannel(view.context, profileId, ch.streamUrl)

                    nameText.text = if (isPlaying) "▶ ${ch.name}" else ch.name
                    nameText.textSize = 13f
                    nameText.setTextColor(if (isPlaying) Color.parseColor("#39FF8B") else Color.WHITE)
                    favImg.visibility = if (isFav) View.VISIBLE else View.GONE

                    Glide.with(view.context).load(ch.logoUrl.ifBlank { null }).placeholder(R.drawable.ic_tv).error(R.drawable.ic_tv).into(logoImg)

                    view.setOnClickListener { onClick(ch) }
                    view.setOnLongClickListener { onLongClick(ch); true }
                    view.setOnFocusChangeListener { v, has ->
                        try {
                            v.animate().scaleX(if (has) 1.03f else 1f).scaleY(if (has) 1.03f else 1f).setDuration(90).start()
                            if (has) {
                                lastFocusedChannelUrl = ch.streamUrl
                                activePanel = ActivePanel.CHANNELS
                                updateAlphabetHint()
                            }
                        } catch (_: Exception) {}
                    }
                } catch (_: Exception) {}
            }
        }
    }
}
