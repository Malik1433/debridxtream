package com.tvonnet.debridxtreamiptv.features.vodv2.ui

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.tvonnet.debridxtreamiptv.data.debrid.model.TmdbImageUrl
import com.tvonnet.debridxtreamiptv.data.debrid.model.TmdbMovie
import com.tvonnet.debridxtreamiptv.data.model.ContentType
import com.tvonnet.debridxtreamiptv.R
import com.tvonnet.debridxtreamiptv.data.omdb.OmdbClient
import com.tvonnet.debridxtreamiptv.data.prefs.CredentialsPreferences
import com.tvonnet.debridxtreamiptv.data.prefs.WatchHistoryPreferences
import com.tvonnet.debridxtreamiptv.databinding.FragmentMovieDetailV2Binding
import com.tvonnet.debridxtreamiptv.player.stabilized.PlayerActivity
import com.tvonnet.debridxtreamiptv.data.local.WatchedIdentityBuilder
import com.tvonnet.debridxtreamiptv.data.repository.WatchedStateRepository
import com.tvonnet.debridxtreamiptv.ui.trailer.TrailerActivity
import com.tvonnet.debridxtreamiptv.ui.trailer.TrailerValueParser
import com.tvonnet.debridxtreamiptv.ui.vod.CastAdapter
import com.tvonnet.debridxtreamiptv.ui.vod.MovieDetailActivity
import com.tvonnet.debridxtreamiptv.ui.vod.SimilarMoviesAdapter
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import java.util.Locale
import javax.inject.Inject

@AndroidEntryPoint
class MovieDetailFragmentV2 : Fragment() {

    private var _binding: FragmentMovieDetailV2Binding? = null
    private val binding get() = _binding!!

    @Inject
    lateinit var watchedStateRepository: WatchedStateRepository

    @Inject
    lateinit var repository: com.tvonnet.debridxtreamiptv.data.repository.XtreamRepository

    private val viewModel: MovieDetailViewModelV2 by viewModels()

    private lateinit var castAdapter: CastAdapter
    private lateinit var similarAdapter: SimilarMoviesAdapter

    // Movie args
    private var streamId: String? = null
    private var movieTitle: String? = null
    private var backdropUrl: String? = null
    private var posterUrl: String? = null
    private var containerExt: String = "mp4"
    private var directSource: String? = null
    private var isDebridMovie: Boolean = false
    private var sourceRail: String? = null

    // Resolved (TMDB / OMDb enrichment)
    private var resolvedTrailer: String? = null
    private var resolvedTmdbId: Int? = null
    private var resolvedImdbId: String? = null

    private var hasResumePosition = false
    private var resumePositionMs: Long = 0L

    private val trailerPreviewHandler = Handler(Looper.getMainLooper())
    private var trailerPreviewRunnable: Runnable? = null
    private var trailerPreviewShown = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentMovieDetailV2Binding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val args = requireArguments()
        streamId = args.getString(ARG_STREAM_ID)
        movieTitle = args.getString(ARG_TITLE)
        backdropUrl = args.getString(ARG_BACKDROP_URL)
        posterUrl = args.getString(ARG_POSTER_URL)
        val plot = args.getString(ARG_PLOT)
        val year = args.getString(ARG_YEAR)
        val genre = args.getString(ARG_GENRE)
        val rating = args.getString(ARG_RATING)
        containerExt = args.getString(ARG_CONTAINER_EXT) ?: "mp4"
        directSource = args.getString(ARG_DIRECT_SOURCE)
        val trailer = args.getString(ARG_TRAILER)
        isDebridMovie = args.getBoolean(ARG_IS_DEBRID, false) ||
            args.getString(ARG_SOURCE).equals("debrid", ignoreCase = true)
        sourceRail = args.getString(ARG_SOURCE_RAIL)
        resolvedTmdbId = firstNonBlank(args, ARG_TMDB_ID, "tmdbId")?.toIntOrNull()
        resolvedImdbId = firstNonBlank(args, ARG_IMDB_ID, "imdbId")

        bindArgFields(plot, year, genre, rating)

        setupAdapters()
        setupWatchedButton(year)

        if (!backdropUrl.isNullOrBlank()) {
            Glide.with(this)
                .load(backdropUrl)
                .transition(DrawableTransitionOptions.withCrossFade(250))
                .into(binding.ivBackdrop)
        }

        binding.pbLoadingCentral.visibility = View.GONE
        wireHeaderButtons(trailer)
        setupFavoriteButton()

        // Enrich with TMDB (plot/trailer/backdrop/rating + cast/similar/age/imdb).
        viewModel.start(title = movieTitle, yearHint = year, existingTrailer = trailer)
        observeEnrichment()

        setupFocusAnimations()
        setupInitialFocus()
        refreshResumeState()
        loadExternalRatings()
    }

    // The argument-supplied fields render first so the page is never blank while TMDB
    // enrichment is in flight; applyEnrichment only overwrites what it actually has.
    private fun bindArgFields(plot: String?, year: String?, genre: String?, rating: String?) {
        binding.tvContentType.text = binding.tvContentType.context.getString(R.string.ui_feature_film)
        binding.tvTitle.text = movieTitle ?: "Loading..."
        binding.tvPlot.text = plot?.takeIf { it.isNotBlank() } ?: "No plot available."
        binding.tvYear.text = year?.takeIf { it.isNotBlank() } ?: "N/A"
        binding.tvGenre.text = genre?.takeIf { it.isNotBlank() } ?: "Genre"
        binding.tvRating.text = rating?.takeIf { it.isNotBlank() } ?: ""

        // Back-to-rail context hint
        val rail = sourceRail?.trim()
        if (!rail.isNullOrBlank()) {
            binding.tvBackHint.text = getString(R.string.f_back_to, rail.uppercase(Locale.getDefault()))
            binding.tvBackHint.visibility = View.VISIBLE
        } else {
            binding.tvBackHint.visibility = View.GONE
        }
    }

    private fun wireHeaderButtons(trailer: String?) {
        binding.btnBack.setOnClickListener { parentFragmentManager.popBackStack() }
        binding.btnPlay.setOnClickListener { playMovie() }

        resolvedTrailer = trailer
        updateTrailerButtonState(resolvedTrailer, isLoading = false)
        binding.btnTrailer.setOnClickListener {
            val currentTrailer = resolvedTrailer
            if (currentTrailer.isNullOrBlank() || TrailerValueParser.parse(currentTrailer) == null) {
                Toast.makeText(requireContext(), requireContext().getString(R.string.c_no_trailer_available), Toast.LENGTH_SHORT).show()
            } else {
                startActivity(TrailerActivity.createIntent(requireContext(), currentTrailer))
            }
        }
    }

    private fun setupAdapters() {
        castAdapter = CastAdapter()
        binding.rvCast.apply {
            layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
            adapter = castAdapter
        }
        binding.tvCastTitle.visibility = View.GONE
        binding.rvCast.visibility = View.GONE

        similarAdapter = SimilarMoviesAdapter { movie -> openSimilarMovie(movie) }
        binding.rvSimilar.apply {
            layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
            adapter = similarAdapter
        }
    }

    private fun openSimilarMovie(movie: TmdbMovie) {
        val tmdbId = movie.id?.toString() ?: return
        val intent = Intent(requireContext(), MovieDetailActivity::class.java).apply {
            putExtra(MovieDetailActivity.EXTRA_MOVIE_ID, tmdbId)
            putExtra(MovieDetailActivity.EXTRA_MOVIE_NAME, movie.title)
            putExtra(MovieDetailActivity.EXTRA_MOVIE_ICON, TmdbImageUrl.getPosterUrl(movie.posterPath))
            putExtra(MovieDetailActivity.EXTRA_MOVIE_BACKDROP, TmdbImageUrl.getBackdropUrl(movie.backdropPath))
            putExtra(MovieDetailActivity.EXTRA_MOVIE_YEAR, movie.releaseDate?.take(4))
            putExtra(MovieDetailActivity.EXTRA_MOVIE_RATING, movie.voteAverage?.toString())
            putExtra(MovieDetailActivity.EXTRA_MOVIE_CATEGORY_ID, "debrid")
        }
        startActivity(intent)
    }

    // One enrichment emission, verbatim: every field is written only when the state carries
    // it, so a later partial emission can never blank an already-populated view.
    private fun applyEnrichment(state: MovieDetailV2UiState) {
        resolvedTrailer = state.trailerValue ?: resolvedTrailer
        updateTrailerButtonState(resolvedTrailer, isLoading = state.isLoading)

        state.plot?.takeIf { it.isNotBlank() }?.let { binding.tvPlot.text = it }
        state.year?.takeIf { it.isNotBlank() }?.let { binding.tvYear.text = it }
        state.genre?.takeIf { it.isNotBlank() }?.let { binding.tvGenre.text = it }
        state.rating?.takeIf { it.isNotBlank() }?.let { binding.tvRating.text = it }

        // Duration from TMDB runtime.
        state.runtimeMinutes?.takeIf { it > 0 }?.let { minutes ->
            binding.tvDuration.text = formatRuntime(minutes)
            binding.tvDuration.visibility = View.VISIBLE
        }

        // Age rating (TMDB certification).
        state.ageRating?.takeIf { it.isNotBlank() }?.let {
            binding.tvAgeRating.text = it
            binding.tvAgeRating.visibility = View.VISIBLE
        }

        applyCreditsAndSimilar(state)
        applyBackdrop(state)

        // Newly-resolved ids let us fetch external ratings + refine resume match.
        val newImdb = state.imdbId
        if (!newImdb.isNullOrBlank() && newImdb != resolvedImdbId) {
            resolvedImdbId = newImdb
            loadExternalRatings()
        }
        state.tmdbId?.let { resolvedTmdbId = it }
        refreshResumeState()
    }

    // Director + cast, then the because-you-watched row.
    private fun applyCreditsAndSimilar(state: MovieDetailV2UiState) {
        state.director?.takeIf { it.isNotBlank() }?.let {
            binding.tvDirector.text = it
            binding.layoutDirector.visibility = View.VISIBLE
        }
        if (state.cast.isNotEmpty()) {
            castAdapter.submitList(state.cast)
            binding.tvCastTitle.visibility = View.VISIBLE
            binding.rvCast.visibility = View.VISIBLE
        }

        // M16: see R.bool.detail_shows_similar_row — on a phone this rail costs the film's own
        // column 169dp of a 411dp screen and pushes Watch Now below the fold.
        if (state.recommendations.isNotEmpty() &&
            resources.getBoolean(R.bool.detail_shows_similar_row)
        ) {
            similarAdapter.submitList(state.recommendations)
            val title = movieTitle?.trim()
            binding.headerSimilar.text = if (!title.isNullOrBlank()) {
                "BECAUSE YOU WATCHED ${title.uppercase(Locale.getDefault())}"
            } else {
                "SIMILAR MOVIES"
            }
            binding.layoutSimilarRow.visibility = View.VISIBLE
        }
    }

    // The trailer preview owns the backdrop while it is showing — never overwrite it.
    private fun applyBackdrop(state: MovieDetailV2UiState) {
        val url = state.backdropUrl?.takeIf { it.isNotBlank() } ?: return
        backdropUrl = url
        if (!trailerPreviewShown) {
            Glide.with(this@MovieDetailFragmentV2)
                .load(url)
                .transition(DrawableTransitionOptions.withCrossFade(250))
                .into(binding.ivBackdrop)
        }
    }

    private fun observeEnrichment() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state -> applyEnrichment(state) }
            }
        }
    }

    private fun playMovie() {
        val id = streamId
        if (id.isNullOrBlank()) {
            Toast.makeText(requireContext(), requireContext().getString(R.string.c_invalid_movie_id), Toast.LENGTH_SHORT).show()
            return
        }
        val prefs = CredentialsPreferences(requireContext())
        val serverUrl = prefs.getServerUrl()
        val username = prefs.getUsername()
        val password = prefs.getPassword()
        if (serverUrl.isNullOrBlank() || username.isNullOrBlank() || password.isNullOrBlank()) {
            Toast.makeText(requireContext(), requireContext().getString(R.string.c_missing_credentials), Toast.LENGTH_SHORT).show()
            return
        }

        val streamUrl = resolveDirectSourceUrl(directSource)
            ?: buildXtreamMovieUrl(serverUrl, username, password, id, containerExt)
        val intent = PlayerActivity.createIntent(
            context = requireContext(),
            streamUrl = streamUrl,
            title = movieTitle,
            contentId = id,
            contentType = ContentType.MOVIE,
            posterUrl = posterUrl,
            backdropUrl = backdropUrl,
            startPositionMs = resumePositionMs
        )
        startActivity(intent)
    }

    /** Real favorite toggle (reuses XtreamRepository favorites, same as browse). */
    private fun setupFavoriteButton() {
        val id = streamId ?: run {
            binding.btnFavorite.visibility = View.GONE
            return
        }
        var isFav = false
        binding.btnFavorite.setImageResource(R.drawable.ic_favorite_border)
        binding.btnFavorite.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    if (isFav) {
                        repository.removeFavorite(id)
                        Toast.makeText(requireContext(), requireContext().getString(R.string.movie_detail_removed_from_favorites), Toast.LENGTH_SHORT).show()
                    } else {
                        repository.addFavorite(
                            streamId = id,
                            type = "vod",
                            name = movieTitle ?: "Movie",
                            iconUrl = com.tvonnet.debridxtreamiptv.util.GlobalConfig.resolveIconUrl(posterUrl)
                        )
                        Toast.makeText(requireContext(), requireContext().getString(R.string.movie_detail_added_to_favorites), Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                repository.getFavoritesByType("vod").collect { favorites ->
                    isFav = favorites.any { it.streamId == id }
                    binding.btnFavorite.setImageResource(
                        if (isFav) R.drawable.ic_favorite else R.drawable.ic_favorite_border
                    )
                }
            }
        }
    }

    private fun setupWatchedButton(year: String?) {
        binding.tvWatchedBadge.visibility = View.GONE
        val id = streamId ?: return

        val identity = if (isDebridMovie) {
            WatchedIdentityBuilder.debridMovie(
                tmdbId = resolvedTmdbId?.toString(), imdbId = resolvedImdbId, title = movieTitle, year = year
            )
        } else {
            WatchedIdentityBuilder.iptvMovie(id, movieTitle, year)
        }
        val watchedSource = if (isDebridMovie) {
            com.tvonnet.debridxtreamiptv.data.local.entity.WatchedStateEntity.SOURCE_DEBRID
        } else {
            com.tvonnet.debridxtreamiptv.data.local.entity.WatchedStateEntity.SOURCE_XTREAM
        }

        var currentWatchedState = false
        binding.btnMarkWatched.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                val repo = watchedStateRepository
                val historyPrefs = WatchHistoryPreferences(requireContext())
                val newState = !currentWatchedState
                val stateEntity = repo.getState(identity)
                    ?: com.tvonnet.debridxtreamiptv.data.local.entity.WatchedStateEntity(
                        identityKey = identity,
                        contentType = com.tvonnet.debridxtreamiptv.data.local.entity.WatchedStateEntity.CONTENT_TYPE_MOVIE,
                        source = watchedSource,
                        iptvStreamId = if (isDebridMovie) null else id,
                        title = movieTitle,
                        year = year,
                        tmdbId = if (isDebridMovie) resolvedTmdbId?.toString() else null,
                        imdbId = if (isDebridMovie) resolvedImdbId else null,
                        durationMs = 0L,
                        updatedAt = System.currentTimeMillis()
                    )
                repo.setManualWatched(stateEntity, newState, System.currentTimeMillis())
                historyPrefs.removeContinueWatchingItem(id)
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                watchedStateRepository.observeState(identity).collect { state ->
                    currentWatchedState = state?.isWatched == true
                    binding.tvWatchedBadge.visibility = if (currentWatchedState) View.VISIBLE else View.GONE
                    binding.btnMarkWatched.text = if (currentWatchedState) "Mark Unwatched" else "Mark Watched"
                }
            }
        }
    }

    /** Fetches IMDb + Rotten Tomatoes scores from OMDb and shows them in the ratings row. */
    private fun loadExternalRatings() {
        val imdb = resolvedImdbId ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            val ratings = OmdbClient.fetchRatings(imdb) ?: return@launch
            if (_binding == null) return@launch
            ratings.imdbRating?.let {
                binding.tvImdbRating.text = it
                binding.badgeImdb.visibility = View.VISIBLE
            }
            ratings.rottenTomatoes?.let {
                binding.tvRtRating.text = it
                binding.badgeRt.visibility = View.VISIBLE
            }
            if (binding.tvAgeRating.visibility != View.VISIBLE && !ratings.rated.isNullOrBlank()) {
                binding.tvAgeRating.text = ratings.rated
                binding.tvAgeRating.visibility = View.VISIBLE
            }
        }
    }

    /** Shows the resume bar for a saved IPTV watch position (0 < progress < 100%). */
    private fun refreshResumeState() {
        if (_binding == null) return
        val resumeItem = WatchHistoryPreferences(requireContext()).getMovieResumeItem(
            contentId = streamId,
            tmdbId = resolvedTmdbId?.toString(),
            imdbId = resolvedImdbId,
            title = movieTitle
        )
        val position = resumeItem?.currentPosition ?: 0L
        val duration = resumeItem?.totalDuration ?: 0L
        val progress = if (duration > 0L) ((position * 100) / duration).toInt() else 0

        hasResumePosition = resumeItem != null && position > 0L && progress in 1..99
        resumePositionMs = if (hasResumePosition) position else 0L
        if (hasResumePosition) {
            binding.tvResumeElapsed.text = getString(R.string.f_resume_from, formatClock(position))
            binding.tvResumeRemaining.text = getString(R.string.f_time_left, formatClock(duration - position))
            binding.pbResume.progress = progress
            binding.layoutResumeBar.visibility = View.VISIBLE
        } else {
            binding.layoutResumeBar.visibility = View.GONE
        }
        binding.btnPlay.text = if (hasResumePosition) "Resume" else "Watch Now"
    }

    private fun setupFocusAnimations() {
        val buttons = listOf(
            binding.btnPlay, binding.btnTrailer, binding.btnFavorite, binding.btnMarkWatched
        )
        // ⭐ The LEADING button grows from its left edge, not its centre — see
        // MovieDetailActivity.setupFocusAnimations for the full reasoning. In short: a television
        // overscans, this content margin already sits close to what the panel shows, and a centred
        // scale pushed the first button OUTSIDE it. Only on focus, only that button, and invisible
        // to a screenshot, because the framebuffer keeps what the screen throws away.
        binding.btnPlay.pivotX = 0f
        buttons.forEach { view ->
            view.alpha = 0.9f
            view.setOnFocusChangeListener { v, hasFocus ->
                if (hasFocus) {
                    v.animate().scaleX(1.05f).scaleY(1.05f).alpha(1.0f).setDuration(200)
                        .setInterpolator(android.view.animation.DecelerateInterpolator()).start()
                } else {
                    v.animate().scaleX(1.0f).scaleY(1.0f).alpha(0.9f).setDuration(150).start()
                }
                if (v.id == binding.btnTrailer.id) {
                    if (hasFocus) scheduleTrailerPreview() else cancelTrailerPreview()
                }
            }
        }
    }

    /** After a 2.5s dwell on Trailer, crossfade the backdrop to the trailer thumbnail. */
    private fun scheduleTrailerPreview() {
        cancelTrailerPreview()
        val key = TrailerValueParser.parse(resolvedTrailer)?.youtubeId ?: return
        val runnable = Runnable {
            if (_binding == null) return@Runnable
            Glide.with(this)
                .load("https://img.youtube.com/vi/$key/hqdefault.jpg")
                .centerCrop()
                .transition(DrawableTransitionOptions.withCrossFade(500))
                .into(binding.ivBackdrop)
            trailerPreviewShown = true
        }
        trailerPreviewRunnable = runnable
        trailerPreviewHandler.postDelayed(runnable, 2500L)
    }

    private fun cancelTrailerPreview() {
        trailerPreviewRunnable?.let { trailerPreviewHandler.removeCallbacks(it) }
        trailerPreviewRunnable = null
        if (trailerPreviewShown && _binding != null) {
            trailerPreviewShown = false
            backdropUrl?.takeIf { it.isNotBlank() }?.let { url ->
                Glide.with(this)
                    .load(url)
                    .centerCrop()
                    .transition(DrawableTransitionOptions.withCrossFade(400))
                    .into(binding.ivBackdrop)
            }
        }
    }

    private fun setupInitialFocus() {
        binding.root.post {
            binding.root.post { binding.btnPlay.requestFocus() }
        }
    }

    /** A provider-supplied absolute stream URL, or null when the Xtream URL must be built. */
    private fun resolveDirectSourceUrl(directSource: String?): String? {
        val direct = directSource?.trim().orEmpty()
        if (direct.isBlank()) return null
        if (direct.startsWith("http://", true) || direct.startsWith("https://", true)) return direct
        if (direct.startsWith("rtmp://", true)) return direct
        if (direct.startsWith("rtsp://", true)) return direct
        return null
    }

    private fun buildXtreamMovieUrl(
        serverUrl: String,
        username: String,
        password: String,
        streamId: String,
        containerExt: String?
    ): String {
        val baseUrl = serverUrl.trimEnd('/')
        val ext = containerExt?.trim()?.trimStart('.')?.ifBlank { "mp4" } ?: "mp4"
        return "$baseUrl/movie/$username/$password/$streamId.$ext"
    }

    private fun updateTrailerButtonState(trailerValue: String?, isLoading: Boolean) {
        val hasTrailer = TrailerValueParser.parse(trailerValue) != null
        binding.btnTrailer.isEnabled = hasTrailer
        binding.btnTrailer.alpha = if (hasTrailer) 1f else 0.6f
        binding.pbLoadingCentral.visibility = if (isLoading && !hasTrailer) View.VISIBLE else View.GONE
    }

    private fun formatRuntime(minutes: Int): String {
        val h = minutes / 60
        val m = minutes % 60
        return if (h > 0) "${h}H ${m}M" else "${m}M"
    }

    private fun formatClock(milliseconds: Long): String {
        val totalSeconds = milliseconds.coerceAtLeast(0L) / 1000L
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.US, "%d:%02d", minutes, seconds)
        }
    }

    private fun firstNonBlank(args: Bundle, vararg keys: String): String? {
        return keys.firstNotNullOfOrNull { key ->
            args.getString(key)?.trim()?.takeIf { it.isNotBlank() }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshResumeState()
    }

    override fun onDestroyView() {
        cancelTrailerPreview()
        super.onDestroyView()
        _binding = null
    }

    companion object {
        private const val ARG_STREAM_ID = "stream_id"
        private const val ARG_TITLE = "title"
        private const val ARG_BACKDROP_URL = "backdrop_url"
        private const val ARG_POSTER_URL = "poster_url"
        private const val ARG_PLOT = "plot"
        private const val ARG_YEAR = "year"
        private const val ARG_GENRE = "genre"
        private const val ARG_RATING = "rating"
        private const val ARG_CONTAINER_EXT = "container_ext"
        private const val ARG_DIRECT_SOURCE = "direct_source"
        private const val ARG_TRAILER = "trailer"
        private const val ARG_SOURCE = "source"
        private const val ARG_IS_DEBRID = "is_debrid"
        private const val ARG_TMDB_ID = "tmdb_id"
        private const val ARG_IMDB_ID = "imdb_id"
        private const val ARG_SOURCE_RAIL = "source_rail"

        fun newInstance(args: Bundle): MovieDetailFragmentV2 {
            return MovieDetailFragmentV2().apply { arguments = args }
        }

        fun newInstance(
            streamId: String,
            title: String?,
            backdropUrl: String?,
            posterUrl: String?,
            plot: String? = null,
            year: String? = null,
            genre: String? = null,
            rating: String? = null,
            containerExt: String? = null,
            directSource: String? = null,
            trailer: String? = null,
            source: String? = null,
            isDebrid: Boolean = false,
            tmdbId: String? = null,
            imdbId: String? = null,
            sourceRail: String? = null
        ): MovieDetailFragmentV2 {
            return MovieDetailFragmentV2().apply {
                arguments = Bundle().apply {
                    putString(ARG_STREAM_ID, streamId)
                    putString(ARG_TITLE, title)
                    putString(ARG_BACKDROP_URL, backdropUrl)
                    putString(ARG_POSTER_URL, posterUrl)
                    putString(ARG_PLOT, plot)
                    putString(ARG_YEAR, year)
                    putString(ARG_GENRE, genre)
                    putString(ARG_RATING, rating)
                    putString(ARG_CONTAINER_EXT, containerExt)
                    putString(ARG_DIRECT_SOURCE, directSource)
                    putString(ARG_TRAILER, trailer)
                    putString(ARG_SOURCE, source)
                    putBoolean(ARG_IS_DEBRID, isDebrid)
                    putString(ARG_TMDB_ID, tmdbId)
                    putString(ARG_IMDB_ID, imdbId)
                    putString(ARG_SOURCE_RAIL, sourceRail)
                }
            }
        }
    }
}
