package com.tvonnet.debridxtreamiptv.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.tvonnet.debridxtreamiptv.data.model.ContentType

/**
 * Room Entity for storing user preferences and viewing patterns
 * Phase 3.2: Content Recommendations System
 *
 * Maintains comprehensive user profile data for personalized recommendations:
 * - Content preferences and genre affinities
 * - Viewing habits and time patterns
 * - Quality and device preferences
 * - Recommendation feedback and adjustments
 */
@Entity(tableName = "user_profiles")
data class UserProfileEntity(
    @PrimaryKey val userId: String = "default_user", // Single user app

    // Basic User Information
    val displayName: String? = null,
    val avatarUrl: String? = null,
    val accountCreated: Long = System.currentTimeMillis(),
    val lastActive: Long = System.currentTimeMillis(),
    val totalWatchTime: Long = 0L, // Total time watched in milliseconds

    // Content Preferences
    val favoriteGenres: List<String> = emptyList(), // User's favorite genres
    val genreScores: Map<String, Float> = emptyMap(), // Genre affinity scores (0.0 to 1.0)
    val preferredCategories: List<String> = emptyList(), // Preferred content categories
    val categoryScores: Map<String, Float> = emptyMap(), // Category affinity scores
    val dislikedGenres: List<String> = emptyList(), // Genres user dislikes
    val blockedContent: List<String> = emptyList(), // Blocked content IDs or types

    // Viewing Habits
    val typicalWatchTime: Map<String, Float> = emptyMap(), // Hour of day -> preference score
    val typicalWatchDays: Map<String, Float> = emptyMap(), // Day of week -> preference score
    val averageSessionDuration: Long = 0L, // Average viewing session in milliseconds
    val bingeWatchingTendency: Float = 0f, // 0.0 to 1.0, likelihood to binge watch
    val preferredContentLength: String = "medium", // "short", "medium", "long"
    val skipIntroTendency: Float = 0f, // Tendency to skip intros (0.0 to 1.0)

    // Quality and Technical Preferences
    val preferredQuality: String = "auto", // "auto", "720p", "1080p", "4K"
    val dataUsagePreference: String = "balanced", // "saver", "balanced", "unlimited"
    val subtitlePreference: String = "auto", // "auto", "enabled", "disabled"
    val preferredAudioLanguage: String = "en", // ISO language code
    val preferredSubtitleLanguage: String = "en", // ISO language code
    val playbackSpeedPreference: Float = 1f, // Default playback speed

    // Content Discovery Preferences
    val recommendationSensitivity: Float = 0.7f, // How conservative/experimental recommendations are
    val newContentWeight: Float = 0.3f, // Weight for new vs familiar content
    val popularityWeight: Float = 0.5f, // Weight for trending/popular content
    val diversityWeight: Float = 0.4f, // Weight for content diversity
    val similarContentWeight: Float = 0.6f, // Weight for similar to watched content

    // Interaction History
    val totalContentWatched: Int = 0, // Total number of content items watched
    val totalSessions: Int = 0, // Total viewing sessions
    val averageRatingGiven: Float = 0f, // Average user rating (if rating system exists)
    val feedbackGiven: Int = 0, // Number of recommendation feedback entries
    val searchHistorySize: Int = 0, // Number of unique searches performed

    // Viewing Statistics
    val moviesWatched: Int = 0,
    val seriesEpisodesWatched: Int = 0,
    val liveTvHoursWatched: Long = 0L,
    val contentCompleted: Int = 0, // Number of content items fully completed
    val contentAbandoned: Int = 0, // Number of content items abandoned

    // Device and Platform Usage
    val primaryDeviceType: String = "tv", // "tv", "phone", "tablet"
    val deviceUsageStats: Map<String, Long> = emptyMap(), // Device type -> usage time
    val networkUsagePatterns: Map<String, Long> = emptyMap(), // Network type -> usage time

    // Recommendation Algorithm Settings
    val algorithmWeights: Map<String, Float> = mapOf(
        "content_based" to 0.4f,
        "collaborative" to 0.3f,
        "trending" to 0.2f,
        "personalized" to 0.1f
    ),
    val lastRecommendationUpdate: Long = System.currentTimeMillis(),
    val recommendationRefreshInterval: Long = 24 * 60 * 60 * 1000, // 24 hours

    // Privacy and Control Settings
    val dataCollectionEnabled: Boolean = true,
    val personalizationEnabled: Boolean = true,
    val analyticsEnabled: Boolean = false,
    val recommendationAgeLimit: String? = null, // Content rating limit

    // Metadata
    val metadata: Map<String, Any> = emptyMap(), // Additional flexible data
    val version: Int = 1, // Profile version for migrations

    // Timestamps
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

/**
 * Quality preference constants
 */
object QualityPreference {
    const val AUTO = "auto"
    const val HD_720P = "720p"
    const val FULL_HD_1080P = "1080p"
    const val ULTRA_HD_4K = "4K"
}

/**
 * Data usage preference constants
 */
object DataUsagePreference {
    const val SAVER = "saver"
    const val BALANCED = "balanced"
    const val UNLIMITED = "unlimited"
}

/**
 * Subtitle preference constants
 */
object SubtitlePreference {
    const val AUTO = "auto"
    const val ENABLED = "enabled"
    const val DISABLED = "disabled"
}

/**
 * Content length preference constants
 */
object ContentLengthPreference {
    const val SHORT = "short" // < 30 minutes
    const val MEDIUM = "medium" // 30-90 minutes
    const val LONG = "long" // > 90 minutes
}

/**
 * Extension function to calculate genre affinity score
 */
fun UserProfileEntity.getGenreAffinityScore(genre: String): Float {
    return genreScores[genre] ?: 0f
}

/**
 * Extension function to update genre affinity
 */
fun UserProfileEntity.updateGenreAffinity(genre: String, delta: Float): UserProfileEntity {
    val currentScore = genreScores[genre] ?: 0f
    val newScore = (currentScore + delta).coerceIn(0f, 1f)
    val updatedScores = genreScores.toMutableMap().apply { put(genre, newScore) }
    return copy(genreScores = updatedScores, updatedAt = System.currentTimeMillis())
}

/**
 * Extension function to get preferred content types
 */
fun UserProfileEntity.getPreferredContentTypes(): Map<ContentType, Float> {
    return mapOf(
        ContentType.LIVE_TV to (liveTvHoursWatched.toFloat() / (totalWatchTime + 1).toFloat()),
        ContentType.MOVIE to (moviesWatched.toFloat() / (totalContentWatched + 1).toFloat()),
        ContentType.SERIES to (seriesEpisodesWatched.toFloat() / (totalContentWatched + 1).toFloat())
    )
}

/**
 * Extension function to calculate engagement level
 */
fun UserProfileEntity.getEngagementLevel(): String {
    val avgSessionsPerDay = if (accountCreated > 0) {
        totalSessions.toFloat() / ((System.currentTimeMillis() - accountCreated) / (24 * 60 * 60 * 1000f))
    } else 0f

    return when {
        avgSessionsPerDay >= 3.0f -> "heavy"
        avgSessionsPerDay >= 1.0f -> "regular"
        avgSessionsPerDay >= 0.3f -> "casual"
        else -> "light"
    }
}

/**
 * Extension function to get viewing time distribution
 */
fun UserProfileEntity.getViewingTimeDistribution(): Map<String, Float> {
    val total = deviceUsageStats.values.sum().toFloat()
    return if (total > 0) {
        deviceUsageStats.mapValues { (_, time) -> time / total }
    } else {
        mapOf("tv" to 1.0f) // Default to TV
    }
}

/**
 * Extension function to check if user is active
 */
fun UserProfileEntity.isActiveUser(): Boolean {
    val daysSinceLastActive = (System.currentTimeMillis() - lastActive) / (24 * 60 * 60 * 1000)
    return daysSinceLastActive <= 30 // Active if used within last 30 days
}
