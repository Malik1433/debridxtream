package com.tvonnet.debridxtreamiptv.ui.vod

import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.tvonnet.debridxtreamiptv.R
import com.tvonnet.debridxtreamiptv.data.Result
import com.tvonnet.debridxtreamiptv.data.debrid.repository.AddonProxyReadiness
import com.tvonnet.debridxtreamiptv.data.debrid.repository.DebridPlaybackRepository
import com.tvonnet.debridxtreamiptv.data.debrid.repository.UnifiedSourceProvider
import com.tvonnet.debridxtreamiptv.data.model.XtreamVodInfo
import com.tvonnet.debridxtreamiptv.data.prefs.CredentialsPreferences
import com.tvonnet.debridxtreamiptv.data.repository.DebridCacheStatus
import com.tvonnet.debridxtreamiptv.data.repository.MovieSource
import com.tvonnet.debridxtreamiptv.data.repository.XtreamRepository
import com.tvonnet.debridxtreamiptv.debug.PlaybackDiagnosticsRecorder
import com.tvonnet.debridxtreamiptv.ui.series.LanguageFilterAdapter
import com.tvonnet.debridxtreamiptv.ui.series.SourceSelectionBottomSheet
import com.tvonnet.debridxtreamiptv.ui.sources.SizeFilterAdapter
import com.tvonnet.debridxtreamiptv.ui.sources.SizeFilterOption
import com.tvonnet.debridxtreamiptv.ui.sources.SourceFilterState
import com.tvonnet.debridxtreamiptv.ui.sources.SourceFilterUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * MD-3: the Debrid + IPTV **source-acquisition** concern lifted out of [MovieDetailActivity] — the
 * direct analogue of [com.tvonnet.debridxtreamiptv.ui.series.SeriesDebridSourceController] (SD-4b),
 * adapted from per-episode maps to this screen's single-movie flat state. It owns the source state
 * (the `debridSources`/`allSources`/filter fields that were Activity fields) and everything that
 * produces / filters / refreshes the source list: the inline source list + its language/size/cached
 * filters, the source bottom-sheet, the cross-source IPTV aggregation, the MediaFusion cache-status
 * sweep, and the "RD summary" header.
 *
 * The Activity's playback launch code (MD-4, still in the Activity) reads/mutates this state through
 * the controller's public members (kept at the original field/method names so the relocation is a
 * mechanical accessor rebind), and the chosen source flows back via [onPlayMovie] / [onPlayDebridMovie]
 * — the exact `sourceType == "IPTV"` branch that lived in `onSourceSelected` / `applySelectedSource`.
 *
 * Behaviour byte-identical to the old private methods; only `this`/field refs were rebound to
 * [activity], the injected getters and the passed-in views/adapters.
 */
class MovieDebridSourceController(
    private val activity: AppCompatActivity,
    private val unifiedSourceProvider: UnifiedSourceProvider,
    private val debridPlaybackRepository: DebridPlaybackRepository,
    private val repository: XtreamRepository,
    private val credentialsPrefs: CredentialsPreferences,
    private val tvSourcesHeader: TextView,
    private val tvSourcesStatus: TextView,
    private val rvSources: RecyclerView,
    private val layoutSourceFilters: View,
    private val btnCachedOnly: TextView,
    private val rvLanguageFilters: RecyclerView,
    private val btnPlay: Button,
    private val layoutRdSummary: View,
    private val tvRdSummaryText: TextView,
    private val tvQualityBadge: TextView,
    private val movieId: () -> String?,
    private val movieName: () -> String?,
    private val movieCategoryId: () -> String?,
    private val movieYear: () -> String?,
    private val movieBackdrop: () -> String?,
    private val movieContainer: () -> String?,
    private val currentImdbId: () -> String?,
    private val onPlayMovie: (MovieSource?) -> Unit,
    private val onPlayDebridMovie: (XtreamVodInfo?, MovieSource?, Boolean) -> Unit,
) {
    // Source state (single source of truth; MD-4 playback reads/mutates these).
    var selectedSource: MovieSource? = null
    var sourcesLoadJob: Job? = null
    var debridSources: List<MovieSource> = emptyList()
    var allSources: List<MovieSource> = emptyList()
    var preferredLanguage: String? = null
    var selectedSizeOption: SizeFilterOption = SourceFilterUtils.SIZE_OPTIONS.first()
    var filterState: SourceFilterState = SourceFilterState()
    var debridFilterState: SourceFilterState = SourceFilterState()
    var selectedDebridStreamId: String? = null
    val failedDebridStreamIds = linkedSetOf<String>()
    var pendingDebridReturnFocusStreamIds: List<String> = emptyList()

    lateinit var sourcesAdapter: MovieSourceAdapter
        private set
    private lateinit var sizeFilterAdapter: SizeFilterAdapter
    private lateinit var languageFilterAdapter: LanguageFilterAdapter

    /** Builds the source list + filter adapters and wires their RecyclerViews / cached-only pill. */
    fun setupSourceViews() {
        sourcesAdapter = MovieSourceAdapter(
            onSourceFocused = { source -> applySelectedSource(source, shouldPlay = false, updateAdapterSelection = false) },
            onSourceClicked = { source -> applySelectedSource(source, shouldPlay = true) },
            onNavigateUpFromFirstRow = {
                (btnCachedOnly.visibility == View.VISIBLE && btnCachedOnly.requestFocus()) ||
                    rvLanguageFilters.requestFocus()
            }
        )
        rvSources.apply {
            layoutManager = LinearLayoutManager(activity)
            adapter = sourcesAdapter
            setHasFixedSize(true)
            isFocusable = true
            isFocusableInTouchMode = true
        }
        sizeFilterAdapter = SizeFilterAdapter { option ->
            selectedSizeOption = option
            sizeFilterAdapter.updateSelection(option)
            filterState = filterState.copy(maxSizeBytes = option.maxSizeBytes)
            applySourceFilters()
        }

        languageFilterAdapter = LanguageFilterAdapter { language ->
            preferredLanguage = if (preferredLanguage == language) null else language
            languageFilterAdapter.updateSelection(preferredLanguage)
            applySourceFilters()
        }
        rvLanguageFilters.layoutManager = LinearLayoutManager(activity, LinearLayoutManager.HORIZONTAL, false)
        rvLanguageFilters.setHasFixedSize(true)
        rvLanguageFilters.adapter = languageFilterAdapter

        btnCachedOnly.setOnClickListener {
            filterState = filterState.copy(cachedOnly = !filterState.cachedOnly)
            btnCachedOnly.isSelected = filterState.cachedOnly
            applySourceFilters()
        }
        btnCachedOnly.setOnFocusChangeListener { _, hasFocus ->
            btnCachedOnly.isSelected = hasFocus || filterState.cachedOnly
        }
    }

    fun loadMovieSources(imdbId: String?) {
        tvSourcesHeader.visibility = View.VISIBLE
        tvSourcesStatus.visibility = View.VISIBLE
        tvSourcesStatus.text = activity.getString(R.string.movie_detail_sources_loading)
        rvSources.visibility = View.GONE
        layoutSourceFilters.visibility = View.GONE
        selectedSource = null
        sourcesAdapter.updateSelection(null, notify = false)
        resetSourceFilters()
        debridSources = emptyList()

        sourcesLoadJob?.cancel()
        sourcesLoadJob = activity.lifecycleScope.launch {
            try {
                val sources = withContext(Dispatchers.IO) {
                    // Use UnifiedSourceProvider for all source resolution
                    unifiedSourceProvider.getMovieSources(
                        streamId = movieId(),
                        title = movieName(),
                        primaryCategoryId = movieCategoryId(),
                        yearHint = movieYear(),
                        imdbId = imdbId
                    )
                }
                if (sources.isEmpty()) {
                    sourcesAdapter.submitList(emptyList())
                    tvSourcesHeader.visibility = View.VISIBLE
                    tvSourcesStatus.visibility = View.VISIBLE
                    tvSourcesStatus.text = activity.getString(R.string.movie_detail_sources_empty)
                    rvSources.visibility = View.GONE
                    layoutSourceFilters.visibility = View.GONE
                } else {
                    tvSourcesHeader.visibility = View.VISIBLE
                    allSources = sources

                    // TASK 020: Update Summary
                    updateRdSummary(sources)

                    updateSourceFilterOptions()
                    applySourceFilters(isInitialLoad = true)
                }
            } catch (e: Exception) {
                android.util.Log.e("MovieDetailActivity", "Failed to load movie sources", e)
                sourcesAdapter.submitList(emptyList())
                tvSourcesHeader.visibility = View.VISIBLE
                tvSourcesStatus.visibility = View.VISIBLE
                tvSourcesStatus.text = activity.getString(R.string.movie_detail_sources_empty)
                rvSources.visibility = View.GONE
                layoutSourceFilters.visibility = View.GONE
            }
        }
    }

    private fun resetSourceFilters() {
        allSources = emptyList()
        preferredLanguage = null
        selectedSizeOption = SourceFilterUtils.SIZE_OPTIONS.first()
        filterState = SourceFilterState()
        languageFilterAdapter.updateSelection(null)
        sizeFilterAdapter.updateSelection(selectedSizeOption)
    }

    private fun updateSourceFilterOptions() {
        val languages = allSources.flatMap { it.languages ?: listOf("multi") }
            .distinct()
            .sorted()

        val showLanguages = languages.size > 1
        rvLanguageFilters.isVisible = showLanguages
        if (showLanguages) {
            languageFilterAdapter.submitList(languages)
        } else {
            preferredLanguage = null
            languageFilterAdapter.updateSelection(null)
            languageFilterAdapter.submitList(emptyList())
        }

        val showSizes = allSources.any { it.sizeBytes != null }

        if (showSizes) {
            sizeFilterAdapter.submitList(SourceFilterUtils.SIZE_OPTIONS)
            sizeFilterAdapter.updateSelection(selectedSizeOption)
        } else {
            selectedSizeOption = SourceFilterUtils.SIZE_OPTIONS.first()
            filterState = filterState.copy(maxSizeBytes = null)
            sizeFilterAdapter.updateSelection(null)
            sizeFilterAdapter.submitList(emptyList())
        }

        val showCached = allSources.any { SourceFilterUtils.hasCacheConfidence(it) }
        btnCachedOnly.isVisible = showCached
        if (!showCached) {
            filterState = filterState.copy(cachedOnly = false)
            btnCachedOnly.isSelected = false
        } else {
            btnCachedOnly.isSelected = filterState.cachedOnly
        }

        layoutSourceFilters.isVisible = showCached || showSizes || showLanguages
    }

    private fun applySourceFilters(isInitialLoad: Boolean = false) {
        val updatedState = filterState.copy(preferredLanguage = preferredLanguage)
        val filteredSources = SourceFilterUtils.apply(allSources, updatedState)
        sourcesAdapter.submitList(filteredSources) {
            if (isInitialLoad) {
                btnPlay.requestFocus()
            }
        }

        val hasSources = filteredSources.isNotEmpty()
        rvSources.isVisible = hasSources
        tvSourcesStatus.isVisible = !hasSources
        if (!hasSources) {
            tvSourcesStatus.text = activity.getString(R.string.source_filters_empty)
        }
    }

    fun showDebridSourcePicker(returnFocusStreamIds: List<String> = emptyList()) {
        val bottomSheet = SourceSelectionBottomSheet(
            onSourceSelected = { source, adjacentStreamIds ->
                selectedDebridStreamId = source.stream.stream_id
                pendingDebridReturnFocusStreamIds = adjacentStreamIds
                PlaybackDiagnosticsRecorder.record(
                    activity,
                    "source_selected",
                    PlaybackDiagnosticsRecorder.contentFields(
                        kind = "movie",
                        tmdbId = movieId()?.takeIf { it.toIntOrNull() != null },
                        imdbId = currentImdbId()
                    ) + PlaybackDiagnosticsRecorder.sourceFields(activity, source)
                )
                if (source.sourceType == "IPTV") {
                    // Xtream listing: play the provider URL directly, no debrid resolve.
                    onPlayMovie(source)
                } else {
                    onPlayDebridMovie(source.stream, source, true)
                }
            },
            onStateChanged = { state ->
                debridFilterState = state
            },
            initialState = debridFilterState,
            initialSelectedStreamId = selectedDebridStreamId,
            initialReturnFocusStreamIds = returnFocusStreamIds,
            contentTitle = movieName(),
            backdropUrl = movieBackdrop(),
            preferredAudioLang = credentialsPrefs.preferredAudioLang
        )

        bottomSheet.show(activity.supportFragmentManager, SourceSelectionBottomSheet.TAG)

        if (debridSources.isNotEmpty()) {
            PlaybackDiagnosticsRecorder.record(
                activity,
                "source_list_loaded",
                PlaybackDiagnosticsRecorder.contentFields(
                    kind = "movie",
                    tmdbId = movieId()?.takeIf { it.toIntOrNull() != null },
                    imdbId = currentImdbId()
                ) + PlaybackDiagnosticsRecorder.sourceListFields(activity, debridSources)
            )
            bottomSheet.showSources(debridSources)
            refreshMediaFusionCacheStatus(debridSources) { updated ->
                debridSources = updated
                if (bottomSheet.isAdded) {
                    bottomSheet.showSources(updated)
                }
            }
            return
        }

        bottomSheet.showLoading()
        activity.lifecycleScope.launch {
            try {
                val sources = withContext(Dispatchers.IO) {
                    val debrid = unifiedSourceProvider.getMovieSources(
                        streamId = movieId(),
                        title = movieName(),
                        primaryCategoryId = movieCategoryId(),
                        yearHint = movieYear(),
                        imdbId = currentImdbId()
                    )
                    // Like the series stream panel: surface the same movie's IPTV (Xtream)
                    // listings alongside the debrid sources, badged "IPTV".
                    val iptv = try {
                        fetchIptvMovieSources(movieName())
                    } catch (e: Exception) {
                        android.util.Log.w("MovieDetailActivity", "IPTV source lookup failed: ${e.message}")
                        emptyList()
                    }
                    debrid + iptv
                }
                debridSources = sources
                PlaybackDiagnosticsRecorder.record(
                    activity,
                    "source_list_loaded",
                    PlaybackDiagnosticsRecorder.contentFields(
                        kind = "movie",
                        tmdbId = movieId()?.takeIf { it.toIntOrNull() != null },
                        imdbId = currentImdbId()
                    ) + PlaybackDiagnosticsRecorder.sourceListFields(activity, sources)
                )
                if (sources.isEmpty()) {
                    bottomSheet.showError(activity.getString(R.string.movie_detail_sources_empty))
                } else {
                    // Update summary on main page as well
                    updateRdSummary(sources)

                    bottomSheet.showSources(sources)
                    refreshMediaFusionCacheStatus(sources) { updated ->
                        debridSources = updated
                        updateRdSummary(updated)
                        if (bottomSheet.isAdded) {
                            bottomSheet.showSources(updated)
                        }
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("MovieDetailActivity", "Failed to load movie sources", e)
                bottomSheet.showError(e.message ?: activity.getString(R.string.movie_detail_sources_empty))
            }
        }
    }

    /**
     * IPTV (Xtream) listings of this movie for the source picker. Delegates to the shared
     * [com.tvonnet.debridxtreamiptv.data.debrid.repository.IptvMovieMatcher] (also used by
     * the in-player Sources panel) so both surfaces match identically.
     */
    private suspend fun fetchIptvMovieSources(title: String?): List<MovieSource> {
        val query = com.tvonnet.debridxtreamiptv.data.debrid.repository.IptvMovieMatcher
            .searchQuery(title) ?: return emptyList()
        return com.tvonnet.debridxtreamiptv.data.debrid.repository.IptvMovieMatcher
            .buildSources(repository.searchVod(query), title, movieYear())
    }

    fun consumeDebridReturnFocusStreamIds(): List<String> {
        val streamIds = pendingDebridReturnFocusStreamIds
        pendingDebridReturnFocusStreamIds = emptyList()
        return streamIds
    }

    /**
     * The bare UnifiedSourceProvider movie-source fetch (same args as [loadMovieSources]'s inner
     * call). MD-4's auto-failover uses it to re-fetch when the in-memory [debridSources] is empty.
     * Suspends; call inside `withContext(Dispatchers.IO)` at the call site as before.
     */
    suspend fun fetchMovieSourcesFromProvider(): List<MovieSource> =
        unifiedSourceProvider.getMovieSources(
            streamId = movieId(),
            title = movieName(),
            primaryCategoryId = movieCategoryId(),
            yearHint = movieYear(),
            imdbId = currentImdbId()
        )

    fun updateRdSummary(sources: List<MovieSource>) {
        if (sources.isEmpty()) {
            layoutRdSummary.visibility = View.GONE
            return
        }

        val cachedCount = sources.count {
            it.cacheStatus == DebridCacheStatus.VERIFIED_CACHED || it.cacheStatus == DebridCacheStatus.DIRECT_STREAM
        }
        val bestQuality = sources.mapNotNull { it.quality }.distinct()
            .sortedByDescending { q ->
                when (q.uppercase()) {
                    "4K" -> 100
                    "2160P" -> 99
                    "1080P" -> 80
                    "720P" -> 60
                    else -> 0
                }
            }.firstOrNull() ?: "HD"

        val bestSize = sources.filter { it.quality?.uppercase() in listOf("4K", "2160P") }
            .mapNotNull { it.sizeBytes }
            .maxOrNull()
            ?.let { formatSizeLabel(it) }

        layoutRdSummary.visibility = View.VISIBLE
        val summary = buildString {
            append("${sources.size} SOURCES FOUND")
            append(" · $cachedCount CACHED")
            append(" · BEST $bestQuality")
            if (bestSize != null) append(" $bestSize")
        }
        tvRdSummaryText.text = summary

        // Update quality badge
        tvQualityBadge.text = bestQuality.uppercase()
        tvQualityBadge.visibility = View.VISIBLE
    }

    fun applySelectedSource(
        source: MovieSource,
        shouldPlay: Boolean,
        updateAdapterSelection: Boolean = true
    ) {
        selectedSource = source
        sourcesAdapter.updateSelection(source.stream.stream_id, notify = updateAdapterSelection)
        if (shouldPlay) {
            onPlayMovie(source)
        }
    }

    fun currentStream() = selectedSource?.stream

    fun currentStreamId(): String? = currentStream()?.stream_id ?: movieId()

    fun currentContainerExtension(): String? =
        currentStream()?.container_extension ?: movieContainer()

    fun currentStreamTitle(): String? = currentStream()?.name ?: movieName()

    fun pickNextDebridSource(
        sources: List<MovieSource>,
        failedStreamId: String
    ): MovieSource? {
        val filteredSources = SourceFilterUtils.apply(sources, debridFilterState)
            .filterNot { source -> source.stream.stream_id?.let { it in failedDebridStreamIds } == true }
        if (filteredSources.isEmpty()) return null

        val failedIndex = filteredSources.indexOfFirst { it.stream.stream_id == failedStreamId }
        val nextIndex = when {
            failedIndex in 0 until filteredSources.lastIndex -> failedIndex + 1
            failedIndex == -1 -> 0
            else -> -1
        }
        return if (nextIndex >= 0) filteredSources[nextIndex] else null
    }

    fun refreshMediaFusionCacheStatus(
        sources: List<MovieSource>,
        onUpdated: (List<MovieSource>) -> Unit
    ) {
        val mediaFusionSources = sources.filter { source ->
            val url = source.stream.direct_source
            !url.isNullOrBlank() && isAddonProxyPlaybackUrl(url)
        }
        if (mediaFusionSources.isEmpty()) return

        activity.lifecycleScope.launch {
            val updates = withContext(Dispatchers.IO) {
                val semaphore = Semaphore(4)
                coroutineScope {
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
                                    (result as? Result.Success)?.data ?: AddonProxyReadiness.UNCERTAIN
                                source.stream.stream_id to readiness
                            }
                        }
                    }.awaitAll().filterNotNull().toMap()
                }
            }
            if (updates.isEmpty()) return@launch

            val updatedSources = sources.map { source ->
                when (updates[source.stream.stream_id]) {
                    AddonProxyReadiness.READY -> if (source.cacheStatus != DebridCacheStatus.DIRECT_STREAM) {
                        source.copy(
                            isCached = true,
                            cacheStatus = DebridCacheStatus.DIRECT_STREAM
                        )
                    } else {
                        source
                    }
                    AddonProxyReadiness.TERMINAL -> {
                        source.stream.stream_id?.let { failedDebridStreamIds.add(it) }
                        if (source.cacheStatus != DebridCacheStatus.NOT_CACHED) {
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
            if (updatedSources != sources) {
                onUpdated(updatedSources)
            }
        }
    }

    fun markDebridSourceCached(streamId: String?, isCached: Boolean) {
        markDebridSourceStatus(
            streamId,
            if (isCached) DebridCacheStatus.DIRECT_STREAM else DebridCacheStatus.NOT_CACHED
        )
    }

    fun markDebridSourceStatus(streamId: String?, cacheStatus: DebridCacheStatus) {
        if (streamId.isNullOrBlank() || debridSources.isEmpty()) return
        debridSources = debridSources.map { source ->
            if (source.stream.stream_id == streamId) {
                source.copy(
                    isCached = cacheStatus != DebridCacheStatus.NOT_CACHED,
                    cacheStatus = cacheStatus
                )
            } else {
                source
            }
        }
    }

    fun isAddonProxyPlaybackUrl(url: String): Boolean {
        return url.contains("mediafusion.elfhosted.com/playback", ignoreCase = true) ||
               url.contains("aiostreams.elfhosted.com", ignoreCase = true)
    }
}
