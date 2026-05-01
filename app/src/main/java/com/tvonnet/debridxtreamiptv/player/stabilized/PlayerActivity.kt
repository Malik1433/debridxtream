package com.tvonnet.debridxtreamiptv.player.stabilized

import android.app.Activity
import android.app.ActivityManager
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
import com.tvonnet.debridxtreamiptv.data.model.toAbsoluteUrl
import com.tvonnet.debridxtreamiptv.data.prefs.CredentialsPreferences
import com.tvonnet.debridxtreamiptv.data.prefs.WatchHistoryPreferences
import kotlinx.coroutines.launch
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import okhttp3.OkHttpClient
import com.tvonnet.debridxtreamiptv.data.prefs.SettingsPreferences
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.SimpleItemAnimator
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
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheDataSink
import androidx.media3.datasource.DataSink
import androidx.media3.datasource.FileDataSource

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

    private var player: ExoPlayer? = null
    private lateinit var playerView: PlayerView
    private lateinit var watchHistoryPrefs: WatchHistoryPreferences

    private var retryCount = 0
    private val maxRetries = 5 
    private var currentUrl: String? = null
    private var streamTitle: String? = null
    private var streamHeaders: Map<String, String>? = null
    private var subtitleEntries: List<String> = emptyList()
    private val subtitleUrlRegex = Regex("https?://\\S+", RegexOption.IGNORE_CASE)
    private val languageCodeRegex = Regex("^[a-z]{2,3}(-[a-z0-9]{2,8})?$", RegexOption.IGNORE_CASE)
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
    private lateinit var rvEpisodes: RecyclerView
    private lateinit var episodeOverlay: View
    private lateinit var pbEpisodesLoading: ProgressBar
    private lateinit var tvEpisodesEmpty: TextView
    private lateinit var episodeAdapter: PlayerEpisodeAdapter
    private var isEpisodeOverlayVisible = false
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

    private var channelName: String? = null
    private var epgOverlay: View? = null
    private var imgChannelLogo: ImageView? = null
    private var tvChannelNumber: TextView? = null
    private var tvChannelName: TextView? = null
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

    private var channelLogoUrl: String? = null
    private var contentType: ContentType? = null
    private var playbackSource: PlaybackSource = PlaybackSource.IPTV
    private var contentId: String? = null
    private var seriesId: String? = null
    private var returnToSourcesOnExit: Boolean = false
    private var didPlaybackComplete: Boolean = false
    private var manualExit: Boolean = false
    private var exitResultHandled: Boolean = false
    private var lastPlaybackPositionMs: Long = 0L
    private var posterUrlExtra: String? = null
    private var backdropUrlExtra: String? = null
    private var tmdbIdExtra: String? = null
    private var imdbIdExtra: String? = null
    private var seriesTitleExtra: String? = null
    private var episodeTitleExtra: String? = null
    private var seasonNumberExtra: Int? = null
    private var episodeNumberExtra: Int? = null
    private var debridInfoHashExtra: String? = null
    private var debridMagnetExtra: String? = null
    private var expiresAtExtra: Long? = null
    private var originalTitle: String? = null
    private var hasRecordedHistory = false
    private val timeFormatter = SimpleDateFormat("HH:mm", Locale.getDefault())
    private var startPositionMs: Long = 0L
    private var hasShownOverlayOnce = false
    private var lastOverlayState: PlayerOverlayUiState? = null
    private var pendingChannelName: String? = null
    private var currentEpgChannelId: String? = null
    private var liveCategoryId: String? = null
    private var baseServerUrl: String? = null
    private var liveChannelIds: ArrayList<String>? = null
    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var networkAvailable = true
    private var isResolvingDebrid = false
    private var isIntentProcessing = false
    private var lastRecoveryTime = 0L
    private var hasAppliedIndexOverride = false
    private var preferredAudioLanguage: String? = null
    private var preferredSubtitleLanguage: String? = null

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
        const val EXTRA_SERIES_TITLE = "EXTRA_SERIES_TITLE"
        const val EXTRA_EPISODE_TITLE = "EXTRA_EPISODE_TITLE"
        const val EXTRA_SEASON_NUM = "EXTRA_SEASON_NUM"
        const val EXTRA_EPISODE_NUM = "EXTRA_EPISODE_NUM"
        const val EXTRA_TMDB_ID = "EXTRA_TMDB_ID"
        const val EXTRA_IMDB_ID = "EXTRA_IMDB_ID"
        const val EXTRA_DEBRID_INFOHASH = "EXTRA_DEBRID_INFOHASH"
        const val EXTRA_DEBRID_MAGNET = "EXTRA_DEBRID_MAGNET"
        const val EXTRA_EXPIRES_AT = "EXTRA_EXPIRES_AT"
        const val EXTRA_AUTO_PLAY_NEXT = "EXTRA_AUTO_PLAY_NEXT"
        const val EXTRA_FAILED_STREAM_ID = "EXTRA_FAILED_STREAM_ID"
        const val EXTRA_FAIL_REASON = "EXTRA_FAIL_REASON"
        const val EXTRA_RETURN_TO_SOURCES = "EXTRA_RETURN_TO_SOURCES"
        const val EXTRA_SUBTITLE_ENTRIES = "EXTRA_SUBTITLE_ENTRIES"

        fun createIntent(
            context: android.content.Context,
            streamUrl: String,
            title: String? = null,
            channelName: String? = null,
            channelLogo: String? = null,
            epgChannelId: String? = null,
            startPositionMs: Long? = null,
            contentId: String? = null,
            contentType: ContentType? = null,
            posterUrl: String? = null,
            backdropUrl: String? = null,
            playbackSource: com.tvonnet.debridxtreamiptv.player.stabilized.PlaybackSource? = null,
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
            subtitles: List<String>? = null,
            expiresAt: Long? = null,
            seriesId: String? = null
        ): Intent {
            return Intent(context, PlayerActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
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
                expiresAt?.let { putExtra(EXTRA_EXPIRES_AT, it) }
                seriesId?.let { putExtra(EXTRA_SERIES_ID, it) }
                subtitles?.let { putStringArrayListExtra(EXTRA_SUBTITLE_ENTRIES, ArrayList(it)) }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        
        if (BuildConfig.DEBUG) {
            Log.d("PlayerActivity", "onNewIntent received")
        }

        if (isSwitching || isResolvingDebrid || isIntentProcessing) {
            Log.w("PlayerActivity", "onNewIntent ignored: isSwitching=$isSwitching isResolving=$isResolvingDebrid isProcessing=$isIntentProcessing")
            return
        }

        val newUrl = intent.getStringExtra(EXTRA_STREAM_URL)
        val newContentId = intent.getStringExtra(EXTRA_CONTENT_ID) ?: newUrl
        
        if (newContentId == contentId && newUrl == currentUrl) {
            Log.d("PlayerActivity", "onNewIntent ignored: Same content already playing")
            return
        }

        isIntentProcessing = true
        try {
            parseIntentData(intent)
            
            if (player != null) {
                // Reuse player instance via seamless switch
                performSeamlessSwitch(currentUrl ?: "")
            } else {
                // Not initialized, fallback to normal initialization
                currentUrl?.let { initializePlayer(it) }
            }
        } finally {
            isIntentProcessing = false
        }
    }

    private fun parseIntentData(intent: Intent) {
        val contentTypeString = intent.getStringExtra(EXTRA_CONTENT_TYPE)
        contentType = contentTypeString?.let { runCatching { ContentType.valueOf(it) }.getOrNull() }
        playbackSource = intent.getStringExtra(EXTRA_PLAYBACK_SOURCE)
            ?.let { runCatching { PlaybackSource.valueOf(it) }.getOrNull() }
            ?: PlaybackSource.IPTV
        contentId = intent.getStringExtra(EXTRA_CONTENT_ID) ?: intent.getStringExtra(EXTRA_STREAM_URL)
        seriesId = intent.getStringExtra(EXTRA_SERIES_ID)
        returnToSourcesOnExit = intent.getBooleanExtra(EXTRA_RETURN_TO_SOURCES, false)
        currentEpgChannelId = intent.getStringExtra(EXTRA_EPG_CHANNEL_ID)
            ?.takeIf { it.isNotBlank() }
            ?: contentId?.takeIf { it.isNotBlank() }
        currentUrl = intent.getStringExtra(EXTRA_STREAM_URL)
        streamTitle = intent.getStringExtra(EXTRA_STREAM_TITLE)
        channelName = intent.getStringExtra(EXTRA_CHANNEL_NAME)
        channelLogoUrl = intent.getStringExtra(EXTRA_CHANNEL_LOGO)
        startPositionMs = intent.getLongExtra(EXTRA_START_POSITION, 0L)
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
        expiresAtExtra = intent.getLongExtra(EXTRA_EXPIRES_AT, -1L).takeIf { it > 0 }
        liveCategoryId = intent.getStringExtra(EXTRA_LIVE_CATEGORY_ID)
        liveChannelIds = intent.getStringArrayListExtra(EXTRA_LIVE_CHANNEL_IDS)
        baseServerUrl = intent.getStringExtra(EXTRA_BASE_SERVER_URL)
        
        @Suppress("UNCHECKED_CAST")
        val headersHashMap = intent.getSerializableExtra(EXTRA_STREAM_HEADERS) as? HashMap<String, String>
        streamHeaders = headersHashMap
        if (streamHeaders == null) {
            streamHeaders = readStreamHeaders(intent)
        }
        subtitleEntries = intent.getStringArrayListExtra(EXTRA_SUBTITLE_ENTRIES) ?: emptyList()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)

        if (BuildConfig.DEBUG) {
            Log.d("PlayerActivity", "onCreate package=$packageName taskId=$taskId component=${intent.component}")
        }

        preferredAudioLanguage = settingsPreferences.getPreferredAudioLanguage()
        preferredSubtitleLanguage = settingsPreferences.getPreferredSubtitleLanguage()
        watchHistoryPrefs = WatchHistoryPreferences(this)

        playerView = findViewById(R.id.player_view)
        layoutDebridResolving = findViewById(R.id.layout_debrid_resolving)
        tvResolvingStatus = findViewById(R.id.tv_resolving_status)
        layoutDebugOverlay = findViewById(R.id.layout_debug_overlay)
        tvDebugInfo = findViewById(R.id.tv_debug_info)

        episodeOverlay = findViewById(R.id.layout_episode_overlay)
        rvEpisodes = findViewById(R.id.rv_episodes)
        pbEpisodesLoading = findViewById(R.id.pb_episodes_loading)
        tvEpisodesEmpty = findViewById(R.id.tv_episodes_empty)
        
        episodeAdapter = PlayerEpisodeAdapter { episode ->
            if (isSwitching) return@PlayerEpisodeAdapter
            toggleEpisodeOverlay(false)
            playSeriesEpisode(episode)
        }
        rvEpisodes.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        (rvEpisodes.itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false
        rvEpisodes.adapter = episodeAdapter

        parseIntentData(intent)
        originalTitle = streamTitle

        // ID Resolution Guard (V2)
        if (contentType == ContentType.EPISODE && seriesId == null) {
            // If seriesId is missing, attempt to use TMDB ID or contentId as a fallback
            seriesId = tmdbIdExtra ?: contentId
            android.util.Log.w("PlayerActivity", "SeriesID missing! Attempting resolution with: $seriesId")
        }

        if (contentType == ContentType.EPISODE && seriesId != null) {
            viewModel.loadSeriesPlaylist(
                seriesId = seriesId!!,
                seasonNum = seasonNumberExtra ?: 1,
                startEpisodeId = contentId ?: ""
            )
        }
        
        if (currentUrl.isNullOrBlank()) {
            showError("Invalid stream URL")
            finish()
            return
        }

        timeoutMs = resolveTimeoutMs(currentUrl!!)
        channelLogoUrl = intent.getStringExtra(EXTRA_CHANNEL_LOGO)
        val channelNameUsed = intent.getStringExtra(EXTRA_CHANNEL_NAME) ?: streamTitle ?: "Playing"
        pendingChannelName = channelNameUsed

        if (contentType == ContentType.LIVE_TV) {
            setupOverlayViews()
            bindChannelMeta(channelNameUsed)
        } else {
            // New Redesign: We use the Controller's Top Bar instead of a separate VOD overlay
        }

        liveCategoryId = intent.getStringExtra(EXTRA_LIVE_CATEGORY_ID)
        baseServerUrl = intent.getStringExtra(EXTRA_BASE_SERVER_URL)
        liveChannelIds = intent.getStringArrayListExtra(EXTRA_LIVE_CHANNEL_IDS)
        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

        val seriesId = intent.getStringExtra(EXTRA_SERIES_ID) ?: tmdbIdExtra ?: imdbIdExtra
        val seasonNum = intent.getIntExtra(EXTRA_SEASON_NUM, -1)
        Log.d("PlayerActivity", "Series Playlist Loading Check: seriesId=$seriesId, season=$seasonNum, contentId=$contentId")
        if (seriesId != null && seasonNum != -1 && contentId != null) {
            viewModel.loadSeriesPlaylist(seriesId, seasonNum, contentId!!)
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
                            val playBtn = playerView.findViewById<View>(R.id.exo_play)
                            val pauseBtn = playerView.findViewById<View>(R.id.exo_pause)
                            
                            val targetBtn = if (playBtn?.isVisible == true) playBtn 
                                           else if (pauseBtn?.isVisible == true) pauseBtn
                                           else playerView.findViewById<View>(R.id.btn_player_shuffle)
                            
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
            
            playerView.findViewById<View>(R.id.btn_player_shuffle)?.setOnClickListener {
                showPlayerSettings()
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
            val btnEpisodes = playerView.findViewById<View>(R.id.btn_episodes_list)
            
            if (contentType == ContentType.SERIES || contentType == ContentType.EPISODE) {
                btnNext?.isVisible = true
                btnNext?.setOnClickListener {
                     playNextEpisode()
                }
                
                btnEpisodes?.isVisible = true
                btnEpisodes?.setOnClickListener {
                    toggleEpisodeOverlay(true)
                    playerView.hideController()
                }
            } else {
                btnNext?.isVisible = false
                btnEpisodes?.isVisible = false
            }

            playerView.findViewById<View>(R.id.btn_aspect_ratio)?.setOnClickListener {
                cycleResizeMode()
            }
            
            setupNextEpisodeViews()
            observeSeriesPlaylistState()
            observeDebridResolutionState()
        }

        initializePlayer(currentUrl!!)
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
                if (isEpisodeOverlayVisible) {
                    toggleEpisodeOverlay(false)
                    return
                }
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
                recordPlaybackHistoryIfNeeded()
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

        performSeamlessSwitch(target.streamUrl)
    }

    private fun playUrl(url: String) {
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

    private fun performSeamlessSwitch(newUrl: String) {
        if (isSwitching) return
        val playerSnapshot = player ?: return
        isSwitching = true
        switchCount++
        Log.i("PlayerActivity", "Performing seamless switch to: $newUrl")
        
        timeoutHandler.removeCallbacks(timeoutRunnable)
        timeoutMs = resolveTimeoutMs(newUrl)
        timeoutHandler.postDelayed(timeoutRunnable, timeoutMs)
        
        startStallMonitor()
        retryCount = 0
        
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
        val subtitleConfigs = buildSubtitleConfigurations(subtitleEntries)
        return if (subtitleConfigs.isEmpty()) {
            MediaItem.fromUri(url)
        } else {
            MediaItem.Builder()
                .setUri(url)
                .setSubtitleConfigurations(subtitleConfigs)
                .build()
        }
    }

    private fun buildSubtitleConfigurations(entries: List<String>): List<MediaItem.SubtitleConfiguration> {
        if (entries.isEmpty()) return emptyList()
        val configs = mutableListOf<MediaItem.SubtitleConfiguration>()
        for (entry in entries) {
            val parsed = parseSubtitleEntry(entry) ?: continue
            val config = MediaItem.SubtitleConfiguration.Builder(Uri.parse(parsed.url))
                .setMimeType(guessSubtitleMimeType(parsed.url))
                .setLanguage(parsed.language)
                .build()
            configs.add(config)
        }
        return configs.distinctBy { it.uri }
    }

    private fun parseSubtitleEntry(entry: String): ParsedSubtitle? {
        val trimmed = entry.trim()
        if (trimmed.isBlank()) return null
        val urlMatch = subtitleUrlRegex.find(trimmed) ?: return null
        val url = urlMatch.value
        val language = extractSubtitleLanguage(trimmed, url)
        return ParsedSubtitle(url, language)
    }

    private fun extractSubtitleLanguage(raw: String, url: String): String? {
        val stripped = raw.replace(url, " ")
            .replace('|', ' ')
            .replace(':', ' ')
            .replace(',', ' ')
        val token = stripped.trim().split(Regex("\\s+")).firstOrNull()
        val fromToken = normalizeLanguageCode(token)
        if (fromToken != null) return fromToken
        val uri = Uri.parse(url)
        val fromQuery = normalizeLanguageCode(uri.getQueryParameter("lang"))
            ?: normalizeLanguageCode(uri.getQueryParameter("language"))
        return fromQuery
    }

    private fun normalizeLanguageCode(value: String?): String? {
        val cleaned = value
            ?.trim()
            ?.lowercase(Locale.US)
            ?.replace('_', '-') ?: return null
        if (cleaned.isBlank() || !languageCodeRegex.matches(cleaned)) return null
        return cleaned
    }

    private fun guessSubtitleMimeType(url: String): String {
        val normalized = url.substringBefore('?').substringBefore('#').lowercase(Locale.US)
        return when {
            normalized.endsWith(".vtt") -> MimeTypes.TEXT_VTT
            normalized.endsWith(".srt") -> MimeTypes.APPLICATION_SUBRIP
            normalized.endsWith(".ass") || normalized.endsWith(".ssa") -> MimeTypes.TEXT_SSA
            normalized.endsWith(".ttml") || normalized.endsWith(".dfxp") -> MimeTypes.APPLICATION_TTML
            else -> MimeTypes.APPLICATION_SUBRIP
        }
    }

    private fun showPlayerSettings() {
        if (isInPictureInPictureMode) return
        val options = arrayOf(
            getString(R.string.player_settings_audio),
            getString(R.string.player_settings_subtitles)
        )
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.player_settings_title)
            .setItems(options) { dialog, which ->
                when (which) {
                    0 -> showAudioSelection()
                    1 -> showSubtitleSelection()
                }
                dialog.dismiss()
            }
            .show()
    }

    private fun showAudioSelection() {
        if (isInPictureInPictureMode) return
        val playerSnapshot = player ?: return
        
        TrackSelectionDialogBuilder(
            this,
            getString(R.string.player_audio_title),
            playerSnapshot,
            C.TRACK_TYPE_AUDIO
        )
            .setAllowAdaptiveSelections(false)
            .setAllowMultipleOverrides(false)
            .setTrackNameProvider(DefaultTrackNameProvider(resources))
            .build()
            .show()
    }

    private fun showSubtitleSelection() {
        if (isInPictureInPictureMode) return
        val playerSnapshot = player ?: return
        val hasTextTracks = playerSnapshot.currentTracks.groups.any { group ->
            group.type == C.TRACK_TYPE_TEXT && group.isSupported
        }
        if (!hasTextTracks) {
            val message = if (subtitleEntries.isNotEmpty()) {
                getString(R.string.player_subtitles_loading)
            } else {
                getString(R.string.player_subtitles_none)
            }
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
            return
        }

        TrackSelectionDialogBuilder(
            this,
            getString(R.string.player_settings_subtitles),
            playerSnapshot,
            C.TRACK_TYPE_TEXT
        )
            .setShowDisableOption(true)
            .setAllowAdaptiveSelections(false)
            .setAllowMultipleOverrides(false)
            .setTrackNameProvider(DefaultTrackNameProvider(resources))
            .build()
            .show()
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
                viewModel.overlayState.collect { renderOverlay(it) }
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

    private var nextEpisodeOverlay: View? = null
    private var tvNextEpTitle: TextView? = null
    private var tvCountdownSeconds: TextView? = null
    private var btnPlayNext: View? = null
    private var btnCancelNext: View? = null
    private var nextEpisodeTimer: CountDownTimer? = null
    private var isNextPromptVisible = false
    private var nextPromptShownForThisEpisode = false
    private val nextCheckHandler = Handler(Looper.getMainLooper())
    private val nextCheckRunnable = object : Runnable {
        override fun run() {
            checkNextEpisodePrompt()
            nextCheckHandler.postDelayed(this, 1000)
        }
    }

    private fun setupNextEpisodeViews() {
        if (nextEpisodeOverlay != null) return
        val overlay = findViewById<View>(R.id.view_next_episode_prompt) ?: return
        nextEpisodeOverlay = overlay
        tvNextEpTitle = overlay.findViewById(R.id.tv_next_episode_title)
        tvCountdownSeconds = overlay.findViewById(R.id.tv_countdown_seconds)
        btnPlayNext = overlay.findViewById(R.id.btn_play_now)
        btnCancelNext = overlay.findViewById(R.id.btn_cancel)

        btnPlayNext?.setOnClickListener {
            playNextEpisode()
        }
        btnCancelNext?.setOnClickListener {
            hideNextEpisodePrompt(cancelled = true)
        }
    }

    private fun observeSeriesPlaylistState() {
        lifecycleScope.launch {
            repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                launch {
                    viewModel.seriesPlaylistState.collect { state ->
                        updateEpisodeOverlayUI(state, viewModel.isPlaylistLoading.value)
                        if (state == null) return@collect
                        
                        episodeAdapter.submitList(state.originalList) {
                            if (isEpisodeOverlayVisible) {
                                rvEpisodes.post {
                                    rvEpisodes.requestFocus()
                                    val index = state.currentIndex
                                    if (index >= 0) rvEpisodes.scrollToPosition(index)
                                }
                            }
                        }
                        episodeAdapter.setCurrentEpisodeId(state.currentEpisode.episodeId)
                        val currentEpId = contentId
                        if (currentEpId != state.currentEpisode.episodeId) {
                             if (playbackSource != PlaybackSource.DEBRID) {
                                 playSeriesEpisode(state.currentEpisode)
                             }
                        }
                    }
                }

                launch {
                    viewModel.isPlaylistLoading.collect { isLoading ->
                        updateEpisodeOverlayUI(viewModel.seriesPlaylistState.value, isLoading)
                    }
                }
            }
        }
    }

    private fun updateEpisodeOverlayUI(state: SeriesPlaylistState?, isLoading: Boolean) {
        val hasData = state != null && state.originalList.isNotEmpty()
        
        // Spinner Logic
        pbEpisodesLoading.visibility = if (isLoading) View.VISIBLE else View.GONE
        
        if (hasData) {
            // Data is PRIORITY. Show the list immediately.
            rvEpisodes.visibility = View.VISIBLE
            tvEpisodesEmpty.visibility = View.GONE
        } else if (isLoading) {
            // No data yet, but still loading
            rvEpisodes.visibility = View.GONE
            tvEpisodesEmpty.visibility = View.GONE
        } else {
            // Not loading and no data
            rvEpisodes.visibility = View.GONE
            tvEpisodesEmpty.visibility = if (isEpisodeOverlayVisible) View.VISIBLE else View.GONE
        }
    }

    private fun playSeriesEpisode(episode: EpisodeEntityV2) {
        val url = viewModel.getSeriesStreamUrl(episode)
        
        contentId = episode.episodeId
        currentUrl = url
        val title = episode.title ?: "Episode ${episode.episodeNumber}"
        originalTitle = title
        bindModernMetadata(title)
        supportActionBar?.title = title
        
        hideNextEpisodePrompt(cancelled = false)
        nextPromptShownForThisEpisode = false
        
        performSeamlessSwitch(url)
    }

    private fun toggleEpisodeOverlay(show: Boolean) {
        if (show == isEpisodeOverlayVisible) return
        isEpisodeOverlayVisible = show
        if (show) {
            episodeOverlay.visibility = View.VISIBLE
            updateEpisodeOverlayUI(viewModel.seriesPlaylistState.value, viewModel.isPlaylistLoading.value)
            rvEpisodes.requestFocus()
            val state = viewModel.seriesPlaylistState.value
            val index = state?.currentIndex ?: -1
            if (index >= 0) rvEpisodes.scrollToPosition(index)
        } else {
            episodeOverlay.visibility = View.GONE
            playerView.requestFocus()
        }
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
        
        val subtitleText = if (contentType == ContentType.EPISODE || contentType == ContentType.SERIES) {
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
        
        subtitleView?.text = subtitleText
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
        nextEpisodeTimer?.cancel()

        if (playbackSource == PlaybackSource.DEBRID && contentType == ContentType.EPISODE) {
             val currentSeason = seasonNumberExtra ?: -1
             val currentEpisode = episodeNumberExtra ?: -1
             val tmdbId = tmdbIdExtra
             
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
                      infoHash = debridInfoHashExtra 
                  )
             } else {
                 showError("Next episode data missing")
             }
        } else {
            viewModel.getNextEpisode() 
        }
    }

    private fun checkNextEpisodePrompt() {
        if (contentType != ContentType.SERIES && contentType != ContentType.EPISODE) return
        val player = player ?: return
        if (!player.isPlaying) return
        
        val duration = player.duration
        val position = player.currentPosition
        if (duration <= 0) return

        val remaining = duration - position
        val percentThresholdMs = (duration * 0.97).toLong() 
        val timeThresholdMs = 45000L 
        
        val showPrompt = position >= percentThresholdMs || remaining < timeThresholdMs
        
        if (showPrompt && !nextPromptShownForThisEpisode && !isNextPromptVisible) {
             val state = viewModel.seriesPlaylistState.value
             if (state?.hasNext == true) {
                 showNextEpisodePrompt(state.originalList[state.currentIndex + 1])
             }
        }
    }

    private fun showNextEpisodePrompt(nextEp: EpisodeEntityV2) {
        if (isNextPromptVisible) return
        isNextPromptVisible = true
        nextPromptShownForThisEpisode = true 
        
        nextEpisodeOverlay?.isVisible = true
        tvNextEpTitle?.text = nextEp.title ?: "Episode ${nextEp.episodeNumber}"
        
        nextEpisodeTimer?.cancel()
        nextEpisodeTimer = object : CountDownTimer(15000, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                val sec = (millisUntilFinished / 1000).toInt()
                tvCountdownSeconds?.text = sec.toString()
                val progress = ((15000 - millisUntilFinished) / 150.0).toInt()
                nextEpisodeOverlay?.findViewById<ProgressBar>(R.id.progress_countdown)?.progress = progress
            }
            override fun onFinish() {
                playNextEpisode()
            }
        }.start()
        
        playerView.hideController()
        playerView.isFocusable = false
        
        com.tvonnet.debridxtreamiptv.utils.FocusCoordinator.requestFocus("PLAYER_NEXT_EP") {
            btnPlayNext?.post { 
                btnPlayNext?.post {
                    try {
                        btnPlayNext?.requestFocus()
                    } finally {
                    }
                }
            }
        }
    }

    private fun hideNextEpisodePrompt(cancelled: Boolean) {
        isNextPromptVisible = false
        nextEpisodeOverlay?.isVisible = false
        nextEpisodeTimer?.cancel()
        
        playerView.isFocusable = true
        com.tvonnet.debridxtreamiptv.utils.FocusCoordinator.release("PLAYER_NEXT_EP")
        
        if (cancelled) {
            playerView.showController()
            playerView.requestFocus()
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

    private fun calculateSmartBuffer(): Int {
        val snapshot = DeviceProfile.get(this)
        val totalRamGb = snapshot.totalRamGb
        return if (!snapshot.isLowRamDevice) 60000 else 25000 
    }

    private fun getSavedNetworkQuality(): NetworkQuality {
        val rawQuality = settingsPreferences.getNetworkQuality()
        return runCatching { NetworkQuality.valueOf(rawQuality) }.getOrDefault(NetworkQuality.MODERATE)
    }

    private fun buildLiveBufferConfig(isHls: Boolean, isDebrid: Boolean = false): BufferConfig {
        val baseMaxBuffer = calculateSmartBuffer()
        val quality = getSavedNetworkQuality()
        val config = when (quality) {
            NetworkQuality.FAST -> BufferConfig(15000, baseMaxBuffer, 1000, 2000)
            NetworkQuality.MODERATE -> BufferConfig(15000, baseMaxBuffer, 2000, 3000)
            NetworkQuality.SLOW -> BufferConfig(20000, (baseMaxBuffer + 10000).coerceAtMost(60000), 3000, 5000)
            NetworkQuality.UNKNOWN -> BufferConfig(15000, baseMaxBuffer, 2000, 3000)
        }
        val debridAdjusted = if (isDebrid) config.copy(startPlaybackMs = (config.startPlaybackMs + 1000).coerceAtLeast(3000), rebufferPlaybackMs = (config.rebufferPlaybackMs + 1000).coerceAtLeast(4000)) else config
        val cappedForDevice = debridAdjusted.copy(maxBufferMs = capMaxBufferForDevice(debridAdjusted.maxBufferMs))
        return if (isHls) cappedForDevice.copy(maxBufferMs = cappedForDevice.maxBufferMs.coerceAtMost(if (DeviceProfile.isLowRamDevice(this)) 25000 else 30000)) else cappedForDevice
    }

    private fun buildVodBufferConfig(isHls: Boolean, isDebrid: Boolean = false): BufferConfig {
        val baseMaxBuffer = calculateSmartBuffer()
        val quality = getSavedNetworkQuality()
        val config = when (quality) {
            NetworkQuality.FAST -> BufferConfig(12000, baseMaxBuffer, 1500, 3000)
            NetworkQuality.MODERATE -> BufferConfig(15000, baseMaxBuffer, 2000, 3500)
            NetworkQuality.SLOW -> BufferConfig(18000, (baseMaxBuffer + 15000).coerceAtMost(90000), 3000, 5000)
            NetworkQuality.UNKNOWN -> BufferConfig(15000, baseMaxBuffer, 2000, 3500)
        }
        val debridAdjusted = if (isDebrid) config.copy(startPlaybackMs = (config.startPlaybackMs + 2000).coerceAtLeast(5000), rebufferPlaybackMs = (config.rebufferPlaybackMs + 2000).coerceAtLeast(6000)) else config
        val cappedForDevice = debridAdjusted.copy(maxBufferMs = capMaxBufferForDevice(debridAdjusted.maxBufferMs))
        return if (isHls) cappedForDevice.copy(maxBufferMs = cappedForDevice.maxBufferMs.coerceAtMost(if (DeviceProfile.isLowRamDevice(this)) 25000 else 45000)) else cappedForDevice
    }

    private fun isHlsUrl(url: String): Boolean = url.contains(".m3u8", ignoreCase = true)

    private fun capMaxBufferForDevice(maxBufferMs: Int): Int = if (DeviceProfile.isLowRamDevice(this)) maxBufferMs.coerceAtMost(LOW_RAM_MAX_BUFFER_MS) else maxBufferMs

    private fun resolveTargetBufferBytes(): Int = if (DeviceProfile.isLowRamDevice(this)) LOW_RAM_TARGET_BUFFER_BYTES else DefaultLoadControl.DEFAULT_TARGET_BUFFER_BYTES

    private fun readStreamHeaders(intent: Intent): Map<String, String>? {
        @Suppress("UNCHECKED_CAST")
        val raw = intent.getSerializableExtra(EXTRA_STREAM_HEADERS) as? HashMap<String, String>
        return raw?.takeIf { it.isNotEmpty() }
    }

    private fun initializePlayer(streamUrl: String) {
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
            val loadControl = if (contentType == ContentType.LIVE_TV) {
                val bufferConfig = buildLiveBufferConfig(isHlsUrl(streamUrl), isDebrid)
                DefaultLoadControl.Builder().setBufferDurationsMs(bufferConfig.minBufferMs, bufferConfig.maxBufferMs, bufferConfig.startPlaybackMs, bufferConfig.rebufferPlaybackMs).setTargetBufferBytes(resolveTargetBufferBytes()).setPrioritizeTimeOverSizeThresholds(true).build()
            } else {
                val bufferConfig = buildVodBufferConfig(isHlsUrl(streamUrl), isDebrid)
                DefaultLoadControl.Builder().setBufferDurationsMs(bufferConfig.minBufferMs, bufferConfig.maxBufferMs, bufferConfig.startPlaybackMs, bufferConfig.rebufferPlaybackMs).setTargetBufferBytes(resolveTargetBufferBytes()).setPrioritizeTimeOverSizeThresholds(true).build()
            }
            
            val trackSelector = DefaultTrackSelector(this)
            var parametersBuilder = trackSelector.buildUponParameters().setTunnelingEnabled(false)
            preferredAudioLanguage?.let { parametersBuilder = parametersBuilder.setPreferredAudioLanguage(it) }
            preferredSubtitleLanguage?.let { parametersBuilder = parametersBuilder.setPreferredTextLanguage(it) }
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
                                  if (playbackSource == PlaybackSource.DEBRID || state?.hasNext == true) {
                                       playNextEpisode()
                                       return
                                  }
                             }
                             didPlaybackComplete = true
                             if (!isNextPromptVisible) finish()
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
                    Log.e("PlayerActivity", "Playback error: ${error.errorCodeName}", error)
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
            val currentTime = SystemClock.elapsedRealtime()
            if (currentTime - lastRecoveryTime < 10000) {
                Log.w("PlayerActivity", "Recovery skipped: Too soon since last recovery attempt")
                return
            }
            lastRecoveryTime = currentTime
            
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
            
            // Silent Recovery Guard: Catch 403 Forbidden or 410 Gone (common for expired Debrid links)
            val isExpiredError = cause is HttpDataSource.InvalidResponseCodeException && 
                                (cause.responseCode == 403 || cause.responseCode == 410)

            if (playbackSource == PlaybackSource.DEBRID && !isResolvingDebrid && (isExpiredError || retryCount > 1) && (!debridInfoHashExtra.isNullOrBlank() || !debridMagnetExtra.isNullOrBlank())) {
                  val currentTime = SystemClock.elapsedRealtime()
                  // BUG FIX: Expired link errors (403/410) ALWAYS bypass the cooldown.
                  // Previously, a second 403 within 10s (e.g., after re-resolution returned
                  // the wrong episode file) would be silently dropped, freezing the player.
                  // Generic non-expired errors still respect the cooldown to avoid thrashing.
                  if (!isExpiredError && currentTime - lastRecoveryTime < 10000) {
                      Log.w("PlayerActivity", "Debrid re-resolution skipped: Cooldown active (non-expired error)")
                      return
                  }
                  lastRecoveryTime = currentTime
                  
                  android.util.Log.i("PlayerActivity", "Silent Recovery: Detected ${if (isExpiredError) "Expired Link (403/410)" else "Playback Error"}. Triggering re-resolution...")
                  isResolvingDebrid = true
                  if (player != null && player!!.currentPosition > 1000L) startPositionMs = player!!.currentPosition
                  viewModel.reResolveDebridUrl(debridInfoHashExtra, debridMagnetExtra, seasonNumberExtra, episodeNumberExtra, episodeTitleExtra)
                  return
            }
            showToast("Retrying... ($retryCount/$maxRetries)")
            if (contentType != ContentType.LIVE_TV && player != null && player!!.currentPosition > 1000L) startPositionMs = player!!.currentPosition
            player?.release(); player = null
            Handler(Looper.getMainLooper()).postDelayed({ currentUrl?.let { initializePlayer(it) } }, retryCount * 1000L)
        } else {
            if (!finishWithReturnToSources(autoPlayNext = true, reason = errorMessage)) showSupportQr()
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

    private enum class NetworkQuality { FAST, MODERATE, SLOW, UNKNOWN }
    private data class BufferConfig(val minBufferMs: Int, val maxBufferMs: Int, val startPlaybackMs: Int, val rebufferPlaybackMs: Int)

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
        if (!finishWithReturnToSources(autoPlayNext = true, reason = "Init failed: ${error.message}")) {
            showError("Failed to initialize player: ${error.message}")
            Handler(Looper.getMainLooper()).postDelayed({ finish() }, 3000)
        }
    }

    private fun handleTimeout() {
        if (player?.playbackState == Player.STATE_BUFFERING) {
            if (retryCount < maxRetries) {
                retryCount++
                // BUG FIX: Changed retryCount > 1 to retryCount >= 1 so Debrid re-resolution fires
                // on the FIRST timeout instead of wasting one full 25s cycle re-trying a dead URL.
                if (playbackSource == PlaybackSource.DEBRID && !isResolvingDebrid && retryCount >= 1 && (!debridInfoHashExtra.isNullOrBlank() || !debridMagnetExtra.isNullOrBlank())) {
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
                if (!finishWithReturnToSources(autoPlayNext = true, reason = "Connection timeout")) {
                    showError("Connection timeout\n\nStream is too slow or unavailable")
                    Handler(Looper.getMainLooper()).postDelayed({ finish() }, 3000)
                }
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
    override fun onStart() { super.onStart(); registerNetworkCallback(); nextCheckHandler.post(nextCheckRunnable) }
    override fun onPause() { super.onPause(); player?.pause(); timeoutHandler.removeCallbacks(timeoutRunnable); stopStallMonitor() }
    override fun onStop() { super.onStop(); debugOverlayHandler.removeCallbacks(debugOverlayRunnable); timeoutHandler.removeCallbacks(timeoutRunnable); stallHandler.removeCallbacks(stallRunnable); unregisterNetworkCallback(); recordPlaybackHistoryIfNeeded(); releasePlayer() }

    private fun releasePlayer() {
        updateLastPlaybackPosition(); timeoutHandler.removeCallbacks(timeoutRunnable); overlayHandler.removeCallbacks(overlayHideRunnable); nextCheckHandler.removeCallbacks(nextCheckRunnable); nextEpisodeTimer?.cancel(); stopStallMonitor()
        player?.stop(); player?.release(); player = null; playerView.player = null
    }

    private fun startStallMonitor() {
        lastBoundPosition = player?.currentPosition ?: 0L
        lastProgressCheckMs = SystemClock.elapsedRealtime(); stallStrikeCount = 0
        stallHandler.removeCallbacks(stallRunnable); stallHandler.postDelayed(stallRunnable, 5000)
    }

    private fun stopStallMonitor() = stallHandler.removeCallbacks(stallRunnable)

    override fun onDestroy() { super.onDestroy(); recordPlaybackHistoryIfNeeded(); releasePlayer() }

    private fun resolveTimeoutMs(url: String): Long = if (url.lowercase().contains("mediafusion.elfhosted.com")) MEDIAFUSION_TIMEOUT_MS else TIMEOUT_MS

    override fun finish() { setExitResultIfNeeded(); super.finish() }

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

        browserCategoryAdapter = BrowserCategoryAdapter { category -> category.category_id?.let { viewModel.selectBrowserCategory(it) } }
        rvCategories.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this); rvCategories.adapter = browserCategoryAdapter

        browserChannelAdapter = BrowserChannelAdapter { stream ->
            stream.stream_id?.let { id ->
                val serverUrl = baseServerUrl ?: prefs.getServerUrl() ?: ""
                val newUrl = "$serverUrl/live/${prefs.getUsername()}/${prefs.getPassword()}/$id.ts"
                performSeamlessSwitch(newUrl)
                contentId = id; currentUrl = newUrl; channelLogoUrl = com.tvonnet.debridxtreamiptv.util.GlobalConfig.resolveIconUrl(stream.stream_icon); currentEpgChannelId = stream.epg_channel_id ?: id
                bindChannelMeta(stream.name); supportActionBar?.title = stream.name
                viewModel.initLiveZapping(viewModel.browserState.value.selectedCategoryId, id, serverUrl, null)
                viewModel.observeEpg(stream.epg_channel_id ?: id, id)
                viewModel.toggleBrowser(false)
            }
        }
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
        if (event.action == KeyEvent.ACTION_DOWN) {
            if (isEpisodeOverlayVisible) {
                if (event.keyCode == KeyEvent.KEYCODE_BACK || event.keyCode == KeyEvent.KEYCODE_ESCAPE) {
                    toggleEpisodeOverlay(false)
                    return true
                }
            }

            if (event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN || event.keyCode == KeyEvent.KEYCODE_MENU) {
                if (contentType == ContentType.SERIES || contentType == ContentType.EPISODE) {
                    if (!isEpisodeOverlayVisible && !playerView.isControllerFullyVisible) {
                        toggleEpisodeOverlay(true)
                        return true
                    }
                }
            }

            if (contentType != ContentType.LIVE_TV && playerView.useController && !isNextPromptVisible && !playerView.isControllerFullyVisible && !isEpisodeOverlayVisible) {
                when (event.keyCode) { KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, KeyEvent.KEYCODE_MEDIA_REWIND, KeyEvent.KEYCODE_MENU -> { playerView.showController(); playerView.requestFocus(); return true } }
            }
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> { if (contentType == ContentType.LIVE_TV && !viewModel.browserState.value.isVisible) { viewModel.toggleBrowser(true, viewModel.zapState.value?.categoryId ?: liveCategoryId, contentId); return true } }
                KeyEvent.KEYCODE_CAPTIONS -> { if (contentType != ContentType.LIVE_TV) { showSubtitleSelection(); return true } }
                KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_PAGE_UP -> { if (contentType == ContentType.LIVE_TV && !viewModel.browserState.value.isVisible) { zapChannel(direction = +1); return true } }
                KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.KEYCODE_PAGE_DOWN -> { if (contentType == ContentType.LIVE_TV && !viewModel.browserState.value.isVisible) { zapChannel(direction = -1); return true } }
                KeyEvent.KEYCODE_DPAD_UP -> { if (contentType == ContentType.LIVE_TV && !viewModel.browserState.value.isVisible) { zapChannel(direction = +1); return true } }
                KeyEvent.KEYCODE_DPAD_DOWN -> { if (contentType == ContentType.LIVE_TV && !viewModel.browserState.value.isVisible) { zapChannel(direction = -1); return true } }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> { if (contentType == ContentType.LIVE_TV) { if (viewModel.browserState.value.isVisible) return super.dispatchKeyEvent(event); if (epgOverlayMode != EpgOverlayMode.HIDDEN && !epgOverlayPinned) hideEpgOverlay() else showEpgOverlay(EpgOverlayMode.COMPACT, pinned = false); return true } }
                KeyEvent.KEYCODE_INFO -> { if (contentType == ContentType.LIVE_TV) toggleEpgOverlayPinned() else { playerView.showController(); playerView.requestFocus() }; return true }
                KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_GUIDE -> {
                    if (event.keyCode == KeyEvent.KEYCODE_MENU) {
                        debugEnabled = !debugEnabled
                        layoutDebugOverlay?.visibility = if (debugEnabled) View.VISIBLE else View.GONE
                        startDebugOverlay()
                    }
                    if (contentType == ContentType.LIVE_TV) { toggleEpgOverlayPinned(); return true }; if (playerView.isControllerFullyVisible) { showSubtitleSelection(); return true } 
                }
                KeyEvent.KEYCODE_BACK -> { if (viewModel.browserState.value.isVisible) { viewModel.toggleBrowser(false); return true }; if (contentType == ContentType.LIVE_TV && epgOverlayPinned && epgOverlayMode != EpgOverlayMode.HIDDEN) { hideEpgOverlay(); return true }; if (event.repeatCount > 0 && supportsPictureInPicture()) { enterPictureInPictureModeInternal(); return true } }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onKeyLongPress(keyCode: Int, event: KeyEvent): Boolean {
        if (contentType == ContentType.LIVE_TV) { when (keyCode) { KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> { toggleEpgOverlayPinned(); return true } } }
        return super.onKeyLongPress(keyCode, event)
    }

    override fun onUserInteraction() { super.onUserInteraction(); if (contentType == ContentType.LIVE_TV || isInPictureInPictureMode || !playerView.useController || isNextPromptVisible) return; if (!playerView.isControllerFullyVisible) { playerView.showController(); playerView.requestFocus() } }

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

    private fun hideUiForPiP() { playerView.hideController(); epgOverlay?.isVisible = false; vodInfoOverlay?.isVisible = false; supportActionBar?.hide(); recordPlaybackHistoryIfNeeded() }
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

    private fun recordPlaybackHistoryIfNeeded() { if (hasRecordedHistory) return; val type = contentType ?: return; val id = contentId ?: currentUrl ?: return; when (type) { ContentType.LIVE_TV -> recordLiveHistory(id); ContentType.MOVIE, ContentType.SERIES, ContentType.EPISODE -> recordContinueWatchingHistory(id, type) }; hasRecordedHistory = true }
    private fun recordLiveHistory(channelId: String) { watchHistoryPrefs.addRecentLiveChannel(RecentLiveChannelItem(channelId, tvChannelName?.text?.toString() ?: pendingChannelName ?: originalTitle ?: getString(R.string.player_epg_channel_unknown), (channelLogoUrl ?: posterUrlExtra).toAbsoluteUrl(ContentType.LIVE_TV, baseServerUrl), System.currentTimeMillis(), currentUrl ?: "", currentEpgChannelId)) }
    private fun recordContinueWatchingHistory(contentId: String, type: ContentType) { val p = player ?: return; val dur = p.duration; if (dur <= 0 || dur == C.TIME_UNSET || dur < MIN_DURATION_TO_TRACK_MS) return; val pos = p.currentPosition; if (pos < MIN_PROGRESS_TO_TRACK_MS) return; val isWatched = (pos / dur.toFloat()) >= COMPLETION_THRESHOLD_RATIO; if (type == ContentType.EPISODE) viewModel.updatePlaybackStatus(contentId, isWatched, if (isWatched) 0 else pos, dur); if (isWatched) { watchHistoryPrefs.removeContinueWatchingItem(resolveContinueWatchingId(type, contentId)); return }; watchHistoryPrefs.saveContinueWatchingItem(ContinueWatchingItem(resolveContinueWatchingId(type, contentId), type, if (type == ContentType.EPISODE) seriesTitleExtra ?: originalTitle ?: pendingChannelName ?: getString(R.string.player_epg_channel_unknown) else originalTitle ?: pendingChannelName ?: getString(R.string.player_epg_channel_unknown), (posterUrlExtra ?: channelLogoUrl).toAbsoluteUrl(type, baseServerUrl), backdropUrlExtra.toAbsoluteUrl(type, baseServerUrl), pos, dur, System.currentTimeMillis(), currentUrl, tmdbIdExtra, imdbIdExtra, seriesTitleExtra, episodeTitleExtra, seasonNumberExtra, episodeNumberExtra, debridInfoHashExtra, debridMagnetExtra, if (playbackSource == PlaybackSource.DEBRID) "debrid" else "xtream", expiresAtExtra)) }
    private fun resolveContinueWatchingId(type: ContentType, fallbackId: String): String = if (playbackSource != PlaybackSource.DEBRID) fallbackId else tmdbIdExtra?.let { if (type == ContentType.EPISODE && seasonNumberExtra != null && episodeNumberExtra != null) "$it:S${seasonNumberExtra}E${episodeNumberExtra}" else it } ?: debridInfoHashExtra ?: fallbackId
    private fun updateLastPlaybackPosition() { lastPlaybackPositionMs = player?.currentPosition ?: lastPlaybackPositionMs }
    private fun setExitResultIfNeeded() { if (exitResultHandled || !returnToSourcesOnExit || playbackSource != PlaybackSource.DEBRID || didPlaybackComplete) return; val pos = player?.currentPosition ?: lastPlaybackPositionMs; if (manualExit || pos < RETURN_TO_SOURCES_THRESHOLD_MS) { exitResultHandled = true; setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_RETURN_TO_SOURCES, true)) } }
    private fun finishWithReturnToSources(autoPlayNext: Boolean, reason: String?): Boolean { if (!returnToSourcesOnExit || playbackSource != PlaybackSource.DEBRID || exitResultHandled) return false; exitResultHandled = true; setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_RETURN_TO_SOURCES, true).putExtra(EXTRA_FAILED_STREAM_ID, contentId ?: debridInfoHashExtra ?: currentUrl).putExtra(EXTRA_FAIL_REASON, reason).putExtra(EXTRA_AUTO_PLAY_NEXT, autoPlayNext)); releasePlayer(); finish(); return true }
    private fun updatePlayPauseVisibility(isPlaying: Boolean) { playerView.findViewById<View>(R.id.exo_play)?.isVisible = !isPlaying; playerView.findViewById<View>(R.id.exo_pause)?.isVisible = isPlaying; if (isControllerVisible) { val play = playerView.findViewById<View>(R.id.exo_play); val pause = playerView.findViewById<View>(R.id.exo_pause); if (isPlaying && play?.isFocused == true) pause?.post { pause.requestFocus() } else if (!isPlaying && pause?.isFocused == true) play?.post { play.requestFocus() } } }

    private fun observeDebridResolutionState() {
        lifecycleScope.launch {
            viewModel.debridResolutionState.collect { state ->
                when (state) {
                    is DebridResolutionState.Loading -> { layoutDebridResolving?.isVisible = true; tvResolvingStatus?.text = "Resolving source via Debrid..." }
                    is DebridResolutionState.Success -> {
                        layoutDebridResolving?.isVisible = false; isResolvingDebrid = false
                        // Metadata Anchor Sync: Update expiresAtExtra with fresh timestamp from resolver
                        state.expiresAt?.let { newExpiry ->
                            if (expiresAtExtra == null || newExpiry > (expiresAtExtra ?: 0L)) {
                                android.util.Log.i("PlayerActivity", "Metadata Anchor: Updating expiresAtExtra from $expiresAtExtra to $newExpiry")
                                expiresAtExtra = newExpiry
                            }
                        }
                        
                        if (state.season != null && state.episode != null) {
                            val isSame = seasonNumberExtra == state.season && episodeNumberExtra == state.episode
                            seasonNumberExtra = state.season; episodeNumberExtra = state.episode; episodeTitleExtra = state.title
                            if (!isSame) startPositionMs = 0L
                            bindModernMetadata("${seriesTitleExtra ?: ""} - S${state.season}:E${state.episode}")
                            if (state.infoHash != null) debridInfoHashExtra = state.infoHash
                            hasRecordedHistory = false; hideNextEpisodePrompt(false); nextPromptShownForThisEpisode = false
                        }
                        currentUrl = state.url; performSeamlessSwitch(state.url)
                    }
                    is DebridResolutionState.Error -> {
                        layoutDebridResolving?.isVisible = false
                        isResolvingDebrid = false
                        android.util.Log.e("PlayerActivity", "Debrid re-resolution FAILED permanently: ${state.message}")
                        // BUG FIX: Previously this only showed a Toast, leaving the player frozen
                        // on a black screen in STATE_BUFFERING with no way to recover.
                        // Now we return to sources (autoPlayNext=true picks the next source
                        // automatically), or fall back to support QR if no sources remain.
                        if (!finishWithReturnToSources(autoPlayNext = true, reason = state.message)) {
                            showSupportQr()
                        }
                    }
                    is DebridResolutionState.Idle -> { layoutDebridResolving?.isVisible = false; isResolvingDebrid = false }
                }
            }
        }
    }

    private fun captureManualTrackSelection() {
        val p = player ?: return; val groups = p.currentTracks.groups; var sAIdx = -1; var sALang: String? = null; var sTIdx = -1; var sTLang: String? = null
        var aC = 0; for (g in groups) { if (g.type == C.TRACK_TYPE_AUDIO) { for (j in 0 until g.length) { if (g.isTrackSelected(j)) { sAIdx = aC; sALang = g.getTrackFormat(j).language; break }; aC++ } }; if (sAIdx != -1) break }
        var tC = 0; for (g in groups) { if (g.type == C.TRACK_TYPE_TEXT) { for (j in 0 until g.length) { if (g.isTrackSelected(j)) { sTIdx = tC; sTLang = g.getTrackFormat(j).language; break }; tC++ } }; if (sTIdx != -1) break }
        if (sALang != null) { preferredAudioLanguage = sALang; settingsPreferences.savePreferredAudioLanguage(sALang) }
        if (sTLang != null) { preferredSubtitleLanguage = sTLang; settingsPreferences.savePreferredSubtitleLanguage(sTLang) }
        settingsPreferences.saveLastTrackSelection(debridInfoHashExtra, sAIdx, sTIdx)
        (intent.getStringExtra(EXTRA_SERIES_ID) ?: tmdbIdExtra ?: imdbIdExtra)?.let { settingsPreferences.saveSeriesTrackPreference(it, sAIdx, sTIdx) }
    }

    private fun applyTrackIndexOverrides() {
        val p = player ?: return; val lastHash = settingsPreferences.getLastSelectionHash(); val currentHash = debridInfoHashExtra; val seriesId = intent.getStringExtra(EXTRA_SERIES_ID) ?: tmdbIdExtra ?: imdbIdExtra
        var tAIdx = -1; var tTIdx = -1
        if (lastHash != null && currentHash != null && lastHash.equals(currentHash, ignoreCase = true)) { tAIdx = settingsPreferences.getLastAudioIndex(); tTIdx = settingsPreferences.getLastTextIndex() }
        else if (seriesId != null) { tAIdx = settingsPreferences.getSeriesAudioIndex(seriesId); tTIdx = settingsPreferences.getSeriesTextIndex(seriesId) }
        if (tAIdx == -1 && tTIdx == -1) return
        val groups = p.currentTracks.groups; var params = p.trackSelectionParameters.buildUpon(); var mod = false
        if (tAIdx != -1) { var aC = 0; for (g in groups) { if (g.type == C.TRACK_TYPE_AUDIO) { for (j in 0 until g.length) { if (aC == tAIdx) { params.addOverride(TrackSelectionOverride(g.mediaTrackGroup, j)); mod = true; break }; aC++ } }; if (mod) break } }
        if (tTIdx != -1) { var tC = 0; var tMod = false; for (g in groups) { if (g.type == C.TRACK_TYPE_TEXT) { for (j in 0 until g.length) { if (tC == tTIdx) { params.addOverride(TrackSelectionOverride(g.mediaTrackGroup, j)); mod = true; tMod = true; break }; tC++ } }; if (tMod) break } }
        if (mod) p.trackSelectionParameters = params.build()
    }

    private fun setupInteractiveAnimations() {
        listOf(R.id.btn_player_shuffle, R.id.exo_rew, R.id.exo_play, R.id.exo_pause, R.id.exo_ffwd, R.id.btn_aspect_ratio, R.id.btn_next_episode).forEach { id ->
            playerView.findViewById<View>(id)?.let { v ->
                v.alpha = 0.7f; v.scaleX = 1f; v.scaleY = 1f
                v.setOnFocusChangeListener { view, f -> if (f) { view.animate().scaleX(1.15f).scaleY(1.15f).alpha(1f).setDuration(200).start(); if (view.id == R.id.btn_player_shuffle) view.animate().rotationBy(90f).setDuration(400).start() } else view.animate().scaleX(1f).scaleY(1f).alpha(0.7f).setDuration(200).start() }
                v.setOnTouchListener { view, e -> when (e.action) { android.view.MotionEvent.ACTION_DOWN -> view.animate().scaleX(0.95f).scaleY(0.95f).setDuration(100).start(); android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> view.animate().scaleX(if (view.hasFocus()) 1.15f else 1f).scaleY(if (view.hasFocus()) 1.15f else 1f).setDuration(100).start() }; false }
            }
        }
    }
}
