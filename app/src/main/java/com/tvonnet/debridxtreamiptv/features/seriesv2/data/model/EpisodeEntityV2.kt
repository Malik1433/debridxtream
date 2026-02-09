package com.tvonnet.debridxtreamiptv.features.seriesv2.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * V2 Entity for Episode data.
 * Table: episodes_v2_core
 * Part of the V2 Rescue Patch.
 */
@Entity(
    tableName = "episodes_v2_core",
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
    @PrimaryKey
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
