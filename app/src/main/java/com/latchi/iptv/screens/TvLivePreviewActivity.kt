package com.latchi.iptv.screens

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
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

/**
 * Android TV VIP live preview screen.
 *
 * Remote behaviour:
 * - OK on a different channel: plays that channel inside preview.
 * - OK again on the currently previewed channel: opens fullscreen PlayerActivity.
 * - OK on the preview video: opens fullscreen PlayerActivity.
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
            setPadding(dp(24), dp(22), dp(24), dp(22))
            setBackgroundResource(R.drawable.bg_app)
        }
        setContentView(root)

        // Left: clean channel list from the same category / current filtered set.
        val leftPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, dp(18), 0)
        }
        root.addView(leftPanel, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 0.34f))

        val listTitle = TextView(this).apply {
            text = "قنوات الفئة (${channels.size})"
            setTextColor(Color.parseColor("#FFD700"))
            textSize = 20f
            gravity = Gravity.CENTER
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, dp(10))
        }
        leftPanel.addView(listTitle, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48)))

        val list = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@TvLivePreviewActivity)
            clipToPadding = false
            setPadding(0, dp(6), 0, dp(6))
        }
        adapter = PreviewAdapter(channels) { ch -> handleChannelSelection(ch) }
        list.adapter = adapter
        TvFocusHelper.setupRecycler(list)
        leftPanel.addView(list, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        // Right: title + real visible PlayerView + hint.
        val right = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(18), 0, 0, 0)
        }
        root.addView(right, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 0.66f))

        title = TextView(this).apply {
            text = selected.name
            setTextColor(Color.parseColor("#FFD700"))
            textSize = 28f
            gravity = Gravity.CENTER
            setTypeface(null, android.graphics.Typeface.BOLD)
            maxLines = 1
            setPadding(dp(8), 0, dp(8), 0)
        }
        right.addView(title, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(56)))

        subtitle = TextView(this).apply {
            text = selected.category
            setTextColor(Color.parseColor("#DDE6FF"))
            textSize = 15f
            gravity = Gravity.CENTER
            maxLines = 1
        }
        right.addView(subtitle, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(32)))

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
            topMargin = dp(12)
            bottomMargin = dp(12)
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

        val hint = TextView(this).apply {
            text = "OK على قناة جديدة = معاينة  •  OK مرة ثانية = شاشة كاملة  •  OK على الفيديو = شاشة كاملة"
            setTextColor(Color.parseColor("#C9D4EE"))
            textSize = 14f
            gravity = Gravity.CENTER
        }
        right.addView(hint, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(38)))

        val selectedIndex = channels.indexOfFirst { it.streamUrl == selected.streamUrl }.coerceAtLeast(0)
        list.post {
            list.scrollToPosition(selectedIndex)
            list.findViewHolderForAdapterPosition(selectedIndex)?.itemView?.requestFocus()
        }
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
        adapter.setPlaying(ch.streamUrl)

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
        private val items: List<Channel>,
        private val onClick: (Channel) -> Unit
    ) : RecyclerView.Adapter<PreviewAdapter.VH>() {
        private var playingUrl: String? = null

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
