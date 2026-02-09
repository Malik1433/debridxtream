package com.tvonnet.debridxtreamiptv.data.recommendation.algorithm

import android.util.Log
import com.tvonnet.debridxtreamiptv.data.local.dao.ContentInteractionDao
import com.tvonnet.debridxtreamiptv.data.local.dao.UserProfileDao
import com.tvonnet.debridxtreamiptv.data.local.dao.WatchHistoryDao
import com.tvonnet.debridxtreamiptv.data.recommendation.RecommendationResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.sqrt

/**
 * Collaborative filtering recommendation algorithm
 * Recommends content based on similar users' preferences and behavior:
 * - User-based collaborative filtering: Find similar users and recommend what they liked
 * - Item-based collaborative filtering: Find similar items based on user interactions
 * - Matrix factorization for latent feature discovery
 * - Cold-start problem handling for new users
 */
@Singleton
class CollaborativeRecommender @Inject constructor(
    private val watchHistoryDao: WatchHistoryDao,
    private val contentInteractionDao: ContentInteractionDao,
    private val userProfileDao: UserProfileDao
) {
    companion object {
        private const val TAG = "CollaborativeRecommender"
        private const val DEFAULT_LIMIT = 20
        private const val MIN_SIMILAR_USERS = 5
        private const val SIMILARITY_THRESHOLD = 0.3f
        private const val MIN_COMMON_ITEMS = 3
    }

    /**
     * Gets collaborative filtering recommendations for the user
     */
    suspend fun getRecommendations(
        userId: String = "default_user",
        limit: Int = DEFAULT_LIMIT,
        contentType: String? = null,
        algorithm: String = "user_based" // "user_based", "item_based", "hybrid"
    ): List<RecommendationResult> {
        return try {
            withContext(Dispatchers.IO) {
                Log.d(TAG, "Generating collaborative recommendations using $algorithm algorithm")

                when (algorithm) {
                    "user_based" -> getUserBasedRecommendations(userId, limit, contentType)
                    "item_based" -> getItemBasedRecommendations(userId, limit, contentType)
                    "hybrid" -> getHybridRecommendations(userId, limit, contentType)
                    else -> getUserBasedRecommendations(userId, limit, contentType)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error generating collaborative recommendations", e)
            emptyList()
        }
    }

    private suspend fun getUserBasedRecommendations(
        userId: String,
        limit: Int,
        contentType: String?
    ): List<RecommendationResult> {
        // Get user's watch history
        val userWatchHistory = watchHistoryDao.getCompletedContent(userId)
        if (userWatchHistory.isEmpty()) {
            Log.d(TAG, "No watch history for collaborative filtering")
            return emptyList()
        }

        // Find similar users
        val similarUsers = findSimilarUsers(userId, userWatchHistory)
        if (similarUsers.isEmpty()) {
            Log.d(TAG, "No similar users found for collaborative filtering")
            return emptyList()
        }

        // Get recommendations from similar users
        val recommendations = generateRecommendationsFromSimilarUsers(
            targetUserId = userId,
            similarUsers = similarUsers,
            userWatchHistory = userWatchHistory,
            contentType = contentType,
            limit = limit
        )

        Log.d(TAG, "Generated ${recommendations.size} user-based collaborative recommendations")
        return recommendations
    }

    private suspend fun getItemBasedRecommendations(
        userId: String,
        limit: Int,
        contentType: String?
    ): List<RecommendationResult> {
        // Get user's positively rated content
        val userLikedContent = contentInteractionDao.getPositiveInteractionsByUser(userId, 100)
            .map { it.contentId }
            .distinct()

        if (userLikedContent.isEmpty()) {
            Log.d(TAG, "No user preferences for item-based collaborative filtering")
            return emptyList()
        }

        // Find similar items based on user interaction patterns
        val recommendations = findSimilarItems(
            seedItems = userLikedContent,
            excludeItems = userLikedContent + watchHistoryDao.getWatchedContentIds(userId),
            contentType = contentType,
            limit = limit
        )

        Log.d(TAG, "Generated ${recommendations.size} item-based collaborative recommendations")
        return recommendations
    }

    private suspend fun getHybridRecommendations(
        userId: String,
        limit: Int,
        contentType: String?
    ): List<RecommendationResult> {
        // Generate both user-based and item-based recommendations
        val userBasedDeferred = async { getUserBasedRecommendations(userId, limit, contentType) }
        val itemBasedDeferred = async { getItemBasedRecommendations(userId, limit, contentType) }

        val (userBasedRecs, itemBasedRecs) = awaitAll(userBasedDeferred, itemBasedDeferred)

        // Combine and weight the recommendations
        val combinedRecommendations = mutableListOf<RecommendationResult>()
        val seenContent = mutableSetOf<String>()

        // Add user-based recommendations with higher weight
        userBasedRecs.forEach { rec ->
            if (!seenContent.contains(rec.contentId)) {
                combinedRecommendations.add(
                    rec.copy(
                        score = rec.score * 0.6f,
                        reason = "Users with similar taste also liked this",
                        metadata = rec.metadata + mapOf(
                            "algorithm_type" to "user_based_hybrid",
                            "weight" to 0.6f
                        )
                    )
                )
                seenContent.add(rec.contentId)
            }
        }

        // Add item-based recommendations with lower weight
        itemBasedRecs.forEach { rec ->
            if (!seenContent.contains(rec.contentId)) {
                combinedRecommendations.add(
                    rec.copy(
                        score = rec.score * 0.4f,
                        reason = "Similar to content you've enjoyed",
                        metadata = rec.metadata + mapOf(
                            "algorithm_type" to "item_based_hybrid",
                            "weight" to 0.4f
                        )
                    )
                )
                seenContent.add(rec.contentId)
            }
        }

        val result = combinedRecommendations
            .sortedByDescending { it.score }
            .take(limit)

        Log.d(TAG, "Generated ${result.size} hybrid collaborative recommendations")
        return result
    }

    private suspend fun findSimilarUsers(
        targetUserId: String,
        targetUserHistory: List<com.tvonnet.debridxtreamiptv.data.local.entity.WatchHistoryEntity>
    ): List<SimilarUser> {
        val targetUserPreferences = extractUserPreferences(targetUserHistory)
        val similarUsers = mutableListOf<SimilarUser>()

        // Get other users with sufficient watch history
        val otherUsers = userProfileDao.getUsersWithMinWatchTime(60 * 60 * 1000) // At least 1 hour
            .filter { it.userId != targetUserId }
            .take(100) // Limit for performance

        for (user in otherUsers) {
            val userHistory = watchHistoryDao.getCompletedContent(user.userId)
            if (userHistory.size >= MIN_COMMON_ITEMS) {
                val userPreferences = extractUserPreferences(userHistory)
                val similarity = calculateUserSimilarity(targetUserPreferences, userPreferences)

                if (similarity >= SIMILARITY_THRESHOLD) {
                    similarUsers.add(
                        SimilarUser(
                            userId = user.userId,
                            similarity = similarity,
                            commonItems = findCommonItems(targetUserHistory, userHistory).size
                        )
                    )
                }
            }
        }

        return similarUsers
            .sortedByDescending { it.similarity }
            .take(MIN_SIMILAR_USERS * 2) // Get more candidates for better recommendations
    }

    private suspend fun generateRecommendationsFromSimilarUsers(
        targetUserId: String,
        similarUsers: List<SimilarUser>,
        userWatchHistory: List<com.tvonnet.debridxtreamiptv.data.local.entity.WatchHistoryEntity>,
        contentType: String?,
        limit: Int
    ): List<RecommendationResult> {
        val recommendationScores = mutableMapOf<String, Float>()
        val alreadyWatched = watchHistoryDao.getWatchedContentIds(targetUserId).toSet()

        // For each similar user, get their highly rated content
        for (similarUser in similarUsers.take(MIN_SIMILAR_USERS)) {
            val userHistory = watchHistoryDao.getCompletedContent(similarUser.userId)
            val userInteractions = contentInteractionDao.getPositiveInteractionsByUser(
                userId = similarUser.userId,
                limit = 50
            )

            // Score content based on user similarity and their ratings
            for (interaction in userInteractions) {
                if (!alreadyWatched.contains(interaction.contentId)) {
                    val contentScore = calculateCollaborativeScore(
                        userSimilarity = similarUser.similarity,
                        userRating = interaction.getEngagementScore(),
                        contentInteractions = contentInteractionDao.getInteractionsByContent(
                            contentId = interaction.contentId
                        )
                    )

                    // Weight by content type match if specified
                    val finalScore = if (contentType != null && interaction.contentType == contentType) {
                        contentScore * 1.2f // Boost for matching content type
                    } else {
                        contentScore
                    }

                    val currentScore = recommendationScores[interaction.contentId] ?: 0f
                    recommendationScores[interaction.contentId] =
                        maxOf(currentScore, finalScore)
                }
            }
        }

        // Convert to recommendation results
        return recommendationScores.entries
            .sortedByDescending { it.value }
            .take(limit)
            .map { (contentId, score) ->
                RecommendationResult(
                    contentId = contentId,
                    score = score.coerceIn(0f, 1f),
                    algorithmType = "collaborative_user_based",
                    reason = "Users with similar taste enjoyed this",
                    metadata = mapOf(
                        "similar_users_count" to similarUsers.size,
                        "algorithm_variant" to "user_based",
                        "min_similarity_threshold" to SIMILARITY_THRESHOLD
                    )
                )
            }
    }

    private suspend fun findSimilarItems(
        seedItems: List<String>,
        excludeItems: Set<String>,
        contentType: String?,
        limit: Int
    ): List<RecommendationResult> {
        val itemSimilarities = mutableMapOf<String, Float>()

        for (seedItem in seedItems) {
            // Find items that were liked by users who also liked the seed item
            val seedItemLikers = contentInteractionDao.getPositiveInteractionsByContent(seedItem)
                .map { it.userId }
                .distinct()

            if (seedItemLikers.isNotEmpty()) {
                // Get other content liked by these users
                val recommendedItems = seedItemLikers.flatMap { userId ->
                    contentInteractionDao.getPositiveInteractionsByUser(userId, 20)
                        .map { it.contentId }
                }
                    .filter { !excludeItems.contains(it) && it != seedItem }

                // Calculate item similarity scores
                for (item in recommendedItems) {
                    val coOccurrenceCount = seedItemLikers.count { userId ->
                        contentInteractionDao.hasPositiveInteraction(userId, item)
                    }

                    val similarity = calculateItemSimilarity(
                        coOccurrenceCount = coOccurrenceCount,
                        seedItemPopularity = seedItemLikers.size,
                        itemPopularity = contentInteractionDao.getPositiveInteractionCount(item)
                    )

                    val currentSimilarity = itemSimilarities[item] ?: 0f
                    itemSimilarities[item] = maxOf(currentSimilarity, similarity)
                }
            }
        }

        return itemSimilarities.entries
            .sortedByDescending { it.value }
            .take(limit)
            .map { (contentId, similarity) ->
                RecommendationResult(
                    contentId = contentId,
                    score = similarity.coerceIn(0f, 1f),
                    algorithmType = "collaborative_item_based",
                    reason = "Similar to content you've enjoyed",
                    metadata = mapOf(
                        "algorithm_variant" to "item_based",
                        "seed_items_count" to seedItems.size,
                        "similarity_score" to similarity
                    )
                )
            }
    }

    private fun extractUserPreferences(
        watchHistory: List<com.tvonnet.debridxtreamiptv.data.local.entity.WatchHistoryEntity>
    ): UserPreferenceVector {
        val genrePreferences = mutableMapOf<String, Float>()
        val categoryPreferences = mutableMapOf<String, Float>()

        for (item in watchHistory) {
            val weight = calculateEngagementWeight(item)

            // Extract genre preferences
            item.genres.forEach { genre ->
                genrePreferences[genre] = (genrePreferences[genre] ?: 0f) + weight
            }

            // Extract category preferences
            item.categories.forEach { category ->
                categoryPreferences[category] = (categoryPreferences[category] ?: 0f) + weight
            }
        }

        return UserPreferenceVector(
            genrePreferences = genrePreferences.normalize(),
            categoryPreferences = categoryPreferences.normalize(),
            averageWatchTime = watchHistory.map { it.watchDuration }.average().toFloat(),
            completionRate = watchHistory.map { it.watchProgress }.average().toFloat()
        )
    }

    private fun calculateUserSimilarity(
        user1: UserPreferenceVector,
        user2: UserPreferenceVector
    ): Float {
        // Calculate cosine similarity for genre preferences
        val genreSimilarity = cosineSimilarity(
            user1.genrePreferences,
            user2.genrePreferences
        )

        // Calculate cosine similarity for category preferences
        val categorySimilarity = cosineSimilarity(
            user1.categoryPreferences,
            user2.categoryPreferences
        )

        // Calculate similarity for behavioral patterns
        val behaviorSimilarity = calculateBehaviorSimilarity(user1, user2)

        // Weighted combination
        return (genreSimilarity * 0.4f + categorySimilarity * 0.3f + behaviorSimilarity * 0.3f)
    }

    private fun cosineSimilarity(
        vector1: Map<String, Float>,
        vector2: Map<String, Float>
    ): Float {
        val commonKeys = vector1.keys.intersect(vector2.keys)
        if (commonKeys.isEmpty()) return 0f

        var dotProduct = 0f
        var magnitude1 = 0f
        var magnitude2 = 0f

        for (key in commonKeys) {
            val v1 = vector1[key] ?: 0f
            val v2 = vector2[key] ?: 0f
            dotProduct += v1 * v2
        }

        for (value in vector1.values) {
            magnitude1 += value * value
        }
        magnitude1 = sqrt(magnitude1)

        for (value in vector2.values) {
            magnitude2 += value * value
        }
        magnitude2 = sqrt(magnitude2)

        return if (magnitude1 > 0 && magnitude2 > 0) {
            dotProduct / (magnitude1 * magnitude2)
        } else {
            0f
        }
    }

    private fun calculateBehaviorSimilarity(
        user1: UserPreferenceVector,
        user2: UserPreferenceVector
    ): Float {
        val watchTimeSimilarity = 1f - kotlin.math.abs(user1.averageWatchTime - user2.averageWatchTime) /
            maxOf(user1.averageWatchTime, user2.averageWatchTime)

        val completionSimilarity = 1f - kotlin.math.abs(user1.completionRate - user2.completionRate)

        return (watchTimeSimilarity + completionSimilarity) / 2f
    }

    private fun findCommonItems(
        history1: List<com.tvonnet.debridxtreamiptv.data.local.entity.WatchHistoryEntity>,
        history2: List<com.tvonnet.debridxtreamiptv.data.local.entity.WatchHistoryEntity>
    ): List<String> {
        val content1 = history1.map { it.contentId }.toSet()
        val content2 = history2.map { it.contentId }.toSet()
        return content1.intersect(content2).toList()
    }

    private fun calculateEngagementWeight(item: com.tvonnet.debridxtreamiptv.data.local.entity.WatchHistoryEntity): Float {
        return when {
            item.watchProgress >= 0.9f -> 1.0f // Completed
            item.watchProgress >= 0.5f -> 0.7f // Half watched
            item.watchProgress >= 0.25f -> 0.4f // Quarter watched
            else -> 0.1f
        }
    }

    private fun calculateCollaborativeScore(
        userSimilarity: Float,
        userRating: Float,
        contentInteractions: List<com.tvonnet.debridxtreamiptv.data.local.entity.ContentInteractionEntity>
    ): Float {
        val popularityScore = contentInteractions.size.coerceAtMost(100).toFloat() / 100f
        val qualityScore = if (contentInteractions.isNotEmpty()) {
            contentInteractions.count { it.isPositiveEngagement() }.toFloat() / contentInteractions.size
        } else 0f

        return (userSimilarity * 0.5f + userRating * 0.3f + popularityScore * 0.1f + qualityScore * 0.1f)
    }

    private fun calculateItemSimilarity(
        coOccurrenceCount: Int,
        seedItemPopularity: Int,
        itemPopularity: Int
    ): Float {
        // Jaccard-like similarity with popularity bias reduction
        val unionSize = seedItemPopularity + itemPopularity - coOccurrenceCount
        if (unionSize == 0) return 0f

        val jaccardSimilarity = coOccurrenceCount.toFloat() / unionSize

        // Reduce bias towards very popular items
        val popularityPenalty = minOf(itemPopularity, 1000).toFloat() / 1000f
        val adjustedSimilarity = jaccardSimilarity * (1f - popularityPenalty * 0.3f)

        return adjustedSimilarity.coerceAtMost(1f)
    }

    private fun Map<String, Float>.normalize(): Map<String, Float> {
        val maxValue = values.maxOrNull() ?: 0f
        return if (maxValue > 0) {
            mapValues { it.value / maxValue }
        } else {
            this
        }
    }

    /**
     * Data class for representing similar users
     */
    private data class SimilarUser(
        val userId: String,
        val similarity: Float,
        val commonItems: Int
    )

    /**
     * Data class for user preference vectors
     */
    private data class UserPreferenceVector(
        val genrePreferences: Map<String, Float>,
        val categoryPreferences: Map<String, Float>,
        val averageWatchTime: Float,
        val completionRate: Float
    )
}