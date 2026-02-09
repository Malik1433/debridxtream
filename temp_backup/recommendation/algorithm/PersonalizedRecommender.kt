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
import kotlin.math.abs

/**
 * Personalized recommendation algorithm
 * Creates highly tailored recommendations based on individual user behavior:
 * - Deep user preference analysis and learning
 * - Time-based and context-aware recommendations
 * - Viewing pattern recognition and habit analysis
 * - Dynamic preference adaptation and feedback incorporation
 */
@Singleton
class PersonalizedRecommender @Inject constructor(
    private val watchHistoryDao: WatchHistoryDao,
    private val contentInteractionDao: ContentInteractionDao,
    private val userProfileDao: UserProfileDao
) {
    companion object {
        private const val TAG = "PersonalizedRecommender"
        private const val DEFAULT_LIMIT = 20
        private const val MIN_WATCH_HISTORY_FOR_PERSONALIZATION = 5
        private const val PREFERENCE_UPDATE_THRESHOLD = 10 // Interactions before updating profile
    }

    /**
     * Gets personalized recommendations for the user
     */
    suspend fun getRecommendations(
        userId: String = "default_user",
        limit: Int = DEFAULT_LIMIT,
        contentType: String? = null,
        context: RecommendationContext = RecommendationContext()
    ): List<RecommendationResult> {
        return try {
            withContext(Dispatchers.IO) {
                Log.d(TAG, "Generating personalized recommendations for user: $userId")

                // Get user's profile and preferences
                val userProfile = userProfileDao.getUserProfile(userId)
                    ?: return@withContext emptyList<RecommendationResult>()

                // Check if user has enough history for personalization
                val watchHistory = watchHistoryDao.getWatchHistory(userId, 100)
                if (watchHistory.size < MIN_WATCH_HISTORY_FOR_PERSONALIZATION) {
                    Log.d(TAG, "Insufficient watch history for personalization")
                    return@withContext emptyList()
                }

                // Generate personalized recommendations
                val recommendations = generatePersonalizedRecommendations(
                    userId = userId,
                    userProfile = userProfile,
                    watchHistory = watchHistory,
                    contentType = contentType,
                    context = context,
                    limit = limit
                )

                // Update user profile based on latest interactions if needed
                updateUserProfileIfNeeded(userId, watchHistory.size)

                Log.d(TAG, "Generated ${recommendations.size} personalized recommendations")
                recommendations
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error generating personalized recommendations", e)
            emptyList()
        }
    }

    private suspend fun generatePersonalizedRecommendations(
        userId: String,
        userProfile: com.tvonnet.debridxtreamiptv.data.local.entity.UserProfileEntity,
        watchHistory: List<com.tvonnet.debridxtreamiptv.data.local.entity.WatchHistoryEntity>,
        contentType: String?,
        context: RecommendationContext,
        limit: Int
    ): List<RecommendationResult> {
        val recommendationCandidates = mutableListOf<PersonalizedCandidate>()

        // Analyze user's viewing patterns and preferences
        val userPatterns = analyzeViewingPatterns(watchHistory)
        val preferenceProfile = buildPreferenceProfile(userProfile, watchHistory)
        val contextualFactors = analyzeContextualFactors(context, watchHistory)

        // Generate candidates from different personalization strategies
        val contentBasedCandidates = async {
            generateContentBasedPersonalizedCandidates(preferenceProfile, contentType, limit / 3)
        }
        val patternBasedCandidates = async {
            generatePatternBasedPersonalizedCandidates(userPatterns, contentType, limit / 3)
        }
        val contextualCandidates = async {
            generateContextualPersonalizedCandidates(contextualFactors, contentType, limit / 3)
        }

        val (contentBased, patternBased, contextual) = awaitAll(
            contentBasedCandidates,
            patternBasedCandidates,
            contextualCandidates
        )

        recommendationCandidates.addAll(contentBased)
        recommendationCandidates.addAll(patternBased)
        recommendationCandidates.addAll(contextual)

        // Score and rank candidates
        val scoredCandidates = scoreAndRankCandidates(
            candidates = recommendationCandidates,
            preferenceProfile = preferenceProfile,
            userPatterns = userPatterns,
            contextualFactors = contextualFactors
        )

        // Convert to recommendation results
        return scoredCandidates
            .take(limit)
            .map { candidate ->
                RecommendationResult(
                    contentId = candidate.contentId,
                    score = candidate.personalizedScore.coerceIn(0f, 1f),
                    algorithmType = "personalized",
                    reason = candidate.reason,
                    metadata = candidate.metadata + mapOf(
                        "personalization_factors" to candidate.personalizationFactors,
                        "context_relevance" to candidate.contextRelevance,
                        "preference_match" to candidate.preferenceMatch
                    )
                )
            }
    }

    private fun analyzeViewingPatterns(
        watchHistory: List<com.tvonnet.debridxtreamiptv.data.local.entity.WatchHistoryEntity>
    ): ViewingPatterns {
        val timePatterns = analyzeTimePatterns(watchHistory)
        val contentPatterns = analyzeContentPatterns(watchHistory)
        val engagementPatterns = analyzeEngagementPatterns(watchHistory)
        val bingePatterns = analyzeBingePatterns(watchHistory)

        return ViewingPatterns(
            timePatterns = timePatterns,
            contentPatterns = contentPatterns,
            engagementPatterns = engagementPatterns,
            bingePatterns = bingePatterns
        )
    }

    private fun analyzeTimePatterns(
        watchHistory: List<com.tvonnet.debridxtreamiptv.data.local.entity.WatchHistoryEntity>
    ): TimePatterns {
        val hourDistribution = IntArray(24)
        val dayOfWeekDistribution = IntArray(7)
        val sessionDurations = mutableListOf<Long>()

        for (entry in watchHistory) {
            val calendar = java.util.Calendar.getInstance().apply {
                timeInMillis = entry.watchDate
            }

            hourDistribution[calendar.get(java.util.Calendar.HOUR_OF_DAY)]++
            dayOfWeekDistribution[calendar.get(java.util.Calendar.DAY_OF_WEEK)]++
            sessionDurations.add(entry.watchDuration)
        }

        // Find peak watching hours
        val peakHours = hourDistribution
            .mapIndexed { index, count -> index to count }
            .sortedByDescending { it.second }
            .take(3)
            .map { it.first }

        // Average session duration
        val avgSessionDuration = if (sessionDurations.isNotEmpty()) {
            sessionDurations.average().toLong()
        } else 0L

        return TimePatterns(
            peakHours = peakHours,
            hourDistribution = hourDistribution,
            dayOfWeekDistribution = dayOfWeekDistribution,
            averageSessionDuration = avgSessionDuration
        )
    }

    private fun analyzeContentPatterns(
        watchHistory: List<com.tvonnet.debridxtreamiptv.data.local.entity.WatchHistoryEntity>
    ): ContentPatterns {
        val genreFrequency = mutableMapOf<String, Int>()
        val categoryFrequency = mutableMapOf<String, Int>()
        val contentTypeFrequency = mutableMapOf<String, Int>()
        val watchingStreaks = mutableListOf<Int>()

        var currentStreak = 0
        var lastWatchDate = 0L

        for (entry in watchHistory.sortedBy { it.watchDate }) {
            // Update genre frequencies
            entry.genres.forEach { genre ->
                genreFrequency[genre] = (genreFrequency[genre] ?: 0) + 1
            }

            // Update category frequencies
            entry.categories.forEach { category ->
                categoryFrequency[category] = (categoryFrequency[category] ?: 0) + 1
            }

            // Update content type frequencies
            contentTypeFrequency[entry.contentType] =
                (contentTypeFrequency[entry.contentType] ?: 0) + 1

            // Calculate watching streaks
            val daysSinceLastWatch = if (lastWatchDate > 0) {
                (entry.watchDate - lastWatchDate) / (24 * 60 * 60 * 1000)
            } else 0

            if (daysSinceLastWatch <= 1) {
                currentStreak++
            } else {
                if (currentStreak > 0) watchingStreaks.add(currentStreak)
                currentStreak = 1
            }
            lastWatchDate = entry.watchDate
        }

        if (currentStreak > 0) watchingStreaks.add(currentStreak)

        return ContentPatterns(
            preferredGenres = genreFrequency.toList().sortedByDescending { it.second }.take(5).map { it.first },
            preferredCategories = categoryFrequency.toList().sortedByDescending { it.second }.take(3).map { it.first },
            preferredContentTypes = contentTypeFrequency.toList().sortedByDescending { it.second }.map { it.first },
            genreFrequency = genreFrequency,
            categoryFrequency = categoryFrequency,
            averageStreakLength = if (watchingStreaks.isNotEmpty()) watchingStreaks.average().toInt() else 0
        )
    }

    private fun analyzeEngagementPatterns(
        watchHistory: List<com.tvonnet.debridxtreamiptv.data.local.entity.WatchHistoryEntity>
    ): EngagementPatterns {
        val completionRates = watchHistory.map { it.watchProgress }
        val watchDurations = watchHistory.map { it.watchDuration }
        val pauseCount = watchHistory.map { it.pauseCount }

        val avgCompletionRate = completionRates.average().toFloat()
        val avgWatchDuration = if (watchDurations.isNotEmpty()) {
            watchDurations.average().toLong()
        } else 0L

        val avgPausesPerSession = if (pauseCount.isNotEmpty()) {
            pauseCount.average().toFloat()
        } else 0f

        // Categorize engagement level
        val engagementLevel = when {
            avgCompletionRate >= 0.8f && avgPausesPerSession <= 2f -> "high"
            avgCompletionRate >= 0.5f && avgPausesPerSession <= 5f -> "medium"
            else -> "low"
        }

        return EngagementPatterns(
            averageCompletionRate = avgCompletionRate,
            averageWatchDuration = avgWatchDuration,
            averagePausesPerSession = avgPausesPerSession,
            engagementLevel = engagementLevel
        )
    }

    private fun analyzeBingePatterns(
        watchHistory: List<com.tvonnet.debridxtreamiptv.data.local.entity.WatchHistoryEntity>
    ): BingePatterns {
        val bingeSessions = watchHistory.filter { it.isBingeSession }
        val bingeByContentType = bingeSessions.groupBy { it.contentType }

        val bingeRate = if (watchHistory.isNotEmpty()) {
            bingeSessions.size.toFloat() / watchHistory.size
        } else 0f

        val preferredBingeContentTypes = bingeByContentType
            .mapValues { it.value.size }
            .toList()
            .sortedByDescending { it.second }
            .take(3)
            .map { it.first }

        return BingePatterns(
            bingeRate = bingeRate,
            preferredBingeContentTypes = preferredBingeContentTypes,
            averageBingeSessionLength = bingeSessions.map { it.watchDuration }.average().toLong()
        )
    }

    private fun buildPreferenceProfile(
        userProfile: com.tvonnet.debridxtreamiptv.data.local.entity.UserProfileEntity,
        watchHistory: List<com.tvonnet.debridxtreamiptv.data.local.entity.WatchHistoryEntity>
    ): PreferenceProfile {
        // Combine explicit preferences from profile with implicit preferences from history
        val explicitGenrePreferences = userProfile.favoriteGenres.associateWith { 1.0f }
        val implicitGenrePreferences = analyzeImplicitGenrePreferences(watchHistory)

        val combinedGenrePreferences = mutableMapOf<String, Float>()
        (explicitGenrePreferences.keys + implicitGenrePreferences.keys).forEach { genre ->
            val explicit = explicitGenrePreferences[genre] ?: 0f
            val implicit = implicitGenrePreferences[genre] ?: 0f
            combinedGenrePreferences[genre] = (explicit * 0.7f + implicit * 0.3f)
        }

        return PreferenceProfile(
            genrePreferences = combinedGenrePreferences,
            timeOfDayPreferences = userProfile.preferredWatchingTimes,
            contentLengthPreference = deriveContentLengthPreference(watchHistory),
            qualityPreference = userProfile.preferredQuality,
            discoveryPreference = userProfile.discoveryPreferences
        )
    }

    private fun analyzeImplicitGenrePreferences(
        watchHistory: List<com.tvonnet.debridxtreamiptv.data.local.entity.WatchHistoryEntity>
    ): Map<String, Float> {
        val genreScores = mutableMapOf<String, Float>()
        val genreCounts = mutableMapOf<String, Int>()

        for (entry in watchHistory) {
            val weight = if (entry.watchProgress >= 0.9f) 1.0f else entry.watchProgress

            entry.genres.forEach { genre ->
                genreScores[genre] = (genreScores[genre] ?: 0f) + weight
                genreCounts[genre] = (genreCounts[genre] ?: 0) + 1
            }
        }

        // Normalize by count to get average preference
        return genreScores.mapValues { (genre, totalScore) ->
            val count = genreCounts[genre] ?: 1
            totalScore / count
        }
    }

    private fun deriveContentLengthPreference(
        watchHistory: List<com.tvonnet.debridxtreamiptv.data.local.entity.WatchHistoryEntity>
    ): String {
        val avgDuration = if (watchHistory.isNotEmpty()) {
            watchHistory.map { it.totalDuration }.average().toLong()
        } else 0L

        return when {
            avgDuration < 30 * 60 * 1000 -> "short" // < 30 minutes
            avgDuration < 60 * 60 * 1000 -> "medium" // < 1 hour
            avgDuration < 120 * 60 * 1000 -> "long" // < 2 hours
            else -> "extended" // > 2 hours
        }
    }

    private fun analyzeContextualFactors(
        context: RecommendationContext,
        watchHistory: List<com.tvonnet.debridxtreamiptv.data.local.entity.WatchHistoryEntity>
    ): ContextualFactors {
        val currentTime = System.currentTimeMillis()
        val currentHour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        val currentDayOfWeek = java.util.Calendar.getInstance().get(java.util.Calendar.DAY_OF_WEEK)

        // Time-based preferences
        val timeBasedPreference = analyzeTimeBasedPreference(currentHour, watchHistory)

        // Day-based preferences
        val dayBasedPreference = analyzeDayBasedPreference(currentDayOfWeek, watchHistory)

        // Device-specific patterns
        val devicePreference = analyzeDevicePreference(context.deviceType, watchHistory)

        return ContextualFactors(
            timePreference = timeBasedPreference,
            dayPreference = dayBasedPreference,
            devicePreference = devicePreference,
            currentContext = context
        )
    }

    private fun analyzeTimeBasedPreference(
        currentHour: Int,
        watchHistory: List<com.tvonnet.debridxtreamiptv.data.local.entity.WatchHistoryEntity>
    ): Map<String, Float> {
        val hourGenrePreferences = mutableMapOf<Int, MutableMap<String, Float>>()

        for (entry in watchHistory) {
            val hour = java.util.Calendar.getInstance().apply {
                timeInMillis = entry.watchDate
            }.get(java.util.Calendar.HOUR_OF_DAY)

            val hourPrefs = hourGenrePreferences.getOrPut(hour) { mutableMapOf() }
            entry.genres.forEach { genre ->
                hourPrefs[genre] = (hourPrefs[genre] ?: 0f) + entry.watchProgress
            }
        }

        return hourGenrePreferences[currentHour] ?: emptyMap()
    }

    private fun analyzeDayBasedPreference(
        currentDayOfWeek: Int,
        watchHistory: List<com.tvonnet.debridxtreamiptv.data.local.entity.WatchHistoryEntity>
    ): Map<String, Float> {
        val dayGenrePreferences = mutableMapOf<Int, MutableMap<String, Float>>()

        for (entry in watchHistory) {
            val dayOfWeek = java.util.Calendar.getInstance().apply {
                timeInMillis = entry.watchDate
            }.get(java.util.Calendar.DAY_OF_WEEK)

            val dayPrefs = dayGenrePreferences.getOrPut(dayOfWeek) { mutableMapOf() }
            entry.genres.forEach { genre ->
                dayPrefs[genre] = (dayPrefs[genre] ?: 0f) + entry.watchProgress
            }
        }

        return dayGenrePreferences[currentDayOfWeek] ?: emptyMap()
    }

    private fun analyzeDevicePreference(
        deviceType: String?,
        watchHistory: List<com.tvonnet.debridxtreamiptv.data.local.entity.WatchHistoryEntity>
    ): Map<String, Float> {
        // This would analyze preferences based on device type
        // For now, return empty map
        return emptyMap()
    }

    private suspend fun generateContentBasedPersonalizedCandidates(
        preferenceProfile: PreferenceProfile,
        contentType: String?,
        limit: Int
    ): List<PersonalizedCandidate> {
        val candidates = mutableListOf<PersonalizedCandidate>()

        // Generate candidates based on genre preferences
        preferenceProfile.genrePreferences.entries
            .sortedByDescending { it.value }
            .take(10)
            .forEach { (genre, preference) ->
                // This would query content catalog for matching items
                // For now, create placeholder candidates
                repeat(3) { index ->
                    candidates.add(
                        PersonalizedCandidate(
                            contentId = "${genre}_${index}_personalized",
                            baseScore = preference,
                            personalizationFactors = mapOf(
                                "genre_preference" to preference,
                                "content_match" to 0.8f
                            ),
                            reason = "Based on your preference for $genre content"
                        )
                    )
                }
            }

        return candidates.take(limit)
    }

    private suspend fun generatePatternBasedPersonalizedCandidates(
        userPatterns: ViewingPatterns,
        contentType: String?,
        limit: Int
    ): List<PersonalizedCandidate> {
        val candidates = mutableListOf<PersonalizedCandidate>()

        // Generate candidates based on viewing patterns
        userPatterns.contentPatterns.preferredGenres.forEach { genre ->
            candidates.add(
                PersonalizedCandidate(
                    contentId = "${genre}_pattern_match",
                    baseScore = 0.7f,
                    personalizationFactors = mapOf(
                        "pattern_match" to 0.7f,
                        "frequency_preference" to 0.8f
                    ),
                    reason = "Matches your viewing patterns"
                )
            )
        }

        return candidates.take(limit)
    }

    private suspend fun generateContextualPersonalizedCandidates(
        contextualFactors: ContextualFactors,
        contentType: String?,
        limit: Int
    ): List<PersonalizedCandidate> {
        val candidates = mutableListOf<PersonalizedCandidate>()

        // Generate candidates based on current context
        contextualFactors.timePreference.entries
            .sortedByDescending { it.value }
            .take(5)
            .forEach { (genre, score) ->
                candidates.add(
                    PersonalizedCandidate(
                        contentId = "${genre}_contextual",
                        baseScore = score * 0.6f, // Contextual matches get lower base score
                        personalizationFactors = mapOf(
                            "contextual_relevance" to score,
                            "time_preference" to 0.8f
                        ),
                        reason = "Perfect for this time of day"
                    )
                )
            }

        return candidates.take(limit)
    }

    private fun scoreAndRankCandidates(
        candidates: List<PersonalizedCandidate>,
        preferenceProfile: PreferenceProfile,
        userPatterns: ViewingPatterns,
        contextualFactors: ContextualFactors
    ): List<PersonalizedCandidate> {
        return candidates.map { candidate ->
            val preferenceScore = calculatePreferenceScore(candidate, preferenceProfile)
            val patternScore = calculatePatternScore(candidate, userPatterns)
            val contextualScore = calculateContextualScore(candidate, contextualFactors)

            val personalizedScore = (
                preferenceScore * 0.5f +
                patternScore * 0.3f +
                contextualScore * 0.2f
            ) * candidate.baseScore

            candidate.copy(
                personalizedScore = personalizedScore,
                preferenceMatch = preferenceScore,
                contextRelevance = contextualScore,
                metadata = candidate.metadata + mapOf(
                    "preference_score" to preferenceScore,
                    "pattern_score" to patternScore,
                    "contextual_score" to contextualScore
                )
            )
        }.sortedByDescending { it.personalizedScore }
    }

    private fun calculatePreferenceScore(
        candidate: PersonalizedCandidate,
        preferenceProfile: PreferenceProfile
    ): Float {
        // Calculate how well the candidate matches user preferences
        val genreMatch = candidate.personalizationFactors["genre_preference"] ?: 0f
        val contentMatch = candidate.personalizationFactors["content_match"] ?: 0f
        val frequencyMatch = candidate.personalizationFactors["frequency_preference"] ?: 0f

        return (genreMatch * 0.5f + contentMatch * 0.3f + frequencyMatch * 0.2f)
    }

    private fun calculatePatternScore(
        candidate: PersonalizedCandidate,
        userPatterns: ViewingPatterns
    ): Float {
        return candidate.personalizationFactors["pattern_match"] ?: 0f
    }

    private fun calculateContextualScore(
        candidate: PersonalizedCandidate,
        contextualFactors: ContextualFactors
    ): Float {
        return candidate.personalizationFactors["contextual_relevance"] ?: 0f
    }

    private suspend fun updateUserProfileIfNeeded(userId: String, interactionCount: Int) {
        if (interactionCount % PREFERENCE_UPDATE_THRESHOLD == 0) {
            // Update user profile with latest preferences
            // This would trigger background preference learning
            Log.d(TAG, "Updating user profile for $userId based on $interactionCount interactions")
        }
    }

    /**
     * Data classes for personalization analysis
     */
    data class RecommendationContext(
        val timeOfDay: Int = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY),
        val dayOfWeek: Int = java.util.Calendar.getInstance().get(java.util.Calendar.DAY_OF_WEEK),
        val deviceType: String? = null,
        val sessionId: String? = null
    )

    data class ViewingPatterns(
        val timePatterns: TimePatterns,
        val contentPatterns: ContentPatterns,
        val engagementPatterns: EngagementPatterns,
        val bingePatterns: BingePatterns
    )

    data class TimePatterns(
        val peakHours: List<Int>,
        val hourDistribution: IntArray,
        val dayOfWeekDistribution: IntArray,
        val averageSessionDuration: Long
    )

    data class ContentPatterns(
        val preferredGenres: List<String>,
        val preferredCategories: List<String>,
        val preferredContentTypes: List<String>,
        val genreFrequency: Map<String, Int>,
        val categoryFrequency: Map<String, Int>,
        val averageStreakLength: Int
    )

    data class EngagementPatterns(
        val averageCompletionRate: Float,
        val averageWatchDuration: Long,
        val averagePausesPerSession: Float,
        val engagementLevel: String
    )

    data class BingePatterns(
        val bingeRate: Float,
        val preferredBingeContentTypes: List<String>,
        val averageBingeSessionLength: Long
    )

    data class PreferenceProfile(
        val genrePreferences: Map<String, Float>,
        val timeOfDayPreferences: List<String>,
        val contentLengthPreference: String,
        val qualityPreference: String,
        val discoveryPreference: Map<String, Float>
    )

    data class ContextualFactors(
        val timePreference: Map<String, Float>,
        val dayPreference: Map<String, Float>,
        val devicePreference: Map<String, Float>,
        val currentContext: RecommendationContext
    )

    data class PersonalizedCandidate(
        val contentId: String,
        val baseScore: Float,
        val personalizationFactors: Map<String, Float>,
        val reason: String,
        val personalizedScore: Float = baseScore,
        val preferenceMatch: Float = 0f,
        val contextRelevance: Float = 0f,
        val metadata: Map<String, Any> = emptyMap()
    )
}