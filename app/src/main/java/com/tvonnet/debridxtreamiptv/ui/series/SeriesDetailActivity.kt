package com.tvonnet.debridxtreamiptv.ui.series

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
import com.tvonnet.debridxtreamiptv.data.model.XtreamSeriesDetailInfo
import com.tvonnet.debridxtreamiptv.data.model.XtreamSeriesDetailResponse
import com.tvonnet.debridxtreamiptv.data.model.XtreamSeriesInfo
import com.tvonnet.debridxtreamiptv.data.onFailure
import com.tvonnet.debridxtreamiptv.data.onSuccess
import com.tvonnet.debridxtreamiptv.data.prefs.CredentialsPreferences
import com.tvonnet.debridxtreamiptv.data.repository.XtreamRepository
import com.tvonnet.debridxtreamiptv.player.stabilized.PlayerActivity
import dagger.hilt.android.AndroidEntryPoint
import androidx.activity.viewModels
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import android.content.Context
import com.tvonnet.debridxtreamiptv.util.usePortraitOnTouchDevices

@AndroidEntryPoint
class SeriesDetailActivity : AppCompatActivity() {

    companion object {
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
    /** SD-4c: Debrid playback launch + source-failover. */
    private lateinit var seriesDebridPlayback: SeriesDebridPlaybackController

    // SD-4c: registration stays in the Activity (timing before STARTED is load-bearing);
    // the body is delegated to SeriesDebridPlaybackController.handlePlayerResult.
    private val playerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result -> seriesDebridPlayback.handlePlayerResult(result) }


    // M13 (option B): the TV layout's type is sized for three metres; scale it up on a handset.
    override fun attachBaseContext(newBase: Context) {
        // NOT phoneScaledContext — see MovieDetailActivity: this screen has a real phone layout
        // now, authored in true dp and sp, and scaling it 1.6x would blow it apart.
        super.attachBaseContext(newBase)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // M13: BEFORE super.onCreate on purpose — the orientation has to be settled before the
        // first inflate, or the layout chosen for the old one stays on screen inside the new
        // window (M11 proved that the hard way, in the opposite direction).
        usePortraitOnTouchDevices()
        super.onCreate(savedInstanceState)
        
        setContentView(R.layout.fragment_series_detail)
        // D4: "◄ ► ▲ ▼ NAVIGATE | BACK / ESC RETURN" — the FOURTH D-pad legend found on a touch
        // device (M14b home, M16a IPTV series, D3 debrid setup gate). The phone rulebook bans them.
        if (!resources.getBoolean(R.bool.ui_uses_dpad_focus)) {
            findViewById<android.view.View>(R.id.layout_hint_bar)?.visibility = android.view.View.GONE
            // The phone layout carries a real back arrow in its app bar. A touchscreen has no
            // BACK button of its own to fall back on beyond the system gesture, and the phone
            // rulebook says every screen must be leavable by something you can see.
            findViewById<android.view.View>(R.id.phone_series_back)?.setOnClickListener {
                onBackPressedDispatcher.onBackPressed()
            }
        }

        // Repository and credentials are now injected via Hilt
        // No need to manually create instances
        
        initViews()
        getSeriesDataFromIntent()
        seriesDebridPlayback.openedFromPlaybackFailure = intent.getBooleanExtra(PlayerActivity.EXTRA_OPENED_FROM_PLAYBACK_FAILURE, false)
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
        setupSeasonUi()
        setupDebridSourceController()
        setupDebridPlaybackController()
    }

    private fun setupSeasonUi() {
        trailerController = SeriesTrailerController(this, tmdbRemoteDataSource, btnWatchTrailer) { seriesId }

        seriesSeasonUi = SeriesSeasonUi(
            activity = this,
            watchedStateRepository = watchedStateRepository,
            views = SeriesSeasonUi.Views(
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
            ),
            host = SeriesSeasonUi.Host(
                isDebrid = { isDebridContent },
                seriesId = { seriesId },
                seriesName = { seriesName },
                seriesReleaseDate = { seriesReleaseDate },
                onPlayEpisode = { seriesDebridPlayback.playEpisode(it) }
            )
        )

    }

    private fun setupDebridSourceController() {
        seriesDebridSources = SeriesDebridSourceController(
            activity = this,
            seasonUi = seriesSeasonUi,
            deps = SeriesDebridSourceController.Deps(
                unifiedSourceProvider = unifiedSourceProvider,
                debridPlaybackRepository = debridPlaybackRepository,
                seriesRepositoryV2 = seriesRepositoryV2,
                credentialsPreferences = credentialsPreferences,
            ),
            views = SeriesDebridSourceController.Views(
                layoutRdSummary = layoutRdSummary,
                tvRdSummary = tvRdSummary,
                tvQualityBadge = tvQualityBadge,
            ),
            host = SeriesDebridSourceController.Host(
                seriesId = { seriesId },
                seriesName = { seriesName },
                seriesBackdrop = { seriesBackdrop },
                seriesReleaseDate = { seriesReleaseDate },
                showLoading = { showLoading(it) },
                onPlayDebridSource = { source, ep -> seriesDebridPlayback.playDebridEpisode(source, ep) },
                onPlayIptvSource = { source, ep -> seriesDebridPlayback.playIptvSourceFromDebrid(source, ep) }
            )
        )

    }

    private fun setupDebridPlaybackController() {
        seriesDebridPlayback = SeriesDebridPlaybackController(
            activity = this,
            seasonUi = seriesSeasonUi,
            sources = seriesDebridSources,
            repository = repository,
            deps = SeriesDebridPlaybackController.Deps(
                credentialsPreferences = credentialsPreferences,
                unifiedSourceProvider = unifiedSourceProvider,
                debridPlaybackRepository = debridPlaybackRepository,
                playbackResolver = playbackResolver,
                seriesRepositoryV2 = seriesRepositoryV2,
            ),
            host = SeriesDebridPlaybackController.Host(
                isDebrid = { isDebridContent },
                seriesId = { seriesId },
                seriesName = { seriesName },
                seriesCover = { seriesCover },
                seriesBackdrop = { seriesBackdrop },
                seriesReleaseDate = { seriesReleaseDate },
                showLoading = { showLoading(it) },
                launch = { playerLauncher.launch(it) }
            )
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
                    seriesDebridPlayback.resumeDebridEpisode(episode)
                } else {
                    seriesDebridPlayback.playEpisode(episode)
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
        // ⭐ The LEADING button grows from its left edge, not its centre — see
        // MovieDetailActivity.setupFocusAnimations for the full reasoning. In short: a television
        // overscans, this content margin already sits close to what the panel shows, and a centred
        // scale pushed the first button OUTSIDE it. Only on focus, only that button, and invisible
        // to a screenshot, because the framebuffer keeps what the screen throws away.
        btnWatchNow.pivotX = 0f
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
        tvRatingPercentage.text = String.format(java.util.Locale.US, "%.1f", ratingValue)

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

        // The poster, for whichever layout has a visible one — the phone does, the television
        // keeps a stub. Same helper the movie screen uses.
        com.tvonnet.debridxtreamiptv.ui.vod.bindDetailPoster(this, seriesCover, seriesBackdrop)
        com.tvonnet.debridxtreamiptv.ui.vod.hideBlankLabels(
            this,
            R.id.tv_quality_badge, R.id.tv_cc_badge, R.id.tv_director, R.id.tv_cast,
            R.id.tv_breadcrumb_debrid, R.id.tv_breadcrumb_type, R.id.tv_premium_badge,
        )

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
                        iconUrl = seriesCover,
                        // S2: this screen serves both worlds. A debrid id survives a change of IPTV
                        // provider; an Xtream series id does not, and must be cleared with it.
                        source = if (isDebridContent) {
                            com.tvonnet.debridxtreamiptv.data.local.entity.WatchedStateEntity.SOURCE_DEBRID
                        } else {
                            com.tvonnet.debridxtreamiptv.data.local.entity.WatchedStateEntity.SOURCE_XTREAM
                        }
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
                // Non-fatal: leave the default button state, but don't hide the cause.
                android.util.Log.w("SeriesDetailActivity", "Favorite state lookup failed", e)
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
            .setTitle(R.string.c_real_debrid_not_configured)
            .setMessage(R.string.c_you_need_to_configure_real)
            .setPositiveButton(R.string.c_configure) { _, _ ->
                 navigateToSettings()
            }
            .setNegativeButton(android.R.string.cancel, null)
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
