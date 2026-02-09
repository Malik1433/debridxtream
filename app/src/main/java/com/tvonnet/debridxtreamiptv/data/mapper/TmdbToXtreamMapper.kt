package com.tvonnet.debridxtreamiptv.data.mapper

import com.tvonnet.debridxtreamiptv.data.debrid.model.TmdbEpisode
import com.tvonnet.debridxtreamiptv.data.debrid.model.TmdbSeasonDetails
import com.tvonnet.debridxtreamiptv.data.debrid.model.TmdbTvShowDetails
import com.tvonnet.debridxtreamiptv.data.model.XtreamEpisodeDetail
import com.tvonnet.debridxtreamiptv.data.model.XtreamEpisodeInfo
import com.tvonnet.debridxtreamiptv.data.model.XtreamSeasonInfo
import com.tvonnet.debridxtreamiptv.data.model.XtreamSeriesDetailInfo
import com.tvonnet.debridxtreamiptv.data.model.XtreamSeriesDetailResponse

object TmdbToXtreamMapper {

    fun mapToXtreamSeriesDetail(
        tmdbDetails: TmdbTvShowDetails,
        seasonsDetails: List<TmdbSeasonDetails>
    ): XtreamSeriesDetailResponse {
        
        val info = XtreamSeriesDetailInfo(
            name = tmdbDetails.name,
            series_id = tmdbDetails.id?.toString(),
            cover = "https://image.tmdb.org/t/p/w500${tmdbDetails.posterPath}",
            plot = tmdbDetails.overview,
            cast = "", 
            director = "",
            genre = tmdbDetails.genres?.joinToString(", ") { it.name ?: "" },
            releaseDate = tmdbDetails.firstAirDate,
            rating = tmdbDetails.voteAverage?.toString(),
            rating_5based = (tmdbDetails.voteAverage?.div(2)) ?: 0.0,
            backdrop_path = "https://image.tmdb.org/t/p/original${tmdbDetails.backdropPath}",
            youtube_trailer = ""
        )

        val seasons = tmdbDetails.seasons?.map { tmdbSeason ->
            XtreamSeasonInfo(
                air_date = tmdbSeason.airDate,
                name = tmdbSeason.name,
                overview = tmdbSeason.overview,
                season_number = tmdbSeason.seasonNumber?.toString(),
                cover = "https://image.tmdb.org/t/p/w500${tmdbSeason.posterPath}"
            )
        } ?: emptyList()

        val episodes = mutableMapOf<String, List<XtreamEpisodeInfo>>()
        
        seasonsDetails.forEach { seasonDetail ->
            val seasonKey = seasonDetail.seasonNumber?.toString() ?: return@forEach
            val episodeList = seasonDetail.episodes?.map { tmdbEpisode ->
                XtreamEpisodeInfo(
                    id = tmdbEpisode.id?.toString(),
                    episode_num = tmdbEpisode.episodeNumber,
                    title = tmdbEpisode.name,
                    container_extension = "mp4",
                    info = XtreamEpisodeDetail(
                        cover = "https://image.tmdb.org/t/p/w500${tmdbEpisode.stillPath}",
                        plot = tmdbEpisode.overview,
                        releaseDate = tmdbEpisode.airDate,
                        rating = tmdbEpisode.voteAverage?.toString(),
                        duration_secs = (tmdbEpisode.runtime?.times(60))?.toString(),
                        cast = "",
                        director = "",
                        genre = ""
                    ),
                    custom_sid = "",
                    added = "",
                    season = seasonDetail.seasonNumber,
                    direct_source = "",
                    stream_type = "movie" // Defaulting to movie/video type
                )
            } ?: emptyList()
            episodes[seasonKey] = episodeList
        }

        return XtreamSeriesDetailResponse(
            info = info,
            seasons = seasons,
            episodes = episodes
        )
    }
}
