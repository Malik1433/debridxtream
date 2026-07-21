package com.tvonnet.debridxtreamiptv.data.debrid.repository

import android.util.Log
import com.tvonnet.debridxtreamiptv.data.debrid.model.AddonDefinition
import com.tvonnet.debridxtreamiptv.data.debrid.model.AddonStream
import com.tvonnet.debridxtreamiptv.data.debrid.source.AddonRemoteDataSource
import com.tvonnet.debridxtreamiptv.data.debrid.source.DynamicAddonFetcher
import com.tvonnet.debridxtreamiptv.data.debrid.source.StremioAddonFetcher
import com.tvonnet.debridxtreamiptv.data.prefs.DebridPreferences
import com.tvonnet.debridxtreamiptv.util.SensitiveLogRedactor
import com.tvonnet.debridxtreamiptv.data.Result as AppResult
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Owns addon discovery: the user-configured registries, the dynamic scrapers
 * built from their definitions, and the configured Stremio addons.
 *
 * Extracted verbatim from [UnifiedSourceProvider] (USP-5) together with the
 * scrape concurrency limit; the provider keeps one instance and delegates, so
 * behaviour and the "UnifiedSourceProvider" log tag are unchanged.
 */
internal class AddonSourceFetcher(
    private val addonRemote: AddonRemoteDataSource,
    private val dynamicAddonFetcher: DynamicAddonFetcher,
    private val stremioAddonFetcher: StremioAddonFetcher,
    private val debridPrefs: DebridPreferences
) {

    /** Limits how many scraper requests run at the same time. */
    private val scrapeSemaphore = Semaphore(10)

    companion object {
        private const val TAG = "UnifiedSourceProvider"
    }

    /**
     * Loads addon definitions from ALL user-configured registry URLs,
     * then fetches movie sources from each definition in parallel.
     *
     * @param imdbId IMDb ID of the movie.
     * @return Aggregated list of [AddonStream] from all dynamic scrapers.
     */
    suspend fun fetchDynamicMovieSources(imdbId: String?): List<AddonStream> = coroutineScope {
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
    suspend fun fetchDynamicEpisodeSources(
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
    suspend fun fetchStremioMovieSources(imdbId: String?): List<AddonStream> = coroutineScope {
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

    suspend fun fetchStremioEpisodeSources(
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
}
