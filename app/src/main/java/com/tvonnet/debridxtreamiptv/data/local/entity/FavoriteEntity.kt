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
    val addedAt: Long = System.currentTimeMillis()
)

