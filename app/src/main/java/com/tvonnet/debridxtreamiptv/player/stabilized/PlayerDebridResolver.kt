package com.tvonnet.debridxtreamiptv.player.stabilized

import com.tvonnet.debridxtreamiptv.data.debrid.repository.IptvMovieMatcher
import com.tvonnet.debridxtreamiptv.data.debrid.repository.PlaybackResolver
import com.tvonnet.debridxtreamiptv.data.debrid.repository.ResolutionResult
import com.tvonnet.debridxtreamiptv.data.debrid.repository.UnifiedSourceProvider
import com.tvonnet.debridxtreamiptv.data.repository.MovieSource
import com.tvonnet.debridxtreamiptv.data.repository.XtreamRepository
import com.tvonnet.debridxtreamiptv.ui.sources.SourceFilterUtils
import com.tvonnet.debridxtreamiptv.util.SensitiveLogRedactor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * C10: turning a chosen debrid source into a URL the player can open.
 *
 * This is the debrid-resume path, which has a history: a source that resolves to the wrong link,
 * or re-resolves when it should not, is what makes playback start from the beginning or fail on a
 * retry. Three behaviours here exist because of that and must survive any future edit:
 *
 *  - **Direct passthrough is opt-in per call.** A stored URL may have expired, so history/resume
 *    passes `allowDirectHttpPassthrough = false` and forces a fresh resolve; live selection passes
 *    true and plays the URL it already has.
 *  - **`stream_id` is not stable.** It carries a volatile `_<list index>` suffix, so the saved id
 *    rarely matches verbatim across fetches. [pickSourceForContinuity] falls back to comparing the
 *    stable part, which is what makes resume re-pick the same source.
 *  - **Language outranks provider and quality when the exact source is gone.** Ranking is
 *    restricted to candidates sharing a requested language first; otherwise a failover silently
 *    changes the audio track, which is the most noticeable thing a viewer can be handed.
 *
 * Bodies moved verbatim from `PlayerViewModel`.
 */
internal class PlayerDebridResolver(
    private val repository: XtreamRepository,
    private val unifiedSourceProvider: UnifiedSourceProvider,
    private val playbackResolver: PlaybackResolver,
    private val scope: CoroutineScope,
) {
    private val _debridResolutionState = MutableStateFlow<DebridResolutionState>(DebridResolutionState.Idle)
    val debridResolutionState: StateFlow<DebridResolutionState> = _debridResolutionState.asStateFlow()

    fun setLoading() {
        _debridResolutionState.value = DebridResolutionState.Loading
    }

    fun setError(message: String) {
        _debridResolutionState.value = DebridResolutionState.Error(message)
    }

    // ── source lists ───────────────────────────────────────────────────────────

    /** Debrid sources plus the movie's IPTV listings, as the detail picker shows them. */
    suspend fun fetchMovieSourcesForPanel(
        streamId: String?,
        title: String?,
        imdbId: String?,
        cleanTitle: String?
    ): List<MovieSource> = withContext(Dispatchers.IO) {
        if (streamId.isNullOrBlank() && imdbId.isNullOrBlank() && title.isNullOrBlank()) {
            return@withContext emptyList()
        }
        val debrid = runCatching {
            unifiedSourceProvider.getMovieSources(
                streamId = streamId,
                title = title,
                primaryCategoryId = "debrid",
                yearHint = null,
                imdbId = imdbId
            )
        }.getOrDefault(emptyList())
        val iptv = runCatching {
            val q = IptvMovieMatcher.searchQuery(cleanTitle ?: title)
            if (q != null) {
                IptvMovieMatcher.buildSources(repository.searchVod(q), cleanTitle ?: title, null)
            } else emptyList()
        }.getOrDefault(emptyList())
        debrid + iptv
    }

    suspend fun getLanguageOptions(
        streamId: String?,
        title: String?,
        imdbId: String?,
        sourceProfile: DebridSourceProfile?
    ): List<String> = withContext(Dispatchers.IO) {
        val sources = unifiedSourceProvider.getMovieSources(
            streamId = streamId,
            title = title,
            primaryCategoryId = "debrid",
            yearHint = null,
            imdbId = imdbId
        )
        val languageSet = linkedSetOf<String>()
        sourceProfile?.languages.orEmpty().forEach { languageSet.add(it) }
        sources.forEach { source -> source.languages.orEmpty().forEach { languageSet.add(it) } }
        languageSet.map { it.trim() }.filter { it.isNotBlank() }.distinct()
    }

    // ── refresh / failover ─────────────────────────────────────────────────────

    /**
     * Re-pick and re-resolve a movie's source, honouring what was playing.
     *
     * [excludeSourceIds] carries the sources that already failed this session, so an
     * error-triggered refresh advances instead of re-resolving the same broken one.
     */
    @Suppress("LongParameterList") // mirrors the public ViewModel API callers depend on
    fun refreshMovieSource(
        streamId: String?,
        title: String?,
        imdbId: String?,
        sourceProfile: DebridSourceProfile?,
        allowDirectHttpPassthrough: Boolean = true,
        excludeSourceIds: Set<String> = emptySet()
    ) {
        scope.launch {
            _debridResolutionState.value = DebridResolutionState.Loading
            if (streamId.isNullOrBlank() && imdbId.isNullOrBlank() && title.isNullOrBlank()) {
                _debridResolutionState.value = DebridResolutionState.Error("Missing metadata to refresh direct source.")
                return@launch
            }
            try {
                val savedId = sourceProfile?.streamId
                val sources = withContext(Dispatchers.IO) {
                    unifiedSourceProvider.getMovieSources(
                        streamId = streamId,
                        title = title,
                        primaryCategoryId = "debrid",
                        yearHint = null,
                        imdbId = imdbId,
                        priorityInfoHash = savedId.takeUnless { it in excludeSourceIds },
                        preferredSourceType = sourceProfile?.sourceType
                    )
                }
                if (sources.isEmpty()) {
                    _debridResolutionState.value = DebridResolutionState.Error("No fresh movie sources found.")
                    return@launch
                }
                resolveMovieSource(
                    targetSource = pickSourceForContinuity(sources, savedId, sourceProfile, excludeSourceIds),
                    season = null,
                    episode = null,
                    title = title,
                    directPreferred = sourceProfile?.directPlayback == true,
                    allowDirectHttpPassthrough = allowDirectHttpPassthrough
                )
            } catch (e: Exception) {
                _debridResolutionState.value = DebridResolutionState.Error("Failed to refresh source: ${e.message}")
            }
        }
    }

    // ── resolution ─────────────────────────────────────────────────────────────

    @Suppress("LongParameterList") // mirrors the public ViewModel API callers depend on
    suspend fun resolveMovieSource(
        targetSource: MovieSource,
        season: Int?,
        episode: Int?,
        title: String?,
        directPreferred: Boolean,
        allowDirectHttpPassthrough: Boolean,
        forceFresh: Boolean = true
    ) {
        val magnet = targetSource.stream.direct_source
        val hashToResolve = targetSource.stream.stream_id
        val direct = directPreferred && DebridSourceScoring.isDirectPlayableUrl(magnet)
        val profile = DebridSourceScoring.profileOf(targetSource, direct)

        if (allowDirectHttpPassthrough && directPreferred && DebridSourceScoring.isDirectPlayableUrl(magnet)) {
            _debridResolutionState.value = DebridResolutionState.Success(
                url = magnet!!,
                season = season,
                episode = episode,
                title = title,
                infoHash = hashToResolve,
                magnet = magnet,
                headers = targetSource.headers,
                directDebridPlayback = true,
                sourceProfile = profile
            )
            return
        }

        if (magnet.isNullOrBlank() && hashToResolve.isNullOrBlank()) {
            _debridResolutionState.value = DebridResolutionState.Error("Invalid source data.")
            return
        }

        val result = withContext(Dispatchers.IO) {
            playbackResolver.resolve(
                source = "debrid",
                streamUrl = null,
                // forceFresh bypasses the resolved-link cache (failure refresh);
                // a fresh next-episode load may hit a pre-warmed cached link.
                isExpired = forceFresh,
                infoHash = hashToResolve,
                magnet = magnet,
                seasonNumber = season,
                episodeNumber = episode,
                episodeTitle = title,
                allowDirectHttpPassthrough = allowDirectHttpPassthrough
            )
        }

        _debridResolutionState.value = when (result) {
            is ResolutionResult.Success -> DebridResolutionState.Success(
                url = result.url,
                season = season,
                episode = episode,
                title = title,
                infoHash = hashToResolve,
                magnet = magnet,
                headers = targetSource.headers,
                directDebridPlayback = false,
                sourceProfile = DebridSourceScoring.profileOf(targetSource, directPlayback = false)
            )
            is ResolutionResult.RefreshRequired ->
                DebridResolutionState.Error("Refresh required to play this episode.")
            is ResolutionResult.Error -> DebridResolutionState.Error(result.message)
        }
    }

    /** Retry path: always re-resolves, never trusts a cached link. */
    @Suppress("LongParameterList") // mirrors the public ViewModel API callers depend on
    fun reResolveUrl(
        infoHash: String?,
        magnet: String?,
        season: Int?,
        episode: Int?,
        title: String?,
        allowDirectHttpPassthrough: Boolean = true
    ) {
        android.util.Log.d(
            "PlayerViewModel",
            "reResolveDebridUrl: infoHash=${SensitiveLogRedactor.describeHash(infoHash)}, magnet=${SensitiveLogRedactor.describeUrl(magnet)}, season=$season, episode=$episode, hasTitle=${!title.isNullOrBlank()}"
        )
        scope.launch {
            _debridResolutionState.value = DebridResolutionState.Loading
            try {
                val result = withContext(Dispatchers.IO) {
                    playbackResolver.resolve(
                        source = "debrid",
                        streamUrl = null,
                        isExpired = true, // Force re-resolution on retry
                        infoHash = infoHash,
                        magnet = magnet,
                        seasonNumber = season,
                        episodeNumber = episode,
                        episodeTitle = title,
                        allowDirectHttpPassthrough = allowDirectHttpPassthrough
                    )
                }
                _debridResolutionState.value = when (result) {
                    is ResolutionResult.Success -> DebridResolutionState.Success(
                        url = result.url,
                        season = season,
                        episode = episode,
                        title = title,
                        infoHash = infoHash
                    )
                    is ResolutionResult.RefreshRequired ->
                        DebridResolutionState.Error("Retry failed: Refresh required.")
                    is ResolutionResult.Error -> DebridResolutionState.Error(result.message)
                }
            } catch (e: Exception) {
                _debridResolutionState.value = DebridResolutionState.Error("Failed to re-resolve: ${e.message}")
            }
        }
    }

    // ── which source to play ───────────────────────────────────────────────────

    fun pickSourceForContinuity(
        sources: List<MovieSource>,
        infoHash: String?,
        profile: DebridSourceProfile?,
        excludeSourceIds: Set<String> = emptySet()
    ): MovieSource {
        val candidates = withoutFailedSources(sources, excludeSourceIds)

        val exactId = profile?.streamId ?: infoHash
        candidates.firstOrNull { source ->
            !exactId.isNullOrBlank() && source.stream.stream_id.equals(exactId, ignoreCase = true)
        }?.let { return it }

        // stream_id carries a volatile "_<list index>" suffix, so the saved id
        // rarely matches verbatim across fetches. Compare on the stable part
        // (bare infoHash / title hash) so resume re-picks the same source.
        DebridSourceScoring.stableIdentity(exactId)?.let { stableExactId ->
            candidates.firstOrNull { source ->
                DebridSourceScoring.stableIdentity(source.stream.stream_id) == stableExactId
            }?.let { return it }
        }

        return rankByContinuity(preferRequestedLanguage(candidates, profile), profile).first()
    }

    /**
     * Sources that already failed playback in this session are skipped so an error-triggered
     * refresh moves on. If exclusion would leave nothing, fall back to the full list rather than
     * failing hard.
     */
    private fun withoutFailedSources(sources: List<MovieSource>, excludeSourceIds: Set<String>): List<MovieSource> {
        val excludedStableIds = excludeSourceIds.mapNotNull { DebridSourceScoring.stableIdentity(it) }.toSet()
        if (excludedStableIds.isEmpty()) return sources
        return sources.filterNot { source ->
            DebridSourceScoring.stableIdentity(source.stream.stream_id) in excludedStableIds
        }.ifEmpty { sources }
    }

    /**
     * When the exact source is gone (or excluded after a failure), keep the user's audio language:
     * restrict ranking to candidates sharing a requested language (or multi-audio) when any exist,
     * instead of letting provider/quality outrank language.
     */
    private fun preferRequestedLanguage(
        candidates: List<MovieSource>,
        profile: DebridSourceProfile?
    ): List<MovieSource> {
        val requested = DebridSourceScoring.normalizeLanguageSet(profile?.languages)
        if (requested.isEmpty()) return candidates
        return candidates.filter { source ->
            val candidateLanguages = DebridSourceScoring.normalizeLanguageSet(source.languages)
            candidateLanguages.contains("multi") || candidateLanguages.intersect(requested).isNotEmpty()
        }.ifEmpty { candidates }
    }

    private fun rankByContinuity(pool: List<MovieSource>, profile: DebridSourceProfile?): List<MovieSource> =
        pool.sortedWith(
            compareByDescending<MovieSource> { DebridSourceScoring.continuityScore(it, profile) }
                .thenByDescending { SourceFilterUtils.isPlaybackReady(it) }
                .thenByDescending { DebridSourceScoring.qualityScore(it.quality) }
                .thenByDescending { it.seeders ?: -1 }
        )
}
