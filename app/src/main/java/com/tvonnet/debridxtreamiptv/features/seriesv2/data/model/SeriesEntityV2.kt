package com.tvonnet.debridxtreamiptv.features.seriesv2.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * V2 Entity for Series data.
 * Table: series_v2_core
 * Part of the V2 Rescue Patch.
 */
@Entity(tableName = "series_v2_core")
data class SeriesEntityV2(
    @PrimaryKey
    @ColumnInfo(name = "series_id")
    val seriesId: String,

    @ColumnInfo(name = "num")
    val num: Int?,

    @ColumnInfo(name = "name")
    val name: String?,

    @ColumnInfo(name = "title")
    val title: String?,

    @ColumnInfo(name = "year")
    val year: String?,

    @ColumnInfo(name = "stream_type")
    val streamType: String?,

    @ColumnInfo(name = "cover")
    val cover: String?,

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

    @ColumnInfo(name = "rating_5based")
    val rating5based: Double?,

    @ColumnInfo(name = "backdrop_path")
    val backdropPath: List<String> = emptyList(),

    @ColumnInfo(name = "youtube_trailer")
    val youtubeTrailer: String?,

    @ColumnInfo(name = "episode_run_time")
    val episodeRunTime: String?,

    @ColumnInfo(name = "category_id")
    val categoryId: String?,
    
    // For V2 we might store list of category IDs if needed, but usually flattened or relational.
    // Keeping simple for now based on typical API response.
    
    @ColumnInfo(name = "last_modified")
    val lastModified: String?,

    // Internal fields for caching
    @ColumnInfo(name = "cached_at")
    val cachedAt: Long = System.currentTimeMillis()
)
