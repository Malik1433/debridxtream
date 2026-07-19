package com.tvonnet.debridxtreamiptv.features.seriesv2.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/**
 * V2 Entity for Episode data.
 * Table: episodes_v2_core
 * Part of the V2 Rescue Patch.
 */
@Entity(
    tableName = "episodes_v2_core",
    // Composite PK (series_id, episode_id): the SAME Xtream episode stream id can legitimately
    // belong to more than one series listing — a show is listed as separate series_ids per
    // category, and the multi-source panel (plus sibling-episode adoption) stores a sibling's
    // episodes under another series' id so each category can resolve its own stream. With
    // episode_id alone as PK, REPLACE-on-conflict flipped a row's series_id to whichever write
    // came last, so getEpisode(thatSeriesId, …) returned null and "select any category → won't
    // play from there". The composite key lets both rows coexist.
    primaryKeys = ["series_id", "episode_id"],
    foreignKeys = [
        ForeignKey(
            entity = SeriesEntityV2::class,
            parentColumns = ["series_id"],
            childColumns = ["series_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("series_id")]
)
data class EpisodeEntityV2(
    @ColumnInfo(name = "episode_id")
    val episodeId: String,

    @ColumnInfo(name = "series_id")
    val seriesId: String,

    @ColumnInfo(name = "season_number")
    val seasonNumber: Int,

    @ColumnInfo(name = "episode_number")
    val episodeNumber: Int,

    @ColumnInfo(name = "title")
    val title: String?,

    @ColumnInfo(name = "container_extension")
    val containerExtension: String?,

    @ColumnInfo(name = "stream_type")
    val streamType: String?,

    @ColumnInfo(name = "duration_secs")
    val durationSecs: String?, // Keeping as String to match raw API, can vary

    @ColumnInfo(name = "added")
    val added: String?,

    @ColumnInfo(name = "custom_sid")
    val customSid: String?,

    @ColumnInfo(name = "direct_source")
    val directSource: String?,

    @ColumnInfo(name = "thumbnail")
    val thumbnail: String?,

    @ColumnInfo(name = "plot")
    val plot: String?,

    @ColumnInfo(name = "cast")
    val cast: String?,

    @ColumnInfo(name = "director")
    val director: String?,

    @ColumnInfo(name = "genre")
    val genre: String?,

    @ColumnInfo(name = "release_date")
    val releaseDate: String?,

    @ColumnInfo(name = "rating")
    val rating: String?,

    // Playback State (V2 specific handling might differ later, but keeping schema standard for now)
    @ColumnInfo(name = "is_watched")
    val isWatched: Boolean = false,

    @ColumnInfo(name = "resume_position")
    val resumePosition: Long = 0,

    @ColumnInfo(name = "duration")
    val duration: Long = 0,
    
    @ColumnInfo(name = "cached_at")
    val cachedAt: Long = System.currentTimeMillis()
)
