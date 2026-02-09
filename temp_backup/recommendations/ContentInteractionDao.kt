package com.tvonnet.debridxtreamiptv.data.local.dao

import androidx.room.*
import com.tvonnet.debridxtreamiptv.data.local.entity.ContentInteractionEntity
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for Content Interaction operations
 * Phase 3.2: Content Recommendations System
 *
 * Provides comprehensive database operations for interaction tracking:
 * - CRUD operations for all interaction types
 * - Analytics and behavioral analysis queries
 * - Recommendation feedback collection
 * - Content discovery pattern analysis
 */
@Dao
interface ContentInteractionDao {

    // Basic CRUD Operations

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertInteraction(interaction: ContentInteractionEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertInteractions(interactions: List<ContentInteractionEntity>): List<Long>

    @Update
    suspend fun updateInteraction(interaction: ContentInteractionEntity)

    @Delete
    suspend fun deleteInteraction(interaction: ContentInteractionEntity)

    @Query("SELECT * FROM content_interactions WHERE id = :id")
    suspend fun getInteractionById(id: Long): ContentInteractionEntity?

    // Session-based Queries

    @Query("SELECT * FROM content_interactions WHERE sessionId = :sessionId ORDER BY timestamp ASC")
    suspend fun getInteractionsBySession(sessionId: String): List<ContentInteractionEntity>

    @Query("SELECT * FROM content_interactions WHERE sessionId = :sessionId ORDER BY timestamp ASC")
    fun getInteractionsBySessionFlow(sessionId: String): Flow<List<ContentInteractionEntity>>

    @Query("SELECT DISTINCT sessionId FROM content_interactions WHERE userId = :userId ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecentSessionIds(userId: String = "default_user", limit: Int = 100): List<String>

    // Content-based Queries

    @Query("SELECT * FROM content_interactions WHERE contentId = :contentId AND userId = :userId ORDER BY timestamp DESC")
    suspend fun getInteractionsByContent(contentId: String, userId: String = "default_user"): List<ContentInteractionEntity>

    @Query("SELECT * FROM content_interactions WHERE contentId = :contentId AND userId = :userId AND interactionType = :interactionType ORDER BY timestamp DESC")
    suspend fun getInteractionsByContentAndType(contentId: String, interactionType: String, userId: String = "default_user"): List<ContentInteractionEntity>

    @Query("SELECT * FROM content_interactions WHERE contentType = :contentType AND userId = :userId ORDER BY timestamp DESC LIMIT :limit")
    fun getInteractionsByContentType(contentType: String, userId: String = "default_user", limit: Int = 50): Flow<List<ContentInteractionEntity>>

    // Interaction Type Queries

    @Query("SELECT * FROM content_interactions WHERE interactionType = :interactionType AND userId = :userId ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getInteractionsByType(interactionType: String, userId: String = "default_user", limit: Int = 100): List<ContentInteractionEntity>

    @Query("SELECT * FROM content_interactions WHERE interactionType IN (:types) AND userId = :userId ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getInteractionsByTypes(types: List<String>, userId: String = "default_user", limit: Int = 100): List<ContentInteractionEntity>

    @Query("SELECT COUNT(*) FROM content_interactions WHERE interactionType = :interactionType AND userId = :userId")
    suspend fun getInteractionTypeCount(interactionType: String, userId: String = "default_user"): Int

    // Positive Engagement Interactions

    @Query("""
        SELECT * FROM content_interactions
        WHERE userId = :userId
        AND (
            interactionType IN ('like', 'add_to_favorites', 'add_to_watchlist', 'share', 'rate', 'recommendation_click', 'more_like_this')
            OR (userRating IS NOT NULL AND userRating >= 4)
        )
        ORDER BY timestamp DESC
        LIMIT :limit
    """)
    suspend fun getPositiveEngagementInteractions(userId: String = "default_user", limit: Int = 50): List<ContentInteractionEntity>

    @Query("""
        SELECT * FROM content_interactions
        WHERE userId = :userId
        AND contentId = :contentId
        AND (
            interactionType IN ('like', 'add_to_favorites', 'add_to_watchlist', 'share', 'rate', 'recommendation_click', 'more_like_this')
            OR (userRating IS NOT NULL AND userRating >= 4)
        )
        ORDER BY timestamp DESC
    """)
    suspend fun getPositiveEngagementForContent(contentId: String, userId: String = "default_user"): List<ContentInteractionEntity>

    // Negative Engagement Interactions

    @Query("""
        SELECT * FROM content_interactions
        WHERE userId = :userId
        AND (
            interactionType IN ('dislike', 'content_abandon', 'remove_from_favorites', 'not_interested', 'recommendation_dismiss')
            OR (userRating IS NOT NULL AND userRating <= 2)
        )
        ORDER BY timestamp DESC
        LIMIT :limit
    """)
    suspend fun getNegativeEngagementInteractions(userId: String = "default_user", limit: Int = 50): List<ContentInteractionEntity>

    // Discovery Method Analysis

    @Query("SELECT DISTINCT discoveryMethod FROM content_interactions WHERE userId = :userId AND discoveryMethod IS NOT NULL")
    suspend fun getDiscoveryMethods(userId: String = "default_user"): List<String>

    @Query("""
        SELECT discoveryMethod, COUNT(*) as count, AVG(interactionDuration) as avgDuration
        FROM content_interactions
        WHERE userId = :userId
        AND discoveryMethod IS NOT NULL
        GROUP BY discoveryMethod
        ORDER BY count DESC
    """)
    suspend fun getDiscoveryMethodStatistics(userId: String = "default_user"): List<DiscoveryMethodStatistic>

    // Recommendation Feedback Analysis

    @Query("SELECT * FROM content_interactions WHERE recommendationId = :recommendationId AND userId = :userId ORDER BY timestamp DESC")
    suspend fun getInteractionsByRecommendation(recommendationId: String, userId: String = "default_user"): List<ContentInteractionEntity>

    @Query("""
        SELECT recommendationId, COUNT(*) as interactions,
               SUM(CASE WHEN interactionType = 'recommendation_click' THEN 1 ELSE 0 END) as clicks,
               SUM(CASE WHEN interactionType = 'recommendation_dismiss' THEN 1 ELSE 0 END) as dismisses,
               AVG(userRating) as avgRating
        FROM content_interactions
        WHERE userId = :userId
        AND recommendationId IS NOT NULL
        GROUP BY recommendationId
        ORDER BY interactions DESC
        LIMIT :limit
    """)
    suspend fun getRecommendationPerformance(userId: String = "default_user", limit: Int = 50): List<RecommendationPerformance>

    // Content Quality and Performance

    @Query("""
        SELECT contentId,
               COUNT(*) as totalInteractions,
               SUM(CASE WHEN isPositiveEngagement() = 1 THEN 1 ELSE 0 END) as positiveInteractions,
               SUM(CASE WHEN isNegativeEngagement() = 1 THEN 1 ELSE 0 END) as negativeInteractions,
               AVG(userRating) as avgRating,
               SUM(interactionDuration) as totalDuration
        FROM content_interactions
        WHERE userId = :userId
        GROUP BY contentId
        HAVING totalInteractions > 0
        ORDER BY (positiveInteractions * 1.0 / totalInteractions) DESC
        LIMIT :limit
    """)
    suspend fun getContentPerformanceMetrics(userId: String = "default_user", limit: Int = 100): List<ContentPerformanceMetric>

    // Time-based Analytics

    @Query("""
        SELECT userTimeOfDay, COUNT(*) as interactionCount, AVG(interactionDuration) as avgDuration
        FROM content_interactions
        WHERE userId = :userId
        AND timestamp >= :startDate
        GROUP BY userTimeOfDay
        ORDER BY interactionCount DESC
    """)
    suspend fun getTimeOfDayDistribution(userId: String = "default_user", startDate: Long): List<TimeOfDayStatistic>

    @Query("""
        SELECT userDayOfWeek, COUNT(*) as interactionCount
        FROM content_interactions
        WHERE userId = :userId
        AND timestamp >= :startDate
        GROUP BY userDayOfWeek
        ORDER BY interactionCount DESC
    """)
    suspend fun getDayOfWeekDistribution(userId: String = "default_user", startDate: Long): List<DayOfWeekStatistic>

    @Query("""
        SELECT DATE(timestamp / 1000, 'unixepoch') as date, COUNT(*) as interactionCount
        FROM content_interactions
        WHERE userId = :userId
        AND timestamp >= :startDate
        GROUP BY DATE(timestamp / 1000, 'unixepoch')
        ORDER BY date DESC
        LIMIT :limit
    """)
    suspend fun getDailyInteractionCounts(userId: String = "default_user", startDate: Long, limit: Int = 30): List<DailyInteractionCount>

    // Device and Technical Analysis

    @Query("""
        SELECT deviceType, COUNT(*) as interactionCount, AVG(interactionDuration) as avgDuration
        FROM content_interactions
        WHERE userId = :userId
        GROUP BY deviceType
        ORDER BY interactionCount DESC
    """)
    suspend fun getDeviceTypeStatistics(userId: String = "default_user"): List<DeviceTypeStatistic>

    @Query("""
        SELECT networkType, COUNT(*) as interactionCount, AVG(responseTime) as avgResponseTime
        FROM content_interactions
        WHERE userId = :userId
        AND networkType IS NOT NULL
        GROUP BY networkType
        ORDER BY interactionCount DESC
    """)
    suspend fun getNetworkTypeStatistics(userId: String = "default_user"): List<NetworkTypeStatistic>

    // Content Discovery Patterns

    @Query("""
        SELECT sourceScreen, COUNT(*) as interactionCount, AVG(interactionDuration) as avgDuration
        FROM content_interactions
        WHERE userId = :userId
        AND sourceScreen IS NOT NULL
        GROUP BY sourceScreen
        ORDER BY interactionCount DESC
    """)
    suspend fun getSourceScreenStatistics(userId: String = "default_user"): List<SourceScreenStatistic>

    @Query("""
        SELECT searchQuery, COUNT(*) as searchCount, AVG(interactionDuration) as avgDuration
        FROM content_interactions
        WHERE userId = :userId
        AND interactionType = 'search'
        AND searchQuery IS NOT NULL
        GROUP BY searchQuery
        ORDER BY searchCount DESC
        LIMIT :limit
    """)
    suspend fun getPopularSearchTerms(userId: String = "default_user", limit: Int = 50): List<PopularSearchTerm>

    // Recommendation Data Preparation

    @Query("""
        SELECT contentId,
               COUNT(*) as interactionCount,
               SUM(CASE WHEN interactionType = 'recommendation_click' THEN 1 ELSE 0 END) as recommendationClicks,
               SUM(CASE WHEN isPositiveEngagement() = 1 THEN 1 ELSE 0 END) as positiveInteractions,
               SUM(CASE WHEN isNegativeEngagement() = 1 THEN 1 ELSE 0 END) as negativeInteractions,
               MAX(timestamp) as lastInteraction
        FROM content_interactions
        WHERE userId = :userId
        AND timestamp >= :sinceDate
        GROUP BY contentId
        HAVING interactionCount > 0
        ORDER BY (positiveInteractions * 1.0 / interactionCount) DESC
        LIMIT :limit
    """)
    suspend fun getContentRecommendationSignals(
        userId: String = "default_user",
        sinceDate: Long,
        limit: Int
    ): List<ContentRecommendationSignal>

    @Query("""
        SELECT genres, COUNT(*) as interactionCount
        FROM content_interactions
        WHERE userId = :userId
        AND isPositiveEngagement() = 1
        AND contentGenres IS NOT NULL
        GROUP BY contentGenres
        ORDER BY interactionCount DESC
        LIMIT :limit
    """)
    suspend fun getPreferredGenresFromInteractions(userId: String = "default_user", limit: Int = 20): List<GenreInteractionStatistic>

    @Query("""
        SELECT categories, COUNT(*) as interactionCount
        FROM content_interactions
        WHERE userId = :userId
        AND isPositiveEngagement() = 1
        AND contentCategories IS NOT NULL
        GROUP BY contentCategories
        ORDER BY interactionCount DESC
        LIMIT :limit
    """)
    suspend fun getPreferredCategoriesFromInteractions(userId: String = "default_user", limit: Int = 20): List<CategoryInteractionStatistic>

    // Error and Performance Analysis

    @Query("SELECT * FROM content_interactions WHERE errorOccurred = 1 AND userId = :userId ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getErrorInteractions(userId: String = "default_user", limit: Int = 50): List<ContentInteractionEntity>

    @Query("""
        SELECT errorMessage, COUNT(*) as errorCount, errorCode
        FROM content_interactions
        WHERE userId = :userId
        AND errorOccurred = 1
        AND errorMessage IS NOT NULL
        GROUP BY errorMessage, errorCode
        ORDER BY errorCount DESC
        LIMIT :limit
    """)
    suspend fun getErrorStatistics(userId: String = "default_user", limit: Int = 20): List<ErrorStatistic>

    @Query("""
        SELECT AVG(responseTime) as avgResponseTime,
               AVG(userWaitTime) as avgWaitTime,
               AVG(interactionDuration) as avgDuration
        FROM content_interactions
        WHERE userId = :userId
        AND timestamp >= :startDate
    """)
    suspend fun getPerformanceMetrics(userId: String = "default_user", startDate: Long): PerformanceMetrics?

    // Maintenance and Cleanup

    @Query("DELETE FROM content_interactions WHERE userId = :userId AND timestamp < :cutoffDate")
    suspend fun deleteOldInteractions(userId: String = "default_user", cutoffDate: Long)

    @Query("""
        DELETE FROM content_interactions
        WHERE userId = :userId
        AND id NOT IN (
            SELECT id FROM content_interactions
            WHERE userId = :userId
            ORDER BY timestamp DESC
            LIMIT :keepCount
        )
    """)
    suspend fun trimInteractionHistory(userId: String = "default_user", keepCount: Int = 10000)

    @Query("DELETE FROM content_interactions WHERE userId = :userId AND interactionType IN (:types)")
    suspend fun deleteInteractionsByTypes(userId: String = "default_user", types: List<String>)

    // Analytics Queries for Recommendation System

    @Query("SELECT COUNT(*) FROM content_interactions WHERE userId = :userId AND timestamp >= :startDate")
    suspend fun getInteractionCountSince(userId: String = "default_user", startDate: Long): Int

    @Query("""
        SELECT interactionType, COUNT(*) as count
        FROM content_interactions
        WHERE userId = :userId
        AND timestamp >= :startDate
        GROUP BY interactionType
        ORDER BY count DESC
    """)
    suspend fun getInteractionTypeDistribution(userId: String = "default_user", startDate: Long): List<InteractionTypeDistribution>

    @Query("""
        SELECT contentId, COUNT(*) as bingeWatchScore
        FROM content_interactions
        WHERE userId = :userId
        AND interactionType = 'content_play'
        AND timestamp >= :startDate
        GROUP BY contentId
        HAVING COUNT(*) > 3
        ORDER BY bingeWatchScore DESC
        LIMIT :limit
    """)
    suspend fun getBingeWatchedContent(userId: String = "default_user", startDate: Long, limit: Int = 20): List<BingeWatchContent>

    @Query("""
        SELECT contentType, AVG(interactionDuration) as avgDuration, COUNT(*) as interactionCount
        FROM content_interactions
        WHERE userId = :userId
        AND interactionType = 'content_play'
        AND timestamp >= :startDate
        GROUP BY contentType
        ORDER BY avgDuration DESC
    """)
    suspend fun getContentEngagementByType(userId: String = "default_user", startDate: Long): List<ContentEngagementByType>
}

// Data classes for complex query results
data class DiscoveryMethodStatistic(
    val discoveryMethod: String,
    val count: Int,
    val avgDuration: Float
)

data class RecommendationPerformance(
    val recommendationId: String,
    val interactions: Int,
    val clicks: Int,
    val dismisses: Int,
    val avgRating: Float?
)

data class ContentPerformanceMetric(
    val contentId: String,
    val totalInteractions: Int,
    val positiveInteractions: Int,
    val negativeInteractions: Int,
    val avgRating: Float?,
    val totalDuration: Long
)

data class TimeOfDayStatistic(
    val userTimeOfDay: Int,
    val interactionCount: Int,
    val avgDuration: Float
)

data class DayOfWeekStatistic(
    val userDayOfWeek: Int,
    val interactionCount: Int
)

data class DailyInteractionCount(
    val date: String,
    val interactionCount: Int
)

data class DeviceTypeStatistic(
    val deviceType: String,
    val interactionCount: Int,
    val avgDuration: Float
)

data class NetworkTypeStatistic(
    val networkType: String,
    val interactionCount: Int,
    val avgResponseTime: Float
)

data class SourceScreenStatistic(
    val sourceScreen: String,
    val interactionCount: Int,
    val avgDuration: Float
)

data class PopularSearchTerm(
    val searchQuery: String,
    val searchCount: Int,
    val avgDuration: Float
)

data class ContentRecommendationSignal(
    val contentId: String,
    val interactionCount: Int,
    val recommendationClicks: Int,
    val positiveInteractions: Int,
    val negativeInteractions: Int,
    val lastInteraction: Long
)

data class GenreInteractionStatistic(
    val genres: String,
    val interactionCount: Int
)

data class CategoryInteractionStatistic(
    val categories: String,
    val interactionCount: Int
)

data class ErrorStatistic(
    val errorMessage: String,
    val errorCount: Int,
    val errorCode: Int?
)

data class PerformanceMetrics(
    val avgResponseTime: Float?,
    val avgWaitTime: Float?,
    val avgDuration: Float?
)

data class InteractionTypeDistribution(
    val interactionType: String,
    val count: Int
)

data class BingeWatchContent(
    val contentId: String,
    val bingeWatchScore: Int
)

data class ContentEngagementByType(
    val contentType: String,
    val avgDuration: Float,
    val interactionCount: Int
)