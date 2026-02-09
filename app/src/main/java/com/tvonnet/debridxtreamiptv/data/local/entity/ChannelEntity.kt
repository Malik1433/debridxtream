package com.tvonnet.debridxtreamiptv.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.tvonnet.debridxtreamiptv.data.model.XtreamStream

/**
 * Room Entity for caching channel/stream data locally
 * Week 6: Room Database Integration
 * Week 8: Added cachedAt timestamp for TTL support (QA Recommendation #2)
 */
@Entity(tableName = "channels")
data class ChannelEntity(
    @PrimaryKey val streamId: String,
    val name: String,
    val categoryId: String,
    val streamIcon: String?,
    val epgChannelId: String?,
    val added: Long,
    val isFavorite: Boolean = false,
    val lastWatched: Long? = null,
    val streamType: String, // "live", "vod", "series"
    val cachedAt: Long = System.currentTimeMillis() // Week 8: For TTL checking
)

/**
 * Extension function to convert XtreamStream to ChannelEntity
 * Week 8: Added cachedAt timestamp
 */
fun XtreamStream.toChannelEntity(categoryId: String, streamType: String): ChannelEntity {
    return ChannelEntity(
        streamId = stream_id ?: "",
        name = name ?: "",
        categoryId = categoryId,
        streamIcon = stream_icon,
        epgChannelId = epg_channel_id,
        added = added?.toLongOrNull() ?: System.currentTimeMillis(),
        isFavorite = false,
        lastWatched = null,
        streamType = streamType,
        cachedAt = System.currentTimeMillis() // Week 8: Cache timestamp
    )
}

/**
 * Extension function to convert ChannelEntity back to XtreamStream
 */
fun ChannelEntity.toXtreamStream(): XtreamStream {
    return XtreamStream(
        num = 0,
        name = name,
        stream_type = streamType,
        stream_id = streamId,
        stream_icon = streamIcon,
        epg_channel_id = epgChannelId,
        added = added.toString(),
        category_id = categoryId,
        custom_sid = null,
        tv_archive = null,
        direct_source = null,
        tv_archive_duration = null,
        category_ids = null,
        container_extension = null
    )
}

