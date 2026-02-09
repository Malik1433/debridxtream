package com.tvonnet.debridxtreamiptv.data.debrid.model

import com.google.gson.annotations.SerializedName

/**
 * =========================
 * Generic Add-on Definition
 * =========================
 */
data class AddonDefinition(
    @SerializedName("name") val name: String,
    @SerializedName("type") val type: String,
    @SerializedName("imdbType") val imdbType: String?,
    @SerializedName("itemType") val itemType: String?,
    @SerializedName("infoHashTargetKey") val infoHashTargetKey: String? = null,
    @SerializedName("torrentNameTargetKey") val torrentNameTargetKey: String? = null,
    @SerializedName("sizeAttr") val sizeAttr: String? = null,
    @SerializedName("jsonResultsKey") val jsonResultsKey: String? = null,
    @SerializedName("searchPattern") val searchPattern: AddonSearchPattern? = null,
    @SerializedName("url") val urlTemplate: String
)

data class AddonSearchPattern(
    @SerializedName("movie") val movie: String?,
    @SerializedName("tv") val tv: String?
)

/**
 * =======================
 * MediaFusion JSON Models
 * =======================
 */
data class MediaFusionResponse(
    @SerializedName("streams") val streams: List<MediaFusionStream> = emptyList()
)

data class MediaFusionStream(
    @SerializedName("name") val name: String?,
    @SerializedName("title") val title: String?,
    @SerializedName("description") val description: String?,
    @SerializedName("type") val type: String?,
    @SerializedName("url") val url: String?,
    @SerializedName("infoHash") val infoHash: String?,
    @SerializedName("magnet") val magnet: String?,
    @SerializedName("behaviorHints") val behaviorHints: MediaFusionBehaviorHints? = null,
    @SerializedName("sources") val sources: List<String>? = null,
    @SerializedName("videoSize") val videoSize: Long? = null,
    @SerializedName("size") val sizeBytes: Long? = null,
    @SerializedName("seeders") val seeders: Int? = null,
    @SerializedName("peers") val peers: Int? = null,
    @SerializedName("quality") val quality: String? = null,
    @SerializedName("languages") val languages: List<String>? = null,
    @SerializedName("subtitles") val subtitles: List<String>? = null
)

data class MediaFusionBehaviorHints(
    @SerializedName("filename") val filename: String?,
    @SerializedName("videoSize") val videoSize: Long?,
    @SerializedName("videoQuality") val videoQuality: String?,
    @SerializedName("videoCodec") val videoCodec: String?,
    @SerializedName("duration") val durationSeconds: Long?,
    @SerializedName("videoFps") val videoFps: Double?,
    @SerializedName("audioChannels") val audioChannels: Int?,
    @SerializedName("proxyHeaders") val proxyHeaders: Map<String, String>? = null,
    @SerializedName("headers") val headers: Map<String, String>? = null
)

/**
 * ===================
 * Zilean JSON Models
 * ===================
 */
data class ZileanStream(
    @SerializedName("info_hash") val infoHash: String?,
    @SerializedName("raw_title") val rawTitle: String?,
    @SerializedName("title") val title: String?,
    @SerializedName("size") val sizeBytes: Long?,
    @SerializedName("seeders") val seeders: Int?,
    @SerializedName("peers") val peers: Int?,
    @SerializedName("languages") val languages: List<String>? = null,
    @SerializedName("audio_languages") val audioLanguages: List<String>? = null,
    @SerializedName("subtitles") val subtitles: List<String>? = null,
    @SerializedName("quality") val quality: String? = null,
    @SerializedName("magnet") val magnet: String? = null
)

/**
 * =========================
 * Aggregated Stream Model(s)
 * =========================
 */
data class AddonStream(
    val source: AddonSourceType,
    val title: String?,
    val url: String? = null,
    val infoHash: String?,
    val magnet: String?,
    val sizeBytes: Long?,
    val seeders: Int?,
    val peers: Int?,
    val quality: String?,
    val languages: List<String>?,
    val subtitles: List<String>?,
    val extras: Map<String, Any?> = emptyMap(),
    val headers: Map<String, String>? = null
)

enum class AddonSourceType {
    MEDIA_FUSION,
    ZILEAN,
    TORRENTIO,
    UNKNOWN
}


