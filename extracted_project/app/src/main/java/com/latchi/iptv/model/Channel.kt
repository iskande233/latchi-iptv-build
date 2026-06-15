package com.latchi.iptv.model

import android.os.Parcel
import android.os.Parcelable

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
    }
}
