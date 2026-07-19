package com.tvonnet.debridxtreamiptv.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.tvonnet.debridxtreamiptv.data.model.XtreamSeriesInfo

/**
 * Room Entity for Series
 * Phase 1: Database Infrastructure
 * B-1: categoryId index — the per-category browse filter was a full table scan.
 */
@Entity(tableName = "series_v2", indices = [Index(value = ["categoryId"])])
data class SeriesEntity(
    @PrimaryKey val seriesId: String,
    val name: String?,
    val cover: String?,
    val plot: String?,
    val cast: String?,
    val director: String?,
    val genre: String?,
    val releaseDate: String?,
    val rating: String?,
    val rating5based: Double?,
    val categoryId: String?,
    @androidx.room.ColumnInfo(name = "category_ids") val category_ids: List<String> = emptyList(),
    @androidx.room.ColumnInfo(name = "backdrop_path") val backdrop_path: List<String> = emptyList(),
    @androidx.room.ColumnInfo(name = "youtube_trailer") val youtube_trailer: String? = null,
    val num: Int?,
    val cachedAt: Long = System.currentTimeMillis()
)

/**
 * Extension function to convert XtreamSeriesInfo to SeriesEntity
 */
fun XtreamSeriesInfo.toSeriesEntity(categoryId: String): SeriesEntity {
    return SeriesEntity(
        seriesId = this.series_id ?: "",
        name = this.name,
        cover = this.cover,
        plot = this.plot,
        cast = this.cast,
        director = this.director,
        genre = this.genre,
        releaseDate = this.releaseDate,
        rating = this.rating,
        rating5based = this.rating_5based,
        categoryId = categoryId,
        category_ids = this.category_ids?.map { it.toString() } ?: emptyList(),
        backdrop_path = this.backdropPathList,
        youtube_trailer = this.youtube_trailer,
        num = this.num
    )
}

/**
 * Extension function to convert SeriesEntity to XtreamSeriesInfo
 */
fun SeriesEntity.toXtreamSeriesInfo(): XtreamSeriesInfo {
    return XtreamSeriesInfo(
        num = this.num,
        name = this.name,
        series_id = this.seriesId,
        cover = this.cover,
        plot = this.plot,
        cast = this.cast,
        director = this.director,
        genre = this.genre,
        releaseDate = this.releaseDate,
        rating = this.rating,
        rating_5based = this.rating5based,
        category_id = this.categoryId,
        category_ids = this.category_ids.mapNotNull { it.toIntOrNull() },
        backdrop_path = this.backdrop_path,
        youtube_trailer = this.youtube_trailer,
        episodes = null // Episodes are fetched separately
    )
}
