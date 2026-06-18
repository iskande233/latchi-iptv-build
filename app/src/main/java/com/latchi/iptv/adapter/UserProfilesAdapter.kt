package com.latchi.iptv.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.latchi.iptv.R
import com.latchi.iptv.utils.IptvProfile
import com.latchi.iptv.utils.TvFocusHelper

class UserProfilesAdapter(
    private var profiles: List<IptvProfile>,
    private var activeId: String? = null,
    private val onOpen: (IptvProfile) -> Unit,
    private val onDelete: (IptvProfile) -> Unit
) : RecyclerView.Adapter<UserProfilesAdapter.ProfileViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ProfileViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_user_profile, parent, false)
        return ProfileViewHolder(view)
    }

    override fun onBindViewHolder(holder: ProfileViewHolder, position: Int) = holder.bind(profiles[position])
    override fun getItemCount(): Int = profiles.size

    fun update(newProfiles: List<IptvProfile>, newActiveId: String?) {
        profiles = newProfiles
        activeId = newActiveId
        notifyDataSetChanged()
    }

    inner class ProfileViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val titleText: TextView = itemView.findViewById(R.id.profileTitle)
        private val codeText: TextView = itemView.findViewById(R.id.profileCode)
        private val activeMarker: TextView = itemView.findViewById(R.id.profileActiveMarker)
        private val deleteButton: ImageView = itemView.findViewById(R.id.deleteButton)

        fun bind(profile: IptvProfile) {
            titleText.text = profile.name
            codeText.text = "Code / Type: ${profile.activationCode}"

            if (profile.id == activeId) {
                activeMarker.visibility = View.VISIBLE
            } else {
                activeMarker.visibility = View.GONE
            }

            itemView.findViewById<View?>(R.id.cardOpenProfile)?.setOnClickListener { onOpen(profile) }
            deleteButton.setOnClickListener { onDelete(profile) }
        }
    }
}
