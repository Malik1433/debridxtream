package com.tvonnet.debridxtreamiptv.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.tvonnet.debridxtreamiptv.data.model.XtreamVodInfo

/**
 * Room Entity for VOD Movies
 * Phase 1: Database Infrastructure
 * B-1: categoryId index — the per-category browse filter was a full table scan.
 */
@Entity(tableName = "vod_v2", indices = [Index(value = ["categoryId"])])
data class VodEntity(
    @PrimaryKey val streamId: String,
    val name: String?,
    val streamIcon: String?,
    val rating: String?,
    val rating5based: Double?,
    val added: String?,
    val categoryId: String?,
    @androidx.room.ColumnInfo(name = "category_ids") val category_ids: List<String> = emptyList(),
    val containerExtension: String?,
    val customSid: String?,
    val directSource: String?,
    val releaseDate: String?,
    val duration: String?,
    val plot: String?,
    val cast: String?,
    val director: String?,
    val genre: String?,
    val youtubeTrailer: String?,
    val cover: String?,
    val ratingImdb: String?,
    val num: Int?,
    val cachedAt: Long = System.currentTimeMillis()
)

/**
 * Extension function to convert XtreamVodInfo to VodEntity
 */
fun XtreamVodInfo.toVodEntity(categoryId: String): VodEntity {
    return VodEntity(
        streamId = this.stream_id ?: "",
        name = this.name,
        streamIcon = this.stream_icon,
        rating = this.rating,
        rating5based = this.rating_5based,
        added = this.added,
        categoryId = categoryId, // We explicitly store the category ID we fetched it for
        category_ids = this.category_ids?.map { it.toString() } ?: emptyList(),
        containerExtension = this.container_extension,
        customSid = this.custom_sid,
        directSource = this.direct_source,
        releaseDate = this.releaseDate,
        duration = this.duration,
        plot = this.plot,
        cast = this.cast,
        director = this.director,
        genre = this.genre,
        youtubeTrailer = this.youtube_trailer,
        cover = this.cover,
        ratingImdb = this.rating_imdb,
        num = this.num
    )
}

/**
 * Extension function to convert VodEntity to XtreamVodInfo
 */
fun VodEntity.toXtreamVodInfo(): XtreamVodInfo {
    return XtreamVodInfo(
        num = this.num,
        name = this.name,
        stream_type = "movie",
        stream_id = this.streamId,
        stream_icon = this.streamIcon,
        rating = this.rating,
        rating_5based = this.rating5based,
        added = this.added,
        category_id = this.categoryId,
        category_ids = this.category_ids.mapNotNull { it.toIntOrNull() },
        container_extension = this.containerExtension,
        custom_sid = this.customSid,
        direct_source = this.directSource,
        releaseDate = this.releaseDate,
        duration = this.duration,
        plot = this.plot,
        cast = this.cast,
        director = this.director,
        genre = this.genre,
        youtube_trailer = this.youtubeTrailer,
        cover = this.cover,
        rating_imdb = this.ratingImdb
    )
}
