package com.tvonnet.debridxtreamiptv.ui.vod

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
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.tvonnet.debridxtreamiptv.R
import com.tvonnet.debridxtreamiptv.data.model.XtreamVodInfo
import com.tvonnet.debridxtreamiptv.data.prefs.CredentialsPreferences
import com.tvonnet.debridxtreamiptv.data.prefs.WatchHistoryPreferences
import com.tvonnet.debridxtreamiptv.data.repository.XtreamRepository
import com.tvonnet.debridxtreamiptv.utils.memory.MemoryManager
import com.tvonnet.debridxtreamiptv.player.stabilized.PlayerActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import com.tvonnet.debridxtreamiptv.data.debrid.repository.UnifiedSourceProvider
import com.tvonnet.debridxtreamiptv.data.debrid.source.TmdbRemoteDataSource
import com.tvonnet.debridxtreamiptv.util.SensitiveLogRedactor
import android.content.Context
import com.tvonnet.debridxtreamiptv.util.lockLandscapeOnTouchDevices
import com.tvonnet.debridxtreamiptv.util.phoneScaledContext

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
    private lateinit var layoutSourceFilters: View
    private lateinit var btnCachedOnly: TextView

    private lateinit var rvLanguageFilters: RecyclerView

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
    /** MD-3: Debrid + IPTV source acquisition + picker + filters + cache-status (source of truth). */
    private lateinit var movieDebridSources: MovieDebridSourceController
    /** MD-4: Debrid playback launch + resume + source-failover (reads source state via [movieDebridSources]). */
    private lateinit var moviePlayback: MovieDebridPlaybackController
    /** MD-5: TMDB details + OMDb ratings + similar-movies enrichment (writes metadata fields back). */
    private lateinit var movieMetadata: MovieMetadataController
    private var movieCategoryId: String? = null
    private var isDebridMovie: Boolean = false
    private var currentImdbId: String? = null

    // MD-4: registration stays in the Activity (timing before STARTED is load-bearing);
    // the body is delegated to MovieDebridPlaybackController.handlePlayerResult.
    private val playerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result -> moviePlayback.handlePlayerResult(result) }

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
    }


    // M13 (option B): the TV layout's type is sized for three metres; scale it up on a handset.
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(phoneScaledContext(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // M13: BEFORE super.onCreate on purpose — the orientation has to be settled before the
        // first inflate, or the layout chosen for the old one stays on screen inside the new
        // window (M11 proved that the hard way, in the opposite direction).
        lockLandscapeOnTouchDevices()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.fragment_movie_detail)

        // Repository initialized by Hilt
        initializeRepository()

        initViews()
        getMovieDataFromIntent()
        moviePlayback.openedFromPlaybackFailure = intent.getBooleanExtra(PlayerActivity.EXTRA_OPENED_FROM_PLAYBACK_FAILURE, false)
        if (moviePlayback.openedFromPlaybackFailure) {
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
                movieMetadata.fetchTmdbDetails(movieId!!)
            } else {
                // ID is likely an InfoHash from History
                android.util.Log.d("MovieDetailActivity", "Detected Debrid Hash ID: ${SensitiveLogRedactor.describeHash(movieId)}")
                moviePlayback.handleDebridHistoryItem(movieId!!)
            }
        } else {
            movieDebridSources.loadMovieSources(null)
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
        bindViewRefs()
        setupSimilarRow()
        setupControllers()
    }

    private fun bindViewRefs() {
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
    }

    private fun setupSimilarRow() {
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
    }

    private fun setupControllers() {
        setupTrailerAndSourceControllers()
        setupPlaybackController()
        setupMetadataController()
    }

    private fun setupTrailerAndSourceControllers() {
        movieTrailerController = MovieTrailerController(
            activity = this,
            tmdbRemoteDataSource = tmdbRemoteDataSource,
            button = btnTrailer,
            backdrop = ivBackdrop,
            movieId = { movieId },
            movieBackdrop = { movieBackdrop }
        )

        movieDebridSources = MovieDebridSourceController(
            activity = this,
            deps = MovieDebridSourceController.Deps(
                unifiedSourceProvider = unifiedSourceProvider,
                debridPlaybackRepository = debridPlaybackRepository,
                repository = repository,
                credentialsPrefs = credentialsPrefs,
            ),
            views = MovieDebridSourceController.Views(
                tvSourcesHeader = tvSourcesHeader,
                tvSourcesStatus = tvSourcesStatus,
                rvSources = rvSources,
                layoutSourceFilters = layoutSourceFilters,
                btnCachedOnly = btnCachedOnly,
                rvLanguageFilters = rvLanguageFilters,
                btnPlay = btnPlay,
                layoutRdSummary = layoutRdSummary,
                tvRdSummaryText = tvRdSummaryText,
                tvQualityBadge = tvQualityBadge,
            ),
            movie = MovieDebridSourceController.MovieAccessors(
                movieId = { movieId },
                movieName = { movieName },
                movieCategoryId = { movieCategoryId },
                movieYear = { movieYear },
                movieBackdrop = { movieBackdrop },
                movieContainer = { movieContainer },
                currentImdbId = { currentImdbId },
            ),
            callbacks = MovieDebridSourceController.Callbacks(
                // Both source-selection paths (the sheet and the inline list) come through here, so the
                // resume question is asked once, in one place, whichever way a source was chosen.
                onPlayMovie = { source -> withResumeChoice { moviePlayback.playMovie(source) } },
                onPlayDebridMovie = { stream, source, returnToSources ->
                    withResumeChoice { moviePlayback.playDebridMovie(stream, source, returnToSources) }
                }
            )
        )
        movieDebridSources.setupSourceViews()
    }

    private fun setupPlaybackController() {
        moviePlayback = MovieDebridPlaybackController(
            activity = this,
            sources = movieDebridSources,
            repository = repository,
            debridPlaybackRepository = debridPlaybackRepository,
            playbackResolver = playbackResolver,
            host = MovieDebridPlaybackController.Host(
                tvSourcesStatus = tvSourcesStatus,
                movieId = { movieId },
                movieName = { movieName },
                movieIcon = { movieIcon },
                movieBackdrop = { movieBackdrop },
                movieContainer = { movieContainer },
                movieCategoryId = { movieCategoryId },
                currentImdbId = { currentImdbId },
                // Zero for a launch the user asked to start over; the stored position is left alone so
                // backing straight out of the player does not lose where they were.
                resumePositionMs = { if (startFromBeginningOnce) 0L else resumePositionMs },
                launch = { playerLauncher.launch(it) }
            )
        )

        setupClickListeners()
        setupFocusAnimations()

        castAdapter = CastAdapter()
        rvCast.apply {
            layoutManager = LinearLayoutManager(this@MovieDetailActivity, LinearLayoutManager.HORIZONTAL, false)
            adapter = castAdapter
        }

    }

    private fun setupMetadataController() {
        movieMetadata = MovieMetadataController(
            activity = this,
            tmdbRemoteDataSource = tmdbRemoteDataSource,
            trailerController = movieTrailerController,
            views = MovieMetadataController.Views(
                castAdapter = castAdapter,
                tvCastTitle = tvCastTitle,
                rvCast = rvCast,
                tvImdbRating = tvImdbRating,
                badgeImdb = badgeImdb,
                tvRtRating = tvRtRating,
                badgeRt = badgeRt,
                tvAgeRating = tvAgeRating,
                layoutMetadataRow = layoutMetadataRow,
                similarAdapter = similarAdapter,
                layoutSimilarRow = layoutSimilarRow,
            ),
            fields = MovieMetadataController.MovieFields(
                movieName = { movieName },
                currentImdbId = { currentImdbId },
                setCurrentImdbId = { currentImdbId = it },
                movieAgeRating = { movieAgeRating },
                setMovieAgeRating = { movieAgeRating = it },
                setMoviePlot = { moviePlot = it },
                setMovieRating = { movieRating = it },
                setMovieYear = { movieYear = it },
                setMovieDuration = { movieDuration = it },
                setMovieGenre = { movieGenre = it },
                setMovieDirector = { movieDirector = it },
            ),
            callbacks = MovieMetadataController.Callbacks(
                onMetadataApplied = { displayMovieDetails(); refreshResumeState() },
                onFetchComplete = { movieDebridSources.loadMovieSources(it) }
            )
        )
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
        displayBackHint()
        displayContentTypeLabel()
        displayMetadataRow()
        displayDirectorAndPlot()

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

    // Back-to-rail context hint (shown when the caller told us where we came from)
    private fun displayBackHint() {
        val rail = movieSourceRail?.trim()
        if (!rail.isNullOrBlank()) {
            tvBackHint.text = "◄ BACK TO ${rail.uppercase(java.util.Locale.getDefault())}"
            tvBackHint.visibility = View.VISIBLE
        } else {
            tvBackHint.visibility = View.GONE
        }
    }

    // Content type label
    private fun displayContentTypeLabel() {
        if (isDebridMovie) {
            tvContentType.text = tvContentType.context.getString(R.string.ui_feature_film)
            tvContentType.visibility = View.VISIBLE
        } else {
            tvContentType.visibility = View.GONE
        }
    }

    // Rating / year / genre / age badges, each hidden when its field is absent; the row
    // itself hides only when every field is.
    private fun displayMetadataRow() {
        val rating = movieRating?.toDoubleOrNull() ?: 0.0
        val hasRating = rating > 0.0
        tvRatingPercentage.visibility = if (hasRating) View.VISIBLE else View.GONE
        tvRatingStars.visibility = View.GONE
        if (hasRating) {
            tvRatingPercentage.text = String.format(java.util.Locale.US, "%.1f", rating)
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

        // Duration in compact format
        val durationText = movieDuration?.takeIf { it.isNotBlank() }?.let { formatDurationCompact(it) }
        tvDuration.text = durationText.orEmpty()
        tvDuration.visibility = if (durationText.isNullOrBlank()) View.GONE else View.VISIBLE

        val hasMetadataRow = hasRating || !movieGenre.isNullOrBlank() || !movieDuration.isNullOrBlank() ||
            !movieYear.isNullOrBlank() || !ageRating.isNullOrBlank()
        layoutMetadataRow.visibility = if (hasMetadataRow) View.VISIBLE else View.GONE
    }

    private fun displayDirectorAndPlot() {
        if (!movieDirector.isNullOrBlank()) {
            tvDirector.text = movieDirector
            tvDirector.visibility = View.VISIBLE
        } else {
            tvDirector.visibility = View.GONE
        }

        val descriptionText = moviePlot?.takeIf { it.isNotBlank() }
        tvDescription.text = descriptionText.orEmpty()
        tvDescription.visibility = if (descriptionText.isNullOrBlank()) View.GONE else View.VISIBLE
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
        if (hasResumePosition) {
            tvResumeElapsed.text = "RESUME FROM ${formatResumeTime(position)}"
            tvResumeRemaining.text = "${formatResumeTime(duration - position)} LEFT"
            pbResume.progress = progress
            layoutResumeBar.visibility = View.VISIBLE
        } else {
            layoutResumeBar.visibility = View.GONE
        }

        // The button no longer changes label with saved state. It used to flip to "Resume", and that
        // flip is what hid the source picker: the resume branch took over and there was no other way
        // in. Now it always means "choose a source", and where to start is asked after one is picked
        // (ResumeChoiceDialog). The resume bar above still shows the saved position.
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

    /**
     * Set for one launch when the user chose "start from beginning", so the playback controller —
     * which reads the saved position through a getter — starts at zero without the saved value being
     * touched. Backing out of the player must not erase where they were.
     */
    private var startFromBeginningOnce: Boolean = false

    /**
     * Ask where to start, then play. Nothing to ask when there is no saved position, so it plays
     * straight through — the question only appears when it has an answer worth having.
     *
     * Backing out of the dialog plays nothing, which is the honest reading of "neither".
     */
    private fun withResumeChoice(play: () -> Unit) {
        if (!hasResumePosition || resumePositionMs <= 0L) {
            startFromBeginningOnce = false
            play()
            return
        }
        com.tvonnet.debridxtreamiptv.ui.dialog.ResumeChoiceDialog.show(
            fragmentManager = supportFragmentManager,
            title = movieName,
            positionMs = resumePositionMs
        ) { resume ->
            startFromBeginningOnce = !resume
            play()
        }
    }

    private fun setupClickListeners() {
        btnPlay.setOnClickListener {
            if (movieCategoryId == "debrid") {
                // Always the picker, resume or not. One button, one meaning — see updateResumeBar.
                movieDebridSources.showDebridSourcePicker()
            } else {
                // IPTV has no source list, so the position question is the only one to ask.
                withResumeChoice { moviePlayback.playMovie() }
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
        movieDebridSources.sourcesLoadJob?.cancel()
        super.onDestroy()
    }

    private fun showDebridConfigDialog() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.c_real_debrid_not_configured)
            .setMessage(R.string.c_you_need_to_configure_real)
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
