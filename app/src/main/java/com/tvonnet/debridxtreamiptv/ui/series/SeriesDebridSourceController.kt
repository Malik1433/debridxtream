package com.tvonnet.debridxtreamiptv.ui.series

import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.tvonnet.debridxtreamiptv.data.prefs.CredentialsPreferences
import com.tvonnet.debridxtreamiptv.data.debrid.repository.AddonProxyReadiness
import com.tvonnet.debridxtreamiptv.data.repository.DebridCacheStatus
import com.tvonnet.debridxtreamiptv.data.repository.MovieSource
import com.tvonnet.debridxtreamiptv.debug.PlaybackDiagnosticsRecorder
import com.tvonnet.debridxtreamiptv.ui.sources.SourceFilterState
import com.tvonnet.debridxtreamiptv.ui.sources.SourceFilterUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * SD-4b: the Debrid **source-acquisition** concern lifted out of [SeriesDetailActivity]. It owns the
 * per-episode source state (the seven maps that were Activity fields) and everything that produces /
 * refreshes the source list for an episode: the source bottom-sheet, the cross-source IPTV
 * aggregation, the MediaFusion cache-status sweep, and the "RD summary" header. The Activity's
 * playback launch code (SD-4c, still in the Activity) reads/mutates that state through this
 * controller's public members (kept at the original field/method names so the relocation is a
 * mechanical accessor rebind), and the chosen source flows back via [onPlayDebridSource] /
 * [onPlayIptvSource] — the exact `sourceType == "IPTV"` branch that lived in `onSourceSelected`.
 *
 * Behaviour byte-identical to the old private methods; only `this`/field refs were rebound to
 * [activity], the injected getters and [seasonUi].
 */
class SeriesDebridSourceController(
    private val activity: AppCompatActivity,
    private val seasonUi: SeriesSeasonUi,
    deps: Deps,
    views: Views,
    host: Host,
) {
    /** The data/resolver collaborators the per-episode source lists are built from. */
    data class Deps(
        val unifiedSourceProvider: com.tvonnet.debridxtreamiptv.data.debrid.repository.UnifiedSourceProvider,
        val debridPlaybackRepository: com.tvonnet.debridxtreamiptv.data.debrid.repository.DebridPlaybackRepository,
        val seriesRepositoryV2: com.tvonnet.debridxtreamiptv.features.seriesv2.data.repository.XtreamSeriesRepositoryV2,
        val credentialsPreferences: CredentialsPreferences,
    )

    /** The RD-summary strip views this controller renders into. */
    data class Views(
        val layoutRdSummary: LinearLayout,
        val tvRdSummary: TextView,
        val tvQualityBadge: TextView,
    )

    /** Current-series accessors + loading/play hand-offs back to the Activity. */
    data class Host(
        val seriesId: () -> String?,
        val seriesName: () -> String?,
        val seriesBackdrop: () -> String?,
        val seriesReleaseDate: () -> String?,
        val showLoading: (Boolean) -> Unit,
        val onPlayDebridSource: (MovieSource, EpisodeUiModel) -> Unit,
        val onPlayIptvSource: (MovieSource, EpisodeUiModel) -> Unit,
    )

    private val unifiedSourceProvider = deps.unifiedSourceProvider
    private val debridPlaybackRepository = deps.debridPlaybackRepository
    private val seriesRepositoryV2 = deps.seriesRepositoryV2
    private val credentialsPreferences = deps.credentialsPreferences
    private val layoutRdSummary = views.layoutRdSummary
    private val tvRdSummary = views.tvRdSummary
    private val tvQualityBadge = views.tvQualityBadge
    private val seriesId = host.seriesId
    private val seriesName = host.seriesName
    private val seriesBackdrop = host.seriesBackdrop
    private val seriesReleaseDate = host.seriesReleaseDate
    private val showLoading = host.showLoading
    private val onPlayDebridSource = host.onPlayDebridSource
    private val onPlayIptvSource = host.onPlayIptvSource

    // Per-episode source state (single source of truth; SD-4c playback reads these).
    val cachedDebridSourcesByEpisode = mutableMapOf<String, List<MovieSource>>()
    val debridFilterStateByEpisode = mutableMapOf<String, SourceFilterState>()
    val selectedDebridStreamIdByEpisode = mutableMapOf<String, String?>()
    val pendingDebridReturnFocusStreamIdsByEpisode = mutableMapOf<String, List<String>>()
    val failedDebridStreamIdsByEpisode = mutableMapOf<String, MutableSet<String>>()
    var activeDebridSourceRequestEpisodeId: String? = null
    var lastDebridEpisode: EpisodeUiModel? = null

    fun consumeDebridReturnFocusStreamIds(episodeId: String): List<String> {
        return pendingDebridReturnFocusStreamIdsByEpisode.remove(episodeId).orEmpty()
    }

    fun resolveDebridEpisodeFetchContext(episode: EpisodeUiModel): Pair<Int, Int>? {
        val seasonNumber = seasonUi.selectedSeasonKey?.toIntOrNull()
        val episodeNumber = episode.episodeNumber
        if (seasonNumber == null || episodeNumber == null) return null
        return seasonNumber to episodeNumber
    }

    fun pickResumeSource(
        episode: EpisodeUiModel,
        sources: List<MovieSource>
    ): MovieSource? {
        val failedIds = failedDebridStreamIdsByEpisode[episode.id].orEmpty()
        // 1) Source picked last time for this episode (same session)
        val preferredId = selectedDebridStreamIdByEpisode[episode.id]
        if (!preferredId.isNullOrBlank() && preferredId !in failedIds) {
            sources.firstOrNull { it.stream.stream_id == preferredId }?.let { return it }
        }
        // 2) Best available: respect the episode's filter state, prefer cached
        val filterState = debridFilterStateByEpisode[episode.id]
            ?: SourceFilterState()
        val candidates = SourceFilterUtils.apply(sources, filterState)
            .filterNot { source -> source.stream.stream_id?.let { it in failedIds } == true }
        return candidates.firstOrNull { it.isCached == true } ?: candidates.firstOrNull()
    }

    fun pickNextDebridEpisodeSource(
        episodeId: String,
        sources: List<MovieSource>,
        failedStreamId: String
    ): MovieSource? {
        val filterState = debridFilterStateByEpisode[episodeId]
            ?: SourceFilterState()
        val failedIds = failedDebridStreamIdsByEpisode[episodeId].orEmpty()
        val filteredSources = SourceFilterUtils.apply(sources, filterState)
            .filterNot { source -> source.stream.stream_id?.let { it in failedIds } == true }
        if (filteredSources.isEmpty()) return null

        val failedIndex = filteredSources.indexOfFirst { it.stream.stream_id == failedStreamId }
        val nextIndex = when {
            failedIndex in 0 until filteredSources.lastIndex -> failedIndex + 1
            failedIndex == -1 -> 0
            else -> -1
        }
        return if (nextIndex >= 0) filteredSources[nextIndex] else null
    }

    fun markDebridEpisodeSourceCached(episodeId: String, streamId: String?, isCached: Boolean) {
        if (streamId.isNullOrBlank()) return
        val current = cachedDebridSourcesByEpisode[episodeId] ?: return
        cachedDebridSourcesByEpisode[episodeId] = current.map { source ->
            if (source.stream.stream_id == streamId) {
                val isDirectHttp = source.stream.direct_source?.startsWith("http", ignoreCase = true) == true &&
                    source.stream.direct_source.endsWith(".torrent", ignoreCase = true).not()
                source.copy(
                    isCached = isCached,
                    cacheStatus = if (isCached) {
                        if (isDirectHttp) DebridCacheStatus.DIRECT_STREAM else DebridCacheStatus.VERIFIED_CACHED
                    } else {
                        DebridCacheStatus.NOT_CACHED
                    }
                )
            } else {
                source
            }
        }
    }

    fun fetchAndShowDebridSources(
        episode: EpisodeUiModel,
        returnFocusStreamIds: List<String> = emptyList()
    ) {
        val requestEpisodeId = episode.id
        activeDebridSourceRequestEpisodeId = requestEpisodeId
        lastDebridEpisode = episode
        val bottomSheet = buildEpisodeSourceSheet(episode, returnFocusStreamIds)

        bottomSheet.show(activity.supportFragmentManager, SourceSelectionBottomSheet.TAG)
        val cachedSources = cachedDebridSourcesByEpisode[episode.id]
        if (cachedSources != null) {
            recordEpisodeSourceList(cachedSources, seasonUi.selectedSeasonKey?.toIntOrNull(), episode.episodeNumber, cacheHit = true)
            bottomSheet.showSources(cachedSources)
            refreshMediaFusionCacheStatus(episode.id, cachedSources, bottomSheet)
            return
        }

        val episodeFetchContext = resolveDebridEpisodeFetchContext(episode)
        if (episodeFetchContext == null) {
            val message = "Episode metadata missing for source fetch"
            bottomSheet.showError(message)
            Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
            return
        }

        bottomSheet.showLoading()
        loadAndShowEpisodeSources(episode, requestEpisodeId, episodeFetchContext, bottomSheet)
    }

    private fun buildEpisodeSourceSheet(
        episode: EpisodeUiModel,
        returnFocusStreamIds: List<String>
    ): SourceSelectionBottomSheet {
        val initialState = debridFilterStateByEpisode[episode.id]
            ?: SourceFilterState()
        val initialSelectedStreamId = selectedDebridStreamIdByEpisode[episode.id]
        return SourceSelectionBottomSheet(
            onSourceSelected = { source, adjacentStreamIds ->
                selectedDebridStreamIdByEpisode[episode.id] = source.stream.stream_id
                pendingDebridReturnFocusStreamIdsByEpisode[episode.id] = adjacentStreamIds
                PlaybackDiagnosticsRecorder.record(
                    activity,
                    "source_selected",
                    PlaybackDiagnosticsRecorder.contentFields(
                        kind = "series_episode",
                        tmdbId = seriesId(),
                        season = seasonUi.selectedSeasonKey?.toIntOrNull(),
                        episode = episode.episodeNumber
                    ) + PlaybackDiagnosticsRecorder.sourceFields(activity, source)
                )
                if (source.sourceType == "IPTV") {
                    onPlayIptvSource(source, episode)
                } else {
                    onPlayDebridSource(source, episode)
                }
            },
            onStateChanged = { state ->
                debridFilterStateByEpisode[episode.id] = state
            },
            initialState = initialState,
            initialSelectedStreamId = initialSelectedStreamId,
            initialReturnFocusStreamIds = returnFocusStreamIds,
            contentTitle = seriesName(),
            backdropUrl = seriesBackdrop(),
            preferredAudioLang = credentialsPreferences.preferredAudioLang
        )
    }

    private fun recordEpisodeSourceList(sources: List<MovieSource>, season: Int?, episodeNumber: Int?, cacheHit: Boolean) {
        PlaybackDiagnosticsRecorder.record(
            activity,
            "source_list_loaded",
            PlaybackDiagnosticsRecorder.contentFields(
                kind = "series_episode",
                tmdbId = seriesId(),
                season = season,
                episode = episodeNumber
            ) + PlaybackDiagnosticsRecorder.sourceListFields(activity, sources) +
                mapOf("sourceListCacheHit" to cacheHit)
        )
    }

    /** True when the sheet/request no longer belongs to the episode on screen. */
    private fun isStaleSourceRequest(requestEpisodeId: String): Boolean =
        activeDebridSourceRequestEpisodeId != requestEpisodeId ||
            seasonUi.selectedEpisode?.id != requestEpisodeId

    private fun loadAndShowEpisodeSources(
        episode: EpisodeUiModel,
        requestEpisodeId: String,
        episodeFetchContext: Pair<Int, Int>,
        bottomSheet: SourceSelectionBottomSheet
    ) {
        activity.lifecycleScope.launch {
            try {
                val (seasonNumber, episodeNumber) = episodeFetchContext

                val debridSources = withContext(Dispatchers.IO) {
                    unifiedSourceProvider.getSeriesEpisodeSources(
                        seriesId = seriesId(),
                        seasonNumber = seasonNumber,
                        episodeNumber = episodeNumber,
                        title = seriesName(),
                        yearHint = seriesReleaseDate()?.take(4)
                    )
                }
                // Cross-source: append IPTV listings of the same show (matched by
                // title across the Xtream catalog). DB-only, effectively instant.
                val iptvGroups = iptvGroupsForEpisode(seasonNumber, episodeNumber)
                val sources = debridSources + mapIptvGroupsToSources(iptvGroups)
                android.util.Log.e("SeriesDetailActivity", "Received ${debridSources.size} debrid + ${sources.size - debridSources.size} IPTV sources. Updating bottom sheet.")
                cachedDebridSourcesByEpisode[episode.id] = sources
                updateRdSummary(sources)
                if (isStaleSourceRequest(requestEpisodeId)) {
                    android.util.Log.d("SeriesDetailActivity", "Ignoring stale Debrid sources for episode=$requestEpisodeId")
                    return@launch
                }
                recordEpisodeSourceList(sources, seasonNumber, episodeNumber, cacheHit = false)
                bottomSheet.showSources(sources)

                // Background: verify IPTV rows (drop listings lacking this episode,
                // fill playable URLs), then run the MediaFusion refresh on the final
                // list so the two updates can't clobber each other.
                val verifiedGroups = verifyIptvGroups(iptvGroups, seasonNumber, episodeNumber)
                val finalSources = debridSources + mapIptvGroupsToSources(verifiedGroups)
                if (isStaleSourceRequest(requestEpisodeId)) return@launch
                cachedDebridSourcesByEpisode[episode.id] = finalSources
                if (finalSources != sources) {
                    bottomSheet.showSources(finalSources)
                }
                refreshMediaFusionCacheStatus(episode.id, finalSources, bottomSheet)
            } catch (e: Exception) {
                android.util.Log.e("SeriesDetailActivity", "Error fetching sources: ${e.message}", e)
                if (isStaleSourceRequest(requestEpisodeId)) {
                    android.util.Log.d("SeriesDetailActivity", "Ignoring stale Debrid source error for episode=$requestEpisodeId")
                    return@launch
                }
                showIptvFallbackSources(episode, requestEpisodeId, episodeFetchContext, bottomSheet, e)
            }
        }
    }

    // Debrid failed — IPTV listings of the same show may still be playable.
    // Show them immediately; verify in the background (never before showing).
    private suspend fun showIptvFallbackSources(
        episode: EpisodeUiModel,
        requestEpisodeId: String,
        episodeFetchContext: Pair<Int, Int>,
        bottomSheet: SourceSelectionBottomSheet,
        error: Exception
    ) {
        val (s, ep) = episodeFetchContext
        val iptvFallbackGroups = iptvGroupsForEpisode(s, ep)
        val iptvFallback = mapIptvGroupsToSources(iptvFallbackGroups)
        if (iptvFallback.isNotEmpty()) {
            cachedDebridSourcesByEpisode[episode.id] = iptvFallback
            bottomSheet.showSources(iptvFallback)
            val verifiedFallback = mapIptvGroupsToSources(verifyIptvGroups(iptvFallbackGroups, s, ep))
            if (isStaleSourceRequest(requestEpisodeId)) return
            if (verifiedFallback.isNotEmpty() && verifiedFallback != iptvFallback) {
                cachedDebridSourcesByEpisode[episode.id] = verifiedFallback
                bottomSheet.showSources(verifiedFallback)
            }
        } else {
            bottomSheet.showError(error.message ?: "Failed to load sources")
        }
    }

    /** IPTV cross-category aggregation for this show (DB-only, instant). */
    private suspend fun iptvGroupsForEpisode(
        seasonNumber: Int,
        episodeNumber: Int
    ): List<com.tvonnet.debridxtreamiptv.features.seriesv2.ui.model.IptvStreamGroup> {
        val title = seriesName()?.takeIf { it.isNotBlank() } ?: return emptyList()
        return try {
            withContext(Dispatchers.IO) {
                seriesRepositoryV2.aggregateStreamsForTitle(
                    title, seasonNumber, episodeNumber, yearHint = seriesReleaseDate()?.take(4)
                )
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.w("SeriesDetailActivity", "iptvGroupsForEpisode failed: ${e.message}")
            emptyList()
        }
    }

    /** Throttled availability sweep over the IPTV groups; never throws. */
    private suspend fun verifyIptvGroups(
        groups: List<com.tvonnet.debridxtreamiptv.features.seriesv2.ui.model.IptvStreamGroup>,
        seasonNumber: Int,
        episodeNumber: Int
    ): List<com.tvonnet.debridxtreamiptv.features.seriesv2.ui.model.IptvStreamGroup> {
        if (groups.isEmpty()) return groups
        return try {
            withContext(Dispatchers.IO) {
                seriesRepositoryV2.verifyStreamGroups(groups, seasonNumber, episodeNumber)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.w("SeriesDetailActivity", "verifyIptvGroups failed: ${e.message}")
            groups
        }
    }

    private fun updateRdSummary(sources: List<MovieSource>) {
        if (sources.isEmpty()) {
            layoutRdSummary.visibility = View.GONE
            return
        }
        layoutRdSummary.visibility = View.VISIBLE
        val cachedCount = sources.count { it.isCached == true }
        val bestQuality = when {
            sources.any { it.quality?.contains("4K", true) == true || it.quality?.contains("2160", true) == true } -> "4K"
            sources.any { it.quality?.contains("1080", true) == true } -> "1080P"
            sources.any { it.quality?.contains("720", true) == true } -> "720P"
            else -> ""
        }
        val bestSize = sources.filter { it.isCached == true }
            .mapNotNull { it.sizeBytes }
            .maxOrNull()
        val sizeLabel = if (bestSize != null) " ${formatSizeLabel(bestSize)}" else ""
        val qualityPart = if (bestQuality.isNotEmpty()) " · BEST $bestQuality$sizeLabel" else ""
        tvRdSummary.text = "${sources.size} SOURCES / EPISODE · $cachedCount CACHED$qualityPart"

        // Update quality badge
        if (bestQuality.isNotEmpty()) {
            tvQualityBadge.visibility = View.VISIBLE
            tvQualityBadge.text = bestQuality
        }
    }

    private fun formatSizeLabel(bytes: Long): String {
        return when {
            bytes >= 1_073_741_824L -> String.format(java.util.Locale.US, "%.1f GB", bytes / 1_073_741_824.0)
            bytes >= 1_048_576L -> String.format(java.util.Locale.US, "%.0f MB", bytes / 1_048_576.0)
            else -> "${bytes / 1024} KB"
        }
    }

    private fun refreshMediaFusionCacheStatus(
        episodeId: String,
        sources: List<MovieSource>,
        bottomSheet: SourceSelectionBottomSheet
    ) {
        val mediaFusionSources = sources.filter { source ->
            val url = source.stream.direct_source
            debridPlaybackRepository.requiresDirectProxyReadinessCheck(
                url,
                source.provider,
                source.sourceName,
                source.sourceType
            )
        }
        if (mediaFusionSources.isEmpty()) return

        activity.lifecycleScope.launch {
            val updates = withContext(Dispatchers.IO) { probeEpisodeProxyReadiness(mediaFusionSources) }
            if (updates.isEmpty()) return@launch

            val updatedSources = applyEpisodeReadinessUpdates(episodeId, sources, updates)
            if (updatedSources != sources) {
                cachedDebridSourcesByEpisode[episodeId] = updatedSources
                if (bottomSheet.isAdded) {
                    bottomSheet.showSources(updatedSources)
                }
            }
        }
    }

    // Bounded fan-out (4 at a time) over the proxy-backed sources; UNCERTAIN on any miss.
    private suspend fun probeEpisodeProxyReadiness(
        mediaFusionSources: List<MovieSource>
    ): Map<String?, AddonProxyReadiness> {
        val semaphore = Semaphore(4)
        return coroutineScope {
            mediaFusionSources.map { source ->
                async {
                    semaphore.withPermit {
                        val url = source.stream.direct_source ?: return@withPermit null
                        val result = debridPlaybackRepository.getAddonProxyPlaybackReadiness(
                            url,
                            source.headers,
                            source.provider,
                            source.sourceName,
                            source.sourceType,
                            diagnosticsContext = activity
                        )
                        val readiness =
                            (result as? com.tvonnet.debridxtreamiptv.data.Result.Success)?.data
                                ?: AddonProxyReadiness.UNCERTAIN
                        source.stream.stream_id to readiness
                    }
                }
            }.awaitAll().filterNotNull().toMap()
        }
    }

    // UNCERTAIN never downgrades a source; TERMINAL also records the failed stream id.
    private fun applyEpisodeReadinessUpdates(
        episodeId: String,
        sources: List<MovieSource>,
        updates: Map<String?, AddonProxyReadiness>
    ): List<MovieSource> = sources.map { source ->
        when (updates[source.stream.stream_id]) {
            AddonProxyReadiness.READY -> if (source.isCached != true) {
                source.copy(
                    isCached = true,
                    cacheStatus = DebridCacheStatus.DIRECT_STREAM
                )
            } else {
                source
            }
            AddonProxyReadiness.TERMINAL -> {
                source.stream.stream_id?.let { streamId ->
                    failedDebridStreamIdsByEpisode.getOrPut(episodeId) { linkedSetOf() }.add(streamId)
                }
                if (source.isCached != false) {
                    source.copy(
                        isCached = false,
                        cacheStatus = DebridCacheStatus.NOT_CACHED
                    )
                } else {
                    source
                }
            }
            AddonProxyReadiness.UNCERTAIN,
            null -> source
        }
    }
}
