package com.tvonnet.debridxtreamiptv.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room Entity for storing user's favorite channels/streams
 * Week 6: Room Database Integration
 * Week 12: Added name and iconUrl fields for better display
 */
@Entity(tableName = "favorites")
data class FavoriteEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val streamId: String,
    val type: String, // "live", "vod", "series"
    val name: String, // Week 12: Display name
    val iconUrl: String? = null, // Week 12: Icon/thumbnail URL
    val addedAt: Long = System.currentTimeMillis(),
    /**
     * S2: where [streamId] is meaningful — [WatchedStateEntity.SOURCE_XTREAM] or
     * [WatchedStateEntity.SOURCE_DEBRID]. An IPTV stream id is only valid on the provider that
     * issued it, so this is what lets a change of provider clear its own favourites and leave the
     * debrid ones alone. Defaults to xtream to match the migration's default for existing rows.
     */
    val source: String = WatchedStateEntity.SOURCE_XTREAM
)

