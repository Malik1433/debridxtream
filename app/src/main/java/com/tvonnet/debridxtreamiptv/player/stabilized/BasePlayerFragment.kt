package com.tvonnet.debridxtreamiptv.player.stabilized

import android.app.ActivityManager
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.annotation.RequiresApi
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.DefaultLivePlaybackSpeedControl
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.ui.PlayerView
import com.tvonnet.debridxtreamiptv.BuildConfig
import com.tvonnet.debridxtreamiptv.R
import com.tvonnet.debridxtreamiptv.data.model.ContentType
import com.tvonnet.debridxtreamiptv.data.model.XtreamStream
import com.tvonnet.debridxtreamiptv.data.model.ContinueWatchingItem
import com.tvonnet.debridxtreamiptv.data.model.RecentLiveChannelItem
import com.tvonnet.debridxtreamiptv.data.model.toLiveStreamUrl

import com.tvonnet.debridxtreamiptv.data.model.toAbsoluteUrl
import com.tvonnet.debridxtreamiptv.data.debrid.repository.DebridPlaybackRepository
import com.tvonnet.debridxtreamiptv.debug.PlaybackDiagnosticsRecorder
import com.tvonnet.debridxtreamiptv.ui.sources.SessionSourcePreference
import com.tvonnet.debridxtreamiptv.data.prefs.CredentialsPreferences
import com.tvonnet.debridxtreamiptv.data.prefs.WatchHistoryPreferences
import com.tvonnet.debridxtreamiptv.data.repository.WatchedStateRepository
import com.tvonnet.debridxtreamiptv.data.network.NetworkQualityManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import okhttp3.OkHttpClient
import com.tvonnet.debridxtreamiptv.data.prefs.SettingsPreferences
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.common.TrackSelectionOverride
import java.text.SimpleDateFormat
import java.util.*
import android.os.CountDownTimer
import com.tvonnet.debridxtreamiptv.features.seriesv2.data.model.EpisodeEntityV2
import com.tvonnet.debridxtreamiptv.util.GlideUtils
import com.tvonnet.debridxtreamiptv.util.DeviceProfile
import com.tvonnet.debridxtreamiptv.util.isMissingImageUrl
import com.tvonnet.debridxtreamiptv.util.PlayerCacheManager
import com.tvonnet.debridxtreamiptv.util.SensitiveLogRedactor
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheDataSink
import androidx.media3.datasource.DataSink
import androidx.media3.datasource.FileDataSource

@UnstableApi
@AndroidEntryPoint
open class BasePlayerFragment : Fragment(), PlayerRecoveryController.RecoveryHost {

    // ── Activity/Context bridges (P26 fragment split) ───────────────────────
    // The player logic + its 15 collaborators were written against a concrete
    // Activity. Hosting the same code in a Fragment, these forwarders keep every
    // `activity.<member>` call site compiling 1:1; the thin host Activity supplies
    // the real Activity behaviour (setResult / finish / PiP / key dispatch).
    internal val intent: Intent get() = requireActivity().intent
    internal val supportActionBar get() = (requireActivity() as AppCompatActivity).supportActionBar
    internal val isFinishing: Boolean get() = activity?.isFinishing ?: true
    internal val isDestroyed: Boolean get() = (activity?.isDestroyed ?: true) || isRemoving || isDetached
    internal val window get() = requireActivity().window
    internal fun setResult(resultCode: Int, data: Intent?) = requireActivity().setResult(resultCode, data)
    internal fun finish() = requireActivity().finish()
    internal fun getSystemService(name: String): Any? = requireContext().getSystemService(name)
    internal fun <T : View> findViewById(id: Int): T = requireView().findViewById(id)
    internal fun overridePendingTransition(enter: Int, exit: Int) =
        requireActivity().overridePendingTransition(enter, exit)
    internal val supportFragmentManager get() = requireActivity().supportFragmentManager

    internal val viewModel: PlayerViewModel by viewModels()

    @Inject
    lateinit var okHttpClient: OkHttpClient

    // Playback-tuned client (longer read timeout) for the video DataSource; the
    // general okHttpClient above is still used for non-playback work (Glide, etc.).
    @Inject
    @javax.inject.Named("playback")
    lateinit var playbackOkHttpClient: OkHttpClient

    @Inject
    lateinit var settingsPreferences: SettingsPreferences

    /** Live failover searches the catalogue for the other feeds of the current channel. */
    @Inject
    lateinit var xtreamRepository: com.tvonnet.debridxtreamiptv.data.repository.XtreamRepository

    @Inject
    lateinit var watchedStateRepository: WatchedStateRepository

    @Inject
    lateinit var debridPlaybackRepository: DebridPlaybackRepository

    internal val prefs by lazy { CredentialsPreferences(requireContext()) }

    /** S1: every mutable screen state lives here; the vars below just delegate. */
    private val session = PlayerSessionState().apply { timeoutMs = TIMEOUT_MS }


    internal var isInPictureInPictureMode = false
    private var wasPlayingBeforePiP: Boolean by session::wasPlayingBeforePiP

    /** P9: PiP capability + entry. */
    private val pipController by lazy { PlayerPipController(requireActivity()) }

    internal var player: ExoPlayer? = null
    internal lateinit var playerView: PlayerView

    // True when this session was launched from the Live TV EPG guide with a shared
    // preview player to adopt (seamless mini <-> fullscreen continuity).
    internal val sharedLiveSession by lazy { intent.getBooleanExtra(PlayerActivity.EXTRA_SHARED_LIVE_PLAYER, false) }
    internal var sharedExitInProgress: Boolean by session::sharedExitInProgress
    private lateinit var watchHistoryPrefs: WatchHistoryPreferences
    internal lateinit var historyManager: PlayerHistoryManager

    private var retryCount: Int by session::retryCount
    private val maxRetries = 5 
    internal var currentUrl: String? by session::currentUrl
    private var streamHeaders: Map<String, String>? by session::streamHeaders
    private var subtitleEntries: List<String> by session::subtitleEntries
    private var timeoutMs: Long by session::timeoutMs
    internal val timeoutHandler = Handler(Looper.getMainLooper())
    internal val timeoutRunnable = Runnable { recovery.handleTimeout() }
    internal val retryHandler = Handler(Looper.getMainLooper())

    /** C1: error/timeout/network-recovery brain. Owns the connectivity callback;
     *  shares this Activity's timeout/retry Handlers so both arm/cancel the same work. */
    internal val recovery: PlayerRecoveryController by lazy {
        PlayerRecoveryController(requireContext(), session, this, timeoutHandler, retryHandler, timeoutRunnable)
    }

    /** C2: Live-TV zapping + OSD + the seamless-switch primitive. Shares this
     *  Activity's timeout Handler so the buffer watchdog arms identically. */
    internal val liveTuner: PlayerLiveTuner by lazy {
        PlayerLiveTuner(this, session, timeoutHandler, timeoutRunnable)
    }

    /** C3: debrid source panel + in-place source switch + resolution-state observer. */
    internal val debridCoordinator: PlayerDebridCoordinator by lazy {
        PlayerDebridCoordinator(this, session)
    }

    /** C3 bridge: reset the next-episode prompt state, but only once its manager exists. */
    internal fun resetNextEpisodeStateIfInitialized() {
        if (::nextEpisodeManager.isInitialized) { nextEpisodeManager.resetState() }
    }

    /** C4 bridge: hide the next-episode prompt, but only once its manager exists. */
    internal fun hideNextEpisodePromptIfInitialized() {
        if (::nextEpisodeManager.isInitialized) { nextEpisodeManager.hidePrompt() }
    }

    /** C4: series episode browsing + switching. */
    internal val seriesController: PlayerSeriesController by lazy {
        PlayerSeriesController(this, session)
    }

    /** C5: the remaining lifecycle-scoped viewModel→UI subscriptions. */
    internal val viewModelBinder: PlayerViewModelBinder by lazy {
        PlayerViewModelBinder(this, session)
    }

    /** C6: the D-pad / media-key routing shell (rules live in PlayerKeyRouting). */
    private val inputRouter: PlayerInputRouter by lazy {
        PlayerInputRouter(this, session)
    }

    /** P21: the legacy live EPG overlay strip renderer. */
    internal val epgRenderer: PlayerLiveEpgRenderer by lazy {
        PlayerLiveEpgRenderer(this, session)
    }

    /** P22: the stall + video-freeze watchdog. */
    internal val stallMonitor: PlayerStallMonitor by lazy {
        PlayerStallMonitor(this, session)
    }
    internal fun startStallMonitor() = stallMonitor.startStallMonitor()
    internal fun stopStallMonitor() = stallMonitor.stopStallMonitor()

    /** P23: VOD title/poster/subtitle/quality chrome binder. */
    private val metadataBinder: PlayerMetadataBinder by lazy {
        PlayerMetadataBinder(this, session)
    }
    internal fun bindModernMetadata(title: String?) = metadataBinder.bindModernMetadata(title)

    /** P24: builds the ExoPlayer MediaItem (owns the TS-vs-HLS LiveConfiguration gate). */
    private val mediaItemFactory: PlayerMediaItemFactory by lazy {
        PlayerMediaItemFactory(this, session)
    }

    /** P26a: audio/subtitle/aspect track pickers + the single managed-dialog slot. */
    private val trackSelectionUi: PlayerTrackSelectionUi by lazy {
        PlayerTrackSelectionUi(this, session)
    }

    /** P26b: VOD transport-controller focus + the custom seek-bar overlay. */
    internal val vodControls: PlayerVodControlsUi by lazy {
        PlayerVodControlsUi(this, session)
    }

    /** P26c: exit-with-result / terminal-failure cluster (shared handoff stays here). */
    internal val exitController: PlayerExitController by lazy {
        PlayerExitController(this, session)
    }
    internal fun showControllerWithSmartFocus(sourceEvent: KeyEvent? = null) =
        vodControls.showControllerWithSmartFocus(sourceEvent)
    internal fun showAudioSelection() = trackSelectionUi.showAudioSelection()
    internal fun showSubtitleSelection() = trackSelectionUi.showSubtitleSelection()
    internal fun showAspectSelection() = trackSelectionUi.showAspectSelection()
    internal fun dismissActiveTrackDialog() = trackSelectionUi.dismissActiveTrackDialog()
    internal fun buildMediaItem(url: String) = mediaItemFactory.buildMediaItem(url)
    /** P24: the factory needs the mimeHint variant of the diagnostics fields. */
    internal fun diagnosticsFieldsWithMime(url: String?, mimeHint: String?) =
        diagnosticsPlaybackFields(url = url, mimeHint = mimeHint)
    /** P21: thin forwarder so C5's viewModel binder + setup still call renderOverlay here. */
    internal fun renderOverlay(state: PlayerOverlayUiState) = epgRenderer.renderOverlay(state)

    /** C6 bridges: lateinit-guarded reads the input router needs. */
    internal fun isNextEpisodePromptVisible(): Boolean =
        ::nextEpisodeManager.isInitialized && nextEpisodeManager.isPromptVisible
    internal fun isEpisodeBrowserVisible(): Boolean =
        ::episodeBrowserController.isInitialized && episodeBrowserController.isVisible()
    internal var frameRateMatchedForCurrentSource: Boolean by session::frameRateMatchedForCurrentSource

    // ── crash-safe progress heartbeat (VOD) ─────────────────────────────────
    // Streaming-app standard: persist the playback position every ~30s while
    // playing so a crash / power cut / system kill loses at most one interval.
    private val progressSaveHandler = Handler(Looper.getMainLooper())
    private val progressSaveRunnable = object : Runnable {
        override fun run() {
            if (player?.isPlaying == true && contentType != ContentType.LIVE_TV) {
                historyManager.saveProgressSnapshot()
            }
            progressSaveHandler.postDelayed(this, PROGRESS_SAVE_INTERVAL_MS)
        }
    }

    // ── progress-aware buffering watchdog (live) ────────────────────────────
    // Baseline bufferedPosition captured when the timeout watchdog is (re)armed;
    // handleTimeout extends the window while the buffer keeps growing.
    private var watchdogExtensions: Int by session::watchdogExtensions
    private var watchdogBufferedPosAtArm: Long by session::watchdogBufferedPosAtArm
    internal fun armWatchdogBaseline() {
        watchdogBufferedPosAtArm = player?.bufferedPosition ?: -1L
        watchdogExtensions = 0
    }

    // ── channel-zap debounce (LP-B-1) ───────────────────────────────────────
    // The OSD/index advances on every keypress; the actual tune is coalesced to
    // the LAST target after the user settles (ZAP_DEBOUNCE_MS of no new zap).
    /** P19c: the Live channel-browser overlay; tuning stays here. */
    private val channelBrowser: PlayerChannelBrowser by lazy {
        PlayerChannelBrowser(requireActivity(), object : ChannelBrowserHost {
            override fun currentStreamId(): String? = contentId
            override fun onChannelSelected(stream: XtreamStream) {
                val id = stream.stream_id ?: return
                val serverUrl = baseServerUrl ?: prefs.getServerUrl() ?: ""
                val newUrl = stream.toLiveStreamUrl(serverUrl, prefs.getUsername().orEmpty(), prefs.getPassword().orEmpty())
                liveTuner.performSeamlessSwitch(newUrl)
                contentId = id
                currentUrl = newUrl
                channelLogoUrl = com.tvonnet.debridxtreamiptv.util.GlobalConfig.resolveIconUrl(stream.stream_icon)
                currentEpgChannelId = stream.epg_channel_id ?: id
                bindChannelMeta(stream.name)
                supportActionBar?.title = stream.name
                viewModel.initLiveZapping(viewModel.browserState.value.selectedCategoryId, id, serverUrl, null)
                viewModel.observeEpg(stream.epg_channel_id ?: id, id)
                viewModel.toggleBrowser(false)
            }
            override fun onBrowserClosed() {
                PlayerChannelBrowser.restorePlayerFocus { playerView.requestFocus() }
            }
        })
    }

    /** P19b: transport-control wiring (buttons + volume), actions stay here. */
    internal val transportButtons: PlayerTransportButtons by lazy {
        PlayerTransportButtons(playerView, object : TransportActions {
            override fun onAudioSelection() = showAudioSelection()
            override fun onSubtitleSelection() = showSubtitleSelection()
            override fun onAspectRatio() = showAspectSelection()
            override fun onPlay() { player?.play() }
            override fun onPause() { player?.pause() }
            // Directional sync-point seeks (fix rapid-FF/RW jitter): the engine default
            // SeekParameters.CLOSEST_SYNC snaps to the nearest keyframe on EITHER side, so a
            // forward press could land on a keyframe BEHIND the target ("goes forward then jumps
            // back") and rapid presses thrash. Force forward → NEXT_SYNC (never lands before the
            // target) and back → PREVIOUS_SYNC (never lands after) so repeated seeks move
            // monotonically while staying keyframe-fast. Each press sets its own direction before
            // seeking, so consecutive FF/RW presses are always resolved consistently.
            override fun onRewind() = seekStep(forward = false)
            override fun onForward() = seekStep(forward = true)
            override fun onNextEpisode() = seriesController.playNextEpisode()
            override fun onPreviousEpisode() = seriesController.playPreviousEpisode()
            override fun onEpisodes() = seriesController.showEpisodeBrowser()
            override fun onSources() = debridCoordinator.openMovieSourcePanel()
            override fun isControllerVisible(): Boolean = isControllerVisible
        })
    }

    /** P18: LP-B-1 — the OSD advances per keypress, the real connect is debounced. */
    internal val zapDebouncer = PlayerZapDebouncer(
        handler = Handler(Looper.getMainLooper()),
        debounceMs = ZAP_DEBOUNCE_MS,
        onCommit = { target -> liveTuner.tuneToZapChannel(target) }
    )

    // HTTP codes that mean the source itself is dead — retrying can never help.
    private val TERMINAL_HTTP_CODES = setOf(400, 401, 403, 404, 405, 410, 416, 451)

    // Fail fast on permanently broken sources so app-level fallback kicks in quickly;
    // transient errors back off exponentially with jitter to avoid retry storms.
    private val playbackLoadErrorPolicy = object : DefaultLoadErrorHandlingPolicy() {
        override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
            val responseCode =
                (loadErrorInfo.exception as? HttpDataSource.InvalidResponseCodeException)?.responseCode
            if (responseCode in TERMINAL_HTTP_CODES) {
                return C.TIME_UNSET
            }
            // HTTP 429 (QA fix 4): the provider WAF is rate-limiting this IP. Standard
            // fast backoff only deepens the ban, so cool off hard at the media3 level
            // too — honor Retry-After if present, else a fixed 20s. The app-level
            // handlePlaybackError applies the same policy + banner/budget accounting.
            if (responseCode == 429) {
                val headers =
                    (loadErrorInfo.exception as? HttpDataSource.InvalidResponseCodeException)?.headerFields
                val retryAfterSec = headers?.entries
                    ?.firstOrNull { it.key?.equals("Retry-After", ignoreCase = true) == true }
                    ?.value?.firstOrNull()?.trim()?.toLongOrNull()
                return retryAfterSec?.let { (it * 1000L).coerceIn(1000L, 60_000L) } ?: HTTP_429_COOLOFF_MS
            }
            val exponentialMs = 1000L shl (loadErrorInfo.errorCount - 1).coerceIn(0, 3)
            return exponentialMs + (0..400).random()
        }
    }

    // ── C1 RecoveryHost bridge: the recovery controller reaches back through these ──
    override fun peekPlayer(): ExoPlayer? = player
    override fun assignPlayer(player: ExoPlayer?) { this.player = player }
    override fun requestSeamlessSwitch(newUrl: String) = liveTuner.performSeamlessSwitch(newUrl)
    override fun isAutoReconnectEnabled(): Boolean = settingsPreferences.isAutoReconnectEnabled()
    override fun maxRetriesConfigured(): Int = maxRetries
    override fun diagnosticsFields(url: String?, retry: Int?): Map<String, Any?> =
        diagnosticsPlaybackFields(url = url, retry = retry)
    override fun reResolveDebridUrl(
        infoHash: String?,
        magnet: String?,
        season: Int?,
        episode: Int?,
        title: String?,
        allowDirectHttpPassthrough: Boolean
    ) = viewModel.reResolveDebridUrl(infoHash, magnet, season, episode, title, allowDirectHttpPassthrough)

    // P10: thin delegators — the recovery subsystems that call these still live here.
    override fun canAttemptReconnect(): Boolean = reconnectManager.canAttemptReconnect()
    internal fun resetReconnectBudget() = reconnectManager.resetBudget()
    private fun showReconnectingBanner() = reconnectManager.showBanner()
    internal fun hideReconnectingBanner() = reconnectManager.hideBanner()
    internal fun maybeUpdateNetworkQuality() =
        reconnectManager.maybeUpdateNetworkQuality(bandwidthMeter?.bitrateEstimate)

    /** P14: remembers which addon-proxy context last failed / already succeeded. */
    internal val addonProxyFailures = AddonProxyFailureTracker()
    internal var hasRenderedFirstFrameForCurrentSource: Boolean by session::hasRenderedFirstFrameForCurrentSource
    // Black-video fallback: some HW decoders (e.g. MTK on high-bitrate/4K HEVC) play audio
    // but never render video under tunneling. If no frame arrives while audio is playing,
    // reinit once without tunneling.
    private var blackVideoFallbackTried: Boolean by session::blackVideoFallbackTried
    internal val blackVideoCheckRunnable = Runnable { checkBlackVideoFallback() }
    internal var isControllerVisible = false

    private val overlayHandler = Handler(Looper.getMainLooper())
    /** P18: the legacy EPG strip's mode/pin/auto-hide (only used without the Live OSD). */
    internal val epgOverlayUi: PlayerEpgOverlay by lazy {
        PlayerEpgOverlay(
            handler = overlayHandler,
            timeoutMs = OVERLAY_TIMEOUT,
            overlay = { epgOverlay },
            isLive = { contentType == ContentType.LIVE_TV },
            shouldBeVisible = { shouldShowEpgOverlay() }
        )
    }
    private var isSwitching: Boolean by session::isSwitching
    private var layoutDebugOverlay: View? = null
    private var tvDebugInfo: TextView? = null
    private var debugEnabled = false
    private var switchCount: Int by session::switchCount
    internal var debugListener: Player.Listener? = null
    internal var playerListener: Player.Listener? = null
    internal val captureRunnable = Runnable { captureManualTrackSelection() }
    /** P8: the 1 Hz developer overlay; it renders only while [debugSnapshot] returns non-null. */
    private val debugOverlay = PlayerDebugOverlay(
        target = { tvDebugInfo },
        snapshot = { debugSnapshot() }
    )

    private fun debugSnapshot(): PlayerDebugSnapshot? {
        if (isFinishing || isDestroyed) return null
        if (!debugEnabled) return null
        val p = player ?: return null
        return PlayerDebugSnapshot(
            playbackState = p.playbackState,
            playWhenReady = p.playWhenReady,
            positionMs = p.currentPosition,
            bufferedPositionMs = p.bufferedPosition,
            switchCount = switchCount,
            playbackSource = playbackSource,
            url = currentUrl
        )
    }

    internal var epgOverlay: View? = null
    private var imgChannelLogo: ImageView? = null
    internal var tvChannelNumber: TextView? = null
    internal var tvChannelName: TextView? = null

    private var vodInfoOverlay: View? = null
    private var imgVodArtwork: ImageView? = null
    private var tvVodTitle: TextView? = null
    private var tvVodSubtitle: TextView? = null

    /** P11: the cinematic pre-playback loader owns its views + animators. */
    internal val loaderUi = PlayerLoaderUi(hideReconnectBanner = { reconnectManager.hideBanner() })
    private var lastBufferingStartMs: Long by session::lastBufferingStartMs
    private var disableTunnelingForSession: Boolean by session::disableTunnelingForSession
    // How many times we've recovered from an AudioTrack-allocation failure this
    // source (reset on a clean READY / new source). Bounds the audio-device retry.
    private var audioSinkRecoveryCount: Int by session::audioSinkRecoveryCount
    // Some providers' live TS streams carry backward PTS jumps right after start;
    // the decoder renders the first frame and then drops everything as "late".
    // Re-preparing the same URL resets the timestamp adjuster past the bad region.
    private var liveFreezeReprepares: Int by session::liveFreezeReprepares
    private var lastFreezeRecoveryUrl: String? by session::lastFreezeRecoveryUrl
    private var lastFreezeRecoveryAtMs: Long by session::lastFreezeRecoveryAtMs
    // Provider socket drops surface as STATE_ENDED; reconnect in place, but cap
    // it so an instantly-dropping stream degrades to the error path, not a loop.
    private var endedReconnects: Int by session::endedReconnects
    internal var lastEndedReconnectAtMs = 0L

    // ── unified reconnect budget (QA fix 2) ─────────────────────────────────
    // The three recovery subsystems (generic error retry, ENDED-reconnect, live
    // freeze re-prepare) each keep their own inner cap, but they can fire
    // sequentially and hammer a WAF-protected / max_connections=1 server on a
    // single dead channel. This aggregate budget is the outer ceiling every path
    // must clear (see PlayerReconnectManager, which owns the budget itself).

    // ── session-adaptive network quality (QA fix 7) ─────────────────────────
    // ExoPlayer's passive bandwidth estimate (no extra network requests — the WAF
    // blocks bursts) feeds SettingsPreferences.saveNetworkQuality() on each
    // BUFFERING→READY transition, debounced to at most once per NETWORK_QUALITY_
    // UPDATE_INTERVAL_MS. New sizing applies on the next player build / zap.
    private var bandwidthMeter: DefaultBandwidthMeter? = null

    // LP-D-2: per-player QoE analytics listener (TTFF, rebuffers, errors).
    internal var qoeTracker: PlaybackQoeTracker? = null

    internal var channelLogoUrl: String? by session::channelLogoUrl
    internal var contentType: ContentType? by session::contentType
    internal var playbackSource: PlaybackSource by session::playbackSource
    internal var directDebridPlayback: Boolean by session::directDebridPlayback
    internal var contentId: String? by session::contentId
    private var returnToSourcesOnExit: Boolean by session::returnToSourcesOnExit
    internal var didPlaybackComplete: Boolean by session::didPlaybackComplete
    private var manualExit: Boolean by session::manualExit
    private var exitResultHandled: Boolean by session::exitResultHandled
    private var lastPlaybackPositionMs: Long by session::lastPlaybackPositionMs
    internal var posterUrlExtra: String? by session::posterUrlExtra
    internal var backdropUrlExtra: String? by session::backdropUrlExtra
    internal var tmdbIdExtra: String? by session::tmdbIdExtra
    internal var imdbIdExtra: String? by session::imdbIdExtra
    internal var seriesTitleExtra: String? by session::seriesTitleExtra
    internal var episodeTitleExtra: String? by session::episodeTitleExtra
    internal var seasonNumberExtra: Int? by session::seasonNumberExtra
    internal var episodeNumberExtra: Int? by session::episodeNumberExtra
    internal var debridInfoHashExtra: String? by session::debridInfoHashExtra
    internal var debridMagnetExtra: String? by session::debridMagnetExtra
    internal var debridProviderExtra: String? by session::debridProviderExtra
    internal var debridSourceTypeExtra: String? by session::debridSourceTypeExtra
    internal var debridSourceNameExtra: String? by session::debridSourceNameExtra
    internal var debridLanguagesExtra: List<String>? by session::debridLanguagesExtra
    internal var debridQualityExtra: String? by session::debridQualityExtra
    internal var debridStreamIdExtra: String? by session::debridStreamIdExtra
    internal var debridBingeGroupExtra: String? by session::debridBingeGroupExtra
    internal var debridFileIdxExtra: Int? by session::debridFileIdxExtra
    internal var expiresAtExtra: Long? by session::expiresAtExtra
    internal var originalTitle: String? by session::originalTitle
    internal var hasRecordedHistory: Boolean by session::hasRecordedHistory
    private val timeFormatter = SimpleDateFormat("HH:mm", Locale.getDefault())
    private var startPositionMs: Long by session::startPositionMs
    internal var pendingChannelName: String? by session::pendingChannelName
    internal var currentEpgChannelId: String? by session::currentEpgChannelId
    internal var liveCategoryId: String? by session::liveCategoryId
    internal var baseServerUrl: String? by session::baseServerUrl
    internal var liveChannelIds: ArrayList<String>? = null
    private var isResolvingDebrid: Boolean by session::isResolvingDebrid
    internal var hasAppliedIndexOverride: Boolean by session::hasAppliedIndexOverride
    internal lateinit var trackManager: PlayerTrackManager
    internal lateinit var episodeBrowserController: EpisodeBrowserController
    private var currentZapRequestId: Long by session::currentZapRequestId
    // Cinematic live OSD (design_handoff Live Player); non-null only for LIVE_TV.
    internal var liveOsd: LivePlayerOsdManager? = null

    // Persistent "Reconnecting… (n/N)" pill (QA fix 3). Shown whenever ANY recovery
    // subsystem triggers a reconnect; hidden on the first rendered frame / READY.
    private var reconnectingBanner: View? = null
    private var reconnectingBannerText: TextView? = null
    private var streamHealthBanner: View? = null
    private var streamHealthText: TextView? = null

    /**
     * Why playback keeps stopping, said out loud.
     *
     * Separate from [stallMonitor], which is a watchdog: that one RECOVERS from a hard stall, this
     * one EXPLAINS a pattern of soft ones. The probe is bounded and runs on the IO dispatcher, and
     * the whole thing is a child of the view's scope, so leaving the player cancels it.
     */
    internal val streamHealth: StreamHealthMonitor by lazy {
        StreamHealthMonitor(
            scope = viewLifecycleOwner.lifecycleScope,
            now = { SystemClock.elapsedRealtime() },
            probe = {
                withContext(Dispatchers.IO) {
                    withTimeoutOrNull(HEALTH_PROBE_TIMEOUT_MS) {
                        NetworkQualityManager(requireContext(), okHttpClient).probeControlKbps()
                    }
                }
            },
            environment = {
                NetworkEnvironmentReader.read(
                    requireContext(),
                    isReconnecting = reconnectingBanner?.isVisible == true,
                )
            },
            onVerdict = ::showStreamHealth,
        )
    }

    /** Feeds of this channel already tried in this sitting, so failover cannot go round in a circle. */
    private val triedLiveStreamIds = mutableSetOf<String>()
    private var liveFailoverInFlight = false

    /**
     * Try the next feed of the SAME channel before giving up on it.
     *
     * @param onExhausted called when there is nothing left to try — the caller's normal error path.
     * @return true when an attempt was started, so the caller must not also show an error. The
     *   search is asynchronous, which is why the give-up path is a callback rather than a return
     *   value: the honest answer is not known yet.
     */
    internal fun tryLiveAlternateSource(onExhausted: () -> Unit): Boolean {
        if (liveFailoverInFlight || isFinishing || isDestroyed) return false
        val name = pendingChannelName ?: tvChannelName?.text?.toString()
        val term = LiveAlternateSources.searchTerm(name)
        if (term.isBlank()) return false

        liveFailoverInFlight = true
        contentId?.let { triedLiveStreamIds += it }
        viewLifecycleOwner.lifecycleScope.launch {
            val alternate = findLiveAlternate(name, term)
            liveFailoverInFlight = false
            if (!isAdded || isFinishing || isDestroyed) return@launch
            if (alternate == null) {
                Log.i("PlayerActivity", "Live failover: no untried feed left for \"$term\"")
                onExhausted()
            } else {
                switchToLiveAlternate(alternate)
            }
        }
        return true
    }

    /** Bounded: a search that has not answered in five seconds is no use to a frozen picture. */
    private suspend fun findLiveAlternate(name: String?, term: String): XtreamStream? = try {
        val found = withTimeoutOrNull(LIVE_ALTERNATE_SEARCH_MS) {
            withContext(Dispatchers.IO) { xtreamRepository.searchLive(term) }
        } ?: emptyList()
        LiveAlternateSources.rank(name, contentId, found, triedLiveStreamIds)
            .firstOrNull { !buildLiveUrl(it).isNullOrBlank() }
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w("PlayerActivity", "Live failover search failed: ${e.javaClass.simpleName}")
        null
    }

    private fun buildLiveUrl(stream: XtreamStream): String? {
        if (stream.stream_id.isNullOrBlank()) return null
        val serverUrl = baseServerUrl ?: prefs.getServerUrl() ?: ""
        return stream.toLiveStreamUrl(
            serverUrl, prefs.getUsername().orEmpty(), prefs.getPassword().orEmpty()
        )
    }

    /**
     * The channel is the SAME channel — only the feed changed — so the name, the number and the
     * EPG stay as they are. Swapping those too would tell the customer they had changed channel
     * when they had not.
     */
    private fun switchToLiveAlternate(alternate: XtreamStream) {
        val url = buildLiveUrl(alternate) ?: return
        Log.i("PlayerActivity", "Live failover: switching to ${alternate.stream_id}")
        alternate.stream_id?.let { triedLiveStreamIds += it }
        showToast(getString(R.string.live_trying_another_source))
        streamHealth.reset()
        // The alternate has not failed at anything yet: carrying the dead feed's exhausted
        // counters across would make it look broken before it had been given a chance.
        retryCount = 0
        resetReconnectBudget()
        contentId = alternate.stream_id
        currentUrl = url
        liveTuner.performSeamlessSwitch(url)
    }

    /**
     * One pill, one sentence. It never appears while the reconnect pill is up — the rule returns
     * OK in that case, so the two cannot both speak.
     */
    private fun showStreamHealth(verdict: StreamHealth) {
        val banner = streamHealthBanner ?: return
        val message = when (verdict) {
            StreamHealth.OK -> null
            StreamHealth.SERVER_SLOW -> R.string.health_server_slow
            StreamHealth.LOCAL_NETWORK_SLOW -> R.string.health_network_slow
            StreamHealth.WIFI_WEAK -> R.string.health_wifi_weak
            StreamHealth.PROVIDER_RATE_LIMITED -> R.string.health_rate_limited
            StreamHealth.PROVIDER_CONNECTION_LIMIT -> R.string.health_connection_limit
        }
        if (message == null) {
            banner.isVisible = false
            return
        }
        streamHealthText?.setText(message)
        banner.isVisible = true
    }

    /** P10: shared reconnect budget + its "RECONNECTING…" banner + network-quality reclassification. */
    private val reconnectManager: PlayerReconnectManager by lazy {
        PlayerReconnectManager(
            handler = timeoutHandler,
            host = object : ReconnectBannerHost {
                override fun bannerView(): View? = reconnectingBanner
                override fun bannerTextView(): TextView? = reconnectingBannerText
                override fun bannerLabel(attempt: Int, max: Int): String =
                    getString(R.string.player_reconnecting_banner, attempt, max)
                override fun isLoaderVisible(): Boolean = loaderUi.isVisible()
                override fun isGone(): Boolean = isFinishing || isDestroyed
            },
            settingsPreferences = settingsPreferences
        )
    }

    private companion object {
        // Player implementation constants (the EXTRA_* launch-contract keys + createIntent
        // stay on the thin host PlayerActivity; these are pure playback tuning values).
        private const val TIMEOUT_MS = 25000L
        /** A verdict nobody waited for is no verdict; the probe gets four seconds. */
        private const val HEALTH_PROBE_TIMEOUT_MS = 4_000L
        /** A failover search that has not answered by now is no use to a frozen picture. */
        private const val LIVE_ALTERNATE_SEARCH_MS = 5_000L
        private const val PROGRESS_SAVE_INTERVAL_MS = 30_000L
        private const val MEDIAFUSION_TIMEOUT_MS = 35000L
        // Live viewers zap away from dead streams fast — detect failure fast too.
        private const val LIVE_TIMEOUT_MS = 12000L
        // Debounce a channel-tune (LP-B-1): only commit to stop()/prepare()/connect
        // ~350ms after the last zap keypress, so holding or spamming channel-up/down
        // updates the OSD instantly but issues ONE provider connection when the user
        // settles — instead of a storm of socket cycles that trips the WAF and
        // (because each tune resets the reconnect budget) never trips our own throttle.
        private const val ZAP_DEBOUNCE_MS = 350L
        // Fixed cool-off for HTTP 429 when the server sends no Retry-After (QA fix 4).
        private const val HTTP_429_COOLOFF_MS = 20_000L
        private const val OVERLAY_TIMEOUT = 6000L
        private const val MIN_CREDIBLE_MOVIE_DURATION_MS = 30 * 60_000L
        private const val MIN_CREDIBLE_EPISODE_DURATION_MS = 5 * 60_000L
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.activity_player, container, false)

    // ── P27-b: live-mode setup + zap-init hooks ─────────────────────────────
    // The live-only branches of onViewCreated were moved verbatim into these open
    // hooks; only LivePlayerFragment overrides them, so a VOD instance runs the
    // empty base impls (identical to the old `contentType != LIVE_TV` path). The
    // live collaborator fields (liveTuner/liveOsd/epgRenderer/channelBrowser/
    // zapDebouncer) still live in the base — they are referenced by shared paths
    // (recovery/stall/PiP/overlay/lifecycle) and move down in a later P27 step.
    /** Live overlay chrome: OSD views, live OSD manager, channel meta, zap backdrop. */
    protected open fun setupLiveOverlayChrome(channelName: String?) {}
    /** Live EPG + overlay/zap viewModel subscriptions (also disables the VOD controller). */
    protected open fun observeLiveEpgAndState() {}
    /** Live zapping init + the channel-browser overlay. */
    protected open fun startLiveZappingAndBrowser() {}
    /** Seed the legacy EPG strip + show the compact overlay when no cinematic OSD. */
    protected open fun seedLiveEpgOverlay() {}
    // P27-c: the live-only BACK branch. Returns true if the press was consumed by a live
    // dismissable (OSD drawer → channel browser → EPG overlay); a VOD instance runs the
    // base default (false) so BACK falls straight through to the VOD-controller/exit logic —
    // identical to the old inline checks, which were already no-ops when liveOsd/browser were
    // absent.
    /** Live BACK: dismiss the OSD drawer / channel browser / EPG overlay in priority order. */
    protected open fun handleLiveBack(): Boolean = false
    // P28-a: the VOD (movie/episode/series) counterpart of the live setup hooks — controller
    // config + transport buttons + next-episode manager + series/debrid/x-ray wiring. Overridden
    // only by VodPlayerFragment; a LIVE instance never reaches it (it takes the LIVE_TV branch).
    /** VOD playback chrome + collaborators (the old `contentType != LIVE_TV` onViewCreated block). */
    protected open fun setupVodPlayback(streamTitle: String?) {}

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        if (BuildConfig.DEBUG) {
            Log.d("PlayerActivity", "onViewCreated package=${requireActivity().packageName} taskId=${requireActivity().taskId} component=${intent.component}")
        }

        bindPlayerChrome(view)

        val streamUrl = intent.getStringExtra(PlayerActivity.EXTRA_STREAM_URL)
        val streamTitle = intent.getStringExtra(PlayerActivity.EXTRA_STREAM_TITLE)
        startPositionMs = intent.getLongExtra(PlayerActivity.EXTRA_START_POSITION, 0L)
        originalTitle = streamTitle
        streamHeaders = readStreamHeaders(intent, PlayerActivity.EXTRA_STREAM_HEADERS)
        subtitleEntries = intent.getStringArrayListExtra(PlayerActivity.EXTRA_SUBTITLE_ENTRIES) ?: emptyList()

        val facts = debridResumeFacts(streamUrl)

        if (facts.isDebridUrlMissing && !facts.canResolverRepair && !facts.canFreshResolveDirectDebrid) {
            showError("Invalid stream URL")
            finish()
            return
        }

        applyLaunchChrome(streamUrl, streamTitle)
        loadSeriesPlaylistIfNeeded(facts.isDebrid)

        if (contentType == ContentType.LIVE_TV) {
            observeLiveEpgAndState()
        } else {
            setupVodPlayback(streamTitle)
        }

        routeInitialPlayback(facts, streamUrl)
        supportActionBar?.title = streamTitle ?: "Playing"

        if (contentType == ContentType.LIVE_TV) {
            startLiveZappingAndBrowser()
        }

        if (contentType == ContentType.LIVE_TV) {
            seedLiveEpgOverlay()
        } else {
            playerView.showController()
        }

        registerBackHandler()
    }

    private fun bindPlayerChrome(view: View) {
        trackManager = PlayerTrackManager(requireContext(), settingsPreferences)
        watchHistoryPrefs = WatchHistoryPreferences(requireContext())
        historyManager = PlayerHistoryManager(this, watchHistoryPrefs, viewModel, watchedStateRepository)
        lifecycle.addObserver(historyManager)

        playerView = view.findViewById(R.id.player_view)
        applySystemCaptionStyle()
        loaderUi.bind(requireActivity())
        layoutDebugOverlay = findViewById(R.id.layout_debug_overlay)
        tvDebugInfo = findViewById(R.id.tv_debug_info)
        reconnectingBanner = findViewById(R.id.reconnecting_banner)
        reconnectingBannerText = findViewById(R.id.reconnecting_banner_text)
        streamHealthBanner = findViewById(R.id.stream_health_banner)
        streamHealthText = findViewById(R.id.stream_health_text)

        PlayerLaunchArgs.readInto(this, session)
    }

    /** What the launch already knows about resuming this debrid playback. */
    private data class DebridResumeFacts(
        val isDebrid: Boolean,
        val canResolverRepair: Boolean,
        val isDebridUrlMissing: Boolean,
        val canFreshResolveDirectDebrid: Boolean,
        val isExpired: Boolean,
    )

    private fun debridResumeFacts(streamUrl: String?): DebridResumeFacts {
        val isDebrid = playbackSource == PlaybackSource.DEBRID
        val canResolveDebrid = canUseDebridResolver()
        val hasResInfo = !debridInfoHashExtra.isNullOrBlank() || !debridMagnetExtra.isNullOrBlank()
        // The resolver can only repair this playback if it is usable AND has a hash/magnet to feed it.
        val canResolverRepair = canResolveDebrid && hasResInfo
        val isDebridUrlMissing = streamUrl.isNullOrBlank()
        // See mustResolveBeforePlaying: a saved position no longer counts as "expired". A resume
        // now plays the link it was handed and repairs on a real 401/403/410, instead of paying a
        // cache-bypassing re-resolution before every first frame.
        val isExpired = mustResolveBeforePlaying(
            isDebrid = isDebrid,
            hasStreamUrl = !isDebridUrlMissing,
            expiresAtMs = expiresAtExtra,
            nowMs = System.currentTimeMillis()
        )
        return DebridResumeFacts(isDebrid, canResolverRepair, isDebridUrlMissing, canFreshResolveDirectDebrid(), isExpired)
    }

    private fun applyLaunchChrome(streamUrl: String?, streamTitle: String?) {
        timeoutMs = resolveTimeoutMs(streamUrl ?: "")
        currentUrl = streamUrl
        channelLogoUrl = intent.getStringExtra(PlayerActivity.EXTRA_CHANNEL_LOGO)
        val channelName = intent.getStringExtra(PlayerActivity.EXTRA_CHANNEL_NAME) ?: streamTitle
        pendingChannelName = channelName

        if (contentType == ContentType.LIVE_TV) {
            setupLiveOverlayChrome(channelName)
        } else {
            // New Redesign: We use the Controller's Top Bar instead of a separate VOD overlay
        }

        liveCategoryId = intent.getStringExtra(PlayerActivity.EXTRA_LIVE_CATEGORY_ID)
        baseServerUrl = intent.getStringExtra(PlayerActivity.EXTRA_BASE_SERVER_URL)
        liveChannelIds = intent.getStringArrayListExtra(PlayerActivity.EXTRA_LIVE_CHANNEL_IDS)
    }

    private fun loadSeriesPlaylistIfNeeded(isDebrid: Boolean) {
        val seriesId = intent.getStringExtra(PlayerActivity.EXTRA_SERIES_ID) ?: tmdbIdExtra ?: imdbIdExtra
        val seasonNum = intent.getIntExtra(PlayerActivity.EXTRA_SEASON_NUM, -1)
        if (seriesId != null && seasonNum != -1) {
            if (isDebrid) {
                viewModel.loadDebridSeriesPlaylist(seriesId, seasonNum, episodeNumberExtra ?: 1)
            } else {
                currentIptvEpisodeIdForPlaylist()?.let { episodeId ->
                    viewModel.loadSeriesPlaylist(
                        seriesId, seasonNum, episodeId, seriesTitleExtra, episodeNumberExtra
                    )
                }
            }
        }
    }

    // The resume rule, routed: fresh-resolve a direct source only when it is KNOWN
    // expired; resolver-repair when the URL is missing/expired; otherwise play what
    // we hold (see resume-play-then-repair). Order of these arms is the semantics.
    private fun routeInitialPlayback(facts: DebridResumeFacts, streamUrl: String?) {
        if (facts.canFreshResolveDirectDebrid && facts.isExpired) {
            refreshDirectDebridSourceFromMetadata("expired_direct_resume")
        } else if (facts.canResolverRepair && (facts.isDebridUrlMissing || facts.isExpired)) {
            Log.i("PlayerActivity", "Debrid resume detected: URL is ${if (facts.isDebridUrlMissing) "missing" else "expired"}. Triggering resolution.")
            isResolvingDebrid = true
            viewModel.reResolveDebridUrl(debridInfoHashExtra, debridMagnetExtra, seasonNumberExtra, episodeNumberExtra, episodeTitleExtra)
        } else if (facts.isDebrid && directDebridPlayback && facts.isExpired) {
            Log.w("PlayerActivity", "Direct Debrid resume is expired and cannot be refreshed; blocking stale passthrough playback.")
            handleTerminalPlaybackFailure("Expired direct source could not be refreshed")
        } else {
            // A ready movie/episode source (debrid pick OR an IPTV/Continue-Watching
            // resume): show the cinematic loader while it buffers instead of the bare
            // player spinner; STATE_READY dissolves it on the first frame. IPTV resumes
            // previously fell through to the spinner — this keeps the loading experience
            // identical (cinematic) for every movie/episode, debrid or IPTV.
            if (contentType == ContentType.MOVIE || contentType == ContentType.EPISODE) {
                showCinematicLoader()
            }
            initializePlayer(streamUrl ?: "")
        }
    }

    private fun registerBackHandler() {
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (handleLiveBack()) {
                    return
                }
                if (contentType == ContentType.MOVIE || contentType == ContentType.EPISODE) {
                    // Standard TV BACK: 1st press hides the controller, 2nd exits. `backHideArmed`
                    // (reset on any explicit show) keeps this reliable even if media3 re-shows the
                    // controller while paused. Uses the tracked isControllerVisible, not the
                    // sometimes-stale isControllerFullyVisible.
                    //
                    // M9: NOT on a phone. This is also the path a back GESTURE takes, and spending
                    // the first one on hiding chrome is why the handset reported "the movie player
                    // doesn't go back" — the swipe appeared to do nothing. A tap already hides the
                    // controls there.
                    val holdBackForControls =
                        resources.getBoolean(R.bool.ui_uses_dpad_focus)
                    if (holdBackForControls && isControllerVisible && !backHideArmed) {
                        playerView.hideController()
                        backHideArmed = true
                        return
                    }
                }
                performBackExit()
            }
        })
    }

    private data class ParsedSubtitle(val url: String, val language: String?)

    // Mi1 dead-code sweep (T3.1): showLanguageSelection / applyDebridLanguagePreference /
    // showVideoSelection removed — no callers (btn_player_language routes to
    // showAudioSelection; video quality is ABR-managed).

    /**
     * LP-D-7 (accessibility): honor the system caption preferences — hearing-impaired
     * users set font size/color/edge/background in Android Settings > Captions, and
     * every polished TV player applies them. No-op when captions are system-disabled
     * (media3's default style stays).
     */
    private fun applySystemCaptionStyle() {
        val cm = getSystemService(Context.CAPTIONING_SERVICE)
            as? android.view.accessibility.CaptioningManager ?: return
        if (!cm.isEnabled) return
        playerView.subtitleView?.let { sv ->
            sv.setStyle(androidx.media3.ui.CaptionStyleCompat.createFromCaptionStyle(cm.userStyle))
            sv.setFractionalTextSize(
                androidx.media3.ui.SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * cm.fontScale
            )
        }
    }


    internal fun setupOverlayViews() {
        if (epgOverlay != null) return
        epgOverlay = findViewById(R.id.epg_overlay)
        imgChannelLogo = findViewById(R.id.img_channel_logo)
        tvChannelNumber = findViewById(R.id.tv_channel_number)
        tvChannelName = findViewById(R.id.tv_channel_name)
        epgRenderer.bindViews()

        pendingChannelName?.let { bindChannelMeta(it) }
        epgRenderer.renderLastState()
    }

    internal lateinit var nextEpisodeManager: PlayerNextEpisodeManager

    // Standard TV BACK: armed after 1st BACK hides the controller, so the 2nd BACK exits
    // even if media3 auto-re-shows the controller (e.g. while paused). Disarmed on explicit show.
    private var backHideArmed: Boolean by session::backHideArmed

    // VOD Player redesign: custom design seek bar (visual only; media3 TimeBar handles scrubbing).



    internal fun currentIptvEpisodeIdForPlaylist(): String? = iptvPlaylistEpisodeId(contentId, currentUrl)

    override fun canUseDebridResolver(): Boolean {
        return playbackSource == PlaybackSource.DEBRID && !directDebridPlayback
    }

    override fun canFreshResolveDirectDebrid(): Boolean = canFreshResolveDirectDebrid(
        playbackSource = playbackSource,
        directDebridPlayback = directDebridPlayback,
        ids = DebridLookupIds(
            tmdbId = tmdbIdExtra,
            imdbId = imdbIdExtra,
            contentId = contentId,
            title = originalTitle
        )
    )

    override fun refreshDirectDebridSourceFromMetadata(reason: String): Boolean {
        if (!canFreshResolveDirectDebrid() || isResolvingDebrid) return false
        PlaybackDiagnosticsRecorder.record(
            requireContext(),
            "direct_debrid_refresh_started",
            diagnosticsPlaybackFields() + mapOf(
                "reasonCode" to PlaybackDiagnosticsRecorder.sanitizeReason(reason),
                "positionMs" to (player?.currentPosition ?: startPositionMs)
            )
        )
        Log.i("PlayerActivity", "Direct Debrid refresh requested: $reason")
        isResolvingDebrid = true
        if (contentType != ContentType.LIVE_TV && (player?.currentPosition ?: 0L) > 1000L) {
            startPositionMs = player?.currentPosition ?: startPositionMs
        }
        dispatchDirectDebridRefresh()
        return true
    }

    private fun dispatchDirectDebridRefresh() {
        if (contentType == ContentType.EPISODE && seasonNumberExtra != null && episodeNumberExtra != null) {
            viewModel.loadNextDebridEpisode(
                seriesId = debridSeriesLookupId(),
                targetSeason = seasonNumberExtra ?: 1,
                targetEpisode = episodeNumberExtra ?: 1,
                seriesTitle = seriesTitleExtra ?: originalTitle,
                infoHash = debridInfoHashExtra ?: debridStreamIdExtra,
                sourceProfile = currentDebridSourceProfile(),
                allowDirectHttpPassthrough = true
            )
        } else {
            viewModel.refreshDebridMovieSource(
                streamId = tmdbIdExtra ?: contentId,
                title = originalTitle,
                imdbId = imdbIdExtra,
                sourceProfile = currentDebridSourceProfile(),
                allowDirectHttpPassthrough = true
            )
        }
    }

    internal fun currentDebridSourceProfile(): DebridSourceProfile? {
        if (playbackSource != PlaybackSource.DEBRID) return null
        return DebridSourceProfile(
            provider = debridProviderExtra,
            sourceType = debridSourceTypeExtra,
            sourceName = debridSourceNameExtra,
            languages = debridLanguagesExtra,
            quality = debridQualityExtra,
            streamId = debridStreamIdExtra ?: debridInfoHashExtra,
            bingeGroup = debridBingeGroupExtra,
            fileIdx = debridFileIdxExtra,
            directPlayback = directDebridPlayback
        )
    }

    internal fun debridSeriesLookupId(): String = debridSeriesLookupId(
        tmdbId = tmdbIdExtra,
        intentSeriesId = intent.getStringExtra(PlayerActivity.EXTRA_SERIES_ID),
        imdbId = imdbIdExtra,
        contentId = contentId
    )

    internal fun applyDebridSourceProfile(profile: DebridSourceProfile?) {
        if (profile == null) return
        debridProviderExtra = profile.provider
        debridSourceTypeExtra = profile.sourceType
        debridSourceNameExtra = profile.sourceName
        debridLanguagesExtra = profile.languages
        debridQualityExtra = profile.quality
        debridStreamIdExtra = profile.streamId
        debridBingeGroupExtra = profile.bingeGroup
        debridFileIdxExtra = profile.fileIdx
    }

    private fun isSeriesEpisodePlayback(): Boolean {
        val result = contentType == ContentType.SERIES || contentType == ContentType.EPISODE || !seriesTitleExtra.isNullOrBlank()
        Log.d("PlayerActivity", "isSeriesEpisodePlayback: contentType=$contentType, seriesTitle=$seriesTitleExtra -> result=$result")
        return result
    }




    internal fun bindChannelMeta(channelName: String?) {
        pendingChannelName = channelName
        liveOsd?.bindChannel(channelName, channelLogoUrl)
        val nameView = tvChannelName ?: return
        nameView.text = channelName ?: getString(R.string.player_epg_channel_unknown)
        imgChannelLogo?.let { logoView ->
            GlideUtils.loadChannelLogo(logoView, channelLogoUrl)
        }
    }



    override fun requiresAddonProxyPlaybackContext(url: String?): Boolean {
        return directDebridPlayback && debridPlaybackRepository.requiresDirectProxyReadinessCheck(
            url = url,
            provider = debridProviderExtra,
            sourceName = debridSourceNameExtra,
            sourceType = debridSourceTypeExtra
        )
    }

    private fun effectivePlaybackHeadersFor(url: String?): Map<String, String>? {
        return if (requiresAddonProxyPlaybackContext(url)) {
            debridPlaybackRepository.effectiveAddonProxyPlaybackHeaders(streamHeaders)
        } else {
            streamHeaders
        }
    }

    internal fun diagnosticsPlaybackFields(
        url: String? = currentUrl,
        headers: Map<String, String>? = effectivePlaybackHeadersFor(url),
        retry: Int? = retryCount,
        mimeHint: String? = null
    ): Map<String, Any?> {
        return diagnosticsContentFields(
                kind = contentType?.name?.lowercase(Locale.US) ?: "unknown",
                tmdbId = tmdbIdExtra,
                imdbId = imdbIdExtra,
                season = seasonNumberExtra,
                episode = episodeNumberExtra
            ) +
            diagnosticsSourceFields(
                provider = debridProviderExtra,
                sourceType = debridSourceTypeExtra,
                sourceName = debridSourceNameExtra,
                languages = debridLanguagesExtra,
                quality = debridQualityExtra,
                bingeGroup = debridBingeGroupExtra,
                fileIdx = debridFileIdxExtra,
                streamId = debridStreamIdExtra,
                infoHash = debridInfoHashExtra
            ) +
            PlaybackDiagnosticsRecorder.playbackFields(
                context = requireContext(),
                url = url,
                headers = headers,
                playbackSource = playbackSource?.name,
                directDebridPlayback = directDebridPlayback,
                retryCount = retry,
                timeoutMs = timeoutMs,
                mimeHint = mimeHint
            )
    }

    private fun directAddonProxyTimeoutContext(url: String): String? {
        if (!requiresAddonProxyPlaybackContext(url)) return null
        return addonProxyContextKey(url, effectivePlaybackHeadersFor(url))
    }

    override fun hasRepeatedDirectAddonProxyTimeout(url: String): Boolean {
        val context = directAddonProxyTimeoutContext(url) ?: return false
        return addonProxyFailures.hasRepeatedTimeout(context)
    }

    override fun hasRepeatedDirectAddonProxyServerError(error: PlaybackException): Boolean {
        val responseCode = (error.cause as? HttpDataSource.InvalidResponseCodeException)?.responseCode
            ?: return false
        if (responseCode !in 500..599) return false
        val url = currentUrl ?: return false
        val context = directAddonProxyTimeoutContext(url) ?: return false
        return addonProxyFailures.hasRepeatedServerError(context)
    }

    internal fun maybeRecordDirectAddonProxySuccess() {
        val url = currentUrl ?: return
        if (!requiresAddonProxyPlaybackContext(url)) return
        if (!hasRenderedFirstFrameForCurrentSource) return
        val minimumDurationMs = when (contentType) {
            ContentType.MOVIE -> MIN_CREDIBLE_MOVIE_DURATION_MS
            ContentType.EPISODE, ContentType.SERIES -> MIN_CREDIBLE_EPISODE_DURATION_MS
            else -> return
        }
        if ((player?.duration ?: 0L) < minimumDurationMs) return
        val context = directAddonProxyTimeoutContext(url) ?: return
        if (!addonProxyFailures.shouldRecordSuccess(context)) return
        SessionSourcePreference.recordFirstFrame(
            provider = debridProviderExtra ?: debridSourceNameExtra,
            sourceType = debridSourceTypeExtra,
            languages = debridLanguagesExtra,
            quality = debridQualityExtra,
            url = url
        )
    }

    override fun recordDirectAddonProxyFailure() {
        val url = currentUrl ?: return
        if (!requiresAddonProxyPlaybackContext(url)) return
        SessionSourcePreference.recordFailure(
            provider = debridProviderExtra ?: debridSourceNameExtra,
            sourceType = debridSourceTypeExtra,
            languages = debridLanguagesExtra,
            quality = debridQualityExtra,
            url = url
        )
    }

    /**
     * H6: persistent per-addon-family delivery record. Success = first frame rendered
     * (called once per source by the event listener); failure = the source died
     * terminally without EVER producing a frame — a link that played and later stalled
     * is a network story, not an addon story, so it is not counted here.
     */
    internal fun recordAddonReliability(success: Boolean) {
        val providerLabel = debridProviderExtra ?: debridSourceNameExtra ?: return
        val appContext = context?.applicationContext ?: return
        if (success) {
            com.tvonnet.debridxtreamiptv.ui.sources.AddonReliabilityStore.recordSuccess(appContext, providerLabel)
        } else {
            if (hasRenderedFirstFrameForCurrentSource) return
            com.tvonnet.debridxtreamiptv.ui.sources.AddonReliabilityStore.recordFailure(appContext, providerLabel)
        }
    }

    /**
     * P27-e: if this is a shared live session launched from the EPG guide, adopt the guide's
     * running preview player (assign it + take audio focus + cover with its last frame) and
     * return true so [initializePlayer] skips cold init. Overridden only by [LivePlayerFragment];
     * the base default returns false (VOD / non-shared always cold-inits).
     */
    protected open fun adoptSharedLivePlayerIfPresent(streamUrl: String): Boolean = false

    override fun initializePlayer(streamUrl: String) {
        if (isFinishing || isDestroyed) {
            Log.w("PlayerActivity", "initializePlayer aborted: Activity is finishing or destroyed")
            return
        }
        // A new stream is a new question — and without this the diagnosis would libel the app.
        // Every channel change goes BUFFERING→READY exactly as a stall does, so zapping three
        // channels in a minute looked identical to three stalls and would have put "this channel
        // is slow" on screen for a customer who was simply browsing.
        streamHealth.reset()
        try {
            // RULE: Prevent Memory Leaks. ALWAYS release existing player before creating new one.
            if (player != null) {
                Log.i("PlayerActivity", "Releasing existing player before re-initialization")
                releasePlayer("reinitialize")
            }

            // Mi3: use the injected instance — re-constructing SettingsPreferences here
            // re-read prefs from disk on the UI thread on every player init.
            val isSoftwareAudioEnabled = settingsPreferences.isSoftwareAudioEnabled()

            // P27-e: warm hand-off from the Live TV EPG guide (LP-ADOPT) — adopt the guide's
            // already-running preview player instead of reconnecting. Live-only; the whole
            // block lives in LivePlayerFragment.adoptSharedLivePlayerIfPresent (base default
            // false → a VOD/non-shared launch always takes the cold-init path below). When it
            // adopts, it has fully set up the player, so we skip cold init entirely.
            if (!adoptSharedLivePlayerIfPresent(streamUrl)) {
                coldStartPlayer(streamUrl)
            }
            player?.playWhenReady = true
            // player?.play() removed as playWhenReady=true is sufficient
            startStallMonitor()
            // A fresh ExoPlayer was just built — rebind the next-episode trigger to it so the
            // scheduled 87%/-30s message lands on this instance (not the released previous one).
            if (::nextEpisodeManager.isInitialized) nextEpisodeManager.rebindPlayer()

            playerListener = PlayerEventListener(this, session, isSoftwareAudioEnabled)
            playerListener?.let { player?.addListener(it) }

            attachDebugListener()
        } catch (e: Exception) {
            timeoutHandler.removeCallbacks(timeoutRunnable)
            recovery.handleInitializationError(e)
        }
    }

    // The cold-init path: watchdog arming BEFORE the engine build, QoE tracker per
    // instance, prepare-then-seek. Order is load-bearing — do not rearrange.
    private fun coldStartPlayer(streamUrl: String) {
        didPlaybackComplete = false
        hasAppliedIndexOverride = false
        timeoutHandler.removeCallbacks(timeoutRunnable)
        timeoutMs = resolveTimeoutMs(streamUrl)
        timeoutHandler.postDelayed(timeoutRunnable, timeoutMs)
        armWatchdogBaseline()
        hasRenderedFirstFrameForCurrentSource = false

        val requestHeaders = effectivePlaybackHeadersFor(streamUrl)
        PlaybackDiagnosticsRecorder.record(
            requireContext(),
            "player_initialize_started",
            diagnosticsPlaybackFields(streamUrl, requestHeaders)
        )
        if (disableTunnelingForSession) {
            Log.i("PlayerActivity", "Tunneling disabled for this session after video freeze")
        }
        val engine = PlayerEngineFactory(requireContext(), playbackOkHttpClient, settingsPreferences).build(
            config = PlaybackEngineConfig(
                streamUrl = streamUrl,
                contentType = contentType,
                playbackSource = playbackSource,
                requestHeaders = requestHeaders,
                disableTunneling = disableTunnelingForSession,
                preferredAudioLanguage = trackManager.preferredAudioLanguage,
                preferredSubtitleLanguage = trackManager.preferredSubtitleLanguage
            ),
            loadErrorPolicy = playbackLoadErrorPolicy,
            codecSelector = dolbyVisionAwareCodecSelector()
        )
        bandwidthMeter = engine.bandwidthMeter
        player = engine.player.also { playerView.player = it }
        frameRateMatchedForCurrentSource = false

        // LP-D-2: QoE analytics (TTFF / rebuffers / dropped frames / errors).
        // One tracker per player instance; summary flushed in releasePlayer.
        qoeTracker = PlaybackQoeTracker(requireContext()) { qoeMode(contentType, playbackSource) }.also { tracker ->
            player?.addAnalyticsListener(tracker)
            tracker.markPrepareStart()
        }

        val mediaItem = buildMediaItem(streamUrl)
        player?.setMediaItem(mediaItem, /* resetPosition = */ true)
        PlaybackDiagnosticsRecorder.record(
            requireContext(),
            "player_prepare_called",
            diagnosticsPlaybackFields(streamUrl, requestHeaders)
        )
        player?.prepare()
        if (startPositionMs > 0L) {
            player?.seekTo(startPositionMs)
        }
    }

    private fun attachDebugListener() {
        debugListener?.let { player?.removeListener(it) }
        debugListener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                debugOverlay.refresh()
            }
            override fun onPlayerError(error: PlaybackException) {
                if (debugEnabled) debugOverlay.appendError(error.message)
            }
        }
        player?.addListener(debugListener!!)
    }



    // Mi1: showSupportQr removed — never called (terminal failures route through
    // handleTerminalPlaybackFailure).

    internal fun showError(message: String) = Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
    internal fun showToast(message: String) = Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()

    /**
     * VOD Player redesign: wire explicit L/R DPAD focus across the transport row so navigation
     * never dead-ends — critical because exo_play/exo_pause overlap and swap visibility.
     */

    /** VOD Player redesign: styled aspect-ratio selection popup (design ASPECT MENU). */

    // T2.1/M7: restore playback for the retained-player path (pause-without-release
    // cycles: Home→return on VOD/Debrid, system dialogs, multi-window transitions).
    private var wasPlayingBeforePause = false

    override fun onResume() {
        super.onResume(); debugOverlay.start()
        val p = player
        if (p == null) {
            if (PlayerRetentionPolicy.shouldColdStartOnResume(hasPlayer = false, isResolvingDebrid = isResolvingDebrid)) {
                currentUrl?.let { initializePlayer(it) }
            }
        } else {
            startStallMonitor()
            // QA fix: don't audibly resume a stale stream underneath an in-flight
            // debrid re-resolve — the resolver's Success switch takes over playback.
            if (PlayerRetentionPolicy.shouldResumePlaybackOnResume(
                    hasPlayer = true,
                    wasPlayingBeforePause = wasPlayingBeforePause,
                    isResolvingDebrid = isResolvingDebrid
                )
            ) { p.play(); wasPlayingBeforePause = false }
        }
    }
    // Re-arm history for the new foreground segment: onStop's exit-save latches
    // hasRecordedHistory=true, and without this reset a Home→return session would
    // silently skip both the 30s progress heartbeat and the final exit save.
    override fun onStart() { super.onStart(); hasRecordedHistory = false; recovery.registerNetworkCallback(); if (::nextEpisodeManager.isInitialized) { nextEpisodeManager.onStart() }; vodControls.onStart(); progressSaveHandler.removeCallbacks(progressSaveRunnable); progressSaveHandler.postDelayed(progressSaveRunnable, PROGRESS_SAVE_INTERVAL_MS) }
    override fun onPause() { super.onPause(); if (::historyManager.isInitialized) historyManager.saveProgressSnapshot(); wasPlayingBeforePause = player?.isPlaying == true; player?.pause(); timeoutHandler.removeCallbacks(timeoutRunnable); stopStallMonitor() }
    override fun onStop() {
        super.onStop(); dismissActiveTrackDialog(); debugOverlay.stop(); timeoutHandler.removeCallbacks(timeoutRunnable); stopStallMonitor(); vodControls.onStop(); progressSaveHandler.removeCallbacks(progressSaveRunnable); if (::nextEpisodeManager.isInitialized) { nextEpisodeManager.onStop() }; recovery.unregisterNetworkCallback(); historyManager.recordPlaybackHistoryIfNeeded()
        // T2.1 (H1): retain the paused player across short stops for VOD/Debrid so
        // Home→return resumes instantly instead of a 1-3s cold reconnect. LIVE still
        // releases: on max_connections=1 Xtream servers a retained live connection
        // would hold the account's only slot while backgrounded (and a live stream
        // has no resume value anyway — it re-tunes to the live edge).
        if (PlayerRetentionPolicy.shouldReleaseOnStop(contentType, isFinishing)) {
            releasePlayer("on_stop")
        } else {
            // QA fix: the retained path must fully disarm every recovery source, or a
            // backgrounded error (codec reclaimed, socket drop) re-inits playback WITH
            // AUDIO while the app is in the background. releasePlayer did this purge;
            // the retained branch has to do it too.
            retryHandler.removeCallbacksAndMessages(null)
            timeoutHandler.removeCallbacksAndMessages(null)
            reconnectManager.cancelPendingBanner()
        }
    }

    // QA fix: retention is otherwise unbounded — under memory pressure release the
    // retained background player (codec + network) instead of waiting for the OS to
    // reclaim the codec mid-hold. Position is already saved (onStop exit-save +
    // updateLastPlaybackPosition in releasePlayer); onResume rebuilds from currentUrl.
    /** P26: the host Activity forwards its onTrimMemory here (Fragment has no such callback). */
    fun onHostTrimMemory(level: Int) {
        if (PlayerRetentionPolicy.shouldReleaseOnTrimMemory(level, player != null, contentType)) {
            releasePlayer("trim_memory_$level")
        }
    }

    internal fun releasePlayer(reason: String = "unspecified") {
        PlaybackDiagnosticsRecorder.record(
            requireContext(),
            "release_player",
            diagnosticsPlaybackFields() + mapOf("releaseReason" to reason)
        )
        updateLastPlaybackPosition(); timeoutHandler.removeCallbacks(timeoutRunnable); timeoutHandler.removeCallbacks(captureRunnable); reconnectManager.cancelPendingBanner(); retryHandler.removeCallbacksAndMessages(null); epgOverlayUi.cancelAutoHide(); zapDebouncer.cancel(); if (::nextEpisodeManager.isInitialized) { nextEpisodeManager.onStop() }; stopStallMonitor()
        playerListener?.let { player?.removeListener(it) }; playerListener = null
        debugListener?.let { player?.removeListener(it) }; debugListener = null
        qoeTracker?.flushSessionSummary(); qoeTracker = null // LP-D-2: emit session QoE
        player?.stop(); player?.release(); player = null; playerView.player = null
    }

    /**
     * After a pause on live TV the stream resumes where it stopped, drifting behind
     * the broadcast. Viewers expect play = live NOW, so snap to the live edge when
     * more than 30s behind (HLS/DASH only — raw TS has no live window).
     */
    internal fun maybeSnapToLiveEdge() {
        val p = player ?: return
        if (!p.isCurrentMediaItemLive) return
        val offsetMs = p.currentLiveOffset
        if (offsetMs != C.TIME_UNSET && offsetMs > 30_000L) {
            Log.i("PlayerActivity", "Resuming ${offsetMs}ms behind live — snapping to live edge")
            p.seekToDefaultPosition()
        }
    }

    /**
     * Switch the display to a refresh rate that is an integer multiple of the content
     * frame rate (e.g. 23.976fps film → 23.976/47.952Hz instead of judddering on 60Hz).
     * Skipped for live TV: a mode switch blanks the screen 1-2s, unacceptable while zapping.
     */
    internal fun maybeMatchDisplayFrameRate() {
        if (frameRateMatchedForCurrentSource) return
        if (contentType == ContentType.LIVE_TV) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val frameRate = player?.videoFormat?.frameRate ?: return
        if (frameRate <= 0f) return
        frameRateMatchedForCurrentSource = true
        try {
            val display = window.decorView.display ?: return
            val currentMode = display.mode
            val candidates = display.supportedModes.filter {
                it.physicalWidth == currentMode.physicalWidth && it.physicalHeight == currentMode.physicalHeight
            }
            val best = candidates.minWithOrNull(
                compareBy(
                    { (frameRateMismatch(frameRate, it.refreshRate) * 1000).toInt() },
                    { -it.refreshRate }
                )
            ) ?: return
            if (best.modeId == currentMode.modeId) return
            val currentMismatch = frameRateMismatch(frameRate, currentMode.refreshRate)
            val bestMismatch = frameRateMismatch(frameRate, best.refreshRate)
            // Only blank the display when the current mode actually judders and the target doesn't.
            if (currentMismatch < 0.01f || bestMismatch >= currentMismatch) return
            Log.i("PlayerActivity", "Frame-rate match: ${frameRate}fps -> ${best.refreshRate}Hz (mode ${best.modeId})")
            window.attributes = window.attributes.also { it.preferredDisplayModeId = best.modeId }
        } catch (e: Exception) {
            Log.w("PlayerActivity", "Frame-rate matching failed", e)
        }
    }


    override fun onDestroy() { super.onDestroy(); loaderUi.release(); dismissActiveTrackDialog(); liveOsd?.release(); if (::historyManager.isInitialized) historyManager.recordPlaybackHistoryIfNeeded(); releasePlayer("on_destroy"); PlaybackDiagnosticsRecorder.finishSession(requireContext(), "activity_destroyed") }

    internal fun resolveTimeoutMs(url: String): Long = when {
        url.lowercase().contains("mediafusion.elfhosted.com") -> MEDIAFUSION_TIMEOUT_MS
        contentType == ContentType.LIVE_TV -> LIVE_TIMEOUT_MS
        else -> TIMEOUT_MS
    }

    /**
     * P26: the host Activity's finish() override calls this BEFORE super.finish(), preserving
     * the "set the return-to-sources / live-return result on the way out" contract that the
     * Activity's own finish() override used to own. Both the shared-handoff BACK path and the
     * two RecoveryHost members ultimately call requireActivity().finish() → host.finish() → here.
     */
    fun onHostFinishing() {
        if (view == null) return
        exitController.setLiveExitResultIfNeeded()
        exitController.setExitResultIfNeeded()
    }

    /**
     * The real BACK exit: records history, flags manualExit (for return-to-sources), and — for a
     * shared Live TV session — hands the running player back to the guide before finishing.
     * Shared by the onBackPressedDispatcher callback (Live/EPG paths) and the VOD dispatchKeyEvent
     * path (which must consume BACK so the media3 controller can't swallow it).
     */
    /**
     * P27-d: the live+shared exit path — hand the running player back to the EPG guide's
     * mini preview instead of releasing it, so playback continues through the shrink-to-mini
     * transition. Overridden only by [LivePlayerFragment]; the base default returns false so a
     * VOD instance takes the plain releasePlayer→finish path below (byte-identical to the old
     * `sharedLiveSession && LIVE_TV && player != null` guard, which was never true off live).
     */
    protected open fun performLiveSharedExitIfNeeded(): Boolean = false

    internal fun performBackExit() {
        manualExit = true
        updateLastPlaybackPosition()
        historyManager.recordPlaybackHistoryIfNeeded()
        if (performLiveSharedExitIfNeeded()) return
        releasePlayer()
        finish()
    }


    internal fun initBrowserOverlay() {
        channelBrowser.bind(
            scope = lifecycleScope,
            onCategorySelected = { categoryId -> viewModel.selectBrowserCategory(categoryId) },
            stateFlow = viewModel.browserState
        )
    }

    // C6: the routing shell lives in PlayerInputRouter; null = the host passes to super.
    // P26: these were Activity overrides — the thin host forwards its callbacks here.
    /**
     * The ONE skip. Both the transport buttons and the phone's double-tap call it, so there is a
     * single seek path — which is what the direction rule above depends on.
     *
     * The double-tap used to send KEYCODE_MEDIA_FAST_FORWARD instead, to reuse exactly this code
     * by going the long way round. It did not work: media3's PlayerView answers a media key
     * arriving while the controller is hidden by SHOWING THE CONTROLLER and swallowing the key, so
     * the first double-tap only revealed the chrome and nothing moved. Calling it directly keeps
     * the shared implementation without the dispatch that ate it.
     */
    internal fun seekStep(forward: Boolean) {
        player?.let {
            it.setSeekParameters(if (forward) SeekParameters.NEXT_SYNC else SeekParameters.PREVIOUS_SYNC)
            if (forward) it.seekForward() else it.seekBack()
        }
    }

    fun hostDispatchKeyEvent(event: KeyEvent): Boolean? = inputRouter.dispatchKeyEvent(event)

    /**
     * M9b: every touch on the player, observed from the Activity so an overlay stacked above the
     * video cannot hide it. The base ignores them; [LivePlayerFragment] turns them into keys.
     */
    open fun hostTouchEvent(event: android.view.MotionEvent) = Unit

    fun hostKeyLongPress(keyCode: Int, event: KeyEvent): Boolean? = inputRouter.onKeyLongPress(keyCode, event)

    fun hostUserInteraction() {
        // Live/PiP never auto-show the controller chrome on interaction.
        //
        // M10c: nor does a touch device, and that one was a real defect. `onUserInteraction` fires
        // on ACTION_DOWN, so a tap showed the controller — and then PlayerView's own
        // `controllerHideOnTouch` toggle fired on ACTION_UP, saw it visible, and hid it again. Show
        // and hide inside a single tap, so on a phone the VOD chrome never appeared at all. On TV
        // nobody taps, which is why it went unseen. A touch device already has a working
        // show/hide — PlayerView's tap toggle — and this must not fight it.
        val chromeSuppressed = contentType == ContentType.LIVE_TV || isInPictureInPictureMode ||
            !resources.getBoolean(R.bool.ui_uses_dpad_focus)
        val nextEpisodePromptShowing =
            ::nextEpisodeManager.isInitialized && nextEpisodeManager.isPromptVisible
        if (chromeSuppressed || !playerView.useController || nextEpisodePromptShowing) {
            return
        }
        
        // Fix: Don't show controller if browser is open
        if (::episodeBrowserController.isInitialized && episodeBrowserController.isVisible()) {
            playerView.hideController()
            return
        }
        
        if (!playerView.isControllerFullyVisible) {
            showControllerWithSmartFocus()
        }
    }


    internal fun supportsPictureInPicture(): Boolean = pipController.isSupported()

    @RequiresApi(Build.VERSION_CODES.O)
    internal fun enterPictureInPictureModeInternal() {
        if (!supportsPictureInPicture() || isInPictureInPictureMode) return
        try {
            player?.let { wasPlayingBeforePiP = it.playWhenReady }
            if (pipController.enter(playerView.width, playerView.height)) hideUiForPiP() else showToast("PiP not available")
        } catch (e: Exception) {
            Log.w("PlayerActivity", "Enter PiP failed", e)
            showToast("PiP failed")
        }
    }

    // NOTE: snapshot (non-latching) — the old recordPlaybackHistoryIfNeeded() here latched
    // hasRecordedHistory on PiP entry, so everything watched IN PiP was never saved and
    // resume went back to the PiP-entry position.
    private fun hideUiForPiP() { playerView.hideController(); if (::episodeBrowserController.isInitialized) episodeBrowserController.hide(); epgOverlay?.isVisible = false; vodInfoOverlay?.isVisible = false; liveOsd?.hideForPip(); supportActionBar?.hide(); historyManager.saveProgressSnapshot() }
    private fun showUiForNormalMode() { if (!isInPictureInPictureMode) { supportActionBar?.show(); liveOsd?.show(); if (contentType == ContentType.LIVE_TV) updateOverlayVisibility() else updateVodOverlayVisibility() } }

    /** P26: the thin host forwards its onPictureInPictureModeChanged here. */
    fun hostPipModeChanged(inPictureInPicture: Boolean) { this.isInPictureInPictureMode = inPictureInPicture; if (inPictureInPicture) hideUiForPiP() else showUiForNormalMode() }

    internal fun updateOverlayVisibility() = epgOverlayUi.applyVisibility()

    /** The rule the overlay asks for: the OSD, PiP and the controller all veto it. */
    private fun shouldShowEpgOverlay(): Boolean {
        if (liveOsd != null) return false
        val hasContent = epgOverlay != null
        return when (contentType) {
            ContentType.LIVE_TV -> hasContent && epgOverlayUi.mode != EpgOverlayMode.HIDDEN && !isInPictureInPictureMode
            else -> hasContent && isControllerVisible && !isInPictureInPictureMode
        }
    }
    private fun updateVodOverlayVisibility() { setVodOverlayVisible(contentType != ContentType.LIVE_TV && isControllerVisible && !isInPictureInPictureMode) }

    private fun setOverlayVisible(visible: Boolean) { val o = epgOverlay ?: return; if (visible) { if (o.isVisible) return; o.alpha = 0f; o.isVisible = true; o.animate().alpha(1f).setDuration(160).start() } else { if (!o.isVisible) return; o.animate().alpha(0f).setDuration(140).withEndAction { o.isVisible = false }.start() } }
    private fun setVodOverlayVisible(visible: Boolean) { val o = vodInfoOverlay ?: return; if (visible) { if (o.isVisible) return; o.alpha = 0f; o.isVisible = true; o.animate().alpha(1f).setDuration(160).start() } else { if (!o.isVisible) return; o.animate().alpha(0f).setDuration(140).withEndAction { o.isVisible = false }.start() } }

    internal fun showEpgOverlay(mode: EpgOverlayMode, pinned: Boolean) {
        if (contentType != ContentType.LIVE_TV) return
        epgOverlayUi.show(mode, pinned)
    }
    internal fun hideEpgOverlay() = epgOverlayUi.hide()
    internal fun toggleEpgOverlayPinned() = epgOverlayUi.togglePinned()

    internal fun formatTimeRangeCompact(program: com.tvonnet.debridxtreamiptv.data.local.entity.EpgEntity): String = "${timeFormatter.format(Date(program.start))}-${timeFormatter.format(Date(program.stop))}"






    private fun updateLastPlaybackPosition() { lastPlaybackPositionMs = player?.currentPosition ?: lastPlaybackPositionMs }
    // P26c: the exit-with-result / terminal-failure cluster now lives in PlayerExitController.
    // finish() and these two C1 RecoveryHost members forward to it. `performBackExit` stays here
    // (shared), but its live+shared hand-back moved to LivePlayerFragment in P27-d
    // (`performLiveSharedExitIfNeeded` / `handBackSharedPlayerIfNeeded`).
    override fun finishWithReturnToSources(autoPlayNext: Boolean, reason: String?): Boolean =
        exitController.finishWithReturnToSources(autoPlayNext, reason)

    override fun handleTerminalPlaybackFailure(reason: String, preferReturnToSources: Boolean) =
        exitController.handleTerminalPlaybackFailure(reason, preferReturnToSources)

    /**
     * If audio is playing (STATE_READY) but no video frame has rendered, the HW decoder is
     * decoding audio while producing no visible video — a known tunneling black-screen on
     * some SoCs (e.g. MTK) for high-bitrate/4K HEVC. Reinit once without tunneling at the
     * current position. If it's still black afterwards, the video is genuinely undecodable.
     */
    private fun checkBlackVideoFallback() {
        if (isFinishing || isDestroyed) return
        val p = player ?: return
        if (hasRenderedFirstFrameForCurrentSource || blackVideoFallbackTried) return
        if (contentType == ContentType.LIVE_TV || disableTunnelingForSession) return
        if (!p.isPlaying) return // still buffering — the buffer watchdog handles that
        blackVideoFallbackTried = true
        disableTunnelingForSession = true
        Log.w("PlayerActivity", "Audio playing but no video frame — reinitializing without tunneling (black-video fallback)")
        PlaybackDiagnosticsRecorder.record(
            requireContext(),
            "black_video_tunneling_retry",
            diagnosticsPlaybackFields() + mapOf("positionMs" to p.currentPosition)
        )
        if (p.currentPosition > 1000L) startPositionMs = p.currentPosition
        player?.release(); player = null
        retryHandler.postDelayed({ currentUrl?.let { initializePlayer(it) } }, 250L)
    }

    /**
     * A [MediaCodecSelector] that, when the display cannot present Dolby Vision, returns NO
     * DV decoder for a `video/dolby-vision` stream. media3 then uses the HEVC decoders it
     * already gathers for the alternative mime type, i.e. it decodes the DV base layer
     * (HDR10/SDR for DV profile 7/8) — playable instead of a black screen. DV-capable
     * displays keep true Dolby Vision.
     */
    private fun dolbyVisionAwareCodecSelector(): androidx.media3.exoplayer.mediacodec.MediaCodecSelector {
        val dvSupported = isDolbyVisionDisplaySupported()
        return androidx.media3.exoplayer.mediacodec.MediaCodecSelector { mimeType, secure, tunneling ->
            if (mimeType == MimeTypes.VIDEO_DOLBY_VISION && !dvSupported) {
                emptyList()
            } else {
                androidx.media3.exoplayer.mediacodec.MediaCodecSelector.DEFAULT
                    .getDecoderInfos(mimeType, secure, tunneling)
            }
        }
    }

    private fun isDolbyVisionDisplaySupported(): Boolean = try {
        val disp = if (Build.VERSION.SDK_INT >= 30) requireActivity().display else @Suppress("DEPRECATION") requireActivity().windowManager.defaultDisplay
        @Suppress("DEPRECATION")
        (disp?.hdrCapabilities?.supportedHdrTypes ?: IntArray(0))
            .any { it == android.view.Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION }
    } catch (e: Exception) {
        Log.w("PlayerActivity", "Dolby Vision capability query failed; assuming unsupported", e)
        false
    }

    /** P11 shim: the loader needs the launch extras, so the Activity supplies them. */
    internal fun showCinematicLoader() = loaderUi.show(
        rawTitle = seriesTitleExtra?.takeIf { it.isNotBlank() } ?: originalTitle,
        backdropUrl = backdropUrlExtra?.takeIf { it.isNotBlank() } ?: posterUrlExtra?.takeIf { it.isNotBlank() }
    )

    private fun captureManualTrackSelection() {
        val p = player ?: return
        val seriesId = intent.getStringExtra(PlayerActivity.EXTRA_SERIES_ID) ?: tmdbIdExtra ?: imdbIdExtra
        trackManager.captureManualTrackSelection(p, debridInfoHashExtra, seriesId)
        PlaybackDiagnosticsRecorder.record(
            requireContext(),
            "track_selected",
            diagnosticsPlaybackFields() + trackDiagnosticsFields(p.currentTracks)
        )
    }

    internal fun applyTrackIndexOverrides() {
        val p = player ?: return
        val seriesId = intent.getStringExtra(PlayerActivity.EXTRA_SERIES_ID) ?: tmdbIdExtra ?: imdbIdExtra
        trackManager.applyTrackIndexOverrides(p, debridInfoHashExtra, seriesId)
    }

}
