package com.tvonnet.debridxtreamiptv.ui.vod

import android.app.Activity
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.widget.Button
import android.widget.ImageView
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatButton
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.tvonnet.debridxtreamiptv.R
import com.tvonnet.debridxtreamiptv.data.Result
import com.tvonnet.debridxtreamiptv.data.debrid.repository.AddonProxyReadiness
import com.tvonnet.debridxtreamiptv.data.model.ContentType
import com.tvonnet.debridxtreamiptv.data.model.XtreamVodInfo
import com.tvonnet.debridxtreamiptv.data.prefs.CredentialsPreferences
import com.tvonnet.debridxtreamiptv.data.prefs.WatchHistoryPreferences
import com.tvonnet.debridxtreamiptv.data.omdb.OmdbClient
import com.tvonnet.debridxtreamiptv.data.debrid.model.DebridFailureType
import com.tvonnet.debridxtreamiptv.data.repository.DebridCacheStatus
import com.tvonnet.debridxtreamiptv.data.repository.MovieSource
import com.tvonnet.debridxtreamiptv.data.repository.XtreamRepository
import com.tvonnet.debridxtreamiptv.debug.PlaybackDiagnosticsRecorder
import com.tvonnet.debridxtreamiptv.utils.memory.MemoryManager
import com.tvonnet.debridxtreamiptv.player.stabilized.PlayerActivity
import com.tvonnet.debridxtreamiptv.ui.vod.MovieSourceAdapter
import com.tvonnet.debridxtreamiptv.ui.series.LanguageFilterAdapter
import com.tvonnet.debridxtreamiptv.ui.sources.SizeFilterAdapter
import com.tvonnet.debridxtreamiptv.ui.sources.SizeFilterOption
import com.tvonnet.debridxtreamiptv.ui.sources.SourceFilterState
import com.tvonnet.debridxtreamiptv.ui.sources.SourceFilterUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import com.tvonnet.debridxtreamiptv.data.debrid.repository.UnifiedSourceProvider
import com.tvonnet.debridxtreamiptv.data.debrid.source.TmdbRemoteDataSource
import com.tvonnet.debridxtreamiptv.util.SensitiveLogRedactor

@AndroidEntryPoint
class MovieDetailActivity : AppCompatActivity() {

    @Inject
    lateinit var unifiedSourceProvider: UnifiedSourceProvider

    @Inject
    lateinit var tmdbRemoteDataSource: TmdbRemoteDataSource

    @Inject
    lateinit var debridPlaybackRepository: com.tvonnet.debridxtreamiptv.data.debrid.repository.DebridPlaybackRepository

    @Inject
    lateinit var playbackResolver: com.tvonnet.debridxtreamiptv.data.debrid.repository.PlaybackResolver

    @Inject
    lateinit var repository: XtreamRepository

    @Inject
    lateinit var memoryManager: MemoryManager

    @Inject
    lateinit var credentialsPrefs: CredentialsPreferences

    @Inject
    lateinit var favoriteDao: com.tvonnet.debridxtreamiptv.data.local.dao.FavoriteDao

    private lateinit var ivBackdrop: ImageView

    private lateinit var tvTitle: TextView
    private lateinit var tvRatingStars: TextView
    private lateinit var tvRatingPercentage: TextView
    private lateinit var tvGenreYear: TextView
    private lateinit var layoutMetadataRow: View

    private lateinit var tvDirector: TextView
    private lateinit var tvDescription: TextView
    private lateinit var tvCastTitle: TextView
    private lateinit var rvCast: RecyclerView
    private lateinit var castAdapter: CastAdapter

    private lateinit var tvDuration: TextView
    private lateinit var detailsGroup: View
    private lateinit var tvSourcesHeader: TextView
    private lateinit var tvSourcesStatus: TextView
    private lateinit var rvSources: RecyclerView
    private lateinit var sourcesAdapter: MovieSourceAdapter
    private lateinit var layoutSourceFilters: View
    private lateinit var btnCachedOnly: TextView

    private lateinit var rvLanguageFilters: RecyclerView
    private lateinit var sizeFilterAdapter: SizeFilterAdapter
    private lateinit var languageFilterAdapter: LanguageFilterAdapter

    // Redesign views
    private lateinit var layoutRdSummary: View
    private lateinit var tvRdSummaryText: TextView
    private lateinit var btnPlay: Button
    private lateinit var btnTrailer: Button
    private lateinit var btnFavorite: ImageButton
    private lateinit var btnBack: ImageButton
    private var isFavorite: Boolean = false
    private lateinit var tvContentType: TextView
    private lateinit var tvYear: TextView
    private lateinit var tvQualityBadge: TextView
    private lateinit var tvAgeRating: TextView
    private lateinit var badgeImdb: View
    private lateinit var tvImdbRating: TextView
    private lateinit var badgeRt: View
    private lateinit var tvRtRating: TextView
    private lateinit var layoutRdStatus: View
    private lateinit var tvPremiumBadge: TextView
    private lateinit var tvBreadcrumbType: TextView
    private lateinit var tvBackHint: TextView
    private var movieSourceRail: String? = null
    private lateinit var layoutSimilarRow: View
    private lateinit var rvSimilar: RecyclerView
    private lateinit var similarAdapter: SimilarMoviesAdapter

    // Resume bar
    private lateinit var layoutResumeBar: View
    private lateinit var tvResumeElapsed: TextView
    private lateinit var tvResumeRemaining: TextView
    private lateinit var pbResume: ProgressBar
    private val watchHistoryPrefs by lazy { WatchHistoryPreferences(this) }
    private var hasResumePosition: Boolean = false
    private var resumePositionMs: Long = 0L
    // The saved Continue-Watching entry for this movie, so pressing "Resume" can relaunch
    // the exact same (Debrid) source and seek — instead of reopening the source picker.
    private var resumeCwItem: com.tvonnet.debridxtreamiptv.data.model.ContinueWatchingItem? = null

    // Movie data
    private var movieId: String? = null
    private var movieName: String? = null
    private var movieIcon: String? = null
    private var movieRating: String? = null
    private var movieYear: String? = null
    private var movieGenre: String? = null
    private var moviePlot: String? = null
    private var movieDirector: String? = null
    private var movieCast: String? = null
    private var movieDuration: String? = null
    private var movieContainer: String? = null
    private var movieBackdrop: String? = null
    private var movieAgeRating: String? = null
    /** MD-1: trailer resolve + hover-preview + Trailer button state. */
    private lateinit var movieTrailerController: MovieTrailerController
    private var movieCategoryId: String? = null
    private var selectedSource: MovieSource? = null
    private var sourcesLoadJob: Job? = null
    private var isDebridMovie: Boolean = false
    private var currentImdbId: String? = null
    private var debridSources: List<MovieSource> = emptyList()
    private var allSources: List<MovieSource> = emptyList()
    private var preferredLanguage: String? = null
    private var selectedSizeOption: SizeFilterOption = SourceFilterUtils.SIZE_OPTIONS.first()
    private var filterState: SourceFilterState = SourceFilterState()
    private var debridFilterState: SourceFilterState = SourceFilterState()
    private var selectedDebridStreamId: String? = null
    private val failedDebridStreamIds = linkedSetOf<String>()
    private var pendingDebridReturnFocusStreamIds: List<String> = emptyList()
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
            if (!failedStreamId.isNullOrBlank()) {
                failedDebridStreamIds.add(failedStreamId)
                markDebridSourceStatus(failedStreamId, DebridCacheStatus.NOT_CACHED)
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
            if (allowAutoPlayNext && !failedStreamId.isNullOrBlank()) {
                autoFallbackAttempts++
                autoPlayNextDebridSource(failedStreamId)
            } else {
                autoFallbackAttempts = 0
                showDebridSourcePicker(consumeDebridReturnFocusStreamIds())
            }
        }
    }

    companion object {
        const val EXTRA_MOVIE_ID = "MOVIE_ID"
        const val EXTRA_MOVIE_NAME = "MOVIE_NAME"
        const val EXTRA_MOVIE_ICON = "MOVIE_ICON"
        const val EXTRA_MOVIE_RATING = "MOVIE_RATING"
        const val EXTRA_MOVIE_YEAR = "MOVIE_YEAR"
        const val EXTRA_MOVIE_GENRE = "MOVIE_GENRE"
        const val EXTRA_MOVIE_PLOT = "MOVIE_PLOT"
        const val EXTRA_MOVIE_DIRECTOR = "MOVIE_DIRECTOR"
        const val EXTRA_MOVIE_CAST = "MOVIE_CAST"
        const val EXTRA_MOVIE_DURATION = "MOVIE_DURATION"
        const val EXTRA_MOVIE_CONTAINER = "MOVIE_CONTAINER"
        const val EXTRA_MOVIE_BACKDROP = "MOVIE_BACKDROP"
        const val EXTRA_MOVIE_CATEGORY_ID = "MOVIE_CATEGORY_ID"
        const val EXTRA_MOVIE_TRAILER = "MOVIE_TRAILER"
        const val EXTRA_SOURCE_RAIL = "SOURCE_RAIL"
        private const val MAX_AUTO_FALLBACK_ATTEMPTS = 3
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.fragment_movie_detail)

        // Repository initialized by Hilt
        initializeRepository()

        initViews()
        getMovieDataFromIntent()
        openedFromPlaybackFailure = intent.getBooleanExtra(PlayerActivity.EXTRA_OPENED_FROM_PLAYBACK_FAILURE, false)
        if (openedFromPlaybackFailure) {
            // The player redirected here after a terminal failure (fresh startActivity, so
            // the playerLauncher callback never runs). Without this the bounce is silent.
            val reason = intent.getStringExtra(PlayerActivity.EXTRA_FAIL_REASON)
            Toast.makeText(
                this,
                if (reason.isNullOrBlank()) "Playback failed. Pick another source."
                else "Playback failed: $reason",
                Toast.LENGTH_LONG
            ).show()
        }
        movieTrailerController.refreshButtonState()
        displayMovieDetails()
        configureTabs()
        loadFavoriteState()
        refreshResumeState()

        // Start data loading chain
        if (movieCategoryId == "debrid" && movieId != null) {
            val id = movieId!!.toIntOrNull()
            if (id != null) {
                fetchTmdbDetails(movieId!!)
            } else {
                // ID is likely an InfoHash from History
                android.util.Log.d("MovieDetailActivity", "Detected Debrid Hash ID: ${SensitiveLogRedactor.describeHash(movieId)}")
                handleDebridHistoryItem(movieId!!)
            }
        } else {
            loadMovieSources(null)
        }
    }

    private fun fetchTmdbDetails(tmdbId: String) {
        lifecycleScope.launch {
            var imdbId: String? = null
            try {
                // Check if it's a valid integer ID
                val id = tmdbId.toIntOrNull()
                
                if (id != null) {
                    val result = withContext(Dispatchers.IO) {
                        tmdbRemoteDataSource.getMovieDetails(id)
                    }
                    
                    when (result) {
                        is com.tvonnet.debridxtreamiptv.data.Result.Success -> {
                            val details = result.data
                            imdbId = details.imdbId
                            currentImdbId = imdbId
                            loadExternalRatings()

                            moviePlot = details.overview
                            movieRating = details.voteAverage?.toString()
                            movieYear = details.releaseDate?.take(4)
                            movieDuration = details.runtime?.let { (it * 60).toString() }
                            movieGenre = details.genres?.joinToString(", ") { it.name ?: "" }
                            movieDirector = details.credits?.crew
                                ?.firstOrNull { it.job?.equals("Director", ignoreCase = true) == true }
                                ?.name
                            movieAgeRating = extractCertification(details.releaseDates)

                            val castList = details.credits?.cast
                            if (!castList.isNullOrEmpty()) {
                                castAdapter.submitList(castList.take(5))
                                tvCastTitle.visibility = View.VISIBLE
                                rvCast.visibility = View.VISIBLE
                            } else {
                                tvCastTitle.visibility = View.GONE
                                rvCast.visibility = View.GONE
                            }
                            // Cache a trailer key so the Trailer button dwell-preview
                            // and enabled state work without a second lookup.
                            if (movieTrailerController.trailerUrl.isNullOrBlank()) {
                                val key = details.videos?.results?.firstOrNull {
                                    it.site?.lowercase() == "youtube" && it.type?.lowercase() == "trailer"
                                }?.key ?: details.videos?.results?.firstOrNull {
                                    it.site?.lowercase() == "youtube"
                                }?.key
                                if (!key.isNullOrBlank()) movieTrailerController.setTrailerUrl(key)
                            }
                            movieTrailerController.refreshButtonState()

                            displayMovieDetails()
                            refreshResumeState()
                            loadSimilarMovies(id)
                        }
                        is com.tvonnet.debridxtreamiptv.data.Result.Error -> {
                            android.util.Log.e("MovieDetailActivity", "Failed to fetch TMDB details", result.exception)
                        }
                        else -> {}
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("MovieDetailActivity", "Error fetching details", e)
            } finally {
                // Always load sources, even if details fetch failed
                loadMovieSources(imdbId)
            }
        }
    }

    private fun initializeRepository() {
        val serverUrl = credentialsPrefs.getServerUrl()
        val username = credentialsPrefs.getUsername()
        val password = credentialsPrefs.getPassword()
        if (!serverUrl.isNullOrBlank() && !username.isNullOrBlank() && !password.isNullOrBlank()) {
            repository.ensureInitialized(serverUrl, username, password)
        }
    }

    private fun initViews() {
        ivBackdrop = findViewById(R.id.iv_backdrop)

        tvTitle = findViewById(R.id.tv_title)
        tvRatingStars = findViewById(R.id.tv_rating_stars)
        tvRatingPercentage = findViewById(R.id.tv_rating_percentage)
        tvGenreYear = findViewById(R.id.tv_genre_year)
        layoutMetadataRow = findViewById(R.id.layout_metadata_row)
        tvDirector = findViewById(R.id.tv_director)
        tvCastTitle = findViewById(R.id.tv_cast_title)
        rvCast = findViewById(R.id.rv_cast)
        tvDescription = findViewById(R.id.tv_description)
        tvDuration = findViewById(R.id.tv_duration)

        detailsGroup = findViewById(R.id.group_details)
        tvSourcesHeader = findViewById(R.id.tv_sources_header)
        tvSourcesStatus = findViewById(R.id.tv_sources_status)
        layoutSourceFilters = findViewById(R.id.layout_source_filters)
        btnCachedOnly = findViewById(R.id.btn_cached_only)

        rvLanguageFilters = findViewById(R.id.rv_language_filters)
        rvSources = findViewById(R.id.rv_sources)

        // Redesign view binding
        layoutRdSummary = findViewById(R.id.layout_rd_summary)
        tvRdSummaryText = findViewById(R.id.tv_rd_summary_text)
        btnPlay = findViewById(R.id.btn_play)
        btnTrailer = findViewById(R.id.btn_trailer)
        btnFavorite = findViewById(R.id.btn_favorite)
        btnBack = findViewById(R.id.btn_back)
        tvContentType = findViewById(R.id.tv_content_type)
        tvYear = findViewById(R.id.tv_year)
        tvQualityBadge = findViewById(R.id.tv_quality_badge)
        tvAgeRating = findViewById(R.id.tv_age_rating)
        badgeImdb = findViewById(R.id.badge_imdb)
        tvImdbRating = findViewById(R.id.tv_imdb_rating)
        badgeRt = findViewById(R.id.badge_rt)
        tvRtRating = findViewById(R.id.tv_rt_rating)
        layoutRdStatus = findViewById(R.id.layout_rd_status)
        tvPremiumBadge = findViewById(R.id.tv_premium_badge)
        tvBreadcrumbType = findViewById(R.id.tv_breadcrumb_type)
        tvBackHint = findViewById(R.id.tv_back_hint)
        layoutSimilarRow = findViewById(R.id.layout_similar_row)
        rvSimilar = findViewById(R.id.rv_similar)

        layoutResumeBar = findViewById(R.id.layout_resume_bar)
        tvResumeElapsed = findViewById(R.id.tv_resume_elapsed)
        tvResumeRemaining = findViewById(R.id.tv_resume_remaining)
        pbResume = findViewById(R.id.pb_resume)

        similarAdapter = SimilarMoviesAdapter { movie ->
            val tmdbId = movie.id?.toString() ?: return@SimilarMoviesAdapter
            val intent = android.content.Intent(this, MovieDetailActivity::class.java).apply {
                putExtra(EXTRA_MOVIE_ID, tmdbId)
                putExtra(EXTRA_MOVIE_NAME, movie.title)
                putExtra(EXTRA_MOVIE_ICON, com.tvonnet.debridxtreamiptv.data.debrid.model.TmdbImageUrl.getPosterUrl(movie.posterPath))
                putExtra(EXTRA_MOVIE_BACKDROP, com.tvonnet.debridxtreamiptv.data.debrid.model.TmdbImageUrl.getBackdropUrl(movie.backdropPath))
                putExtra(EXTRA_MOVIE_YEAR, movie.releaseDate?.take(4))
                putExtra(EXTRA_MOVIE_RATING, movie.voteAverage?.toString())
                putExtra(EXTRA_MOVIE_CATEGORY_ID, "debrid")
            }
            startActivity(intent)
        }
        rvSimilar.apply {
            layoutManager = LinearLayoutManager(this@MovieDetailActivity, LinearLayoutManager.HORIZONTAL, false)
            adapter = similarAdapter
        }

        movieTrailerController = MovieTrailerController(
            activity = this,
            tmdbRemoteDataSource = tmdbRemoteDataSource,
            button = btnTrailer,
            backdrop = ivBackdrop,
            movieId = { movieId },
            movieBackdrop = { movieBackdrop }
        )

        setupClickListeners()
        setupFocusAnimations()

        sourcesAdapter = MovieSourceAdapter(
            onSourceFocused = { source -> applySelectedSource(source, shouldPlay = false, updateAdapterSelection = false) },
            onSourceClicked = { source -> applySelectedSource(source, shouldPlay = true) },
            onNavigateUpFromFirstRow = {
                (btnCachedOnly.visibility == View.VISIBLE && btnCachedOnly.requestFocus()) ||
                    rvLanguageFilters.requestFocus()
            }
        )
        rvSources.apply {
            layoutManager = LinearLayoutManager(this@MovieDetailActivity)
            adapter = sourcesAdapter
            setHasFixedSize(true)
            isFocusable = true
            isFocusableInTouchMode = true
        }
        sizeFilterAdapter = SizeFilterAdapter { option ->
            selectedSizeOption = option
            sizeFilterAdapter.updateSelection(option)
            filterState = filterState.copy(maxSizeBytes = option.maxSizeBytes)
            applySourceFilters()
        }


        languageFilterAdapter = LanguageFilterAdapter { language ->
            preferredLanguage = if (preferredLanguage == language) null else language
            languageFilterAdapter.updateSelection(preferredLanguage)
            applySourceFilters()
        }
        rvLanguageFilters.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        rvLanguageFilters.setHasFixedSize(true)
        rvLanguageFilters.adapter = languageFilterAdapter

        castAdapter = CastAdapter()
        rvCast.apply {
            layoutManager = LinearLayoutManager(this@MovieDetailActivity, LinearLayoutManager.HORIZONTAL, false)
            adapter = castAdapter
        }

        btnCachedOnly.setOnClickListener {
            filterState = filterState.copy(cachedOnly = !filterState.cachedOnly)
            btnCachedOnly.isSelected = filterState.cachedOnly
            applySourceFilters()
        }
        btnCachedOnly.setOnFocusChangeListener { _, hasFocus ->
            btnCachedOnly.isSelected = hasFocus || filterState.cachedOnly
        }
    }


    private fun getMovieDataFromIntent() {
        movieId = intent.getStringExtra(EXTRA_MOVIE_ID)
        movieName = intent.getStringExtra(EXTRA_MOVIE_NAME)
        movieIcon = intent.getStringExtra(EXTRA_MOVIE_ICON)
        movieRating = intent.getStringExtra(EXTRA_MOVIE_RATING)
        movieYear = intent.getStringExtra(EXTRA_MOVIE_YEAR)
        movieGenre = intent.getStringExtra(EXTRA_MOVIE_GENRE)
        moviePlot = intent.getStringExtra(EXTRA_MOVIE_PLOT)
        movieDirector = intent.getStringExtra(EXTRA_MOVIE_DIRECTOR)
        movieCast = intent.getStringExtra(EXTRA_MOVIE_CAST)
        movieDuration = intent.getStringExtra(EXTRA_MOVIE_DURATION)
        movieContainer = intent.getStringExtra(EXTRA_MOVIE_CONTAINER)
        movieBackdrop = intent.getStringExtra(EXTRA_MOVIE_BACKDROP)
        movieTrailerController.setTrailerUrl(intent.getStringExtra(EXTRA_MOVIE_TRAILER))
        movieCategoryId = intent.getStringExtra(EXTRA_MOVIE_CATEGORY_ID)
        movieSourceRail = intent.getStringExtra(EXTRA_SOURCE_RAIL)
        isDebridMovie = movieCategoryId == "debrid"
    }



    private fun displayMovieDetails() {
        tvTitle.text = movieName ?: getString(R.string.movie_detail_unknown_movie)

        // Back-to-rail context hint (shown when the caller told us where we came from)
        val rail = movieSourceRail?.trim()
        if (!rail.isNullOrBlank()) {
            tvBackHint.text = "◄ BACK TO ${rail.uppercase(java.util.Locale.getDefault())}"
            tvBackHint.visibility = View.VISIBLE
        } else {
            tvBackHint.visibility = View.GONE
        }

        // Content type label
        if (isDebridMovie) {
            tvContentType.text = "FEATURE FILM"
            tvContentType.visibility = View.VISIBLE
        } else {
            tvContentType.visibility = View.GONE
        }

        // Rating
        val rating = movieRating?.toDoubleOrNull() ?: 0.0
        val hasRating = rating > 0.0
        tvRatingPercentage.visibility = if (hasRating) View.VISIBLE else View.GONE
        tvRatingStars.visibility = View.GONE
        if (hasRating) {
            tvRatingPercentage.text = String.format("%.1f", rating)
        }

        // Year
        if (!movieYear.isNullOrBlank()) {
            tvYear.text = movieYear
            tvYear.visibility = View.VISIBLE
        } else {
            tvYear.visibility = View.GONE
        }

        // Genre (without year — year is separate now)
        tvGenreYear.text = movieGenre.orEmpty()
        tvGenreYear.visibility = if (movieGenre.isNullOrBlank()) View.GONE else View.VISIBLE

        // Age / content rating badge
        val ageRating = movieAgeRating?.trim()
        if (!ageRating.isNullOrBlank()) {
            tvAgeRating.text = ageRating
            tvAgeRating.visibility = View.VISIBLE
        } else {
            tvAgeRating.visibility = View.GONE
        }

        val hasMetadataRow = hasRating || !movieGenre.isNullOrBlank() || !movieDuration.isNullOrBlank() ||
            !movieYear.isNullOrBlank() || !ageRating.isNullOrBlank()
        layoutMetadataRow.visibility = if (hasMetadataRow) View.VISIBLE else View.GONE

        // Director
        if (!movieDirector.isNullOrBlank()) {
            tvDirector.text = movieDirector
            tvDirector.visibility = View.VISIBLE
        } else {
            tvDirector.visibility = View.GONE
        }

        val descriptionText = moviePlot?.takeIf { it.isNotBlank() }
        tvDescription.text = descriptionText.orEmpty()
        tvDescription.visibility = if (descriptionText.isNullOrBlank()) View.GONE else View.VISIBLE

        // Duration in compact format
        val durationText = movieDuration?.takeIf { it.isNotBlank() }?.let { formatDurationCompact(it) }
        tvDuration.text = durationText.orEmpty()
        tvDuration.visibility = if (durationText.isNullOrBlank()) View.GONE else View.VISIBLE

        if (!movieBackdrop.isNullOrBlank()) {
            Glide.with(this)
                .load(movieBackdrop)
                .centerCrop()
                .into(ivBackdrop)
        }

        // RD CONNECTED status pill removed per updated design — keep hidden.
        layoutRdStatus.visibility = View.GONE
        tvBreadcrumbType.text = if (isDebridMovie) "/ MOVIES" else "/ VOD"
    }

    /**
     * Shows the resume bar when a saved watch position exists for this movie with
     * 0 < progress < 100%, and flips the Watch Now button label to "Resume".
     */
    private fun refreshResumeState() {
        if (!::layoutResumeBar.isInitialized) return

        val resumeItem = watchHistoryPrefs.getMovieResumeItem(
            contentId = movieId,
            tmdbId = movieId?.takeIf { it.toIntOrNull() != null },
            imdbId = currentImdbId,
            title = movieName
        )

        val position = resumeItem?.currentPosition ?: 0L
        val duration = resumeItem?.totalDuration ?: 0L
        val progress = if (duration > 0L) ((position * 100) / duration).toInt() else 0

        hasResumePosition = resumeItem != null && position > 0L && progress in 1..99
        resumePositionMs = if (hasResumePosition) position else 0L
        resumeCwItem = if (hasResumePosition) resumeItem else null
        if (hasResumePosition) {
            tvResumeElapsed.text = "RESUME FROM ${formatResumeTime(position)}"
            tvResumeRemaining.text = "${formatResumeTime(duration - position)} LEFT"
            pbResume.progress = progress
            layoutResumeBar.visibility = View.VISIBLE
        } else {
            layoutResumeBar.visibility = View.GONE
        }

        if (::btnPlay.isInitialized) {
            btnPlay.text = if (hasResumePosition) "Resume" else "Watch Now"
        }
    }

    /** Formats a millisecond duration as a clock: "1:12:34" / "12:34". */
    private fun formatResumeTime(milliseconds: Long): String {
        val totalSeconds = milliseconds.coerceAtLeast(0L) / 1000L
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            String.format(java.util.Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(java.util.Locale.US, "%d:%02d", minutes, seconds)
        }
    }

    /** Picks a content/age certification, preferring US then GB, else the first available. */
    private fun extractCertification(
        releaseDates: com.tvonnet.debridxtreamiptv.data.debrid.model.TmdbReleaseDatesResponse?
    ): String? {
        val results = releaseDates?.results ?: return null
        fun certFor(country: String): String? = results
            .firstOrNull { it.countryCode.equals(country, ignoreCase = true) }
            ?.releaseDates
            ?.mapNotNull { it.certification?.trim() }
            ?.firstOrNull { it.isNotBlank() }
        return certFor("US")
            ?: certFor("GB")
            ?: results.flatMap { it.releaseDates ?: emptyList() }
                .mapNotNull { it.certification?.trim() }
                .firstOrNull { it.isNotBlank() }
    }

    private fun formatDurationCompact(duration: String): String {
        val seconds = duration.toLongOrNull()
        return if (seconds != null) {
            val hours = seconds / 3600
            val minutes = (seconds % 3600) / 60
            if (hours > 0) "${hours}H ${minutes}M" else "${minutes}M"
        } else {
            duration
        }
    }

    private fun displayRating(rating: Double) {
        val stars = (rating / 2.0).coerceAtLeast(0.0).toInt().coerceAtMost(5)
        val starString = "★".repeat(stars) + "☆".repeat(5 - stars)
        tvRatingStars.text = starString
        tvRatingPercentage.text = getString(R.string.movie_detail_rating_format, rating)
    }

    private fun formatDuration(duration: String): String {
        val seconds = duration.toLongOrNull()
        return if (seconds != null) {
            val hours = seconds / 3600
            val minutes = (seconds % 3600) / 60
            if (hours > 0) {
                getString(R.string.movie_detail_hours_minutes_duration, hours, minutes)
            } else {
                getString(R.string.movie_detail_minutes_duration, minutes)
            }
        } else {
            getString(R.string.movie_detail_generic_duration, duration)
        }
    }

    private fun configureTabs() {
    }

    /** Fetches IMDb + Rotten Tomatoes scores from OMDb and shows them in the ratings row. */
    private fun loadExternalRatings() {
        val imdb = currentImdbId ?: return
        lifecycleScope.launch {
            val ratings = OmdbClient.fetchRatings(imdb) ?: return@launch
            ratings.imdbRating?.let {
                tvImdbRating.text = it
                badgeImdb.visibility = View.VISIBLE
            }
            ratings.rottenTomatoes?.let {
                tvRtRating.text = it
                badgeRt.visibility = View.VISIBLE
            }
            // OMDb age rating as a fallback when TMDB certification is missing.
            if (movieAgeRating.isNullOrBlank() && !ratings.rated.isNullOrBlank()) {
                movieAgeRating = ratings.rated
                tvAgeRating.text = ratings.rated
                tvAgeRating.visibility = View.VISIBLE
                layoutMetadataRow.visibility = View.VISIBLE
            }
        }
    }

    private fun loadSimilarMovies(tmdbId: Int) {
        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    tmdbRemoteDataSource.getMovieRecommendations(tmdbId)
                }
                when (result) {
                    is com.tvonnet.debridxtreamiptv.data.Result.Success -> {
                        val movies = result.data.results?.take(6) ?: emptyList()
                        if (movies.isNotEmpty()) {
                            similarAdapter.submitList(movies)
                            val title = movieName?.trim()
                            findViewById<TextView>(R.id.header_similar).text =
                                if (!title.isNullOrBlank()) {
                                    "BECAUSE YOU WATCHED ${title.uppercase(java.util.Locale.getDefault())}"
                                } else {
                                    "SIMILAR MOVIES"
                                }
                            layoutSimilarRow.visibility = View.VISIBLE
                        }
                    }
                    else -> {}
                }
            } catch (_: Exception) {}
        }
    }



    private fun loadMovieSources(imdbId: String?) {
        tvSourcesHeader.visibility = View.VISIBLE
        tvSourcesStatus.visibility = View.VISIBLE
        tvSourcesStatus.text = getString(R.string.movie_detail_sources_loading)
        rvSources.visibility = View.GONE
        layoutSourceFilters.visibility = View.GONE
        selectedSource = null
        sourcesAdapter.updateSelection(null, notify = false)
        resetSourceFilters()
        debridSources = emptyList()

        sourcesLoadJob?.cancel()
        sourcesLoadJob = lifecycleScope.launch {
            try {
                val sources = withContext(Dispatchers.IO) {
                    // Use UnifiedSourceProvider for all source resolution
                    unifiedSourceProvider.getMovieSources(
                        streamId = movieId,
                        title = movieName,
                        primaryCategoryId = movieCategoryId,
                        yearHint = movieYear,
                        imdbId = imdbId
                    )
                }
                if (sources.isEmpty()) {
                    sourcesAdapter.submitList(emptyList())
                    tvSourcesHeader.visibility = View.VISIBLE
                    tvSourcesStatus.visibility = View.VISIBLE
                    tvSourcesStatus.text = getString(R.string.movie_detail_sources_empty)
                    rvSources.visibility = View.GONE
                    layoutSourceFilters.visibility = View.GONE
                } else {
                    tvSourcesHeader.visibility = View.VISIBLE
                    allSources = sources
                    
                    // TASK 020: Update Summary
                    updateRdSummary(sources)
                    
                    updateSourceFilterOptions()
                    applySourceFilters(isInitialLoad = true)
                }
            } catch (e: Exception) {
                android.util.Log.e("MovieDetailActivity", "Failed to load movie sources", e)
                sourcesAdapter.submitList(emptyList())
                tvSourcesHeader.visibility = View.VISIBLE
                tvSourcesStatus.visibility = View.VISIBLE
                tvSourcesStatus.text = getString(R.string.movie_detail_sources_empty)
                rvSources.visibility = View.GONE
                layoutSourceFilters.visibility = View.GONE
            }
        }
    }

    private fun resetSourceFilters() {
        allSources = emptyList()
        preferredLanguage = null
        selectedSizeOption = SourceFilterUtils.SIZE_OPTIONS.first()
        filterState = SourceFilterState()
        languageFilterAdapter.updateSelection(null)
        sizeFilterAdapter.updateSelection(selectedSizeOption)
    }

    private fun updateSourceFilterOptions() {
        val languages = allSources.flatMap { it.languages ?: listOf("multi") }
            .distinct()
            .sorted()

        val showLanguages = languages.size > 1
        rvLanguageFilters.isVisible = showLanguages
        if (showLanguages) {
            languageFilterAdapter.submitList(languages)
        } else {
            preferredLanguage = null
            languageFilterAdapter.updateSelection(null)
            languageFilterAdapter.submitList(emptyList())
        }

        val showSizes = allSources.any { it.sizeBytes != null }

        if (showSizes) {
            sizeFilterAdapter.submitList(SourceFilterUtils.SIZE_OPTIONS)
            sizeFilterAdapter.updateSelection(selectedSizeOption)
        } else {
            selectedSizeOption = SourceFilterUtils.SIZE_OPTIONS.first()
            filterState = filterState.copy(maxSizeBytes = null)
            sizeFilterAdapter.updateSelection(null)
            sizeFilterAdapter.submitList(emptyList())
        }

        val showCached = allSources.any { SourceFilterUtils.hasCacheConfidence(it) }
        btnCachedOnly.isVisible = showCached
        if (!showCached) {
            filterState = filterState.copy(cachedOnly = false)
            btnCachedOnly.isSelected = false
        } else {
            btnCachedOnly.isSelected = filterState.cachedOnly
        }

        layoutSourceFilters.isVisible = showCached || showSizes || showLanguages
    }

    private fun applySourceFilters(isInitialLoad: Boolean = false) {
        val updatedState = filterState.copy(preferredLanguage = preferredLanguage)
        val filteredSources = SourceFilterUtils.apply(allSources, updatedState)
        sourcesAdapter.submitList(filteredSources) {
            if (isInitialLoad) {
                btnPlay.requestFocus()
            }
        }

        val hasSources = filteredSources.isNotEmpty()
        rvSources.isVisible = hasSources
        tvSourcesStatus.isVisible = !hasSources
        if (!hasSources) {
            tvSourcesStatus.text = getString(R.string.source_filters_empty)
        }
    }

    private fun showDebridSourcePicker(returnFocusStreamIds: List<String> = emptyList()) {
        val bottomSheet = com.tvonnet.debridxtreamiptv.ui.series.SourceSelectionBottomSheet(
            onSourceSelected = { source, adjacentStreamIds ->
                selectedDebridStreamId = source.stream.stream_id
                pendingDebridReturnFocusStreamIds = adjacentStreamIds
                PlaybackDiagnosticsRecorder.record(
                    this,
                    "source_selected",
                    PlaybackDiagnosticsRecorder.contentFields(
                        kind = "movie",
                        tmdbId = movieId?.takeIf { it.toIntOrNull() != null },
                        imdbId = currentImdbId
                    ) + PlaybackDiagnosticsRecorder.sourceFields(this, source)
                )
                if (source.sourceType == "IPTV") {
                    // Xtream listing: play the provider URL directly, no debrid resolve.
                    playMovie(source)
                } else {
                    playDebridMovie(source.stream, source, returnToSources = true)
                }
            },
            onStateChanged = { state ->
                debridFilterState = state
            },
            initialState = debridFilterState,
            initialSelectedStreamId = selectedDebridStreamId,
            initialReturnFocusStreamIds = returnFocusStreamIds,
            contentTitle = movieName,
            backdropUrl = movieBackdrop,
            preferredAudioLang = credentialsPrefs.preferredAudioLang
        )

        bottomSheet.show(supportFragmentManager, com.tvonnet.debridxtreamiptv.ui.series.SourceSelectionBottomSheet.TAG)

        if (debridSources.isNotEmpty()) {
            PlaybackDiagnosticsRecorder.record(
                this,
                "source_list_loaded",
                PlaybackDiagnosticsRecorder.contentFields(
                    kind = "movie",
                    tmdbId = movieId?.takeIf { it.toIntOrNull() != null },
                    imdbId = currentImdbId
                ) + PlaybackDiagnosticsRecorder.sourceListFields(this, debridSources)
            )
            bottomSheet.showSources(debridSources)
            refreshMediaFusionCacheStatus(debridSources) { updated ->
                debridSources = updated
                if (bottomSheet.isAdded) {
                    bottomSheet.showSources(updated)
                }
            }
            return
        }

        bottomSheet.showLoading()
        lifecycleScope.launch {
            try {
                val sources = withContext(Dispatchers.IO) {
                    val debrid = unifiedSourceProvider.getMovieSources(
                        streamId = movieId,
                        title = movieName,
                        primaryCategoryId = movieCategoryId,
                        yearHint = movieYear,
                        imdbId = currentImdbId
                    )
                    // Like the series stream panel: surface the same movie's IPTV (Xtream)
                    // listings alongside the debrid sources, badged "IPTV".
                    val iptv = try {
                        fetchIptvMovieSources(movieName)
                    } catch (e: Exception) {
                        android.util.Log.w("MovieDetailActivity", "IPTV source lookup failed: ${e.message}")
                        emptyList()
                    }
                    debrid + iptv
                }
                debridSources = sources
                PlaybackDiagnosticsRecorder.record(
                    this@MovieDetailActivity,
                    "source_list_loaded",
                    PlaybackDiagnosticsRecorder.contentFields(
                        kind = "movie",
                        tmdbId = movieId?.takeIf { it.toIntOrNull() != null },
                        imdbId = currentImdbId
                    ) + PlaybackDiagnosticsRecorder.sourceListFields(this@MovieDetailActivity, sources)
                )
                if (sources.isEmpty()) {
                    bottomSheet.showError(getString(R.string.movie_detail_sources_empty))
                } else {
                    // Update summary on main page as well
                    updateRdSummary(sources)
                    
                    bottomSheet.showSources(sources)
                    refreshMediaFusionCacheStatus(sources) { updated ->
                        debridSources = updated
                        updateRdSummary(updated)
                        if (bottomSheet.isAdded) {
                            bottomSheet.showSources(updated)
                        }
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("MovieDetailActivity", "Failed to load movie sources", e)
                bottomSheet.showError(e.message ?: getString(R.string.movie_detail_sources_empty))
            }
        }
    }

    /**
     * IPTV (Xtream) listings of this movie for the source picker. Delegates to the shared
     * [com.tvonnet.debridxtreamiptv.data.debrid.repository.IptvMovieMatcher] (also used by
     * the in-player Sources panel) so both surfaces match identically.
     */
    private suspend fun fetchIptvMovieSources(title: String?): List<MovieSource> {
        val query = com.tvonnet.debridxtreamiptv.data.debrid.repository.IptvMovieMatcher
            .searchQuery(title) ?: return emptyList()
        return com.tvonnet.debridxtreamiptv.data.debrid.repository.IptvMovieMatcher
            .buildSources(repository.searchVod(query), title, movieYear)
    }

    private fun consumeDebridReturnFocusStreamIds(): List<String> {
        val streamIds = pendingDebridReturnFocusStreamIds
        pendingDebridReturnFocusStreamIds = emptyList()
        return streamIds
    }

    private fun updateRdSummary(sources: List<MovieSource>) {
        if (sources.isEmpty()) {
            layoutRdSummary.visibility = View.GONE
            return
        }

        val cachedCount = sources.count {
            it.cacheStatus == DebridCacheStatus.VERIFIED_CACHED || it.cacheStatus == DebridCacheStatus.DIRECT_STREAM
        }
        val bestQuality = sources.mapNotNull { it.quality }.distinct()
            .sortedByDescending { q ->
                when (q.uppercase()) {
                    "4K" -> 100
                    "2160P" -> 99
                    "1080P" -> 80
                    "720P" -> 60
                    else -> 0
                }
            }.firstOrNull() ?: "HD"

        val bestSize = sources.filter { it.quality?.uppercase() in listOf("4K", "2160P") }
            .mapNotNull { it.sizeBytes }
            .maxOrNull()
            ?.let { formatSizeLabel(it) }

        layoutRdSummary.visibility = View.VISIBLE
        val summary = buildString {
            append("${sources.size} SOURCES FOUND")
            append(" · $cachedCount CACHED")
            append(" · BEST $bestQuality")
            if (bestSize != null) append(" $bestSize")
        }
        tvRdSummaryText.text = summary

        // Update quality badge
        tvQualityBadge.text = bestQuality.uppercase()
        tvQualityBadge.visibility = View.VISIBLE
    }

    private fun formatSizeLabel(sizeBytes: Long): String {
        val gb = 1024.0 * 1024.0 * 1024.0
        val mb = 1024.0 * 1024.0
        return if (sizeBytes >= gb) {
            String.format(java.util.Locale.US, "%.1f GB", sizeBytes / gb)
        } else {
            String.format(java.util.Locale.US, "%.0f MB", sizeBytes / mb)
        }
    }

    private fun setupClickListeners() {
        btnPlay.setOnClickListener {
            if (movieCategoryId == "debrid") {
                val item = resumeCwItem
                if (hasResumePosition && item != null && canResumeDebridDirectly(item)) {
                    // One-tap resume: relaunch the exact source (stored URL / info-hash /
                    // magnet) and seek to the saved position — matching the home-screen
                    // Continue Watching behaviour instead of reopening the source picker.
                    resumeDebridMovieDirectly(item)
                } else {
                    showDebridSourcePicker()
                }
            } else {
                playMovie()
            }
        }
        btnTrailer.setOnClickListener {
            movieTrailerController.launch()
        }
        btnFavorite.setOnClickListener {
            toggleFavorite()
        }
        btnBack.setOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }
    }

    private fun setupFocusAnimations() {
        val focusViews: List<View> = listOf(btnPlay, btnTrailer, btnFavorite, btnBack)
        focusViews.forEach { view ->
            view.setOnFocusChangeListener { v, hasFocus ->
                if (hasFocus) {
                    v.animate()
                        .scaleX(1.05f)
                        .scaleY(1.05f)
                        .alpha(1.0f)
                        .setDuration(200)
                        .setInterpolator(android.view.animation.DecelerateInterpolator())
                        .start()
                } else {
                    v.animate()
                        .scaleX(1.0f)
                        .scaleY(1.0f)
                        .alpha(0.9f)
                        .setDuration(150)
                        .start()
                }
                if (v.id == R.id.btn_trailer) {
                    if (hasFocus) movieTrailerController.schedulePreview() else movieTrailerController.cancelPreview()
                }
            }
        }
    }

    private fun loadFavoriteState() {
        val streamId = movieId ?: return
        lifecycleScope.launch {
            isFavorite = withContext(Dispatchers.IO) { favoriteDao.isFavorite(streamId) }
            updateFavoriteIcon()
        }
    }

    private fun toggleFavorite() {
        val streamId = movieId ?: return
        lifecycleScope.launch {
            val nowFavorite = !isFavorite
            withContext(Dispatchers.IO) {
                if (nowFavorite) {
                    favoriteDao.insertFavorite(
                        com.tvonnet.debridxtreamiptv.data.local.entity.FavoriteEntity(
                            streamId = streamId,
                            type = "vod",
                            name = movieName ?: "",
                            iconUrl = movieIcon
                        )
                    )
                } else {
                    favoriteDao.deleteFavoriteByStreamId(streamId)
                }
            }
            isFavorite = nowFavorite
            updateFavoriteIcon()
            Toast.makeText(
                this@MovieDetailActivity,
                if (nowFavorite) "Added to favorites" else "Removed from favorites",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun updateFavoriteIcon() {
        if (isFavorite) {
            btnFavorite.setImageResource(R.drawable.ic_favorite)
            btnFavorite.imageTintList = android.content.res.ColorStateList.valueOf(0xFFFF3366.toInt())
        } else {
            btnFavorite.setImageResource(R.drawable.ic_favorite_border)
            btnFavorite.imageTintList = androidx.core.content.ContextCompat.getColorStateList(
                this, R.color.cin_detail_focus_text
            )
        }
    }



    private fun applySelectedSource(
        source: MovieSource,
        shouldPlay: Boolean,
        updateAdapterSelection: Boolean = true
    ) {
        selectedSource = source
        sourcesAdapter.updateSelection(source.stream.stream_id, notify = updateAdapterSelection)
        if (shouldPlay) {
            playMovie(source)
        }
    }

    private fun currentStream() = selectedSource?.stream

    private fun currentStreamId(): String? = currentStream()?.stream_id ?: movieId

    private fun currentContainerExtension(): String? =
        currentStream()?.container_extension ?: movieContainer

    private fun currentStreamTitle(): String? = currentStream()?.name ?: movieName

    private fun playMovie(sourceOverride: MovieSource? = null) {
        val stream = sourceOverride?.stream ?: currentStream()
        val streamId = stream?.stream_id ?: movieId ?: return
        val containerExt = stream?.container_extension ?: movieContainer ?: "mp4"

        val credentialsPrefs = CredentialsPreferences(this)
        val serverUrl = credentialsPrefs.getServerUrl() ?: return
        val username = credentialsPrefs.getUsername() ?: return
        val password = credentialsPrefs.getPassword() ?: return

        // Check if it's a Debrid source (magnet link or explicit label)
        // BUT exclude direct HTTP streams (which should be played directly)
        val isDirectHttp = stream?.direct_source?.startsWith("http") == true && 
                           stream?.direct_source?.endsWith(".torrent") == false
                           
        val isDebrid = !isDirectHttp && (
                       stream?.direct_source?.startsWith("magnet:") == true || 
                       sourceOverride?.label?.contains("Torrentio") == true ||
                       sourceOverride?.label?.contains("MediaFusion") == true ||
                       sourceOverride?.label?.contains("PureFire") == true)

        if (isDebrid) {
            playDebridMovie(stream, sourceOverride)
            return
        }

        val streamUrl = if (isDirectHttp) {
             stream!!.direct_source!!
        } else if (stream != null) {
            repository.buildVodStreamUrl(stream, serverUrl)
        } else {
            val baseUrl = serverUrl.trimEnd('/')
            "$baseUrl/movie/$username/$password/$streamId.$containerExt"
        }
        android.util.Log.d(
            "MovieDetailActivity",
            "Playing movie: ${stream?.name ?: movieName} - URL: ${SensitiveLogRedactor.describeUrl(streamUrl)}"
        )

        val tmdbId = movieId?.takeIf { it.toIntOrNull() != null }
        // An IPTV row picked from the debrid source sheet is plain Xtream playback:
        // never route it through the debrid resolver machinery.
        val isIptvSource = sourceOverride?.sourceType == "IPTV"
        val playbackSource =
            if (movieCategoryId == "debrid" && !isIptvSource) {
                com.tvonnet.debridxtreamiptv.player.stabilized.PlaybackSource.DEBRID
            } else {
                null
            }
        val directDebridPlayback = isDirectHttp && movieCategoryId == "debrid" && !isIptvSource
        val resolverBackedDebridPlayback = playbackSource != null && !directDebridPlayback

        val launchPlayer = {
            PlaybackDiagnosticsRecorder.record(
                this,
                "playback_launch",
                PlaybackDiagnosticsRecorder.contentFields(
                    kind = "movie",
                    tmdbId = tmdbId,
                    imdbId = currentImdbId
                ) + PlaybackDiagnosticsRecorder.sourceFields(this, sourceOverride) +
                    PlaybackDiagnosticsRecorder.playbackFields(
                        context = this,
                        url = streamUrl,
                        headers = sourceOverride?.headers,
                        playbackSource = playbackSource?.name,
                        directDebridPlayback = directDebridPlayback
                    ) + mapOf("launchPath" to "direct_or_iptv")
            )
            val intent = PlayerActivity.createIntent(
                context = this,
                streamUrl = streamUrl,
                title = stream?.name ?: movieName ?: getString(R.string.movie_detail_unknown_movie),
                contentId = stream?.stream_id ?: streamId,
                contentType = ContentType.MOVIE,
                posterUrl = com.tvonnet.debridxtreamiptv.util.GlobalConfig.resolveIconUrl(stream?.stream_icon) ?: movieIcon,
                backdropUrl = stream?.cover ?: movieBackdrop,
                headers = sourceOverride?.headers,
                tmdbId = tmdbId,
                imdbId = currentImdbId,
                debridInfoHash = if (resolverBackedDebridPlayback) stream?.stream_id else null,
                debridMagnet = if (resolverBackedDebridPlayback) stream?.direct_source else null,
                directDebridPlayback = directDebridPlayback,
                debridProvider = sourceOverride?.provider,
                debridSourceType = sourceOverride?.sourceType,
                debridSourceName = sourceOverride?.sourceName,
                debridLanguages = sourceOverride?.languages,
                debridQuality = sourceOverride?.quality,
                debridStreamId = sourceOverride?.stream?.stream_id,
                debridBingeGroup = sourceOverride?.bingeGroup,
                debridFileIdx = sourceOverride?.fileIdx,
                playbackSource = playbackSource,
                startPositionMs = resumePositionMs
            )
            startActivity(intent)
        }

        if (isAddonProxyPlaybackUrl(streamUrl)) {
            lifecycleScope.launch {
                val readyResult = withContext(Dispatchers.IO) {
                    debridPlaybackRepository.getAddonProxyPlaybackReadiness(
                        streamUrl,
                        sourceOverride?.headers,
                        sourceOverride?.provider,
                        sourceOverride?.sourceName,
                        sourceOverride?.sourceType,
                        diagnosticsContext = this@MovieDetailActivity
                    )
                }
                val readiness = (readyResult as? Result.Success)?.data ?: AddonProxyReadiness.UNCERTAIN
                if (readiness == AddonProxyReadiness.TERMINAL) {
                    stream?.stream_id?.let { failedDebridStreamIds.add(it) }
                    markDebridSourceStatus(stream?.stream_id, DebridCacheStatus.NOT_CACHED)
                    Toast.makeText(
                        this@MovieDetailActivity,
                        "Source is unavailable. Choose another source.",
                        Toast.LENGTH_LONG
                    ).show()
                    if (sourceOverride != null) {
                        showDebridSourcePicker(consumeDebridReturnFocusStreamIds())
                    }
                    return@launch
                }
                if (readiness == AddonProxyReadiness.READY) {
                    markDebridSourceStatus(stream?.stream_id, DebridCacheStatus.DIRECT_STREAM)
                }
                launchPlayer()
            }
            return
        }

        launchPlayer()
    }

    private fun autoPlayNextDebridSource(failedStreamId: String) {
        if (debridSources.isNotEmpty()) {
            val nextSource = pickNextDebridSource(debridSources, failedStreamId)
            if (nextSource != null) {
                selectedDebridStreamId = nextSource.stream.stream_id
                Toast.makeText(this, "Trying next source...", Toast.LENGTH_SHORT).show()
                playDebridMovie(nextSource.stream, nextSource, returnToSources = true)
                return
            }
            showDebridSourcePicker()
            return
        }

        lifecycleScope.launch {
            val sources = withContext(Dispatchers.IO) {
                unifiedSourceProvider.getMovieSources(
                    streamId = movieId,
                    title = movieName,
                    primaryCategoryId = movieCategoryId,
                    yearHint = movieYear,
                    imdbId = currentImdbId
                )
            }
            debridSources = sources
            val nextSource = pickNextDebridSource(sources, failedStreamId)
            if (nextSource != null) {
                selectedDebridStreamId = nextSource.stream.stream_id
                Toast.makeText(this@MovieDetailActivity, "Trying next source...", Toast.LENGTH_SHORT).show()
                playDebridMovie(nextSource.stream, nextSource, returnToSources = true)
            } else {
                showDebridSourcePicker()
            }
        }
    }

    private fun pickNextDebridSource(
        sources: List<MovieSource>,
        failedStreamId: String
    ): MovieSource? {
        val filteredSources = SourceFilterUtils.apply(sources, debridFilterState)
            .filterNot { source -> source.stream.stream_id?.let { it in failedDebridStreamIds } == true }
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

    private fun refreshMediaFusionCacheStatus(
        sources: List<MovieSource>,
        onUpdated: (List<MovieSource>) -> Unit
    ) {
        val mediaFusionSources = sources.filter { source ->
            val url = source.stream.direct_source
            !url.isNullOrBlank() && isAddonProxyPlaybackUrl(url)
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
                                    diagnosticsContext = this@MovieDetailActivity
                                )
                                val readiness =
                                    (result as? Result.Success)?.data ?: AddonProxyReadiness.UNCERTAIN
                                source.stream.stream_id to readiness
                            }
                        }
                    }.awaitAll().filterNotNull().toMap()
                }
            }
            if (updates.isEmpty()) return@launch

            val updatedSources = sources.map { source ->
                when (updates[source.stream.stream_id]) {
                    AddonProxyReadiness.READY -> if (source.cacheStatus != DebridCacheStatus.DIRECT_STREAM) {
                        source.copy(
                            isCached = true,
                            cacheStatus = DebridCacheStatus.DIRECT_STREAM
                        )
                    } else {
                        source
                    }
                    AddonProxyReadiness.TERMINAL -> {
                        source.stream.stream_id?.let { failedDebridStreamIds.add(it) }
                        if (source.cacheStatus != DebridCacheStatus.NOT_CACHED) {
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
                onUpdated(updatedSources)
            }
        }
    }

    private fun markDebridSourceCached(streamId: String?, isCached: Boolean) {
        markDebridSourceStatus(
            streamId,
            if (isCached) DebridCacheStatus.DIRECT_STREAM else DebridCacheStatus.NOT_CACHED
        )
    }

    private fun markDebridSourceStatus(streamId: String?, cacheStatus: DebridCacheStatus) {
        if (streamId.isNullOrBlank() || debridSources.isEmpty()) return
        debridSources = debridSources.map { source ->
            if (source.stream.stream_id == streamId) {
                source.copy(
                    isCached = cacheStatus != DebridCacheStatus.NOT_CACHED,
                    cacheStatus = cacheStatus
                )
            } else {
                source
            }
        }
    }

    private fun isAddonProxyPlaybackUrl(url: String): Boolean {
        return url.contains("mediafusion.elfhosted.com/playback", ignoreCase = true) ||
               url.contains("aiostreams.elfhosted.com", ignoreCase = true)
    }

    private fun playDebridMovie(
        stream: com.tvonnet.debridxtreamiptv.data.model.XtreamVodInfo?,
        source: MovieSource?,
        returnToSources: Boolean = false
    ) {
        val magnet = stream?.direct_source
        val infoHash = stream?.stream_id // UnifiedSourceProvider sets stream_id to infoHash for Debrid sources
        
        if (magnet.isNullOrBlank() && infoHash.isNullOrBlank()) {
             Toast.makeText(this, "Invalid Debrid source", Toast.LENGTH_SHORT).show()
             return
        }

        val isDirectHttp = magnet?.startsWith("http", ignoreCase = true) == true &&
            magnet.endsWith(".torrent", ignoreCase = true).not()
        if (isDirectHttp && !magnet.isNullOrBlank()) {
            val directUrl = magnet
            val tmdbId = movieId?.takeIf { it.toIntOrNull() != null }
            val launchDirectMovie = {
                PlaybackDiagnosticsRecorder.record(
                    this@MovieDetailActivity,
                    "playback_launch",
                    PlaybackDiagnosticsRecorder.contentFields(
                        kind = "movie",
                        tmdbId = tmdbId,
                        imdbId = currentImdbId
                    ) + PlaybackDiagnosticsRecorder.sourceFields(this@MovieDetailActivity, source) +
                        PlaybackDiagnosticsRecorder.playbackFields(
                            context = this@MovieDetailActivity,
                            url = directUrl,
                            headers = source?.headers,
                            playbackSource = com.tvonnet.debridxtreamiptv.player.stabilized.PlaybackSource.DEBRID.name,
                            directDebridPlayback = true
                        ) + mapOf("launchPath" to "direct")
                )
                val intent = PlayerActivity.createIntent(
                    context = this@MovieDetailActivity,
                    streamUrl = directUrl,
                    title = stream?.name ?: movieName ?: getString(R.string.movie_detail_unknown_movie),
                    contentId = infoHash ?: movieId ?: "",
                    contentType = ContentType.MOVIE,
                    posterUrl = com.tvonnet.debridxtreamiptv.util.GlobalConfig.resolveIconUrl(stream?.stream_icon) ?: movieIcon,
                    backdropUrl = stream?.cover ?: movieBackdrop,
                    headers = source?.headers,
                    playbackSource = com.tvonnet.debridxtreamiptv.player.stabilized.PlaybackSource.DEBRID,
                    tmdbId = tmdbId,
                    imdbId = currentImdbId,
                    directDebridPlayback = true,
                    debridProvider = source?.provider,
                    debridSourceType = source?.sourceType,
                    debridSourceName = source?.sourceName,
                    debridLanguages = source?.languages,
                    debridQuality = source?.quality,
                    debridStreamId = source?.stream?.stream_id,
                    debridBingeGroup = source?.bingeGroup,
                    debridFileIdx = source?.fileIdx,
                    // Resume from the saved position when the user pressed "Resume".
                    startPositionMs = resumePositionMs
                )
                if (returnToSources) {
                    intent.putExtra(PlayerActivity.EXTRA_RETURN_TO_SOURCES, true)
                    playerLauncher.launch(intent)
                } else {
                    startActivity(intent)
                }
            }

            if (debridPlaybackRepository.requiresDirectProxyReadinessCheck(
                    directUrl,
                    source?.provider,
                    source?.sourceName,
                    source?.sourceType
                )
            ) {
                lifecycleScope.launch {
                    tvSourcesStatus.visibility = View.VISIBLE
                    tvSourcesStatus.text = "Checking source..."
                    val readyResult = withContext(Dispatchers.IO) {
                        debridPlaybackRepository.getAddonProxyPlaybackReadiness(
                            directUrl,
                            source?.headers,
                            source?.provider,
                            source?.sourceName,
                            source?.sourceType,
                            diagnosticsContext = this@MovieDetailActivity
                        )
                    }
                    tvSourcesStatus.visibility = View.GONE
                    val readiness = (readyResult as? Result.Success)?.data ?: AddonProxyReadiness.UNCERTAIN
                    if (readiness == AddonProxyReadiness.TERMINAL) {
                        infoHash?.let { failedDebridStreamIds.add(it) }
                        markDebridSourceStatus(infoHash, DebridCacheStatus.NOT_CACHED)
                        Toast.makeText(this@MovieDetailActivity, "Source is unavailable. Choose another source.", Toast.LENGTH_LONG).show()
                        showDebridSourcePicker(consumeDebridReturnFocusStreamIds())
                        return@launch
                    }
                    if (readiness == AddonProxyReadiness.READY) {
                        markDebridSourceStatus(infoHash, DebridCacheStatus.DIRECT_STREAM)
                    }
                    launchDirectMovie()
                }
                return
            }

            launchDirectMovie()
            return
        }

        lifecycleScope.launch {
            // Show loading indicator
            tvSourcesStatus.visibility = View.VISIBLE
            tvSourcesStatus.text = "Resolving Debrid link..."
            
            val tmdbId = movieId?.takeIf { it.toIntOrNull() != null }
            
            val result = withContext(Dispatchers.IO) {
                playbackResolver.resolve(
                    source = "debrid",
                    streamUrl = magnet,
                    isExpired = true,
                    infoHash = infoHash,
                    magnet = magnet,
                    seasonNumber = null,
                    episodeNumber = null,
                    episodeTitle = movieName
                )
            }
            
            tvSourcesStatus.visibility = View.GONE
            
            when (result) {
                is com.tvonnet.debridxtreamiptv.data.debrid.repository.ResolutionResult.Success -> {
                    PlaybackDiagnosticsRecorder.record(
                        this@MovieDetailActivity,
                        "playback_launch",
                        PlaybackDiagnosticsRecorder.contentFields(
                            kind = "movie",
                            tmdbId = tmdbId,
                            imdbId = currentImdbId
                        ) + PlaybackDiagnosticsRecorder.sourceFields(this@MovieDetailActivity, source) +
                            PlaybackDiagnosticsRecorder.playbackFields(
                                context = this@MovieDetailActivity,
                                url = result.url,
                                headers = source?.headers,
                                playbackSource = com.tvonnet.debridxtreamiptv.player.stabilized.PlaybackSource.DEBRID.name,
                                directDebridPlayback = false
                            ) + mapOf("launchPath" to "resolver_backed")
                    )
                    val intent = PlayerActivity.createIntent(
                        context = this@MovieDetailActivity,
                        streamUrl = result.url,
                        title = stream?.name ?: movieName ?: getString(R.string.movie_detail_unknown_movie),
                        contentId = infoHash ?: movieId ?: "",
                        contentType = ContentType.MOVIE,
                        posterUrl = com.tvonnet.debridxtreamiptv.util.GlobalConfig.resolveIconUrl(stream?.stream_icon) ?: movieIcon,
                        backdropUrl = stream?.cover ?: movieBackdrop,
                        headers = source?.headers,
                        playbackSource = com.tvonnet.debridxtreamiptv.player.stabilized.PlaybackSource.DEBRID,
                        tmdbId = tmdbId,
                        imdbId = currentImdbId,
                        debridInfoHash = infoHash,
                        debridMagnet = magnet,
                        debridProvider = source?.provider,
                        debridSourceType = source?.sourceType,
                        debridSourceName = source?.sourceName,
                        debridLanguages = source?.languages,
                        debridQuality = source?.quality,
                        debridStreamId = source?.stream?.stream_id,
                        debridBingeGroup = source?.bingeGroup,
                        debridFileIdx = source?.fileIdx,
                        // Resume from the saved position when the user pressed "Resume".
                        startPositionMs = resumePositionMs
                    )
                    if (returnToSources) {
                        intent.putExtra(PlayerActivity.EXTRA_RETURN_TO_SOURCES, true)
                        playerLauncher.launch(intent)
                    } else {
                        startActivity(intent)
                    }
                }
                is com.tvonnet.debridxtreamiptv.data.debrid.repository.ResolutionResult.RefreshRequired -> {
                    Toast.makeText(this@MovieDetailActivity, "Source expired, please refresh", Toast.LENGTH_SHORT).show()
                    showDebridSourcePicker(consumeDebridReturnFocusStreamIds())
                }
                is com.tvonnet.debridxtreamiptv.data.debrid.repository.ResolutionResult.Error -> {
                    val failedStreamId = stream?.stream_id
                    markDebridSourceCached(failedStreamId, false)
                    updateRdSummary(debridSources)

                    if (shouldTryNextDebridSource(result.failureType) && !failedStreamId.isNullOrBlank()) {
                        notifyDebridFailure(result.message, autoPlayNext = true)
                        autoPlayNextDebridSource(failedStreamId)
                    } else {
                        Toast.makeText(this@MovieDetailActivity, result.message, Toast.LENGTH_LONG).show()
                        showDebridSourcePicker(consumeDebridReturnFocusStreamIds())
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

    /**
     * Whether a saved Debrid movie can be resumed straight into the player (mirrors the
     * home-screen Continue-Watching decision). True when we have a still-valid stored URL,
     * a resolvable info-hash/magnet, or a direct-Debrid item we can fresh-resolve by id.
     */
    private fun canResumeDebridDirectly(
        item: com.tvonnet.debridxtreamiptv.data.model.ContinueWatchingItem
    ): Boolean {
        // A stored URL is enough — the player replays it and refreshes from metadata if
        // it turns out stale, so we don't gate on the coarse Debrid "expired" heuristic.
        val hasStreamUrl = !item.streamUrl.isNullOrBlank()
        val hasResolutionInfo = !item.debridInfoHash.isNullOrBlank() || !item.debridMagnet.isNullOrBlank()
        val canFreshResolveDirect = item.directDebridPlayback &&
            !item.contentId.isNullOrBlank() &&
            !item.title.isNullOrBlank()
        return hasStreamUrl || hasResolutionInfo || canFreshResolveDirect
    }

    /**
     * Relaunch the exact source saved for this Debrid movie and seek to the saved
     * position. Passes every stored Debrid field to the player (URL / info-hash / magnet /
     * direct-playback), exactly like the home-screen resume path, so the player can replay
     * or fresh-resolve as needed — no source picker.
     */
    private fun resumeDebridMovieDirectly(
        item: com.tvonnet.debridxtreamiptv.data.model.ContinueWatchingItem
    ) {
        val hasResolutionInfo = !item.debridInfoHash.isNullOrBlank() || !item.debridMagnet.isNullOrBlank()
        // Prefer replaying the stored (re-resolvable) direct URL — most Debrid movie
        // sources are proxy links the player can reopen and, if stale, refresh from
        // metadata. Only hand the player an empty URL when we have an info-hash/magnet
        // it can freshly resolve instead.
        val resumeUrl = when {
            !item.streamUrl.isNullOrBlank() -> item.streamUrl!!
            hasResolutionInfo -> ""
            else -> ""
        }
        val intent = PlayerActivity.createIntent(
            context = this,
            streamUrl = resumeUrl,
            title = item.title,
            startPositionMs = item.currentPosition,
            contentId = item.tmdbId?.takeIf { it.isNotBlank() } ?: item.contentId,
            contentType = ContentType.MOVIE,
            playbackSource = com.tvonnet.debridxtreamiptv.player.stabilized.PlaybackSource.DEBRID,
            posterUrl = item.posterUrl,
            backdropUrl = item.backdropUrl,
            tmdbId = item.tmdbId,
            imdbId = item.imdbId,
            debridInfoHash = item.debridInfoHash,
            debridMagnet = item.debridMagnet,
            directDebridPlayback = item.directDebridPlayback,
            debridProvider = item.debridProvider,
            debridSourceType = item.debridSourceType,
            debridSourceName = item.debridSourceName,
            debridLanguages = item.debridLanguages,
            debridQuality = item.debridQuality,
            debridStreamId = item.debridStreamId,
            debridBingeGroup = item.debridBingeGroup,
            debridFileIdx = item.debridFileIdx,
            expiresAt = item.expiresAt
        )
        startActivity(intent)
    }

    private fun handleDebridHistoryItem(infoHash: String) {
        tvSourcesStatus.visibility = View.VISIBLE
        tvSourcesStatus.text = "Resuming from Debrid History..." 
        
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                playbackResolver.resolve(
                    source = "debrid",
                    streamUrl = null,
                    isExpired = true,
                    infoHash = infoHash,
                    magnet = null,
                    allowDirectHttpPassthrough = false
                )
            }
            
            tvSourcesStatus.visibility = View.GONE
            
            when (result) {
                is com.tvonnet.debridxtreamiptv.data.debrid.repository.ResolutionResult.Success -> {
                    val tmdbId = movieId?.takeIf { it.toIntOrNull() != null }
                    val intent = PlayerActivity.createIntent(
                        context = this@MovieDetailActivity,
                        streamUrl = result.url,
                        title = movieName ?: getString(R.string.movie_detail_unknown_movie),
                        contentId = infoHash,
                        contentType = ContentType.MOVIE,
                        posterUrl = movieIcon,
                        backdropUrl = movieBackdrop,
                        playbackSource = com.tvonnet.debridxtreamiptv.player.stabilized.PlaybackSource.DEBRID,
                        tmdbId = tmdbId,
                        imdbId = currentImdbId,
                        debridInfoHash = infoHash,
                        // This is the "Resume from Debrid History" path — carry the saved position.
                        startPositionMs = resumePositionMs
                    )
                    startActivity(intent)
                }
                is com.tvonnet.debridxtreamiptv.data.debrid.repository.ResolutionResult.RefreshRequired -> {
                    Toast.makeText(this@MovieDetailActivity, "History item expired", Toast.LENGTH_SHORT).show()
                }
                is com.tvonnet.debridxtreamiptv.data.debrid.repository.ResolutionResult.Error -> {
                    Toast.makeText(this@MovieDetailActivity, result.message, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Refresh in case the user just watched some of this movie and returned.
        refreshResumeState()
    }

    override fun onPause() {
        super.onPause()
        movieTrailerController.cancelPreview()
    }

    override fun onDestroy() {
        sourcesLoadJob?.cancel()
        super.onDestroy()
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
        super.onBackPressed()
        finish()
    }
}
