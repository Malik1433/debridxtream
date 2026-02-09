package com.tvonnet.debridxtreamiptv.ui.home

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.commit
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.tvonnet.debridxtreamiptv.R
import com.tvonnet.debridxtreamiptv.databinding.FragmentHomeModernV2Binding
import com.tvonnet.debridxtreamiptv.ui.live.LiveFragment
import com.tvonnet.debridxtreamiptv.ui.search.SearchFragment
import com.tvonnet.debridxtreamiptv.ui.series.SeriesFragment
import com.tvonnet.debridxtreamiptv.ui.settings.SettingsFragmentNew
import com.tvonnet.debridxtreamiptv.ui.vod.VodFragment
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

@AndroidEntryPoint
class HomeFragmentModern : Fragment() {

    private var _binding: FragmentHomeModernV2Binding? = null
    private val binding get() = _binding!!

    private val viewModel: HomeViewModel by viewModels()
    private lateinit var sectionAdapter: HomeSectionAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHomeModernV2Binding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupRecyclerView()
        setupNavigation()
        observeState()
    }

    @javax.inject.Inject
    lateinit var repository: com.tvonnet.debridxtreamiptv.data.repository.XtreamRepository
    
    @javax.inject.Inject
    lateinit var credentialsPrefs: com.tvonnet.debridxtreamiptv.data.prefs.CredentialsPreferences

    private fun setupRecyclerView() {
        sectionAdapter = HomeSectionAdapter { item ->
            handleContentClick(item)
        }
        binding.rvHomeSections.apply {
            layoutManager = LinearLayoutManager(context)
            adapter = sectionAdapter
        }
    }

    private fun setupNavigation() {
        // Sidebar Navigation
        binding.btnNavSearch.setOnClickListener { navigateToSection("search") }
        binding.btnNavLive.setOnClickListener { navigateToSection("live") }
        binding.btnNavMovies.setOnClickListener { navigateToSection("movies") }
        binding.btnNavSeries.setOnClickListener { navigateToSection("series") }
        binding.btnNavDebrid.setOnClickListener { navigateToSection("debrid") }
        binding.btnNavSettings.setOnClickListener { navigateToSection("settings") }
        
        // Home button (Focus Top / Refresh)
        binding.btnNavHome.setOnClickListener { 
            binding.rvHomeSections.smoothScrollToPosition(0)
        }
        
        // Default focus
        binding.rvHomeSections.requestFocus()
    }

    private fun observeState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collectLatest { state ->
                    binding.pbHomeLoading.visibility = if (state.isLoading) View.VISIBLE else View.GONE
                    
                    if (!state.isLoading) {
                        sectionAdapter.updateData(state.heroItem, state.sections)
                    }
                }
            }
        }
    }

    private fun handleContentClick(item: Any) {
        when (item) {
            is com.tvonnet.debridxtreamiptv.data.model.XtreamVodInfo -> {
                item.stream_icon?.let { url ->
                    launchPlayer(
                        url = buildStreamUrl(item.stream_id ?: "", "movie", item.container_extension ?: "mp4"),
                        title = item.name ?: "Movie",
                        id = item.stream_id ?: "",
                        type = com.tvonnet.debridxtreamiptv.data.model.ContentType.MOVIE,
                        poster = item.stream_icon
                    )
                }
            }
            is com.tvonnet.debridxtreamiptv.data.model.XtreamStream -> {
                 launchLiveStream(item)
            }
            is com.tvonnet.debridxtreamiptv.data.model.XtreamSeriesInfo -> {
                val fragment = com.tvonnet.debridxtreamiptv.features.seriesv2.ui.SeriesDetailFragmentV2().apply {
                    arguments = Bundle().apply {
                        putString("series_id", item.series_id)
                        putString("title", item.name)
                        putString("backdrop_url", item.cover) // or backdropPathList.firstOrNull()
                        putString("poster_url", item.cover)
                    }
                }
                parentFragmentManager.beginTransaction()
                    .replace(R.id.content_container, fragment)
                    .addToBackStack(null) // Allow back navigation to Home
                    .commit()
            }
            is HeroContent -> {
                if (item.type == "MOVIE") {
                    launchPlayer(
                        url = buildStreamUrl(item.streamId, "movie", "mp4"), // Extension assumption
                        title = item.title,
                        id = item.streamId,
                        type = com.tvonnet.debridxtreamiptv.data.model.ContentType.MOVIE,
                        poster = item.imageUrl
                    )
                } else if (item.type == "SERIES") {
                    val fragment = com.tvonnet.debridxtreamiptv.features.seriesv2.ui.SeriesDetailFragmentV2().apply {
                        arguments = Bundle().apply {
                             putString("series_id", item.streamId)
                             putString("title", item.title)
                             putString("backdrop_url", item.imageUrl)
                             putString("poster_url", item.imageUrl)
                        }
                    }
                    parentFragmentManager.beginTransaction()
                        .replace(R.id.content_container, fragment)
                        .addToBackStack(null)
                        .commit()
                }
            }
        }
    }

    private fun buildStreamUrl(streamId: String, type: String, ext: String): String {
        val server = credentialsPrefs.getServerUrl()?.trimEnd('/') ?: ""
        val user = credentialsPrefs.getUsername() ?: ""
        val pass = credentialsPrefs.getPassword() ?: ""
        return "$server/$type/$user/$pass/$streamId.$ext"
    }

    private fun launchPlayer(url: String, title: String, id: String, type: com.tvonnet.debridxtreamiptv.data.model.ContentType, poster: String?) {
        val intent = com.tvonnet.debridxtreamiptv.player.PlayerActivity.createIntent(
            context = requireContext(),
            streamUrl = url,
            title = title,
            contentId = id,
            contentType = type,
            posterUrl = poster,
            backdropUrl = null
        )
        startActivity(intent)
    }

    private fun launchLiveStream(stream: com.tvonnet.debridxtreamiptv.data.model.XtreamStream) {
        val url = buildStreamUrl(stream.stream_id ?: "", "live", "ts") // Live typically .ts
        launchPlayer(
            url = url,
            title = stream.name ?: "Channel",
            id = stream.stream_id ?: "",
            type = com.tvonnet.debridxtreamiptv.data.model.ContentType.LIVE_TV,
            poster = stream.stream_icon
        )
    }

    private fun navigateToSection(section: String) {
        val fragment = when (section) {
            "live" -> LiveFragment()
            "movies" -> VodFragment()
            "series" -> SeriesFragment()
            "debrid" -> com.tvonnet.debridxtreamiptv.ui.debrid.DebridFragment()
            "search" -> SearchFragment()
            "settings" -> com.tvonnet.debridxtreamiptv.ui.settings.SettingsFragment()
            else -> return
        }
        
        parentFragmentManager.commit {
            replace(R.id.content_container, fragment)
            addToBackStack(null)
        }
    }

    /**
     * Handles back press on Home Screen.
     * Returns true if the event was consumed (e.g., scrolled to top).
     * Returns false if the activity should proceed with exit logic.
     */
    fun handleBackPress(): Boolean {
        val rv = binding.rvHomeSections
        // Check if we can scroll vertically (meaning we are not at the top)
        // computeVerticalScrollOffset > 0 is reliable.
        if (rv.computeVerticalScrollOffset() > 0) {
            // Scroll smoothly to top
            rv.smoothScrollToPosition(0)
            // Optional: Request focus on the first item after scrolling
             rv.postDelayed({
                val holder = rv.findViewHolderForAdapterPosition(0)
                holder?.itemView?.requestFocus()
            }, 300)
            return true
        }
        return false
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
