package com.tvonnet.debridxtreamiptv.data.local.dao

import androidx.room.*
import com.tvonnet.debridxtreamiptv.data.local.entity.UserProfileEntity
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for User Profile operations
 * Phase 3.2: Content Recommendations System
 *
 * Provides comprehensive database operations for user profile management:
 * - CRUD operations for user profiles
 * - Preference and pattern tracking
 * - Recommendation algorithm configuration
 * - Analytics and insights generation
 */
@Dao
interface UserProfileDao {

    // Basic CRUD Operations

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertUserProfile(userProfile: UserProfileEntity)

    @Update
    suspend fun updateUserProfile(userProfile: UserProfileEntity)

    @Delete
    suspend fun deleteUserProfile(userProfile: UserProfileEntity)

    @Query("SELECT * FROM user_profiles WHERE userId = :userId")
    suspend fun getUserProfile(userId: String = "default_user"): UserProfileEntity?

    @Query("SELECT * FROM user_profiles WHERE userId = :userId")
    fun getUserProfileFlow(userId: String = "default_user"): Flow<UserProfileEntity?>

    // Preference Management

    @Query("UPDATE user_profiles SET favoriteGenres = :genres, updatedAt = :timestamp WHERE userId = :userId")
    suspend fun updateFavoriteGenres(userId: String, genres: List<String>, timestamp: Long = System.currentTimeMillis())

    @Query("UPDATE user_profiles SET genreScores = :scores, updatedAt = :timestamp WHERE userId = :userId")
    suspend fun updateGenreScores(userId: String, scores: Map<String, Float>, timestamp: Long = System.currentTimeMillis())

    @Query("UPDATE user_profiles SET preferredCategories = :categories, updatedAt = :timestamp WHERE userId = :userId")
    suspend fun updatePreferredCategories(userId: String, categories: List<String>, timestamp: Long = System.currentTimeMillis())

    @Query("UPDATE user_profiles SET dislikedGenres = :genres, updatedAt = :timestamp WHERE userId = :userId")
    suspend fun updateDislikedGenres(userId: String, genres: List<String>, timestamp: Long = System.currentTimeMillis())

    @Query("UPDATE user_profiles SET blockedContent = :contentIds, updatedAt = :timestamp WHERE userId = :userId")
    suspend fun updateBlockedContent(userId: String, contentIds: List<String>, timestamp: Long = System.currentTimeMillis())

    // Viewing Habits Updates

    @Query("UPDATE user_profiles SET typicalWatchTime = :watchTimes, updatedAt = :timestamp WHERE userId = :userId")
    suspend fun updateTypicalWatchTime(userId: String, watchTimes: Map<String, Float>, timestamp: Long = System.currentTimeMillis())

    @Query("UPDATE user_profiles SET typicalWatchDays = :watchDays, updatedAt = :timestamp WHERE userId = :userId")
    suspend fun updateTypicalWatchDays(userId: String, watchDays: Map<String, Float>, timestamp: Long = System.currentTimeMillis())

    @Query("UPDATE user_profiles SET averageSessionDuration = :duration, updatedAt = :timestamp WHERE userId = :userId")
    suspend fun updateAverageSessionDuration(userId: String, duration: Long, timestamp: Long = System.currentTimeMillis())

    @Query("UPDATE user_profiles SET bingeWatchingTendency = :tendency, updatedAt = :timestamp WHERE userId = :userId")
    suspend fun updateBingeWatchingTendency(userId: String, tendency: Float, timestamp: Long = System.currentTimeMillis())

    @Query("UPDATE user_profiles SET preferredContentLength = :length, updatedAt = :timestamp WHERE userId = :userId")
    suspend fun updatePreferredContentLength(userId: String, length: String, timestamp: Long = System.currentTimeMillis())

    // Quality and Technical Preferences

    @Query("UPDATE user_profiles SET preferredQuality = :quality, updatedAt = :timestamp WHERE userId = :userId")
    suspend fun updatePreferredQuality(userId: String, quality: String, timestamp: Long = System.currentTimeMillis())

    @Query("UPDATE user_profiles SET dataUsagePreference = :preference, updatedAt = :timestamp WHERE userId = :userId")
    suspend fun updateDataUsagePreference(userId: String, preference: String, timestamp: Long = System.currentTimeMillis())

    @Query("UPDATE user_profiles SET subtitlePreference = :preference, updatedAt = :timestamp WHERE userId = :userId")
    suspend fun updateSubtitlePreference(userId: String, preference: String, timestamp: Long = System.currentTimeMillis())

    @Query("UPDATE user_profiles SET preferredAudioLanguage = :language, updatedAt = :timestamp WHERE userId = :userId")
    suspend fun updatePreferredAudioLanguage(userId: String, language: String, timestamp: Long = System.currentTimeMillis())

    @Query("UPDATE user_profiles SET preferredSubtitleLanguage = :language, updatedAt = :timestamp WHERE userId = :userId")
    suspend fun updatePreferredSubtitleLanguage(userId: String, language: String, timestamp: Long = System.currentTimeMillis())

    // Recommendation Configuration

    @Query("UPDATE user_profiles SET recommendationSensitivity = :sensitivity, updatedAt = :timestamp WHERE userId = :userId")
    suspend fun updateRecommendationSensitivity(userId: String, sensitivity: Float, timestamp: Long = System.currentTimeMillis())

    @Query("UPDATE user_profiles SET newContentWeight = :weight, updatedAt = :timestamp WHERE userId = :userId")
    suspend fun updateNewContentWeight(userId: String, weight: Float, timestamp: Long = System.currentTimeMillis())

    @Query("UPDATE user_profiles SET popularityWeight = :weight, updatedAt = :timestamp WHERE userId = :userId")
    suspend fun updatePopularityWeight(userId: String, weight: Float, timestamp: Long = System.currentTimeMillis())

    @Query("UPDATE user_profiles SET diversityWeight = :weight, updatedAt = :timestamp WHERE userId = :userId")
    suspend fun updateDiversityWeight(userId: String, weight: Float, timestamp: Long = System.currentTimeMillis())

    @Query("UPDATE user_profiles SET similarContentWeight = :weight, updatedAt = :timestamp WHERE userId = :userId")
    suspend fun updateSimilarContentWeight(userId: String, weight: Float, timestamp: Long = System.currentTimeMillis())

    @Query("UPDATE user_profiles SET algorithmWeights = :weights, updatedAt = :timestamp WHERE userId = :userId")
    suspend fun updateAlgorithmWeights(userId: String, weights: Map<String, Float>, timestamp: Long = System.currentTimeMillis())

    // Statistics Updates

    @Query("UPDATE user_profiles SET totalWatchTime = totalWatchTime + :duration, lastActive = :timestamp, updatedAt = :timestamp WHERE userId = :userId")
    suspend fun incrementTotalWatchTime(userId: String, duration: Long, timestamp: Long = System.currentTimeMillis())

    @Query("UPDATE user_profiles SET totalContentWatched = totalContentWatched + 1, lastActive = :timestamp, updatedAt = :timestamp WHERE userId = :userId")
    suspend fun incrementTotalContentWatched(userId: String, timestamp: Long = System.currentTimeMillis())

    @Query("UPDATE user_profiles SET totalSessions = totalSessions + 1, lastActive = :timestamp, updatedAt = :timestamp WHERE userId = :userId")
    suspend fun incrementTotalSessions(userId: String, timestamp: Long = System.currentTimeMillis())

    @Query("UPDATE user_profiles SET moviesWatched = moviesWatched + 1, lastActive = :timestamp, updatedAt = :timestamp WHERE userId = :userId")
    suspend fun incrementMoviesWatched(userId: String, timestamp: Long = System.currentTimeMillis())

    @Query("UPDATE user_profiles SET seriesEpisodesWatched = seriesEpisodesWatched + 1, lastActive = :timestamp, updatedAt = :timestamp WHERE userId = :userId")
    suspend fun incrementSeriesEpisodesWatched(userId: String, timestamp: Long = System.currentTimeMillis())

    @Query("UPDATE user_profiles SET liveTvHoursWatched = liveTvHoursWatched + :hours, lastActive = :timestamp, updatedAt = :timestamp WHERE userId = :userId")
    suspend fun incrementLiveTvHoursWatched(userId: String, hours: Long, timestamp: Long = System.currentTimeMillis())

    @Query("UPDATE user_profiles SET contentCompleted = contentCompleted + 1, lastActive = :timestamp, updatedAt = :timestamp WHERE userId = :userId")
    suspend fun incrementContentCompleted(userId: String, timestamp: Long = System.currentTimeMillis())

    @Query("UPDATE user_profiles SET contentAbandoned = contentAbandoned + 1, lastActive = :timestamp, updatedAt = :timestamp WHERE userId = :userId")
    suspend fun incrementContentAbandoned(userId: String, timestamp: Long = System.currentTimeMillis())

    @Query("UPDATE user_profiles SET feedbackGiven = feedbackGiven + 1, lastActive = :timestamp, updatedAt = :timestamp WHERE userId = :userId")
    suspend fun incrementFeedbackGiven(userId: String, timestamp: Long = System.currentTimeMillis())

    // Device Usage Tracking

    @Query("UPDATE user_profiles SET primaryDeviceType = :deviceType, lastActive = :timestamp, updatedAt = :timestamp WHERE userId = :userId")
    suspend fun updatePrimaryDeviceType(userId: String, deviceType: String, timestamp: Long = System.currentTimeMillis())

    @Query("UPDATE user_profiles SET deviceUsageStats = :stats, lastActive = :timestamp, updatedAt = :timestamp WHERE userId = :userId")
    suspend fun updateDeviceUsageStats(userId: String, stats: Map<String, Long>, timestamp: Long = System.currentTimeMillis())

    @Query("UPDATE user_profiles SET networkUsagePatterns = :patterns, lastActive = :timestamp, updatedAt = :timestamp WHERE userId = :userId")
    suspend fun updateNetworkUsagePatterns(userId: String, patterns: Map<String, Long>, timestamp: Long = System.currentTimeMillis())

    // Privacy and Control Settings

    @Query("UPDATE user_profiles SET dataCollectionEnabled = :enabled, updatedAt = :timestamp WHERE userId = :userId")
    suspend fun updateDataCollectionEnabled(userId: String, enabled: Boolean, timestamp: Long = System.currentTimeMillis())

    @Query("UPDATE user_profiles SET personalizationEnabled = :enabled, updatedAt = :timestamp WHERE userId = :userId")
    suspend fun updatePersonalizationEnabled(userId: String, enabled: Boolean, timestamp: Long = System.currentTimeMillis())

    @Query("UPDATE user_profiles SET analyticsEnabled = :enabled, updatedAt = :timestamp WHERE userId = :userId")
    suspend fun updateAnalyticsEnabled(userId: String, enabled: Boolean, timestamp: Long = System.currentTimeMillis())

    @Query("UPDATE user_profiles SET recommendationAgeLimit = :limit, updatedAt = :timestamp WHERE userId = :userId")
    suspend fun updateRecommendationAgeLimit(userId: String, limit: String?, timestamp: Long = System.currentTimeMillis())

    // Analytics and Insights Queries

    @Query("SELECT totalWatchTime, totalContentWatched, totalSessions, moviesWatched, seriesEpisodesWatched, liveTvHoursWatched FROM user_profiles WHERE userId = :userId")
    suspend fun getUsageStatistics(userId: String = "default_user"): UsageStatistics?

    @Query("SELECT favoriteGenres, genreScores, preferredCategories, dislikedGenres FROM user_profiles WHERE userId = :userId")
    suspend fun getPreferenceProfile(userId: String = "default_user"): PreferenceProfile?

    @Query("SELECT algorithmWeights, recommendationSensitivity, newContentWeight, popularityWeight, diversityWeight, similarContentWeight FROM user_profiles WHERE userId = :userId")
    suspend fun getRecommendationConfiguration(userId: String = "default_user"): RecommendationConfiguration?

    @Query("SELECT primaryDeviceType, deviceUsageStats, networkUsagePatterns FROM user_profiles WHERE userId = :userId")
    suspend fun getDeviceUsageProfile(userId: String = "default_user"): DeviceUsageProfile?

    @Query("SELECT bingeWatchingTendency, averageSessionDuration, typicalWatchTime, typicalWatchDays FROM user_profiles WHERE userId = :userId")
    suspend fun getBehavioralProfile(userId: String = "default_user"): BehavioralProfile?

    // Batch Updates for Watch History Integration

    @Query("""
        UPDATE user_profiles
        SET totalWatchTime = totalWatchTime + :watchTime,
            totalContentWatched = totalContentWatched + :contentCount,
            totalSessions = totalSessions + :sessionCount,
            moviesWatched = moviesWatched + :moviesCount,
            seriesEpisodesWatched = seriesEpisodesWatched + :episodesCount,
            liveTvHoursWatched = liveTvHoursWatched + :liveTvHours,
            contentCompleted = contentCompleted + :completedCount,
            contentAbandoned = contentAbandoned + :abandonedCount,
            lastActive = :timestamp,
            updatedAt = :timestamp
        WHERE userId = :userId
    """)
    suspend fun batchUpdateWatchStatistics(
        userId: String,
        watchTime: Long,
        contentCount: Int,
        sessionCount: Int,
        moviesCount: Int,
        episodesCount: Int,
        liveTvHours: Long,
        completedCount: Int,
        abandonedCount: Int,
        timestamp: Long = System.currentTimeMillis()
    )

    @Query("""
        UPDATE user_profiles
        SET genreScores = :genreScores,
            categoryScores = :categoryScores,
            lastActive = :timestamp,
            updatedAt = :timestamp
        WHERE userId = :userId
    """)
    suspend fun batchUpdateAffinityScores(
        userId: String,
        genreScores: Map<String, Float>,
        categoryScores: Map<String, Float>,
        timestamp: Long = System.currentTimeMillis()
    )

    // Recommendation System Integration

    @Query("UPDATE user_profiles SET lastRecommendationUpdate = :timestamp, updatedAt = :timestamp WHERE userId = :userId")
    suspend fun updateLastRecommendationUpdate(userId: String, timestamp: Long = System.currentTimeMillis())

    @Query("UPDATE user_profiles SET recommendationRefreshInterval = :interval, updatedAt = :timestamp WHERE userId = :userId")
    suspend fun updateRecommendationRefreshInterval(userId: String, interval: Long, timestamp: Long = System.currentTimeMillis())

    @Query("SELECT lastRecommendationUpdate, recommendationRefreshInterval, algorithmWeights, datasetVersion FROM user_profiles WHERE userId = :userId")
    suspend fun getRecommendationSystemStatus(userId: String = "default_user"): RecommendationSystemStatus?

    // User Profile Analytics

    @Query("SELECT COUNT(*) FROM user_profiles")
    suspend fun getTotalUserProfilesCount(): Int

    @Query("SELECT AVG(totalWatchTime) FROM user_profiles WHERE totalWatchTime > 0")
    suspend fun getAverageWatchTime(): Float?

    @Query("SELECT AVG(moviesWatched) FROM user_profiles WHERE moviesWatched > 0")
    suspend fun getAverageMoviesWatched(): Float?

    @Query("SELECT AVG(seriesEpisodesWatched) FROM user_profiles WHERE seriesEpisodesWatched > 0")
    suspend fun getAverageSeriesEpisodesWatched(): Float?

    @Query("""
        SELECT primaryDeviceType, COUNT(*) as userCount
        FROM user_profiles
        WHERE primaryDeviceType IS NOT NULL
        GROUP BY primaryDeviceType
        ORDER BY userCount DESC
    """)
    suspend fun getDeviceTypeDistribution(): List<DeviceTypeDistribution>

    @Query("""
        SELECT preferredQuality, COUNT(*) as userCount
        FROM user_profiles
        WHERE preferredQuality IS NOT NULL
        GROUP BY preferredQuality
        ORDER BY userCount DESC
    """)
    suspend fun getQualityPreferenceDistribution(): List<QualityPreferenceDistribution>

    // Maintenance and Cleanup

    @Query("UPDATE user_profiles SET lastActive = :timestamp, updatedAt = :timestamp WHERE userId = :userId")
    suspend fun updateLastActive(userId: String, timestamp: Long = System.currentTimeMillis())

    @Query("DELETE FROM user_profiles WHERE lastActive < :cutoffDate AND userId != 'default_user'")
    suspend fun deleteInactiveUsers(cutoffDate: Long)

    @Query("UPDATE user_profiles SET version = :newVersion, updatedAt = :timestamp WHERE userId = :userId")
    suspend fun updateProfileVersion(userId: String, newVersion: Int, timestamp: Long = System.currentTimeMillis())

    @Query("UPDATE user_profiles SET metadata = :metadata, updatedAt = :timestamp WHERE userId = :userId")
    suspend fun updateMetadata(userId: String, metadata: Map<String, Any>, timestamp: Long = System.currentTimeMillis())
}

// Data classes for complex query results
data class UsageStatistics(
    val totalWatchTime: Long,
    val totalContentWatched: Int,
    val totalSessions: Int,
    val moviesWatched: Int,
    val seriesEpisodesWatched: Int,
    val liveTvHoursWatched: Long
)

data class PreferenceProfile(
    val favoriteGenres: List<String>,
    val genreScores: Map<String, Float>,
    val preferredCategories: List<String>,
    val dislikedGenres: List<String>
)

data class RecommendationConfiguration(
    val algorithmWeights: Map<String, Float>,
    val recommendationSensitivity: Float,
    val newContentWeight: Float,
    val popularityWeight: Float,
    val diversityWeight: Float,
    val similarContentWeight: Float
)

data class DeviceUsageProfile(
    val primaryDeviceType: String,
    val deviceUsageStats: Map<String, Long>,
    val networkUsagePatterns: Map<String, Long>
)

data class BehavioralProfile(
    val bingeWatchingTendency: Float,
    val averageSessionDuration: Long,
    val typicalWatchTime: Map<String, Float>,
    val typicalWatchDays: Map<String, Float>
)

data class RecommendationSystemStatus(
    val lastRecommendationUpdate: Long,
    val recommendationRefreshInterval: Long,
    val algorithmWeights: Map<String, Float>,
    val datasetVersion: String
)

data class DeviceTypeDistribution(
    val primaryDeviceType: String,
    val userCount: Int
)

data class QualityPreferenceDistribution(
    val preferredQuality: String,
    val userCount: Int
)