package com.tvonnet.debridxtreamiptv.player.stabilized

import androidx.lifecycle.lifecycleScope
import androidx.media3.exoplayer.ExoPlayer
import com.tvonnet.debridxtreamiptv.data.model.ContentType
import com.tvonnet.debridxtreamiptv.data.prefs.CredentialsPreferences
import kotlinx.coroutines.launch

/**
 * The debrid source panel + in-place source switch + resolution-state observer
 * (Phase C3 of the route to ≤600).
 *
 * Moved verbatim out of [PlayerActivity]: [openMovieSourcePanel] (the "other
 * sources" bottom sheet for a movie), [switchToMovieSource] (swap to a picked
 * source IN-PLACE — no relaunch, resume from the current position) and
 * [observeDebridResolutionState] (the single collector that turns a
 * [DebridResolutionState] into a play/switch/terminal reaction).
 *
 * State flows through [PlayerSessionState] (S1) — including the debrid identity
 * fields that used to be loose `var`s on the Activity, which is what makes this
 * cluster cleanly movable now. Activity collaborators (the loader, metadata bind,
 * `initializePlayer`, the live tuner's seamless switch) are reached on [activity].
 */
internal class PlayerDebridCoordinator(
    private val activity: BasePlayerFragment,
    private val session: PlayerSessionState,
) {

    private val viewModel get() = activity.viewModel
    private val loaderUi get() = activity.loaderUi
    private val liveTuner get() = activity.liveTuner
    private val lifecycleScope get() = activity.lifecycleScope
    private val supportFragmentManager get() = activity.supportFragmentManager
    private val isFinishing get() = activity.isFinishing

    private var player: ExoPlayer?
        get() = activity.player
        set(value) { activity.player = value }

    // ── screen state (S1) ──
    private val contentType: ContentType? by session::contentType
    private var originalTitle: String? by session::originalTitle
    private val backdropUrlExtra: String? by session::backdropUrlExtra
    private var debridStreamIdExtra: String? by session::debridStreamIdExtra
    private val contentId: String? by session::contentId
    private val imdbIdExtra: String? by session::imdbIdExtra
    private var startPositionMs: Long by session::startPositionMs
    private var playbackSource: PlaybackSource by session::playbackSource
    private var directDebridPlayback: Boolean by session::directDebridPlayback
    private var debridInfoHashExtra: String? by session::debridInfoHashExtra
    private var debridMagnetExtra: String? by session::debridMagnetExtra
    private var isResolvingDebrid: Boolean by session::isResolvingDebrid
    private var streamHeaders: Map<String, String>? by session::streamHeaders
    private var seasonNumberExtra: Int? by session::seasonNumberExtra
    private var episodeNumberExtra: Int? by session::episodeNumberExtra
    private var episodeTitleExtra: String? by session::episodeTitleExtra
    private val seriesTitleExtra: String? by session::seriesTitleExtra
    private var hasRecordedHistory: Boolean by session::hasRecordedHistory
    private var currentUrl: String? by session::currentUrl
    private var disableTunnelingForSession: Boolean by session::disableTunnelingForSession

    // ── forwarders to collaborators kept on the Activity ──
    private fun showToast(message: String) = activity.showToast(message)
    private fun showCinematicLoader() = activity.showCinematicLoader()
    private fun bindModernMetadata(title: String?) = activity.bindModernMetadata(title)
    private fun applyDebridSourceProfile(profile: DebridSourceProfile?) = activity.applyDebridSourceProfile(profile)
    private fun handleTerminalPlaybackFailure(reason: String) = activity.handleTerminalPlaybackFailure(reason)
    private fun initializePlayer(streamUrl: String) = activity.initializePlayer(streamUrl)

    fun openMovieSourcePanel() {
        if (isFinishing || contentType != ContentType.MOVIE) return
        val sheet = com.tvonnet.debridxtreamiptv.ui.series.SourceSelectionBottomSheet(
            onSourceSelected = { source, _ -> switchToMovieSource(source) },
            contentTitle = originalTitle,
            backdropUrl = backdropUrlExtra
        )
        sheet.show(supportFragmentManager, "player_movie_sources")
        lifecycleScope.launch {
            val sources = viewModel.fetchMovieSourcesForPanel(
                streamId = debridStreamIdExtra ?: contentId,
                title = originalTitle,
                imdbId = imdbIdExtra,
                cleanTitle = cleanLoaderTitle(originalTitle)
            )
            if (!sheet.isAdded) return@launch
            if (sources.isEmpty()) sheet.showError("No other sources found") else sheet.showSources(sources)
        }
    }

    /**
     * Switch the movie to the picked source IN-PLACE (no activity relaunch — relaunching
     * collapsed the back stack to home). Saves the current position, updates the source
     * identity, and re-inits: resolver-backed magnet sources go through the debrid
     * resolution (cinematic loader), direct-http/IPTV sources re-init on the ready URL.
     * The saved position is restored on the first frame (initializePlayer seeks to it).
     */
    private fun switchToMovieSource(source: com.tvonnet.debridxtreamiptv.data.repository.MovieSource) {
        if (contentType != ContentType.MOVIE || isFinishing) return
        val stream = source.stream
        val magnet = stream.direct_source
        val infoHash = stream.stream_id
        val route = sourceSwitchRoute(source, magnet)
        if (route.resolverBacked && magnet.isNullOrBlank() && infoHash.isNullOrBlank()) {
            showToast("Invalid source"); return
        }

        applySwitchedSourceIdentity(stream, route.isIptv)

        when {
            route.isIptv -> switchToIptvSource(stream)
            route.isDirectHttp -> switchToDirectHttpSource(magnet!!)
            else -> switchToResolverBackedSource(infoHash, magnet)
        }
    }

    private data class SourceSwitchRoute(
        val isIptv: Boolean,
        val isDirectHttp: Boolean,
        val resolverBacked: Boolean,
    )

    private fun sourceSwitchRoute(
        source: com.tvonnet.debridxtreamiptv.data.repository.MovieSource,
        magnet: String?
    ): SourceSwitchRoute {
        val isIptv = source.sourceType == "IPTV"
        val isDirectHttp = !isIptv && magnet?.startsWith("http", ignoreCase = true) == true &&
            !magnet.endsWith(".torrent", ignoreCase = true)
        return SourceSwitchRoute(isIptv, isDirectHttp, !isIptv && !isDirectHttp)
    }

    private fun applySwitchedSourceIdentity(
        stream: com.tvonnet.debridxtreamiptv.data.model.XtreamVodInfo,
        isIptv: Boolean
    ) {
        // Resume the new source from where we are now.
        startPositionMs = (player?.currentPosition ?: startPositionMs).coerceAtLeast(0L)
        playbackSource = if (isIptv) PlaybackSource.IPTV else PlaybackSource.DEBRID
        debridStreamIdExtra = stream.stream_id
        stream.name?.let { originalTitle = it }
        // Refresh the on-screen title so the controller shows the source we just switched to,
        // not the previous one.
        bindModernMetadata(stream.name ?: originalTitle)
    }

    private fun switchToIptvSource(stream: com.tvonnet.debridxtreamiptv.data.model.XtreamVodInfo) {
        val prefs = CredentialsPreferences(activity.requireContext())
        val server = prefs.getServerUrl()?.trimEnd('/')
        val user = prefs.getUsername()
        val pass = prefs.getPassword()
        val credentialsIncomplete =
            server.isNullOrBlank() || user.isNullOrBlank() || pass.isNullOrBlank()
        if (credentialsIncomplete || stream.stream_id.isNullOrBlank()) {
            showToast("Missing credentials"); return
        }
        directDebridPlayback = false
        debridInfoHashExtra = null; debridMagnetExtra = null
        val ext = stream.container_extension?.takeIf { it.isNotBlank() } ?: "mp4"
        showCinematicLoader()
        initializePlayer("$server/movie/$user/$pass/${stream.stream_id}.$ext")
    }

    private fun switchToDirectHttpSource(directUrl: String) {
        directDebridPlayback = true
        debridInfoHashExtra = null; debridMagnetExtra = null
        showCinematicLoader()
        initializePlayer(directUrl)
    }

    // Resolver-backed magnet: set the new identity and let the player resolve it.
    private fun switchToResolverBackedSource(infoHash: String?, magnet: String?) {
        directDebridPlayback = false
        debridInfoHashExtra = infoHash
        debridMagnetExtra = magnet
        isResolvingDebrid = true
        showCinematicLoader()
        viewModel.reResolveDebridUrl(infoHash, magnet, null, null, originalTitle)
    }

    /**
     * How a freshly-resolved URL starts playing.
     *
     * ROOT CAUSE (Amlogic "gazelle" / Fire TV 4K Max): 4K HEVC decodes in TUNNELED mode — the
     * video is composited on a hardware plane BEHIND the app window and the SurfaceView only
     * punches a transparent hole for it; the app never receives the frames. A mid-stream VOD
     * switch that REUSES the player (performSeamlessSwitch = stop→prepare on the SAME surface)
     * tears the tunnel session down and rebuilds it on that same surface while the previous one
     * was still live. On this SoC the HW video plane is then NOT re-shown, so the screen stays
     * BLACK even though the new decoder is decoding (logcat: "Got First Frame Render" + bitrate
     * flowing, yet black). Auto-advance works because it switches from the ENDED state, where the
     * tunnel session is already terminal and the new one establishes cleanly.
     *
     * Fix: a mid-stream VOD switch rebuilds the player with TUNNELING OFF. A non-tunneled decoder
     * renders into the app SurfaceView normally — the same proven path checkBlackVideoFallback
     * uses for the 4K "audio plays, video black" case (device-verified) — and
     * keepContentOnPlayerReset covers the surface reset. Cold start and the LIVE zap keep tunneling.
     */
    private fun startOrSwitchToResolvedUrl(url: String) {
        when {
            player == null -> initializePlayer(url)
            contentType == ContentType.LIVE_TV -> liveTuner.performSeamlessSwitch(url)
            else -> {
                disableTunnelingForSession = true
                initializePlayer(url)
            }
        }
    }

    fun observeDebridResolutionState() {
        lifecycleScope.launch {
            viewModel.debridResolutionState.collect { state ->
                when (state) {
                    is DebridResolutionState.Loading -> {
                        isResolvingDebrid = true
                        // Kill the OLD episode's audio the instant a switch starts resolving, so it
                        // doesn't keep playing (audibly) behind the loading screen during the async
                        // debrid resolve. Surface-safe: only the volume changes — no pause/stop/
                        // rebuild (those tripped the tunnel surface race, see the black-video fix).
                        // The Success path builds a FRESH player (volume defaults to 1); Idle restores.
                        player?.volume = 0f
                        showCinematicLoader()
                    }
                    is DebridResolutionState.Success -> {
                        // Keep the loader up through buffering; STATE_READY hides it on first frame.
                        isResolvingDebrid = false
                        directDebridPlayback = state.directDebridPlayback
                        streamHeaders = state.headers
                        applyDebridSourceProfile(state.sourceProfile)
                        debridMagnetExtra = state.magnet ?: debridMagnetExtra
                        if (state.season != null && state.episode != null) {
                            val isSame = seasonNumberExtra == state.season && episodeNumberExtra == state.episode
                            seasonNumberExtra = state.season; episodeNumberExtra = state.episode; episodeTitleExtra = state.title
                            if (!isSame) startPositionMs = 0L
                            bindModernMetadata("${seriesTitleExtra ?: ""} - S${state.season}:E${state.episode}")
                            if (state.infoHash != null) debridInfoHashExtra = state.infoHash
                            if (state.sourceProfile?.streamId != null) debridStreamIdExtra = state.sourceProfile.streamId
                            hasRecordedHistory = false; activity.resetNextEpisodeStateIfInitialized()
                        }
                        currentUrl = state.url
                        startOrSwitchToResolvedUrl(state.url)
                    }
                    is DebridResolutionState.Error -> {
                        loaderUi.hide()
                        isResolvingDebrid = false
                        handleTerminalPlaybackFailure("Failed: ${state.message}")
                    }
                    is DebridResolutionState.Idle -> { loaderUi.hide(); isResolvingDebrid = false; player?.volume = 1f }
                }
            }
        }
    }
}
