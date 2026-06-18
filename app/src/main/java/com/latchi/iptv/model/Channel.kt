package com.latchi.iptv.model

import android.os.Parcel
import android.os.Parcelable
import org.json.JSONObject

data class Channel(
    val name: String,
    val logoUrl: String,
    val streamUrl: String,
    val category: String = "Other",
    val contentType: String = "live" // live | movie | series
) : Parcelable {
    constructor(parcel: Parcel) : this(
        parcel.readString() ?: "",
        parcel.readString() ?: "",
        parcel.readString() ?: "",
        parcel.readString() ?: "Other",
        parcel.readString() ?: "live"
    )

    fun toJson(): JSONObject {
        return JSONObject()
            .put("name", name)
            .put("logoUrl", logoUrl)
            .put("streamUrl", streamUrl)
            .put("category", category)
            .put("contentType", contentType)
    }

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeString(name)
        parcel.writeString(logoUrl)
        parcel.writeString(streamUrl)
        parcel.writeString(category)
        parcel.writeString(contentType)
    }

    override fun describeContents(): Int = 0

    companion object CREATOR : Parcelable.Creator<Channel> {
        override fun createFromParcel(parcel: Parcel): Channel = Channel(parcel)
        override fun newArray(size: Int): Array<Channel?> = arrayOfNulls(size)

        fun fromJson(json: JSONObject): Channel {
            return Channel(
                name = json.optString("name", ""),
                logoUrl = json.optString("logoUrl", ""),
                streamUrl = json.optString("streamUrl", ""),
                category = json.optString("category", "Other"),
                contentType = json.optString("contentType", "live")
            )
        }
    }
}
