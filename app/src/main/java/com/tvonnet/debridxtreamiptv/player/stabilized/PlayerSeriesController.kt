package com.tvonnet.debridxtreamiptv.player.stabilized

import android.util.Log
import android.view.View
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.tvonnet.debridxtreamiptv.R
import com.tvonnet.debridxtreamiptv.data.model.ContentType
import com.tvonnet.debridxtreamiptv.features.seriesv2.data.model.EpisodeEntityV2
import com.tvonnet.debridxtreamiptv.util.SensitiveLogRedactor
import kotlinx.coroutines.launch

/**
 * Series episode browsing + switching (Phase C4 of the route to ≤600).
 *
 * Moved verbatim out of [PlayerActivity]: the seriesPlaylist observer + episode
 * browser overlay ([observeSeriesPlaylistState], [setupEpisodeBrowser],
 * [showEpisodeBrowser], [onEpisodeSelected]) and the episode-switch commit points
 * ([playSeriesEpisode], [playNextEpisode], [playPreviousEpisode] +
 * [resolveIptvEpisodeResumeMs] — the IPTV resume-position lookup with the
 * latest-wins guard).
 *
 * The playlist-hijack guard `shouldPlaylistDrivePlayback` and the browser-view
 * decisions live in `PlayerSeriesBrowsing.kt` (P13) and are called directly.
 * Thin cross-cutting wrappers with non-series callers — `debridSeriesLookupId`,
 * `currentDebridSourceProfile`, `currentIptvEpisodeIdForPlaylist`,
 * `bindModernMetadata` — stay on the Activity and are reached through it.
 */
internal class PlayerSeriesController(
    private val activity: PlayerScreenFragment,
    private val session: PlayerSessionState,
) {

    private val viewModel get() = activity.viewModel
    private val playerView get() = activity.playerView
    private val historyManager get() = activity.historyManager
    private val watchedStateRepository get() = activity.watchedStateRepository
    private val liveTuner get() = activity.liveTuner
    private val lifecycleScope get() = activity.lifecycleScope
    private val intent get() = activity.intent
    private val supportActionBar get() = activity.supportActionBar

    private var episodeBrowserController: EpisodeBrowserController
        get() = activity.episodeBrowserController
        set(value) { activity.episodeBrowserController = value }

    // ── screen state (S1) ──
    private var contentId: String? by session::contentId
    private var currentUrl: String? by session::currentUrl
    private var originalTitle: String? by session::originalTitle
    private val contentType: ContentType? by session::contentType
    private val playbackSource: PlaybackSource by session::playbackSource
    private val seriesTitleExtra: String? by session::seriesTitleExtra
    private var seasonNumberExtra: Int? by session::seasonNumberExtra
    private var episodeNumberExtra: Int? by session::episodeNumberExtra
    private var episodeTitleExtra: String? by session::episodeTitleExtra
    private val tmdbIdExtra: String? by session::tmdbIdExtra
    private val imdbIdExtra: String? by session::imdbIdExtra
    private val posterUrlExtra: String? by session::posterUrlExtra
    private val backdropUrlExtra: String? by session::backdropUrlExtra
    private val debridInfoHashExtra: String? by session::debridInfoHashExtra
    private val debridStreamIdExtra: String? by session::debridStreamIdExtra
    private var isResolvingDebrid: Boolean by session::isResolvingDebrid
    private var hasRecordedHistory: Boolean by session::hasRecordedHistory
    private var startPositionMs: Long by session::startPositionMs

    // ── forwarders to cross-cutting collaborators kept on the Activity ──
    private fun currentDebridSourceProfile() = activity.currentDebridSourceProfile()
    private fun debridSeriesLookupId() = activity.debridSeriesLookupId()
    private fun currentIptvEpisodeIdForPlaylist() = activity.currentIptvEpisodeIdForPlaylist()
    private fun bindModernMetadata(title: String?) = activity.bindModernMetadata(title)
    private fun showError(message: String) = activity.showError(message)

    fun observeSeriesPlaylistState() {
        lifecycleScope.launch {
            activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.seriesPlaylistState.collect { state ->
                    Log.d("PlayerActivity", "seriesPlaylistState collected: episodes=${state?.originalList?.size ?: 0}")
                    if (state == null) return@collect
                    Log.e(
                        "IPTV_EP_LOAD_FIX",
                        "OBSERVED loading=${state.isLoading} count=${state.originalList.size} error=${state.error}"
                    )

                    // Sync episode browser if visible
                    if (episodeBrowserController.isVisible()) {
                        val seasonTitle = episodeBrowserSeasonTitle(seriesTitleExtra, seasonNumberExtra)
                        when (val view = episodeBrowserViewFor(state.isLoading, state.originalList, state.error)) {
                            is EpisodeBrowserView.Loading -> episodeBrowserController.setLoading(true)
                            is EpisodeBrowserView.Message ->
                                episodeBrowserController.showMessage(view.text, seasonTitle)
                            is EpisodeBrowserView.Episodes -> {
                                episodeBrowserController.setLoading(false)
                                Log.e("IPTV_EP_LOAD_FIX", "SEND_TO_BROWSER count=${view.episodes.size}")
                                episodeBrowserController.show(
                                    episodes = view.episodes,
                                    currentEpisodeId = contentId,
                                    seasonTitle = seasonTitle,
                                    fallbackImageUrl = getEpisodeBrowserFallbackImageUrl()
                                )
                            }
                        }
                    }

                    // Do NOT let a passive playlist load hijack the stream the user is
                    // already watching. When playback started from a SIBLING category
                    // (multi-source panel), the playing stream id isn't in the PRIMARY
                    // series' episode list, so emitSeriesPlaylist number-matches it to the
                    // PRIMARY's own episode — a DIFFERENT source. Switching to it made every
                    // category selection play the same (primary) stream ("select NL/DE →
                    // same file"). The intent already started the correct chosen stream
                    // (currentUrl), and next/prev/browser-pick switch episodes explicitly,
                    // so only let the playlist drive playback when nothing is playing yet.
                    if (shouldPlaylistDrivePlayback(
                            isLoading = state.isLoading,
                            playlistEpisodeId = state.currentEpisode?.episodeId,
                            currentEpisodeId = contentId,
                            currentUrl = currentUrl,
                            playbackSource = playbackSource
                        )
                    ) {
                        state.currentEpisode?.let { playSeriesEpisode(it) }
                    }
                }
            }
        }
    }

    fun setupEpisodeBrowser() {
        val browserContainer = activity.findViewById<View>(R.id.view_episode_browser) ?: return
        episodeBrowserController = EpisodeBrowserController(browserContainer) { episode ->
            onEpisodeSelected(episode)
        }
    }

    fun showEpisodeBrowser() {
        // FORCE HIDE the standard controller to prevent overlap
        playerView.hideController()

        val browserView = activity.findViewById<View>(R.id.view_episode_browser)
        browserView?.bringToFront()

        val state = viewModel.seriesPlaylistState.value
        Log.d(
            "PlayerActivity",
            "showEpisodeBrowser: episodes=${state?.originalList?.size ?: 0}, contentId=${SensitiveLogRedactor.describeHash(contentId)}"
        )

        val seasonTitle = episodeBrowserSeasonTitle(seriesTitleExtra, seasonNumberExtra)

        if (state == null) {
            // Show overlay in loading state immediately
            episodeBrowserController.show(emptyList(), contentId, seasonTitle, getEpisodeBrowserFallbackImageUrl())
            episodeBrowserController.setLoading(true)

            val seriesId = intent.getStringExtra(PlayerActivity.EXTRA_SERIES_ID) ?: tmdbIdExtra ?: imdbIdExtra
            val seasonNum = intent.getIntExtra(PlayerActivity.EXTRA_SEASON_NUM, -1)
            Log.d("PlayerActivity", "showEpisodeBrowser: state is null, triggering load. seriesId=${SensitiveLogRedactor.describeHash(seriesId)}, seasonNum=$seasonNum, contentId=${SensitiveLogRedactor.describeHash(contentId)}")
            if (seriesId != null && seasonNum != -1) {
                 if (playbackSource == PlaybackSource.DEBRID) {
                     viewModel.loadDebridSeriesPlaylist(seriesId, seasonNum, episodeNumberExtra ?: 1)
                 } else if (playbackSource != PlaybackSource.DEBRID) {
                     val episodeId = currentIptvEpisodeIdForPlaylist()
                     if (episodeId != null) {
                         viewModel.loadSeriesPlaylist(
                             seriesId, seasonNum, episodeId, seriesTitleExtra, episodeNumberExtra
                         )
                     } else {
                         episodeBrowserController.showMessage("Episodes unavailable", seasonTitle)
                     }
                 } else {
                     episodeBrowserController.showMessage("Episodes unavailable", seasonTitle)
                 }
            } else {
                 Log.e("PlayerActivity", "showEpisodeBrowser: CANNOT load playlist, missing IDs! seriesId=$seriesId, seasonNum=$seasonNum")
                 episodeBrowserController.showMessage("Episodes unavailable", seasonTitle)
            }
            return
        }
        when (val view = episodeBrowserViewFor(state.isLoading, state.originalList, state.error)) {
            is EpisodeBrowserView.Loading -> {
                episodeBrowserController.show(emptyList(), contentId, seasonTitle, getEpisodeBrowserFallbackImageUrl())
                episodeBrowserController.setLoading(true)
            }
            is EpisodeBrowserView.Message -> episodeBrowserController.showMessage(view.text, seasonTitle)
            is EpisodeBrowserView.Episodes -> {
                Log.e("IPTV_EP_LOAD_FIX", "SEND_TO_BROWSER count=${view.episodes.size}")
                episodeBrowserController.show(
                    episodes = view.episodes,
                    currentEpisodeId = contentId,
                    seasonTitle = seasonTitle,
                    fallbackImageUrl = getEpisodeBrowserFallbackImageUrl()
                )
            }
        }
    }

    private fun getEpisodeBrowserFallbackImageUrl(): String? =
        episodeBrowserFallbackImageUrl(posterUrlExtra, backdropUrlExtra)

    private fun onEpisodeSelected(episode: EpisodeEntityV2) {
        episodeBrowserController.hide()

        // Finalize watched state for CURRENT episode before switching identities
        historyManager.recordPlaybackHistoryIfNeeded()

        if (playbackSource == PlaybackSource.DEBRID) {
            // For Debrid, we need to resolve the new episode's link
            contentId = episode.episodeId
            episodeNumberExtra = episode.episodeNumber
            episodeTitleExtra = episode.title
            isResolvingDebrid = true
            viewModel.loadNextDebridEpisode(
                seriesId = debridSeriesLookupId(),
                targetSeason = seasonNumberExtra ?: 1,
                targetEpisode = episode.episodeNumber,
                seriesTitle = seriesTitleExtra ?: episode.title,
                infoHash = debridInfoHashExtra ?: debridStreamIdExtra,
                sourceProfile = currentDebridSourceProfile()
            )
        } else if (playbackSource != PlaybackSource.DEBRID) {
            playSeriesEpisode(episode)
        }
    }

    fun playSeriesEpisode(episode: EpisodeEntityV2) {
        val url = viewModel.getSeriesStreamUrl(episode)

        contentId = episode.episodeId
        currentUrl = url
        episodeNumberExtra = episode.episodeNumber
        episodeTitleExtra = episode.title
        val title = episode.title ?: "Episode ${episode.episodeNumber}"
        originalTitle = title
        bindModernMetadata(title)
        supportActionBar?.title = title

        hasRecordedHistory = false
        activity.hideNextEpisodePromptIfInitialized()
        activity.resetNextEpisodeStateIfInitialized()

        // Resume the switched-to episode from its saved position (next/prev/browser all
        // funnel here). Key collapses to episode:xtream:episode:<id>, matching the save
        // key in PlayerHistoryManager. Lookup is fast (single DB row); switch on main.
        // QA fix: latest-wins guard — two rapid NEXT/PREV presses launch two coroutines
        // whose DB reads can complete out of order; without the guard the STALE
        // episode's switch could run last (player plays A while metadata says B).
        val reqId = ++episodeSwitchRequestId
        lifecycleScope.launch {
            val resumeMs = resolveIptvEpisodeResumeMs(episode)
            if (reqId != episodeSwitchRequestId) return@launch
            startPositionMs = resumeMs
            liveTuner.performSeamlessSwitch(url)
        }
    }

    // QA fix: monotonically increasing id for IPTV episode switches (latest-wins).
    private var episodeSwitchRequestId = 0L

    /** Saved resume position for an IPTV episode, 0 if none / already watched. */
    private suspend fun resolveIptvEpisodeResumeMs(episode: EpisodeEntityV2): Long {
        val episodeId = episode.episodeId?.takeIf { it.isNotBlank() } ?: return 0L
        val key = com.tvonnet.debridxtreamiptv.data.local.WatchedIdentityBuilder.iptvEpisode(
            episodeId = episodeId,
            seriesId = intent.getStringExtra(PlayerActivity.EXTRA_SERIES_ID),
            seasonNumber = episode.seasonNumber,
            episodeNumber = episode.episodeNumber
        )
        val state = watchedStateRepository.getState(key) ?: return 0L
        if (state.isWatched) return 0L
        return state.progressMs.takeIf { it > 0L } ?: 0L
    }

    fun playNextEpisode() {
        activity.hideNextEpisodePromptIfInitialized()

        // Finalize watched state for CURRENT episode before switching identities
        historyManager.recordPlaybackHistoryIfNeeded()

        if (playbackSource == PlaybackSource.DEBRID && contentType == ContentType.EPISODE) {
             val currentSeason = seasonNumberExtra ?: -1
             val currentEpisode = episodeNumberExtra ?: -1
             val tmdbId = debridSeriesLookupId().takeIf { it.isNotBlank() }

              if (tmdbId != null && currentSeason != -1 && currentEpisode != -1) {
                  val nextEp = viewModel.getNextEpisode()

                  val targetSeason = nextEp?.seasonNumber?.takeIf { it >= currentSeason } ?: currentSeason
                  val targetEpisode = if (nextEp != null && nextEp.seasonNumber == currentSeason && nextEp.episodeNumber > currentEpisode) {
                      nextEp.episodeNumber
                  } else if (nextEp != null && nextEp.seasonNumber > currentSeason) {
                      nextEp.episodeNumber
                  } else {
                      currentEpisode + 1
                  }

                  viewModel.loadNextDebridEpisode(
                      seriesId = tmdbId,
                      targetSeason = targetSeason,
                      targetEpisode = targetEpisode,
                      seriesTitle = seriesTitleExtra,
                      infoHash = debridInfoHashExtra ?: debridStreamIdExtra,
                      sourceProfile = currentDebridSourceProfile()
                  )
             } else {
                 showError("Next episode data missing")
             }
        } else {
            viewModel.getNextEpisode()
        }
    }

    fun playPreviousEpisode() {
        activity.hideNextEpisodePromptIfInitialized()

        // Finalize watched state for CURRENT episode before switching identities
        historyManager.recordPlaybackHistoryIfNeeded()

        if (playbackSource == PlaybackSource.DEBRID && contentType == ContentType.EPISODE) {
            val currentSeason = seasonNumberExtra ?: -1
            val currentEpisode = episodeNumberExtra ?: -1
            val tmdbId = debridSeriesLookupId().takeIf { it.isNotBlank() }

            if (tmdbId != null && currentSeason != -1 && currentEpisode != -1) {
                val prevEp = viewModel.getPrevEpisode()
                val targetSeason = prevEp?.seasonNumber ?: currentSeason
                val targetEpisode = prevEp?.episodeNumber ?: (currentEpisode - 1).coerceAtLeast(1)

                viewModel.loadNextDebridEpisode(
                    seriesId = tmdbId,
                    targetSeason = targetSeason,
                    targetEpisode = targetEpisode,
                    seriesTitle = seriesTitleExtra,
                    infoHash = debridInfoHashExtra ?: debridStreamIdExtra,
                    sourceProfile = currentDebridSourceProfile()
                )
            } else {
                showError("Previous episode data missing")
            }
        } else {
            viewModel.getPrevEpisode()
        }
    }
}
