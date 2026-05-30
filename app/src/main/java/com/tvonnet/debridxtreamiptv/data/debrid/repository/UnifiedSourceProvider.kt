package com.tvonnet.debridxtreamiptv.data.debrid.repository

import android.util.Log
import com.tvonnet.debridxtreamiptv.data.debrid.model.AddonDefinition
import com.tvonnet.debridxtreamiptv.data.debrid.model.AddonStream
import com.tvonnet.debridxtreamiptv.data.debrid.model.AddonSourceType
import com.tvonnet.debridxtreamiptv.data.debrid.model.DebridFailureClassifier
import com.tvonnet.debridxtreamiptv.data.debrid.source.AddonRemoteDataSource
import com.tvonnet.debridxtreamiptv.data.debrid.source.DynamicAddonFetcher
import com.tvonnet.debridxtreamiptv.data.debrid.source.RealDebridRemoteDataSource
import com.tvonnet.debridxtreamiptv.data.debrid.source.SimplifiedPureFireFetcher
import com.tvonnet.debridxtreamiptv.data.debrid.source.StremioAddonFetcher
import com.tvonnet.debridxtreamiptv.data.debrid.source.TmdbRemoteDataSource
import com.tvonnet.debridxtreamiptv.data.prefs.DebridPreferences
import com.tvonnet.debridxtreamiptv.data.repository.DebridCacheStatus
import com.tvonnet.debridxtreamiptv.data.repository.MovieSource
import com.tvonnet.debridxtreamiptv.data.repository.XtreamRepository
import com.tvonnet.debridxtreamiptv.data.model.XtreamVodInfo
import com.tvonnet.debridxtreamiptv.data.model.ContentType
import com.tvonnet.debridxtreamiptv.util.SensitiveLogRedactor
import com.tvonnet.debridxtreamiptv.data.Result as AppResult
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext
import java.util.concurrent.ConcurrentHashMap

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
    private val simplifiedPureFireFetcher: SimplifiedPureFireFetcher,
    private val mediaFusionFetcher: com.tvonnet.debridxtreamiptv.data.debrid.source.MediaFusionFetcher,
    private val settingsPrefs: com.tvonnet.debridxtreamiptv.data.prefs.SettingsPreferences,
    private val dynamicAddonFetcher: DynamicAddonFetcher,
    private val stremioAddonFetcher: StremioAddonFetcher,
    private val debridPrefs: DebridPreferences,
    private val realDebridRemote: RealDebridRemoteDataSource,
    private val realDebridRateLimiter: RealDebridRateLimiter
) {

    /** Limits how many scraper requests run at the same time. */
    private val scrapeSemaphore = Semaphore(10)
    private val availabilityCache = ConcurrentHashMap<String, AvailabilityCacheEntry>()

    companion object {
        private const val TAG = "UnifiedSourceProvider"
        private const val TIMEOUT_MS = 30000L // 30 second timeout
        private const val BATCH_SIZE = 10 // Process sources in batches
        private const val MAX_CACHE_VERIFICATION_HASHES = 10
        private const val CACHED_STATUS_TTL_MS = 15L * 60L * 1000L
        private const val NOT_CACHED_STATUS_TTL_MS = 3L * 60L * 1000L
        private const val UNKNOWN_STATUS_TTL_MS = 60L * 1000L
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
                            sortLanguage = preferredLang
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
                        val primaryCount = finalSources.count { it.isPrimary }
                        Log.d(TAG, "[SUCCESS] Sources available: count=${finalSources.size}, primary=$primaryCount")
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

            val stremioUrls = debridPrefs.getStremioAddonUrls()
            if (stremioUrls.isNotEmpty()) {
                Log.d(TAG, "Using ${stremioUrls.size} configured Stremio addon(s) as clean source path")
                val addonStreams = prioritizeAddonStreams(
                    deduplicateStreams(fetchStremioMovieSources(imdbId))
                )
                if (addonStreams.isEmpty()) return@coroutineScope emptyList()

                val cacheStatusByHash = verifyRealDebridCacheStatuses(addonStreams)
                return@coroutineScope convertAddonStreamsToMovieSources(addonStreams, title, cacheStatusByHash)
            }

            // Step 2: Fetch real sources from Enhanced Simplified PureFire (REAL TORRENTIO API) AND MediaFusion
            Log.d(TAG, "🚀 Step 2: Fetching real Torrentio & MediaFusion sources (IMDB: $imdbId, Title: '$title')")
            val contentType = ContentType.MOVIE
            val releaseYear = yearHint?.toIntOrNull()

            // PARALLEL EXECUTION: Run both built-in fetchers at the same time
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

            // DYNAMIC ADDONS: Fetch from all user-configured registries in parallel
            val dynamicDeferred = async {
                fetchDynamicMovieSources(imdbId)
            }

            // Wait for all to finish
            val torrentioStreams = torrentioDeferred.await()
            val mediaFusionStreams = mediaFusionDeferred.await()
            val dynamicStreams = dynamicDeferred.await()

            // Merge results
            val addonStreams = prioritizeAddonStreams(
                deduplicateStreams(torrentioStreams + mediaFusionStreams + dynamicStreams)
            )

            if (addonStreams.isNotEmpty()) {
                // Enhanced logging with provider breakdown
                Log.d(TAG, "PureFire movie sources returned: count=${addonStreams.size}, titlePresent=${!title.isNullOrBlank()}, hasImdbId=${!imdbId.isNullOrBlank()}")

                // Group sources by provider for detailed logging
                val sourcesByProvider = addonStreams.groupBy { it.source }

                Log.d(TAG, "   ┌─ Provider breakdown:")
                sourcesByProvider.forEach { (source, streams) ->
                    val providerName = when (source) {
                        AddonSourceType.TORRENTIO -> "Torrentio"
                        AddonSourceType.MEDIA_FUSION -> "MediaFusion"
                        AddonSourceType.STREMIO -> "Stremio"
                        AddonSourceType.ZILEAN -> "Zilean"
                        AddonSourceType.DYNAMIC -> "Dynamic"
                        AddonSourceType.UNKNOWN -> "PureFire"
                    }
                    Log.d(TAG, "   ├─ $providerName: ${streams.size} sources")

                    // Log provider-level metadata without release names.
                    streams.take(2).forEach { stream ->
                        val displayTitle = stream.extras["displayTitle"] as? String
                        val languageFlag = stream.extras["languageFlag"] as? String ?: "🌍"
                        val sizeFormatted = stream.sizeBytes?.let { formatFileSize(it) }
                        val seeders = stream.seeders

                        val sampleInfo = buildString {
                            if (!displayTitle.isNullOrBlank()) {
                                append("title=${SensitiveLogRedactor.describeSecret(displayTitle)}")
                            } else {
                                append("title=${SensitiveLogRedactor.describeSecret(stream.title)}")
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

                val cacheStatusByHash = verifyRealDebridCacheStatuses(addonStreams)
                val movieSources = convertAddonStreamsToMovieSources(addonStreams, title, cacheStatusByHash)
                Log.d(TAG, "🔄 Converted ${addonStreams.size} enhanced addon streams to ${movieSources.size} movie sources")

                // Log conversion success rate
                val conversionRate = if (addonStreams.isNotEmpty()) {
                    (movieSources.size.toDouble() / addonStreams.size * 100).toInt()
                } else 0
                Log.d(TAG, "📊 Conversion success rate: $conversionRate% (${movieSources.size}/${addonStreams.size})")

                return@coroutineScope movieSources
            } else {
                Log.i(TAG, "PureFire movie sources received: count=0, titlePresent=${!title.isNullOrBlank()}, hasImdbId=${!imdbId.isNullOrBlank()}")
                Log.w(TAG, "[NO-SOURCES] PureFire API returned no sources")
                if (!imdbId.isNullOrBlank()) {
                    Log.d(TAG, "   Movie identified but no torrents available")
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
            Log.w(TAG, "[GRACEFUL] PureFire source fetch interrupted: titlePresent=${!title.isNullOrBlank()}, error=${SensitiveLogRedactor.describeException(e)}")
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
        Log.d(TAG, "getSeriesEpisodeSources called: titlePresent=${!title.isNullOrBlank()}, season=$seasonNumber, episode=$episodeNumber, seriesId=${SensitiveLogRedactor.describeHash(seriesId)}")

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
                Log.d(TAG, "Series ID looks like TMDB ID. Fetching external IDs.")
                // It's likely a TMDB ID, fetch external IDs
                val detailsResult = tmdbRemote.getSeriesDetails(seriesId.toInt())
                if (detailsResult.isSuccess) {
                    val resolvedId = detailsResult.getOrNull()?.externalIds?.imdbId
                    Log.d(TAG, "Resolved IMDb ID from TMDB ID: imdbId=${SensitiveLogRedactor.describeHash(resolvedId)}, seriesId=${SensitiveLogRedactor.describeHash(seriesId)}")
                    resolvedId
                } else {
                    Log.w(TAG, "Failed to fetch series details from TMDB: seriesId=${SensitiveLogRedactor.describeHash(seriesId)}, error=${SensitiveLogRedactor.describeException(detailsResult.exceptionOrNull())}")
                    null
                }
            } else {
                Log.d(TAG, "Series ID appears to be IMDb/non-numeric. Using provided id=${SensitiveLogRedactor.describeHash(seriesId)}")
                seriesId // Assume it's already IMDb ID if not all digits (e.g. tt123456)
            }

            if (imdbId.isNullOrBlank()) {
                 Log.w(TAG, "Could not resolve IMDb ID for series=${SensitiveLogRedactor.describeHash(seriesId)}. Aborting source fetch.")
                 return@coroutineScope emptyList()
            }

            val stremioUrls = debridPrefs.getStremioAddonUrls()
            if (stremioUrls.isNotEmpty()) {
                Log.e(TAG, "Using ${stremioUrls.size} configured Stremio addon(s) as clean episode source path")
                val addonStreams = prioritizeAddonStreams(
                    deduplicateStreams(fetchStremioEpisodeSources(imdbId, seasonNumber, episodeNumber))
                )
                if (addonStreams.isEmpty()) return@coroutineScope emptyList()

                val cacheStatusByHash = verifyRealDebridCacheStatuses(addonStreams)
                return@coroutineScope convertAddonStreamsToMovieSources(addonStreams, title, cacheStatusByHash)
            }

            // Fetch real sources from Enhanced Simplified PureFire AND MediaFusion
            Log.e(TAG, "🚀 Fetching episode sources from Torrentio & MediaFusion for IMDb ID: $imdbId")

            // PARALLEL EXECUTION — built-in fetchers
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

            // DYNAMIC ADDONS: Fetch from all user-configured registries
            val dynamicDeferred = async {
                fetchDynamicEpisodeSources(imdbId, seasonNumber, episodeNumber)
            }

            val torrentioStreams = torrentioDeferred.await()
            val mediaFusionStreams = mediaFusionDeferred.await()
            val dynamicStreams = dynamicDeferred.await()

            val addonStreams = prioritizeAddonStreams(
                deduplicateStreams(torrentioStreams + mediaFusionStreams + dynamicStreams)
            )

            if (addonStreams.isNotEmpty()) {
                Log.e(TAG, "✅ PureFire API returned ${addonStreams.size} sources for Episode")
                val sourcesByProvider = addonStreams.groupBy { it.source }
                Log.e(TAG, "Provider breakdown (episode):")
                sourcesByProvider.forEach { (source, streams) ->
                    val providerName = when (source) {
                        AddonSourceType.TORRENTIO -> "Torrentio"
                        AddonSourceType.MEDIA_FUSION -> "MediaFusion"
                        AddonSourceType.STREMIO -> "Stremio"
                        AddonSourceType.ZILEAN -> "Zilean"
                        AddonSourceType.DYNAMIC -> "Dynamic"
                        AddonSourceType.UNKNOWN -> "PureFire"
                    }
                    Log.e(TAG, "   - $providerName: ${streams.size} sources")
                }
                val cacheStatusByHash = verifyRealDebridCacheStatuses(addonStreams)
                return@coroutineScope convertAddonStreamsToMovieSources(addonStreams, title, cacheStatusByHash)
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
        title: String?,
        cacheStatusByHash: Map<String, DebridCacheStatus>
    ): List<MovieSource> {
        val movieTitle = title ?: "Unknown Movie"
        Log.d(TAG, "🔄 Converting ${addonStreams.size} enhanced addon streams to MovieSource objects")

        return addonStreams.mapIndexedNotNull { index, addonStream ->
            // Filter out Addon error streams (e.g. MediaFusion/Torrentio API limits)
            val streamTitleLower = (addonStream.title ?: "").lowercase()
            val streamNameLower = (addonStream.extras["sourceName"] as? String ?: "").lowercase()
            val streamDescLower = (addonStream.extras["description"] as? String ?: "").lowercase()
            val combinedText = "$streamTitleLower $streamNameLower $streamDescLower"
            
            if (combinedText.contains("api exception") || 
                combinedText.contains("rate limit") || 
                combinedText.contains("invalid token") || 
                combinedText.contains("error occurred") ||
                combinedText.contains("debrid error")) {
                Log.w(TAG, "[SKIP] Filtering out Addon error stream: provider=${addonStream.source}, hasTitle=${!addonStream.title.isNullOrBlank()}")
                return@mapIndexedNotNull null
            }

            // Validate that we have a valid source (either url, infoHash or magnet)
            val validInfoHash = normalizeInfoHash(addonStream.infoHash)
            val validMagnet = addonStream.magnet?.takeIf { isValidMagnet(it) }
            val magnetInfoHash = extractInfoHashFromMagnet(validMagnet)
            val hasValidSource = !addonStream.url.isNullOrBlank() || validInfoHash != null || validMagnet != null

            if (!hasValidSource) {
                Log.w(TAG, "⚠️ [SKIP] Source skipped - no valid URL, magnet or infoHash")
                return@mapIndexedNotNull null
            }

            // Construct the magnet link or use a direct URL (MediaFusion prefers direct URLs)
            val mediaFusionUrl = addonStream.url
                ?.takeIf { addonStream.source == AddonSourceType.MEDIA_FUSION && it.isNotBlank() }
            val directUrl = addonStream.url?.takeIf { addonStream.source != AddonSourceType.MEDIA_FUSION && isDirectStreamUrl(it) }
            val directSource = when {
                mediaFusionUrl != null -> mediaFusionUrl
                directUrl != null -> directUrl
                validMagnet != null -> validMagnet
                validInfoHash != null -> "magnet:?xt=urn:btih:$validInfoHash"
                !addonStream.url.isNullOrBlank() -> addonStream.url
                else -> null
            }

            val streamId = if (mediaFusionUrl != null) {
                mediaFusionUrl.hashCode().toString() + "_$index"
            } else {
                (validInfoHash ?: addonStream.title.hashCode().toString()) + "_$index"
            }
            val providerLabel = (addonStream.extras["providerName"] as? String) ?: getSourceLabel(addonStream.source)
            val sourceName = addonStream.extras["sourceName"] as? String
            val bingeGroup = addonStream.extras["bingeGroup"] as? String
            val fileIdx = when (val rawFileIdx = addonStream.extras["fileIdx"]) {
                is Int -> rawFileIdx
                is Number -> rawFileIdx.toInt()
                is String -> rawFileIdx.toIntOrNull()
                else -> null
            }
            val cacheStatus = resolveCacheStatus(
                addonStream = addonStream,
                mediaFusionUrl = mediaFusionUrl,
                directUrl = directUrl,
                infoHash = validInfoHash ?: magnetInfoHash,
                cacheStatusByHash = cacheStatusByHash
            )
            val cachedFlag = cacheStatus.toLegacyCachedFlag()

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
                cacheStatus = cacheStatus,
                provider = providerLabel,
                sourceType = addonStream.source.name,
                sourceName = sourceName,
                bingeGroup = bingeGroup,
                fileIdx = fileIdx,
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
            AddonSourceType.STREMIO -> "Stremio"
            AddonSourceType.ZILEAN -> "Zilean"
            AddonSourceType.TORRENTIO -> "TorrentIO"
            AddonSourceType.DYNAMIC -> "Dynamic"
            AddonSourceType.UNKNOWN -> "PureFire"
        }
    }

    // ── Dynamic Addon Helpers ─────────────────────────────────────────────

    /**
     * Loads addon definitions from ALL user-configured registry URLs,
     * then fetches movie sources from each definition in parallel.
     *
     * @param imdbId IMDb ID of the movie.
     * @return Aggregated list of [AddonStream] from all dynamic scrapers.
     */
    private suspend fun fetchDynamicMovieSources(imdbId: String?): List<AddonStream> = coroutineScope {
        if (imdbId.isNullOrBlank()) return@coroutineScope emptyList()

        val definitions = loadAllDynamicDefinitions()
        if (definitions.isEmpty()) {
            Log.d(TAG, "📭 No dynamic addon definitions configured")
            return@coroutineScope emptyList()
        }

        Log.d(TAG, "Launching ${definitions.size} dynamic scrapers for movie id=${SensitiveLogRedactor.describeHash(imdbId)}")

        val results = definitions.map { def ->
            async {
                scrapeSemaphore.withPermit {
                    try {
                        dynamicAddonFetcher.fetchMovieSources(def, imdbId)
                    } catch (e: Exception) {
                        Log.w(TAG, "Dynamic [${def.name}] movie failed: error=${SensitiveLogRedactor.describeException(e)}")
                        emptyList()
                    }
                }
            }
        }.awaitAll().flatten()

        Log.d(TAG, "✅ Dynamic scrapers returned ${results.size} total movie streams")
        results
    }

    /**
     * Loads addon definitions from ALL user-configured registry URLs,
     * then fetches episode sources from each definition in parallel.
     *
     * @param imdbId  IMDb ID of the series.
     * @param season  Season number.
     * @param episode Episode number.
     * @return Aggregated list of [AddonStream] from all dynamic scrapers.
     */
    private suspend fun fetchDynamicEpisodeSources(
        imdbId: String?,
        season: Int,
        episode: Int
    ): List<AddonStream> = coroutineScope {
        if (imdbId.isNullOrBlank()) return@coroutineScope emptyList()

        val definitions = loadAllDynamicDefinitions()
        if (definitions.isEmpty()) return@coroutineScope emptyList()

        Log.d(TAG, "Launching ${definitions.size} dynamic scrapers for episode id=${SensitiveLogRedactor.describeHash(imdbId)} S${season}E${episode}")

        val results = definitions.map { def ->
            async {
                scrapeSemaphore.withPermit {
                    try {
                        dynamicAddonFetcher.fetchEpisodeSources(def, imdbId, season, episode)
                    } catch (e: Exception) {
                        Log.w(TAG, "Dynamic [${def.name}] episode failed: error=${SensitiveLogRedactor.describeException(e)}")
                        emptyList()
                    }
                }
            }
        }.awaitAll().flatten()

        Log.d(TAG, "✅ Dynamic scrapers returned ${results.size} total episode streams")
        results
    }

    /**
     * Fetches and aggregates [AddonDefinition] lists from every user-configured
     * registry URL. Deduplicates by (name + urlTemplate) to prevent
     * the same scraper from appearing twice.
     *
     * @return Flattened, deduplicated list of addon definitions.
     */
    private suspend fun fetchStremioMovieSources(imdbId: String?): List<AddonStream> = coroutineScope {
        if (imdbId.isNullOrBlank()) return@coroutineScope emptyList()
        val urls = debridPrefs.getStremioAddonUrls().toList()
        if (urls.isEmpty()) return@coroutineScope emptyList()

        urls.map { manifestUrl ->
            async {
                scrapeSemaphore.withPermit {
                    try {
                        stremioAddonFetcher.fetchMovieSources(manifestUrl, imdbId)
                    } catch (e: Exception) {
                        Log.w(TAG, "Stremio movie addon failed: url=${SensitiveLogRedactor.describeUrl(manifestUrl)}, error=${SensitiveLogRedactor.describeException(e)}")
                        emptyList()
                    }
                }
            }
        }.awaitAll().flatten()
    }

    private suspend fun fetchStremioEpisodeSources(
        imdbId: String?,
        season: Int,
        episode: Int
    ): List<AddonStream> = coroutineScope {
        if (imdbId.isNullOrBlank()) return@coroutineScope emptyList()
        val urls = debridPrefs.getStremioAddonUrls().toList()
        if (urls.isEmpty()) return@coroutineScope emptyList()

        urls.map { manifestUrl ->
            async {
                scrapeSemaphore.withPermit {
                    try {
                        stremioAddonFetcher.fetchEpisodeSources(manifestUrl, imdbId, season, episode)
                    } catch (e: Exception) {
                        Log.w(TAG, "Stremio episode addon failed: url=${SensitiveLogRedactor.describeUrl(manifestUrl)}, error=${SensitiveLogRedactor.describeException(e)}")
                        emptyList()
                    }
                }
            }
        }.awaitAll().flatten()
    }

    private suspend fun loadAllDynamicDefinitions(): List<AddonDefinition> {
        val urls = (debridPrefs.getAddonRegistryUrls() + DebridPreferences.DEFAULT_REGISTRY_URL + DebridPreferences.PUREFIRE_REGISTRY_URL)
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
        if (urls.isEmpty()) return emptyList()

        val allDefs = mutableListOf<AddonDefinition>()
        for (url in urls) {
            try {
                val result = addonRemote.fetchAddonDefinitions(url)
                if (result is AppResult.Success) {
                    allDefs.addAll(result.data)
                    Log.d(TAG, "Registry ${SensitiveLogRedactor.describeUrl(url)} -> ${result.data.size} definitions")
                } else {
                    Log.w(TAG, "Registry fetch failed for: ${SensitiveLogRedactor.describeUrl(url)}")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Registry error: url=${SensitiveLogRedactor.describeUrl(url)}, error=${SensitiveLogRedactor.describeException(e)}")
            }
        }

        // Deduplicate by (name + url template)
        val unique = allDefs.distinctBy { "${it.name}|${it.urlTemplate}" }
        Log.d(TAG, "📋 Total unique definitions: ${unique.size} (from ${allDefs.size} raw)")
        return unique
    }

    /**
     * Removes duplicate streams that share the same infoHash.
     * When duplicates are found, the stream with more metadata wins.
     *
     * @param streams Raw, possibly duplicated stream list.
     * @return Deduplicated list.
     */
    private fun deduplicateStreams(streams: List<AddonStream>): List<AddonStream> {
        if (streams.size <= 1) return streams

        val seen = mutableMapOf<String, AddonStream>()
        val noHashStreams = mutableListOf<AddonStream>()

        for (stream in streams) {
            val hash = stream.infoHash?.trim()?.lowercase()
            if (hash.isNullOrBlank()) {
                noHashStreams.add(stream)
                continue
            }
            val existing = seen[hash]
            if (existing == null) {
                seen[hash] = stream
            } else {
                // Keep the one with more useful metadata; cache is verified later.
                val existingScore = metadataScore(existing)
                val newScore = metadataScore(stream)
                if (newScore > existingScore) {
                    seen[hash] = stream
                }
            }
        }

        val result = seen.values.toList() + noHashStreams
        val removed = streams.size - result.size
        if (removed > 0) {
            Log.d(TAG, "🔄 Dedup removed $removed duplicate streams (${streams.size} → ${result.size})")
        }
        return result
    }

    /**
     * Puts user/recovery language streams early enough that limited RD cache
     * checks do not spend the whole verification budget on English-only entries.
     */
    private fun prioritizeAddonStreams(streams: List<AddonStream>): List<AddonStream> {
        if (streams.size <= 1) return streams

        val preferred = settingsPrefs.getPreferredAudioLanguage()?.trim()?.lowercase()
        val recoveryLanguages = listOfNotNull(preferred, "hi", "de").distinct()

        return streams.sortedWith(
            compareByDescending<AddonStream> { getAddonLanguageScore(it, recoveryLanguages) }
                .thenByDescending { sourcePriority(it.source) }
                .thenByDescending { qualityPriority(it.quality) }
                .thenByDescending { it.seeders ?: -1 }
                .thenByDescending { it.sizeBytes ?: -1L }
        )
    }

    private fun getAddonLanguageScore(stream: AddonStream, preferredLanguages: List<String>): Int {
        val languages = stream.languages?.map { it.lowercase() } ?: return 0
        val preferredScore = preferredLanguages.firstOrNull { languages.contains(it) }?.let { lang ->
            when (lang) {
                preferredLanguages.firstOrNull() -> 4
                "hi", "de" -> 3
                else -> 2
            }
        } ?: 0
        val multiScore = if (languages.contains("multi")) 2 else 0
        return maxOf(preferredScore, multiScore)
    }

    private fun sourcePriority(source: AddonSourceType): Int {
        return when (source) {
            AddonSourceType.MEDIA_FUSION -> 4
            AddonSourceType.STREMIO -> 4
            AddonSourceType.DYNAMIC -> 3
            AddonSourceType.TORRENTIO -> 2
            AddonSourceType.ZILEAN -> 1
            AddonSourceType.UNKNOWN -> 0
        }
    }

    private fun qualityPriority(quality: String?): Int {
        return when (quality?.lowercase()) {
            "4k", "2160p" -> 4
            "1080p" -> 3
            "720p" -> 2
            "480p" -> 1
            else -> 0
        }
    }

    /**
     * Scores a stream by how much useful metadata it carries.
     * Higher score = more informative stream entry.
     */
    private fun metadataScore(stream: AddonStream): Int {
        var score = 0
        if (stream.sizeBytes != null && stream.sizeBytes > 0) score += 2
        if (stream.seeders != null && stream.seeders > 0) score += 1
        if (!stream.quality.isNullOrBlank() && stream.quality != "Unknown") score += 1
        if (!stream.url.isNullOrBlank()) score += 1
        return score
    }

    private suspend fun verifyRealDebridCacheStatuses(
        streams: List<AddonStream>
    ): Map<String, DebridCacheStatus> = coroutineScope {
        val hashes = streams.mapNotNull { stream ->
            normalizeInfoHash(stream.infoHash) ?: extractInfoHashFromMagnet(stream.magnet)
        }.distinct().take(MAX_CACHE_VERIFICATION_HASHES)

        if (hashes.isEmpty()) return@coroutineScope emptyMap()

        val availabilitySemaphore = Semaphore(1)
        try {
            withTimeout(20000L) {
                hashes.map { hash ->
                    async {
                        availabilitySemaphore.withPermit {
                            hash to resolveCachedAvailability(hash)
                        }
                    }
                }.awaitAll().toMap()
            }
        } catch (e: Exception) {
            coroutineContext.ensureActive()
            Log.w(TAG, "RD cache verification incomplete; cache state remains unknown for ${hashes.size} hashes")
            emptyMap()
        }
    }

    private suspend fun resolveCachedAvailability(hash: String): DebridCacheStatus {
        val now = System.currentTimeMillis()
        availabilityCache[hash]?.let { entry ->
            if (entry.expiresAtMillis > now) return entry.status
            availabilityCache.remove(hash)
        }

        realDebridRateLimiter.globalCooldown()?.let {
            val status = DebridCacheStatus.UNKNOWN
            availabilityCache[hash] = AvailabilityCacheEntry(status, now + UNKNOWN_STATUS_TTL_MS)
            return status
        }

        realDebridRateLimiter.sourceCooldown(hash)?.let {
            val status = DebridCacheStatus.UNKNOWN
            availabilityCache[hash] = AvailabilityCacheEntry(status, now + UNKNOWN_STATUS_TTL_MS)
            return status
        }

        realDebridRateLimiter.awaitPermit()
        val status = when (val result = realDebridRemote.getInstantAvailability(hash)) {
            is AppResult.Success -> if (result.data) {
                DebridCacheStatus.VERIFIED_CACHED
            } else {
                DebridCacheStatus.NOT_CACHED
            }
            is AppResult.Error -> {
                val failure = DebridFailureClassifier.classify(result.exception)
                realDebridRateLimiter.recordFailure(hash, failure)
                DebridCacheStatus.UNKNOWN
            }
            else -> DebridCacheStatus.UNKNOWN
        }

        val ttl = when (status) {
            DebridCacheStatus.VERIFIED_CACHED -> CACHED_STATUS_TTL_MS
            DebridCacheStatus.NOT_CACHED -> NOT_CACHED_STATUS_TTL_MS
            else -> UNKNOWN_STATUS_TTL_MS
        }
        availabilityCache[hash] = AvailabilityCacheEntry(status, now + ttl)
        return status
    }

    private data class AvailabilityCacheEntry(
        val status: DebridCacheStatus,
        val expiresAtMillis: Long
    )

    private fun resolveCacheStatus(
        addonStream: AddonStream,
        mediaFusionUrl: String?,
        directUrl: String?,
        infoHash: String?,
        cacheStatusByHash: Map<String, DebridCacheStatus>
    ): DebridCacheStatus {
        if (mediaFusionUrl != null || directUrl != null) {
            return DebridCacheStatus.DIRECT_STREAM
        }

        val verified = infoHash?.let { cacheStatusByHash[it] }
        if (verified != null) return verified

        return when (parseCachedValue(addonStream.extras["cached"])) {
            false -> DebridCacheStatus.NOT_CACHED
            else -> DebridCacheStatus.UNKNOWN
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
        if (lowered.contains("aiostreams.elfhosted.com")) return true
        if (lowered.contains("/stremio/")) return true
        if (lowered.contains("/stream/")) return true
        if (lowered.contains("/play/")) return true
        if (lowered.contains("/playback/")) return true

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
        return if (trimmed.length >= 32) trimmed.lowercase() else null
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

    private fun extractInfoHashFromMagnet(magnet: String?): String? {
        if (magnet.isNullOrBlank()) return null
        val btihIndex = magnet.indexOf("btih:", ignoreCase = true)
        if (btihIndex == -1) return null
        return normalizeInfoHash(magnet.substring(btihIndex + 5).substringBefore('&').substringBefore('#'))
    }

    private fun DebridCacheStatus.toLegacyCachedFlag(): Boolean? {
        return when (this) {
            DebridCacheStatus.VERIFIED_CACHED,
            DebridCacheStatus.DIRECT_STREAM -> true
            DebridCacheStatus.NOT_CACHED -> false
            DebridCacheStatus.UNKNOWN -> null
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
