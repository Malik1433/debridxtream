package com.tvonnet.debridxtreamiptv.data.debrid.model

import com.google.gson.annotations.SerializedName

data class TmdbVideoResponse(
    @SerializedName("id") val id: Int? = null,
    @SerializedName("results") val results: List<TmdbVideo>? = null
)

data class TmdbVideo(
    @SerializedName("id") val id: String? = null,
    @SerializedName("key") val key: String? = null,
    @SerializedName("name") val name: String? = null,
    @SerializedName("site") val site: String? = null,
    @SerializedName("type") val type: String? = null,
    @SerializedName("official") val official: Boolean? = null,
    @SerializedName("size") val size: Int? = null
)

