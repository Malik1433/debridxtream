package com.tvonnet.debridxtreamiptv.ui.favorites

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.tvonnet.debridxtreamiptv.R
import com.tvonnet.debridxtreamiptv.data.local.entity.FavoriteEntity
import com.tvonnet.debridxtreamiptv.data.model.ContentType
import com.tvonnet.debridxtreamiptv.player.stabilized.PlayerActivity
import com.tvonnet.debridxtreamiptv.utils.RecyclerViewAnimations
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

/**
 * FavoritesFragment - Week 10: Favorites System
 * 
 * Displays user's favorited content with filtering options
 */
@AndroidEntryPoint
class FavoritesFragment : Fragment() {
    
    private val viewModel: FavoritesViewModel by viewModels()
    
    private lateinit var rvFavorites: RecyclerView
    private lateinit var progressBar: ProgressBar
    private lateinit var tvEmptyState: TextView
    private lateinit var tvError: TextView
    private lateinit var filterAll: TextView
    private lateinit var filterLive: TextView
    private lateinit var filterVod: TextView
    private lateinit var filterSeries: TextView
    private lateinit var btnClearAll: Button
    
    private val favoritesAdapter by lazy {
        FavoritesAdapter(
            onItemClick = { favorite -> handleFavoriteClick(favorite) },
            onRemoveClick = { favorite -> 
                viewModel.onEvent(FavoritesEvent.RemoveFavorite(favorite.streamId))
            }
        )
    }
    
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_favorites, container, false)
    }
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        // Initialize views
        rvFavorites = view.findViewById(R.id.rv_favorites)
        progressBar = view.findViewById(R.id.progress_bar)
        tvEmptyState = view.findViewById(R.id.tv_empty_state)
        tvError = view.findViewById(R.id.tv_error)
        filterAll = view.findViewById(R.id.filter_all)
        filterLive = view.findViewById(R.id.filter_live)
        filterVod = view.findViewById(R.id.filter_vod)
        filterSeries = view.findViewById(R.id.filter_series)
        btnClearAll = view.findViewById(R.id.btn_clear_all)
        
        setupRecyclerView()
        setupFilters()
        setupClearButton()
        observeViewModel()
    }
    
    private fun setupRecyclerView() {
        rvFavorites.apply {
            layoutManager = GridLayoutManager(context, 4)
            adapter = favoritesAdapter
            setHasFixedSize(true)
        }
        
        // Week 14: Apply smooth animations
        RecyclerViewAnimations.applyAnimations(rvFavorites)
    }
    
    private fun setupFilters() {
        filterAll.setOnClickListener {
            viewModel.onEvent(FavoritesEvent.FilterByType(FavoriteFilter.ALL))
            updateFilterSelection(FavoriteFilter.ALL)
        }
        
        filterLive.setOnClickListener {
            viewModel.onEvent(FavoritesEvent.FilterByType(FavoriteFilter.LIVE))
            updateFilterSelection(FavoriteFilter.LIVE)
        }
        
        filterVod.setOnClickListener {
            viewModel.onEvent(FavoritesEvent.FilterByType(FavoriteFilter.VOD))
            updateFilterSelection(FavoriteFilter.VOD)
        }
        
        filterSeries.setOnClickListener {
            viewModel.onEvent(FavoritesEvent.FilterByType(FavoriteFilter.SERIES))
            updateFilterSelection(FavoriteFilter.SERIES)
        }
        
        // Set initial selection
        updateFilterSelection(FavoriteFilter.ALL)
    }
    
    private fun setupClearButton() {
        btnClearAll.setOnClickListener {
            // Show confirmation dialog
            androidx.appcompat.app.AlertDialog.Builder(requireContext())
                .setTitle("Clear All Favorites")
                .setMessage("Are you sure you want to remove all favorites?")
                .setPositiveButton("Yes") { _, _ ->
                    viewModel.onEvent(FavoritesEvent.ClearAll)
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }
    
    private fun updateFilterSelection(selectedFilter: FavoriteFilter) {
        val filters = listOf(filterAll, filterLive, filterVod, filterSeries)
        filters.forEach { it.isSelected = false }
        
        when (selectedFilter) {
            FavoriteFilter.ALL -> filterAll.isSelected = true
            FavoriteFilter.LIVE -> filterLive.isSelected = true
            FavoriteFilter.VOD -> filterVod.isSelected = true
            FavoriteFilter.SERIES -> filterSeries.isSelected = true
        }
    }
    
    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.uiState.collect { state ->
                updateUI(state)
            }
        }
    }
    
    private fun updateUI(state: FavoritesUiState) {
        // Loading state
        progressBar.visibility = if (state.isLoading) View.VISIBLE else View.GONE
        
        // Error state
        if (state.error != null) {
            tvError.visibility = View.VISIBLE
            tvError.text = state.error
        } else {
            tvError.visibility = View.GONE
        }
        
        // Empty state
        if (state.favorites.isEmpty() && !state.isLoading) {
            tvEmptyState.visibility = View.VISIBLE
            rvFavorites.visibility = View.GONE
            btnClearAll.isEnabled = false
        } else {
            tvEmptyState.visibility = View.GONE
            rvFavorites.visibility = View.VISIBLE
            btnClearAll.isEnabled = state.favorites.isNotEmpty()
        }
        
        // Update adapter
        favoritesAdapter.submitList(state.favorites)
    }
    
    private fun handleFavoriteClick(favorite: FavoriteEntity) {
        // Week 12: Implement playback for favorites
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                // Get repository from viewModel or inject via Hilt
                val repository = viewModel.getRepository()
                val credentialsPrefs = com.tvonnet.debridxtreamiptv.data.prefs.CredentialsPreferences(requireContext())
                val serverUrl = credentialsPrefs.getServerUrl() ?: ""
                
                if (serverUrl.isEmpty()) {
                    showError("Server URL not found. Please login again.")
                    return@launch
                }
                
                when (favorite.type) {
                    "live" -> {
                        // Look up live stream from cache
                        val stream = repository.getLiveStreamById(favorite.streamId)
                        if (stream != null) {
                            val streamUrl = repository.buildLiveStreamUrl(stream, serverUrl)
                            val epgId = stream.epg_channel_id?.takeIf { it.isNotBlank() }
                                ?: stream.stream_id?.toString()
                            playStream(
                                streamUrl = streamUrl,
                                title = favorite.name,
                                channelName = stream.name ?: favorite.name,
                                channelLogo = com.tvonnet.debridxtreamiptv.util.GlobalConfig.resolveIconUrl(stream.stream_icon),
                                epgChannelId = epgId,
                                contentId = favorite.streamId,
                                contentType = ContentType.LIVE_TV,
                                posterUrl = com.tvonnet.debridxtreamiptv.util.GlobalConfig.resolveIconUrl(stream.stream_icon)
                            )
                        } else {
                            showError("Live channel not found in cache")
                        }
                    }
                    "vod" -> {
                        // Look up VOD from cache
                        val vod = repository.getVodById(favorite.streamId)
                        if (vod != null) {
                        val streamUrl = repository.buildVodStreamUrl(vod, serverUrl)
                        playStream(
                            streamUrl = streamUrl,
                            title = favorite.name,
                            contentId = favorite.streamId,
                            contentType = ContentType.MOVIE,
                            posterUrl = com.tvonnet.debridxtreamiptv.util.GlobalConfig.resolveIconUrl(vod.stream_icon),
                            backdropUrl = vod.cover
                        )
                        } else {
                            showError("Movie not found in cache")
                        }
                    }
                    "series" -> {
                        // For series, we would navigate to series details
                        // For now, show a toast
                        Toast.makeText(
                            requireContext(),
                            "Series playback: ${favorite.name}. Navigate to series details to select episode.",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    else -> {
                        showError("Unknown content type: ${favorite.type}")
                    }
                }
            } catch (e: Exception) {
                showError("Failed to play: ${e.message}")
            }
        }
    }
    
    private fun playStream(
        streamUrl: String,
        title: String,
        channelName: String? = null,
        channelLogo: String? = null,
        epgChannelId: String? = null,
        contentId: String? = null,
        contentType: ContentType? = null,
        posterUrl: String? = null,
        backdropUrl: String? = null,
        startPosition: Long? = null
    ) {
        val intent = PlayerActivity.createIntent(
            context = requireContext(),
            streamUrl = streamUrl,
            title = title,
            channelName = channelName,
            channelLogo = channelLogo,
            epgChannelId = epgChannelId,
            startPositionMs = startPosition,
            contentId = contentId,
            contentType = contentType,
            posterUrl = posterUrl,
            backdropUrl = backdropUrl
        )
        startActivity(intent)
    }
    
    private fun showError(message: String) {
        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
    }
}
