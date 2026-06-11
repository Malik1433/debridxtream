package com.tvonnet.debridxtreamiptv.features.seriesv2.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.paging.LoadState
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.tvonnet.debridxtreamiptv.databinding.FragmentSeriesDetailV2Binding
import com.tvonnet.debridxtreamiptv.features.seriesv2.ui.model.SeriesDetailUiState
import com.tvonnet.debridxtreamiptv.features.seriesv2.ui.model.SeriesNavigationEvent
import com.tvonnet.debridxtreamiptv.ui.trailer.TrailerActivity
import com.tvonnet.debridxtreamiptv.ui.trailer.TrailerValueParser
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * SeriesDetailFragmentV2
 * Phase 3: High Performance Series Engine
 * 
 * "Zero Latency" architecture.
 */
@AndroidEntryPoint
class SeriesDetailFragmentV2 : Fragment() {

    private var _binding: FragmentSeriesDetailV2Binding? = null
    private val binding get() = _binding!!

    @javax.inject.Inject
    lateinit var watchedStateRepository: com.tvonnet.debridxtreamiptv.data.repository.WatchedStateRepository

    private val viewModel: SeriesDetailViewModelV2 by viewModels()
    private var adapter: EpisodesAdapterV2? = null
    private var seasonsAdapter: SeasonsAdapterV2? = null
    
    // Focus Tracking
    private var lastFocusedEpisodeId: String? = null
    private var lastFocusedSeasonNum: Int? = null
    private var isRestoringFocus = false
    private var seriesPlot: String? = null
    private var resolvedTrailer: String? = null
    
    // Top Badge State
    private var currentRating: String = ""
    private var currentWatchedState: SeriesWatchedState = SeriesWatchedState.UNWATCHED

    companion object {
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
        ): SeriesDetailFragmentV2 {
            return SeriesDetailFragmentV2().apply {
                arguments = Bundle().apply {
                    putString(ARG_SERIES_ID, seriesId)
                    putString(ARG_TITLE, title)
                    putString(ARG_BACKDROP_URL, backdropUrl)
                    putString(ARG_POSTER_URL, posterUrl)
                    putString(ARG_TRAILER, trailer)
                }
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSeriesDetailV2Binding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        setupOptimisticUI()
        setupRecyclerView()
        setupObservers()
        setupListeners()
    }

    private fun setupOptimisticUI() {
        // FR-UI-01: 0ms Latency navigation. 
        // We render what we know immediately from arguments, without waiting for VM/Network.
        val args = arguments
        if (args != null) {
            val title = args.getString(ARG_TITLE)
            val backdropUrl = args.getString(ARG_BACKDROP_URL)
            val posterUrl = args.getString(ARG_POSTER_URL)
            resolvedTrailer = args.getString(ARG_TRAILER)

            binding.tvTitle.text = title ?: "Loading..."
            
            if (!backdropUrl.isNullOrEmpty()) {
                Glide.with(this)
                    .load(backdropUrl)
                    .dontAnimate() // Important for instant feel
                    .into(binding.ivBackdrop)
            }
            
            // Poster logic removed for Cinematic Layout
        }
        updateTrailerButtonState()
    }

    private fun setupRecyclerView() {
        adapter = EpisodesAdapterV2(
            onEpisodeClick = { episode ->
                viewModel.onEpisodeClicked(episode)
            },
            onEpisodeFocused = { episode ->
                // Update header with episode info
                binding.tvEpisodeMeta.text = "Episode ${episode.episodeNumber} • ${episode.title}"
                binding.tvEpisodeMeta.visibility = View.VISIBLE
                binding.tvPlot.text = episode.plot ?: seriesPlot ?: ""
                
                // Track for restoration
                lastFocusedEpisodeId = episode.episodeId
            }
        )
        adapter?.seriesId = arguments?.getString(ARG_SERIES_ID)
        binding.rvEpisodes.layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
        binding.rvEpisodes.adapter = adapter

        seasonsAdapter = SeasonsAdapterV2 { seasonNum ->
            viewModel.onSeasonSelected(seasonNum)
        }
        binding.rvSeasons.layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
        binding.rvSeasons.adapter = seasonsAdapter

        // Null Animators: Prevent focus jitter during updates
        binding.rvEpisodes.itemAnimator = null
        binding.rvSeasons.itemAnimator = null

        // Handle Loading State based on Paging LoadState
        lifecycleScope.launch {
            adapter?.loadStateFlow?.collect { loadStates ->
                val isListEmpty = loadStates.refresh is LoadState.NotLoading && adapter?.itemCount == 0
                // Check ALL loading states: Refresh, Append, Source Refresh, Source Append, Mediator Refresh
                val isLoading = loadStates.refresh is LoadState.Loading || 
                                loadStates.append is LoadState.Loading ||
                                loadStates.source.refresh is LoadState.Loading ||
                                loadStates.source.append is LoadState.Loading ||
                                loadStates.mediator?.refresh is LoadState.Loading

                // Only show big skeleton if we have NO items
                binding.layoutSkeleton.visibility = if (isLoading && adapter?.itemCount == 0) View.VISIBLE else View.GONE
                binding.rvEpisodes.visibility = if (isLoading && adapter?.itemCount == 0) View.GONE else View.VISIBLE
                
                // User Request: Always show explicit spinner when loading, OR if list is empty and we are strictly waiting (not error)
                val isReallyLoading = isLoading || (adapter?.itemCount == 0 && loadStates.refresh !is LoadState.Error)
                binding.pbLoadingCentral.visibility = if (isReallyLoading) View.VISIBLE else View.GONE
                
                // Show error state? (For now just Toast)
                val errorState = loadStates.source.refresh as? LoadState.Error
                    ?: loadStates.source.prepend as? LoadState.Error
                    ?: loadStates.source.append as? LoadState.Error
                    ?: loadStates.refresh as? LoadState.Error
                
                errorState?.let {
                    Toast.makeText(context, "\uD83D\uDEAB Error: ${it.error.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun setupObservers() {
        // Watched State Stream
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                watchedStateRepository.observeAllWatchedEpisodeKeys().collectLatest { keys ->
                    val watchedSet = keys.toSet()
                    if (adapter?.watchedKeys != watchedSet) {
                        adapter?.watchedKeys = watchedSet
                        // Force rebind of visible items to update checkmarks
                        adapter?.notifyItemRangeChanged(0, adapter?.itemCount ?: 0, Any())
                    }
                }
            }
        }

        // Series Watched State Stream
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.seriesWatchedState.collect { state ->
                    currentWatchedState = state
                    updateTopBadge()
                }
            }
        }

        // UI State (Metadata)
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    when (state) {
                        is SeriesDetailUiState.Success -> {
                            bindMetadata(state)
                        }
                        is SeriesDetailUiState.Error -> {
                            // Handled via LoadState usually, but VM might have specific logic
                        }
                        SeriesDetailUiState.Loading -> {
                            // Optimistic UI handles header, but we must show skeleton for content
                            if (adapter?.itemCount == 0) {
                                binding.layoutSkeleton.visibility = View.VISIBLE
                                binding.rvEpisodes.visibility = View.GONE
                                binding.pbLoadingCentral.visibility = View.VISIBLE 
                            }
                        }
                    }
                }
            }
        }

        // Episodes Stream (Separate from UiState for Paging)
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.episodes.collectLatest { pagingData ->
                    // #region agent log
                    val itemCount = pagingData.toString().length // Approximate check
                    android.util.Log.d("SeriesDetailFragmentV2", "Episodes pagingData collected, submitting to adapter")
                    // #endregion
                    adapter?.submitData(pagingData)
                    
                    // Restore focus if we have a target
                    if (lastFocusedEpisodeId != null) {
                        restoreFocusDetails()
                    }
                }
            }
        }
        
        // Seasons Stream
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.seasons.collect { seasons ->
                        if (seasons.isNotEmpty()) {
                            binding.rvSeasons.visibility = View.VISIBLE
                            val wasEmpty = seasonsAdapter?.currentList.isNullOrEmpty()
                            seasonsAdapter?.submitList(seasons) {
                                // If list was empty, and we just loaded, scroll to first? 
                                // Adapter handles it.
                            }
                        } else {
                            binding.rvSeasons.visibility = View.GONE
                        }
                    }
                }
                launch {
                    viewModel.selectedSeason.collect { selected ->
                        val oldSeason = seasonsAdapter?.selectedSeason
                        if (oldSeason != selected) {
                            seasonsAdapter?.selectedSeason = selected
                            
                            // Surgical Update: Only update the affected items
                            val oldPos = seasonsAdapter?.currentList?.indexOfFirst { it.seasonNum == oldSeason } ?: -1
                            val newPos = seasonsAdapter?.currentList?.indexOfFirst { it.seasonNum == selected } ?: -1
                            
                            if (oldPos != -1) seasonsAdapter?.notifyItemChanged(oldPos)
                            if (newPos != -1) seasonsAdapter?.notifyItemChanged(newPos)
                            
                            lastFocusedSeasonNum = selected
                        }
                    }
                }
            }
        }

        // Navigation Events
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.navigationEvents.collect { event ->
                    when (event) {
                        is SeriesNavigationEvent.NavigateToPlayer -> {
                            val seriesTitle = arguments?.getString(ARG_TITLE)
                                ?: binding.tvTitle.text?.toString()
                            val intent = com.tvonnet.debridxtreamiptv.player.stabilized.PlayerActivity.createIntent(
                                context = requireContext(),
                                streamUrl = event.streamUrl,
                                title = event.title,
                                channelName = "Series ID: ${event.seriesId}", // Optional context
                                contentId = event.episodeId,
                                contentType = com.tvonnet.debridxtreamiptv.data.model.ContentType.EPISODE,
                                posterUrl = arguments?.getString(ARG_POSTER_URL) ?: event.posterUrl,
                                backdropUrl = null,
                                seriesTitle = seriesTitle,
                                episodeTitle = event.title,
                                seasonNumber = event.seasonNumber,
                                episodeNumber = event.episodeNumber,
                                seriesId = event.seriesId
                            )
                            startActivity(intent)
                        }
                        SeriesNavigationEvent.NavigateBack -> {
                            parentFragmentManager.popBackStack() 
                        }
                        is SeriesNavigationEvent.ShowMessage -> {
                            Toast.makeText(context, event.msg, Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }
    }
    
    private fun updateTopBadge() {
        val parts = mutableListOf<String>()
        if (currentRating.isNotEmpty()) parts.add(currentRating)
        
        when (currentWatchedState) {
            SeriesWatchedState.WATCHED -> parts.add("WATCHED")
            SeriesWatchedState.IN_PROGRESS -> parts.add("IN-PROGRESS")
            SeriesWatchedState.UNWATCHED -> {}
        }
        
        if (parts.isNotEmpty()) {
            binding.tvRatingBadge.text = parts.joinToString(" • ")
            binding.tvRatingBadge.visibility = View.VISIBLE
        } else {
            binding.tvRatingBadge.visibility = View.GONE
        }
    }

    private fun bindMetadata(state: SeriesDetailUiState.Success) {
        val s = state.series
        seriesPlot = s.plot
        if (!s.youtubeTrailer.isNullOrBlank()) {
            resolvedTrailer = s.youtubeTrailer
        }
        binding.tvTitle.text = s.title ?: s.name
        binding.tvPlot.text = s.plot ?: "No plot available."
        
        // Single row meta row: Genre • Year
        val genreText = s.genre ?: ""
        val yearText = s.year ?: ""
        val metaRowText = listOf(genreText, yearText).filter { it.isNotEmpty() }.joinToString(" • ")
        binding.tvGenreYear.text = metaRowText
        
        // Rating Badge
        currentRating = s.rating ?: ""
        updateTopBadge()
        
        // Fix 3: Pass cover to adapter for fallback
        if (!s.cover.isNullOrEmpty() && adapter?.seriesCoverUrl != s.cover) {
            adapter?.seriesCoverUrl = com.tvonnet.debridxtreamiptv.util.GlobalConfig.resolveIconUrl(s.cover)
            // Force re-bind of visible items to update thumbnails if they were placeholders
            adapter?.notifyItemRangeChanged(0, adapter?.itemCount ?: 0, Any()) 
        }
        updateTrailerButtonState()
    }


    private fun setupListeners() {
        binding.btnBack.setOnClickListener {
            parentFragmentManager.popBackStack()
        }

        binding.btnPlay?.setOnClickListener {
            // "Watch Now" Logic: Play the first available episode or resume
            adapter?.snapshot()?.items?.firstOrNull()?.let { firstEpisode ->
                viewModel.onEpisodeClicked(firstEpisode)
            } ?: run {
                Toast.makeText(context, "No episodes available", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnFavorite?.setOnClickListener {
            Toast.makeText(context, "Added to favorites", Toast.LENGTH_SHORT).show()
        }

        binding.btnTrailer.setOnClickListener {
            val trailerValue = resolvedTrailer
            if (TrailerValueParser.parse(trailerValue) == null || trailerValue.isNullOrBlank()) {
                Toast.makeText(context, "No trailer available", Toast.LENGTH_SHORT).show()
            } else {
                startActivity(TrailerActivity.createIntent(requireContext(), trailerValue))
            }
        }
    }

    private fun updateTrailerButtonState() {
        if (_binding == null) return
        val hasTrailer = TrailerValueParser.parse(resolvedTrailer) != null
        binding.btnTrailer.isEnabled = hasTrailer
        binding.btnTrailer.alpha = if (hasTrailer) 1.0f else 0.45f
    }

    private fun restoreFocusDetails() {
        val targetId = lastFocusedEpisodeId ?: return
        
        binding.rvEpisodes.post {
            val snapshot = adapter?.snapshot()?.items ?: return@post
            val position = snapshot.indexOfFirst { it.episodeId == targetId }
            
            if (position != -1) {
                isRestoringFocus = true
                binding.rvEpisodes.scrollToPosition(position)
                binding.rvEpisodes.post {
                    val holder = binding.rvEpisodes.findViewHolderForAdapterPosition(position)
                    holder?.itemView?.requestFocus()
                    isRestoringFocus = false
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
