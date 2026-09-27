package com.tvonnet.debridxtreamiptv.data.model

import com.google.gson.annotations.SerializedName

data class XtreamShortEpgResponse(
    @SerializedName("epg_listings") val listings: List<XtreamEpgListing>? = null
)

data class XtreamEpgListing(
    @SerializedName("title") val title: String? = null,
    @SerializedName("description") val description: String? = null,
    @SerializedName("start_timestamp") val startTimestamp: String? = null,
    @SerializedName("stop_timestamp") val stopTimestamp: String? = null,
    /** get_simple_data_table only: 1 when the provider kept a recording of this programme. */
    @SerializedName("has_archive") val hasArchive: Int? = null
)

