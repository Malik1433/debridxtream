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
 * Content-based recommendation algorithm
 * Recommends content similar to what the user has watched based on:
 * - Content characteristics (genres, categories, metadata)
 * - User viewing patterns and preferences
 * - Content similarity scoring
 */
@Singleton
class ContentBasedRecommender @Inject constructor(
    private val watchHistoryDao: WatchHistoryDao,
    private val contentInteractionDao: ContentInteractionDao
) {
    companion object {
        private const val TAG = "ContentBasedRecommender"
        private const val DEFAULT_LIMIT = 20
        private const val SIMILARITY_THRESHOLD = 0.3f
        private const val MIN_INTERACTIONS_FOR_RELATIONSHIP = 3
    }

    /**
     * Gets content-based recommendations for the user
     */
    suspend fun getRecommendations(
        userId: String = "default_user",
        limit: Int = DEFAULT_LIMIT,
        contentType: String? = null,
        seedContentId: String? = null
    ): List<RecommendationResult> {
        return try {
            withContext(Dispatchers.IO) {
                Log.d(TAG, "Generating content-based recommendations")

                // Get user's completed watch history
                val watchHistory = watchHistoryDao.getCompletedContent(userId)
                    .sortedByDescending { it.watchDate }
                    .take(50) // Consider last 50 completed items

                if (watchHistory.isEmpty()) {
                    Log.d(TAG, "No watch history available for content-based recommendations")
                    emptyList()
                } else {
                    generateRecommendations(
                        watchHistory = watchHistory,
                        limit = limit,
                        contentType = contentType,
                        seedContentId = seedContentId
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error generating content-based recommendations", e)
            emptyList()
        }
    }

    /**
     * Gets recommendations similar to a specific content item
     */
    suspend fun getRecommendations(
        seedContentId: String,
        userId: String = "default_user",
        limit: Int = DEFAULT_LIMIT,
        contentType: String? = null
    ): List<RecommendationResult> {
        return try {
            withContext(Dispatchers.IO) {
                Log.d(TAG, "Generating recommendations similar to content: $seedContentId")

                val contentInteractions = contentInteractionDao.getInteractionsByContent(
                    contentId = seedContentId,
                    userId = userId
                )

                // Extract characteristics from seed content
                val seedCharacteristics = extractContentCharacteristics(contentInteractions)

                if (seedCharacteristics.isEmpty()) {
                    Log.w(TAG, "No characteristics found for seed content: $seedContentId")
                    emptyList()
                } else {
                    // Find similar content based on characteristics
                    findSimilarContent(
                        seedCharacteristics = seedCharacteristics,
                        excludeContentId = seedContentId,
                        limit = limit,
                        contentType = contentType
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error generating similar content recommendations", e)
            emptyList()
        }
    }

    private suspend fun generateRecommendations(
        watchHistory: List<com.tvonnet.debridxtreamiptv.data.local.entity.WatchHistoryEntity>,
        limit: Int,
        contentType: String?,
        seedContentId: String? = null
    ): List<RecommendationResult> {
        val recommendations = mutableListOf<RecommendationResult>()
        val processedContent = mutableSetOf<String>()

        // Analyze user preferences from watch history
        val userPreferences = analyzeUserPreferences(watchHistory)

        // For each watched content, find similar items
        for (historyItem in watchHistory.take(20)) { // Analyze top 20 most recent
            if (processedContent.contains(historyItem.contentId)) continue

            // Get content interactions for this item
            val interactions = contentInteractionDao.getInteractionsByContent(
                contentId = historyItem.contentId,
                userId = "default_user"
            )

            val contentCharacteristics = extractContentCharacteristics(interactions)
            if (contentCharacteristics.isEmpty()) continue

            // Find similar content
            val similarContent = findSimilarContent(
                seedCharacteristics = contentCharacteristics,
                excludeContentId = historyItem.contentId,
                limit = (limit / 4).coerceAtLeast(1), // Distribute across multiple seeds
                contentType = contentType
            )

            // Apply preference weighting
            val weightedRecommendations = similarContent.map { recommendation ->
                val preferenceScore = calculatePreferenceScore(
                    recommendation = recommendation,
                    userPreferences = userPreferences,
                    historyItem = historyItem
                )
                recommendation.copy(
                    score = recommendation.score * preferenceScore,
                    reason = "Similar to ${historyItem.title}",
                    metadata = recommendation.metadata + mapOf(
                        "seed_content_id" to historyItem.contentId,
                        "seed_title" to historyItem.title,
                        "preference_score" to preferenceScore
                    )
                )
            }

            recommendations.addAll(weightedRecommendations)
            processedContent.add(historyItem.contentId)

            if (recommendations.size >= limit * 2) break // Generate extras to filter from
        }

        // Remove duplicates, sort by score, and return top results
        return recommendations
            .distinctBy { it.contentId }
            .sortedByDescending { it.score }
            .take(limit)
    }

    private suspend fun findSimilarContent(
        seedCharacteristics: Map<String, Any>,
        excludeContentId: String,
        limit: Int,
        contentType: String?
    ): List<RecommendationResult> {
        val recommendations = mutableListOf<RecommendationResult>()

        // This is a simplified implementation
        // In a real system, this would:
        // 1. Query content catalog with similar characteristics
        // 2. Calculate similarity scores
        // 3. Filter by content type if specified
        // 4. Return top matches

        // For now, return empty list - this would be implemented with content catalog integration
        return recommendations
    }

    private fun extractContentCharacteristics(
        interactions: List<com.tvonnet.debridxtreamiptv.data.local.entity.ContentInteractionEntity>
    ): Map<String, Any> {
        val characteristics = mutableMapOf<String, Any>()

        // Extract genres from content metadata
        val genres = interactions
            .flatMap { it.contentGenres }
            .distinct()
            .filter { it.isNotBlank() }

        if (genres.isNotEmpty()) {
            characteristics["genres"] = genres
        }

        // Extract categories
        val categories = interactions
            .flatMap { it.contentCategories }
            .distinct()
            .filter { it.isNotBlank() }

        if (categories.isNotEmpty()) {
            characteristics["categories"] = categories
        }

        // Extract content type distribution
        val contentTypeDistribution = interactions
            .groupBy { it.contentType }
            .mapValues { it.size }

        if (contentTypeDistribution.isNotEmpty()) {
            characteristics["content_type_distribution"] = contentTypeDistribution
        }

        // Calculate interaction patterns
        val positiveInteractions = interactions.count { it.isPositiveEngagement() }
        val totalInteractions = interactions.size
        if (totalInteractions > 0) {
            characteristics["engagement_ratio"] = positiveInteractions.toFloat() / totalInteractions
        }

        return characteristics
    }

    private fun analyzeUserPreferences(
        watchHistory: List<com.tvonnet.debridxtreamiptv.data.local.entity.WatchHistoryEntity>
    ): UserPreferences {
        val genrePreferences = mutableMapOf<String, Float>()
        val categoryPreferences = mutableMapOf<String, Float>()
        val contentTypePreferences = mutableMapOf<String, Float>()

        // Analyze completed content for preferences
        val completedContent = watchHistory.filter {
            it.completionStatus == "completed"
        }

        for (item in completedContent) {
            // Extract genre preferences
            item.genres.forEach { genre ->
                genrePreferences[genre] = (genrePreferences[genre] ?: 0f) + 1f
            }

            // Extract category preferences
            item.categories.forEach { category ->
                categoryPreferences[category] = (categoryPreferences[category] ?: 0f) + 1f
            }

            // Extract content type preferences
            contentTypePreferences[item.contentType] =
                (contentTypePreferences[item.contentType] ?: 0f) + 1f
        }

        // Normalize preferences
        val totalGenreCount = genrePreferences.values.sum()
        val totalCategoryCount = categoryPreferences.values.sum()
        val totalContentTypeCount = contentTypePreferences.values.sum()

        val normalizedGenres = if (totalGenreCount > 0) {
            genrePreferences.mapValues { it / totalGenreCount }
        } else emptyMap()

        val normalizedCategories = if (totalCategoryCount > 0) {
            categoryPreferences.mapValues { it / totalCategoryCount }
        } else emptyMap()

        val normalizedContentTypes = if (totalContentTypeCount > 0) {
            contentTypePreferences.mapValues { it / totalContentTypeCount }
        } else emptyMap()

        return UserPreferences(
            genrePreferences = normalizedGenres,
            categoryPreferences = normalizedCategories,
            contentTypePreferences = normalizedContentTypes,
            averageWatchTime = completedContent.map { it.watchDuration }.average().toLong(),
            completionRate = if (completedContent.isNotEmpty()) {
                completedContent.count { it.watchProgress >= 0.9f }.toFloat() / completedContent.size
            } else 0f
        )
    }

    private fun calculatePreferenceScore(
        recommendation: RecommendationResult,
        userPreferences: UserPreferences,
        historyItem: com.tvonnet.debridxtreamiptv.data.local.entity.WatchHistoryEntity
    ): Float {
        var score = 1.0f

        // Boost score if content matches user's preferred genres
        val contentGenres = recommendation.metadata["genres"] as? List<String>
        contentGenres?.forEach { genre ->
            userPreferences.genrePreferences[genre]?.let { genreScore ->
                score += genreScore * 0.3f
            }
        }

        // Boost score if content matches user's preferred categories
        val contentCategories = recommendation.metadata["categories"] as? List<String>
        contentCategories?.forEach { category ->
            userPreferences.categoryPreferences[category]?.let { categoryScore ->
                score += categoryScore * 0.2f
            }
        }

        // Boost score if content type matches user's preference
        val contentType = recommendation.metadata["content_type"] as? String
        if (contentType != null) {
            userPreferences.contentTypePreferences[contentType]?.let { typeScore ->
                score += typeScore * 0.1f
            }
        }

        // Consider recency - slightly boost recently watched similar content
        val daysSinceWatched = (System.currentTimeMillis() - historyItem.watchDate) / (24 * 60 * 60 * 1000)
        if (daysSinceWatched <= 7) {
            score *= 1.1f // 10% boost for recent similar content
        } else if (daysSinceWatched <= 30) {
            score *= 1.05f // 5% boost for moderately recent content
        }

        return score.coerceIn(0.1f, 2.0f)
    }

    /**
     * Data class representing user preferences derived from watch history
     */
    private data class UserPreferences(
        val genrePreferences: Map<String, Float>,
        val categoryPreferences: Map<String, Float>,
        val contentTypePreferences: Map<String, Float>,
        val averageWatchTime: Long,
        val completionRate: Float
    )
}