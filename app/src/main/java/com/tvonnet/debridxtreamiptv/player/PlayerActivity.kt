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
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.TsExtractor
import java.text.SimpleDateFormat
import java.util.*
import androidx.core.view.isVisible
import android.os.CountDownTimer
import com.tvonnet.debridxtreamiptv.features.seriesv2.data.model.EpisodeEntityV2
import com.tvonnet.debridxtreamiptv.player.SeriesPlaylistState
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
    private var isResolvingDebrid = false
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
        const val EXTRA_SUBTITLE_ENTRIES = "EXTRA_SUBTITLE_ENTRIES"

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
            subtitles: List<String>? = null
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

        preferredAudioLanguage = settingsPreferences.getPreferredAudioLanguage()
        preferredSubtitleLanguage = settingsPreferences.getPreferredSubtitleLanguage()
        watchHistoryPrefs = WatchHistoryPreferences(this)

        playerView = findViewById(R.id.player_view)
        layoutDebridResolving = findViewById(R.id.layout_debrid_resolving)
        tvResolvingStatus = findViewById(R.id.tv_resolving_status)

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
        subtitleEntries = intent.getStringArrayListExtra(EXTRA_SUBTITLE_ENTRIES) ?: emptyList()

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
            // New Redesign: We use the Controller's Top Bar instead of a separate VOD overlay
            // setupVodOverlayViews()
            // bindVodMeta(streamTitle)
        }

        liveCategoryId = intent.getStringExtra(EXTRA_LIVE_CATEGORY_ID)
        baseServerUrl = intent.getStringExtra(EXTRA_BASE_SERVER_URL)
        liveChannelIds = intent.getStringArrayListExtra(EXTRA_LIVE_CHANNEL_IDS)
        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

        // Phase 5: Series Playlist Init
        val seriesId = intent.getStringExtra(EXTRA_SERIES_ID) ?: tmdbIdExtra ?: imdbIdExtra
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
            
            // Custom Settings Button (Shuffle icon in redesign)
            playerView.findViewById<View>(R.id.btn_player_shuffle)?.setOnClickListener {
                showPlayerSettings()
            }

            // Manually wire playback controls to ensure they work on all TV devices
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
            

            
            // Initial sync
            player?.let { updatePlayPauseVisibility(it.isPlaying) }
            
            // New Redesign: Bind Metadata
            bindModernMetadata(streamTitle)
            
            // New Redesign: Initialize interactive animations
            setupInteractiveAnimations()

            // New Redesign: Next Episode Button
            val btnNext = playerView.findViewById<View>(R.id.btn_next_episode)
            if (contentType == ContentType.SERIES || contentType == ContentType.EPISODE) {
                btnNext?.isVisible = true
                btnNext?.setOnClickListener {
                     playNextEpisode()
                }
            } else {
                btnNext?.isVisible = false
            }

            // Phase 4: Aspect Ratio (Resize) Button
            playerView.findViewById<View>(R.id.btn_aspect_ratio)?.setOnClickListener {
                cycleResizeMode()
            }
            
            setupNextEpisodeViews()
            observeSeriesPlaylistState()
            observeDebridResolutionState()
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
            val mediaItem = buildMediaItem(url)
            player?.setMediaItem(mediaItem, /* resetPosition = */ true)
            player?.prepare()
            player?.playWhenReady = true
        } catch (e: Exception) {
            timeoutHandler.removeCallbacks(timeoutRunnable)
            handleInitializationError(e)
        }
    }

    private data class ParsedSubtitle(val url: String, val language: String?)

    private fun buildMediaItem(url: String): MediaItem {
        val subtitleConfigs = buildSubtitleConfigurations(subtitleEntries)
        if (subtitleEntries.isNotEmpty()) {
            Log.d("PlayerActivity", "Subtitle entries=${subtitleEntries.size} configs=${subtitleConfigs.size}")
        }
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
        if (!isMissingImageUrl(artworkUrl) && imgVodArtwork != null) {
            Glide.with(this)
                .load(artworkUrl)
                .placeholder(R.drawable.tv_card_placeholder)
                .error(R.drawable.tv_card_placeholder)
                .into(imgVodArtwork!!)
        } else {
            imgVodArtwork?.setImageResource(R.drawable.tv_card_placeholder)
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
                         // For Debrid, we handle playback via observeDebridResolutionState, so skip here.
                         if (playbackSource != PlaybackSource.DEBRID) {
                             playSeriesEpisode(state.currentEpisode)
                         }
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
        bindModernMetadata(title)
        supportActionBar?.title = title
        
        // Reset Prompt State
        hideNextEpisodePrompt(cancelled = false)
        nextPromptShownForThisEpisode = false
        
        // Play
        playUrl(url)
    }

    private fun bindModernMetadata(title: String?) {
        val titleView = playerView.findViewById<TextView>(R.id.tv_player_title)
        val subtitleView = playerView.findViewById<TextView>(R.id.tv_player_subtitle)
        
        // CLEANUP: If it's a long cluttered file name, clean it up
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
        
        // For episodes, show "Season X • Episode Y"
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

    /**
     * Cleans up messy torrent-style titles for a more professional look.
     * E.g. "The.Bluff.2026.2160p.AMZN..." -> "The Bluff (2026)"
     */
    private fun cleanTitle(raw: String): String {
        var clean = raw.replace(".", " ").replace("_", " ")
        
        // Remove common resolution/codec patterns and everything after
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
        
        // Try to keep the year if it was removed by patterns above, 
        // but if it's there, format it nicely: "Title (Year)"
        val yearRegex = Regex("(\\d{4})")
        val yearMatch = yearRegex.find(raw) // Search in raw to be sure
        if (yearMatch != null) {
            val year = yearMatch.groupValues[1]
            if (!clean.contains(year)) {
                // If the cleaned title doesn't have the year, add it if it makes sense
                // (Only if the year isn't part of a sequence we cut off)
            } else {
                // Format: Title (Year)
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
                  // Manually advance playlist index so "Up Next" will be correct for the *following* episode
                  val nextEp = viewModel.getNextEpisode()

                  // Decide next season/episode. Prefer the synced playlist, but fallback to incrementing manually.
                  // Since Debrid streams often fail to sync `contentId` to local XTREAM ids, we MUST check if nextEp
                  // actually moved forward. If nextEp is stuck or missing, just increment currentEpisode.
                  val targetSeason = nextEp?.seasonNumber?.takeIf { it >= currentSeason } ?: currentSeason
                  val targetEpisode = if (nextEp != null && nextEp.seasonNumber == currentSeason && nextEp.episodeNumber > currentEpisode) {
                      nextEp.episodeNumber
                  } else if (nextEp != null && nextEp.seasonNumber > currentSeason) {
                      nextEp.episodeNumber
                  } else {
                      currentEpisode + 1
                  }

                  // Trigger Debrid Resolution for the NEW episode
                  viewModel.loadNextDebridEpisode(
                      seriesId = tmdbId,
                      targetSeason = targetSeason,
                      targetEpisode = targetEpisode,
                      seriesTitle = seriesTitleExtra,
                      infoHash = debridInfoHashExtra // Critical for binge consistency
                  )
             } else {
                 showError("Next episode data missing")
             }
        } else {
            // Standard IPTV Logic
            viewModel.getNextEpisode() // Updates state -> triggers observer -> calls playSeriesEpisode
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
        
        com.tvonnet.debridxtreamiptv.utils.FocusCoordinator.requestFocus("PLAYER_NEXT_EP") {
            btnPlayNext?.post { 
                btnPlayNext?.post {
                    try {
                        val focused = btnPlayNext?.requestFocus()
                        if (focused == true) {
                             btnPlayNext?.sendAccessibilityEvent(android.view.accessibility.AccessibilityEvent.TYPE_VIEW_FOCUSED)
                        } else {
                             btnPlayNext?.requestFocus()
                        }
                    } finally {
                        // Note: actual release happens in hideNextEpisodePrompt to lock focus to this overlay
                    }
                }
            }
        }
    }

    private fun hideNextEpisodePrompt(cancelled: Boolean) {
        isNextPromptVisible = false
        nextEpisodeOverlay?.isVisible = false
        nextEpisodeTimer?.cancel()
        
        // Restore Player Focus
        playerView.isFocusable = true
        com.tvonnet.debridxtreamiptv.utils.FocusCoordinator.release("PLAYER_NEXT_EP")
        
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
        imgChannelLogo?.let { logoView ->
            GlideUtils.loadChannelLogo(logoView, channelLogoUrl)
        }
    }

    private fun calculateSmartBuffer(): Int {
        val snapshot = DeviceProfile.get(this)
        val totalRamGb = snapshot.totalRamGb
        return if (!snapshot.isLowRamDevice) {
            Log.i(
                "PlayerActivity",
                "Smart RAM: High Spec Device (${String.format("%.1f", totalRamGb)}GB, class=${snapshot.memoryClassMb}MB). " +
                    "Using Ultra Buffer (60s)."
            )
            60000 // 60s for Shield/FireCube/FireStick 4K
        } else {
            Log.i(
                "PlayerActivity",
                "Smart RAM: Low Spec Device (${String.format("%.1f", totalRamGb)}GB, class=${snapshot.memoryClassMb}MB). " +
                    "Using Safe Buffer (25s)."
            )
            25000 // 25s for FireStick Lite/Older TVs to prevent OOM
        }
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
            NetworkQuality.SLOW -> {
                val bumpedMax = (baseMaxBuffer + 10000).coerceAtMost(60000)
                BufferConfig(20000, bumpedMax, 3000, 5000)
            }
            NetworkQuality.UNKNOWN -> BufferConfig(15000, baseMaxBuffer, 2000, 3000)
        }

        // Debrid specialization for Live (e.g. via MediaFusion)
        val debridAdjusted = if (isDebrid) {
            config.copy(
                startPlaybackMs = (config.startPlaybackMs + 1000).coerceAtLeast(3000),
                rebufferPlaybackMs = (config.rebufferPlaybackMs + 1000).coerceAtLeast(4000)
            )
        } else config

        val cappedForDevice = debridAdjusted.copy(maxBufferMs = capMaxBufferForDevice(debridAdjusted.maxBufferMs))
        return if (isHls) {
            val hlsCap = if (DeviceProfile.isLowRamDevice(this)) 25000 else 30000
            cappedForDevice.copy(maxBufferMs = cappedForDevice.maxBufferMs.coerceAtMost(hlsCap))
        } else {
            cappedForDevice
        }
    }

    private fun buildVodBufferConfig(isHls: Boolean, isDebrid: Boolean = false): BufferConfig {
        val baseMaxBuffer = calculateSmartBuffer()
        val quality = getSavedNetworkQuality()
        val config = when (quality) {
            NetworkQuality.FAST -> BufferConfig(12000, baseMaxBuffer, 1500, 3000)
            NetworkQuality.MODERATE -> BufferConfig(15000, baseMaxBuffer, 2000, 3500)
            NetworkQuality.SLOW -> {
                val bumpedMax = (baseMaxBuffer + 15000).coerceAtMost(90000)
                BufferConfig(18000, bumpedMax, 3000, 5000)
            }
            NetworkQuality.UNKNOWN -> BufferConfig(15000, baseMaxBuffer, 2000, 3500)
        }

        // Debrid specialization: More robust initial buffer to prevent immediate expiration/stalls
        val debridAdjusted = if (isDebrid) {
            config.copy(
                startPlaybackMs = (config.startPlaybackMs + 2000).coerceAtLeast(5000),
                rebufferPlaybackMs = (config.rebufferPlaybackMs + 2000).coerceAtLeast(6000)
            )
        } else config

        val cappedForDevice = debridAdjusted.copy(maxBufferMs = capMaxBufferForDevice(debridAdjusted.maxBufferMs))
        return if (isHls) {
            val hlsCap = if (DeviceProfile.isLowRamDevice(this)) 25000 else 45000
            cappedForDevice.copy(maxBufferMs = cappedForDevice.maxBufferMs.coerceAtMost(hlsCap))
        } else {
            cappedForDevice
        }
    }

    private fun isHlsUrl(url: String): Boolean {
        return url.contains(".m3u8", ignoreCase = true)
    }

    private fun capMaxBufferForDevice(maxBufferMs: Int): Int {
        return if (DeviceProfile.isLowRamDevice(this)) {
            maxBufferMs.coerceAtMost(LOW_RAM_MAX_BUFFER_MS)
        } else {
            maxBufferMs
        }
    }

    private fun resolveTargetBufferBytes(): Int {
        return if (DeviceProfile.isLowRamDevice(this)) {
            LOW_RAM_TARGET_BUFFER_BYTES
        } else {
            DefaultLoadControl.DEFAULT_TARGET_BUFFER_BYTES
        }
    }

    private fun readStreamHeaders(intent: Intent): Map<String, String>? {
        @Suppress("UNCHECKED_CAST")
        val raw = intent.getSerializableExtra(EXTRA_STREAM_HEADERS) as? HashMap<String, String>
        return raw?.takeIf { it.isNotEmpty() }
    }

    private fun initializePlayer(streamUrl: String) {
        try {
            didPlaybackComplete = false
            hasAppliedIndexOverride = false
            timeoutHandler.removeCallbacks(timeoutRunnable)
            timeoutMs = resolveTimeoutMs(streamUrl)
            timeoutHandler.postDelayed(timeoutRunnable, timeoutMs)

            // Phase 5: Supercharge Engine - OkHttp + Disk Caching (Stremio Experience)
            val httpDataSourceFactory = OkHttpDataSource.Factory(okHttpClient)
            val requestHeaders = streamHeaders
            if (!requestHeaders.isNullOrEmpty()) {
                httpDataSourceFactory.setDefaultRequestProperties(requestHeaders)
            }
            val hasUserAgent = requestHeaders?.keys?.any { it.equals("User-Agent", ignoreCase = true) } == true
            if (!hasUserAgent) {
                httpDataSourceFactory.setUserAgent("DebridXtream/1.0 (Linux; Android 10; TV)")
            }

            // Wrap in CacheDataSource for Disk Caching
            val dataSourceFactory = CacheDataSource.Factory()
                .setCache(PlayerCacheManager.getCache(this))
                .setUpstreamDataSourceFactory(httpDataSourceFactory)
                .setCacheWriteDataSinkFactory(CacheDataSink.Factory().setCache(PlayerCacheManager.getCache(this)))
                .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

            val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory)
            
            // Phase 5: Ultra Engine V2 - Smart Dynamic Buffer
            // Fixes "Long Freezes" by allowing huge buffers (60s) but resuming FAST (2s)
            val isDebrid = playbackSource == PlaybackSource.DEBRID
            val loadControl = if (contentType == ContentType.LIVE_TV) {
                val bufferConfig = buildLiveBufferConfig(isHlsUrl(streamUrl), isDebrid)
                Log.i(
                    "PlayerActivity",
                    "Ultra Engine V2: Active (min=${bufferConfig.minBufferMs} max=${bufferConfig.maxBufferMs} " +
                        "start=${bufferConfig.startPlaybackMs} rebuffer=${bufferConfig.rebufferPlaybackMs} " +
                        "debrid=$isDebrid net=${getSavedNetworkQuality()})"
                )
                DefaultLoadControl.Builder()
                    .setBufferDurationsMs(
                        bufferConfig.minBufferMs,
                        bufferConfig.maxBufferMs,
                        bufferConfig.startPlaybackMs,
                        bufferConfig.rebufferPlaybackMs
                    )
                    .setTargetBufferBytes(resolveTargetBufferBytes())
                    .setPrioritizeTimeOverSizeThresholds(true)
                    .build()
            } else {
                val isDebrid = playbackSource == PlaybackSource.DEBRID
                val bufferConfig = buildVodBufferConfig(isHlsUrl(streamUrl), isDebrid)
                Log.i(
                    "PlayerActivity",
                    "Playback Buffer: Active (min=${bufferConfig.minBufferMs} max=${bufferConfig.maxBufferMs} " +
                        "start=${bufferConfig.startPlaybackMs} rebuffer=${bufferConfig.rebufferPlaybackMs} " +
                        "debrid=$isDebrid net=${getSavedNetworkQuality()})"
                )
                DefaultLoadControl.Builder()
                    .setBufferDurationsMs(
                        bufferConfig.minBufferMs,
                        bufferConfig.maxBufferMs,
                        bufferConfig.startPlaybackMs,
                        bufferConfig.rebufferPlaybackMs
                    )
                    .setTargetBufferBytes(resolveTargetBufferBytes())
                    .setPrioritizeTimeOverSizeThresholds(true) // FIX: Prioritize time over size for VOD/Debrid
                    .build()
            }
            
            // Phase 2: Hardware Tunneling (Reduces CPU usage on TV)
            // UPDATE: Disabled Tunneling because it causes "No Audio" on 4K channels (AC3/EAC3) 
            // where the hardware DSP cannot handle the specific codec.
            val trackSelector = DefaultTrackSelector(this)
            var parametersBuilder = trackSelector.buildUponParameters()
                .setTunnelingEnabled(false) // Disabled to ensure Audio works
            
            // Apply language persistence (Phase 2.3)
            preferredAudioLanguage?.let { parametersBuilder = parametersBuilder.setPreferredAudioLanguage(it) }
            preferredSubtitleLanguage?.let { parametersBuilder = parametersBuilder.setPreferredTextLanguage(it) }
            
            trackSelector.parameters = parametersBuilder.build()

            // Configure Renderers to allow software decoding fallback for complex Audio
            val isSoftwareAudioEnabled = com.tvonnet.debridxtreamiptv.data.prefs.SettingsPreferences(this).isSoftwareAudioEnabled()
            val extensionMode = if (isSoftwareAudioEnabled) {
                DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER
            } else {
                DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON
            }
            val renderersFactory = DefaultRenderersFactory(this)
                .setExtensionRendererMode(extensionMode)

            player = ExoPlayer.Builder(this)
                .setRenderersFactory(renderersFactory)
                .setMediaSourceFactory(mediaSourceFactory)
                .setLoadControl(loadControl)
                .setTrackSelector(trackSelector)
                .setSeekForwardIncrementMs(15000) // 15s skip for buttons
                .setSeekBackIncrementMs(15000)    // 15s skip for buttons
                .build()
                .also { playerView.player = it }

            val mediaItem = buildMediaItem(streamUrl)
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
                                  if (playbackSource == PlaybackSource.DEBRID) {
                                       playNextEpisode()
                                       return
                                  } else if (state?.hasNext == true) {
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

                    override fun onTrackSelectionParametersChanged(parameters: androidx.media3.common.TrackSelectionParameters) {
                    captureManualTrackSelection()
                }
                
                override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                    if (!hasAppliedIndexOverride) {
                        applyTrackIndexOverrides()
                        hasAppliedIndexOverride = true
                    }
                    captureManualTrackSelection()
                    
                    // Smart Audio Fallback: If current audio track is unsupported, try finding a supported one
                    if (isSoftwareAudioEnabled) {
                        val audioGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
                        var hasSupportedAudioSelected = false
                        var firstSupportedGroup: androidx.media3.common.Tracks.Group? = null
                        
                        for (group in audioGroups) {
                            if (group.isSupported) {
                                if (firstSupportedGroup == null) firstSupportedGroup = group
                                if (group.isSelected) {
                                    hasSupportedAudioSelected = true
                                    break
                                }
                            }
                        }
                        
                        if (!hasSupportedAudioSelected && firstSupportedGroup != null && audioGroups.size > 1) {
                             Log.w("PlayerActivity", "Smart Audio Fallback: Selected track isn't supported. Switching to supported track.")
                             val playerSnapshot = player
                             if (playerSnapshot != null) {
                                 playerSnapshot.trackSelectionParameters = playerSnapshot.trackSelectionParameters
                                     .buildUpon()
                                     .setOverrideForType(
                                         androidx.media3.common.TrackSelectionOverride(
                                             firstSupportedGroup.mediaTrackGroup,
                                             0 // Select first track in supported group
                                         )
                                     )
                                     .build()
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

        val cause = error.cause
        val errorMessage = when (cause) {
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
            
            // Tier 3: Aggressive Debrid Re-resolution (Stremio-Mode)
            // Debrid links are ephemeral — ANY playback error likely means the token expired.
            // Instead of blindly retrying the same dead URL, immediately re-resolve a fresh
            // link when we have metadata (infoHash / magnet).
            if (playbackSource == PlaybackSource.DEBRID && !isResolvingDebrid) {
                 val hasMeta = !debridInfoHashExtra.isNullOrBlank() || !debridMagnetExtra.isNullOrBlank()
                 
                 if (hasMeta) {
                      val isTrulyExpired = cause is HttpDataSource.InvalidResponseCodeException && 
                          (cause.responseCode == 403 || cause.responseCode == 410)
                      val reason = when {
                          isTrulyExpired -> "Link expired"
                          cause is HttpDataSource.InvalidResponseCodeException -> "HTTP ${cause.responseCode}"
                          else -> "Playback error"
                      }
                      Log.i("PlayerActivity", "Debrid $reason detected. Attempting immediate re-resolution.")
                      showToast("$reason. Refreshing stream...")
                      isResolvingDebrid = true
                      
                      // Save current position for re-resume
                      val currentPos = player?.currentPosition ?: 0L
                      if (currentPos > 1000L) startPositionMs = currentPos
                      
                      viewModel.reResolveDebridUrl(
                          infoHash = debridInfoHashExtra,
                          magnet = debridMagnetExtra,
                          season = seasonNumberExtra,
                          episode = episodeNumberExtra,
                          title = episodeTitleExtra
                      )
                      return
                 }
            }

            showToast("Retrying... ($retryCount/$maxRetries)")
            
            // Fix: Save position for VOD resume
            if (contentType != ContentType.LIVE_TV) {
                val currentPos = player?.currentPosition ?: 0L
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
            val isLowRamDevice = DeviceProfile.isLowRamDevice(this)
            val stallThresholdMs = if (isLowRamDevice) LOW_RAM_STALL_THRESHOLD_MS else STALL_THRESHOLD_MS
            val requiredStrikes = if (isLowRamDevice) LOW_RAM_STALL_STRIKES else 1
            val currentPos = p.currentPosition
            if (currentPos > lastBoundPosition) {
                lastBoundPosition = currentPos
                lastProgressCheckMs = now
                stallStrikeCount = 0
                return
            }
            if (currentPos > 0 && now - lastProgressCheckMs >= stallThresholdMs) {
                stallStrikeCount += 1
                if (stallStrikeCount >= requiredStrikes) {
                    Log.w("PlayerActivity", "Stall detected at $currentPos")
                    stallStrikeCount = 0
                    handlePlaybackError(PlaybackException(null, null, PlaybackException.ERROR_CODE_REMOTE_ERROR))
                } else {
                    Log.w(
                        "PlayerActivity",
                        "Stall suspected at $currentPos (strike=$stallStrikeCount/$requiredStrikes)"
                    )
                }
                lastProgressCheckMs = now
            }
        } else {
            lastBoundPosition = p.currentPosition
            lastProgressCheckMs = now
            stallStrikeCount = 0
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
                
            // Phase 1: Debrid Smart Re-resolution logic (for timeouts)
            if (playbackSource == PlaybackSource.DEBRID && !isResolvingDebrid) {
                 val hasMeta = !debridInfoHashExtra.isNullOrBlank() || !debridMagnetExtra.isNullOrBlank()
                 
                 // On first timeout, try a standard retry. On 2nd+ strike, re-resolve.
                 if (hasMeta && retryCount > 1) {
                      Log.i("PlayerActivity", "Debrid persistent timeout. Attempting re-resolution.")
                      showToast("Refreshing slow link...")
                      isResolvingDebrid = true
                      
                      val currentPos = player?.currentPosition ?: 0L
                      if (currentPos > 1000L) startPositionMs = currentPos
                      
                      viewModel.reResolveDebridUrl(
                          infoHash = debridInfoHashExtra,
                          magnet = debridMagnetExtra,
                          season = seasonNumberExtra,
                          episode = episodeNumberExtra,
                          title = episodeTitleExtra
                      )
                      return
                 } else if (hasMeta) {
                      Log.i("PlayerActivity", "Debrid timeout (Strike 1). Trying standard retry first.")
                 }
            }

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

    private fun cycleResizeMode() {
        val currentMode = playerView.resizeMode
        val nextMode = when (currentMode) {
            androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FILL
            androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FILL -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIXED_WIDTH
            androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIXED_WIDTH -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIXED_HEIGHT
            else -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
        }
        playerView.resizeMode = nextMode
        
        val modeName = when (nextMode) {
            androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT -> "Fit"
            androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FILL -> "Stretch"
            androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> "Zoom"
            androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIXED_WIDTH -> "Fixed Width"
            androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIXED_HEIGHT -> "Fixed Height"
            else -> "Fit"
        }
        showToast("Resize Mode: $modeName")
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
        stallStrikeCount = 0
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
                channelLogoUrl = com.tvonnet.debridxtreamiptv.util.GlobalConfig.resolveIconUrl(stream.stream_icon)
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
        rvChannels.itemAnimator = null // Performance & Focus stability
        rvChannels.setItemViewCacheSize(12) // Prefetch more items for faster zapping
        rvChannels.adapter = browserChannelAdapter
        
        rvCategories.setHasFixedSize(true)
        rvCategories.itemAnimator = null

        lifecycleScope.launch {
            var wasVisible = false
            viewModel.browserState.collect { state ->
                val browserVisible = state.isVisible
                browserView.visibility = if (browserVisible) View.VISIBLE else View.GONE
                
                if (browserVisible) {
                    // Update Categories
                    browserCategoryAdapter.submitList(state.categories)
                    browserCategoryAdapter.setSelectedId(state.selectedCategoryId)
                    
                    // Update Channels with callback for focus
                    browserChannelAdapter.submitList(state.channels) {
                        val currentId = contentId
                        if (state.channels.isNotEmpty() && currentId != null) {
                             val chanIndex = state.channels.indexOfFirst { it.stream_id == currentId }
                             if (chanIndex != -1) {
                                 // Fix 3: Double-post pattern for stable focus
                                 rvChannels.scrollToPosition(chanIndex)
                                 rvChannels.post {
                                     rvChannels.findViewHolderForAdapterPosition(chanIndex)?.itemView?.requestFocus()
                                 }
                             }
                        }
                    }
                    
                    // Loading / Empty States
                    pbLoading.visibility = if (state.isLoadingChannels) View.VISIBLE else View.GONE
                    tvEmpty.visibility = if (!state.isLoadingChannels && state.channels.isEmpty()) View.VISIBLE else View.GONE
                    
                    // Scrolling for Categories
                    if (state.categories.isNotEmpty()) {
                        val catIndex = state.categories.indexOfFirst { it.category_id == state.selectedCategoryId }
                        if (catIndex != -1) {
                             rvCategories.scrollToPosition(catIndex)
                        }
                    }
                } else if (wasVisible) {
                    // Fix 2: Restore focus to player when browser closes
                    com.tvonnet.debridxtreamiptv.utils.FocusCoordinator.force("PLAYER") {
                        playerView.requestFocus()
                    }
                    com.tvonnet.debridxtreamiptv.utils.FocusCoordinator.release("PLAYER")
                }
                wasVisible = browserVisible
            }
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            if (contentType != ContentType.LIVE_TV &&
                playerView.useController &&
                !isNextPromptVisible &&
                !playerView.isControllerFullyVisible
            ) {
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
                        playerView.showController()
                        playerView.requestFocus()
                        return true
                    }
                }
            }
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

                KeyEvent.KEYCODE_CAPTIONS -> {
                    if (contentType != ContentType.LIVE_TV) {
                        showSubtitleSelection()
                        return true
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
                        playerView.requestFocus()
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
                    if (contentType != ContentType.LIVE_TV && playerView.isControllerFullyVisible) {
                        showSubtitleSelection()
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

    override fun onUserInteraction() {
        super.onUserInteraction()
        if (contentType == ContentType.LIVE_TV || isInPictureInPictureMode) return
        if (!playerView.useController || isNextPromptVisible) return
        if (!playerView.isControllerFullyVisible) {
            playerView.showController()
            playerView.requestFocus()
        }
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
        val rawLogo = channelLogoUrl ?: posterUrlExtra
        val logo = rawLogo.toAbsoluteUrl(ContentType.LIVE_TV, baseServerUrl)

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
            posterUrl = (posterUrlExtra ?: channelLogoUrl).toAbsoluteUrl(type, baseServerUrl),
            backdropUrl = backdropUrlExtra.toAbsoluteUrl(type, baseServerUrl),
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
    private fun updatePlayPauseVisibility(isPlaying: Boolean) {
        playerView.findViewById<View>(R.id.exo_play)?.isVisible = !isPlaying
        playerView.findViewById<View>(R.id.exo_pause)?.isVisible = isPlaying
        
        // Re-focus if the hidden button had focus
        if (isControllerVisible) {
            val playBtn = playerView.findViewById<View>(R.id.exo_play)
            val pauseBtn = playerView.findViewById<View>(R.id.exo_pause)
            if (isPlaying && playBtn?.isFocused == true) {
                pauseBtn?.post { pauseBtn?.requestFocus() }
            } else if (!isPlaying && pauseBtn?.isFocused == true) {
                playBtn?.post { playBtn?.requestFocus() }
            }
        }
    }

    private fun observeDebridResolutionState() {
        lifecycleScope.launch {
            viewModel.debridResolutionState.collect { state ->
                when (state) {
                    is DebridResolutionState.Loading -> {
                        layoutDebridResolving?.isVisible = true
                        tvResolvingStatus?.text = "Resolving source via Debrid..."
                    }
                    is DebridResolutionState.Success -> {
                        layoutDebridResolving?.isVisible = false
                        isResolvingDebrid = false
                        
                        // Update metadata if provided (Phase 2.1)
                        if (state.season != null && state.episode != null) {
                            val isSameEpisode = seasonNumberExtra == state.season && episodeNumberExtra == state.episode
                            
                            seasonNumberExtra = state.season
                            episodeNumberExtra = state.episode
                            episodeTitleExtra = state.title
                            
                            if (!isSameEpisode) {
                                Log.d("PlayerActivity", "Debrid: New episode detected. Resetting start position.")
                                startPositionMs = 0L // Start next episode from beginning
                            } else {
                                Log.d("PlayerActivity", "Debrid: Same episode re-resolved. Maintaining position: ${startPositionMs}ms")
                            }
                            
                            // Update UI immediately (Redesign: use modern binding)
                            val newTitle = "${seriesTitleExtra ?: ""} - S${state.season}:E${state.episode}"
                            bindModernMetadata(newTitle)
                            
                            // Update infoHash for source consistency in next "Next" click
                            if (state.infoHash != null) {
                                debridInfoHashExtra = state.infoHash
                            }

                            // Re-record history tracking for new episode
                            hasRecordedHistory = false
                            
                            // Reset Prompt State for Next Episode (Phase 2.4/2.5)
                            hideNextEpisodePrompt(cancelled = false)
                            nextPromptShownForThisEpisode = false
                        }

                        currentUrl = state.url
                        releasePlayer()
                        initializePlayer(state.url)
                    }
                    is DebridResolutionState.Error -> {
                        layoutDebridResolving?.isVisible = false
                        isResolvingDebrid = false
                        showError("Failed to re-resolve link: ${state.message}")
                    }
                    is DebridResolutionState.Idle -> {
                        layoutDebridResolving?.isVisible = false
                        isResolvingDebrid = false
                    }
                }
            }
        }
    }

    private fun captureManualTrackSelection() {
        val p = player ?: return
        val groups = p.currentTracks.groups
        
        var selectedAudioIndex = -1
        var selectedAudioLang: String? = null
        
        var selectedTextIndex = -1
        var selectedTextLang: String? = null

        // 1. Audio
        var audioCounter = 0
        for (i in 0 until groups.size) {
            val group = groups[i]
            if (group.type == C.TRACK_TYPE_AUDIO) {
                for (j in 0 until group.length) {
                    if (group.isTrackSelected(j)) {
                        selectedAudioIndex = audioCounter
                        selectedAudioLang = group.getTrackFormat(j).language
                        break
                    }
                    audioCounter++
                }
            }
            if (selectedAudioIndex != -1) break
        }

        // 2. Text
        var textCounter = 0
        for (i in 0 until groups.size) {
            val group = groups[i]
            if (group.type == C.TRACK_TYPE_TEXT) {
                for (j in 0 until group.length) {
                    if (group.isTrackSelected(j)) {
                        selectedTextIndex = textCounter
                        selectedTextLang = group.getTrackFormat(j).language
                        break
                    }
                    textCounter++
                }
            }
            if (selectedTextIndex != -1) break
        }

        // Persistence
        if (selectedAudioLang != null) {
            preferredAudioLanguage = selectedAudioLang
            settingsPreferences.savePreferredAudioLanguage(selectedAudioLang)
        }
        if (selectedTextLang != null) {
            preferredSubtitleLanguage = selectedTextLang
            settingsPreferences.savePreferredSubtitleLanguage(selectedTextLang)
        }
        
        // 1. Exact Source index persistence (for SAME torrent consistency)
        settingsPreferences.saveLastTrackSelection(debridInfoHashExtra, selectedAudioIndex, selectedTextIndex)
        
        // 2. Series-level index persistence (for NEXT torrent consistency)
        val seriesId = intent.getStringExtra(EXTRA_SERIES_ID) ?: tmdbIdExtra ?: imdbIdExtra
        if (seriesId != null) {
            settingsPreferences.saveSeriesTrackPreference(seriesId, selectedAudioIndex, selectedTextIndex)
        }
        
        Log.e("PlayerActivity", "🎯 Track choice captured: audio=$selectedAudioLang (idx=$selectedAudioIndex), sub=$selectedTextLang (idx=$selectedTextIndex)")
    }

    private fun applyTrackIndexOverrides() {
        val p = player ?: return
        val lastHash = settingsPreferences.getLastSelectionHash()
        val currentHash = debridInfoHashExtra
        
        val seriesId = intent.getStringExtra(EXTRA_SERIES_ID) ?: tmdbIdExtra ?: imdbIdExtra
        
        var targetAudioIdx = -1
        var targetTextIdx = -1
        
        if (lastHash != null && currentHash != null && lastHash.equals(currentHash, ignoreCase = true)) {
            // Hash match: use exact indices
            targetAudioIdx = settingsPreferences.getLastAudioIndex()
            targetTextIdx = settingsPreferences.getLastTextIndex()
            Log.d("PlayerActivity", "⚡ Hash matched. Using exact track indices: audio=$targetAudioIdx, sub=$targetTextIdx")
        } else if (seriesId != null) {
            // Hash mismatch: fallback to series-level fuzzy indices
            targetAudioIdx = settingsPreferences.getSeriesAudioIndex(seriesId)
            targetTextIdx = settingsPreferences.getSeriesTextIndex(seriesId)
            if (targetAudioIdx != -1 || targetTextIdx != -1) {
                Log.d("PlayerActivity", "⚡ Source changed. Falling back to series-level track indices: audio=$targetAudioIdx, sub=$targetTextIdx")
            }
        }
        
        if (targetAudioIdx == -1 && targetTextIdx == -1) return
        
        val groups = p.currentTracks.groups
        var params = p.trackSelectionParameters.buildUpon()
        var modified = false

        // Audio override
        if (targetAudioIdx != -1) {
            var audioCounter = 0
            for (i in 0 until groups.size) {
                val group = groups[i]
                if (group.type == C.TRACK_TYPE_AUDIO) {
                    for (j in 0 until group.length) {
                        if (audioCounter == targetAudioIdx) {
                            params.addOverride(TrackSelectionOverride(group.mediaTrackGroup, j))
                            modified = true
                            Log.e("PlayerActivity", "⚡ Applied Audio Index Override: $targetAudioIdx")
                            break
                        }
                        audioCounter++
                    }
                }
                if (modified) break
            }
        }

        // Subtitle override
        if (targetTextIdx != -1) {
            var textCounter = 0
            var textModified = false
            for (i in 0 until groups.size) {
                val group = groups[i]
                if (group.type == C.TRACK_TYPE_TEXT) {
                    for (j in 0 until group.length) {
                        if (textCounter == targetTextIdx) {
                            params.addOverride(TrackSelectionOverride(group.mediaTrackGroup, j))
                            modified = true
                            textModified = true
                            Log.e("PlayerActivity", "⚡ Applied Text Index Override: $targetTextIdx")
                            break
                        }
                        textCounter++
                    }
                }
                if (textModified) break
            }
        }

        if (modified) {
            p.trackSelectionParameters = params.build()
        }
    }

    private fun setupInteractiveAnimations() {
        val buttons = listOf(
            R.id.btn_player_shuffle,
            R.id.exo_rew,
            R.id.exo_play,
            R.id.exo_pause,
            R.id.exo_ffwd,
            R.id.btn_aspect_ratio,
            R.id.btn_next_episode
        )
        
        buttons.forEach { id ->
            playerView.findViewById<View>(id)?.let { view ->
                // Start state: Subtle opacity, standard scale
                view.alpha = 0.7f
                view.scaleX = 1.0f
                view.scaleY = 1.0f
                
                view.setOnFocusChangeListener { v, hasFocus ->
                    if (hasFocus) {
                        // Focus Interaction: Scale up + Full Brightness
                        v.animate()
                            .scaleX(1.15f)
                            .scaleY(1.15f)
                            .alpha(1.0f)
                            .setDuration(200)
                            .setInterpolator(android.view.animation.AccelerateDecelerateInterpolator())
                            .start()
                            
                        // Special Gear Spin for Settings
                        if (v.id == R.id.btn_player_shuffle) {
                            v.animate().rotationBy(90f).setDuration(400).start()
                        }
                    } else {
                        // Unfocused: Restore state
                        v.animate()
                            .scaleX(1.0f)
                            .scaleY(1.0f)
                            .alpha(0.7f)
                            .setDuration(200)
                            .start()
                    }
                }
                
                // Pressed Interaction: Subtle shrink to simulate physical push
                // For TV, this provides visual feedback for the D-pad "Center" click
                view.setOnTouchListener { v, event ->
                    when (event.action) {
                        android.view.MotionEvent.ACTION_DOWN -> {
                            v.animate().scaleX(0.95f).scaleY(0.95f).setDuration(100).start()
                        }
                        android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                            v.animate().scaleX(if (v.hasFocus()) 1.15f else 1.0f)
                                .scaleY(if (v.hasFocus()) 1.15f else 1.0f)
                                .setDuration(100).start()
                        }
                    }
                    false
                }
            }
        }
    }
}
