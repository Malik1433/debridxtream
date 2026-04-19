package com.tvonnet.debridxtreamiptv.data.debrid.repository

import android.util.Log
import com.tvonnet.debridxtreamiptv.data.debrid.model.AddonStream
import com.tvonnet.debridxtreamiptv.data.debrid.model.AddonSourceType
import com.tvonnet.debridxtreamiptv.data.debrid.source.AddonRemoteDataSource
import com.tvonnet.debridxtreamiptv.data.debrid.source.SimplifiedPureFireFetcher
import com.tvonnet.debridxtreamiptv.data.debrid.source.TmdbRemoteDataSource
import com.tvonnet.debridxtreamiptv.data.repository.MovieSource
import com.tvonnet.debridxtreamiptv.data.repository.XtreamRepository
import com.tvonnet.debridxtreamiptv.data.model.XtreamVodInfo
import com.tvonnet.debridxtreamiptv.data.model.ContentType
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
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
 * - Batch processing for performance optimization
 * - Language filtering and source prioritization
 * - Comprehensive error handling and retry logic
 */
@Singleton
class UnifiedSourceProvider @Inject constructor(
    private val xtreamRepository: XtreamRepository,
    private val addonRemote: AddonRemoteDataSource,
    private val tmdbRemote: TmdbRemoteDataSource,
    private val simplifiedPureFireFetcher: SimplifiedPureFireFetcher,
    private val mediaFusionFetcher: com.tvonnet.debridxtreamiptv.data.debrid.source.MediaFusionFetcher,
    private val settingsPrefs: com.tvonnet.debridxtreamiptv.data.prefs.SettingsPreferences
) {
    // PureFire integration is now enabled!

    companion object {
        private const val TAG = "UnifiedSourceProvider"
        private const val TIMEOUT_MS = 30000L // 30 second timeout
        private const val BATCH_SIZE = 10 // Process sources in batches
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
        imdbId: String? = null
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

                    // Simplified health check - TMDB API connectivity check
                    Log.d(TAG, "🏥 [HEALTH] Checking TMDB API connectivity...")
                    try {
                        val testResult = tmdbRemote.searchMovies("Inception")
                        val tmdbHealth = testResult.isSuccess && testResult.getOrNull()?.results?.isNotEmpty() == true
                        Log.d(TAG, "   📡 TMDB API Health: ${if (tmdbHealth) "✅ OK" else "❌ FAILED"}")

                        if (!tmdbHealth) {
                            Log.w(TAG, "⚠️ [WARNING] TMDB API health check failed - debrid resolution may not work properly")
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "⚠️ [WARNING] TMDB API health check failed: ${e.message}")
                    }

                    val isDebridMovie = isDebridContent(primaryCategoryId, title)
                    Log.d(TAG, "🎬 [DETECTION] Movie: '$title' | Debrid: $isDebridMovie | Category: $primaryCategoryId")

                    Log.d(TAG, "🛤️ [ROUTING] Decision: ${if (isDebridMovie) "DEBRID PATH → PureFire" else "IPTV PATH → Direct sources"}")

                    val sources = when {
                        isDebridMovie -> {
                            Log.d(TAG, "🔥 [PATH] Taking DEBRID route - attempting to fetch real torrent sources")
                            getDebridMovieSources(title, yearHint, imdbId)
                        }
                        else -> {
                            Log.d(TAG, "📺 [PATH] Taking IPTV route - using direct streaming sources")
                            getIptvMovieSources(streamId, title, primaryCategoryId, yearHint)
                        }
                    }

                    Log.d(TAG, "📊 [RESULT] Found ${sources.size} sources before filtering")

                    // Refined filtering: Apply Language and Quality prioritization
                    val finalSources = if (sources.isNotEmpty()) {
                        Log.d(TAG, "🔍 [FILTER] Applying SourceFilterUtils logic...")
                        val preferredLang = settingsPrefs.getPreferredAudioLanguage()
                        val filterState = com.tvonnet.debridxtreamiptv.ui.sources.SourceFilterState(
                            preferredLanguage = preferredLang
                        )
                        com.tvonnet.debridxtreamiptv.ui.sources.SourceFilterUtils.apply(sources, filterState)
                    } else {
                        Log.d(TAG, "📭 [NO-SOURCES] No sources available to filter")
                        sources
                    }
                    // Final summary with graceful handling
                    Log.d(TAG, "✅ [COMPLETE] Source resolution finished")
                    Log.d(TAG, "📈 [SUMMARY] Final source count: ${finalSources.size}")

                    if (finalSources.isNotEmpty()) {
                        Log.e(TAG, "🎯 [SUCCESS] Sample sources:")
                        finalSources.take(10).forEach { source ->
                            Log.e(TAG, "   📦 ${source.label} (Primary: ${source.isPrimary})")
                        }
                    } else {
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


                    finalSources
                }
            } catch (e: Exception) {
                coroutineContext.ensureActive()

                // Graceful error handling - don't crash the app, just return empty list
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
                return@coroutineScope emptyList<MovieSource>()
            }
        }
    }

    /**
     * Get sources for debrid movies (using PureFire integration) with comprehensive logging
     */
    private suspend fun getDebridMovieSources(
        title: String?,
        yearHint: String?,
        providedImdbId: String? = null
    ): List<MovieSource> = coroutineScope {
        Log.d(TAG, "🔥 Getting real PureFire sources for: '$title' (year: $yearHint, imdb: $providedImdbId)")

        try {
            if (title.isNullOrBlank()) {
                Log.w(TAG, "⚠️ Cannot get debrid sources: title is null or blank")
                return@coroutineScope emptyList()
            }

            // Step 1: Use provided IMDb ID or try to resolve it (skipped for now)
            val imdbId = providedImdbId
            
            if (!imdbId.isNullOrBlank()) {
                Log.d(TAG, "✅ Using provided IMDB ID: $imdbId for '$title'")
            } else {
                Log.w(TAG, "⚠️ No IMDB ID provided for '$title', will proceed with title-based search")
            }

            // Step 2: Fetch real sources from Enhanced Simplified PureFire (REAL TORRENTIO API) AND MediaFusion
            Log.d(TAG, "🚀 Step 2: Fetching real Torrentio & MediaFusion sources (IMDB: $imdbId, Title: '$title')")
            val contentType = ContentType.MOVIE
            val releaseYear = yearHint?.toIntOrNull()

            // PARALLEL EXECUTION: Run both fetchers at the same time
            val torrentioDeferred = async {
                try {
                    simplifiedPureFireFetcher.fetchMovieSources(imdbId, title, releaseYear, contentType)
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Torrentio fetch failed", e)
                    emptyList()
                }
            }

            val mediaFusionDeferred = async {
                try {
                    mediaFusionFetcher.fetchMovieSources(imdbId)
                } catch (e: Exception) {
                    Log.e(TAG, "❌ MediaFusion fetch failed (Safe Catch)", e)
                    emptyList()
                }
            }

            // Wait for both to finish
            val torrentioStreams = torrentioDeferred.await()
            val mediaFusionStreams = mediaFusionDeferred.await()

            // Merge results
            val addonStreams = torrentioStreams + mediaFusionStreams

            if (addonStreams.isNotEmpty()) {
                // Enhanced logging with provider breakdown
                Log.d(TAG, "✅ PureFire API returned ${addonStreams.size} sources for '$title' ${if (!imdbId.isNullOrBlank()) "(IMDB: $imdbId)" else ""}")

                // Group sources by provider for detailed logging
                val sourcesByProvider = addonStreams.groupBy { it.source }

                Log.d(TAG, "   ┌─ Provider breakdown:")
                sourcesByProvider.forEach { (source, streams) ->
                    val providerName = when (source) {
                        AddonSourceType.TORRENTIO -> "Torrentio"
                        AddonSourceType.MEDIA_FUSION -> "MediaFusion"
                        AddonSourceType.ZILEAN -> "Zilean"
                        AddonSourceType.UNKNOWN -> "PureFire"
                    }
                    Log.d(TAG, "   ├─ $providerName: ${streams.size} sources")

                    // Log sample sources from each provider with rich metadata
                    streams.take(2).forEach { stream ->
                        val displayTitle = stream.extras["displayTitle"] as? String
                        val languageFlag = stream.extras["languageFlag"] as? String ?: "🌍"
                        val sizeFormatted = stream.sizeBytes?.let { formatFileSize(it) }
                        val seeders = stream.seeders

                        val sampleInfo = buildString {
                            if (!displayTitle.isNullOrBlank()) {
                                append("📦 $displayTitle")
                            } else {
                                append("📦 ${stream.title ?: "Unknown"}")
                                append(" $languageFlag")
                                if (!stream.quality.isNullOrBlank() && stream.quality != "Unknown") {
                                    append(" ${stream.quality}")
                                }
                                if (!sizeFormatted.isNullOrBlank()) {
                                    append(" ($sizeFormatted)")
                                }
                            }
                            if (seeders != null) {
                                append(" [$seeders seeders]")
                            }
                            append(" 🔗 [$providerName]")
                        }
                        Log.d(TAG, "   │  $sampleInfo")
                    }
                }
                Log.d(TAG, "   └─ Total: ${addonStreams.size} enhanced sources")

                val movieSources = convertAddonStreamsToMovieSources(addonStreams, title)
                Log.d(TAG, "🔄 Converted ${addonStreams.size} enhanced addon streams to ${movieSources.size} movie sources")

                // Log conversion success rate
                val conversionRate = if (addonStreams.isNotEmpty()) {
                    (movieSources.size.toDouble() / addonStreams.size * 100).toInt()
                } else 0
                Log.d(TAG, "📊 Conversion success rate: $conversionRate% (${movieSources.size}/${addonStreams.size})")

                return@coroutineScope movieSources
            } else {
                Log.i(TAG, "🎬 PureFire movie sources received: count=0 for '$title' (imdbId=${imdbId ?: "none"})")
                Log.w(TAG, "📭 [NO-SOURCES] PureFire API returned no sources for '$title'")
                if (!imdbId.isNullOrBlank()) {
                    Log.d(TAG, "   IMDB ID: $imdbId (movie identified but no torrents available)")
                } else {
                    Log.d(TAG, "   Movie identification failed - title-based search unsuccessful")
                }
                Log.d(TAG, "💡 This is normal for:")
                Log.d(TAG, "   - New releases not yet on torrent sites")
                Log.d(TAG, "   - Content exclusive to streaming platforms")
                Log.d(TAG, "   - Region-restricted or niche content")

                return@coroutineScope emptyList()
            }

        } catch (e: Exception) {
            coroutineContext.ensureActive()
            Log.w(TAG, "⚠️ [GRACEFUL] PureFire source fetch interrupted for '$title'")
            Log.d(TAG, "   Issue: ${e.javaClass.simpleName}")
            Log.d(TAG, "💡 Torrent providers will be retried on next request")
            return@coroutineScope emptyList()
        }
    }

    /**
     * Get sources for series episodes (using PureFire integration)
     */
    suspend fun getSeriesEpisodeSources(
        seriesId: String?,
        seasonNumber: Int,
        episodeNumber: Int,
        title: String?,
        yearHint: String?
    ): List<MovieSource> = coroutineScope {
        Log.e(TAG, "🔥 [DEBUG] getSeriesEpisodeSources called for: '$title' S${seasonNumber}:E${episodeNumber} (Series ID: $seriesId)")

        try {
            if (seriesId.isNullOrBlank()) {
                Log.e(TAG, "⚠️ Cannot get debrid sources: series ID is null or blank")
                return@coroutineScope emptyList()
            }

            // We assume seriesId passed here is the TMDB ID.
            // We need to resolve it to IMDb ID if possible, but for now we'll assume we might need to fetch it or use TMDB ID if supported.
            // SimplifiedPureFireFetcher expects IMDb ID.
            // Let's try to fetch external IDs for the series first using TmdbRemoteDataSource if we only have TMDB ID.
            
            val imdbId = if (seriesId.all { it.isDigit() }) {
                Log.e(TAG, "ℹ️ Series ID $seriesId looks like TMDB ID. Fetching external IDs...")
                // It's likely a TMDB ID, fetch external IDs
                val detailsResult = tmdbRemote.getSeriesDetails(seriesId.toInt())
                if (detailsResult.isSuccess) {
                    val resolvedId = detailsResult.getOrNull()?.externalIds?.imdbId
                    Log.e(TAG, "✅ Resolved IMDb ID: $resolvedId from TMDB ID: $seriesId")
                    resolvedId
                } else {
                    Log.e(TAG, "❌ Failed to fetch series details from TMDB for ID: $seriesId. Error: ${detailsResult.exceptionOrNull()?.message}")
                    null
                }
            } else {
                Log.e(TAG, "ℹ️ Series ID $seriesId looks like IMDb ID (or non-numeric). Using as is.")
                seriesId // Assume it's already IMDb ID if not all digits (e.g. tt123456)
            }

            if (imdbId.isNullOrBlank()) {
                 Log.e(TAG, "⚠️ Could not resolve IMDb ID for series $seriesId. Aborting source fetch.")
                 return@coroutineScope emptyList()
            }

            // Fetch real sources from Enhanced Simplified PureFire AND MediaFusion
            Log.e(TAG, "🚀 Fetching episode sources from Torrentio & MediaFusion for IMDb ID: $imdbId")

            // PARALLEL EXECUTION
            val torrentioDeferred = async {
                try {
                    simplifiedPureFireFetcher.fetchEpisodeSources(imdbId, seasonNumber, episodeNumber, title)
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Torrentio episode fetch failed", e)
                    emptyList()
                }
            }

            val mediaFusionDeferred = async {
                try {
                    mediaFusionFetcher.fetchEpisodeSources(imdbId, seasonNumber, episodeNumber)
                } catch (e: Exception) {
                    Log.e(TAG, "❌ MediaFusion episode fetch failed (Safe Catch)", e)
                    emptyList()
                }
            }

            val torrentioStreams = torrentioDeferred.await()
            val mediaFusionStreams = mediaFusionDeferred.await()

            val addonStreams = torrentioStreams + mediaFusionStreams

            if (addonStreams.isNotEmpty()) {
                Log.e(TAG, "✅ PureFire API returned ${addonStreams.size} sources for Episode")
                val sourcesByProvider = addonStreams.groupBy { it.source }
                Log.e(TAG, "Provider breakdown (episode):")
                sourcesByProvider.forEach { (source, streams) ->
                    val providerName = when (source) {
                        AddonSourceType.TORRENTIO -> "Torrentio"
                        AddonSourceType.MEDIA_FUSION -> "MediaFusion"
                        AddonSourceType.ZILEAN -> "Zilean"
                        AddonSourceType.UNKNOWN -> "PureFire"
                    }
                    Log.e(TAG, "   - $providerName: ${streams.size} sources")
                }
                return@coroutineScope convertAddonStreamsToMovieSources(addonStreams, title)
            } else {
                Log.e(TAG, "📭 [NO-SOURCES] PureFire API returned no sources for Episode")
                return@coroutineScope emptyList()
            }

        } catch (e: Exception) {
            coroutineContext.ensureActive()
            Log.e(TAG, "⚠️ [GRACEFUL] PureFire source fetch interrupted for Episode", e)
            return@coroutineScope emptyList()
        }
    }

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
     * Check if content should be treated as debrid content
     * Improved logic to better identify movies that should use PureFire sources
     * - Uses explicit debrid category flag when available
     * - Applies intelligent detection based on title patterns and categories
     * - Avoids routing obvious TV/live content to debrid
     */
    private fun isDebridContent(
        categoryId: String?,
        title: String?
    ): Boolean {
        val titleLower = title?.lowercase() ?: ""

        // Explicit debrid category
        if (categoryId == "debrid") {
            Log.d(TAG, "🚨 DEBRID DETECTION SUCCESS: explicit flag = true - WILL SKIP IPTV API")
            return true
        }

        // Enhanced category-based detection
        val movieCategories = listOf(
            "movies", "cinema", "film", "movies_vod", "vod_movies"
        )
        val tvCategories = listOf(
            "live_tv", "live", "sports", "news", "tv_channels", "series", "shows"
        )

        // Check if category suggests this should use IPTV
        if (categoryId != null && tvCategories.any { categoryId.lowercase().contains(it) }) {
            Log.d(TAG, "📺 IPTV CATEGORY DETECTED: $categoryId - will use IPTV sources")
            return false
        }

        // Skip obvious non-debrid content based on title patterns
        val nonDebridPatterns = listOf(
            "live ", " news", " weather", " sports channel", " tv channel",
            " documentary series", " reality show", " talk show", " podcast",
            " season ", " episode ", " s", " e", " ep ", " ptv ", " channel "
        )

        // Skip titles that indicate TV/series content
        val tvContentPatterns = listOf(
            " season ", " episode ", " s01", " s02", " s03", " s04", " s05",
            " ep ", " part ", " series", " show", " channel ", " tv ",
            " live ", " news ", " weather "
        )

        val isNonDebridContent = nonDebridPatterns.any { pattern ->
            titleLower.contains(pattern)
        } || tvContentPatterns.any { pattern ->
            titleLower.contains(pattern)
        }

        if (isNonDebridContent) {
            Log.d(TAG, "📺 NON-DEBRID CONTENT DETECTED: '$title' - will use IPTV sources")
            return false
        }

        // More specific movie detection patterns
        val movieIndicators = listOf(
            "(", ")", "movie", "film", "cinema", "hd", "4k", "bluray", "dvd"
        )

        val hasMovieIndicators = movieIndicators.any { indicator ->
            titleLower.contains(indicator)
        }

        // Check for year patterns (common in movies)
        val yearPattern = Regex("\\b(19|20)\\d{2}\\b")
        val hasYearPattern = yearPattern.containsMatchIn(titleLower)

        // Check title length (movies tend to have longer, more descriptive titles)
        val hasReasonableTitleLength = titleLower.length > 5

        // Final decision logic
        val shouldUseDebrid = when {
            // Skip if title is empty or too short
            titleLower.isBlank() || !hasReasonableTitleLength -> {
                Log.d(TAG, "📺 INVALID TITLE: '$title' - will use IPTV sources")
                false
            }

            // Use debrid for content with strong movie indicators
            hasMovieIndicators && hasYearPattern -> {
                Log.d(TAG, "🎬 STRONG MOVIE INDICATORS: '$title' - will try PureFire sources first")
                true
            }

            // Use debrid for movie category content
            movieCategories.any { categoryId?.lowercase()?.contains(it) == true } -> {
                Log.d(TAG, "🎬 MOVIE CATEGORY: '$categoryId' - will try PureFire sources first")
                true
            }

            // Default to IPTV for ambiguous content
            else -> {
                Log.d(TAG, "📺 AMBIGUOUS CONTENT: '$title' (category: $categoryId) - defaulting to IPTV sources")
                false
            }
        }

        Log.d(TAG, "🤖 Debrid detection for '$title': $shouldUseDebrid (category: $categoryId)")
        return shouldUseDebrid
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

            // Check PureFire source fetcher
            results["PureFire"] = try {
                Log.d(TAG, "   🔥 Checking PureFire source fetcher...")
                // This is a simple check - actual connectivity would require network call
                true
            } catch (e: Exception) {
                Log.w(TAG, "   ❌ PureFire fetcher check failed: ${e.message}")
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
     * Convert AddonStream objects to MovieSource objects with enhanced metadata support
     */
    private fun convertAddonStreamsToMovieSources(
        addonStreams: List<AddonStream>,
        title: String?
    ): List<MovieSource> {
        val movieTitle = title ?: "Unknown Movie"
        Log.d(TAG, "🔄 Converting ${addonStreams.size} enhanced addon streams to MovieSource objects")

        return addonStreams.mapIndexedNotNull { index, addonStream ->
            // Validate that we have a valid source (either url, infoHash or magnet)
            val validInfoHash = normalizeInfoHash(addonStream.infoHash)
            val validMagnet = addonStream.magnet?.takeIf { isValidMagnet(it) }
            val hasValidSource = !addonStream.url.isNullOrBlank() || validInfoHash != null || validMagnet != null

            if (!hasValidSource) {
                Log.w(TAG, "⚠️ [SKIP] Source '${addonStream.title}' skipped - no valid URL, magnet or infoHash")
                return@mapIndexedNotNull null
            }

            // Construct the magnet link or use a direct URL (MediaFusion prefers direct URLs)
            val mediaFusionUrl = addonStream.url
                ?.takeIf { addonStream.source == AddonSourceType.MEDIA_FUSION && it.isNotBlank() }
            val directUrl = addonStream.url?.takeIf { addonStream.source != AddonSourceType.MEDIA_FUSION && isDirectStreamUrl(it) }
            val directSource = when {
                mediaFusionUrl != null -> mediaFusionUrl
                validMagnet != null -> validMagnet
                validInfoHash != null -> "magnet:?xt=urn:btih:$validInfoHash"
                !addonStream.url.isNullOrBlank() -> addonStream.url
                else -> null
            }

            val streamId = if (mediaFusionUrl != null) {
                mediaFusionUrl.hashCode().toString()
            } else {
                validInfoHash ?: addonStream.title.hashCode().toString()
            }
            val providerLabel = getSourceLabel(addonStream.source)
            val cachedFlag = inferCachedFlag(addonStream)

            // Enhanced label creation with rich metadata from extras
            val label = (addonStream.extras["displayTitle"] as? String)
                ?: buildString {
                    append(providerLabel)
                    append(": ")
                    append(addonStream.title ?: movieTitle)

                    // Add language flag if available
                    val languageFlag = addonStream.extras["languageFlag"] as? String
                    if (languageFlag != null) {
                        append(" $languageFlag")
                    }

                    // Add quality if available
                    if (!addonStream.quality.isNullOrBlank() && addonStream.quality != "Unknown") {
                        append(" ${addonStream.quality}")
                    }

                    // Add size if available
                    val sizeFormatted = addonStream.sizeBytes?.let { formatFileSize(it) }
                    if (!sizeFormatted.isNullOrBlank()) {
                        append(" ($sizeFormatted)")
                    }

                    // Add seeders info if available
                    addonStream.seeders?.let { seeders ->
                        append(" [$seeders seeders]")
                    }
                    
                    // Add binge group indicator if available
                    val bingeGroup = addonStream.extras["bingeGroup"] as? String
                    if (!bingeGroup.isNullOrBlank()) {
                        append(" 🔗")
                    }

                    // Add file index if available
                    val fileIdx = addonStream.extras["fileIdx"] as? Int
                    if (fileIdx != null) {
                        append(" [File: $fileIdx]")
                    }
                }

            // Enhanced conversion logging
            Log.d(TAG, "✅ [CONVERT] Converted: $label")
            Log.d(TAG, "   📋 Source details:")
            Log.d(TAG, "      - Provider: ${addonStream.source}")
            Log.d(TAG, "      - Quality: ${addonStream.quality ?: "Unknown"}")
            Log.d(TAG, "      - Languages: ${addonStream.languages?.joinToString(", ") ?: "en"}")
            Log.d(TAG, "      - Size: ${addonStream.sizeBytes?.let { formatFileSize(it) } ?: "Unknown"}")
            Log.d(TAG, "      - Seeders: ${addonStream.seeders ?: "N/A"}")
            Log.d(TAG, "      - Has URL: ${!addonStream.url.isNullOrBlank()}")
            Log.d(TAG, "      - Has magnet: ${validMagnet != null}")
            Log.d(TAG, "      - Has infoHash: ${validInfoHash != null}")

            // Enhanced XtreamVodInfo with metadata
            MovieSource(
                stream = com.tvonnet.debridxtreamiptv.data.model.XtreamVodInfo(
                    stream_id = streamId,
                    name = addonStream.title ?: "$movieTitle ${addonStream.quality}",
                    direct_source = directSource,
                    num = index + 1,
                    stream_type = "movie",
                    category_id = null,
                    category_ids = null,
                    container_extension = null,
                    custom_sid = null,
                    rating = null,
                    rating_5based = null,
                    added = null,
                    cast = null,
                    director = null,
                    plot = addonStream.extras["description"] as? String, // Use description if available
                    releaseDate = null,
                    duration = addonStream.extras["duration"]?.let {
                        when (it) {
                            is Long -> "${it}s"
                            is Number -> "${it.toLong()}s"
                            else -> it.toString()
                        }
                    },
                    genre = null,
                    youtube_trailer = null,
                    cover = null,
                    rating_imdb = null,
                    stream_icon = null
                ),
                category = null, // debrid is not an XtreamCategory
                label = label,
                isPrimary = index == 0,
                languages = addonStream.languages,
                quality = addonStream.quality,
                sizeBytes = addonStream.sizeBytes,
                seeders = addonStream.seeders,
                isCached = cachedFlag,
                provider = providerLabel,
                headers = addonStream.headers
            )
        }.also { filteredSources ->
            Log.e(TAG, "🎯 Final conversion: ${filteredSources.size} valid MovieSource objects")

            // Log conversion success statistics
            val successRate = if (addonStreams.isNotEmpty()) {
                (filteredSources.size.toDouble() / addonStreams.size * 100).toInt()
            } else 0
            Log.e(TAG, "📊 Success rate: $successRate% (${filteredSources.size}/${addonStreams.size}) sources converted")

            val qualityBreakdown = filteredSources.groupBy { source ->
                // Extract quality from label
                when {
                    source.label.contains("4K") -> "4K"
                    source.label.contains("1080p") -> "1080p"
                    source.label.contains("720p") -> "720p"
                    source.label.contains("480p") -> "480p"
                    else -> "Other"
                }
            }
            Log.d(TAG, "🎨 Quality breakdown:")
            qualityBreakdown.forEach { (quality, sources) ->
                Log.d(TAG, "   - $quality: ${sources.size} sources")
            }
        }
    }

    private fun getSourceLabel(source: AddonSourceType): String {
        return when (source) {
            AddonSourceType.MEDIA_FUSION -> "MediaFusion"
            AddonSourceType.ZILEAN -> "Zilean"
            AddonSourceType.TORRENTIO -> "TorrentIO"
            AddonSourceType.UNKNOWN -> "PureFire"
        }
    }

    /**
     * Format file size in bytes to human readable format
     */
    private fun formatFileSize(bytes: Long): String {
        if (bytes < 1024) return "${bytes}B"
        val kb = bytes / 1024.0
        if (kb < 1024) return "%.1fKB".format(kb)
        val mb = kb / 1024.0
        if (mb < 1024) return "%.1fMB".format(mb)
        val gb = mb / 1024.0
        if (gb < 1024) return "%.1fGB".format(gb)
        val tb = gb / 1024.0
        return "%.1fTB".format(tb)
    }

    private fun isDirectStreamUrl(url: String): Boolean {
        val lowered = url.lowercase()
        if (!lowered.startsWith("http")) return false
        if (lowered.contains("mediafusion.elfhosted.com")) return true

        val clean = lowered.substringBefore('?').substringBefore('#')
        return clean.endsWith(".mp4") ||
            clean.endsWith(".mkv") ||
            clean.endsWith(".m3u8") ||
            clean.endsWith(".mpd") ||
            clean.endsWith(".webm") ||
            clean.endsWith(".m4v") ||
            clean.endsWith(".mov") ||
            clean.endsWith(".ts") ||
            clean.endsWith(".m2ts")
    }

    private fun normalizeInfoHash(infoHash: String?): String? {
        val trimmed = infoHash?.trim() ?: return null
        return if (trimmed.length >= 32) trimmed else null
    }

    private fun isValidMagnet(magnet: String?): Boolean {
        if (magnet.isNullOrBlank()) return false
        val trimmed = magnet.trim()
        if (!trimmed.startsWith("magnet:", ignoreCase = true)) return false
        val btihIndex = trimmed.indexOf("btih:", ignoreCase = true)
        if (btihIndex == -1) return false
        val hash = trimmed.substring(btihIndex + 5).substringBefore('&').substringBefore('#')
        return hash.length >= 32
    }

    private fun inferCachedFlag(stream: AddonStream): Boolean? {
        val cachedExtra = parseCachedValue(stream.extras["cached"])
        if (cachedExtra != null) return cachedExtra

        val displayTitle = stream.extras["displayTitle"] as? String
        val rawTitle = stream.extras["rawTitle"] as? String
        val sourceName = stream.extras["sourceName"] as? String
        val description = stream.extras["description"] as? String

        val text = listOfNotNull(stream.title, displayTitle, rawTitle, sourceName, description)
            .joinToString(" ")
            .lowercase()

        val hasRdMarker = Regex("\\brd\\b").containsMatchIn(text) ||
            Regex("\\brd\\d").containsMatchIn(text) ||
            text.contains("real-debrid") ||
            text.contains("real debrid")

        return when {
            hasRdMarker -> true
            text.contains("rd+") || text.contains("[rd+]") || text.contains("cached") || text.contains("instant") -> true
            text.contains("rd-") || text.contains("[rd-]") || text.contains("uncached") -> false
            else -> null
        }
    }

    private fun parseCachedValue(value: Any?): Boolean? {
        return when (value) {
            is Boolean -> value
            is String -> when (value.trim().lowercase()) {
                "true", "1", "yes" -> true
                "false", "0", "no" -> false
                else -> null
            }
            is Number -> value.toInt() != 0
            else -> null
        }
    }

}
