package com.tvonnet.debridxtreamiptv.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.tvonnet.debridxtreamiptv.data.model.ContentType

/**
 * Room Entity for tracking user watch history and viewing behavior
 * Phase 3.2: Content Recommendations System
 *
 * Tracks comprehensive viewing data for recommendation algorithms:
 * - Content identification and metadata
 * - Viewing duration and progress tracking
 * - Session management for binge-watching detection
 * - Quality preferences and device information
 * - Completion status for content engagement analysis
 */
@Entity(tableName = "watch_history")
data class WatchHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,

    // Content Identification
    val contentId: String, // Unique identifier for the content (stream_id, movie_id, series_id)
    val contentType: String, // "live_tv", "movie", "series_episode"
    val title: String, // Content title for display
    val posterUrl: String? = null, // Content thumbnail/poster URL
    val seriesId: String? = null, // For series episodes, parent series ID
    val seasonNumber: Int? = null, // For series episodes
    val episodeNumber: Int? = null, // For series episodes
    val episodeTitle: String? = null, // For series episodes

    // Viewing Duration and Progress
    val watchDuration: Long = 0L, // Total time watched in milliseconds
    val totalDuration: Long = 0L, // Total content duration in milliseconds
    val lastWatchedPosition: Long = 0L, // Last position in milliseconds (for resume)
    val watchProgress: Float = 0f, // Progress percentage (0.0 to 1.0)
    val completionThreshold: Float = 0.9f, // Threshold for considering content "watched"

    // Session Management
    val sessionId: String, // Unique session identifier
    val sessionStartTime: Long, // When the viewing session started
    val sessionEndTime: Long? = null, // When the viewing session ended
    val pauseCount: Int = 0, // Number of times content was paused
    val seekCount: Int = 0, // Number of times user seeked in content

    // Viewing Behavior
    val watchDate: Long = System.currentTimeMillis(), // When content was watched
    val completionStatus: String, // "not_started", "in_progress", "completed", "abandoned"
    val engagementScore: Float = 0f, // Calculated engagement based on various factors
    val isBingeWatch: Boolean = false, // Part of binge-watching session
    val consecutiveEpisodes: Int = 1, // Number of episodes watched in a row

    // Quality and Technical
    val quality: String? = null, // "720p", "1080p", "4K", etc.
    val deviceInfo: String? = null, // Device model and type
    val networkType: String? = null, // "wifi", "mobile", "ethernet"
    val playbackSpeed: Float = 1f, // User's preferred playback speed

    // Genre and Category Information
    val genres: List<String> = emptyList(), // Content genres for recommendation
    val categories: List<String> = emptyList(), // Content categories
    val tags: List<String> = emptyList(), // Additional tags for content classification

    // Metadata and Analytics
    val metadata: Map<String, Any> = emptyMap(), // Additional flexible metadata
    val recommendationSource: String? = null, // How user discovered this content
    val isRecommendedContent: Boolean = false, // If this was from recommendations

    // Timestamps
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val lastAccessedAt: Long = System.currentTimeMillis()
)

/**
 * Content type constants for watch history tracking
 */
object WatchHistoryContentType {
    const val LIVE_TV = "live_tv"
    const val MOVIE = "movie"
    const val SERIES_EPISODE = "series_episode"
}

/**
 * Completion status constants
 */
object WatchHistoryCompletionStatus {
    const val NOT_STARTED = "not_started"
    const val IN_PROGRESS = "in_progress"
    const val COMPLETED = "completed"
    const val ABANDONED = "abandoned"
}

/**
 * Extension function to calculate engagement score
 */
fun WatchHistoryEntity.calculateEngagementScore(): Float {
    // Base score from watch progress (40% weight)
    val progressScore = watchProgress * 0.4f

    // Completion bonus (20% weight)
    val completionBonus = if (completionStatus == WatchHistoryCompletionStatus.COMPLETED) 0.2f else 0f

    // Session engagement (20% weight) - less pausing = higher engagement
    val sessionPenalty = (pauseCount * 0.02f) + (seekCount * 0.01f)
    val sessionScore = (0.2f - sessionPenalty).coerceAtLeast(0f)

    // Binge watching bonus (10% weight)
    val bingeBonus = if (isBingeWatch) 0.1f * (consecutiveEpisodes / 5f).coerceAtMost(1f) else 0f

    // Recent content bonus (10% weight) - more recent = higher score
    val daysSinceWatched = (System.currentTimeMillis() - watchDate) / (24 * 60 * 60 * 1000)
    val recentBonus = if (daysSinceWatched <= 7) 0.1f * (1f - daysSinceWatched / 7f) else 0f

    return (progressScore + completionBonus + sessionScore + bingeBonus + recentBonus).coerceAtMost(1f)
}

/**
 * Extension function to check if content is considered "watched"
 */
fun WatchHistoryEntity.isConsideredWatched(): Boolean {
    return watchProgress >= completionThreshold ||
           completionStatus == WatchHistoryCompletionStatus.COMPLETED ||
           (watchDuration > (totalDuration * 0.8)) // 80% threshold fallback
}

/**
 * Extension function to get formatted watch time
 */
fun WatchHistoryEntity.getFormattedWatchTime(): String {
    val hours = watchDuration / (1000 * 60 * 60)
    val minutes = (watchDuration % (1000 * 60 * 60)) / (1000 * 60)
    return when {
        hours > 0 -> "${hours}h ${minutes}m"
        minutes > 0 -> "${minutes}m"
        else -> "< 1m"
    }
}