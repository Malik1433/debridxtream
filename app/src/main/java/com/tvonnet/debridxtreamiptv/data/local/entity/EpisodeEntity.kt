package com.tvonnet.debridxtreamiptv.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.tvonnet.debridxtreamiptv.data.model.XtreamEpisodeInfo

@Entity(
    tableName = "episodes",
    foreignKeys = [
        ForeignKey(
            entity = SeriesEntity::class,
            parentColumns = ["seriesId"],
            childColumns = ["seriesId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("seriesId")]
)
data class EpisodeEntity(
    @PrimaryKey val episodeId: String,
    val seriesId: String,
    val seasonNumber: Int,
    val episodeNumber: Int,
    val title: String?,
    val containerExtension: String?,
    val streamType: String?,
    val durationSecs: String?,
    val added: String?,
    val customSid: String?,
    val directSource: String?,
    val thumbnail: String?,
    val plot: String?,
    val cast: String?,
    val director: String?,
    val genre: String?,
    val releaseDate: String?,
    val rating: String?,
    @androidx.room.ColumnInfo(name = "is_watched") val isWatched: Boolean = false,
    @androidx.room.ColumnInfo(name = "resume_position") val resumePosition: Long = 0,
    @androidx.room.ColumnInfo(name = "duration") val duration: Long = 0
)

fun XtreamEpisodeInfo.toEpisodeEntity(seriesId: String, seasonNum: Int): EpisodeEntity {
    return EpisodeEntity(
        episodeId = this.id ?: "",
        seriesId = seriesId,
        seasonNumber = seasonNum,
        episodeNumber = this.episode_num ?: 0,
        title = this.title,
        containerExtension = this.container_extension,
        streamType = this.stream_type,
        durationSecs = this.duration_secs ?: this.info?.duration_secs,
        added = this.added,
        customSid = this.custom_sid,
        directSource = this.direct_source,
        thumbnail = this.thumbnail ?: this.info?.cover,
        plot = this.info?.plot,
        cast = this.info?.cast,
        director = this.info?.director,
        genre = this.info?.genre,
        releaseDate = this.info?.releaseDate,
        rating = this.info?.rating
    )
}

fun EpisodeEntity.toXtreamEpisodeInfo(): com.tvonnet.debridxtreamiptv.data.model.XtreamEpisodeInfo {
    return com.tvonnet.debridxtreamiptv.data.model.XtreamEpisodeInfo(
        id = this.episodeId,
        title = this.title,
        container_extension = this.containerExtension,
        info = com.tvonnet.debridxtreamiptv.data.model.XtreamEpisodeDetail(
            plot = this.plot,
            cast = this.cast,
            director = this.director,
            genre = this.genre,
            releaseDate = this.releaseDate,
            rating = this.rating,
            duration_secs = this.durationSecs,
            cover = this.thumbnail
        ),
        stream_type = this.streamType,
        episode_num = this.episodeNumber,
        season = this.seasonNumber,
        duration_secs = this.durationSecs,
        added = this.added,
        custom_sid = this.customSid,
        direct_source = this.directSource,
        series_id = this.seriesId,
        thumbnail = this.thumbnail,
        isWatched = this.isWatched,
        resumePosition = this.resumePosition
    )
}
