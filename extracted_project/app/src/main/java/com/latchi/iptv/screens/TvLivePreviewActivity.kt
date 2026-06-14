package com.latchi.iptv.screens

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.ui.PlayerView
import com.latchi.iptv.R
import com.latchi.iptv.utils.ErrorOverlayHelper
import com.latchi.iptv.adapter.ChannelsAdapter
import com.latchi.iptv.model.Channel
import com.latchi.iptv.utils.ChannelCache
import com.latchi.iptv.utils.FavoritesPrefs
import com.latchi.iptv.utils.SourcePrefs
import com.latchi.iptv.utils.ThemeManager
import com.latchi.iptv.utils.TvFocusHelper
import com.latchi.iptv.utils.TvUtils
import com.latchi.iptv.utils.LiveClockHelper

/**
 * TV-only Live Preview Screen (Priority 4 from instructions)
 * 
 * When user selects a Live channel on TV:
 * - Opens this dedicated preview (does NOT replace ChannelListActivity).
 * - Left: Channel list (same category or current results).
 * - Right: Live preview player + channel info.
 * - OK on preview or on a channel in list (second time) → opens full PlayerActivity.
 * - Back → returns to ChannelListActivity (preserves previous state).
 * - Phone behavior remains unchanged (direct to Player).
 */
class TvLivePreviewActivity : AppCompatActivity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(com.latchi.iptv.utils.LocaleHelper.wrap(newBase))
    }

    private lateinit var previewPlayerView: PlayerView
    private lateinit var channelListRecycler: RecyclerView
    private lateinit var channelNameText: TextView
    private lateinit var channelInfoText: TextView
    private lateinit var backButton: TextView
    private lateinit var categoryButton: TextView

    private var player: ExoPlayer? = null
    private var currentChannel: Channel? = null
    private var currentChannels: List<Channel> = emptyList()
    private var currentCategory: String = "All"

    private lateinit var adapter: ChannelsAdapter

    companion object {
        private const val EXTRA_CHANNEL = "extra_channel"
        private const val EXTRA_CATEGORY = "extra_category"
        private const val EXTRA_CHANNELS = "extra_channels" // ParcelableArrayList

        fun start(context: Context, channel: Channel, category: String = "All", channels: List<Channel> = emptyList()) {
            val intent = Intent(context, TvLivePreviewActivity::class.java).apply {
                putExtra(EXTRA_CHANNEL, channel)
                putExtra(EXTRA_CATEGORY, category)
                if (channels.isNotEmpty()) {
                    putParcelableArrayListExtra(EXTRA_CHANNELS, ArrayList(channels))
                }
            }
            context.startActivity(intent)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!TvUtils.isTv(this)) {
            // Fallback for safety - should never happen
            val channel = intent.getParcelableExtra<Channel>(EXTRA_CHANNEL)
            if (channel != null) {
                PlayerActivity.start(this, channel)
            }
            finish()
            return
        }

        ThemeManager.apply(this)
        setContentView(R.layout.activity_tv)  // Re-use the existing excellent split TV layout

        // Bind views from activity_tv.xml
        previewPlayerView = findViewById(R.id.tvPreviewPlayer)
        channelListRecycler = findViewById(R.id.tvChannelList)
        channelNameText = findViewById(R.id.tvChannelName)
        channelInfoText = findViewById(R.id.tvChannelInfo)
        backButton = findViewById(R.id.tvBackButton)
        categoryButton = findViewById(R.id.tvCategoryButton)
        // Priority 5: Live clock in TV Preview header
        findViewById<TextView?>(R.id.tvClock)?.let { LiveClockHelper.startClock(it) }

        // Setup back
        backButton.setOnClickListener { finish() }
        backButton.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                finish()
                true
            } else false
        }

        // Load data from intent
        currentChannel = intent.getParcelableExtra(EXTRA_CHANNEL)
        currentCategory = intent.getStringExtra(EXTRA_CATEGORY) ?: "All"

        val passedChannels = intent.getParcelableArrayListExtra<Channel>(EXTRA_CHANNELS)
        if (!passedChannels.isNullOrEmpty()) {
            currentChannels = passedChannels
        } else {
            // Fallback: load from cache or provider (simplified)
            currentChannels = loadChannelsForCategory(currentCategory)
        }

        if (currentChannel == null) {
            ErrorOverlayHelper.show(this, "تنبيه", "No channel selected")
            finish()
            return
        }

        // Setup channel list (TV friendly)
        setupChannelList()

        // Setup initial preview
        updatePreview(currentChannel!!)

        // Focus management for TV
        TvFocusHelper.setup(this)
        channelListRecycler.requestFocus()

        // Optional: auto-focus first item in list
        channelListRecycler.postDelayed({
            if (channelListRecycler.findViewHolderForAdapterPosition(0) != null) {
                channelListRecycler.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus()
            }
        }, 300)
    }

    private fun setupChannelList() {
        adapter = ChannelsAdapter(
            currentChannels,
            isGrid = false,
            isFavorite = { channel ->
                val active = SourcePrefs.getActiveProfile(this)
                active?.let { FavoritesPrefs.isFavorite(this, it.id, channel.streamUrl) } ?: false
            },
            onFavoriteClicked = { channel ->
                val active = SourcePrefs.getActiveProfile(this)
                active?.let {
                    FavoritesPrefs.toggle(this, it.id, channel.streamUrl)
                    adapter.notifyDataSetChanged()
                }
            },
            onChannelClicked = { channel ->
                // On TV: switch preview immediately
                if (TvUtils.isTv(this)) {
                    updatePreview(channel)
                    // Keep focus on list or move to preview area if wanted
                    // For simplicity we stay in list; user can press OK again on preview to go full
                } else {
                    // Should not happen
                    PlayerActivity.start(this, channel)
                }
            }
        )

        channelListRecycler.layoutManager = LinearLayoutManager(this)
        TvFocusHelper.setupRecycler(channelListRecycler)
        channelListRecycler.adapter = adapter

        // Highlight current channel in list if possible
        val currentIndex = currentChannels.indexOfFirst { it.streamUrl == currentChannel?.streamUrl }
        if (currentIndex >= 0) {
            channelListRecycler.postDelayed({
                channelListRecycler.scrollToPosition(currentIndex)
                channelListRecycler.findViewHolderForAdapterPosition(currentIndex)?.itemView?.requestFocus()
            }, 400)
        }

        // Category button shows current filter
        categoryButton.text = if (currentCategory == "All") "كل القنوات" else currentCategory
        categoryButton.setOnClickListener {
            // Could open category selector, but for now just toast
            ErrorOverlayHelper.show(this, "تنبيه", "Use back to return to categories")
        }
    }

    private fun updatePreview(channel: Channel) {
        currentChannel = channel

        // Update info texts
        channelNameText.text = channel.name
        channelInfoText.text = channel.category.ifBlank { "Live TV" }

        // Stop previous player
        player?.release()
        player = null

        // Create new preview player (low volume for preview, no full controls)
        player = ExoPlayer.Builder(this).build().apply {
            val mediaItem = MediaItem.fromUri(channel.streamUrl)
            setMediaItem(mediaItem)
            prepare()
            playWhenReady = true
            volume = 0.3f   // Quiet preview
        }

        previewPlayerView.player = player

        // Make preview clickable to go full screen player
        previewPlayerView.setOnClickListener {
            goToFullPlayer()
        }

        // For TV remote: allow OK on the player view area to go full
        previewPlayerView.isFocusable = true
        previewPlayerView.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_DPAD_CENTER) {
                if (event.action == KeyEvent.ACTION_UP) {
                    goToFullPlayer()
                    true
                } else false
            } else false
        }

        // Update adapter selection (visual)
        adapter.updateSelectedChannel(channel.streamUrl)
    }

    private fun goToFullPlayer() {
        currentChannel?.let { channel ->
            // Stop preview player before launching full one
            player?.release()
            player = null

            PlayerActivity.start(this, channel)
            // We finish so user returns to ChannelList when they back from full player
            finish()
        }
    }

    private fun loadChannelsForCategory(category: String): List<Channel> {
        // Simple fallback: try to load from ChannelCache for active profile
        val active = SourcePrefs.getActiveProfile(this) ?: return emptyList()
        return try {
            ChannelCache.load(this, active.id).filter {
                it.contentType == "live" &&
                (category == "All" || it.category.equals(category, ignoreCase = true))
            }.take(50) // limit for performance
        } catch (e: Exception) {
            emptyList()
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        // Handle back globally for TV
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            finish()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onPause() {
        super.onPause()
        player?.playWhenReady = false
    }

    override fun onResume() {
        super.onResume()
        player?.playWhenReady = true
    }

    override fun onDestroy() {
        LiveClockHelper.stopClock()
        super.onDestroy()
        player?.release()
        player = null
    }
}
