package com.tvonnet.debridxtreamiptv.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room Entity for caching recommendation results
 * Phase 3.2: Content Recommendations System
 *
 * Caches computed recommendations to improve performance and reduce computation:
 * - Algorithm-specific results storage
 * - TTL-based cache invalidation
 * - Performance metrics and analytics
 * - Recommendation quality tracking
 */
@Entity(tableName = "recommendation_cache")
data class RecommendationCacheEntity(
    @PrimaryKey val cacheKey: String, // Unique identifier for this cache entry

    // Recommendation Request
    val userId: String = "default_user",
    val algorithmType: String, // "content_based", "collaborative", "trending", "hybrid"
    val contentType: String?, // "live_tv", "movie", "series", null for all
    val limit: Int = 20, // Number of recommendations requested
    val context: Map<String, Any> = emptyMap(), // Additional context parameters

    // Cached Recommendations
    val contentIds: List<String>, // Recommended content IDs
    val contentScores: Map<String, Float>, // Content ID -> confidence score
    val recommendationReasons: Map<String, String>, // Content ID -> reason text
    val metadata: Map<String, Any> = emptyMap(), // Additional algorithm data

    // Cache Metadata
    val generatedAt: Long = System.currentTimeMillis(), // When recommendations were generated
    val expiresAt: Long = System.currentTimeMillis() + (24 * 60 * 60 * 1000), // Default 24h TTL
    val algorithmVersion: String, // Version of algorithm used
    val datasetVersion: String, // Version of data used
    val computationTime: Long = 0L, // Time taken to compute (ms)

    // Performance Metrics
    val cacheHits: Int = 0, // Number of times this cache was hit
    val cacheAge: Long = 0L, // Current age in milliseconds
    val lastAccessed: Long = System.currentTimeMillis(),
    val freshnessScore: Float = 1f, // 0.0 to 1.0, how fresh the data is
    val diversityScore: Float = 0f, // Content diversity score
    val coverageScore: Float = 0f, // Content coverage score

    // Quality Metrics
    val predictionAccuracy: Float? = null, // Known accuracy if available
    val userFeedbackScore: Float? = null, // Average user feedback
    val clickThroughRate: Float? = null, // Known CTR if available
    val recommendationScore: Float = 0f, // Overall quality score

    // Business Metrics
    val businessValue: Float = 0f, // Business impact score
    val conversionScore: Float = 0f, // Conversion likelihood
    val engagementScore: Float = 0f, // Expected engagement

    // Status and Flags
    val isActive: Boolean = true, // Whether cache entry is active
    val isStale: Boolean = false, // Whether cache entry is stale
    val needsRefresh: Boolean = false, // Whether cache should be refreshed
    val priority: Int = 0, // Cache priority (higher = more important)

    // Algorithm Configuration
    val algorithmWeights: Map<String, Float> = emptyMap(), // Algorithm weights used
    val personalizationLevel: Float = 0.7f, // Level of personalization applied
    val diversityLevel: Float = 0.5f, // Diversity constraint applied
    val noveltyLevel: Float = 0.3f, // Novelty preference applied

    // Content Analysis
    val genreDistribution: Map<String, Float> = emptyMap(), // Genre distribution in recommendations
    val categoryDistribution: Map<String, Float> = emptyMap(), // Category distribution
    val qualityDistribution: Map<String, Float> = emptyMap(), // Quality distribution
    val lengthDistribution: Map<String, Float> = emptyMap(), // Content length distribution

    // Debug and Analytics
    val debugInfo: Map<String, Any> = emptyMap(), // Debug information
    val analyticsTags: List<String> = emptyList(), // Analytics tags
    val experimentGroup: String? = null, // A/B test group
    val featureFlags: Map<String, Boolean> = emptyMap(), // Feature flags used

    // Timestamps
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val lastValidated: Long = System.currentTimeMillis() // When cache was last validated
)

/**
 * Algorithm type constants for recommendation systems
 */
object RecommendationAlgorithm {
    const val CONTENT_BASED = "content_based"
    const val COLLABORATIVE = "collaborative"
    const val TRENDING = "trending"
    const val POPULAR = "popular"
    const val PERSONALIZED = "personalized"
    const val HYBRID = "hybrid"
    const val SIMILAR_CONTENT = "similar_content"
    const val CONTINUE_WATCHING = "continue_watching"
    const val NEW_CONTENT = "new_content"
    const val CATEGORY_BASED = "category_based"
    const val GENRE_BASED = "genre_based"
}

/**
 * Content type constants
 */
object RecommendationContentType {
    const val LIVE_TV = "live_tv"
    const val MOVIE = "movie"
    const val SERIES = "series"
    const val ALL = "all"
}

/**
 * Cache priority constants
 */
object CachePriority {
    const val LOW = 0
    const val NORMAL = 1
    const val HIGH = 2
    const val CRITICAL = 3
}

/**
 * Extension function to check if cache entry is expired
 */
fun RecommendationCacheEntity.isExpired(): Boolean {
    return System.currentTimeMillis() > expiresAt
}

/**
 * Extension function to check if cache entry is stale (needs refresh soon)
 */
fun RecommendationCacheEntity.isStale(thresholdHours: Int = 6): Boolean {
    val thresholdMs = thresholdHours * 60 * 60 * 1000L
    val age = System.currentTimeMillis() - generatedAt
    return age > thresholdMs
}

/**
 * Extension function to get cache age in hours
 */
fun RecommendationCacheEntity.getAgeInHours(): Long {
    return (System.currentTimeMillis() - generatedAt) / (60 * 60 * 1000L)
}

/**
 * Extension function to update cache hit metrics
 */
fun RecommendationCacheEntity.recordHit(): RecommendationCacheEntity {
    val newHits = cacheHits + 1
    val newAge = System.currentTimeMillis() - generatedAt
    val newFreshnessScore = calculateFreshnessScore()

    return copy(
        cacheHits = newHits,
        cacheAge = newAge,
        lastAccessed = System.currentTimeMillis(),
        freshnessScore = newFreshnessScore
    )
}

/**
 * Extension function to calculate freshness score
 */
fun RecommendationCacheEntity.calculateFreshnessScore(): Float {
    val ageMs = System.currentTimeMillis() - generatedAt
    val maxAgeMs = expiresAt - generatedAt
    return if (maxAgeMs > 0) {
        1f - (ageMs.toFloat() / maxAgeMs.toFloat()).coerceIn(0f, 1f)
    } else 0f
}

/**
 * Extension function to get top N recommendations
 */
fun RecommendationCacheEntity.getTopRecommendations(limit: Int = 10): List<Pair<String, Float>> {
    return contentScores.entries
        .sortedByDescending { (_, score) -> score }
        .take(limit)
        .map { (contentId, score) -> contentId to score }
}

/**
 * Extension function to get recommendation reason for content
 */
fun RecommendationCacheEntity.getReason(contentId: String): String? {
    return recommendationReasons[contentId]
}

/**
 * Extension function to check if content is recommended
 */
fun RecommendationCacheEntity.isRecommended(contentId: String): Boolean {
    return contentId in contentIds
}

/**
 * Extension function to get content score
 */
fun RecommendationCacheEntity.getContentScore(contentId: String): Float {
    return contentScores[contentId] ?: 0f
}

/**
 * Extension function to calculate cache quality score
 */
fun RecommendationCacheEntity.calculateQualityScore(): Float {
    val freshnessWeight = 0.3f
    val diversityWeight = 0.2f
    val coverageWeight = 0.2f
    val feedbackWeight = 0.3f

    val freshnessScore = freshnessScore
    val diversityScore = diversityScore
    val coverageScore = coverageScore
    val feedbackScore = userFeedbackScore ?: 0f

    return (freshnessScore * freshnessWeight +
            diversityScore * diversityWeight +
            coverageScore * coverageWeight +
            feedbackScore * feedbackWeight).coerceIn(0f, 1f)
}

/**
 * Extension function to generate cache key
 */
fun generateRecommendationCacheKey(
    userId: String,
    algorithmType: String,
    contentType: String?,
    limit: Int,
    context: Map<String, Any> = emptyMap()
): String {
    val contextHash = context.entries
        .sortedBy { it.key }
        .joinToString(",") { "${it.key}=${it.value}" }
        .hashCode()

    return "${userId}_${algorithmType}_${contentType}_${limit}_${contextHash}"
}

/**
 * Extension function to create cache key for content-based recommendations
 */
fun generateContentBasedCacheKey(
    userId: String,
    contentId: String,
    limit: Int = 10
): String {
    return generateRecommendationCacheKey(
        userId = userId,
        algorithmType = RecommendationAlgorithm.CONTENT_BASED,
        contentType = null,
        limit = limit,
        context = mapOf("seed_content_id" to contentId)
    )
}

/**
 * Extension function to create cache key for personalized recommendations
 */
fun generatePersonalizedCacheKey(
    userId: String,
    contentType: String? = null,
    limit: Int = 20
): String {
    return generateRecommendationCacheKey(
        userId = userId,
        algorithmType = RecommendationAlgorithm.PERSONALIZED,
        contentType = contentType,
        limit = limit
    )
}

/**
 * Extension function to create cache key for trending recommendations
 */
fun generateTrendingCacheKey(
    contentType: String? = null,
    limit: Int = 20,
    timeWindow: String = "7d"
): String {
    return generateRecommendationCacheKey(
        userId = "global",
        algorithmType = RecommendationAlgorithm.TRENDING,
        contentType = contentType,
        limit = limit,
        context = mapOf("time_window" to timeWindow)
    )
}
