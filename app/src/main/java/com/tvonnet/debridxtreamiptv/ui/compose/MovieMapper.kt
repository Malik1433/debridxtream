package com.tvonnet.debridxtreamiptv.ui.compose

import com.tvonnet.debridxtreamiptv.data.model.XtreamVodInfo

object MovieMapper {
    fun mapToUiModel(vodInfo: XtreamVodInfo): MovieUiModel {
        val ratingValue = vodInfo.rating?.toFloatOrNull() 
            ?: (vodInfo.rating_5based?.toFloat()?.times(2)) 
            ?: 0f

        return MovieUiModel(
            id = vodInfo.stream_id ?: "",
            title = vodInfo.name ?: "",
            overview = vodInfo.plot ?: "",
            posterUrl = com.tvonnet.debridxtreamiptv.util.GlobalConfig.resolveIconUrl(vodInfo.stream_icon),
            backdropUrl = vodInfo.cover,
            releaseYear = vodInfo.releaseDate?.take(4) ?: "",
            duration = vodInfo.duration ?: "",
            rating = ratingValue,
            genres = vodInfo.genre?.split(",")?.map { it.trim() } ?: emptyList()
        )
    }

    fun mapTmdbToUiModel(details: com.tvonnet.debridxtreamiptv.data.debrid.model.TmdbMovieDetails): MovieUiModel {
        return MovieUiModel(
            id = details.id?.toString() ?: "",
            title = details.title ?: "",
            overview = details.overview ?: "",
            posterUrl = com.tvonnet.debridxtreamiptv.data.debrid.model.TmdbImageUrl.getPosterUrl(details.posterPath),
            backdropUrl = com.tvonnet.debridxtreamiptv.data.debrid.model.TmdbImageUrl.getBackdropUrl(details.backdropPath),
            releaseYear = details.releaseDate?.take(4) ?: "",
            duration = details.runtime?.let { "${it} min" } ?: "",
            rating = details.voteAverage?.toFloat() ?: 0f,
            genres = details.genres?.map { it.name ?: "" } ?: emptyList(),
            pgRating = null // TMDB doesn't provide PG rating directly in this endpoint usually
        )
    }
}
