package com.tvonnet.debridxtreamiptv.player

import android.app.Activity
import android.app.ActivityManager
import android.app.PictureInPictureParams
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.Rect
import android.net.ConnectivityManager
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
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import com.tvonnet.debridxtreamiptv.BuildConfig
import com.tvonnet.debridxtreamiptv.R
import com.tvonnet.debridxtreamiptv.data.model.ContentType
import com.tvonnet.debridxtreamiptv.data.model.ContinueWatchingItem
import com.tvonnet.debridxtreamiptv.data.model.RecentLiveChannelItem
import com.tvonnet.debridxtreamiptv.data.prefs.CredentialsPreferences
import com.tvonnet.debridxtreamiptv.data.prefs.WatchHistoryPreferences
import kotlinx.coroutines.launch
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import okhttp3.OkHttpClient
import com.tvonnet.debridxtreamiptv.data.prefs.SettingsPreferences
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.TsExtractor
import java.text.SimpleDateFormat
import java.util.*
import androidx.core.view.isVisible
import android.os.CountDownTimer
import com.tvonnet.debridxtreamiptv.features.seriesv2.data.model.EpisodeEntityV2
import com.tvonnet.debridxtreamiptv.player.SeriesPlaylistState

@AndroidEntryPoint
class PlayerActivity : AppCompatActivity() {
    private val viewModel: PlayerViewModel by viewModels()

    @Inject
    lateinit var okHttpClient: OkHttpClient

    @Inject
    lateinit var settingsPreferences: SettingsPreferences

    private val prefs by lazy { CredentialsPreferences(this) }



    // Phase 3.2: Picture-in-Picture (PiP) Support
    private var isInPictureInPictureMode = false
    private var wasPlayingBeforePiP = true

    private var player: ExoPlayer? = null
    private lateinit var playerView: PlayerView
    private lateinit var watchHistoryPrefs: WatchHistoryPreferences

    private var retryCount = 0
    private val maxRetries = 5 // Phase 3: 5-Strike Rule
    private var currentUrl: String? = null
    private var streamHeaders: Map<String, String>? = null
    private var timeoutMs: Long = TIMEOUT_MS
    private val timeoutHandler = Handler(Looper.getMainLooper())
    private val timeoutRunnable = Runnable { handleTimeout() }
    private var isControllerVisible = false

    private enum class EpgOverlayMode { HIDDEN, COMPACT, EXPANDED }
    private val overlayHandler = Handler(Looper.getMainLooper())
    private val overlayHideRunnable = Runnable { maybeAutoHideOverlay() }
    private var epgOverlayMode: EpgOverlayMode = EpgOverlayMode.HIDDEN
    private var epgOverlayPinned: Boolean = false

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

    // Phase 3: Support QR
    private var layoutSupportQr: View? = null
    private var imgSupportQr: ImageView? = null
    private val stallHandler = Handler(Looper.getMainLooper())
    private var lastBoundPosition = 0L
    private var lastProgressCheckMs = 0L
    private var lastBufferingStartMs = 0L
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
        private const val TIMEOUT_MS = 15000L
        private const val MEDIAFUSION_TIMEOUT_MS = 35000L
        private const val OVERLAY_TIMEOUT = 6000L
        private const val MIN_PROGRESS_TO_TRACK_MS = 15_000L
        private const val MIN_DURATION_TO_TRACK_MS = 60_000L
        private const val RETURN_TO_SOURCES_THRESHOLD_MS = 60_000L
        private const val COMPLETION_THRESHOLD_RATIO = 0.95f
        private const val STALL_THRESHOLD_MS = 8000L
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
        const val EXTRA_RETURN_TO_SOURCES = "EXTRA_RETURN_TO_SOURCES"
        const val EXTRA_FAILED_STREAM_ID = "EXTRA_FAILED_STREAM_ID"
        const val EXTRA_FAIL_REASON = "EXTRA_FAIL_REASON"
        const val EXTRA_AUTO_PLAY_NEXT = "EXTRA_AUTO_PLAY_NEXT"

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
            debridMagnet: String? = null
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
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)

        if (BuildConfig.DEBUG) {
            Log.d("PlayerActivity", "onCreate package=$packageName taskId=$taskId component=${intent.component}")
        }

        watchHistoryPrefs = WatchHistoryPreferences(this)

        playerView = findViewById(R.id.player_view)

        val contentTypeString = intent.getStringExtra(EXTRA_CONTENT_TYPE)
        contentType = contentTypeString?.let { runCatching { ContentType.valueOf(it) }.getOrNull() }
        playbackSource = intent.getStringExtra(EXTRA_PLAYBACK_SOURCE)
            ?.let { runCatching { PlaybackSource.valueOf(it) }.getOrNull() }
            ?: PlaybackSource.IPTV
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

        val streamUrl = intent.getStringExtra(EXTRA_STREAM_URL)
        val streamTitle = intent.getStringExtra(EXTRA_STREAM_TITLE)
        originalTitle = streamTitle
        streamHeaders = readStreamHeaders(intent)

        if (streamUrl.isNullOrBlank()) {
            showError("Invalid stream URL")
            finish()
            return
        }

        timeoutMs = resolveTimeoutMs(streamUrl)
        currentUrl = streamUrl
        channelLogoUrl = intent.getStringExtra(EXTRA_CHANNEL_LOGO)
        startPositionMs = intent.getLongExtra(EXTRA_START_POSITION, 0L)
        val channelName = intent.getStringExtra(EXTRA_CHANNEL_NAME) ?: streamTitle
        pendingChannelName = channelName

        if (contentType == ContentType.LIVE_TV) {
            setupOverlayViews()
            bindChannelMeta(channelName)
        } else {
            setupVodOverlayViews()
            bindVodMeta(streamTitle)
        }

        liveCategoryId = intent.getStringExtra(EXTRA_LIVE_CATEGORY_ID)
        baseServerUrl = intent.getStringExtra(EXTRA_BASE_SERVER_URL)
        liveChannelIds = intent.getStringArrayListExtra(EXTRA_LIVE_CHANNEL_IDS)
        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

        // Phase 5: Series Playlist Init
        val seriesId = intent.getStringExtra(EXTRA_SERIES_ID)
        val seasonNum = intent.getIntExtra(EXTRA_SEASON_NUM, -1)
        if (seriesId != null && seasonNum != -1 && contentId != null) {
            viewModel.loadSeriesPlaylist(seriesId, seasonNum, contentId!!)
        }

        if (contentType == ContentType.LIVE_TV) {
            // IPTV best-practice: avoid the default fullscreen controller overlay (dims the whole screen and
            // conflicts with DPAD zapping). Use our own XCloud-style info bar instead.
            playerView.useController = false

            val epgChannelId = currentEpgChannelId
            val streamIdForEpg = contentId?.takeIf { it.toLongOrNull() != null }
            viewModel.observeEpg(epgChannelId, streamIdForEpg)
            observeOverlayState()
            observeZapState()
        } else {
            playerView.useController = true
            playerView.setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { visibility ->
                isControllerVisible = visibility == View.VISIBLE
                updateVodOverlayVisibility()
            })
            playerView.setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { visibility ->
                isControllerVisible = visibility == View.VISIBLE
                updateVodOverlayVisibility()
            })
            setupNextEpisodeViews()
            observeSeriesPlaylistState()
        }

        initializePlayer(streamUrl)
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

        // Ensure something user-friendly appears immediately in fullscreen for Live TV.
        if (contentType == ContentType.LIVE_TV) {
            // Seed a syncing state locally so overlay can render before Flow collection starts.
            if (lastOverlayState == null) {
                lastOverlayState = PlayerOverlayUiState(epgAvailable = false, isSyncing = true, errorMessage = null)
            }
            showEpgOverlay(EpgOverlayMode.COMPACT, pinned = false)
        } else {
            playerView.showController()
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // Priority 1: Close Browser if open
                if (viewModel.browserState.value.isVisible) {
                    viewModel.toggleBrowser(false)
                    return
                }

                // Priority 2: Close EPG Overlay if pinned/visible (Live TV)
                if (contentType == ContentType.LIVE_TV && (epgOverlayPinned || epgOverlayMode != EpgOverlayMode.HIDDEN)) {
                    hideEpgOverlay()
                    return
                }
                
                // Priority 3: Hide Player Controls if visible (VOD)
                if (contentType == ContentType.MOVIE || contentType == ContentType.EPISODE) {
                    if (playerView.isControllerFullyVisible) {
                        playerView.hideController()
                        return
                    }
                }

                // Priority 4: Standard Exit
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

        // Update meta immediately (best-practice: optimistic UI while stream buffers).
        contentId = target.streamId
        currentUrl = target.streamUrl
        channelLogoUrl = target.logoUrl
        bindChannelMeta(target.name)
        supportActionBar?.title = target.name

        // Refresh EPG overlay for the new channel.
        val epgKey = target.epgChannelId?.takeIf { it.isNotBlank() } ?: target.streamId
        currentEpgChannelId = epgKey
        viewModel.observeEpg(epgKey, target.streamId)

        // Keep the info bar visible if the user pinned it.
        val keepPinned = epgOverlayPinned && epgOverlayMode != EpgOverlayMode.HIDDEN
        showEpgOverlay(mode = EpgOverlayMode.COMPACT, pinned = keepPinned)

        // Fast switch without recreating the Activity or Player.
        playUrl(target.streamUrl)
    }

    private fun playUrl(url: String) {
        try {
            didPlaybackComplete = false
            timeoutHandler.removeCallbacks(timeoutRunnable)
            timeoutMs = resolveTimeoutMs(url)
            timeoutHandler.postDelayed(timeoutRunnable, timeoutMs)

            // Phase 3: Start Stall Monitor
            startStallMonitor()

            retryCount = 0
            val mediaItem = MediaItem.fromUri(url)
            player?.setMediaItem(mediaItem, /* resetPosition = */ true)
            player?.prepare()
            player?.playWhenReady = true
        } catch (e: Exception) {
            timeoutHandler.removeCallbacks(timeoutRunnable)
            handleInitializationError(e)
        }
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

    private fun setupVodOverlayViews() {
        if (vodInfoOverlay != null) return
        vodInfoOverlay = findViewById(R.id.vod_info_overlay)
        imgVodArtwork = findViewById(R.id.img_vod_artwork)
        tvVodTitle = findViewById(R.id.tv_vod_title)
        tvVodSubtitle = findViewById(R.id.tv_vod_subtitle)
    }

    private fun bindVodMeta(title: String?) {
        tvVodTitle?.text = title?.takeIf { it.isNotBlank() } ?: getString(R.string.app_name)

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
        tvVodSubtitle?.text = "$contentLabel • $sourceLabel"

        val artworkUrl = backdropUrlExtra?.takeIf { it.isNotBlank() }
            ?: posterUrlExtra?.takeIf { it.isNotBlank() }
        if (!artworkUrl.isNullOrBlank() && imgVodArtwork != null) {
            Glide.with(this)
                .load(artworkUrl)
                .placeholder(R.drawable.tv_card_placeholder)
                .error(R.drawable.tv_card_placeholder)
                .into(imgVodArtwork!!)
        }
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

    // Phase 5: Next Episode Logic
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
        val progressCountdown = overlay.findViewById<ProgressBar>(R.id.progress_countdown)

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
                viewModel.seriesPlaylistState.collect { state ->
                    if (state == null) return@collect
                    // If episode changed and we are not playing it, switch
                    val currentEpId = contentId
                    if (currentEpId != state.currentEpisode.episodeId) {
                         // Switch!
                         playSeriesEpisode(state.currentEpisode)
                    }
                }
            }
        }
    }

    private fun playSeriesEpisode(episode: EpisodeEntityV2) {
        val url = viewModel.getSeriesStreamUrl(episode)
        
        // Update Metadata
        contentId = episode.episodeId
        currentUrl = url
        val title = episode.title ?: "Episode ${episode.episodeNumber}"
        originalTitle = title
        tvVodTitle?.text = title
        supportActionBar?.title = title
        
        // Reset Prompt State
        hideNextEpisodePrompt(cancelled = false)
        nextPromptShownForThisEpisode = false
        
        // Play
        playUrl(url)
    }

    private fun playNextEpisode() {
        nextEpisodeTimer?.cancel()
        viewModel.getNextEpisode() // Updates state -> triggers observer -> calls playSeriesEpisode
    }

    private fun checkNextEpisodePrompt() {
        if (contentType != ContentType.SERIES && contentType != ContentType.EPISODE) return
        val player = player ?: return
        if (!player.isPlaying) return
        
        val duration = player.duration
        val position = player.currentPosition
        if (duration <= 0) return

        val remaining = duration - position
        // Threshold: 97% completion OR 45s remaining (whichever comes first)
        val percentThresholdMs = (duration * 0.97).toLong() // 97% mark
        val timeThresholdMs = 45000L // 45s remaining
        
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
        nextPromptShownForThisEpisode = true // Only show once per episode
        
        nextEpisodeOverlay?.isVisible = true
        tvNextEpTitle?.text = nextEp.title ?: "Episode ${nextEp.episodeNumber}"
        
        // Start Countdown (15s)
        nextEpisodeTimer?.cancel()
        nextEpisodeTimer = object : CountDownTimer(15000, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                val sec = (millisUntilFinished / 1000).toInt()
                tvCountdownSeconds?.text = sec.toString()
                val progress = ((15000 - millisUntilFinished) / 150.0).toInt() // 0-100
                nextEpisodeOverlay?.findViewById<ProgressBar>(R.id.progress_countdown)?.progress = progress
            }
            override fun onFinish() {
                playNextEpisode()
            }
        }.start()
        
        // Focus play button
        playerView.hideController() // Ensure controller doesn't steal focus
        playerView.isFocusable = false
        
        btnPlayNext?.postDelayed({ 
            val focused = btnPlayNext?.requestFocus()
            if (focused == true) {
                 btnPlayNext?.sendAccessibilityEvent(android.view.accessibility.AccessibilityEvent.TYPE_VIEW_FOCUSED)
            } else {
                 // Fallback: try forcing parent or retry
                 Log.w("PlayerActivity", "Failed to focus Play button, retrying...")
                 btnPlayNext?.requestFocus()
            }
        }, 150) // Increased delay to 150ms for safety
    }

    private fun hideNextEpisodePrompt(cancelled: Boolean) {
        isNextPromptVisible = false
        nextEpisodeOverlay?.isVisible = false
        nextEpisodeTimer?.cancel()
        
        // Restore Player Focus
        playerView.isFocusable = true
        
        if (cancelled) {
            // Focus back to player or controller
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
        bindNextSlot(
            container = cardNext1,
            titleView = tvNext1Title,
            timeView = tvNext1Time,
            program = nextPrograms.getOrNull(0)
        )
        bindNextSlot(
            container = cardNext2,
            titleView = tvNext2Title,
            timeView = tvNext2Time,
            program = nextPrograms.getOrNull(1)
        )
        bindNextSlot(
            container = cardNext3,
            titleView = tvNext3Title,
            timeView = tvNext3Time,
            program = nextPrograms.getOrNull(2)
        )

        if (!hasShownOverlayOnce) {
            showEpgOverlay(EpgOverlayMode.COMPACT, pinned = false)
            hasShownOverlayOnce = true
        }

        updateOverlayVisibility()
    }

    private fun bindNextSlot(
        container: View?,
        titleView: TextView?,
        timeView: TextView?,
        program: com.tvonnet.debridxtreamiptv.data.local.entity.EpgEntity?
    ) {
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
        val logo = channelLogoUrl
        if (!logo.isNullOrBlank()) {
            imgChannelLogo?.let {
                Glide.with(this)
                    .load(logo)
                    .placeholder(R.drawable.tv_card_placeholder)
                    .into(it)
            }
        } else {
            imgChannelLogo?.setImageResource(R.drawable.tv_card_placeholder)
        }
    }

    private fun calculateSmartBuffer(): Int {
        val actManager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        actManager.getMemoryInfo(memInfo)
        
        val totalRamGb = memInfo.totalMem / (1024 * 1024 * 1024.0)
        // Lower threshold to 1.5GB to include FireStick 4K / Chromecast 4K
        return if (totalRamGb > 1.5) {
            Log.i("PlayerActivity", "Smart RAM: High Spec Device (${String.format("%.1f", totalRamGb)}GB). Using Ultra Buffer (60s).")
            60000 // 60s for Shield/FireCube/FireStick 4K
        } else {
            Log.i("PlayerActivity", "Smart RAM: Low Spec Device (${String.format("%.1f", totalRamGb)}GB). Using Safe Buffer (25s).")
            25000 // 25s for FireStick Lite/Older TVs to prevent OOM
        }
    }

    private fun getSavedNetworkQuality(): NetworkQuality {
        val rawQuality = settingsPreferences.getNetworkQuality()
        return runCatching { NetworkQuality.valueOf(rawQuality) }.getOrDefault(NetworkQuality.MODERATE)
    }

    private fun buildLiveBufferConfig(isHls: Boolean): BufferConfig {
        val baseMaxBuffer = calculateSmartBuffer()
        val quality = getSavedNetworkQuality()
        val config = when (quality) {
            NetworkQuality.FAST -> BufferConfig(15000, baseMaxBuffer, 1000, 2000)
            NetworkQuality.MODERATE -> BufferConfig(15000, baseMaxBuffer, 2000, 3000)
            NetworkQuality.SLOW -> {
                val bumpedMax = (baseMaxBuffer + 10000).coerceAtMost(60000)
                BufferConfig(20000, bumpedMax, 3000, 5000)
            }
            NetworkQuality.UNKNOWN -> BufferConfig(15000, baseMaxBuffer, 2000, 3000)
        }

        return if (isHls) {
            val cappedMax = config.maxBufferMs.coerceAtMost(30000)
            config.copy(maxBufferMs = cappedMax)
        } else {
            config
        }
    }

    private fun isHlsUrl(url: String): Boolean {
        return url.contains(".m3u8", ignoreCase = true)
    }

    private fun readStreamHeaders(intent: Intent): Map<String, String>? {
        @Suppress("UNCHECKED_CAST")
        val raw = intent.getSerializableExtra(EXTRA_STREAM_HEADERS) as? HashMap<String, String>
        return raw?.takeIf { it.isNotEmpty() }
    }

    private fun initializePlayer(streamUrl: String) {
        try {
            didPlaybackComplete = false
            timeoutHandler.removeCallbacks(timeoutRunnable)
            timeoutMs = resolveTimeoutMs(streamUrl)
            timeoutHandler.postDelayed(timeoutRunnable, timeoutMs)

            // Phase 2: "Ultra" Engine - OkHttp Data Source for Connection Pooling
            val dataSourceFactory = OkHttpDataSource.Factory(okHttpClient)
            val requestHeaders = streamHeaders
            if (!requestHeaders.isNullOrEmpty()) {
                dataSourceFactory.setDefaultRequestProperties(requestHeaders)
            }
            val hasUserAgent = requestHeaders?.keys?.any { it.equals("User-Agent", ignoreCase = true) } == true
            if (!hasUserAgent) {
                dataSourceFactory.setUserAgent("DebridXtream/1.0 (Linux; Android 10; TV)")
            }

            val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory)
            
            // Phase 5: Ultra Engine V2 - Smart Dynamic Buffer
            // Fixes "Long Freezes" by allowing huge buffers (60s) but resuming FAST (2s)
            val loadControl = if (contentType == ContentType.LIVE_TV) {
                val bufferConfig = buildLiveBufferConfig(isHlsUrl(streamUrl))
                Log.i(
                    "PlayerActivity",
                    "Ultra Engine V2: Active (min=${bufferConfig.minBufferMs} max=${bufferConfig.maxBufferMs} " +
                        "start=${bufferConfig.startPlaybackMs} rebuffer=${bufferConfig.rebufferPlaybackMs} " +
                        "net=${getSavedNetworkQuality()})"
                )
                DefaultLoadControl.Builder()
                    .setBufferDurationsMs(
                        bufferConfig.minBufferMs,
                        bufferConfig.maxBufferMs,
                        bufferConfig.startPlaybackMs,
                        bufferConfig.rebufferPlaybackMs
                    )
                    .setPrioritizeTimeOverSizeThresholds(true)
                    .build()
            } else {
                DefaultLoadControl.Builder().build()
            }
            
            // Phase 2: Hardware Tunneling (Reduces CPU usage on TV)
            // UPDATE: Disabled Tunneling because it causes "No Audio" on 4K channels (AC3/EAC3) 
            // where the hardware DSP cannot handle the specific codec.
            val trackSelector = DefaultTrackSelector(this)
            trackSelector.parameters = trackSelector.buildUponParameters()
                .setTunnelingEnabled(false) // Disabled to ensure Audio works
                .build()

            // Configure Renderers to allow software decoding fallback for complex Audio
            val renderersFactory = DefaultRenderersFactory(this)
                .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)

            player = ExoPlayer.Builder(this)
                .setRenderersFactory(renderersFactory)
                .setMediaSourceFactory(mediaSourceFactory)
                .setLoadControl(loadControl)
                .setTrackSelector(trackSelector)
                .build()
                .also { playerView.player = it }

            val mediaItem = MediaItem.fromUri(streamUrl)
            player?.setMediaItem(mediaItem, /* resetPosition = */ true)
            player?.prepare()
            if (startPositionMs > 0L) {
                Log.d("PlayerActivity", "Initializing player with resume position: ${startPositionMs}ms")
                player?.seekTo(startPositionMs)
                // Do NOT clear startPositionMs here. Wait for STATE_READY.
                // startPositionMs = 0L 
            } else {
                Log.d("PlayerActivity", "Initializing player at start (0ms)")
            }
            player?.playWhenReady = true
            startStallMonitor()

            player?.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_READY) {
                         // Only clear resume position if we successfully started playing
                         if (player?.currentPosition ?: 0L > 1000) {
                             // Consider the resume "consumed"
                             // But actually, for robust retry, we might want to keep it updated via checks
                             startPositionMs = 0L 
                         }
                        timeoutHandler.removeCallbacks(timeoutRunnable)
                        retryCount = 0
                        lastBufferingStartMs = 0L
                        val bufferedMs = (player?.bufferedPosition ?: 0L) - (player?.currentPosition ?: 0L)
                        Log.d("PlayerActivity", "Ready. buffer=${bufferedMs}ms net=${getSavedNetworkQuality()}")
                    } else if (playbackState == Player.STATE_BUFFERING) {
                        // Fix: Re-post timeout when buffering occurs mid-stream (network drop)
                        timeoutHandler.removeCallbacks(timeoutRunnable)
                        timeoutHandler.postDelayed(timeoutRunnable, timeoutMs)
                        if (lastBufferingStartMs == 0L) {
                            lastBufferingStartMs = SystemClock.elapsedRealtime()
                        }
                        val bufferedMs = (player?.bufferedPosition ?: 0L) - (player?.currentPosition ?: 0L)
                        Log.d("PlayerActivity", "Buffering... buffer=${bufferedMs}ms net=${getSavedNetworkQuality()}")
                    } else if (playbackState == Player.STATE_ENDED || playbackState == Player.STATE_IDLE) {
                        timeoutHandler.removeCallbacks(timeoutRunnable)
                        lastBufferingStartMs = 0L

                        if (playbackState == Player.STATE_ENDED) {
                             // Phase 5: Auto-Next on End
                             if (contentType == ContentType.SERIES || contentType == ContentType.EPISODE) {
                                  val state = viewModel.seriesPlaylistState.value
                                  if (state?.hasNext == true) {
                                       playNextEpisode()
                                       return
                                  }
                                  didPlaybackComplete = true
                                  finish() // End of season/series
                             } else {
                                  didPlaybackComplete = true
                             }
                        }
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    timeoutHandler.removeCallbacks(timeoutRunnable)
                    handlePlaybackError(error)
                }
            })
        } catch (e: Exception) {
            timeoutHandler.removeCallbacks(timeoutRunnable)
            handleInitializationError(e)
        }
    }

    private fun attemptNetworkRecovery(reason: String) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            timeoutHandler.post { attemptNetworkRecovery(reason) }
            return
        }
        if (!settingsPreferences.isAutoReconnectEnabled()) return
        val url = currentUrl ?: return
        val playerSnapshot = player ?: return
        if (!playerSnapshot.playWhenReady) return

        val now = SystemClock.elapsedRealtime()
        val bufferingMs = if (lastBufferingStartMs > 0L) now - lastBufferingStartMs else 0L
        val shouldRestart = playerSnapshot.playbackState == Player.STATE_IDLE ||
            (playerSnapshot.playbackState == Player.STATE_BUFFERING && bufferingMs >= NETWORK_RECOVERY_BUFFER_MS)

        if (shouldRestart) {
            Log.w("PlayerActivity", "Network recovery ($reason), restarting player.")
            // Fix: Save position before release
            // Fix: Save position before release
            if (contentType != ContentType.LIVE_TV) {
                val currentPos = playerSnapshot.currentPosition
                if (currentPos > 1000L) {
                    startPositionMs = currentPos
                }
            }
            playerSnapshot.release()
            player = null
            initializePlayer(url)
        }
    }

    private fun registerNetworkCallback() {
        if (networkCallback != null) return
        val manager = connectivityManager ?: return
        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: android.net.Network) {
                networkAvailable = true
                attemptNetworkRecovery("available")
            }

            override fun onLost(network: android.net.Network) {
                networkAvailable = false
                Log.w("PlayerActivity", "Network lost.")
            }
        }
        manager.registerDefaultNetworkCallback(networkCallback!!)
    }

    private fun unregisterNetworkCallback() {
        val callback = networkCallback ?: return
        try {
            connectivityManager?.unregisterNetworkCallback(callback)
        } catch (e: Exception) {
            Log.w("PlayerActivity", "Network callback unregister failed", e)
        } finally {
            networkCallback = null
        }
    }

    private fun handlePlaybackError(error: PlaybackException) {
        Log.w("PlayerActivity", "Playback error code=${error.errorCode} msg=${error.message}")
        if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
            Log.w("PlayerActivity", "Behind live window, seeking to live edge.")
            player?.seekToDefaultPosition()
            player?.prepare()
            player?.playWhenReady = true
            return
        }

        val errorMessage = when (val cause = error.cause) {
            is HttpDataSource.InvalidResponseCodeException -> {
                when (cause.responseCode) {
                    503 -> "Server unavailable (503)"
                    404 -> "Stream not found (404):\n$currentUrl"
                    403 -> "Access denied (403)"
                    else -> "HTTP error: ${cause.responseCode}"
                }
            }
            else -> error.message ?: "Playback error"
        }

        if (retryCount < maxRetries) {
            retryCount++
            showToast("Retrying... ($retryCount/$maxRetries)")
            
            // Fix: Save position for VOD resume
            // Fix: Save position for VOD resume
            if (contentType != ContentType.LIVE_TV) {
                val currentPos = player?.currentPosition ?: 0L
                // If player crashed immediately (pos=0), keep the *previous* startPositionMs
                // Otherwise update it to the new progress
                if (currentPos > 1000L) {
                    startPositionMs = currentPos
                }
            }
            
            player?.release()
            player = null
            Handler(Looper.getMainLooper()).postDelayed({
                currentUrl?.let { initializePlayer(it) }
            }, retryCount * 1000L) // Faster retry (1s, 2s, 3s...)
        } else {
            val handled = finishWithReturnToSources(autoPlayNext = true, reason = errorMessage)
            if (handled) return
            // Phase 3: 5-Strikes reached -> Show Support QR
            showSupportQr()
        }
    }
    
    private fun checkForStall() {
        val p = player ?: return
        val now = SystemClock.elapsedRealtime()
        if (p.playWhenReady && p.playbackState == Player.STATE_READY) {
            val currentPos = p.currentPosition
            if (currentPos > lastBoundPosition) {
                lastBoundPosition = currentPos
                lastProgressCheckMs = now
                return
            }
            if (currentPos > 0 && now - lastProgressCheckMs >= STALL_THRESHOLD_MS) {
                Log.w("PlayerActivity", "Stall detected at $currentPos")
                handlePlaybackError(PlaybackException(null, null, PlaybackException.ERROR_CODE_REMOTE_ERROR))
                lastProgressCheckMs = now
            }
        } else {
            lastBoundPosition = p.currentPosition
            lastProgressCheckMs = now
        }
    }

    private enum class NetworkQuality {
        FAST,
        MODERATE,
        SLOW,
        UNKNOWN
    }

    private data class BufferConfig(
        val minBufferMs: Int,
        val maxBufferMs: Int,
        val startPlaybackMs: Int,
        val rebufferPlaybackMs: Int
    )

    private fun showSupportQr() {
        releasePlayer()
        layoutSupportQr?.isVisible = true
        
        val supportUrl = settingsPreferences.getSupportUrl()
        try {
            val barcodeEncoder = com.journeyapps.barcodescanner.BarcodeEncoder()
            val bitmap = barcodeEncoder.encodeBitmap(supportUrl, com.google.zxing.BarcodeFormat.QR_CODE, 400, 400)
            imgSupportQr?.setImageBitmap(bitmap)
            showToast("Playback Failed. Scan to contact support.")
        } catch (e: Exception) {
            Log.e("PlayerActivity", "QR Error", e)
        }
    }

    private fun handleInitializationError(error: Exception) {
        val handled = finishWithReturnToSources(
            autoPlayNext = true,
            reason = "Failed to initialize: ${error.message}"
        )
        if (handled) return
        showError("Failed to initialize player: ${error.message}")
        Handler(Looper.getMainLooper()).postDelayed({ finish() }, 3000)
    }

    private fun handleTimeout() {
        if (player?.playbackState == Player.STATE_BUFFERING) {
            if (retryCount < maxRetries) {
                retryCount++
                showToast("Connection timeout, retrying...")
                
                // Fix: Save position for resume (Critical for timeout functionality)
                if (contentType != ContentType.LIVE_TV) {
                    val currentPos = player?.currentPosition ?: 0L
                    Log.d("PlayerActivity", "Timeout detected. Saving resume position: ${currentPos}ms")
                    if (currentPos > 1000L) {
                        startPositionMs = currentPos
                    }
                }
                
                player?.release()
                player = null
                Handler(Looper.getMainLooper()).postDelayed({
                    currentUrl?.let { initializePlayer(it) }
                }, 2000)
            } else {
                val handled = finishWithReturnToSources(
                    autoPlayNext = true,
                    reason = "Connection timeout"
                )
                if (handled) return
                showError("Connection timeout\n\nStream is too slow or unavailable")
                Handler(Looper.getMainLooper()).postDelayed({ finish() }, 3000)
            }
        }
    }

    private fun showError(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    override fun onResume() {
        super.onResume()
        if (player == null) {
            currentUrl?.let { initializePlayer(it) }
        } else {
            startStallMonitor()
        }
    }

    override fun onStart() {
        super.onStart()
        registerNetworkCallback()
        nextCheckHandler.post(nextCheckRunnable)
    }

    override fun onPause() {
        super.onPause()
        player?.pause()
        timeoutHandler.removeCallbacks(timeoutRunnable)
        stopStallMonitor()
    }

    override fun onStop() {
        super.onStop()
        timeoutHandler.removeCallbacks(timeoutRunnable)
        stallHandler.removeCallbacks(stallRunnable) // Stop monitor
        unregisterNetworkCallback()
        recordPlaybackHistoryIfNeeded()
        releasePlayer()
    }

    private fun releasePlayer() {
        updateLastPlaybackPosition()
        timeoutHandler.removeCallbacks(timeoutRunnable)
        overlayHandler.removeCallbacks(overlayHideRunnable)
        nextCheckHandler.removeCallbacks(nextCheckRunnable)
        nextEpisodeTimer?.cancel()
        stopStallMonitor()
        player?.stop()
        player?.release()
        player = null
        playerView.player = null
    }

    private fun startStallMonitor() {
        lastBoundPosition = player?.currentPosition ?: 0L
        lastProgressCheckMs = SystemClock.elapsedRealtime()
        stallHandler.removeCallbacks(stallRunnable)
        stallHandler.postDelayed(stallRunnable, 5000)
    }

    private fun stopStallMonitor() {
        stallHandler.removeCallbacks(stallRunnable)
    }

    override fun onDestroy() {
        super.onDestroy()
        recordPlaybackHistoryIfNeeded()
        releasePlayer()
    }

    private fun resolveTimeoutMs(url: String): Long {
        val lowered = url.lowercase()
        return if (lowered.contains("mediafusion.elfhosted.com")) {
            MEDIAFUSION_TIMEOUT_MS
        } else {
            TIMEOUT_MS
        }
    }

    override fun finish() {
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

        // Setup Clock (simple implementation, ideally use a stored generic function or value)
        tvClock.text = java.text.SimpleDateFormat("h:mm a", java.util.Locale.getDefault()).format(java.util.Date())

        // Setup Adapters
        browserCategoryAdapter = BrowserCategoryAdapter { category ->
            category.category_id?.let { viewModel.selectBrowserCategory(it) }
        }
        rvCategories.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this)
        rvCategories.adapter = browserCategoryAdapter

        browserChannelAdapter = BrowserChannelAdapter { stream ->
            // Play channel instantly without restarting Activity
            stream.stream_id?.let { id ->
                val username = prefs.getUsername()
                val password = prefs.getPassword()
                val serverUrl = baseServerUrl ?: prefs.getServerUrl() ?: ""
                
                // 1. Build URL
                val newUrl = "$serverUrl/live/$username/$password/$id.ts"
                
                // 2. Play
                playUrl(newUrl)
                
                // 3. Update internal state
                contentId = id
                currentUrl = newUrl
                channelLogoUrl = stream.stream_icon
                currentEpgChannelId = stream.epg_channel_id ?: id
                
                // 4. Update UI
                bindChannelMeta(stream.name)
                supportActionBar?.title = stream.name
                
                // 5. Update Zapping Context (so Next/Prev works in new category)
                val currentCategory = viewModel.browserState.value.selectedCategoryId
                viewModel.initLiveZapping(
                    categoryId = currentCategory,
                    currentStreamId = id,
                    baseServerUrl = serverUrl,
                    orderedStreamIds = null // Allow ViewModel to fetch full list from cache for this category
                )
                
                // 6. Refresh EPG Logic
                val epgKey = stream.epg_channel_id?.takeIf { it.isNotBlank() } ?: id
                viewModel.observeEpg(epgKey, id)
                
                // 7. Hide Overlay
                viewModel.toggleBrowser(false)
            }
        }
        rvChannels.layoutManager = androidx.recyclerview.widget.GridLayoutManager(this, 4)
        rvChannels.setHasFixedSize(true)
        rvChannels.adapter = browserChannelAdapter

        lifecycleScope.launch {
            viewModel.browserState.collect { state ->
                browserView.visibility = if (state.isVisible) View.VISIBLE else View.GONE
                
                if (state.isVisible) {
                    // Update Categories
                    browserCategoryAdapter.submitList(state.categories)
                    browserCategoryAdapter.setSelectedId(state.selectedCategoryId)
                    
                    // Update Channels with callback for focus
                    browserChannelAdapter.submitList(state.channels) {
                        // This block runs after the list difference is calculated and applied.
                        val currentId = contentId
                        if (state.channels.isNotEmpty() && currentId != null) {
                             val chanIndex = state.channels.indexOfFirst { it.stream_id == currentId }
                             if (chanIndex != -1) {
                                 rvChannels.scrollToPosition(chanIndex)
                                 
                                 // Focus only if we are just opening the browser (no existing focus in lists)
                                 // OR if we want to force focus on the channel list (user request implied this).
                                 // Let's refine: If categories has focus, don't steal it unless it's initial open.
                                 // User said: "channel focus... not happening"
                                 // Safer bet: Post a requestFocus for the specific view holder
                                 // Ensure the view is laid out before requesting focus
                                 rvChannels.post {
                                     val v = rvChannels.findViewHolderForAdapterPosition(chanIndex)?.itemView
                                     if (v != null) {
                                         v.requestFocus()
                                     } else {
                                         // Fallback if view holder is null (e.g. not laid out yet)
                                         // Force scroll smoothly again and retry focus
                                         rvChannels.postDelayed({
                                            rvChannels.findViewHolderForAdapterPosition(chanIndex)?.itemView?.requestFocus()
                                         }, 100)
                                     }
                                 }
                             }
                        }
                    }
                    
                    // Loading / Empty States
                    pbLoading.visibility = if (state.isLoadingChannels) View.VISIBLE else View.GONE
                    tvEmpty.visibility = if (!state.isLoadingChannels && state.channels.isEmpty()) View.VISIBLE else View.GONE
                    
                    // Focus Management & Scrolling for Categories
                    if (state.categories.isNotEmpty()) {
                        val catIndex = state.categories.indexOfFirst { it.category_id == state.selectedCategoryId }
                        if (catIndex != -1) {
                             rvCategories.scrollToPosition(catIndex)
                             // Category Focus (Only if channels didn't take it or initial load preference)
                             // If we want channel focus to be primary when opening, we should prioritize channels
                             // But usually sidebar navigation starts on sidebar.
                             // User complaint was specifically about CHANNEL focus not working.
                             // Let's just scroll categories but not grab focus if we establish focus on channel above.
                        }
                    }
                }
            }
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                     if (contentType == ContentType.LIVE_TV) {
                         val browserVisible = viewModel.browserState.value.isVisible
                         if (!browserVisible) {
                             // Pass current category and channel ID to sync selection
                             val currentCatId = viewModel.zapState.value?.categoryId ?: liveCategoryId
                             val currentChanId = contentId
                             viewModel.toggleBrowser(true, currentCatId, currentChanId)
                             return true
                         }
                     }
                }
                
                // ... existing keys ...
                
                KeyEvent.KEYCODE_CHANNEL_UP,
                KeyEvent.KEYCODE_MEDIA_NEXT,
                KeyEvent.KEYCODE_PAGE_UP -> {
                    if (contentType == ContentType.LIVE_TV && !viewModel.browserState.value.isVisible) {
                        zapChannel(direction = +1)
                        return true
                    }
                }

                KeyEvent.KEYCODE_CHANNEL_DOWN,
                KeyEvent.KEYCODE_MEDIA_PREVIOUS,
                KeyEvent.KEYCODE_PAGE_DOWN -> {
                    if (contentType == ContentType.LIVE_TV && !viewModel.browserState.value.isVisible) {
                        zapChannel(direction = -1)
                        return true
                    }
                }

                // Best-practice IPTV mapping (Fire TV friendly):
                // - DPAD_UP/DOWN: channel zap
                // - INFO: toggle EPG overlay
                KeyEvent.KEYCODE_DPAD_UP -> {
                    if (contentType == ContentType.LIVE_TV) {
                         if (viewModel.browserState.value.isVisible) {
                             // Let standard nav work
                             return super.dispatchKeyEvent(event)
                         }
                        zapChannel(direction = +1)
                        return true
                    }
                }
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    if (contentType == ContentType.LIVE_TV) {
                        if (viewModel.browserState.value.isVisible) {
                             // Let standard nav work
                             return super.dispatchKeyEvent(event)
                         }
                        zapChannel(direction = -1)
                        return true
                    }
                }

                KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                    if (contentType == ContentType.LIVE_TV) {
                        if (viewModel.browserState.value.isVisible) {
                            return super.dispatchKeyEvent(event) // Handle click in browser
                        }
                        if (epgOverlayMode != EpgOverlayMode.HIDDEN && !epgOverlayPinned) {
                            hideEpgOverlay()
                        } else {
                            showEpgOverlay(EpgOverlayMode.COMPACT, pinned = false)
                        }
                        return true
                    }
                }

                KeyEvent.KEYCODE_INFO -> {
                    if (contentType == ContentType.LIVE_TV) {
                        toggleEpgOverlayPinned()
                    } else {
                        playerView.showController()
                    }
                    return true
                }

                // Many TV remotes don't have an INFO key; MENU/GUIDE are common alternatives.
                KeyEvent.KEYCODE_MENU,
                KeyEvent.KEYCODE_GUIDE -> {
                    if (contentType == ContentType.LIVE_TV) {
                        toggleEpgOverlayPinned()
                        return true
                    }
                }
                // Phase 3.2: PiP support - Enter PiP mode with long press on back
                KeyEvent.KEYCODE_BACK -> {
                    if (viewModel.browserState.value.isVisible) {
                        viewModel.toggleBrowser(false)
                        return true
                    }
                    if (contentType == ContentType.LIVE_TV && epgOverlayPinned && epgOverlayMode != EpgOverlayMode.HIDDEN) {
                        hideEpgOverlay()
                        return true
                    }
                    if (event.repeatCount > 0 && supportsPictureInPicture()) {
                        enterPictureInPictureModeInternal()
                        return true
                    }
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onKeyLongPress(keyCode: Int, event: KeyEvent): Boolean {
        if (contentType == ContentType.LIVE_TV) {
            when (keyCode) {
                // Long OK = open/close EPG overlay (common IPTV UX on Fire TV).
                KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                    toggleEpgOverlayPinned()
                    return true
                }
            }
        }
        return super.onKeyLongPress(keyCode, event)
    }

    // Phase 3.2: Picture-in-Picture (PiP) Implementation
    private fun supportsPictureInPicture(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE) ||
            packageManager.hasSystemFeature("android.software.picture_in_picture")
        } else {
            false
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun enterPictureInPictureModeInternal() {
        if (!supportsPictureInPicture() || isInPictureInPictureMode) return

        try {
            // Save current playback state
            player?.let {
                wasPlayingBeforePiP = it.playWhenReady
            }

            // Create PiP parameters
            val params = PictureInPictureParams.Builder()
                .setAspectRatio(Rational(16, 9))
                .setSourceRectHint(Rect(0, 0, playerView.width, playerView.height))
                .build()

            val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                enterPictureInPictureMode(params)
            } else {
                false
            }

            if (result) {
                Log.d("PlayerActivity", "Successfully entered Picture-in-Picture mode")
                // Hide UI elements when entering PiP
                hideUiForPiP()
            } else {
                Log.w("PlayerActivity", "Failed to enter Picture-in-Picture mode")
                showToast("Picture-in-Picture not available")
            }
        } catch (e: Exception) {
            Log.e("PlayerActivity", "Error entering Picture-in-Picture mode", e)
            showToast("Picture-in-Picture failed")
        }
    }

    private fun hideUiForPiP() {
        // Hide controller, EPG overlay, and other UI elements
        playerView.hideController()
        epgOverlay?.isVisible = false
        vodInfoOverlay?.isVisible = false
        supportActionBar?.hide()

        // Record playback history before entering PiP
        recordPlaybackHistoryIfNeeded()
    }

    private fun showUiForNormalMode() {
        // Restore UI elements when returning from PiP
        if (!isInPictureInPictureMode) {
            supportActionBar?.show()
            if (contentType == ContentType.LIVE_TV) updateOverlayVisibility() else updateVodOverlayVisibility()
        }
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: android.content.res.Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        this.isInPictureInPictureMode = isInPictureInPictureMode

        if (isInPictureInPictureMode) {
            // Hide all UI controls when entering PiP mode
            hideUiForPiP()
            Log.d("PlayerActivity", "Entered PiP mode")
        } else {
            // Restore UI controls when returning from PiP mode
            showUiForNormalMode()
            Log.d("PlayerActivity", "Exited PiP mode")
        }
    }

    private fun updateOverlayVisibility() {
        val state = lastOverlayState
        // For Live TV, the overlay should be allowed to render even when EPG is empty (it still
        // shows channel name/logo + "No guide" or "Syncing").
        val hasOverlayContent = epgOverlay != null && when (contentType) {
            ContentType.LIVE_TV -> true
            else -> (state?.epgAvailable == true || state?.isSyncing == true || !state?.errorMessage.isNullOrBlank())
        }

        val shouldShowOverlay = when (contentType) {
            ContentType.LIVE_TV -> {
                hasOverlayContent && epgOverlayMode != EpgOverlayMode.HIDDEN && !isInPictureInPictureMode
            }
            else -> hasOverlayContent && isControllerVisible && !isInPictureInPictureMode
        }

        setOverlayVisible(shouldShowOverlay)
    }

    private fun updateVodOverlayVisibility() {
        val shouldShowOverlay =
            contentType != ContentType.LIVE_TV && isControllerVisible && !isInPictureInPictureMode
        setVodOverlayVisible(shouldShowOverlay)
    }

    private fun setOverlayVisible(visible: Boolean) {
        val overlay = epgOverlay ?: return
        if (visible) {
            if (overlay.isVisible) return
            overlay.alpha = 0f
            overlay.isVisible = true
            overlay.animate().alpha(1f).setDuration(160).start()
        } else {
            if (!overlay.isVisible) return
            overlay.animate()
                .alpha(0f)
                .setDuration(140)
                .withEndAction { overlay.isVisible = false }
                .start()
        }
    }

    private fun setVodOverlayVisible(visible: Boolean) {
        val overlay = vodInfoOverlay ?: return
        if (visible) {
            if (overlay.isVisible) return
            overlay.alpha = 0f
            overlay.isVisible = true
            overlay.animate().alpha(1f).setDuration(160).start()
        } else {
            if (!overlay.isVisible) return
            overlay.animate()
                .alpha(0f)
                .setDuration(140)
                .withEndAction { overlay.isVisible = false }
                .start()
        }
    }

    private fun showEpgOverlay(mode: EpgOverlayMode, pinned: Boolean) {
        if (contentType != ContentType.LIVE_TV) return
        epgOverlayMode = if (mode == EpgOverlayMode.HIDDEN) EpgOverlayMode.HIDDEN else EpgOverlayMode.COMPACT
        epgOverlayPinned = pinned
        applyEpgOverlayMode()
        updateOverlayVisibility()

        overlayHandler.removeCallbacks(overlayHideRunnable)
        if (!pinned) {
            overlayHandler.postDelayed(overlayHideRunnable, OVERLAY_TIMEOUT)
        }
    }

    private fun hideEpgOverlay() {
        overlayHandler.removeCallbacks(overlayHideRunnable)
        epgOverlayPinned = false
        epgOverlayMode = EpgOverlayMode.HIDDEN
        updateOverlayVisibility()
    }

    private fun maybeAutoHideOverlay() {
        if (contentType == ContentType.LIVE_TV && epgOverlayPinned) return
        hideEpgOverlay()
    }

    private fun toggleEpgOverlayPinned() {
        if (epgOverlayMode != EpgOverlayMode.HIDDEN && epgOverlayPinned) {
            hideEpgOverlay()
        } else {
            showEpgOverlay(EpgOverlayMode.COMPACT, pinned = true)
        }
    }

    private fun applyEpgOverlayMode() {
        // XCloud-style compact bar; visibility/content handled in renderOverlay().
    }

    private fun formatTimeRange(program: com.tvonnet.debridxtreamiptv.data.local.entity.EpgEntity): String {
        val start = timeFormatter.format(Date(program.start))
        val end = timeFormatter.format(Date(program.stop))
        return "$start - $end"
    }

    private fun formatTimeRangeCompact(program: com.tvonnet.debridxtreamiptv.data.local.entity.EpgEntity): String {
        val start = timeFormatter.format(Date(program.start))
        val end = timeFormatter.format(Date(program.stop))
        return "$start-$end"
    }

    private fun recordPlaybackHistoryIfNeeded() {
        if (hasRecordedHistory) return
        val type = contentType ?: return
        val id = contentId ?: currentUrl ?: return
        when (type) {
            ContentType.LIVE_TV -> recordLiveHistory(id)
            ContentType.MOVIE, ContentType.SERIES, ContentType.EPISODE -> recordContinueWatchingHistory(id, type)
        }
        hasRecordedHistory = true
    }

    private fun recordLiveHistory(channelId: String) {
        val streamUrl = currentUrl ?: return
        val name = tvChannelName?.text?.toString()
            ?.takeIf { it.isNotBlank() }
            ?: pendingChannelName
            ?: originalTitle
            ?: getString(R.string.player_epg_channel_unknown)
        val epgId = currentEpgChannelId
        val logo = channelLogoUrl ?: posterUrlExtra

        watchHistoryPrefs.addRecentLiveChannel(
            RecentLiveChannelItem(
                channelId = channelId,
                channelName = name,
                channelLogo = logo,
                lastWatchedTimestamp = System.currentTimeMillis(),
                streamUrl = streamUrl,
                epgChannelId = epgId
            )
        )
    }

    private fun recordContinueWatchingHistory(contentId: String, type: ContentType) {
        val playerSnapshot = player ?: return
        val duration = playerSnapshot.duration
        if (duration <= 0 || duration == C.TIME_UNSET || duration < MIN_DURATION_TO_TRACK_MS) return

        val position = playerSnapshot.currentPosition
        if (position < MIN_PROGRESS_TO_TRACK_MS) return

        val progressRatio = position / duration.toFloat()
        val isWatched = progressRatio >= COMPLETION_THRESHOLD_RATIO
        val stableContentId = resolveContinueWatchingId(type, contentId)

        if (type == ContentType.EPISODE) {
             viewModel.updatePlaybackStatus(
                 contentId = contentId,
                 isWatched = isWatched,
                 resumePosition = if (isWatched) 0 else position,
                 duration = duration
             )
        }

        if (isWatched) {
            watchHistoryPrefs.removeContinueWatchingItem(stableContentId)
            return
        }

        val fallbackTitle = originalTitle
            ?: pendingChannelName
            ?: getString(R.string.player_epg_channel_unknown)
        val title = if (type == ContentType.EPISODE) {
            seriesTitleExtra?.takeIf { it.isNotBlank() } ?: fallbackTitle
        } else {
            fallbackTitle
        }

        val item = ContinueWatchingItem(
            contentId = stableContentId,
            contentType = type,
            title = title,
            posterUrl = posterUrlExtra ?: channelLogoUrl,
            backdropUrl = backdropUrlExtra,
            currentPosition = position,
            totalDuration = duration,
            lastWatchedTimestamp = System.currentTimeMillis(),
            streamUrl = currentUrl,
            tmdbId = tmdbIdExtra,
            imdbId = imdbIdExtra,
            seriesTitle = seriesTitleExtra,
            episodeTitle = episodeTitleExtra,
            seasonNumber = seasonNumberExtra,
            episodeNumber = episodeNumberExtra,
            debridInfoHash = debridInfoHashExtra,
            debridMagnet = debridMagnetExtra,
            source = if (playbackSource == PlaybackSource.DEBRID) "debrid" else "xtream"
        )
        watchHistoryPrefs.saveContinueWatchingItem(item)
    }

    private fun resolveContinueWatchingId(type: ContentType, fallbackId: String): String {
        if (playbackSource != PlaybackSource.DEBRID) return fallbackId
        val tmdbId = tmdbIdExtra
        if (!tmdbId.isNullOrBlank()) {
            if (type == ContentType.EPISODE && seasonNumberExtra != null && episodeNumberExtra != null) {
                return "${tmdbId}:S${seasonNumberExtra}E${episodeNumberExtra}"
            }
            return tmdbId
        }
        return debridInfoHashExtra ?: fallbackId
    }

    private fun updateLastPlaybackPosition() {
        lastPlaybackPositionMs = player?.currentPosition ?: lastPlaybackPositionMs
    }

    private fun setExitResultIfNeeded() {
        if (exitResultHandled) return
        exitResultHandled = true
        if (!returnToSourcesOnExit || playbackSource != PlaybackSource.DEBRID) return
        if (didPlaybackComplete) return

        val position = player?.currentPosition ?: lastPlaybackPositionMs
        val shouldReturnToSources = manualExit || position < RETURN_TO_SOURCES_THRESHOLD_MS
        if (!shouldReturnToSources) return

        val data = Intent().putExtra(EXTRA_RETURN_TO_SOURCES, true)
        setResult(Activity.RESULT_OK, data)
    }

    private fun finishWithReturnToSources(autoPlayNext: Boolean, reason: String?): Boolean {
        if (!returnToSourcesOnExit || playbackSource != PlaybackSource.DEBRID) return false
        if (exitResultHandled) return false

        val failedStreamId = contentId ?: debridInfoHashExtra ?: currentUrl
        val data = Intent()
            .putExtra(EXTRA_RETURN_TO_SOURCES, true)
            .putExtra(EXTRA_FAILED_STREAM_ID, failedStreamId)
            .putExtra(EXTRA_FAIL_REASON, reason)
            .putExtra(EXTRA_AUTO_PLAY_NEXT, autoPlayNext)
        exitResultHandled = true
        setResult(Activity.RESULT_OK, data)
        releasePlayer()
        finish()
        return true
    }
}
