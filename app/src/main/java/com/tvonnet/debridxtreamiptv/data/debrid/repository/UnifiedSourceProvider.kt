package com.tvonnet.debridxtreamiptv.data.debrid.repository

import android.util.Log
import com.tvonnet.debridxtreamiptv.data.debrid.model.AddonStream
import com.tvonnet.debridxtreamiptv.data.debrid.model.AddonSourceType
import com.tvonnet.debridxtreamiptv.data.debrid.source.AddonRemoteDataSource
import com.tvonnet.debridxtreamiptv.data.debrid.source.DynamicAddonFetcher
import com.tvonnet.debridxtreamiptv.data.debrid.source.RealDebridRemoteDataSource
import com.tvonnet.debridxtreamiptv.data.debrid.source.StremioAddonFetcher
import com.tvonnet.debridxtreamiptv.data.debrid.source.TmdbRemoteDataSource
import com.tvonnet.debridxtreamiptv.data.prefs.DebridPreferences
import com.tvonnet.debridxtreamiptv.data.repository.DebridCacheStatus
import com.tvonnet.debridxtreamiptv.data.repository.MovieSource
import com.tvonnet.debridxtreamiptv.data.repository.XtreamRepository
import com.tvonnet.debridxtreamiptv.data.model.ContentType
import com.tvonnet.debridxtreamiptv.util.SensitiveLogRedactor
import com.tvonnet.debridxtreamiptv.data.Result as AppResult
import kotlinx.coroutines.async
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
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
                            getDebridMovieSources(title, yearHint, imdbId, priorityInfoHash, preferredSourceType)
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
            } catch (e: CancellationException) {
                throw e
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
        providedImdbId: String? = null,
        priorityInfoHash: String? = null,
        preferredSourceType: String? = null
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

            val contentType = ContentType.MOVIE
            val releaseYear = yearHint?.toIntOrNull()

            // RESUME FAST PATH: the caller already knows which source it will
            // replay (priorityInfoHash) and which provider served it — ask only
            // that provider first. Full multi-provider discovery runs only when
            // the saved source is no longer offered there.
            val stablePriorityId = stableStreamIdentity(priorityInfoHash)
            if (stablePriorityId != null && !preferredSourceType.isNullOrBlank()) {
                val scoped = fetchScopedMovieStreams(preferredSourceType, imdbId, title, yearHint, releaseYear, contentType)
                if (scoped.any { streamMatchesStableIdentity(it, stablePriorityId) }) {
                    val scopedStreams = prioritizeAddonStreams(
                        deduplicateStreams(filterMismatchedAddonMovieStreams(scoped, title, yearHint))
                    )
                    Log.i(TAG, "Scoped resume fetch hit: provider=$preferredSourceType, streams=${scopedStreams.size} — skipping full discovery")
                    val cacheStatusByHash = cacheVerifier.verifyRealDebridCacheStatuses(scopedStreams, priorityInfoHash)
                    return@coroutineScope convertAddonStreamsToMovieSources(scopedStreams, title, cacheStatusByHash)
                }
                Log.i(TAG, "Scoped resume fetch miss: provider=$preferredSourceType — falling back to full discovery")
            }

            // Step 2: Fetch real sources from Enhanced Simplified PureFire (REAL TORRENTIO API) AND MediaFusion
            Log.d(TAG, "🚀 Step 2: Fetching real Torrentio & MediaFusion sources (IMDB: $imdbId, Title: '$title')")

            // PARALLEL EXECUTION: configured Stremio addons run alongside the built-in fetchers
            val stremioDeferred = async {
                val stremioUrls = debridPrefs.getStremioAddonUrls()
                if (stremioUrls.isEmpty()) return@async emptyList<AddonStream>()
                try {
                    Log.d(TAG, "Using ${stremioUrls.size} configured Stremio addon(s) alongside fallback movie providers")
                    val fetchedStreams = addonFetcher.fetchStremioMovieSources(imdbId)
                    val titleMatchedStreams = filterMismatchedAddonMovieStreams(fetchedStreams, title, yearHint)
                    val filteredCount = fetchedStreams.size - titleMatchedStreams.size
                    if (filteredCount > 0) {
                        Log.w(TAG, "Filtered $filteredCount mismatched configured Stremio movie stream(s) before fallback providers")
                    }
                    val addonStreams = deduplicateStreams(titleMatchedStreams)
                    if (addonStreams.isEmpty()) {
                        Log.w(TAG, "Configured Stremio movie provider returned no usable streams; continuing fallback providers: titlePresent=${!title.isNullOrBlank()}, hasImdbId=${!imdbId.isNullOrBlank()}")
                    } else {
                        Log.d(TAG, "Configured Stremio movie provider returned ${addonStreams.size} usable streams; continuing fallback providers")
                    }
                    addonStreams
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Configured Stremio movie provider failed: titlePresent=${!title.isNullOrBlank()}, hasImdbId=${!imdbId.isNullOrBlank()}, error=${e.message}", e)
                    emptyList()
                }
            }

            val mediaFusionDeferred = async {
                try {
                    mediaFusionFetcher.fetchMovieSources(imdbId)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "MediaFusion movie provider failed: titlePresent=${!title.isNullOrBlank()}, hasImdbId=${!imdbId.isNullOrBlank()}, error=${e.message}", e)
                    Log.e(TAG, "❌ MediaFusion fetch failed (Safe Catch)", e)
                    emptyList()
                }
            }

            // DYNAMIC ADDONS: Fetch from all user-configured registries in parallel
            val dynamicDeferred = async {
                try {
                    addonFetcher.fetchDynamicMovieSources(imdbId)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Dynamic addon movie providers failed: titlePresent=${!title.isNullOrBlank()}, hasImdbId=${!imdbId.isNullOrBlank()}, error=${e.message}", e)
                    emptyList()
                }
            }

            // Wait for all to finish
            val configuredAddonStreams = stremioDeferred.await()
            val mediaFusionStreams = mediaFusionDeferred.await()
            val dynamicStreams = dynamicDeferred.await()

            // Merge results (PureFire/Torrentio removed — sources come from the external addons)
            val fetchedStreams = configuredAddonStreams + mediaFusionStreams + dynamicStreams
            val titleMatchedStreams = filterMismatchedAddonMovieStreams(fetchedStreams, title, yearHint)
            val filteredCount = fetchedStreams.size - titleMatchedStreams.size
            if (filteredCount > 0) {
                Log.w(TAG, "Filtered $filteredCount mismatched addon movie stream(s) before picker conversion")
            }
            val addonStreams = prioritizeAddonStreams(
                deduplicateStreams(titleMatchedStreams)
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

                val cacheStatusByHash = cacheVerifier.verifyRealDebridCacheStatuses(addonStreams, priorityInfoHash)
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

        } catch (e: CancellationException) {
            throw e
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
        yearHint: String?,
        priorityInfoHash: String? = null,
        preferredSourceType: String? = null
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

            // RESUME FAST PATH: ask only the provider that served the saved
            // source; full discovery only when the source vanished from it.
            val stablePriorityId = stableStreamIdentity(priorityInfoHash)
            if (stablePriorityId != null && !preferredSourceType.isNullOrBlank()) {
                val scoped = fetchScopedEpisodeStreams(preferredSourceType, imdbId, seasonNumber, episodeNumber, title)
                if (scoped.any { streamMatchesStableIdentity(it, stablePriorityId) }) {
                    val scopedStreams = prioritizeAddonStreams(deduplicateStreams(scoped))
                    Log.i(TAG, "Scoped resume fetch hit (episode): provider=$preferredSourceType, streams=${scopedStreams.size} — skipping full discovery")
                    val cacheStatusByHash = cacheVerifier.verifyRealDebridCacheStatuses(scopedStreams, priorityInfoHash)
                    return@coroutineScope convertAddonStreamsToMovieSources(scopedStreams, title, cacheStatusByHash)
                }
                Log.i(TAG, "Scoped resume fetch miss (episode): provider=$preferredSourceType — falling back to full discovery")
            }

            // Fetch real sources from Enhanced Simplified PureFire AND MediaFusion
            Log.e(TAG, "🚀 Fetching episode sources from Torrentio & MediaFusion for IMDb ID: $imdbId")

            // PARALLEL EXECUTION: configured Stremio addons run alongside the built-in fetchers
            val stremioDeferred = async {
                val stremioUrls = debridPrefs.getStremioAddonUrls()
                if (stremioUrls.isEmpty()) return@async emptyList<AddonStream>()
                try {
                    Log.e(TAG, "Using ${stremioUrls.size} configured Stremio addon(s) alongside fallback episode providers")
                    val addonStreams = deduplicateStreams(addonFetcher.fetchStremioEpisodeSources(imdbId, seasonNumber, episodeNumber))
                    if (addonStreams.isEmpty()) {
                        Log.w(TAG, "Configured Stremio episode provider returned no usable streams; continuing fallback providers: season=$seasonNumber, episode=$episodeNumber, hasImdbId=${!imdbId.isNullOrBlank()}")
                    } else {
                        Log.d(TAG, "Configured Stremio episode provider returned ${addonStreams.size} usable streams; continuing fallback providers")
                    }
                    addonStreams
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Configured Stremio episode provider failed: season=$seasonNumber, episode=$episodeNumber, hasImdbId=${!imdbId.isNullOrBlank()}, error=${e.message}", e)
                    emptyList()
                }
            }

            val mediaFusionDeferred = async {
                try {
                    mediaFusionFetcher.fetchEpisodeSources(imdbId, seasonNumber, episodeNumber)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "MediaFusion episode provider failed: season=$seasonNumber, episode=$episodeNumber, hasImdbId=${!imdbId.isNullOrBlank()}, error=${e.message}", e)
                    Log.e(TAG, "❌ MediaFusion episode fetch failed (Safe Catch)", e)
                    emptyList()
                }
            }

            // DYNAMIC ADDONS: Fetch from all user-configured registries
            val dynamicDeferred = async {
                try {
                    addonFetcher.fetchDynamicEpisodeSources(imdbId, seasonNumber, episodeNumber)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Dynamic addon episode providers failed: season=$seasonNumber, episode=$episodeNumber, hasImdbId=${!imdbId.isNullOrBlank()}, error=${e.message}", e)
                    emptyList()
                }
            }

            val configuredAddonStreams = stremioDeferred.await()
            val mediaFusionStreams = mediaFusionDeferred.await()
            val dynamicStreams = dynamicDeferred.await()

            val addonStreams = prioritizeAddonStreams(
                deduplicateStreams(configuredAddonStreams + mediaFusionStreams + dynamicStreams)
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
                val cacheStatusByHash = cacheVerifier.verifyRealDebridCacheStatuses(addonStreams, priorityInfoHash)
                return@coroutineScope convertAddonStreamsToMovieSources(addonStreams, title, cacheStatusByHash)
            } else {
                Log.e(TAG, "📭 [NO-SOURCES] PureFire API returned no sources for Episode")
                return@coroutineScope emptyList()
            }

        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            coroutineContext.ensureActive()
            Log.e(TAG, "⚠️ [GRACEFUL] PureFire source fetch interrupted for Episode", e)
            return@coroutineScope emptyList()
        }
    }

    /**
     * Resume fast-path helpers: fetch from a single provider family, identified
     * by the saved DebridSourceProfile.sourceType (AddonSourceType enum name).
     */
    private suspend fun fetchScopedMovieStreams(
        sourceType: String,
        imdbId: String?,
        title: String?,
        yearHint: String?,
        releaseYear: Int?,
        contentType: ContentType
    ): List<AddonStream> {
        return try {
            withTimeoutOrNull(SCOPED_FETCH_TIMEOUT_MS) {
                when (sourceType.uppercase()) {
                    AddonSourceType.STREMIO.name ->
                        filterMismatchedAddonMovieStreams(addonFetcher.fetchStremioMovieSources(imdbId), title, yearHint)
                    AddonSourceType.TORRENTIO.name -> emptyList() // PureFire/Torrentio removed
                    AddonSourceType.MEDIA_FUSION.name ->
                        mediaFusionFetcher.fetchMovieSources(imdbId)
                    AddonSourceType.DYNAMIC.name ->
                        addonFetcher.fetchDynamicMovieSources(imdbId)
                    else -> emptyList()
                }
            } ?: emptyList()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Scoped movie fetch failed for provider=$sourceType: ${e.javaClass.simpleName}")
            emptyList()
        }
    }

    private suspend fun fetchScopedEpisodeStreams(
        sourceType: String,
        imdbId: String,
        seasonNumber: Int,
        episodeNumber: Int,
        title: String?
    ): List<AddonStream> {
        return try {
            withTimeoutOrNull(SCOPED_FETCH_TIMEOUT_MS) {
                when (sourceType.uppercase()) {
                    AddonSourceType.STREMIO.name ->
                        addonFetcher.fetchStremioEpisodeSources(imdbId, seasonNumber, episodeNumber)
                    AddonSourceType.TORRENTIO.name -> emptyList() // PureFire/Torrentio removed
                    AddonSourceType.MEDIA_FUSION.name ->
                        mediaFusionFetcher.fetchEpisodeSources(imdbId, seasonNumber, episodeNumber)
                    AddonSourceType.DYNAMIC.name ->
                        addonFetcher.fetchDynamicEpisodeSources(imdbId, seasonNumber, episodeNumber)
                    else -> emptyList()
                }
            } ?: emptyList()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Scoped episode fetch failed for provider=$sourceType: ${e.javaClass.simpleName}")
            emptyList()
        }
    }

    /** Stable part of a saved stream id — strips the volatile "_<index>" suffix. */


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




}
