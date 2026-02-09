package com.tvonnet.debridxtreamiptv.data.service

import android.content.Context
import android.util.Log
import com.tvonnet.debridxtreamiptv.data.local.dao.ContentInteractionDao
import com.tvonnet.debridxtreamiptv.data.local.dao.UserProfileDao
import com.tvonnet.debridxtreamiptv.data.local.dao.WatchHistoryDao
import com.tvonnet.debridxtreamiptv.data.local.entity.ContentInteractionEntity
import com.tvonnet.debridxtreamiptv.data.local.entity.WatchHistoryEntity
import com.tvonnet.debridxtreamiptv.data.local.entity.WatchHistoryContentType
import com.tvonnet.debridxtreamiptv.data.local.entity.WatchHistoryCompletionStatus
import com.tvonnet.debridxtreamiptv.data.local.entity.InteractionType
import com.tvonnet.debridxtreamiptv.data.local.entity.InteractionSubType
import com.tvonnet.debridxtreamiptv.data.local.entity.DiscoveryMethod
import com.tvonnet.debridxtreamiptv.data.local.entity.SourceScreen
import com.tvonnet.debridxtreamiptv.data.model.ContentType
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.*
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Service for tracking user viewing sessions and watch history
 * Phase 3.2: Content Recommendations System
 *
 * Manages real-time watch history tracking including:
 * - Session management and binge-watching detection
 * - Progress tracking and completion status calculation
 * - Interaction logging for recommendation data
 * - User profile updates based on viewing behavior
 */
@Singleton
class WatchHistoryService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val watchHistoryDao: WatchHistoryDao,
    private val userProfileDao: UserProfileDao,
    private val contentInteractionDao: ContentInteractionDao
) {
    companion object {
        private const val TAG = "WatchHistoryService"
        private const val PROGRESS_UPDATE_INTERVAL = 5000L // 5 seconds
        private const val AUTO_SAVE_INTERVAL = 30000L // 30 seconds
        private const val BINGE_WATCH_THRESHOLD_MINUTES = 15 // Minutes between episodes
        private const val SESSION_TIMEOUT_MINUTES = 30 // Minutes of inactivity
    }

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Current session state
    private val _currentSession = MutableStateFlow<WatchingSession?>(null)
    val currentSession: StateFlow<WatchingSession?> = _currentSession.asStateFlow()

    private val _isTracking = MutableStateFlow(false)
    val isTracking: StateFlow<Boolean> = _isTracking.asStateFlow()

    // Progress tracking
    private var progressUpdateJob: kotlinx.coroutines.Job? = null
    private var autoSaveJob: kotlinx.coroutines.Job? = null
    private var lastProgressUpdate = 0L
    private var sessionStartTime = 0L

    /**
     * Starts tracking a viewing session
     */
    fun startWatching(
        contentId: String,
        contentType: ContentType,
        title: String,
        posterUrl: String? = null,
        seriesId: String? = null,
        seasonNumber: Int? = null,
        episodeNumber: Int? = null,
        episodeTitle: String? = null,
        totalDuration: Long,
        recommendationId: String? = null,
        discoveryMethod: String? = null,
        sourceScreen: String? = null
    ) {
        Log.d(TAG, "Starting watch session for content: $title")

        serviceScope.launch {
            try {
                // End any existing session
                endCurrentSession()

                val sessionId = generateSessionId()
                val now = System.currentTimeMillis()
                sessionStartTime = now

                // Create new watching session
                val session = WatchingSession(
                    sessionId = sessionId,
                    contentId = contentId,
                    contentType = mapContentType(contentType),
                    title = title,
                    posterUrl = posterUrl,
                    seriesId = seriesId,
                    seasonNumber = seasonNumber,
                    episodeNumber = episodeNumber,
                    episodeTitle = episodeTitle,
                    totalDuration = totalDuration,
                    currentPosition = 0L,
                    watchProgress = 0f,
                    startTime = now,
                    lastUpdateTime = now,
                    pauseCount = 0,
                    seekCount = 0,
                    recommendationId = recommendationId,
                    discoveryMethod = discoveryMethod,
                    sourceScreen = sourceScreen
                )

                _currentSession.value = session
                _isTracking.value = true

                // Check for existing watch history for this content
                val existingHistory = watchHistoryDao.getLatestWatchHistoryByContent(contentId)

                if (existingHistory != null) {
                    // Continue from previous position
                    session.currentPosition = existingHistory.lastWatchedPosition
                    session.watchProgress = existingHistory.watchProgress
                    Log.d(TAG, "Resuming from position: ${session.currentPosition}ms (${String.format("%.1f", session.watchProgress * 100)}%)")
                }

                // Log content view interaction
                logContentInteraction(
                    contentId = contentId,
                    contentType = mapContentType(contentType),
                    interactionType = InteractionType.CONTENT_VIEW,
                    sessionId = sessionId,
                    discoveryMethod = discoveryMethod,
                    sourceScreen = sourceScreen,
                    recommendationId = recommendationId,
                    contentTitle = title,
                    posterUrl = posterUrl
                )

                // Start progress tracking
                startProgressTracking()

                Log.d(TAG, "Watch session started successfully")

            } catch (e: Exception) {
                Log.e(TAG, "Error starting watch session", e)
            }
        }
    }

    /**
     * Updates the current playback position
     */
    fun updatePosition(
        position: Long,
        isPlaying: Boolean = true,
        quality: String? = null,
        playbackSpeed: Float = 1f
    ) {
        val session = _currentSession.value ?: return

        serviceScope.launch {
            try {
                val now = System.currentTimeMillis()
                val watchDuration = now - sessionStartTime
                val progress = if (session.totalDuration > 0) {
                    (position.toFloat() / session.totalDuration.toFloat()).coerceIn(0f, 1f)
                } else 0f

                session.currentPosition = position
                session.watchProgress = progress
                session.lastUpdateTime = now
                session.lastPlayingState = isPlaying
                session.quality = quality
                session.playbackSpeed = playbackSpeed

                // Auto-save progress periodically
                if (now - lastProgressUpdate > AUTO_SAVE_INTERVAL) {
                    saveProgress(session, watchDuration)
                    lastProgressUpdate = now
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error updating position", e)
            }
        }
    }

    /**
     * Records a pause event
     */
    fun recordPause() {
        val session = _currentSession.value ?: return
        session.pauseCount++

        logContentInteraction(
            contentId = session.contentId,
            contentType = session.contentType,
            interactionType = InteractionType.CONTENT_PAUSE,
            sessionId = session.sessionId
        )

        Log.d(TAG, "Pause recorded. Total pauses: ${session.pauseCount}")
    }

    /**
     * Records a resume event
     */
    fun recordResume() {
        val session = _currentSession.value ?: return

        logContentInteraction(
            contentId = session.contentId,
            contentType = session.contentType,
            interactionType = InteractionType.CONTENT_RESUME,
            sessionId = session.sessionId
        )

        Log.d(TAG, "Resume recorded")
    }

    /**
     * Records a seek event
     */
    fun recordSeek(fromPosition: Long, toPosition: Long) {
        val session = _currentSession.value ?: return
        session.seekCount++

        logContentInteraction(
            contentId = session.contentId,
            contentType = session.contentType,
            interactionType = InteractionType.SCROLL,
            sessionId = session.sessionId,
            interactionValue = mapOf(
                "from_position" to fromPosition,
                "to_position" to toPosition,
                "seek_distance" to kotlin.math.abs(toPosition - fromPosition)
            )
        )

        Log.d(TAG, "Seek recorded. Total seeks: ${session.seekCount}")
    }

    /**
     * Records a quality change
     */
    fun recordQualityChange(fromQuality: String?, toQuality: String?) {
        val session = _currentSession.value ?: return

        logContentInteraction(
            contentId = session.contentId,
            contentType = session.contentType,
            interactionType = InteractionType.QUALITY_CHANGE,
            sessionId = session.sessionId,
            interactionValue = mapOf(
                "from_quality" to fromQuality,
                "to_quality" to toQuality
            )
        )

        session.quality = toQuality
        Log.d(TAG, "Quality change recorded: $fromQuality -> $toQuality")
    }

    /**
     * Ends the current watching session
     */
    fun endCurrentSession(completionStatus: String = WatchHistoryCompletionStatus.IN_PROGRESS) {
        val session = _currentSession.value ?: return

        Log.d(TAG, "Ending watch session for: ${session.title}")

        serviceScope.launch {
            try {
                stopProgressTracking()

                val now = System.currentTimeMillis()
                val watchDuration = now - sessionStartTime
                val finalProgress = session.watchProgress

                // Determine completion status
                val finalCompletionStatus = when {
                    finalProgress >= 0.9f -> WatchHistoryCompletionStatus.COMPLETED
                    finalProgress <= 0.05f -> WatchHistoryCompletionStatus.NOT_STARTED
                    completionStatus == WatchHistoryCompletionStatus.ABANDONED -> WatchHistoryCompletionStatus.ABANDONED
                    else -> WatchHistoryCompletionStatus.IN_PROGRESS
                }

                // Check for binge-watching
                val isBingeWatch = checkForBingeWatch(session)

                // Create watch history entry
                val watchHistory = WatchHistoryEntity(
                    contentId = session.contentId,
                    contentType = session.contentType,
                    title = session.title,
                    posterUrl = session.posterUrl,
                    seriesId = session.seriesId,
                    seasonNumber = session.seasonNumber,
                    episodeNumber = session.episodeNumber,
                    episodeTitle = session.episodeTitle,
                    watchDuration = watchDuration,
                    totalDuration = session.totalDuration,
                    lastWatchedPosition = session.currentPosition,
                    watchProgress = finalProgress,
                    completionThreshold = 0.9f,
                    sessionId = session.sessionId,
                    sessionStartTime = session.startTime,
                    sessionEndTime = now,
                    pauseCount = session.pauseCount,
                    seekCount = session.seekCount,
                    watchDate = session.startTime,
                    completionStatus = finalCompletionStatus,
                    engagementScore = calculateEngagementScore(session, finalProgress, watchDuration),
                    isBingeWatch = isBingeWatch,
                    consecutiveEpisodes = if (isBingeWatch) calculateConsecutiveEpisodes(session) else 1,
                    quality = session.quality,
                    playbackSpeed = session.playbackSpeed,
                    genres = emptyList(), // Would be populated from content metadata
                    categories = emptyList(),
                    tags = emptyList(),
                    metadata = mapOf(
                        "device_type" to "tv",
                        "network_type" to "wifi", // Would be detected
                        "recommendation_id" to session.recommendationId,
                        "discovery_method" to session.discoveryMethod,
                        "source_screen" to session.sourceScreen
                    ),
                    recommendationSource = session.discoveryMethod,
                    isRecommendedContent = session.recommendationId != null
                )

                // Save watch history
                val watchHistoryId = watchHistoryDao.insertWatchHistory(watchHistory)

                // Log interaction based on completion status
                val interactionType = when (finalCompletionStatus) {
                    WatchHistoryCompletionStatus.COMPLETED -> InteractionType.CONTENT_COMPLETE
                    WatchHistoryCompletionStatus.ABANDONED -> InteractionType.CONTENT_ABANDON
                    else -> InteractionType.CONTENT_STOP
                }

                logContentInteraction(
                    contentId = session.contentId,
                    contentType = session.contentType,
                    interactionType = interactionType,
                    sessionId = session.sessionId,
                    interactionValue = mapOf(
                        "watch_history_id" to watchHistoryId,
                        "final_progress" to finalProgress,
                        "watch_duration" to watchDuration,
                        "completion_status" to finalCompletionStatus
                    )
                )

                // Update user profile
                updateUserProfile(session, finalCompletionStatus, watchDuration)

                // Reset state
                _currentSession.value = null
                _isTracking.value = false
                sessionStartTime = 0L

                Log.d(TAG, "Watch session ended successfully. Final progress: ${String.format("%.1f", finalProgress * 100)}%")

            } catch (e: Exception) {
                Log.e(TAG, "Error ending watch session", e)
            }
        }
    }

    /**
     * Gets the resume position for content
     */
    suspend fun getResumePosition(contentId: String): Long? {
        return try {
            val history = watchHistoryDao.getLatestWatchHistoryByContent(contentId)
            history?.lastWatchedPosition
        } catch (e: Exception) {
            Log.e(TAG, "Error getting resume position", e)
            null
        }
    }

    /**
     * Gets continue watching content
     */
    fun getContinueWatching() = watchHistoryDao.getContinueWatching()

    /**
     * Gets recently watched content
     */
    fun getRecentlyWatched() = watchHistoryDao.getRecentlyWatched()

    /**
     * Gets watch statistics
     */
    suspend fun getWatchStatistics() = watchHistoryDao.getUsageStatistics()

    // Private helper methods

    private fun startProgressTracking() {
        progressUpdateJob = serviceScope.launch {
            while (isActive && _currentSession.value != null) {
                delay(PROGRESS_UPDATE_INTERVAL)
                _currentSession.value?.let { session ->
                    // Auto-save progress
                    val watchDuration = System.currentTimeMillis() - sessionStartTime
                    saveProgress(session, watchDuration)
                }
            }
        }
    }

    private fun stopProgressTracking() {
        progressUpdateJob?.cancel()
        autoSaveJob?.cancel()
    }

    private suspend fun saveProgress(session: WatchingSession, watchDuration: Long) {
        try {
            // Update existing watch history if it exists
            val existingHistory = watchHistoryDao.getLatestWatchHistoryByContent(session.contentId)

            if (existingHistory != null) {
                val updatedHistory = existingHistory.copy(
                    watchDuration = existingHistory.watchDuration + (PROGRESS_UPDATE_INTERVAL),
                    lastWatchedPosition = session.currentPosition,
                    watchProgress = session.watchProgress,
                    updatedAt = System.currentTimeMillis()
                )
                watchHistoryDao.updateWatchHistory(updatedHistory)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error saving progress", e)
        }
    }

    private fun logContentInteraction(
        contentId: String,
        contentType: String,
        interactionType: String,
        sessionId: String,
        interactionSubType: String? = null,
        interactionValue: Map<String, Any> = emptyMap(),
        discoveryMethod: String? = null,
        sourceScreen: String? = null,
        recommendationId: String? = null,
        contentTitle: String? = null,
        posterUrl: String? = null
    ) {
        serviceScope.launch {
            try {
                val now = System.currentTimeMillis()
                val calendar = Calendar.getInstance()

                val interaction = ContentInteractionEntity(
                    sessionId = sessionId,
                    contentId = contentId,
                    contentType = contentType,
                    interactionType = interactionType,
                    interactionSubType = interactionSubType,
                    sourceScreen = sourceScreen,
                    discoveryMethod = discoveryMethod,
                    recommendationId = recommendationId,
                    interactionValue = interactionValue,
                    userId = "default_user",
                    userTimeOfDay = calendar.get(Calendar.HOUR_OF_DAY),
                    userDayOfWeek = calendar.get(Calendar.DAY_OF_WEEK),
                    deviceType = "tv",
                    contentTitle = contentTitle,
                    posterUrl = posterUrl,
                    timestamp = now
                )

                contentInteractionDao.insertInteraction(interaction)

            } catch (e: Exception) {
                Log.e(TAG, "Error logging content interaction", e)
            }
        }
    }

    private fun checkForBingeWatch(session: WatchingSession): Boolean {
        // Check if user has watched previous episodes in the same series recently
        if (session.seriesId == null) return false

        return try {
            // This would check for recent episodes from the same series
            // Implementation depends on business logic for binge-watching detection
            false // Simplified for now
        } catch (e: Exception) {
            Log.e(TAG, "Error checking for binge watch", e)
            false
        }
    }

    private fun calculateConsecutiveEpisodes(session: WatchingSession): Int {
        // Calculate consecutive episodes watched in the same session
        // Implementation would depend on tracking episode sequence
        return 1 // Simplified for now
    }

    private fun calculateEngagementScore(
        session: WatchingSession,
        progress: Float,
        duration: Long
    ): Float {
        // Calculate engagement score based on various factors
        val progressScore = progress * 0.4f
        val durationScore = (duration.toFloat() / session.totalDuration) * 0.3f
        val pausePenalty = (session.pauseCount * 0.02f).coerceAtMost(0.2f)
        val seekPenalty = (session.seekCount * 0.01f).coerceAtMost(0.1f)

        return (progressScore + durationScore - pausePenalty - seekPenalty).coerceIn(0f, 1f)
    }

    private suspend fun updateUserProfile(
        session: WatchingSession,
        completionStatus: String,
        watchDuration: Long
    ) {
        try {
            // Update user statistics
            userProfileDao.incrementTotalWatchTime(watchDuration)
            userProfileDao.incrementTotalContentWatched()
            userProfileDao.incrementTotalSessions()

            when (session.contentType) {
                WatchHistoryContentType.MOVIE -> userProfileDao.incrementMoviesWatched()
                WatchHistoryContentType.SERIES_EPISODE -> userProfileDao.incrementSeriesEpisodesWatched()
                WatchHistoryContentType.LIVE_TV -> userProfileDao.incrementLiveTvHoursWatched(watchDuration / (1000 * 60 * 60))
            }

            if (completionStatus == WatchHistoryCompletionStatus.COMPLETED) {
                userProfileDao.incrementContentCompleted()
            } else if (completionStatus == WatchHistoryCompletionStatus.ABANDONED) {
                userProfileDao.incrementContentAbandoned()
            }

            userProfileDao.updateLastActive("default_user")

        } catch (e: Exception) {
            Log.e(TAG, "Error updating user profile", e)
        }
    }

    private fun generateSessionId(): String {
        return UUID.randomUUID().toString()
    }

    private fun mapContentType(contentType: ContentType): String {
        return when (contentType) {
            ContentType.LIVE_TV -> WatchHistoryContentType.LIVE_TV
            ContentType.MOVIE -> WatchHistoryContentType.MOVIE
            ContentType.SERIES -> WatchHistoryContentType.SERIES_EPISODE
        }
    }

    /**
     * Data class representing an active watching session
     */
    data class WatchingSession(
        val sessionId: String,
        val contentId: String,
        val contentType: String,
        val title: String,
        val posterUrl: String?,
        val seriesId: String?,
        val seasonNumber: Int?,
        val episodeNumber: Int?,
        val episodeTitle: String?,
        val totalDuration: Long,
        var currentPosition: Long,
        var watchProgress: Float,
        val startTime: Long,
        var lastUpdateTime: Long,
        var pauseCount: Int,
        var seekCount: Int,
        val recommendationId: String?,
        val discoveryMethod: String?,
        val sourceScreen: String?,
        var lastPlayingState: Boolean = true,
        var quality: String? = null,
        var playbackSpeed: Float = 1f
    )
}