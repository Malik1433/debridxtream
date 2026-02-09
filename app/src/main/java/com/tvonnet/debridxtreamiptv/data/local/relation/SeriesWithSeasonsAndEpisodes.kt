package com.tvonnet.debridxtreamiptv.data.local.relation

import androidx.room.Embedded
import androidx.room.Relation
import com.tvonnet.debridxtreamiptv.data.local.entity.EpisodeEntity
import com.tvonnet.debridxtreamiptv.data.local.entity.SeasonEntity
import com.tvonnet.debridxtreamiptv.data.local.entity.SeriesEntity
import com.tvonnet.debridxtreamiptv.data.local.entity.toXtreamSeasonInfo
import com.tvonnet.debridxtreamiptv.data.local.entity.toXtreamEpisodeInfo

data class SeriesWithSeasonsAndEpisodes(
    @Embedded val series: SeriesEntity,
    
    @Relation(
        parentColumn = "seriesId",
        entityColumn = "seriesId"
    )
    val seasons: List<SeasonEntity>,
    
    @Relation(
        parentColumn = "seriesId",
        entityColumn = "seriesId"
    )
    val episodes: List<EpisodeEntity>
)

fun SeriesWithSeasonsAndEpisodes.toXtreamSeriesDetailResponse(): com.tvonnet.debridxtreamiptv.data.model.XtreamSeriesDetailResponse {
    val detailInfo = com.tvonnet.debridxtreamiptv.data.model.XtreamSeriesDetailInfo(
        name = this.series.name,
        series_id = this.series.seriesId,
        plot = this.series.plot,
        cast = this.series.cast,
        director = this.series.director,
        genre = this.series.genre,
        releaseDate = this.series.releaseDate,
        rating = this.series.rating,
        rating_5based = this.series.rating5based,
        cover = this.series.cover,
        backdrop_path = this.series.backdrop_path.firstOrNull(), // SeriesEntity.backdrop_path is List<String>
        youtube_trailer = this.series.youtube_trailer
    )
    
    val seasonsInfo = this.seasons.map { it.toXtreamSeasonInfo() }
    
    val episodesMap = this.episodes.groupBy { it.seasonNumber.toString() }
        .mapValues { (_, episodes) -> 
            episodes.map { it.toXtreamEpisodeInfo() } 
        }
        
    return com.tvonnet.debridxtreamiptv.data.model.XtreamSeriesDetailResponse(
        info = detailInfo,
        seasons = seasonsInfo,
        episodes = episodesMap
    )
}
