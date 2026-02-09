package com.tvonnet.debridxtreamiptv.ui.recommendations

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.tvonnet.debridxtreamiptv.R
import com.tvonnet.debridxtreamiptv.data.recommendation.RecommendationResult
import com.tvonnet.debridxtreamiptv.player.PlayerActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * TV-optimized Recommendations Screen
 *
 * Features:
 * - Multiple recommendation categories (Personalized, Trending, Popular, Similar)
 * - Horizontal scrolling rows optimized for D-pad navigation
 * - Focus management and visual feedback for TV navigation
 * - Lazy loading and caching for performance
 * - Integration with watch history and user preferences
 */
@AndroidEntryPoint
class RecommendationsFragment : Fragment() {

    @Inject
    lateinit var recommendationItemClickListener: RecommendationItemClickListener

    private val viewModel: RecommendationsViewModel by viewModels()

    // UI Components
    private lateinit var tvTitle: TextView
    private lateinit var btnBack: ImageView
    private lateinit var loadingIndicator: View
    private lateinit var errorMessage: TextView
    private lateinit var retryButton: TextView

    // Recommendation Rows
    private lateinit var rvPersonalized: RecyclerView
    private lateinit var rvTrending: RecyclerView
    private lateinit var rvPopular: RecyclerView
    private lateinit var rvSimilar: RecyclerView
    private lateinit var rvContinueWatching: RecyclerView

    // Section Headers
    private lateinit var tvPersonalizedHeader: TextView
    private lateinit var tvTrendingHeader: TextView
    private lateinit var tvPopularHeader: TextView
    private lateinit var tvSimilarHeader: TextView
    private lateinit var tvContinueWatchingHeader: TextView

    // Adapters
    private lateinit var personalizedAdapter: RecommendationCardAdapter
    private lateinit var trendingAdapter: RecommendationCardAdapter
    private lateinit var popularAdapter: RecommendationCardAdapter
    private lateinit var similarAdapter: RecommendationCardAdapter
    private lateinit var continueWatchingAdapter: RecommendationCardAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_recommendations, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        initializeViews(view)
        setupAdapters()
        setupRecyclerViews()
        observeViewModel()

        // Load recommendations
        viewModel.loadRecommendations()

        // Set initial focus
        view.post {
            rvPersonalized.requestFocus()
        }
    }

    private fun initializeViews(view: View) {
        // Header components
        tvTitle = view.findViewById(R.id.tv_recommendations_title)
        btnBack = view.findViewById(R.id.btn_back)
        loadingIndicator = view.findViewById(R.id.loading_indicator)
        errorMessage = view.findViewById(R.tv_error_message)
        retryButton = view.findViewById(R.id.btn_retry)

        // RecyclerViews
        rvPersonalized = view.findViewById(R.id.rv_personalized_recommendations)
        rvTrending = view.findViewById(R.id.rv_trending_recommendations)
        rvPopular = view.findViewById(R.id.rv_popular_recommendations)
        rvSimilar = view.findViewById(R.id.rv_similar_recommendations)
        rvContinueWatching = view.findViewById(R.id.rv_continue_watching)

        // Section headers
        tvPersonalizedHeader = view.findViewById(R.id.tv_personalized_header)
        tvTrendingHeader = view.findViewById(R.id.tv_trending_header)
        tvPopularHeader = view.findViewById(R.id.tv_popular_header)
        tvSimilarHeader = view.findViewById(R.id.tv_similar_header)
        tvContinueWatchingHeader = view.findViewById(R.id.tv_continue_watching_header)

        // Setup click listeners
        btnBack.setOnClickListener {
            parentFragmentManager.popBackStack()
        }

        retryButton.setOnClickListener {
            viewModel.loadRecommendations()
        }
    }

    private fun setupAdapters() {
        val itemClickListener = { recommendation: RecommendationResult ->
            recommendationItemClickListener.onRecommendationClick(recommendation)
        }

        personalizedAdapter = RecommendationCardAdapter(
            recommendations = emptyList(),
            onItemClick = itemClickListener,
            cardType = RecommendationCardAdapter.CardType.PERSONALIZED
        )

        trendingAdapter = RecommendationCardAdapter(
            recommendations = emptyList(),
            onItemClick = itemClickListener,
            cardType = RecommendationCardAdapter.CardType.TRENDING
        )

        popularAdapter = RecommendationCardAdapter(
            recommendations = emptyList(),
            onItemClick = itemClickListener,
            cardType = RecommendationCardAdapter.CardType.POPULAR
        )

        similarAdapter = RecommendationCardAdapter(
            recommendations = emptyList(),
            onItemClick = itemClickListener,
            cardType = RecommendationCardAdapter.CardType.SIMILAR
        )

        continueWatchingAdapter = RecommendationCardAdapter(
            recommendations = emptyList(),
            onItemClick = itemClickListener,
            cardType = RecommendationCardAdapter.CardType.CONTINUE_WATCHING
        )
    }

    private fun setupRecyclerViews() {
        // Configure RecyclerViews for TV navigation
        rvPersonalized.apply {
            layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
            adapter = personalizedAdapter
            setHasFixedSize(true)

            // TV-specific optimizations
            isFocusable = true
            isFocusableInTouchMode = true
            descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        }

        rvTrending.apply {
            layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
            adapter = trendingAdapter
            setHasFixedSize(true)
            isFocusable = true
            isFocusableInTouchMode = true
            descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        }

        rvPopular.apply {
            layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
            adapter = popularAdapter
            setHasFixedSize(true)
            isFocusable = true
            isFocusableInTouchMode = true
            descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        }

        rvSimilar.apply {
            layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
            adapter = similarAdapter
            setHasFixedSize(true)
            isFocusable = true
            isFocusableInTouchMode = true
            descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        }

        rvContinueWatching.apply {
            layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
            adapter = continueWatchingAdapter
            setHasFixedSize(true)
            isFocusable = true
            isFocusableInTouchMode = true
            descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        }
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.uiState.collect { uiState ->
                when (uiState) {
                    is RecommendationsUiState.Loading -> {
                        showLoading()
                    }
                    is RecommendationsUiState.Success -> {
                        showRecommendations(uiState.data)
                    }
                    is RecommendationsUiState.Error -> {
                        showError(uiState.message)
                    }
                }
            }
        }
    }

    private fun showLoading() {
        loadingIndicator.isVisible = true
        errorMessage.isVisible = false
        retryButton.isVisible = false

        // Hide all recommendation rows
        hideAllRecommendationRows()
    }

    private fun showRecommendations(data: RecommendationsData) {
        loadingIndicator.isVisible = false
        errorMessage.isVisible = false
        retryButton.isVisible = false

        // Update personalized recommendations
        if (data.personalized.isNotEmpty()) {
            tvPersonalizedHeader.isVisible = true
            rvPersonalized.isVisible = true
            personalizedAdapter.updateRecommendations(data.personalized)
        } else {
            tvPersonalizedHeader.isVisible = false
            rvPersonalized.isVisible = false
        }

        // Update trending recommendations
        if (data.trending.isNotEmpty()) {
            tvTrendingHeader.isVisible = true
            rvTrending.isVisible = true
            trendingAdapter.updateRecommendations(data.trending)
        } else {
            tvTrendingHeader.isVisible = false
            rvTrending.isVisible = false
        }

        // Update popular recommendations
        if (data.popular.isNotEmpty()) {
            tvPopularHeader.isVisible = true
            rvPopular.isVisible = true
            popularAdapter.updateRecommendations(data.popular)
        } else {
            tvPopularHeader.isVisible = false
            rvPopular.isVisible = false
        }

        // Update similar recommendations
        if (data.similar.isNotEmpty()) {
            tvSimilarHeader.isVisible = true
            rvSimilar.isVisible = true
            similarAdapter.updateRecommendations(data.similar)
        } else {
            tvSimilarHeader.isVisible = false
            rvSimilar.isVisible = false
        }

        // Update continue watching
        if (data.continueWatching.isNotEmpty()) {
            tvContinueWatchingHeader.isVisible = true
            rvContinueWatching.isVisible = true
            continueWatchingAdapter.updateRecommendations(data.continueWatching)
        } else {
            tvContinueWatchingHeader.isVisible = false
            rvContinueWatching.isVisible = false
        }
    }

    private fun showError(message: String) {
        loadingIndicator.isVisible = false
        errorMessage.isVisible = true
        retryButton.isVisible = true
        errorMessage.text = message

        // Hide all recommendation rows
        hideAllRecommendationRows()
    }

    private fun hideAllRecommendationRows() {
        tvPersonalizedHeader.isVisible = false
        rvPersonalized.isVisible = false
        tvTrendingHeader.isVisible = false
        rvTrending.isVisible = false
        tvPopularHeader.isVisible = false
        rvPopular.isVisible = false
        tvSimilarHeader.isVisible = false
        rvSimilar.isVisible = false
        tvContinueWatchingHeader.isVisible = false
        rvContinueWatching.isVisible = false
    }

    override fun onResume() {
        super.onResume()
        // Refresh recommendations when fragment becomes visible
        viewModel.refreshRecommendations()
    }
}

/**
 * Interface for handling recommendation item clicks
 */
interface RecommendationItemClickListener {
    fun onRecommendationClick(recommendation: RecommendationResult)
    fun onRecommendationLongClick(recommendation: RecommendationResult): Boolean {
        // Default implementation for optional long click handling
        return false
    }
}

/**
 * Default implementation of RecommendationItemClickListener
 */
class DefaultRecommendationItemClickListener @Inject constructor() : RecommendationItemClickListener {
    override fun onRecommendationClick(recommendation: RecommendationResult) {
        // Launch player with the recommended content
        // This would integrate with the existing PlayerActivity
        // PlayerActivity.start(requireContext(), recommendation.contentId, recommendation.contentType)
    }
}