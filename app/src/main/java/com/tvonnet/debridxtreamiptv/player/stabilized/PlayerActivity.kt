package com.tvonnet.debridxtreamiptv.player.stabilized

import android.app.Activity
import android.app.ActivityManager
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.viewModels
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.bumptech.glide.Glide
import androidx.media3.common.AudioAttributes
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
import kotlinx.coroutines.launch
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
import com.tvonnet.debridxtreamiptv.ui.series.SeriesDetailActivity
import com.tvonnet.debridxtreamiptv.ui.vod.MovieDetailActivity

@UnstableApi
@AndroidEntryPoint
class PlayerActivity : AppCompatActivity() {
    private val viewModel: PlayerViewModel by viewModels()

    @Inject
    lateinit var okHttpClient: OkHttpClient

    // Playback-tuned client (longer read timeout) for the video DataSource; the
    // general okHttpClient above is still used for non-playback work (Glide, etc.).
    @Inject
    @javax.inject.Named("playback")
    lateinit var playbackOkHttpClient: OkHttpClient

    @Inject
    lateinit var settingsPreferences: SettingsPreferences

    @Inject
    lateinit var watchedStateRepository: WatchedStateRepository

    @Inject
    lateinit var debridPlaybackRepository: DebridPlaybackRepository

    private val prefs by lazy { CredentialsPreferences(this) }

    private var isInPictureInPictureMode = false
    private var wasPlayingBeforePiP = true

    /** P9: PiP capability + entry. */
    private val pipController = PlayerPipController(this)

    /** P9: the in-player X-Ray panel (toggle, animation, metadata/cast rendering). */
    private val xrayController: PlayerXRayController by lazy {
        PlayerXRayController(
            activity = this,
            playerView = playerView,
            isSeriesPlayback = { isSeriesEpisodePlayback() },
            // Guards stay exactly as they were before P9: nothing happens while the
            // episode browser has not been initialised yet.
            showEpisodeBrowserIfHidden = {
                if (::episodeBrowserController.isInitialized && !episodeBrowserController.isVisible()) {
                    showEpisodeBrowser()
                }
            },
            hideEpisodeBrowserIfVisible = {
                if (::episodeBrowserController.isInitialized && episodeBrowserController.isVisible()) {
                    episodeBrowserController.hide()
                }
            },
            fallbackTitle = { originalTitle }
        )
    }

    internal var player: ExoPlayer? = null
    private lateinit var playerView: PlayerView

    // True when this session was launched from the Live TV EPG guide with a shared
    // preview player to adopt (seamless mini <-> fullscreen continuity).
    private val sharedLiveSession by lazy { intent.getBooleanExtra(EXTRA_SHARED_LIVE_PLAYER, false) }
    private var sharedExitInProgress = false
    private lateinit var watchHistoryPrefs: WatchHistoryPreferences
    internal lateinit var historyManager: PlayerHistoryManager

    private var retryCount = 0
    private val maxRetries = 5 
    internal var currentUrl: String? = null
    private var streamHeaders: Map<String, String>? = null
    private var subtitleEntries: List<String> = emptyList()
    private var activeTrackDialog: Dialog? = null
    private var timeoutMs: Long = TIMEOUT_MS
    private val timeoutHandler = Handler(Looper.getMainLooper())
    private val timeoutRunnable = Runnable { handleTimeout() }
    private val retryHandler = Handler(Looper.getMainLooper())
    private var frameRateMatchedForCurrentSource = false

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
    private var watchdogExtensions = 0
    private var watchdogBufferedPosAtArm = -1L
    private fun armWatchdogBaseline() {
        watchdogBufferedPosAtArm = player?.bufferedPosition ?: -1L
        watchdogExtensions = 0
    }

    // ── channel-zap debounce (LP-B-1) ───────────────────────────────────────
    // The OSD/index advances on every keypress; the actual tune is coalesced to
    // the LAST target after the user settles (ZAP_DEBOUNCE_MS of no new zap).
    /** P18: LP-B-1 — the OSD advances per keypress, the real connect is debounced. */
    private val zapDebouncer = PlayerZapDebouncer(
        handler = Handler(Looper.getMainLooper()),
        debounceMs = ZAP_DEBOUNCE_MS,
        onCommit = { target -> tuneToZapChannel(target) }
    )

    // Fail fast on permanently broken sources so app-level fallback kicks in quickly;
    // transient errors back off exponentially with jitter to avoid retry storms.
    private val playbackLoadErrorPolicy = object : DefaultLoadErrorHandlingPolicy() {
        override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
            val responseCode =
                (loadErrorInfo.exception as? HttpDataSource.InvalidResponseCodeException)?.responseCode
            if (responseCode == 400 || responseCode == 401 || responseCode == 403 ||
                responseCode == 404 || responseCode == 405 || responseCode == 410 ||
                responseCode == 416 || responseCode == 451
            ) {
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

    private fun retryBackoffDelayMs(): Long =
        (1000L shl (retryCount - 1).coerceIn(0, 3)) + (0..400).random()

    // P10: thin delegators — the recovery subsystems that call these still live here.
    private fun canAttemptReconnect(): Boolean = reconnectManager.canAttemptReconnect()
    private fun resetReconnectBudget() = reconnectManager.resetBudget()
    private fun showReconnectingBanner() = reconnectManager.showBanner()
    private fun hideReconnectingBanner() = reconnectManager.hideBanner()
    private fun maybeUpdateNetworkQuality() =
        reconnectManager.maybeUpdateNetworkQuality(bandwidthMeter?.bitrateEstimate)

    /** P14: remembers which addon-proxy context last failed / already succeeded. */
    private val addonProxyFailures = AddonProxyFailureTracker()
    private var hasRenderedFirstFrameForCurrentSource = false
    // Black-video fallback: some HW decoders (e.g. MTK on high-bitrate/4K HEVC) play audio
    // but never render video under tunneling. If no frame arrives while audio is playing,
    // reinit once without tunneling.
    private var blackVideoFallbackTried = false
    private val blackVideoCheckRunnable = Runnable { checkBlackVideoFallback() }
    private var isControllerVisible = false

    private val overlayHandler = Handler(Looper.getMainLooper())
    /** P18: the legacy EPG strip's mode/pin/auto-hide (only used without the Live OSD). */
    private val epgOverlayUi: PlayerEpgOverlay by lazy {
        PlayerEpgOverlay(
            handler = overlayHandler,
            timeoutMs = OVERLAY_TIMEOUT,
            overlay = { epgOverlay },
            isLive = { contentType == ContentType.LIVE_TV },
            shouldBeVisible = { shouldShowEpgOverlay() }
        )
    }
    private var isSwitching = false
    private var layoutDebugOverlay: View? = null
    private var tvDebugInfo: TextView? = null
    private var debugEnabled = false
    private var switchCount = 0
    private var debugListener: Player.Listener? = null
    private var playerListener: Player.Listener? = null
    private val captureRunnable = Runnable { captureManualTrackSelection() }
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

    private var epgOverlay: View? = null
    private var imgChannelLogo: ImageView? = null
    private var tvChannelNumber: TextView? = null
    internal var tvChannelName: TextView? = null
    private var tvNowTitle: TextView? = null
    private var tvNowTime: TextView? = null
    private var progressNow: ProgressBar? = null
    private var tvEpgStatus: TextView? = null
    private var tvNowCardTime: TextView? = null
    private var tvNowCardTitle: TextView? = null
    private var cardNext1: View? = null
    private var tvNext1Time: TextView? = null
    private var tvNext1Title: TextView? = null
    private var cardNext2: View? = null
    private var tvNext2Time: TextView? = null
    private var tvNext2Title: TextView? = null
    private var cardNext3: View? = null
    private var tvNext3Time: TextView? = null
    private var tvNext3Title: TextView? = null

    private var vodInfoOverlay: View? = null
    private var imgVodArtwork: ImageView? = null
    private var tvVodTitle: TextView? = null
    private var tvVodSubtitle: TextView? = null

    /** P11: the cinematic pre-playback loader owns its views + animators. */
    private val loaderUi = PlayerLoaderUi(hideReconnectBanner = { reconnectManager.hideBanner() })
    private val stallHandler = Handler(Looper.getMainLooper())
    /** P15: position-progress strikes (detection only — the reaction stays here). */
    private val stallDetector = PlayerStallDetector()
    private var lastBufferingStartMs = 0L
    // Video-freeze watchdog: position keeps advancing with audio when only the
    // video decoder dies (e.g. HEVC GuiExt alloc failures on MTK Fire TVs), so
    // rendered-frame progress is tracked separately from position progress.
    /** P15: rendered-frame progress ("audio plays, video frozen"). */
    private val freezeDetector = VideoFreezeDetector()
    private var disableTunnelingForSession = false
    // How many times we've recovered from an AudioTrack-allocation failure this
    // source (reset on a clean READY / new source). Bounds the audio-device retry.
    private var audioSinkRecoveryCount = 0
    // Some providers' live TS streams carry backward PTS jumps right after start;
    // the decoder renders the first frame and then drops everything as "late".
    // Re-preparing the same URL resets the timestamp adjuster past the bad region.
    private var liveFreezeReprepares = 0
    private var lastFreezeRecoveryUrl: String? = null
    private var lastFreezeRecoveryAtMs = 0L
    // Provider socket drops surface as STATE_ENDED; reconnect in place, but cap
    // it so an instantly-dropping stream degrades to the error path, not a loop.
    private var endedReconnects = 0
    private var lastEndedReconnectAtMs = 0L

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
    private var qoeTracker: PlaybackQoeTracker? = null

    private val stallRunnable = object : Runnable {
        override fun run() {
            checkForStall()
            stallHandler.postDelayed(this, 3000)
        }
    }

    internal var channelLogoUrl: String? = null
    internal var contentType: ContentType? = null
    internal var playbackSource: PlaybackSource = PlaybackSource.IPTV
    internal var directDebridPlayback: Boolean = false
    internal var contentId: String? = null
    private var returnToSourcesOnExit: Boolean = false
    private var didPlaybackComplete: Boolean = false
    private var manualExit: Boolean = false
    private var exitResultHandled: Boolean = false
    private var lastPlaybackPositionMs: Long = 0L
    internal var posterUrlExtra: String? = null
    internal var backdropUrlExtra: String? = null
    internal var tmdbIdExtra: String? = null
    internal var imdbIdExtra: String? = null
    internal var seriesTitleExtra: String? = null
    internal var episodeTitleExtra: String? = null
    internal var seasonNumberExtra: Int? = null
    internal var episodeNumberExtra: Int? = null
    internal var debridInfoHashExtra: String? = null
    internal var debridMagnetExtra: String? = null
    internal var debridProviderExtra: String? = null
    internal var debridSourceTypeExtra: String? = null
    internal var debridSourceNameExtra: String? = null
    internal var debridLanguagesExtra: List<String>? = null
    internal var debridQualityExtra: String? = null
    internal var debridStreamIdExtra: String? = null
    internal var debridBingeGroupExtra: String? = null
    internal var debridFileIdxExtra: Int? = null
    internal var expiresAtExtra: Long? = null
    internal var originalTitle: String? = null
    internal var hasRecordedHistory = false
    private val timeFormatter = SimpleDateFormat("HH:mm", Locale.getDefault())
    private var startPositionMs: Long = 0L
    private var hasShownOverlayOnce = false
    private var lastOverlayState: PlayerOverlayUiState? = null
    internal var pendingChannelName: String? = null
    internal var currentEpgChannelId: String? = null
    internal var liveCategoryId: String? = null
    internal var baseServerUrl: String? = null
    private var liveChannelIds: ArrayList<String>? = null
    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var networkAvailable = true
    private var isResolvingDebrid = false
    private var hasAppliedIndexOverride = false
    private lateinit var trackManager: PlayerTrackManager
    private lateinit var episodeBrowserController: EpisodeBrowserController
    private var currentZapRequestId = 0L
    // Cinematic live OSD (design_handoff Live Player); non-null only for LIVE_TV.
    private var liveOsd: LivePlayerOsdManager? = null

    // Persistent "Reconnecting… (n/N)" pill (QA fix 3). Shown whenever ANY recovery
    // subsystem triggers a reconnect; hidden on the first rendered frame / READY.
    private var reconnectingBanner: View? = null
    private var reconnectingBannerText: TextView? = null

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

    companion object {
        const val EXTRA_STREAM_URL = "STREAM_URL"
        const val EXTRA_STREAM_HEADERS = "STREAM_HEADERS"
        const val EXTRA_STREAM_TITLE = "STREAM_TITLE"
        const val EXTRA_CHANNEL_NAME = "CHANNEL_NAME"
        const val EXTRA_CHANNEL_LOGO = "CHANNEL_LOGO"
        const val EXTRA_EPG_CHANNEL_ID = "EPG_CHANNEL_ID"
        const val EXTRA_START_POSITION = "START_POSITION"
        const val EXTRA_CONTENT_ID = "CONTENT_ID"
        const val EXTRA_CONTENT_TYPE = "CONTENT_TYPE"
        const val EXTRA_POSTER_URL = "POSTER_URL"
        const val EXTRA_BACKDROP_URL = "BACKDROP_URL"
        const val EXTRA_PLAYBACK_SOURCE = "PLAYBACK_SOURCE"
        const val EXTRA_LIVE_CATEGORY_ID = "LIVE_CATEGORY_ID"
        const val EXTRA_LIVE_CHANNEL_IDS = "LIVE_CHANNEL_IDS"
        const val EXTRA_BASE_SERVER_URL = "BASE_SERVER_URL"
        const val EXTRA_LIVE_RETURN_CHANNEL_ID = "LIVE_RETURN_CHANNEL_ID"
        const val EXTRA_SHARED_LIVE_PLAYER = "SHARED_LIVE_PLAYER"
        const val EXTRA_LIVE_RETURN_CHANNEL_NAME = "LIVE_RETURN_CHANNEL_NAME"
        const val EXTRA_LIVE_RETURN_CHANNEL_LOGO = "LIVE_RETURN_CHANNEL_LOGO"
        const val EXTRA_LIVE_RETURN_EPG_CHANNEL_ID = "LIVE_RETURN_EPG_CHANNEL_ID"
        const val EXTRA_LIVE_RETURN_STREAM_URL = "LIVE_RETURN_STREAM_URL"
        const val EXTRA_LIVE_RETURN_CATEGORY_ID = "LIVE_RETURN_CATEGORY_ID"
        private const val TIMEOUT_MS = 25000L
        private const val PROGRESS_SAVE_INTERVAL_MS = 30_000L
        private const val MEDIAFUSION_TIMEOUT_MS = 35000L
        // Live viewers zap away from dead streams fast — detect failure fast too.
        private const val LIVE_TIMEOUT_MS = 12000L
        private const val VIDEO_FREEZE_THRESHOLD_MS = 8000L
        // Live viewers zap within seconds — catch a frozen first frame fast.
        private const val LIVE_VIDEO_FREEZE_THRESHOLD_MS = 4000L
        private const val MAX_LIVE_FREEZE_REPREPARES = 2
        // Dead/looping provider restreams reconnect forever without this cap:
        // the server replays the same GOP on every connection, so retry #3+
        // can never succeed. Fail fast with a clear message instead.
        private const val LIVE_MAX_RETRIES = 2
        // How long audio may play with no video frame before the black-video fallback
        // (reinit without tunneling) kicks in.
        private const val BLACK_VIDEO_CHECK_MS = 5_000L
        // Unified reconnect budget (QA fix 2): aggregate ceiling across ALL recovery
        // subsystems so a dead channel can't chain error-retry + ENDED-reconnect +
        // freeze re-prepare into a burst against a WAF/max_connections=1 server.
        // Debounce a channel-tune (LP-B-1): only commit to stop()/prepare()/connect
        // ~350ms after the last zap keypress, so holding or spamming channel-up/down
        // updates the OSD instantly but issues ONE provider connection when the user
        // settles — instead of a storm of socket cycles that trips the WAF and
        // (because each tune resets the reconnect budget) never trips our own throttle.
        private const val ZAP_DEBOUNCE_MS = 350L
        // Progress-aware watchdog: extra 12s windows granted while the buffer is
        // still growing (slow-but-alive connection), before tearing the player down.
        private const val MAX_WATCHDOG_EXTENSIONS = 2
        // A just-dropped provider socket usually needs a moment before it accepts a
        // new connection — an immediate retry mostly fails and burns a budget slot.
        private const val LIVE_ENDED_RECONNECT_DELAY_MS = 1500L
        // AudioTrack-allocation recovery (device-verified 2026-07-18, dumpsys
        // media.audio_flinger). Two distinct failures share the "AudioTrack init failed /
        // Cannot create AudioTrack" symptom:
        //   (a) tunneled HW-AV-sync slot briefly held by a prior session — ONE re-init
        //       without tunneling recovers it (the rare, genuinely transient case);
        //   (b) AudioFlinger output SATURATED ("no more tracks available", errno -12) —
        //       ~80 leaked tracks accumulated across app restarts because this Amlogic
        //       firmware never reclaims released/dead-process AudioTracks. NOTHING an
        //       in-app re-init can do here; each attempt only LEAKS ~5 more tracks (media3
        //       retries the AudioTrack build 5× per init). Needs a device reboot.
        // So: exactly ONE recovery attempt (covers case a), then fail fast with an
        // actionable message (case b) instead of a leak-amplifying retry spiral.
        private const val AUDIO_SINK_MAX_RECOVERIES = 1
        private const val AUDIO_SINK_COOLOFF_MS = 900L
        // Fixed cool-off for HTTP 429 when the server sends no Retry-After (QA fix 4).
        private const val HTTP_429_COOLOFF_MS = 20_000L
        private const val OVERLAY_TIMEOUT = 6000L
        private const val MIN_PROGRESS_TO_TRACK_MS = 15_000L
        private const val MIN_DURATION_TO_TRACK_MS = 60_000L
        private const val MIN_CREDIBLE_MOVIE_DURATION_MS = 30 * 60_000L
        private const val MIN_CREDIBLE_EPISODE_DURATION_MS = 5 * 60_000L
        private const val RETURN_TO_SOURCES_THRESHOLD_MS = 60_000L
        private const val COMPLETION_THRESHOLD_RATIO = 0.95f
        private const val STALL_THRESHOLD_MS = 12000L
        private const val LOW_RAM_STALL_THRESHOLD_MS = 15000L
        private const val LOW_RAM_STALL_STRIKES = 2
        // M3 parity: IPTV VOD gets the same multi-strike tolerance as Debrid so a
        // single transiently-slow 12s window no longer forces a full teardown.
        private const val NON_DEBRID_READY_STALL_STRIKES = 3
        // Live recovers faster than VOD (frozen live edge), but no single-strike
        // hair-trigger either.
        private const val LIVE_READY_STALL_STRIKES = 2
        private const val DEBRID_READY_STALL_STRIKES = 3
        private const val DIRECT_DEBRID_READY_STALL_STRIKES = 4
        private const val DIRECT_DEBRID_LOW_RAM_STALL_STRIKES = 3
        private const val NETWORK_RECOVERY_BUFFER_MS = 5000L
        const val EXTRA_SERIES_ID = "EXTRA_SERIES_ID"
        const val EXTRA_SEASON_NUM = "EXTRA_SEASON_NUM"
        const val EXTRA_EPISODE_NUM = "EXTRA_EPISODE_NUM"
        const val EXTRA_TMDB_ID = "EXTRA_TMDB_ID"
        const val EXTRA_IMDB_ID = "EXTRA_IMDB_ID"
        const val EXTRA_SERIES_TITLE = "EXTRA_SERIES_TITLE"
        const val EXTRA_EPISODE_TITLE = "EXTRA_EPISODE_TITLE"
        const val EXTRA_DEBRID_INFOHASH = "EXTRA_DEBRID_INFOHASH"
        const val EXTRA_DEBRID_MAGNET = "EXTRA_DEBRID_MAGNET"
        const val EXTRA_DIRECT_DEBRID_PLAYBACK = "EXTRA_DIRECT_DEBRID_PLAYBACK"
        const val EXTRA_DEBRID_PROVIDER = "EXTRA_DEBRID_PROVIDER"
        const val EXTRA_DEBRID_SOURCE_TYPE = "EXTRA_DEBRID_SOURCE_TYPE"
        const val EXTRA_DEBRID_SOURCE_NAME = "EXTRA_DEBRID_SOURCE_NAME"
        const val EXTRA_DEBRID_LANGUAGES = "EXTRA_DEBRID_LANGUAGES"
        const val EXTRA_DEBRID_QUALITY = "EXTRA_DEBRID_QUALITY"
        const val EXTRA_DEBRID_STREAM_ID = "EXTRA_DEBRID_STREAM_ID"
        const val EXTRA_DEBRID_BINGE_GROUP = "EXTRA_DEBRID_BINGE_GROUP"
        const val EXTRA_DEBRID_FILE_IDX = "EXTRA_DEBRID_FILE_IDX"
        const val EXTRA_EXPIRES_AT = "EXTRA_EXPIRES_AT"
        const val EXTRA_RETURN_TO_SOURCES = "EXTRA_RETURN_TO_SOURCES"
        const val EXTRA_FAILED_STREAM_ID = "EXTRA_FAILED_STREAM_ID"
        const val EXTRA_FAIL_REASON = "EXTRA_FAIL_REASON"
        const val EXTRA_AUTO_PLAY_NEXT = "EXTRA_AUTO_PLAY_NEXT"
        const val EXTRA_SUBTITLE_ENTRIES = "EXTRA_SUBTITLE_ENTRIES"
        const val EXTRA_OPENED_FROM_PLAYBACK_FAILURE = "EXTRA_OPENED_FROM_PLAYBACK_FAILURE"

        fun createIntent(
            context: Context,
            streamUrl: String,
            title: String? = null,
            channelName: String? = null,
            channelLogo: String? = null,
            epgChannelId: String? = null,
            startPositionMs: Long? = null,
            contentId: String? = null,
            contentType: ContentType? = null,
            playbackSource: PlaybackSource? = null,
            posterUrl: String? = null,
            backdropUrl: String? = null,
            liveCategoryId: String? = null,
            liveChannelIds: ArrayList<String>? = null,
            baseServerUrl: String? = null,
            headers: Map<String, String>? = null,
            tmdbId: String? = null,
            imdbId: String? = null,
            seriesTitle: String? = null,
            episodeTitle: String? = null,
            seasonNumber: Int? = null,
            episodeNumber: Int? = null,
            debridInfoHash: String? = null,
            debridMagnet: String? = null,
            directDebridPlayback: Boolean = false,
            debridProvider: String? = null,
            debridSourceType: String? = null,
            debridSourceName: String? = null,
            debridLanguages: List<String>? = null,
            debridQuality: String? = null,
            debridStreamId: String? = null,
            debridBingeGroup: String? = null,
            debridFileIdx: Int? = null,
            subtitles: List<String>? = null,
            expiresAt: Long? = null,
            seriesId: String? = null,
            sharedLivePlayer: Boolean = false
        ): Intent {
            return Intent(context, PlayerActivity::class.java).apply {
                putExtra(EXTRA_STREAM_URL, streamUrl)
                putExtra(EXTRA_STREAM_TITLE, title)
                putExtra(EXTRA_CHANNEL_NAME, channelName)
                putExtra(EXTRA_CHANNEL_LOGO, channelLogo)
                putExtra(EXTRA_EPG_CHANNEL_ID, epgChannelId)
                putExtra(EXTRA_CONTENT_ID, contentId)
                putExtra(EXTRA_CONTENT_TYPE, contentType?.name)
                putExtra(EXTRA_PLAYBACK_SOURCE, playbackSource?.name)
                putExtra(EXTRA_POSTER_URL, posterUrl)
                putExtra(EXTRA_BACKDROP_URL, backdropUrl)
                liveCategoryId?.let { putExtra(EXTRA_LIVE_CATEGORY_ID, it) }
                liveChannelIds?.let { putStringArrayListExtra(EXTRA_LIVE_CHANNEL_IDS, it) }
                baseServerUrl?.let { putExtra(EXTRA_BASE_SERVER_URL, it) }
                putExtra(EXTRA_SERIES_ID, seriesId)
                putExtra(EXTRA_SHARED_LIVE_PLAYER, sharedLivePlayer)
                startPositionMs?.let { putExtra(EXTRA_START_POSITION, it) }
                headers?.let { putExtra(EXTRA_STREAM_HEADERS, HashMap(it)) }
                tmdbId?.let { putExtra(EXTRA_TMDB_ID, it) }
                imdbId?.let { putExtra(EXTRA_IMDB_ID, it) }
                seriesTitle?.let { putExtra(EXTRA_SERIES_TITLE, it) }
                episodeTitle?.let { putExtra(EXTRA_EPISODE_TITLE, it) }
                seasonNumber?.let { putExtra(EXTRA_SEASON_NUM, it) }
                episodeNumber?.let { putExtra(EXTRA_EPISODE_NUM, it) }
                debridInfoHash?.let { putExtra(EXTRA_DEBRID_INFOHASH, it) }
                debridMagnet?.let { putExtra(EXTRA_DEBRID_MAGNET, it) }
                putExtra(EXTRA_DIRECT_DEBRID_PLAYBACK, directDebridPlayback)
                debridProvider?.let { putExtra(EXTRA_DEBRID_PROVIDER, it) }
                debridSourceType?.let { putExtra(EXTRA_DEBRID_SOURCE_TYPE, it) }
                debridSourceName?.let { putExtra(EXTRA_DEBRID_SOURCE_NAME, it) }
                debridLanguages?.let { putStringArrayListExtra(EXTRA_DEBRID_LANGUAGES, ArrayList(it)) }
                debridQuality?.let { putExtra(EXTRA_DEBRID_QUALITY, it) }
                debridStreamId?.let { putExtra(EXTRA_DEBRID_STREAM_ID, it) }
                debridBingeGroup?.let { putExtra(EXTRA_DEBRID_BINGE_GROUP, it) }
                debridFileIdx?.let { putExtra(EXTRA_DEBRID_FILE_IDX, it) }
                expiresAt?.let { putExtra(EXTRA_EXPIRES_AT, it) }
                subtitles?.let { putStringArrayListExtra(EXTRA_SUBTITLE_ENTRIES, ArrayList(it)) }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)

        if (BuildConfig.DEBUG) {
            Log.d("PlayerActivity", "onCreate package=$packageName taskId=$taskId component=${intent.component}")
        }

        trackManager = PlayerTrackManager(this, settingsPreferences)
        watchHistoryPrefs = WatchHistoryPreferences(this)
        historyManager = PlayerHistoryManager(this, watchHistoryPrefs, viewModel, watchedStateRepository)
        lifecycle.addObserver(historyManager)

        playerView = findViewById(R.id.player_view)
        applySystemCaptionStyle()
        loaderUi.bind(this)
        layoutDebugOverlay = findViewById(R.id.layout_debug_overlay)
        tvDebugInfo = findViewById(R.id.tv_debug_info)
        reconnectingBanner = findViewById(R.id.reconnecting_banner)
        reconnectingBannerText = findViewById(R.id.reconnecting_banner_text)

        val contentTypeString = intent.getStringExtra(EXTRA_CONTENT_TYPE)
        contentType = contentTypeString?.let { runCatching { ContentType.valueOf(it) }.getOrNull() }
        Log.d("PlayerActivity", "onCreate: contentTypeString=$contentTypeString, contentType=$contentType, contentId=${SensitiveLogRedactor.describeHash(contentId)}")
        playbackSource = intent.getStringExtra(EXTRA_PLAYBACK_SOURCE)
            ?.let { runCatching { PlaybackSource.valueOf(it) }.getOrNull() }
            ?: PlaybackSource.IPTV
        directDebridPlayback = intent.getBooleanExtra(EXTRA_DIRECT_DEBRID_PLAYBACK, false)
        contentId = intent.getStringExtra(EXTRA_CONTENT_ID) ?: intent.getStringExtra(EXTRA_STREAM_URL)
        returnToSourcesOnExit = intent.getBooleanExtra(EXTRA_RETURN_TO_SOURCES, false)
        currentEpgChannelId = intent.getStringExtra(EXTRA_EPG_CHANNEL_ID)
            ?.takeIf { it.isNotBlank() }
            ?: contentId?.takeIf { it.isNotBlank() }
        posterUrlExtra = intent.getStringExtra(EXTRA_POSTER_URL)
        backdropUrlExtra = intent.getStringExtra(EXTRA_BACKDROP_URL)
        tmdbIdExtra = intent.getStringExtra(EXTRA_TMDB_ID)
        imdbIdExtra = intent.getStringExtra(EXTRA_IMDB_ID)
        seriesTitleExtra = intent.getStringExtra(EXTRA_SERIES_TITLE)
        episodeTitleExtra = intent.getStringExtra(EXTRA_EPISODE_TITLE)
        seasonNumberExtra = intent.getIntExtra(EXTRA_SEASON_NUM, -1).takeIf { it >= 0 }
        episodeNumberExtra = intent.getIntExtra(EXTRA_EPISODE_NUM, -1).takeIf { it >= 0 }
        debridInfoHashExtra = intent.getStringExtra(EXTRA_DEBRID_INFOHASH)
        debridMagnetExtra = intent.getStringExtra(EXTRA_DEBRID_MAGNET)
            ?: intent.getStringExtra("DEBRID_MAGNET")
        debridProviderExtra = intent.getStringExtra(EXTRA_DEBRID_PROVIDER)
        debridSourceTypeExtra = intent.getStringExtra(EXTRA_DEBRID_SOURCE_TYPE)
        debridSourceNameExtra = intent.getStringExtra(EXTRA_DEBRID_SOURCE_NAME)
        debridLanguagesExtra = intent.getStringArrayListExtra(EXTRA_DEBRID_LANGUAGES)
        debridQualityExtra = intent.getStringExtra(EXTRA_DEBRID_QUALITY)
        debridStreamIdExtra = intent.getStringExtra(EXTRA_DEBRID_STREAM_ID)
        debridBingeGroupExtra = intent.getStringExtra(EXTRA_DEBRID_BINGE_GROUP)
        debridFileIdxExtra = intent.getIntExtra(EXTRA_DEBRID_FILE_IDX, -1).takeIf { it >= 0 }
        expiresAtExtra = intent.getLongExtra(EXTRA_EXPIRES_AT, -1L).takeIf { it > 0 }

        val streamUrl = intent.getStringExtra(EXTRA_STREAM_URL)
        val streamTitle = intent.getStringExtra(EXTRA_STREAM_TITLE)
        startPositionMs = intent.getLongExtra(EXTRA_START_POSITION, 0L)
        originalTitle = streamTitle
        streamHeaders = readStreamHeaders(intent, EXTRA_STREAM_HEADERS)
        subtitleEntries = intent.getStringArrayListExtra(EXTRA_SUBTITLE_ENTRIES) ?: emptyList()

        val isDebrid = playbackSource == PlaybackSource.DEBRID
        val canResolveDebrid = canUseDebridResolver()
        val hasResInfo = !debridInfoHashExtra.isNullOrBlank() || !debridMagnetExtra.isNullOrBlank()
        val isDebridHistoryResume = isDebrid && startPositionMs > 0L
        val isDebridUrlMissing = streamUrl.isNullOrBlank()
        val canFreshResolveDirectDebrid = canFreshResolveDirectDebrid()
        val isExpired = expiresAtExtra?.let { System.currentTimeMillis() > it }
            ?: (isDebrid && (isDebridUrlMissing || isDebridHistoryResume))

        if (isDebridUrlMissing && !(canResolveDebrid && hasResInfo) && !canFreshResolveDirectDebrid) {
            showError("Invalid stream URL")
            finish()
            return
        }

        timeoutMs = resolveTimeoutMs(streamUrl ?: "")
        currentUrl = streamUrl
        channelLogoUrl = intent.getStringExtra(EXTRA_CHANNEL_LOGO)
        val channelName = intent.getStringExtra(EXTRA_CHANNEL_NAME) ?: streamTitle
        pendingChannelName = channelName

        if (contentType == ContentType.LIVE_TV) {
            setupOverlayViews()
            setupLiveOsd()
            bindChannelMeta(channelName)
            // Backdrop covers the black surface until the first frame renders.
            liveOsd?.showZapBackdrop()
        } else {
            // New Redesign: We use the Controller's Top Bar instead of a separate VOD overlay
        }

        liveCategoryId = intent.getStringExtra(EXTRA_LIVE_CATEGORY_ID)
        baseServerUrl = intent.getStringExtra(EXTRA_BASE_SERVER_URL)
        liveChannelIds = intent.getStringArrayListExtra(EXTRA_LIVE_CHANNEL_IDS)
        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

        val seriesId = intent.getStringExtra(EXTRA_SERIES_ID) ?: tmdbIdExtra ?: imdbIdExtra
        val seasonNum = intent.getIntExtra(EXTRA_SEASON_NUM, -1)
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

        if (contentType == ContentType.LIVE_TV) {
            playerView.useController = false
            val epgChannelId = currentEpgChannelId
            val streamIdForEpg = contentId?.takeIf { it.toLongOrNull() != null }
            viewModel.observeEpg(epgChannelId, streamIdForEpg)
            observeOverlayState()
            observeZapState()
        } else {
            playerView.useController = true
            playerView.controllerAutoShow = true
            playerView.controllerHideOnTouch = true
            playerView.setControllerVisibilityListener(object : PlayerView.ControllerVisibilityListener {
                override fun onVisibilityChanged(visibility: Int) {
                    isControllerVisible = visibility == View.VISIBLE
                    if (!isControllerVisible) {
                        playerView.requestFocus()
                    }
                }
            })
            
            playerView.findViewById<View>(R.id.btn_player_audio)?.setOnClickListener {
                showAudioSelection()
            }

            playerView.findViewById<View>(R.id.btn_player_language)?.setOnClickListener {
                showAudioSelection()
            }

            playerView.findViewById<View>(R.id.btn_player_subtitles)?.setOnClickListener {
                showSubtitleSelection()
            }

            playerView.findViewById<View>(R.id.exo_play)?.setOnClickListener {
                player?.play()
                updatePlayPauseVisibility(playerView, true, isControllerVisible)
            }
            playerView.findViewById<View>(R.id.exo_pause)?.setOnClickListener {
                player?.pause()
                updatePlayPauseVisibility(playerView, false, isControllerVisible)
            }
            playerView.findViewById<View>(R.id.exo_rew)?.setOnClickListener {
                player?.seekBack()
            }
            playerView.findViewById<View>(R.id.exo_ffwd)?.setOnClickListener {
                player?.seekForward()
            }
            
            player?.let { updatePlayPauseVisibility(playerView, it.isPlaying, isControllerVisible) }
            bindModernMetadata(streamTitle)
            setupInteractiveAnimations(playerView)
            setupSeekOverlay()

            val btnNext = playerView.findViewById<View>(R.id.btn_next_episode)
            val btnPrev = playerView.findViewById<View>(R.id.btn_prev_episode)
            val btnEpisodes = playerView.findViewById<View>(R.id.btn_episodes)
            val isSeriesControls = contentType == ContentType.SERIES || contentType == ContentType.EPISODE
            if (isSeriesControls) {
                btnNext?.isVisible = true
                btnNext?.setOnClickListener {
                     playNextEpisode()
                }
                // VOD Player redesign: series-only PREV EP + EPISODES controls.
                btnPrev?.isVisible = true
                btnPrev?.setOnClickListener {
                    playPreviousEpisode()
                }
                btnEpisodes?.isVisible = true
                btnEpisodes?.setOnClickListener {
                    playerView.hideController()
                    showEpisodeBrowser()
                }
            } else {
                btnNext?.isVisible = false
                btnPrev?.isVisible = false
                btnEpisodes?.isVisible = false
            }
            // Movies: an in-player "Sources" button to switch source/language mid-playback
            // (e.g. Hindi → German) without going back to the detail screen.
            val btnSources = playerView.findViewById<View>(R.id.btn_player_sources)
            if (contentType == ContentType.MOVIE) {
                btnSources?.isVisible = true
                btnSources?.setOnClickListener {
                    playerView.hideController()
                    openMovieSourcePanel()
                }
            } else {
                btnSources?.isVisible = false
            }
            // VOD Player redesign: explicit horizontal focus chain so play/pause visibility
            // swaps never trap focus (both exo_play & exo_pause share the same L/R links).
            wireControlFocus(isSeriesControls)

            playerView.findViewById<View>(R.id.btn_aspect_ratio)?.setOnClickListener {
                showAspectSelection()
            }

            nextEpisodeManager = PlayerNextEpisodeManager(this, playerView, object : PlayerNextEpisodeManager.Delegate {
                override fun isPlaybackEligibleForPrompt(): Boolean {
                    return contentType == ContentType.SERIES || contentType == ContentType.EPISODE
                }
                override fun getPlayerDuration(): Long = player?.duration ?: 0L
                override fun getPlayerCurrentPosition(): Long = player?.currentPosition ?: 0L
                override fun isPlayerPlaying(): Boolean = player?.isPlaying ?: false
                override fun getPlayer(): ExoPlayer? = player
                override fun getSeriesFallbackPosterUrl(): String? =
                    posterUrlExtra?.takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }
                        ?: backdropUrlExtra?.takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }
                override fun getNextEpisode(): com.tvonnet.debridxtreamiptv.features.seriesv2.data.model.EpisodeEntityV2? {
                    val state = viewModel.seriesPlaylistState.value
                    return if (state?.hasNext == true) {
                        state.originalList.getOrNull(state.currentIndex + 1)
                    } else null
                }
                override fun onPlayNextEpisodeRequested() {
                    playNextEpisode()
                }
                override fun onNextEpisodePromptShown(nextEpisode: com.tvonnet.debridxtreamiptv.features.seriesv2.data.model.EpisodeEntityV2) {
                    if (playbackSource != PlaybackSource.DEBRID || contentType != ContentType.EPISODE) return
                    val currentSeason = seasonNumberExtra ?: return
                    val currentEpisode = episodeNumberExtra ?: return
                    val tmdbId = debridSeriesLookupId().takeIf { it.isNotBlank() } ?: return
                    val targetSeason = nextEpisode.seasonNumber.takeIf { it >= currentSeason } ?: currentSeason
                    val targetEpisode = when {
                        nextEpisode.seasonNumber == currentSeason && nextEpisode.episodeNumber > currentEpisode ->
                            nextEpisode.episodeNumber
                        nextEpisode.seasonNumber > currentSeason -> nextEpisode.episodeNumber
                        else -> currentEpisode + 1
                    }
                    viewModel.preResolveNextDebridEpisode(
                        seriesId = tmdbId,
                        targetSeason = targetSeason,
                        targetEpisode = targetEpisode,
                        seriesTitle = seriesTitleExtra,
                        infoHash = debridInfoHashExtra ?: debridStreamIdExtra,
                        sourceProfile = currentDebridSourceProfile()
                    )
                }
            })
            nextEpisodeManager.setupViews()
            observeSeriesPlaylistState()
            observeDebridResolutionState()
            setupEpisodeBrowser()

            val volumeProgress = playerView.findViewById<android.widget.SeekBar>(R.id.volume_progress)
            val btnVolume = playerView.findViewById<android.widget.ImageButton>(R.id.btn_player_volume)
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager

            if (audioManager != null && volumeProgress != null) {
                val maxVolume = audioManager.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
                val currentVolume = audioManager.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
                volumeProgress.max = maxVolume
                volumeProgress.progress = currentVolume

                volumeProgress.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                        if (fromUser) {
                            audioManager.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, progress, android.media.AudioManager.FLAG_SHOW_UI)
                            updateVolumeIcon(progress, maxVolume, btnVolume)
                        }
                    }
                    override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
                    override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {}
                })

                btnVolume?.setOnClickListener {
                    val isMuted = audioManager.getStreamVolume(android.media.AudioManager.STREAM_MUSIC) == 0
                    if (isMuted) {
                        val prev = 7.coerceAtMost(maxVolume)
                        audioManager.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, prev, android.media.AudioManager.FLAG_SHOW_UI)
                        volumeProgress.progress = prev
                        updateVolumeIcon(prev, maxVolume, btnVolume)
                    } else {
                        audioManager.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, 0, android.media.AudioManager.FLAG_SHOW_UI)
                        volumeProgress.progress = 0
                        updateVolumeIcon(0, maxVolume, btnVolume)
                    }
                }
            }

            xrayController.bindToggle()

            if (contentType == ContentType.MOVIE || contentType == ContentType.EPISODE || contentType == ContentType.SERIES) {
                viewModel.loadXRayMetadata(
                    contentId = contentId,
                    tmdbId = tmdbIdExtra ?: imdbIdExtra,
                    isMovie = contentType == ContentType.MOVIE,
                    title = originalTitle
                )
            }

            lifecycleScope.launch {
                lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                    viewModel.xrayMetadata.collect { state -> xrayController.render(state) }
                }
            }
        }

        if (canFreshResolveDirectDebrid && isExpired) {
            refreshDirectDebridSourceFromMetadata("expired_direct_resume")
        } else if (canResolveDebrid && hasResInfo && (isDebridUrlMissing || isExpired)) {
            Log.i("PlayerActivity", "Debrid resume detected: URL is ${if (isDebridUrlMissing) "missing" else "expired"}. Triggering resolution.")
            isResolvingDebrid = true
            viewModel.reResolveDebridUrl(debridInfoHashExtra, debridMagnetExtra, seasonNumberExtra, episodeNumberExtra, episodeTitleExtra)
        } else if (isDebrid && directDebridPlayback && isExpired) {
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
        supportActionBar?.title = streamTitle ?: "Playing"

        if (contentType == ContentType.LIVE_TV) {
            viewModel.initLiveZapping(
                categoryId = liveCategoryId,
                currentStreamId = contentId,
                baseServerUrl = baseServerUrl ?: prefs.getServerUrl(),
                orderedStreamIds = liveChannelIds
            )
            initBrowserOverlay()
        }

        if (contentType == ContentType.LIVE_TV) {
            if (lastOverlayState == null) {
                lastOverlayState = PlayerOverlayUiState(epgAvailable = false, isSyncing = true, errorMessage = null)
            }
            if (liveOsd == null) showEpgOverlay(EpgOverlayMode.COMPACT, pinned = false)
        } else {
            playerView.showController()
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (liveOsd?.handleDrawerBack() == true) {
                    return
                }
                if (viewModel.browserState.value.isVisible) {
                    viewModel.toggleBrowser(false)
                    return
                }
                if (contentType == ContentType.LIVE_TV && (epgOverlayUi.isPinned || epgOverlayUi.mode != EpgOverlayMode.HIDDEN)) {
                    hideEpgOverlay()
                    return
                }
                if (contentType == ContentType.MOVIE || contentType == ContentType.EPISODE) {
                    // Standard TV BACK: 1st press hides the controller, 2nd exits. `backHideArmed`
                    // (reset on any explicit show) keeps this reliable even if media3 re-shows the
                    // controller while paused. Uses the tracked isControllerVisible, not the
                    // sometimes-stale isControllerFullyVisible.
                    if (isControllerVisible && !backHideArmed) {
                        playerView.hideController()
                        backHideArmed = true
                        return
                    }
                }
                performBackExit()
            }
        })
    }

    private fun zapChannel(direction: Int) {
        val target = viewModel.moveZap(direction) ?: run {
            val isReady = viewModel.zapState.value?.channels?.isNotEmpty() == true
            val msg = if (isReady) getString(R.string.player_zap_unavailable)
                else getString(R.string.player_zap_loading)
            liveOsd?.showToast(msg.uppercase(Locale.getDefault())) ?: showToast(msg)
            return
        }
        // LP-B-1: moveZap already advanced the in-memory index. Update the OSD
        // immediately for every keypress (including auto-repeat) so surfing feels
        // instant, but DEBOUNCE the real stream connect — only tuneToZapChannel
        // (which resets the reconnect budget + calls performSeamlessSwitch) fires,
        // once, ZAP_DEBOUNCE_MS after the user stops pressing.
        val osd = liveOsd
        if (osd != null) {
            osd.setChannelNumber((viewModel.zapState.value?.index ?: 0) + 1)
            osd.setFavorites(viewModel.liveFavoriteIds.value, target.streamId)
            osd.showZapOsd()
        } else {
            val keepPinned = epgOverlayUi.isPinnedUp()
            showEpgOverlay(mode = EpgOverlayMode.COMPACT, pinned = keepPinned)
        }
        zapDebouncer.schedule(target)
    }

    /** OK-tune from the surf drawer (Live Player OSD spec §7). */
    private fun tuneToZapIndex(index: Int) {
        val target = viewModel.zapTo(index) ?: return
        liveOsd?.showToast("▶ TUNING · ${target.name}")
        tuneToZapChannel(target)
    }

    private fun tuneToZapChannel(target: ZapChannel) {
        // This is the single commit point for a tune (debounced zap, drawer OK-tune,
        // resume). Cancel any still-pending debounced zap so a late commit can't
        // fire on top of this one (LP-B-1).
        zapDebouncer.cancel()

        currentZapRequestId++
        val reqId = currentZapRequestId

        // A user-initiated zap is a fresh recovery context — reset the unified
        // reconnect budget (fix 2) and retire any leftover banner (fix 3) so the new
        // channel starts with a full budget.
        resetReconnectBudget()
        hideReconnectingBanner()
        retryCount = 0
        audioSinkRecoveryCount = 0
        endedReconnects = 0
        liveFreezeReprepares = 0

        contentId = target.streamId
        currentUrl = target.streamUrl
        channelLogoUrl = target.logoUrl
        bindChannelMeta(target.name)
        supportActionBar?.title = target.name

        val epgKey = target.epgChannelId?.takeIf { it.isNotBlank() } ?: target.streamId
        currentEpgChannelId = epgKey
        viewModel.observeEpg(epgKey, target.streamId)

        val osd = liveOsd
        if (osd != null) {
            osd.setChannelNumber((viewModel.zapState.value?.index ?: 0) + 1)
            osd.setFavorites(viewModel.liveFavoriteIds.value, target.streamId)
            osd.showZapOsd()
        } else {
            val keepPinned = epgOverlayUi.isPinnedUp()
            showEpgOverlay(mode = EpgOverlayMode.COMPACT, pinned = keepPinned)
        }

        performSeamlessSwitch(target.streamUrl, reqId)
    }

    private fun setupLiveOsd() {
        if (liveOsd != null) return
        val osdRoot = findViewById<View>(R.id.view_live_osd) ?: return
        liveOsd = LivePlayerOsdManager(
            root = osdRoot,
            playerView = playerView,
            playerProvider = { player },
            onRequestGuideData = { windowStart, windowEnd ->
                viewModel.loadGuideEpg(windowStart, windowEnd)
            },
            onTuneChannel = { index -> tuneToZapIndex(index) },
            onToggleFavorite = { toggleLiveFavorite() },
            onCategorySelected = { categoryId ->
                viewModel.switchZapCategory(categoryId, baseServerUrl ?: prefs.getServerUrl())
            }
        ).also {
            it.show()
            it.showOsd(focus = false)
        }
        viewModel.observeLiveFavorites()
        viewModel.loadSurfCategories()
        lifecycleScope.launch {
            repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                viewModel.liveFavoriteIds.collect { ids ->
                    liveOsd?.setFavorites(ids, contentId)
                }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                viewModel.surfCategories.collect { cats ->
                    liveOsd?.setCategories(cats)
                }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                viewModel.surfEpg.collect { map ->
                    liveOsd?.setSurfEpg(map)
                }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                viewModel.guideEpg.collect { map ->
                    liveOsd?.setGuideEpg(map)
                }
            }
        }
    }

    private fun toggleLiveFavorite() {
        val id = contentId
        val name = pendingChannelName
        lifecycleScope.launch {
            val added = viewModel.toggleLiveFavorite(id, name, channelLogoUrl) ?: return@launch
            liveOsd?.showToast(
                if (added) "★ ADDED TO FAVORITES · ${name.orEmpty()}"
                else "REMOVED FROM FAVORITES"
            )
        }
    }

    private fun performSeamlessSwitch(newUrl: String, requestId: Long = -1L) {
        if (isFinishing || isDestroyed) return
        val playerSnapshot = player ?: return
        
        if (requestId != -1L && requestId != currentZapRequestId) {
            Log.w("SWITCH_DEBUG", "Dropping stale switch request: $requestId vs latest $currentZapRequestId")
            return
        }
        
        isSwitching = true
        switchCount++
        frameRateMatchedForCurrentSource = false
        liveOsd?.showZapBackdrop()
        qoeTracker?.markPrepareStart() // LP-D-2: TTFF measures this switch (zap latency)
        Log.i("PlayerActivity", "Performing seamless switch to: ${SensitiveLogRedactor.describeUrl(newUrl)}")
        
        timeoutHandler.removeCallbacks(timeoutRunnable)
        timeoutMs = resolveTimeoutMs(newUrl)
        timeoutHandler.postDelayed(timeoutRunnable, timeoutMs)
        armWatchdogBaseline()

        startStallMonitor()
        
        // GOLD STANDARD SWITCH BLOCK (Atomic Reset)
        // 1. Stop current playback pipeline
        playerSnapshot.stop()
        
        // 2. Clear previous media items to avoid state leakage
        playerSnapshot.clearMediaItems()
        
        val mediaItem = buildMediaItem(newUrl)
        
        // 3. Set new target
        playerSnapshot.setMediaItem(mediaItem)
        
        // 4. Prepare FIRST to initialize codecs
        playerSnapshot.prepare()
        
        // 5. Seek AFTER prepare for stable decoder positioning
        if (startPositionMs > 0L) {
            playerSnapshot.seekTo(startPositionMs)
        }
        
        // 6. Start
        playerSnapshot.playWhenReady = true
        
        // NOTE: DO NOT re-bind playerView.player here. 
        // Re-binding causes Surface detachment/attachment race conditions on many Android TV devices.
        Log.d("SWITCH_DEBUG", "Seamless switch command sequence complete (Position: $startPositionMs)")
    }

    private data class ParsedSubtitle(val url: String, val language: String?)

    private fun buildMediaItem(url: String): MediaItem {
        val subtitleConfigs = trackManager.buildSubtitleConfigurations(subtitleEntries)
        val builder = MediaItem.Builder().setUri(url)
        val mimeHint = resolveMediaMimeType(
            url,
            listOf(
                url,
                debridProviderExtra,
                debridSourceTypeExtra,
                debridSourceNameExtra,
                debridQualityExtra,
                originalTitle
            ),
            streamHeaders
        )
        mimeHint?.let { builder.setMimeType(it) }
        // LiveConfiguration ONLY for HLS/DASH. On raw TS it flips the item to
        // "live window" mode and the live-offset control seeks an unseekable
        // stream ~200ms after the first frame — an endless flush/reload loop
        // that froze every .ts channel on the first frame (device-diagnosed).
        if (contentType == ContentType.LIVE_TV &&
            (mimeHint == MimeTypes.APPLICATION_M3U8 || mimeHint == MimeTypes.APPLICATION_MPD)
        ) {
            builder.setLiveConfiguration(
                MediaItem.LiveConfiguration.Builder()
                    .setMinPlaybackSpeed(0.97f)
                    .setMaxPlaybackSpeed(1.03f)
                    .build()
            )
        }
        if (subtitleConfigs.isNotEmpty()) {
            builder.setSubtitleConfigurations(subtitleConfigs)
        }
        PlaybackDiagnosticsRecorder.record(
            this,
            "media_item_built",
            diagnosticsPlaybackFields(url, mimeHint = mimeHint) + mapOf(
                "subtitleConfigCount" to subtitleConfigs.size
            )
        )
        return builder.build()
    }

    private fun showAudioSelection() {
        if (isInPictureInPictureMode) return
        val playerSnapshot = player ?: return
        trackManager.showAudioSelection(playerSnapshot) { dialog ->
            showManagedTrackDialog(dialog, R.id.btn_player_audio)
        }
    }

    private fun showSubtitleSelection() {
        if (isInPictureInPictureMode) return
        val playerSnapshot = player ?: return
        trackManager.showSubtitleSelection(playerSnapshot, subtitleEntries) { dialog ->
            showManagedTrackDialog(dialog, R.id.btn_player_subtitles)
        }
    }

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

    private fun showManagedTrackDialog(dialog: Dialog, anchorViewId: Int? = null) {
        dismissActiveTrackDialog()
        activeTrackDialog = dialog
        dialog.setOnDismissListener {
            if (activeTrackDialog === dialog) {
                activeTrackDialog = null
            }
            anchorViewId?.let { id ->
                playerView.findViewById<View>(id)?.requestFocus()
            }
        }
        dialog.show()
    }

    private fun dismissActiveTrackDialog() {
        activeTrackDialog?.setOnDismissListener(null)
        activeTrackDialog?.dismiss()
        activeTrackDialog = null
    }

    private fun setupOverlayViews() {
        if (epgOverlay != null) return
        epgOverlay = findViewById(R.id.epg_overlay)
        imgChannelLogo = findViewById(R.id.img_channel_logo)
        tvChannelNumber = findViewById(R.id.tv_channel_number)
        tvChannelName = findViewById(R.id.tv_channel_name)
        tvNowTitle = findViewById(R.id.tv_now_title)
        tvNowTime = findViewById(R.id.tv_now_time)
        progressNow = findViewById(R.id.progress_now)
        tvEpgStatus = findViewById(R.id.tv_epg_status)
        tvNowCardTime = findViewById(R.id.tv_now_card_time)
        tvNowCardTitle = findViewById(R.id.tv_now_card_title)
        cardNext1 = findViewById(R.id.card_next_1)
        tvNext1Time = findViewById(R.id.tv_next_1_time)
        tvNext1Title = findViewById(R.id.tv_next_1_title)
        cardNext2 = findViewById(R.id.card_next_2)
        tvNext2Time = findViewById(R.id.tv_next_2_time)
        tvNext2Title = findViewById(R.id.tv_next_2_title)
        cardNext3 = findViewById(R.id.card_next_3)
        tvNext3Time = findViewById(R.id.tv_next_3_time)
        tvNext3Title = findViewById(R.id.tv_next_3_title)
        
        pendingChannelName?.let { bindChannelMeta(it) }
        lastOverlayState?.let { renderOverlay(it) }
    }

    private fun observeOverlayState() {
        lifecycleScope.launch {
            repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                viewModel.overlayState.collect { 
                    if (it.streamId != null && it.streamId != contentId) return@collect
                    renderOverlay(it) 
                }
            }
        }
    }

    private fun observeZapState() {
        lifecycleScope.launch {
            repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                viewModel.zapState.collect { state ->
                    if (contentType != ContentType.LIVE_TV) return@collect
                    val number = state?.let { (it.index + 1).coerceAtLeast(1) } ?: 1
                    tvChannelNumber?.text = number.toString()
                    liveOsd?.setChannelNumber(state?.let { it.index + 1 })
                    state?.let {
                        liveOsd?.setZapState(it.categoryId, it.categoryName, it.channels, it.index)
                        viewModel.refreshSurfEpg()
                    }
                }
            }
        }
    }

    private lateinit var nextEpisodeManager: PlayerNextEpisodeManager

    // Standard TV BACK: armed after 1st BACK hides the controller, so the 2nd BACK exits
    // even if media3 auto-re-shows the controller (e.g. while paused). Disarmed on explicit show.
    private var backHideArmed = false

    // VOD Player redesign: custom design seek bar (visual only; media3 TimeBar handles scrubbing).
    private var seekOverlay: VodSeekOverlay? = null
    private val seekOverlayHandler = Handler(Looper.getMainLooper())
    private val seekOverlayRunnable = object : Runnable {
        override fun run() {
            updateSeekOverlay()
            seekOverlayHandler.postDelayed(this, 500)
        }
    }

    private fun updateSeekOverlay() {
        val p = player ?: return
        val dur = p.duration
        if (dur > 0L) {
            seekOverlay?.setProgress(p.currentPosition.toFloat() / dur)
            seekOverlay?.setBuffered(p.bufferedPosition.toFloat() / dur)
        } else {
            seekOverlay?.setProgress(0f)
            seekOverlay?.setBuffered(0f)
        }
    }

    private fun setupSeekOverlay() {
        seekOverlay = playerView.findViewById(R.id.vod_seek_overlay)
        // Install the DPAD-DOWN → play/pause seek-nav handler up front so the seek bar never
        // dead-ends onto the GONE exo_play even before the first showControllerWithSmartFocus().
        installControllerSeekNavigation()
        val timeBar = playerView.findViewById<androidx.media3.ui.DefaultTimeBar>(R.id.exo_progress)
        timeBar?.addListener(object : androidx.media3.ui.TimeBar.OnScrubListener {
            override fun onScrubStart(timeBar: androidx.media3.ui.TimeBar, position: Long) {
                seekOverlay?.setFocusedVisual(true)
            }
            override fun onScrubMove(timeBar: androidx.media3.ui.TimeBar, position: Long) {
                val dur = player?.duration ?: 0L
                if (dur > 0L) seekOverlay?.setProgress(position.toFloat() / dur)
            }
            override fun onScrubStop(timeBar: androidx.media3.ui.TimeBar, position: Long, canceled: Boolean) {
                seekOverlay?.setFocusedVisual(false)
                updateSeekOverlay()
            }
        })
        timeBar?.setOnFocusChangeListener { _, focused -> seekOverlay?.setFocusedVisual(focused) }
        seekOverlayHandler.removeCallbacks(seekOverlayRunnable)
        seekOverlayHandler.post(seekOverlayRunnable)
    }



    private fun observeSeriesPlaylistState() {
        lifecycleScope.launch {
            repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
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

    private fun setupEpisodeBrowser() {
        val browserContainer = findViewById<View>(R.id.view_episode_browser) ?: return
        episodeBrowserController = EpisodeBrowserController(browserContainer) { episode ->
            onEpisodeSelected(episode)
        }
    }

    private fun showEpisodeBrowser() {
        // FORCE HIDE the standard controller to prevent overlap
        playerView.hideController()
        
        val browserView = findViewById<View>(R.id.view_episode_browser)
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
            
            val seriesId = intent.getStringExtra(EXTRA_SERIES_ID) ?: tmdbIdExtra ?: imdbIdExtra
            val seasonNum = intent.getIntExtra(EXTRA_SEASON_NUM, -1)
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

    private fun currentIptvEpisodeIdForPlaylist(): String? = iptvPlaylistEpisodeId(contentId, currentUrl)

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

    private fun canUseDebridResolver(): Boolean {
        return playbackSource == PlaybackSource.DEBRID && !directDebridPlayback
    }

    private fun canFreshResolveDirectDebrid(): Boolean = canFreshResolveDirectDebrid(
        playbackSource = playbackSource,
        directDebridPlayback = directDebridPlayback,
        ids = DebridLookupIds(
            tmdbId = tmdbIdExtra,
            imdbId = imdbIdExtra,
            contentId = contentId,
            title = originalTitle
        )
    )

    private fun refreshDirectDebridSourceFromMetadata(reason: String): Boolean {
        if (!canFreshResolveDirectDebrid() || isResolvingDebrid) return false
        PlaybackDiagnosticsRecorder.record(
            this,
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
        return true
    }

    private fun currentDebridSourceProfile(): DebridSourceProfile? {
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

    private fun debridSeriesLookupId(): String = debridSeriesLookupId(
        tmdbId = tmdbIdExtra,
        intentSeriesId = intent.getStringExtra(EXTRA_SERIES_ID),
        imdbId = imdbIdExtra,
        contentId = contentId
    )

    private fun applyDebridSourceProfile(profile: DebridSourceProfile?) {
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

    private fun playSeriesEpisode(episode: EpisodeEntityV2) {
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
        if (::nextEpisodeManager.isInitialized) { nextEpisodeManager.hidePrompt() }
        if (::nextEpisodeManager.isInitialized) { nextEpisodeManager.resetState() }

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
            performSeamlessSwitch(url)
        }
    }

    // QA fix: monotonically increasing id for IPTV episode switches (latest-wins).
    private var episodeSwitchRequestId = 0L

    /** Saved resume position for an IPTV episode, 0 if none / already watched. */
    private suspend fun resolveIptvEpisodeResumeMs(episode: EpisodeEntityV2): Long {
        val episodeId = episode.episodeId?.takeIf { it.isNotBlank() } ?: return 0L
        val key = com.tvonnet.debridxtreamiptv.data.local.WatchedIdentityBuilder.iptvEpisode(
            episodeId = episodeId,
            seriesId = intent.getStringExtra(EXTRA_SERIES_ID),
            seasonNumber = episode.seasonNumber,
            episodeNumber = episode.episodeNumber
        )
        val state = watchedStateRepository.getState(key) ?: return 0L
        if (state.isWatched) return 0L
        return state.progressMs.takeIf { it > 0L } ?: 0L
    }

    private fun bindModernMetadata(title: String?) {
        val posterView = playerView.findViewById<ImageView>(R.id.iv_player_poster)
        val titleView = playerView.findViewById<TextView>(R.id.tv_player_title)
        val subtitleView = playerView.findViewById<TextView>(R.id.tv_player_subtitle)
        val qualityView = playerView.findViewById<TextView>(R.id.tv_player_quality)

        posterView?.let { GlideUtils.loadMoviePoster(it, posterUrlExtra) }
        
        val displayTitle = if (playbackSource == PlaybackSource.DEBRID) {
            cleanTitle(title ?: getString(R.string.app_name))
        } else {
            title ?: getString(R.string.app_name)
        }
        
        titleView?.text = displayTitle
        
        val contentLabel = when (contentType) {
            ContentType.MOVIE -> getString(R.string.label_movie)
            ContentType.SERIES -> getString(R.string.label_series)
            ContentType.EPISODE -> getString(R.string.label_episode)
            else -> getString(R.string.label_video)
        }
        val sourceLabel = when (playbackSource) {
            PlaybackSource.DEBRID -> getString(R.string.label_debrid)
            PlaybackSource.IPTV -> getString(R.string.label_iptv)
        }
        
        val subtitle = if (contentType == ContentType.EPISODE || contentType == ContentType.SERIES) {
            val season = seasonNumberExtra
            val episode = episodeNumberExtra
            if (season != null && episode != null) {
                "S${season} • E${episode} • $sourceLabel"
            } else {
                "$contentLabel • $sourceLabel"
            }
        } else {
            "$contentLabel • $sourceLabel"
        }
        
        subtitleView?.text = subtitle
        qualityView?.apply {
            text = debridQualityExtra
            isVisible = !debridQualityExtra.isNullOrBlank()
        }
    }

    private fun playNextEpisode() {
        if (::nextEpisodeManager.isInitialized) { nextEpisodeManager.hidePrompt() }

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

    private fun playPreviousEpisode() {
        if (::nextEpisodeManager.isInitialized) { nextEpisodeManager.hidePrompt() }

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



    private fun renderOverlay(state: PlayerOverlayUiState) {
        lastOverlayState = state
        if (contentType != ContentType.LIVE_TV) return
        liveOsd?.let { osd ->
            osd.bindEpg(state.now, state.next ?: state.upcoming.firstOrNull())
            return
        }
        if (epgOverlay == null) return

        val statusText = when {
            state.isSyncing -> getString(R.string.player_epg_syncing)
            !state.errorMessage.isNullOrBlank() -> state.errorMessage
            !state.epgAvailable -> getString(R.string.player_epg_no_guide)
            else -> null
        }
        tvEpgStatus?.text = statusText
        tvEpgStatus?.isVisible = !statusText.isNullOrBlank()

        val now = state.now
        tvNowTitle?.text = now?.title ?: getString(R.string.epg_no_info)
        tvNowTime?.text = now?.let { formatTimeRangeCompact(it) } ?: "--:--"
        tvNowCardTitle?.text = now?.title ?: getString(R.string.epg_no_info)
        tvNowCardTime?.text = now?.let { formatTimeRangeCompact(it) } ?: "--:--"

        val progress = now?.let { program ->
            val duration = (program.stop - program.start).coerceAtLeast(1L)
            ((System.currentTimeMillis() - program.start)
                .coerceAtLeast(0)
                .coerceAtMost(duration)
                * 100 / duration).toInt()
        } ?: 0
        progressNow?.progress = progress

        val nextPrograms = state.upcoming.take(3)
        bindNextSlot(cardNext1, tvNext1Title, tvNext1Time, nextPrograms.getOrNull(0))
        bindNextSlot(cardNext2, tvNext2Title, tvNext2Time, nextPrograms.getOrNull(1))
        bindNextSlot(cardNext3, tvNext3Title, tvNext3Time, nextPrograms.getOrNull(2))

        if (!hasShownOverlayOnce) {
            showEpgOverlay(EpgOverlayMode.COMPACT, pinned = false)
            hasShownOverlayOnce = true
        }

        updateOverlayVisibility()
    }

    private fun bindNextSlot(container: View?, titleView: TextView?, timeView: TextView?, program: com.tvonnet.debridxtreamiptv.data.local.entity.EpgEntity?) {
        titleView ?: return
        timeView ?: return
        if (program == null) {
            container?.isVisible = false
            return
        }
        container?.isVisible = true
        timeView.text = formatTimeRangeCompact(program)
        titleView.text = program.title ?: getString(R.string.epg_no_info)
    }

    private fun bindChannelMeta(channelName: String?) {
        pendingChannelName = channelName
        liveOsd?.bindChannel(channelName, channelLogoUrl)
        val nameView = tvChannelName ?: return
        nameView.text = channelName ?: getString(R.string.player_epg_channel_unknown)
        imgChannelLogo?.let { logoView ->
            GlideUtils.loadChannelLogo(logoView, channelLogoUrl)
        }
    }



    private fun requiresAddonProxyPlaybackContext(url: String?): Boolean {
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

    private fun diagnosticsPlaybackFields(
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
                context = this,
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

    private fun hasRepeatedDirectAddonProxyTimeout(url: String): Boolean {
        val context = directAddonProxyTimeoutContext(url) ?: return false
        return addonProxyFailures.hasRepeatedTimeout(context)
    }

    private fun hasRepeatedDirectAddonProxyServerError(error: PlaybackException): Boolean {
        val responseCode = (error.cause as? HttpDataSource.InvalidResponseCodeException)?.responseCode
            ?: return false
        if (responseCode !in 500..599) return false
        val url = currentUrl ?: return false
        val context = directAddonProxyTimeoutContext(url) ?: return false
        return addonProxyFailures.hasRepeatedServerError(context)
    }

    private fun maybeRecordDirectAddonProxySuccess() {
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

    private fun recordDirectAddonProxyFailure() {
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

    private fun initializePlayer(streamUrl: String) {
        if (isFinishing || isDestroyed) {
            Log.w("PlayerActivity", "initializePlayer aborted: Activity is finishing or destroyed")
            return
        }
        try {
            // RULE: Prevent Memory Leaks. ALWAYS release existing player before creating new one.
            if (player != null) {
                Log.i("PlayerActivity", "Releasing existing player before re-initialization")
                releasePlayer("reinitialize")
            }

            // Mi3: use the injected instance — re-constructing SettingsPreferences here
            // re-read prefs from disk on the UI thread on every player init.
            val isSoftwareAudioEnabled = settingsPreferences.isSoftwareAudioEnabled()

            // Warm hand-off from the Live TV EPG guide (LP-ADOPT). The guide's preview
            // player is now built with the SAME tuned engine as a cold start (see
            // PreviewPlayerPanel: ffmpeg SW-audio renderer, low-RAM LoadControl/OOM
            // guard, 429 cool-off LoadErrorHandlingPolicy), so we ADOPT the
            // already-running instance instead of reconnecting. That keeps the single
            // provider connection (no release→reconnect → no 403 storm on
            // max_connections=1 accounts) while fullscreen still runs a fully tuned
            // player. We only take over audio focus (the muted preview deliberately
            // never held it) and cover our surface with the guide's last frame until we
            // render our own — the hand-off never reconnects or flashes black.
            val adoptedShared = if (sharedLiveSession && contentType == ContentType.LIVE_TV) {
                LiveSharedPlayer.adopt(streamUrl)
            } else {
                null
            }
            if (adoptedShared != null) {
                didPlaybackComplete = false
                hasAppliedIndexOverride = false
                timeoutHandler.removeCallbacks(timeoutRunnable)
                hasRenderedFirstFrameForCurrentSource = true
                frameRateMatchedForCurrentSource = false
                player = adoptedShared.also {
                    playerView.player = it
                    it.volume = 1f
                    // The preview built the player muted with NO audio-focus handling
                    // (browsing the guide must not duck other apps). Fullscreen owns
                    // audio now — take over focus at runtime.
                    it.setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(C.USAGE_MEDIA)
                            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                            .build(),
                        /* handleAudioFocus= */ true
                    )
                }
                PlaybackDiagnosticsRecorder.record(
                    this,
                    "player_adopted_shared_tuned",
                    diagnosticsPlaybackFields(streamUrl, null)
                )
                // Cover our surface with the guide's last rendered frame until we draw
                // our own first frame — the hand-off never flashes black.
                LiveSharedPlayer.takeFrame()?.let { showAdoptedCoverFrame(this@PlayerActivity, it, player, timeoutHandler) }
            } else {
            didPlaybackComplete = false
            hasAppliedIndexOverride = false
            timeoutHandler.removeCallbacks(timeoutRunnable)
            timeoutMs = resolveTimeoutMs(streamUrl)
            timeoutHandler.postDelayed(timeoutRunnable, timeoutMs)
            armWatchdogBaseline()
            hasRenderedFirstFrameForCurrentSource = false

            val requestHeaders = effectivePlaybackHeadersFor(streamUrl)
            PlaybackDiagnosticsRecorder.record(
                this,
                "player_initialize_started",
                diagnosticsPlaybackFields(streamUrl, requestHeaders)
            )
            if (disableTunnelingForSession) {
                Log.i("PlayerActivity", "Tunneling disabled for this session after video freeze")
            }
            val engine = PlayerEngineFactory(this, playbackOkHttpClient, settingsPreferences).build(
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
            qoeTracker = PlaybackQoeTracker(this) { qoeMode(contentType, playbackSource) }.also { tracker ->
                player?.addAnalyticsListener(tracker)
                tracker.markPrepareStart()
            }

            val mediaItem = buildMediaItem(streamUrl)
            player?.setMediaItem(mediaItem, /* resetPosition = */ true)
            PlaybackDiagnosticsRecorder.record(
                this,
                "player_prepare_called",
                diagnosticsPlaybackFields(streamUrl, requestHeaders)
            )
            player?.prepare()
            if (startPositionMs > 0L) {
                player?.seekTo(startPositionMs)
            }
            }
            player?.playWhenReady = true
            // player?.play() removed as playWhenReady=true is sufficient
            startStallMonitor()
            // A fresh ExoPlayer was just built — rebind the next-episode trigger to it so the
            // scheduled 87%/-30s message lands on this instance (not the released previous one).
            if (::nextEpisodeManager.isInitialized) nextEpisodeManager.rebindPlayer()

            playerListener = object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    PlaybackDiagnosticsRecorder.record(
                        this@PlayerActivity,
                        "player_state_changed",
                        diagnosticsPlaybackFields() + mapOf(
                            "playerState" to PlaybackDiagnosticsRecorder.playerStateName(playbackState),
                            "positionMs" to (player?.currentPosition ?: 0L),
                            "durationMs" to (player?.duration ?: 0L)
                        )
                    )
                    if (playbackState == Player.STATE_READY) {
                         isSwitching = false
                         // BUFFERING→READY: refresh the session network-quality estimate
                         // (QA fix 7) using the passive bandwidth meter, debounced.
                         if (lastBufferingStartMs != 0L) maybeUpdateNetworkQuality()
                         // Keep the resume target alive while a debrid re-resolve is in flight:
                         // clearing it here would make the upcoming seamless switch start from 0.
                         if (!isResolvingDebrid && player?.currentPosition ?: 0L > 1000) startPositionMs = 0L
                        maybeMatchDisplayFrameRate()
                        // Black-video watchdog (VOD): audio can play while the HW decoder
                        // renders no frames under tunneling — reinit without tunneling if so.
                        if (contentType != ContentType.LIVE_TV &&
                            !hasRenderedFirstFrameForCurrentSource && !blackVideoFallbackTried
                        ) {
                            timeoutHandler.removeCallbacks(blackVideoCheckRunnable)
                            timeoutHandler.postDelayed(blackVideoCheckRunnable, BLACK_VIDEO_CHECK_MS)
                        }
                        timeoutHandler.removeCallbacks(timeoutRunnable)
                        watchdogExtensions = 0
                        watchdogBufferedPosAtArm = -1L
                        retryCount = 0
                        audioSinkRecoveryCount = 0
                        // A clean READY means recovery succeeded — reset the unified
                        // reconnect budget (fix 2) and retire the banner (fix 3).
                        resetReconnectBudget()
                        hideReconnectingBanner()
                        // First playable frame — dissolve the cinematic loader to reveal video.
                        loaderUi.hide()
                        lastBufferingStartMs = 0L
                        addonProxyFailures.clearFailures()
                        maybeRecordDirectAddonProxySuccess()
                        if (contentType != ContentType.LIVE_TV) {
                            updatePlayPauseVisibility(playerView, player?.playWhenReady == true, isControllerVisible)
                            backHideArmed = false
                            playerView.showController()
                        }
                    } else if (playbackState == Player.STATE_BUFFERING) {
                        timeoutHandler.removeCallbacks(timeoutRunnable)
                        timeoutHandler.postDelayed(timeoutRunnable, timeoutMs)
                        armWatchdogBaseline()
                        if (lastBufferingStartMs == 0L) lastBufferingStartMs = SystemClock.elapsedRealtime()
                    } else if (playbackState == Player.STATE_ENDED || playbackState == Player.STATE_IDLE) {
                        timeoutHandler.removeCallbacks(timeoutRunnable)
                        lastBufferingStartMs = 0L
                        if (playbackState == Player.STATE_ENDED) {
                             val nowMs = SystemClock.elapsedRealtime()
                             if (nowMs - lastEndedReconnectAtMs > 30_000L) endedReconnects = 0
                             val canReconnect = endedReconnects < 3
                             // A live stream never legitimately ends — ENDED means the
                             // provider dropped the socket. Reconnect instead of
                             // silently finishing back to the channel list.
                             if (contentType == ContentType.LIVE_TV) {
                                  // Inner cap (endedReconnects) AND the aggregate budget
                                  // must both allow it; canAttemptReconnect() records the
                                  // attempt + shows the banner only when it returns true.
                                  if (canReconnect && canAttemptReconnect()) {
                                       endedReconnects++; lastEndedReconnectAtMs = nowMs
                                       Log.i("PlayerActivity", "Live stream ended unexpectedly — reconnecting ($endedReconnects/3)")
                                       // A just-dropped socket usually refuses an immediate
                                       // reconnect — give the provider a moment so the FIRST
                                       // attempt succeeds instead of burning budget slots.
                                       // Stale-guarded: a zap meanwhile changes currentUrl
                                       // and this runnable no-ops.
                                       val urlAtEnded = currentUrl
                                       retryHandler.postDelayed({
                                            if (!isFinishing && !isDestroyed && currentUrl == urlAtEnded) {
                                                 urlAtEnded?.let { performSeamlessSwitch(it) }
                                            }
                                       }, LIVE_ENDED_RECONNECT_DELAY_MS)
                                  } else {
                                       handlePlaybackError(PlaybackException(null, null, PlaybackException.ERROR_CODE_REMOTE_ERROR))
                                  }
                                  return
                             }
                             // Same for VOD: a mid-stream socket drop reports ENDED
                             // long before the real end. Only finish when actually
                             // at the end of the content; otherwise resume in place.
                             val durationMs = player?.duration ?: 0L
                             val positionMs = player?.currentPosition ?: 0L
                             val endedMidStream = durationMs > 0L && positionMs < durationMs - 15_000L
                             if (endedMidStream && canReconnect && canAttemptReconnect()) {
                                  endedReconnects++; lastEndedReconnectAtMs = nowMs
                                  Log.i(
                                      "PlayerActivity",
                                      "Stream ended ${durationMs - positionMs}ms before content end — resuming ($endedReconnects/3)"
                                  )
                                  startPositionMs = positionMs
                                  currentUrl?.let { performSeamlessSwitch(it) }
                                  return
                             }
                             if (contentType == ContentType.SERIES || contentType == ContentType.EPISODE) {
                                  // A next-episode resolve is already in flight (countdown/prompt
                                  // path fired first) — never double-advance or finish() under it.
                                  if (isResolvingDebrid) return
                                  val state = viewModel.seriesPlaylistState.value
                                  if (state?.hasNext == true) {
                                       playNextEpisode()
                                       return
                                  }
                             }
                             didPlaybackComplete = true
                             if (!(::nextEpisodeManager.isInitialized && nextEpisodeManager.isPromptVisible)) finish()
                        }
                    }
                }
                override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                    if (contentType != ContentType.LIVE_TV) {
                        updatePlayPauseVisibility(playerView, playWhenReady, isControllerVisible)
                    } else if (playWhenReady) {
                        maybeSnapToLiveEdge()
                    }
                }
                override fun onRenderedFirstFrame() {
                    hasRenderedFirstFrameForCurrentSource = true
                    timeoutHandler.removeCallbacks(blackVideoCheckRunnable)
                    liveOsd?.hideZapBackdrop()
                    // First rendered frame = the source is genuinely playing; clear the
                    // unified reconnect budget (fix 2) and hide the banner (fix 3).
                    resetReconnectBudget()
                    hideReconnectingBanner()
                    maybeRecordDirectAddonProxySuccess()
                    PlaybackDiagnosticsRecorder.record(
                        this@PlayerActivity,
                        "first_frame_rendered",
                        diagnosticsPlaybackFields() + mapOf("positionMs" to (player?.currentPosition ?: 0L))
                    )
                }
                override fun onTrackSelectionParametersChanged(parameters: androidx.media3.common.TrackSelectionParameters) {
                    timeoutHandler.removeCallbacks(captureRunnable)
                    timeoutHandler.postDelayed(captureRunnable, 1500)
                }

                override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                    PlaybackDiagnosticsRecorder.record(
                        this@PlayerActivity,
                        "track_discovery",
                        diagnosticsPlaybackFields() + trackDiagnosticsFields(tracks)
                    )
                    if (!hasAppliedIndexOverride) {
                        applyTrackIndexOverrides()
                        hasAppliedIndexOverride = true
                    }
                    
                    timeoutHandler.removeCallbacks(captureRunnable)
                    timeoutHandler.postDelayed(captureRunnable, 1500)
                    
                    if (isSoftwareAudioEnabled) {
                        val audioGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
                        var hasSupportedAudioSelected = false
                        var firstSupportedGroup: androidx.media3.common.Tracks.Group? = null
                        var hasAudioOverride = false
                        for (group in audioGroups) {
                            if (player?.trackSelectionParameters?.overrides?.containsKey(group.mediaTrackGroup) == true) {
                                hasAudioOverride = true
                            }
                            if (group.isSupported) {
                                if (firstSupportedGroup == null) firstSupportedGroup = group
                                if (group.isSelected) { hasSupportedAudioSelected = true; break }
                            }
                        }
                        if (!hasSupportedAudioSelected && firstSupportedGroup != null && audioGroups.size > 1 && !hasAudioOverride) {
                             // LP-B-6: guard the snapshot — the old `?: player?.trackSelectionParameters!!`
                             // fallback NPE'd if the player was released while this callback was in flight.
                             player?.let { p ->
                                 p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                                     .setOverrideForType(TrackSelectionOverride(firstSupportedGroup.mediaTrackGroup, 0))
                                     .build()
                             }
                        }
                    }
                }
                override fun onPlayerError(error: PlaybackException) {
                    isSwitching = false
                    timeoutHandler.removeCallbacks(timeoutRunnable)
                    handlePlaybackError(error)
                }
            }
            playerListener?.let { player?.addListener(it) }

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
        } catch (e: Exception) {
            timeoutHandler.removeCallbacks(timeoutRunnable)
            handleInitializationError(e)
        }
    }

    private fun attemptNetworkRecovery(reason: String) {
        if (Looper.myLooper() != Looper.getMainLooper()) { timeoutHandler.post { attemptNetworkRecovery(reason) }; return }
        if (!settingsPreferences.isAutoReconnectEnabled()) return
        val url = currentUrl ?: return
        val playerSnapshot = player ?: return
        if (!playerSnapshot.playWhenReady) return
        val now = SystemClock.elapsedRealtime()
        val bufferingMs = if (lastBufferingStartMs > 0L) now - lastBufferingStartMs else 0L
        if (playerSnapshot.playbackState == Player.STATE_IDLE || (playerSnapshot.playbackState == Player.STATE_BUFFERING && bufferingMs >= NETWORK_RECOVERY_BUFFER_MS)) {
            // Consult the unified budget (fix 2) so a flapping network can't chain
            // re-inits past the aggregate ceiling; shows the banner (fix 3) too.
            if (!canAttemptReconnect()) {
                handleTerminalPlaybackFailure("Network unstable")
                return
            }
            if (contentType != ContentType.LIVE_TV && playerSnapshot.currentPosition > 1000L) startPositionMs = playerSnapshot.currentPosition
            playerSnapshot.release()
            player = null
            initializePlayer(url)
        }
    }

    private fun registerNetworkCallback() {
        if (networkCallback != null) return
        val manager = connectivityManager ?: return
        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: android.net.Network) { networkAvailable = true; attemptNetworkRecovery("available") }
            override fun onLost(network: android.net.Network) { networkAvailable = false }
        }
        manager.registerDefaultNetworkCallback(networkCallback!!)
    }

    private fun unregisterNetworkCallback() {
        val callback = networkCallback ?: return
        try { connectivityManager?.unregisterNetworkCallback(callback) } catch (e: Exception) { } finally { networkCallback = null }
    }

    private fun handlePlaybackError(error: PlaybackException) {
        isSwitching = false
        if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
            player?.seekToDefaultPosition(); player?.prepare(); player?.playWhenReady = true; return
        }
        // ROOT CAUSE (device-verified via logcat 2026-07-18, both .35/.64 Amlogic):
        // the OS refuses to allocate even a plain 48kHz/stereo/16-bit AudioTrack
        // ("AudioTrack init failed 0 Config(48000,12,2)" → UnsupportedOperationException
        // "Cannot create AudioTrack"). The format is fully decoded (FFmpeg → raw PCM,
        // format_supported), so this is NOT a codec/format problem — the audio HAL's
        // output slot is still held by a prior session (tunneled AV-sync AudioTrack from
        // zap/retry churn). The FfmpegAudioRenderer then throws a FATAL renderer error
        // right AFTER the video's first frame renders → the picture freezes on frame 1.
        //
        // Recovery: (1) drop tunneling for the session — it removes the HW-AV-sync
        // AudioTrack requirement that most often wedges the slot; (2) fully release the
        // player so its AudioTrack is freed; (3) wait AUDIO_SINK_COOLOFF_MS (the old
        // 250ms was too soon — the HAL hadn't reclaimed the handle) before re-init.
        // Bounded by AUDIO_SINK_MAX_RECOVERIES so a genuinely dead audio device fails
        // cleanly instead of looping a frozen frame.
        if (isAudioSinkInitFailure(error)) {
            if (audioSinkRecoveryCount < AUDIO_SINK_MAX_RECOVERIES && canAttemptReconnect()) {
                audioSinkRecoveryCount++
                val tunnelingWasOn = !disableTunnelingForSession
                disableTunnelingForSession = true
                Log.w(
                    "PlayerActivity",
                    "AudioTrack init failed — audio-device recovery $audioSinkRecoveryCount/$AUDIO_SINK_MAX_RECOVERIES (tunnelingWasOn=$tunnelingWasOn), cool-off ${AUDIO_SINK_COOLOFF_MS}ms"
                )
                PlaybackDiagnosticsRecorder.record(
                    this,
                    "audio_sink_recovery",
                    diagnosticsPlaybackFields() + mapOf(
                        "errorCode" to error.errorCode,
                        "recovery" to audioSinkRecoveryCount,
                        "tunnelingWasOn" to tunnelingWasOn
                    )
                )
                if (contentType != ContentType.LIVE_TV && (player?.currentPosition ?: 0L) > 1000L) {
                    startPositionMs = player!!.currentPosition
                }
                player?.release(); player = null
                retryHandler.postDelayed({ currentUrl?.let { initializePlayer(it) } }, AUDIO_SINK_COOLOFF_MS)
                return
            }
            // The single recovery didn't help → the audio output is saturated (leaked
            // AudioTracks the firmware won't reclaim). Release OUR track so we stop
            // holding a slot, and stop retrying — more re-inits only leak more. Tell the
            // user the one thing that actually clears it.
            player?.release(); player = null
            PlaybackDiagnosticsRecorder.record(
                this,
                "audio_sink_exhausted",
                diagnosticsPlaybackFields() + mapOf("errorCode" to error.errorCode)
            )
            handleTerminalPlaybackFailure("Audio unavailable — please restart your Fire TV (the device ran out of audio channels)")
            return
        }
        val cause = error.cause
        val errorMessage = when (cause) {
            is HttpDataSource.InvalidResponseCodeException -> "HTTP ${cause.responseCode}"
            else -> error.message ?: "Playback error"
        }
        PlaybackDiagnosticsRecorder.record(
            this,
            "player_error",
            diagnosticsPlaybackFields() + mapOf(
                "reasonCode" to PlaybackDiagnosticsRecorder.sanitizeReason(errorMessage),
                "httpStatusCode" to PlaybackDiagnosticsRecorder.httpStatusCode(errorMessage),
                "errorCode" to error.errorCode
            )
        )
        if (isTerminalDirectHttpPlaybackError(error, directDebridPlayback)) {
            if ((error.cause as? HttpDataSource.InvalidResponseCodeException)?.responseCode == 404) {
                recordDirectAddonProxyFailure()
            }
            handleTerminalPlaybackFailure(errorMessage)
            return
        }
        if (hasRepeatedDirectAddonProxyServerError(error)) {
            recordDirectAddonProxyFailure()
            handleTerminalPlaybackFailure(errorMessage, preferReturnToSources = true)
            return
        }
        // Definitive client errors (removed/nonexistent listing, wrong method) never
        // recover by retrying. Plain IPTV/Xtream playback had NO terminal-HTTP path —
        // isTerminalDirectHttpPlaybackError only fires for directDebridPlayback — so a
        // dead listing (e.g. a provider that answers 405/500 for an unavailable movie id)
        // looped the "Reconnecting…" banner until the whole retry budget drained.
        // Fail fast for non-live sources instead. 429 (rate-limit) is excluded — handled
        // below with a cool-off.
        val terminalClientCode =
            (error.cause as? HttpDataSource.InvalidResponseCodeException)?.responseCode
        if (isTerminalHttpForNonLive(terminalClientCode, contentType, playbackSource)) {
            handleTerminalPlaybackFailure(errorMessage, preferReturnToSources = true)
            return
        }
        // HTTP 429 for plain IPTV/Xtream (QA fix 4): the provider WAF is rate-limiting
        // this IP. Fast-backoff retries only deepen the ban, so cool off — honor a
        // Retry-After header if the server sent one, else a fixed 20s. The direct-
        // debrid path treats 429 as terminal separately (isTerminalDirectHttpPlaybackError).
        val responseCode = (error.cause as? HttpDataSource.InvalidResponseCodeException)?.responseCode
        if (isRateLimitCoolOff(responseCode, directDebridPlayback)) {
            if (retryCount < maxRetriesFor(contentType, LIVE_MAX_RETRIES, maxRetries) &&
                canAttemptReconnect()
            ) {
                retryCount++
                val coolOffMs = parseRetryAfterMs(error) ?: HTTP_429_COOLOFF_MS
                Log.i("PlayerActivity", "HTTP 429 (rate limited) — cooling off ${coolOffMs}ms before reconnect")
                PlaybackDiagnosticsRecorder.record(
                    this,
                    "retry_triggered",
                    diagnosticsPlaybackFields(retry = retryCount) + mapOf(
                        "reasonCode" to "HTTP_429",
                        "retrySource" to "rate_limit_cooloff",
                        "coolOffMs" to coolOffMs
                    )
                )
                if (contentType != ContentType.LIVE_TV && player != null && player!!.currentPosition > 1000L) {
                    startPositionMs = player!!.currentPosition
                }
                player?.release(); player = null
                retryHandler.postDelayed({ currentUrl?.let { initializePlayer(it) } }, coolOffMs)
            } else {
                handleTerminalPlaybackFailure(errorMessage)
            }
            return
        }
        if (retryCount < maxRetriesFor(contentType, LIVE_MAX_RETRIES, maxRetries)) {
            // Aggregate budget gate (fix 2): even under the per-source retry cap, refuse
            // to reconnect once the rolling window is spent. Records the attempt + shows
            // the banner when allowed.
            if (!canAttemptReconnect()) {
                handleTerminalPlaybackFailure(errorMessage)
                return
            }
            retryCount++
            PlaybackDiagnosticsRecorder.record(
                this,
                "retry_triggered",
                diagnosticsPlaybackFields(retry = retryCount) + mapOf(
                    "reasonCode" to PlaybackDiagnosticsRecorder.sanitizeReason(errorMessage),
                    "retrySource" to "player_error"
                )
            )
            val hasDurableDebridIdentity = !debridInfoHashExtra.isNullOrBlank() || !debridMagnetExtra.isNullOrBlank()
            if (directDebridPlayback && refreshDirectDebridSourceFromMetadata("player_error_direct_refresh")) {
                return
            }
            if (canUseDebridResolver() && !isResolvingDebrid && hasDurableDebridIdentity) {
                  isResolvingDebrid = true
                  if (player != null && player!!.currentPosition > 1000L) startPositionMs = player!!.currentPosition
                  viewModel.reResolveDebridUrl(
                      debridInfoHashExtra,
                      debridMagnetExtra,
                      seasonNumberExtra,
                      episodeNumberExtra,
                      episodeTitleExtra,
                      allowDirectHttpPassthrough = false
                  )
                  return
            }
            // No "Retrying…" toast here: canAttemptReconnect() already drives the single
            // unified "RECONNECTING… (n/N)" banner. A second toast with a DIFFERENT counter
            // (retryCount/maxRetries) rendered two overlapping indicators — the bug in the
            // user's photo (top "RECONNECTING… 1/4" + centre "Retrying… 2/5").
            if (contentType != ContentType.LIVE_TV && player != null && player!!.currentPosition > 1000L) startPositionMs = player!!.currentPosition
            PlaybackDiagnosticsRecorder.record(
                this,
                "release_player",
                diagnosticsPlaybackFields() + mapOf("releaseReason" to "player_error_retry")
            )
            player?.release(); player = null
            retryHandler.postDelayed({ currentUrl?.let { initializePlayer(it) } }, retryBackoffDelayMs())
        } else {
            handleTerminalPlaybackFailure(errorMessage)
        }
    }

    private fun checkForStall() {
        val p = player ?: return
        val now = SystemClock.elapsedRealtime()
        val isLowRamDevice = DeviceProfile.isLowRamDevice(this)
        val stallThresholdMs = if (isLowRamDevice) LOW_RAM_STALL_THRESHOLD_MS else STALL_THRESHOLD_MS
        val requiredStrikes = readyStallRequiredStrikes(isLowRamDevice)
        val currentPos = p.currentPosition

        when (
            stallDetector.onTick(
                isActivelyPlaying = p.playWhenReady && p.playbackState == Player.STATE_READY,
                positionMs = currentPos,
                nowMs = now,
                thresholdMs = stallThresholdMs,
                requiredStrikes = requiredStrikes
            )
        ) {
            StallVerdict.PROGRESSING -> checkVideoRenderProgress(p, now)
            StallVerdict.IDLE -> Unit
            StallVerdict.WARNING -> PlaybackDiagnosticsRecorder.record(
                this,
                "stall_warning",
                diagnosticsPlaybackFields() + mapOf(
                    "positionMs" to currentPos,
                    "stallThresholdMs" to stallThresholdMs,
                    "stallStrikeCount" to stallDetector.strikeCount,
                    "requiredStrikes" to requiredStrikes
                )
            )
            StallVerdict.STALLED -> {
                PlaybackDiagnosticsRecorder.record(
                    this,
                    "stall_triggered",
                    diagnosticsPlaybackFields() + mapOf(
                        "positionMs" to currentPos,
                        "stallThresholdMs" to stallThresholdMs
                    )
                )
                handlePlaybackError(PlaybackException(null, null, PlaybackException.ERROR_CODE_REMOTE_ERROR))
            }
        }
    }

    /**
     * Detects "audio plays, video frozen": playback position advances but the video
     * decoder stops producing rendered frames (seen on MTK Fire TV HEVC live channels
     * — GuiExt alloc errors leave the tunneled decoder outputting nothing). First
     * occurrence re-initializes the player with tunneling disabled for the rest of
     * this session; repeats fall through to the normal error/retry path.
     */
    private fun checkVideoRenderProgress(p: ExoPlayer, now: Long) {
        if (p.videoFormat == null) { freezeDetector.onNoVideoFormat(); return }
        val counters = p.videoDecoderCounters ?: return
        counters.ensureUpdated()
        val rendered = counters.renderedOutputBufferCount
        val freezeThresholdMs =
            if (contentType == ContentType.LIVE_TV) LIVE_VIDEO_FREEZE_THRESHOLD_MS else VIDEO_FREEZE_THRESHOLD_MS
        if (!freezeDetector.onTick(rendered, now, freezeThresholdMs)) return

        PlaybackDiagnosticsRecorder.record(
            this,
            "video_freeze_detected",
            diagnosticsPlaybackFields() + mapOf(
                "positionMs" to p.currentPosition,
                "renderedFrames" to rendered,
                "tunnelingDisabledRetry" to !disableTunnelingForSession
            )
        )
        if (contentType == ContentType.LIVE_TV) {
            // Live freeze = usually broken PTS in the provider's TS mux, not the
            // decoder. Re-preparing the same URL restarts the timestamp adjuster
            // from the current live data, past the discontinuity.
            if (currentUrl != lastFreezeRecoveryUrl || now - lastFreezeRecoveryAtMs > 60_000L) {
                liveFreezeReprepares = 0
                lastFreezeRecoveryUrl = currentUrl
            }
            // Inner cap (liveFreezeReprepares) AND the aggregate budget (fix 2) must
            // both allow it; canAttemptReconnect() records the attempt + shows the
            // banner (fix 3) only when it returns true.
            if (liveFreezeReprepares < MAX_LIVE_FREEZE_REPREPARES && canAttemptReconnect()) {
                liveFreezeReprepares++
                lastFreezeRecoveryAtMs = now
                Log.i(
                    "PlayerActivity",
                    "Live video frozen (rendered=$rendered) — re-preparing stream, attempt $liveFreezeReprepares/$MAX_LIVE_FREEZE_REPREPARES"
                )
                currentUrl?.let { performSeamlessSwitch(it) }
            } else {
                handlePlaybackError(PlaybackException(null, null, PlaybackException.ERROR_CODE_REMOTE_ERROR))
            }
            return
        }

        if (!disableTunnelingForSession) {
            disableTunnelingForSession = true
            Log.w("PlayerActivity", "Video frozen while audio playing — reinitializing without tunneling")
            showToast("Recovering video...")
            if (contentType != ContentType.LIVE_TV && p.currentPosition > 1000L) startPositionMs = p.currentPosition
            player?.release(); player = null
            retryHandler.postDelayed({ currentUrl?.let { initializePlayer(it) } }, 250L)
        } else {
            handlePlaybackError(PlaybackException(null, null, PlaybackException.ERROR_CODE_REMOTE_ERROR))
        }
    }

    private fun readyStallRequiredStrikes(isLowRamDevice: Boolean): Int {
        if (playbackSource != PlaybackSource.DEBRID) {
            return when {
                contentType == ContentType.LIVE_TV -> LIVE_READY_STALL_STRIKES
                isLowRamDevice -> LOW_RAM_STALL_STRIKES
                else -> NON_DEBRID_READY_STALL_STRIKES
            }
        }
        if (directDebridPlayback) {
            return if (isLowRamDevice) DIRECT_DEBRID_LOW_RAM_STALL_STRIKES else DIRECT_DEBRID_READY_STALL_STRIKES
        }
        return DEBRID_READY_STALL_STRIKES
    }



    // Mi1: showSupportQr removed — never called (terminal failures route through
    // handleTerminalPlaybackFailure).

    private fun handleInitializationError(error: Exception) {
        handleTerminalPlaybackFailure("Init failed: ${error.message}")
    }

    private fun handleTimeout() {
        if (player?.playbackState == Player.STATE_BUFFERING) {
            // Progress-aware watchdog (live): if the buffer has GROWN since the
            // watchdog was armed, data is still flowing — the connection is slow,
            // not dead. Tearing the whole player down here (release + 2s wait +
            // full re-init) turns a recovering rebuffer into a much longer visible
            // reconnect on weak channels. Extend the window instead (bounded), and
            // only tear down when the buffer is genuinely not filling.
            if (contentType == ContentType.LIVE_TV) {
                val buffered = player?.bufferedPosition ?: 0L
                if (buffered > watchdogBufferedPosAtArm + 500L &&
                    watchdogExtensions < MAX_WATCHDOG_EXTENSIONS
                ) {
                    watchdogExtensions++
                    watchdogBufferedPosAtArm = buffered
                    Log.i(
                        "PlayerActivity",
                        "Buffering slow but progressing (buffered=${buffered}ms) — extending watchdog $watchdogExtensions/$MAX_WATCHDOG_EXTENSIONS"
                    )
                    timeoutHandler.postDelayed(timeoutRunnable, timeoutMs)
                    return
                }
            }
            val timedOutUrl = currentUrl
            PlaybackDiagnosticsRecorder.record(
                this,
                "buffer_timeout",
                diagnosticsPlaybackFields(timedOutUrl) + mapOf("reasonCode" to "TIMEOUT")
            )
            if (!timedOutUrl.isNullOrBlank() &&
                requiresAddonProxyPlaybackContext(timedOutUrl) &&
                !hasRenderedFirstFrameForCurrentSource
            ) {
                recordDirectAddonProxyFailure()
                finishWithReturnToSources(autoPlayNext = true, reason = "Connection timeout")
                return
            }
            if (!timedOutUrl.isNullOrBlank() && hasRepeatedDirectAddonProxyTimeout(timedOutUrl)) {
                recordDirectAddonProxyFailure()
                handleTerminalPlaybackFailure("Connection timeout")
                return
            }
            if (retryCount < (if (contentType == ContentType.LIVE_TV) LIVE_MAX_RETRIES else maxRetries)) {
                // Aggregate budget gate (fix 2): a stuck first-connect timeout is part
                // of the same reconnect family; refuse once the window is spent.
                if (!canAttemptReconnect()) {
                    handleTerminalPlaybackFailure("Connection timeout")
                    return
                }
                retryCount++
                PlaybackDiagnosticsRecorder.record(
                    this,
                    "retry_triggered",
                    diagnosticsPlaybackFields(timedOutUrl, retry = retryCount) + mapOf(
                        "reasonCode" to "TIMEOUT",
                        "retrySource" to "buffer_timeout"
                    )
                )
                if (directDebridPlayback && refreshDirectDebridSourceFromMetadata("buffer_timeout_direct_refresh")) {
                    return
                }
                if (canUseDebridResolver() && !isResolvingDebrid && retryCount > 1 && (!debridInfoHashExtra.isNullOrBlank() || !debridMagnetExtra.isNullOrBlank())) {
                      isResolvingDebrid = true
                      if (player != null && player!!.currentPosition > 1000L) startPositionMs = player!!.currentPosition
                      viewModel.reResolveDebridUrl(debridInfoHashExtra, debridMagnetExtra, seasonNumberExtra, episodeNumberExtra, episodeTitleExtra)
                      return
                }
                // No toast here either — the unified "RECONNECTING…" banner (shown by
                // canAttemptReconnect() above) is the single reconnect indicator.
                // Live first retry: light in-place re-prepare on the SAME engine
                // (the proven ENDED/freeze recovery path) instead of the heavy
                // release → 2s wait → full re-init. The player instance is healthy —
                // only the source starved — so this cuts each visible reconnect by
                // ~4-5s and avoids the audio-focus abandon/request churn. The full
                // engine rebuild is kept as the SECOND retry for wedged pipelines.
                if (contentType == ContentType.LIVE_TV && retryCount < LIVE_MAX_RETRIES && player != null) {
                    Log.i("PlayerActivity", "Buffer timeout — in-place re-prepare (retry $retryCount)")
                    currentUrl?.let { performSeamlessSwitch(it) }
                    return
                }
                if (contentType != ContentType.LIVE_TV && player != null && player!!.currentPosition > 1000L) startPositionMs = player!!.currentPosition
                PlaybackDiagnosticsRecorder.record(
                    this,
                    "release_player",
                    diagnosticsPlaybackFields(timedOutUrl) + mapOf("releaseReason" to "buffer_timeout_retry")
                )
                player?.release(); player = null
                retryHandler.postDelayed({ currentUrl?.let { initializePlayer(it) } }, 2000)
            } else {
                handleTerminalPlaybackFailure("Connection timeout")
            }
        }
    }

    private fun showError(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    private fun showToast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    /**
     * VOD Player redesign: wire explicit L/R DPAD focus across the transport row so navigation
     * never dead-ends — critical because exo_play/exo_pause overlap and swap visibility.
     */
    private fun wireControlFocus(isSeries: Boolean) {
        fun link(id: Int, leftId: Int, rightId: Int) {
            playerView.findViewById<View>(id)?.apply {
                nextFocusLeftId = leftId
                nextFocusRightId = rightId
            }
        }
        if (isSeries) {
            link(R.id.exo_rew, R.id.exo_rew, R.id.btn_prev_episode)
            link(R.id.btn_prev_episode, R.id.exo_rew, R.id.exo_play)
            link(R.id.exo_play, R.id.btn_prev_episode, R.id.btn_next_episode)
            link(R.id.exo_pause, R.id.btn_prev_episode, R.id.btn_next_episode)
            link(R.id.btn_next_episode, R.id.exo_play, R.id.exo_ffwd)
            link(R.id.exo_ffwd, R.id.btn_next_episode, R.id.btn_episodes)
            link(R.id.btn_episodes, R.id.exo_ffwd, R.id.btn_player_audio)
            link(R.id.btn_player_audio, R.id.btn_episodes, R.id.btn_player_subtitles)
        } else if (contentType == ContentType.MOVIE) {
            // Movies: the Sources button sits between fast-forward and the audio button,
            // so the focus chain must route through it (it was being skipped).
            link(R.id.exo_rew, R.id.exo_rew, R.id.exo_play)
            link(R.id.exo_play, R.id.exo_rew, R.id.exo_ffwd)
            link(R.id.exo_pause, R.id.exo_rew, R.id.exo_ffwd)
            link(R.id.exo_ffwd, R.id.exo_play, R.id.btn_player_sources)
            link(R.id.btn_player_sources, R.id.exo_ffwd, R.id.btn_player_audio)
            link(R.id.btn_player_audio, R.id.btn_player_sources, R.id.btn_player_subtitles)
        } else {
            link(R.id.exo_rew, R.id.exo_rew, R.id.exo_play)
            link(R.id.exo_play, R.id.exo_rew, R.id.exo_ffwd)
            link(R.id.exo_pause, R.id.exo_rew, R.id.exo_ffwd)
            link(R.id.exo_ffwd, R.id.exo_play, R.id.btn_player_audio)
            link(R.id.btn_player_audio, R.id.exo_ffwd, R.id.btn_player_subtitles)
        }
        link(R.id.btn_player_subtitles, R.id.btn_player_audio, R.id.btn_aspect_ratio)
        link(R.id.btn_aspect_ratio, R.id.btn_player_subtitles, R.id.btn_aspect_ratio)
    }

    /** VOD Player redesign: styled aspect-ratio selection popup (design ASPECT MENU). */
    private fun showAspectSelection() {
        if (isInPictureInPictureMode) return
        val modes = listOf(
            androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT to "Fit",
            androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FILL to "Stretch",
            androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM to "Zoom",
            androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIXED_WIDTH to "Fixed Width",
            androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIXED_HEIGHT to "Fixed Height"
        )
        val labels = modes.map { it.second }
        val current = modes.indexOfFirst { it.first == playerView.resizeMode }.coerceAtLeast(0)
        lateinit var dialog: androidx.appcompat.app.AlertDialog
        val adapter = TrackSelectionAdapter(labels, current) { which ->
            val mode = modes[which].first
            playerView.resizeMode = mode
            updateAspectLabel(mode)
            dialog.dismiss()
        }
        dialog = androidx.appcompat.app.AlertDialog.Builder(this, R.style.Theme_DebridXtream_CinematicDialog)
            .setTitle("Aspect Ratio")
            .setAdapter(adapter, adapter.asDialogClickListener())
            .create()
        showManagedTrackDialog(dialog, R.id.btn_aspect_ratio)
    }

    private fun updateAspectLabel(mode: Int) {
        val label = when (mode) {
            androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FILL -> "FILL"
            androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> "ZOOM"
            androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIXED_WIDTH -> "W·FIT"
            androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIXED_HEIGHT -> "H·FIT"
            else -> "FIT"
        }
        playerView.findViewById<TextView>(R.id.tv_aspect_label)?.text = label
    }

    private fun cycleResizeMode() {
        val nextMode = when (playerView.resizeMode) {
            androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FILL
            androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FILL -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIXED_WIDTH
            androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIXED_WIDTH -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIXED_HEIGHT
            else -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
        }
        playerView.resizeMode = nextMode
        showToast("Resize Mode: ${when(nextMode) {
            androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT -> "Fit"
            androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FILL -> "Stretch"
            androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> "Zoom"
            androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIXED_WIDTH -> "Fixed Width"
            androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIXED_HEIGHT -> "Fixed Height"
            else -> "Fit"
        }}")
    }

    // T2.1/M7: restore playback for the retained-player path (pause-without-release
    // cycles: Home→return on VOD/Debrid, system dialogs, multi-window transitions).
    private var wasPlayingBeforePause = false

    override fun onResume() {
        super.onResume(); debugOverlay.start()
        val p = player
        if (p == null) {
            if (!isResolvingDebrid) currentUrl?.let { initializePlayer(it) }
        } else {
            startStallMonitor()
            // QA fix: don't audibly resume a stale stream underneath an in-flight
            // debrid re-resolve — the resolver's Success switch takes over playback.
            if (wasPlayingBeforePause && !isResolvingDebrid) { p.play(); wasPlayingBeforePause = false }
        }
    }
    // Re-arm history for the new foreground segment: onStop's exit-save latches
    // hasRecordedHistory=true, and without this reset a Home→return session would
    // silently skip both the 30s progress heartbeat and the final exit save.
    override fun onStart() { super.onStart(); hasRecordedHistory = false; registerNetworkCallback(); if (::nextEpisodeManager.isInitialized) { nextEpisodeManager.onStart() }; if (seekOverlay != null) { seekOverlayHandler.removeCallbacks(seekOverlayRunnable); seekOverlayHandler.post(seekOverlayRunnable) }; progressSaveHandler.removeCallbacks(progressSaveRunnable); progressSaveHandler.postDelayed(progressSaveRunnable, PROGRESS_SAVE_INTERVAL_MS) }
    override fun onPause() { super.onPause(); if (::historyManager.isInitialized) historyManager.saveProgressSnapshot(); wasPlayingBeforePause = player?.isPlaying == true; player?.pause(); timeoutHandler.removeCallbacks(timeoutRunnable); stopStallMonitor() }
    override fun onStop() {
        super.onStop(); dismissActiveTrackDialog(); debugOverlay.stop(); timeoutHandler.removeCallbacks(timeoutRunnable); stallHandler.removeCallbacks(stallRunnable); seekOverlayHandler.removeCallbacks(seekOverlayRunnable); progressSaveHandler.removeCallbacks(progressSaveRunnable); if (::nextEpisodeManager.isInitialized) { nextEpisodeManager.onStop() }; unregisterNetworkCallback(); historyManager.recordPlaybackHistoryIfNeeded()
        // T2.1 (H1): retain the paused player across short stops for VOD/Debrid so
        // Home→return resumes instantly instead of a 1-3s cold reconnect. LIVE still
        // releases: on max_connections=1 Xtream servers a retained live connection
        // would hold the account's only slot while backgrounded (and a live stream
        // has no resume value anyway — it re-tunes to the live edge).
        if (contentType == ContentType.LIVE_TV || isFinishing) {
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
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_BACKGROUND && player != null && contentType != ContentType.LIVE_TV) {
            releasePlayer("trim_memory_$level")
        }
    }

    /**
     * Live TV sessions launched from the EPG guide share one ExoPlayer with the
     * guide's mini preview. On exit, hand the running player back to the guide
     * (via [LiveSharedPlayer]) instead of releasing it, so playback continues
     * seamlessly through the shrink-to-mini transition.
     */
    private fun handBackSharedPlayerIfNeeded(frame: android.graphics.Bitmap? = null): Boolean {
        if (!sharedLiveSession || contentType != ContentType.LIVE_TV) return false
        val p = player ?: return false
        playerListener?.let { p.removeListener(it) }
        playerListener = null
        debugListener?.let { p.removeListener(it) }
        debugListener = null
        stopStallMonitor()
        timeoutHandler.removeCallbacks(timeoutRunnable)
        playerView.player = null
        player = null
        LiveSharedPlayer.offer(p, currentUrl, contentId, frame)
        PlaybackDiagnosticsRecorder.record(
            this,
            "player_handed_back_shared",
            diagnosticsPlaybackFields()
        )
        return true
    }

    private fun releasePlayer(reason: String = "unspecified") {
        PlaybackDiagnosticsRecorder.record(
            this,
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
    private fun maybeSnapToLiveEdge() {
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
    private fun maybeMatchDisplayFrameRate() {
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

    // Distance of refreshRate from the nearest integer multiple of frameRate (0 = judder-free).
    private fun startStallMonitor() {
        stallDetector.reset(player?.currentPosition ?: 0L, SystemClock.elapsedRealtime())
        freezeDetector.reset()
        stallHandler.removeCallbacks(stallRunnable); stallHandler.postDelayed(stallRunnable, 5000)
    }

    private fun stopStallMonitor() = stallHandler.removeCallbacks(stallRunnable)

    override fun onDestroy() { super.onDestroy(); loaderUi.release(); dismissActiveTrackDialog(); liveOsd?.release(); historyManager.recordPlaybackHistoryIfNeeded(); releasePlayer("on_destroy"); PlaybackDiagnosticsRecorder.finishSession(this, "activity_destroyed") }

    private fun resolveTimeoutMs(url: String): Long = when {
        url.lowercase().contains("mediafusion.elfhosted.com") -> MEDIAFUSION_TIMEOUT_MS
        contentType == ContentType.LIVE_TV -> LIVE_TIMEOUT_MS
        else -> TIMEOUT_MS
    }

    override fun finish() {
        setLiveExitResultIfNeeded()
        setExitResultIfNeeded()
        super.finish()
    }

    /**
     * The real BACK exit: records history, flags manualExit (for return-to-sources), and — for a
     * shared Live TV session — hands the running player back to the guide before finishing.
     * Shared by the onBackPressedDispatcher callback (Live/EPG paths) and the VOD dispatchKeyEvent
     * path (which must consume BACK so the media3 controller can't swallow it).
     */
    private fun performBackExit() {
        manualExit = true
        updateLastPlaybackPosition()
        historyManager.recordPlaybackHistoryIfNeeded()
        if (sharedLiveSession && contentType == ContentType.LIVE_TV && player != null) {
            if (sharedExitInProgress) return
            sharedExitInProgress = true
            // Snapshot the on-screen frame first (async for SurfaceView),
            // then hand the running player back and leave without animation.
            captureVideoFrame(playerView) { frame ->
                if (isFinishing || isDestroyed) return@captureVideoFrame
                handBackSharedPlayerIfNeeded(frame)
                releasePlayer()
                finish()
                overridePendingTransition(0, 0)
            }
            return
        }
        releasePlayer()
        finish()
    }

    private lateinit var browserCategoryAdapter: BrowserCategoryAdapter
    private lateinit var browserChannelAdapter: BrowserChannelAdapter

    private fun initBrowserOverlay() {
        val browserView = findViewById<View>(R.id.view_channel_browser) ?: return
        val rvCategories = browserView.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.rv_browser_categories)
        val rvChannels = browserView.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.rv_browser_channels)
        val pbLoading = browserView.findViewById<View>(R.id.pb_browser_loading)
        val tvEmpty = browserView.findViewById<View>(R.id.tv_browser_empty)
        val tvClock = browserView.findViewById<android.widget.TextView>(R.id.tv_browser_clock)

        tvClock.text = java.text.SimpleDateFormat("h:mm a", java.util.Locale.getDefault()).format(java.util.Date())

        browserCategoryAdapter = BrowserCategoryAdapter(
            onCategoryClick = { category -> category.category_id?.let { viewModel.selectBrowserCategory(it) } },
            onRightPressed = {
                if (browserChannelAdapter.itemCount > 0) {
                    rvChannels.post {
                        val targetIndex = if (contentId != null) {
                            browserChannelAdapter.currentList.indexOfFirst { it.stream_id == contentId }.coerceAtLeast(0)
                        } else 0
                        rvChannels.scrollToPosition(targetIndex)
                        rvChannels.post {
                            rvChannels.findViewHolderForAdapterPosition(targetIndex)?.itemView?.requestFocus()
                                ?: rvChannels.requestFocus()
                        }
                    }
                }
            }
        )
        rvCategories.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this); rvCategories.adapter = browserCategoryAdapter

        browserChannelAdapter = BrowserChannelAdapter(
            onChannelClick = { stream ->
                stream.stream_id?.let { id ->
                    val serverUrl = baseServerUrl ?: prefs.getServerUrl() ?: ""
                    val username = prefs.getUsername().orEmpty()
                    val password = prefs.getPassword().orEmpty()
                    val newUrl = stream.toLiveStreamUrl(serverUrl, username, password)
                    performSeamlessSwitch(newUrl)
                    contentId = id; currentUrl = newUrl; channelLogoUrl = com.tvonnet.debridxtreamiptv.util.GlobalConfig.resolveIconUrl(stream.stream_icon); currentEpgChannelId = stream.epg_channel_id ?: id
                    bindChannelMeta(stream.name); supportActionBar?.title = stream.name
                    viewModel.initLiveZapping(viewModel.browserState.value.selectedCategoryId, id, serverUrl, null)
                    viewModel.observeEpg(stream.epg_channel_id ?: id, id)
                    viewModel.toggleBrowser(false)
                }
            },
            onLeftPressedAtZero = {
                val selectedPos = browserCategoryAdapter.getSelectedPosition().coerceAtLeast(0)
                rvCategories.scrollToPosition(selectedPos)
                rvCategories.post {
                    rvCategories.findViewHolderForAdapterPosition(selectedPos)?.itemView?.requestFocus()
                        ?: rvCategories.requestFocus()
                }
            }
        )
        rvChannels.layoutManager = androidx.recyclerview.widget.GridLayoutManager(this, 4); rvChannels.adapter = browserChannelAdapter
        
        lifecycleScope.launch {
            var wasVisible = false
            viewModel.browserState.collect { state ->
                browserView.visibility = if (state.isVisible) View.VISIBLE else View.GONE
                if (state.isVisible) {
                    browserCategoryAdapter.submitList(state.categories); browserCategoryAdapter.setSelectedId(state.selectedCategoryId)
                    browserChannelAdapter.submitList(state.channels) {
                         if (state.channels.isNotEmpty() && contentId != null) {
                              val chanIndex = state.channels.indexOfFirst { it.stream_id == contentId }
                              if (chanIndex != -1) { rvChannels.scrollToPosition(chanIndex); rvChannels.post { rvChannels.findViewHolderForAdapterPosition(chanIndex)?.itemView?.requestFocus() } }
                         }
                    }
                    pbLoading.visibility = if (state.isLoadingChannels) View.VISIBLE else View.GONE
                    tvEmpty.visibility = if (!state.isLoadingChannels && state.channels.isEmpty()) View.VISIBLE else View.GONE
                } else if (wasVisible) {
                    com.tvonnet.debridxtreamiptv.utils.FocusCoordinator.force("PLAYER") { playerView.requestFocus() }
                }
                wasVisible = state.isVisible
            }
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // LP-D-6: per-key logging only in debug builds (was unconditional).
        if (BuildConfig.DEBUG) {
            Log.d("PlayerActivity", "dispatchKeyEvent: code=${event.keyCode}, action=${event.action}")
        }

        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            // In-player TV Guide: BACK closes the grid instead of exiting.
            if (liveOsd?.isGuideOpen == true) {
                if (event.action == KeyEvent.ACTION_UP) liveOsd?.handleGuideBack()
                return true
            }
            // Surf drawer: BACK steps categories→channels→close; else exits (spec §8).
            if (liveOsd?.isDrawerOpen == true) {
                if (event.action == KeyEvent.ACTION_UP) liveOsd?.handleDrawerBack()
                return true
            }
            // VOD Player redesign: BACK cancels the "Up Next" auto-play prompt instead of exiting.
            if (::nextEpisodeManager.isInitialized && nextEpisodeManager.isPromptVisible) {
                if (event.action == KeyEvent.ACTION_UP) nextEpisodeManager.hidePrompt()
                return true
            }
            val isXrayVisible = xrayController.isPanelVisible()
            val isEpisodeBrowserVisible = ::episodeBrowserController.isInitialized && episodeBrowserController.isVisible()
            // These two overlays aren't handled by onBackPressedDispatcher, so dismiss them here.
            if (isXrayVisible || isEpisodeBrowserVisible) {
                if (event.action == KeyEvent.ACTION_UP) {
                    if (isEpisodeBrowserVisible) episodeBrowserController.hide()
                    xrayController.collapse()
                }
                return true
            }
            // Long-press BACK -> PiP (if supported).
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount > 0 && supportsPictureInPicture()) {
                enterPictureInPictureModeInternal()
                return true
            }
            // VOD (movie/episode): consume BACK here — media3's controller would otherwise swallow it
            // so onBackPressedDispatcher never runs (the "BACK doesn't exit" bug). 1st press hides the
            // controls, 2nd runs the real exit (records history, sets manualExit).
            if (contentType == ContentType.MOVIE || contentType == ContentType.EPISODE) {
                if (event.action == KeyEvent.ACTION_UP) {
                    when (vodBackAction(isInPictureInPictureMode, isControllerVisible, backHideArmed)) {
                        VodBackAction.PASS_THROUGH -> return super.dispatchKeyEvent(event)
                        VodBackAction.HIDE_CONTROLLER -> {
                            playerView.hideController()
                            backHideArmed = true
                        }
                        VodBackAction.EXIT -> performBackExit()
                    }
                }
                return true
            }
            // LIVE_TV and anything else: fall through to onBackPressedDispatcher, which handles the
            // channel browser, EPG, and the shared Live TV player hand-off + manualExit / history.
        }

        if (event.action == KeyEvent.ACTION_DOWN) {
            if (::episodeBrowserController.isInitialized && episodeBrowserController.isVisible()) {
                // VOD Player redesign: coverflow panel. Delegate UP/DOWN to the list; LEFT/BACK
                // close it — restore transport controls when it closes. While it stays open, consume
                // every key so an unhandled one can't fall through and flash the controller underneath.
                playerView.hideController()
                episodeBrowserController.handleKeyEvent(event)
                if (!episodeBrowserController.isVisible()) {
                    showControllerWithSmartFocus()
                }
                return true
            }

            // VOD Player redesign: the episodes list opens ONLY via the EPISODES button now
            // (DPAD_DOWN no longer auto-opens it).

            // Standard Controller triggers
            if (contentType != ContentType.LIVE_TV && playerView.useController && !(::nextEpisodeManager.isInitialized && nextEpisodeManager.isPromptVisible) && !playerView.isControllerFullyVisible) {
                if (isControllerTriggerKey(event.keyCode)) {
                    showControllerWithSmartFocus(event)
                    return true
                }
            }

            // Cinematic live OSD routing (spec §8): ◀ drawer, ▶/OK controls row.
            // Zap keys fall through to the branches below.
            if (contentType == ContentType.LIVE_TV && !viewModel.browserState.value.isVisible) {
                liveOsd?.let { osd -> if (osd.handleKeyEvent(event)) return true }
            }
            val surfDrawerOpen = liveOsd?.isDrawerOpen == true
            // Guide owns the screen while open — never zap the channel underneath it.
            val guideOpen = liveOsd?.isGuideOpen == true

            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> { if (contentType == ContentType.LIVE_TV && liveOsd == null && !viewModel.browserState.value.isVisible) { viewModel.toggleBrowser(true, viewModel.zapState.value?.categoryId ?: liveCategoryId, contentId); return true } }
                KeyEvent.KEYCODE_CAPTIONS -> { if (contentType != ContentType.LIVE_TV) { showSubtitleSelection(); return true } }
                KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_PAGE_UP,
                KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.KEYCODE_PAGE_DOWN,
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                    val direction = zapDirectionFor(event.keyCode)
                    if (direction != null && canZapNow(
                            action = event.action,
                            repeatCount = event.repeatCount,
                            contentType = contentType,
                            surfaces = OpenPlayerSurfaces(
                                isBrowserVisible = viewModel.browserState.value.isVisible,
                                isSurfDrawerOpen = surfDrawerOpen,
                                isGuideOpen = guideOpen
                            )
                        )
                    ) {
                        zapChannel(direction = direction)
                        return true
                    }
                }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> { if (contentType == ContentType.LIVE_TV) { if (viewModel.browserState.value.isVisible || liveOsd != null) return super.dispatchKeyEvent(event); if (epgOverlayUi.mode != EpgOverlayMode.HIDDEN && !epgOverlayUi.isPinned) hideEpgOverlay() else showEpgOverlay(EpgOverlayMode.COMPACT, pinned = false); return true } }
                KeyEvent.KEYCODE_INFO -> {
                    if (contentType == ContentType.LIVE_TV) {
                        liveOsd?.showOsd(focus = true) ?: toggleEpgOverlayPinned()
                    } else { backHideArmed = false; playerView.showController(); playerView.requestFocus() }
                    return true
                }
                KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_GUIDE -> {
                    if (contentType == ContentType.LIVE_TV) {
                        liveOsd?.let {
                            viewModel.toggleBrowser(true, viewModel.zapState.value?.categoryId ?: liveCategoryId, contentId)
                        } ?: toggleEpgOverlayPinned()
                        return true
                    }
                    if (playerView.isControllerFullyVisible) { showSubtitleSelection(); return true }
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onKeyLongPress(keyCode: Int, event: KeyEvent): Boolean {
        if (contentType == ContentType.LIVE_TV) { when (keyCode) { KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> { liveOsd?.showOsd(focus = true) ?: toggleEpgOverlayPinned(); return true } } }
        return super.onKeyLongPress(keyCode, event)
    }

    override fun onUserInteraction() { 
        super.onUserInteraction()
        if (contentType == ContentType.LIVE_TV || isInPictureInPictureMode || !playerView.useController || (::nextEpisodeManager.isInitialized && nextEpisodeManager.isPromptVisible)) return
        
        // Fix: Don't show controller if browser is open
        if (::episodeBrowserController.isInitialized && episodeBrowserController.isVisible()) {
            playerView.hideController()
            return
        }
        
        if (!playerView.isControllerFullyVisible) {
            showControllerWithSmartFocus()
        }
    }

    private fun showControllerWithSmartFocus(sourceEvent: KeyEvent? = null) {
        // Explicit user show re-arms the two-step BACK (1st hide, 2nd exit).
        backHideArmed = false
        playerView.showController()
        installControllerSeekNavigation()
        val keyCode = sourceEvent?.keyCode
        val wantsToSeek = keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
        Log.d("PLAYER_SEEK_FOCUS", "show controller focus key=$keyCode wantsToSeek=$wantsToSeek")
        
        playerView.postDelayed({
            if (wantsToSeek) {
                val seekBar = playerView.findViewById<View>(R.id.exo_progress)
                seekBar?.requestFocus()
                if (sourceEvent?.action == KeyEvent.ACTION_DOWN) {
                    seekBar?.dispatchKeyEvent(sourceEvent)
                    seekBar?.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode ?: KeyEvent.KEYCODE_UNKNOWN))
                }
            } else {
                focusVisiblePlayPause()
            }
        }, 50L)
    }

    private fun installControllerSeekNavigation() {
        val seekBar = playerView.findViewById<View>(R.id.exo_progress) ?: return
        seekBar.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN || keyCode != KeyEvent.KEYCODE_DPAD_DOWN) {
                return@setOnKeyListener false
            }
            focusVisiblePlayPause()
            true
        }
    }

    private fun focusVisiblePlayPause() {
        val target = playerView.findViewById<View>(R.id.exo_pause)?.takeIf { it.isVisible }
            ?: playerView.findViewById<View>(R.id.exo_play)?.takeIf { it.isVisible }
            ?: playerView.findViewById<View>(R.id.exo_rew)
        target?.requestFocus()
        Log.d("PLAYER_SEEK_FOCUS", "focus transport target=${target?.resources?.getResourceEntryName(target.id)}")
    }

    private fun supportsPictureInPicture(): Boolean = pipController.isSupported()

    @RequiresApi(Build.VERSION_CODES.O)
    private fun enterPictureInPictureModeInternal() {
        if (!supportsPictureInPicture() || isInPictureInPictureMode) return
        try {
            player?.let { wasPlayingBeforePiP = it.playWhenReady }
            if (pipController.enter(playerView.width, playerView.height)) hideUiForPiP() else showToast("PiP not available")
        } catch (e: Exception) { showToast("PiP failed") }
    }

    // NOTE: snapshot (non-latching) — the old recordPlaybackHistoryIfNeeded() here latched
    // hasRecordedHistory on PiP entry, so everything watched IN PiP was never saved and
    // resume went back to the PiP-entry position.
    private fun hideUiForPiP() { playerView.hideController(); if (::episodeBrowserController.isInitialized) episodeBrowserController.hide(); xrayController.collapse(); epgOverlay?.isVisible = false; vodInfoOverlay?.isVisible = false; liveOsd?.hideForPip(); supportActionBar?.hide(); historyManager.saveProgressSnapshot() }
    private fun showUiForNormalMode() { if (!isInPictureInPictureMode) { supportActionBar?.show(); liveOsd?.show(); if (contentType == ContentType.LIVE_TV) updateOverlayVisibility() else updateVodOverlayVisibility() } }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: android.content.res.Configuration) { super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig); this.isInPictureInPictureMode = isInPictureInPictureMode; if (isInPictureInPictureMode) hideUiForPiP() else showUiForNormalMode() }

    private fun updateOverlayVisibility() = epgOverlayUi.applyVisibility()

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

    private fun showEpgOverlay(mode: EpgOverlayMode, pinned: Boolean) {
        if (contentType != ContentType.LIVE_TV) return
        epgOverlayUi.show(mode, pinned)
    }
    private fun hideEpgOverlay() = epgOverlayUi.hide()
    private fun toggleEpgOverlayPinned() = epgOverlayUi.togglePinned()

    private fun formatTimeRangeCompact(program: com.tvonnet.debridxtreamiptv.data.local.entity.EpgEntity): String = "${timeFormatter.format(Date(program.start))}-${timeFormatter.format(Date(program.stop))}"






    private fun updateLastPlaybackPosition() { lastPlaybackPositionMs = player?.currentPosition ?: lastPlaybackPositionMs }
    private fun setLiveExitResultIfNeeded() {
        if (contentType != ContentType.LIVE_TV || exitResultHandled) return
        val streamId = contentId?.takeIf { it.isNotBlank() } ?: return
        setResult(
            Activity.RESULT_OK,
            buildLiveReturnIntent(
                LiveReturnChannel(
                    channelId = streamId,
                    channelName = tvChannelName?.text?.toString() ?: pendingChannelName,
                    channelLogoUrl = channelLogoUrl,
                    epgChannelId = currentEpgChannelId,
                    streamUrl = currentUrl,
                    categoryId = liveCategoryId
                )
            )
        )
    }

    private fun setExitResultIfNeeded() { if (exitResultHandled || !returnToSourcesOnExit || playbackSource != PlaybackSource.DEBRID || didPlaybackComplete) return; val pos = player?.currentPosition ?: lastPlaybackPositionMs; if (manualExit || pos < RETURN_TO_SOURCES_THRESHOLD_MS) { exitResultHandled = true; setResult(Activity.RESULT_OK, buildReturnToSourcesIntent()) } }
    private fun finishWithReturnToSources(autoPlayNext: Boolean, reason: String?): Boolean { if (!returnToSourcesOnExit || playbackSource != PlaybackSource.DEBRID || exitResultHandled) return false; exitResultHandled = true; PlaybackDiagnosticsRecorder.record(this, "return_to_sources", diagnosticsPlaybackFields() + mapOf("reasonCode" to PlaybackDiagnosticsRecorder.sanitizeReason(reason), "autoPlayNext" to autoPlayNext)); setResult(Activity.RESULT_OK, buildFailedSourceReturnIntent(failedStreamId = debridStreamIdExtra ?: debridInfoHashExtra ?: contentId ?: currentUrl, reason = reason, autoPlayNext = autoPlayNext)); releasePlayer("return_to_sources"); PlaybackDiagnosticsRecorder.finishSession(this, "return_to_sources"); finish(); return true }
    private fun handleTerminalPlaybackFailure(reason: String, preferReturnToSources: Boolean = false) {
        PlaybackDiagnosticsRecorder.record(
            this,
            "terminal_failure",
            diagnosticsPlaybackFields() + mapOf(
                "reasonCode" to PlaybackDiagnosticsRecorder.sanitizeReason(reason),
                "httpStatusCode" to PlaybackDiagnosticsRecorder.httpStatusCode(reason)
            )
        )
        // autoPlayNext=true: the detail screens auto-try the next ranked source
        // (capped there) instead of dropping the user at the picker.
        if (preferReturnToSources && finishWithReturnToSources(autoPlayNext = true, reason = reason)) {
            return
        }
        val redirected = redirectToFailureDetail(reason)
        if (redirected) {
            return
        }
        if (!finishWithReturnToSources(autoPlayNext = true, reason = reason)) {
            val isHttpError = reason.contains("HTTP", ignoreCase = true)
            showError(
                when {
                    contentType == ContentType.LIVE_TV ->
                        "Channel stream is broken at the provider\n\nTry another channel or source"
                    reason.contains("timeout", ignoreCase = true) ->
                        "Connection timeout\n\nStream is too slow or unavailable"
                    // Plain IPTV/Xtream dead listing (405/404/5xx): the provider simply
                    // doesn't have this file — steer the user to a debrid source.
                    playbackSource == PlaybackSource.IPTV && isHttpError ->
                        "This IPTV listing isn't available from your provider\n\nTry a debrid source or another listing"
                    else -> "Playback failed\n\n$reason"
                }
            )
            Handler(Looper.getMainLooper()).postDelayed({ finish() }, 3000)
        }
    }
    private fun redirectToFailureDetail(reason: String): Boolean {
        if (playbackSource != PlaybackSource.DEBRID || contentType == ContentType.LIVE_TV || isFinishing) return false
        val detailIntent = when (contentType) {
            ContentType.MOVIE -> Intent(this, MovieDetailActivity::class.java).apply {
                putExtra(MovieDetailActivity.EXTRA_MOVIE_ID, tmdbIdExtra ?: contentId ?: debridStreamIdExtra ?: debridInfoHashExtra)
                putExtra(MovieDetailActivity.EXTRA_MOVIE_NAME, originalTitle)
                putExtra(MovieDetailActivity.EXTRA_MOVIE_ICON, posterUrlExtra)
                putExtra(MovieDetailActivity.EXTRA_MOVIE_BACKDROP, backdropUrlExtra)
                putExtra(MovieDetailActivity.EXTRA_MOVIE_CATEGORY_ID, "debrid")
            }
            ContentType.SERIES, ContentType.EPISODE -> Intent(this, SeriesDetailActivity::class.java).apply {
                putExtra(SeriesDetailActivity.EXTRA_SERIES_ID, debridSeriesLookupId())
                putExtra(SeriesDetailActivity.EXTRA_SERIES_NAME, seriesTitleExtra ?: originalTitle)
                putExtra(SeriesDetailActivity.EXTRA_SERIES_COVER, posterUrlExtra)
                putExtra(SeriesDetailActivity.EXTRA_SERIES_BACKDROP, backdropUrlExtra)
                // This redirect only runs for DEBRID playback (guarded at the top of the method),
                // so open the detail screen in debrid mode — without this it loaded as Xtream and
                // showed "No episodes" for debrid content.
                putExtra(SeriesDetailActivity.EXTRA_IS_DEBRID, true)
            }
            null, ContentType.LIVE_TV -> null
        } ?: return false

        detailIntent.putExtra(EXTRA_OPENED_FROM_PLAYBACK_FAILURE, true)
        detailIntent.putExtra(EXTRA_FAIL_REASON, reason)
        releasePlayer("failure_detail_redirect")
        PlaybackDiagnosticsRecorder.finishSession(this, "failure_detail_redirect")
        startActivity(detailIntent)
        finish()
        return true
    }

    /**
     * In-player movie "Sources" panel: fetch the movie's sources and show the same picker
     * as the detail screen so the user can switch source/language (e.g. Hindi → German)
     * mid-playback. Picking one relaunches the player on that source at the current position.
     */
    private fun openMovieSourcePanel() {
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
        val isIptv = source.sourceType == "IPTV"
        val isDirectHttp = !isIptv && magnet?.startsWith("http", ignoreCase = true) == true &&
            !magnet.endsWith(".torrent", ignoreCase = true)
        val resolverBacked = !isIptv && !isDirectHttp
        if (resolverBacked && magnet.isNullOrBlank() && infoHash.isNullOrBlank()) {
            showToast("Invalid source"); return
        }

        // Resume the new source from where we are now.
        startPositionMs = (player?.currentPosition ?: startPositionMs).coerceAtLeast(0L)
        playbackSource = if (isIptv) PlaybackSource.IPTV else PlaybackSource.DEBRID
        debridStreamIdExtra = stream.stream_id
        stream.name?.let { originalTitle = it }
        // Refresh the on-screen title so the controller shows the source we just switched to,
        // not the previous one.
        bindModernMetadata(stream.name ?: originalTitle)

        when {
            isIptv -> {
                val prefs = CredentialsPreferences(this)
                val server = prefs.getServerUrl()?.trimEnd('/')
                val user = prefs.getUsername()
                val pass = prefs.getPassword()
                if (server.isNullOrBlank() || user.isNullOrBlank() || pass.isNullOrBlank() || stream.stream_id.isNullOrBlank()) {
                    showToast("Missing credentials"); return
                }
                directDebridPlayback = false
                debridInfoHashExtra = null; debridMagnetExtra = null
                val ext = stream.container_extension?.takeIf { it.isNotBlank() } ?: "mp4"
                showCinematicLoader()
                initializePlayer("$server/movie/$user/$pass/${stream.stream_id}.$ext")
            }
            isDirectHttp -> {
                directDebridPlayback = true
                debridInfoHashExtra = null; debridMagnetExtra = null
                showCinematicLoader()
                initializePlayer(magnet!!)
            }
            else -> {
                // Resolver-backed magnet: set the new identity and let the player resolve it.
                directDebridPlayback = false
                debridInfoHashExtra = infoHash
                debridMagnetExtra = magnet
                isResolvingDebrid = true
                showCinematicLoader()
                viewModel.reResolveDebridUrl(infoHash, magnet, null, null, originalTitle)
            }
        }
    }

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
            this,
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
        val disp = if (Build.VERSION.SDK_INT >= 30) display else @Suppress("DEPRECATION") windowManager.defaultDisplay
        @Suppress("DEPRECATION")
        (disp?.hdrCapabilities?.supportedHdrTypes ?: IntArray(0))
            .any { it == android.view.Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION }
    } catch (e: Exception) {
        false
    }

    /** P11 shim: the loader needs the launch extras, so the Activity supplies them. */
    private fun showCinematicLoader() = loaderUi.show(
        rawTitle = seriesTitleExtra?.takeIf { it.isNotBlank() } ?: originalTitle,
        backdropUrl = backdropUrlExtra?.takeIf { it.isNotBlank() } ?: posterUrlExtra?.takeIf { it.isNotBlank() }
    )

    private fun observeDebridResolutionState() {
        lifecycleScope.launch {
            viewModel.debridResolutionState.collect { state ->
                when (state) {
                    is DebridResolutionState.Loading -> { isResolvingDebrid = true; showCinematicLoader() }
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
                            hasRecordedHistory = false; if (::nextEpisodeManager.isInitialized) { nextEpisodeManager.resetState() }
                        }
                        currentUrl = state.url
                        if (player == null) {
                            initializePlayer(state.url)
                        } else {
                            performSeamlessSwitch(state.url)
                        }
                    }
                    is DebridResolutionState.Error -> {
                        loaderUi.hide()
                        isResolvingDebrid = false
                        handleTerminalPlaybackFailure("Failed: ${state.message}")
                    }
                    is DebridResolutionState.Idle -> { loaderUi.hide(); isResolvingDebrid = false }
                }
            }
        }
    }

    private fun captureManualTrackSelection() {
        val p = player ?: return
        val seriesId = intent.getStringExtra(EXTRA_SERIES_ID) ?: tmdbIdExtra ?: imdbIdExtra
        trackManager.captureManualTrackSelection(p, debridInfoHashExtra, seriesId)
        PlaybackDiagnosticsRecorder.record(
            this,
            "track_selected",
            diagnosticsPlaybackFields() + trackDiagnosticsFields(p.currentTracks)
        )
    }

    private fun applyTrackIndexOverrides() {
        val p = player ?: return
        val seriesId = intent.getStringExtra(EXTRA_SERIES_ID) ?: tmdbIdExtra ?: imdbIdExtra
        trackManager.applyTrackIndexOverrides(p, debridInfoHashExtra, seriesId)
    }

}
