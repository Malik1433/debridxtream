package com.tvonnet.debridxtreamiptv.ui.series

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
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
import com.tvonnet.debridxtreamiptv.data.Result
import com.tvonnet.debridxtreamiptv.data.model.ContentType
import com.tvonnet.debridxtreamiptv.data.model.XtreamEpisodeInfo
import com.tvonnet.debridxtreamiptv.data.model.XtreamSeasonInfo
import com.tvonnet.debridxtreamiptv.data.model.XtreamSeriesDetailInfo
import com.tvonnet.debridxtreamiptv.data.model.XtreamSeriesDetailResponse
import com.tvonnet.debridxtreamiptv.data.model.XtreamSeriesInfo
import com.tvonnet.debridxtreamiptv.data.onFailure
import com.tvonnet.debridxtreamiptv.data.onSuccess
import com.tvonnet.debridxtreamiptv.data.prefs.CredentialsPreferences
import com.tvonnet.debridxtreamiptv.data.repository.XtreamRepository
import com.tvonnet.debridxtreamiptv.player.stabilized.PlayerActivity
import com.tvonnet.debridxtreamiptv.ui.sources.SourceFilterUtils
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
    private lateinit var btnWatchNow: Button
    private lateinit var btnWatchTrailer: Button
    private lateinit var btnAddFavorite: Button
    private lateinit var btnRefresh: ImageButton
    private lateinit var loadingOverlay: View
    private lateinit var tvLoadingMessage: TextView

    private lateinit var seasonsAdapter: SeriesSeasonAdapter
    private lateinit var episodesAdapter: SeriesEpisodeAdapter

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

    private var currentDetail: XtreamSeriesDetailResponse? = null
    private var selectedSeasonKey: String? = null
    private var selectedEpisode: EpisodeUiModel? = null
    private var lastDebridEpisode: EpisodeUiModel? = null
    private val cachedDebridSourcesByEpisode = mutableMapOf<String, List<com.tvonnet.debridxtreamiptv.data.repository.MovieSource>>()
    private val debridFilterStateByEpisode = mutableMapOf<String, com.tvonnet.debridxtreamiptv.ui.sources.SourceFilterState>()
    private val selectedDebridStreamIdByEpisode = mutableMapOf<String, String?>()

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
            val episode = lastDebridEpisode
            if (autoPlayNext && !failedStreamId.isNullOrBlank() && episode != null) {
                autoPlayNextDebridEpisodeSource(episode, failedStreamId)
            } else {
                lastDebridEpisode?.let { fetchAndShowDebridSources(it) }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // DEBUG: FORCE RED BACKGROUND
        window.decorView.setBackgroundColor(android.graphics.Color.RED)
        
        setContentView(R.layout.activity_series_detail)

        // Repository and credentials are now injected via Hilt
        // No need to manually create instances
        
        initViews()
        setupAdapters()
        getSeriesDataFromIntent()
        
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

        rvSeasons.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        rvSeasons.setHasFixedSize(true)
        androidx.recyclerview.widget.LinearSnapHelper().attachToRecyclerView(rvSeasons)
        rvEpisodes.layoutManager = LinearLayoutManager(this, LinearLayoutManager.VERTICAL, false)
        rvEpisodes.setHasFixedSize(true)
    }

    private fun setupAdapters() {
        seasonsAdapter = SeriesSeasonAdapter(
            onSeasonSelected = { season ->
                selectedSeasonKey = season.key
                showEpisodesForSeason(season.key)
            },
            onSeasonFocused = { season ->
                selectedSeasonKey = season.key
                showEpisodesForSeason(season.key)
            }
        )
        rvSeasons.adapter = seasonsAdapter

        episodesAdapter = SeriesEpisodeAdapter(
            onEpisodeClick = { episode ->
                selectedEpisode = episode
                episodesAdapter.setSelectedEpisode(episode.id)
                updateWatchNowState()
                playEpisode(episode)
            },
            onEpisodeFocused = { episode ->
                selectedEpisode = episode
                episodesAdapter.setSelectedEpisode(episode.id)
                updateWatchNowState()
            }
        )
        rvEpisodes.adapter = episodesAdapter
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
            isDebridContent = data.getBooleanExtra(EXTRA_IS_DEBRID, false)
        }

        if (seriesId.isNullOrBlank()) {
            Toast.makeText(this, R.string.series_detail_error_loading, Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        
        // Week 14: Set transition name for Shared Element Transition
        ivPoster.transitionName = "series_poster_$seriesId"
    }

    private fun setupClickListeners() {
        btnWatchNow.setOnClickListener {
            selectedEpisode?.let { playEpisode(it) }
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
    }

    private fun displaySeriesInfo() {
        tvTitle.text = seriesName ?: getString(R.string.series_detail_title_placeholder)

        val ratingValue = seriesRating?.toDoubleOrNull() ?: 0.0
        displayRating(ratingValue)

        val yearText = buildGenreYearText(seriesGenre, seriesReleaseDate)
        tvGenreYear.text = yearText

        if (!seriesDirector.isNullOrBlank()) {
            tvDirector.visibility = View.VISIBLE
            tvDirector.text = getString(R.string.movie_detail_directed_by, seriesDirector)
        } else {
            tvDirector.visibility = View.GONE
        }

        if (!seriesCast.isNullOrBlank()) {
            tvCast.visibility = View.VISIBLE
            tvCast.text = getString(R.string.movie_detail_cast, seriesCast)
        } else {
            tvCast.visibility = View.GONE
        }

        tvDescription.text = seriesPlot?.takeIf { it.isNotBlank() }
            ?: getString(R.string.series_detail_description_placeholder)

        if (!seriesBackdrop.isNullOrBlank()) {
            Glide.with(this)
                .load(seriesBackdrop)
                .centerCrop()
                .into(ivBackdrop)
        } else {
            ivBackdrop.setImageDrawable(null)
        }

        // Removed postpone logic to fix build
        // supportPostponeEnterTransition()

        if (!seriesCover.isNullOrBlank()) {
            Glide.with(this)
                .load(seriesCover)
                .placeholder(R.drawable.tv_card_placeholder)
                .error(R.drawable.tv_card_placeholder)
                .centerCrop()
                .into(ivPoster)
        } else {
            ivPoster.setImageResource(R.drawable.tv_card_placeholder)
        }
        
        android.util.Log.d("SeriesDetailActivity", "Displaying series info: $seriesName")

        updateWatchNowState()
    }
    



    private fun ensureRepositoryInitialized() {
        repository.ensureInitialized(
            credentialsPreferences.getServerUrl(),
            credentialsPreferences.getUsername(),
            credentialsPreferences.getPassword()
        )
    }

    private fun displayRating(rating: Double) {
        val stars = (rating / 2.0).coerceAtLeast(0.0).toInt().coerceAtMost(5)
        val starString = "★".repeat(stars) + "☆".repeat(5 - stars)
        tvRatingStars.text = starString
        tvRatingPercentage.text = getString(R.string.movie_detail_rating_format, rating)
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
        seriesCover = com.tvonnet.debridxtreamiptv.util.GlobalConfig.resolveIconUrl(info.cover) ?: seriesCover
        seriesBackdrop = info.backdropPathString ?: seriesBackdrop

        displaySeriesInfo()
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
            val initialKey = selectedSeasonKey ?: seasonItems.first().key
            selectedSeasonKey = initialKey
            showEpisodesForSeason(initialKey)
            rvSeasons.post {
                rvSeasons.post {
                    rvSeasons.requestFocus()
                }
            }
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
        if (episodes.isEmpty()) {
            tvEmptyEpisodes.visibility = View.VISIBLE
            episodesAdapter.submitEpisodes(emptyList(), null)
            selectedEpisode = null
        } else {
            tvEmptyEpisodes.visibility = View.GONE
            val currentSelectionId = selectedEpisode?.id
            val newSelection = episodes.firstOrNull { it.id == currentSelectionId } ?: episodes.first()
            selectedEpisode = newSelection
            episodesAdapter.submitEpisodes(episodes, newSelection.id)
            // Removed auto-focus to prevent jumping when navigating seasons
            // rvEpisodes.post { rvEpisodes.requestFocus() }
        }
        updateWatchNowState()
    }

    private fun updateWatchNowState() {
        btnWatchNow.isEnabled = selectedEpisode != null
    }

    private fun handleSeriesDetailError(error: Exception) {
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

        val intent = PlayerActivity.createIntent(
            context = this,
            streamUrl = streamUrl,
            title = episode.title,
            contentId = episode.id,
            contentType = ContentType.EPISODE,
            posterUrl = episode.thumbnailUrl,
            seriesTitle = seriesName,
            episodeTitle = episode.title,
            seasonNumber = selectedSeasonKey?.toIntOrNull(),
            episodeNumber = episode.episodeNumber
        )
        seriesId?.let { intent.putExtra(PlayerActivity.EXTRA_SERIES_ID, it) }
        startActivity(intent)
    }

    private fun fetchAndShowDebridSources(episode: EpisodeUiModel) {
        lastDebridEpisode = episode
        val initialState = debridFilterStateByEpisode[episode.id]
            ?: com.tvonnet.debridxtreamiptv.ui.sources.SourceFilterState()
        val initialSelectedStreamId = selectedDebridStreamIdByEpisode[episode.id]
        val bottomSheet = SourceSelectionBottomSheet(
            onSourceSelected = { source ->
                selectedDebridStreamIdByEpisode[episode.id] = source.stream.stream_id
                playDebridEpisode(source, episode)
            },
            onStateChanged = { state ->
                debridFilterStateByEpisode[episode.id] = state
            },
            initialState = initialState,
            initialSelectedStreamId = initialSelectedStreamId
        )
        bottomSheet.show(supportFragmentManager, SourceSelectionBottomSheet.TAG)
        val cachedSources = cachedDebridSourcesByEpisode[episode.id]
        if (cachedSources != null) {
            bottomSheet.showSources(cachedSources)
            refreshMediaFusionCacheStatus(episode.id, cachedSources, bottomSheet)
            return
        }

        bottomSheet.showLoading()

        lifecycleScope.launch {
            try {
                val seasonNumber = selectedSeasonKey?.toIntOrNull() ?: 1
                val episodeNumber = episode.episodeNumber ?: 1
                
                val sources = withContext(Dispatchers.IO) {
                    unifiedSourceProvider.getSeriesEpisodeSources(
                        seriesId = seriesId,
                        seasonNumber = seasonNumber,
                        episodeNumber = episodeNumber,
                        title = seriesName,
                        yearHint = seriesReleaseDate?.take(4)
                    )
                }
                android.util.Log.e("SeriesDetailActivity", "Received ${sources.size} sources from provider. Updating bottom sheet.")
                cachedDebridSourcesByEpisode[episode.id] = sources
                bottomSheet.showSources(sources)
                refreshMediaFusionCacheStatus(episode.id, sources, bottomSheet)
            } catch (e: Exception) {
                android.util.Log.e("SeriesDetailActivity", "Error fetching sources: ${e.message}", e)
                bottomSheet.showError(e.message ?: "Failed to load sources")
            }
        }
    }

    private fun playDebridEpisode(source: com.tvonnet.debridxtreamiptv.data.repository.MovieSource, episode: EpisodeUiModel) {
        lastDebridEpisode = episode
        selectedDebridStreamIdByEpisode[episode.id] = source.stream.stream_id
        val stream = source.stream
        val magnet = stream.direct_source
        val infoHash = stream.stream_id

        if (magnet.isNullOrBlank() && infoHash.isNullOrBlank()) {
             Toast.makeText(this, "Invalid Debrid source", Toast.LENGTH_SHORT).show()
             return
        }

        val seasonNumber = selectedSeasonKey?.toIntOrNull()
        val episodeNumber = episode.episodeNumber
        val tmdbId = seriesId
        val seriesTitle = seriesName
        // Check if direct HTTP link (MediaFusion direct stream)
        if (magnet?.startsWith("http") == true && magnet.endsWith(".torrent") == false) {
            val launchPlayer = {
                val intent = PlayerActivity.createIntent(
                    context = this@SeriesDetailActivity,
                    streamUrl = magnet,
                    title = "${seriesName} - S${selectedSeasonKey}E${episode.episodeNumber}",
                    contentId = infoHash ?: seriesId ?: "",
                    contentType = ContentType.EPISODE,
                    playbackSource = com.tvonnet.debridxtreamiptv.player.stabilized.PlaybackSource.DEBRID,
                    posterUrl = episode.thumbnailUrl,
                    backdropUrl = seriesBackdrop,
                    headers = source.headers,
                    tmdbId = tmdbId,
                    seriesTitle = seriesTitle,
                    episodeTitle = episode.title,
                    seasonNumber = seasonNumber,
                    episodeNumber = episodeNumber,
                    debridInfoHash = infoHash,
                    debridMagnet = magnet
                )
                intent.putExtra(PlayerActivity.EXTRA_RETURN_TO_SOURCES, true)
                playerLauncher.launch(intent)
            }

            if (isMediaFusionPlaybackUrl(magnet)) {
                lifecycleScope.launch {
                    val readyResult = withContext(Dispatchers.IO) {
                        debridPlaybackRepository.isMediaFusionPlaybackReady(magnet)
                    }
                    val isReady = readyResult is com.tvonnet.debridxtreamiptv.data.Result.Success && readyResult.data
                    if (!isReady) {
                        markDebridEpisodeSourceCached(episode.id, stream.stream_id, false)
                        Toast.makeText(
                            this@SeriesDetailActivity,
                            "MediaFusion: link not cached on RD yet",
                            Toast.LENGTH_LONG
                        ).show()
                        fetchAndShowDebridSources(episode)
                        return@launch
                    }
                    markDebridEpisodeSourceCached(episode.id, stream.stream_id, true)
                    launchPlayer()
                }
                return
            }

            launchPlayer()
            return
        }

        showLoading(true)
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                debridPlaybackRepository.resolveDebridUrl(
                    infoHash = infoHash,
                    magnet = magnet,
                    seasonNumber = seasonNumber,
                    episodeNumber = episode.episodeNumber,
                    episodeTitle = episode.title
                )
            }
            showLoading(false)

            result.onSuccess { streamUrl ->
                 val intent = PlayerActivity.createIntent(
                    context = this@SeriesDetailActivity,
                    streamUrl = streamUrl,
                    title = "${seriesName} - S${selectedSeasonKey}E${episode.episodeNumber}",
                    contentId = infoHash ?: seriesId ?: "",
                    contentType = ContentType.EPISODE,
                    playbackSource = com.tvonnet.debridxtreamiptv.player.stabilized.PlaybackSource.DEBRID,
                    posterUrl = episode.thumbnailUrl,
                    backdropUrl = seriesBackdrop,
                    tmdbId = tmdbId,
                    seriesTitle = seriesTitle,
                    episodeTitle = episode.title,
                    seasonNumber = seasonNumber,
                    episodeNumber = episodeNumber,
                    debridInfoHash = infoHash,
                    debridMagnet = magnet
                )
                intent.putExtra(PlayerActivity.EXTRA_RETURN_TO_SOURCES, true)
                playerLauncher.launch(intent)
            }.onFailure { e ->
                if (e is com.tvonnet.debridxtreamiptv.data.debrid.model.NotAuthenticatedException) {
                    showDebridConfigDialog()
                } else {
                    Toast.makeText(this@SeriesDetailActivity, "Failed to resolve Debrid link: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
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

        lifecycleScope.launch {
            val seasonNumber = selectedSeasonKey?.toIntOrNull() ?: 1
            val episodeNumber = episode.episodeNumber ?: 1
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

    private fun pickNextDebridEpisodeSource(
        episodeId: String,
        sources: List<com.tvonnet.debridxtreamiptv.data.repository.MovieSource>,
        failedStreamId: String
    ): com.tvonnet.debridxtreamiptv.data.repository.MovieSource? {
        val filterState = debridFilterStateByEpisode[episodeId]
            ?: com.tvonnet.debridxtreamiptv.ui.sources.SourceFilterState()
        val filteredSources = SourceFilterUtils.apply(sources, filterState)
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
            if (source.stream.stream_id == streamId) source.copy(isCached = isCached) else source
        }
    }

    private fun refreshMediaFusionCacheStatus(
        episodeId: String,
        sources: List<com.tvonnet.debridxtreamiptv.data.repository.MovieSource>,
        bottomSheet: SourceSelectionBottomSheet
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
                    source.copy(isCached = cached)
                } else {
                    source
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

    private fun isMediaFusionPlaybackUrl(url: String): Boolean {
        return url.contains("mediafusion.elfhosted.com/playback", ignoreCase = true)
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

    private fun updateFavoriteButton(isFavorite: Boolean) {
        btnAddFavorite.text = if (isFavorite) {
            getString(R.string.series_detail_remove_from_favorites)
        } else {
            getString(R.string.series_detail_add_to_favorites)
        }
    }

    private fun launchTrailer() {
        val seriesIdStr = seriesId ?: return
        
        // Phase 2: Check for TMDB trailer (Since Xtream rarely provides series trailers)
        lifecycleScope.launch {
            try {
                val tmdbId = seriesIdStr.toIntOrNull() ?: return@launch
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
                        startActivity(com.tvonnet.debridxtreamiptv.ui.trailer.TrailerActivity.createIntent(this@SeriesDetailActivity, trailer.key))
                    } else {
                        Toast.makeText(this@SeriesDetailActivity, R.string.movie_detail_trailer_unavailable, Toast.LENGTH_SHORT).show()
                    }
                }.onFailure {
                    Toast.makeText(this@SeriesDetailActivity, R.string.movie_detail_trailer_unavailable, Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(this@SeriesDetailActivity, R.string.movie_detail_trailer_unavailable, Toast.LENGTH_SHORT).show()
            }
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
