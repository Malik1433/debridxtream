package com.tvonnet.debridxtreamiptv.ui.home

import com.tvonnet.debridxtreamiptv.data.local.entity.FavoriteEntity
import com.tvonnet.debridxtreamiptv.data.model.ContentType
import com.tvonnet.debridxtreamiptv.data.model.FavoriteItem
import com.tvonnet.debridxtreamiptv.data.model.toAbsoluteUrl
import com.tvonnet.debridxtreamiptv.data.repository.XtreamRepository

object HomeFavoriteMapper {

    suspend fun mapToFavoriteItem(
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
                val rawPoster = stream?.stream_icon ?: entity.iconUrl
                val absolutePoster = rawPoster.toAbsoluteUrl(ContentType.LIVE_TV, finalServerUrl)
                
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
                    posterUrl = absolutePoster,
                    backdropUrl = absolutePoster,
                    addedTimestamp = entity.addedAt,
                    streamUrl = streamUrl
                )
            }

            "vod" -> {
                val vod = repository.getVodById(entity.streamId)
                val rawPoster = vod?.stream_icon ?: entity.iconUrl
                val rawBackdrop = vod?.cover ?: rawPoster
                
                val absolutePoster = rawPoster.toAbsoluteUrl(ContentType.MOVIE, finalServerUrl)
                val absoluteBackdrop = rawBackdrop.toAbsoluteUrl(ContentType.MOVIE, finalServerUrl)
                
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
                    posterUrl = absolutePoster,
                    backdropUrl = absoluteBackdrop,
                    addedTimestamp = entity.addedAt,
                    streamUrl = streamUrl
                )
            }

            "series" -> {
                val series = repository.getSeriesById(entity.streamId)
                val rawArtwork = series?.cover ?: entity.iconUrl
                val absoluteArtwork = rawArtwork.toAbsoluteUrl(ContentType.SERIES, finalServerUrl)
                
                FavoriteItem(
                    contentId = entity.streamId,
                    contentType = ContentType.SERIES,
                    title = series?.name ?: entity.name,
                    posterUrl = absoluteArtwork,
                    backdropUrl = absoluteArtwork,
                    addedTimestamp = entity.addedAt,
                    streamUrl = null
                )
            }

            else -> null
        }
    }
}
