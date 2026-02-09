package com.tvonnet.debridxtreamiptv.ui.debrid

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.tvonnet.debridxtreamiptv.R
import com.tvonnet.debridxtreamiptv.data.onFailure
import com.tvonnet.debridxtreamiptv.data.onSuccess
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Debrid section fragment - main hub for Real-Debrid content browsing
 * 
 * Features:
 * - Device-code authentication flow
 * - Trending Movies/Series rows
 * - Continue Watching integration
 * - My Library
 * - Customizable content rows
 */
@AndroidEntryPoint
class DebridFragment : Fragment() {
    
    private val viewModel: DebridViewModel by viewModels()

    @Inject
    lateinit var debridPlaybackRepository: com.tvonnet.debridxtreamiptv.data.debrid.repository.DebridPlaybackRepository
    
    // UI components
    private lateinit var progressBar: ProgressBar
    private lateinit var errorContainer: View
    private lateinit var errorText: TextView
    private lateinit var refreshBanner: View
    private lateinit var refreshProgress: ProgressBar
    private lateinit var refreshText: TextView
    private lateinit var loginPrompt: View
    private lateinit var contentArea: View
    private lateinit var rvDebridRows: RecyclerView
    
    // Adapter
    private lateinit var debridRowsAdapter: DebridRowsAdapter
    
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_debrid, container, false)
    }
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        initializeViews(view)
        setupRecyclerView()
        observeViewModel()
        
        // Check auth status and load content or show login
        viewModel.checkAuthStatus()
    }
    
    private fun initializeViews(view: View) {
        progressBar = view.findViewById(R.id.progress_bar)
        errorContainer = view.findViewById(R.id.error_container)
        errorText = view.findViewById(R.id.tv_error)
        refreshBanner = view.findViewById(R.id.refresh_banner)
        refreshProgress = view.findViewById(R.id.refresh_progress)
        refreshText = view.findViewById(R.id.refresh_text)
        loginPrompt = view.findViewById(R.id.login_prompt_container)
        contentArea = view.findViewById(R.id.content_area)
        rvDebridRows = view.findViewById(R.id.rv_debrid_rows)
        
        // Setup login button
        view.findViewById<View>(R.id.btn_login_debrid)?.setOnClickListener {
            navigateToLogin()
        }
        view.findViewById<View>(R.id.btn_retry_debrid)?.setOnClickListener {
            viewModel.loadCatalog(force = true)
        }
        
        // Setup Search button
        view.findViewById<View>(R.id.btn_search_debrid)?.setOnClickListener {
            val intent = android.content.Intent(requireContext(), com.tvonnet.debridxtreamiptv.ui.debrid.search.DebridSearchActivity::class.java)
            startActivity(intent)
        }
    }
    
    private fun setupRecyclerView() {
        debridRowsAdapter = DebridRowsAdapter(
            onItemClick = { item -> onContentItemClick(item) },
            onRowLoadMore = { rowId -> viewModel.loadNextPage(rowId) }
        )
        
        rvDebridRows.apply {
            layoutManager = LinearLayoutManager(context)
            adapter = debridRowsAdapter
            setHasFixedSize(true)
        }
    }
    
    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.uiState.collect { state ->
                updateUI(state)
            }
        }
    }
    
    private fun updateUI(state: DebridUiState) {
        when (state) {
            is DebridUiState.Loading -> {
                progressBar.visibility = View.VISIBLE
                errorContainer.visibility = View.GONE
                refreshBanner.visibility = View.GONE
                loginPrompt.visibility = View.GONE
                contentArea.visibility = View.GONE
            }
            is DebridUiState.NotAuthenticated -> {
                progressBar.visibility = View.GONE
                errorContainer.visibility = View.GONE
                refreshBanner.visibility = View.GONE
                loginPrompt.visibility = View.VISIBLE
                contentArea.visibility = View.GONE
            }
            is DebridUiState.Authenticated -> {
                progressBar.visibility = View.GONE
                errorContainer.visibility = View.GONE
                refreshBanner.visibility = View.GONE
                loginPrompt.visibility = View.GONE
                contentArea.visibility = View.VISIBLE
                
                // Load catalog content
                viewModel.loadCatalog()
            }
            is DebridUiState.Content -> {
                progressBar.visibility = View.GONE
                errorContainer.visibility = View.GONE
                loginPrompt.visibility = View.GONE
                contentArea.visibility = View.VISIBLE
                
                debridRowsAdapter.updateRows(state.rows)
                updateRefreshBanner(state.refreshState)
            }
            is DebridUiState.Error -> {
                progressBar.visibility = View.GONE
                errorContainer.visibility = View.VISIBLE
                refreshBanner.visibility = View.GONE
                errorText.text = state.message
                loginPrompt.visibility = View.GONE
                contentArea.visibility = View.GONE
            }
        }
    }
    
    private fun navigateToLogin() {
        // Navigate to DebridAuthFragment
        val authFragment = DebridAuthFragment()
        parentFragmentManager.beginTransaction()
            .replace(R.id.content_container, authFragment)
            .addToBackStack(null)
            .commit()
    }
    
    private fun onContentItemClick(item: DebridContentItem) {
        val context = requireContext()
        if (item.isContinueWatching && tryResumeDebrid(item)) {
            return
        }
        if (item.type == "movie") {
            val intent = android.content.Intent(context, com.tvonnet.debridxtreamiptv.ui.vod.MovieDetailActivity::class.java).apply {
                putExtra(com.tvonnet.debridxtreamiptv.ui.vod.MovieDetailActivity.EXTRA_MOVIE_ID, item.id)
                putExtra(com.tvonnet.debridxtreamiptv.ui.vod.MovieDetailActivity.EXTRA_MOVIE_NAME, item.title)
                putExtra(com.tvonnet.debridxtreamiptv.ui.vod.MovieDetailActivity.EXTRA_MOVIE_ICON, item.posterUrl)
                putExtra(com.tvonnet.debridxtreamiptv.ui.vod.MovieDetailActivity.EXTRA_MOVIE_BACKDROP, item.backdropUrl)
                putExtra(com.tvonnet.debridxtreamiptv.ui.vod.MovieDetailActivity.EXTRA_MOVIE_YEAR, item.year)
                putExtra(com.tvonnet.debridxtreamiptv.ui.vod.MovieDetailActivity.EXTRA_MOVIE_RATING, item.rating)
                putExtra(com.tvonnet.debridxtreamiptv.ui.vod.MovieDetailActivity.EXTRA_MOVIE_CATEGORY_ID, "debrid")
            }
            startActivity(intent)
        } else if (item.type == "show" || item.type == "series") {
            val intent = android.content.Intent(context, com.tvonnet.debridxtreamiptv.ui.series.SeriesDetailActivity::class.java).apply {
                putExtra(com.tvonnet.debridxtreamiptv.ui.series.SeriesDetailActivity.EXTRA_SERIES_ID, item.id)
                putExtra(com.tvonnet.debridxtreamiptv.ui.series.SeriesDetailActivity.EXTRA_SERIES_NAME, item.title)
                putExtra(com.tvonnet.debridxtreamiptv.ui.series.SeriesDetailActivity.EXTRA_SERIES_COVER, item.posterUrl)
                putExtra(com.tvonnet.debridxtreamiptv.ui.series.SeriesDetailActivity.EXTRA_IS_DEBRID, true)
            }
            startActivity(intent)
        } else {
            android.widget.Toast.makeText(context, "Unknown content type: ${item.type}", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private fun tryResumeDebrid(item: DebridContentItem): Boolean {
        val resumePosition = item.resumePositionMs ?: return false
        if (resumePosition <= 0L) return false

        val infoHash = item.debridInfoHash
        val magnet = item.debridMagnet
        val streamUrl = item.streamUrl
        val contentType = item.contentType
            ?: if (item.type == "series") com.tvonnet.debridxtreamiptv.data.model.ContentType.EPISODE
            else com.tvonnet.debridxtreamiptv.data.model.ContentType.MOVIE

        if (infoHash.isNullOrBlank() && magnet.isNullOrBlank()) {
            if (streamUrl.isNullOrBlank()) return false
            val intent = com.tvonnet.debridxtreamiptv.player.PlayerActivity.createIntent(
                context = requireContext(),
                streamUrl = streamUrl,
                title = item.title,
                startPositionMs = resumePosition,
                contentId = item.tmdbId ?: item.id,
                contentType = contentType,
                playbackSource = com.tvonnet.debridxtreamiptv.player.PlaybackSource.DEBRID,
                posterUrl = item.posterUrl,
                backdropUrl = item.backdropUrl,
                tmdbId = item.tmdbId,
                imdbId = item.imdbId,
                seriesTitle = item.seriesTitle,
                episodeTitle = item.episodeTitle,
                seasonNumber = item.seasonNumber,
                episodeNumber = item.episodeNumber,
                debridInfoHash = infoHash,
                debridMagnet = magnet
            )
            startActivity(intent)
            return true
        }

        Toast.makeText(requireContext(), "Resuming...", Toast.LENGTH_SHORT).show()
        viewLifecycleOwner.lifecycleScope.launch {
            val result = debridPlaybackRepository.resolveDebridUrl(
                infoHash = infoHash,
                magnet = magnet,
                seasonNumber = item.seasonNumber,
                episodeNumber = item.episodeNumber,
                episodeTitle = item.episodeTitle
            )
            result.onSuccess { url ->
                val intent = com.tvonnet.debridxtreamiptv.player.PlayerActivity.createIntent(
                    context = requireContext(),
                    streamUrl = url,
                    title = item.title,
                    startPositionMs = resumePosition,
                    contentId = item.tmdbId ?: item.id,
                    contentType = contentType,
                    playbackSource = com.tvonnet.debridxtreamiptv.player.PlaybackSource.DEBRID,
                    posterUrl = item.posterUrl,
                    backdropUrl = item.backdropUrl,
                    tmdbId = item.tmdbId,
                    imdbId = item.imdbId,
                    seriesTitle = item.seriesTitle,
                    episodeTitle = item.episodeTitle,
                    seasonNumber = item.seasonNumber,
                    episodeNumber = item.episodeNumber,
                    debridInfoHash = infoHash,
                    debridMagnet = magnet
                )
                startActivity(intent)
            }.onFailure { error ->
                if (error is com.tvonnet.debridxtreamiptv.data.debrid.model.NotAuthenticatedException) {
                    navigateToLogin()
                } else {
                    Toast.makeText(
                        requireContext(),
                        error.message ?: "Failed to resume",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
        return true
    }

    private fun updateRefreshBanner(state: DebridRefreshState) {
        when (state) {
            DebridRefreshState.Idle -> {
                refreshBanner.visibility = View.GONE
            }
            DebridRefreshState.Refreshing -> {
                refreshBanner.visibility = View.VISIBLE
                refreshProgress.visibility = View.VISIBLE
                refreshText.text = getString(R.string.debrid_refreshing)
            }
            DebridRefreshState.Failed -> {
                refreshBanner.visibility = View.VISIBLE
                refreshProgress.visibility = View.GONE
                refreshText.text = getString(R.string.debrid_refresh_failed)
            }
        }
    }
}
