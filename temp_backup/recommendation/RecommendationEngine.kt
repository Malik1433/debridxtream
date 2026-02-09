package com.tvonnet.debridxtreamiptv.data.recommendation

import android.util.Log
import com.tvonnet.debridxtreamiptv.data.local.dao.ContentInteractionDao
import com.tvonnet.debridxtreamiptv.data.local.dao.RecommendationCacheDao
import com.tvonnet.debridxtreamiptv.data.local.dao.UserProfileDao
import com.tvonnet.debridxtreamiptv.data.local.dao.WatchHistoryDao
import com.tvonnet.debridxtreamiptv.data.local.entity.RecommendationCacheEntity
import com.tvonnet.debridxtreamiptv.data.local.entity.RecommendationAlgorithm
import com.tvonnet.debridxtreamiptv.data.local.entity.RecommendationContentType
import com.tvonnet.debridxtreamiptv.data.model.ContentType
import com.tvonnet.debridxtreamiptv.data.recommendation.algorithm.ContentBasedRecommender
import com.tvonnet.debridxtreamiptv.data.recommendation.algorithm.CollaborativeRecommender
import com.tvonnet.debridxtreamiptv.data.recommendation.algorithm.TrendingRecommender
import com.tvonnet.debridxtreamiptv.data.recommendation.algorithm.PopularRecommender
import com.tvonnet.debridxtreamiptv.data.recommendation.algorithm.PersonalizedRecommender
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

/**
 * Core recommendation engine that orchestrates multiple recommendation algorithms
 * Phase 3.2: Content Recommendations System
 *
 * Features:
 * - Multiple recommendation algorithms (content-based, collaborative, trending, popular)
 * - Hybrid recommendation system with weighted algorithm combination
 * - Intelligent caching for performance optimization
 * - Real-time personalization based on user behavior
 * - A/B testing support for algorithm optimization
 */
@Singleton
class RecommendationEngine @Inject constructor(
    private val watchHistoryDao: WatchHistoryDao,
    private val userProfileDao: UserProfileDao,
    private val contentInteractionDao: ContentInteractionDao,
    private val recommendationCacheDao: RecommendationCacheDao,
    private val contentBasedRecommender: ContentBasedRecommender,
    private val collaborativeRecommender: CollaborativeRecommender,
    private val trendingRecommender: TrendingRecommender,
    private val popularRecommender: PopularRecommender,
    private val personalizedRecommender: PersonalizedRecommender
) {
    companion object {
        private const val TAG = "RecommendationEngine"
        private const val DEFAULT_RECOMMENDATION_LIMIT = 20
        private const val CACHE_EXPIRY_HOURS = 24
        private const val MIN_WATCH_HISTORY_FOR_PERSONALIZATION = 5
    }

    private val engineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // State management
    private val _isLoading = MutableStateFlow(false)
    val isLoading: Flow<Boolean> = _isLoading.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: Flow<String?> = _lastError.asStateFlow()

    // Algorithm weights
    private var algorithmWeights = mapOf(
        RecommendationAlgorithm.CONTENT_BASED to 0.3f,
        RecommendationAlgorithm.COLLABORATIVE to 0.2f,
        RecommendationAlgorithm.TRENDING to 0.2f,
        RecommendationAlgorithm.POPULAR to 0.1f,
        RecommendationAlgorithm.PERSONALIZED to 0.2f
    )

    /**
     * Gets personalized recommendations for the user
     */
    suspend fun getPersonalizedRecommendations(
        limit: Int = DEFAULT_RECOMMENDATION_LIMIT,
        contentType: String? = null,
        refreshCache: Boolean = false
    ): List<RecommendationResult> {
        return try {
            _isLoading.value = true
            _lastError.value = null

            val userId = "default_user"
            val userProfile = userProfileDao.getUserProfile(userId) ?: return emptyList()

            // Generate cache key
            val cacheKey = com.tvonnet.debridxtreamiptv.data.local.entity.generatePersonalizedCacheKey(
                userId = userId,
                contentType = contentType,
                limit = limit
            )

            // Check cache first (unless refresh is forced)
            if (!refreshCache) {
                val cached = recommendationCacheDao.getRecommendationCache(cacheKey)
                if (cached != null && !cached.isExpired()) {
                    Log.d(TAG, "Returning cached recommendations")
                    return cached.getTopRecommendations(limit).map { (contentId, score) ->
                        RecommendationResult(
                            contentId = contentId,
                            score = score,
                            algorithmType = RecommendationAlgorithm.HYBRID,
                            reason = "Personalized hybrid recommendation",
                            cacheKey = cacheKey
                        )
                    }
                }
            }

            // Get watch history count to determine if we have enough data
            val watchHistoryCount = watchHistoryDao.getTotalWatchHistoryCount(userId)
            val hasEnoughData = watchHistoryCount >= MIN_WATCH_HISTORY_FOR_PERSONALIZATION

            // Generate recommendations using hybrid approach
            val recommendations = if (hasEnoughData) {
                generateHybridRecommendations(userId, limit, contentType, userProfile)
            } else {
                // Fallback to popular/trending for new users
                generateColdStartRecommendations(limit, contentType)
            }

            // Cache the results
            cacheRecommendations(
                cacheKey = cacheKey,
                algorithmType = RecommendationAlgorithm.PERSONALIZED,
                contentType = contentType,
                recommendations = recommendations,
                userProfile = userProfile
            )

            Log.d(TAG, "Generated ${recommendations.size} personalized recommendations")
            recommendations

        } catch (e: Exception) {
            Log.e(TAG, "Error getting personalized recommendations", e)
            _lastError.value = "Failed to generate recommendations: ${e.message}"
            emptyList()
        } finally {
            _isLoading.value = false
        }
    }

    /**
     * Gets content-based recommendations similar to the given content
     */
    suspend fun getContentBasedRecommendations(
        contentId: String,
        limit: Int = DEFAULT_RECOMMENDATION_LIMIT
    ): List<RecommendationResult> {
        return try {
            _isLoading.value = true

            val cacheKey = com.tvonnet.debridxtreamiptv.data.local.entity.generateContentBasedCacheKey(
                userId = "default_user",
                contentId = contentId,
                limit = limit
            )

            // Check cache first
            val cached = recommendationCacheDao.getRecommendationCache(cacheKey)
            if (cached != null && !cached.isExpired()) {
                return cached.getTopRecommendations(limit).map { (contentId, score) ->
                    RecommendationResult(
                        contentId = contentId,
                        score = score,
                        algorithmType = RecommendationAlgorithm.CONTENT_BASED,
                        reason = "Similar content you might like",
                        cacheKey = cacheKey
                    )
                }
            }

            // Generate new recommendations
            val recommendations = contentBasedRecommender.getRecommendations(
                seedContentId = contentId,
                limit = limit,
                userId = "default_user"
            )

            // Cache results
            if (recommendations.isNotEmpty()) {
                cacheRecommendations(
                    cacheKey = cacheKey,
                    algorithmType = RecommendationAlgorithm.CONTENT_BASED,
                    contentType = null,
                    recommendations = recommendations
                )
            }

            recommendations

        } catch (e: Exception) {
            Log.e(TAG, "Error getting content-based recommendations", e)
            _lastError.value = "Failed to get similar content: ${e.message}"
            emptyList()
        } finally {
            _isLoading.value = false
        }
    }

    /**
     * Gets trending recommendations
     */
    suspend fun getTrendingRecommendations(
        contentType: String? = null,
        timeWindow: String = "7d",
        limit: Int = DEFAULT_RECOMMENDATION_LIMIT
    ): List<RecommendationResult> {
        return try {
            _isLoading.value = true

            val cacheKey = com.tvonnet.debridxtreamiptv.data.local.entity.generateTrendingCacheKey(
                contentType = contentType,
                limit = limit,
                timeWindow = timeWindow
            )

            // Check cache first
            val cached = recommendationCacheDao.getRecommendationCache(cacheKey)
            if (cached != null && !cached.isExpired()) {
                return cached.getTopRecommendations(limit).map { (contentId, score) ->
                    RecommendationResult(
                        contentId = contentId,
                        score = score,
                        algorithmType = RecommendationAlgorithm.TRENDING,
                        reason = "Trending content",
                        cacheKey = cacheKey
                    )
                }
            }

            // Generate new trending recommendations
            val recommendations = trendingRecommender.getRecommendations(
                contentType = contentType,
                timeWindow = timeWindow,
                limit = limit
            )

            // Cache results
            if (recommendations.isNotEmpty()) {
                cacheRecommendations(
                    cacheKey = cacheKey,
                    algorithmType = RecommendationAlgorithm.TRENDING,
                    contentType = contentType,
                    recommendations = recommendations
                )
            }

            recommendations

        } catch (e: Exception) {
            Log.e(TAG, "Error getting trending recommendations", e)
            _lastError.value = "Failed to get trending content: ${e.message}"
            emptyList()
        } finally {
            _isLoading.value = false
        }
    }

    /**
     * Gets continue watching recommendations
     */
    suspend fun getContinueWatchingRecommendations(
        limit: Int = DEFAULT_RECOMMENDATION_LIMIT
    ): List<RecommendationResult> {
        return try {
            _isLoading.value = true

            val continueWatching = watchHistoryDao.getContinueWatching(limit = limit)

            continueWatching.map { history ->
                RecommendationResult(
                    contentId = history.contentId,
                    score = history.engagementScore,
                    algorithmType = RecommendationAlgorithm.CONTINUE_WATCHING,
                    reason = "Continue watching - ${String.format("%.1f", history.watchProgress * 100)}% complete",
                    metadata = mapOf(
                        "progress" to history.watchProgress,
                        "last_position" to history.lastWatchedPosition,
                        "total_duration" to history.totalDuration,
                        "poster_url" to history.posterUrl,
                        "title" to history.title,
                        "episode_info" to if (history.episodeTitle != null)
                            "S${history.seasonNumber ?: "?"}E${history.episodeNumber ?: "?"}: ${history.episodeTitle}"
                        else null
                    )
                )
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error getting continue watching recommendations", e)
            _lastError.value = "Failed to get continue watching: ${e.message}"
            emptyList()
        } finally {
            _isLoading.value = false
        }
    }

    /**
     * Records user feedback on recommendations
     */
    suspend fun recordRecommendationFeedback(
        cacheKey: String,
        contentId: String,
        feedback: RecommendationFeedback
    ) {
        try {
            engineScope.launch {
                // Update cache with feedback
                val cached = recommendationCacheDao.getRecommendationCache(cacheKey)
                if (cached != null) {
                    val feedbackScore = when (feedback) {
                        RecommendationFeedback.LIKE -> 1.0f
                        RecommendationFeedback.DISLIKE -> -1.0f
                        RecommendationFeedback.NOT_INTERESTED -> -0.5f
                        RecommendationFeedback.CLICK -> 0.2f
                    }

                    recommendationCacheDao.updateUserFeedbackScore(
                        cacheKey = cacheKey,
                        score = feedbackScore
                    )

                    // Log interaction
                    contentInteractionDao.insertInteraction(
                        com.tvonnet.debridxtreamiptv.data.local.entity.ContentInteractionEntity(
                            sessionId = "",
                            contentId = contentId,
                            contentType = "",
                            interactionType = when (feedback) {
                                RecommendationFeedback.LIKE -> "like"
                                RecommendationFeedback.DISLIKE -> "dislike"
                                RecommendationFeedback.NOT_INTERESTED -> "not_interested"
                                RecommendationFeedback.CLICK -> "recommendation_click"
                            },
                            interactionSubType = "recommendation_feedback",
                            recommendationId = cacheKey,
                            interactionValue = mapOf(
                                "feedback" to feedback.name,
                                "feedback_score" to feedbackScore
                            )
                        )
                    )
                }
            }

            Log.d(TAG, "Recorded recommendation feedback: $feedback for content: $contentId")

        } catch (e: Exception) {
            Log.e(TAG, "Error recording recommendation feedback", e)
        }
    }

    /**
     * Updates algorithm weights for personalization
     */
    suspend fun updateAlgorithmWeights(newWeights: Map<String, Float>) {
        try {
            // Validate weights
            val totalWeight = newWeights.values.sum()
            if (kotlin.math.abs(totalWeight - 1.0f) > 0.01f) {
                Log.w(TAG, "Algorithm weights don't sum to 1.0, normalizing...")
                val normalizedWeights = newWeights.mapValues { it.value / totalWeight }
                algorithmWeights = normalizedWeights
            } else {
                algorithmWeights = newWeights
            }

            // Update user profile
            val userId = "default_user"
            userProfileDao.updateAlgorithmWeights(userId, algorithmWeights)

            Log.d(TAG, "Updated algorithm weights: $algorithmWeights")

        } catch (e: Exception) {
            Log.e(TAG, "Error updating algorithm weights", e)
        }
    }

    /**
     * Clears recommendation cache for the user
     */
    suspend fun clearRecommendationCache(userId: String = "default_user") {
        try {
            engineScope.launch {
                recommendationCacheDao.deleteUserCache(userId)
                Log.d(TAG, "Cleared recommendation cache for user: $userId")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error clearing recommendation cache", e)
        }
    }

    /**
     * Gets recommendation performance metrics
     */
    suspend fun getRecommendationMetrics(): RecommendationMetrics? {
        return try {
            val algorithmMetrics = recommendationCacheDao.getAlgorithmPerformanceMetrics()
            val userUsage = recommendationCacheDao.getUserCacheUsage(limit = 1)
            val contentTypePerformance = recommendationCacheDao.getContentTypePerformance()

            RecommendationMetrics(
                totalRecommendations = recommendationCacheDao.getActiveCacheCount(),
                expiredCache = recommendationCacheDao.getExpiredCacheCount(),
                needsRefresh = recommendationCacheDao.getCacheNeedingRefreshCount(),
                algorithmPerformance = algorithmMetrics.associateBy { it.algorithmType },
                topUserCacheUsage = userUsage.firstOrNull(),
                contentTypePerformance = contentTypePerformance.associateBy { it.contentType }
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error getting recommendation metrics", e)
            null
        }
    }

    // Private helper methods

    private suspend fun generateHybridRecommendations(
        userId: String,
        limit: Int,
        contentType: String?,
        userProfile: com.tvonnet.debridxtreamiptv.data.local.entity.UserProfileEntity
    ): List<RecommendationResult> {
        val recommendations = mutableListOf<RecommendationResult>()
        val remainingLimit = limit

        // Get recommendations from each algorithm based on weights
        val contentBasedLimit = (remainingLimit * (algorithmWeights[RecommendationAlgorithm.CONTENT_BASED] ?: 0f)).toInt()
        val collaborativeLimit = (remainingLimit * (algorithmWeights[RecommendationAlgorithm.COLLABORATIVE] ?: 0f)).toInt()
        val trendingLimit = (remainingLimit * (algorithmWeights[RecommendationAlgorithm.TRENDING] ?: 0f)).toInt()
        val popularLimit = (remainingLimit * (algorithmWeights[RecommendationAlgorithm.POPULAR] ?: 0f)).toInt()
        val personalizedLimit = remainingLimit - contentBasedLimit - collaborativeLimit - trendingLimit - popularLimit

        // Content-based recommendations
        if (contentBasedLimit > 0) {
            val contentBased = contentBasedRecommender.getRecommendations(
                userId = userId,
                limit = contentBasedLimit,
                contentType = contentType
            )
            recommendations.addAll(contentBased.map { rec ->
                rec.copy(algorithmType = RecommendationAlgorithm.CONTENT_BASED)
            })
        }

        // Collaborative filtering recommendations
        if (collaborativeLimit > 0) {
            val collaborative = collaborativeRecommender.getRecommendations(
                userId = userId,
                limit = collaborativeLimit,
                contentType = contentType
            )
            recommendations.addAll(collaborative.map { rec ->
                rec.copy(algorithmType = RecommendationAlgorithm.COLLABORATIVE)
            })
        }

        // Trending recommendations
        if (trendingLimit > 0) {
            val trending = trendingRecommender.getRecommendations(
                contentType = contentType,
                limit = trendingLimit,
                timeWindow = "7d"
            )
            recommendations.addAll(trending.map { rec ->
                rec.copy(algorithmType = RecommendationAlgorithm.TRENDING)
            })
        }

        // Popular recommendations
        if (popularLimit > 0) {
            val popular = popularRecommender.getRecommendations(
                contentType = contentType,
                limit = popularLimit
            )
            recommendations.addAll(popular.map { rec ->
                rec.copy(algorithmType = RecommendationAlgorithm.POPULAR)
            })
        }

        // Personalized recommendations
        if (personalizedLimit > 0) {
            val personalized = personalizedRecommender.getRecommendations(
                userId = userId,
                limit = personalizedLimit,
                contentType = contentType,
                userProfile = userProfile
            )
            recommendations.addAll(personalized.map { rec ->
                rec.copy(algorithmType = RecommendationAlgorithm.PERSONALIZED)
            })
        }

        // Remove duplicates and sort by score
        return recommendations
            .distinctBy { it.contentId }
            .sortedByDescending { it.score }
            .take(limit)
    }

    private suspend fun generateColdStartRecommendations(
        limit: Int,
        contentType: String?
    ): List<RecommendationResult> {
        // For new users, return popular/trending content
        val recommendations = mutableListOf<RecommendationResult>()

        // Add trending content (60%)
        val trendingLimit = (limit * 0.6f).toInt()
        val trending = trendingRecommender.getRecommendations(
            contentType = contentType,
            limit = trendingLimit,
            timeWindow = "7d"
        )
        recommendations.addAll(trending.map { rec ->
            rec.copy(algorithmType = RecommendationAlgorithm.TRENDING)
        })

        // Add popular content (40%)
        val popularLimit = limit - trending.size
        if (popularLimit > 0) {
            val popular = popularRecommender.getRecommendations(
                contentType = contentType,
                limit = popularLimit
            )
            recommendations.addAll(popular.map { rec ->
                rec.copy(algorithmType = RecommendationAlgorithm.POPULAR)
            })
        }

        return recommendations.sortedByDescending { it.score }.take(limit)
    }

    private suspend fun cacheRecommendations(
        cacheKey: String,
        algorithmType: String,
        contentType: String?,
        recommendations: List<RecommendationResult>,
        userProfile: com.tvonnet.debridxtreamiptv.data.local.entity.UserProfileEntity? = null
    ) {
        try {
            val contentIds = recommendations.map { it.contentId }
            val contentScores = recommendations.associate { it.contentId to it.score }
            val recommendationReasons = recommendations.associate { it.contentId to it.reason }

            val cacheEntity = RecommendationCacheEntity(
                cacheKey = cacheKey,
                userId = "default_user",
                algorithmType = algorithmType,
                contentType = contentType,
                limit = recommendations.size,
                contentIds = contentIds,
                contentScores = contentScores,
                recommendationReasons = recommendationReasons,
                context = mapOf(
                    "algorithm_weights" to (userProfile?.algorithmWeights ?: algorithmWeights),
                    "user_engagement_level" to (userProfile?.getEngagementLevel() ?: "new")
                ),
                algorithmWeights = userProfile?.algorithmWeights ?: algorithmWeights,
                diversityScore = calculateDiversityScore(recommendations),
                coverageScore = calculateCoverageScore(recommendations, contentType),
                datasetVersion = "v1.0"
            )

            recommendationCacheDao.insertRecommendationCache(cacheEntity)

        } catch (e: Exception) {
            Log.e(TAG, "Error caching recommendations", e)
        }
    }

    private fun calculateDiversityScore(recommendations: List<RecommendationResult>): Float {
        // Simplified diversity calculation
        // In a real implementation, this would analyze content categories, genres, etc.
        return when {
            recommendations.isEmpty() -> 0f
            recommendations.size < 5 -> 0.3f
            recommendations.size < 10 -> 0.6f
            else -> 0.8f
        }
    }

    private fun calculateCoverageScore(
        recommendations: List<RecommendationResult>,
        contentType: String?
    ): Float {
        // Simplified coverage calculation
        // In a real implementation, this would check how well recommendations cover the content space
        return when {
            recommendations.isEmpty() -> 0f
            recommendations.size < 10 -> 0.4f
            recommendations.size < 20 -> 0.7f
            else -> 0.9f
        }
    }
}

// Data classes for recommendation results
data class RecommendationResult(
    val contentId: String,
    val score: Float,
    val algorithmType: String,
    val reason: String,
    val cacheKey: String? = null,
    val metadata: Map<String, Any> = emptyMap()
)

data class RecommendationMetrics(
    val totalRecommendations: Int,
    val expiredCache: Int,
    val needsRefresh: Int,
    val algorithmPerformance: Map<String, com.tvonnet.debridxtreamiptv.data.local.entity.AlgorithmPerformanceMetric>,
    val topUserCacheUsage: com.tvonnet.debridxtreamiptv.data.local.entity.UserCacheUsage?,
    val contentTypePerformance: Map<String, com.tvonnet.debridxtreamiptv.data.local.entity.ContentTypePerformance>
)

enum class RecommendationFeedback {
    LIKE,
    DISLIKE,
    NOT_INTERESTED,
    CLICK
}