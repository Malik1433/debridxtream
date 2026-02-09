package com.tvonnet.debridxtreamiptv.data.recommendation.algorithm

import android.util.Log
import com.tvonnet.debridxtreamiptv.data.local.dao.ContentInteractionDao
import com.tvonnet.debridxtreamiptv.data.local.dao.WatchHistoryDao
import com.tvonnet.debridxtreamiptv.data.recommendation.RecommendationResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

/**
 * Trending recommendation algorithm
 * Recommends content that is currently popular or gaining popularity based on:
 * - Recent watch frequency across all users
 * - Positive engagement trends
 * - Rising content popularity
 * - Time-window based trending analysis
 */
@Singleton
class TrendingRecommender @Inject constructor(
    private val watchHistoryDao: WatchHistoryDao,
    private val contentInteractionDao: ContentInteractionDao
) {
    companion object {
        private const val TAG = "TrendingRecommender"
        private const val DEFAULT_LIMIT = 20
        private const val MIN_INTERACTIONS_FOR_TRENDING = 10
        private const val TRENDING_WINDOW_DAYS = 7
    }

    /**
     * Gets trending recommendations for the specified time window
     */
    suspend fun getRecommendations(
        contentType: String? = null,
        timeWindow: String = "7d",
        limit: Int = DEFAULT_LIMIT
    ): List<RecommendationResult> {
        return try {
            withContext(Dispatchers.IO) {
                Log.d(TAG, "Generating trending recommendations for time window: $timeWindow")

                val timeWindowMs = parseTimeWindow(timeWindow)
                val now = System.currentTimeMillis()
                val cutoffDate = now - timeWindowMs

                // Get recent positive interactions
                val recentPositiveInteractions = contentInteractionDao.getPositiveEngagementInteractions(
                    limit = 1000
                )

                // Get recent watch history
                val recentWatchHistory = watchHistoryDao.getWatchHistoryByContentType(
                    contentType = contentType ?: "",
                    limit = 500
                )

                // Analyze trends from interactions and watch history
                val trendingItems = analyzeTrends(
                    interactions = recentPositiveInteractions,
                    watchHistory = recentWatchHistory,
                    contentType = contentType,
                    cutoffDate = cutoffDate,
                    limit = limit
                )

                Log.d(TAG, "Generated ${trendingItems.size} trending recommendations")
                trendingItems
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error generating trending recommendations", e)
            emptyList()
        }
    }

    private suspend fun analyzeTrends(
        interactions: List<com.tvonnet.debridxtreamiptv.data.local.entity.ContentInteractionEntity>,
        watchHistory: List<com.tvonnet.debridxtreamiptv.data.local.entity.WatchHistoryEntity>,
        contentType: String?,
        cutoffDate: Long,
        limit: Int
    ): List<RecommendationResult> {
        val trendingScores = mutableMapOf<String, TrendingScore>()

        // Analyze interaction trends
        analyzeInteractionTrends(interactions, cutoffDate, trendingScores)

        // Analyze watch history trends
        analyzeWatchHistoryTrends(watchHistory, cutoffDate, trendingScores)

        // Combine scores and generate recommendations
        val recommendations = trendingScores.entries
            .sortedByDescending { it.value.combinedScore }
            .take(limit)
            .map { (contentId, score) ->
                RecommendationResult(
                    contentId = contentId,
                    score = score.combinedScore.coerceIn(0f, 1f),
                    algorithmType = "trending",
                    reason = generateTrendingReason(score),
                    metadata = mapOf(
                        "interaction_trend_score" to score.interactionScore,
                        "watch_trend_score" to score.watchScore,
                        "velocity_score" to score.velocityScore,
                        "consistency_score" to score.consistencyScore,
                        "total_interactions" to score.totalInteractions,
                        "unique_viewers" to score.uniqueViewers
                    )
                )
            }

        return recommendations
    }

    private fun analyzeInteractionTrends(
        interactions: List<com.tvonnet.debridxtreamiptv.data.local.entity.ContentInteractionEntity>,
        cutoffDate: Long,
        trendingScores: MutableMap<String, TrendingScore>
    ) {
        // Group interactions by content
        val contentInteractions = interactions
            .filter { it.timestamp >= cutoffDate }
            .groupBy { it.contentId }

        for ((contentId, contentInteractionsList) in contentInteractions) {
            val totalInteractions = contentInteractionsList.size
            val positiveInteractions = contentInteractionsList.count { it.isPositiveEngagement() }

            // Calculate trend metrics
            val interactionVelocity = calculateInteractionVelocity(contentInteractionsList, cutoffDate)
            val engagementQuality = if (totalInteractions > 0) {
                positiveInteractions.toFloat() / totalInteractions
            } else 0f

            // Calculate trending score
            val interactionScore = calculateInteractionTrendingScore(
                totalInteractions = totalInteractions,
                velocity = interactionVelocity,
                quality = engagementQuality,
                recency = calculateRecencyScore(contentInteractionsList.lastOrNull()?.timestamp ?: System.currentTimeMillis())
            )

            // Update or create trending score
            val existingScore = trendingScores.getOrPut(contentId) {
                TrendingScore(
                    contentId = contentId,
                    interactionScore = 0f,
                    watchScore = 0f,
                    velocityScore = 0f,
                    consistencyScore = 0f,
                    totalInteractions = 0,
                    uniqueViewers = 0,
                    combinedScore = 0f
                )
            }

            existingScore.apply {
                this.interactionScore = (this.interactionScore + interactionScore) * 0.6f
                this.totalInteractions += totalInteractions
                this.velocityScore = (this.velocityScore + interactionVelocity) * 0.4f
            }
        }
    }

    private fun analyzeWatchHistoryTrends(
        watchHistory: List<com.tvonnet.debridxtreamiptv.data.local.entity.WatchHistoryEntity>,
        cutoffDate: Long,
        trendingScores: MutableMap<String, TrendingScore>
    ) {
        // Group watch history by content
        valContentViews = watchHistory
            .filter { it.watchDate >= cutoffDate }
            .groupBy { it.contentId }

        for ((contentId, contentViewList) in contentViews) {
            val totalWatchTime = contentViewList.sumOf { it.watchDuration }
            val averageCompletionRate = if (contentViewList.isNotEmpty()) {
                contentViewList.map { it.watchProgress }.average().toFloat()
            } else 0f
            val uniqueViewers = contentViewList.distinctBy { it.sessionId }.size

            // Calculate watch trends
            val watchVelocity = calculateWatchVelocity(contentContentViews, cutoffDate)
            val watchQuality = averageCompletionRate

            // Calculate watch trending score
            val watchScore = calculateWatchTrendingScore(
                totalWatchTime = totalWatchTime,
                completionRate = averageCompletionRate,
                velocity = watchVelocity,
                uniqueViewers = uniqueViewers
            )

            // Update or create trending score
            val existingScore = trendingScores.getOrPut(contentId) {
                TrendingScore(
                    contentId = contentId,
                    interactionScore = 0f,
                    watchScore = 0f,
                    velocityScore = 0f,
                    consistencyScore = 0f,
                    totalInteractions = 0,
                    uniqueViewers = 0,
                    combinedScore = 0f
                )
            }

            existingScore.apply {
                this.watchScore = (this.watchScore + watchScore) * 0.4f
                this.uniqueViewers = uniqueViewers
                this.consistencyScore = calculateConsistencyScore(contentViewList) * 0.3f
            }
        }
    }

    private fun calculateInteractionVelocity(
        interactions: List<com.tvonnet.debridxtreamiptv.data.local.entity.ContentInteractionEntity>,
        cutoffDate: Long
    ): Float {
        if (interactions.isEmpty()) return 0f

        // Calculate interaction frequency over time
        val timeSpan = (System.currentTimeMillis() - cutoffDate).toFloat()
        val interactionRate = interactions.size.toFloat() / (timeSpan / (24 * 60 * 60 * 1000)) // interactions per day

        // Normalize velocity score
        return (interactionRate / 10f).coerceAtMost(1f)
    }

    private fun calculateWatchVelocity(
        contentView: List<com.tvonnet.debridxtreamiptv.data.local.entity.WatchHistoryEntity>,
        cutoffDate: Long
    ): Float {
        if (contentView.isEmpty()) return 0f

        // Calculate watch frequency over time
        val timeSpan = (System.currentTimeMillis() - cutoffDate).toFloat()
        val watchRate = contentView.size.toFloat() / (timeSpan / (24 * 60 * 60 * 1000)) // watches per day

        return (watchRate / 5f).coerceAtMost(1f)
    }

    private fun calculateRecencyScore(timestamp: Long): Float {
        val hoursSince = (System.currentTimeMillis() - timestamp) / (60 * 60 * 1000)
        return when {
            hoursSince <= 1 -> 1.0f // Very recent
            hoursSince <= 6 -> 0.8f // Recent
            hoursSince <= 24 -> 0.6f // Somewhat recent
            hoursSince <= 72 -> 0.4f // Recent past
            else -> 0.2f // Older
        }
    }

    private fun calculateInteractionTrendingScore(
        totalInteractions: Int,
        velocity: Float,
        quality: Float,
        recency: Float
    ): Float {
        return (totalInteractions / 100f).coerceAtMost(1f) * 0.4f +
               velocity * 0.3f +
               quality * 0.2f +
               recency * 0.1f
    }

    private fun calculateWatchTrendingScore(
        totalWatchTime: Long,
        completionRate: Float,
        velocity: Float,
        uniqueViewers: Int
    ): Float {
        val watchTimeScore = (totalWatchTime / (60 * 60 * 1000f)).coerceAtMost(1f) // Convert minutes to score
        return watchTimeScore * 0.5f +
               completionRate * 0.3f +
               velocity * 0.2f
    }

    private fun calculateConsistencyScore(
        events: List<Any>
    ): Float {
        // Calculate consistency based on interaction/watch patterns
        // This is a simplified implementation
        return when {
            events.size >= 10 -> 1.0f
            events.size >= 5 -> 0.7f
            events.size >= 3 -> 0.4f
            else -> 0.2f
        }
    }

    private fun generateTrendingReason(score: TrendingScore): String {
        return when {
            score.velocityScore > 0.7f -> "Trending rapidly"
            score.interactionScore > 0.7f -> "High engagement"
            score.watchScore > 0.7f -> "High watch time"
            score.consistencyScore > 0.7f -> "Consistent popularity"
            else -> "Growing in popularity"
        }
    }

    private fun parseTimeWindow(timeWindow: String): Long {
        return when (timeWindow.lowercase()) {
            "1d" -> 24 * 60 * 60 * 1000L
            "3d" -> 3 * 24 * 60 * 60 * 1000L
            "7d" -> 7 * 24 * 60 * 60 * 1000L
            "14d" -> 14 * 24 * 60 * 60 * 1000L
            "30d" -> 30 * 24 * 60 * 60 * 1000L
            else -> 7 * 24 * 60 * 60 * 1000L // Default to 7 days
        }
    }

    /**
     * Data class for tracking trending scores
     */
    private data class TrendingScore(
        val contentId: String,
        var interactionScore: Float,
        var watchScore: Float,
        var velocityScore: Float,
        var consistencyScore: Float,
        var totalInteractions: Int,
        var uniqueViewers: Int,
        var combinedScore: Float = 0f
    ) {
        fun updateCombinedScore() {
            combinedScore = (interactionScore + watchScore + velocityScore + consistencyScore) / 4f
        }
    }
}