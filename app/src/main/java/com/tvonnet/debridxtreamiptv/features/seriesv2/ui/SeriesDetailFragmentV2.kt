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
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.tvonnet.debridxtreamiptv.databinding.FragmentSeriesDetailV2Binding
import com.tvonnet.debridxtreamiptv.features.seriesv2.ui.model.SeriesDetailUiState
import com.tvonnet.debridxtreamiptv.features.seriesv2.ui.model.SeriesNavigationEvent
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

    private val viewModel: SeriesDetailViewModelV2 by viewModels()
    private var adapter: EpisodesAdapterV2? = null
    private var seasonsAdapter: SeasonsAdapterV2? = null

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
            val title = args.getString("title")
            val backdropUrl = args.getString("backdrop_url")
            val posterUrl = args.getString("poster_url")

            binding.tvTitle.text = title ?: "Loading..."
            
            if (!backdropUrl.isNullOrEmpty()) {
                Glide.with(this)
                    .load(backdropUrl)
                    .dontAnimate() // Important for instant feel
                    .into(binding.ivBackdrop)
            }
            
            // Poster logic removed for Cinematic Layout
        }
    }

    private fun setupRecyclerView() {
        // Episodes Adapter
        adapter = EpisodesAdapterV2 { episode ->
            viewModel.onEpisodeClicked(episode)
        }
        
        binding.rvEpisodes.layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
        binding.rvEpisodes.adapter = adapter

        // Seasons Adapter
        seasonsAdapter = SeasonsAdapterV2 { seasonNum ->
            viewModel.onSeasonSelected(seasonNum)
        }
        binding.rvSeasons.layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
        binding.rvSeasons.adapter = seasonsAdapter

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
                    // #region agent log
                    android.util.Log.d("SeriesDetailFragmentV2", "Episodes submitted to adapter, adapter itemCount=${adapter?.itemCount}")
                    // #endregion
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
                        seasonsAdapter?.selectedSeason = selected
                        seasonsAdapter?.notifyDataSetChanged() // Force refresh selection UI
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
                            val seriesTitle = arguments?.getString("title")
                                ?: binding.tvTitle.text?.toString()
                            val intent = com.tvonnet.debridxtreamiptv.player.PlayerActivity.createIntent(
                                context = requireContext(),
                                streamUrl = event.streamUrl,
                                title = event.title,
                                channelName = "Series ID: ${event.seriesId}", // Optional context
                                contentId = event.episodeId,
                                contentType = com.tvonnet.debridxtreamiptv.data.model.ContentType.EPISODE,
                                posterUrl = event.posterUrl,
                                backdropUrl = null,
                                seriesTitle = seriesTitle,
                                episodeTitle = event.title,
                                seasonNumber = event.seasonNumber,
                                episodeNumber = event.episodeNumber
                            ).apply {
                                putExtra("EXTRA_SERIES_ID", event.seriesId)
                                putExtra("EXTRA_SEASON_NUM", event.seasonNumber)
                            }
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
    
    private fun bindMetadata(state: SeriesDetailUiState.Success) {
        val s = state.series
        binding.tvTitle.text = s.title ?: s.name
        binding.tvPlot.text = s.plot ?: "No plot available."
        
        // Modern Pill Binding
        binding.tvYear?.text = s.year ?: "N/A"
        binding.tvGenre?.text = s.genre ?: "Genre"
        
        // Rating with Star
        val ratingText = s.rating ?: ""
        binding.tvRating?.text = if (ratingText.isNotEmpty()) "★ $ratingText" else ""
        binding.tvRating?.visibility = if (ratingText.isNotEmpty()) View.VISIBLE else View.GONE
        
        
        // Fix 3: Pass cover to adapter for fallback
        if (!s.cover.isNullOrEmpty() && adapter?.seriesCoverUrl != s.cover) {
            adapter?.seriesCoverUrl = s.cover
            // Force re-bind of visible items to update thumbnails if they were placeholders
            adapter?.notifyItemRangeChanged(0, adapter?.itemCount ?: 0, Any()) 
        }
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
            Toast.makeText(context, "Added to Favorites (Demo)", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
