package com.tvonnet.debridxtreamiptv.ui.series

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatButton
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import android.graphics.drawable.Drawable
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target
import com.tvonnet.debridxtreamiptv.R
import com.tvonnet.debridxtreamiptv.utils.updatePreservingFocus
import com.tvonnet.debridxtreamiptv.data.Result
import com.tvonnet.debridxtreamiptv.data.debrid.repository.AddonProxyReadiness
import com.tvonnet.debridxtreamiptv.data.debrid.model.DebridFailureType
import com.tvonnet.debridxtreamiptv.data.model.ContentType
import com.tvonnet.debridxtreamiptv.data.model.XtreamSeriesDetailInfo
import com.tvonnet.debridxtreamiptv.data.model.XtreamSeriesDetailResponse
import com.tvonnet.debridxtreamiptv.data.model.XtreamSeriesInfo
import com.tvonnet.debridxtreamiptv.data.onFailure
import com.tvonnet.debridxtreamiptv.data.onSuccess
import com.tvonnet.debridxtreamiptv.data.prefs.CredentialsPreferences
import com.tvonnet.debridxtreamiptv.data.repository.XtreamRepository
import com.tvonnet.debridxtreamiptv.debug.PlaybackDiagnosticsRecorder
import com.tvonnet.debridxtreamiptv.player.stabilized.PlayerActivity
import dagger.hilt.android.AndroidEntryPoint
import androidx.activity.viewModels
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@AndroidEntryPoint
class SeriesDetailActivity : AppCompatActivity() {

    companion object {
        private const val MAX_AUTO_FALLBACK_ATTEMPTS = 3
        const val EXTRA_SERIES_ID = "SERIES_ID"
        const val EXTRA_SERIES_NAME = "SERIES_NAME"
        const val EXTRA_SERIES_COVER = "SERIES_COVER"
        const val EXTRA_SERIES_BACKDROP = "SERIES_BACKDROP"
        const val EXTRA_SERIES_PLOT = "SERIES_PLOT"
        const val EXTRA_SERIES_CAST = "SERIES_CAST"
        const val EXTRA_SERIES_DIRECTOR = "SERIES_DIRECTOR"
        const val EXTRA_SERIES_GENRE = "SERIES_GENRE"
        const val EXTRA_SERIES_RELEASE_DATE = "SERIES_RELEASE_DATE"
        const val EXTRA_SERIES_RATING = "SERIES_RATING"
        const val EXTRA_SERIES_TRAILER = "SERIES_TRAILER"
        const val EXTRA_IS_DEBRID = "IS_DEBRID"
    }

    @Inject
    lateinit var repository: XtreamRepository
    
    @Inject
    lateinit var credentialsPreferences: CredentialsPreferences

    @Inject
    lateinit var tmdbRemoteDataSource: com.tvonnet.debridxtreamiptv.data.debrid.source.TmdbRemoteDataSource

    @Inject
    lateinit var unifiedSourceProvider: com.tvonnet.debridxtreamiptv.data.debrid.repository.UnifiedSourceProvider

    @Inject
    lateinit var debridPlaybackRepository: com.tvonnet.debridxtreamiptv.data.debrid.repository.DebridPlaybackRepository

    @Inject
    lateinit var playbackResolver: com.tvonnet.debridxtreamiptv.data.debrid.repository.PlaybackResolver

    @Inject
    lateinit var seriesRepositoryV2: com.tvonnet.debridxtreamiptv.features.seriesv2.data.repository.XtreamSeriesRepositoryV2

    @Inject
    lateinit var watchedStateRepository: com.tvonnet.debridxtreamiptv.data.repository.WatchedStateRepository

    private var isDebridContent: Boolean = false

    private lateinit var ivBackdrop: ImageView
    private lateinit var ivPoster: ImageView
    private lateinit var tvTitle: TextView
    private lateinit var tvRatingStars: TextView
    private lateinit var tvRatingPercentage: TextView
    private lateinit var tvGenreYear: TextView
    private lateinit var tvDirector: TextView
    private lateinit var tvCast: TextView
    private lateinit var tvDescription: TextView
    private lateinit var rvSeasons: RecyclerView
    private lateinit var rvEpisodes: RecyclerView
    private lateinit var tvEmptyEpisodes: TextView
    private lateinit var btnWatchNow: AppCompatButton
    private lateinit var btnWatchTrailer: AppCompatButton
    private lateinit var btnAddFavorite: ImageButton
    private lateinit var btnRefresh: Button
    private lateinit var loadingOverlay: View
    private lateinit var tvLoadingMessage: TextView

    // New cinematic views
    private lateinit var tvContentType: TextView
    private lateinit var tvYear: TextView
    private lateinit var tvSeasonEpisodeCount: TextView
    private lateinit var tvQualityBadge: TextView
    private lateinit var tvCcBadge: TextView
    private lateinit var layoutRdSummary: LinearLayout
    private lateinit var tvRdSummary: TextView
    private lateinit var layoutResume: LinearLayout
    private lateinit var tvResumeLabel: TextView
    private lateinit var tvResumeRight: TextView
    private lateinit var vResumeProgress: View
    private lateinit var tvResumeEpTitle: TextView
    private lateinit var btnSeasonSelector: AppCompatButton
    private lateinit var tvSeasonProgress: TextView
    private lateinit var tvPremiumBadge: TextView

    private var isFavorite: Boolean = false
    /** SD-3: season/episode UI + watched-progress state (single source of truth). */
    private lateinit var seriesSeasonUi: SeriesSeasonUi

    private val viewModel: SeriesDetailViewModel by viewModels()

    private var seriesId: String? = null
    private var seriesName: String? = null
    private var seriesCover: String? = null
    private var seriesBackdrop: String? = null
    private var seriesPlot: String? = null
    private var seriesCast: String? = null
    private var seriesDirector: String? = null
    private var seriesGenre: String? = null
    private var seriesReleaseDate: String? = null
    private var seriesRating: String? = null
    /** SD-1: trailer resolution + "Watch Trailer" button state. */
    private lateinit var trailerController: SeriesTrailerController

    /** SD-4b: Debrid per-episode source state + acquisition (single source of truth). */
    private lateinit var seriesDebridSources: SeriesDebridSourceController
    private var openedFromPlaybackFailure: Boolean = false
    // Consecutive automatic source-failover attempts; the picker resets it so a
    // bad night of sources degrades to manual choice instead of an endless chain.
    private var autoFallbackAttempts = 0

    private val playerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        val shouldReturnToSources =
            result.resultCode == Activity.RESULT_OK &&
                data?.getBooleanExtra(PlayerActivity.EXTRA_RETURN_TO_SOURCES, false) == true
        if (shouldReturnToSources) {
            val autoPlayNext =
                data?.getBooleanExtra(PlayerActivity.EXTRA_AUTO_PLAY_NEXT, false) == true
            val failedStreamId = data?.getStringExtra(PlayerActivity.EXTRA_FAILED_STREAM_ID)
            val failReason = data?.getStringExtra(PlayerActivity.EXTRA_FAIL_REASON)
            val episode = seriesDebridSources.lastDebridEpisode
            if (!failedStreamId.isNullOrBlank() && episode != null) {
                seriesDebridSources.failedDebridStreamIdsByEpisode.getOrPut(episode.id) { linkedSetOf() }.add(failedStreamId)
                seriesDebridSources.markDebridEpisodeSourceCached(episode.id, failedStreamId, false)
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
            if (allowAutoPlayNext && !failedStreamId.isNullOrBlank() && episode != null) {
                autoFallbackAttempts++
                autoPlayNextDebridEpisodeSource(episode, failedStreamId)
            } else {
                autoFallbackAttempts = 0
                seriesDebridSources.lastDebridEpisode?.let {
                    seriesDebridSources.fetchAndShowDebridSources(it, seriesDebridSources.consumeDebridReturnFocusStreamIds(it.id))
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        setContentView(R.layout.fragment_series_detail)

        // Repository and credentials are now injected via Hilt
        // No need to manually create instances
        
        initViews()
        getSeriesDataFromIntent()
        openedFromPlaybackFailure = intent.getBooleanExtra(PlayerActivity.EXTRA_OPENED_FROM_PLAYBACK_FAILURE, false)
        trailerController.refreshButtonState()
        
        displaySeriesInfo()
        updateFavoriteState()
        ensureRepositoryInitialized()
        setupClickListeners()
        loadSeriesDetail()
        observeViewModel()
    }



    private fun initViews() {
        ivBackdrop = findViewById(R.id.iv_backdrop)
        ivPoster = findViewById(R.id.iv_poster)
        tvTitle = findViewById(R.id.tv_title)
        tvRatingStars = findViewById(R.id.tv_rating_stars)
        tvRatingPercentage = findViewById(R.id.tv_rating_percentage)
        tvGenreYear = findViewById(R.id.tv_genre_year)
        tvDirector = findViewById(R.id.tv_director)
        tvCast = findViewById(R.id.tv_cast)
        tvDescription = findViewById(R.id.tv_description)
        rvSeasons = findViewById(R.id.rv_seasons)
        rvEpisodes = findViewById(R.id.rv_episodes)
        tvEmptyEpisodes = findViewById(R.id.tv_empty_episodes)
        tvEmptyEpisodes.visibility = View.GONE
        btnWatchNow = findViewById(R.id.btn_watch_now)
        btnWatchTrailer = findViewById(R.id.btn_watch_trailer)
        btnAddFavorite = findViewById(R.id.btn_add_favorite)
        btnRefresh = findViewById(R.id.btn_refresh)
        loadingOverlay = findViewById(R.id.layout_loading_overlay)
        tvLoadingMessage = findViewById(R.id.tv_loading_message)

        // New cinematic views
        tvContentType = findViewById(R.id.tv_content_type)
        tvYear = findViewById(R.id.tv_year)
        tvSeasonEpisodeCount = findViewById(R.id.tv_season_episode_count)
        tvQualityBadge = findViewById(R.id.tv_quality_badge)
        tvCcBadge = findViewById(R.id.tv_cc_badge)
        layoutRdSummary = findViewById(R.id.layout_rd_summary)
        tvRdSummary = findViewById(R.id.tv_rd_summary)
        layoutResume = findViewById(R.id.layout_resume)
        tvResumeLabel = findViewById(R.id.tv_resume_label)
        tvResumeRight = findViewById(R.id.tv_resume_right)
        vResumeProgress = findViewById(R.id.v_resume_progress)
        tvResumeEpTitle = findViewById(R.id.tv_resume_ep_title)
        btnSeasonSelector = findViewById(R.id.btn_season_selector)
        tvSeasonProgress = findViewById(R.id.tv_season_progress)
        tvPremiumBadge = findViewById(R.id.tv_premium_badge)

        rvSeasons.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        rvSeasons.setHasFixedSize(true)
        rvEpisodes.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        rvEpisodes.setHasFixedSize(true)

        setupCollaborators()
        setupFocusAnimations()
    }

    /** SD-1/SD-3/SD-4b: construct the extracted collaborators once views are bound. */
    private fun setupCollaborators() {
        trailerController = SeriesTrailerController(this, tmdbRemoteDataSource, btnWatchTrailer) { seriesId }

        seriesSeasonUi = SeriesSeasonUi(
            activity = this,
            rvSeasons = rvSeasons,
            rvEpisodes = rvEpisodes,
            tvEmptyEpisodes = tvEmptyEpisodes,
            btnWatchNow = btnWatchNow,
            btnSeasonSelector = btnSeasonSelector,
            tvSeasonProgress = tvSeasonProgress,
            tvSeasonEpisodeCount = tvSeasonEpisodeCount,
            layoutResume = layoutResume,
            tvResumeLabel = tvResumeLabel,
            tvResumeRight = tvResumeRight,
            vResumeProgress = vResumeProgress,
            tvResumeEpTitle = tvResumeEpTitle,
            watchedStateRepository = watchedStateRepository,
            isDebrid = { isDebridContent },
            seriesId = { seriesId },
            seriesName = { seriesName },
            seriesReleaseDate = { seriesReleaseDate },
            onPlayEpisode = { playEpisode(it) }
        )

        seriesDebridSources = SeriesDebridSourceController(
            activity = this,
            seasonUi = seriesSeasonUi,
            unifiedSourceProvider = unifiedSourceProvider,
            debridPlaybackRepository = debridPlaybackRepository,
            seriesRepositoryV2 = seriesRepositoryV2,
            credentialsPreferences = credentialsPreferences,
            layoutRdSummary = layoutRdSummary,
            tvRdSummary = tvRdSummary,
            tvQualityBadge = tvQualityBadge,
            seriesId = { seriesId },
            seriesName = { seriesName },
            seriesBackdrop = { seriesBackdrop },
            seriesReleaseDate = { seriesReleaseDate },
            showLoading = { showLoading(it) },
            onPlayDebridSource = { source, ep -> playDebridEpisode(source, ep) },
            onPlayIptvSource = { source, ep -> playIptvSourceFromDebrid(source, ep) }
        )
    }

    private fun getSeriesDataFromIntent() {
        intent?.let { data ->
            seriesId = data.getStringExtra(EXTRA_SERIES_ID)
            seriesName = data.getStringExtra(EXTRA_SERIES_NAME)
            seriesCover = data.getStringExtra(EXTRA_SERIES_COVER)
            seriesBackdrop = data.getStringExtra(EXTRA_SERIES_BACKDROP)
            seriesPlot = data.getStringExtra(EXTRA_SERIES_PLOT)
            seriesCast = data.getStringExtra(EXTRA_SERIES_CAST)
            seriesDirector = data.getStringExtra(EXTRA_SERIES_DIRECTOR)
            seriesGenre = data.getStringExtra(EXTRA_SERIES_GENRE)
            seriesReleaseDate = data.getStringExtra(EXTRA_SERIES_RELEASE_DATE)
            seriesReleaseDate = data.getStringExtra(EXTRA_SERIES_RELEASE_DATE)
            seriesRating = data.getStringExtra(EXTRA_SERIES_RATING)
            trailerController.setTrailerUrl(data.getStringExtra(EXTRA_SERIES_TRAILER))
            isDebridContent = data.getBooleanExtra(EXTRA_IS_DEBRID, false)
        }

        if (seriesId.isNullOrBlank()) {
            Toast.makeText(this, R.string.series_detail_error_loading, Toast.LENGTH_SHORT).show()
            finish()
            return
        }
    }

    private fun setupClickListeners() {
        btnWatchNow.setOnClickListener {
            seriesSeasonUi.selectedEpisode?.let { episode ->
                val hasResume = isDebridContent &&
                    !seriesSeasonUi.isWatched(episode.id) &&
                    seriesSeasonUi.hasStoredProgress(episode.id)
                if (hasResume) {
                    resumeDebridEpisode(episode)
                } else {
                    playEpisode(episode)
                }
            }
        }

        btnWatchTrailer.setOnClickListener {
            trailerController.launch()
        }

        btnAddFavorite.setOnClickListener {
            toggleFavorite()
        }

        btnRefresh.setOnClickListener {
            seriesId?.let { id ->
                refreshSeries(id)
            }
        }

        btnSeasonSelector.setOnClickListener {
            seriesSeasonUi.showSeasonPopup()
        }
    }

    private fun setupFocusAnimations() {
        val focusTargets = listOf<View>(btnWatchNow, btnWatchTrailer, btnAddFavorite, btnSeasonSelector)
        focusTargets.forEach { view ->
            view.setOnFocusChangeListener { v, hasFocus ->
                val scale = if (hasFocus) 1.05f else 1.0f
                val alpha = if (hasFocus) 1.0f else 0.9f
                v.animate().scaleX(scale).scaleY(scale).alpha(alpha).setDuration(150).start()
            }
        }
    }

    private fun displaySeriesInfo() {
        tvTitle.text = seriesName ?: getString(R.string.series_detail_title_placeholder)

        val ratingValue = seriesRating?.toDoubleOrNull() ?: 0.0
        tvRatingPercentage.text = String.format("%.1f", ratingValue)

        // Year
        val year = seriesReleaseDate?.takeIf { it.isNotBlank() }?.take(4)
        tvYear.text = year ?: ""

        // Genre (just the genre text, no year)
        tvGenreYear.text = seriesGenre?.takeIf { it.isNotBlank() } ?: ""

        // Content type label
        tvContentType.text = if (isDebridContent) "LIMITED SERIES" else "TV SERIES"

        tvDescription.text = seriesPlot?.takeIf { it.isNotBlank() }
            ?: getString(R.string.series_detail_description_placeholder)

        // Backdrop
        val backdropUrl = seriesBackdrop ?: seriesCover
        if (!backdropUrl.isNullOrBlank()) {
            Glide.with(this)
                .load(backdropUrl)
                .centerCrop()
                .into(ivBackdrop)
        } else {
            ivBackdrop.setImageDrawable(null)
        }

        seriesSeasonUi.refreshControls()
    }




    private fun ensureRepositoryInitialized() {
        repository.ensureInitialized(
            credentialsPreferences.getServerUrl(),
            credentialsPreferences.getUsername(),
            credentialsPreferences.getPassword()
        )
    }

    private fun loadSeriesDetail() {
        val id = seriesId ?: return

        if (isDebridContent) {
            showLoading(true)
            lifecycleScope.launch {
                val result = fetchDebridSeriesDetail(tmdbRemoteDataSource, id)
                handleSeriesDetailResult(id, result, allowRetry = false)
            }
            return
        }

        // Xtream Content - Database First
        viewModel.loadSeries(id)
    }

    private fun refreshSeries(id: String) {
        if (!isDebridContent) {
            viewModel.refreshSeries(id)
        }
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            viewModel.uiState.collect { state ->
                when (state) {
                    is SeriesDetailUiState.Loading -> showLoading(true)
                    is SeriesDetailUiState.Success -> {
                        val detail = state.detail
                        // If we have content, hide loading
                        val hasContent = !detail.seasons.isNullOrEmpty() || !detail.episodes.isNullOrEmpty()
                        if (hasContent) {
                            showLoading(false)
                        }
                        applyDetailInfo(detail.info)
                        seriesSeasonUi.buildSeasonList(detail)
                    }
                    is SeriesDetailUiState.Error -> {
                        showLoading(false)
                         // Handle error if needed, maybe fallback
                    }
                     else -> {}
                }
            }
        }
    }

    private suspend fun handleSeriesDetailResult(
        seriesId: String,
        result: Result<XtreamSeriesDetailResponse>,
        allowRetry: Boolean
    ) {
        result.onSuccess { detail ->
            val needsRefresh = detail.episodes.isNullOrEmpty() && allowRetry
            if (needsRefresh) {
                val refreshed = withContext(Dispatchers.IO) {
                    repository.fetchSeriesDetail(seriesId, forceRefresh = true)
                }
                handleSeriesDetailResult(seriesId, refreshed, allowRetry = false)
                return@onSuccess
            }
            applyDetailInfo(detail.info)
            seriesSeasonUi.buildSeasonList(detail)
            showLoading(false)
        }.onFailure { error ->
            handleSeriesDetailError(error)
        }
    }

    private fun applyDetailInfo(info: XtreamSeriesDetailInfo?) {
        if (info == null) return

        seriesName = info.name ?: seriesName
        seriesPlot = info.plot ?: seriesPlot
        seriesCast = info.cast ?: seriesCast
        seriesDirector = info.director ?: seriesDirector
        seriesGenre = info.genre ?: seriesGenre
        seriesReleaseDate = info.releaseDate ?: seriesReleaseDate
        seriesRating = info.rating ?: seriesRating
        trailerController.mergeTrailerUrl(info.youtube_trailer)
        seriesCover = com.tvonnet.debridxtreamiptv.util.GlobalConfig.resolveIconUrl(info.cover) ?: seriesCover
        seriesBackdrop = info.backdropPathString ?: seriesBackdrop

        displaySeriesInfo()
        trailerController.refreshButtonState()
    }

    override fun onResume() {
        super.onResume()
        // Returning from the player: re-read watched-state so progress bars,
        // the continue bar and the Play/Resume label reflect the latest position.
        if (::seriesSeasonUi.isInitialized) {
            seriesSeasonUi.onResume()
        }
    }

    private suspend fun handleSeriesDetailError(error: Exception) {
        showLoading(false)

        val fallbackSeries = seriesId?.let { repository.getSeriesById(it) }
        if (fallbackSeries != null) {
            applyFallbackSeriesInfo(fallbackSeries)
        } else {
            seriesSeasonUi.showEmptyEpisodes(getString(R.string.series_detail_empty_episodes_error))
        }

        val errorMessage = error.message ?: getString(R.string.series_detail_error_loading)
        android.util.Log.e("SeriesDetailActivity", "handleSeriesDetailError: Showing Toast with message: '$errorMessage'", error)
        Toast.makeText(
            this,
            errorMessage,
            Toast.LENGTH_LONG
        ).show()
    }

    private fun applyFallbackSeriesInfo(seriesInfo: XtreamSeriesInfo) {
        seriesName = seriesInfo.name ?: seriesName
        seriesPlot = seriesInfo.plot ?: seriesPlot
        seriesCast = seriesInfo.cast ?: seriesCast
        seriesDirector = seriesInfo.director ?: seriesDirector
        seriesGenre = seriesInfo.genre ?: seriesGenre
        seriesReleaseDate = seriesInfo.releaseDate ?: seriesReleaseDate
        seriesRating = seriesInfo.rating ?: seriesRating
        seriesCover = com.tvonnet.debridxtreamiptv.util.GlobalConfig.resolveIconUrl(seriesInfo.cover) ?: seriesCover
        seriesBackdrop = com.tvonnet.debridxtreamiptv.util.GlobalConfig.resolveIconUrl(seriesInfo.cover) ?: seriesBackdrop

        displaySeriesInfo()

        val fallbackDetail = buildFallbackSeriesDetail(seriesInfo) {
            getString(R.string.series_detail_season_name, it)
        }
        if (fallbackDetail != null) {
            seriesSeasonUi.buildSeasonList(fallbackDetail)
        } else {
            seriesSeasonUi.showEmptyEpisodes(getString(R.string.series_detail_empty_episodes_error))
        }
    }


    /**
     * Resume button: play the in-progress episode directly instead of re-opening
     * the source list. Reuses the source picked last time when possible, otherwise
     * silently fetches sources and auto-picks the best cached one. The player
     * restores the saved position from watch history.
     */
    private fun resumeDebridEpisode(episode: EpisodeUiModel) {
        val cached = seriesDebridSources.cachedDebridSourcesByEpisode[episode.id]
        if (!cached.isNullOrEmpty()) {
            val source = seriesDebridSources.pickResumeSource(episode, cached)
            if (source != null) {
                playDebridEpisode(source, episode)
                return
            }
        }

        val fetchContext = seriesDebridSources.resolveDebridEpisodeFetchContext(episode)
        if (fetchContext == null) {
            seriesDebridSources.fetchAndShowDebridSources(episode)
            return
        }

        showLoading(true)
        lifecycleScope.launch {
            try {
                val (seasonNumber, episodeNumber) = fetchContext
                val sources = withContext(Dispatchers.IO) {
                    unifiedSourceProvider.getSeriesEpisodeSources(
                        seriesId = seriesId,
                        seasonNumber = seasonNumber,
                        episodeNumber = episodeNumber,
                        title = seriesName,
                        yearHint = seriesReleaseDate?.take(4)
                    )
                }
                seriesDebridSources.cachedDebridSourcesByEpisode[episode.id] = sources
                showLoading(false)
                val source = seriesDebridSources.pickResumeSource(episode, sources)
                if (source != null) {
                    playDebridEpisode(source, episode)
                } else {
                    seriesDebridSources.fetchAndShowDebridSources(episode)
                }
            } catch (e: Exception) {
                android.util.Log.e("SeriesDetailActivity", "Resume source fetch failed: ${e.message}", e)
                showLoading(false)
                seriesDebridSources.fetchAndShowDebridSources(episode)
            }
        }
    }

    private fun playEpisode(episode: EpisodeUiModel) {
        if (isDebridContent) {
            seriesDebridSources.fetchAndShowDebridSources(episode)
            return
        }

        val serverUrl = credentialsPreferences.getServerUrl()
        val username = credentialsPreferences.getUsername()
        val password = credentialsPreferences.getPassword()

        if (serverUrl.isNullOrBlank() || username.isNullOrBlank() || password.isNullOrBlank()) {
            Toast.makeText(this, R.string.series_detail_no_server_credentials, Toast.LENGTH_SHORT).show()
            return
        }

        val streamUrl = repository.buildSeriesEpisodeStreamUrl(
            episodeId = episode.id,
            containerExtension = episode.containerExtension,
            baseServerUrl = serverUrl
        )

        // Saved resume position for this episode (null when watched or never started).
        val resumeMs = seriesSeasonUi.resumePositionMs(episode.id)
        val intent = PlayerActivity.createIntent(
            context = this,
            streamUrl = streamUrl,
            title = episode.title,
            contentId = episode.id,
            contentType = ContentType.EPISODE,
            posterUrl = seriesCover ?: episode.thumbnailUrl,
            seriesTitle = seriesName,
            episodeTitle = episode.title,
            seasonNumber = seriesSeasonUi.selectedSeasonKey?.toIntOrNull(),
            episodeNumber = episode.episodeNumber,
            startPositionMs = resumeMs
        )
        seriesId?.let { intent.putExtra(PlayerActivity.EXTRA_SERIES_ID, it) }
        startActivity(intent)
    }

    /** Play an injected IPTV source picked from the Debrid source sheet. */
    private fun playIptvSourceFromDebrid(
        source: com.tvonnet.debridxtreamiptv.data.repository.MovieSource,
        episode: EpisodeUiModel
    ) {
        val seasonNumber = seriesSeasonUi.selectedSeasonKey?.toIntOrNull()
        val episodeNumber = episode.episodeNumber
        val iptvSeriesId = source.stream.stream_id?.removePrefix("iptv:")

        lifecycleScope.launch {
            var url = source.stream.direct_source
            var iptvEpisodeId = source.stream.custom_sid
            if (url.isNullOrBlank()) {
                if (iptvSeriesId.isNullOrBlank() || seasonNumber == null || episodeNumber == null) {
                    Toast.makeText(this@SeriesDetailActivity, "Stream unavailable", Toast.LENGTH_SHORT).show()
                    return@launch
                }
                showLoading(true)
                val resolved = withContext(Dispatchers.IO) {
                    try {
                        seriesRepositoryV2.resolveIptvPlayUrl(iptvSeriesId, seasonNumber, episodeNumber)
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        android.util.Log.w("SeriesDetailActivity", "resolveIptvPlayUrl failed: ${e.message}")
                        null
                    }
                }
                showLoading(false)
                if (resolved == null) {
                    Toast.makeText(
                        this@SeriesDetailActivity,
                        "Episode not available in this IPTV category",
                        Toast.LENGTH_LONG
                    ).show()
                    return@launch
                }
                url = resolved.first
                iptvEpisodeId = resolved.third
            }

            // Saved resume position for this episode (null when watched or never started).
            val resumeMs = seriesSeasonUi.resumePositionMs(episode.id)

            val intent = PlayerActivity.createIntent(
                context = this@SeriesDetailActivity,
                streamUrl = url!!,
                title = episode.title,
                contentId = iptvEpisodeId ?: "iptv:$iptvSeriesId:$seasonNumber:$episodeNumber",
                contentType = ContentType.EPISODE,
                posterUrl = seriesCover ?: episode.thumbnailUrl,
                backdropUrl = seriesBackdrop,
                seriesTitle = seriesName,
                episodeTitle = episode.title,
                seasonNumber = seasonNumber,
                episodeNumber = episodeNumber,
                startPositionMs = resumeMs
            )
            iptvSeriesId?.let { intent.putExtra(PlayerActivity.EXTRA_SERIES_ID, it) }
            startActivity(intent)
        }
    }

    private fun playDebridEpisode(source: com.tvonnet.debridxtreamiptv.data.repository.MovieSource, episode: EpisodeUiModel) {
        seriesDebridSources.lastDebridEpisode = episode
        seriesDebridSources.selectedDebridStreamIdByEpisode[episode.id] = source.stream.stream_id
        val stream = source.stream
        val magnet = stream.direct_source
        val infoHash = stream.stream_id
        val seasonNumber = seriesSeasonUi.selectedSeasonKey?.toIntOrNull()
        val episodeNumber = episode.episodeNumber
        val tmdbId = seriesId
        val seriesTitle = seriesName
        // Saved resume position for this episode (null when watched or never started)
        val resumeMs = seriesSeasonUi.resumePositionMs(episode.id)
        val isDirectHttp = magnet?.startsWith("http", ignoreCase = true) == true &&
            magnet.endsWith(".torrent", ignoreCase = true).not()

        val launchDirectEpisode = { directUrl: String ->
            PlaybackDiagnosticsRecorder.record(
                this@SeriesDetailActivity,
                "playback_launch",
                PlaybackDiagnosticsRecorder.contentFields(
                    kind = "series_episode",
                    tmdbId = tmdbId,
                    season = seasonNumber,
                    episode = episodeNumber
                ) + PlaybackDiagnosticsRecorder.sourceFields(this@SeriesDetailActivity, source) +
                    PlaybackDiagnosticsRecorder.playbackFields(
                        context = this@SeriesDetailActivity,
                        url = directUrl,
                        headers = source.headers,
                        playbackSource = com.tvonnet.debridxtreamiptv.player.stabilized.PlaybackSource.DEBRID.name,
                        directDebridPlayback = true
                    ) + mapOf("launchPath" to "direct")
            )
            val intent = PlayerActivity.createIntent(
                context = this@SeriesDetailActivity,
                streamUrl = directUrl,
                title = "${seriesName} - S${seriesSeasonUi.selectedSeasonKey}E${episode.episodeNumber}",
                contentId = "$seriesId:$seasonNumber:$episodeNumber",
                contentType = ContentType.EPISODE,
                playbackSource = com.tvonnet.debridxtreamiptv.player.stabilized.PlaybackSource.DEBRID,
                posterUrl = seriesCover ?: episode.thumbnailUrl,
                backdropUrl = seriesBackdrop,
                headers = source.headers,
                tmdbId = tmdbId,
                seriesTitle = seriesTitle,
                episodeTitle = episode.title,
                seasonNumber = seasonNumber,
                episodeNumber = episodeNumber,
                directDebridPlayback = true,
                debridProvider = source.provider,
                debridSourceType = source.sourceType,
                debridSourceName = source.sourceName,
                debridLanguages = source.languages,
                debridQuality = source.quality,
                debridStreamId = source.stream.stream_id,
                debridBingeGroup = source.bingeGroup,
                debridFileIdx = source.fileIdx,
                startPositionMs = resumeMs
            )
            intent.putExtra(PlayerActivity.EXTRA_RETURN_TO_SOURCES, true)
            playerLauncher.launch(intent)
        }

        if (isDirectHttp && !magnet.isNullOrBlank()) {
            if (debridPlaybackRepository.requiresDirectProxyReadinessCheck(
                    magnet,
                    source.provider,
                    source.sourceName,
                    source.sourceType
                )
            ) {
                lifecycleScope.launch {
                    showLoading(true)
                    val readyResult = withContext(Dispatchers.IO) {
                        debridPlaybackRepository.getAddonProxyPlaybackReadiness(
                            magnet,
                            source.headers,
                            source.provider,
                            source.sourceName,
                            source.sourceType,
                            diagnosticsContext = this@SeriesDetailActivity
                        )
                    }
                    showLoading(false)

                    val readiness = (readyResult as? Result.Success)?.data
                        ?: AddonProxyReadiness.UNCERTAIN
                    if (readiness == AddonProxyReadiness.TERMINAL) {
                        source.stream.stream_id?.let { streamId ->
                            seriesDebridSources.failedDebridStreamIdsByEpisode.getOrPut(episode.id) { linkedSetOf() }.add(streamId)
                        }
                        seriesDebridSources.markDebridEpisodeSourceCached(episode.id, source.stream.stream_id, false)
                        val message = "Source is unavailable. Choose another source."
                        Toast.makeText(this@SeriesDetailActivity, message, Toast.LENGTH_LONG).show()
                        seriesDebridSources.fetchAndShowDebridSources(episode, seriesDebridSources.consumeDebridReturnFocusStreamIds(episode.id))
                        return@launch
                    }

                    if (readiness == AddonProxyReadiness.READY) {
                        seriesDebridSources.markDebridEpisodeSourceCached(episode.id, source.stream.stream_id, true)
                    }
                    launchDirectEpisode(magnet)
                }
                return
            }

            launchDirectEpisode(magnet)
            return
        }

        lifecycleScope.launch {
            showLoading(true)
            
            val result = withContext(Dispatchers.IO) {
                playbackResolver.resolve(
                    source = "debrid",
                    streamUrl = magnet,
                    isExpired = true,
                    infoHash = infoHash,
                    magnet = magnet,
                    seasonNumber = seasonNumber,
                    episodeNumber = episodeNumber,
                    episodeTitle = seriesName
                )
            }
            
            showLoading(false)
            
            when (result) {
                is com.tvonnet.debridxtreamiptv.data.debrid.repository.ResolutionResult.Success -> {
                    PlaybackDiagnosticsRecorder.record(
                        this@SeriesDetailActivity,
                        "playback_launch",
                        PlaybackDiagnosticsRecorder.contentFields(
                            kind = "series_episode",
                            tmdbId = tmdbId,
                            season = seasonNumber,
                            episode = episodeNumber
                        ) + PlaybackDiagnosticsRecorder.sourceFields(this@SeriesDetailActivity, source) +
                            PlaybackDiagnosticsRecorder.playbackFields(
                                context = this@SeriesDetailActivity,
                                url = result.url,
                                headers = source.headers,
                                playbackSource = com.tvonnet.debridxtreamiptv.player.stabilized.PlaybackSource.DEBRID.name,
                                directDebridPlayback = false
                            ) + mapOf("launchPath" to "resolver_backed")
                    )
                    val intent = PlayerActivity.createIntent(
                        context = this@SeriesDetailActivity,
                        streamUrl = result.url,
                        title = "${seriesName} - S${seriesSeasonUi.selectedSeasonKey}E${episode.episodeNumber}",
                        contentId = "$seriesId:$seasonNumber:$episodeNumber",
                        contentType = ContentType.EPISODE,
                        playbackSource = com.tvonnet.debridxtreamiptv.player.stabilized.PlaybackSource.DEBRID,
                        posterUrl = seriesCover ?: episode.thumbnailUrl,
                        backdropUrl = seriesBackdrop,
                        tmdbId = tmdbId,
                        seriesTitle = seriesTitle,
                        episodeTitle = episode.title,
                        seasonNumber = seasonNumber,
                        episodeNumber = episodeNumber,
                        debridInfoHash = infoHash,
                        debridMagnet = magnet,
                        debridProvider = source.provider,
                        debridSourceType = source.sourceType,
                        debridSourceName = source.sourceName,
                        debridLanguages = source.languages,
                        debridQuality = source.quality,
                        debridStreamId = source.stream.stream_id,
                        debridBingeGroup = source.bingeGroup,
                        debridFileIdx = source.fileIdx,
                        startPositionMs = resumeMs
                    )
                    intent.putExtra(PlayerActivity.EXTRA_RETURN_TO_SOURCES, true)
                    playerLauncher.launch(intent)
                }
                is com.tvonnet.debridxtreamiptv.data.debrid.repository.ResolutionResult.RefreshRequired -> {
                    Toast.makeText(this@SeriesDetailActivity, "Refresh required", Toast.LENGTH_SHORT).show()
                }
                is com.tvonnet.debridxtreamiptv.data.debrid.repository.ResolutionResult.Error -> {
                    seriesDebridSources.markDebridEpisodeSourceCached(episode.id, infoHash, false)
                    if (shouldTryNextDebridSource(result.failureType) && !infoHash.isNullOrBlank()) {
                        notifyDebridFailure(result.message, autoPlayNext = true)
                        autoPlayNextDebridEpisodeSource(episode, infoHash)
                    } else {
                        Toast.makeText(this@SeriesDetailActivity, result.message, Toast.LENGTH_LONG).show()
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

    private fun autoPlayNextDebridEpisodeSource(episode: EpisodeUiModel, failedStreamId: String) {
        val cachedSources = seriesDebridSources.cachedDebridSourcesByEpisode[episode.id]
        if (!cachedSources.isNullOrEmpty()) {
            val nextSource = seriesDebridSources.pickNextDebridEpisodeSource(episode.id, cachedSources, failedStreamId)
            if (nextSource != null) {
                seriesDebridSources.selectedDebridStreamIdByEpisode[episode.id] = nextSource.stream.stream_id
                Toast.makeText(this, "Trying next source...", Toast.LENGTH_SHORT).show()
                playDebridEpisode(nextSource, episode)
                return
            }
            seriesDebridSources.fetchAndShowDebridSources(episode)
            return
        }

        val episodeFetchContext = seriesDebridSources.resolveDebridEpisodeFetchContext(episode)
        if (episodeFetchContext == null) {
            val message = "Episode metadata missing for source fetch"
            Toast.makeText(this@SeriesDetailActivity, message, Toast.LENGTH_LONG).show()
            seriesDebridSources.fetchAndShowDebridSources(episode)
            return
        }

        lifecycleScope.launch {
            val (seasonNumber, episodeNumber) = episodeFetchContext
            val sources = withContext(Dispatchers.IO) {
                unifiedSourceProvider.getSeriesEpisodeSources(
                    seriesId = seriesId,
                    seasonNumber = seasonNumber,
                    episodeNumber = episodeNumber,
                    title = seriesName,
                    yearHint = seriesReleaseDate?.take(4)
                )
            }
            seriesDebridSources.cachedDebridSourcesByEpisode[episode.id] = sources
            val nextSource = seriesDebridSources.pickNextDebridEpisodeSource(episode.id, sources, failedStreamId)
            if (nextSource != null) {
                seriesDebridSources.selectedDebridStreamIdByEpisode[episode.id] = nextSource.stream.stream_id
                Toast.makeText(this@SeriesDetailActivity, "Trying next source...", Toast.LENGTH_SHORT).show()
                playDebridEpisode(nextSource, episode)
            } else {
                seriesDebridSources.fetchAndShowDebridSources(episode)
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
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun toggleFavorite() {
        val streamId = seriesId ?: return

        lifecycleScope.launch {
            try {
                val isFavorite = repository.isFavorite(streamId)
                if (isFavorite) {
                    repository.removeFavorite(streamId)
                    Toast.makeText(this@SeriesDetailActivity, R.string.favorite_removed, Toast.LENGTH_SHORT).show()
                    updateFavoriteButton(false)
                } else {
                    repository.addFavorite(
                        streamId = streamId,
                        type = "series",
                        name = seriesName ?: getString(R.string.series_detail_title_placeholder),
                        iconUrl = seriesCover
                    )
                    Toast.makeText(this@SeriesDetailActivity, R.string.favorite_added, Toast.LENGTH_SHORT).show()
                    updateFavoriteButton(true)
                }
            } catch (e: Exception) {
                Toast.makeText(
                    this@SeriesDetailActivity,
                    e.message ?: getString(R.string.series_detail_error_loading),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun updateFavoriteState() {
        val streamId = seriesId ?: return
        lifecycleScope.launch {
            try {
                val isFavorite = repository.isFavorite(streamId)
                updateFavoriteButton(isFavorite)
            } catch (e: Exception) {
                // Ignore and leave default state
            }
        }
    }

    private fun updateFavoriteButton(isFav: Boolean) {
        this.isFavorite = isFav
        if (isFav) {
            btnAddFavorite.setImageResource(R.drawable.ic_favorite)
            btnAddFavorite.imageTintList = android.content.res.ColorStateList.valueOf(0xFFFF3366.toInt())
        } else {
            btnAddFavorite.setImageResource(R.drawable.ic_favorite_border)
            btnAddFavorite.imageTintList = resources.getColorStateList(R.color.cin_detail_focus_text, theme)
        }
    }

    private fun showLoading(show: Boolean) {
        loadingOverlay.visibility = if (show) View.VISIBLE else View.GONE
        tvLoadingMessage.visibility = if (show) View.VISIBLE else View.GONE
        
        if (show) {
            tvEmptyEpisodes.visibility = View.GONE
        }
    }

    private fun showDebridConfigDialog() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Real-Debrid Not Configured")
            .setMessage("You need to configure Real-Debrid settings to play this content. Do you want to configure it now?")
            .setPositiveButton("Configure") { _, _ ->
                 navigateToSettings()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun navigateToSettings() {
        val intent = android.content.Intent(this, com.tvonnet.debridxtreamiptv.ui.MainActivity::class.java)
        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP)
        intent.putExtra("OPEN_SETTINGS", true)
        startActivity(intent)
    }

    override fun onBackPressed() {
        if (isDebridContent) {
            finish()
        } else {
            super.onBackPressed()
        }
    }
}

data class EpisodeUiModel(
    val id: String,
    val title: String,
    val episodeNumber: Int?,
    val description: String?,
    val durationMinutes: Int?,
    val thumbnailUrl: String?,
    val containerExtension: String?,
    val isWatched: Boolean = false,
    val resumePosition: Long = 0
)

data class SeasonUiModel(
    val key: String,
    val displayName: String
)
