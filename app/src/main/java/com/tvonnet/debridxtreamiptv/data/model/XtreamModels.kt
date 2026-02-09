package com.tvonnet.debridxtreamiptv.data.model

import com.google.gson.annotations.SerializedName
import com.google.gson.annotations.JsonAdapter
import com.tvonnet.debridxtreamiptv.data.remote.adapter.SeriesEpisodesJsonAdapter

data class XtreamLoginResponse(
    val user_info: XtreamUserInfo?,
    val server_info: XtreamServerInfo?
)

data class XtreamUserInfo(
    val username: String?,
    val password: String?,
    val message: String?,
    val auth: Int?,
    val status: String?,
    val exp_date: String?,
    val is_trial: String?,
    val active_cons: String?,
    val created_at: String?,
    val max_connections: String?,
    val allowed_output_formats: List<String>?
)

data class XtreamServerInfo(
    val url: String?,
    val port: String?,
    val https_port: String?,
    val server_protocol: String?,
    val rtmp_port: String?,
    val timezone: String?,
    val timestamp_now: Long?
)

data class XtreamCategory(
    val category_id: String?,
    val category_name: String?,
    val parent_id: String?
)

data class XtreamStream(
    val num: Int?,
    val name: String?,
    val stream_type: String?,
    val stream_id: String?,
    val stream_icon: String?,
    val epg_channel_id: String?,
    val added: String?,
    val category_id: String?,
    val category_ids: List<Int>?,
    val container_extension: String?,
    val custom_sid: String?,
    val direct_source: String?,
    val tv_archive: Int?,
    val tv_archive_duration: Int?,
    var currentProgramTitle: String? = null
)

data class XtreamVodInfo(
    val num: Int?,
    val name: String?,
    val stream_type: String?,
    val stream_id: String?,
    val stream_icon: String?,
    val rating: String?,
    val rating_5based: Double?,
    val added: String?,
    val category_id: String?,
    val category_ids: List<Int>?,
    val container_extension: String?,
    val custom_sid: String?,
    val direct_source: String?,
    val releaseDate: String?,
    val duration: String?,
    val plot: String?,
    val cast: String?,
    val director: String?,
    val genre: String?,
    val youtube_trailer: String?,
    val cover: String?,
    val rating_imdb: String?
)

data class XtreamSeriesInfo(
    val num: Int?,
    val name: String?,
    val series_id: String?,
    val cover: String?,
    val plot: String?,
    val cast: String?,
    val director: String?,
    val genre: String?,
    val releaseDate: String?,
    val rating: String?,
    val rating_5based: Double?,
    val category_id: String?,
    val category_ids: List<Int>?,
    val backdrop_path: Any?, // Can be List<String> or String
    val youtube_trailer: String?,
    val episodes: Map<String, List<XtreamEpisodeInfo>>?
) {
    val backdropPathList: List<String>
        get() = when (backdrop_path) {
            is List<*> -> backdrop_path.filterIsInstance<String>()
            is String -> if (backdrop_path.isNotEmpty()) listOf(backdrop_path) else emptyList()
            else -> emptyList()
        }
}

data class XtreamEpisodeInfo(
    val id: String?,
    val title: String?,
    val container_extension: String?,
    @JsonAdapter(com.tvonnet.debridxtreamiptv.data.remote.adapter.EpisodeDetailJsonAdapter::class)
    val info: XtreamEpisodeDetail?,
    val stream_type: String?,
    val episode_num: Int? = null,
    val season: Int? = null,
    val duration_secs: String? = null,
    val added: String? = null,
    val custom_sid: String? = null,
    val direct_source: String? = null,
    val series_id: String? = null,
    val thumbnail: String? = null,
    val isWatched: Boolean = false,
    val resumePosition: Long = 0
)

data class XtreamEpisodeDetail(
    val plot: String?,
    val cast: String?,
    val director: String?,
    val genre: String?,
    val releaseDate: String?,
    val rating: String?,
    val duration_secs: String?,
    val cover: String?
)

data class XtreamSeriesDetailInfo(
    val name: String?,
    val series_id: String?,
    val plot: String?,
    val cast: String?,
    val director: String?,
    val genre: String?,
    val releaseDate: String?,
    val rating: String?,
    val rating_5based: Double?,
    val cover: String?,
    val backdrop_path: Any?, // Can be List<String> or String - Fix for JsonSyntaxException
    val youtube_trailer: String?
) {
    /**
     * Helper to get backdrop_path as string (first item if array, or string itself)
     */
    val backdropPathString: String?
        get() = when (backdrop_path) {
            is List<*> -> backdrop_path.filterIsInstance<String>().firstOrNull()
            is String -> if (backdrop_path.isNotEmpty()) backdrop_path else null
            else -> null
        }
}

data class XtreamSeasonInfo(
    val season_number: String?,
    val name: String?,
    val overview: String?,
    val air_date: String?,
    val cover: String?
)



data class XtreamSeriesDetailResponse(
    val info: XtreamSeriesDetailInfo?,
    @JsonAdapter(SeriesEpisodesJsonAdapter::class)
    val episodes: Map<String, List<XtreamEpisodeInfo>>?,
    val seasons: List<XtreamSeasonInfo>?
)

data class EpgProgram(
    val channelId: String,
    val start: Long,
    val stop: Long,
    val title: String?,
    val desc: String?,
    val category: String?
)

