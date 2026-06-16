package com.latchi.iptv.adapter

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.OvershootInterpolator
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.latchi.iptv.R
import com.latchi.iptv.utils.TvFocusHelper

class CategoryGridAdapter(
    private var categories: List<CategoryItem>,
    private val onSelect: (CategoryItem) -> Unit,
    private val onLongSelect: ((CategoryItem) -> Unit)? = null
) : RecyclerView.Adapter<CategoryGridAdapter.CatViewHolder>() {

    data class CategoryItem(val name: String, val count: Int)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CatViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_category_grid, parent, false)
        TvFocusHelper.setupFocusableItem(view)
        return CatViewHolder(view)
    }

    override fun onBindViewHolder(holder: CatViewHolder, position: Int) = holder.bind(categories[position])
    override fun getItemCount(): Int = categories.size

    fun update(newList: List<CategoryItem>) {
        categories = newList
        notifyDataSetChanged()
    }

    inner class CatViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val iconImg: ImageView = itemView.findViewById(R.id.catGridIconImg)
        private val nameText: TextView = itemView.findViewById(R.id.catGridName)
        private val countText: TextView = itemView.findViewById(R.id.catGridCount)

        fun bind(item: CategoryItem) {
            val prefs = itemView.context.getSharedPreferences("pinned_categories", Context.MODE_PRIVATE)
            val profileId = com.latchi.iptv.utils.SourcePrefs.getActiveProfile(itemView.context)?.id ?: ""
            val pinned = prefs.getString("pinned_${profileId}", "") ?: ""
            val isPinned = pinned == item.name && item.name != "All" && item.name != "Favorites"

            nameText.text = if (isPinned) "📌 ${item.name}" else (if (item.name == "All") "كل القنوات" else if (item.name == "Favorites") "⭐ المفضلة" else item.name)
            nameText.isSelected = true
            nameText.setHorizontallyScrolling(true)
            countText.text = if (item.count < 0) "فتح سريع" else "${item.count} قناة"

            itemView.setOnLongClickListener {
                onLongSelect?.invoke(item)
                true
            }

            val lower = item.name.lowercase()
            val iconRes = when {
                lower.contains("world cup") || lower.contains("كأس العالم") -> R.drawable.ic_cat_trophy
                lower.contains("bein") -> R.drawable.ic_cat_football
                lower.contains("sport") || lower.contains("ssc") || lower.contains("alkass") || lower.contains("ad sport") -> R.drawable.ic_cat_sports
                lower.contains("movie") || lower.contains("film") || lower.contains("أفلام") || lower.contains("افلام") -> R.drawable.ic_cat_movie
                lower.contains("series") || lower.contains("مسلسل") -> R.drawable.ic_cat_series
                lower.contains("kid") || lower.contains("أطفال") || lower.contains("اطفال") || lower.contains("cartoon") -> R.drawable.ic_cat_kids
                lower.contains("news") || lower.contains("أخبار") || lower.contains("اخبار") -> R.drawable.ic_cat_news
                lower.contains("music") || lower.contains("موسيقى") || lower.contains("أغاني") -> R.drawable.ic_cat_music
                lower.contains("islam") || lower.contains("quran") || lower.contains("إسلام") || lower.contains("قرآن") || lower.contains("مجد") -> R.drawable.ic_cat_mosque
                else -> R.drawable.ic_cat_globe
            }

            iconImg.setImageResource(iconRes)

            itemView.setOnClickListener {
                // 🌪 Elastic Spring Animation Effect 🌪
                itemView.animate().cancel()
                itemView.scaleX = 0.9f
                itemView.scaleY = 0.9f
                itemView.animate()
                    .scaleX(1.05f).scaleY(1.05f)
                    .setDuration(160)
                    .setInterpolator(OvershootInterpolator(4f))
                    .withEndAction {
                        itemView.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
                        onSelect(item)
                    }
                    .start()
            }
        }
    }
}
