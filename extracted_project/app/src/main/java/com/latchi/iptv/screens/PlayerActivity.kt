package com.latchi.iptv.screens

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.PlaybackException
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.source.DefaultMediaSourceFactory
import com.google.android.exoplayer2.source.ProgressiveMediaSource
import com.google.android.exoplayer2.source.hls.HlsMediaSource
import com.google.android.exoplayer2.ui.PlayerView
import com.google.android.exoplayer2.upstream.DefaultHttpDataSource
import com.google.android.exoplayer2.util.Util
import com.latchi.iptv.R
import com.latchi.iptv.model.Channel
import com.latchi.iptv.utils.LastWatchedPrefs
import com.latchi.iptv.utils.SourcePrefs
import com.latchi.iptv.utils.PlayerPrefs
import com.latchi.iptv.utils.PlayerServerSyncHelper
import com.latchi.iptv.utils.ServerUpdateOverlayHelper

class PlayerActivity : AppCompatActivity() {
    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(com.latchi.iptv.utils.LocaleHelper.wrap(newBase))
    }

    private var player: ExoPlayer? = null
    private lateinit var playerView: PlayerView
    private lateinit var progressBar: ProgressBar
    private lateinit var errorTextView: TextView
    private lateinit var imageViewFullScreen: ImageView
    private lateinit var imageViewLock: ImageView
    private lateinit var linearLayoutControlUp: LinearLayout
    private lateinit var linearLayoutControlBottom: LinearLayout
    private lateinit var epgOverlay: LinearLayout
    private lateinit var epgNow: TextView
    private lateinit var epgNext: TextView
    private lateinit var channel: Channel
    private var playbackPosition = 0L
    private var isPlayerReady = false
    private var isLock = false
    private var sleepTimerHandler: android.os.Handler? = null
    private var sleepTimerRunnable: Runnable? = null
    private var isInPipMode = false
    private var channelList: List<com.latchi.iptv.model.Channel> = emptyList()
    private var currentChannelIndex = -1
    private lateinit var watermarkText: TextView
    private var watermarkHandler: android.os.Handler? = null
    private var playerControlReceiver: BroadcastReceiver? = null

    companion object {
        private const val INCREMENT_MILLIS = 5000L
        fun start(context: Context, channel: Channel) {
            val intent = Intent(context, PlayerActivity::class.java).apply { putExtra("channel", channel) }
            context.startActivity(intent)
        }
    }

    @SuppressLint("SourceLockedOrientationActivity")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        hideSystemUi()
        setContentView(R.layout.activity_player)
        com.latchi.iptv.utils.ThemeManager.apply(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // 🚫 منع تسجيل الشاشة والتقاط الصور (حماية المحتوى من إعادة البث)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        channel = savedInstanceState?.getParcelable("channel") ?: intent.getParcelableExtra("channel") ?: run { finish(); return }
        SourcePrefs.getActiveProfile(this)?.let { LastWatchedPrefs.save(this, it.id, channel) }
        playbackPosition = savedInstanceState?.getLong("playbackPosition") ?: getResumePosition()
        setFindViewById(); setupPlayer(); setLockScreen(); setFullScreen(); setupResize(); setupSleepTimer(); setupPip(); setupEpgToggle(); registerPlayerVoiceControls(); loadEpg(); loadChannelList(); if(channel.contentType=="live"){
            watermarkHandler=android.os.Handler(android.os.Looper.getMainLooper())
            watermarkHandler?.postDelayed(object:Runnable{
                var moveCount=0
                override fun run(){
                    val p=watermarkText.parent as? android.view.ViewGroup
                    if(p!=null&&p.width>0){
                        if(moveCount==0){
                            watermarkText.visibility=android.view.View.VISIBLE
                            watermarkText.alpha=0.7f
                        }
                        watermarkText.x=(Math.random()*(p.width-300).coerceAtLeast(1)).toFloat()
                        watermarkText.y=(Math.random()*(p.height-100).coerceAtLeast(1)).toFloat()
                        moveCount++
                        if(moveCount<30){
                            watermarkHandler?.postDelayed(this,500)
                        }else{
                            moveCount=0
                            watermarkText.visibility=android.view.View.GONE
                            watermarkHandler?.postDelayed(this,900000)
                        }
                    }else{
                        watermarkHandler?.postDelayed(this,2000)
                    }
                }
            },5000)
        }
    }

    // Cycle through aspect-ratio modes: FIT -> FILL -> ZOOM
    private var resizeIndex = 0
    private val resizeModes = listOf(
        com.google.android.exoplayer2.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT to "FIT",
        com.google.android.exoplayer2.ui.AspectRatioFrameLayout.RESIZE_MODE_FILL to "FILL",
        com.google.android.exoplayer2.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM to "ZOOM"
    )

    private fun setupResize() {
        val resizeBtn = findViewById<TextView>(R.id.textViewResize)
        resizeBtn.setOnClickListener {
            resizeIndex = (resizeIndex + 1) % resizeModes.size
            val (mode, label) = resizeModes[resizeIndex]
            playerView.resizeMode = mode
            resizeBtn.text = label
        }
    }

    private fun setupSleepTimer() {
        val sleepBtn = findViewById<TextView>(R.id.textViewSleep)
        sleepBtn.setOnClickListener {
            val options = arrayOf(
                getString(R.string.sleep_timer_30),
                getString(R.string.sleep_timer_60),
                getString(R.string.sleep_timer_90),
                getString(R.string.sleep_timer_120),
                getString(R.string.sleep_timer_off)
            )
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(getString(R.string.sleep_timer_title))
                .setItems(options) { _, which ->
                    when (which) {
                        0 -> startSleepTimer(30)
                        1 -> startSleepTimer(60)
                        2 -> startSleepTimer(90)
                        3 -> startSleepTimer(120)
                        4 -> cancelSleepTimer()
                    }
                }
                .setNegativeButton(getString(R.string.close), null)
                .show()
        }
    }

    private fun startSleepTimer(minutes: Int) {
        cancelSleepTimer()
        sleepTimerHandler = android.os.Handler(android.os.Looper.getMainLooper())
        sleepTimerRunnable = Runnable {
            Toast.makeText(this, getString(R.string.sleep_timer_expired), Toast.LENGTH_LONG).show()
            finish()
        }
        sleepTimerHandler?.postDelayed(sleepTimerRunnable!!, minutes * 60_000L)
        Toast.makeText(this, getString(R.string.sleep_timer_set, minutes), Toast.LENGTH_SHORT).show()
        // Change button text to show timer is active
        findViewById<TextView>(R.id.textViewSleep)?.text = "⏲ $minutes"
    }

    private fun cancelSleepTimer() {
        sleepTimerHandler?.removeCallbacksAndMessages(null)
        sleepTimerRunnable = null
        findViewById<TextView>(R.id.textViewSleep)?.text = "⏲ Sleep"
        ErrorOverlayHelper.show(this, "تنبيه", getString(R.string.sleep_timer_cancelled))
    }

    private fun setupPip() {
        val pipBtn = findViewById<TextView>(R.id.textViewPip)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            pipBtn.setOnClickListener {
                if (channel.contentType == "live" || channel.contentType == "movie") {
                    enterPictureInPictureMode()
                } else {
                    ErrorOverlayHelper.show(this, "تنبيه", getString(R.string.pip_not_supported))
                }
            }
        } else {
            pipBtn.visibility = View.GONE
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        isInPipMode = isInPictureInPictureMode
        if (isInPictureInPictureMode) {
            linearLayoutControlUp.visibility = View.GONE
            linearLayoutControlBottom.visibility = View.GONE
            imageViewLock.visibility = View.GONE
            epgOverlay.visibility = View.GONE
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O && !isInPipMode) {
            if (channel.contentType == "live" && player?.playWhenReady == true) {
                enterPictureInPictureMode()
            }
        }
    }

    private fun setupEpgToggle() {
        val epgBtn = findViewById<TextView>(R.id.textViewEpg)
        epgBtn.setOnClickListener {
            epgOverlay.visibility = if (epgOverlay.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            if (epgOverlay.visibility == View.VISIBLE && epgNow.text.isNullOrBlank()) {
                loadEpg()
            }
        }
    }

    private fun hideSystemUi() {
        supportActionBar?.hide()
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
    }

    private fun setFindViewById() {
        playerView = findViewById(R.id.playerView); progressBar = findViewById(R.id.progressBar); errorTextView = findViewById(R.id.errorTextView)
        imageViewFullScreen = findViewById(R.id.imageViewFullScreen); imageViewLock = findViewById(R.id.imageViewLock)
        linearLayoutControlUp = findViewById(R.id.linearLayoutControlUp); linearLayoutControlBottom = findViewById(R.id.linearLayoutControlBottom)
        epgOverlay = findViewById(R.id.epgOverlay); watermarkText = findViewById(R.id.watermarkText); epgNow = findViewById(R.id.epgNow); epgNext = findViewById(R.id.epgNext)
    }

    // Fetch now/next EPG for live channels and show it briefly as an overlay.
    private fun loadChannelList() {
        val active = SourcePrefs.getActiveProfile(this) ?: return
        val cached = com.latchi.iptv.utils.ChannelCache.load(this, active.id)
        channelList = cached.filter { it.contentType == "live" }
        currentChannelIndex = channelList.indexOfFirst { it.streamUrl == channel.streamUrl }
    }
    private fun playChannel(idx: Int) {
        if (idx < 0 || idx >= channelList.size) return
        channel = channelList[idx]
        currentChannelIndex = idx
        player?.release()
        setupPlayer()
        SourcePrefs.getActiveProfile(this)?.let { com.latchi.iptv.utils.LastWatchedPrefs.save(this, it.id, channel) }
    }

    private fun loadEpg() {
        if (channel.contentType != "live") return
        val active = SourcePrefs.getActiveProfile(this) ?: return
        val creds = com.latchi.iptv.utils.XtreamHelper.parseCreds(active.m3uUrl) ?: return
        val streamId = com.latchi.iptv.utils.XtreamHelper.liveStreamId(channel.streamUrl) ?: return
        Thread {
            val items = try {
                com.latchi.iptv.utils.XtreamHelper.fetchShortEpg(creds, streamId, 2)
            } catch (e: Exception) {
                emptyList()
            }
            runOnUiThread {
                if (items.isEmpty()) return@runOnUiThread
                epgNow.text = "● ${items[0].title}"
                if (items.size > 1) {
                    epgNext.text = "${getString(R.string.next_label)}: ${items[1].title}"
                    epgNext.visibility = View.VISIBLE
                } else {
                    epgNext.visibility = View.GONE
                }
                epgOverlay.visibility = View.VISIBLE
                // Auto-hide after 8 seconds so it doesn't block the view
                epgOverlay.postDelayed({ epgOverlay.visibility = View.GONE }, 8000)
            }
        }.start()
    }

    private fun setupPlayer() {
        progressBar.visibility = View.VISIBLE
        errorTextView.visibility = View.GONE
        playerView.visibility = View.VISIBLE

        val dataSourceFactory = DefaultHttpDataSource.Factory()
            .setUserAgent("Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36")
            .setAllowCrossProtocolRedirects(true)

        val streamUrl = channel.streamUrl.trim().replace("&amp;", "&")
        val mediaItem = MediaItem.fromUri(Uri.parse(streamUrl))
        val mode = PlayerPrefs.getMode(this)

        player = if (mode == PlayerPrefs.MODE_AUTO) {
            val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory)
            ExoPlayer.Builder(this).setMediaSourceFactory(mediaSourceFactory)
                .setSeekBackIncrementMs(INCREMENT_MILLIS)
                .setSeekForwardIncrementMs(INCREMENT_MILLIS)
                .build()
        } else {
            ExoPlayer.Builder(this)
                .setSeekBackIncrementMs(INCREMENT_MILLIS)
                .setSeekForwardIncrementMs(INCREMENT_MILLIS)
                .build()
        }.also { exoPlayer ->
            playerView.player = exoPlayer
            playerView.useController = true

            if (mode == PlayerPrefs.MODE_HLS) {
                val source = HlsMediaSource.Factory(dataSourceFactory).createMediaSource(mediaItem)
                exoPlayer.setMediaSource(source)
            } else if (mode == PlayerPrefs.MODE_PROGRESSIVE) {
                val source = ProgressiveMediaSource.Factory(dataSourceFactory).createMediaSource(mediaItem)
                exoPlayer.setMediaSource(source)
            } else {
                exoPlayer.setMediaItem(mediaItem)
            }

            exoPlayer.seekTo(playbackPosition)
            exoPlayer.playWhenReady = true
            exoPlayer.prepare()
            exoPlayer.addListener(object : Player.Listener {
                override fun onIsLoadingChanged(isLoading: Boolean) {
                    progressBar.visibility = if (isLoading) View.VISIBLE else View.GONE
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_READY) {
                        isPlayerReady = true
                        progressBar.visibility = View.GONE
                        errorTextView.visibility = View.GONE
                        playerView.visibility = View.VISIBLE
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    progressBar.visibility = View.GONE
                    playerView.visibility = View.GONE
                    errorTextView.visibility = View.VISIBLE
                    errorTextView.text = getString(R.string.channel_unavailable)
                }
            })
        }
    }

    private fun lockScreen(lock: Boolean) {
        linearLayoutControlUp.visibility = if (lock) View.INVISIBLE else View.VISIBLE
        linearLayoutControlBottom.visibility = if (lock) View.INVISIBLE else View.VISIBLE
    }

    private fun setLockScreen() {
        imageViewLock.setOnClickListener {
            isLock = !isLock
            imageViewLock.setImageDrawable(ContextCompat.getDrawable(applicationContext, if (isLock) R.drawable.ic_baseline_lock else R.drawable.ic_baseline_lock_open))
            lockScreen(isLock)
        }
    }

    private fun setFullScreen() { imageViewFullScreen.setOnClickListener { hideSystemUi() } }
    override fun onWindowFocusChanged(hasFocus: Boolean) { super.onWindowFocusChanged(hasFocus); if (hasFocus) hideSystemUi() }
    override fun onConfigurationChanged(newConfig: Configuration) { super.onConfigurationChanged(newConfig); hideSystemUi() }
    override fun onSaveInstanceState(outState: Bundle) { super.onSaveInstanceState(outState); player?.let { playbackPosition = it.currentPosition }; outState.putParcelable("channel", channel); outState.putLong("playbackPosition", playbackPosition) }
    override fun onStart() { super.onStart(); if (Util.SDK_INT > 23) player?.playWhenReady = true }
    override fun onResume() { super.onResume(); hideSystemUi(); if (Util.SDK_INT <= 23 || !isPlayerReady) player?.playWhenReady = true }
        // === Priority 1: Safe server sync check during playback (non-destructive) ===
        // Only checks; if changed it shows overlay then returns to Home (does not kill current stream immediately)
        PlayerServerSyncHelper.checkDuringPlayback(this, force = false)
    override fun onPause() { super.onPause(); if (Util.SDK_INT <= 23) player?.let { playbackPosition = it.currentPosition; it.playWhenReady = false } }
    private fun saveResumePosition() {
        if (channel.contentType == "movie" || channel.contentType == "series") {
            player?.let {
                val pos = it.currentPosition
                if (pos > 5000) { // Only save if watched > 5 seconds
                    getSharedPreferences("resume_prefs", MODE_PRIVATE).edit()
                        .putLong("pos_" + channel.streamUrl.hashCode(), pos).apply()
                }
            }
        }
    }
    private fun getResumePosition(): Long {
        if (channel.contentType == "movie" || channel.contentType == "series") {
            return getSharedPreferences("resume_prefs", MODE_PRIVATE)
                .getLong("pos_" + channel.streamUrl.hashCode(), 0L)
        }
        return 0L
    }

    private fun registerPlayerVoiceControls() {
        if (playerControlReceiver != null) return
        playerControlReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val target = intent?.getStringExtra("target") ?: return
                val extra = intent.getStringExtra("extra") ?: ""
                executePlayerControl(target, extra)
            }
        }
        val filter = IntentFilter("com.latchi.iptv.PLAYER_CONTROL")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(playerControlReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(playerControlReceiver, filter)
        }
    }

    private fun executePlayerControl(targetRaw: String, extra: String) {
        val target = targetRaw.lowercase().trim()
        val p = player
        when (target) {
            "pause" -> p?.playWhenReady = false
            "resume", "play" -> p?.playWhenReady = true
            "next_channel", "next" -> if (channelList.isNotEmpty() && currentChannelIndex < channelList.size - 1) playChannel(currentChannelIndex + 1)
            "previous_channel", "prev_channel", "previous", "prev" -> if (channelList.isNotEmpty() && currentChannelIndex > 0) playChannel(currentChannelIndex - 1)
            "seek_forward", "forward" -> {
                val seconds = extra.toLongOrNull() ?: 10L
                p?.seekTo((p.currentPosition + seconds * 1000L).coerceAtMost(if (p.duration > 0) p.duration else Long.MAX_VALUE))
            }
            "seek_back", "rewind" -> {
                val seconds = extra.toLongOrNull() ?: 10L
                p?.seekTo((p.currentPosition - seconds * 1000L).coerceAtLeast(0))
            }
            "volume_up" -> audioManager()?.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
            "volume_down" -> audioManager()?.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
            "mute" -> audioManager()?.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_TOGGLE_MUTE, AudioManager.FLAG_SHOW_UI)
            "fullscreen" -> hideSystemUi()
            "lock" -> { isLock = true; lockScreen(true) }
            "unlock" -> { isLock = false; lockScreen(false) }
        }
        playerView.showController()
    }

    private fun audioManager(): AudioManager? = getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    override fun onStop() { super.onStop(); watermarkHandler?.removeCallbacksAndMessages(null); saveResumePosition(); if (Util.SDK_INT > 23) player?.let { playbackPosition = it.currentPosition; it.playWhenReady = false } }
    override fun onDestroy() { super.onDestroy(); playerControlReceiver?.let { try { unregisterReceiver(it) } catch (_: Exception) {} }; playerControlReceiver = null; player?.release(); player = null }
    @Deprecated("Deprecated in Java") override fun onBackPressed() { if (!isLock) super.onBackPressed() }

    // TV remote / d-pad control for the player
    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent?): Boolean {
        if (isLock) {
            // When locked, ignore everything except unlocking via center
            return super.onKeyDown(keyCode, event)
        }
        val p = player
        when (keyCode) {
            android.view.KeyEvent.KEYCODE_DPAD_CENTER,
            android.view.KeyEvent.KEYCODE_ENTER,
            android.view.KeyEvent.KEYCODE_NUMPAD_ENTER,
            android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                if (p != null) {
                    p.playWhenReady = !p.playWhenReady
                    playerView.showController()
                }
                return true
            }
            android.view.KeyEvent.KEYCODE_DPAD_RIGHT,
            android.view.KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                p?.seekTo((p.currentPosition + 10000).coerceAtMost(if (p.duration > 0) p.duration else Long.MAX_VALUE))
                playerView.showController()
                return true
            }
            android.view.KeyEvent.KEYCODE_DPAD_LEFT,
            android.view.KeyEvent.KEYCODE_MEDIA_REWIND -> {
                p?.seekTo((p.currentPosition - 10000).coerceAtLeast(0))
                playerView.showController()
                return true
            }
            android.view.KeyEvent.KEYCODE_CHANNEL_UP -> {
                if (channelList.isNotEmpty() && currentChannelIndex > 0) { playChannel(currentChannelIndex - 1); return true }
                playerView.showController()
                return true
            }
            android.view.KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                if (channelList.isNotEmpty() && currentChannelIndex < channelList.size - 1) { playChannel(currentChannelIndex + 1); return true }
                playerView.showController()
                return true
            }
            android.view.KeyEvent.KEYCODE_DPAD_UP -> {
                if (channelList.isNotEmpty() && currentChannelIndex > 0) { playChannel(currentChannelIndex - 1); return true }
                playerView.showController()
                return true
            }
            android.view.KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (channelList.isNotEmpty() && currentChannelIndex < channelList.size - 1) { playChannel(currentChannelIndex + 1); return true }
                playerView.showController()
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }
}
