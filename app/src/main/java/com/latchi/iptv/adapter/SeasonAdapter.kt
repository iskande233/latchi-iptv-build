package com.latchi.iptv.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.latchi.iptv.R
import com.latchi.iptv.utils.TvFocusHelper

class SeasonAdapter(
    private val seasons: List<Int>,
    private val onSeasonClicked: (Int) -> Unit
) : RecyclerView.Adapter<SeasonAdapter.VH>() {

    private var selectedSeason: Int = seasons.firstOrNull() ?: -1

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        val text: TextView = view.findViewById(R.id.seasonText)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_season, parent, false)
        TvFocusHelper.setupFocusableItem(view)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val season = seasons[position]
        val ctx = holder.itemView.context
        holder.text.text = if (season > 0) {
            "${ctx.getString(R.string.season_prefix)} $season"
        } else {
            ctx.getString(R.string.season_prefix)
        }
        val isSelected = season == selectedSeason
        holder.itemView.setBackgroundResource(
            if (isSelected) R.drawable.bg_button_primary else R.drawable.bg_panel_focusable
        )
        holder.itemView.setOnClickListener {
            selectedSeason = season
            notifyDataSetChanged()
            onSeasonClicked(season)
        }
    }

    override fun getItemCount(): Int = seasons.size
}
