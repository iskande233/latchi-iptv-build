package com.latchi.iptv.adapter

import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.OvershootInterpolator
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.latchi.iptv.R
import com.latchi.iptv.model.Channel
import com.latchi.iptv.utils.TvFocusHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ChannelsAdapter(
    private var channels: List<Channel>,
    private val isGrid: Boolean = false,
    private val isFavorite: (Channel) -> Boolean,
    private val onFavoriteClicked: (Channel) -> Unit,
    private val onChannelClicked: (Channel) -> Unit
    private var selectedStreamUrl: String? = null
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val VIEW_TYPE_LIST = 1
    private val VIEW_TYPE_GRID = 2
    private var updateGeneration = 0

    override fun getItemViewType(position: Int): Int {
        return if (isGrid) VIEW_TYPE_GRID else VIEW_TYPE_LIST
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return if (viewType == VIEW_TYPE_GRID) {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_poster_card, parent, false)
            TvFocusHelper.setupFocusableItem(view, 1.05f)
            PosterViewHolder(view)
        } else {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_channel, parent, false)
            TvFocusHelper.setupFocusableItem(view, 1.04f)
            ChannelViewHolder(view)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val channel = channels[position]
        if (holder is PosterViewHolder) {
            holder.bind(channel)
        } else if (holder is ChannelViewHolder) {
            holder.bind(channel)
        }
    }

    override fun getItemCount(): Int = channels.size

    /** Highlight the currently playing/previewed channel (used by TvLivePreviewActivity) */
    fun updateSelectedChannel(streamUrl: String) {
    }

    /** Highlight the currently playing/previewed channel (used by TvLivePreviewActivity) */
    }

    fun updateChannels(newChannels: List<Channel>) {
        val generation = ++updateGeneration
        val old = channels

        // القوائم IPTV غالباً كبيرة جداً. DiffUtil على 30k/100k عنصر يقدر يجي متأخر
        // ويغلب فلتر التصنيف الجديد. لذلك نعمل تحديث مباشر للقوائم الكبيرة،
        // ونستعمل generation guard للقوائم الصغيرة.
        if (old.size > 5000 || newChannels.size > 5000) {
            channels = newChannels
            notifyDataSetChanged()
            return
        }

        CoroutineScope(Dispatchers.Default).launch {
            val diffResult = DiffUtil.calculateDiff(ChannelDiffCallback(old, newChannels))
            withContext(Dispatchers.Main) {
                if (generation != updateGeneration) return@withContext
                channels = newChannels
                diffResult.dispatchUpdatesTo(this@ChannelsAdapter)
            }
        }
    }

    // ⚡ ViewHolder for Movies & Series Posters Grid ⚡
    inner class PosterViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val posterImageView: ImageView = itemView.findViewById(R.id.posterImageView)
        private val posterNameTextView: TextView = itemView.findViewById(R.id.posterNameTextView)
        private val posterCatTextView: TextView = itemView.findViewById(R.id.posterCatTextView)

        fun bind(channel: Channel) {
            posterNameTextView.text = channel.name
            posterCatTextView.text = channel.category

            Glide.with(itemView.context)
                .load(channel.logoUrl.ifBlank { null })
                .placeholder(R.drawable.ic_tv)
                .error(R.drawable.ic_tv)
                .into(posterImageView)

            val isTv = itemView.context.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_LEANBACK)
            if (isTv) {
                itemView.isFocusable = true
                itemView.isFocusableInTouchMode = false
                itemView.isClickable = true
                itemView.setOnClickListener { onChannelClicked(channel) }
                itemView.setOnLongClickListener { onFavoriteClicked(channel); true }
            } else {
                itemView.isFocusable = false
                itemView.isFocusableInTouchMode = false
                itemView.isClickable = true
                itemView.setOnClickListener { onChannelClicked(channel) }
                itemView.setOnLongClickListener { onFavoriteClicked(channel); true }
            }
        }
    }

    // ⚡ ViewHolder for Live TV Horizontal Channels List ⚡
    inner class ChannelViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val logoImageView: ImageView = itemView.findViewById(R.id.logoImageView)
        private val nameTextView: TextView = itemView.findViewById(R.id.nameTextView)
        private val categoryTextView: TextView = itemView.findViewById(R.id.categoryTextView)
        private val typeBadgeTextView: TextView = itemView.findViewById(R.id.typeBadgeTextView)
        private val favoriteButton: ImageView = itemView.findViewById(R.id.favoriteButton)

        fun bind(channel: Channel) {
            nameTextView.text = channel.name
            categoryTextView.text = channel.category

            renderFavorite(isFavorite(channel))

            when (channel.contentType) {
                "movie" -> { typeBadgeTextView.text = "MOVIE"; typeBadgeTextView.setBackgroundColor(Color.parseColor("#7C3AED")) }
                "series" -> { typeBadgeTextView.text = "SERIES"; typeBadgeTextView.setBackgroundColor(Color.parseColor("#DB2777")) }
                else -> { typeBadgeTextView.text = "LIVE"; typeBadgeTextView.setBackgroundColor(Color.parseColor("#16A34A")) }
            }

            Glide.with(itemView.context)
                .load(channel.logoUrl.ifBlank { null })
                .placeholder(R.drawable.ic_tv)
                .error(R.drawable.ic_tv)
                .into(logoImageView)

            favoriteButton.setOnClickListener {
                onFavoriteClicked(channel)
                val nowFav = isFavorite(channel)
                renderFavorite(nowFav)
                animateHeart(favoriteButton, nowFav)
            }

            val isTv = itemView.context.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_LEANBACK)

            if (isTv) {
                itemView.isFocusable = true
                itemView.isFocusableInTouchMode = false
                itemView.isClickable = true
                itemView.setOnClickListener { onChannelClicked(channel) }
                itemView.setOnLongClickListener { performFavoriteToggle(channel); true }
            } else {
                itemView.isFocusable = false
                itemView.isFocusableInTouchMode = false
                itemView.isClickable = true
                itemView.setOnClickListener { onChannelClicked(channel) }
                itemView.setOnLongClickListener { performFavoriteToggle(channel); true }
            }
        }

        private fun performFavoriteToggle(channel: Channel) {
            onFavoriteClicked(channel)
            val nowFav = isFavorite(channel)
            renderFavorite(nowFav)
            animateHeart(favoriteButton, nowFav)
            val msg = if (nowFav) itemView.context.getString(R.string.added_to_favorites)
                      else itemView.context.getString(R.string.removed_from_favorites)
            Toast.makeText(itemView.context, msg, Toast.LENGTH_SHORT).show()
        }

        private fun renderFavorite(fav: Boolean) {
            favoriteButton.setImageResource(if (fav) R.drawable.ic_heart_filled else R.drawable.ic_heart_outline)
        }

        private fun animateHeart(view: View, added: Boolean) {
            val peak = if (added) 1.6f else 1.3f
            view.animate().cancel()
            view.scaleX = 0.7f; view.scaleY = 0.7f
            view.animate().scaleX(peak).scaleY(peak).setDuration(140).setInterpolator(OvershootInterpolator(3f)).withEndAction {
                view.animate().scaleX(1f).scaleY(1f).setDuration(140).start()
            }.start()
        }
    }

    private class ChannelDiffCallback(private val oldList: List<Channel>, private val newList: List<Channel>) : DiffUtil.Callback() {
        override fun getOldListSize(): Int = oldList.size
        override fun getNewListSize(): Int = newList.size
        override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean = oldList[oldItemPosition].streamUrl == newList[newItemPosition].streamUrl
        override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean = oldList[oldItemPosition] == newList[newItemPosition]
    }
}
