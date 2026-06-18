package com.latchi.iptv.screens

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.latchi.iptv.MainActivity
import com.latchi.iptv.R
import com.latchi.iptv.adapter.CategoryGridAdapter
import com.latchi.iptv.adapter.ChannelsAdapter
import com.latchi.iptv.model.Channel
import com.latchi.iptv.provider.ChannelCategory
import com.latchi.iptv.provider.ChannelsProvider
import com.latchi.iptv.utils.ChannelCache
import com.latchi.iptv.utils.FavoritesPrefs
import com.latchi.iptv.utils.FloatingBackHelper
import com.latchi.iptv.utils.SourcePrefs
import com.latchi.iptv.utils.ServerSyncManager
import com.latchi.iptv.utils.ServerUpdateOverlayHelper
import com.latchi.iptv.utils.ThemeManager
import com.latchi.iptv.utils.TvUtils

class ChannelListActivity : AppCompatActivity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(com.latchi.iptv.utils.LocaleHelper.wrap(newBase))
    }

    private lateinit var channelsProvider: ChannelsProvider
    private lateinit var adapter: ChannelsAdapter
    private lateinit var gridAdapter: CategoryGridAdapter

    private lateinit var stickyCatTitle: TextView
    private lateinit var stickyCatSubtitle: TextView
    private lateinit var stickyCategoryBar: LinearLayout

    private lateinit var menuButton: TextView
    private lateinit var searchButton: TextView
    private lateinit var refreshButton: TextView
    private lateinit var searchEditText: EditText
    private lateinit var categorySearchEditText: EditText
    private lateinit var progressBar: ProgressBar
    private lateinit var recyclerView: RecyclerView
    private lateinit var catGridRecyclerView: RecyclerView
    private lateinit var categoryOverlayGrid: View
    private lateinit var btnCloseGrid: TextView
    private lateinit var drawerLayout: DrawerLayout

    private var contentType = "live"
    private var screenTitle = "LIVE TV"
    private var currentQuery = ""
    private var categoryQuery = ""
    private var currentCategory = "All"
    private var debounceHandler: Handler? = null
    private var categoryDebounceHandler: Handler? = null
    private var lastChannels: List<Channel> = emptyList()
    private var currentVisibleChannels: List<Channel> = emptyList()
    private var saveAfterFetch = false
    private var lazyXtreamMode = false
    private var activeSourceUrl = ""
    private var xtreamCategories: List<ChannelCategory> = emptyList()
    private var firstCategorySelected = false
    private var focusMode = "channels"

    companion object {
        private const val EXTRA_TYPE = "extra_type"
        private const val EXTRA_TITLE = "extra_title"
        fun start(context: Context, type: String, title: String) {
            val intent = Intent(context, ChannelListActivity::class.java).apply {
                putExtra(EXTRA_TYPE, type)
                putExtra(EXTRA_TITLE, title)
            }
            context.startActivity(intent)
        }

        fun startSearch(context: Context, type: String, title: String, query: String) {
            val intent = Intent(context, ChannelListActivity::class.java).apply {
                putExtra(EXTRA_TYPE, type)
                putExtra(EXTRA_TITLE, title)
                putExtra("search_query", query)
            }
            context.startActivity(intent)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        TvUtils.applyOrientation(this)
        ThemeManager.apply(this)
        setContentView(R.layout.activity_channel_list)

        // 🎯 Permanent Universal Permanent Dual Floating Action Buttons (FAB) 🎯
        FloatingBackHelper.setup(this)

        contentType = intent.getStringExtra(EXTRA_TYPE) ?: "live"
        screenTitle = intent.getStringExtra(EXTRA_TITLE) ?: "LIVE TV"
        currentCategory = intent.getStringExtra("category") ?: "All"

        channelsProvider = ViewModelProvider(this)[ChannelsProvider::class.java]
        
        stickyCatTitle = findViewById(R.id.stickyCatTitle)
        stickyCatSubtitle = findViewById(R.id.stickyCatSubtitle)
        stickyCategoryBar = findViewById(R.id.stickyCategoryBar)

        menuButton = findViewById(R.id.menuButton)
        searchButton = findViewById(R.id.searchButton)
        refreshButton = findViewById(R.id.refreshButton)
        searchEditText = findViewById(R.id.searchEditText)
        categorySearchEditText = findViewById(R.id.categorySearchEditText)
        progressBar = findViewById(R.id.progressBar)
        recyclerView = findViewById(R.id.recyclerView)
        catGridRecyclerView = findViewById(R.id.catGridRecyclerView)
        categoryOverlayGrid = findViewById(R.id.categoryOverlayGrid)
        btnCloseGrid = findViewById(R.id.btnCloseGrid)
        drawerLayout = findViewById(R.id.drawerLayout)

        stickyCatTitle.text = if (currentCategory == "Favorites") "⭐ المفضلة" else (if (screenTitle.contains("LIVE")) "كل القنوات" else screenTitle)

        val isGridMode = (contentType == "movie" || contentType == "series")

        adapter = ChannelsAdapter(
            emptyList(),
            isGrid = isGridMode,
            isFavorite = { channel -> activeId()?.let { FavoritesPrefs.isFavorite(this, it, channel.streamUrl) } ?: false },
            onFavoriteClicked = { channel -> 
                activeId()?.let { profileId ->
                    FavoritesPrefs.toggle(this, profileId, channel.streamUrl)
                    val isFav = com.latchi.iptv.utils.FavoriteManager.toggleFavoriteChannel(this, profileId, channel)
                    val msg = if (isFav) "⭐ تم حفظ القناة '${channel.name}' في المفضلة ✓" else "إلغاء حفظ القناة '${channel.name}' من المفضلة"
                    Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                    applyFilter() 
                } 
            },
            onChannelClicked = { channel ->
                when {
                    channel.contentType == "series" || channel.streamUrl.startsWith("series://") -> {
                        val active = SourcePrefs.getActiveProfile(this)
                        if (active != null) {
                            SeriesDetailActivity.start(this, channel, active.m3uUrl)
                        } else {
                            Toast.makeText(this, getString(R.string.series_not_available), Toast.LENGTH_SHORT).show()
                        }
                    }
                    channel.contentType == "movie" -> {
                        MovieDetailActivity.start(this, channel)
                    }
                    else -> {
                        if (TvUtils.isTv(this) && contentType == "live") {
                            TvLivePreviewActivity.startWithChannels(this, channel, previewChannelsFor(channel), channel.category.ifBlank { currentCategory })
                        } else {
                            PlayerActivity.start(this, channel)
                        }
                    }
                }
            }
        )
        
        // ⚡ Requirement 4: Massive Grid of 3 Columns for Movies & Series ⚡
        recyclerView.layoutManager = if (isGridMode) GridLayoutManager(this, if (TvUtils.isTv(this)) 6 else 3) else LinearLayoutManager(this)
        com.latchi.iptv.utils.TvFocusHelper.setupRecycler(recyclerView)
        recyclerView.adapter = adapter

        gridAdapter = CategoryGridAdapter(
            emptyList(),
            onSelect = { item ->
                currentCategory = item.name
                stickyCatTitle.text = if (item.name == "All") "كل القنوات" else if (item.name == "Favorites") "⭐ المفضلة" else item.name
                stickyCatSubtitle.text = if (item.count < 0) "(تحميل سريع حسب الفئة)" else "(${item.count} قناة - اضغط للتبديل)"
                closeCategoryGrid()
                if (lazyXtreamMode) {
                    loadLazyXtreamCategory(item.name)
                } else {
                    applyFilter()
                }
            },
            onLongSelect = { item ->
                if (item.name == "All" || item.name == "Favorites") return@CategoryGridAdapter
                val prefs = getSharedPreferences("pinned_categories", Context.MODE_PRIVATE)
                val profileId = activeId() ?: ""
                val currentPinned = prefs.getString("pinned_${profileId}", "") ?: ""
                
                if (currentPinned == item.name) {
                    prefs.edit().remove("pinned_${profileId}").apply()
                    Toast.makeText(this, "📌 تم إلغاء تثبيت الفئة: ${item.name}", Toast.LENGTH_SHORT).show()
                } else {
                    prefs.edit().putString("pinned_${profileId}", item.name).apply()
                    Toast.makeText(this, "📌 تم تثبيت فئة ${item.name} في الأعلى!", Toast.LENGTH_SHORT).show()
                }
                buildCategories(lastChannels)
            }
        )
        
        catGridRecyclerView.layoutManager = LinearLayoutManager(this)
        com.latchi.iptv.utils.TvFocusHelper.setupRecycler(catGridRecyclerView)
        catGridRecyclerView.adapter = gridAdapter

        setupDrawer()

        menuButton.setOnClickListener {
            // 👑 الإصلاح الملكي الجذري: فتح شاشة المفضلة والخيارات المستقلة بدلاً من شاشة تسجيل الدخول
            com.latchi.iptv.screens.RoyalFavoritesDashboardActivity.start(this)
        }
        refreshButton.visibility = View.GONE
        searchButton.setOnClickListener {
            searchEditText.visibility = if (searchEditText.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            if (searchEditText.visibility == View.VISIBLE) searchEditText.requestFocus()
        }

        stickyCategoryBar.setOnClickListener { openCategoryGrid() }
        btnCloseGrid.setOnClickListener { closeCategoryGrid() }

        setupSearch()
        setupCategorySearch()
        setupObservers()
        loadCachedOrFetch()
        startSilentServerSync()

        intent.getStringExtra("search_query")?.let { q ->
            if (q.isNotBlank()) {
                currentQuery = q
                searchEditText.setText(q)
                searchEditText.visibility = View.VISIBLE
            }
        }
    }

    override fun onResume() {
        super.onResume()
        try {
            val active = com.latchi.iptv.utils.SourcePrefs.getActiveProfile(this)
            if (active != null && com.latchi.iptv.utils.SourcePrefs.isPendingServerRefresh(this, active.id)) {
                com.latchi.iptv.utils.SourcePrefs.setPendingServerRefresh(this, active.id, false)
                startSilentServerSync()
            } else {
                startSilentServerSync()
            }
        } catch (_: Exception) {}
    }

    private fun startSilentServerSync() {
        ServerSyncManager.checkForServerUpdate(this, force = false) { result ->
            if (!result.changed || isFinishing) return@checkForServerUpdate
            val intent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }
            startActivity(intent)
            finish()
        }
    }

    private fun setupDrawer() {
        findViewById<TextView>(R.id.drawerFavorites)?.setOnClickListener {
            drawerLayout.closeDrawer(GravityCompat.END)
            currentCategory = "Favorites"
            stickyCatTitle.text = "⭐ المفضلة"
            applyFilter()
        }
        findViewById<TextView>(R.id.drawerTodaysMatches)?.setOnClickListener {
            drawerLayout.closeDrawer(GravityCompat.END)
            startActivity(Intent(this, MatchesActivity::class.java))
        }
        findViewById<TextView>(R.id.drawerSettings)?.setOnClickListener {
            drawerLayout.closeDrawer(GravityCompat.END)
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        findViewById<TextView>(R.id.drawerLogout)?.setOnClickListener {
            drawerLayout.closeDrawer(GravityCompat.END)
            val intent = Intent(this, UserListActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            startActivity(intent)
            finish()
        }
    }

    private fun activeId(): String? = SourcePrefs.getActiveProfile(this)?.id

    private fun openCategoryGrid() {
        focusMode = "categories"
        categoryOverlayGrid.visibility = View.VISIBLE
        recyclerView.isFocusable = false
        recyclerView.descendantFocusability = android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS
        catGridRecyclerView.isFocusable = true
        buildCategories(lastChannels)
        catGridRecyclerView.postDelayed({
            catGridRecyclerView.requestFocus()
            catGridRecyclerView.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus()
        }, 120L)
    }

    private fun closeCategoryGrid() {
        focusMode = "channels"
        categoryOverlayGrid.visibility = View.GONE
        recyclerView.isFocusable = true
        recyclerView.descendantFocusability = android.view.ViewGroup.FOCUS_AFTER_DESCENDANTS
        catGridRecyclerView.clearFocus()
        recyclerView.postDelayed({
            recyclerView.requestFocus()
            recyclerView.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus()
        }, 80L)
    }

    private fun setupSearch() {
        searchEditText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                debounceHandler?.removeCallbacksAndMessages(null)
                debounceHandler = Handler(Looper.getMainLooper())
                debounceHandler?.postDelayed({ currentQuery = s.toString(); applyFilter() }, 300)
            }
            override fun afterTextChanged(s: Editable?) {}
        })
    }

    private fun setupCategorySearch() {
        categorySearchEditText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                categoryDebounceHandler?.removeCallbacksAndMessages(null)
                categoryDebounceHandler = Handler(Looper.getMainLooper())
                categoryDebounceHandler?.postDelayed({
                    categoryQuery = s.toString()
                    buildCategories(lastChannels)
                }, 250)
            }
            override fun afterTextChanged(s: Editable?) {}
        })
    }

    private fun setupObservers() {
        channelsProvider.categories.observe(this, Observer { data ->
            if (!lazyXtreamMode) return@Observer
            xtreamCategories = data.filter { it.contentType == contentType }
            if (!firstCategorySelected && xtreamCategories.isNotEmpty()) {
                val first = xtreamCategories.first()
                firstCategorySelected = true
                currentCategory = first.name
                stickyCatTitle.text = first.name
                stickyCatSubtitle.text = "(تحميل سريع حسب الفئة)"
            }
            buildCategories(lastChannels)
        })

        channelsProvider.channels.observe(this, Observer { data ->
            try {
                progressBar.visibility = View.GONE
                lastChannels = data
                buildCategories(data)
                applyFilter()

                if (!lazyXtreamMode && saveAfterFetch && data.isNotEmpty()) {
                    val profileId = activeId()
                    if (profileId != null) {
                        val appContext = applicationContext
                        val snapshot = data.toList()
                        saveAfterFetch = false
                        Thread { ChannelCache.save(appContext, profileId, snapshot) }.start()
                    }
                }
            } catch (e: Exception) { Log.e("ChannelList", "Observer Error: ${e.message}") }
        })
        channelsProvider.filteredChannels.observe(this, Observer { data ->
            currentVisibleChannels = data
            adapter.updateChannels(data)
            recyclerView.postDelayed({
                if (com.latchi.iptv.utils.TvUtils.isTv(this) && recyclerView.findViewHolderForAdapterPosition(0) != null && currentFocus == null) {
                    recyclerView.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus()
                }
            }, 220L)
        })
        channelsProvider.error.observe(this, Observer { error ->
            if (!error.isNullOrBlank()) {
                progressBar.visibility = View.GONE
                Toast.makeText(this, error, Toast.LENGTH_LONG).show()
            }
        })
    }

    private fun loadCachedOrFetch() {
        try {
            val active = SourcePrefs.getActiveProfile(this)
            if (active == null) {
                startActivity(Intent(this, UserListActivity::class.java).putExtra("show_settings", true))
                finish()
                return
            }
            activeSourceUrl = active.m3uUrl
            lazyXtreamMode = channelsProvider.isXtreamSource(activeSourceUrl)
            if (lazyXtreamMode) {
                progressBar.visibility = View.VISIBLE
                firstCategorySelected = false
                channelsProvider.fetchXtreamCategoriesAndFirst(activeSourceUrl, contentType)
                return
            }

            Thread {
                val cached = ChannelCache.load(applicationContext, active.id)
                val embedded = if (cached.isEmpty() && active.m3uUrl.isBlank()) {
                    com.latchi.iptv.utils.EmbeddedChannelsLoader.load(applicationContext)
                } else emptyList()
                Handler(Looper.getMainLooper()).post {
                    when {
                        cached.isNotEmpty() -> channelsProvider.setLocalChannels(cached)
                        embedded.isNotEmpty() -> channelsProvider.setLocalChannels(embedded)
                        else -> {
                            saveAfterFetch = true
                            progressBar.visibility = View.VISIBLE
                            channelsProvider.fetchM3UFile(active.m3uUrl)
                        }
                    }
                }
            }.start()
        } catch (e: Exception) { Log.e("ChannelList", "Load Error: ${e.message}") }
    }


    private fun previewChannelsFor(channel: Channel): List<Channel> {
        val source = currentVisibleChannels.ifEmpty { lastChannels }.filter { it.contentType == "live" }
        val sameCategory = source.filter { it.category.equals(channel.category, ignoreCase = true) }
        val base = (if (sameCategory.isNotEmpty()) sameCategory else source).ifEmpty { listOf(channel) }
        val ordered = listOf(channel) + base.filter { it.streamUrl != channel.streamUrl }
        return ordered.take(300)
    }

    private fun sortCategoriesByPriority(cats: List<String>): List<String> {
        val prefs = getSharedPreferences("pinned_categories", Context.MODE_PRIVATE)
        val profileId = activeId() ?: ""
        val pinned = prefs.getString("pinned_${profileId}", "") ?: ""

        return cats.sortedWith(Comparator { a, b ->
            fun score(cat: String): Int {
                if (cat == pinned && cat != "All" && cat != "Favorites") return -1
                val l = cat.lowercase()
                return when {
                    l == "all" -> 0
                    l == "favorites" -> 1
                    l.contains("world cup") || l.contains("كأس العالم") -> 2
                    l.contains("bein sport max") || l.contains("bein sports max") -> 4
                    l.contains("bein sport") || l.contains("bein sports") || l.contains("bein") -> 3
                    l.contains("sport") || l.contains("ssc") || l.contains("alkass") || l.contains("ad sport") -> 5
                    l.contains("movie") || l.contains("film") || l.contains("أفلام") || l.contains("افلام") -> 6
                    l.contains("series") || l.contains("مسلسل") -> 7
                    l.contains("kid") || l.contains("أطفال") || l.contains("اطفال") || l.contains("cartoon") -> 8
                    l.contains("news") || l.contains("أخبار") || l.contains("اخبار") -> 9
                    l.contains("music") || l.contains("موسيقى") -> 10
                    l.contains("islam") || l.contains("quran") || l.contains("إسلام") || l.contains("قرآن") -> 11
                    else -> 12
                }
            }
            val sA = score(a)
            val sB = score(b)
            if (sA != sB) sA.compareTo(sB) else a.compareTo(b)
        })
    }

    // ⚡ Requirement 1: Only list categories related to the current Content Type ⚡
    private fun buildCategories(data: List<Channel>) {
        try {
            if (lazyXtreamMode) {
                val visible = if (categoryQuery.isBlank()) xtreamCategories else xtreamCategories.filter { it.name.contains(categoryQuery, ignoreCase = true) }
                gridAdapter.update(visible.map { CategoryGridAdapter.CategoryItem(it.name, it.count) })
                return
            }

            val filtered = data.filter { it.contentType == contentType }
            val cats = mutableListOf("All", "Favorites")
            cats.addAll(filtered.map { it.category }.distinct())

            val sortedCats = sortCategoriesByPriority(cats)
            val visibleCats = if (categoryQuery.isBlank()) sortedCats else sortedCats.filter { it.contains(categoryQuery, ignoreCase = true) }

            if (!firstCategorySelected && filtered.isNotEmpty()) {
                val firstReal = sortedCats.firstOrNull { it != "All" && it != "Favorites" }
                if (!firstReal.isNullOrBlank()) {
                    firstCategorySelected = true
                    currentCategory = firstReal
                    stickyCatTitle.text = firstReal
                    stickyCatSubtitle.text = "اضغط للتبديل"
                }
            }

            val favoriteUrls = activeId()?.let { FavoritesPrefs.getFavorites(this, it) } ?: emptySet()
            val listItems = visibleCats.map { cat ->
                val count = when (cat) {
                    "All" -> filtered.size
                    "Favorites" -> filtered.count { favoriteUrls.contains(it.streamUrl) }
                    else -> filtered.count { it.category.equals(cat, ignoreCase = true) }
                }
                CategoryGridAdapter.CategoryItem(cat, count)
            }

            gridAdapter.update(listItems)
        } catch (e: Exception) { Log.e("ChannelList", "Build Categories Error: ${e.message}") }
    }

    private fun loadLazyXtreamCategory(categoryName: String) {
        val cat = xtreamCategories.firstOrNull { it.name.equals(categoryName, ignoreCase = true) } ?: return
        progressBar.visibility = View.VISIBLE
        lastChannels = emptyList()
        adapter.updateChannels(emptyList())
        channelsProvider.fetchXtreamCategoryChannels(activeSourceUrl, contentType, cat.id, cat.name)
    }

    private fun resetCategoryToAll() {
        currentCategory = "All"
        stickyCatTitle.text = if (contentType == "live") "كل القنوات" else "الكل"
        stickyCatSubtitle.text = "اضغط للتبديل"
        closeCategoryGrid()
        applyFilter()
    }

    private fun applyFilter() {
        try {
            val favs = activeId()?.let { FavoritesPrefs.getFavorites(this, it) } ?: emptySet()
            channelsProvider.filterChannels(currentQuery, contentType, currentCategory, favs)
        } catch (e: Exception) { Log.e("ChannelList", "Apply Filter Error: ${e.message}") }
    }

    override fun onBackPressed() {
        when {
            drawerLayout.isDrawerOpen(GravityCompat.END) -> {
                drawerLayout.closeDrawer(GravityCompat.END)
            }
            categoryOverlayGrid.visibility == View.VISIBLE -> {
                closeCategoryGrid()
            }
            currentCategory != "All" -> {
                resetCategoryToAll()
            }
            else -> {
                val intent = Intent(this, MainActivity::class.java)
                intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                startActivity(intent)
                finish()
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent?): Boolean {
        if (focusMode == "categories" && categoryOverlayGrid.visibility == View.VISIBLE) {
            if (keyCode == android.view.KeyEvent.KEYCODE_BACK && event?.action == android.view.KeyEvent.ACTION_DOWN) { closeCategoryGrid(); return true }
            return super.onKeyDown(keyCode, event)
        }
        if (com.latchi.iptv.utils.TvFocusHelper.handleKey(this, keyCode, event)) return true
        return super.onKeyDown(keyCode, event)
    }

}
