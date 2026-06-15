package com.latchi.iptv.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.latchi.iptv.R
import com.latchi.iptv.utils.TvFocusHelper
import com.latchi.iptv.utils.XtreamHelper

class EpisodeAdapter(
    private val episodes: List<XtreamHelper.Episode>,
    private val onEpisodeClicked: (XtreamHelper.Episode) -> Unit
) : RecyclerView.Adapter<EpisodeAdapter.VH>() {

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        val number: TextView = view.findViewById(R.id.episodeNumber)
        val title: TextView = view.findViewById(R.id.episodeTitle)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_episode, parent, false)
        TvFocusHelper.setupFocusableItem(view)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val ep = episodes[position]
        val ctx = holder.itemView.context
        holder.number.text = "${ctx.getString(R.string.episode_prefix)} ${ep.episodeNum}"
        holder.title.text = ep.title
        holder.itemView.setOnClickListener { onEpisodeClicked(ep) }
    }

    override fun getItemCount(): Int = episodes.size
}
