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
import android.util.JsonReader
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
import com.latchi.iptv.utils.DigitNormalizer
import com.latchi.iptv.utils.FavoriteManager
import com.latchi.iptv.utils.PreparedCatalogHelper
import com.latchi.iptv.utils.RemoteViewConfigPrefs
import com.latchi.iptv.utils.SourcePrefs
import com.latchi.iptv.utils.ThemeManager
import com.latchi.iptv.utils.TvFocusHelper
import com.latchi.iptv.utils.UnifiedChannelRepository
import com.latchi.iptv.utils.XtreamHelper
import java.util.concurrent.TimeUnit
import java.net.URLEncoder
import okhttp3.Request
import okhttp3.OkHttpClient

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
    private var directFilterMode: String? = null
    private var lastFocusedChannelUrl: String? = null
    private var dashboardInitialized: Boolean = false
    private var lazyXtreamLiveMode: Boolean = false
    private val lazyCategoryCache = mutableMapOf<String, List<Channel>>()
    private var lazyTvCategories: List<TvLazyCategory> = emptyList()
    private var lazyCurrentRawChannels: List<Channel> = emptyList()
    private var lazyLoadingCategory: Boolean = false

    private data class TvLazyCategory(val id: String, val name: String, val count: Int = -1)

    private val lazyClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(35, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    // ExoPlayer
    private var player: ExoPlayer? = null
    private lateinit var viewPlayer: PlayerView
    private lateinit var frameVideo: FrameLayout

    // Views
    private lateinit var mainDashboardContainer: FrameLayout
    private lateinit var panelCategories: LinearLayout
    private lateinit var panelAlphabet: LinearLayout
    private lateinit var panelChannels: LinearLayout
    private lateinit var panelPlayer: FrameLayout
    private lateinit var recyclerCategories: RecyclerView
    private lateinit var recyclerChannels: RecyclerView
    private lateinit var txtFilterHint: TextView
    private lateinit var alphabetScroller: View
    private lateinit var containerAlphabet: LinearLayout
    private lateinit var txtChannelTitle: TextView
    private lateinit var txtCategorySubtitle: TextView
    private lateinit var txtEpgInfo: TextView
    private lateinit var txtDetailsBottom: TextView
    private var darkOverlay: View? = null
    private var menuOverlay: View? = null
    private val overlayHandler = Handler(Looper.getMainLooper())
    private val hideOverlayRunnable = Runnable { hideMenuOverlay() }

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

        fun startDirectBeinSports(context: Context) {
            context.startActivity(Intent(context, TvLivePreviewActivity::class.java).apply {
                putExtra("category", "beIN SPORTS")
                putExtra("hide_categories", true)
                putExtra("load_from_cache", true)
                putExtra("direct_filter_mode", "bein_alwan")
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
            directFilterMode = intent.getStringExtra("direct_filter_mode")?.takeIf { it.isNotBlank() }

            // جلب القنوات
            val passedChannel = intent.getParcelableExtra<Channel>("channel")
            val customList = intent.getParcelableArrayListExtra<Channel>("extra_channels")
            
            if (customList != null && customList.isNotEmpty()) {
                // القنوات مرسلة مع الـ Intent (beIN Sports / Matches - عدد قليل)
                allLiveChannels = customList.filter { it.contentType == "live" }.ifEmpty { customList }
                selectedChannel = resolveInitialChannel(passedChannel, allLiveChannels)
                initDashboard()
            } else {
                initDashboard()
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

    /**
     * 🛡️ v6.0 Fix: جلب واحد موثوق — Room أولاً، إذا فارغة → force sync من السيرفر.
     * يتجنب race conditions بين عدة threads وجلبات متعددة.
     */
    private fun loadLiveChannelsSmart(passedChannel: Channel?) {
        val active = SourcePrefs.getActiveProfile(this)
        if (active == null) {
            finish()
            return
        }

        runOnUiThread {
            txtDetailsBottom.text = "⏳ جاري تحميل القنوات بنفس آلية الهاتف..."
            txtDetailsBottom.setTextColor(Color.parseColor("#A5B4FC"))
        }

        val xtreamCreds = XtreamHelper.parseCreds(active.m3uUrl)
        if (xtreamCreds != null && directFilterMode != "bein_alwan") {
            loadXtreamLiveCategoriesLazy(active, xtreamCreds, passedChannel)
            return
        }

        // توحيد آلية الجلب: نفس Repository ونفس الداتا للهاتف والتلفاز.
        // الاختلاف فقط في الواجهة (Overlay/Focus)، أما القنوات والفلترة فمصدرها واحد.
        UnifiedChannelRepository.loadLive(applicationContext, active) { result ->
            if (isFinishing) return@loadLive

            fun applyLoadedChannels(source: List<Channel>, forceMessage: String? = null) {
                val liveOnly = source.filter { it.contentType == "live" }.ifEmpty { source }
                allLiveChannels = applyDirectFilterIfNeeded(liveOnly)
                selectedChannel = resolveInitialChannel(passedChannel, allLiveChannels)
                initDashboard()
                if (allLiveChannels.isEmpty()) {
                    txtDetailsBottom.text = forceMessage ?: "⚠️ لا توجد قنوات Live متاحة حالياً. تأكد من اتصال السيرفر أو أعد التحديث."
                    txtDetailsBottom.setTextColor(Color.parseColor("#FF5577"))
                } else {
                    txtDetailsBottom.text = forceMessage ?: "✅ تم تحميل ${allLiveChannels.size} قناة Live"
                    txtDetailsBottom.setTextColor(Color.parseColor("#39FF8B"))
                }
            }

            val loaded = result.channels.filter { it.contentType == "live" }.ifEmpty { result.channels }
            val directFiltered = applyDirectFilterIfNeeded(loaded)

            // إذا كانت واجهة beIN/ALWAN فارغة رغم وجود قنوات عامة، نفحصها بالفلتر الذكي الموحد.
            if (directFilterMode == "bein_alwan" && directFiltered.isEmpty()) {
                UnifiedChannelRepository.loadBein(this, active) { beinResult ->
                    if (!isFinishing) {
                        applyLoadedChannels(beinResult.channels, if (beinResult.channels.isEmpty()) "📭 لم يتم العثور على قنوات beIN في السيرفر الحالي" else null)
                    }
                }
                return@loadLive
            }

            applyLoadedChannels(loaded, if (result.source == "cache_fallback") "⚠️ تم عرض آخر كاش متوفر" else null)
        }
    }

    private fun loadXtreamLiveCategoriesLazy(active: com.latchi.iptv.utils.IptvProfile, creds: XtreamHelper.Creds, passedChannel: Channel?) {
        lazyXtreamLiveMode = true
        Thread {
            val cats = runCatching { fetchXtreamLiveCategories(creds) }.getOrDefault(emptyList())
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                if (cats.isEmpty()) {
                    lazyXtreamLiveMode = false
                    // fallback للمنطق العام إذا API الفئات فشل.
                    loadLiveChannelsSmartFallback(active, passedChannel)
                    return@runOnUiThread
                }
                lazyTvCategories = sortLazyCategories(cats)
                currentCategories = lazyTvCategories.map { it.name }
                val preferred = chooseInitialLazyCategory(lazyTvCategories, requestedCategoryName)
                selectedCategoryName = preferred.name
                renderCategoriesList()
                requestCategoryFocus(preferred.name)
                loadLazyXtreamCategory(preferred.name, null, passedChannel)
            }
        }.start()
    }

    private fun loadLiveChannelsSmartFallback(active: com.latchi.iptv.utils.IptvProfile, passedChannel: Channel?) {
        UnifiedChannelRepository.loadLive(applicationContext, active) { result ->
            if (isFinishing) return@loadLive
            val loaded = result.channels.filter { it.contentType == "live" }.ifEmpty { result.channels }
            allLiveChannels = applyDirectFilterIfNeeded(loaded)
            selectedChannel = resolveInitialChannel(passedChannel, allLiveChannels)
            initDashboard()
            if (allLiveChannels.isEmpty()) {
                txtDetailsBottom.text = "⚠️ لا توجد قنوات Live متاحة حالياً. تأكد من اتصال السيرفر أو أعد التحديث."
                txtDetailsBottom.setTextColor(Color.parseColor("#FF5577"))
            }
        }
    }

    private fun fetchXtreamLiveCategories(creds: XtreamHelper.Creds): List<TvLazyCategory> {
        val url = "${creds.server}/player_api.php?username=${enc(creds.username)}&password=${enc(creds.password)}&action=get_live_categories"
        val req = Request.Builder().url(url).header("User-Agent", "Mozilla/5.0 (Linux; Android 10)").get().build()
        lazyClient.newCall(req).execute().use { res ->
            if (!res.isSuccessful) return emptyList()
            val stream = res.body?.byteStream() ?: return emptyList()
            val out = mutableListOf<TvLazyCategory>()
            JsonReader(stream.bufferedReader(Charsets.UTF_8)).use { r ->
                r.beginArray()
                while (r.hasNext()) {
                    var id = ""
                    var name = ""
                    var count = -1
                    r.beginObject()
                    while (r.hasNext()) {
                        when (r.nextName()) {
                            "category_id" -> id = readJsonStringSafe(r)
                            "category_name" -> name = readJsonStringSafe(r)
                            "count", "category_count" -> count = readJsonStringSafe(r).toIntOrNull() ?: -1
                            else -> r.skipValue()
                        }
                    }
                    r.endObject()
                    if (id.isNotBlank() && name.isNotBlank()) out.add(TvLazyCategory(id, name.trim(), count))
                }
                r.endArray()
            }
            return out
        }
    }

    private fun fetchXtreamLiveCategoryChannels(creds: XtreamHelper.Creds, cat: TvLazyCategory): List<Channel> {
        val url = "${creds.server}/player_api.php?username=${enc(creds.username)}&password=${enc(creds.password)}&action=get_live_streams&category_id=${enc(cat.id)}"
        val req = Request.Builder().url(url).header("User-Agent", "Mozilla/5.0 (Linux; Android 10)").get().build()
        val out = mutableListOf<Channel>()
        lazyClient.newCall(req).execute().use { res ->
            if (!res.isSuccessful) return emptyList()
            val stream = res.body?.byteStream() ?: return emptyList()
            JsonReader(stream.bufferedReader(Charsets.UTF_8)).use { r ->
                r.beginArray()
                while (r.hasNext()) {
                    var id = ""
                    var name = ""
                    var icon = ""
                    r.beginObject()
                    while (r.hasNext()) {
                        when (r.nextName()) {
                            "stream_id" -> id = readJsonStringSafe(r)
                            "name" -> name = readJsonStringSafe(r).ifBlank { "Live Stream" }
                            "stream_icon" -> icon = readJsonStringSafe(r)
                            else -> r.skipValue()
                        }
                    }
                    r.endObject()
                    if (id.isNotBlank() && name.isNotBlank()) {
                        out.add(Channel(name, icon, "${creds.server}/live/${creds.username}/${creds.password}/$id.ts", cat.name, "live"))
                    }
                }
                r.endArray()
            }
        }
        return out.distinctBy { it.streamUrl }
    }

    private fun loadLazyXtreamCategory(catLabel: String, filterLetter: String?, passedChannel: Channel? = null) {
        val active = SourcePrefs.getActiveProfile(this) ?: return
        val creds = XtreamHelper.parseCreds(active.m3uUrl) ?: return
        val cat = lazyTvCategories.firstOrNull { UnifiedChannelRepository.sameCategory(it.name, catLabel) } ?: return
        selectedCategoryName = cat.name
        activeChLetter = filterLetter
        activePanel = ActivePanel.CHANNELS
        categoriesAdapter?.setSelectedCat(cat.name)
        updateAlphabetHint()
        val cached = lazyCategoryCache[cat.id]
        if (cached != null) {
            applyLazyCategoryChannels(cat, cached, filterLetter, passedChannel)
            return
        }
        if (lazyLoadingCategory) return
        lazyLoadingCategory = true
        txtDetailsBottom.text = "⏳ جاري تحميل فئة ${cat.name}..."
        txtDetailsBottom.setTextColor(Color.parseColor("#A5B4FC"))
        Thread {
            val channels = runCatching { fetchXtreamLiveCategoryChannels(creds, cat) }.getOrDefault(emptyList())
            runOnUiThread {
                lazyLoadingCategory = false
                if (channels.isNotEmpty()) lazyCategoryCache[cat.id] = channels
                applyLazyCategoryChannels(cat, channels, filterLetter, passedChannel)
            }
        }.start()
    }

    private fun applyLazyCategoryChannels(cat: TvLazyCategory, channels: List<Channel>, filterLetter: String?, passedChannel: Channel?) {
        lazyCurrentRawChannels = channels
        allLiveChannels = channels
        currentCategoryChannels = if (filterLetter != null && filterLetter != "All") {
            channels.filter { it.name.trim().startsWith(filterLetter, true) }
        } else channels
        renderChannelsList()
        txtFilterHint.text = "📺 ${cat.name}${if (filterLetter != null) " (حرف $filterLetter)" else ""}"
        if (currentCategoryChannels.isEmpty()) {
            releasePreviewPlayer()
            txtChannelTitle.text = "لا توجد قناة"
            txtCategorySubtitle.text = "الفئة '${cat.name}' فارغة حالياً"
            txtDetailsBottom.text = "📭 لا توجد قنوات في هذه الفئة"
            return
        }
        val target = resolveInitialChannel(passedChannel ?: selectedChannel, currentCategoryChannels) ?: currentCategoryChannels.first()
        playRoyalLiveChannel(target)
        requestChannelFocusByUrl(target.streamUrl, fallbackToFirst = true)
        txtDetailsBottom.text = "✅ ${currentCategoryChannels.size} قناة في ${cat.name}"
        txtDetailsBottom.setTextColor(Color.parseColor("#39FF8B"))
    }

    private fun chooseInitialLazyCategory(cats: List<TvLazyCategory>, requested: String): TvLazyCategory {
        return cats.firstOrNull { it.name.equals(requested, true) }
            ?: cats.firstOrNull { c -> c.name.lowercase().let { it.contains("world cup") || it.contains("كأس العالم") } }
            ?: cats.firstOrNull { c -> c.name.lowercase().let { it.contains("bein") || it.contains("sport") || it.contains("رياض") } }
            ?: cats.first()
    }

    private fun sortLazyCategories(cats: List<TvLazyCategory>): List<TvLazyCategory> {
        fun score(name: String): Int {
            val l = name.lowercase()
            return when {
                l.contains("world cup") || l.contains("كأس العالم") -> 0
                l.contains("bein") || l.contains("بي ان") || l.contains("بي إن") -> 1
                l.contains("sport") || l.contains("رياض") || l.contains("ssc") || l.contains("alkass") -> 2
                l.contains("news") || l.contains("أخبار") || l.contains("اخبار") -> 3
                l.contains("kid") || l.contains("أطفال") || l.contains("اطفال") -> 4
                else -> 10
            }
        }
        return cats.sortedWith(compareBy<TvLazyCategory> { score(it.name) }.thenBy { it.name.lowercase() })
    }

    private fun readJsonStringSafe(r: JsonReader): String {
        return try {
            r.nextString()
        } catch (_: Exception) {
            try { r.nextInt().toString() } catch (_: Exception) { r.skipValue(); "" }
        }
    }

    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")

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

    private fun applyDirectFilterIfNeeded(channels: List<Channel>): List<Channel> {
        return when (directFilterMode) {
            "bein_alwan" -> filterDirectBeinAndAlwanChannels(channels)
            else -> channels
        }
    }

    private fun filterDirectBeinAndAlwanChannels(channels: List<Channel>): List<Channel> {
        val remoteConfig = RemoteViewConfigPrefs.getFilterConfig(this, profileId)
        val beinTokens = remoteConfig.beinKeywords
        val beinMaxTokens = remoteConfig.beinMaxKeywords
        val alwanTokens = remoteConfig.alwanKeywords

        fun normalized(ch: Channel): String = DigitNormalizer.normalizeDigits("${ch.name} ${ch.category}").lowercase()
        fun hasAny(text: String, tokens: List<String>): Boolean = tokens.any { token -> text.contains(token.lowercase()) }
        fun isBein(text: String): Boolean = hasAny(text, beinTokens)
        fun isBeinMax(text: String): Boolean = hasAny(text, beinTokens) && hasAny(text, beinMaxTokens)
        fun isAlwan(text: String): Boolean = hasAny(text, alwanTokens)
        fun isWorldCup(text: String): Boolean =
            text.contains("world cup") ||
            text.contains("كاس العالم") ||
            text.contains("كأس العالم") ||
            text.contains("كاس العالم") ||
            text.contains("fifa") ||
            text.contains("wc ")
        fun firstNumber(text: String): Int = Regex("\\d+").find(text)?.value?.toIntOrNull() ?: 999

        // 🛡️ فلترة: beIN + ALWAN فقط (إزالة القنوات العامة مثل "sport" بدون bein)
        // + World Cup دائماً يمر (لاحظته مهم)
        return channels
            .filter { ch ->
                val text = normalized(ch)
                isBein(text) || isAlwan(text) || isWorldCup(text)
            }
            .distinctBy { it.streamUrl.ifBlank { it.name } }
            .sortedWith(
                compareBy<Channel> { ch ->
                    val text = normalized(ch)
                    when {
                        // 🏆 كأس العالم في المرتبة 0 (يظهر أولاً)
                        isWorldCup(text) -> 0
                        // 👑 beIN MAX في المرتبة 1 (بعد كأس العالم مباشرة)
                        isBeinMax(text) -> 1
                        // 📺 beIN العادي في المرتبة 2
                        isBein(text) -> 2
                        // 🎨 ALWAN في المرتبة 3
                        isAlwan(text) -> 3
                        else -> 4
                    }
                }.thenBy { ch ->
                    val text = normalized(ch)
                    firstNumber(text)
                }.thenBy { ch -> normalized(ch) }
            )
    }

    private fun initDashboard() {
        if (!dashboardInitialized) {
            setFindViewById()
            if (!hideCategories) buildUniversalAlphabetBar()
            onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (isFullscreenMode) toggleFullscreen(false)
                    else finish()
                }
            })
            dashboardInitialized = true
        }
        prepareSmartCategories()
    }

    private fun setFindViewById() {
        mainDashboardContainer = findViewById(R.id.mainDashboardContainer)
        panelCategories = findViewById(R.id.panelCategories)
        panelAlphabet = findViewById(R.id.panelAlphabet)
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
        viewPlayer = findViewById(R.id.player_view)
        txtDetailsBottom = findViewById(R.id.txtDetailsBottom)
        darkOverlay = findViewById(R.id.dark_overlay)
        menuOverlay = findViewById(R.id.menuOverlay)

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
            // Cinematic Overlay UI: الفيديو يبقى دائماً في الخلفية بكامل الشاشة،
            // والقوائم تظهر فوقه بطبقة زجاجية شفافة بدون تغيير طريقة جلب القنوات.
            darkOverlay?.visibility = View.VISIBLE
            menuOverlay?.visibility = View.VISIBLE
            if (hideCategories) {
                panelCategories.visibility = View.GONE
                panelAlphabet.visibility = View.GONE
                alphabetScroller.visibility = View.GONE
            } else {
                panelCategories.visibility = View.VISIBLE
                panelAlphabet.visibility = View.VISIBLE
                alphabetScroller.visibility = View.VISIBLE
            }
            panelChannels.visibility = View.VISIBLE
            panelPlayer.visibility = View.VISIBLE

            txtFilterHint.textSize = 13f
            txtChannelTitle.textSize = if (hideCategories) 18f else 16f
            txtCategorySubtitle.textSize = 11.5f
            txtEpgInfo.textSize = 11f
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
        frameVideo.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        )
        viewPlayer.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
    }

    private fun showMenuOverlay(autoHide: Boolean = true) {
        try {
            overlayHandler.removeCallbacks(hideOverlayRunnable)
            darkOverlay?.visibility = View.VISIBLE
            menuOverlay?.visibility = View.VISIBLE
            if (autoHide) scheduleMenuOverlayHide()
        } catch (_: Exception) {}
    }

    private fun scheduleMenuOverlayHide(delayMs: Long = 3000L) {
        try {
            overlayHandler.removeCallbacks(hideOverlayRunnable)
            overlayHandler.postDelayed(hideOverlayRunnable, delayMs)
        } catch (_: Exception) {}
    }

    private fun hideMenuOverlay() {
        try {
            if (isFullscreenMode) return
            darkOverlay?.visibility = View.GONE
            menuOverlay?.visibility = View.GONE
            frameVideo.requestFocus()
        } catch (_: Exception) {}
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && !isFullscreenMode) {
            val wasHidden = menuOverlay?.visibility != View.VISIBLE
            showMenuOverlay(autoHide = true)
            // أول ضغطة بعد اختفاء القائمة هدفها إظهار القائمة فقط، بلا ما تبدل الفوكس بالغلط.
            if (wasHidden && event.keyCode != KeyEvent.KEYCODE_BACK) return true
        }
        return super.dispatchKeyEvent(event)
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

            if (allLiveChannels.isEmpty() || rawCats.isEmpty()) {
                currentCategories = emptyList()
                selectedCategoryName = requestedCategoryName.ifBlank { "All" }
                renderCategoriesList()
                currentCategoryChannels = emptyList()
                renderChannelsList()
                activePanel = if (hideCategories) ActivePanel.CHANNELS else ActivePanel.CATEGORIES
                releasePreviewPlayer()
                txtChannelTitle.text = "لا توجد قناة"
                txtCategorySubtitle.text = "جاري المزامنة أو القائمة فارغة حالياً"
                txtEpgInfo.text = "افتح الواجهة بشكل عادي، وستظهر القنوات فور عودة السيرفر أو بعد أي تحديث جديد"
                txtDetailsBottom.text = "⚠️ لا توجد قنوات حالياً"
                txtFilterHint.text = if (hideCategories) "📭 لا توجد قنوات في هذه القائمة" else "📭 الفئات والقنوات فارغة حالياً"
                return
            }

            val beinCat = rawCats.firstOrNull { cat ->
                val l = cat.lowercase()
                l.contains("sport ar") || l.contains("bein") || l.contains("sports")
            } ?: rawCats.first()

            val requestedCat = rawCats.firstOrNull { it.equals(requestedCategoryName, true) }
            val preferredCat = requestedCat ?: beinCat
            val others = rawCats.filter { !it.equals(beinCat, true) }.sorted()

            if (hideCategories) {
                selectedCategoryName = requestedCategoryName.ifBlank { preferredCat }
                currentCategories = listOf(selectedCategoryName)
                currentCategoryChannels = allLiveChannels
                renderChannelsList()
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

            renderCategoriesList()
            loadCategoryChannels(preferredCat, null)
            requestCategoryFocus(preferredCat)
        } catch (e: Exception) {
            Log.e("TvLiveDashboard", "Prepare Cats Crash: ${e.message}")
        }
    }

    private fun renderCategoriesList() {
        if (hideCategories) return
        categoriesAdapter = RoyalCategoriesAdapter(currentCategories, selectedCategoryName) { chosen ->
            if (lazyXtreamLiveMode) loadLazyXtreamCategory(chosen, null) else loadCategoryChannels(chosen, null)
            lastFocusedChannelUrl = currentCategoryChannels.firstOrNull()?.streamUrl
            recyclerChannels.postDelayed({
                requestChannelFocusByUrl(lastFocusedChannelUrl, fallbackToFirst = true)
                activePanel = ActivePanel.CHANNELS
                updateAlphabetHint()
            }, 80)
        }
        recyclerCategories.adapter = categoriesAdapter
    }

    private fun renderChannelsList() {
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
    }

    private fun releasePreviewPlayer() {
        try {
            player?.release()
            player = null
            viewPlayer.player = null
            currentPlayingUrl = null
            channelsAdapter?.setPlayingUrl("")
        } catch (_: Exception) {}
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
            val remoteConfig = RemoteViewConfigPrefs.getFilterConfig(this, profileId)
            val originalCatName = remoteConfig.customNames.entries
                .firstOrNull { it.value.equals(realCatName, ignoreCase = true) }?.key ?: realCatName
            fun sameCategory(a: String, b: String): Boolean = UnifiedChannelRepository.sameCategory(a, b)

            if (lazyXtreamLiveMode && !isFavSection) {
                val catExists = lazyTvCategories.any { sameCategory(it.name, realCatName) }
                if (catExists && lazyTvCategories.none { sameCategory(it.name, selectedCategoryName) && lazyCategoryCache.containsKey(it.id) }) {
                    loadLazyXtreamCategory(realCatName, filterLetter)
                    return
                }
                if (catExists && !sameCategory(realCatName, selectedCategoryName)) {
                    loadLazyXtreamCategory(realCatName, filterLetter)
                    return
                }
            }

            var rawList = when {
                hideCategories -> allLiveChannels
                isFavSection -> FavoriteManager.getFavoriteChannels(this, profileId)
                isFavCatSection -> allLiveChannels.filter { sameCategory(it.category, originalCatName) || sameCategory(it.category, realCatName) }
                realCatName == "All" -> allLiveChannels.filter { !hiddenSet.contains(it.category.trim().lowercase()) }
                else -> allLiveChannels.filter {
                    (sameCategory(it.category, originalCatName) || sameCategory(it.category, realCatName)) &&
                        !hiddenSet.contains(it.category.trim().lowercase())
                }
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

            renderChannelsList()

            categoriesAdapter?.setSelectedCat(catLabel)

            val suffix = if (!hideCategories && filterLetter != null) " (حرف $filterLetter)" else ""
            txtFilterHint.text = if (hideCategories) {
                "📺 قنوات ${realCatName.ifBlank { requestedCategoryName }}"
            } else {
                "📺 القنوات: $catLabel$suffix"
            }

            if (currentCategoryChannels.isEmpty()) {
                releasePreviewPlayer()
                txtChannelTitle.text = "لا توجد قناة"
                txtCategorySubtitle.text = "الفئة '$realCatName' فارغة حالياً"
                txtEpgInfo.text = "يمكنك تجربة حرف آخر أو الانتظار حتى تتوفر القنوات"
                txtDetailsBottom.text = "📭 لا توجد قنوات في هذه الفئة"
                return
            }

            if (player == null) {
                playRoyalLiveChannel(resolveInitialChannel(selectedChannel, currentCategoryChannels) ?: currentCategoryChannels.first())
            }

            if (hideCategories || activePanel == ActivePanel.CHANNELS) {
                requestChannelFocusByUrl(keepFocusUrl, fallbackToFirst = true)
            }
        } catch (e: Exception) {
            Log.e("TvLiveDashboard", "Load Channels Crash: ${e.message}")
        }
    }

    private fun buildUniversalAlphabetBar() {
        try {
            containerAlphabet.removeAllViews()
            val letters = listOf(
                "All", "A","B","C","D","E","F","G","H","I","J","K","L","M",
                "N","O","P","Q","R","S","T","U","V","W","X","Y","Z"
            )
            val btns = mutableListOf<TextView>()
            letters.forEachIndexed { index, letter ->
                val btn = TextView(this).apply {
                    text = if (letter == "All") "#" else letter
                    tag = letter
                    setTextColor(Color.parseColor("#A5B4FC"))
                    textSize = 10f
                    setTypeface(null, Typeface.BOLD)
                    gravity = Gravity.CENTER
                    isClickable = true
                    isFocusable = true
                    setPadding(dp(4), dp(4), dp(4), dp(4))
                    setBackgroundResource(R.drawable.tv_item_selector)
                    layoutParams = LinearLayout.LayoutParams(dp(34), dp(28)).apply {
                        bottomMargin = if (index == letters.lastIndex) 0 else dp(4)
                    }

                    setOnClickListener {
                        val chosenLetter = if (letter == "All") null else letter
                        when (activePanel) {
                            ActivePanel.CATEGORIES -> filterCategoriesByAlphabet(chosenLetter)
                            ActivePanel.CHANNELS -> loadCategoryChannels(selectedCategoryName, chosenLetter)
                        }
                        highlightAlphabetButton(this)
                    }
                    setOnFocusChangeListener { v, has ->
                        v.animate().scaleX(if (has) 1.08f else 1f).scaleY(if (has) 1.08f else 1f).setDuration(80).start()
                    }
                }
                btns.add(btn)
                containerAlphabet.addView(btn)
            }
            letterButtons = btns
            btns.firstOrNull()?.let { highlightAlphabetButton(it) }
            updateAlphabetHint()
        } catch (_: Exception) {}
    }

    private fun highlightAlphabetButton(selectedButton: TextView) {
        letterButtons.forEach { button ->
            button.isSelected = button == selectedButton
            button.setTextColor(if (button == selectedButton) Color.parseColor("#050A1A") else Color.parseColor("#A5B4FC"))
        }
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
                    showMenuOverlay(autoHide = true)
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
                panelAlphabet.visibility = View.GONE
                panelChannels.visibility = View.GONE
                darkOverlay?.visibility = View.GONE
                menuOverlay?.visibility = View.GONE
                txtChannelTitle.visibility = View.GONE
                txtCategorySubtitle.visibility = View.GONE
                txtEpgInfo.visibility = View.GONE
                txtDetailsBottom.visibility = View.GONE
                findViewById<View>(R.id.txtDetailsBottom)?.visibility = View.GONE
                (frameVideo.parent as? ViewGroup)?.findViewById<View>(R.id.txtDetailsBottom)?.visibility = View.GONE

                mainDashboardContainer.setPadding(0, 0, 0, 0)
                panelPlayer.setPadding(0, 0, 0, 0)
                panelPlayer.setBackgroundColor(Color.BLACK)
                panelPlayer.layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
                frameVideo.layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
                frameVideo.foreground = null
                frameVideo.clearFocus()
                viewPlayer.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM

                Toast.makeText(this, "وضع الشاشة الكامله • اضغط Back للعودة", Toast.LENGTH_SHORT).show()
            } else {
                mainDashboardContainer.setPadding(0, 0, 0, 0)
                panelPlayer.setPadding(dp(10), dp(10), dp(10), dp(10))
                panelPlayer.setBackgroundResource(R.drawable.bg_panel)
                frameVideo.foreground = null
                showMenuOverlay(autoHide = true)
                panelCategories.visibility = if (hideCategories) View.GONE else View.VISIBLE
                panelAlphabet.visibility = if (hideCategories) View.GONE else View.VISIBLE
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
        try { overlayHandler.removeCallbacks(hideOverlayRunnable) } catch (_: Exception) {}
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
                            v.animate().scaleX(if (has) 1.1f else 1f).scaleY(if (has) 1.1f else 1f).setDuration(200).start()
                            v.elevation = if (has) 10f else 0f
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
