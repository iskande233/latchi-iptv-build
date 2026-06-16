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
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
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
import com.latchi.iptv.utils.PlayerServerSyncHelper
import com.latchi.iptv.utils.SourcePrefs
import com.latchi.iptv.utils.ThemeManager
import com.latchi.iptv.utils.TvFocusHelper
import com.latchi.iptv.utils.TvUtils

/**
 * Android TV VIP Unified Live TV Screen (v2.1).
 * Redesigned into a cohesive single-screen, multi-functional layout.
 */
class TvLivePreviewActivity : AppCompatActivity() {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(com.latchi.iptv.utils.LocaleHelper.wrap(newBase))
    }

    private lateinit var selected: Channel
    private var channels: List<Channel> = emptyList()
    private var player: ExoPlayer? = null
    private lateinit var playerView: PlayerView
    private lateinit var title: TextView
    private lateinit var subtitle: TextView
    private lateinit var epgText: TextView
    private lateinit var detailsText: TextView
    private lateinit var listTitle: TextView
    private lateinit var list: RecyclerView
    private lateinit var adapter: PreviewAdapter
    private var currentPlayingChannelUrl: String? = null

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

        val customList = intent.getParcelableArrayListExtra<Channel>("extra_channels")
        channels = sanitizeChannels(if (!customList.isNullOrEmpty()) customList else loadChannelsForPreview(intent.getStringExtra("category") ?: selected.category))
        buildUi()
        playPreview(selected)
    }

    private fun sanitizeChannels(input: List<Channel>): List<Channel> {
        val unique = LinkedHashMap<String, Channel>()
        unique[selected.streamUrl] = selected
        input.filter { it.contentType == "live" }.forEach { unique[it.streamUrl] = it }
        return unique.values.toList().ifEmpty { listOf(selected) }
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(20), dp(16), dp(20), dp(16))
            setBackgroundResource(R.drawable.bg_app)
        }
        setContentView(root)

        // Left Panel: Categories + List + A-Z Fast Scroller
        val leftPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, dp(14), 0)
        }
        root.addView(leftPanel, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 0.36f))

        // Categories & Title Row
        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(8))
        }

        // 📂 زر الفئات المستطيل ناعم الحواف
        val btnCategories = TextView(this).apply {
            text = "📂 الفئات / Categories"
            setTextColor(Color.parseColor("#FFD700"))
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1A1B3A"))
                cornerRadius = dp(10).toFloat()
                setStroke(dp(2), Color.parseColor("#FFD700"))
            }
            setOnClickListener {
                showCategoriesDialog()
            }
            setOnFocusChangeListener { v, has ->
                v.animate().scaleX(if (has) 1.05f else 1f).scaleY(if (has) 1.05f else 1f).setDuration(120).start()
                if (has) {
                    (v.background as? GradientDrawable)?.setColor(Color.parseColor("#FFD700"))
                    setTextColor(Color.parseColor("#050A1A"))
                } else {
                    (v.background as? GradientDrawable)?.setColor(Color.parseColor("#1A1B3A"))
                    setTextColor(Color.parseColor("#FFD700"))
                }
            }
        }
        headerRow.addView(btnCategories, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            marginEnd = dp(8)
        })

        listTitle = TextView(this).apply {
            text = intent.getStringExtra("category") ?: selected.category
            setTextColor(Color.WHITE)
            textSize = 14f
            gravity = Gravity.CENTER_VERTICAL
            setTypeface(null, Typeface.BOLD)
            maxLines = 1
        }
        headerRow.addView(listTitle, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        leftPanel.addView(headerRow, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        // RecyclerView & A-Z Scroller Side-by-Side Row
        val listRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        leftPanel.addView(listRow, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        list = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@TvLivePreviewActivity)
            clipToPadding = false
            setPadding(0, dp(4), 0, dp(4))
        }
        adapter = PreviewAdapter(channels) { ch -> handleChannelSelection(ch) }
        list.adapter = adapter
        TvFocusHelper.setupRecycler(list)
        listRow.addView(list, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))

        // 🔤 شريط البحث الأبجدي الجانبي السريع (A-Z Fast Scroller)
        val scroller = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(40), LinearLayout.LayoutParams.MATCH_PARENT).apply {
                marginStart = dp(6)
            }
            isVerticalScrollBarEnabled = false
        }
        val letterContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        scroller.addView(letterContainer)

        val letters = listOf("A", "B", "C", "D", "E", "F", "G", "H", "I", "J", "K", "L", "M", "N", "O", "P", "Q", "R", "S", "T", "U", "V", "W", "X", "Y", "Z")
        letters.forEach { letter ->
            val letterView = TextView(this).apply {
                text = letter
                setTextColor(Color.parseColor("#A5B4FC"))
                textSize = 11f
                setTypeface(null, Typeface.BOLD)
                gravity = Gravity.CENTER
                isClickable = true
                isFocusable = true
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#121228"))
                    cornerRadius = dp(14).toFloat()
                    setStroke(dp(1), Color.parseColor("#3d3d5c"))
                }
                layoutParams = LinearLayout.LayoutParams(dp(28), dp(28)).apply {
                    bottomMargin = dp(4)
                }
                setOnClickListener {
                    val index = channels.indexOfFirst { it.name.trim().startsWith(letter, ignoreCase = true) }
                    if (index >= 0) {
                        list.scrollToPosition(index)
                        list.postDelayed({
                            list.findViewHolderForAdapterPosition(index)?.itemView?.requestFocus()
                        }, 100)
                    } else {
                        val isArabic = java.util.Locale.getDefault().language == "ar"
                        val msg = if (isArabic) "لا توجد قنوات تبدأ بحرف $letter" else "No channels found starting with $letter"
                        Toast.makeText(this@TvLivePreviewActivity, msg, Toast.LENGTH_SHORT).show()
                    }
                }
                setOnFocusChangeListener { v, has ->
                    v.animate().scaleX(if (has) 1.15f else 1f).scaleY(if (has) 1.15f else 1f).setDuration(100).start()
                    if (has) {
                        (v.background as? GradientDrawable)?.setColor(Color.parseColor("#FFD700"))
                        setTextColor(Color.parseColor("#050A1A"))
                    } else {
                        (v.background as? GradientDrawable)?.setColor(Color.parseColor("#121228"))
                        setTextColor(Color.parseColor("#A5B4FC"))
                    }
                }
            }
            letterContainer.addView(letterView)
        }
        listRow.addView(scroller)

        // Right Panel: PlayerView + Metadata + EPG
        val right = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(14), 0, 0, 0)
        }
        root.addView(right, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 0.64f))

        title = TextView(this).apply {
            text = selected.name
            setTextColor(Color.parseColor("#FFD700"))
            textSize = 24f
            gravity = Gravity.CENTER
            setTypeface(null, Typeface.BOLD)
            maxLines = 1
            setPadding(dp(8), 0, dp(8), 0)
        }
        right.addView(title, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48)))

        subtitle = TextView(this).apply {
            text = selected.category
            setTextColor(Color.parseColor("#DDE6FF"))
            textSize = 14f
            gravity = Gravity.CENTER
            maxLines = 1
        }
        right.addView(subtitle, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(28)))

        epgText = TextView(this).apply {
            text = "EPG: جاري جلب تفاصيل البرنامج..."
            setTextColor(Color.parseColor("#9FEAFF"))
            textSize = 13f
            gravity = Gravity.CENTER
            maxLines = 1
            setPadding(dp(8), dp(2), dp(8), dp(2))
        }
        right.addView(epgText, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(28)))

        val videoFrame = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            isFocusable = true
            isClickable = true
            foreground = getDrawable(R.drawable.focus_ring_vip)
            setOnClickListener { handlePlayerOkClick() }
            setOnKeyListener { _, keyCode, event ->
                if (event.action == KeyEvent.ACTION_DOWN && (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)) {
                    handlePlayerOkClick(); true
                } else false
            }
        }
        right.addView(videoFrame, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f).apply {
            topMargin = dp(8)
            bottomMargin = dp(8)
        })

        playerView = PlayerView(this).apply {
            useController = false
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            setShutterBackgroundColor(Color.BLACK)
            setBackgroundColor(Color.BLACK)
            isFocusable = false
            isClickable = false
        }
        videoFrame.addView(playerView, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        // 📺 بطاقة تفاصيل معلومات القناة المصغرة أسفل المشغل مباشرة
        val channelDetailsCard = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(6), dp(12), dp(6))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#B3121228"))
                cornerRadius = dp(8).toFloat()
                setStroke(dp(1), Color.parseColor("#3d3d5c"))
            }
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(6)
            }
        }
        detailsText = TextView(this).apply {
            setTextColor(Color.parseColor("#FFD700"))
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            maxLines = 1
        }
        channelDetailsCard.addView(detailsText)
        right.addView(channelDetailsCard)

        val hint = TextView(this).apply {
            text = "OK على قناة جديدة = معاينة  •  OK مرة ثانية = شاشة كاملة  •  OK على الفيديو = شاشة كاملة"
            setTextColor(Color.parseColor("#C9D4EE"))
            textSize = 13f
            gravity = Gravity.CENTER
        }
        right.addView(hint, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(32)))

        val selectedIndex = channels.indexOfFirst { it.streamUrl == selected.streamUrl }.coerceAtLeast(0)
        list.post {
            list.scrollToPosition(selectedIndex)
            list.findViewHolderForAdapterPosition(selectedIndex)?.itemView?.requestFocus()
        }
    }

    private fun showCategoriesDialog() {
        val active = SourcePrefs.getActiveProfile(this)
        val cached = active?.let { ChannelCache.load(this, it.id) }.orEmpty()
        val live = cached.filter { it.contentType == "live" }
        if (live.isEmpty()) {
            Toast.makeText(this, "قائمة القنوات فارغة", Toast.LENGTH_SHORT).show()
            return
        }
        val categoriesList = listOf("All") + live.map { it.category }.distinct().sorted()

        val builder = androidx.appcompat.app.AlertDialog.Builder(this)
        builder.setTitle("اختر الفئة / Select Category")
        builder.setItems(categoriesList.toTypedArray()) { _, which ->
            val chosenCat = categoriesList[which]
            val newChannels = if (chosenCat == "All") {
                live
            } else {
                live.filter { it.category.equals(chosenCat, ignoreCase = true) }
            }
            this.channels = newChannels
            listTitle.text = chosenCat
            adapter.updateChannels(newChannels)
            if (newChannels.isNotEmpty()) {
                playPreview(newChannels.first())
                list.scrollToPosition(0)
                list.postDelayed({
                    list.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus()
                }, 100)
            }
        }
        builder.show()
    }

    private fun handleChannelSelection(ch: Channel) {
        if (currentPlayingChannelUrl == ch.streamUrl) {
            PlayerActivity.start(this, ch)
        } else {
            playPreview(ch)
        }
    }

    private fun handlePlayerOkClick() {
        PlayerActivity.start(this, selected)
    }

    private fun loadChannelsForPreview(category: String): List<Channel> {
        val active = SourcePrefs.getActiveProfile(this)
        val cached = active?.let { ChannelCache.load(this, it.id) }.orEmpty()
        val live = cached.filter { it.contentType == "live" }
        val same = live.filter { it.category.equals(category, ignoreCase = true) || it.category.equals(selected.category, ignoreCase = true) }
        return (if (same.isNotEmpty()) same else live).ifEmpty { listOf(selected) }.take(300)
    }

    private fun playPreview(ch: Channel) {
        selected = ch
        currentPlayingChannelUrl = ch.streamUrl
        title.text = ch.name
        subtitle.text = ch.category
        epgText.text = "EPG: جاري جلب تفاصيل البرنامج..."
        adapter.setPlaying(ch.streamUrl)
        loadPreviewEpg(ch)

        // تحديث نصوص التفاصيل أسفل الشاشة باللغات ديناميكياً بجزء من الثانية
        val isArabic = java.util.Locale.getDefault().language == "ar"
        val isFrench = java.util.Locale.getDefault().language == "fr"
        detailsText.text = when {
            isArabic -> "القناة الحالية: ${ch.name} | الفئة: ${ch.category}"
            isFrench -> "Chaîne actuelle: ${ch.name} | Catégorie: ${ch.category}"
            else -> "Current Channel: ${ch.name} | Category: ${ch.category}"
        }

        val dataSourceFactory = DefaultHttpDataSource.Factory()
            .setUserAgent("Mozilla/5.0 (Linux; Android TV) AppleWebKit/537.36")
            .setAllowCrossProtocolRedirects(true)
        val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory)
        val streamUrl = ch.streamUrl.trim().replace("&amp;", "&")

        player?.release()
        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(mediaSourceFactory)
            .build()
            .also { exo ->
                playerView.player = exo
                exo.volume = 0.45f
                exo.setMediaItem(MediaItem.fromUri(Uri.parse(streamUrl)))
                exo.playWhenReady = true
                exo.prepare()
            }
    }

    private fun loadPreviewEpg(ch: Channel) {
        if (ch.contentType != "live") {
            epgText.text = "EPG: دليل البرامج متاح للقنوات المباشرة فقط"
            return
        }
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
            } catch (_: Exception) {
                emptyList()
            }
            runOnUiThread {
                if (selected.streamUrl != expectedUrl) return@runOnUiThread
                epgText.text = when {
                    items.isEmpty() -> "EPG: لا توجد تفاصيل برنامج متاحة الآن"
                    items.size == 1 -> "EPG الآن: ${items[0].title}"
                    else -> "EPG الآن: ${items[0].title}\nالتالي: ${items[1].title}"
                }
            }
        }.start()
    }

    override fun onResume() {
        super.onResume()
        PlayerServerSyncHelper.checkDuringPlayback(this)
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

    private inner class PreviewAdapter(
        private var items: List<Channel>,
        private val onClick: (Channel) -> Unit
    ) : RecyclerView.Adapter<PreviewAdapter.VH>() {
        private var playingUrl: String? = null

        fun updateChannels(newItems: List<Channel>) {
            this.items = newItems
            notifyDataSetChanged()
        }

        fun setPlaying(url: String) {
            val old = playingUrl
            playingUrl = url
            val oldIdx = items.indexOfFirst { it.streamUrl == old }
            val newIdx = items.indexOfFirst { it.streamUrl == url }
            if (oldIdx >= 0) notifyItemChanged(oldIdx)
            if (newIdx >= 0) notifyItemChanged(newIdx)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val row = LinearLayout(parent.context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(10), dp(8), dp(10), dp(8))
                background = getDrawable(R.drawable.bg_panel_focusable)
                foreground = getDrawable(R.drawable.focus_ring_vip)
                isFocusable = true
                isClickable = true
            }
            val logo = ImageView(parent.context).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
            row.addView(logo, LinearLayout.LayoutParams(dp(46), dp(46)).apply { marginEnd = dp(10) })
            val texts = LinearLayout(parent.context).apply { orientation = LinearLayout.VERTICAL }
            val name = TextView(parent.context).apply {
                setTextColor(Color.WHITE); textSize = 14f; setTypeface(null, android.graphics.Typeface.BOLD); maxLines = 1
            }
            val cat = TextView(parent.context).apply {
                setTextColor(Color.parseColor("#B8C2D8")); textSize = 11f; maxLines = 1
            }
            texts.addView(name)
            texts.addView(cat)
            row.addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            return VH(row, logo, name, cat)
        }

        override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position])
        override fun getItemCount(): Int = items.size

        inner class VH(itemView: View, private val logo: ImageView, private val name: TextView, private val cat: TextView) : RecyclerView.ViewHolder(itemView) {
            fun bind(ch: Channel) {
                name.text = if (ch.streamUrl == playingUrl) "▶ ${ch.name}" else ch.name
                cat.text = ch.category
                name.setTextColor(if (ch.streamUrl == playingUrl) Color.parseColor("#FFD700") else Color.WHITE)
                Glide.with(itemView.context).load(ch.logoUrl.ifBlank { null }).placeholder(R.drawable.ic_tv).error(R.drawable.ic_tv).into(logo)
                itemView.setOnClickListener { onClick(ch) }
            }
        }
    }
}
