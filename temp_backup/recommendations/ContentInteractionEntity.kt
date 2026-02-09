package com.tvonnet.debridxtreamiptv.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room Entity for logging detailed user interactions with content
 * Phase 3.2: Content Recommendations System
 *
 * Tracks granular user interaction data for advanced recommendation algorithms:
 * - Search, view, and engagement interactions
 * - User feedback and preferences
 * - Content discovery patterns
 * - Time-based interaction analysis
 */
@Entity(tableName = "content_interactions")
data class ContentInteractionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,

    // Interaction Identification
    val sessionId: String, // Related viewing session
    val contentId: String, // Content that was interacted with
    val contentType: String, // "live_tv", "movie", "series_episode"
    val interactionType: String, // Type of interaction
    val interactionSubType: String? = null, // More specific interaction type

    // Interaction Context
    val sourceScreen: String? = null, // Where interaction occurred
    val sourceComponent: String? = null, // UI component used
    val discoveryMethod: String? = null, // How content was discovered
    val recommendationId: String? = null, // If from recommendations
    val searchQuery: String? = null, // If from search

    // Interaction Details
    val interactionValue: Map<String, Any> = emptyMap(), // Interaction-specific data
    val interactionDuration: Long = 0L, // Time spent on interaction (ms)
    val interactionSuccess: Boolean = true, // Whether interaction was successful
    val userRating: Int? = null, // User rating (1-5) if provided
    val userFeedback: String? = null, // User feedback text

    // Content Metadata at Time of Interaction
    val contentTitle: String? = null,
    val contentGenres: List<String> = emptyList(),
    val contentCategories: List<String> = emptyList(),
    val contentQuality: String? = null,
    val contentDuration: Long? = null,

    // User Context
    val userId: String = "default_user",
    val userLocation: String? = null, // User location if available
    val userTimeOfDay: Int, // Hour of day (0-23)
    val userDayOfWeek: Int, // Day of week (1-7, Monday=1)
    val userSessionNumber: Int = 1, // Session number for the day

    // Technical Context
    val deviceType: String = "tv", // "tv", "phone", "tablet"
    val appVersion: String,
    val networkType: String? = null,
    val connectivityQuality: String? = null, // "excellent", "good", "fair", "poor"

    // Response Metrics
    val responseTime: Long = 0L, // System response time (ms)
    val userWaitTime: Long = 0L, // Time user waited (ms)
    val errorOccurred: Boolean = false,
    val errorMessage: String? = null,
    val errorCode: Int? = null,

    // Business Metrics
    val conversionValue: Float = 0f, // Business value of interaction
    val engagementScore: Float = 0f, // Calculated engagement impact
    val recommendationImpact: Float = 0f, // Impact on recommendation accuracy

    // Timestamps
    val timestamp: Long = System.currentTimeMillis(),
    val sessionTimestamp: Long = System.currentTimeMillis(), // When session started
    val timezoneOffset: Int = 0 // Timezone offset in minutes
)

/**
 * Interaction type constants for tracking user behavior
 */
object InteractionType {
    // Discovery Interactions
    const val SEARCH = "search"
    const val BROWSE = "browse"
    const val RECOMMENDATION_VIEW = "recommendation_view"
    const val CATEGORY_BROWSE = "category_browse"
    const val TRENDING_VIEW = "trending_view"
    const val HOME_VIEW = "home_view"
    const val CONTINUE_WATCHING = "continue_watching"

    // Content Interactions
    const val CONTENT_VIEW = "content_view"
    const val CONTENT_PLAY = "content_play"
    const val CONTENT_PAUSE = "content_pause"
    const val CONTENT_RESUME = "content_resume"
    const val CONTENT_STOP = "content_stop"
    const val CONTENT_COMPLETE = "content_complete"
    const val CONTENT_ABANDON = "content_abandon"

    // Engagement Interactions
    const val LIKE = "like"
    const val DISLIKE = "dislike"
    const val ADD_TO_FAVORITES = "add_to_favorites"
    const val REMOVE_FROM_FAVORITES = "remove_from_favorites"
    const val ADD_TO_WATCHLIST = "add_to_watchlist"
    const val SHARE = "share"
    const val RATE = "rate"
    const val COMMENT = "comment"

    // Navigation Interactions
    const val SCROLL = "scroll"
    const val CLICK = "click"
    const val LONG_PRESS = "long_press"
    const val SWIPE = "swipe"
    const val VOICE_COMMAND = "voice_command"

    // Quality Interactions
    const val QUALITY_CHANGE = "quality_change"
    const val SUBTITLE_TOGGLE = "subtitle_toggle"
    const val AUDIO_CHANGE = "audio_change"
    const val PLAYBACK_SPEED_CHANGE = "playback_speed_change"

    // Recommendation Interactions
    const val RECOMMENDATION_CLICK = "recommendation_click"
    const val RECOMMENDATION_DISMISS = "recommendation_dismiss"
    const val RECOMMENDATION_FEEDBACK = "recommendation_feedback"
    const val NOT_INTERESTED = "not_interested"
    const val MORE_LIKE_THIS = "more_like_this"
}

/**
 * Interaction sub-type constants for more detailed tracking
 */
object InteractionSubType {
    // Search sub-types
    const val VOICE_SEARCH = "voice_search"
    const val TEXT_SEARCH = "text_search"
    const val TRENDING_SEARCH = "trending_search"
    const val CATEGORY_SEARCH = "category_search"

    // Content play sub-types
    const val AUTO_PLAY = "auto_play"
    const val USER_INITIATED = "user_initiated"
    const val RESUME_PLAYBACK = "resume_playback"
    const val RESTART_PLAYBACK = "restart_playback"

    // Recommendation sub-types
    const val CONTENT_BASED = "content_based"
    const val COLLABORATIVE = "collaborative"
    const val TRENDING = "trending"
    const val PERSONALIZED = "personalized"
    const val SIMILAR_CONTENT = "similar_content"

    // UI sub-types
    const val HOME_CAROUSEL = "home_carousel"
    const val SEARCH_RESULTS = "search_results"
    const val CATEGORY_GRID = "category_grid"
    const val DETAIL_PAGE = "detail_page"
    const val PLAYER_CONTROLS = "player_controls"
}

/**
 * Discovery method constants
 */
object DiscoveryMethod {
    const val SEARCH = "search"
    const val BROWSE = "browse"
    const val RECOMMENDATION = "recommendation"
    const val HOME_SCREEN = "home_screen"
    const val TRENDING = "trending"
    const val CONTINUE_WATCHING = "continue_watching"
    const val EXTERNAL_LINK = "external_link"
    const val NOTIFICATION = "notification"
}

/**
 * Source screen constants
 */
object SourceScreen {
    const val HOME = "home"
    const val SEARCH = "search"
    const val LIVE_TV = "live_tv"
    const val MOVIES = "movies"
    const val SERIES = "series"
    const val FAVORITES = "favorites"
    const val PLAYER = "player"
    const val SETTINGS = "settings"
    const val RECOMMENDATIONS = "recommendations"
}

/**
 * Extension function to get interaction age in hours
 */
fun ContentInteractionEntity.getAgeInHours(): Long {
    return (System.currentTimeMillis() - timestamp) / (60 * 60 * 1000)
}

/**
 * Extension function to check if interaction is recent (within 24 hours)
 */
fun ContentInteractionEntity.isRecent(): Boolean {
    return getAgeInHours() <= 24
}

/**
 * Extension function to get interaction value as typed value
 */
inline fun <reified T> ContentInteractionEntity.getInteractionValue(key: String): T? {
    return interactionValue[key] as? T
}

/**
 * Extension function to check if interaction indicates positive engagement
 */
fun ContentInteractionEntity.isPositiveEngagement(): Boolean {
    return interactionType in listOf(
        InteractionType.CONTENT_PLAY,
        InteractionType.LIKE,
        InteractionType.ADD_TO_FAVORITES,
        InteractionType.ADD_TO_WATCHLIST,
        InteractionType.SHARE,
        InteractionType.RATE,
        InteractionType.RECOMMENDATION_CLICK,
        InteractionType.MORE_LIKE_THIS
    ) || (userRating ?: 0) >= 4
}

/**
 * Extension function to check if interaction indicates negative engagement
 */
fun ContentInteractionEntity.isNegativeEngagement(): Boolean {
    return interactionType in listOf(
        InteractionType.DISLIKE,
        InteractionType.CONTENT_ABANDON,
        InteractionType.REMOVE_FROM_FAVORITES,
        InteractionType.NOT_INTERESTED,
        InteractionType.RECOMMENDATION_DISMISS
    ) || (userRating ?: 6) <= 2
}

/**
 * Extension function to calculate interaction weight for recommendations
 */
fun ContentInteractionEntity.calculateInteractionWeight(): Float {
    val baseWeight = when (interactionType) {
        InteractionType.CONTENT_COMPLETE -> 1.0f
        InteractionType.LIKE, InteractionType.ADD_TO_FAVORITES -> 0.8f
        InteractionType.CONTENT_PLAY -> 0.6f
        InteractionType.RATE -> 0.5f
        InteractionType.RECOMMENDATION_CLICK -> 0.4f
        InteractionType.CONTENT_VIEW -> 0.3f
        InteractionType.CONTENT_ABANDON -> -0.4f
        InteractionType.DISLIKE -> -0.6f
        InteractionType.NOT_INTERESTED -> -0.8f
        else -> 0f
    }

    // Apply rating modifier
    val ratingModifier = when (userRating) {
        5 -> 0.3f
        4 -> 0.1f
        3 -> 0f
        2 -> -0.1f
        1 -> -0.3f
        else -> 0f
    }

    // Apply recency modifier (more recent = higher weight)
    val ageInHours = getAgeInHours()
    val recencyModifier = when {
        ageInHours <= 1 -> 0.2f
        ageInHours <= 24 -> 0.1f
        ageInHours <= 168 -> 0.05f // 1 week
        else -> 0f
    }

    return (baseWeight + ratingModifier + recencyModifier).coerceIn(-1f, 1f)
}