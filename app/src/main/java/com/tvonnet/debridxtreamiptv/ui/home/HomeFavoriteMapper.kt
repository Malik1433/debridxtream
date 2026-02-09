package com.tvonnet.debridxtreamiptv.ui.home

import com.tvonnet.debridxtreamiptv.data.local.entity.FavoriteEntity
import com.tvonnet.debridxtreamiptv.data.model.ContentType
import com.tvonnet.debridxtreamiptv.data.model.FavoriteItem
import com.tvonnet.debridxtreamiptv.data.repository.XtreamRepository

object HomeFavoriteMapper {

    fun mapToFavoriteItem(
        entity: FavoriteEntity,
        repository: XtreamRepository,
        serverUrl: String?,
        username: String?,
        password: String?
    ): FavoriteItem? {
        val type = entity.type.lowercase()
        val finalServerUrl = serverUrl?.trimEnd('/') ?: ""

        return when (type) {
            "live" -> {
                val stream = repository.getLiveStreamById(entity.streamId)
                val poster = stream?.stream_icon ?: entity.iconUrl
                val streamUrl = if (stream != null && !serverUrl.isNullOrBlank()) {
                    repository.buildLiveStreamUrl(stream, serverUrl)
                } else if (!serverUrl.isNullOrBlank() && !username.isNullOrBlank() && !password.isNullOrBlank()) {
                    "$finalServerUrl/live/$username/$password/${entity.streamId}.ts"
                } else {
                    null
                }
                FavoriteItem(
                    contentId = entity.streamId,
                    contentType = ContentType.LIVE_TV,
                    title = stream?.name ?: entity.name,
                    posterUrl = poster,
                    backdropUrl = stream?.stream_icon ?: poster,
                    addedTimestamp = entity.addedAt,
                    streamUrl = streamUrl
                )
            }

            "vod" -> {
                val vod = repository.getVodById(entity.streamId)
                val poster = vod?.stream_icon ?: entity.iconUrl
                val backdrop = vod?.cover ?: poster
                val streamUrl = if (vod != null && !serverUrl.isNullOrBlank()) {
                    repository.buildVodStreamUrl(vod, serverUrl)
                } else if (!serverUrl.isNullOrBlank() && !username.isNullOrBlank() && !password.isNullOrBlank()) {
                    "$finalServerUrl/movie/$username/$password/${entity.streamId}.mp4"
                } else {
                    null
                }
                FavoriteItem(
                    contentId = entity.streamId,
                    contentType = ContentType.MOVIE,
                    title = vod?.name ?: entity.name,
                    posterUrl = poster,
                    backdropUrl = backdrop,
                    addedTimestamp = entity.addedAt,
                    streamUrl = streamUrl
                )
            }

            "series" -> {
                val series = repository.getSeriesById(entity.streamId)
                val artwork = series?.cover ?: entity.iconUrl
                FavoriteItem(
                    contentId = entity.streamId,
                    contentType = ContentType.SERIES,
                    title = series?.name ?: entity.name,
                    posterUrl = artwork,
                    backdropUrl = artwork,
                    addedTimestamp = entity.addedAt,
                    streamUrl = null
                )
            }

            else -> null
        }
    }
}
