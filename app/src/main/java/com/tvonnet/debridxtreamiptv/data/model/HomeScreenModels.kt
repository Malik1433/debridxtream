package com.tvonnet.debridxtreamiptv.data.model

/**
 * Model for content that user is currently watching
 */
data class ContinueWatchingItem(
    val contentId: String,
    val contentType: ContentType,
    val title: String,
    val posterUrl: String?,
    val backdropUrl: String?,
    val currentPosition: Long, // in milliseconds
    val totalDuration: Long, // in milliseconds
    val lastWatchedTimestamp: Long,
    val streamUrl: String?,
    val tmdbId: String? = null,
    val imdbId: String? = null,
    val seriesTitle: String? = null,
    val episodeTitle: String? = null,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
    val debridInfoHash: String? = null,
    val debridMagnet: String? = null,
    val source: String = "xtream" // "xtream" or "debrid"
) {
    val progressPercentage: Int
        get() = if (totalDuration > 0) {
            ((currentPosition.toFloat() / totalDuration.toFloat()) * 100).toInt()
        } else 0
    
    val formattedProgress: String
        get() = "${formatTime(currentPosition)} / ${formatTime(totalDuration)}"
    
    private fun formatTime(milliseconds: Long): String {
        val totalSeconds = milliseconds / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        
        return if (hours > 0) {
            String.format("%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format("%d:%02d", minutes, seconds)
        }
    }
}

/**
 * Model for user's favorite content
 */
data class FavoriteItem(
    val contentId: String,
    val contentType: ContentType,
    val title: String,
    val posterUrl: String?,
    val backdropUrl: String?,
    val addedTimestamp: Long,
    val streamUrl: String?
)

/**
 * Model for recently watched LIVE channels only (not VOD/Series)
 */
data class RecentLiveChannelItem(
    val channelId: String,
    val channelName: String,
    val channelLogo: String?,
    val lastWatchedTimestamp: Long,
    val streamUrl: String?,
    val epgChannelId: String? = null
)

/**
 * Model for recently watched content (different from continue watching - shows completed content too)
 */
data class RecentlyWatchedItem(
    val contentId: String,
    val contentType: ContentType,
    val title: String,
    val posterUrl: String?,
    val backdropUrl: String?,
    val lastWatchedTimestamp: Long,
    val streamUrl: String?
)

/**
 * Featured content item for home screen
 */
data class FeaturedItem(
    val contentId: String,
    val contentType: ContentType,
    val title: String,
    val backdropUrl: String?,
    val posterUrl: String?,
    val description: String?,
    val streamUrl: String?
)

/**
 * New Added content item - recently added to IPTV server
 */
data class NewAddedItem(
    val contentId: String,
    val contentType: ContentType,
    val title: String,
    val posterUrl: String?,
    val addedDate: String?, // Date when content was added to server
    val addedTimestamp: Long, // Timestamp for sorting
    val year: String?,
    val quality: String?, // HD, 4K, etc.
    val streamUrl: String?
)

/**
 * Content type enum
 */
enum class ContentType {
    LIVE_TV,
    MOVIE,
    SERIES,
    EPISODE
}

/**
 * Helper extensions to convert Xtream models to home screen models
 */
fun XtreamStream.toFeaturedItem(serverUrl: String, username: String, password: String): FeaturedItem {
    val streamUrl = "$serverUrl/live/$username/$password/${stream_id ?: ""}.ts"
    return FeaturedItem(
        contentId = stream_id ?: "",
        contentType = ContentType.LIVE_TV,
        title = name ?: "Unknown Channel",
        backdropUrl = stream_icon,
        posterUrl = stream_icon,
        description = null,
        streamUrl = streamUrl
    )
}

fun XtreamVodInfo.toFeaturedItem(serverUrl: String, username: String, password: String): FeaturedItem {
    val streamUrl = "$serverUrl/movie/$username/$password/${stream_id ?: ""}.${container_extension ?: "mp4"}"
    return FeaturedItem(
        contentId = stream_id ?: "",
        contentType = ContentType.MOVIE,
        title = name ?: "Unknown Movie",
        backdropUrl = cover ?: stream_icon, // Fallback to icon if cover missing
        posterUrl = stream_icon ?: cover,   // Fallback to cover if icon missing
        description = plot,
        streamUrl = streamUrl
    )
}

fun XtreamSeriesInfo.toFeaturedItem(): FeaturedItem {
    return FeaturedItem(
        contentId = series_id ?: "",
        contentType = ContentType.SERIES,
        title = name ?: "Unknown Series",
        backdropUrl = cover,
        posterUrl = cover,
        description = plot,
        streamUrl = null // Series need episode selection
    )
}

fun XtreamStream.toFavoriteItem(serverUrl: String, username: String, password: String, addedTimestamp: Long): FavoriteItem {
    val streamUrl = "$serverUrl/live/$username/$password/${stream_id ?: ""}.ts"
    return FavoriteItem(
        contentId = stream_id ?: "",
        contentType = ContentType.LIVE_TV,
        title = name ?: "Unknown Channel",
        posterUrl = stream_icon,
        backdropUrl = stream_icon,
        addedTimestamp = addedTimestamp,
        streamUrl = streamUrl
    )
}

fun XtreamVodInfo.toFavoriteItem(serverUrl: String, username: String, password: String, addedTimestamp: Long): FavoriteItem {
    val streamUrl = "$serverUrl/movie/$username/$password/${stream_id ?: ""}.${container_extension ?: "mp4"}"
    return FavoriteItem(
        contentId = stream_id ?: "",
        contentType = ContentType.MOVIE,
        title = name ?: "Unknown Movie",
        posterUrl = stream_icon,
        backdropUrl = cover,
        addedTimestamp = addedTimestamp,
        streamUrl = streamUrl
    )
}

fun XtreamSeriesInfo.toFavoriteItem(addedTimestamp: Long): FavoriteItem {
    return FavoriteItem(
        contentId = series_id ?: "",
        contentType = ContentType.SERIES,
        title = name ?: "Unknown Series",
        posterUrl = cover,
        backdropUrl = cover,
        addedTimestamp = addedTimestamp,
        streamUrl = null
    )
}

fun XtreamVodInfo.toNewAddedItem(serverUrl: String, username: String, password: String): NewAddedItem {
    val streamUrl = "$serverUrl/movie/$username/$password/${stream_id ?: ""}.${container_extension ?: "mp4"}"
    val addedTime = try { added?.toLong() ?: 0L } catch (e: Exception) { 0L }
    return NewAddedItem(
        contentId = stream_id ?: "",
        contentType = ContentType.MOVIE,
        title = name ?: "Unknown Movie",
        posterUrl = stream_icon ?: cover, // Fallback to cover
        addedDate = added,
        addedTimestamp = addedTime,
        year = releaseDate?.take(4),
        quality = if (name?.contains("4K", ignoreCase = true) == true) "4K" else "HD",
        streamUrl = streamUrl
    )
}

fun XtreamSeriesInfo.toNewAddedItem(): NewAddedItem {
    // Series usually don't have 'added' timestamp in the same way, or it might be null
    // We'll use 0L or try to parse if available
    return NewAddedItem(
        contentId = series_id ?: "",
        contentType = ContentType.SERIES,
        title = name ?: "Unknown Series",
        posterUrl = cover,
        addedDate = null,
        addedTimestamp = 0L,
        year = releaseDate?.take(4),
        quality = "HD", // Default
        streamUrl = null
    )
}
