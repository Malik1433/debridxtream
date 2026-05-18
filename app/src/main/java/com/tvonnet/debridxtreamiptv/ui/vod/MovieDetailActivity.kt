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
import com.tvonnet.debridxtreamiptv.data.model.ContentType
import com.tvonnet.debridxtreamiptv.data.onSuccess
import com.tvonnet.debridxtreamiptv.data.onFailure
import com.tvonnet.debridxtreamiptv.data.prefs.CredentialsPreferences
import com.tvonnet.debridxtreamiptv.data.debrid.model.DebridFailureType
import com.tvonnet.debridxtreamiptv.data.repository.DebridCacheStatus
import com.tvonnet.debridxtreamiptv.data.repository.MovieSource
import com.tvonnet.debridxtreamiptv.data.repository.XtreamRepository
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

    private lateinit var ivBackdrop: ImageView

    private lateinit var tvTitle: TextView
    private lateinit var tvRatingStars: TextView
    private lateinit var tvRatingPercentage: TextView
    private lateinit var tvGenreYear: TextView

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

    // TASK 020 New Views
    private lateinit var layoutRdSummary: View
    private lateinit var tvRdSummaryText: TextView
    private lateinit var btnPlay: Button
    private lateinit var btnTrailer: Button
    private lateinit var btnFavorite: Button
    private lateinit var btnBack: ImageButton

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
    private var movieTrailerUrl: String? = null
    private var hasTrailer: Boolean = false
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
            notifyDebridFailure(failReason, autoPlayNext)
            if (autoPlayNext && !failedStreamId.isNullOrBlank()) {
                autoPlayNextDebridSource(failedStreamId)
            } else {
                showDebridSourcePicker()
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
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.fragment_movie_detail)

        // Repository initialized by Hilt
        initializeRepository()

        initViews()
        getMovieDataFromIntent()
        displayMovieDetails()
        configureTabs()
        
        // Start data loading chain
        if (movieCategoryId == "debrid" && movieId != null) {
            layoutRdSummary.visibility = View.VISIBLE
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
                            // Capture IMDb ID for source fetching
                            imdbId = details.imdbId
                            currentImdbId = imdbId
                            
                            // Update UI with fetched details
                            moviePlot = details.overview
                            movieRating = details.voteAverage?.toString()
                            movieYear = details.releaseDate?.take(4)
                            movieDuration = details.runtime?.let { (it * 60).toString() } // Convert mins to seconds for formatDuration
                            movieGenre = details.genres?.joinToString(", ") { it.name ?: "" }

                            // Cast
                            val castList = details.credits?.cast
                            if (!castList.isNullOrEmpty()) {
                                castAdapter.submitList(castList.take(3))
                                tvCastTitle.visibility = View.VISIBLE
                                rvCast.visibility = View.VISIBLE
                            } else {
                                tvCastTitle.visibility = View.GONE
                                rvCast.visibility = View.GONE
                            }
                            // Update views
                            displayMovieDetails()
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

        // TASK 020 View Binding
        layoutRdSummary = findViewById(R.id.layout_rd_summary)
        tvRdSummaryText = findViewById(R.id.tv_rd_summary_text)
        btnPlay = findViewById(R.id.btn_play)
        btnTrailer = findViewById(R.id.btn_trailer)
        btnFavorite = findViewById(R.id.btn_favorite)
        btnBack = findViewById(R.id.btn_back)

        setupClickListeners()
        setupFocusAnimations()

        sourcesAdapter = MovieSourceAdapter(
            onSourceFocused = { source -> applySelectedSource(source, shouldPlay = false, updateAdapterSelection = false) },
            onSourceClicked = { source -> applySelectedSource(source, shouldPlay = true) }
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
        movieTrailerUrl = intent.getStringExtra(EXTRA_MOVIE_TRAILER)
        movieCategoryId = intent.getStringExtra(EXTRA_MOVIE_CATEGORY_ID)
        isDebridMovie = movieCategoryId == "debrid"
    }



    private fun displayMovieDetails() {
        tvTitle.text = movieName ?: getString(R.string.movie_detail_unknown_movie)

        val rating = movieRating?.toDoubleOrNull() ?: 0.0
        displayRating(rating)

        val genreText = when {
            !movieGenre.isNullOrBlank() && !movieYear.isNullOrBlank() ->
                getString(R.string.movie_detail_genre_and_year, movieGenre, movieYear)

            !movieGenre.isNullOrBlank() -> movieGenre
            !movieYear.isNullOrBlank() -> movieYear
            else -> getString(R.string.movie_detail_not_available)
        }
        tvGenreYear.text = genreText

        if (!movieDirector.isNullOrBlank()) {
            tvDirector.text = getString(R.string.movie_detail_directed_by, movieDirector)
            tvDirector.visibility = View.VISIBLE
        } else {
            tvDirector.visibility = View.GONE
        }



        tvDescription.text = moviePlot?.takeIf { it.isNotBlank() }
            ?: getString(R.string.movie_detail_description_unavailable)

        tvDuration.text = movieDuration?.takeIf { it.isNotBlank() }
            ?.let { formatDuration(it) }
            ?: getString(R.string.movie_detail_duration_unavailable)

        if (!movieBackdrop.isNullOrBlank()) {
            Glide.with(this)
                .load(movieBackdrop)
                .centerCrop()
                .into(ivBackdrop)
        }
    }

    private fun hasTmdbTrailer(): Boolean {
        // We'll check this once we have TMDB details
        return false // Placeholder, will be improved
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
        // No-op for now as we are using buttons instead of tabs for main actions
        // But we keep the function if you want to restore tabs later
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

    private fun showDebridSourcePicker() {
        val bottomSheet = com.tvonnet.debridxtreamiptv.ui.series.SourceSelectionBottomSheet(
            onSourceSelected = { source ->
                selectedDebridStreamId = source.stream.stream_id
                playDebridMovie(source.stream, source, returnToSources = true)
            },
            onStateChanged = { state ->
                debridFilterState = state
            },
            initialState = debridFilterState,
            initialSelectedStreamId = selectedDebridStreamId,
            contentTitle = movieName,
            backdropUrl = movieBackdrop
        )

        bottomSheet.show(supportFragmentManager, com.tvonnet.debridxtreamiptv.ui.series.SourceSelectionBottomSheet.TAG)

        if (debridSources.isNotEmpty()) {
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
                    unifiedSourceProvider.getMovieSources(
                        streamId = movieId,
                        title = movieName,
                        primaryCategoryId = movieCategoryId,
                        yearHint = movieYear,
                        imdbId = currentImdbId
                    )
                }
                debridSources = sources
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

    private fun updateRdSummary(sources: List<MovieSource>) {
        if (sources.isEmpty()) {
            layoutRdSummary.visibility = View.GONE
            return
        }

        val verifiedCount = sources.count { it.cacheStatus == DebridCacheStatus.VERIFIED_CACHED }
        val directCount = sources.count { it.cacheStatus == DebridCacheStatus.DIRECT_STREAM }
        val bestQuality = sources.mapNotNull { it.quality }.distinct()
            .sortedByDescending { q -> 
                when(q.uppercase()) {
                    "4K" -> 100
                    "2160P" -> 99
                    "1080P" -> 80
                    "720P" -> 60
                    else -> 0
                }
            }.firstOrNull() ?: "HD"

        val languages = sources.flatMap { it.languages ?: emptyList() }
            .distinct()
            .joinToString(", ")

        layoutRdSummary.visibility = View.VISIBLE
        val summary = buildString {
            append("Real-Debrid Ready")
            if (verifiedCount > 0) {
                append(" • $verifiedCount verified cached")
            } else if (directCount > 0) {
                append(" • $directCount direct sources")
            } else {
                append(" • ${sources.size} sources found")
            }
            append(" • Best: $bestQuality")
            
            if (languages.isNotBlank()) {
                append("\nAvailable Languages: $languages")
            } else {
                append("\nLanguages available after source selection")
            }
        }
        tvRdSummaryText.text = summary
    }

    private fun setupClickListeners() {
        btnPlay.setOnClickListener {
            if (movieCategoryId == "debrid") {
                showDebridSourcePicker()
            } else {
                playMovie()
            }
        }
        btnTrailer.setOnClickListener {
            launchTrailer()
        }
        btnFavorite.setOnClickListener {
            toggleFavorite()
        }
        btnBack.setOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }
    }

    private fun setupFocusAnimations() {
        val buttons = listOf(btnPlay, btnTrailer, btnFavorite, btnBack)
        buttons.forEach { view ->
            // Unfocused state: slightly transparent for depth
            view.alpha = 0.85f
            view.setOnFocusChangeListener { v, hasFocus ->
                if (hasFocus) {
                    v.animate()
                        .scaleX(1.1f)
                        .scaleY(1.1f)
                        .alpha(1.0f)
                        .setDuration(250)
                        .setInterpolator(android.view.animation.DecelerateInterpolator())
                        .start()
                } else {
                    v.animate()
                        .scaleX(1.0f)
                        .scaleY(1.0f)
                        .alpha(0.85f)
                        .setDuration(200)
                        .start()
                }
            }
        }
    }

    private fun toggleFavorite() {
        // Logic to be implemented
        Toast.makeText(this, "Added to favorites", Toast.LENGTH_SHORT).show()
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

    private fun launchTrailer() {
        val trailerUrl = movieTrailerUrl
        if (!trailerUrl.isNullOrBlank()) {
            startActivity(com.tvonnet.debridxtreamiptv.ui.trailer.TrailerActivity.createIntent(this, trailerUrl))
            return
        }

        // If no intent trailer, check stored TMDB data (this is where Phase 2 re-wiring happens)
        // We'll see if the detail fetch populated any videos
        lifecycleScope.launch {
            val tmdbId = movieId?.toIntOrNull() ?: return@launch
            val result = withContext(Dispatchers.IO) {
                tmdbRemoteDataSource.getMovieDetails(tmdbId)
            }
            
            result.onSuccess { details ->
                val trailer = details.videos?.results?.firstOrNull { 
                    it.site?.lowercase() == "youtube" && it.type?.lowercase() == "trailer" 
                } ?: details.videos?.results?.firstOrNull { 
                    it.site?.lowercase() == "youtube" 
                }
                
                if (trailer?.key != null) {
                    startActivity(com.tvonnet.debridxtreamiptv.ui.trailer.TrailerActivity.createIntent(this@MovieDetailActivity, trailer.key))
                } else {
                    Toast.makeText(this@MovieDetailActivity, R.string.movie_detail_trailer_unavailable, Toast.LENGTH_SHORT).show()
                }
            }.onFailure {
                Toast.makeText(this@MovieDetailActivity, R.string.movie_detail_trailer_unavailable, Toast.LENGTH_SHORT).show()
            }
        }
    }

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
        val playbackSource =
            if (movieCategoryId == "debrid") {
                com.tvonnet.debridxtreamiptv.player.stabilized.PlaybackSource.DEBRID
            } else {
                null
            }
        val directDebridPlayback = isDirectHttp && movieCategoryId == "debrid"
        val resolverBackedDebridPlayback = playbackSource != null && !directDebridPlayback

        val launchPlayer = {
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
                playbackSource = playbackSource
            )
            startActivity(intent)
        }

        if (isMediaFusionPlaybackUrl(streamUrl)) {
            lifecycleScope.launch {
                val readyResult = withContext(Dispatchers.IO) {
                    debridPlaybackRepository.isMediaFusionPlaybackReady(streamUrl)
                }
                val isReady = readyResult is com.tvonnet.debridxtreamiptv.data.Result.Success && readyResult.data
                if (!isReady) {
                    markDebridSourceCached(stream?.stream_id, false)
                    Toast.makeText(
                        this@MovieDetailActivity,
                        "MediaFusion: link not cached on RD yet",
                        Toast.LENGTH_LONG
                    ).show()
                    if (sourceOverride != null) {
                        showDebridSourcePicker()
                    }
                    return@launch
                }
                markDebridSourceCached(stream?.stream_id, true)
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

    private fun refreshMediaFusionCacheStatus(
        sources: List<MovieSource>,
        onUpdated: (List<MovieSource>) -> Unit
    ) {
        val mediaFusionSources = sources.filter { source ->
            val url = source.stream.direct_source
            !url.isNullOrBlank() && isMediaFusionPlaybackUrl(url)
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
                                val result = debridPlaybackRepository.isMediaFusionPlaybackReady(url)
                                val isReady =
                                    result is com.tvonnet.debridxtreamiptv.data.Result.Success && result.data
                                source.stream.stream_id to isReady
                            }
                        }
                    }.awaitAll().filterNotNull().toMap()
                }
            }
            if (updates.isEmpty()) return@launch

            val updatedSources = sources.map { source ->
                val cached = updates[source.stream.stream_id]
                    if (cached != null && source.isCached != cached) {
                        source.copy(
                            isCached = cached,
                            cacheStatus = if (cached) {
                                DebridCacheStatus.DIRECT_STREAM
                            } else {
                                DebridCacheStatus.NOT_CACHED
                            }
                        )
                    } else {
                        source
                    }
            }
            if (updatedSources != sources) {
                onUpdated(updatedSources)
            }
        }
    }

    private fun markDebridSourceCached(streamId: String?, isCached: Boolean) {
        if (streamId.isNullOrBlank() || debridSources.isEmpty()) return
        debridSources = debridSources.map { source ->
            if (source.stream.stream_id == streamId) {
                source.copy(
                    isCached = isCached,
                    cacheStatus = if (isCached) {
                        DebridCacheStatus.VERIFIED_CACHED
                    } else {
                        DebridCacheStatus.NOT_CACHED
                    }
                )
            } else {
                source
            }
        }
    }

    private fun isMediaFusionPlaybackUrl(url: String): Boolean {
        return url.contains("mediafusion.elfhosted.com/playback", ignoreCase = true)
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
                        debridFileIdx = source?.fileIdx
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
                    showDebridSourcePicker()
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
                        showDebridSourcePicker()
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
                    magnet = null
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
                        debridInfoHash = infoHash
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
    }

    override fun onPause() {
        super.onPause()
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
