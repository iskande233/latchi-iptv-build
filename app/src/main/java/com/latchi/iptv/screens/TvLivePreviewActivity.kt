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
import com.latchi.iptv.utils.ChannelCache
import com.latchi.iptv.utils.DigitNormalizer
import com.latchi.iptv.utils.FavoriteManager
import com.latchi.iptv.utils.PlayerServerSyncHelper
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
    private var currentPlayingUrl: String? = null
    private var hideCategories: Boolean = false

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
                putParcelableArrayListExtra("extra_channels", ArrayList(live))
                putExtra("category", "All")
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

            // جلب القنوات
            val passedChannel = intent.getParcelableExtra<Channel>("channel")
            val customList = intent.getParcelableArrayListExtra<Channel>("extra_channels")
            val cachedList = ChannelCache.load(this, profileId)

            var rawLive = customList?.filter { it.contentType == "live" }
            if (rawLive.isNullOrEmpty()) rawLive = cachedList.filter { it.contentType == "live" }
            if (rawLive.isEmpty() && passedChannel != null) rawLive = listOf(passedChannel)

            if (rawLive.isEmpty()) {
                Toast.makeText(this, "⏳ يرجى انتظار تحميل القنوات...", Toast.LENGTH_LONG).show()
                finish()
                return
            }

            allLiveChannels = rawLive
            selectedChannel = passedChannel ?: allLiveChannels.first()

            setFindViewById()
            buildUniversalAlphabetBar()
            prepareSmartCategories()

            // معالج زر Back
            onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (isFullscreenMode) toggleFullscreen(false)
                    else finish()
                }
            })
        } catch (e: Exception) {
            Log.e("TvLiveDashboard", "onCreate Crash: ${e.message}")
            Toast.makeText(this, "حدث خطأ: ${e.message}", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    private fun setFindViewById() {
        panelCategories = findViewById(R.id.panelCategories)
        panelChannels = findViewById(R.id.panelChannels)
        panelPlayer = findViewById(R.id.panelPlayer)
        recyclerCategories = findViewById(R.id.recyclerCategories)
        recyclerChannels = findViewById(R.id.recyclerChannels)
        txtFilterHint = findViewById(R.id.txtFilterHint)
        containerAlphabet = findViewById(R.id.containerAlphabet)
        txtChannelTitle = findViewById(R.id.txtChannelTitle)
        txtCategorySubtitle = findViewById(R.id.txtCategorySubtitle)
        txtEpgInfo = findViewById(R.id.txtEpgInfo)
        frameVideo = findViewById(R.id.frameVideo)
        viewPlayer = findViewById(R.id.viewPlayer)
        txtDetailsBottom = findViewById(R.id.txtDetailsBottom)

        recyclerCategories.layoutManager = LinearLayoutManager(this)
        recyclerChannels.layoutManager = LinearLayoutManager(this)

        TvFocusHelper.setupRecycler(recyclerCategories)
        TvFocusHelper.setupRecycler(recyclerChannels)

        frameVideo.setOnClickListener { toggleFullscreen(true) }
        frameVideo.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN && (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)) {
                toggleFullscreen(true); true
            } else false
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
                .map { it.category }.distinct().filter { it.isNotBlank() }

            // 👑 الذكاء الاصطناعي: إبراز beIN Sports كفئة أولى
            val beinCat = rawCats.firstOrNull { cat ->
                val l = cat.lowercase()
                l.contains("sport ar") || l.contains("bein") || l.contains("sports")
            } ?: (if (rawCats.isNotEmpty()) rawCats.first() else "All")

            val others = rawCats.filter { !it.equals(beinCat, true) }.sorted()
            
            val completeList = mutableListOf<String>()
            completeList.add("⭐ المفضلة")
            completeList.addAll(favCats.map { "📁 $it" })
            completeList.add(beinCat)
            completeList.addAll(others)

            currentCategories = completeList.distinct()
            selectedCategoryName = beinCat

            categoriesAdapter = RoyalCategoriesAdapter(currentCategories, selectedCategoryName) { chosen ->
                loadCategoryChannels(chosen, null)
                recyclerChannels.postDelayed({
                    recyclerChannels.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus()
                    activePanel = ActivePanel.CHANNELS
                    updateAlphabetHint()
                }, 80)
            }
            recyclerCategories.adapter = categoriesAdapter

            loadCategoryChannels(beinCat, null)

            // فوكس على أول فئة ذكية
            val beinIdx = currentCategories.indexOf(beinCat).coerceAtLeast(0)
            recyclerCategories.post {
                recyclerCategories.scrollToPosition(beinIdx)
                recyclerCategories.findViewHolderForAdapterPosition(beinIdx)?.itemView?.requestFocus()
            }
        } catch (e: Exception) { Log.e("TvLiveDashboard", "Prepare Cats Crash: ${e.message}") }
    }

    private fun loadCategoryChannels(catLabel: String, filterLetter: String?) {
        try {
            selectedCategoryName = catLabel
            activeChLetter = filterLetter

            val isFavSection = catLabel == "⭐ المفضلة"
            val isFavCatSection = catLabel.startsWith("📁 ")
            val realCatName = if (isFavCatSection) catLabel.removePrefix("📁 ") else catLabel

            val hiddenSet = getHiddenSet()

            var rawList = when {
                isFavSection -> FavoriteManager.getFavoriteChannels(this, profileId)
                isFavCatSection -> allLiveChannels.filter { it.category.equals(realCatName, true) }
                realCatName == "All" -> allLiveChannels.filter { !hiddenSet.contains(it.category.trim().lowercase()) }
                else -> allLiveChannels.filter { it.category.equals(realCatName, true) && !hiddenSet.contains(it.category.trim().lowercase()) }
            }

            if (rawList.isEmpty() && !isFavSection) {
                rawList = allLiveChannels.filter { !hiddenSet.contains(it.category.trim().lowercase()) }
            }

            // تنظيف beIN وتصدر MAX
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

            // تطبيق فلتر الحرف إن وجد
            currentCategoryChannels = if (filterLetter != null && filterLetter != "All") {
                rawList.filter { it.name.trim().startsWith(filterLetter, true) }
            } else {
                rawList
            }

            // تحديث الواجهة
            if (channelsAdapter == null) {
                channelsAdapter = RoyalChannelsAdapter(currentCategoryChannels, selectedChannel?.streamUrl, onClick = { ch ->
                    if (selectedChannel?.streamUrl == ch.streamUrl && player?.isPlaying == true) {
                        toggleFullscreen(true)
                    } else {
                        playRoyalLiveChannel(ch)
                    }
                }, onLongClick = { ch ->
                    performFavoriteChannelToggle(ch)
                })
                recyclerChannels.adapter = channelsAdapter
            } else {
                channelsAdapter?.update(currentCategoryChannels)
                recyclerChannels.scrollToPosition(0)
            }

            categoriesAdapter?.setSelectedCat(catLabel)
            
            val suffix = if (filterLetter != null) " (حرف $filterLetter)" else ""
            txtFilterHint.text = "📺 القنوات: $catLabel$suffix"

            // تشغيل أول قناة تلقائياً إذا لم يكن المشغل شغالاً
            if (currentCategoryChannels.isNotEmpty() && player == null) {
                playRoyalLiveChannel(currentCategoryChannels.first())
            }
        } catch (e: Exception) { Log.e("TvLiveDashboard", "Load Channels Crash: ${e.message}") }
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
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(36)).apply { marginEnd = dp(6) }

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
                        v.animate().scaleX(if (has) 1.1f else 1f).scaleY(if (has) 1.1f else 1f).setDuration(80).start()
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
            val hint = when (activePanel) {
                ActivePanel.CATEGORIES -> "🔤 الحروف تفلتر الفئات (ركز على عمود الفئات)"
                ActivePanel.CHANNELS -> "🔤 الحروف تفلتر القنوات (ركز على عمود القنوات)"
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
                panelCategories.visibility = View.VISIBLE
                panelChannels.visibility = View.VISIBLE
                txtChannelTitle.visibility = View.VISIBLE
                txtCategorySubtitle.visibility = View.VISIBLE
                txtEpgInfo.visibility = View.VISIBLE
                txtDetailsBottom.visibility = View.VISIBLE

                panelPlayer.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 0.40f)
                frameVideo.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            }
        } catch (_: Exception) {}
    }

    private fun performFavoriteChannelToggle(ch: Channel) {
        try {
            val isAdded = FavoriteManager.toggleFavoriteChannel(this, profileId, ch)
            val msg = if (isAdded) "⭐ تم حفظ القناة '${ch.name}' في المفضلة ✓" else "إلغاء حفظ القناة '${ch.name}' من المفضلة"
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
            channelsAdapter?.notifyDataSetChanged()
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

        fun setSelectedCat(cat: String) { this.selectedCat = cat; notifyDataSetChanged() }
        fun update(newList: List<String>) { this.items = newList; notifyDataSetChanged() }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_tv_category_card, parent, false)
            return VH(view)
        }

        override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position])
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
                    nameText.text = if (isSel) "● $label" else label
                    nameText.setTextColor(if (isSel) Color.parseColor("#FFD700") else Color.WHITE)

                    view.setOnClickListener { onClick(cat) }
                    view.setOnLongClickListener { performFavoriteCategoryToggle(cat.removePrefix("📁 ")); true }
                    view.setOnFocusChangeListener { v, has ->
                        try {
                            v.animate().scaleX(if (has) 1.05f else 1f).scaleY(if (has) 1.05f else 1f).setDuration(100).start()
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

        fun update(newList: List<Channel>) { this.items = newList; notifyDataSetChanged() }
        fun setPlayingUrl(url: String) { this.playingUrl = url; notifyDataSetChanged() }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_tv_channel_card, parent, false)
            return VH(view)
        }

        override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position])
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
                    nameText.setTextColor(if (isPlaying) Color.parseColor("#39FF8B") else Color.WHITE)
                    favImg.visibility = if (isFav) View.VISIBLE else View.GONE

                    Glide.with(view.context).load(ch.logoUrl.ifBlank { null }).placeholder(R.drawable.ic_tv).error(R.drawable.ic_tv).into(logoImg)

                    view.setOnClickListener { onClick(ch) }
                    view.setOnLongClickListener { onLongClick(ch); true }
                    view.setOnFocusChangeListener { v, has ->
                        try {
                            v.animate().scaleX(if (has) 1.04f else 1f).scaleY(if (has) 1.04f else 1f).setDuration(100).start()
                            if (has) {
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
