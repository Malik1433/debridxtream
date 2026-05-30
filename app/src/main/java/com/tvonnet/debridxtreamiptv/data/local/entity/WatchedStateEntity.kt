package com.tvonnet.debridxtreamiptv.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "watched_state",
    indices = [
        Index(value = ["content_type", "source"]),
        Index(value = ["series_id", "source", "season_number"])
    ]
)
data class WatchedStateEntity(
    @PrimaryKey
    @ColumnInfo(name = "identity_key")
    val identityKey: String,
    @ColumnInfo(name = "content_type")
    val contentType: String,
    val source: String,
    @ColumnInfo(name = "is_watched")
    val isWatched: Boolean = false,
    @ColumnInfo(name = "progress_ms", defaultValue = "0")
    val progressMs: Long = 0L,
    @ColumnInfo(name = "duration_ms", defaultValue = "0")
    val durationMs: Long = 0L,
    @ColumnInfo(name = "watched_at")
    val watchedAt: Long? = null,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
    @ColumnInfo(name = "manual_state")
    val manualState: String? = null,
    @ColumnInfo(name = "manual_updated_at")
    val manualUpdatedAt: Long? = null,
    val title: String? = null,
    val year: String? = null,
    @ColumnInfo(name = "tmdb_id")
    val tmdbId: String? = null,
    @ColumnInfo(name = "imdb_id")
    val imdbId: String? = null,
    @ColumnInfo(name = "iptv_stream_id")
    val iptvStreamId: String? = null,
    @ColumnInfo(name = "series_id")
    val seriesId: String? = null,
    @ColumnInfo(name = "season_number")
    val seasonNumber: Int? = null,
    @ColumnInfo(name = "episode_number")
    val episodeNumber: Int? = null,
    @ColumnInfo(name = "episode_id")
    val episodeId: String? = null
) {
    companion object {
        const val CONTENT_TYPE_MOVIE = "MOVIE"
        const val CONTENT_TYPE_EPISODE = "EPISODE"
        const val SOURCE_XTREAM = "xtream"
        const val SOURCE_DEBRID = "debrid"
        const val MANUAL_WATCHED = "WATCHED"
        const val MANUAL_UNWATCHED = "UNWATCHED"
    }
}
