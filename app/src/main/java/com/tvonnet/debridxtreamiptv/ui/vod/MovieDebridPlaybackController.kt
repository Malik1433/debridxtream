package com.tvonnet.debridxtreamiptv.ui.vod

import android.content.Intent
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResult
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.tvonnet.debridxtreamiptv.R
import com.tvonnet.debridxtreamiptv.data.Result
import com.tvonnet.debridxtreamiptv.data.debrid.model.DebridFailureType
import com.tvonnet.debridxtreamiptv.data.debrid.repository.AddonProxyReadiness
import com.tvonnet.debridxtreamiptv.data.debrid.repository.DebridPlaybackRepository
import com.tvonnet.debridxtreamiptv.data.debrid.repository.PlaybackResolver
import com.tvonnet.debridxtreamiptv.data.model.ContentType
import com.tvonnet.debridxtreamiptv.data.model.ContinueWatchingItem
import com.tvonnet.debridxtreamiptv.data.model.XtreamVodInfo
import com.tvonnet.debridxtreamiptv.data.prefs.CredentialsPreferences
import com.tvonnet.debridxtreamiptv.data.repository.DebridCacheStatus
import com.tvonnet.debridxtreamiptv.data.repository.MovieSource
import com.tvonnet.debridxtreamiptv.data.repository.XtreamRepository
import com.tvonnet.debridxtreamiptv.debug.PlaybackDiagnosticsRecorder
import com.tvonnet.debridxtreamiptv.player.stabilized.PlayerActivity
import com.tvonnet.debridxtreamiptv.util.SensitiveLogRedactor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * MD-4: the Debrid **playback launch** concern lifted out of [MovieDetailActivity] — the direct
 * analogue of [com.tvonnet.debridxtreamiptv.ui.series.SeriesDebridPlaybackController] (SD-4c). Owns
 * the play/resume/failover flow for a movie (direct-HTTP + resolver-backed debrid, Xtream IPTV,
 * addon-proxy readiness gating, auto-advance to the next source, Debrid-history resume) and the
 * source-failover accounting ([autoFallbackAttempts] / [openedFromPlaybackFailure]). The source
 * *state* lives in [MovieDebridSourceController] (MD-3) and is read/mutated through [sources].
 *
 * The `playerLauncher` `registerForActivityResult` stays REGISTERED IN THE ACTIVITY (its timing
 * before STARTED is load-bearing); the Activity forwards its callback to [handlePlayerResult] and
 * passes [launch] = `playerLauncher::launch`. Behaviour byte-identical to the old private methods;
 * only `this`/field refs were rebound to [activity], the injected deps and the getters.
 */
class MovieDebridPlaybackController(
    private val activity: AppCompatActivity,
    private val sources: MovieDebridSourceController,
    private val repository: XtreamRepository,
    private val debridPlaybackRepository: DebridPlaybackRepository,
    private val playbackResolver: PlaybackResolver,
    host: Host,
) {
    /** The Activity-owned status view + current-movie accessors + player launch hand-off. */
    data class Host(
        val tvSourcesStatus: TextView,
        val movieId: () -> String?,
        val movieName: () -> String?,
        val movieIcon: () -> String?,
        val movieBackdrop: () -> String?,
        val movieContainer: () -> String?,
        val movieCategoryId: () -> String?,
        val currentImdbId: () -> String?,
        val resumePositionMs: () -> Long,
        val launch: (Intent) -> Unit,
    )

    private val tvSourcesStatus = host.tvSourcesStatus
    private val movieId = host.movieId
    private val movieName = host.movieName
    private val movieIcon = host.movieIcon
    private val movieBackdrop = host.movieBackdrop
    private val movieContainer = host.movieContainer
    private val movieCategoryId = host.movieCategoryId
    private val currentImdbId = host.currentImdbId
    private val resumePositionMs = host.resumePositionMs
    private val launch = host.launch

    private companion object {
        const val MAX_AUTO_FALLBACK_ATTEMPTS = 3
    }

    // Consecutive automatic source-failover attempts; the picker resets it so a
    // bad night of sources degrades to manual choice instead of an endless chain.
    private var autoFallbackAttempts = 0
    var openedFromPlaybackFailure: Boolean = false

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
            if (!failedStreamId.isNullOrBlank()) {
                sources.failedDebridStreamIds.add(failedStreamId)
                sources.markDebridSourceStatus(failedStreamId, DebridCacheStatus.NOT_CACHED)
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
            if (allowAutoPlayNext && !failedStreamId.isNullOrBlank()) {
                autoFallbackAttempts++
                autoPlayNextDebridSource(failedStreamId)
            } else {
                autoFallbackAttempts = 0
                sources.showDebridSourcePicker(sources.consumeDebridReturnFocusStreamIds())
            }
        }
    }

    fun playMovie(sourceOverride: MovieSource? = null) {
        val stream = sourceOverride?.stream ?: sources.currentStream()
        val streamId = stream?.stream_id ?: movieId() ?: return
        val containerExt = stream?.container_extension ?: movieContainer() ?: "mp4"

        val credentialsPrefs = CredentialsPreferences(activity)
        val serverUrl = credentialsPrefs.getServerUrl() ?: return
        val username = credentialsPrefs.getUsername() ?: return
        val password = credentialsPrefs.getPassword() ?: return

        val isDirectHttp = isDirectHttpStream(stream)
        if (isDebridSelection(stream, sourceOverride, isDirectHttp)) {
            playDebridMovie(stream, sourceOverride)
            return
        }

        val streamUrl = buildXtreamStreamUrl(
            XtreamUrlFacts(stream, isDirectHttp, serverUrl, username, password, streamId, containerExt)
        )
        android.util.Log.d(
            "MovieDetailActivity",
            "Playing movie: ${stream?.name ?: movieName()} - URL: ${SensitiveLogRedactor.describeUrl(streamUrl)}"
        )

        val flags = moviePlaybackFlags(isDirectHttp, sourceOverride)
        val launchPlayer = {
            launchIptvOrDirectMovie(
                IptvMovieLaunch(stream, sourceOverride, streamUrl, streamId, flags)
            )
        }

        if (sources.isAddonProxyPlaybackUrl(streamUrl)) {
            gateOnAddonProxyReadiness(streamUrl, sourceOverride, stream, launchPlayer)
            return
        }

        launchPlayer()
    }

    // Check if it's a Debrid source (magnet link or explicit label)
    // BUT exclude direct HTTP streams (which should be played directly)
    private fun isDirectHttpStream(stream: XtreamVodInfo?): Boolean =
        stream?.direct_source?.startsWith("http") == true &&
            stream.direct_source?.endsWith(".torrent") == false

    private fun isDebridSelection(
        stream: XtreamVodInfo?,
        sourceOverride: MovieSource?,
        isDirectHttp: Boolean
    ): Boolean =
        !isDirectHttp && (
            stream?.direct_source?.startsWith("magnet:") == true ||
                sourceOverride?.label?.contains("Torrentio") == true ||
                sourceOverride?.label?.contains("MediaFusion") == true ||
                sourceOverride?.label?.contains("PureFire") == true
            )

    /** Everything the non-debrid stream-URL choice depends on, bundled. */
    private data class XtreamUrlFacts(
        val stream: XtreamVodInfo?,
        val isDirectHttp: Boolean,
        val serverUrl: String,
        val username: String,
        val password: String,
        val streamId: String,
        val containerExt: String,
    )

    private fun buildXtreamStreamUrl(facts: XtreamUrlFacts): String {
        return if (facts.isDirectHttp) {
            facts.stream!!.direct_source!!
        } else if (facts.stream != null) {
            repository.buildVodStreamUrl(facts.stream, facts.serverUrl)
        } else {
            val baseUrl = facts.serverUrl.trimEnd('/')
            "$baseUrl/movie/${facts.username}/${facts.password}/${facts.streamId}.${facts.containerExt}"
        }
    }

    private data class MoviePlaybackFlags(
        val playbackSource: com.tvonnet.debridxtreamiptv.player.stabilized.PlaybackSource?,
        val directDebridPlayback: Boolean,
        val resolverBackedDebridPlayback: Boolean,
    )

    // An IPTV row picked from the debrid source sheet is plain Xtream playback:
    // never route it through the debrid resolver machinery.
    private fun moviePlaybackFlags(isDirectHttp: Boolean, sourceOverride: MovieSource?): MoviePlaybackFlags {
        val isIptvSource = sourceOverride?.sourceType == "IPTV"
        val playbackSource =
            if (movieCategoryId() == "debrid" && !isIptvSource) {
                com.tvonnet.debridxtreamiptv.player.stabilized.PlaybackSource.DEBRID
            } else {
                null
            }
        val directDebridPlayback = isDirectHttp && movieCategoryId() == "debrid" && !isIptvSource
        return MoviePlaybackFlags(playbackSource, directDebridPlayback, playbackSource != null && !directDebridPlayback)
    }

    private data class IptvMovieLaunch(
        val stream: XtreamVodInfo?,
        val sourceOverride: MovieSource?,
        val streamUrl: String,
        val streamId: String,
        val flags: MoviePlaybackFlags,
    )

    private fun launchIptvOrDirectMovie(plan: IptvMovieLaunch) {
        val (stream, sourceOverride, streamUrl, streamId, flags) = plan
        val tmdbId = movieId()?.takeIf { it.toIntOrNull() != null }
        PlaybackDiagnosticsRecorder.record(
            activity,
            "playback_launch",
            PlaybackDiagnosticsRecorder.contentFields(
                kind = "movie",
                tmdbId = tmdbId,
                imdbId = currentImdbId()
            ) + PlaybackDiagnosticsRecorder.sourceFields(activity, sourceOverride) +
                PlaybackDiagnosticsRecorder.playbackFields(
                    context = activity,
                    url = streamUrl,
                    headers = sourceOverride?.headers,
                    playbackSource = flags.playbackSource?.name,
                    directDebridPlayback = flags.directDebridPlayback
                ) + mapOf("launchPath" to "direct_or_iptv")
        )
        val intent = PlayerActivity.createIntent(
            context = activity,
            streamUrl = streamUrl,
            title = stream?.name ?: movieName() ?: activity.getString(R.string.movie_detail_unknown_movie),
            contentId = stream?.stream_id ?: streamId,
            contentType = ContentType.MOVIE,
            posterUrl = com.tvonnet.debridxtreamiptv.util.GlobalConfig.resolveIconUrl(stream?.stream_icon) ?: movieIcon(),
            backdropUrl = stream?.cover ?: movieBackdrop(),
            headers = sourceOverride?.headers,
            tmdbId = tmdbId,
            imdbId = currentImdbId(),
            debridInfoHash = if (flags.resolverBackedDebridPlayback) stream?.stream_id else null,
            debridMagnet = if (flags.resolverBackedDebridPlayback) stream?.direct_source else null,
            directDebridPlayback = flags.directDebridPlayback,
            debridProvider = sourceOverride?.provider,
            debridSourceType = sourceOverride?.sourceType,
            debridSourceName = sourceOverride?.sourceName,
            debridLanguages = sourceOverride?.languages,
            debridQuality = sourceOverride?.quality,
            debridStreamId = sourceOverride?.stream?.stream_id,
            debridBingeGroup = sourceOverride?.bingeGroup,
            debridFileIdx = sourceOverride?.fileIdx,
            playbackSource = flags.playbackSource,
            startPositionMs = resumePositionMs()
        )
        activity.startActivity(intent)
    }

    private fun gateOnAddonProxyReadiness(
        streamUrl: String,
        sourceOverride: MovieSource?,
        stream: XtreamVodInfo?,
        launchPlayer: () -> Unit
    ) {
        activity.lifecycleScope.launch {
            val readyResult = withContext(Dispatchers.IO) {
                debridPlaybackRepository.getAddonProxyPlaybackReadiness(
                    streamUrl,
                    sourceOverride?.headers,
                    sourceOverride?.provider,
                    sourceOverride?.sourceName,
                    sourceOverride?.sourceType,
                    diagnosticsContext = activity
                )
            }
            val readiness = (readyResult as? Result.Success)?.data ?: AddonProxyReadiness.UNCERTAIN
            if (readiness == AddonProxyReadiness.TERMINAL) {
                stream?.stream_id?.let { sources.failedDebridStreamIds.add(it) }
                sources.markDebridSourceStatus(stream?.stream_id, DebridCacheStatus.NOT_CACHED)
                Toast.makeText(
                    activity,
                    "Source is unavailable. Choose another source.",
                    Toast.LENGTH_LONG
                ).show()
                if (sourceOverride != null) {
                    sources.showDebridSourcePicker(sources.consumeDebridReturnFocusStreamIds())
                }
                return@launch
            }
            if (readiness == AddonProxyReadiness.READY) {
                sources.markDebridSourceStatus(stream?.stream_id, DebridCacheStatus.DIRECT_STREAM)
            }
            launchPlayer()
        }
    }

    private fun autoPlayNextDebridSource(failedStreamId: String) {
        if (sources.debridSources.isNotEmpty()) {
            val nextSource = sources.pickNextDebridSource(sources.debridSources, failedStreamId)
            if (nextSource != null) {
                sources.selectedDebridStreamId = nextSource.stream.stream_id
                Toast.makeText(activity, "Trying next source...", Toast.LENGTH_SHORT).show()
                playDebridMovie(nextSource.stream, nextSource, returnToSources = true)
                return
            }
            sources.showDebridSourcePicker()
            return
        }

        activity.lifecycleScope.launch {
            val newSources = withContext(Dispatchers.IO) {
                sources.fetchMovieSourcesFromProvider()
            }
            sources.debridSources = newSources
            val nextSource = sources.pickNextDebridSource(newSources, failedStreamId)
            if (nextSource != null) {
                sources.selectedDebridStreamId = nextSource.stream.stream_id
                Toast.makeText(activity, "Trying next source...", Toast.LENGTH_SHORT).show()
                playDebridMovie(nextSource.stream, nextSource, returnToSources = true)
            } else {
                sources.showDebridSourcePicker()
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

    fun playDebridMovie(
        stream: XtreamVodInfo?,
        source: MovieSource?,
        returnToSources: Boolean = false
    ) {
        val magnet = stream?.direct_source
        val infoHash = stream?.stream_id // UnifiedSourceProvider sets stream_id to infoHash for Debrid sources

        if (magnet.isNullOrBlank() && infoHash.isNullOrBlank()) {
            Toast.makeText(activity, "Invalid Debrid source", Toast.LENGTH_SHORT).show()
            return
        }

        val isDirectHttp = magnet?.startsWith("http", ignoreCase = true) == true &&
            magnet.endsWith(".torrent", ignoreCase = true).not()
        if (isDirectHttp && !magnet.isNullOrBlank()) {
            playDirectHttpDebridMovie(stream, source, magnet, infoHash, returnToSources)
            return
        }

        resolveAndPlayDebridMovie(stream, source, magnet, infoHash, returnToSources)
    }

    /** What actually goes into the player intent for a debrid movie launch. */
    private data class DebridMovieLaunch(
        val stream: XtreamVodInfo?,
        val source: MovieSource?,
        val streamUrl: String,
        val contentId: String,
        val intentInfoHash: String?,
        val intentMagnet: String?,
        val directDebridPlayback: Boolean,
        val launchPath: String,
        val returnToSources: Boolean,
    )

    private fun launchDebridMovie(plan: DebridMovieLaunch) {
        val tmdbId = movieId()?.takeIf { it.toIntOrNull() != null }
        PlaybackDiagnosticsRecorder.record(
            activity,
            "playback_launch",
            PlaybackDiagnosticsRecorder.contentFields(
                kind = "movie",
                tmdbId = tmdbId,
                imdbId = currentImdbId()
            ) + PlaybackDiagnosticsRecorder.sourceFields(activity, plan.source) +
                PlaybackDiagnosticsRecorder.playbackFields(
                    context = activity,
                    url = plan.streamUrl,
                    headers = plan.source?.headers,
                    playbackSource = com.tvonnet.debridxtreamiptv.player.stabilized.PlaybackSource.DEBRID.name,
                    directDebridPlayback = plan.directDebridPlayback
                ) + mapOf("launchPath" to plan.launchPath)
        )
        val intent = PlayerActivity.createIntent(
            context = activity,
            streamUrl = plan.streamUrl,
            title = plan.stream?.name ?: movieName() ?: activity.getString(R.string.movie_detail_unknown_movie),
            contentId = plan.contentId,
            contentType = ContentType.MOVIE,
            posterUrl = com.tvonnet.debridxtreamiptv.util.GlobalConfig.resolveIconUrl(plan.stream?.stream_icon) ?: movieIcon(),
            backdropUrl = plan.stream?.cover ?: movieBackdrop(),
            headers = plan.source?.headers,
            playbackSource = com.tvonnet.debridxtreamiptv.player.stabilized.PlaybackSource.DEBRID,
            tmdbId = tmdbId,
            imdbId = currentImdbId(),
            debridInfoHash = plan.intentInfoHash,
            debridMagnet = plan.intentMagnet,
            directDebridPlayback = plan.directDebridPlayback,
            debridProvider = plan.source?.provider,
            debridSourceType = plan.source?.sourceType,
            debridSourceName = plan.source?.sourceName,
            debridLanguages = plan.source?.languages,
            debridQuality = plan.source?.quality,
            debridStreamId = plan.source?.stream?.stream_id,
            debridBingeGroup = plan.source?.bingeGroup,
            debridFileIdx = plan.source?.fileIdx,
            // Resume from the saved position when the user pressed "Resume".
            startPositionMs = resumePositionMs()
        )
        if (plan.returnToSources) {
            intent.putExtra(PlayerActivity.EXTRA_RETURN_TO_SOURCES, true)
            launch(intent)
        } else {
            activity.startActivity(intent)
        }
    }

    private fun playDirectHttpDebridMovie(
        stream: XtreamVodInfo?,
        source: MovieSource?,
        directUrl: String,
        infoHash: String?,
        returnToSources: Boolean
    ) {
        val plan = DebridMovieLaunch(
            stream = stream,
            source = source,
            streamUrl = directUrl,
            contentId = infoHash ?: movieId() ?: "",
            intentInfoHash = null,
            intentMagnet = null,
            directDebridPlayback = true,
            launchPath = "direct",
            returnToSources = returnToSources,
        )

        if (debridPlaybackRepository.requiresDirectProxyReadinessCheck(
                directUrl,
                source?.provider,
                source?.sourceName,
                source?.sourceType
            )
        ) {
            activity.lifecycleScope.launch {
                tvSourcesStatus.visibility = View.VISIBLE
                tvSourcesStatus.text = "Checking source..."
                val readyResult = withContext(Dispatchers.IO) {
                    debridPlaybackRepository.getAddonProxyPlaybackReadiness(
                        directUrl,
                        source?.headers,
                        source?.provider,
                        source?.sourceName,
                        source?.sourceType,
                        diagnosticsContext = activity
                    )
                }
                tvSourcesStatus.visibility = View.GONE
                val readiness = (readyResult as? Result.Success)?.data ?: AddonProxyReadiness.UNCERTAIN
                if (readiness == AddonProxyReadiness.TERMINAL) {
                    infoHash?.let { sources.failedDebridStreamIds.add(it) }
                    sources.markDebridSourceStatus(infoHash, DebridCacheStatus.NOT_CACHED)
                    Toast.makeText(activity, "Source is unavailable. Choose another source.", Toast.LENGTH_LONG).show()
                    sources.showDebridSourcePicker(sources.consumeDebridReturnFocusStreamIds())
                    return@launch
                }
                if (readiness == AddonProxyReadiness.READY) {
                    sources.markDebridSourceStatus(infoHash, DebridCacheStatus.DIRECT_STREAM)
                }
                launchDebridMovie(plan)
            }
            return
        }

        launchDebridMovie(plan)
    }

    private fun resolveAndPlayDebridMovie(
        stream: XtreamVodInfo?,
        source: MovieSource?,
        magnet: String?,
        infoHash: String?,
        returnToSources: Boolean
    ) {
        activity.lifecycleScope.launch {
            // Show loading indicator
            tvSourcesStatus.visibility = View.VISIBLE
            tvSourcesStatus.text = "Resolving Debrid link..."

            val result = withContext(Dispatchers.IO) {
                playbackResolver.resolve(
                    source = "debrid",
                    streamUrl = magnet,
                    isExpired = true,
                    infoHash = infoHash,
                    magnet = magnet,
                    seasonNumber = null,
                    episodeNumber = null,
                    episodeTitle = movieName()
                )
            }

            tvSourcesStatus.visibility = View.GONE

            when (result) {
                is com.tvonnet.debridxtreamiptv.data.debrid.repository.ResolutionResult.Success -> {
                    launchDebridMovie(
                        DebridMovieLaunch(
                            stream = stream,
                            source = source,
                            streamUrl = result.url,
                            contentId = infoHash ?: movieId() ?: "",
                            intentInfoHash = infoHash,
                            intentMagnet = magnet,
                            directDebridPlayback = false,
                            launchPath = "resolver_backed",
                            returnToSources = returnToSources,
                        )
                    )
                }
                is com.tvonnet.debridxtreamiptv.data.debrid.repository.ResolutionResult.RefreshRequired -> {
                    Toast.makeText(activity, "Source expired, please refresh", Toast.LENGTH_SHORT).show()
                    sources.showDebridSourcePicker(sources.consumeDebridReturnFocusStreamIds())
                }
                is com.tvonnet.debridxtreamiptv.data.debrid.repository.ResolutionResult.Error -> {
                    val failedStreamId = stream?.stream_id
                    sources.markDebridSourceCached(failedStreamId, false)
                    sources.updateRdSummary(sources.debridSources)

                    if (shouldTryNextDebridSource(result.failureType) && !failedStreamId.isNullOrBlank()) {
                        notifyDebridFailure(result.message, autoPlayNext = true)
                        autoPlayNextDebridSource(failedStreamId)
                    } else {
                        Toast.makeText(activity, result.message, Toast.LENGTH_LONG).show()
                        sources.showDebridSourcePicker(sources.consumeDebridReturnFocusStreamIds())
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

    fun handleDebridHistoryItem(infoHash: String) {
        tvSourcesStatus.visibility = View.VISIBLE
        tvSourcesStatus.text = "Resuming from Debrid History..."

        activity.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                playbackResolver.resolve(
                    source = "debrid",
                    streamUrl = null,
                    isExpired = true,
                    infoHash = infoHash,
                    magnet = null,
                    allowDirectHttpPassthrough = false
                )
            }

            tvSourcesStatus.visibility = View.GONE

            when (result) {
                is com.tvonnet.debridxtreamiptv.data.debrid.repository.ResolutionResult.Success -> {
                    val tmdbId = movieId()?.takeIf { it.toIntOrNull() != null }
                    val intent = PlayerActivity.createIntent(
                        context = activity,
                        streamUrl = result.url,
                        title = movieName() ?: activity.getString(R.string.movie_detail_unknown_movie),
                        contentId = infoHash,
                        contentType = ContentType.MOVIE,
                        posterUrl = movieIcon(),
                        backdropUrl = movieBackdrop(),
                        playbackSource = com.tvonnet.debridxtreamiptv.player.stabilized.PlaybackSource.DEBRID,
                        tmdbId = tmdbId,
                        imdbId = currentImdbId(),
                        debridInfoHash = infoHash,
                        // This is the "Resume from Debrid History" path — carry the saved position.
                        startPositionMs = resumePositionMs()
                    )
                    activity.startActivity(intent)
                }
                is com.tvonnet.debridxtreamiptv.data.debrid.repository.ResolutionResult.RefreshRequired -> {
                    Toast.makeText(activity, "History item expired", Toast.LENGTH_SHORT).show()
                }
                is com.tvonnet.debridxtreamiptv.data.debrid.repository.ResolutionResult.Error -> {
                    Toast.makeText(activity, result.message, Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}
