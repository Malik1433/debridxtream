package com.tvonnet.debridxtreamiptv.ui.series

import android.content.Intent
import android.widget.Toast
import androidx.activity.result.ActivityResult
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.tvonnet.debridxtreamiptv.R
import com.tvonnet.debridxtreamiptv.data.Result
import com.tvonnet.debridxtreamiptv.data.debrid.model.DebridFailureType
import com.tvonnet.debridxtreamiptv.data.debrid.repository.AddonProxyReadiness
import com.tvonnet.debridxtreamiptv.data.model.ContentType
import com.tvonnet.debridxtreamiptv.data.prefs.CredentialsPreferences
import com.tvonnet.debridxtreamiptv.data.repository.MovieSource
import com.tvonnet.debridxtreamiptv.data.repository.XtreamRepository
import com.tvonnet.debridxtreamiptv.debug.PlaybackDiagnosticsRecorder
import com.tvonnet.debridxtreamiptv.player.stabilized.PlayerActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * SD-4c: the Debrid **playback launch** concern lifted out of [SeriesDetailActivity]. Owns the
 * play/resume/failover flow for a series episode (direct-HTTP + resolver-backed debrid, injected
 * IPTV-from-debrid, Xtream IPTV, auto-advance to the next source) and the source-failover
 * accounting ([autoFallbackAttempts] / [openedFromPlaybackFailure]). Per-episode source *state*
 * lives in [SeriesDebridSourceController] (SD-4b) and is read/mutated through [sources].
 *
 * The `playerLauncher` `registerForActivityResult` stays REGISTERED IN THE ACTIVITY (its timing
 * before STARTED is load-bearing); the Activity forwards its callback to [handlePlayerResult] and
 * passes [launch] = `playerLauncher::launch`. Behaviour byte-identical to the old private methods;
 * only `this`/field refs were rebound to [activity], the injected deps and the getters.
 */
class SeriesDebridPlaybackController(
    private val activity: AppCompatActivity,
    private val seasonUi: SeriesSeasonUi,
    private val sources: SeriesDebridSourceController,
    private val repository: XtreamRepository,
    deps: Deps,
    host: Host,
) {
    /** The debrid/resolver collaborators the playback path resolves through. */
    data class Deps(
        val credentialsPreferences: CredentialsPreferences,
        val unifiedSourceProvider: com.tvonnet.debridxtreamiptv.data.debrid.repository.UnifiedSourceProvider,
        val debridPlaybackRepository: com.tvonnet.debridxtreamiptv.data.debrid.repository.DebridPlaybackRepository,
        val playbackResolver: com.tvonnet.debridxtreamiptv.data.debrid.repository.PlaybackResolver,
        val seriesRepositoryV2: com.tvonnet.debridxtreamiptv.features.seriesv2.data.repository.XtreamSeriesRepositoryV2,
    )

    /** Current-series accessors + loading/launch hand-offs back to the Activity. */
    data class Host(
        val isDebrid: () -> Boolean,
        val seriesId: () -> String?,
        val seriesName: () -> String?,
        val seriesCover: () -> String?,
        val seriesBackdrop: () -> String?,
        val seriesReleaseDate: () -> String?,
        val showLoading: (Boolean) -> Unit,
        val launch: (Intent) -> Unit,
    )

    private val credentialsPreferences = deps.credentialsPreferences
    private val unifiedSourceProvider = deps.unifiedSourceProvider
    private val debridPlaybackRepository = deps.debridPlaybackRepository
    private val playbackResolver = deps.playbackResolver
    private val seriesRepositoryV2 = deps.seriesRepositoryV2
    private val isDebrid = host.isDebrid
    private val seriesId = host.seriesId
    private val seriesName = host.seriesName
    private val seriesCover = host.seriesCover
    private val seriesBackdrop = host.seriesBackdrop
    private val seriesReleaseDate = host.seriesReleaseDate
    private val showLoading = host.showLoading
    private val launch = host.launch

    private companion object {
        const val MAX_AUTO_FALLBACK_ATTEMPTS = 3
    }

    // Consecutive automatic source-failover attempts; the picker resets it so a
    // bad night of sources degrades to manual choice instead of an endless chain.
    private var autoFallbackAttempts = 0
    var openedFromPlaybackFailure: Boolean = false

    // H2: same-file failover state (SourceAlternates) — retries the SAME file via
    // another addon's copy, silently, before the file is blacklisted.
    private val alternateFailover = com.tvonnet.debridxtreamiptv.ui.sources.AlternateFailover()

    /** Body of the Activity's `playerLauncher` ActivityResult callback (registration stays in the Activity). */
    fun handlePlayerResult(result: ActivityResult) {
        val data = result.data
        val shouldReturnToSources =
            result.resultCode == android.app.Activity.RESULT_OK &&
                data?.getBooleanExtra(PlayerActivity.EXTRA_RETURN_TO_SOURCES, false) == true
        if (shouldReturnToSources) {
            val autoPlayNext =
                data?.getBooleanExtra(PlayerActivity.EXTRA_AUTO_PLAY_NEXT, false) == true
            val failedStreamId = data?.getStringExtra(PlayerActivity.EXTRA_FAILED_STREAM_ID)
            val failReason = data?.getStringExtra(PlayerActivity.EXTRA_FAIL_REASON)
            val episode = sources.lastDebridEpisode
            if (retriedViaAlternate(failedStreamId, episode)) {
                return
            }
            val allowAutoPlayNext = autoPlayNext && !openedFromPlaybackFailure &&
                autoFallbackAttempts < MAX_AUTO_FALLBACK_ATTEMPTS
            // Only a REAL failure carries a failed-stream-id / reason / auto-play flag
            // (buildFailedSourceReturnIntent). A plain manual BACK or <60s exit
            // (buildReturnToSourcesIntent) carries none of these — showing "Playback
            // failed: source could not be played" there was a false alarm.
            if (!failedStreamId.isNullOrBlank() || !failReason.isNullOrBlank() || autoPlayNext) {
                notifyDebridFailure(failReason, allowAutoPlayNext)
            }
            if (allowAutoPlayNext && !failedStreamId.isNullOrBlank() && episode != null) {
                autoFallbackAttempts++
                autoPlayNextDebridEpisodeSource(episode, failedStreamId)
            } else {
                autoFallbackAttempts = 0
                alternateFailover.reset()
                sources.lastDebridEpisode?.let {
                    sources.fetchAndShowDebridSources(it, sources.consumeDebridReturnFocusStreamIds(it.id))
                }
            }
        }
    }

    // H2: before declaring the FILE dead, silently retry it via another addon's copy
    // (no toast, no blacklist, no auto-fallback budget spent). True = a replay was
    // launched; false = no failure to handle, or the copies ran out (then the file
    // is blacklisted here and the caller falls through to its normal advance).
    private fun retriedViaAlternate(failedStreamId: String?, episode: EpisodeUiModel?): Boolean {
        if (failedStreamId.isNullOrBlank() || episode == null) return false
        val alternate = alternateFailover.nextFor(
            failedStreamId,
            sources.cachedDebridSourcesByEpisode[episode.id].orEmpty()
        )
        if (alternate != null) {
            playDebridEpisode(alternate, episode)
            return true
        }
        sources.failedDebridStreamIdsByEpisode.getOrPut(episode.id) { linkedSetOf() }.add(failedStreamId)
        sources.markDebridEpisodeSourceCached(episode.id, failedStreamId, false)
        return false
    }

    /**
     * Resume button: play the in-progress episode directly instead of re-opening
     * the source list. Reuses the source picked last time when possible, otherwise
     * silently fetches sources and auto-picks the best cached one. The player
     * restores the saved position from watch history.
     */
    fun resumeDebridEpisode(episode: EpisodeUiModel) {
        val cached = sources.cachedDebridSourcesByEpisode[episode.id]
        if (!cached.isNullOrEmpty()) {
            val source = sources.pickResumeSource(episode, cached)
            if (source != null) {
                playDebridEpisode(source, episode)
                return
            }
        }

        val fetchContext = sources.resolveDebridEpisodeFetchContext(episode)
        if (fetchContext == null) {
            sources.fetchAndShowDebridSources(episode)
            return
        }

        showLoading(true)
        activity.lifecycleScope.launch {
            try {
                val (seasonNumber, episodeNumber) = fetchContext
                val fetched = withContext(Dispatchers.IO) {
                    unifiedSourceProvider.getSeriesEpisodeSources(
                        seriesId = seriesId(),
                        seasonNumber = seasonNumber,
                        episodeNumber = episodeNumber,
                        title = seriesName(),
                        yearHint = seriesReleaseDate()?.take(4)
                    )
                }
                sources.cachedDebridSourcesByEpisode[episode.id] = fetched
                showLoading(false)
                val source = sources.pickResumeSource(episode, fetched)
                if (source != null) {
                    playDebridEpisode(source, episode)
                } else {
                    sources.fetchAndShowDebridSources(episode)
                }
            } catch (e: Exception) {
                android.util.Log.e("SeriesDetailActivity", "Resume source fetch failed: ${e.message}", e)
                showLoading(false)
                sources.fetchAndShowDebridSources(episode)
            }
        }
    }

    fun playEpisode(episode: EpisodeUiModel) {
        if (isDebrid()) {
            sources.fetchAndShowDebridSources(episode)
            return
        }

        val serverUrl = credentialsPreferences.getServerUrl()
        val username = credentialsPreferences.getUsername()
        val password = credentialsPreferences.getPassword()

        if (serverUrl.isNullOrBlank() || username.isNullOrBlank() || password.isNullOrBlank()) {
            Toast.makeText(activity, R.string.series_detail_no_server_credentials, Toast.LENGTH_SHORT).show()
            return
        }

        val streamUrl = repository.buildSeriesEpisodeStreamUrl(
            episodeId = episode.id,
            containerExtension = episode.containerExtension,
            baseServerUrl = serverUrl
        )

        // Saved resume position for this episode (null when watched or never started).
        val resumeMs = seasonUi.resumePositionMs(episode.id)
        val intent = PlayerActivity.createIntent(
            context = activity,
            streamUrl = streamUrl,
            title = episode.title,
            contentId = episode.id,
            contentType = ContentType.EPISODE,
            posterUrl = seriesCover() ?: episode.thumbnailUrl,
            seriesTitle = seriesName(),
            episodeTitle = episode.title,
            seasonNumber = seasonUi.selectedSeasonKey?.toIntOrNull(),
            episodeNumber = episode.episodeNumber,
            startPositionMs = resumeMs
        )
        seriesId()?.let { intent.putExtra(PlayerActivity.EXTRA_SERIES_ID, it) }
        activity.startActivity(intent)
    }

    /** Play an injected IPTV source picked from the Debrid source sheet. */
    fun playIptvSourceFromDebrid(
        source: MovieSource,
        episode: EpisodeUiModel
    ) {
        val seasonNumber = seasonUi.selectedSeasonKey?.toIntOrNull()
        val episodeNumber = episode.episodeNumber
        val iptvSeriesId = source.stream.stream_id?.removePrefix("iptv:")

        activity.lifecycleScope.launch {
            var url = source.stream.direct_source
            var iptvEpisodeId = source.stream.custom_sid
            if (url.isNullOrBlank()) {
                if (iptvSeriesId.isNullOrBlank() || seasonNumber == null || episodeNumber == null) {
                    Toast.makeText(activity, activity.getString(R.string.c_stream_unavailable), Toast.LENGTH_SHORT).show()
                    return@launch
                }
                showLoading(true)
                val resolved = withContext(Dispatchers.IO) {
                    try {
                        seriesRepositoryV2.resolveIptvPlayUrl(iptvSeriesId, seasonNumber, episodeNumber)
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        android.util.Log.w("SeriesDetailActivity", "resolveIptvPlayUrl failed: ${e.message}")
                        null
                    }
                }
                showLoading(false)
                if (resolved == null) {
                    Toast.makeText(
                        activity, activity.getString(R.string.c_episode_not_available_in_this),
                        Toast.LENGTH_LONG
                    ).show()
                    return@launch
                }
                url = resolved.first
                iptvEpisodeId = resolved.third
            }

            // Saved resume position for this episode (null when watched or never started).
            val resumeMs = seasonUi.resumePositionMs(episode.id)

            val intent = PlayerActivity.createIntent(
                context = activity,
                streamUrl = url!!,
                title = episode.title,
                contentId = iptvEpisodeId ?: "iptv:$iptvSeriesId:$seasonNumber:$episodeNumber",
                contentType = ContentType.EPISODE,
                posterUrl = seriesCover() ?: episode.thumbnailUrl,
                backdropUrl = seriesBackdrop(),
                seriesTitle = seriesName(),
                episodeTitle = episode.title,
                seasonNumber = seasonNumber,
                episodeNumber = episodeNumber,
                startPositionMs = resumeMs
            )
            iptvSeriesId?.let { intent.putExtra(PlayerActivity.EXTRA_SERIES_ID, it) }
            activity.startActivity(intent)
        }
    }

    fun playDebridEpisode(source: MovieSource, episode: EpisodeUiModel) {
        sources.lastDebridEpisode = episode
        sources.selectedDebridStreamIdByEpisode[episode.id] = source.stream.stream_id
        val magnet = source.stream.direct_source
        val infoHash = source.stream.stream_id
        val isDirectHttp = magnet?.startsWith("http", ignoreCase = true) == true &&
            magnet.endsWith(".torrent", ignoreCase = true).not()

        if (isDirectHttp && !magnet.isNullOrBlank()) {
            playDirectHttpDebridEpisode(source, episode, magnet)
            return
        }

        resolveAndPlayDebridEpisode(source, episode, magnet, infoHash)
    }

    /** What actually goes into the player intent for a debrid episode launch. */
    private data class DebridEpisodeLaunch(
        val source: MovieSource,
        val episode: EpisodeUiModel,
        val streamUrl: String,
        val intentInfoHash: String?,
        val intentMagnet: String?,
        val intentHeaders: Map<String, String>?,
        val directDebridPlayback: Boolean,
        val launchPath: String,
    )

    private fun launchDebridEpisode(plan: DebridEpisodeLaunch) {
        val episode = plan.episode
        val seasonNumber = seasonUi.selectedSeasonKey?.toIntOrNull()
        val episodeNumber = episode.episodeNumber
        val tmdbId = seriesId()
        // Saved resume position for this episode (null when watched or never started)
        val resumeMs = seasonUi.resumePositionMs(episode.id)
        PlaybackDiagnosticsRecorder.record(
            activity,
            "playback_launch",
            PlaybackDiagnosticsRecorder.contentFields(
                kind = "series_episode",
                tmdbId = tmdbId,
                season = seasonNumber,
                episode = episodeNumber
            ) + PlaybackDiagnosticsRecorder.sourceFields(activity, plan.source) +
                PlaybackDiagnosticsRecorder.playbackFields(
                    context = activity,
                    url = plan.streamUrl,
                    headers = plan.source.headers,
                    playbackSource = com.tvonnet.debridxtreamiptv.player.stabilized.PlaybackSource.DEBRID.name,
                    directDebridPlayback = plan.directDebridPlayback
                ) + mapOf("launchPath" to plan.launchPath)
        )
        val intent = PlayerActivity.createIntent(
            context = activity,
            streamUrl = plan.streamUrl,
            title = "${seriesName()} - S${seasonUi.selectedSeasonKey}E${episode.episodeNumber}",
            contentId = "${seriesId()}:$seasonNumber:$episodeNumber",
            contentType = ContentType.EPISODE,
            playbackSource = com.tvonnet.debridxtreamiptv.player.stabilized.PlaybackSource.DEBRID,
            posterUrl = seriesCover() ?: episode.thumbnailUrl,
            backdropUrl = seriesBackdrop(),
            headers = plan.intentHeaders,
            tmdbId = tmdbId,
            seriesTitle = seriesName(),
            episodeTitle = episode.title,
            seasonNumber = seasonNumber,
            episodeNumber = episodeNumber,
            debridInfoHash = plan.intentInfoHash,
            debridMagnet = plan.intentMagnet,
            directDebridPlayback = plan.directDebridPlayback,
            debridProvider = plan.source.provider,
            debridSourceType = plan.source.sourceType,
            debridSourceName = plan.source.sourceName,
            debridLanguages = plan.source.languages,
            debridQuality = plan.source.quality,
            debridStreamId = plan.source.stream.stream_id,
            debridBingeGroup = plan.source.bingeGroup,
            debridFileIdx = plan.source.fileIdx,
            startPositionMs = resumeMs
        )
        intent.putExtra(PlayerActivity.EXTRA_RETURN_TO_SOURCES, true)
        launch(intent)
    }

    private fun playDirectHttpDebridEpisode(
        source: MovieSource,
        episode: EpisodeUiModel,
        directUrl: String
    ) {
        val plan = DebridEpisodeLaunch(
            source = source,
            episode = episode,
            streamUrl = directUrl,
            intentInfoHash = null,
            intentMagnet = null,
            intentHeaders = source.headers,
            directDebridPlayback = true,
            launchPath = "direct",
        )

        if (debridPlaybackRepository.requiresDirectProxyReadinessCheck(
                directUrl,
                source.provider,
                source.sourceName,
                source.sourceType
            )
        ) {
            activity.lifecycleScope.launch {
                showLoading(true)
                val readyResult = withContext(Dispatchers.IO) {
                    debridPlaybackRepository.getAddonProxyPlaybackReadiness(
                        directUrl,
                        source.headers,
                        source.provider,
                        source.sourceName,
                        source.sourceType,
                        diagnosticsContext = activity
                    )
                }
                showLoading(false)

                val readiness = (readyResult as? Result.Success)?.data
                    ?: AddonProxyReadiness.UNCERTAIN
                if (readiness == AddonProxyReadiness.TERMINAL) {
                    source.stream.stream_id?.let { streamId ->
                        sources.failedDebridStreamIdsByEpisode.getOrPut(episode.id) { linkedSetOf() }.add(streamId)
                    }
                    sources.markDebridEpisodeSourceCached(episode.id, source.stream.stream_id, false)
                    val message = "Source is unavailable. Choose another source."
                    Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
                    sources.fetchAndShowDebridSources(episode, sources.consumeDebridReturnFocusStreamIds(episode.id))
                    return@launch
                }

                if (readiness == AddonProxyReadiness.READY) {
                    sources.markDebridEpisodeSourceCached(episode.id, source.stream.stream_id, true)
                }
                launchDebridEpisode(plan)
            }
            return
        }

        launchDebridEpisode(plan)
    }

    private fun resolveAndPlayDebridEpisode(
        source: MovieSource,
        episode: EpisodeUiModel,
        magnet: String?,
        infoHash: String?
    ) {
        val seasonNumber = seasonUi.selectedSeasonKey?.toIntOrNull()
        val episodeNumber = episode.episodeNumber
        activity.lifecycleScope.launch {
            showLoading(true)

            val result = withContext(Dispatchers.IO) {
                playbackResolver.resolve(
                    source = "debrid",
                    streamUrl = magnet,
                    isExpired = true,
                    infoHash = infoHash,
                    magnet = magnet,
                    seasonNumber = seasonNumber,
                    episodeNumber = episodeNumber,
                    episodeTitle = seriesName()
                )
            }

            showLoading(false)

            when (result) {
                is com.tvonnet.debridxtreamiptv.data.debrid.repository.ResolutionResult.Success -> {
                    launchDebridEpisode(
                        DebridEpisodeLaunch(
                            source = source,
                            episode = episode,
                            streamUrl = result.url,
                            intentInfoHash = infoHash,
                            intentMagnet = magnet,
                            intentHeaders = null,
                            directDebridPlayback = false,
                            launchPath = "resolver_backed",
                        )
                    )
                }
                is com.tvonnet.debridxtreamiptv.data.debrid.repository.ResolutionResult.RefreshRequired -> {
                    Toast.makeText(activity, activity.getString(R.string.c_refresh_required), Toast.LENGTH_SHORT).show()
                }
                is com.tvonnet.debridxtreamiptv.data.debrid.repository.ResolutionResult.Error -> {
                    sources.markDebridEpisodeSourceCached(episode.id, infoHash, false)
                    if (shouldTryNextDebridSource(result.failureType) && !infoHash.isNullOrBlank()) {
                        notifyDebridFailure(result.message, autoPlayNext = true)
                        autoPlayNextDebridEpisodeSource(episode, infoHash)
                    } else {
                        Toast.makeText(activity, result.message, Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    private fun shouldTryNextDebridSource(failureType: DebridFailureType?): Boolean {
        return when (failureType) {
            DebridFailureType.COPYRIGHT_BLOCKED,
            DebridFailureType.LEGAL_RESTRICTION,
            DebridFailureType.NOT_CACHED,
            DebridFailureType.UNAVAILABLE -> true
            DebridFailureType.RATE_LIMITED,
            DebridFailureType.AUTH_REQUIRED,
            DebridFailureType.NETWORK,
            DebridFailureType.UNKNOWN,
            null -> false
        }
    }

    private fun autoPlayNextDebridEpisodeSource(episode: EpisodeUiModel, failedStreamId: String) {
        val cachedSources = sources.cachedDebridSourcesByEpisode[episode.id]
        if (!cachedSources.isNullOrEmpty()) {
            val nextSource = sources.pickNextDebridEpisodeSource(episode.id, cachedSources, failedStreamId)
            if (nextSource != null) {
                sources.selectedDebridStreamIdByEpisode[episode.id] = nextSource.stream.stream_id
                Toast.makeText(activity, activity.getString(R.string.c_trying_next_source), Toast.LENGTH_SHORT).show()
                playDebridEpisode(nextSource, episode)
                return
            }
            sources.fetchAndShowDebridSources(episode)
            return
        }

        val episodeFetchContext = sources.resolveDebridEpisodeFetchContext(episode)
        if (episodeFetchContext == null) {
            val message = "Episode metadata missing for source fetch"
            Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
            sources.fetchAndShowDebridSources(episode)
            return
        }

        activity.lifecycleScope.launch {
            val (seasonNumber, episodeNumber) = episodeFetchContext
            val fetched = withContext(Dispatchers.IO) {
                unifiedSourceProvider.getSeriesEpisodeSources(
                    seriesId = seriesId(),
                    seasonNumber = seasonNumber,
                    episodeNumber = episodeNumber,
                    title = seriesName(),
                    yearHint = seriesReleaseDate()?.take(4)
                )
            }
            sources.cachedDebridSourcesByEpisode[episode.id] = fetched
            val nextSource = sources.pickNextDebridEpisodeSource(episode.id, fetched, failedStreamId)
            if (nextSource != null) {
                sources.selectedDebridStreamIdByEpisode[episode.id] = nextSource.stream.stream_id
                Toast.makeText(activity, activity.getString(R.string.c_trying_next_source), Toast.LENGTH_SHORT).show()
                playDebridEpisode(nextSource, episode)
            } else {
                sources.fetchAndShowDebridSources(episode)
            }
        }
    }

    private fun notifyDebridFailure(reason: String?, autoPlayNext: Boolean) {
        // B7: a blank reason used to return early → the user got ZERO feedback on
        // a failed selection. Fall back to a generic message instead.
        val detail = reason?.takeIf { it.isNotBlank() } ?: "source could not be played"
        val message = if (autoPlayNext) {
            "Playback failed, trying next source: $detail"
        } else {
            "Playback failed: $detail"
        }
        Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
    }
}
