package com.latchi.iptv.screens

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.bumptech.glide.Glide
import com.latchi.iptv.MainActivity
import com.latchi.iptv.R
import com.latchi.iptv.model.Channel
import com.latchi.iptv.utils.FavoritesPrefs
import com.latchi.iptv.utils.SourcePrefs

class MovieDetailActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_CHANNEL = "extra_channel"
        fun start(context: Context, channel: Channel) {
            val intent = Intent(context, MovieDetailActivity::class.java)
            intent.putExtra(EXTRA_CHANNEL, channel)
            context.startActivity(intent)
        }
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(com.latchi.iptv.utils.LocaleHelper.wrap(newBase))
    }

    private lateinit var channel: Channel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        com.latchi.iptv.utils.TvUtils.applyOrientation(this)
        com.latchi.iptv.utils.ThemeManager.apply(this)
        setContentView(R.layout.activity_movie_detail)
        com.latchi.iptv.utils.FloatingBackHelper.setup(this)

        channel = intent.getParcelableExtra(EXTRA_CHANNEL) ?: return finish()

        findViewById<TextView>(R.id.backButton).setOnClickListener { finish() }

        val poster = findViewById<ImageView>(R.id.moviePoster)
        val title = findViewById<TextView>(R.id.movieTitle)
        val category = findViewById<TextView>(R.id.movieCategory)
        val description = findViewById<TextView>(R.id.movieDescription)
        val watchBtn = findViewById<LinearLayout>(R.id.watchButton)
        val favBtn = findViewById<LinearLayout>(R.id.favoriteButton)
        val favIcon = findViewById<ImageView>(R.id.favoriteIcon)
        val favText = findViewById<TextView>(R.id.favoriteText)

        title.text = channel.name
        category.text = channel.category.uppercase()

        description.text = getString(R.string.movie_description, channel.category)

        Glide.with(this)
            .load(channel.logoUrl.ifBlank { null })
            .placeholder(R.drawable.ic_tv)
            .error(R.drawable.ic_tv)
            .into(poster)

        watchBtn.setOnClickListener {
            PlayerActivity.start(this, channel)
        }

        findViewById<LinearLayout>(R.id.homeButton)?.setOnClickListener {
            val homeIntent = Intent(this, MainActivity::class.java)
            homeIntent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            startActivity(homeIntent)
            finish()
        }

        val profileId = SourcePrefs.getActiveProfile(this)?.id
        fun updateFavIcon() {
            val isFav = profileId?.let { FavoritesPrefs.isFavorite(this, it, channel.streamUrl) } ?: false
            favIcon.setImageResource(if (isFav) R.drawable.ic_heart_filled else R.drawable.ic_heart_outline)
            favText.text = if (isFav) getString(R.string.in_favorites) else getString(R.string.add_favorite)
        }
        updateFavIcon()
        favBtn.setOnClickListener {
            if (profileId != null) {
                FavoritesPrefs.toggle(this, profileId, channel.streamUrl)
                updateFavIcon()
                Toast.makeText(this, if (favText.text == getString(R.string.add_favorite)) getString(R.string.added_to_favorites) else getString(R.string.removed_from_favorites), Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent?): Boolean {
        if (com.latchi.iptv.utils.TvFocusHelper.handleKey(this, keyCode, event)) return true
        return super.onKeyDown(keyCode, event)
    }

}
