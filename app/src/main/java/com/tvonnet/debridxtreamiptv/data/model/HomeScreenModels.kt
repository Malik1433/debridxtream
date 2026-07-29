package com.tvonnet.debridxtreamiptv.data.model

import com.tvonnet.debridxtreamiptv.data.debrid.model.TmdbImageUrl
import com.tvonnet.debridxtreamiptv.data.debrid.model.TmdbMovie
import com.tvonnet.debridxtreamiptv.data.debrid.model.TmdbTvShow
import com.tvonnet.debridxtreamiptv.util.GlobalConfig


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
    val seriesId: String? = null,
    val seriesTitle: String? = null,
    val episodeTitle: String? = null,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
    val debridInfoHash: String? = null,
    val debridMagnet: String? = null,
    val directDebridPlayback: Boolean = false,
    val debridProvider: String? = null,
    val debridSourceType: String? = null,
    val debridSourceName: String? = null,
    val debridLanguages: List<String>? = null,
    val debridQuality: String? = null,
    val debridStreamId: String? = null,
    val debridBingeGroup: String? = null,
    val debridFileIdx: Int? = null,
    val source: String = "xtream", // "xtream" or "debrid"
    val expiresAt: Long? = null
) {
    /**
     * Is the link we hold KNOWN to be dead? Not "might it be" — see [mustResolveBeforePlaying],
     * which states the same rule for the player.
     *
     * This used to return true whenever [expiresAt] was unknown, which is every debrid row, because
     * nothing on the resume path ever stores one. So `canResumeDirectly`'s "we have a URL and it is
     * fresh" clause could never fire, and a row was only playable via the *other* clauses — meaning
     * a row holding a perfectly good URL, but no infoHash or magnet to re-resolve from, was sent to
     * the detail screen instead of just playing.
     *
     * Unknown expiry now means play it. If it turns out to be dead, the player repairs at the saved
     * position (see isRepairableExpiredDebridLink) — which is cheaper than refusing to resume, and
     * far cheaper than resolving up front every time.
     */
    fun isExpired(): Boolean {
        if (source == "xtream") return false   // an Xtream URL does not expire
        return expiresAt != null && System.currentTimeMillis() > expiresAt
    }

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
            String.format(java.util.Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(java.util.Locale.US, "%d:%02d", minutes, seconds)
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
 * Model for Sidebar navigation
 */
data class SidebarItem(
    val id: Int,
    val title: String,
    val iconRes: Int,
    var isSelected: Boolean = false
)

/**
 * Featured content item for Hero section
 */
data class HeroContent(
    val title: String,
    val description: String,
    val imageUrl: String?,
    val rating: String,
    val type: String, // "MOVIE" or "SERIES"
    val streamId: String
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
    val epgChannelId: String? = null,
    val categoryId: String? = null
)

/**
 * Model for recently watched content (different from continue watching - shows completed content too)
 */


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
    val rating: String? = null,
    val streamUrl: String?,
    val sourceType: SourceType = SourceType.IPTV,
    val trailerValue: String? = null
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
 * Data Source Type
 */
enum class SourceType {
    IPTV,
    TMDB
}

/**
 * Enhanced Extension: Reconstructs absolute URLs using GlobalConfig for centralized logic.
 * Falls back to passed serverUrl if GlobalConfig is not initialized yet.
 */
// Enhanced Extension with manual fallback support if GlobalConfig is missing
fun String?.toAbsoluteUrl(type: ContentType = ContentType.LIVE_TV, fallbackServerUrl: String? = null): String? {
    if (this == null || this.isBlank()) return null
    if (this.startsWith("http")) return this
    
    val base = GlobalConfig.baseUrl.takeIf { it.isNotBlank() } ?: fallbackServerUrl?.trimEnd('/')?.let { "$it/" }
    if (base == null) return null
    
    val cleanPath = if (this.startsWith("/")) this.substring(1) else this
    return "$base$cleanPath"
}

// Deprecated version now correctly passes the serverUrl as a fallback
fun String?.toAbsoluteUrl(serverUrl: String): String? = this.toAbsoluteUrl(ContentType.LIVE_TV, serverUrl)

fun XtreamStream.toFeaturedItem(serverUrl: String, username: String, password: String): FeaturedItem {
    val streamUrl = "$serverUrl/live/$username/$password/${stream_id ?: ""}.ts"
    val absoluteIcon = stream_icon.toAbsoluteUrl(ContentType.LIVE_TV, serverUrl)
    return FeaturedItem(
        contentId = stream_id ?: "",
        contentType = ContentType.LIVE_TV,
        title = name ?: "Unknown Channel",
        backdropUrl = absoluteIcon,
        posterUrl = absoluteIcon,
        description = null,
        streamUrl = streamUrl
    )
}

fun XtreamVodInfo.toFeaturedItem(serverUrl: String, username: String, password: String): FeaturedItem {
    val streamUrl = "$serverUrl/movie/$username/$password/${stream_id ?: ""}.${container_extension ?: "mp4"}"
    val absoluteIcon = stream_icon.toAbsoluteUrl(ContentType.MOVIE, serverUrl)
    val absoluteCover = cover.toAbsoluteUrl(ContentType.MOVIE, serverUrl)
    
    return FeaturedItem(
        contentId = stream_id ?: "",
        contentType = ContentType.MOVIE,
        title = name ?: "Unknown Movie",
        backdropUrl = absoluteCover ?: absoluteIcon,
        posterUrl = absoluteIcon ?: absoluteCover,
        description = plot,
        streamUrl = streamUrl,
        trailerValue = youtube_trailer
    )
}

fun XtreamSeriesInfo.toFeaturedItem(serverUrl: String): FeaturedItem {
    val absoluteCover = cover.toAbsoluteUrl(ContentType.SERIES, serverUrl)
    return FeaturedItem(
        contentId = series_id ?: "",
        contentType = ContentType.SERIES,
        title = name ?: "Unknown Series",
        backdropUrl = absoluteCover,
        posterUrl = absoluteCover,
        description = plot,
        streamUrl = null, // Series need episode selection
        trailerValue = youtube_trailer
    )
}

fun XtreamStream.toFavoriteItem(serverUrl: String, username: String, password: String, addedTimestamp: Long): FavoriteItem {
    val streamUrl = "$serverUrl/live/$username/$password/${stream_id ?: ""}.ts"
    val absoluteIcon = stream_icon.toAbsoluteUrl(ContentType.LIVE_TV, serverUrl)
    return FavoriteItem(
        contentId = stream_id ?: "",
        contentType = ContentType.LIVE_TV,
        title = name ?: "Unknown Channel",
        posterUrl = absoluteIcon,
        backdropUrl = absoluteIcon,
        addedTimestamp = addedTimestamp,
        streamUrl = streamUrl
    )
}

fun XtreamVodInfo.toFavoriteItem(serverUrl: String, username: String, password: String, addedTimestamp: Long): FavoriteItem {
    val streamUrl = "$serverUrl/movie/$username/$password/${stream_id ?: ""}.${container_extension ?: "mp4"}"
    val absoluteIcon = stream_icon.toAbsoluteUrl(ContentType.MOVIE, serverUrl)
    val absoluteCover = cover.toAbsoluteUrl(ContentType.MOVIE, serverUrl)
    return FavoriteItem(
        contentId = stream_id ?: "",
        contentType = ContentType.MOVIE,
        title = name ?: "Unknown Movie",
        posterUrl = absoluteIcon,
        backdropUrl = absoluteCover,
        addedTimestamp = addedTimestamp,
        streamUrl = streamUrl
    )
}

fun XtreamSeriesInfo.toFavoriteItem(serverUrl: String, addedTimestamp: Long): FavoriteItem {
    val absoluteCover = cover.toAbsoluteUrl(ContentType.SERIES, serverUrl)
    return FavoriteItem(
        contentId = series_id ?: "",
        contentType = ContentType.SERIES,
        title = name ?: "Unknown Series",
        posterUrl = absoluteCover,
        backdropUrl = absoluteCover,
        addedTimestamp = addedTimestamp,
        streamUrl = null
    )
}

fun XtreamVodInfo.toNewAddedItem(serverUrl: String, username: String, password: String): NewAddedItem {
    val streamUrl = "$serverUrl/movie/$username/$password/${stream_id ?: ""}.${container_extension ?: "mp4"}"
    val addedTime = added?.toLongOrNull() ?: 0L
    val absoluteIcon = stream_icon.toAbsoluteUrl(ContentType.MOVIE, serverUrl)
    val absoluteCover = cover.toAbsoluteUrl(ContentType.MOVIE, serverUrl)
    
    return NewAddedItem(
        contentId = stream_id ?: "",
        contentType = ContentType.MOVIE,
        title = name ?: "Unknown Movie",
        posterUrl = absoluteIcon ?: absoluteCover,
        addedDate = added,
        addedTimestamp = addedTime,
        year = releaseDate?.take(4),
        quality = if (name?.contains("4K", ignoreCase = true) == true) "4K" else "HD",
        streamUrl = streamUrl
    )
}

fun XtreamSeriesInfo.toNewAddedItem(serverUrl: String): NewAddedItem {
    val absoluteCover = cover.toAbsoluteUrl(ContentType.SERIES, serverUrl)
    return NewAddedItem(
        contentId = series_id ?: "",
        contentType = ContentType.SERIES,
        title = name ?: "Unknown Series",
        posterUrl = absoluteCover,
        addedDate = null,
        addedTimestamp = 0L,
        year = releaseDate?.take(4),
        quality = "HD", // Default
        streamUrl = null
    )
}

/**
 * TMDB Model to Home Screen Model Converters
 */
fun TmdbMovie.toFeaturedItem(): FeaturedItem {
    return FeaturedItem(
        contentId = id?.toString() ?: "",
        contentType = ContentType.MOVIE,
        title = title ?: originalTitle ?: "Unknown Movie",
        backdropUrl = TmdbImageUrl.getBackdropUrl(backdropPath, size = TmdbImageUrl.BACKDROP_SIZE_ORIGINAL),
        posterUrl = TmdbImageUrl.getPosterUrl(posterPath, size = TmdbImageUrl.POSTER_SIZE_W500),
        description = overview,
        rating = voteAverage?.let { String.format(java.util.Locale.US, "%.1f", it) },
        streamUrl = null, // TMDB items always use debrid bridge
        sourceType = SourceType.TMDB
    )
}

fun TmdbTvShow.toFeaturedItem(): FeaturedItem {
    return FeaturedItem(
        contentId = id?.toString() ?: "",
        contentType = ContentType.SERIES,
        title = name ?: originalName ?: "Unknown Series",
        backdropUrl = TmdbImageUrl.getBackdropUrl(backdropPath, size = TmdbImageUrl.BACKDROP_SIZE_ORIGINAL),
        posterUrl = TmdbImageUrl.getPosterUrl(posterPath, size = TmdbImageUrl.POSTER_SIZE_W500),
        description = overview,
        rating = voteAverage?.let { String.format(java.util.Locale.US, "%.1f", it) },
        streamUrl = null, // TMDB items always use debrid bridge
        sourceType = SourceType.TMDB
    )
}
