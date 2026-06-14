package com.latchi.iptv.screens

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.ui.PlayerView
import com.latchi.iptv.R
import com.latchi.iptv.adapter.ChannelsAdapter
import com.latchi.iptv.model.Channel
import com.latchi.iptv.utils.ChannelCache
import com.latchi.iptv.utils.FavoritesPrefs
import com.latchi.iptv.utils.PlayerServerSyncHelper
import com.latchi.iptv.utils.SourcePrefs
import com.latchi.iptv.utils.ThemeManager
import com.latchi.iptv.utils.TvFocusHelper

class TvLivePreviewActivity : AppCompatActivity() {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(com.latchi.iptv.utils.LocaleHelper.wrap(newBase))
    }

    private lateinit var selected: Channel
    private var channels: List<Channel> = emptyList()
    private var player: ExoPlayer? = null
    private lateinit var playerView: PlayerView
    private lateinit var title: TextView
    private lateinit var adapter: ChannelsAdapter

    companion object {
        fun start(context: Context, channel: Channel, category: String) {
            context.startActivity(Intent(context, TvLivePreviewActivity::class.java).apply {
                putExtra("channel", channel)
                putExtra("category", category)
            })
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        ThemeManager.apply(this)
        selected = intent.getParcelableExtra("channel") ?: run { finish(); return }
        channels = loadChannelsForPreview(intent.getStringExtra("category") ?: selected.category)
        buildUi()
        playPreview(selected)
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(18), dp(18), dp(18), dp(18))
            setBackgroundResource(R.drawable.bg_app)
        }
        setContentView(root)

        val list = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@TvLivePreviewActivity)
        }
        adapter = ChannelsAdapter(
            channels,
            isGrid = false,
            isFavorite = { ch -> SourcePrefs.getActiveProfile(this)?.let { FavoritesPrefs.isFavorite(this, it.id, ch.streamUrl) } ?: false },
            onFavoriteClicked = { ch -> SourcePrefs.getActiveProfile(this)?.let { FavoritesPrefs.toggle(this, it.id, ch.streamUrl) } },
            onChannelClicked = { ch -> selected = ch; playPreview(ch) }
        )
        list.adapter = adapter
        TvFocusHelper.setupRecycler(list)
        root.addView(list, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 0.42f))

        val right = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(18), 0, 0, 0)
        }
        root.addView(right, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 0.58f))

        title = TextView(this).apply {
            text = selected.name
            setTextColor(0xFFFFD700.toInt())
            textSize = 22f
            gravity = Gravity.CENTER
            setTypeface(null, android.graphics.Typeface.BOLD)
        }
        right.addView(title, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(52)))

        playerView = PlayerView(this).apply {
            useController = true
            isFocusable = true
            isClickable = true
            setOnClickListener { PlayerActivity.start(this@TvLivePreviewActivity, selected) }
            setOnKeyListener { _, keyCode, event ->
                if (event.action == KeyEvent.ACTION_DOWN && (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)) {
                    PlayerActivity.start(this@TvLivePreviewActivity, selected)
                    true
                } else false
            }
        }
        right.addView(playerView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        val hint = TextView(this).apply {
            text = "OK على المعاينة = شاشة كاملة  •  Back = رجوع للقنوات"
            setTextColor(0xFFEADCF8.toInt())
            textSize = 14f
            gravity = Gravity.CENTER
        }
        right.addView(hint, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(42)))
    }

    private fun loadChannelsForPreview(category: String): List<Channel> {
        val active = SourcePrefs.getActiveProfile(this)
        val cached = active?.let { ChannelCache.load(this, it.id) }.orEmpty()
        val live = cached.filter { it.contentType == "live" }
        val same = live.filter { it.category.equals(category, ignoreCase = true) }
        return (if (same.isNotEmpty()) same else live).ifEmpty { listOf(selected) }
    }

    private fun playPreview(ch: Channel) {
        title.text = ch.name
        player?.release()
        player = ExoPlayer.Builder(this).build().also { exo ->
            playerView.player = exo
            exo.volume = 0.35f
            exo.setMediaItem(MediaItem.fromUri(ch.streamUrl))
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
}
