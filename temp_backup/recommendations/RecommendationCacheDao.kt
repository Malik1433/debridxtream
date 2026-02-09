package com.tvonnet.debridxtreamiptv.data.local.dao

import androidx.room.*
import com.tvonnet.debridxtreamiptv.data.local.entity.RecommendationCacheEntity
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for Recommendation Cache operations
 * Phase 3.2: Content Recommendations System
 *
 * Provides comprehensive database operations for recommendation caching:
 * - CRUD operations for cached recommendations
 * - TTL-based cache management
 * - Performance optimization queries
 * - Analytics and metrics for cache performance
 */
@Dao
interface RecommendationCacheDao {

    // Basic CRUD Operations

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRecommendationCache(cache: RecommendationCacheEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRecommendationCaches(caches: List<RecommendationCacheEntity>): List<Long>

    @Update
    suspend fun updateRecommendationCache(cache: RecommendationCacheEntity)

    @Delete
    suspend fun deleteRecommendationCache(cache: RecommendationCacheEntity)

    @Query("SELECT * FROM recommendation_cache WHERE cacheKey = :cacheKey")
    suspend fun getRecommendationCache(cacheKey: String): RecommendationCacheEntity?

    @Query("SELECT * FROM recommendation_cache WHERE cacheKey = :cacheKey")
    fun getRecommendationCacheFlow(cacheKey: String): Flow<RecommendationCacheEntity?>

    // Cache Retrieval Operations

    @Query("SELECT * FROM recommendation_cache WHERE userId = :userId AND algorithmType = :algorithmType AND contentType = :contentType ORDER BY generatedAt DESC LIMIT 1")
    suspend fun getLatestRecommendations(userId: String, algorithmType: String, contentType: String?): RecommendationCacheEntity?

    @Query("SELECT * FROM recommendation_cache WHERE userId = :userId AND algorithmType = :algorithmType AND contentType = :contentType AND isActive = 1 ORDER BY generatedAt DESC LIMIT 1")
    suspend fun getActiveRecommendations(userId: String, algorithmType: String, contentType: String?): RecommendationCacheEntity?

    @Query("SELECT * FROM recommendation_cache WHERE userId = :userId AND algorithmType IN (:algorithmTypes) AND isActive = 1 ORDER BY priority DESC, generatedAt DESC")
    suspend fun getActiveRecommendationsByAlgorithms(userId: String, algorithmTypes: List<String>): List<RecommendationCacheEntity>

    @Query("SELECT * FROM recommendation_cache WHERE userId = :userId AND contentType = :contentType AND isActive = 1 ORDER BY priority DESC, generatedAt DESC LIMIT :limit")
    suspend fun getRecommendationsByContentType(userId: String, contentType: String?, limit: Int = 10): List<RecommendationCacheEntity>

    // Algorithm-specific Queries

    @Query("SELECT * FROM recommendation_cache WHERE algorithmType = :algorithmType AND isActive = 1 ORDER BY priority DESC, generatedAt DESC LIMIT :limit")
    suspend fun getRecommendationsByAlgorithm(algorithmType: String, limit: Int = 20): List<RecommendationCacheEntity>

    @Query("SELECT * FROM recommendation_cache WHERE algorithmType = :algorithmType AND userId = :userId AND isActive = 1 ORDER BY priority DESC, generatedAt DESC LIMIT :limit")
    suspend fun getUserRecommendationsByAlgorithm(algorithmType: String, userId: String = "default_user", limit: Int = 20): List<RecommendationCacheEntity>

    @Query("SELECT DISTINCT algorithmType FROM recommendation_cache WHERE userId = :userId AND isActive = 1")
    suspend fun getAvailableAlgorithms(userId: String = "default_user"): List<String>

    // Cache Health and Status Queries

    @Query("SELECT * FROM recommendation_cache WHERE isActive = 1 AND isExpired() = 0 ORDER BY generatedAt DESC LIMIT :limit")
    suspend fun getFreshRecommendations(limit: Int = 50): List<RecommendationCacheEntity>

    @Query("SELECT * FROM recommendation_cache WHERE isActive = 1 AND isStale() = 1 ORDER BY generatedAt DESC LIMIT :limit")
    suspend fun getStaleRecommendations(limit: Int = 50): List<RecommendationCacheEntity>

    @Query("SELECT * FROM recommendation_cache WHERE isActive = 0 ORDER BY generatedAt DESC LIMIT :limit")
    suspend fun getInactiveRecommendations(limit: Int = 50): List<RecommendationCacheEntity>

    @Query("SELECT * FROM recommendation_cache WHERE needsRefresh = 1 ORDER BY priority DESC, generatedAt ASC LIMIT :limit")
    suspend fun getRecommendationsNeedingRefresh(limit: Int = 20): List<RecommendationCacheEntity>

    @Query("SELECT * FROM recommendation_cache WHERE isActive = 1 AND isExpired() = 1")
    suspend fun getExpiredRecommendations(): List<RecommendationCacheEntity>

    // Performance and Analytics Queries

    @Query("SELECT COUNT(*) FROM recommendation_cache WHERE isActive = 1")
    suspend fun getActiveCacheCount(): Int

    @Query("SELECT COUNT(*) FROM recommendation_cache WHERE isExpired() = 1")
    suspend fun getExpiredCacheCount(): Int

    @Query("SELECT COUNT(*) FROM recommendation_cache WHERE needsRefresh = 1")
    suspend fun getCacheNeedingRefreshCount(): Int

    @Query("""
        SELECT algorithmType,
               COUNT(*) as cacheCount,
               AVG(cacheHits) as avgHits,
               AVG(freshnessScore) as avgFreshness,
               AVG(recommendationScore) as avgQuality
        FROM recommendation_cache
        WHERE isActive = 1
        GROUP BY algorithmType
        ORDER BY cacheCount DESC
    """)
    suspend fun getAlgorithmPerformanceMetrics(): List<AlgorithmPerformanceMetric>

    @Query("""
        SELECT userId,
               COUNT(*) as cacheCount,
               AVG(cacheHits) as avgHits,
               SUM(cacheHits) as totalHits
        FROM recommendation_cache
        WHERE isActive = 1
        GROUP BY userId
        ORDER BY totalHits DESC
        LIMIT :limit
    """)
    suspend fun getUserCacheUsage(limit: Int = 20): List<UserCacheUsage>

    @Query("""
        SELECT contentType,
               COUNT(*) as cacheCount,
               AVG(cacheHits) as avgHits,
               AVG(recommendationScore) as avgQuality
        FROM recommendation_cache
        WHERE isActive = 1
        AND contentType IS NOT NULL
        GROUP BY contentType
        ORDER BY cacheCount DESC
    """)
    suspend fun getContentTypePerformance(): List<ContentTypePerformance>

    // Cache Maintenance Operations

    @Query("UPDATE recommendation_cache SET isActive = 0, isStale = 1, updatedAt = :timestamp WHERE cacheKey = :cacheKey")
    suspend fun deactivateCache(cacheKey: String, timestamp: Long = System.currentTimeMillis())

    @Query("UPDATE recommendation_cache SET needsRefresh = 1, updatedAt = :timestamp WHERE cacheKey = :cacheKey")
    suspend fun markForRefresh(cacheKey: String, timestamp: Long = System.currentTimeMillis())

    @Query("UPDATE recommendation_cache SET cacheHits = cacheHits + 1, lastAccessed = :timestamp, freshnessScore = :freshnessScore WHERE cacheKey = :cacheKey")
    suspend fun recordCacheHit(cacheKey: String, timestamp: Long = System.currentTimeMillis(), freshnessScore: Float)

    @Query("UPDATE recommendation_cache SET priority = :priority, updatedAt = :timestamp WHERE cacheKey = :cacheKey")
    suspend fun updateCachePriority(cacheKey: String, priority: Int, timestamp: Long = System.currentTimeMillis())

    @Query("UPDATE recommendation_cache SET recommendationScore = :score, updatedAt = :timestamp WHERE cacheKey = :cacheKey")
    suspend fun updateRecommendationScore(cacheKey: String, score: Float, timestamp: Long = System.currentTimeMillis())

    @Query("UPDATE recommendation_cache SET userFeedbackScore = :score, feedbackGiven = feedbackGiven + 1, updatedAt = :timestamp WHERE cacheKey = :cacheKey")
    suspend fun updateUserFeedbackScore(cacheKey: String, score: Float, timestamp: Long = System.currentTimeMillis())

    // Batch Operations

    @Query("DELETE FROM recommendation_cache WHERE isExpired() = 1")
    suspend fun deleteExpiredCaches()

    @Query("DELETE FROM recommendation_cache WHERE isActive = 0 AND generatedAt < :cutoffDate")
    suspend fun deleteOldInactiveCaches(cutoffDate: Long)

    @Query("UPDATE recommendation_cache SET needsRefresh = 1, updatedAt = :timestamp WHERE algorithmType = :algorithmType AND userId = :userId")
    suspend fun markAlgorithmForRefresh(algorithmType: String, userId: String, timestamp: Long = System.currentTimeMillis())

    @Query("UPDATE recommendation_cache SET isStale = 1, needsRefresh = 1, updatedAt = :timestamp WHERE algorithmType = :algorithmType")
    suspend fun invalidateAlgorithmCache(algorithmType: String, timestamp: Long = System.currentTimeMillis())

    @Query("DELETE FROM recommendation_cache WHERE userId = :userId")
    suspend fun deleteUserCache(userId: String = "default_user")

    @Query("DELETE FROM recommendation_cache WHERE algorithmType = :algorithmType")
    suspend fun deleteAlgorithmCache(algorithmType: String)

    // Cache Statistics and Monitoring

    @Query("SELECT SUM(computationTime) FROM recommendation_cache WHERE generatedAt >= :startDate")
    suspend fun getTotalComputationTimeSince(startDate: Long): Long?

    @Query("SELECT AVG(computationTime) FROM recommendation_cache WHERE generatedAt >= :startDate")
    suspend fun getAverageComputationTimeSince(startDate: Long): Float?

    @Query("""
        SELECT DATE(generatedAt / 1000, 'unixepoch') as date,
               COUNT(*) as cacheCount,
               AVG(computationTime) as avgComputationTime,
               AVG(cacheHits) as avgHits
        FROM recommendation_cache
        WHERE generatedAt >= :startDate
        GROUP BY DATE(generatedAt / 1000, 'unixepoch')
        ORDER BY date DESC
        LIMIT :limit
    """)
    suspend fun getDailyCacheStatistics(startDate: Long, limit: Int = 30): List<DailyCacheStatistic>

    @Query("""
        SELECT algorithmType,
               SUM(computationTime) as totalTime,
               COUNT(*) as count,
               AVG(computationTime) as avgTime
        FROM recommendation_cache
        WHERE generatedAt >= :startDate
        GROUP BY algorithmType
        ORDER BY totalTime DESC
    """)
    suspend fun getComputationTimeByAlgorithm(startDate: Long): List<AlgorithmComputationTime>

    @Query("SELECT * FROM recommendation_cache WHERE priority = :priority ORDER BY generatedAt DESC LIMIT :limit")
    suspend fun getRecommendationsByPriority(priority: Int, limit: Int = 20): List<RecommendationCacheEntity>

    @Query("SELECT * FROM recommendation_cache WHERE experimentGroup = :experimentGroup ORDER BY generatedAt DESC LIMIT :limit")
    suspend fun getRecommendationsByExperimentGroup(experimentGroup: String, limit: Int = 20): List<RecommendationCacheEntity>

    // Cache Efficiency Analysis

    @Query("""
        SELECT cacheKey,
               cacheHits,
               freshnessScore,
               recommendationScore,
               generatedAt,
               lastAccessed
        FROM recommendation_cache
        WHERE isActive = 1
        AND cacheHits > 0
        ORDER BY cacheHits DESC
        LIMIT :limit
    """)
    suspend fun getMostUsedCaches(limit: Int = 50): List<CacheUsageDetail>

    @Query("""
        SELECT cacheKey,
               cacheHits,
               freshnessScore,
               recommendationScore,
               generatedAt,
               computationTime
        FROM recommendation_cache
        WHERE isActive = 1
        AND cacheHits = 0
        ORDER BY generatedAt DESC
        LIMIT :limit
    """)
    suspend fun getUnusedCaches(limit: Int = 50): List<CacheUnusedDetail>

    @Query("""
        SELECT cacheKey,
               cacheHits,
               (CASE WHEN isExpired() = 1 THEN 1 ELSE 0 END) as isExpired,
               freshnessScore,
               recommendationScore
        FROM recommendation_cache
        WHERE isActive = 1
        ORDER BY freshnessScore ASC
        LIMIT :limit
    """)
    suspend fun getLeastFreshCaches(limit: Int = 50): List<CacheFreshnessDetail>

    // Content Analysis from Cache

    @Query("""
        SELECT contentIds, COUNT(*) as cacheCount
        FROM recommendation_cache
        WHERE isActive = 1
        AND contentIds IS NOT NULL
        GROUP BY contentIds
        HAVING COUNT(*) > 1
        ORDER BY cacheCount DESC
        LIMIT :limit
    """)
    suspend fun getMostRecommendedContent(limit: Int = 20): List<MostRecommendedContent>

    // Cache Validation and Quality Control

    @Query("SELECT * FROM recommendation_cache WHERE diversityScore < :minThreshold OR coverageScore < :minThreshold ORDER BY recommendationScore ASC LIMIT :limit")
    suspend fun getLowQualityCaches(minThreshold: Float = 0.3f, limit: Int = 20): List<RecommendationCacheEntity>

    @Query("UPDATE recommendation_cache SET lastValidated = :timestamp WHERE isActive = 1 AND algorithmType = :algorithmType")
    suspend fun validateAlgorithmCache(algorithmType: String, timestamp: Long = System.currentTimeMillis())

    @Query("SELECT * FROM recommendation_cache WHERE version != :currentVersion AND isActive = 1")
    suspend fun getOutdatedVersionCaches(currentVersion: Int): List<RecommendationCacheEntity>

    // Advanced Analytics

    @Query("""
        SELECT algorithmType,
               contentType,
               COUNT(*) as cacheCount,
               AVG(recommendationScore) as avgScore,
               AVG(cacheHits) as avgHits,
               AVG(diversityScore) as avgDiversity
        FROM recommendation_cache
        WHERE isActive = 1
        GROUP BY algorithmType, contentType
        ORDER BY cacheCount DESC
        LIMIT :limit
    """)
    suspend fun getAlgorithmContentTypeMetrics(limit: Int = 50): List<AlgorithmContentTypeMetric>

    @Query("""
        SELECT priority,
               COUNT(*) as cacheCount,
               AVG(cacheHits) as avgHits,
               AVG(recommendationScore) as avgScore
        FROM recommendation_cache
        WHERE isActive = 1
        GROUP BY priority
        ORDER BY priority DESC
    """)
    suspend fun getPriorityLevelMetrics(): List<PriorityLevelMetric>

    @Query("SELECT COUNT(*) FROM recommendation_cache WHERE userId = :userId AND isActive = 1")
    suspend fun getUserActiveCacheCount(userId: String = "default_user"): Int
}

// Data classes for complex query results
data class AlgorithmPerformanceMetric(
    val algorithmType: String,
    val cacheCount: Int,
    val avgHits: Float,
    val avgFreshness: Float,
    val avgQuality: Float
)

data class UserCacheUsage(
    val userId: String,
    val cacheCount: Int,
    val avgHits: Float,
    val totalHits: Int
)

data class ContentTypePerformance(
    val contentType: String,
    val cacheCount: Int,
    val avgHits: Float,
    val avgQuality: Float
)

data class DailyCacheStatistic(
    val date: String,
    val cacheCount: Int,
    val avgComputationTime: Float,
    val avgHits: Float
)

data class AlgorithmComputationTime(
    val algorithmType: String,
    val totalTime: Long,
    val count: Int,
    val avgTime: Float
)

data class CacheUsageDetail(
    val cacheKey: String,
    val cacheHits: Int,
    val freshnessScore: Float,
    val recommendationScore: Float,
    val generatedAt: Long,
    val lastAccessed: Long
)

data class CacheUnusedDetail(
    val cacheKey: String,
    val cacheHits: Int,
    val freshnessScore: Float,
    val recommendationScore: Float,
    val generatedAt: Long,
    val computationTime: Long
)

data class CacheFreshnessDetail(
    val cacheKey: String,
    val cacheHits: Int,
    val isExpired: Int,
    val freshnessScore: Float,
    val recommendationScore: Float
)

data class MostRecommendedContent(
    val contentIds: String,
    val cacheCount: Int
)

data class AlgorithmContentTypeMetric(
    val algorithmType: String,
    val contentType: String,
    val cacheCount: Int,
    val avgScore: Float,
    val avgHits: Float,
    val avgDiversity: Float
)

data class PriorityLevelMetric(
    val priority: Int,
    val cacheCount: Int,
    val avgHits: Float,
    val avgScore: Float
)