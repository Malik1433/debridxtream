package com.tvonnet.debridxtreamiptv.ui.recommendations

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tvonnet.debridxtreamiptv.data.recommendation.RecommendationEngine
import com.tvonnet.debridxtreamiptv.data.recommendation.RecommendationResult
import com.tvonnet.debridxtreamiptv.data.recommendation.algorithm.PersonalizedRecommender
import com.tvonnet.debridxtreamiptv.data.service.WatchHistoryService
import com.tvonnet.debridxtreamiptv.ui.base.BaseViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for the Recommendations Screen
 *
 * Manages:
 * - Loading and caching of recommendation data
 * - State management for different recommendation categories
 * - Error handling and retry logic
 * - Integration with RecommendationEngine
 * - Real-time updates based on user interactions
 */
@HiltViewModel
class RecommendationsViewModel @Inject constructor(
    private val recommendationEngine: RecommendationEngine,
    private val watchHistoryService: WatchHistoryService,
    private val personalizedRecommender: PersonalizedRecommender
) : BaseViewModel() {

    private val _uiState = MutableStateFlow<RecommendationsUiState>(RecommendationsUiState.Loading)
    val uiState: StateFlow<RecommendationsUiState> = _uiState.asStateFlow()

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    init {
        loadRecommendations()
    }

    /**
     * Load all recommendation categories
     */
    fun loadRecommendations() {
        viewModelScope.launch {
            try {
                _uiState.value = RecommendationsUiState.Loading

                val recommendationsData = fetchRecommendationsData()
                _uiState.value = RecommendationsUiState.Success(recommendationsData)

            } catch (e: Exception) {
                _uiState.value = RecommendationsUiState.Error(
                    message = "Failed to load recommendations: ${e.message}"
                )
            }
        }
    }

    /**
     * Refresh recommendations (useful for pull-to-refresh or periodic updates)
     */
    fun refreshRecommendations() {
        if (_refreshing.value) return

        viewModelScope.launch {
            try {
                _refreshing.value = true

                // Clear cache to force fresh recommendations
                recommendationEngine.clearCache()

                val recommendationsData = fetchRecommendationsData()
                _uiState.value = RecommendationsUiState.Success(recommendationsData)

            } catch (e: Exception) {
                // Don't overwrite existing data on refresh error, just log
                // Could show a toast message instead
            } finally {
                _refreshing.value = false
            }
        }
    }

    /**
     * Fetch recommendations from all categories
     */
    private suspend fun fetchRecommendationsData(): RecommendationsData {
        // Fetch all recommendation types in parallel for better performance
        val personalizedDeferred = async {
            recommendationEngine.getRecommendations(
                algorithm = "personalized",
                limit = 10
            )
        }

        val trendingDeferred = async {
            recommendationEngine.getRecommendations(
                algorithm = "trending",
                timeWindow = "7d",
                limit = 10
            )
        }

        val popularDeferred = async {
            recommendationEngine.getRecommendations(
                algorithm = "popular",
                timeWindow = "30d",
                limit = 10
            )
        }

        val similarDeferred = async {
            recommendationEngine.getRecommendations(
                algorithm = "content_based",
                limit = 10
            )
        }

        val continueWatchingDeferred = async {
            watchHistoryService.getContinueWatchingItems(limit = 10)
        }

        // Wait for all requests to complete
        val personalized = personalizedDeferred.await()
        val trending = trendingDeferred.await()
        val popular = popularDeferred.await()
        val similar = similarDeferred.await()
        val continueWatching = continueWatchingDeferred.await()

        return RecommendationsData(
            personalized = personalized,
            trending = trending,
            popular = popular,
            similar = similar,
            continueWatching = continueWatching.map { watchHistoryItem ->
                // Convert watch history to recommendation results
                RecommendationResult(
                    contentId = watchHistoryItem.contentId,
                    score = calculateContinueWatchingScore(watchHistoryItem),
                    algorithmType = "continue_watching",
                    reason = "Continue watching ${watchHistoryItem.title}",
                    metadata = mapOf(
                        "watch_progress" to watchHistoryItem.watchProgress,
                        "last_watched" to watchHistoryItem.lastWatchedPosition,
                        "total_duration" to watchHistoryItem.totalDuration,
                        "content_type" to watchHistoryItem.contentType
                    )
                )
            }
        )
    }

    /**
     * Calculate score for continue watching items based on progress and recency
     */
    private fun calculateContinueWatchingScore(watchHistoryItem: com.tvonnet.debridxtreamiptv.data.local.entity.WatchHistoryEntity): Float {
        val progressScore = when {
            watchHistoryItem.watchProgress >= 0.9f -> 0.3f // Almost finished, lower priority
            watchHistoryItem.watchProgress >= 0.5f -> 1.0f // Half watched, high priority
            watchHistoryItem.watchProgress >= 0.25f -> 0.8f // Some progress, medium priority
            else -> 0.5f // Just started, lower priority
        }

        // Boost score for recently watched content
        val hoursSinceWatched = (System.currentTimeMillis() - watchHistoryItem.watchDate) / (60 * 60 * 1000)
        val recencyScore = when {
            hoursSinceWatched <= 24 -> 1.0f // Watched in last day
            hoursSinceWatched <= 72 -> 0.8f // Watched in last 3 days
            hoursSinceWatched <= 168 -> 0.6f // Watched in last week
            else -> 0.4f // Watched longer ago
        }

        // Boost score for binge sessions
        val bingeScore = if (watchHistoryItem.isBingeSession) 1.2f else 1.0f

        return (progressScore * recencyScore * bingeScore).coerceAtMost(1.0f)
    }

    /**
     * Get recommendations for a specific algorithm
     */
    fun getRecommendationsForAlgorithm(
        algorithm: String,
        contentType: String? = null,
        limit: Int = 20
    ) {
        viewModelScope.launch {
            try {
                val recommendations = recommendationEngine.getRecommendations(
                    algorithm = algorithm,
                    contentType = contentType,
                    limit = limit
                )

                // Update specific category in current state
                val currentState = _uiState.value
                if (currentState is RecommendationsUiState.Success) {
                    val updatedData = when (algorithm) {
                        "personalized" -> currentState.data.copy(personalized = recommendations)
                        "trending" -> currentState.data.copy(trending = recommendations)
                        "popular" -> currentState.data.copy(popular = recommendations)
                        "content_based" -> currentState.data.copy(similar = recommendations)
                        else -> currentState.data
                    }
                    _uiState.value = RecommendationsUiState.Success(updatedData)
                }

            } catch (e: Exception) {
                // Log error but don't update UI state for individual category failures
            }
        }
    }

    /**
     * Update user preferences based on interaction
     */
    fun onRecommendationInteraction(
        recommendation: RecommendationResult,
        interactionType: String // "click", "long_click", "dismiss", etc.
    ) {
        viewModelScope.launch {
            try {
                // Log the interaction for learning
                watchHistoryService.logRecommendationInteraction(
                    contentId = recommendation.contentId,
                    algorithmType = recommendation.algorithmType,
                    interactionType = interactionType,
                    metadata = recommendation.metadata
                )

                // Could trigger immediate preference update for certain interactions
                if (interactionType == "click") {
                    // Refresh personalized recommendations to incorporate immediate feedback
                    getRecommendationsForAlgorithm("personalized", limit = 10)
                }

            } catch (e: Exception) {
                // Log error but don't disrupt user experience
            }
        }
    }

    /**
     * Dismiss a recommendation (user doesn't want to see it)
     */
    fun dismissRecommendation(recommendation: RecommendationResult) {
        viewModelScope.launch {
            try {
                // Add to dismiss list to avoid showing again
                recommendationEngine.dismissRecommendation(recommendation.contentId)

                // Log the dismissal for learning
                onRecommendationInteraction(recommendation, "dismiss")

                // Refresh recommendations to replace dismissed item
                getRecommendationsForAlgorithm(
                    algorithm = recommendation.algorithmType,
                    limit = 10
                )

            } catch (e: Exception) {
                // Log error but don't disrupt user experience
            }
        }
    }

    /**
     * Get context-based recommendations for specific scenarios
     */
    fun getContextualRecommendations(
        context: RecommendationContext
    ) {
        viewModelScope.launch {
            try {
                val personalizedRecommendations = personalizedRecommender.getRecommendations(
                    userId = "default_user",
                    contentType = context.contentType,
                    context = com.tvonnet.debridxtreamiptv.data.recommendation.algorithm.PersonalizedRecommender.RecommendationContext(
                        timeOfDay = context.timeOfDay,
                        dayOfWeek = context.dayOfWeek,
                        deviceType = context.deviceType,
                        sessionId = context.sessionId
                    ),
                    limit = 10
                )

                // Update personalized recommendations with contextual ones
                val currentState = _uiState.value
                if (currentState is RecommendationsUiState.Success) {
                    val updatedData = currentState.data.copy(
                        personalized = personalizedRecommendations
                    )
                    _uiState.value = RecommendationsUiState.Success(updatedData)
                }

            } catch (e: Exception) {
                // Log error but don't update UI state
            }
        }
    }
}

/**
 * UI State for Recommendations Screen
 */
sealed class RecommendationsUiState {
    object Loading : RecommendationsUiState()
    data class Success(val data: RecommendationsData) : RecommendationsUiState()
    data class Error(val message: String) : RecommendationsUiState()
}

/**
 * Data class containing all recommendation categories
 */
data class RecommendationsData(
    val personalized: List<RecommendationResult> = emptyList(),
    val trending: List<RecommendationResult> = emptyList(),
    val popular: List<RecommendationResult> = emptyList(),
    val similar: List<RecommendationResult> = emptyList(),
    val continueWatching: List<RecommendationResult> = emptyList()
)

/**
 * Context for contextual recommendations
 */
data class RecommendationContext(
    val timeOfDay: Int = 0, // Hour of day (0-23)
    val dayOfWeek: Int = 1, // Day of week (1-7)
    val deviceType: String? = null,
    val sessionId: String? = null,
    val contentType: String? = null,
    val previousContentId: String? = null
)