package com.tvonnet.debridxtreamiptv.features.seriesv2.ui

import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.paging.CombinedLoadStates
import androidx.paging.LoadState
import androidx.recyclerview.widget.LinearLayoutManager
import com.bumptech.glide.Glide
import com.tvonnet.debridxtreamiptv.R
import com.tvonnet.debridxtreamiptv.databinding.FragmentSeriesDetailV2Binding
import com.tvonnet.debridxtreamiptv.utils.updatePreservingFocus
import com.tvonnet.debridxtreamiptv.features.seriesv2.data.model.EpisodeEntityV2
import com.tvonnet.debridxtreamiptv.features.seriesv2.ui.model.SeriesDetailUiState
import com.tvonnet.debridxtreamiptv.features.seriesv2.ui.model.SeriesNavigationEvent
import com.tvonnet.debridxtreamiptv.ui.trailer.TrailerActivity
import com.tvonnet.debridxtreamiptv.ui.trailer.TrailerValueParser
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * IPTV Series Detail — cinematic layout matching "IPTV Series Detail.dc.html".
 *
 * Opening an episode presents the "SELECT STREAM" panel, which aggregates the same
 * show's streams across every Xtream playlist category it appears in.
 */
@AndroidEntryPoint
class SeriesDetailFragmentV2 : Fragment() {

    private var _binding: FragmentSeriesDetailV2Binding? = null
    private val binding get() = _binding!!

    @javax.inject.Inject
    lateinit var watchedStateRepository: com.tvonnet.debridxtreamiptv.data.repository.WatchedStateRepository

    @javax.inject.Inject
    lateinit var repository: com.tvonnet.debridxtreamiptv.data.repository.XtreamRepository

    // Used to resolve a YouTube trailer from TMDB when the Xtream series has none
    // (parity with the movie detail page, which enriches via TMDB).
    @javax.inject.Inject
    lateinit var tmdbRemoteDataSource: com.tvonnet.debridxtreamiptv.data.debrid.source.TmdbRemoteDataSource
    private var trailerLookupStarted = false
    private var enrichStarted = false
    private val castAdapter by lazy { com.tvonnet.debridxtreamiptv.ui.vod.CastAdapter() }

    private val viewModel: SeriesDetailViewModelV2 by viewModels()
    private var adapter: EpisodesAdapterV2? = null
    private var streamAdapter: StreamPanelAdapter? = null

    private var lastFocusedEpisodeId: String? = null
    private var activeEpisode: EpisodeEntityV2? = null
    private var seriesPlot: String? = null
    private var resolvedTrailer: String? = null
    private var currentSeasons: List<SeasonUiModel> = emptyList()
    private var toastRunnable: Runnable? = null
    private var seasonPopup: PopupWindow? = null
    private var pendingStreamFocus = false

    companion object {
        /** Empty strip within this window after open = still loading, not "No episodes". */
        private const val EPISODES_GRACE_MS = 12_000L

        const val ARG_SERIES_ID = "series_id"
        const val ARG_TITLE = "title"
        const val ARG_BACKDROP_URL = "backdrop_url"
        const val ARG_POSTER_URL = "poster_url"
        const val ARG_TRAILER = "trailer"

        fun newInstance(
            seriesId: String,
            title: String?,
            backdropUrl: String?,
            posterUrl: String?,
            trailer: String? = null
        ): SeriesDetailFragmentV2 = SeriesDetailFragmentV2().apply {
            arguments = Bundle().apply {
                putString(ARG_SERIES_ID, seriesId)
                putString(ARG_TITLE, title)
                putString(ARG_BACKDROP_URL, backdropUrl)
                putString(ARG_POSTER_URL, posterUrl)
                putString(ARG_TRAILER, trailer)
            }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentSeriesDetailV2Binding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupOptimisticUI()
        setupEpisodes()
        setupCredits()
        setupStreamPanel()
        setupListeners()
        setupFocusAnimations()
        setupObservers()
        setupBackHandling()
    }

    /**
     * Clear D-pad focus feedback on the top action row — the series page had none, so the
     * Play button barely showed focus (the movie page, which has this, felt "perfect").
     * Scale only (no alpha) so it never fights the Trailer button's enabled/disabled alpha.
     */
    private fun setupFocusAnimations() {
        listOf(binding.btnPlay, binding.btnTrailer, binding.btnFavorite, binding.btnSeasonSelector).forEach { v ->
            v.setOnFocusChangeListener { view, hasFocus ->
                view.animate().cancel()
                if (hasFocus) {
                    view.elevation = 8f // draw above neighbours without reordering the row
                    view.animate().scaleX(1.08f).scaleY(1.08f).setDuration(180)
                        .setInterpolator(android.view.animation.DecelerateInterpolator()).start()
                } else {
                    view.animate().scaleX(1f).scaleY(1f).setDuration(140)
                        .withEndAction { view.elevation = 0f }.start()
                }
            }
        }
    }

    private fun setupOptimisticUI() {
        val args = arguments ?: return
        binding.tvTitle.text = args.getString(ARG_TITLE) ?: "Loading..."
        resolvedTrailer = args.getString(ARG_TRAILER)
        args.getString(ARG_BACKDROP_URL)?.takeIf { it.isNotEmpty() }?.let {
            Glide.with(this).load(it).dontAnimate().into(binding.ivBackdrop)
        }
        updateTrailerButtonState()
        // Enrich from the arg title on open — independent of get_series_info, so even
        // dead listings (no episodes) get cast/creator/runtime/ratings.
        enrichFromTmdb(args.getString(ARG_TITLE), null)
    }

    // ── Episodes strip ─────────────────────────────────────────────────────────

    private fun setupEpisodes() {
        adapter = EpisodesAdapterV2(
            onEpisodeClick = { episode -> viewModel.onEpisodeClicked(episode) },
            onEpisodeFocused = { episode ->
                activeEpisode = episode
                lastFocusedEpisodeId = episode.episodeId
                adapter?.setSelectedEpisode(episode.episodeId)
                updatePlayButton(episode)
            }
        ).also { it.seriesId = arguments?.getString(ARG_SERIES_ID) }

        binding.rvEpisodes.layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
        binding.rvEpisodes.adapter = adapter
        binding.rvEpisodes.itemAnimator = null

        pageOpenedAt = android.os.SystemClock.elapsedRealtime()
        // Re-evaluate once the grace window lapses so a genuinely-dead listing flips
        // from the skeleton to "No episodes" even without a new LoadState emission.
        binding.root.postDelayed({
            if (_binding != null) evaluateEpisodesLoadingUi()
        }, EPISODES_GRACE_MS + 250)

        lifecycleScope.launch {
            adapter?.loadStateFlow?.collect { states ->
                lastEpisodeLoadStates = states
                evaluateEpisodesLoadingUi()
            }
        }
    }

    /**
     * Episodes strip loading/empty UI. Room paging reports "empty" instantly on a
     * cold open while get_series_info is still on the wire — so an empty strip only
     * counts as truly empty AFTER the grace window; until then the shimmer skeleton
     * shows so the user can see something is loading.
     */
    private fun evaluateEpisodesLoadingUi() {
        val b = _binding ?: return
        val states = lastEpisodeLoadStates ?: return
        val count = adapter?.itemCount ?: 0
        val loading = states.refresh is LoadState.Loading ||
            states.mediator?.refresh is LoadState.Loading
        val error = states.refresh is LoadState.Error
        val withinGrace =
            android.os.SystemClock.elapsedRealtime() - pageOpenedAt < EPISODES_GRACE_MS
        val emptySettled =
            states.refresh is LoadState.NotLoading && count == 0 && !loading && !withinGrace
        val showSkeleton = count == 0 && !error && !emptySettled
        b.pbLoadingCentral.visibility = View.GONE
        b.llEpisodesLoading.visibility = if (showSkeleton) View.VISIBLE else View.GONE
        if (showSkeleton) startEpisodeShimmer() else stopEpisodeShimmer()
        b.tvEmptyEpisodes.visibility = if (emptySettled) View.VISIBLE else View.GONE
        if (emptySettled) b.tvEmptyEpisodes.text = "No episodes"
    }

    private var episodeShimmerRunning = false
    private var pageOpenedAt = 0L
    private var lastEpisodeLoadStates: CombinedLoadStates? = null

    private fun startEpisodeShimmer() {
        if (episodeShimmerRunning) return
        episodeShimmerRunning = true
        val container = binding.llEpisodesLoading
        for (i in 0 until container.childCount) {
            val shimmer = container.getChildAt(i)
                ?.findViewById<View>(com.tvonnet.debridxtreamiptv.R.id.v_shimmer) ?: continue
            val anim = android.view.animation.TranslateAnimation(
                android.view.animation.Animation.RELATIVE_TO_PARENT, -1.0f,
                android.view.animation.Animation.RELATIVE_TO_PARENT, 1.0f,
                android.view.animation.Animation.RELATIVE_TO_PARENT, 0f,
                android.view.animation.Animation.RELATIVE_TO_PARENT, 0f
            ).apply {
                duration = 1200L + (i * 80L)
                repeatCount = android.view.animation.Animation.INFINITE
                repeatMode = android.view.animation.Animation.RESTART
                interpolator = android.view.animation.LinearInterpolator()
            }
            shimmer.startAnimation(anim)
        }
    }

    private fun stopEpisodeShimmer() {
        if (!episodeShimmerRunning) return
        episodeShimmerRunning = false
        val container = binding.llEpisodesLoading
        for (i in 0 until container.childCount) {
            container.getChildAt(i)
                ?.findViewById<View>(com.tvonnet.debridxtreamiptv.R.id.v_shimmer)?.clearAnimation()
        }
    }

    // ── Stream panel ───────────────────────────────────────────────────────────

    private fun setupStreamPanel() {
        streamAdapter = StreamPanelAdapter(onStreamClick = { option ->
            showToast("CONNECTING…")
            // Panel closes from the ViewModel once the stream resolves; on failure it
            // stays open so the user can pick another category.
            viewModel.onStreamSelected(option)
        })
        binding.rvStreams.adapter = streamAdapter

        binding.viewScrim.setOnClickListener { viewModel.closeStreamPanel() }
        binding.chipQuality.setOnClickListener { viewModel.cycleFilter(FilterKey.QUALITY) }
        binding.chipLanguage.setOnClickListener { viewModel.cycleFilter(FilterKey.LANGUAGE) }
        binding.chipContainer.setOnClickListener { viewModel.cycleFilter(FilterKey.CONTAINER) }
        binding.chipQuality.isFocusable = true
        binding.chipLanguage.isFocusable = true
        binding.chipContainer.isFocusable = true

        // Panel starts off-screen (to the right)
        binding.panelStreams.visibility = View.GONE
        binding.viewScrim.visibility = View.GONE
    }

    private fun setupListeners() {
        binding.btnPlay.setOnClickListener {
            val ep = activeEpisode ?: adapter?.snapshot()?.items?.firstOrNull()
            if (ep != null) viewModel.onEpisodeClicked(ep)
            else Toast.makeText(context, "No episodes available", Toast.LENGTH_SHORT).show()
        }
        setupFavoriteButton()
        binding.btnTrailer.setOnClickListener {
            val trailerValue = resolvedTrailer
            if (trailerValue.isNullOrBlank() || TrailerValueParser.parse(trailerValue) == null) {
                Toast.makeText(context, "No trailer available", Toast.LENGTH_SHORT).show()
            } else {
                startActivity(TrailerActivity.createIntent(requireContext(), trailerValue))
            }
        }
        binding.btnSeasonSelector.setOnClickListener { showSeasonPopup() }
    }

    /** Real favorite toggle for the series (reuses XtreamRepository favorites, type "series"). */
    private fun setupFavoriteButton() {
        val id = arguments?.getString(ARG_SERIES_ID) ?: run {
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
                        showToast("REMOVED FROM FAVORITES")
                    } else {
                        repository.addFavorite(
                            streamId = id,
                            type = "series",
                            name = binding.tvTitle.text?.toString() ?: "Series",
                            iconUrl = com.tvonnet.debridxtreamiptv.util.GlobalConfig.resolveIconUrl(
                                arguments?.getString(ARG_POSTER_URL) ?: arguments?.getString(ARG_BACKDROP_URL)
                            )
                        )
                        showToast("ADDED TO FAVORITES")
                    }
                } catch (e: Exception) {
                    showToast("ERROR: ${e.message}")
                }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                repository.getFavoritesByType("series").collect { favorites ->
                    isFav = favorites.any { it.streamId == id }
                    binding.btnFavorite.setImageResource(
                        if (isFav) R.drawable.ic_favorite else R.drawable.ic_favorite_border
                    )
                }
            }
        }
    }

    private fun setupBackHandling() {
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    viewModel.streamPanel.value.visible -> viewModel.closeStreamPanel()
                    seasonPopup?.isShowing == true -> seasonPopup?.dismiss()
                    else -> {
                        isEnabled = false
                        parentFragmentManager.popBackStack()
                    }
                }
            }
        })
    }

    // ── Observers ──────────────────────────────────────────────────────────────

    private fun setupObservers() {
        // Watched keys → refresh strip
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                watchedStateRepository.observeAllWatchedEpisodeKeys().collectLatest { keys ->
                    val set = keys.toSet()
                    if (adapter?.watchedKeys != set) {
                        adapter?.watchedKeys = set
                        adapter?.notifyItemRangeChanged(0, adapter?.itemCount ?: 0, Any())
                    }
                }
            }
        }

        // In-progress positions → episode-strip resume bars.
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.episodeProgress.collectLatest { progress ->
                    if (adapter?.progressByKey != progress) {
                        adapter?.progressByKey = progress
                        adapter?.notifyItemRangeChanged(0, adapter?.itemCount ?: 0, Any())
                    }
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.uiState.collect { if (it is SeriesDetailUiState.Success) bindMetadata(it) } }
                launch { viewModel.seriesTotals.collect { (seasons, eps) -> bindSeriesTotals(seasons, eps) } }
                launch { viewModel.seasonProgress.collect { (w, t) -> binding.tvSeasonProgress.text = "$w / $t WATCHED" } }
                launch { viewModel.categoryListingCount.collect { bindSummary(it) } }
                launch {
                    viewModel.seasons.collect {
                        currentSeasons = it
                        updateSeasonButton(viewModel.selectedSeason.value)
                    }
                }
                launch { viewModel.selectedSeason.collect { updateSeasonButton(it) } }
                launch {
                    viewModel.episodes.collectLatest { data ->
                        adapter?.submitData(data)
                        if (lastFocusedEpisodeId != null) restoreFocus()
                    }
                }
                launch { viewModel.streamPanel.collect { renderStreamPanel(it) } }
                launch {
                    viewModel.navigationEvents.collect { event ->
                        when (event) {
                            is SeriesNavigationEvent.NavigateToPlayer -> launchPlayer(event)
                            SeriesNavigationEvent.NavigateBack -> parentFragmentManager.popBackStack()
                            is SeriesNavigationEvent.ShowMessage -> Toast.makeText(context, event.msg, Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }
    }

    private fun launchPlayer(event: SeriesNavigationEvent.NavigateToPlayer) {
        val seriesTitle = arguments?.getString(ARG_TITLE) ?: binding.tvTitle.text?.toString()
        val intent = com.tvonnet.debridxtreamiptv.player.stabilized.PlayerActivity.createIntent(
            context = requireContext(),
            streamUrl = event.streamUrl,
            title = event.title,
            channelName = "Series ID: ${event.seriesId}",
            contentId = event.episodeId,
            contentType = com.tvonnet.debridxtreamiptv.data.model.ContentType.EPISODE,
            posterUrl = arguments?.getString(ARG_POSTER_URL) ?: event.posterUrl,
            backdropUrl = null,
            seriesTitle = seriesTitle,
            episodeTitle = event.title,
            seasonNumber = event.seasonNumber,
            episodeNumber = event.episodeNumber,
            seriesId = event.seriesId,
            startPositionMs = event.resumePositionMs
        )
        startActivity(intent)
    }

    // ── Metadata binding ───────────────────────────────────────────────────────

    private fun bindMetadata(state: SeriesDetailUiState.Success) {
        val s = state.series
        seriesPlot = s.plot
        if (!s.youtubeTrailer.isNullOrBlank()) resolvedTrailer = s.youtubeTrailer

        binding.tvTitle.text = s.title ?: s.name ?: binding.tvTitle.text
        binding.tvPlot.text = s.plot?.takeIf { it.isNotBlank() } ?: "No plot available."

        val year = s.year?.takeIf { it.isNotBlank() } ?: s.releaseDate?.take(4)?.takeIf { it.isNotBlank() }
        binding.tvYear.apply {
            text = year; visibility = if (year.isNullOrBlank()) View.GONE else View.VISIBLE
        }
        val genre = s.genre?.takeIf { it.isNotBlank() }
        binding.tvGenre.apply {
            text = genre; visibility = if (genre == null) View.GONE else View.VISIBLE
        }
        val rating = s.rating?.toDoubleOrNull()
        if (rating != null && rating > 0) {
            binding.layoutRating.visibility = View.VISIBLE
            binding.tvRatingPercentage.text = String.format(java.util.Locale.US, "%.1f", rating)
        } else {
            binding.layoutRating.visibility = View.GONE
        }
        updateMetaDots()

        val cover = s.cover
        if (!cover.isNullOrEmpty() && adapter?.seriesCoverUrl != cover) {
            adapter?.seriesCoverUrl = com.tvonnet.debridxtreamiptv.util.GlobalConfig.resolveIconUrl(cover)
            adapter?.notifyItemRangeChanged(0, adapter?.itemCount ?: 0, Any())
        }
        updateTrailerButtonState()
        enrichFromTmdb(s.title ?: s.name, year)
    }

    private fun setupCredits() {
        binding.rvCast.layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
        binding.rvCast.adapter = castAdapter
    }

    /**
     * Full TMDB TV enrichment (parity with the movie page): trailer + cast + creator +
     * runtime + age rating, plus IMDb/RottenTomatoes via OMDb. Runs at most once.
     */
    private fun enrichFromTmdb(title: String?, year: String?) {
        if (enrichStarted) return
        val raw = title?.trim().orEmpty()
        if (raw.isEmpty()) return
        enrichStarted = true
        // Strip |...| tags, then a leading short-code prefix like "PH - ", "SOM - ",
        // "EN | " which the pipe-cleaner leaves behind and which breaks TMDB search.
        val base = com.tvonnet.debridxtreamiptv.util.MediaTitleCleaner.clean(raw).ifBlank { raw }
        val cleanTitle = base
            .replace(Regex("^[A-Za-z]{2,4}\\s*[-–—|:]\\s*"), "")
            .trim()
            .ifBlank { base }
        viewLifecycleOwner.lifecycleScope.launch {
            val details = runCatching { fetchTmdbTvDetails(cleanTitle, year) }.getOrNull() ?: return@launch
            if (_binding == null) return@launch
            bindTmdbEnrichment(details)
        }
    }

    private suspend fun fetchTmdbTvDetails(
        title: String,
        year: String?
    ): com.tvonnet.debridxtreamiptv.data.debrid.model.TmdbTvShowDetails? {
        val search = tmdbRemoteDataSource.searchTvShows(query = title)
        val results = (search as? com.tvonnet.debridxtreamiptv.data.Result.Success)?.data?.results ?: return null
        if (results.isEmpty()) return null
        val best = results.firstOrNull { it.name.equals(title, true) && (year == null || it.firstAirDate?.take(4) == year) }
            ?: results.firstOrNull { it.name.equals(title, true) }
            ?: results.firstOrNull { year != null && it.firstAirDate?.take(4) == year }
            ?: results.first()
        val tvId = best.id ?: return null
        return (tmdbRemoteDataSource.getSeriesDetails(tvId) as? com.tvonnet.debridxtreamiptv.data.Result.Success)?.data
    }

    private fun bindTmdbEnrichment(d: com.tvonnet.debridxtreamiptv.data.debrid.model.TmdbTvShowDetails) {
        // Trailer (only if the provider didn't supply one).
        if (TrailerValueParser.parse(resolvedTrailer) == null) {
            val key = d.videos?.results
                ?.asSequence()
                ?.filter { it.site.equals("YouTube", true) && !it.key.isNullOrBlank() }
                ?.sortedWith(
                    compareByDescending<com.tvonnet.debridxtreamiptv.data.debrid.model.TmdbVideo> { it.type.equals("Trailer", true) }
                        .thenByDescending { it.official == true }
                )
                ?.firstOrNull()?.key
            if (!key.isNullOrBlank()) {
                resolvedTrailer = key
                updateTrailerButtonState()
            }
        }

        // Cast (top-billed 5, with photos).
        val cast = d.credits?.cast?.sortedBy { it.order ?: 99 }?.take(5).orEmpty()
        if (cast.isNotEmpty()) {
            castAdapter.submitList(cast)
            binding.layoutCast.visibility = View.VISIBLE
            binding.layoutCredits.visibility = View.VISIBLE
        }

        // Creator / Director.
        val creator = d.createdBy?.firstOrNull { !it.name.isNullOrBlank() }?.name
            ?: d.credits?.crew?.firstOrNull { it.job.equals("Director", true) }?.name
        if (!creator.isNullOrBlank()) {
            binding.tvDirector.text = creator
            binding.layoutDirector.visibility = View.VISIBLE
            binding.layoutCredits.visibility = View.VISIBLE
        }

        // Episode runtime.
        val runtime = d.episodeRunTime?.firstOrNull { it > 0 }
        if (runtime != null) {
            binding.tvRuntime.text = "${runtime}M"
            binding.tvRuntime.visibility = View.VISIBLE
        }

        // Age / content rating (US → GB → any).
        val cert = d.contentRatings?.results?.let { r ->
            r.firstOrNull { it.countryCode.equals("US", true) }?.rating
                ?: r.firstOrNull { it.countryCode.equals("GB", true) }?.rating
                ?: r.firstOrNull { !it.rating.isNullOrBlank() }?.rating
        }?.takeIf { it.isNotBlank() }
        if (cert != null) {
            binding.tvAgeRating.text = cert
            binding.tvAgeRating.visibility = View.VISIBLE
        }

        // IMDb / Rotten Tomatoes via OMDb.
        d.externalIds?.imdbId?.takeIf { it.isNotBlank() }?.let { loadExternalRatings(it) }
    }

    private fun loadExternalRatings(imdbId: String) {
        viewLifecycleOwner.lifecycleScope.launch {
            val ratings = com.tvonnet.debridxtreamiptv.data.omdb.OmdbClient.fetchRatings(imdbId) ?: return@launch
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

    private fun bindSeriesTotals(seasons: Int, episodes: Int) {
        if (seasons <= 0 && episodes <= 0) {
            binding.tvSeasonEpisodeCount.visibility = View.GONE
        } else {
            binding.tvSeasonEpisodeCount.visibility = View.VISIBLE
            val sLabel = if (seasons == 1) "SEASON" else "SEASONS"
            val eLabel = if (episodes == 1) "EPISODE" else "EPISODES"
            binding.tvSeasonEpisodeCount.text = "$seasons $sLabel · $episodes $eLabel"
        }
        updateMetaDots()
    }

    private fun bindSummary(categoryCount: Int) {
        if (categoryCount >= 2) {
            binding.layoutSummary.visibility = View.VISIBLE
            binding.tvSummary.text = "AVAILABLE IN $categoryCount CATEGORIES · MULTI-SOURCE"
        } else {
            binding.layoutSummary.visibility = View.GONE
        }
    }

    private fun updateMetaDots() {
        val yearVisible = binding.tvYear.visibility == View.VISIBLE
        val countVisible = binding.tvSeasonEpisodeCount.visibility == View.VISIBLE
        val genreVisible = binding.tvGenre.visibility == View.VISIBLE
        binding.tvDot1.visibility = if (yearVisible && countVisible) View.VISIBLE else View.GONE
        binding.tvDot2.visibility = if (countVisible && genreVisible) View.VISIBLE else View.GONE
    }

    private fun updatePlayButton(episode: EpisodeEntityV2) {
        val label = "S%02d · E%02d".format(episode.seasonNumber, episode.episodeNumber)
        // Default label immediately; EpisodeEntityV2.resumePosition is never populated
        // in the V2 flow, so the real progress comes from WatchedStateRepository.
        binding.btnPlay.text = "Play $label"
        viewLifecycleOwner.lifecycleScope.launch {
            val key = com.tvonnet.debridxtreamiptv.data.local.WatchedIdentityBuilder.iptvEpisode(
                episodeId = episode.episodeId,
                seriesId = arguments?.getString(ARG_SERIES_ID),
                seasonNumber = episode.seasonNumber,
                episodeNumber = episode.episodeNumber,
                fallbackDiscriminator = episode.episodeId.ifBlank { episode.title }
            )
            val state = watchedStateRepository.getState(key)
            val resumed = state != null && !state.isWatched && state.progressMs > 0L &&
                (state.durationMs <= 0L || state.progressMs < state.durationMs)
            if (_binding == null) return@launch
            // Ignore stale results if focus moved to another episode meanwhile.
            if (activeEpisode?.episodeId != episode.episodeId) return@launch
            binding.btnPlay.text = if (resumed) "Resume $label" else "Play $label"
        }
    }

    // ── Season selector ────────────────────────────────────────────────────────

    private fun updateSeasonButton(selected: Int?) {
        // Caret matches the Debrid series page (SeriesDetailActivity: "SEASON n ▾").
        binding.btnSeasonSelector.text = "SEASON ${selected ?: currentSeasons.firstOrNull()?.seasonNum ?: 1} ▾"
    }

    private fun showSeasonPopup() {
        val seasons = currentSeasons
        if (seasons.isEmpty()) return
        val ctx = requireContext()
        val selected = viewModel.selectedSeason.value

        val content = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF0E0D15.toInt())
            setPadding(dp(6), dp(6), dp(6), dp(6))
        }
        content.addView(TextView(ctx).apply {
            text = "SELECT SEASON"
            setTextColor(0xFF64748B.toInt())
            textSize = 5f
            typeface = Typeface.MONOSPACE
            letterSpacing = 0.12f
            setPadding(dp(5), dp(3), dp(5), dp(4))
        })

        val popup = PopupWindow(content, dp(118), ViewGroup.LayoutParams.WRAP_CONTENT, true).apply {
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(0xFF0E0D15.toInt()))
            elevation = 12f
        }
        seasonPopup = popup

        seasons.forEach { season ->
            val isActive = season.seasonNum == selected
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(7), 0, dp(7), 0)
                minimumHeight = dp(22)
                isFocusable = true
                isClickable = true
                if (isActive) setBackgroundColor(0x1400F0FF)
                setOnClickListener {
                    viewModel.onSeasonSelected(season.seasonNum)
                    updateSeasonButton(season.seasonNum)
                    popup.dismiss()
                }
                setOnFocusChangeListener { v, hasFocus ->
                    v.setBackgroundColor(if (hasFocus) 0x2400F0FF else if (isActive) 0x1400F0FF else 0x00000000)
                }
            }
            row.addView(TextView(ctx).apply {
                text = "Season ${season.seasonNum}"
                setTextColor(if (isActive) 0xFF00F0FF.toInt() else 0xFFE2E8F0.toInt())
                textSize = 7f
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            if (isActive) {
                row.addView(TextView(ctx).apply {
                    text = "●"
                    setTextColor(0xFF00F0FF.toInt())
                    textSize = 6f
                })
            }
            content.addView(row)
        }

        content.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        popup.showAsDropDown(binding.btnSeasonSelector, 0, -binding.btnSeasonSelector.height - content.measuredHeight, Gravity.START or Gravity.BOTTOM)
    }

    // ── Stream panel rendering ─────────────────────────────────────────────────

    private fun renderStreamPanel(state: StreamPanelState) {
        val panel = binding.panelStreams
        val scrim = binding.viewScrim

        if (!state.visible) {
            if (panel.visibility == View.VISIBLE) {
                panel.animate().translationX(panel.width.toFloat()).setDuration(200).withEndAction {
                    panel.visibility = View.GONE
                    panel.translationX = 0f
                }.start()
                scrim.animate().alpha(0f).setDuration(200).withEndAction { scrim.visibility = View.GONE }.start()
            }
            setBackgroundInteractable(true)
            return
        }

        setBackgroundInteractable(false)
        if (panel.visibility != View.VISIBLE) {
            pendingStreamFocus = true
            panel.visibility = View.VISIBLE
            panel.post {
                panel.translationX = panel.width.toFloat()
                panel.animate().translationX(0f).setDuration(260).start()
            }
            scrim.visibility = View.VISIBLE
            scrim.alpha = 0f
            scrim.animate().alpha(1f).setDuration(200).start()
        }

        binding.tvEpisodeLabel.text = state.episodeLabel
        styleChip(binding.chipQuality, "Quality", state.filters.quality)
        styleChip(binding.chipLanguage, "Language", state.filters.language)
        styleChip(binding.chipContainer, "Format", state.filters.container)

        binding.pbStreams.visibility = if (state.loading) View.VISIBLE else View.GONE

        val groups = state.filteredGroups
        val streamCount = state.filteredStreamCount
        binding.tvScanLine.text = "$streamCount ${if (streamCount == 1) "stream" else "streams"} across " +
            "${groups.size} ${if (groups.size == 1) "category" else "categories"}"

        when {
            state.loading -> {
                binding.tvNoStreams.visibility = View.GONE
                streamAdapter?.submit(emptyList(), null)
            }
            groups.isEmpty() -> {
                binding.tvNoStreams.visibility = View.VISIBLE
                binding.tvNoStreams.text = state.error ?: "NO STREAMS MATCH CURRENT FILTERS"
                streamAdapter?.submit(emptyList(), null)
            }
            else -> {
                binding.tvNoStreams.visibility = View.GONE
                // CC-1: a filter-chip toggle rebuilds the list via notifyDataSetChanged; if the
                // user was inside the stream list, keep their row (or nearest surviving one)
                // instead of dropping focus and forcing them to re-navigate. The initial
                // panel-open focus is still handled explicitly via pendingStreamFocus below.
                binding.rvStreams.updatePreservingFocus {
                    streamAdapter?.submit(groups, state.resolvingStreamId)
                }
                if (pendingStreamFocus) {
                    pendingStreamFocus = false
                    binding.rvStreams.post {
                        val pos = streamAdapter?.firstRowPosition() ?: -1
                        if (pos >= 0) binding.rvStreams.findViewHolderForAdapterPosition(pos)?.itemView?.requestFocus()
                    }
                }
            }
        }
    }

    private fun styleChip(tv: TextView, label: String, value: String) {
        val active = value != "All"
        tv.text = "$label: $value"
        tv.setTextColor(if (active) 0xFF00F0FF.toInt() else 0xFF94A3B8.toInt())
        val fill = if (active) 0x1400F0FF else 0x08FFFFFF
        val stroke = if (active) 0x4000F0FF else 0x12FFFFFF
        tv.background = GradientDrawable().apply {
            setColor(fill)
            cornerRadius = dp(4).toFloat()
            setStroke(dp(1), stroke)
        }
    }

    /** Prevent background focus escaping while the stream panel is open. */
    private fun setBackgroundInteractable(enabled: Boolean) {
        binding.btnPlay.isFocusable = enabled
        binding.btnTrailer.isFocusable = enabled
        binding.btnFavorite.isFocusable = enabled
        binding.btnSeasonSelector.isFocusable = enabled
        binding.rvEpisodes.descendantFocusability =
            if (enabled) ViewGroup.FOCUS_AFTER_DESCENDANTS else ViewGroup.FOCUS_BLOCK_DESCENDANTS
    }

    private fun restoreFocus() {
        val targetId = lastFocusedEpisodeId ?: return
        binding.rvEpisodes.post {
            val snapshot = adapter?.snapshot()?.items ?: return@post
            val position = snapshot.indexOfFirst { it.episodeId == targetId }
            if (position != -1) {
                binding.rvEpisodes.scrollToPosition(position)
                binding.rvEpisodes.post {
                    binding.rvEpisodes.findViewHolderForAdapterPosition(position)?.itemView?.requestFocus()
                }
            }
        }
    }

    private fun updateTrailerButtonState() {
        if (_binding == null) return
        val hasTrailer = TrailerValueParser.parse(resolvedTrailer) != null
        binding.btnTrailer.isEnabled = hasTrailer
        binding.btnTrailer.alpha = if (hasTrailer) 1.0f else 0.45f
    }

    private fun showToast(message: String, next: String? = null) {
        val handler = binding.layoutToast.handler ?: android.os.Handler(android.os.Looper.getMainLooper())
        toastRunnable?.let { handler.removeCallbacks(it) }
        binding.tvToast.text = message
        binding.layoutToast.visibility = View.VISIBLE
        toastRunnable = Runnable {
            if (next != null) showToast(next, null)
            else binding.layoutToast.visibility = View.GONE
        }
        handler.postDelayed(toastRunnable!!, 1500)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onDestroyView() {
        super.onDestroyView()
        seasonPopup?.dismiss()
        seasonPopup = null
        toastRunnable = null
        _binding = null
    }
}
