package com.latchi.iptv.screens

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AnimationUtils
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.fragment.app.Fragment
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.latchi.iptv.MainActivity
import com.latchi.iptv.R
import com.latchi.iptv.adapter.UserProfilesAdapter
import com.latchi.iptv.model.Channel
import com.latchi.iptv.provider.ChannelsProvider
import com.latchi.iptv.utils.ChannelCache
import com.latchi.iptv.utils.DateText
import com.latchi.iptv.utils.GeminiVoiceController
import com.latchi.iptv.utils.LastWatchedPrefs
import com.latchi.iptv.utils.SourcePrefs
import com.latchi.iptv.utils.ServerSyncManager
import com.latchi.iptv.utils.ServerUpdateOverlayHelper
import com.latchi.iptv.utils.UpdateChecker
import com.latchi.iptv.utils.VoiceCommand
import com.latchi.iptv.utils.VoiceCommandParser
import com.latchi.iptv.utils.VoiceHandler
import com.latchi.iptv.utils.VoiceIndex

class HomeFragment : Fragment() {
    private lateinit var channelsProvider: ChannelsProvider
    private lateinit var cardLive: LinearLayout
    private lateinit var cardMovies: LinearLayout
    private lateinit var cardSeries: LinearLayout
    private lateinit var cardMatches: LinearLayout
    private lateinit var cardBeInSports: LinearLayout
    private lateinit var cardSettings: LinearLayout
    private lateinit var cardAccounts: LinearLayout
    private lateinit var toolbarSettings: LinearLayout
    private lateinit var toolbarUsers: LinearLayout
    private lateinit var lastWatchedButton: TextView
    private lateinit var clockTimeText: TextView
    private lateinit var clockDateText: TextView
    private lateinit var liveCount: TextView
    private lateinit var movieCount: TextView
    private lateinit var seriesCount: TextView
    private lateinit var updatedText: TextView
    private lateinit var loggedInText: TextView
    private lateinit var expiryText: TextView
    private lateinit var progressBar: View
    private lateinit var loadingOverlay: View
    private lateinit var usersRecyclerView: RecyclerView
    private lateinit var usersAdapter: UserProfilesAdapter
    private lateinit var drawerLayout: DrawerLayout

    private var cardAIVoice: View? = null
    private var voiceOverlay: FrameLayout? = null
    private var voiceHandler: VoiceHandler? = null

    private var saveAfterFetch = false
    private var loadingTimeoutHandler: Handler? = null
    private var adhkarRotatorHandler: Handler? = null
    private var adhkarRotatorRunnable: Runnable? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return try {
            val view = inflater.inflate(R.layout.fragment_home, container, false)
            channelsProvider = ViewModelProvider(this)[ChannelsProvider::class.java]
            cardLive = view.findViewById(R.id.cardLive)
            cardMovies = view.findViewById(R.id.cardMovies)
            cardSeries = view.findViewById(R.id.cardSeries)
            cardMatches = view.findViewById(R.id.cardMatches)
            cardBeInSports = view.findViewById(R.id.cardBeInSports)
            cardSettings = view.findViewById(R.id.cardSettings)
            cardAccounts = view.findViewById(R.id.cardAccounts)
            toolbarSettings = view.findViewById(R.id.toolbarSettings)
            toolbarUsers = view.findViewById(R.id.toolbarUsers)
            lastWatchedButton = view.findViewById(R.id.lastWatchedButton)
            clockTimeText = view.findViewById(R.id.clockTimeText)
            clockDateText = view.findViewById(R.id.clockDateText)
            liveCount = view.findViewById(R.id.liveCount)
            movieCount = view.findViewById(R.id.movieCount)
            seriesCount = view.findViewById(R.id.seriesCount)
            updatedText = view.findViewById(R.id.updatedText)
            loggedInText = view.findViewById(R.id.loggedInText)
            expiryText = view.findViewById(R.id.expiryText)
            progressBar = view.findViewById(R.id.loadingRow)
            loadingOverlay = view.findViewById(R.id.loadingOverlay)
            usersRecyclerView = view.findViewById(R.id.usersRecyclerView)
            drawerLayout = view.findViewById(R.id.drawerLayout)

            cardAIVoice = view.findViewById(R.id.cardAIVoice)
            if (com.latchi.iptv.utils.TvUtils.isTv(requireContext())) {
                cardAIVoice?.visibility = View.GONE
            }
            voiceOverlay = view.findViewById(R.id.voiceOverlay)

            setupDrawer(view)
            setupUsersRecycler()
            setupVoiceHandler()
            setupClicks(view)

            // 🕒 تحديث الساعة الفورية (Time + Date Widget)
            startClockUpdater()

            view
        } catch (e: Throwable) {
            val errorView = TextView(requireContext()).apply {
                text = "onCreateView Crash:\n${Log.getStackTraceString(e)}"
                setTextColor(android.graphics.Color.RED)
                setPadding(40, 40, 40, 40)
            }
            errorView
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        try {
            setupObservers()
            loadCachedOrFetch()
            animateUi(view)
            com.latchi.iptv.utils.TvFocusHelper.setupTree(view)
            startSilentServerSync()
            if (arguments?.getBoolean(MainActivity.EXTRA_OPEN_AI_VOICE, false) == true) {
                view.postDelayed({ onAIVoiceClicked() }, 650L)
                arguments?.putBoolean(MainActivity.EXTRA_OPEN_AI_VOICE, false)
            }
        } catch (e: Throwable) {
            com.latchi.iptv.utils.CustomOverlayHelper.show(requireActivity(), "خطأ", "HomeFragment Crash: ${e.message}", false)
            AlertDialog.Builder(requireContext())
                .setTitle("HomeFragment Crash")
                .setMessage(Log.getStackTraceString(e))
                .setPositiveButton("OK", null)
                .show()
        }
    }

    private var syncInProgress = false
    private var lastForegroundSyncMs = 0L
    private val SYNC_COOLDOWN_MS = 30_000L // 30 ثانية بين عمليات الفحص

    /**
     * يبدأ فحص تحديث السيرفر.
     * - عند onCreate: فحص عادي
     * - عند onResume (الرجوع للشاشة الرئيسية): فحص فقط إذا مر 30+ ثانية
     */
    private fun startSilentServerSync(force: Boolean = false) {
        if (syncInProgress) return
        val now = System.currentTimeMillis()
        if (!force && now - lastForegroundSyncMs < SYNC_COOLDOWN_MS) return
        syncInProgress = true
        lastForegroundSyncMs = now

        ServerSyncManager.checkForServerUpdate(requireContext(), force = force) { result ->
            syncInProgress = false
            if (!isAdded || !result.changed) return@checkForServerUpdate
            // 🔄 عرض واجهة التحديث (مرحلة التحميل → مرحلة النجاح)
            ServerUpdateOverlayHelper.show(requireActivity()) {
                // ✅ تحديث سلس للقنوات في المكان بدلاً من إعادة تشغيل Activity
                refreshChannelsSilently()            }
        }
    }

    /**
     * تحديث سلس للقنوات بعد تحديث السيرفر:
     * 1. مسح الكاش المحلي
     * 2. إعادة جلب من السيرفر الجديد
     * 3. تحديث ChannelsProvider في الوقت الفعلي
     */
    private fun refreshChannelsSilently() {
        try {
            val active = com.latchi.iptv.utils.SourcePrefs.getActiveProfile(requireContext()) ?: return
            // مسح الكاش القديم (الـ ServerSyncManager قد فعل ذلك بالفعل، لكن للأمان نكرر)
            com.latchi.iptv.utils.ChannelCache.clear(requireContext().applicationContext, active.id)
            saveAfterFetch = true
            // جلب من السيرفر الجديد بشكل صامت
            channelsProvider.fetchM3UFile(active.m3uUrl)
        } catch (e: Throwable) {
            Log.e("HomeFragment", "Silent refresh failed: ${e.message}")
        }
    }

    override fun onResume() {
        super.onResume()
        // 🔄 فحص فوري عند الرجوع للشاشة الرئيسية (مع cooldown 30 ثانية)
        try {
            view?.post { startSilentServerSync(force = false) }
        } catch (_: Exception) {}
    }

    private fun setupDrawer(view: View) {
        view.findViewById<TextView>(R.id.menuButton).setOnClickListener {
            drawerLayout.openDrawer(GravityCompat.END)
        }
        // 📞 الدعم الفني (انتقل من الشاشة الرئيسية إلى القائمة الجانبية)
        view.findViewById<TextView?>(R.id.drawerSupport)?.setOnClickListener {
            drawerLayout.closeDrawer(GravityCompat.END)
            try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/213798712450"))) } catch (_: Exception) {}
        }
        view.findViewById<TextView>(R.id.drawerMatches).setOnClickListener {
            drawerLayout.closeDrawer(GravityCompat.END)
            startActivity(Intent(requireContext(), MatchesActivity::class.java))
        }
        view.findViewById<TextView>(R.id.drawerPrayer).setOnClickListener {
            drawerLayout.closeDrawer(GravityCompat.END)
            startActivity(Intent(requireContext(), PrayerActivity::class.java))
        }
        view.findViewById<TextView>(R.id.drawerPricing).setOnClickListener {
            drawerLayout.closeDrawer(GravityCompat.END)
            startActivity(Intent(requireContext(), PricingActivity::class.java))
        }
        view.findViewById<TextView>(R.id.drawerThemeSettings).setOnClickListener {
            drawerLayout.closeDrawer(GravityCompat.END)
            startActivity(Intent(requireContext(), ThemeSettingsActivity::class.java))
        }
        view.findViewById<TextView>(R.id.drawerSettings).setOnClickListener {
            drawerLayout.closeDrawer(GravityCompat.END)
            startActivity(Intent(requireContext(), SettingsActivity::class.java))
        }
        view.findViewById<TextView>(R.id.drawerAbout).setOnClickListener {
            drawerLayout.closeDrawer(GravityCompat.END)
            AlertDialog.Builder(requireContext())
                .setTitle(getString(R.string.app_info_title))
                .setMessage(getString(R.string.app_info_desc))
                .setPositiveButton(getString(R.string.close), null)
                .show()
        }
    }

    private fun setupUsersRecycler() {
        usersAdapter = UserProfilesAdapter(emptyList(), onOpen = { profile ->
            SourcePrefs.setActiveProfile(requireContext(), profile.id)
            requireActivity().recreate()
        }, onDelete = { profile ->
            SourcePrefs.deleteProfile(requireContext(), profile.id)
            refreshUsers()
        })
        usersRecyclerView.layoutManager = LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
        com.latchi.iptv.utils.TvFocusHelper.setupRecycler(usersRecyclerView)
        usersRecyclerView.adapter = usersAdapter
        refreshUsers()
    }

    private fun refreshUsers() {
        val profiles = SourcePrefs.getProfiles(requireContext())
        usersAdapter.update(profiles, SourcePrefs.getActiveProfile(requireContext())?.id)
        usersRecyclerView.visibility = if (profiles.size > 1) View.VISIBLE else View.GONE
    }

    private fun animateUi(root: View) {
        try {
            val ctx = root.context
            root.findViewById<View?>(R.id.headerLogo)?.startAnimation(AnimationUtils.loadAnimation(ctx, R.anim.float_updown))
            listOf(R.id.cardLive, R.id.cardMovies, R.id.cardSeries, R.id.cardMatches, R.id.cardBeInSports, R.id.cardSettings, R.id.cardAccounts).forEachIndexed { i, id ->
                root.findViewById<View?>(id)?.let { c ->
                    val anim = AnimationUtils.loadAnimation(ctx, R.anim.card_slide_up).apply { startOffset = (i * 90).toLong() }
                    c.startAnimation(anim)
                }
            }
        } catch (_: Exception) {}
    }

    private fun setupClicks(view: View) {
        // 🎬 الصف الأول
        cardLive.setOnClickListener { ChannelListActivity.start(requireContext(), "live", getString(R.string.live_tv)) }
        cardMovies.setOnClickListener { ChannelListActivity.start(requireContext(), "movie", getString(R.string.movies)) }
        cardSeries.setOnClickListener { ChannelListActivity.start(requireContext(), "series", getString(R.string.series)) }
        cardMatches.setOnClickListener { startActivity(Intent(requireContext(), MatchesActivity::class.java)) }

        // ⚙️ الصف الثاني
        // 🏆 beIN Sports - فتح شاشة الفئات المفلترة
        cardBeInSports.setOnClickListener { startActivity(Intent(requireContext(), BeInSportsCategoriesActivity::class.java)) }
        // ⚙️ الإعدادات
        cardSettings.setOnClickListener { startActivity(Intent(requireContext(), SettingsActivity::class.java)) }
        // 🔑 الحسابات - شاشة VIP النظيفة الجديدة
        cardAccounts.setOnClickListener { startActivity(Intent(requireContext(), VipAccountsActivity::class.java)) }

        // 🔧 شريط أدوات علوي قديم (للتوافق)
        toolbarSettings.setOnClickListener { startActivity(Intent(requireContext(), SettingsActivity::class.java)) }
        toolbarUsers.setOnClickListener {
            startActivity(Intent(requireContext(), UserListActivity::class.java).putExtra("show_settings", true))
            requireActivity().finish()
        }
        cardAIVoice?.setOnClickListener { onAIVoiceClicked() }
        lastWatchedButton.setOnClickListener {
            SourcePrefs.getActiveProfile(requireContext())?.let { active ->
                LastWatchedPrefs.load(requireContext(), active.id)?.let { ch ->
                    PlayerActivity.start(requireContext(), ch)
                } ?: com.latchi.iptv.utils.CustomOverlayHelper.show(requireActivity(), "تنبيه", getString(R.string.no_last_watched), false)
            }
        }
    }

    private var clockHandler: Handler? = null
    private var clockRunnable: Runnable? = null

    private fun startClockUpdater() {
        stopClockUpdater()
        clockHandler = Handler(Looper.getMainLooper())
        clockRunnable = object : Runnable {
            override fun run() {
                try {
                    val now = java.util.Date()
                    val locale = java.util.Locale("ar")
                    val timeFmt = java.text.SimpleDateFormat("hh:mm a", locale)
                    val dateFmt = java.text.SimpleDateFormat("EEEE d MMMM yyyy", locale)
                    clockTimeText?.text = timeFmt.format(now)
                    clockDateText?.text = dateFmt.format(now)
                } catch (_: Exception) { }
                clockHandler?.postDelayed(this, 1000L)
            }
        }
        clockHandler?.post(clockRunnable!!)
    }

    private fun stopClockUpdater() {
        clockHandler?.removeCallbacksAndMessages(null)
        clockHandler = null
        clockRunnable = null
    }

    private fun setupVoiceHandler() {
        val overlay = voiceOverlay ?: return
        voiceHandler = VoiceHandler(requireContext(), overlay, onCommand = { command -> handleVoiceCommand(command) }, onGeminiAction = { action -> handleGeminiAction(action) })
    }

    private fun onAIVoiceClicked() {
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 2026)
            return
        }
        voiceHandler?.startListening()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (requestCode == 2026 && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            voiceHandler?.startListening()
        } else {
            com.latchi.iptv.utils.CustomOverlayHelper.show(requireActivity(), "صلاحية", "صلاحية الميكروفون مطلوبة للبحث الصوتي", false)
        }
    }

    private fun handleVoiceCommand(command: VoiceCommand) {
        when (command) {
            is VoiceCommand.Play -> playOrSearch(command.channelName, null)
            is VoiceCommand.Navigate -> navigateTo(command.screen)
            is VoiceCommand.Search -> openSearchResults(command.query, command.contentType)
            is VoiceCommand.Category -> openCategorySearch(command.category)
            is VoiceCommand.PlayerControl -> sendPlayerControl(command.target, command.extra)
            VoiceCommand.Favorites -> openSearchResults("Favorites", "live")
            VoiceCommand.Home -> com.latchi.iptv.utils.CustomOverlayHelper.show(requireActivity(), "الرئيسية", getString(R.string.home), true)
            VoiceCommand.Unknown -> com.latchi.iptv.utils.CustomOverlayHelper.show(requireActivity(), "عذراً", getString(R.string.voice_not_understood), false)
        }
    }

    private fun handleGeminiAction(action: GeminiVoiceController.VoiceAction) {
        if (!action.isConfident()) {
            com.latchi.iptv.utils.CustomOverlayHelper.show(requireActivity(), "تنبيه", getString(R.string.voice_try_again), false)
            return
        }
        when (action.actionType.lowercase()) {
            "play" -> playOrSearch(action.target, action.extra.ifBlank { null })
            "navigate" -> navigateByScreenName(action.screen)
            "search" -> openSearchResults(action.target, action.extra.ifBlank { null })
            "category" -> openCategorySearch(action.target)
            "player_control" -> sendPlayerControl(action.target, action.extra)
            "favorites" -> openSearchResults("Favorites", "live")
            "home" -> com.latchi.iptv.utils.CustomOverlayHelper.show(requireActivity(), "الرئيسية", getString(R.string.home), true)
            else -> com.latchi.iptv.utils.CustomOverlayHelper.show(requireActivity(), "معلومة", "ℹ️ ${action.target}", true)
        }
    }

    private fun playOrSearch(query: String, preferredType: String?) {
        val clean = query.trim()
        if (clean.isBlank()) {
            Toast.makeText(requireContext(), getString(R.string.voice_not_understood), Toast.LENGTH_SHORT).show()
            return
        }
        val item = VoiceIndex.findChannel(clean, preferredType)
        if (item != null) {
            openChannelItem(item)
        } else {
            val results = VoiceIndex.search(clean, preferredType, 8)
            if (results.size == 1) openChannelItem(results.first()) else openSearchResults(clean, preferredType)
        }
    }

    private fun openChannelItem(channel: Channel) {
        when (channel.contentType) {
            "movie" -> MovieDetailActivity.start(requireContext(), channel)
            "series" -> {
                val active = SourcePrefs.getActiveProfile(requireContext())
                if (active != null) SeriesDetailActivity.start(requireContext(), channel, active.m3uUrl)
                else Toast.makeText(requireContext(), getString(R.string.series_not_available), Toast.LENGTH_SHORT).show()
            }
            else -> PlayerActivity.start(requireContext(), channel)
        }
    }

    private fun openSearchResults(query: String, type: String?) {
        val targetType = when (type) {
            "movie", "movies" -> "movie"
            "series" -> "series"
            else -> "live"
        }
        val title = when (targetType) {
            "movie" -> getString(R.string.movies)
            "series" -> getString(R.string.series)
            else -> getString(R.string.live_tv)
        }
        ChannelListActivity.startSearch(requireContext(), targetType, "${getString(R.string.search)}: $query", query)
    }

    private fun openCategorySearch(category: String) {
        if (category.isBlank()) {
            ChannelListActivity.start(requireContext(), "live", getString(R.string.live_tv))
        } else {
            ChannelListActivity.startSearch(requireContext(), "live", "${getString(R.string.categories)}: $category", category)
        }
    }

    private fun sendPlayerControl(target: String, extra: String) {
        requireContext().sendBroadcast(Intent("com.latchi.iptv.PLAYER_CONTROL").apply {
            setPackage(requireContext().packageName)
            putExtra("target", target)
            putExtra("extra", extra)
        })
        Toast.makeText(requireContext(), "🎮 $target", Toast.LENGTH_SHORT).show()
    }

    private fun navigateByScreenName(screen: String?) {
        when (screen?.lowercase()) {
            "settings" -> navigateTo(VoiceCommand.Screen.SETTINGS)
            "matches" -> navigateTo(VoiceCommand.Screen.MATCHES)
            "movies", "movie" -> navigateTo(VoiceCommand.Screen.MOVIES)
            "series" -> navigateTo(VoiceCommand.Screen.SERIES)
            "live", "tv" -> navigateTo(VoiceCommand.Screen.LIVE)
            "users" -> navigateTo(VoiceCommand.Screen.USERS)
            "pricing" -> navigateTo(VoiceCommand.Screen.PRICING)
            "prayer" -> navigateTo(VoiceCommand.Screen.PRAYER)
            "about" -> navigateTo(VoiceCommand.Screen.ABOUT)
            else -> Toast.makeText(requireContext(), getString(R.string.voice_not_understood), Toast.LENGTH_SHORT).show()
        }
    }

    private fun navigateTo(screen: VoiceCommand.Screen) {
        when (screen) {
            VoiceCommand.Screen.SETTINGS -> startActivity(Intent(requireContext(), SettingsActivity::class.java))
            VoiceCommand.Screen.MATCHES -> startActivity(Intent(requireContext(), MatchesActivity::class.java))
            VoiceCommand.Screen.MOVIES -> ChannelListActivity.start(requireContext(), "movie", getString(R.string.movies))
            VoiceCommand.Screen.SERIES -> ChannelListActivity.start(requireContext(), "series", getString(R.string.series))
            VoiceCommand.Screen.LIVE -> ChannelListActivity.start(requireContext(), "live", getString(R.string.live_tv))
            VoiceCommand.Screen.USERS -> { startActivity(Intent(requireContext(), UserListActivity::class.java).putExtra("show_settings", true)); requireActivity().finish() }
            VoiceCommand.Screen.PRICING -> startActivity(Intent(requireContext(), PricingActivity::class.java))
            VoiceCommand.Screen.PRAYER -> startActivity(Intent(requireContext(), PrayerActivity::class.java))
            VoiceCommand.Screen.ABOUT -> AlertDialog.Builder(requireContext()).setTitle(getString(R.string.app_info_title)).setMessage(getString(R.string.app_info_desc)).show()
        }
    }

    private fun setupObservers() {
        channelsProvider.channels.observe(viewLifecycleOwner, Observer { data ->
            try {
                loadingTimeoutHandler?.removeCallbacksAndMessages(null)
                progressBar.visibility = View.GONE
                loadingOverlay.visibility = View.GONE
                stopAdhkarRotator()
                updateCounts(data)
                VoiceIndex.update(data)
                
                val active = SourcePrefs.getActiveProfile(requireContext())
                if (saveAfterFetch && active != null && data.isNotEmpty()) {
                    val profileId = active.id
                    val appContext = requireContext().applicationContext
                    saveAfterFetch = false
                    Thread {
                        ChannelCache.save(appContext, profileId, data)
                        Handler(Looper.getMainLooper()).post {
                            try {
                                updateCacheTime(profileId)
                                com.latchi.iptv.utils.CustomOverlayHelper.show(requireActivity(), "تحديث", getString(R.string.playlist_updated), true)
                            } catch (_: Exception) {}
                        }
                    }.start()
                }
            } catch (e: Throwable) { Log.e("HomeFragment", "Observer Crash: ${e.message}") }
        })
        channelsProvider.error.observe(viewLifecycleOwner, Observer { error ->
            try {
                loadingTimeoutHandler?.removeCallbacksAndMessages(null)
                if (!error.isNullOrBlank()) {
                    progressBar.visibility = View.GONE
                    loadingOverlay.visibility = View.GONE
                    stopAdhkarRotator()
                    com.latchi.iptv.utils.CustomOverlayHelper.show(requireActivity(), "خطأ", error, false)
                }
            } catch (e: Throwable) { Log.e("HomeFragment", "Error Observer Crash: ${e.message}") }
        })
    }

    private fun loadCachedOrFetch() {
        try {
            val active = SourcePrefs.getActiveProfile(requireContext())
            if (active == null) { startActivity(Intent(requireContext(), UserListActivity::class.java).putExtra("show_settings", true)); requireActivity().finish(); return }
            try {
                loggedInText.text = "${getString(R.string.logged_in)}: ${active.name}"
                expiryText.text = "${getString(R.string.expiry)}: ${DateText.shortDate(active.expiresAt)}"
            } catch (_: Throwable) {}
            checkExpiryWarning(active.expiresAt)

            val appContext = requireContext().applicationContext
            Thread {
                val cached = ChannelCache.load(appContext, active.id)
                // القنوات المدمجة داخل assets تستعمل فقط كـ fallback إذا لم يكن للحساب رابط أصلاً.
                // سابقاً كانت assets تتغلب دائماً على حساب المستخدم، وهذا يسبب قوائم/تصنيفات خاطئة.
                val embedded = if (cached.isEmpty() && active.m3uUrl.isBlank()) {
                    com.latchi.iptv.utils.EmbeddedChannelsLoader.load(appContext)
                } else emptyList()
                Handler(Looper.getMainLooper()).post {
                    try {
                        when {
                            cached.isNotEmpty() -> {
                                channelsProvider.setLocalChannels(cached)
                                updateCacheTime(active.id)
                                VoiceIndex.update(cached)
                                progressBar.visibility = View.GONE
                                loadingOverlay.visibility = View.GONE
                                stopAdhkarRotator()
                            }
                            embedded.isNotEmpty() -> {
                                channelsProvider.setLocalChannels(embedded)
                                VoiceIndex.update(embedded)
                                progressBar.visibility = View.GONE
                                loadingOverlay.visibility = View.GONE
                                stopAdhkarRotator()
                            }
                            else -> {
                                if (channelsProvider.isXtreamSource(active.m3uUrl)) {
                                    // نظام التحميل السريع: لا نحمل كل القنوات في الشاشة الرئيسية.
                                    // كل قسم يحمل فئته الأولى فقط عند الدخول، مثل تطبيقات JSON السريعة.
                                    channelsProvider.setLocalChannels(emptyList())
                                    progressBar.visibility = View.GONE
                                    loadingOverlay.visibility = View.GONE
                                    stopAdhkarRotator()
                                } else {
                                    saveAfterFetch = true
                                    progressBar.visibility = View.VISIBLE
                                    loadingOverlay.visibility = View.VISIBLE
                                    startAdhkarRotator()
                                    startLoadingTimeout()
                                    channelsProvider.fetchM3UFile(active.m3uUrl)
                                }
                            }
                        }
                    } catch (_: Exception) {}
                }
            }.start()
        } catch (e: Throwable) {
            Log.e("HomeFragment", "Load Data Crash: ${e.message}")
            AlertDialog.Builder(requireContext()).setTitle("Load Data Crash").setMessage(Log.getStackTraceString(e)).show()
        }
    }

    private fun checkExpiryWarning(expiresAt: String) {
        try {
            val sdf = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
            val expiryDate = sdf.parse(expiresAt) ?: return
            val diffMillis = expiryDate.time - System.currentTimeMillis()
            val diffDays = (diffMillis / (1000 * 60 * 60 * 24)).toInt()
            if (diffDays in 0..3) {
                val msg = if (diffDays == 0) "⏰ اشتراكك ينتهي اليوم! جدّد الآن" else "⏰ اشتراكك ينتهي بعد $diffDays يوم"
                view?.post {
                    Toast.makeText(requireContext(), getString(R.string.expiry_warning, diffDays), Toast.LENGTH_LONG).show()
                    view?.findViewById<TextView?>(R.id.expiryBanner)?.let { b ->
                        b.text = msg; b.visibility = View.VISIBLE; b.setOnClickListener { try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/213798712450"))) } catch (_: Exception) {} }
                    }
                }
            }
        } catch (_: Exception) {}
    }

    private fun forceUpdate() {
        val active = SourcePrefs.getActiveProfile(requireContext()) ?: return
        saveAfterFetch = true
        progressBar.visibility = View.VISIBLE
        loadingOverlay.visibility = View.VISIBLE
        startAdhkarRotator()
        startLoadingTimeout()
        channelsProvider.fetchM3UFile(active.m3uUrl)
    }

    private fun startLoadingTimeout() {
        loadingTimeoutHandler?.removeCallbacksAndMessages(null)
        loadingTimeoutHandler = Handler(Looper.getMainLooper())
        loadingTimeoutHandler?.postDelayed({
            if (progressBar.visibility == View.VISIBLE) {
                progressBar.visibility = View.GONE
                loadingOverlay.visibility = View.GONE
                stopAdhkarRotator()
                com.latchi.iptv.utils.CustomOverlayHelper.show(requireActivity(), "تنبيه", "انتهت مهلة التحميل. اضغط 'تحديث'", false)
            }
        }, 120000)
    }

    private fun startAdhkarRotator() {
        stopAdhkarRotator()
        val allTexts = resources.getStringArray(R.array.adhkar_items).toList() + resources.getStringArray(R.array.ayat_items).toList()
        val allRefs = resources.getStringArray(R.array.adhkar_references).toList() + resources.getStringArray(R.array.ayat_references).toList()
        loadingOverlay.findViewById<TextView?>(R.id.loadingTitle)?.text = getString(R.string.loading_adhkar_title)
        val tv = loadingOverlay.findViewById<TextView?>(R.id.loadingText)
        val ref = loadingOverlay.findViewById<TextView?>(R.id.loadingRef)
        if (allTexts.isEmpty()) return

        adhkarRotatorHandler = Handler(Looper.getMainLooper())
        adhkarRotatorRunnable = object : Runnable {
            override fun run() {
                val idx = (0 until allTexts.size).random()
                tv?.text = allTexts[idx]; ref?.text = allRefs.getOrNull(idx) ?: ""
                adhkarRotatorHandler?.postDelayed(this, 8000)
            }
        }
        adhkarRotatorHandler?.post(adhkarRotatorRunnable!!)
    }

    private fun stopAdhkarRotator() {
        adhkarRotatorHandler?.removeCallbacksAndMessages(null)
        adhkarRotatorHandler = null; adhkarRotatorRunnable = null
    }

    private fun updateCounts(data: List<Channel>) {
        fun setCount(view: TextView, count: Int, suffix: String) {
            if (count > 0) {
                view.visibility = View.VISIBLE
                view.text = "$count $suffix"
            } else {
                view.visibility = View.GONE
            }
        }
        setCount(liveCount, data.count { it.contentType == "live" }, getString(R.string.channels_suffix))
        setCount(movieCount, data.count { it.contentType == "movie" }, getString(R.string.movies_suffix))
        setCount(seriesCount, data.count { it.contentType == "series" }, getString(R.string.series_suffix))
    }

    private fun updateCacheTime(profileId: String) {
        val time = ChannelCache.updatedAt(requireContext().applicationContext, profileId)
        updatedText.text = "${getString(R.string.last_update)}: " + if (time == 0L) "--" else "✓"
    }

    override fun onDestroyView() {
        super.onDestroyView()
        voiceHandler?.destroy()
        voiceHandler = null
        stopAdhkarRotator()
        stopClockUpdater()
    }
}
