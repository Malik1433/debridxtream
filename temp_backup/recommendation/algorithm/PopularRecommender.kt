package com.tvonnet.debridxtreamiptv.data.recommendation.algorithm

import android.util.Log
import com.tvonnet.debridxtreamiptv.data.local.dao.ContentInteractionDao
import com.tvonnet.debridxtreamiptv.data.local.dao.WatchHistoryDao
import com.tvonnet.debridxtreamiptv.data.recommendation.RecommendationResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Popular content recommendation algorithm
 * Recommends content based on overall popularity metrics:
 * - Most watched content across all users
 * - High engagement rates and positive feedback
 * - Time-based popularity windows (all-time, recent, trending)
 * - Content type and category-specific popularity
 */
@Singleton
class PopularRecommender @Inject constructor(
    private val watchHistoryDao: WatchHistoryDao,
    private val contentInteractionDao: ContentInteractionDao
) {
    companion object {
        private const val TAG = "PopularRecommender"
        private const val DEFAULT_LIMIT = 20
        private const val MIN_WATCHES_FOR_POPULARITY = 10
        private const val TRENDING_WINDOW_DAYS = 7
    }

    /**
     * Gets popularity-based recommendations
     */
    suspend fun getRecommendations(
        contentType: String? = null,
        timeWindow: String = "all_time", // "all_time", "recent", "trending"
        limit: Int = DEFAULT_LIMIT,
        excludeWatched: Boolean = true,
        userId: String? = null
    ): List<RecommendationResult> {
        return try {
            withContext(Dispatchers.IO) {
                Log.d(TAG, "Generating popularity-based recommendations for time window: $timeWindow")

                val alreadyWatched = if (excludeWatched && userId != null) {
                    watchHistoryDao.getWatchedContentIds(userId).toSet()
                } else {
                    emptySet()
                }

                val recommendations = when (timeWindow) {
                    "trending" -> getTrendingRecommendations(contentType, limit, alreadyWatched)
                    "recent" -> getRecentPopularRecommendations(contentType, limit, alreadyWatched)
                    "all_time" -> getAllTimePopularRecommendations(contentType, limit, alreadyWatched)
                    else -> getAllTimePopularRecommendations(contentType, limit, alreadyWatched)
                }

                Log.d(TAG, "Generated ${recommendations.size} popularity-based recommendations")
                recommendations
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error generating popularity recommendations", e)
            emptyList()
        }
    }

    private suspend fun getAllTimePopularRecommendations(
        contentType: String?,
        limit: Int,
        excludeContent: Set<String>
    ): List<RecommendationResult> {
        // Get most watched content
        val mostWatched = watchHistoryDao.getMostWatchedContent(
            contentType = contentType ?: "",
            limit = limit * 2 // Get more to filter from
        )

        // Get highest rated content
        val highestRated = contentInteractionDao.getHighestRatedContent(
            contentType = contentType ?: "",
            minInteractions = MIN_WATCHES_FOR_POPULARITY,
            limit = limit * 2
        )

        // Combine and score popularity
        val popularityScores = mutableMapOf<String, PopularityScore>()

        // Process most watched content
        mostWatched.forEach { contentStats ->
            if (!excludeContent.contains(contentStats.contentId)) {
                val watchScore = calculateWatchPopularityScore(contentStats)
                val existingScore = popularityScores.getOrPut(contentStats.contentId) {
                    PopularityScore(contentStats.contentId)
                }
                existingScore.addWatchScore(watchScore, contentStats.totalWatchTime)
            }
        }

        // Process highest rated content
        highestRated.forEach { contentStats ->
            if (!excludeContent.contains(contentStats.contentId)) {
                val ratingScore = calculateRatingPopularityScore(contentStats)
                val existingScore = popularityScores.getOrPut(contentStats.contentId) {
                    PopularityScore(contentStats.contentId)
                }
                existingScore.addRatingScore(ratingScore, contentStats.positiveInteractions.toFloat())
            }
        }

        // Convert to recommendations
        return popularityScores.values
            .sortedByDescending { it.finalScore }
            .take(limit)
            .map { score ->
                RecommendationResult(
                    contentId = score.contentId,
                    score = score.finalScore.coerceIn(0f, 1f),
                    algorithmType = "popular",
                    reason = generatePopularReason(score),
                    metadata = mapOf(
                        "popularity_type" to "all_time",
                        "watch_score" to score.watchScore,
                        "rating_score" to score.ratingScore,
                        "total_watches" to score.totalWatches,
                        "positive_interactions" to score.positiveInteractions,
                        "algorithm_variant" to "all_time_popular"
                    )
                )
            }
    }

    private suspend fun getRecentPopularRecommendations(
        contentType: String?,
        limit: Int,
        excludeContent: Set<String>
    ): List<RecommendationResult> {
        val recentWindowMs = 30L * 24 * 60 * 60 * 1000 // 30 days
        val cutoffDate = System.currentTimeMillis() - recentWindowMs

        // Get recent popular content
        val recentPopular = watchHistoryDao.getRecentPopularContent(
            contentType = contentType ?: "",
            since = cutoffDate,
            minWatches = MIN_WATCHES_FOR_POPULARITY / 2, // Lower threshold for recent content
            limit = limit * 2
        )

        val popularityScores = mutableMapOf<String, PopularityScore>()

        recentPopular.forEach { contentStats ->
            if (!excludeContent.contains(contentStats.contentId)) {
                val recencyScore = calculateRecencyScore(contentStats.lastWatched)
                val watchScore = calculateWatchPopularityScore(contentStats) * (1f + recencyScore)

                val score = popularityScores.getOrPut(contentStats.contentId) {
                    PopularityScore(contentStats.contentId)
                }
                score.addWatchScore(watchScore, contentStats.totalWatchTime)
                score.recencyBonus = recencyScore
            }
        }

        return popularityScores.values
            .sortedByDescending { it.finalScore }
            .take(limit)
            .map { score ->
                RecommendationResult(
                    contentId = score.contentId,
                    score = score.finalScore.coerceIn(0f, 1f),
                    algorithmType = "popular",
                    reason = "Recently popular content",
                    metadata = mapOf(
                        "popularity_type" to "recent",
                        "recency_bonus" to score.recencyBonus,
                        "algorithm_variant" to "recent_popular"
                    )
                )
            }
    }

    private suspend fun getTrendingRecommendations(
        contentType: String?,
        limit: Int,
        excludeContent: Set<String>
    ): List<RecommendationResult> {
        // This is similar to TrendingRecommender but focuses on pure popularity metrics
        val trendingWindowMs = 7L * 24 * 60 * 60 * 1000 // 7 days
        val cutoffDate = System.currentTimeMillis() - trendingWindowMs

        // Get content with rising popularity
        val trendingContent = contentInteractionDao.getTrendingContent(
            since = cutoffDate,
            contentType = contentType ?: "",
            limit = limit * 2
        )

        val popularityScores = mutableMapOf<String, PopularityScore>()

        trendingContent.forEach { contentStats ->
            if (!excludeContent.contains(contentStats.contentId)) {
                // Calculate trending velocity
                val velocityScore = calculateTrendingVelocity(contentStats)
                val watchScore = calculateWatchPopularityScore(contentStats)

                val score = popularityScores.getOrPut(contentStats.contentId) {
                    PopularityScore(contentStats.contentId)
                }
                score.addWatchScore(watchScore, contentStats.totalWatchTime)
                score.velocityBonus = velocityScore
            }
        }

        return popularityScores.values
            .sortedByDescending { it.finalScore }
            .take(limit)
            .map { score ->
                RecommendationResult(
                    contentId = score.contentId,
                    score = score.finalScore.coerceIn(0f, 1f),
                    algorithmType = "popular",
                    reason = "Trending popular content",
                    metadata = mapOf(
                        "popularity_type" to "trending",
                        "velocity_bonus" to score.velocityBonus,
                        "algorithm_variant" to "trending_popular"
                    )
                )
            }
    }

    private fun calculateWatchPopularityScore(
        contentStats: Any // Would be a data class with watch statistics
    ): Float {
        // This would extract actual stats from the contentStats object
        // For now, using placeholder calculations
        val totalWatches = 100f // placeholder
        val uniqueViewers = 80f // placeholder
        val averageCompletionRate = 0.7f // placeholder

        // Normalize and combine metrics
        val watchScore = (totalWatches / 1000f).coerceAtMost(1f) * 0.4f
        val viewerScore = (uniqueViewers / 500f).coerceAtMost(1f) * 0.3f
        val completionScore = averageCompletionRate * 0.3f

        return (watchScore + viewerScore + completionScore)
    }

    private fun calculateRatingPopularityScore(
        contentStats: Any // Would be a data class with rating statistics
    ): Float {
        // Extract rating metrics
        val totalInteractions = 200f // placeholder
        val positiveInteractions = 150f // placeholder
        val averageRating = 4.2f // placeholder

        val engagementScore = (totalInteractions / 500f).coerceAtMost(1f) * 0.3f
        val positivityScore = (positiveInteractions / totalInteractions) * 0.4f
        val ratingScore = (averageRating / 5f) * 0.3f

        return (engagementScore + positivityScore + ratingScore)
    }

    private fun calculateRecencyScore(timestamp: Long): Float {
        val daysSince = (System.currentTimeMillis() - timestamp) / (24 * 60 * 60 * 1000)
        return when {
            daysSince <= 1 -> 1.0f
            daysSince <= 3 -> 0.8f
            daysSince <= 7 -> 0.6f
            daysSince <= 14 -> 0.4f
            daysSince <= 30 -> 0.2f
            else -> 0.1f
        }
    }

    private fun calculateTrendingVelocity(
        contentStats: Any // Would be a data class with trending statistics
    ): Float {
        // Calculate how quickly popularity is increasing
        val recentGrowth = 1.5f // placeholder growth rate
        val sustainedGrowth = 0.8f // placeholder sustainability factor

        return (recentGrowth * 0.7f + sustainedGrowth * 0.3f).coerceAtMost(2f)
    }

    private fun generatePopularReason(score: PopularityScore): String {
        return when {
            score.velocityBonus > 0.5f -> "Rapidly gaining popularity"
            score.recencyBonus > 0.7f -> "Recently popular content"
            score.ratingScore > 0.8f -> "Highly rated by viewers"
            score.watchScore > 0.8f -> "Most watched content"
            score.positiveInteractions > 100 -> "Loved by many viewers"
            else -> "Popular content"
        }
    }

    /**
     * Data class for tracking popularity scores
     */
    private data class PopularityScore(
        val contentId: String,
        var watchScore: Float = 0f,
        var ratingScore: Float = 0f,
        var totalWatches: Long = 0L,
        var positiveInteractions: Int = 0,
        var recencyBonus: Float = 0f,
        var velocityBonus: Float = 0f
    ) {
        val finalScore: Float
            get() = (watchScore * 0.5f + ratingScore * 0.3f + recencyBonus * 0.1f + velocityBonus * 0.1f)
                .coerceIn(0f, 1f)

        fun addWatchScore(score: Float, watches: Long) {
            watchScore = maxOf(watchScore, score)
            totalWatches = maxOf(totalWatches, watches)
        }

        fun addRatingScore(score: Float, interactions: Float) {
            ratingScore = maxOf(ratingScore, score)
            positiveInteractions = maxOf(positiveInteractions, interactions.toInt())
        }
    }
}