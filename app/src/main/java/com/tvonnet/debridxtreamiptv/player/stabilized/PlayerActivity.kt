package com.tvonnet.debridxtreamiptv.player.stabilized

import android.app.Activity
import android.app.ActivityManager
import android.app.Dialog
import android.app.PictureInPictureParams
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.Rect
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.util.Rational
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
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.DefaultTrackNameProvider
import androidx.media3.ui.PlayerView
import androidx.media3.ui.TrackSelectionDialogBuilder
import com.tvonnet.debridxtreamiptv.BuildConfig
import com.tvonnet.debridxtreamiptv.R
import com.tvonnet.debridxtreamiptv.data.model.ContentType
import com.tvonnet.debridxtreamiptv.data.model.ContinueWatchingItem
import com.tvonnet.debridxtreamiptv.data.model.RecentLiveChannelItem
import com.tvonnet.debridxtreamiptv.data.model.toLiveStreamUrl

import com.tvonnet.debridxtreamiptv.data.model.toAbsoluteUrl
import com.tvonnet.debridxtreamiptv.data.prefs.CredentialsPreferences
import com.tvonnet.debridxtreamiptv.data.prefs.WatchHistoryPreferences
import kotlinx.coroutines.launch
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import okhttp3.OkHttpClient
import com.tvonnet.debridxtreamiptv.data.prefs.SettingsPreferences
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.TsExtractor
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

    @Inject
    lateinit var settingsPreferences: SettingsPreferences

    private val prefs by lazy { CredentialsPreferences(this) }

    private var isInPictureInPictureMode = false
    private var wasPlayingBeforePiP = true

    internal var player: ExoPlayer? = null
    private lateinit var playerView: PlayerView
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
    private var isControllerVisible = false

    private enum class EpgOverlayMode { HIDDEN, COMPACT, EXPANDED }
    private val overlayHandler = Handler(Looper.getMainLooper())
    private val overlayHideRunnable = Runnable { maybeAutoHideOverlay() }
    private var epgOverlayMode: EpgOverlayMode = EpgOverlayMode.HIDDEN
    private var epgOverlayPinned: Boolean = false
    private var isSwitching = false
    private var layoutDebugOverlay: View? = null
    private var tvDebugInfo: TextView? = null
    private var debugEnabled = false
    private var switchCount = 0
    private var debugListener: Player.Listener? = null
    private val debugOverlayHandler = Handler(Looper.getMainLooper())
    private val debugOverlayRunnable = object : Runnable {
        override fun run() {
            if (isFinishing || isDestroyed) return
            val p = player
            if (!debugEnabled || p == null) return
            updateDebugOverlay()
            debugOverlayHandler.postDelayed(this, 1000)
        }
    }

    private fun startDebugOverlay() {
        debugOverlayHandler.removeCallbacks(debugOverlayRunnable)
        if (debugEnabled && player != null) {
            debugOverlayHandler.post(debugOverlayRunnable)
        }
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

    private var layoutSupportQr: View? = null
    private var layoutDebridResolving: View? = null
    private var tvResolvingStatus: TextView? = null
    private var imgSupportQr: ImageView? = null
    private val stallHandler = Handler(Looper.getMainLooper())
    private var lastBoundPosition = 0L
    private var lastProgressCheckMs = 0L
    private var lastBufferingStartMs = 0L
    private var stallStrikeCount = 0
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
        const val EXTRA_LIVE_RETURN_CHANNEL_NAME = "LIVE_RETURN_CHANNEL_NAME"
        const val EXTRA_LIVE_RETURN_CHANNEL_LOGO = "LIVE_RETURN_CHANNEL_LOGO"
        const val EXTRA_LIVE_RETURN_EPG_CHANNEL_ID = "LIVE_RETURN_EPG_CHANNEL_ID"
        const val EXTRA_LIVE_RETURN_STREAM_URL = "LIVE_RETURN_STREAM_URL"
        const val EXTRA_LIVE_RETURN_CATEGORY_ID = "LIVE_RETURN_CATEGORY_ID"
        private const val TIMEOUT_MS = 25000L
        private const val MEDIAFUSION_TIMEOUT_MS = 35000L
        private const val OVERLAY_TIMEOUT = 6000L
        private const val MIN_PROGRESS_TO_TRACK_MS = 15_000L
        private const val MIN_DURATION_TO_TRACK_MS = 60_000L
        private const val RETURN_TO_SOURCES_THRESHOLD_MS = 60_000L
        private const val COMPLETION_THRESHOLD_RATIO = 0.95f
        private const val STALL_THRESHOLD_MS = 12000L
        private const val LOW_RAM_STALL_THRESHOLD_MS = 15000L
        private const val LOW_RAM_STALL_STRIKES = 2
        private const val NETWORK_RECOVERY_BUFFER_MS = 5000L
        private const val LOW_RAM_MAX_BUFFER_MS = 30000
        private const val LOW_RAM_TARGET_BUFFER_BYTES = 12 * 1024 * 1024
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
            seriesId: String? = null
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
        historyManager = PlayerHistoryManager(this, watchHistoryPrefs, viewModel)
        lifecycle.addObserver(historyManager)

        playerView = findViewById(R.id.player_view)
        layoutDebridResolving = findViewById(R.id.layout_debrid_resolving)
        tvResolvingStatus = findViewById(R.id.tv_resolving_status)
        layoutDebugOverlay = findViewById(R.id.layout_debug_overlay)
        tvDebugInfo = findViewById(R.id.tv_debug_info)

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
        streamHeaders = readStreamHeaders(intent)
        subtitleEntries = intent.getStringArrayListExtra(EXTRA_SUBTITLE_ENTRIES) ?: emptyList()

        val isDebrid = playbackSource == PlaybackSource.DEBRID
        val canResolveDebrid = canUseDebridResolver()
        val hasResInfo = !debridInfoHashExtra.isNullOrBlank() || !debridMagnetExtra.isNullOrBlank()
        val isDebridHistoryResume = isDebrid && startPositionMs > 0L
        val isDebridUrlMissing = streamUrl.isNullOrBlank()
        val canFreshResolveDirectDebrid = isDebrid && directDebridPlayback && (
            tmdbIdExtra?.isNotBlank() == true ||
                imdbIdExtra?.isNotBlank() == true ||
                contentId?.isNotBlank() == true ||
                originalTitle?.isNotBlank() == true
            )
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
            bindChannelMeta(channelName)
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
                    viewModel.loadSeriesPlaylist(seriesId, seasonNum, episodeId, seriesTitleExtra)
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
                    if (isControllerVisible) {
                        com.tvonnet.debridxtreamiptv.utils.FocusCoordinator.requestFocus("PLAYER_CONTROLLER") {
                            val targetBtn = playerView.findViewById<View>(R.id.exo_progress)
                                ?: playerView.findViewById<View>(R.id.exo_play)
                                ?: playerView.findViewById<View>(R.id.exo_pause)
                            
                            targetBtn?.post { 
                                targetBtn.post { 
                                    try {
                                        targetBtn.requestFocus()
                                    } finally {
                                        com.tvonnet.debridxtreamiptv.utils.FocusCoordinator.release("PLAYER_CONTROLLER")
                                    }
                                }
                            } ?: com.tvonnet.debridxtreamiptv.utils.FocusCoordinator.release("PLAYER_CONTROLLER")
                        }
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
                updatePlayPauseVisibility(true)
            }
            playerView.findViewById<View>(R.id.exo_pause)?.setOnClickListener {
                player?.pause()
                updatePlayPauseVisibility(false)
            }
            playerView.findViewById<View>(R.id.exo_rew)?.setOnClickListener {
                player?.seekBack()
            }
            playerView.findViewById<View>(R.id.exo_ffwd)?.setOnClickListener {
                player?.seekForward()
            }
            
            player?.let { updatePlayPauseVisibility(it.isPlaying) }
            bindModernMetadata(streamTitle)
            setupInteractiveAnimations()

            val btnNext = playerView.findViewById<View>(R.id.btn_next_episode)
            if (contentType == ContentType.SERIES || contentType == ContentType.EPISODE) {
                btnNext?.isVisible = true
                btnNext?.setOnClickListener {
                     playNextEpisode()
                }
            } else {
                btnNext?.isVisible = false
            }

            playerView.findViewById<View>(R.id.btn_aspect_ratio)?.setOnClickListener {
                cycleResizeMode()
            }

            nextEpisodeManager = PlayerNextEpisodeManager(this, playerView, object : PlayerNextEpisodeManager.Delegate {
                override fun isPlaybackEligibleForPrompt(): Boolean {
                    return contentType == ContentType.SERIES || contentType == ContentType.EPISODE
                }
                override fun getPlayerDuration(): Long = player?.duration ?: 0L
                override fun getPlayerCurrentPosition(): Long = player?.currentPosition ?: 0L
                override fun isPlayerPlaying(): Boolean = player?.isPlaying ?: false
                override fun getNextEpisode(): com.tvonnet.debridxtreamiptv.features.seriesv2.data.model.EpisodeEntityV2? {
                    val state = viewModel.seriesPlaylistState.value
                    return if (state?.hasNext == true) {
                        state.originalList.getOrNull(state.currentIndex + 1)
                    } else null
                }
                override fun onPlayNextEpisodeRequested() {
                    playNextEpisode()
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

            val xrayToggle = playerView.findViewById<View>(R.id.layout_xray_toggle)
            val xraySwitch = playerView.findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.switch_xray)
            xrayToggle?.setOnClickListener {
                val isChecked = !(xraySwitch?.isChecked ?: false)
                xraySwitch?.isChecked = isChecked
                toggleXRayPanel(isChecked)
            }

            if (contentType == ContentType.MOVIE || contentType == ContentType.EPISODE || contentType == ContentType.SERIES) {
                viewModel.loadXRayMetadata(
                    contentId = contentId,
                    tmdbId = tmdbIdExtra ?: imdbIdExtra,
                    isMovie = contentType == ContentType.MOVIE,
                    title = originalTitle
                )
            }

            observeXRayMetadata()
        }

        if (canFreshResolveDirectDebrid && isExpired) {
            Log.i("PlayerActivity", "Direct Debrid resume detected: refreshing source from metadata before playback.")
            isResolvingDebrid = true
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
        } else if (canResolveDebrid && hasResInfo && (isDebridUrlMissing || isExpired)) {
            Log.i("PlayerActivity", "Debrid resume detected: URL is ${if (isDebridUrlMissing) "missing" else "expired"}. Triggering resolution.")
            isResolvingDebrid = true
            viewModel.reResolveDebridUrl(debridInfoHashExtra, debridMagnetExtra, seasonNumberExtra, episodeNumberExtra, episodeTitleExtra)
        } else if (isDebrid && directDebridPlayback && isExpired) {
            Log.w("PlayerActivity", "Direct Debrid resume is expired and cannot be refreshed; blocking stale passthrough playback.")
            handleTerminalPlaybackFailure("Expired direct source could not be refreshed")
        } else {
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
            showEpgOverlay(EpgOverlayMode.COMPACT, pinned = false)
        } else {
            playerView.showController()
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (viewModel.browserState.value.isVisible) {
                    viewModel.toggleBrowser(false)
                    return
                }
                if (contentType == ContentType.LIVE_TV && (epgOverlayPinned || epgOverlayMode != EpgOverlayMode.HIDDEN)) {
                    hideEpgOverlay()
                    return
                }
                if (contentType == ContentType.MOVIE || contentType == ContentType.EPISODE) {
                    if (playerView.isControllerFullyVisible) {
                        playerView.hideController()
                        return
                    }
                }
                manualExit = true
                updateLastPlaybackPosition()
                historyManager.recordPlaybackHistoryIfNeeded()
                releasePlayer()
                finish()
            }
        })
    }

    private fun zapChannel(direction: Int) {
        val target = viewModel.moveZap(direction) ?: run {
            val isReady = viewModel.zapState.value?.channels?.isNotEmpty() == true
            showToast(
                if (isReady) getString(R.string.player_zap_unavailable)
                else getString(R.string.player_zap_loading)
            )
            return
        }

        currentZapRequestId++
        val reqId = currentZapRequestId

        contentId = target.streamId
        currentUrl = target.streamUrl
        channelLogoUrl = target.logoUrl
        bindChannelMeta(target.name)
        supportActionBar?.title = target.name

        val epgKey = target.epgChannelId?.takeIf { it.isNotBlank() } ?: target.streamId
        currentEpgChannelId = epgKey
        viewModel.observeEpg(epgKey, target.streamId)

        val keepPinned = epgOverlayPinned && epgOverlayMode != EpgOverlayMode.HIDDEN
        showEpgOverlay(mode = EpgOverlayMode.COMPACT, pinned = keepPinned)

        performSeamlessSwitch(target.streamUrl, reqId)
    }

    private fun playUrl(url: String) {
        if (isFinishing || isDestroyed) return
        if (isSwitching) return
        isSwitching = true
        try {
            didPlaybackComplete = false
            timeoutHandler.removeCallbacks(timeoutRunnable)
            timeoutMs = resolveTimeoutMs(url)
            timeoutHandler.postDelayed(timeoutRunnable, timeoutMs)

            startStallMonitor()

            val p = player
            if (p != null) {
                p.stop()
                p.clearMediaItems()
                val mediaItem = buildMediaItem(url)
                p.setMediaItem(mediaItem)
                p.prepare()
                if (startPositionMs > 0L) {
                    p.seekTo(startPositionMs)
                }
                p.playWhenReady = true
            } else {
                initializePlayer(url)
            }
            // Redundant binding removed to prevent Surface race conditions
        } catch (e: Exception) {
            isSwitching = false
            timeoutHandler.removeCallbacks(timeoutRunnable)
            handleInitializationError(e)
        }
    }

    private fun updateDebugOverlay() {
        if (!debugEnabled) return
        val p = player ?: return
        try {
            val stateStr = when (p.playbackState) {
                Player.STATE_IDLE -> "IDLE"
                Player.STATE_BUFFERING -> "BUFFER"
                Player.STATE_READY -> "READY"
                Player.STATE_ENDED -> "ENDED"
                else -> "UNKNOWN"
            }
            val posStr = String.format("%02d:%02d", java.util.concurrent.TimeUnit.MILLISECONDS.toMinutes(p.currentPosition), java.util.concurrent.TimeUnit.MILLISECONDS.toSeconds(p.currentPosition) - java.util.concurrent.TimeUnit.MINUTES.toSeconds(java.util.concurrent.TimeUnit.MILLISECONDS.toMinutes(p.currentPosition)))
            val bufStr = String.format("%02d:%02d", java.util.concurrent.TimeUnit.MILLISECONDS.toMinutes(p.bufferedPosition), java.util.concurrent.TimeUnit.MILLISECONDS.toSeconds(p.bufferedPosition) - java.util.concurrent.TimeUnit.MINUTES.toSeconds(java.util.concurrent.TimeUnit.MILLISECONDS.toMinutes(p.bufferedPosition)))
            val urlRaw = currentUrl ?: "Null"
            val shortUrl = if (urlRaw.length > 55) urlRaw.substring(0, 52) + "..." else urlRaw

            val debugStr = buildString {
                append("State: $stateStr | Playing: ${p.playWhenReady}\n")
                append("Pos: $posStr | Buf: $bufStr\n")
                append("Switches: $switchCount | Src: $playbackSource\n")
                append("URL: $shortUrl")
            }
            tvDebugInfo?.text = debugStr
        } catch (e: Exception) { }
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
        Log.i("PlayerActivity", "Performing seamless switch to: ${SensitiveLogRedactor.describeUrl(newUrl)}")
        
        timeoutHandler.removeCallbacks(timeoutRunnable)
        timeoutMs = resolveTimeoutMs(newUrl)
        timeoutHandler.postDelayed(timeoutRunnable, timeoutMs)
        
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
        return if (subtitleConfigs.isEmpty()) {
            MediaItem.fromUri(url)
        } else {
            MediaItem.Builder()
                .setUri(url)
                .setSubtitleConfigurations(subtitleConfigs)
                .build()
        }
    }

    private fun showAudioSelection() {
        if (isInPictureInPictureMode) return
        val playerSnapshot = player ?: return
        trackManager.showAudioSelection(playerSnapshot) { dialog ->
            showManagedTrackDialog(dialog)
        }
    }

    private fun showSubtitleSelection() {
        if (isInPictureInPictureMode) return
        val playerSnapshot = player ?: return
        trackManager.showSubtitleSelection(playerSnapshot, subtitleEntries) { dialog ->
            showManagedTrackDialog(dialog)
        }
    }

    private fun showLanguageSelection() {
        if (isInPictureInPictureMode) return
        lifecycleScope.launch {
            val options = if (playbackSource == PlaybackSource.DEBRID && contentType == ContentType.MOVIE) {
                runCatching {
                    viewModel.getDebridLanguageOptions(
                        streamId = tmdbIdExtra ?: contentId,
                        title = originalTitle,
                        imdbId = imdbIdExtra,
                        sourceProfile = currentDebridSourceProfile()
                    )
                }.getOrElse { emptyList() }
            } else {
                debridLanguagesExtra.orEmpty()
            }

            val distinctOptions = options
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .distinct()

            if (distinctOptions.isEmpty()) {
                Toast.makeText(this@PlayerActivity, "No language options available", Toast.LENGTH_SHORT).show()
                return@launch
            }

            val dialog = androidx.appcompat.app.AlertDialog.Builder(this@PlayerActivity)
                .setTitle(R.string.player_language_title)
                .setItems(distinctOptions.toTypedArray()) { dialog, which ->
                    val selected = distinctOptions[which]
                    applyDebridLanguagePreference(selected)
                    dialog.dismiss()
                }
                .create()
            showManagedTrackDialog(dialog)
        }
    }

    private fun applyDebridLanguagePreference(language: String) {
        trackManager.applyDebridLanguagePreference(language)

        if (playbackSource == PlaybackSource.DEBRID && contentType == ContentType.MOVIE) {
            isResolvingDebrid = true
            val profile = currentDebridSourceProfile()?.copy(languages = listOf(language))
            debridLanguagesExtra = profile?.languages
            viewModel.refreshDebridMovieSource(
                streamId = tmdbIdExtra ?: contentId,
                title = originalTitle,
                imdbId = imdbIdExtra,
                sourceProfile = profile
            )
        } else {
            Toast.makeText(this, "Language preference saved", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showVideoSelection() {
        if (isInPictureInPictureMode) return
        val playerSnapshot = player ?: return
        val hasVideoTracks = playerSnapshot.currentTracks.groups.any { group ->
            group.type == C.TRACK_TYPE_VIDEO && group.isSupported
        }
        if (!hasVideoTracks) {
            Toast.makeText(this, "No video tracks available", Toast.LENGTH_SHORT).show()
            return
        }

        val dialog = TrackSelectionDialogBuilder(
            this,
            getString(R.string.player_video_title),
            playerSnapshot,
            C.TRACK_TYPE_VIDEO
        )
            .setAllowAdaptiveSelections(true)
            .setAllowMultipleOverrides(false)
            .setTrackNameProvider(DefaultTrackNameProvider(resources))
            .build()
        showManagedTrackDialog(dialog)
    }

    private fun showManagedTrackDialog(dialog: Dialog) {
        dismissActiveTrackDialog()
        activeTrackDialog = dialog
        dialog.setOnDismissListener {
            if (activeTrackDialog === dialog) {
                activeTrackDialog = null
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
        
        layoutSupportQr = findViewById(R.id.layout_support_qr)
        imgSupportQr = findViewById(R.id.img_support_qr)

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
                }
            }
        }
    }

    private lateinit var nextEpisodeManager: PlayerNextEpisodeManager



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
                        val seasonTitle = seriesTitleExtra ?: "Season ${seasonNumberExtra ?: ""}"
                        if (state.isLoading) {
                            episodeBrowserController.setLoading(true)
                        } else if (state.originalList.isEmpty()) {
                            episodeBrowserController.showMessage(
                                state.error ?: "No episodes available",
                                seasonTitle
                            )
                        } else {
                            episodeBrowserController.setLoading(false)
                            Log.e("IPTV_EP_LOAD_FIX", "SEND_TO_BROWSER count=${state.originalList.size}")
                            episodeBrowserController.show(
                                episodes = state.originalList,
                                currentEpisodeId = contentId,
                                seasonTitle = seasonTitle,
                                fallbackImageUrl = getEpisodeBrowserFallbackImageUrl()
                            )
                        }
                    }

                    val currentEpId = contentId
                    if (!state.isLoading && state.currentEpisode != null && currentEpId != state.currentEpisode.episodeId) {
                         if (playbackSource != PlaybackSource.DEBRID) {
                             state.currentEpisode?.let { playSeriesEpisode(it) }
                         }
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
        
        val seasonTitle = seriesTitleExtra ?: "Season ${seasonNumberExtra ?: ""}"
        
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
                         viewModel.loadSeriesPlaylist(seriesId, seasonNum, episodeId, seriesTitleExtra)
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
        if (state.isLoading) {
            episodeBrowserController.show(emptyList(), contentId, seasonTitle, getEpisodeBrowserFallbackImageUrl())
            episodeBrowserController.setLoading(true)
        } else if (state.originalList.isEmpty()) {
            episodeBrowserController.showMessage(state.error ?: "No episodes available", seasonTitle)
        } else {
            Log.e("IPTV_EP_LOAD_FIX", "SEND_TO_BROWSER count=${state.originalList.size}")
            episodeBrowserController.show(
                episodes = state.originalList,
                currentEpisodeId = contentId,
                seasonTitle = seriesTitleExtra ?: "Season ${seasonNumberExtra ?: ""}",
                fallbackImageUrl = getEpisodeBrowserFallbackImageUrl()
            )
        }
    }

    private fun currentIptvEpisodeIdForPlaylist(): String? {
        val id = contentId?.takeIf { it.isNotBlank() } ?: return null
        val currentStreamUrl = currentUrl?.takeIf { it.isNotBlank() }
        return id.takeUnless { it == currentStreamUrl }
    }

    private fun getEpisodeBrowserFallbackImageUrl(): String? {
        return posterUrlExtra
            ?.takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }
            ?: backdropUrlExtra?.takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }
    }

    private fun onEpisodeSelected(episode: EpisodeEntityV2) {
        episodeBrowserController.hide()
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

    private fun debridSeriesLookupId(): String {
        return tmdbIdExtra
            ?: intent.getStringExtra(EXTRA_SERIES_ID)
            ?: imdbIdExtra
            ?: contentId?.substringBefore(':')
            ?: ""
    }

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
        val title = episode.title ?: "Episode ${episode.episodeNumber}"
        originalTitle = title
        bindModernMetadata(title)
        supportActionBar?.title = title
        
        if (::nextEpisodeManager.isInitialized) { nextEpisodeManager.hidePrompt() }
        if (::nextEpisodeManager.isInitialized) { nextEpisodeManager.resetState() }
        
        performSeamlessSwitch(url)
    }

    private fun bindModernMetadata(title: String?) {
        val titleView = playerView.findViewById<TextView>(R.id.tv_player_title)
        val subtitleView = playerView.findViewById<TextView>(R.id.tv_player_subtitle)
        
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
    }

    private fun cleanTitle(raw: String): String {
        var clean = raw.replace(".", " ").replace("_", " ")
        val patterns = listOf(
            "\\d{3,4}p", "2160p", "1080p", "720p", "480p",
            "WEB-DL", "WEBRip", "BluRay", "HDRip", "DV", "HDR",
            "H264", "H265", "x264", "x265", "HEVC",
            "DDP5.1", "DTS", "AAC", "AC3",
            "Hybrid", "MULTI", "BTM"
        )
        patterns.forEach { pattern ->
            val regex = Regex("(?i)\\b$pattern\\b")
            val match = regex.find(clean)
            if (match != null) {
                clean = clean.substring(0, match.range.first).trim()
            }
        }
        val yearRegex = Regex("(\\d{4})")
        val yearMatch = yearRegex.find(raw)
        if (yearMatch != null) {
            val year = yearMatch.groupValues[1]
            if (clean.contains(year)) {
                clean = clean.replace(year, "($year)").replace("  ", " ").trim()
            }
        }
        return clean.trim()
    }

    private fun playNextEpisode() {
        if (::nextEpisodeManager.isInitialized) { nextEpisodeManager.hidePrompt() }

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



    private fun renderOverlay(state: PlayerOverlayUiState) {
        lastOverlayState = state
        if (epgOverlay == null) return
        if (contentType != ContentType.LIVE_TV) return

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
        val nameView = tvChannelName ?: return
        nameView.text = channelName ?: getString(R.string.player_epg_channel_unknown)
        imgChannelLogo?.let { logoView ->
            GlideUtils.loadChannelLogo(logoView, channelLogoUrl)
        }
    }



    private fun readStreamHeaders(intent: Intent): Map<String, String>? {
        @Suppress("UNCHECKED_CAST")
        val raw = intent.getSerializableExtra(EXTRA_STREAM_HEADERS) as? HashMap<String, String>
        return raw?.takeIf { it.isNotEmpty() }
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
                releasePlayer()
            }

            didPlaybackComplete = false
            hasAppliedIndexOverride = false
            timeoutHandler.removeCallbacks(timeoutRunnable)
            timeoutMs = resolveTimeoutMs(streamUrl)
            timeoutHandler.postDelayed(timeoutRunnable, timeoutMs)

            val httpDataSourceFactory = OkHttpDataSource.Factory(okHttpClient)
            val requestHeaders = streamHeaders
            if (!requestHeaders.isNullOrEmpty()) {
                httpDataSourceFactory.setDefaultRequestProperties(requestHeaders)
            }
            if (requestHeaders?.keys?.any { it.equals("User-Agent", ignoreCase = true) } != true) {
                httpDataSourceFactory.setUserAgent("DebridXtream/1.0 (Linux; Android 10; TV)")
            }

            val dataSourceFactory = CacheDataSource.Factory()
                .setCache(PlayerCacheManager.getCache(this))
                .setUpstreamDataSourceFactory(httpDataSourceFactory)
                .setCacheWriteDataSinkFactory(CacheDataSink.Factory().setCache(PlayerCacheManager.getCache(this)))
                .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

            val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory)
            
            val isDebrid = playbackSource == PlaybackSource.DEBRID
            val bufferConfig = PlayerBufferConfigFactory.buildConfig(this, settingsPreferences, contentType, streamUrl, isDebrid)
            val loadControl = DefaultLoadControl.Builder()
                .setBufferDurationsMs(bufferConfig.minBufferMs, bufferConfig.maxBufferMs, bufferConfig.startPlaybackMs, bufferConfig.rebufferPlaybackMs)
                .setTargetBufferBytes(PlayerBufferConfigFactory.resolveTargetBufferBytes(this))
                .setPrioritizeTimeOverSizeThresholds(true)
                .build()
            
            val trackSelector = DefaultTrackSelector(this)
            var parametersBuilder = trackSelector.buildUponParameters().setTunnelingEnabled(false)
            trackManager.preferredAudioLanguage?.let { parametersBuilder = parametersBuilder.setPreferredAudioLanguage(it) }
            trackManager.preferredSubtitleLanguage?.let { parametersBuilder = parametersBuilder.setPreferredTextLanguage(it) }
            trackSelector.parameters = parametersBuilder.build()

            val isSoftwareAudioEnabled = com.tvonnet.debridxtreamiptv.data.prefs.SettingsPreferences(this).isSoftwareAudioEnabled()
            val extensionMode = if (isSoftwareAudioEnabled) DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER else DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON
            val renderersFactory = DefaultRenderersFactory(this).setExtensionRendererMode(extensionMode)

            player = ExoPlayer.Builder(this)
                .setRenderersFactory(renderersFactory)
                .setMediaSourceFactory(mediaSourceFactory)
                .setLoadControl(loadControl)
                .setTrackSelector(trackSelector)
                .setSeekForwardIncrementMs(15000)
                .setSeekBackIncrementMs(15000)
                .build()
                .also { playerView.player = it }

            val mediaItem = buildMediaItem(streamUrl)
            player?.setMediaItem(mediaItem, /* resetPosition = */ true)
            player?.prepare()
            if (startPositionMs > 0L) {
                player?.seekTo(startPositionMs)
            }
            player?.playWhenReady = true
            // player?.play() removed as playWhenReady=true is sufficient
            startStallMonitor()

            player?.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_READY) {
                         isSwitching = false
                         if (player?.currentPosition ?: 0L > 1000) startPositionMs = 0L 
                        timeoutHandler.removeCallbacks(timeoutRunnable)
                        retryCount = 0
                        lastBufferingStartMs = 0L
                    } else if (playbackState == Player.STATE_BUFFERING) {
                        timeoutHandler.removeCallbacks(timeoutRunnable)
                        timeoutHandler.postDelayed(timeoutRunnable, timeoutMs)
                        if (lastBufferingStartMs == 0L) lastBufferingStartMs = SystemClock.elapsedRealtime()
                    } else if (playbackState == Player.STATE_ENDED || playbackState == Player.STATE_IDLE) {
                        timeoutHandler.removeCallbacks(timeoutRunnable)
                        lastBufferingStartMs = 0L
                        if (playbackState == Player.STATE_ENDED) {
                             if (contentType == ContentType.SERIES || contentType == ContentType.EPISODE) {
                                  val state = viewModel.seriesPlaylistState.value
                                  if (canUseDebridResolver() || state?.hasNext == true) {
                                       playNextEpisode()
                                       return
                                  }
                             }
                             didPlaybackComplete = true
                             if (!(::nextEpisodeManager.isInitialized && nextEpisodeManager.isPromptVisible)) finish()
                        }
                    }
                }
                override fun onTrackSelectionParametersChanged(parameters: androidx.media3.common.TrackSelectionParameters) = captureManualTrackSelection()
                override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                    if (!hasAppliedIndexOverride) {
                        applyTrackIndexOverrides()
                        hasAppliedIndexOverride = true
                    }
                    captureManualTrackSelection()
                    if (isSoftwareAudioEnabled) {
                        val audioGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
                        var hasSupportedAudioSelected = false
                        var firstSupportedGroup: androidx.media3.common.Tracks.Group? = null
                        for (group in audioGroups) {
                            if (group.isSupported) {
                                if (firstSupportedGroup == null) firstSupportedGroup = group
                                if (group.isSelected) { hasSupportedAudioSelected = true; break }
                            }
                        }
                        if (!hasSupportedAudioSelected && firstSupportedGroup != null && audioGroups.size > 1) {
                             player?.trackSelectionParameters = player?.trackSelectionParameters?.buildUpon()?.setOverrideForType(TrackSelectionOverride(firstSupportedGroup.mediaTrackGroup, 0))?.build() ?: player?.trackSelectionParameters!!
                        }
                    }
                }
                override fun onPlayerError(error: PlaybackException) {
                    isSwitching = false
                    timeoutHandler.removeCallbacks(timeoutRunnable)
                    handlePlaybackError(error)
                }
            })
            
            debugListener?.let { player?.removeListener(it) }
            debugListener = object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    updateDebugOverlay()
                }
                override fun onPlayerError(error: PlaybackException) {
                    if (debugEnabled) tvDebugInfo?.text = tvDebugInfo?.text?.toString() + "\nERR: ${error.message}"
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
        val cause = error.cause
        val errorMessage = when (cause) {
            is HttpDataSource.InvalidResponseCodeException -> "HTTP ${cause.responseCode}"
            else -> error.message ?: "Playback error"
        }
        if (retryCount < maxRetries) {
            retryCount++
            val hasDurableDebridIdentity = !debridInfoHashExtra.isNullOrBlank() || !debridMagnetExtra.isNullOrBlank()
            if (canUseDebridResolver() && !isResolvingDebrid && hasDurableDebridIdentity) {
                  isResolvingDebrid = true
                  if (player != null && player!!.currentPosition > 1000L) startPositionMs = player!!.currentPosition
                  val allowDirectHttpPassthroughForRetry = !hasDurableDebridIdentity && directDebridPlayback
                  viewModel.reResolveDebridUrl(
                      debridInfoHashExtra,
                      debridMagnetExtra,
                      seasonNumberExtra,
                      episodeNumberExtra,
                      episodeTitleExtra,
                      allowDirectHttpPassthrough = allowDirectHttpPassthroughForRetry
                  )
                  return
            }
            showToast("Retrying... ($retryCount/$maxRetries)")
            if (contentType != ContentType.LIVE_TV && player != null && player!!.currentPosition > 1000L) startPositionMs = player!!.currentPosition
            player?.release(); player = null
            Handler(Looper.getMainLooper()).postDelayed({ currentUrl?.let { initializePlayer(it) } }, retryCount * 1000L)
        } else {
            handleTerminalPlaybackFailure(errorMessage)
        }
    }
    
    private fun checkForStall() {
        val p = player ?: return
        val now = SystemClock.elapsedRealtime()
        if (p.playWhenReady && p.playbackState == Player.STATE_READY) {
            val isLowRamDevice = DeviceProfile.isLowRamDevice(this)
            val stallThresholdMs = if (isLowRamDevice) LOW_RAM_STALL_THRESHOLD_MS else STALL_THRESHOLD_MS
            val requiredStrikes = if (isLowRamDevice) LOW_RAM_STALL_STRIKES else 1
            val currentPos = p.currentPosition
            if (currentPos > lastBoundPosition) { lastBoundPosition = currentPos; lastProgressCheckMs = now; stallStrikeCount = 0; return }
            if (currentPos > 0 && now - lastProgressCheckMs >= stallThresholdMs) {
                stallStrikeCount++
                if (stallStrikeCount >= requiredStrikes) { stallStrikeCount = 0; handlePlaybackError(PlaybackException(null, null, PlaybackException.ERROR_CODE_REMOTE_ERROR)) }
                lastProgressCheckMs = now
            }
        } else { lastBoundPosition = p.currentPosition; lastProgressCheckMs = now; stallStrikeCount = 0 }
    }



    private fun showSupportQr() {
        releasePlayer(); layoutSupportQr?.isVisible = true
        val supportUrl = settingsPreferences.getSupportUrl()
        try {
            val barcodeEncoder = com.journeyapps.barcodescanner.BarcodeEncoder()
            val bitmap = barcodeEncoder.encodeBitmap(supportUrl, com.google.zxing.BarcodeFormat.QR_CODE, 400, 400)
            imgSupportQr?.setImageBitmap(bitmap)
            showToast("Playback Failed. Scan to contact support.")
        } catch (e: Exception) { }
    }

    private fun handleInitializationError(error: Exception) {
        handleTerminalPlaybackFailure("Init failed: ${error.message}")
    }

    private fun handleTimeout() {
        if (player?.playbackState == Player.STATE_BUFFERING) {
            if (retryCount < maxRetries) {
                retryCount++
                if (canUseDebridResolver() && !isResolvingDebrid && retryCount > 1 && (!debridInfoHashExtra.isNullOrBlank() || !debridMagnetExtra.isNullOrBlank())) {
                      isResolvingDebrid = true
                      if (player != null && player!!.currentPosition > 1000L) startPositionMs = player!!.currentPosition
                      viewModel.reResolveDebridUrl(debridInfoHashExtra, debridMagnetExtra, seasonNumberExtra, episodeNumberExtra, episodeTitleExtra)
                      return
                }
                showToast("Connection timeout, retrying...")
                if (contentType != ContentType.LIVE_TV && player != null && player!!.currentPosition > 1000L) startPositionMs = player!!.currentPosition
                player?.release(); player = null
                Handler(Looper.getMainLooper()).postDelayed({ currentUrl?.let { initializePlayer(it) } }, 2000)
            } else {
                handleTerminalPlaybackFailure("Connection timeout")
            }
        }
    }

    private fun showError(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    private fun showToast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

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

    override fun onResume() { super.onResume(); startDebugOverlay(); if (player == null) currentUrl?.let { initializePlayer(it) } else startStallMonitor() }
    override fun onStart() { super.onStart(); registerNetworkCallback(); if (::nextEpisodeManager.isInitialized) { nextEpisodeManager.onStart() } }
    override fun onPause() { super.onPause(); player?.pause(); timeoutHandler.removeCallbacks(timeoutRunnable); stopStallMonitor() }
    override fun onStop() { super.onStop(); dismissActiveTrackDialog(); debugOverlayHandler.removeCallbacks(debugOverlayRunnable); timeoutHandler.removeCallbacks(timeoutRunnable); stallHandler.removeCallbacks(stallRunnable); unregisterNetworkCallback(); historyManager.recordPlaybackHistoryIfNeeded(); releasePlayer() }

    private fun releasePlayer() {
        updateLastPlaybackPosition(); timeoutHandler.removeCallbacks(timeoutRunnable); overlayHandler.removeCallbacks(overlayHideRunnable); if (::nextEpisodeManager.isInitialized) { nextEpisodeManager.onStop() }; stopStallMonitor()
        player?.stop(); player?.release(); player = null; playerView.player = null
    }

    private fun startStallMonitor() {
        lastBoundPosition = player?.currentPosition ?: 0L
        lastProgressCheckMs = SystemClock.elapsedRealtime(); stallStrikeCount = 0
        stallHandler.removeCallbacks(stallRunnable); stallHandler.postDelayed(stallRunnable, 5000)
    }

    private fun stopStallMonitor() = stallHandler.removeCallbacks(stallRunnable)

    override fun onDestroy() { super.onDestroy(); dismissActiveTrackDialog(); historyManager.recordPlaybackHistoryIfNeeded(); releasePlayer() }

    private fun resolveTimeoutMs(url: String): Long = if (url.lowercase().contains("mediafusion.elfhosted.com")) MEDIAFUSION_TIMEOUT_MS else TIMEOUT_MS

    override fun finish() {
        setLiveExitResultIfNeeded()
        setExitResultIfNeeded()
        super.finish()
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
        // LOG EVERY KEY FOR PROOF
        Log.d("PlayerActivity", "dispatchKeyEvent: code=${event.keyCode}, action=${event.action}")
        
        if (event.action == KeyEvent.ACTION_DOWN) {
            val xrayPanel = findViewById<View>(R.id.view_xray_panel)
            if (xrayPanel != null && xrayPanel.visibility == View.VISIBLE) {
                if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                    playerView.findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.switch_xray)?.isChecked = false
                    toggleXRayPanel(false)
                    return true
                }
            }

            if (::episodeBrowserController.isInitialized && episodeBrowserController.isVisible()) {
                if (event.keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                    episodeBrowserController.hide()
                    showControllerFocusedOnSeekBar()
                    return true
                }
                playerView.hideController()
                val handled = episodeBrowserController.handleKeyEvent(event)
                Log.d("EP_BROWSER_FOCUS_FIX", "browser visible key=${event.keyCode} handled=$handled")
                if (handled) return true
            }

            // Priority 1: Handle DPAD_DOWN for Episode Browser
            if (event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                val isSeries = isSeriesEpisodePlayback()
                val isFocusOnBottomControls = currentFocus?.let { focus ->
                    val id = focus.id
                    id == R.id.exo_play || id == R.id.exo_rew || id == R.id.exo_ffwd || 
                    id == R.id.btn_player_volume || id == R.id.volume_progress || id == R.id.btn_next_episode
                } ?: false

                Log.d("PlayerActivity", "DPAD_DOWN detected: isSeries=$isSeries, browserVisible=${viewModel.browserState.value.isVisible}")
                if (isSeries && !viewModel.browserState.value.isVisible && (!playerView.isControllerFullyVisible || isFocusOnBottomControls)) {
                    playerView.hideController()
                    if (!episodeBrowserController.isVisible()) {
                        showEpisodeBrowser()
                    } else {
                        episodeBrowserController.requestFocus()
                    }
                    return true
                }
            }

            // Priority 2: Standard Controller triggers
            if (contentType != ContentType.LIVE_TV && playerView.useController && !(::nextEpisodeManager.isInitialized && nextEpisodeManager.isPromptVisible) && !playerView.isControllerFullyVisible) {
                // EXCLUDE DPAD_DOWN from showing the standard controller if it's a series (we want episode browser handled above)
                val isSeriesDown = event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN && isSeriesEpisodePlayback()
                if (!isSeriesDown) {
                    when (event.keyCode) {
                        KeyEvent.KEYCODE_DPAD_LEFT,
                        KeyEvent.KEYCODE_DPAD_RIGHT,
                        KeyEvent.KEYCODE_DPAD_UP,
                        KeyEvent.KEYCODE_DPAD_DOWN,
                        KeyEvent.KEYCODE_DPAD_CENTER,
                        KeyEvent.KEYCODE_ENTER,
                        KeyEvent.KEYCODE_NUMPAD_ENTER,
                        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                        KeyEvent.KEYCODE_MEDIA_PLAY,
                        KeyEvent.KEYCODE_MEDIA_PAUSE,
                        KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
                        KeyEvent.KEYCODE_MEDIA_REWIND,
                        KeyEvent.KEYCODE_MENU -> {
                            showControllerFocusedOnSeekBar(event)
                            return true
                        }
                    }
                }
            }

            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> { if (contentType == ContentType.LIVE_TV && !viewModel.browserState.value.isVisible) { viewModel.toggleBrowser(true, viewModel.zapState.value?.categoryId ?: liveCategoryId, contentId); return true } }
                KeyEvent.KEYCODE_CAPTIONS -> { if (contentType != ContentType.LIVE_TV) { showSubtitleSelection(); return true } }
                KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_PAGE_UP -> { if (contentType == ContentType.LIVE_TV && !viewModel.browserState.value.isVisible) { zapChannel(direction = +1); return true } }
                KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.KEYCODE_PAGE_DOWN -> { if (contentType == ContentType.LIVE_TV && !viewModel.browserState.value.isVisible) { zapChannel(direction = -1); return true } }
                KeyEvent.KEYCODE_DPAD_UP -> { if (contentType == ContentType.LIVE_TV && !viewModel.browserState.value.isVisible) { zapChannel(direction = +1); return true } }
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    if (contentType == ContentType.LIVE_TV && !viewModel.browserState.value.isVisible) { zapChannel(direction = -1); return true }
                }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> { if (contentType == ContentType.LIVE_TV) { if (viewModel.browserState.value.isVisible) return super.dispatchKeyEvent(event); if (epgOverlayMode != EpgOverlayMode.HIDDEN && !epgOverlayPinned) hideEpgOverlay() else showEpgOverlay(EpgOverlayMode.COMPACT, pinned = false); return true } }
                KeyEvent.KEYCODE_INFO -> { if (contentType == ContentType.LIVE_TV) toggleEpgOverlayPinned() else { playerView.showController(); playerView.requestFocus() }; return true }
                KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_GUIDE -> {
                    if (contentType == ContentType.LIVE_TV) { toggleEpgOverlayPinned(); return true }; if (playerView.isControllerFullyVisible) { showSubtitleSelection(); return true } 
                }
                KeyEvent.KEYCODE_BACK -> {
                    if (::episodeBrowserController.isInitialized && episodeBrowserController.isVisible()) {
                        episodeBrowserController.hide()
                        playerView.findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.switch_xray)?.isChecked = false
                        toggleXRayPanel(false)
                        return true
                    }
                    val xray = findViewById<View>(R.id.view_xray_panel)
                    if (xray != null && xray.visibility == View.VISIBLE) {
                        playerView.findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.switch_xray)?.isChecked = false
                        toggleXRayPanel(false)
                        return true
                    }
                    if (viewModel.browserState.value.isVisible) { viewModel.toggleBrowser(false); return true }
                    if (contentType == ContentType.LIVE_TV && epgOverlayPinned && epgOverlayMode != EpgOverlayMode.HIDDEN) { hideEpgOverlay(); return true }
                    if (event.repeatCount > 0 && supportsPictureInPicture()) { enterPictureInPictureModeInternal(); return true }
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onKeyLongPress(keyCode: Int, event: KeyEvent): Boolean {
        if (contentType == ContentType.LIVE_TV) { when (keyCode) { KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> { toggleEpgOverlayPinned(); return true } } }
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
            showControllerFocusedOnSeekBar()
        }
    }

    private fun showControllerFocusedOnSeekBar(sourceEvent: KeyEvent? = null) {
        playerView.showController()
        val seekBar = playerView.findViewById<View>(R.id.exo_progress)
        installControllerSeekNavigation()
        Log.d("PLAYER_SEEK_FOCUS", "show controller focusSeek key=${sourceEvent?.keyCode}")
        seekBar?.postDelayed({
            seekBar.requestFocus()
            val keyCode = sourceEvent?.keyCode
            if (sourceEvent?.action == KeyEvent.ACTION_DOWN &&
                (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT)
            ) {
                seekBar.dispatchKeyEvent(sourceEvent)
                seekBar.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
            }
        }, 50L) ?: playerView.requestFocus()
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

    private fun supportsPictureInPicture(): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE) || packageManager.hasSystemFeature("android.software.picture_in_picture") else false

    @RequiresApi(Build.VERSION_CODES.O)
    private fun enterPictureInPictureModeInternal() {
        if (!supportsPictureInPicture() || isInPictureInPictureMode) return
        try {
            player?.let { wasPlayingBeforePiP = it.playWhenReady }
            val params = PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).setSourceRectHint(Rect(0, 0, playerView.width, playerView.height)).build()
            if (enterPictureInPictureMode(params)) hideUiForPiP() else showToast("PiP not available")
        } catch (e: Exception) { showToast("PiP failed") }
    }

    private fun hideUiForPiP() { playerView.hideController(); epgOverlay?.isVisible = false; vodInfoOverlay?.isVisible = false; supportActionBar?.hide(); historyManager.recordPlaybackHistoryIfNeeded() }
    private fun showUiForNormalMode() { if (!isInPictureInPictureMode) { supportActionBar?.show(); if (contentType == ContentType.LIVE_TV) updateOverlayVisibility() else updateVodOverlayVisibility() } }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: android.content.res.Configuration) { super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig); this.isInPictureInPictureMode = isInPictureInPictureMode; if (isInPictureInPictureMode) hideUiForPiP() else showUiForNormalMode() }

    private fun updateOverlayVisibility() { val hasContent = epgOverlay != null; val shouldShow = when (contentType) { ContentType.LIVE_TV -> hasContent && epgOverlayMode != EpgOverlayMode.HIDDEN && !isInPictureInPictureMode; else -> hasContent && isControllerVisible && !isInPictureInPictureMode }; setOverlayVisible(shouldShow) }
    private fun updateVodOverlayVisibility() { setVodOverlayVisible(contentType != ContentType.LIVE_TV && isControllerVisible && !isInPictureInPictureMode) }

    private fun setOverlayVisible(visible: Boolean) { val o = epgOverlay ?: return; if (visible) { if (o.isVisible) return; o.alpha = 0f; o.isVisible = true; o.animate().alpha(1f).setDuration(160).start() } else { if (!o.isVisible) return; o.animate().alpha(0f).setDuration(140).withEndAction { o.isVisible = false }.start() } }
    private fun setVodOverlayVisible(visible: Boolean) { val o = vodInfoOverlay ?: return; if (visible) { if (o.isVisible) return; o.alpha = 0f; o.isVisible = true; o.animate().alpha(1f).setDuration(160).start() } else { if (!o.isVisible) return; o.animate().alpha(0f).setDuration(140).withEndAction { o.isVisible = false }.start() } }

    private fun showEpgOverlay(mode: EpgOverlayMode, pinned: Boolean) { if (contentType != ContentType.LIVE_TV) return; epgOverlayMode = if (mode == EpgOverlayMode.HIDDEN) EpgOverlayMode.HIDDEN else EpgOverlayMode.COMPACT; epgOverlayPinned = pinned; applyEpgOverlayMode(); updateOverlayVisibility(); overlayHandler.removeCallbacks(overlayHideRunnable); if (!pinned) overlayHandler.postDelayed(overlayHideRunnable, OVERLAY_TIMEOUT) }
    private fun hideEpgOverlay() { overlayHandler.removeCallbacks(overlayHideRunnable); epgOverlayPinned = false; epgOverlayMode = EpgOverlayMode.HIDDEN; updateOverlayVisibility() }
    private fun maybeAutoHideOverlay() { if (contentType == ContentType.LIVE_TV && epgOverlayPinned) return; hideEpgOverlay() }
    private fun toggleEpgOverlayPinned() { if (epgOverlayMode != EpgOverlayMode.HIDDEN && epgOverlayPinned) hideEpgOverlay() else showEpgOverlay(EpgOverlayMode.COMPACT, pinned = true) }
    private fun applyEpgOverlayMode() { }

    private fun formatTimeRangeCompact(program: com.tvonnet.debridxtreamiptv.data.local.entity.EpgEntity): String = "${timeFormatter.format(Date(program.start))}-${timeFormatter.format(Date(program.stop))}"






    private fun updateLastPlaybackPosition() { lastPlaybackPositionMs = player?.currentPosition ?: lastPlaybackPositionMs }
    private fun setLiveExitResultIfNeeded() {
        if (contentType != ContentType.LIVE_TV || exitResultHandled) return
        val streamId = contentId?.takeIf { it.isNotBlank() } ?: return
        setResult(
            Activity.RESULT_OK,
            Intent()
                .putExtra(EXTRA_LIVE_RETURN_CHANNEL_ID, streamId)
                .putExtra(EXTRA_LIVE_RETURN_CHANNEL_NAME, tvChannelName?.text?.toString() ?: pendingChannelName)
                .putExtra(EXTRA_LIVE_RETURN_CHANNEL_LOGO, channelLogoUrl)
                .putExtra(EXTRA_LIVE_RETURN_EPG_CHANNEL_ID, currentEpgChannelId)
                .putExtra(EXTRA_LIVE_RETURN_STREAM_URL, currentUrl)
                .putExtra(EXTRA_LIVE_RETURN_CATEGORY_ID, liveCategoryId)
        )
    }

    private fun setExitResultIfNeeded() { if (exitResultHandled || !returnToSourcesOnExit || playbackSource != PlaybackSource.DEBRID || didPlaybackComplete) return; val pos = player?.currentPosition ?: lastPlaybackPositionMs; if (manualExit || pos < RETURN_TO_SOURCES_THRESHOLD_MS) { exitResultHandled = true; setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_RETURN_TO_SOURCES, true)) } }
    private fun finishWithReturnToSources(autoPlayNext: Boolean, reason: String?): Boolean { if (!returnToSourcesOnExit || playbackSource != PlaybackSource.DEBRID || exitResultHandled) return false; exitResultHandled = true; setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_RETURN_TO_SOURCES, true).putExtra(EXTRA_FAILED_STREAM_ID, contentId ?: debridInfoHashExtra ?: currentUrl).putExtra(EXTRA_FAIL_REASON, reason).putExtra(EXTRA_AUTO_PLAY_NEXT, autoPlayNext)); releasePlayer(); finish(); return true }
    private fun handleTerminalPlaybackFailure(reason: String) {
        val redirected = redirectToFailureDetail(reason)
        if (redirected) {
            return
        }
        if (!finishWithReturnToSources(autoPlayNext = false, reason = reason)) {
            showError(
                if (reason.contains("timeout", ignoreCase = true)) {
                    "Connection timeout\n\nStream is too slow or unavailable"
                } else {
                    "Playback failed\n\n$reason"
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
            }
            null, ContentType.LIVE_TV -> null
        } ?: return false

        detailIntent.putExtra(EXTRA_OPENED_FROM_PLAYBACK_FAILURE, true)
        detailIntent.putExtra(EXTRA_FAIL_REASON, reason)
        releasePlayer()
        startActivity(detailIntent)
        finish()
        return true
    }
    private fun updatePlayPauseVisibility(isPlaying: Boolean) { playerView.findViewById<View>(R.id.exo_play)?.isVisible = !isPlaying; playerView.findViewById<View>(R.id.exo_pause)?.isVisible = isPlaying; if (isControllerVisible) { val play = playerView.findViewById<View>(R.id.exo_play); val pause = playerView.findViewById<View>(R.id.exo_pause); if (isPlaying && play?.isFocused == true) pause?.post { pause.requestFocus() } else if (!isPlaying && pause?.isFocused == true) play?.post { play.requestFocus() } } }

    private fun observeDebridResolutionState() {
        lifecycleScope.launch {
            viewModel.debridResolutionState.collect { state ->
                when (state) {
                    is DebridResolutionState.Loading -> { layoutDebridResolving?.isVisible = true; tvResolvingStatus?.text = "Resolving source via Debrid..." }
                    is DebridResolutionState.Success -> {
                        layoutDebridResolving?.isVisible = false; isResolvingDebrid = false
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
                        layoutDebridResolving?.isVisible = false
                        isResolvingDebrid = false
                        handleTerminalPlaybackFailure("Failed: ${state.message}")
                    }
                    is DebridResolutionState.Idle -> { layoutDebridResolving?.isVisible = false; isResolvingDebrid = false }
                }
            }
        }
    }

    private fun captureManualTrackSelection() {
        val p = player ?: return
        val seriesId = intent.getStringExtra(EXTRA_SERIES_ID) ?: tmdbIdExtra ?: imdbIdExtra
        trackManager.captureManualTrackSelection(p, debridInfoHashExtra, seriesId)
    }

    private fun applyTrackIndexOverrides() {
        val p = player ?: return
        val seriesId = intent.getStringExtra(EXTRA_SERIES_ID) ?: tmdbIdExtra ?: imdbIdExtra
        trackManager.applyTrackIndexOverrides(p, debridInfoHashExtra, seriesId)
    }

    private fun setupInteractiveAnimations() {
        listOf(
            R.id.exo_rew,
            R.id.exo_play,
            R.id.exo_pause,
            R.id.exo_ffwd,
            R.id.btn_aspect_ratio,
            R.id.btn_next_episode,
            R.id.btn_player_audio,
            R.id.btn_player_language,
            R.id.btn_player_subtitles,
            R.id.layout_xray_toggle,
            R.id.btn_player_volume,
            R.id.volume_progress
        ).forEach { id ->
            playerView.findViewById<View>(id)?.let { v ->
                v.alpha = 0.7f; v.scaleX = 1f; v.scaleY = 1f
                v.setOnFocusChangeListener { view, f -> if (f) { view.animate().scaleX(1.15f).scaleY(1.15f).alpha(1f).setDuration(200).start() } else view.animate().scaleX(1f).scaleY(1f).alpha(0.7f).setDuration(200).start() }
                v.setOnTouchListener { view, e -> when (e.action) { android.view.MotionEvent.ACTION_DOWN -> view.animate().scaleX(0.95f).scaleY(0.95f).setDuration(100).start(); android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> view.animate().scaleX(if (view.hasFocus()) 1.15f else 1f).scaleY(if (view.hasFocus()) 1.15f else 1f).setDuration(100).start() }; false }
            }
        }
    }

    private fun updateVolumeIcon(progress: Int, maxVolume: Int, btnVolume: android.widget.ImageButton?) {
        if (btnVolume == null) return
        if (progress == 0) {
            btnVolume.setImageResource(R.drawable.ic_player_volume_off)
        } else {
            btnVolume.setImageResource(R.drawable.ic_player_volume)
        }
    }

    private fun toggleXRayPanel(show: Boolean) {
        val xrayPanel = findViewById<View>(R.id.view_xray_panel) ?: return
        val isSeries = isSeriesEpisodePlayback()

        if (show) {
            xrayPanel.bringToFront()
            xrayPanel.visibility = View.VISIBLE
            xrayPanel.translationX = -xrayPanel.width.toFloat()
            xrayPanel.animate()
                .translationX(0f)
                .setDuration(300)
                .start()

            if (isSeries) {
                if (::episodeBrowserController.isInitialized && !episodeBrowserController.isVisible()) {
                    showEpisodeBrowser()
                }
            } else {
                val rvCast = xrayPanel.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.rv_xray_cast)
                rvCast?.post { rvCast.requestFocus() }
            }
        } else {
            xrayPanel.animate()
                .translationX(-xrayPanel.width.toFloat())
                .setDuration(300)
                .withEndAction { xrayPanel.visibility = View.GONE }
                .start()

            if (isSeries) {
                if (::episodeBrowserController.isInitialized && episodeBrowserController.isVisible()) {
                    episodeBrowserController.hide()
                }
            }
        }
    }

    private fun observeXRayMetadata() {
        lifecycleScope.launch {
            lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                viewModel.xrayMetadata.collect { state ->
                    val xrayPanel = findViewById<View>(R.id.view_xray_panel) ?: return@collect
                    if (state == null) {
                        xrayPanel.findViewById<TextView>(R.id.tv_xray_title)?.text = originalTitle
                        xrayPanel.findViewById<TextView>(R.id.tv_xray_plot)?.text = ""
                        xrayPanel.findViewById<TextView>(R.id.tv_xray_meta)?.text = ""
                        xrayPanel.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.rv_xray_cast)?.adapter = null
                        return@collect
                    }

                    xrayPanel.findViewById<TextView>(R.id.tv_xray_title)?.text = state.title
                    xrayPanel.findViewById<TextView>(R.id.tv_xray_plot)?.text = state.overview ?: "No description available."
                    xrayPanel.findViewById<TextView>(R.id.tv_xray_meta)?.text = state.meta ?: ""
                    
                    val castTitle = xrayPanel.findViewById<TextView>(R.id.tv_xray_cast_title)
                    val rvCast = xrayPanel.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.rv_xray_cast)
                    
                    if (state.cast.isEmpty()) {
                        castTitle?.visibility = View.GONE
                        rvCast?.visibility = View.GONE
                    } else {
                        castTitle?.visibility = View.VISIBLE
                        rvCast?.visibility = View.VISIBLE
                        rvCast?.adapter = CastAdapter(state.cast)
                    }
                }
            }
        }
    }

    private class CastAdapter(
        private val items: List<com.tvonnet.debridxtreamiptv.player.stabilized.XRayCastMember>
    ) : androidx.recyclerview.widget.RecyclerView.Adapter<CastViewHolder>() {
        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): CastViewHolder {
            val view = android.view.LayoutInflater.from(parent.context)
                .inflate(R.layout.item_player_cast, parent, false)
            return CastViewHolder(view)
        }
        override fun onBindViewHolder(holder: CastViewHolder, position: Int) {
            holder.bind(items[position])
        }
        override fun getItemCount(): Int = items.size
    }

    private class CastViewHolder(view: View) : androidx.recyclerview.widget.RecyclerView.ViewHolder(view) {
        private val ivAvatar: android.widget.ImageView = view.findViewById(R.id.iv_cast_avatar)
        private val tvName: TextView = view.findViewById(R.id.tv_cast_name)
        private val tvCharacter: TextView = view.findViewById(R.id.tv_cast_character)
        
        init {
            itemView.setOnFocusChangeListener { v, hasFocus ->
                v.scaleX = if (hasFocus) 1.1f else 1.0f
                v.scaleY = if (hasFocus) 1.1f else 1.0f
            }
        }

        fun bind(item: com.tvonnet.debridxtreamiptv.player.stabilized.XRayCastMember) {
            tvName.text = item.name
            tvCharacter.text = item.character
            Glide.with(ivAvatar)
                .load(item.avatarUrl)
                .placeholder(R.drawable.ic_person_placeholder)
                .error(R.drawable.ic_person_placeholder)
                .circleCrop()
                .into(ivAvatar)
        }
    }
}
