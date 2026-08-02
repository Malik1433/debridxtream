package com.tvonnet.debridxtreamiptv.data.debrid.repository

import android.util.Log
import com.tvonnet.debridxtreamiptv.data.debrid.model.AddonStream
import com.tvonnet.debridxtreamiptv.data.debrid.model.AddonSourceType
import com.tvonnet.debridxtreamiptv.data.debrid.source.TmdbRemoteDataSource
import com.tvonnet.debridxtreamiptv.data.prefs.DebridPreferences
import com.tvonnet.debridxtreamiptv.data.repository.DebridCacheStatus
import com.tvonnet.debridxtreamiptv.data.repository.MovieSource
import com.tvonnet.debridxtreamiptv.data.model.ContentType
import com.tvonnet.debridxtreamiptv.util.SensitiveLogRedactor
import kotlinx.coroutines.async
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Owns the debrid discovery pipeline for movies and series episodes: the
 * resume fast-path (single-provider scoped fetch), the parallel
 * Stremio/MediaFusion/dynamic fan-out, stream prioritisation and the handoff
 * to cache verification and MovieSource conversion.
 *
 * Extracted verbatim from [UnifiedSourceProvider] (USP-7); the provider is now
 * a thin routing facade over this. Log tag stays "UnifiedSourceProvider".
 */
internal class DebridSourceOrchestrator(
    private val tmdbRemote: TmdbRemoteDataSource,
    private val mediaFusionFetcher: com.tvonnet.debridxtreamiptv.data.debrid.source.MediaFusionFetcher,
    private val settingsPrefs: com.tvonnet.debridxtreamiptv.data.prefs.SettingsPreferences,
    private val debridPrefs: DebridPreferences,
    private val addonFetcher: AddonSourceFetcher,
    private val cacheVerifier: DebridCacheVerifier
) {

    companion object {
        private const val TAG = "UnifiedSourceProvider"
        private const val SCOPED_FETCH_TIMEOUT_MS = 10000L // resume fast-path: single-provider fetch budget
    }

    /**
     * Get sources for debrid movies (using PureFire integration) with comprehensive logging
     */
    suspend fun getDebridMovieSources(
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
                val scoped = fetchScopedMovieStreams(preferredSourceType, imdbId, title, yearHint)
                if (scoped.any { streamMatchesStableIdentity(it, stablePriorityId) }) {
                    val scopedStreams = prioritizeAddonStreams(
                        deduplicateStreams(UnifiedSourceProvider.filterMismatchedAddonMovieStreams(scoped, title, yearHint))
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
                    val titleMatchedStreams = UnifiedSourceProvider.filterMismatchedAddonMovieStreams(fetchedStreams, title, yearHint)
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
            val titleMatchedStreams = UnifiedSourceProvider.filterMismatchedAddonMovieStreams(fetchedStreams, title, yearHint)
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
        yearHint: String?
    ): List<AddonStream> {
        return try {
            withTimeoutOrNull(SCOPED_FETCH_TIMEOUT_MS) {
                when (sourceType.uppercase()) {
                    AddonSourceType.STREMIO.name ->
                        UnifiedSourceProvider.filterMismatchedAddonMovieStreams(addonFetcher.fetchStremioMovieSources(imdbId), title, yearHint)
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
