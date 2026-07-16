package com.tvonnet.debridxtreamiptv.ui.series

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
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
import com.tvonnet.debridxtreamiptv.data.model.XtreamEpisodeInfo
import com.tvonnet.debridxtreamiptv.data.model.XtreamSeasonInfo
import com.tvonnet.debridxtreamiptv.data.model.XtreamSeriesDetailInfo
import com.tvonnet.debridxtreamiptv.data.model.XtreamSeriesDetailResponse
import com.tvonnet.debridxtreamiptv.data.model.XtreamSeriesInfo
import com.tvonnet.debridxtreamiptv.data.onFailure
import com.tvonnet.debridxtreamiptv.data.onSuccess
import com.tvonnet.debridxtreamiptv.data.prefs.CredentialsPreferences
import com.tvonnet.debridxtreamiptv.data.repository.DebridCacheStatus
import com.tvonnet.debridxtreamiptv.data.repository.XtreamRepository
import com.tvonnet.debridxtreamiptv.debug.PlaybackDiagnosticsRecorder
import com.tvonnet.debridxtreamiptv.player.stabilized.PlayerActivity
import com.tvonnet.debridxtreamiptv.ui.sources.SourceFilterUtils
import com.tvonnet.debridxtreamiptv.ui.trailer.TrailerValueParser
import com.tvonnet.debridxtreamiptv.data.local.entity.toXtreamSeasonInfo
import com.tvonnet.debridxtreamiptv.data.local.entity.toXtreamEpisodeInfo
import dagger.hilt.android.AndroidEntryPoint
import androidx.activity.viewModels
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
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
    private lateinit var seasonsAdapter: SeriesSeasonAdapter
    private lateinit var cinEpisodeAdapter: CinEpisodeAdapter

    // Current season's episodes + their loaded watch progress (episodeId -> pct / watched set)
    private var currentEpisodes: List<EpisodeUiModel> = emptyList()
    private val episodeProgress = mutableMapOf<String, Int>()
    private val episodeProgressMs = mutableMapOf<String, Long>()
    private val episodeWatched = mutableSetOf<String>()

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
    private var seriesTrailerUrl: String? = null
    private var isTrailerLookupInProgress: Boolean = false

    private var currentDetail: XtreamSeriesDetailResponse? = null
    private var selectedSeasonKey: String? = null
    private var selectedEpisode: EpisodeUiModel? = null
    private var lastDebridEpisode: EpisodeUiModel? = null
    private val cachedDebridSourcesByEpisode = mutableMapOf<String, List<com.tvonnet.debridxtreamiptv.data.repository.MovieSource>>()
    private val debridFilterStateByEpisode = mutableMapOf<String, com.tvonnet.debridxtreamiptv.ui.sources.SourceFilterState>()
    private val selectedDebridStreamIdByEpisode = mutableMapOf<String, String?>()
    private val pendingDebridReturnFocusStreamIdsByEpisode = mutableMapOf<String, List<String>>()
    private val failedDebridStreamIdsByEpisode = mutableMapOf<String, MutableSet<String>>()
    private var activeDebridSourceRequestEpisodeId: String? = null
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
            val episode = lastDebridEpisode
            if (!failedStreamId.isNullOrBlank() && episode != null) {
                failedDebridStreamIdsByEpisode.getOrPut(episode.id) { linkedSetOf() }.add(failedStreamId)
                markDebridEpisodeSourceCached(episode.id, failedStreamId, false)
            }
            val allowAutoPlayNext = autoPlayNext && !openedFromPlaybackFailure &&
                autoFallbackAttempts < MAX_AUTO_FALLBACK_ATTEMPTS
            notifyDebridFailure(failReason, allowAutoPlayNext)
            if (allowAutoPlayNext && !failedStreamId.isNullOrBlank() && episode != null) {
                autoFallbackAttempts++
                autoPlayNextDebridEpisodeSource(episode, failedStreamId)
            } else {
                autoFallbackAttempts = 0
                lastDebridEpisode?.let {
                    fetchAndShowDebridSources(it, consumeDebridReturnFocusStreamIds(it.id))
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
        setupAdapters()
        getSeriesDataFromIntent()
        openedFromPlaybackFailure = intent.getBooleanExtra(PlayerActivity.EXTRA_OPENED_FROM_PLAYBACK_FAILURE, false)
        updateTrailerButtonState()
        
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

        setupFocusAnimations()
    }

    private fun setupAdapters() {
        seasonsAdapter = SeriesSeasonAdapter(
            onSeasonSelected = { season ->
                selectedSeasonKey = season.key
                showEpisodesForSeason(season.key)
                updateSeasonButton()
            },
            onSeasonFocused = { season ->
                selectedSeasonKey = season.key
                showEpisodesForSeason(season.key)
                updateSeasonButton()
            }
        )
        rvSeasons.adapter = seasonsAdapter

        cinEpisodeAdapter = CinEpisodeAdapter(
            onEpisodeClick = { episode ->
                selectedEpisode = episode
                cinEpisodeAdapter.setSelectedEpisode(episode.id)
                updateWatchNowState()
                playEpisode(episode)
            },
            onEpisodeFocused = { episode ->
                selectedEpisode = episode
                cinEpisodeAdapter.setSelectedEpisode(episode.id)
                updateWatchNowState()
            }
        )
        rvEpisodes.adapter = cinEpisodeAdapter
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
            seriesTrailerUrl = data.getStringExtra(EXTRA_SERIES_TRAILER)
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
            selectedEpisode?.let { episode ->
                val hasResume = isDebridContent &&
                    !episodeWatched.contains(episode.id) &&
                    episodeProgress.containsKey(episode.id)
                if (hasResume) {
                    resumeDebridEpisode(episode)
                } else {
                    playEpisode(episode)
                }
            }
        }

        btnWatchTrailer.setOnClickListener {
            launchTrailer()
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
            showSeasonPopup()
        }
    }

    private fun showSeasonPopup() {
        val episodeKeys = currentDetail?.episodes?.keys
            ?.sortedWith(compareBy { it.toIntOrNull() ?: Int.MAX_VALUE }) ?: return
        if (episodeKeys.isEmpty()) return

        // Rounded panel background (design: #0E0D15, cyan border, 8px radius)
        val panelBg = android.graphics.drawable.GradientDrawable().apply {
            setColor(0xFF0E0D15.toInt())
            cornerRadius = dp(8).toFloat()
            setStroke(dp(1), 0x4000F0FF)
        }

        val popupView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = panelBg
            setPadding(dp(6), dp(6), dp(6), dp(6))
        }

        val headerTv = TextView(this).apply {
            text = "SELECT SEASON"
            setTextColor(0xFF64748B.toInt())
            textSize = 9f
            typeface = android.graphics.Typeface.MONOSPACE
            letterSpacing = 0.12f
            setPadding(dp(8), dp(5), dp(8), dp(6))
        }
        popupView.addView(headerTv)

        val popup = PopupWindow(
            popupView,
            dp(178),
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true
        ).apply {
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
            elevation = dp(12).toFloat()
        }

        episodeKeys.forEachIndexed { index, key ->
            val seasonNum = key.toIntOrNull() ?: (index + 1)
            val epCount = currentDetail?.episodes?.get(key)?.size ?: 0
            val isActive = key == selectedSeasonKey

            val itemBg = android.graphics.drawable.GradientDrawable().apply {
                setColor(if (isActive) 0x1400F0FF else 0x00000000)
                cornerRadius = dp(5).toFloat()
            }
            val itemView = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), 0, dp(10), 0)
                minimumHeight = dp(40)
                isFocusable = true
                isClickable = true
                background = itemBg
                setOnClickListener {
                    selectedSeasonKey = key
                    showEpisodesForSeason(key)
                    updateSeasonButton()
                    popup.dismiss()
                }
                setOnFocusChangeListener { _, hasFocus ->
                    itemBg.setColor(
                        when {
                            hasFocus -> 0x2400F0FF
                            isActive -> 0x1400F0FF
                            else -> 0x00000000
                        }
                    )
                }
            }

            val labelTv = TextView(this).apply {
                text = "Season $seasonNum"
                setTextColor(if (isActive) 0xFF00F0FF.toInt() else 0xFFE2E8F0.toInt())
                textSize = 14f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            itemView.addView(labelTv)

            val countTv = TextView(this).apply {
                text = "$epCount EP"
                setTextColor(0xFF64748B.toInt())
                textSize = 11f
                typeface = android.graphics.Typeface.MONOSPACE
                setPadding(0, 0, dp(8), 0)
            }
            itemView.addView(countTv)

            val check = TextView(this).apply {
                text = if (isActive) "●" else ""
                setTextColor(0xFF00F0FF.toInt())
                textSize = 11f
            }
            itemView.addView(check)

            popupView.addView(itemView, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(2) })
        }

        popupView.measure(
            View.MeasureSpec.makeMeasureSpec(dp(178), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        popup.showAsDropDown(
            btnSeasonSelector,
            0,
            -btnSeasonSelector.height - popupView.measuredHeight - dp(4),
            Gravity.START or Gravity.BOTTOM
        )
    }

    private fun updateSeasonButton() {
        val seasonNum = selectedSeasonKey?.toIntOrNull()
        btnSeasonSelector.text = if (seasonNum != null) "SEASON $seasonNum ▾" else "SEASON ▾"
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

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

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

        updateWatchNowState()
        updateSeasonButton()
    }
    



    private fun ensureRepositoryInitialized() {
        repository.ensureInitialized(
            credentialsPreferences.getServerUrl(),
            credentialsPreferences.getUsername(),
            credentialsPreferences.getPassword()
        )
    }

    private fun displayRating(rating: Double) {
        tvRatingPercentage.text = String.format("%.1f", rating)
    }

    private fun buildGenreYearText(genre: String?, releaseDate: String?): String {
        val year = releaseDate?.takeIf { it.isNotBlank() }?.let {
            if (it.length >= 4) it.substring(0, 4) else it
        }

        return when {
            !genre.isNullOrBlank() && !year.isNullOrBlank() ->
                getString(R.string.movie_detail_genre_and_year, genre, year)
            !genre.isNullOrBlank() -> genre
            !year.isNullOrBlank() -> year
            else -> getString(R.string.movie_detail_not_available)
        }
    }

    private fun loadSeriesDetail() {
        val id = seriesId ?: return

        if (isDebridContent) {
            showLoading(true)
            lifecycleScope.launch {
                val result = fetchDebridSeriesDetail(id)
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
                        currentDetail = detail
                        applyDetailInfo(detail.info)
                        buildSeasonList(detail)
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

    private suspend fun fetchDebridSeriesDetail(tmdbId: String): Result<XtreamSeriesDetailResponse> {
        return withContext(Dispatchers.IO) {
            try {
                val idInt = tmdbId.toIntOrNull() ?: return@withContext Result.Error(Exception("Invalid TMDB ID"))
                
                // 1. Fetch Show Details
                val showDetailsResult = tmdbRemoteDataSource.getSeriesDetails(idInt)
                if (showDetailsResult is Result.Error) return@withContext Result.Error(showDetailsResult.exception)
                val showDetails = (showDetailsResult as Result.Success).data
                
                // 2. Fetch Seasons Details (to get episodes)
                val seasonsDetails = mutableListOf<com.tvonnet.debridxtreamiptv.data.debrid.model.TmdbSeasonDetails>()
                showDetails.seasons?.forEach { season ->
                    val seasonNumber = season.seasonNumber ?: return@forEach
                    // Skip season 0 (specials) if desired, or keep it.
                    if (seasonNumber > 0) {
                        val seasonResult = tmdbRemoteDataSource.getSeasonDetails(idInt, seasonNumber)
                        if (seasonResult is Result.Success) {
                            seasonsDetails.add(seasonResult.data)
                        }
                    }
                }
                
                // 3. Map to Xtream format
                val xtreamResponse = com.tvonnet.debridxtreamiptv.data.mapper.TmdbToXtreamMapper.mapToXtreamSeriesDetail(showDetails, seasonsDetails)
                Result.Success(xtreamResponse)
            } catch (e: Exception) {
                Result.Error(e)
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
            currentDetail = detail
            applyDetailInfo(detail.info)
            buildSeasonList(detail)
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
        seriesTrailerUrl = info.youtube_trailer ?: seriesTrailerUrl
        seriesCover = com.tvonnet.debridxtreamiptv.util.GlobalConfig.resolveIconUrl(info.cover) ?: seriesCover
        seriesBackdrop = info.backdropPathString ?: seriesBackdrop

        displaySeriesInfo()
        updateTrailerButtonState()
    }
    private fun buildSeasonList(detail: XtreamSeriesDetailResponse) {
        val seasonItems = when {
            !detail.seasons.isNullOrEmpty() -> {
                detail.seasons.mapIndexedNotNull { index, season ->
                    val key = season.season_number ?: (index + 1).toString()
                    val displayName = season.name?.takeIf { it.isNotBlank() }
                        ?: getString(R.string.series_detail_season_name, key.toIntOrNull() ?: (index + 1))
                    SeasonUiModel(key = key, displayName = displayName)
                }
            }
            !detail.episodes.isNullOrEmpty() -> {
                detail.episodes.keys.sortedWith(compareBy { it.toIntOrNull() ?: Int.MAX_VALUE })
                    .mapIndexed { index, key ->
                        SeasonUiModel(
                            key = key,
                            displayName = getString(
                                R.string.series_detail_season_name,
                                key.toIntOrNull() ?: (index + 1)
                            )
                        )
                    }
            }
            else -> emptyList()
        }

        seasonsAdapter.submitList(seasonItems, selectedSeasonKey)

        if (seasonItems.isNotEmpty()) {
            // Prefer the first season that actually has episodes (skip empty "Specials" / season 0).
            val firstWithEpisodes = seasonItems.firstOrNull { season ->
                !currentDetail?.episodes?.get(season.key).isNullOrEmpty()
            }?.key
            val initialKey = selectedSeasonKey ?: firstWithEpisodes ?: seasonItems.first().key
            selectedSeasonKey = initialKey
            showEpisodesForSeason(initialKey)
            updateSeasonButton()
            rvEpisodes.post { rvEpisodes.requestFocus() }
        } else {
            showEpisodes(emptyList())
        }
    }

    private fun showEpisodesForSeason(seasonKey: String) {
        val episodes = currentDetail?.episodes?.get(seasonKey)
        if (episodes.isNullOrEmpty()) {
            showEpisodes(emptyList())
            return
        }

        val episodeModels = episodes
            .filter { !it.id.isNullOrBlank() }
            .sortedWith(compareBy<XtreamEpisodeInfo> { it.episode_num ?: Int.MAX_VALUE }
                .thenBy { it.title ?: "" })
            .map { episode ->
                EpisodeUiModel(
                    id = episode.id!!,
                    title = episode.title ?: "Episode",
                    episodeNumber = episode.episode_num,
                    description = episode.info?.plot,
                    durationMinutes = parseDurationMinutes(episode),
                    thumbnailUrl = episode.info?.cover ?: episode.thumbnail,
                    containerExtension = episode.container_extension,
                    isWatched = episode.isWatched,
                    resumePosition = episode.resumePosition
                )
            }

        showEpisodes(episodeModels)
    }

    private fun parseDurationMinutes(episode: XtreamEpisodeInfo): Int? {
        val durationSeconds = episode.duration_secs
            ?: episode.info?.duration_secs
        val seconds = durationSeconds?.toLongOrNull()
        return seconds?.let { (it / 60).toInt().coerceAtLeast(1) }
    }

    private fun showEpisodes(episodes: List<EpisodeUiModel>) {
        currentEpisodes = episodes
        if (episodes.isEmpty()) {
            tvEmptyEpisodes.visibility = View.VISIBLE
            cinEpisodeAdapter.submitEpisodes(emptyList(), null)
            selectedEpisode = null
        } else {
            tvEmptyEpisodes.visibility = View.GONE
            val currentSelectionId = selectedEpisode?.id
            val newSelection = episodes.firstOrNull { it.id == currentSelectionId } ?: episodes.first()
            selectedEpisode = newSelection
            cinEpisodeAdapter.submitEpisodes(episodes, newSelection.id)
        }
        updateWatchNowState()
        updateSeasonProgress(episodes)
        updateResumeSection(episodes)
        loadSeasonWatchedProgress()
    }

    override fun onResume() {
        super.onResume()
        // Returning from the player: re-read watched-state so progress bars,
        // the continue bar and the Play/Resume label reflect the latest position.
        if (::cinEpisodeAdapter.isInitialized && currentEpisodes.isNotEmpty()) {
            loadSeasonWatchedProgress()
        }
    }

    /**
     * Loads real watched-state (progress + watched) for every episode in the current
     * season and pushes it to the adapter, the continue bar and the Play button.
     * Bound to the season's episode data, not the focused card.
     */
    private fun loadSeasonWatchedProgress() {
        val episodes = currentEpisodes
        if (episodes.isEmpty()) return
        val seasonNumber = selectedSeasonKey?.toIntOrNull()
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                val progress = HashMap<String, Int>()
                val progressMs = HashMap<String, Long>()
                val watched = HashSet<String>()
                for (ep in episodes) {
                    var pct: Int? = null
                    var posMs: Long? = null
                    var isW = ep.isWatched
                    // 1) resume position carried by the episode model (Xtream)
                    val durMin = ep.durationMinutes ?: 0
                    if (ep.resumePosition > 0 && durMin > 0) {
                        pct = ((ep.resumePosition.toFloat() / (durMin * 60_000L)) * 100).toInt().coerceIn(0, 100)
                        posMs = ep.resumePosition
                    }
                    // 2) watched-state store (Debrid + anything with a saved position)
                    if (seasonNumber != null) {
                        val key = if (isDebridContent) {
                            com.tvonnet.debridxtreamiptv.data.local.WatchedIdentityBuilder.debridEpisode(
                                seriesIdentity = seriesId,
                                seasonNumber = seasonNumber,
                                episodeNumber = ep.episodeNumber,
                                seriesTitle = seriesName,
                                seriesYear = seriesReleaseDate?.take(4),
                                fallbackDiscriminator = ep.id.ifBlank { ep.title }
                            )
                        } else {
                            com.tvonnet.debridxtreamiptv.data.local.WatchedIdentityBuilder.iptvEpisode(
                                episodeId = ep.id,
                                seriesId = seriesId,
                                seasonNumber = seasonNumber,
                                episodeNumber = ep.episodeNumber,
                                fallbackDiscriminator = ep.id.ifBlank { ep.title }
                            )
                        }
                        val state = watchedStateRepository.getState(key)
                        if (state != null) {
                            if (state.isWatched) isW = true
                            if (!state.isWatched && state.durationMs > 0 && state.progressMs > 0) {
                                pct = ((state.progressMs.toFloat() / state.durationMs) * 100).toInt().coerceIn(0, 100)
                                posMs = state.progressMs
                            }
                        }
                    }
                    if (isW) {
                        watched.add(ep.id)
                    } else if (pct != null && pct in 1..99) {
                        progress[ep.id] = pct
                        posMs?.let { progressMs[ep.id] = it }
                    }
                }
                Triple(progress, progressMs, watched)
            }
            episodeProgress.clear()
            episodeProgress.putAll(result.first)
            episodeProgressMs.clear()
            episodeProgressMs.putAll(result.second)
            episodeWatched.clear()
            episodeWatched.addAll(result.third)
            // CC-1: applyOverrides() does a blanket notifyDataSetChanged; on return from the
            // player this fires and would drop focus off the just-watched episode. Preserve
            // the user's focused card (restores to the same episode by stable id).
            rvEpisodes.updatePreservingFocus {
                cinEpisodeAdapter.applyOverrides(episodeProgress, episodeWatched)
            }
            updateResumeSection(episodes)
            updateWatchNowState()
        }
    }

    private fun updateSeasonProgress(episodes: List<EpisodeUiModel>) {
        val watchedCount = episodes.count { it.isWatched }
        tvSeasonProgress.text = "$watchedCount / ${episodes.size} WATCHED"

        // Update season/episode count in metadata
        val totalSeasons = currentDetail?.seasons?.size
            ?: currentDetail?.episodes?.keys?.size ?: 0
        val totalEpisodes = currentDetail?.episodes?.values?.sumOf { it.size } ?: episodes.size
        tvSeasonEpisodeCount.text = "$totalSeasons SEASONS · $totalEpisodes EPISODES"
    }

    private fun updateResumeSection(episodes: List<EpisodeUiModel>) {
        // Bound to the season's in-progress episode data (0 < progress < 100),
        // sourced from episodeProgress which loadSeasonWatchedProgress() fills.
        val resumeEp = episodes.firstOrNull {
            !episodeWatched.contains(it.id) && episodeProgress.containsKey(it.id)
        }
        if (resumeEp != null) {
            val pct = episodeProgress[resumeEp.id] ?: 0
            val durMin = resumeEp.durationMinutes ?: 0
            val minsLeft = if (durMin > 0) {
                ((durMin.toLong() * (100 - pct)) / 100).coerceAtLeast(1)
            } else 1L
            renderResumeBar(resumeEp, pct, minsLeft)
        } else {
            layoutResume.visibility = View.GONE
        }
    }

    private fun renderResumeBar(episode: EpisodeUiModel, pct: Int, minsLeft: Long) {
        layoutResume.visibility = View.VISIBLE
        val seasonNum = selectedSeasonKey?.toIntOrNull() ?: 1
        val epNum = episode.episodeNumber ?: 1
        tvResumeLabel.text = "CONTINUE · S${String.format("%02d", seasonNum)} · E${String.format("%02d", epNum)}"
        tvResumeRight.text = "$pct% · ${minsLeft}M LEFT"
        tvResumeEpTitle.text = episode.title
        vResumeProgress.post {
            val parent = vResumeProgress.parent as? View ?: return@post
            val lp = vResumeProgress.layoutParams
            lp.width = (parent.width * pct / 100f).toInt()
            vResumeProgress.layoutParams = lp
        }
    }

    private fun updateWatchNowState() {
        btnWatchNow.isEnabled = selectedEpisode != null
        val ep = selectedEpisode
        val seasonNum = selectedSeasonKey?.toIntOrNull() ?: 1
        val epNum = ep?.episodeNumber ?: 1
        val epLabel = "S${String.format("%02d", seasonNum)} · E${String.format("%02d", epNum)}"
        val hasResume = ep != null && !episodeWatched.contains(ep.id) &&
            (episodeProgress.containsKey(ep.id) || (ep.resumePosition > 0 && !ep.isWatched))
        btnWatchNow.text = if (hasResume) "Resume $epLabel" else "Play $epLabel"
    }

    private fun updateRdSummary(sources: List<com.tvonnet.debridxtreamiptv.data.repository.MovieSource>) {
        if (sources.isEmpty()) {
            layoutRdSummary.visibility = View.GONE
            return
        }
        layoutRdSummary.visibility = View.VISIBLE
        val cachedCount = sources.count { it.isCached == true }
        val bestQuality = when {
            sources.any { it.quality?.contains("4K", true) == true || it.quality?.contains("2160", true) == true } -> "4K"
            sources.any { it.quality?.contains("1080", true) == true } -> "1080P"
            sources.any { it.quality?.contains("720", true) == true } -> "720P"
            else -> ""
        }
        val bestSize = sources.filter { it.isCached == true }
            .mapNotNull { it.sizeBytes }
            .maxOrNull()
        val sizeLabel = if (bestSize != null) " ${formatSizeLabel(bestSize)}" else ""
        val qualityPart = if (bestQuality.isNotEmpty()) " · BEST $bestQuality$sizeLabel" else ""
        tvRdSummary.text = "${sources.size} SOURCES / EPISODE · $cachedCount CACHED$qualityPart"

        // Update quality badge
        if (bestQuality.isNotEmpty()) {
            tvQualityBadge.visibility = View.VISIBLE
            tvQualityBadge.text = bestQuality
        }
    }

    private fun formatSizeLabel(bytes: Long): String {
        return when {
            bytes >= 1_073_741_824L -> String.format("%.1f GB", bytes / 1_073_741_824.0)
            bytes >= 1_048_576L -> String.format("%.0f MB", bytes / 1_048_576.0)
            else -> "${bytes / 1024} KB"
        }
    }

    private suspend fun handleSeriesDetailError(error: Exception) {
        showLoading(false)

        val fallbackSeries = seriesId?.let { repository.getSeriesById(it) }
        if (fallbackSeries != null) {
            applyFallbackSeriesInfo(fallbackSeries)
        } else {
            tvEmptyEpisodes.text = getString(R.string.series_detail_empty_episodes_error)
            showEpisodes(emptyList())
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

        val fallbackDetail = buildFallbackDetail(seriesInfo)
        if (fallbackDetail != null) {
            currentDetail = fallbackDetail
            buildSeasonList(fallbackDetail)
        } else {
            tvEmptyEpisodes.text = getString(R.string.series_detail_empty_episodes_error)
            showEpisodes(emptyList())
        }
    }

    private fun buildFallbackDetail(seriesInfo: XtreamSeriesInfo): XtreamSeriesDetailResponse? {
        val originalEpisodes = seriesInfo.episodes ?: return null

        val grouped = mutableMapOf<String, MutableList<XtreamEpisodeInfo>>()
        originalEpisodes.forEach { (seasonKeyRaw, episodeList) ->
            val normalizedKey = seasonKeyRaw.takeIf { it.isNotBlank() }
                ?: episodeList.firstOrNull()?.season?.takeIf { it > 0 }?.toString()
                ?: "1"
            val bucket = grouped.getOrPut(normalizedKey) { mutableListOf() }
            bucket.addAll(episodeList)
        }

        if (grouped.isEmpty()) {
            return null
        }

        grouped.values.forEach { list ->
            list.sortWith(compareBy<XtreamEpisodeInfo>({ it.episode_num ?: Int.MAX_VALUE }, { it.title ?: "" }))
        }

        val seasonKeys = grouped.keys.sortedWith(compareBy { it.toIntOrNull() ?: Int.MAX_VALUE })
        val seasons = seasonKeys.mapIndexed { index, key ->
            val seasonNumber = key.toIntOrNull() ?: (index + 1)
            XtreamSeasonInfo(
                season_number = key,
                name = getString(R.string.series_detail_season_name, seasonNumber),
                overview = null,
                air_date = null,
                cover = null
            )
        }

        val normalizedEpisodes = seasonKeys.associateWith { seasonKey ->
            grouped[seasonKey]?.toList() ?: emptyList()
        }

        val detailInfo = XtreamSeriesDetailInfo(
            name = seriesInfo.name,
            series_id = seriesInfo.series_id,
            plot = seriesInfo.plot,
            cast = seriesInfo.cast,
            director = seriesInfo.director,
            genre = seriesInfo.genre,
            releaseDate = seriesInfo.releaseDate,
            rating = seriesInfo.rating,
            rating_5based = seriesInfo.rating_5based,
            cover = com.tvonnet.debridxtreamiptv.util.GlobalConfig.resolveIconUrl(seriesInfo.cover),
            backdrop_path = com.tvonnet.debridxtreamiptv.util.GlobalConfig.resolveIconUrl(seriesInfo.cover),
            youtube_trailer = null
        )

        return XtreamSeriesDetailResponse(
            info = detailInfo,
            episodes = normalizedEpisodes,
            seasons = seasons
        )
    }

    /**
     * Resume button: play the in-progress episode directly instead of re-opening
     * the source list. Reuses the source picked last time when possible, otherwise
     * silently fetches sources and auto-picks the best cached one. The player
     * restores the saved position from watch history.
     */
    private fun resumeDebridEpisode(episode: EpisodeUiModel) {
        val cached = cachedDebridSourcesByEpisode[episode.id]
        if (!cached.isNullOrEmpty()) {
            val source = pickResumeSource(episode, cached)
            if (source != null) {
                playDebridEpisode(source, episode)
                return
            }
        }

        val fetchContext = resolveDebridEpisodeFetchContext(episode)
        if (fetchContext == null) {
            fetchAndShowDebridSources(episode)
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
                cachedDebridSourcesByEpisode[episode.id] = sources
                showLoading(false)
                val source = pickResumeSource(episode, sources)
                if (source != null) {
                    playDebridEpisode(source, episode)
                } else {
                    fetchAndShowDebridSources(episode)
                }
            } catch (e: Exception) {
                android.util.Log.e("SeriesDetailActivity", "Resume source fetch failed: ${e.message}", e)
                showLoading(false)
                fetchAndShowDebridSources(episode)
            }
        }
    }

    private fun pickResumeSource(
        episode: EpisodeUiModel,
        sources: List<com.tvonnet.debridxtreamiptv.data.repository.MovieSource>
    ): com.tvonnet.debridxtreamiptv.data.repository.MovieSource? {
        val failedIds = failedDebridStreamIdsByEpisode[episode.id].orEmpty()
        // 1) Source picked last time for this episode (same session)
        val preferredId = selectedDebridStreamIdByEpisode[episode.id]
        if (!preferredId.isNullOrBlank() && preferredId !in failedIds) {
            sources.firstOrNull { it.stream.stream_id == preferredId }?.let { return it }
        }
        // 2) Best available: respect the episode's filter state, prefer cached
        val filterState = debridFilterStateByEpisode[episode.id]
            ?: com.tvonnet.debridxtreamiptv.ui.sources.SourceFilterState()
        val candidates = SourceFilterUtils.apply(sources, filterState)
            .filterNot { source -> source.stream.stream_id?.let { it in failedIds } == true }
        return candidates.firstOrNull { it.isCached == true } ?: candidates.firstOrNull()
    }

    private fun playEpisode(episode: EpisodeUiModel) {
        if (isDebridContent) {
            fetchAndShowDebridSources(episode)
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
        val resumeMs = episodeProgressMs[episode.id]
            ?.takeIf { it > 0 && !episodeWatched.contains(episode.id) }
        val intent = PlayerActivity.createIntent(
            context = this,
            streamUrl = streamUrl,
            title = episode.title,
            contentId = episode.id,
            contentType = ContentType.EPISODE,
            posterUrl = seriesCover ?: episode.thumbnailUrl,
            seriesTitle = seriesName,
            episodeTitle = episode.title,
            seasonNumber = selectedSeasonKey?.toIntOrNull(),
            episodeNumber = episode.episodeNumber,
            startPositionMs = resumeMs
        )
        seriesId?.let { intent.putExtra(PlayerActivity.EXTRA_SERIES_ID, it) }
        startActivity(intent)
    }

    private fun fetchAndShowDebridSources(
        episode: EpisodeUiModel,
        returnFocusStreamIds: List<String> = emptyList()
    ) {
        val requestEpisodeId = episode.id
        activeDebridSourceRequestEpisodeId = requestEpisodeId
        lastDebridEpisode = episode
        val initialState = debridFilterStateByEpisode[episode.id]
            ?: com.tvonnet.debridxtreamiptv.ui.sources.SourceFilterState()
        val initialSelectedStreamId = selectedDebridStreamIdByEpisode[episode.id]
        val bottomSheet = SourceSelectionBottomSheet(
            onSourceSelected = { source, adjacentStreamIds ->
                selectedDebridStreamIdByEpisode[episode.id] = source.stream.stream_id
                pendingDebridReturnFocusStreamIdsByEpisode[episode.id] = adjacentStreamIds
                PlaybackDiagnosticsRecorder.record(
                    this,
                    "source_selected",
                    PlaybackDiagnosticsRecorder.contentFields(
                        kind = "series_episode",
                        tmdbId = seriesId,
                        season = selectedSeasonKey?.toIntOrNull(),
                        episode = episode.episodeNumber
                    ) + PlaybackDiagnosticsRecorder.sourceFields(this, source)
                )
                if (source.sourceType == "IPTV") {
                    playIptvSourceFromDebrid(source, episode)
                } else {
                    playDebridEpisode(source, episode)
                }
            },
            onStateChanged = { state ->
                debridFilterStateByEpisode[episode.id] = state
            },
            initialState = initialState,
            initialSelectedStreamId = initialSelectedStreamId,
            initialReturnFocusStreamIds = returnFocusStreamIds,
            contentTitle = seriesName,
            backdropUrl = seriesBackdrop,
            preferredAudioLang = credentialsPreferences.preferredAudioLang
        )

        bottomSheet.show(supportFragmentManager, SourceSelectionBottomSheet.TAG)
        val cachedSources = cachedDebridSourcesByEpisode[episode.id]
        if (cachedSources != null) {
            PlaybackDiagnosticsRecorder.record(
                this,
                "source_list_loaded",
                PlaybackDiagnosticsRecorder.contentFields(
                    kind = "series_episode",
                    tmdbId = seriesId,
                    season = selectedSeasonKey?.toIntOrNull(),
                    episode = episode.episodeNumber
                ) + PlaybackDiagnosticsRecorder.sourceListFields(this, cachedSources) +
                    mapOf("sourceListCacheHit" to true)
            )
            bottomSheet.showSources(cachedSources)
            refreshMediaFusionCacheStatus(episode.id, cachedSources, bottomSheet)
            return
        }

        val episodeFetchContext = resolveDebridEpisodeFetchContext(episode)
        if (episodeFetchContext == null) {
            val message = "Episode metadata missing for source fetch"
            bottomSheet.showError(message)
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            return
        }

        bottomSheet.showLoading()

        lifecycleScope.launch {
            try {
                val (seasonNumber, episodeNumber) = episodeFetchContext
                
                val debridSources = withContext(Dispatchers.IO) {
                    unifiedSourceProvider.getSeriesEpisodeSources(
                        seriesId = seriesId,
                        seasonNumber = seasonNumber,
                        episodeNumber = episodeNumber,
                        title = seriesName,
                        yearHint = seriesReleaseDate?.take(4)
                    )
                }
                // Cross-source: append IPTV listings of the same show (matched by
                // title across the Xtream catalog). DB-only, effectively instant.
                val iptvGroups = iptvGroupsForEpisode(seasonNumber, episodeNumber)
                val sources = debridSources + mapIptvGroupsToSources(iptvGroups)
                android.util.Log.e("SeriesDetailActivity", "Received ${debridSources.size} debrid + ${sources.size - debridSources.size} IPTV sources. Updating bottom sheet.")
                cachedDebridSourcesByEpisode[episode.id] = sources
                updateRdSummary(sources)
                if (activeDebridSourceRequestEpisodeId != requestEpisodeId || selectedEpisode?.id != requestEpisodeId) {
                    android.util.Log.d("SeriesDetailActivity", "Ignoring stale Debrid sources for episode=$requestEpisodeId")
                    return@launch
                }
                PlaybackDiagnosticsRecorder.record(
                    this@SeriesDetailActivity,
                    "source_list_loaded",
                    PlaybackDiagnosticsRecorder.contentFields(
                        kind = "series_episode",
                        tmdbId = seriesId,
                        season = seasonNumber,
                        episode = episodeNumber
                    ) + PlaybackDiagnosticsRecorder.sourceListFields(this@SeriesDetailActivity, sources) +
                        mapOf("sourceListCacheHit" to false)
                )
                bottomSheet.showSources(sources)

                // Background: verify IPTV rows (drop listings lacking this episode,
                // fill playable URLs), then run the MediaFusion refresh on the final
                // list so the two updates can't clobber each other.
                val verifiedGroups = verifyIptvGroups(iptvGroups, seasonNumber, episodeNumber)
                val finalSources = debridSources + mapIptvGroupsToSources(verifiedGroups)
                if (activeDebridSourceRequestEpisodeId != requestEpisodeId || selectedEpisode?.id != requestEpisodeId) return@launch
                cachedDebridSourcesByEpisode[episode.id] = finalSources
                if (finalSources != sources) {
                    bottomSheet.showSources(finalSources)
                }
                refreshMediaFusionCacheStatus(episode.id, finalSources, bottomSheet)
            } catch (e: Exception) {
                android.util.Log.e("SeriesDetailActivity", "Error fetching sources: ${e.message}", e)
                if (activeDebridSourceRequestEpisodeId != requestEpisodeId || selectedEpisode?.id != requestEpisodeId) {
                    android.util.Log.d("SeriesDetailActivity", "Ignoring stale Debrid source error for episode=$requestEpisodeId")
                    return@launch
                }
                // Debrid failed — IPTV listings of the same show may still be playable.
                // Show them immediately; verify in the background (never before showing).
                val (s, ep) = episodeFetchContext
                val iptvFallbackGroups = iptvGroupsForEpisode(s, ep)
                val iptvFallback = mapIptvGroupsToSources(iptvFallbackGroups)
                if (iptvFallback.isNotEmpty()) {
                    cachedDebridSourcesByEpisode[episode.id] = iptvFallback
                    bottomSheet.showSources(iptvFallback)
                    val verifiedFallback = mapIptvGroupsToSources(verifyIptvGroups(iptvFallbackGroups, s, ep))
                    if (activeDebridSourceRequestEpisodeId != requestEpisodeId || selectedEpisode?.id != requestEpisodeId) return@launch
                    if (verifiedFallback.isNotEmpty() && verifiedFallback != iptvFallback) {
                        cachedDebridSourcesByEpisode[episode.id] = verifiedFallback
                        bottomSheet.showSources(verifiedFallback)
                    }
                } else {
                    bottomSheet.showError(e.message ?: "Failed to load sources")
                }
            }
        }
    }

    /** IPTV cross-category aggregation for this show (DB-only, instant). */
    private suspend fun iptvGroupsForEpisode(
        seasonNumber: Int,
        episodeNumber: Int
    ): List<com.tvonnet.debridxtreamiptv.features.seriesv2.ui.model.IptvStreamGroup> {
        val title = seriesName?.takeIf { it.isNotBlank() } ?: return emptyList()
        return try {
            withContext(Dispatchers.IO) {
                seriesRepositoryV2.aggregateStreamsForTitle(
                    title, seasonNumber, episodeNumber, yearHint = seriesReleaseDate?.take(4)
                )
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.w("SeriesDetailActivity", "iptvGroupsForEpisode failed: ${e.message}")
            emptyList()
        }
    }

    /** Throttled availability sweep over the IPTV groups; never throws. */
    private suspend fun verifyIptvGroups(
        groups: List<com.tvonnet.debridxtreamiptv.features.seriesv2.ui.model.IptvStreamGroup>,
        seasonNumber: Int,
        episodeNumber: Int
    ): List<com.tvonnet.debridxtreamiptv.features.seriesv2.ui.model.IptvStreamGroup> {
        if (groups.isEmpty()) return groups
        return try {
            withContext(Dispatchers.IO) {
                seriesRepositoryV2.verifyStreamGroups(groups, seasonNumber, episodeNumber)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.w("SeriesDetailActivity", "verifyIptvGroups failed: ${e.message}")
            groups
        }
    }

    /**
     * Map the IPTV cross-category aggregation onto [MovieSource] rows so the Debrid
     * source sheet can offer the same show's Xtream streams alongside debrid results.
     * Unresolved listings carry an empty direct_source and resolve on selection.
     */
    private fun mapIptvGroupsToSources(
        groups: List<com.tvonnet.debridxtreamiptv.features.seriesv2.ui.model.IptvStreamGroup>
    ): List<com.tvonnet.debridxtreamiptv.data.repository.MovieSource> {
        return try {
            groups.flatMap { group ->
                group.streams.map { opt ->
                    com.tvonnet.debridxtreamiptv.data.repository.MovieSource(
                        stream = com.tvonnet.debridxtreamiptv.data.model.XtreamVodInfo(
                            num = null,
                            name = "${group.categoryName} · ${opt.name}",
                            stream_type = "series",
                            stream_id = "iptv:${opt.sourceSeriesId}",
                            stream_icon = null,
                            rating = null,
                            rating_5based = null,
                            added = null,
                            category_id = group.categoryId,
                            category_ids = null,
                            container_extension = opt.containerExtension,
                            custom_sid = opt.streamId.takeUnless { it.startsWith("pending:") },
                            direct_source = opt.playUrl.ifBlank { null },
                            releaseDate = null,
                            duration = null,
                            plot = null,
                            cast = null,
                            director = null,
                            genre = null,
                            youtube_trailer = null,
                            cover = null,
                            rating_imdb = null
                        ),
                        category = null,
                        // The source row renders `label` as its title — lead with the
                        // playlist category so the user sees where each stream lives.
                        label = "${group.categoryName} · ${opt.name}",
                        isPrimary = false,
                        languages = opt.languages.map { it.code.lowercase() },
                        quality = opt.quality.label,
                        isCached = true,
                        cacheStatus = DebridCacheStatus.DIRECT_STREAM,
                        provider = "IPTV",
                        sourceType = "IPTV",
                        sourceName = group.categoryName
                    )
                }
            }
        } catch (e: Exception) {
            android.util.Log.w("SeriesDetailActivity", "mapIptvGroupsToSources failed: ${e.message}")
            emptyList()
        }
    }

    /** Play an injected IPTV source picked from the Debrid source sheet. */
    private fun playIptvSourceFromDebrid(
        source: com.tvonnet.debridxtreamiptv.data.repository.MovieSource,
        episode: EpisodeUiModel
    ) {
        val seasonNumber = selectedSeasonKey?.toIntOrNull()
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
            val resumeMs = episodeProgressMs[episode.id]
                ?.takeIf { it > 0 && !episodeWatched.contains(episode.id) }

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

    private fun consumeDebridReturnFocusStreamIds(episodeId: String): List<String> {
        return pendingDebridReturnFocusStreamIdsByEpisode.remove(episodeId).orEmpty()
    }

    private fun playDebridEpisode(source: com.tvonnet.debridxtreamiptv.data.repository.MovieSource, episode: EpisodeUiModel) {
        lastDebridEpisode = episode
        selectedDebridStreamIdByEpisode[episode.id] = source.stream.stream_id
        val stream = source.stream
        val magnet = stream.direct_source
        val infoHash = stream.stream_id
        val seasonNumber = selectedSeasonKey?.toIntOrNull()
        val episodeNumber = episode.episodeNumber
        val tmdbId = seriesId
        val seriesTitle = seriesName
        // Saved resume position for this episode (null when watched or never started)
        val resumeMs = episodeProgressMs[episode.id]
            ?.takeIf { it > 0 && !episodeWatched.contains(episode.id) }
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
                title = "${seriesName} - S${selectedSeasonKey}E${episode.episodeNumber}",
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
                            failedDebridStreamIdsByEpisode.getOrPut(episode.id) { linkedSetOf() }.add(streamId)
                        }
                        markDebridEpisodeSourceCached(episode.id, source.stream.stream_id, false)
                        val message = "Source is unavailable. Choose another source."
                        Toast.makeText(this@SeriesDetailActivity, message, Toast.LENGTH_LONG).show()
                        fetchAndShowDebridSources(episode, consumeDebridReturnFocusStreamIds(episode.id))
                        return@launch
                    }

                    if (readiness == AddonProxyReadiness.READY) {
                        markDebridEpisodeSourceCached(episode.id, source.stream.stream_id, true)
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
                        title = "${seriesName} - S${selectedSeasonKey}E${episode.episodeNumber}",
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
                    markDebridEpisodeSourceCached(episode.id, infoHash, false)
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
        val cachedSources = cachedDebridSourcesByEpisode[episode.id]
        if (!cachedSources.isNullOrEmpty()) {
            val nextSource = pickNextDebridEpisodeSource(episode.id, cachedSources, failedStreamId)
            if (nextSource != null) {
                selectedDebridStreamIdByEpisode[episode.id] = nextSource.stream.stream_id
                Toast.makeText(this, "Trying next source...", Toast.LENGTH_SHORT).show()
                playDebridEpisode(nextSource, episode)
                return
            }
            fetchAndShowDebridSources(episode)
            return
        }

        val episodeFetchContext = resolveDebridEpisodeFetchContext(episode)
        if (episodeFetchContext == null) {
            val message = "Episode metadata missing for source fetch"
            Toast.makeText(this@SeriesDetailActivity, message, Toast.LENGTH_LONG).show()
            fetchAndShowDebridSources(episode)
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
            cachedDebridSourcesByEpisode[episode.id] = sources
            val nextSource = pickNextDebridEpisodeSource(episode.id, sources, failedStreamId)
            if (nextSource != null) {
                selectedDebridStreamIdByEpisode[episode.id] = nextSource.stream.stream_id
                Toast.makeText(this@SeriesDetailActivity, "Trying next source...", Toast.LENGTH_SHORT).show()
                playDebridEpisode(nextSource, episode)
            } else {
                fetchAndShowDebridSources(episode)
            }
        }
    }

    private fun resolveDebridEpisodeFetchContext(episode: EpisodeUiModel): Pair<Int, Int>? {
        val seasonNumber = selectedSeasonKey?.toIntOrNull()
        val episodeNumber = episode.episodeNumber
        if (seasonNumber == null || episodeNumber == null) return null
        return seasonNumber to episodeNumber
    }

    private fun pickNextDebridEpisodeSource(
        episodeId: String,
        sources: List<com.tvonnet.debridxtreamiptv.data.repository.MovieSource>,
        failedStreamId: String
    ): com.tvonnet.debridxtreamiptv.data.repository.MovieSource? {
        val filterState = debridFilterStateByEpisode[episodeId]
            ?: com.tvonnet.debridxtreamiptv.ui.sources.SourceFilterState()
        val failedIds = failedDebridStreamIdsByEpisode[episodeId].orEmpty()
        val filteredSources = SourceFilterUtils.apply(sources, filterState)
            .filterNot { source -> source.stream.stream_id?.let { it in failedIds } == true }
        if (filteredSources.isEmpty()) return null

        val failedIndex = filteredSources.indexOfFirst { it.stream.stream_id == failedStreamId }
        val nextIndex = when {
            failedIndex in 0 until filteredSources.lastIndex -> failedIndex + 1
            failedIndex == -1 -> 0
            else -> -1
        }
        return if (nextIndex >= 0) filteredSources[nextIndex] else null
    }

    private fun notifyDebridFailure(reason: String?, autoPlayNext: Boolean) {
        if (reason.isNullOrBlank()) return
        val message = if (autoPlayNext) {
            "Playback failed, trying next source: $reason"
        } else {
            "Playback failed: $reason"
        }
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun markDebridEpisodeSourceCached(episodeId: String, streamId: String?, isCached: Boolean) {
        if (streamId.isNullOrBlank()) return
        val current = cachedDebridSourcesByEpisode[episodeId] ?: return
        cachedDebridSourcesByEpisode[episodeId] = current.map { source ->
            if (source.stream.stream_id == streamId) {
                val isDirectHttp = source.stream.direct_source?.startsWith("http", ignoreCase = true) == true &&
                    source.stream.direct_source.endsWith(".torrent", ignoreCase = true).not()
                source.copy(
                    isCached = isCached,
                    cacheStatus = if (isCached) {
                        if (isDirectHttp) DebridCacheStatus.DIRECT_STREAM else DebridCacheStatus.VERIFIED_CACHED
                    } else {
                        DebridCacheStatus.NOT_CACHED
                    }
                )
            } else {
                source
            }
        }
    }

    private fun refreshMediaFusionCacheStatus(
        episodeId: String,
        sources: List<com.tvonnet.debridxtreamiptv.data.repository.MovieSource>,
        bottomSheet: SourceSelectionBottomSheet
    ) {
        val mediaFusionSources = sources.filter { source ->
            val url = source.stream.direct_source
            debridPlaybackRepository.requiresDirectProxyReadinessCheck(
                url,
                source.provider,
                source.sourceName,
                source.sourceType
            )
        }
        if (mediaFusionSources.isEmpty()) return

        lifecycleScope.launch {
            val updates = withContext(Dispatchers.IO) {
                val semaphore = Semaphore(4)
                coroutineScope {
                    mediaFusionSources.map { source ->
                        async {
                            semaphore.withPermit {
                                val url = source.stream.direct_source ?: return@withPermit null
                                val result = debridPlaybackRepository.getAddonProxyPlaybackReadiness(
                                    url,
                                    source.headers,
                                    source.provider,
                                    source.sourceName,
                                    source.sourceType,
                                    diagnosticsContext = this@SeriesDetailActivity
                                )
                                val readiness =
                                    (result as? com.tvonnet.debridxtreamiptv.data.Result.Success)?.data
                                        ?: AddonProxyReadiness.UNCERTAIN
                                source.stream.stream_id to readiness
                            }
                        }
                    }.awaitAll().filterNotNull().toMap()
                }
            }
            if (updates.isEmpty()) return@launch

            val updatedSources = sources.map { source ->
                when (updates[source.stream.stream_id]) {
                    AddonProxyReadiness.READY -> if (source.isCached != true) {
                        source.copy(
                            isCached = true,
                            cacheStatus = DebridCacheStatus.DIRECT_STREAM
                        )
                    } else {
                        source
                    }
                    AddonProxyReadiness.TERMINAL -> {
                        source.stream.stream_id?.let { streamId ->
                            failedDebridStreamIdsByEpisode.getOrPut(episodeId) { linkedSetOf() }.add(streamId)
                        }
                        if (source.isCached != false) {
                            source.copy(
                                isCached = false,
                                cacheStatus = DebridCacheStatus.NOT_CACHED
                            )
                        } else {
                            source
                        }
                    }
                    AddonProxyReadiness.UNCERTAIN,
                    null -> source
                }
            }
            if (updatedSources != sources) {
                cachedDebridSourcesByEpisode[episodeId] = updatedSources
                if (bottomSheet.isAdded) {
                    bottomSheet.showSources(updatedSources)
                }
            }
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

    private fun launchTrailer() {
        val trailerUrl = seriesTrailerUrl
        if (!trailerUrl.isNullOrBlank() && TrailerValueParser.parse(trailerUrl) != null) {
            startActivity(com.tvonnet.debridxtreamiptv.ui.trailer.TrailerActivity.createIntent(this, trailerUrl))
            return
        }

        if (isTrailerLookupInProgress) return

        val tmdbId = seriesId?.toIntOrNull()
        if (tmdbId == null) {
            Toast.makeText(this, R.string.movie_detail_trailer_unavailable, Toast.LENGTH_SHORT).show()
            updateTrailerButtonState()
            return
        }

        lifecycleScope.launch {
            isTrailerLookupInProgress = true
            btnWatchTrailer.isEnabled = false
            try {
                val result = withContext(Dispatchers.IO) {
                    tmdbRemoteDataSource.getSeriesDetails(tmdbId)
                }
                
                result.onSuccess { details ->
                    val trailer = details.videos?.results?.firstOrNull { 
                        it.site?.lowercase() == "youtube" && it.type?.lowercase() == "trailer" 
                    } ?: details.videos?.results?.firstOrNull { 
                        it.site?.lowercase() == "youtube" 
                    }
                    
                    if (trailer?.key != null) {
                        seriesTrailerUrl = trailer.key
                        startActivity(com.tvonnet.debridxtreamiptv.ui.trailer.TrailerActivity.createIntent(this@SeriesDetailActivity, trailer.key))
                    } else {
                        Toast.makeText(this@SeriesDetailActivity, R.string.movie_detail_trailer_unavailable, Toast.LENGTH_SHORT).show()
                    }
                }.onFailure {
                    Toast.makeText(this@SeriesDetailActivity, R.string.movie_detail_trailer_unavailable, Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(this@SeriesDetailActivity, R.string.movie_detail_trailer_unavailable, Toast.LENGTH_SHORT).show()
            } finally {
                isTrailerLookupInProgress = false
                updateTrailerButtonState()
            }
        }
    }

    private fun updateTrailerButtonState() {
        if (!::btnWatchTrailer.isInitialized) return
        val canResolveTrailer = TrailerValueParser.parse(seriesTrailerUrl) != null || seriesId?.toIntOrNull() != null
        btnWatchTrailer.isEnabled = canResolveTrailer && !isTrailerLookupInProgress
        btnWatchTrailer.alpha = if (canResolveTrailer && !isTrailerLookupInProgress) 1.0f else 0.45f
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
