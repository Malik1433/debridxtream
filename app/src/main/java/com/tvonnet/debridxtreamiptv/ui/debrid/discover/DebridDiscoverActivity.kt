package com.tvonnet.debridxtreamiptv.ui.debrid.discover

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.view.ViewGroup
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.tvonnet.debridxtreamiptv.R
import com.tvonnet.debridxtreamiptv.utils.FocusCoordinator
import android.view.ViewTreeObserver
import com.tvonnet.debridxtreamiptv.data.debrid.model.TmdbGenre
import com.tvonnet.debridxtreamiptv.data.debrid.repository.CatalogItem
import com.tvonnet.debridxtreamiptv.ui.series.SeriesDetailActivity
import com.tvonnet.debridxtreamiptv.ui.vod.MovieDetailActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

/**
 * Full-screen discovery activity for Debrid content.
 *
 * Provides a Stremio-like browsing experience with 4 filter rows:
 * 1. Type    (Movies / Series)
 * 2. Genres  (Dynamically loaded from TMDB)
 * 3. Region  (Hollywood, Bollywood, Punjabi, Tamil, etc.)
 * 4. Sort    (Popular, Newest, Top Rated)
 */
@AndroidEntryPoint
class DebridDiscoverActivity : AppCompatActivity() {

    private val viewModel: DebridDiscoverViewModel by viewModels()

    // ── Views ────────────────────────────────────────────────────────────────────
    private lateinit var progressBar: ProgressBar
    private lateinit var tvError: TextView
    private lateinit var rvGrid: RecyclerView
    private lateinit var llTypeSelector: LinearLayout
    private lateinit var llGenreSelector: LinearLayout
    private lateinit var llRegionSelector: LinearLayout
    private lateinit var llSortSelector: LinearLayout

    // ── Adapter ──────────────────────────────────────────────────────────────────
    private lateinit var gridAdapter: DebridDiscoverAdapter

    // ── Selected filter tracking ─────────────────────────────────────────────────
    private var selectedType = "movie"
    private var selectedGenreId: Int? = null
    private var selectedRegionCode: String? = null
    private var selectedSort = "popularity.desc"
    
    // ── Focus Tracking (Premium Feature) ──────────────────────────────────────────
    private var lastFocusedPosition = RecyclerView.NO_POSITION

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_debrid_discover)

        if (savedInstanceState != null) {
            lastFocusedPosition = savedInstanceState.getInt("last_focus_pos", RecyclerView.NO_POSITION)
        }

        initViews()
        setupGrid()
        
        // Handle incoming filters from Intent before setting up selectors
        intent.getStringExtra(EXTRA_TYPE)?.let { selectedType = it }
        selectedGenreId = intent.getIntExtra(EXTRA_GENRE_ID, -1).takeIf { it != -1 }
        
        setupTypeSelector()
        setupRegionSelector()
        setupSortSelector()
        
        // Push initial state to ViewModel if filtered
        if (intent.hasExtra(EXTRA_TYPE) || intent.hasExtra(EXTRA_GENRE_ID)) {
            viewModel.setType(selectedType)
            viewModel.setGenre(selectedGenreId)
        }
        
        observeViewModel()
        
        setupInitialFocus()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("last_focus_pos", lastFocusedPosition)
    }

    private fun setupInitialFocus() {
        // Automatically focus the first chip in the type selector on first launch
        llTypeSelector.viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                if (lastFocusedPosition == RecyclerView.NO_POSITION) {
                    if (llTypeSelector.childCount > 0) {
                        llTypeSelector.getChildAt(0).requestFocus()
                    }
                }
                llTypeSelector.viewTreeObserver.removeOnGlobalLayoutListener(this)
            }
        })
    }

    private fun initViews() {
        progressBar = findViewById(R.id.progress_bar)
        tvError = findViewById(R.id.tv_error)
        rvGrid = findViewById(R.id.rv_discover_grid)
        llTypeSelector = findViewById(R.id.ll_type_selector)
        llGenreSelector = findViewById(R.id.ll_genre_selector)
        llRegionSelector = findViewById(R.id.ll_region_selector)
        llSortSelector = findViewById(R.id.ll_sort_selector)
    }

    private fun setupGrid() {
        gridAdapter = DebridDiscoverAdapter(
            onItemClick = { item -> onItemClick(item) },
            onItemFocused = { item -> 
                updateBackdrop(item)
                // Save focus position for restoration
                val focusedView = rvGrid.focusedChild ?: return@DebridDiscoverAdapter
                val viewHolder = rvGrid.findContainingViewHolder(focusedView)
                if (viewHolder != null) {
                    lastFocusedPosition = viewHolder.bindingAdapterPosition
                }
            },
            onLoadMore = { viewModel.loadNextPage() }
        )
        rvGrid.apply {
            layoutManager = GridLayoutManager(this@DebridDiscoverActivity, 6)
            adapter = gridAdapter
            itemAnimator = null // Phase 4: Disable animations for focus stability
            setHasFixedSize(true)
            // Important for TV: prevent focus from jumping to RecyclerView before items are ready
            descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        }
    }

    // ── Filter setups ─────────────────────────────────────────────────────────────

    private fun setupTypeSelector() {
        val types = listOf("Movies" to "movie", "Series" to "series")
        types.forEach { (label, value) ->
            val chip = createChip(label, isSelected = value == selectedType)
            chip.setOnClickListener {
                if (selectedType == value) return@setOnClickListener
                selectedType = value
                selectedGenreId = null
                viewModel.setType(value)
                refreshChipSelection(llTypeSelector, chip)
                // genre chips will be rebuilt by the observer
            }
            llTypeSelector.addView(chip)
        }
    }

    private fun populateGenreChips(genres: List<TmdbGenre>) {
        llGenreSelector.removeAllViews()
        val allChip = createChip("All", isSelected = selectedGenreId == null)
        allChip.setOnClickListener {
            selectedGenreId = null
            viewModel.setGenre(null)
            refreshChipSelection(llGenreSelector, allChip)
        }
        llGenreSelector.addView(allChip)

        genres.forEach { genre ->
            val isSelected = genre.id == selectedGenreId
            val chip = createChip(genre.name ?: "", isSelected = isSelected)
            chip.setOnClickListener {
                selectedGenreId = genre.id
                viewModel.setGenre(genre.id)
                refreshChipSelection(llGenreSelector, chip)
            }
            llGenreSelector.addView(chip)
        }
    }

    private fun setupRegionSelector() {
        viewModel.regions.forEach { region ->
            val isSelected = region.code == selectedRegionCode
            val chip = createChip(region.label, isSelected = isSelected)
            chip.setOnClickListener {
                selectedRegionCode = region.code
                viewModel.setRegion(region.code)
                refreshChipSelection(llRegionSelector, chip)
            }
            llRegionSelector.addView(chip)
        }
    }

    private fun setupSortSelector() {
        viewModel.sortOptions.forEach { sort ->
            val isSelected = sort.value == selectedSort
            val chip = createChip(sort.label, isSelected = isSelected)
            chip.setOnClickListener {
                selectedSort = sort.value
                viewModel.setSort(sort.value)
                refreshChipSelection(llSortSelector, chip)
            }
            llSortSelector.addView(chip)
        }
    }

    // ── Observers ─────────────────────────────────────────────────────────────────

    private fun observeViewModel() {
        lifecycleScope.launch {
            viewModel.genres.collect { genres ->
                populateGenreChips(genres)
            }
        }
        lifecycleScope.launch {
            viewModel.uiState.collect { state ->
                when (state) {
                    is DiscoverUiState.Loading -> {
                        progressBar.visibility = View.VISIBLE
                        tvError.visibility = View.GONE
                        rvGrid.visibility = View.GONE
                    }
                    is DiscoverUiState.Content -> {
                        progressBar.visibility = View.GONE
                        tvError.visibility = View.GONE
                        rvGrid.visibility = View.VISIBLE
                        gridAdapter.submitItems(state.items, state.canLoadMore)
                        
                        // Phase 4: Premium Focus Restoration (Double-Post)
                        if (lastFocusedPosition != RecyclerView.NO_POSITION) {
                            FocusCoordinator.requestFocus("DEBRID_DISCOVER") {
                                rvGrid.post {
                                    rvGrid.scrollToPosition(lastFocusedPosition)
                                    rvGrid.post {
                                        val targetView = (rvGrid.layoutManager as? GridLayoutManager)?.findViewByPosition(lastFocusedPosition)
                                        targetView?.requestFocus()
                                        FocusCoordinator.release("DEBRID_DISCOVER")
                                    }
                                }
                            }
                        }
                    }
                    is DiscoverUiState.Error -> {
                        progressBar.visibility = View.GONE
                        tvError.visibility = View.VISIBLE
                        rvGrid.visibility = View.GONE
                        tvError.text = state.message
                    }
                }
            }
        }
    }

    // ── Navigation ─────────────────────────────────────────────────────────────────

    private fun onItemClick(item: CatalogItem) {
        if (item.type == "movie") {
            val intent = Intent(this, MovieDetailActivity::class.java).apply {
                putExtra(MovieDetailActivity.EXTRA_MOVIE_ID, item.id)
                putExtra(MovieDetailActivity.EXTRA_MOVIE_NAME, item.title)
                putExtra(MovieDetailActivity.EXTRA_MOVIE_ICON, item.posterUrl)
                putExtra(MovieDetailActivity.EXTRA_MOVIE_BACKDROP, item.backdropUrl)
                putExtra(MovieDetailActivity.EXTRA_MOVIE_YEAR, item.year)
                putExtra(MovieDetailActivity.EXTRA_MOVIE_RATING, item.rating)
                putExtra(MovieDetailActivity.EXTRA_MOVIE_CATEGORY_ID, "debrid")
            }
            startActivity(intent)
        } else {
            val intent = Intent(this, SeriesDetailActivity::class.java).apply {
                putExtra(SeriesDetailActivity.EXTRA_SERIES_ID, item.id)
                putExtra(SeriesDetailActivity.EXTRA_SERIES_NAME, item.title)
                putExtra(SeriesDetailActivity.EXTRA_SERIES_COVER, item.posterUrl)
                putExtra(SeriesDetailActivity.EXTRA_IS_DEBRID, true)
            }
            startActivity(intent)
        }
    }

    private fun updateBackdrop(item: CatalogItem) {
        val imageUrl = item.backdropUrl ?: item.posterUrl ?: return
        val ivBackdrop = findViewById<android.widget.ImageView>(R.id.iv_discover_backdrop)
        Glide.with(this)
            .load(imageUrl)
            .transition(DrawableTransitionOptions.withCrossFade(500))
            .into(ivBackdrop)
    }

    // ── Chip helper ─────────────────────────────────────────────────────────────────

    /**
     * Creates a styled filter chip (reusing item_filter_chip.xml layout).
     *
     * @param label  Display text for the chip.
     * @param isSelected  Whether to show the chip as selected.
     * @return The inflated and configured TextView chip.
     */
    private fun createChip(label: String, isSelected: Boolean): TextView {
        val chip = layoutInflater.inflate(R.layout.item_filter_chip, null, false) as TextView
        chip.text = label
        chip.isSelected = isSelected
        return chip
    }

    /**
     * Updates the selected state of all chips in a LinearLayout so only [selectedChip] is selected.
     *
     * @param container  The parent LinearLayout containing all chips.
     * @param selectedChip  The chip that should be selected.
     */
    private fun refreshChipSelection(container: LinearLayout, selectedChip: TextView) {
        for (i in 0 until container.childCount) {
            val child = container.getChildAt(i) as? TextView ?: continue
            child.isSelected = child === selectedChip
        }
    }

    companion object {
        const val EXTRA_TYPE = "extra_type"
        const val EXTRA_GENRE_ID = "extra_genre_id"

        /**
         * Creates an Intent to launch DebridDiscoverActivity.
         *
         * @param context The calling context.
         * @param type Optional initial filter type (movie/series).
         * @param genreId Optional initial filter genre ID.
         * @return A ready-to-use Intent.
         */
        fun createIntent(context: Context, type: String? = null, genreId: Int? = null): Intent =
            Intent(context, DebridDiscoverActivity::class.java).apply {
                type?.let { putExtra(EXTRA_TYPE, it) }
                genreId?.let { putExtra(EXTRA_GENRE_ID, it) }
            }
    }
}
