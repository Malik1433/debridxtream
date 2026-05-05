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
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.TrackSelectionDialogBuilder
import android.graphics.Color
import com.tvonnet.debridxtreamiptv.BuildConfig
import com.tvonnet.debridxtreamiptv.R
import com.tvonnet.debridxtreamiptv.data.model.ContentType
import com.tvonnet.debridxtreamiptv.data.model.ContinueWatchingItem
import com.tvonnet.debridxtreamiptv.data.model.RecentLiveChannelItem
import com.tvonnet.debridxtreamiptv.data.model.toAbsoluteUrl
import com.tvonnet.debridxtreamiptv.data.prefs.CredentialsPreferences
import com.tvonnet.debridxtreamiptv.data.prefs.WatchHistoryPreferences
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
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
class PlayerActivity : AppCompatActivity(), ZappingCallback, PlayerCoreListener {
    private val viewModel: PlayerViewModel by viewModels()

    @Inject
    lateinit var okHttpClient: OkHttpClient

    @Inject
    lateinit var settingsPreferences: SettingsPreferences

    private lateinit var playerCore: PlayerCore

    private val prefs by lazy { CredentialsPreferences(this) }

    private var isInPictureInPictureMode = false
    private var wasPlayingBeforePiP = true

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
    private lateinit var controlsManager: PlayerControlsManager
    private lateinit var overlayManager: PlayerOverlayManager
    private lateinit var zappingEngine: PlayerZappingEngine

    private var layoutDebugOverlay: View? = null
    private var tvDebugInfo: TextView? = null
    private var debugEnabled = false
    private var switchCount = 0
    private var debugListener: Player.Listener? = null
    private val debugOverlayHandler = Handler(Looper.getMainLooper())
    private lateinit var episodeAdapter: PlayerEpisodeAdapter
    private val debugOverlayRunnable = object : Runnable {
        override fun run() {
            if (isFinishing || isDestroyed) return
            val p = playerCore.player
            if (!debugEnabled || p == null) return
            updateDebugOverlay()
            debugOverlayHandler.postDelayed(this, 1000)
        }
    }
    private var nextPromptShownForThisEpisode = false

    private fun validateStateSync(): Boolean {
        return true
    }

    private fun startDebugOverlay() {
        debugOverlayHandler.removeCallbacks(debugOverlayRunnable)
        if (debugEnabled && playerCore.player != null) {
            debugOverlayHandler.post(debugOverlayRunnable)
        }
    }

    private var channelName: String? = null
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
    private var preferredSubtitleLanguage: String? = null
    private var lastRecoveryTime = 0L
    private var hasAppliedIndexOverride = false
    private var preferredAudioLanguage: String? = null
    private val shadowCoordinator = PlaybackStateCoordinator()
    
    @Volatile
    private var isExitingPlayer = false
    private var isPlayerReleased = false

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

        if (!shadowCoordinator.canExecute(ActionType.ACTION_NEW_INTENT)) {
            shadowCoordinator.queueIntent(intent)
            Log.d("COORDINATOR", "INTENT QUEUED")
            return
        }

        handleNewIntent(intent)
    }

    private fun handleNewIntent(intent: Intent) {
        val newUrl = intent.getStringExtra(EXTRA_STREAM_URL)
        val newContentId = intent.getStringExtra(EXTRA_CONTENT_ID) ?: newUrl
        
        if (newContentId == contentId && newUrl == currentUrl) {
            Log.d("PlayerActivity", "onNewIntent ignored: Same content already playing")
            return
        }

        try {
            parseIntentData(intent)
            
            if (playerCore.player != null) {
                zappingEngine.switchTo(currentUrl ?: "")
            } else {
                currentUrl?.let { initializePlayer(it) }
            }
        } catch (e: Exception) {
            Log.e("PlayerActivity", "Error handling new intent", e)
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
        subtitleEntries = intent.getStringArrayListExtra(EXTRA_SUBTITLE_ENTRIES) ?: emptyList()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        Log.d("LIFECYCLE", "onCreate")
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)

        if (BuildConfig.DEBUG) {
            Log.d("PlayerActivity", "onCreate package=$packageName taskId=$taskId component=${intent.component}")
        }

        preferredAudioLanguage = settingsPreferences.getPreferredAudioLanguage()
        preferredSubtitleLanguage = settingsPreferences.getPreferredSubtitleLanguage()
        watchHistoryPrefs = WatchHistoryPreferences(this)

        playerView = findViewById(R.id.player_view)
        layoutDebugOverlay = findViewById(R.id.layout_debug_overlay)
        tvDebugInfo = findViewById(R.id.tv_debug_info)

        overlayManager = PlayerOverlayManager(this, findViewById(android.R.id.content))
        overlayManager.onPlayNextClicked = { playNextEpisode() }
        overlayManager.onCancelNextClicked = { hideNextEpisodePrompt(cancelled = true) }
        
        zappingEngine = PlayerZappingEngine(this)
        
        playerCore = PlayerCore(this, okHttpClient, settingsPreferences, this)
        
        episodeAdapter = PlayerEpisodeAdapter { episode ->
            if (!shadowCoordinator.canExecute(ActionType.SWITCH_CHANNEL)) return@PlayerEpisodeAdapter
            overlayManager.toggleEpisodeOverlay(false)
            playSeriesEpisode(episode)
        }
        
        overlayManager.getEpisodesRecyclerView()?.let { rv ->
            rv.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
            (rv.itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false
            rv.adapter = episodeAdapter
        }

        parseIntentData(intent)
        originalTitle = streamTitle

        if (contentType == ContentType.EPISODE && seriesId == null) {
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

        channelLogoUrl = intent.getStringExtra(EXTRA_CHANNEL_LOGO)
        val channelNameUsed = intent.getStringExtra(EXTRA_CHANNEL_NAME) ?: streamTitle ?: "Playing"
        pendingChannelName = channelNameUsed

        // Overlay setup is now centralized in initializePlayer() via Phase 5 fix
        
        // Phase 4: Sanitize overlays to prevent blocking video
        findViewById<View>(R.id.layout_episode_overlay)?.let { 
            it.isClickable = false
            it.setBackgroundColor(Color.TRANSPARENT)
        }
        findViewById<View>(R.id.view_channel_browser)?.let {
            it.isClickable = false
            it.setBackgroundColor(Color.TRANSPARENT)
        }
        findViewById<View>(R.id.epg_overlay)?.let {
            it.isClickable = false
            it.setBackgroundColor(Color.TRANSPARENT)
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
            
            // VOD attachment moved to centralized initializePlayer()
            
            controlsManager = PlayerControlsManager(this, playerView, playerCore.player) {
                showPlayerSettings()
            }
            controlsManager.setVisibilityListener {
                updateOverlayVisibility()
            }

            playerView.findViewById<View>(R.id.exo_rew)?.setOnClickListener {
                playerCore.player?.seekBack()
            }
            playerView.findViewById<View>(R.id.exo_ffwd)?.setOnClickListener {
                playerCore.player?.seekForward()
            }
            
            playerCore.player?.let { updatePlayPauseVisibility(it.isPlaying) }
            bindModernMetadata(streamTitle)

            val btnNext = playerView.findViewById<View>(R.id.btn_next_episode)
            val btnEpisodes = playerView.findViewById<View>(R.id.btn_episodes_list)
            
            if (contentType == ContentType.SERIES || contentType == ContentType.EPISODE) {
                btnNext?.isVisible = true
                btnNext?.setOnClickListener {
                     playNextEpisode()
                }
                
                btnEpisodes?.isVisible = true
                btnEpisodes?.setOnClickListener {
                    overlayManager.toggleEpisodeOverlay(true)
                    playerView.hideController()
                }
            } else {
                btnNext?.isVisible = false
                btnEpisodes?.isVisible = false
            }

            playerView.findViewById<View>(R.id.btn_aspect_ratio)?.setOnClickListener {
                cycleResizeMode()
            }
            
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

        if (contentType != ContentType.LIVE_TV) {
            playerView.showController()
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (overlayManager.isEpisodeOverlayVisible) {
                    overlayManager.toggleEpisodeOverlay(false)
                    return
                }
                if (viewModel.browserState.value.isVisible) {
                    viewModel.toggleBrowser(false)
                    return
                }
                if (contentType == ContentType.LIVE_TV && (overlayManager.epgOverlayPinned || overlayManager.epgOverlayMode != PlayerOverlayManager.EpgOverlayMode.HIDDEN)) {
                    overlayManager.hideEpgOverlay()
                    return
                }
                if (contentType == ContentType.MOVIE || contentType == ContentType.EPISODE) {
                    if (playerView.isControllerFullyVisible) {
                        playerView.hideController()
                        return
                    }
                }
                if (isExitingPlayer) return
                isExitingPlayer = true
                Log.d("BACK", "Back pressed - exiting safely")
                
                manualExit = true
                updateLastPlaybackPosition()
                lifecycleScope.launch(Dispatchers.IO) {
                    recordPlaybackHistoryIfNeeded()
                }
                releasePlayer()
                finish()
            }
        })
    }

    private fun playUrl(url: String) {
        if (!shadowCoordinator.canExecute(ActionType.SWITCH_CHANNEL)) return
        
        try {
            didPlaybackComplete = false
            startStallMonitor()
            initializePlayer(url)
        } catch (e: Exception) {
            handleInitializationError(e)
        }
    }

    private fun updateDebugOverlay() {
        if (!debugEnabled) return
        val p = playerCore.player ?: return
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

    private data class ParsedSubtitle(val url: String, val language: String?)

    private fun buildMediaItemFromUrl(url: String): MediaItem {
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
        val playerSnapshot = playerCore.player ?: return
        
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
        val playerSnapshot = playerCore.player ?: return
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
        pendingChannelName?.let { bindChannelMeta(it) }
        lastOverlayState?.let { overlayManager.renderEpgOverlay(it) }
    }

    private fun observeOverlayState() {
        lifecycleScope.launch {
            repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                viewModel.overlayState.collect { overlayManager.renderEpgOverlay(it) }
            }
        }
    }

    private fun observeZapState() {
        lifecycleScope.launch {
            repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                viewModel.zapState.collect { state ->
                    if (contentType != ContentType.LIVE_TV) return@collect
                    val number = state?.let { (it.index + 1).coerceAtLeast(1) } ?: 1
                    overlayManager.setChannelNumber(number)
                }
            }
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
                            if (overlayManager.isEpisodeOverlayVisible) {
                                overlayManager.getEpisodesRecyclerView()?.post {
                                    overlayManager.getEpisodesRecyclerView()?.requestFocus()
                                    val index = state.currentIndex
                                    if (index >= 0) overlayManager.getEpisodesRecyclerView()?.scrollToPosition(index)
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
        
        overlayManager.setEpisodesLoading(isLoading)
        
        if (hasData) {
            overlayManager.getEpisodesRecyclerView()?.visibility = View.VISIBLE
            overlayManager.setEpisodesEmpty(false)
        } else if (isLoading) {
            overlayManager.getEpisodesRecyclerView()?.visibility = View.GONE
            overlayManager.setEpisodesEmpty(false)
        } else {
            overlayManager.getEpisodesRecyclerView()?.visibility = View.GONE
            overlayManager.setEpisodesEmpty(overlayManager.isEpisodeOverlayVisible)
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
        
        zappingEngine.switchTo(url)
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
        if (::overlayManager.isInitialized) overlayManager.hideNextEpisodePrompt()

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
        val player = playerCore.player ?: return
        if (!player.isPlaying) return
        
        val duration = player.duration
        val position = player.currentPosition
        if (duration <= 0) return

        val remaining = duration - position
        val percentThresholdMs = (duration * 0.97).toLong() 
        val timeThresholdMs = 45000L 
        
        val showPrompt = position >= percentThresholdMs || remaining < timeThresholdMs
        
        if (showPrompt && !nextPromptShownForThisEpisode && !overlayManager.isNextPromptVisible) {
             val state = viewModel.seriesPlaylistState.value
             if (state?.hasNext == true) {
                 showNextEpisodePrompt(state.originalList[state.currentIndex + 1])
             }
        }
    }

    private fun showNextEpisodePrompt(nextEp: EpisodeEntityV2) {
        overlayManager.showNextEpisodePrompt(nextEp.title ?: "Episode ${nextEp.episodeNumber}", 15)
        nextPromptShownForThisEpisode = true
    }

    private fun hideNextEpisodePrompt(cancelled: Boolean) {
        overlayManager.hideNextEpisodePrompt()
        if (cancelled) {
            nextPromptShownForThisEpisode = true
        }
    }

    private fun bindChannelMeta(channelName: String?) {
        pendingChannelName = channelName
        overlayManager.bindChannelMeta(channelName, channelLogoUrl)
    }

    private fun initializePlayer(streamUrl: String) {
        Log.d("PLAYER", "initializePlayer called with url=$streamUrl")
        if (!shadowCoordinator.canExecute(ActionType.ACTION_PLAYER_OPERATION)) {
            Log.d("COORDINATOR", "PLAYER INIT BLOCKED")
            return
        }
        
        if (playerCore.player != null) {
            releasePlayer()
        }

        // Phase 5: Reset overlays to prevent data mix
        overlayManager.resetAll()
        overlayManager.setContentType(contentType)
        
        // Bind initial data based on type
        when (contentType) {
            ContentType.LIVE_TV -> overlayManager.bindLive(channelName, channelLogoUrl)
            ContentType.MOVIE -> overlayManager.bindVod(streamTitle, posterUrlExtra)
            ContentType.SERIES, ContentType.EPISODE -> overlayManager.bindEpisode(episodeTitleExtra ?: streamTitle)
            else -> {}
        }
        
        playerCore.initializeAndPlay(
            mediaItem = buildMediaItemFromUrl(streamUrl),
            isLive = contentType == ContentType.LIVE_TV,
            contentType = contentType ?: ContentType.LIVE_TV,
            startPositionMs = startPositionMs
        )

        // Phase 4: Bind PlayerView centrally (Fixes Black Screen for Live TV)
        Log.d("PlayerActivity", "Centralized attach: Binding PlayerView to ExoPlayer")
        playerCore.attach(playerView)
        
        // Force Rendering Layer visibility
        playerView.visibility = View.VISIBLE
        playerView.alpha = 1f
        playerView.bringToFront()
        playerView.z = 10f
        playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
        
        playerView.requestLayout()
        playerView.invalidate()

        zappingEngine.setPlayer(playerCore.player as? androidx.media3.exoplayer.ExoPlayer)
        shadowCoordinator.transitionTo(PlaybackState.PREPARING_PLAYER, "PLAYER_INIT")
        startStallMonitor()
    }

    override fun onReady() {
        // No-op, handled in onPlaybackStateChanged
    }

    override fun onError(error: Exception) {
        if (error is PlaybackException) {
            onPlayerError(error)
        } else {
            handleInitializationError(error)
        }
    }

    override fun onBuffering(isBuffering: Boolean) {
        // No-op, handled in onPlaybackStateChanged
    }

    override fun onPlaybackEnded() {
        // No-op, handled in onPlaybackStateChanged
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        if (isExitingPlayer) {
            Log.w("PLAYER_STATE", "Ignored due to exit")
            return
        }
        Log.d("PLAYER_STATE", "state=$playbackState")
        if (playbackState == Player.STATE_READY) {
             shadowCoordinator.transitionTo(PlaybackState.PLAYING, "PLAYER_READY")
             
             val nextIntent = shadowCoordinator.resolveNextIntent()
             if (nextIntent != null) {
                 Log.d("COORDINATOR", "EXECUTING QUEUED INTENT")
                 handleNewIntent(nextIntent)
             }
             
             if (playerCore.player?.currentPosition ?: 0L > 1000) startPositionMs = 0L 
             
             if (::controlsManager.isInitialized) {
                 controlsManager.updatePlayPauseVisibility(playerCore.player?.isPlaying == true)
             }
             
            if (::zappingEngine.isInitialized) {
                 zappingEngine.notifyPlaybackStarted()
            }
            retryCount = 0
            lastBufferingStartMs = 0L
        } else if (playbackState == Player.STATE_BUFFERING) {
            if (lastBufferingStartMs == 0L) lastBufferingStartMs = SystemClock.elapsedRealtime()
        } else if (playbackState == Player.STATE_ENDED || playbackState == Player.STATE_IDLE) {
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
                 if (!overlayManager.isNextPromptVisible) finish()
            }
        }
    }

    private fun onPlayerError(error: PlaybackException) {
        Log.e("PlayerActivity", "Playback error: ${error.errorCodeName}", error)
        shadowCoordinator.transitionTo(PlaybackState.ERROR, "PLAYER_ERROR")
        handlePlaybackError(error)
    }

    override fun onTracksChanged() {
        if (!hasAppliedIndexOverride) {
            playerCore.applyTrackIndexOverrides(debridInfoHashExtra, seriesId ?: tmdbIdExtra ?: imdbIdExtra)
            hasAppliedIndexOverride = true
        }
    }

    private fun handlePlaybackError(error: PlaybackException) {
        if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
            playerCore.player?.seekToDefaultPosition(); playerCore.player?.prepare(); playerCore.player?.playWhenReady = true; return
        }
        val cause = error.cause
        val errorMessage = when (cause) {
            is HttpDataSource.InvalidResponseCodeException -> "HTTP ${cause.responseCode}"
            else -> error.message ?: "Playback error"
        }
        if (retryCount < maxRetries) {
            retryCount++
            
            val isExpiredError = cause is HttpDataSource.InvalidResponseCodeException && 
                                (cause.responseCode == 403 || cause.responseCode == 410)

            if (playbackSource == PlaybackSource.DEBRID && shadowCoordinator.canExecute(ActionType.RESOLVE_DEBRID) && (isExpiredError || retryCount > 1) && (!debridInfoHashExtra.isNullOrBlank() || !debridMagnetExtra.isNullOrBlank())) {
                  val currentTime = SystemClock.elapsedRealtime()
                  if (!isExpiredError && currentTime - lastRecoveryTime < 10000) {
                      Log.w("PlayerActivity", "Debrid re-resolution skipped: Cooldown active (non-expired error)")
                      return
                  }
                  lastRecoveryTime = currentTime
                  
                  android.util.Log.i("PlayerActivity", "Silent Recovery: Detected ${if (isExpiredError) "Expired Link (403/410)" else "Playback Error"}. Triggering re-resolution...")
                  if (playerCore.player != null && playerCore.player!!.currentPosition > 1000L) startPositionMs = playerCore.player!!.currentPosition
                  viewModel.reResolveDebridUrl(debridInfoHashExtra, debridMagnetExtra, seasonNumberExtra, episodeNumberExtra, episodeTitleExtra)
                  return
            }
            showToast("Retrying... ($retryCount/$maxRetries)")
            if (contentType != ContentType.LIVE_TV && playerCore.player != null && playerCore.player!!.currentPosition > 1000L) startPositionMs = playerCore.player!!.currentPosition
            playerCore.release()
            Handler(Looper.getMainLooper()).postDelayed({ currentUrl?.let { initializePlayer(it) } }, retryCount * 1000L)
        } else {
            if (!finishWithReturnToSources(autoPlayNext = true, reason = errorMessage)) showSupportQr()
        }
    }
    
    private fun checkForStall() {
        val p = playerCore.player ?: return
        val now = SystemClock.elapsedRealtime()
        if (p.playWhenReady && p.playbackState == Player.STATE_READY) {
            val isLowRamDevice = DeviceProfile.isLowRamDevice(this)
            val stallThresholdMs = if (isLowRamDevice) LOW_RAM_STALL_THRESHOLD_MS else STALL_THRESHOLD_MS
            val requiredStrikes = if (isLowRamDevice) LOW_RAM_STALL_STRIKES else 1
            val currentPos = p.currentPosition
            if (currentPos > lastBoundPosition) { lastBoundPosition = currentPos; lastProgressCheckMs = now; stallStrikeCount = 0; return }
            if (currentPos > 0 && now - lastProgressCheckMs >= stallThresholdMs) {
                stallStrikeCount++
                if (stallStrikeCount >= requiredStrikes) { 
                    stallStrikeCount = 0
                    if (!shadowCoordinator.canExecute(ActionType.ACTION_STALL_RECOVERY)) return
                    
                    shadowCoordinator.transitionTo(PlaybackState.STALLED, "STALL_MONITOR")
                    handlePlaybackError(PlaybackException(null, null, PlaybackException.ERROR_CODE_REMOTE_ERROR)) 
                }
                lastProgressCheckMs = now
            }
        } else { lastBoundPosition = p.currentPosition; lastProgressCheckMs = now; stallStrikeCount = 0 }
    }

    private fun showSupportQr() {
        releasePlayer()
        overlayManager.showSupportQr(settingsPreferences.getSupportUrl())
        showToast("Playback Failed. Scan to contact support.")
    }

    private fun handleInitializationError(error: Exception) {
        if (!finishWithReturnToSources(autoPlayNext = true, reason = "Init failed: ${error.message}")) {
            showError("Failed to initialize player: ${error.message}")
            Handler(Looper.getMainLooper()).postDelayed({ finish() }, 3000)
        }
    }

    private fun updateLastPlaybackPosition() {
        val p = playerCore.player ?: return
        lastPlaybackPositionMs = p.currentPosition
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

    override fun onResume() { 
        Log.d("LIFECYCLE", "onResume")
        super.onResume()
        startDebugOverlay()
        if (playerCore.player == null) {
            currentUrl?.let { initializePlayer(it) }
        } else {
            startStallMonitor()
        }
    }

    private fun registerNetworkCallback() {
        if (connectivityManager == null) return
        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: android.net.Network) {
                runOnUiThread {
                    networkAvailable = true
                    if (shadowCoordinator.currentState.value == PlaybackState.ERROR && SystemClock.elapsedRealtime() - lastRecoveryTime > NETWORK_RECOVERY_BUFFER_MS) {
                        lastRecoveryTime = SystemClock.elapsedRealtime()
                        currentUrl?.let { initializePlayer(it) }
                    }
                }
            }
            override fun onLost(network: android.net.Network) {
                runOnUiThread { networkAvailable = false }
            }
        }
        try {
            connectivityManager?.registerDefaultNetworkCallback(networkCallback!!)
        } catch (e: Exception) {
            Log.e("PlayerActivity", "Failed to register network callback", e)
        }
    }

    private fun unregisterNetworkCallback() {
        networkCallback?.let {
            try {
                connectivityManager?.unregisterNetworkCallback(it)
            } catch (e: Exception) {
                Log.e("PlayerActivity", "Failed to unregister network callback", e)
            }
        }
        networkCallback = null
    }

    override fun onStart() { Log.d("LIFECYCLE", "onStart"); super.onStart(); registerNetworkCallback() }
    override fun onPause() { super.onPause(); playerCore.player?.pause(); stopStallMonitor() }
    override fun onStop() { 
        Log.d("LIFECYCLE", "onStop")
        super.onStop()
        if (!isInPictureInPictureMode) {
            lifecycleScope.launch(Dispatchers.IO) {
                recordPlaybackHistoryIfNeeded()
            }
            releasePlayer()
        }
    }

    private fun releasePlayer() {
        if (isPlayerReleased) {
            Log.w("RELEASE", "Already released, skipping")
            return
        }

        isPlayerReleased = true
        isExitingPlayer = true
        zappingEngine.isExiting = true

        Log.d("RELEASE", "releasePlayer safe execution")
        shadowCoordinator.transitionTo(PlaybackState.IDLE, "PLAYER_RELEASE")
        updateLastPlaybackPosition()
        stopStallMonitor()
        zappingEngine.setPlayer(null)
        playerCore.release()
    }

    private fun startStallMonitor() {
        lastBoundPosition = playerCore.player?.currentPosition ?: 0L
        lastProgressCheckMs = SystemClock.elapsedRealtime()
        stallStrikeCount = 0
        stallHandler.removeCallbacks(stallRunnable)
        stallHandler.postDelayed(stallRunnable, 5000)
    }

    private fun stopStallMonitor() = stallHandler.removeCallbacks(stallRunnable)

    override fun onDestroy() { 
        Log.d("LIFECYCLE", "onDestroy")
        super.onDestroy()
        lifecycleScope.launch(Dispatchers.IO) {
            recordPlaybackHistoryIfNeeded()
        }
        releasePlayer()
        overlayManager.release()
        zappingEngine.release()
        debugOverlayHandler.removeCallbacksAndMessages(null)
        stallHandler.removeCallbacksAndMessages(null)
    }

    private fun resolveTimeoutMsForUrl(url: String): Long = if (url.lowercase().contains("mediafusion.elfhosted.com")) MEDIAFUSION_TIMEOUT_MS else TIMEOUT_MS

    override fun finish() { setExitResultIfNeeded(); super.finish() }

    private lateinit var browserCategoryAdapter: BrowserCategoryAdapter
    private lateinit var browserChannelAdapter: BrowserChannelAdapter

    private fun initBrowserOverlay() {
        browserCategoryAdapter = BrowserCategoryAdapter { category -> category.category_id?.let { viewModel.selectBrowserCategory(it) } }
        browserChannelAdapter = BrowserChannelAdapter { stream ->
            stream.stream_id?.let { id ->
                val serverUrl = baseServerUrl ?: prefs.getServerUrl() ?: ""
                val newUrl = "$serverUrl/live/${prefs.getUsername()}/${prefs.getPassword()}/$id.ts"
                zappingEngine.switchTo(newUrl)
                contentId = id; currentUrl = newUrl; channelLogoUrl = com.tvonnet.debridxtreamiptv.util.GlobalConfig.resolveIconUrl(stream.stream_icon); currentEpgChannelId = stream.epg_channel_id ?: id
                bindChannelMeta(stream.name); supportActionBar?.title = stream.name
                viewModel.initLiveZapping(viewModel.browserState.value.selectedCategoryId, id, serverUrl, null)
                viewModel.observeEpg(stream.epg_channel_id ?: id, id)
                viewModel.toggleBrowser(false)
            }
        }

        overlayManager.initBrowserOverlay(
            viewModel = viewModel,
            lifecycleScope = lifecycleScope,
            browserCategoryAdapter = browserCategoryAdapter,
            browserChannelAdapter = browserChannelAdapter,
            onChannelFocus = { index ->
                 if (contentId != null) {
                     val chanIndex = viewModel.browserState.value.channels.indexOfFirst { it.stream_id == contentId }
                     if (chanIndex != -1) {
                         overlayManager.requestBrowserChannelFocus(chanIndex)
                     }
                 }
            }
        )
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            if (overlayManager.isEpisodeOverlayVisible) {
                if (event.keyCode == KeyEvent.KEYCODE_BACK || event.keyCode == KeyEvent.KEYCODE_ESCAPE) {
                    overlayManager.toggleEpisodeOverlay(false)
                    return true
                }
            }

            if (event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN || event.keyCode == KeyEvent.KEYCODE_MENU) {
                    if (contentType == ContentType.SERIES || contentType == ContentType.EPISODE) {
                        val isCtrlVisible = if (::controlsManager.isInitialized) controlsManager.isControllerVisible else false
                        if (!overlayManager.isEpisodeOverlayVisible && !isCtrlVisible) {
                            overlayManager.toggleEpisodeOverlay(true)
                            return true
                        }
                    }
                }

            if (contentType != ContentType.LIVE_TV && playerView.useController && !overlayManager.isNextPromptVisible && !playerView.isControllerFullyVisible && !overlayManager.isEpisodeOverlayVisible) {
                when (event.keyCode) { KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, KeyEvent.KEYCODE_MEDIA_REWIND, KeyEvent.KEYCODE_MENU -> { showController(); playerView.requestFocus(); return true } }
            }
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> { if (contentType == ContentType.LIVE_TV && !viewModel.browserState.value.isVisible) { viewModel.toggleBrowser(true, viewModel.zapState.value?.categoryId ?: liveCategoryId, contentId); return true } }
                KeyEvent.KEYCODE_CAPTIONS -> { if (contentType != ContentType.LIVE_TV) { showSubtitleSelection(); return true } }
                KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_PAGE_UP -> { if (contentType == ContentType.LIVE_TV && !viewModel.browserState.value.isVisible) { zappingEngine.zap(+1); return true } }
                KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.KEYCODE_PAGE_DOWN -> { if (contentType == ContentType.LIVE_TV && !viewModel.browserState.value.isVisible) { zappingEngine.zap(-1); return true } }
                KeyEvent.KEYCODE_DPAD_UP -> { if (contentType == ContentType.LIVE_TV && !viewModel.browserState.value.isVisible) { zappingEngine.zap(+1); return true } }
                KeyEvent.KEYCODE_DPAD_DOWN -> { if (contentType == ContentType.LIVE_TV && !viewModel.browserState.value.isVisible) { zappingEngine.zap(-1); return true } }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> { if (contentType == ContentType.LIVE_TV) { if (viewModel.browserState.value.isVisible) return super.dispatchKeyEvent(event); if (overlayManager.epgOverlayMode != PlayerOverlayManager.EpgOverlayMode.HIDDEN && !overlayManager.epgOverlayPinned) overlayManager.hideEpgOverlay() else overlayManager.showEpgOverlay(PlayerOverlayManager.EpgOverlayMode.COMPACT, pinned = false); return true } }
                KeyEvent.KEYCODE_INFO -> { if (contentType == ContentType.LIVE_TV) overlayManager.toggleEpgOverlayPinned() else { showController(); playerView.requestFocus() }; return true }
                KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_GUIDE -> {
                    if (event.keyCode == KeyEvent.KEYCODE_MENU) {
                        debugEnabled = !debugEnabled
                        layoutDebugOverlay?.visibility = if (debugEnabled) View.VISIBLE else View.GONE
                        startDebugOverlay()
                    }
                    if (contentType == ContentType.LIVE_TV) { overlayManager.toggleEpgOverlayPinned(); return true }; if (playerView.isControllerFullyVisible) { showSubtitleSelection(); return true } 
                }
                KeyEvent.KEYCODE_BACK -> { if (viewModel.browserState.value.isVisible) { viewModel.toggleBrowser(false); return true }; if (contentType == ContentType.LIVE_TV && overlayManager.epgOverlayPinned && overlayManager.epgOverlayMode != PlayerOverlayManager.EpgOverlayMode.HIDDEN) { overlayManager.hideEpgOverlay(); return true }; if (event.repeatCount > 0 && supportsPictureInPicture()) { enterPictureInPictureModeInternal(); return true } }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onKeyLongPress(keyCode: Int, event: KeyEvent): Boolean {
        if (contentType == ContentType.LIVE_TV) { when (keyCode) { KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> { overlayManager.toggleEpgOverlayPinned(); return true } } }
        return super.onKeyLongPress(keyCode, event)
    }

    override fun onUserInteraction() { super.onUserInteraction(); if (contentType == ContentType.LIVE_TV || isInPictureInPictureMode || !playerView.useController || overlayManager.isNextPromptVisible) return; if (!playerView.isControllerFullyVisible) { showController(); playerView.requestFocus() } }

    private fun supportsPictureInPicture(): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE) || packageManager.hasSystemFeature("android.software.picture_in_picture") else false

    @RequiresApi(Build.VERSION_CODES.O)
    private fun enterPictureInPictureModeInternal() {
        if (!supportsPictureInPicture() || isInPictureInPictureMode) return
        try {
            playerCore.player?.let { wasPlayingBeforePiP = it.playWhenReady }
            val params = PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).setSourceRectHint(Rect(0, 0, playerView.width, playerView.height)).build()
            if (enterPictureInPictureMode(params)) hideUiForPiP() else showToast("PiP not available")
        } catch (e: Exception) { showToast("PiP failed") }
    }

    private fun hideUiForPiP() { 
        playerView.hideController()
        overlayManager.hideEpgOverlay()
        supportActionBar?.hide()
        lifecycleScope.launch(Dispatchers.IO) {
            recordPlaybackHistoryIfNeeded()
        }
    }
    private fun showUiForNormalMode() { if (!isInPictureInPictureMode) { supportActionBar?.show(); if (contentType == ContentType.LIVE_TV) updateOverlayVisibility() } }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: android.content.res.Configuration) { super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig); this.isInPictureInPictureMode = isInPictureInPictureMode; if (isInPictureInPictureMode) hideUiForPiP() else showUiForNormalMode() }

    private fun updateOverlayVisibility() { 
        val isCtrlVisible = if (::controlsManager.isInitialized) controlsManager.isControllerVisible else false
        val shouldShow = when (contentType) { ContentType.LIVE_TV -> overlayManager.epgOverlayMode != PlayerOverlayManager.EpgOverlayMode.HIDDEN && !isInPictureInPictureMode; else -> isCtrlVisible && !isInPictureInPictureMode }; overlayManager.updateEpgVisibility(shouldShow) 
    }
    private fun showController() {
        if (::controlsManager.isInitialized) {
            controlsManager.showController()
        } else {
            playerView.showController()
        }
    }

    private fun hideController() {
        if (::controlsManager.isInitialized) {
            controlsManager.hideController()
        } else {
            playerView.hideController()
        }
    }

    private fun toggleEpisodeOverlay(visible: Boolean) { overlayManager.toggleEpisodeOverlay(visible) }
    private fun showEpgOverlay(mode: PlayerOverlayManager.EpgOverlayMode, pinned: Boolean) { if (contentType == ContentType.LIVE_TV) overlayManager.showEpgOverlay(mode, pinned) }
    private fun hideEpgOverlay() { overlayManager.hideEpgOverlay() }
    private fun toggleEpgOverlayPinned() { overlayManager.toggleEpgOverlayPinned() }

    private fun formatTimeRangeCompact(program: com.tvonnet.debridxtreamiptv.data.local.entity.EpgEntity): String = "${timeFormatter.format(Date(program.start))}-${timeFormatter.format(Date(program.stop))}"

    private fun recordPlaybackHistoryIfNeeded() { if (hasRecordedHistory) return; val type = contentType ?: return; val id = contentId ?: currentUrl ?: return; when (type) { ContentType.LIVE_TV -> recordLiveHistory(id); ContentType.MOVIE, ContentType.SERIES, ContentType.EPISODE -> recordContinueWatchingHistory(id, type) }; hasRecordedHistory = true }
    private fun recordLiveHistory(channelId: String) { watchHistoryPrefs.addRecentLiveChannel(RecentLiveChannelItem(channelId, pendingChannelName ?: originalTitle ?: getString(R.string.player_epg_channel_unknown), (channelLogoUrl ?: posterUrlExtra).toAbsoluteUrl(ContentType.LIVE_TV, baseServerUrl), System.currentTimeMillis(), currentUrl ?: "", currentEpgChannelId)) }
    private fun recordContinueWatchingHistory(contentId: String, type: ContentType) { 
        val p = playerCore.player ?: return
        val dur = p.duration
        if (dur <= 0 || dur == C.TIME_UNSET || dur < MIN_DURATION_TO_TRACK_MS) return
        val pos = p.currentPosition
        if (pos < MIN_PROGRESS_TO_TRACK_MS) return
        val isWatched = (pos / dur.toFloat()) >= COMPLETION_THRESHOLD_RATIO
        if (type == ContentType.EPISODE) viewModel.updatePlaybackStatus(contentId, isWatched, if (isWatched) 0 else pos, dur)
        if (isWatched) { 
            watchHistoryPrefs.removeContinueWatchingItem(resolveContinueWatchingId(type, contentId))
            return 
        }
        watchHistoryPrefs.saveContinueWatchingItem(ContinueWatchingItem(resolveContinueWatchingId(type, contentId), type, if (type == ContentType.EPISODE) seriesTitleExtra ?: originalTitle ?: pendingChannelName ?: getString(R.string.player_epg_channel_unknown) else originalTitle ?: pendingChannelName ?: getString(R.string.player_epg_channel_unknown), (posterUrlExtra ?: channelLogoUrl).toAbsoluteUrl(type, baseServerUrl), backdropUrlExtra.toAbsoluteUrl(type, baseServerUrl), pos, dur, System.currentTimeMillis(), currentUrl, tmdbIdExtra, imdbIdExtra, seriesTitleExtra, episodeTitleExtra, seasonNumberExtra, episodeNumberExtra, debridInfoHashExtra, debridMagnetExtra, if (playbackSource == PlaybackSource.DEBRID) "debrid" else "xtream", expiresAtExtra)) 
    }
    private fun resolveContinueWatchingId(type: ContentType, fallbackId: String): String = if (playbackSource != PlaybackSource.DEBRID) fallbackId else tmdbIdExtra?.let { if (type == ContentType.EPISODE && seasonNumberExtra != null && episodeNumberExtra != null) "$it:S${seasonNumberExtra}E${episodeNumberExtra}" else it } ?: debridInfoHashExtra ?: fallbackId
    private fun setExitResultIfNeeded() { if (exitResultHandled || !returnToSourcesOnExit || playbackSource != PlaybackSource.DEBRID || didPlaybackComplete) return; val pos = playerCore.player?.currentPosition ?: lastPlaybackPositionMs; if (manualExit || pos < RETURN_TO_SOURCES_THRESHOLD_MS) { exitResultHandled = true; setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_RETURN_TO_SOURCES, true)) } }
    private fun finishWithReturnToSources(autoPlayNext: Boolean, reason: String?): Boolean { if (!returnToSourcesOnExit || playbackSource != PlaybackSource.DEBRID || exitResultHandled) return false; exitResultHandled = true; setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_RETURN_TO_SOURCES, true).putExtra(EXTRA_FAILED_STREAM_ID, contentId ?: debridInfoHashExtra ?: currentUrl).putExtra(EXTRA_FAIL_REASON, reason).putExtra(EXTRA_AUTO_PLAY_NEXT, autoPlayNext)); releasePlayer(); finish(); return true }
    private fun updatePlayPauseVisibility(isPlaying: Boolean) {
        if (::controlsManager.isInitialized) {
            controlsManager.updatePlayPauseVisibility(isPlaying)
        }
    }

    private fun observeDebridResolutionState() {
        lifecycleScope.launch {
            viewModel.debridResolutionState.collect { state ->
                if (isExitingPlayer || isFinishing || isDestroyed) {
                    Log.w("DEBRID", "Ignored due to exiting state")
                    return@collect
                }
                when (state) {
                    is DebridResolutionState.Loading -> { 
                        Log.d("DEBRID", "state=Loading")
                        overlayManager.setDebridResolving(true, "Resolving source via Debrid...")
                        shadowCoordinator.transitionTo(PlaybackState.RESOLVING_SOURCE, "DEBRID_RESOLVE")
                    }
                    is DebridResolutionState.Success -> {
                        Log.d("DEBRID", "state=Success SUCCESS url=${state.url}")
                        overlayManager.setDebridResolving(false)
                        
                        if (!shadowCoordinator.canExecute(ActionType.ACTION_RESOLUTION_COMPLETE)) {
                            // Fetch current intent if possible, or just log
                            shadowCoordinator.queueIntent(intent)
                            Log.d("COORDINATOR", "DEBRID QUEUED")
                            return@collect
                        }
                        
                        // Metadata Anchor Sync: Update expiresAtExtra with fresh timestamp from resolver
                        state.expiresAt?.let { expiresAtExtra = it }
                        
                        if (state.season != null && state.episode != null) {
                            val isSame = seasonNumberExtra == state.season && episodeNumberExtra == state.episode
                            seasonNumberExtra = state.season; episodeNumberExtra = state.episode; episodeTitleExtra = state.title
                            if (!isSame) startPositionMs = 0L
                            bindModernMetadata("${seriesTitleExtra ?: ""} - S${state.season}:E${state.episode}")
                            if (state.infoHash != null) debridInfoHashExtra = state.infoHash
                            hasRecordedHistory = false; overlayManager.hideNextEpisodePrompt(); nextPromptShownForThisEpisode = false
                        }
                        currentUrl = state.url; zappingEngine.switchTo(state.url)
                    }
                    is DebridResolutionState.Error -> {
                        overlayManager.setDebridResolving(false)
                        android.util.Log.e("PlayerActivity", "Debrid re-resolution FAILED permanently: ${state.message}")
                        // BUG FIX: Previously this only showed a Toast, leaving the player frozen
                        // on a black screen in STATE_BUFFERING with no way to recover.
                        // Now we return to sources (autoPlayNext=true picks the next source
                        // automatically), or fall back to support QR if no sources remain.
                        if (!finishWithReturnToSources(autoPlayNext = true, reason = state.message)) {
                            showSupportQr()
                        }
                    }
                    is DebridResolutionState.Idle -> { overlayManager.setDebridResolving(false) }
                }
            }
        }
    }

    private fun captureManualTrackSelection() {
        val p = playerCore.player ?: return
        val groups = p.currentTracks.groups
        var sAIdx = -1; var sALang: String? = null
        var sTIdx = -1; var sTLang: String? = null
        
        var aC = 0
        for (g in groups) {
            if (g.type == C.TRACK_TYPE_AUDIO) {
                for (j in 0 until g.length) {
                    if (g.isTrackSelected(j)) {
                        sAIdx = aC
                        sALang = g.getTrackFormat(j).language
                        break
                    }
                    aC++
                }
            }
            if (sAIdx != -1) break
        }
        
        var tC = 0
        for (g in groups) {
            if (g.type == C.TRACK_TYPE_TEXT) {
                for (j in 0 until g.length) {
                    if (g.isTrackSelected(j)) {
                        sTIdx = tC
                        sTLang = g.getTrackFormat(j).language
                        break
                    }
                    tC++
                }
            }
            if (sTIdx != -1) break
        }
        
        if (sALang != null) { preferredAudioLanguage = sALang; settingsPreferences.savePreferredAudioLanguage(sALang) }
        if (sTLang != null) { preferredSubtitleLanguage = sTLang; settingsPreferences.savePreferredSubtitleLanguage(sTLang) }
        settingsPreferences.saveLastTrackSelection(debridInfoHashExtra, sAIdx, sTIdx)
        (intent.getStringExtra(EXTRA_SERIES_ID) ?: tmdbIdExtra ?: imdbIdExtra)?.let { settingsPreferences.saveSeriesTrackPreference(it, sAIdx, sTIdx) }
    }



    // Redundant setupInteractiveAnimations moved to PlayerControlsManager
    // ZappingCallback Implementation
    override fun onSwitchStart(url: String) {
        switchCount++
        startStallMonitor()
        retryCount = 0
    }

    override fun onSwitchComplete() {
        // Any specific UI cleanup for switch completion
    }

    override fun onSwitchError(message: String) {
        showToast("Switch failed: $message")
        shadowCoordinator.transitionTo(PlaybackState.ERROR, "SWITCH_TIMEOUT")
        if (!finishWithReturnToSources(autoPlayNext = true, reason = message)) {
            showSupportQr()
        }
    }

    override fun buildMediaItem(url: String): MediaItem {
        return buildMediaItemFromUrl(url)
    }

    override fun resolveTimeoutMs(url: String): Long {
        return resolveTimeoutMsForUrl(url)
    }

    override fun onStateTransition(state: PlaybackState, reason: String) {
        shadowCoordinator.transitionTo(state, reason)
    }

    override fun canExecuteSwitch(): Boolean {
        return shadowCoordinator.canExecute(ActionType.SWITCH_CHANNEL)
    }

    override fun onZapRequest(direction: Int): ZapChannel? {
        val target = viewModel.moveZap(direction) ?: run {
            val isReady = viewModel.zapState.value?.channels?.isNotEmpty() == true
            showToast(
                if (isReady) getString(R.string.player_zap_unavailable)
                else getString(R.string.player_zap_loading)
            )
            return null
        }

        // UI Side Effects
        contentId = target.streamId
        currentUrl = target.streamUrl
        channelLogoUrl = target.logoUrl
        bindChannelMeta(target.name)
        supportActionBar?.title = target.name

        val epgKey = target.epgChannelId?.takeIf { it.isNotBlank() } ?: target.streamId
        currentEpgChannelId = epgKey
        viewModel.observeEpg(epgKey, target.streamId)

        val keepPinned = overlayManager.epgOverlayPinned && overlayManager.epgOverlayMode != PlayerOverlayManager.EpgOverlayMode.HIDDEN
        overlayManager.showEpgOverlay(mode = PlayerOverlayManager.EpgOverlayMode.COMPACT, pinned = keepPinned)

        return target
    }
}
