package com.latchi.iptv.screens

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.latchi.iptv.R
import com.latchi.iptv.model.Channel
import com.latchi.iptv.utils.CatalogRepository
import com.latchi.iptv.utils.ChannelCache
import com.latchi.iptv.utils.FavoriteManager
import com.latchi.iptv.utils.SourcePrefs
import com.latchi.iptv.utils.ThemeManager
import com.latchi.iptv.utils.TvFocusHelper

/**
 * 👑 RoyalFavoritesDashboardActivity
 *
 * الشاشة الملكية المستقلة لإدارة (القنوات المفضلة، الفئات المفضلة، المشاهدة مؤخراً، والإعدادات).
 * 1. تفتح فوراً من الهاتف ومن التلفاز عند الضغط على زر ☰.
 * 2. تعرض القنوات في شبكة 4×2 مثالية ذات حواف منحنية من الجوانب الأربعة.
 * 3. تتيح إزالة العناصر بالضغط المطول (0.5 ثانية).
 */
class RoyalFavoritesDashboardActivity : AppCompatActivity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(com.latchi.iptv.utils.LocaleHelper.wrap(newBase))
    }

    private lateinit var btnBack: TextView
    private lateinit var activeProfileText: TextView
    private lateinit var tabFavChannels: TextView
    private lateinit var tabFavCategories: TextView
    private lateinit var tabRecentChannels: TextView
    private lateinit var btnOpenSettings: TextView
    private lateinit var btnOpenSupport: TextView
    private lateinit var channelsRecycler: RecyclerView
    private lateinit var categoriesRecycler: RecyclerView
    private lateinit var emptyText: TextView

    private lateinit var profileId: String
    private var currentMode = MODE_FAV_CHANNELS
    private var channelsAdapter: RoyalChannelsAdapter? = null
    private var categoriesAdapter: RoyalCategoriesAdapter? = null

    companion object {
        private const val MODE_FAV_CHANNELS = 0
        private const val MODE_FAV_CATEGORIES = 1
        private const val MODE_RECENT_CHANNELS = 2

        fun start(context: Context) {
            context.startActivity(Intent(context, RoyalFavoritesDashboardActivity::class.java))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        com.latchi.iptv.utils.TvUtils.applyOrientation(this)
        ThemeManager.apply(this)
        setContentView(R.layout.activity_royal_favorites)
        com.latchi.iptv.utils.FloatingBackHelper.setup(this)

        val active = SourcePrefs.getActiveProfile(this)
        if (active == null) {
            finish()
            return
        }
        profileId = active.id

        setFindViewById()
        setupListeners()
        selectTab(MODE_FAV_CHANNELS)
    }

    private fun setFindViewById() {
        btnBack = findViewById(R.id.btnBack)
        activeProfileText = findViewById(R.id.activeProfileText)
        tabFavChannels = findViewById(R.id.tabFavChannels)
        tabFavCategories = findViewById(R.id.tabFavCategories)
        tabRecentChannels = findViewById(R.id.tabRecentChannels)
        btnOpenSettings = findViewById(R.id.btnOpenSettings)
        btnOpenSupport = findViewById(R.id.btnOpenSupport)
        channelsRecycler = findViewById(R.id.channelsRecycler)
        categoriesRecycler = findViewById(R.id.categoriesRecycler)
        emptyText = findViewById(R.id.emptyText)

        activeProfileText.text = SourcePrefs.getActiveProfile(this)?.name ?: "VIP Profile"

        // ضبط الشبكة الملكية 4×2
        val isTv = com.latchi.iptv.utils.TvUtils.isTv(this)
        if (isTv) {
            // للتلفاز: 2 صفوف أفقياً
            channelsRecycler.layoutManager = GridLayoutManager(this, 2, RecyclerView.HORIZONTAL, false)
        } else {
            // للهاتف: 4 أعمدة عمودياً
            channelsRecycler.layoutManager = GridLayoutManager(this, 4, RecyclerView.VERTICAL, false)
        }
        categoriesRecycler.layoutManager = LinearLayoutManager(this)

        TvFocusHelper.setupRecycler(channelsRecycler)
        TvFocusHelper.setupRecycler(categoriesRecycler)
    }

    private fun setupListeners() {
        btnBack.setOnClickListener { finish() }
        tabFavChannels.setOnClickListener { selectTab(MODE_FAV_CHANNELS) }
        tabFavCategories.setOnClickListener { selectTab(MODE_FAV_CATEGORIES) }
        tabRecentChannels.setOnClickListener { selectTab(MODE_RECENT_CHANNELS) }

        btnOpenSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        btnOpenSupport.setOnClickListener {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/213798712450")))
            } catch (_: Exception) {}
        }
    }

    private fun currentServerChannels(): List<Channel> {
        val active = SourcePrefs.getActiveProfile(this) ?: return emptyList()
        return ChannelCache.load(this, active.id).ifEmpty {
            runCatching { CatalogRepository.getChannelsByTypeBlocking(this, active.id, "live") }.getOrDefault(emptyList())
        }
    }

    private fun filterChannelsForCurrentServer(list: List<Channel>): List<Channel> {
        val current = currentServerChannels()
        if (current.isEmpty()) return list
        val urls = current.map { it.streamUrl }.toHashSet()
        return list.filter { it.streamUrl in urls }
    }

    private fun filterCategoriesForCurrentServer(list: List<String>): List<String> {
        val current = currentServerChannels()
        if (current.isEmpty()) return list
        val cats = current.map { it.category.lowercase().trim() }.toSet()
        return list.filter { it.lowercase().trim() in cats }
    }

    private fun selectTab(mode: Int) {
        currentMode = mode
        tabFavChannels.setTextColor(if (mode == MODE_FAV_CHANNELS) Color.parseColor("#FFD700") else Color.WHITE)
        tabFavCategories.setTextColor(if (mode == MODE_FAV_CATEGORIES) Color.parseColor("#FFD700") else Color.WHITE)
        tabRecentChannels.setTextColor(if (mode == MODE_RECENT_CHANNELS) Color.parseColor("#FFD700") else Color.WHITE)

        when (mode) {
            MODE_FAV_CHANNELS -> loadChannels(filterChannelsForCurrentServer(FavoriteManager.getFavoriteChannels(this, profileId)))
            MODE_FAV_CATEGORIES -> loadCategories(filterCategoriesForCurrentServer(FavoriteManager.getFavoriteCategories(this, profileId)))
            MODE_RECENT_CHANNELS -> loadChannels(filterChannelsForCurrentServer(FavoriteManager.getRecentChannels(this, profileId)))
        }
    }

    private fun loadChannels(list: List<Channel>) {
        channelsRecycler.visibility = View.VISIBLE
        categoriesRecycler.visibility = View.GONE
        emptyText.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE

        channelsAdapter = RoyalChannelsAdapter(list, onClick = { ch ->
            if (com.latchi.iptv.utils.TvUtils.isTv(this)) {
                TvLivePreviewActivity.startWithChannels(this, ch, list, if (currentMode == MODE_FAV_CHANNELS) "المفضلة" else "المشاهدة مؤخراً")
            } else {
                PlayerActivity.start(this, ch)
            }
        }, onLongClick = { ch ->
            if (currentMode == MODE_FAV_CHANNELS) {
                confirmRemoveFavoriteChannel(ch)
            } else {
                confirmRemoveRecentChannel(ch)
            }
        })
        channelsRecycler.adapter = channelsAdapter
    }

    private fun loadCategories(list: List<String>) {
        channelsRecycler.visibility = View.GONE
        categoriesRecycler.visibility = View.VISIBLE
        emptyText.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE

        categoriesAdapter = RoyalCategoriesAdapter(list, onClick = { catName ->
            ChannelListActivity.startSearch(this, "live", "📁 $catName", catName)
        }, onLongClick = { catName ->
            confirmRemoveFavoriteCategory(catName)
        })
        categoriesRecycler.adapter = categoriesAdapter
    }

    private fun confirmRemoveFavoriteChannel(ch: Channel) {
        AlertDialog.Builder(this)
            .setTitle("إزالة من المفضلة")
            .setMessage("هل تريد إزالة القناة '${ch.name}' من المفضلة؟")
            .setPositiveButton("نعم، إزالة") { _, _ ->
                FavoriteManager.toggleFavoriteChannel(this, profileId, ch)
                selectTab(MODE_FAV_CHANNELS)
                Toast.makeText(this, "⭐ تمت إزالة القناة من المفضلة", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun confirmRemoveRecentChannel(ch: Channel) {
        AlertDialog.Builder(this)
            .setTitle("إزالة من المشاهدة مؤخراً")
            .setMessage("هل تريد إزالة القناة '${ch.name}' من السجل؟")
            .setPositiveButton("نعم، إزالة") { _, _ ->
                val current = FavoriteManager.getRecentChannels(this, profileId).toMutableList()
                current.removeAll { it.streamUrl == ch.streamUrl }
                getSharedPreferences("latchi_royal_favorites_v2", MODE_PRIVATE).edit()
                    .putString("recent_channels_$profileId", org.json.JSONArray().apply { current.forEach { put(it.toJson()) } }.toString()).apply()
                selectTab(MODE_RECENT_CHANNELS)
                Toast.makeText(this, "🔄 تمت إزالة القناة من السجل", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun confirmRemoveFavoriteCategory(catName: String) {
        AlertDialog.Builder(this)
            .setTitle("إزالة الفئة من المفضلة")
            .setMessage("هل تريد إزالة الفئة '$catName' من المفضلة؟")
            .setPositiveButton("نعم، إزالة") { _, _ ->
                FavoriteManager.toggleFavoriteCategory(this, profileId, catName)
                selectTab(MODE_FAV_CATEGORIES)
                Toast.makeText(this, "📁 تمت إزالة الفئة من المفضلة", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    // ─────────────────────────────────────────────────────────────
    // محول القنوات (Royal Grid 4×2)
    // ─────────────────────────────────────────────────────────────
    private inner class RoyalChannelsAdapter(
        private val items: List<Channel>,
        private val onClick: (Channel) -> Unit,
        private val onLongClick: (Channel) -> Unit
    ) : RecyclerView.Adapter<RoyalChannelsAdapter.VH>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val card = androidx.cardview.widget.CardView(parent.context).apply {
                layoutParams = ViewGroup.MarginLayoutParams(dp(168), dp(98)).apply {
                    setMargins(dp(8), dp(8), dp(8), dp(8))
                }
                radius = dp(16).toFloat()
                cardElevation = dp(8).toFloat()
                setCardBackgroundColor(Color.parseColor("#1A1A30"))
                isClickable = true
                isFocusable = true
                foreground = getDrawable(R.drawable.focus_selector)
            }
            val container = LinearLayout(parent.context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(8), dp(8), dp(8), dp(8))
            }
            val logo = ImageView(parent.context).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                layoutParams = LinearLayout.LayoutParams(dp(50), dp(50)).apply { bottomMargin = dp(6) }
            }
            val name = TextView(parent.context).apply {
                setTextColor(Color.WHITE); textSize = 13f; setTypeface(null, Typeface.BOLD); maxLines = 1
                gravity = Gravity.CENTER
            }
            container.addView(logo); container.addView(name)
            card.addView(container)
            return VH(card, logo, name)
        }

        override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position])
        override fun getItemCount() = items.size

        inner class VH(private val card: androidx.cardview.widget.CardView, private val logo: ImageView, private val name: TextView) : RecyclerView.ViewHolder(card) {
            fun bind(ch: Channel) {
                name.text = ch.name
                Glide.with(card.context).load(ch.logoUrl.ifBlank { null }).placeholder(R.drawable.ic_tv).error(R.drawable.ic_tv).into(logo)

                card.setOnClickListener { onClick(ch) }
                card.setOnLongClickListener { onLongClick(ch); true }
                card.setOnFocusChangeListener { v, has ->
                    v.animate().scaleX(if (has) 1.06f else 1f).scaleY(if (has) 1.06f else 1f).setDuration(100).start()
                    if (has) {
                        card.setCardBackgroundColor(Color.parseColor("#2A2C5A"))
                        name.setTextColor(Color.parseColor("#FFD700"))
                    } else {
                        card.setCardBackgroundColor(Color.parseColor("#1A1A30"))
                        name.setTextColor(Color.WHITE)
                    }
                }
            }
        }
    }

    // ─────────────────────────────────────────────────────────────
    // محول الفئات
    // ─────────────────────────────────────────────────────────────
    private inner class RoyalCategoriesAdapter(
        private val items: List<String>,
        private val onClick: (String) -> Unit,
        private val onLongClick: (String) -> Unit
    ) : RecyclerView.Adapter<RoyalCategoriesAdapter.VH>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val tv = TextView(parent.context).apply {
                setTextColor(Color.WHITE); textSize = 16f; setTypeface(null, Typeface.BOLD)
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(18), dp(16), dp(18), dp(16))
                isClickable = true; isFocusable = true
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#1A1B3A"))
                    cornerRadius = dp(12).toFloat()
                    setStroke(dp(1), Color.parseColor("#3d3d5c"))
                }
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = dp(10)
                }
                foreground = getDrawable(R.drawable.focus_selector)
            }
            return VH(tv)
        }

        override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position])
        override fun getItemCount() = items.size

        inner class VH(private val tv: TextView) : RecyclerView.ViewHolder(tv) {
            fun bind(cat: String) {
                tv.text = "📁 $cat"

                tv.setOnClickListener { onClick(cat) }
                tv.setOnLongClickListener { onLongClick(cat); true }
                tv.setOnFocusChangeListener { v, has ->
                    v.animate().scaleX(if (has) 1.04f else 1f).scaleY(if (has) 1.04f else 1f).setDuration(100).start()
                    if (has) {
                        (tv.background as? GradientDrawable)?.setColor(Color.parseColor("#2A2C5A"))
                        (tv.background as? GradientDrawable)?.setStroke(dp(2), Color.parseColor("#FFD700"))
                        tv.setTextColor(Color.parseColor("#FFD700"))
                    } else {
                        (tv.background as? GradientDrawable)?.setColor(Color.parseColor("#1A1B3A"))
                        (tv.background as? GradientDrawable)?.setStroke(dp(1), Color.parseColor("#3d3d5c"))
                        tv.setTextColor(Color.WHITE)
                    }
                }
            }
        }
    }
}
