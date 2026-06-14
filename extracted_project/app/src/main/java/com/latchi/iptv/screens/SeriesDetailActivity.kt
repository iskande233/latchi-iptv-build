package com.latchi.iptv.screens

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.latchi.iptv.R
import com.latchi.iptv.adapter.EpisodeAdapter
import com.latchi.iptv.adapter.SeasonAdapter
import com.latchi.iptv.model.Channel
import com.latchi.iptv.utils.TvUtils
import com.latchi.iptv.utils.XtreamHelper

class SeriesDetailActivity : AppCompatActivity() {

    private lateinit var series: Channel
    private lateinit var m3uUrl: String

    private var allEpisodes: List<XtreamHelper.Episode> = emptyList()
    private var seasonsList = emptyList<Int>()

    companion object {
        private const val EXTRA_SERIES = "extra_series"
        private const val EXTRA_M3U = "extra_m3u"

        fun start(context: Context, series: Channel, m3uUrl: String) {
            val intent = Intent(context, SeriesDetailActivity::class.java).apply {
                putExtra(EXTRA_SERIES, series)
                putExtra(EXTRA_M3U, m3uUrl)
            }
            context.startActivity(intent)
        }
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(com.latchi.iptv.utils.LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        TvUtils.applyOrientation(this)
        com.latchi.iptv.utils.ThemeManager.apply(this)
        setContentView(R.layout.activity_series_detail)
        com.latchi.iptv.utils.FloatingBackHelper.setup(this)

        series = intent.getParcelableExtra(EXTRA_SERIES) ?: return finish()
        m3uUrl = intent.getStringExtra(EXTRA_M3U) ?: return finish()

        findViewById<TextView>(R.id.backButton).setOnClickListener { finish() }
        findViewById<TextView>(R.id.seriesTitle).text = series.name

        val poster = findViewById<ImageView>(R.id.seriesPoster)
        val category = findViewById<TextView>(R.id.seriesCategory)
        val description = findViewById<TextView>(R.id.seriesDescription)

        category.text = series.category.uppercase()
        description.text = getString(R.string.series_description, series.category)

        Glide.with(this)
            .load(series.logoUrl.ifBlank { null })
            .placeholder(R.drawable.ic_tv)
            .error(R.drawable.ic_tv)
            .into(poster)

        val seasonsRecycler = findViewById<RecyclerView>(R.id.seasonsRecycler)
        val episodesRecycler = findViewById<RecyclerView>(R.id.episodesRecycler)

        seasonsRecycler.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        com.latchi.iptv.utils.TvFocusHelper.setupRecycler(seasonsRecycler)
        com.latchi.iptv.utils.TvFocusHelper.setupRecycler(episodesRecycler)
        episodesRecycler.layoutManager = if (TvUtils.isTv(this)) {
            androidx.recyclerview.widget.GridLayoutManager(this, 3)
        } else {
            LinearLayoutManager(this)
        }

        loadEpisodes()
    }

    private fun loadEpisodes() {
        val creds = XtreamHelper.parseCreds(m3uUrl)
        val seriesId = XtreamHelper.seriesIdFromMarker(series.streamUrl)
        if (creds == null || seriesId == null) {
            Toast.makeText(this, getString(R.string.series_not_available), Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        Toast.makeText(this, getString(R.string.loading_episodes), Toast.LENGTH_SHORT).show()
        Thread {
            val episodes = try {
                XtreamHelper.fetchEpisodes(creds, seriesId)
            } catch (e: Exception) {
                emptyList()
            }
            runOnUiThread {
                if (episodes.isEmpty()) {
                    Toast.makeText(this, getString(R.string.no_episodes), Toast.LENGTH_SHORT).show()
                    finish()
                    return@runOnUiThread
                }
                allEpisodes = episodes
                seasonsList = episodes.map { it.season }.distinct().sorted()
                setupSeasons()
                selectSeason(seasonsList.firstOrNull() ?: 0)
                com.latchi.iptv.utils.TvFocusHelper.setup(this)
            }
        }.start()
    }

    private fun setupSeasons() {
        val recycler = findViewById<RecyclerView>(R.id.seasonsRecycler)
        recycler.adapter = SeasonAdapter(seasonsList) { season ->
            selectSeason(season)
        }
    }

    private fun selectSeason(season: Int) {
        val filtered = allEpisodes.filter { it.season == season }
        val creds = XtreamHelper.parseCreds(m3uUrl) ?: return
        val recycler = findViewById<RecyclerView>(R.id.episodesRecycler)
        recycler.adapter = EpisodeAdapter(filtered) { episode ->
            val url = XtreamHelper.episodeUrl(creds, episode)
            val playable = Channel(
                name = "${series.name} - ${episode.title}",
                logoUrl = series.logoUrl,
                streamUrl = url,
                category = series.category,
                contentType = "series"
            )
            PlayerActivity.start(this, playable)
        }
    }

    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent?): Boolean {
        if (com.latchi.iptv.utils.TvFocusHelper.handleKey(this, keyCode, event)) return true
        return super.onKeyDown(keyCode, event)
    }

}
