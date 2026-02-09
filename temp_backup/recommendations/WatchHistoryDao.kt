package com.tvonnet.debridxtreamiptv.data.local.dao

import androidx.room.*
import com.tvonnet.debridxtreamiptv.data.local.entity.WatchHistoryEntity
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for Watch History operations
 * Phase 3.2: Content Recommendations System
 *
 * Provides comprehensive database operations for watch history tracking:
 * - CRUD operations for watch history entries
 * - Analytics and statistics queries
 * - Recommendation data preparation
 * - Session and binge-watching analysis
 */
@Dao
interface WatchHistoryDao {

    // Basic CRUD Operations

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertWatchHistory(watchHistory: WatchHistoryEntity): Long

    @Update
    suspend fun updateWatchHistory(watchHistory: WatchHistoryEntity)

    @Delete
    suspend fun deleteWatchHistory(watchHistory: WatchHistoryEntity)

    @Query("SELECT * FROM watch_history WHERE id = :id")
    suspend fun getWatchHistoryById(id: Long): WatchHistoryEntity?

    // Session-based Operations

    @Query("SELECT * FROM watch_history WHERE sessionId = :sessionId ORDER BY watchDate ASC")
    suspend fun getWatchHistoryBySession(sessionId: String): List<WatchHistoryEntity>

    @Query("SELECT * FROM watch_history WHERE sessionId = :sessionIds ORDER BY watchDate DESC")
    fun getWatchHistoryBySessions(sessionIds: List<String>): Flow<List<WatchHistoryEntity>>

    @Query("SELECT DISTINCT sessionId FROM watch_history WHERE userId = :userId ORDER BY sessionStartTime DESC")
    suspend fun getUserSessionIds(userId: String = "default_user"): List<String>

    // Content-based Queries

    @Query("SELECT * FROM watch_history WHERE contentId = :contentId AND userId = :userId ORDER BY watchDate DESC")
    suspend fun getWatchHistoryByContent(contentId: String, userId: String = "default_user"): List<WatchHistoryEntity>

    @Query("SELECT * FROM watch_history WHERE contentId = :contentId AND userId = :userId ORDER BY watchDate DESC LIMIT 1")
    suspend fun getLatestWatchHistoryByContent(contentId: String, userId: String = "default_user"): WatchHistoryEntity?

    @Query("SELECT * FROM watch_history WHERE contentType = :contentType AND userId = :userId ORDER BY watchDate DESC LIMIT :limit")
    fun getWatchHistoryByContentType(contentType: String, userId: String = "default_user", limit: Int = 50): Flow<List<WatchHistoryEntity>>

    @Query("SELECT * FROM watch_history WHERE seriesId = :seriesId AND userId = :userId ORDER BY seasonNumber, episodeNumber, watchDate DESC")
    suspend fun getSeriesWatchHistory(seriesId: String, userId: String = "default_user"): List<WatchHistoryEntity>

    // Continue Watching

    @Query("""
        SELECT * FROM watch_history
        WHERE userId = :userId
        AND completionStatus IN ('in_progress', 'not_started')
        AND watchProgress > 0.05
        AND watchProgress < 0.9
        ORDER BY lastAccessedAt DESC
        LIMIT :limit
    """)
    fun getContinueWatching(userId: String = "default_user", limit: Int = 20): Flow<List<WatchHistoryEntity>>

    @Query("""
        SELECT * FROM watch_history
        WHERE userId = :userId
        AND contentId = :contentId
        AND completionStatus IN ('in_progress', 'not_started')
        ORDER BY watchDate DESC
        LIMIT 1
    """)
    suspend fun getContinueWatchingForContent(contentId: String, userId: String = "default_user"): WatchHistoryEntity?

    // Recently Watched

    @Query("SELECT * FROM watch_history WHERE userId = :userId ORDER BY watchDate DESC LIMIT :limit")
    fun getRecentlyWatched(userId: String = "default_user", limit: Int = 50): Flow<List<WatchHistoryEntity>>

    @Query("SELECT DISTINCT contentId FROM watch_history WHERE userId = :userId ORDER BY MAX(watchDate) DESC LIMIT :limit")
    suspend fun getRecentlyWatchedContentIds(userId: String = "default_user", limit: Int = 100): List<String>

    // Completed Content

    @Query("SELECT * FROM watch_history WHERE userId = :userId AND completionStatus = 'completed' ORDER BY watchDate DESC LIMIT :limit")
    fun getCompletedContent(userId: String = "default_user", limit: Int = 50): Flow<List<WatchHistoryEntity>>

    @Query("SELECT * FROM watch_history WHERE userId = :userId AND isConsideredWatched() = 1 ORDER BY watchDate DESC LIMIT :limit")
    fun getWatchedContent(userId: String = "default_user", limit: Int = 50): Flow<List<WatchHistoryEntity>>

    // Analytics and Statistics

    @Query("SELECT COUNT(*) FROM watch_history WHERE userId = :userId")
    suspend fun getTotalWatchHistoryCount(userId: String = "default_user"): Int

    @Query("SELECT SUM(watchDuration) FROM watch_history WHERE userId = :userId")
    suspend fun getTotalWatchTime(userId: String = "default_user"): Long?

    @Query("SELECT COUNT(DISTINCT contentId) FROM watch_history WHERE userId = :userId")
    suspend fun getUniqueContentCount(userId: String = "default_user"): Int

    @Query("SELECT COUNT(*) FROM watch_history WHERE userId = :userId AND completionStatus = 'completed'")
    suspend fun getCompletedContentCount(userId: String = "default_user"): Int

    @Query("""
        SELECT contentType, COUNT(*) as count, SUM(watchDuration) as totalDuration
        FROM watch_history
        WHERE userId = :userId
        GROUP BY contentType
    """)
    suspend fun getContentStatistics(userId: String = "default_user"): List<ContentStatistic>

    @Query("""
        SELECT DATE(watchDate / 1000, 'unixepoch') as date,
               SUM(watchDuration) as totalWatchTime,
               COUNT(*) as sessionCount
        FROM watch_history
        WHERE userId = :userId
        AND watchDate >= :startDate
        GROUP BY DATE(watchDate / 1000, 'unixepoch')
        ORDER BY date DESC
    """)
    suspend fun getDailyWatchStats(userId: String = "default_user", startDate: Long): List<DailyWatchStatistic>

    // Genre and Category Analysis

    @Query("""
        SELECT genre, COUNT(*) as watchCount, SUM(watchDuration) as totalDuration
        FROM watch_history,
             json_each(genres) as genre
        WHERE userId = :userId
        AND genres IS NOT NULL
        GROUP BY genre.value
        ORDER BY watchCount DESC
    """)
    suspend fun getGenreStatistics(userId: String = "default_user"): List<GenreStatistic>

    @Query("""
        SELECT category, COUNT(*) as watchCount
        FROM watch_history,
             json_each(categories) as category
        WHERE userId = :userId
        AND categories IS NOT NULL
        GROUP BY category.value
        ORDER BY watchCount DESC
    """)
    suspend fun getCategoryStatistics(userId: String = "default_user"): List<CategoryStatistic>

    // Binge Watching Analysis

    @Query("SELECT * FROM watch_history WHERE isBingeWatch = 1 AND userId = :userId ORDER BY watchDate DESC")
    fun getBingeWatchingSessions(userId: String = "default_user"): Flow<List<WatchHistoryEntity>>

    @Query("""
        SELECT seriesId, COUNT(*) as episodeCount,
               MAX(consecutiveEpisodes) as maxConsecutive,
               SUM(watchDuration) as totalDuration
        FROM watch_history
        WHERE userId = :userId
        AND seriesId IS NOT NULL
        AND isBingeWatch = 1
        GROUP BY seriesId
        ORDER BY episodeCount DESC
    """)
    suspend fun getBingeWatchingStats(userId: String = "default_user"): List<BingeWatchingStatistic>

    // Quality and Device Analysis

    @Query("""
        SELECT quality, COUNT(*) as count, SUM(watchDuration) as totalDuration
        FROM watch_history
        WHERE userId = :userId
        AND quality IS NOT NULL
        GROUP BY quality
        ORDER BY totalDuration DESC
    """)
    suspend fun getQualityStatistics(userId: String = "default_user"): List<QualityStatistic>

    @Query("""
        SELECT deviceInfo, COUNT(*) as count, SUM(watchDuration) as totalDuration
        FROM watch_history
        WHERE userId = :userId
        AND deviceInfo IS NOT NULL
        GROUP BY deviceInfo
        ORDER BY totalDuration DESC
    """)
    suspend fun getDeviceStatistics(userId: String = "default_user"): List<DeviceStatistic>

    // Recommendation Data Preparation

    @Query("""
        SELECT contentId, COUNT(*) as watchCount, AVG(watchProgress) as avgProgress,
               SUM(watchDuration) as totalDuration, MAX(watchDate) as lastWatched
        FROM watch_history
        WHERE userId = :userId
        AND completionStatus = 'completed'
        GROUP BY contentId
        HAVING watchCount > 0
        ORDER BY (avgProgress * watchCount) DESC
        LIMIT :limit
    """)
    suspend fun getPreferredContent(userId: String = "default_user", limit: Int = 100): List<ContentPreference>

    @Query("""
        SELECT genres, COUNT(*) as watchCount
        FROM watch_history
        WHERE userId = :userId
        AND completionStatus = 'completed'
        AND genres IS NOT NULL
        GROUP BY genres
        ORDER BY watchCount DESC
        LIMIT :limit
    """)
    suspend fun getFavoriteGenres(userId: String = "default_user", limit: Int = 20): List<FavoriteGenre>

    @Query("""
        SELECT similarContentId as contentId, COUNT(*) as similarityScore
        FROM watch_history wh1
        JOIN watch_history wh2 ON wh1.sessionId = wh2.sessionId
                              AND wh1.contentId != wh2.contentId
        WHERE wh1.userId = :userId
        AND wh2.userId = :userId
        GROUP BY similarContentId
        ORDER BY similarityScore DESC
        LIMIT :limit
    """)
    suspend fun getSimilarContentFromSessions(userId: String = "default_user", limit: Int = 50): List<SimilarContent>

    // Maintenance and Cleanup

    @Query("DELETE FROM watch_history WHERE userId = :userId AND watchDate < :cutoffDate")
    suspend fun deleteOldWatchHistory(userId: String = "default_user", cutoffDate: Long)

    @Query("""
        DELETE FROM watch_history
        WHERE userId = :userId
        AND id NOT IN (
            SELECT id FROM watch_history
            WHERE userId = :userId
            ORDER BY watchDate DESC
            LIMIT :keepCount
        )
    """)
    suspend fun trimWatchHistory(userId: String = "default_user", keepCount: Int = 1000)

    @Query("UPDATE watch_history SET lastAccessedAt = :timestamp WHERE id = :id")
    suspend fun updateLastAccessed(id: Long, timestamp: Long = System.currentTimeMillis())

    @Query("UPDATE watch_history SET engagementScore = :score WHERE id = :id")
    suspend fun updateEngagementScore(id: Long, score: Float)

    // Advanced Queries for Recommendations

    @Query("""
        SELECT * FROM watch_history
        WHERE userId = :userId
        AND contentType = :contentType
        AND completionStatus = 'completed'
        AND watchDate >= :sinceDate
        ORDER BY engagementScore DESC, watchDate DESC
        LIMIT :limit
    """)
    suspend fun getHighlyEngagedContent(
        userId: String = "default_user",
        contentType: String,
        sinceDate: Long,
        limit: Int
    ): List<WatchHistoryEntity>

    @Query("""
        SELECT * FROM watch_history
        WHERE userId = :userId
        AND completionStatus IN ('in_progress', 'abandoned')
        AND watchProgress > :minProgress
        AND watchProgress < :maxProgress
        ORDER BY lastAccessedAt DESC
        LIMIT :limit
    """)
    suspend fun getPartiallyWatchedContent(
        userId: String = "default_user",
        minProgress: Float = 0.1f,
        maxProgress: Float = 0.8f,
        limit: Int = 50
    ): List<WatchHistoryEntity>

    @Query("""
        SELECT sessionStartTime, COUNT(*) as contentCount, SUM(watchDuration) as totalDuration
        FROM watch_history
        WHERE userId = :userId
        AND watchDate >= :startDate
        GROUP BY sessionId, sessionStartTime
        HAVING contentCount > :minContentCount
        ORDER BY totalDuration DESC
        LIMIT :limit
    """)
    suspend fun getTopViewingSessions(
        userId: String = "default_user",
        startDate: Long,
        minContentCount: Int = 2,
        limit: Int = 20
    ): List<TopSession>
}

// Data classes for complex query results
data class ContentStatistic(
    val contentType: String,
    val count: Int,
    val totalDuration: Long
)

data class DailyWatchStatistic(
    val date: String,
    val totalWatchTime: Long,
    val sessionCount: Int
)

data class GenreStatistic(
    val genre: String,
    val watchCount: Int,
    val totalDuration: Long
)

data class CategoryStatistic(
    val category: String,
    val watchCount: Int
)

data class BingeWatchingStatistic(
    val seriesId: String,
    val episodeCount: Int,
    val maxConsecutive: Int,
    val totalDuration: Long
)

data class QualityStatistic(
    val quality: String,
    val count: Int,
    val totalDuration: Long
)

data class DeviceStatistic(
    val deviceInfo: String,
    val count: Int,
    val totalDuration: Long
)

data class ContentPreference(
    val contentId: String,
    val watchCount: Int,
    val avgProgress: Float,
    val totalDuration: Long,
    val lastWatched: Long
)

data class FavoriteGenre(
    val genres: String,
    val watchCount: Int
)

data class SimilarContent(
    val contentId: String,
    val similarityScore: Int
)

data class TopSession(
    val sessionStartTime: Long,
    val contentCount: Int,
    val totalDuration: Long
)