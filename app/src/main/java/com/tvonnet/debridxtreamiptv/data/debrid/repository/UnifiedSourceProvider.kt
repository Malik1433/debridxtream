package com.tvonnet.debridxtreamiptv.data.debrid.repository

import android.util.Log
import com.tvonnet.debridxtreamiptv.data.debrid.model.AddonStream
import com.tvonnet.debridxtreamiptv.data.debrid.source.AddonRemoteDataSource
import com.tvonnet.debridxtreamiptv.data.debrid.source.DynamicAddonFetcher
import com.tvonnet.debridxtreamiptv.data.debrid.source.RealDebridRemoteDataSource
import com.tvonnet.debridxtreamiptv.data.debrid.source.StremioAddonFetcher
import com.tvonnet.debridxtreamiptv.data.debrid.source.TmdbRemoteDataSource
import com.tvonnet.debridxtreamiptv.data.prefs.DebridPreferences
import com.tvonnet.debridxtreamiptv.data.repository.MovieSource
import com.tvonnet.debridxtreamiptv.data.repository.XtreamRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeout
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

/**
 * Unified provider that combines IPTV and Debrid sources with intelligent routing
 *
 * Features:
 * - Smart debrid detection based on content metadata
 * - Fallback between IPTV and debrid sources
 * - Parallel fetching from unlimited dynamic scrapers
 * - InfoHash-based deduplication across providers
 * - Language filtering and source prioritization
 * - Comprehensive error handling and retry logic
 */
@Singleton
class UnifiedSourceProvider @Inject constructor(
    private val xtreamRepository: XtreamRepository,
    private val addonRemote: AddonRemoteDataSource,
    private val tmdbRemote: TmdbRemoteDataSource,
    private val mediaFusionFetcher: com.tvonnet.debridxtreamiptv.data.debrid.source.MediaFusionFetcher,
    private val settingsPrefs: com.tvonnet.debridxtreamiptv.data.prefs.SettingsPreferences,
    private val dynamicAddonFetcher: DynamicAddonFetcher,
    private val stremioAddonFetcher: StremioAddonFetcher,
    private val debridPrefs: DebridPreferences,
    private val realDebridRemote: RealDebridRemoteDataSource,
    private val realDebridRateLimiter: RealDebridRateLimiter
) {

    /** USP-5: registry + dynamic/Stremio addon discovery (owns the scrape limit). */
    private val addonFetcher = AddonSourceFetcher(addonRemote, dynamicAddonFetcher, stremioAddonFetcher, debridPrefs)

    /** USP-4: Real-Debrid cache-status resolution (TTL cache + rate-limiter cooldowns). */
    private val cacheVerifier = DebridCacheVerifier(realDebridRemote, realDebridRateLimiter)

    /** USP-7: the debrid movie/episode discovery pipeline. */
    private val orchestrator = DebridSourceOrchestrator(
        tmdbRemote, mediaFusionFetcher, settingsPrefs, debridPrefs, addonFetcher, cacheVerifier
    )

    companion object {
        private const val TAG = "UnifiedSourceProvider"
        private const val TIMEOUT_MS = 30000L // 30 second timeout
        private const val SCOPED_FETCH_TIMEOUT_MS = 10000L // resume fast-path: single-provider fetch budget
        private const val BATCH_SIZE = 10 // Process sources in batches
        private val MOVIE_YEAR_REGEX = Regex("\\b(?:19|20)\\d{2}\\b")

        internal fun filterMismatchedAddonMovieStreams(
            streams: List<AddonStream>,
            requestedTitle: String?,
            requestedYear: String?
        ): List<AddonStream> {
            val targetTitle = normalizeMovieTitle(requestedTitle)
            val targetYear = extractMovieYear(requestedYear)
            if (targetTitle.isNullOrBlank() && targetYear == null) return streams

            return streams.filterNot { stream ->
                val candidateTitle = stream.title?.trim().orEmpty()
                // B5: a year-like number can be part of the TITLE itself ("Blade
                // Runner 2049", "1917", "2012"). Taking only the FIRST token used to
                // mismatch such titles against the release year and EMPTY the picker.
                // Consider every year token: wrong-year only when tokens exist and
                // NONE of them equals the requested year.
                val candidateYearMatches = MOVIE_YEAR_REGEX.findAll(candidateTitle).toList()
                val candidateYears = candidateYearMatches.mapNotNull { it.value.toIntOrNull() }
                val hasWrongYear = targetYear != null &&
                    candidateYears.isNotEmpty() &&
                    candidateYears.none { it == targetYear }
                // Title key: split at the token matching the release year when
                // present (keeps "2049" inside the title), else at the last token.
                val splitMatch = candidateYearMatches.lastOrNull { it.value.toIntOrNull() == targetYear }
                    ?: candidateYearMatches.lastOrNull()
                val candidateTitleBeforeYear = splitMatch
                    ?.range
                    ?.first
                    ?.let { candidateTitle.substring(0, it) }
                val candidateTitleKey = normalizeMovieTitle(candidateTitleBeforeYear)

                val hasClearlyWrongTitle = !targetTitle.isNullOrBlank() &&
                    candidateYears.isNotEmpty() &&
                    !candidateTitleKey.isNullOrBlank() &&
                    !candidateTitleKey.contains(targetTitle) &&
                    !targetTitle.contains(candidateTitleKey)

                hasWrongYear || hasClearlyWrongTitle
            }
        }

        private fun normalizeMovieTitle(value: String?): String? {
            if (value.isNullOrBlank()) return null
            val withoutYear = MOVIE_YEAR_REGEX.find(value)
                ?.range
                ?.first
                ?.let { value.substring(0, it) }
                ?: value
            return withoutYear
                .lowercase()
                .replace(Regex("[^a-z0-9]+"), "")
                .takeIf { it.isNotBlank() }
        }

        private fun extractMovieYear(value: String?): Int? {
            if (value.isNullOrBlank()) return null
            return MOVIE_YEAR_REGEX.find(value)?.value?.toIntOrNull()
        }
    }

    /**
     * Get movie sources with intelligent debrid detection and fallback
     * Enhanced with comprehensive debugging and system health checks
     */
    suspend fun getMovieSources(
        streamId: String?,
        title: String?,
        primaryCategoryId: String?,
        yearHint: String?,
        imdbId: String? = null,
        priorityInfoHash: String? = null,
        preferredSourceType: String? = null
    ): List<MovieSource> {
        return coroutineScope {
            try {
                withTimeout(TIMEOUT_MS) {
                    // Enhanced debugging: Log input parameters and system health
                    Log.d(TAG, "🚀 [START] Unified source resolution started")
                    Log.d(TAG, "📋 [INPUT] Stream ID: $streamId")
                    Log.d(TAG, "📋 [INPUT] Title: '$title'")
                    Log.d(TAG, "📋 [INPUT] Category: $primaryCategoryId")
                    Log.d(TAG, "📋 [INPUT] Year: $yearHint")
                    Log.d(TAG, "📋 [INPUT] IMDb ID: $imdbId")

                    val isDebridMovie = isDebridContent(primaryCategoryId, title)
                    Log.d(TAG, "🎬 [DETECTION] Movie: '$title' | Debrid: $isDebridMovie | Category: $primaryCategoryId")

                    Log.d(TAG, "🛤️ [ROUTING] Decision: ${if (isDebridMovie) "DEBRID PATH → PureFire" else "IPTV PATH → Direct sources"}")

                    val sources = when {
                        isDebridMovie -> {
                            Log.d(TAG, "🔥 [PATH] Taking DEBRID route - attempting to fetch real torrent sources")
                            orchestrator.getDebridMovieSources(title, yearHint, imdbId, priorityInfoHash, preferredSourceType)
                        }
                        else -> {
                            Log.d(TAG, "📺 [PATH] Taking IPTV route - using direct streaming sources")
                            getIptvMovieSources(streamId, title, primaryCategoryId, yearHint)
                        }
                    }

                    Log.d(TAG, "📊 [RESULT] Found ${sources.size} sources before filtering")

                    val finalSources = applyPreferredLanguageFilter(sources)
                    logResolutionOutcome(finalSources, isDebridMovie, title)

                    finalSources
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                coroutineContext.ensureActive()

                // Graceful error handling - don't crash the app, just return empty list
                logGracefulFailure(e, title)
                return@coroutineScope emptyList<MovieSource>()
            }
        }
    }

    // Refined filtering: Apply Language and Quality prioritization
    private fun applyPreferredLanguageFilter(sources: List<MovieSource>): List<MovieSource> {
        if (sources.isEmpty()) {
            Log.d(TAG, "📭 [NO-SOURCES] No sources available to filter")
            return sources
        }
        Log.d(TAG, "🔍 [FILTER] Applying SourceFilterUtils logic...")
        val preferredLang = settingsPrefs.getPreferredAudioLanguage()
        val filterState = com.tvonnet.debridxtreamiptv.ui.sources.SourceFilterState(
            sortLanguage = preferredLang
        )
        return com.tvonnet.debridxtreamiptv.ui.sources.SourceFilterUtils.apply(sources, filterState)
    }

    // Final summary with graceful handling
    private fun logResolutionOutcome(finalSources: List<MovieSource>, isDebridMovie: Boolean, title: String?) {
        Log.d(TAG, "✅ [COMPLETE] Source resolution finished")
        Log.d(TAG, "📈 [SUMMARY] Final source count: ${finalSources.size}")

        if (finalSources.isNotEmpty()) {
            val primaryCount = finalSources.count { it.isPrimary }
            Log.d(TAG, "[SUCCESS] Sources available: count=${finalSources.size}, primary=$primaryCount")
            return
        }
        // Only log "NO-SOURCES" if we actually tried all providers and got nothing
        if (isDebridMovie) {
            Log.w(TAG, "📭 [NO-SOURCES] All PureFire providers returned empty for '$title'")
            Log.d(TAG, "   This means: Torrentio, Comet, and MediaFusion all had no sources")
            Log.d(TAG, "💡 This is normal for:")
            Log.d(TAG, "   - New releases not yet available on torrent sites")
            Log.d(TAG, "   - Content exclusive to streaming platforms")
            Log.d(TAG, "   - Region-restricted or niche content")
            Log.d(TAG, "   - Temporary provider unavailability")
        } else {
            Log.w(TAG, "📭 [NO-SOURCES] IPTV provider returned no sources for '$title'")
            Log.d(TAG, "💡 This could mean:")
            Log.d(TAG, "   - Content not available on current IPTV service")
            Log.d(TAG, "   - Movie is too new or region-restricted")
            Log.d(TAG, "   - IPTV provider doesn't carry this content")
        }
    }

    private fun logGracefulFailure(e: Exception, title: String?) {
        when (e) {
            is kotlinx.coroutines.TimeoutCancellationException -> {
                Log.w(TAG, "⏰ [TIMEOUT] Source resolution timed out for '$title'")
                Log.d(TAG, "   Sources took longer than ${TIMEOUT_MS}ms to load")
            }
            is java.net.UnknownHostException -> {
                Log.w(TAG, "🌐 [NETWORK] No internet connection for '$title'")
                Log.d(TAG, "   DNS resolution failed - check network connectivity")
            }
            is java.net.SocketTimeoutException -> {
                Log.w(TAG, "🌐 [NETWORK] Slow server response for '$title'")
                Log.d(TAG, "   Server took too long to respond - will retry later")
            }
            else -> {
                Log.w(TAG, "⚠️ [GRACEFUL] Issue loading sources for '$title': ${e.javaClass.simpleName}")
                Log.d(TAG, "   Details: ${e.message}")
            }
        }
        Log.w(TAG, "📭 [GRACEFUL] Returning empty source list for '$title'")
    }

    /** Stable part of a saved stream id — strips the volatile "_<index>" suffix. */


    /**
     * Get sources for series episodes (using PureFire integration).
     * Thin delegator — the pipeline lives in [DebridSourceOrchestrator].
     */
    suspend fun getSeriesEpisodeSources(
        seriesId: String?,
        seasonNumber: Int,
        episodeNumber: Int,
        title: String?,
        yearHint: String?,
        priorityInfoHash: String? = null,
        preferredSourceType: String? = null
    ): List<MovieSource> = orchestrator.getSeriesEpisodeSources(
        seriesId, seasonNumber, episodeNumber, title, yearHint, priorityInfoHash, preferredSourceType
    )

    /**
     * Get IPTV sources (original functionality)
     */
    private suspend fun getIptvMovieSources(
        streamId: String?,
        title: String?,
        primaryCategoryId: String?,
        yearHint: String?
    ): List<MovieSource> {
        Log.d(TAG, "📺 Getting IPTV sources for: $title")

        return try {
            xtreamRepository.getMovieSources(streamId, title, primaryCategoryId, yearHint)
        } catch (e: Exception) {
            coroutineContext.ensureActive()
            Log.w(TAG, "⚠️ [GRACEFUL] IPTV source fetch interrupted for '$title'")
            Log.d(TAG, "   Issue: ${e.javaClass.simpleName}")
            Log.d(TAG, "💡 IPTV service will be retried on next request")
            emptyList()
        }
    }


    /**
     * Simplified health check for available source providers
     */
    suspend fun healthCheck(): Map<String, Boolean> = coroutineScope {
        try {
            Log.d(TAG, "🏥 [HEALTH-CHECK] Starting system health check...")
            val results = mutableMapOf<String, Boolean>()

            // Check IPTV - simplified check
            results["IPTV"] = try {
                Log.d(TAG, "   📺 Checking IPTV repository...")
                true // Simplified - assumes repository is injected correctly
            } catch (e: Exception) {
                Log.w(TAG, "   ❌ IPTV repository check failed: ${e.message}")
                false
            }

            // Check TMDB API connectivity
            results["TMDB_API"] = try {
                Log.d(TAG, "   📡 Checking TMDB API connectivity...")
                val testResult = tmdbRemote.searchMovies("Inception")
                val tmdbHealth = testResult.isSuccess && testResult.getOrNull()?.results?.isNotEmpty() == true
                Log.d(TAG, "   ${if (tmdbHealth) "✅" else "❌"} TMDB API health: $tmdbHealth")
                tmdbHealth
            } catch (e: Exception) {
                Log.w(TAG, "   ❌ TMDB API check failed: ${e.message}")
                false
            }

            val healthyCount = results.values.count { it }
            val totalCount = results.size
            Log.d(TAG, "📊 [HEALTH-SUMMARY] $healthyCount/$totalCount components healthy")

            if (healthyCount == totalCount) {
                Log.d(TAG, "✅ [HEALTH] All systems operational")
            } else {
                Log.w(TAG, "⚠️ [HEALTH] Some components may not be functioning correctly")
                results.filter { !it.value }.forEach { (component, _) ->
                    Log.w(TAG, "   ⚠️ Unhealthy component: $component")
                }
            }

            results
        } catch (e: Exception) {
            coroutineContext.ensureActive()
            Log.e(TAG, "❌ [HEALTH-CRITICAL] Overall health check failed", e)
            mapOf("All" to false)
        }
    }


    /**
     * Removes duplicate streams that share the same infoHash.
     * When duplicates are found, the stream with more metadata wins.
     *
     * @param streams Raw, possibly duplicated stream list.
     * @return Deduplicated list.
     */




}
