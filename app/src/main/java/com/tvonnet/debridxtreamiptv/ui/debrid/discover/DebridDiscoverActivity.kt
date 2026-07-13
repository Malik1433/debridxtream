package com.tvonnet.debridxtreamiptv.ui.debrid.discover

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.tvonnet.debridxtreamiptv.R
import com.tvonnet.debridxtreamiptv.data.debrid.model.TmdbGenre
import com.tvonnet.debridxtreamiptv.data.debrid.repository.AddonCatalogRepository
import com.tvonnet.debridxtreamiptv.data.debrid.repository.CatalogItem
import com.tvonnet.debridxtreamiptv.ui.series.SeriesDetailActivity
import com.tvonnet.debridxtreamiptv.ui.vod.MovieDetailActivity
import com.tvonnet.debridxtreamiptv.utils.FocusCoordinator
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

/**
 * Full-screen Debrid discovery with four backend-backed selectors and the existing poster grid.
 */
@AndroidEntryPoint
class DebridDiscoverActivity : AppCompatActivity() {

    private val viewModel: DebridDiscoverViewModel by viewModels()

    private lateinit var progressBar: ProgressBar
    private lateinit var tvError: TextView
    private lateinit var rvGrid: RecyclerView
    private lateinit var btnType: TextView
    private lateinit var btnCatalogue: TextView
    private lateinit var btnGenre: TextView
    private lateinit var btnYear: TextView
    private lateinit var gridAdapter: DebridDiscoverAdapter

    private var currentGenres: List<TmdbGenre> = emptyList()
    private var lastFocusedPosition = RecyclerView.NO_POSITION
    private var lastFocusedSelector: TextView? = null
    private var pendingSelectorFocus: TextView? = null
    private var activePopup: PopupWindow? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_debrid_discover)

        if (savedInstanceState != null) {
            lastFocusedPosition = savedInstanceState.getInt(KEY_LAST_FOCUS_POS, RecyclerView.NO_POSITION)
        }

        val initType = intent.getStringExtra(EXTRA_TYPE)
        val initGenreId = intent.getIntExtra(EXTRA_GENRE_ID, -1).takeIf { it != -1 }
        val initCatalogue = intent.getStringExtra(EXTRA_CATALOGUE)

        initType?.let { viewModel.setType(it) }
        initCatalogue?.let { viewModel.setCatalogue(it) }
        initGenreId?.let { viewModel.setGenre(it) }
        
        // Pass custom row filters if they exist
        val lang = intent.getStringExtra(EXTRA_LANGUAGE)
        val region = intent.getStringExtra(EXTRA_REGION)
        val releaseDateGte = intent.getStringExtra(EXTRA_RELEASE_DATE_GTE)
        if (lang != null || region != null || releaseDateGte != null) {
            viewModel.setCustomFilters(lang, region, releaseDateGte)
        }

        // View wiring MUST run before observeViewModel()/setupInitialFocus(), both of
        // which dereference the lateinit selector views (btnType et al.). Previously
        // these three calls were missing here and had been nested inside setupGrid()
        // (which also called itself → StackOverflow); onCreate reached setupInitialFocus()
        // with uninitialized lateinits. Order restored: init views → grid → selectors.
        initViews()
        setupGrid()
        setupSelectors()

        observeViewModel()
        setupInitialFocus()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_LAST_FOCUS_POS, lastFocusedPosition)
    }

    override fun onBackPressed() {
        if (activePopup?.isShowing == true) {
            activePopup?.dismiss()
            return
        }
        super.onBackPressed()
    }

    private fun initViews() {
        progressBar = findViewById(R.id.progress_bar)
        tvError = findViewById(R.id.tv_error)
        rvGrid = findViewById(R.id.rv_discover_grid)
        btnType = findViewById(R.id.btn_type_selector)
        btnCatalogue = findViewById(R.id.btn_catalogue_selector)
        btnGenre = findViewById(R.id.btn_genre_selector)
        btnYear = findViewById(R.id.btn_year_selector)
    }

    private fun setupGrid() {
        gridAdapter = DebridDiscoverAdapter(
            onItemClick = { item -> onItemClick(item) },
            onItemFocused = { item ->
                updateBackdrop(item)
                val focusedView = rvGrid.focusedChild ?: return@DebridDiscoverAdapter
                val viewHolder = rvGrid.findContainingViewHolder(focusedView) ?: return@DebridDiscoverAdapter
                lastFocusedPosition = viewHolder.bindingAdapterPosition
            },
            onLoadMore = { viewModel.loadNextPage() }
        )

        rvGrid.apply {
            layoutManager = GridLayoutManager(this@DebridDiscoverActivity, 6)
            adapter = gridAdapter
            itemAnimator = null
            setHasFixedSize(true)
            descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
            setOnKeyListener { _, keyCode, event ->
                if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                    val firstVisible = (layoutManager as GridLayoutManager).findFirstVisibleItemPosition()
                    if (firstVisible in 0..5) {
                        (lastFocusedSelector ?: btnType).requestFocus()
                        return@setOnKeyListener true
                    }
                }
                false
            }
        }
    }

    private fun setupSelectors() {
        listOf(btnType, btnCatalogue, btnGenre, btnYear).forEach { selector ->
            applySelectorFocusStyle(selector, false)
            selector.setOnFocusChangeListener { view, hasFocus ->
                val textView = view as TextView
                applySelectorFocusStyle(textView, hasFocus)
                // SECURE FOCUS: Track the exact selector currently focused so we can reliably return to it 
                // from the grid without relying on Android's unpredictable global focus-search
                if (hasFocus) lastFocusedSelector = textView
            }
            selector.setOnKeyListener { view, keyCode, event ->
                if (event.action == KeyEvent.ACTION_DOWN) {
                    when (keyCode) {
                        KeyEvent.KEYCODE_DPAD_DOWN -> {
                            // SECURE FOCUS: Safely transition focus downwards into the grid area
                            focusFirstPosterOrGrid()
                            return@setOnKeyListener true
                        }
                        KeyEvent.KEYCODE_DPAD_UP -> {
                            // SECURE FOCUS: Block UP from escaping the layout entirely, preventing 
                            // focus from jumping to unseen System UI elements
                            return@setOnKeyListener true
                        }
                    }
                }
                false
            }
        }

        btnType.setOnClickListener {
            val currentState = viewModel.filterState.value
            showDropdown(
                anchor = btnType,
                options = listOf(FilterOption("Movies", TYPE_MOVIE), FilterOption("Series", TYPE_SERIES)),
                selectedValue = currentState.type
            ) { option ->
                val type = option.value as String
                if (currentState.type != type) {
                    queueSelectorFocus(btnType)
                    viewModel.setType(type)
                }
            }
        }

        btnCatalogue.setOnClickListener {
            val currentState = viewModel.filterState.value
            showDropdown(
                anchor = btnCatalogue,
                options = viewModel.catalogueOptions.map { FilterOption(it.label, it.value) },
                selectedValue = currentState.catalogue
            ) { option ->
                val catalogue = option.value as String
                if (currentState.catalogue != catalogue) {
                    queueSelectorFocus(btnCatalogue)
                    viewModel.setCatalogue(catalogue)
                }
            }
        }

        btnGenre.setOnClickListener {
            val currentState = viewModel.filterState.value
            val options = buildList {
                add(FilterOption(GENRE_ALL, null))
                currentGenres.forEach { genre ->
                    val id = genre.id ?: return@forEach
                    add(FilterOption(genre.name ?: id.toString(), id))
                }
            }
            showDropdown(anchor = btnGenre, options = options, selectedValue = currentState.genreId) { option ->
                val genreId = option.value as Int?
                if (currentState.genreId != genreId) {
                    queueSelectorFocus(btnGenre)
                    viewModel.setGenre(genreId)
                }
            }
        }

        btnYear.setOnClickListener {
            val currentState = viewModel.filterState.value
            val options = viewModel.yearOptions.map { year ->
                FilterOption(year?.toString() ?: YEAR_ALL, year)
            }
            showDropdown(anchor = btnYear, options = options, selectedValue = currentState.year) { option ->
                val year = option.value as Int?
                if (currentState.year != year) {
                    queueSelectorFocus(btnYear)
                    viewModel.setYear(year)
                }
            }
        }
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            viewModel.genres.collect { genres ->
                currentGenres = genres
                // Triggers an update to the selector text since genre data is needed to display label
                updateSelectorText(viewModel.filterState.value)
            }
        }
        lifecycleScope.launch {
            viewModel.filterState.collect { state ->
                // Ensure UI is reactively bound to ViewModel state emissions
                updateSelectorText(state)
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
                        restoreFocusAfterContent()
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

    private fun restoreFocusAfterContent() {
        pendingSelectorFocus?.let { selector ->
            selector.post { selector.requestFocus() }
            pendingSelectorFocus = null
            return
        }

        if (lastFocusedPosition != RecyclerView.NO_POSITION && rvGrid.hasFocus()) {
            FocusCoordinator.requestFocus("DEBRID_DISCOVER") {
                rvGrid.post {
                    rvGrid.scrollToPosition(lastFocusedPosition)
                    rvGrid.post {
                        val targetView = (rvGrid.layoutManager as? GridLayoutManager)
                            ?.findViewByPosition(lastFocusedPosition)
                        targetView?.requestFocus()
                        FocusCoordinator.release("DEBRID_DISCOVER")
                    }
                }
            }
        }
    }

    private fun setupInitialFocus() {
        btnType.viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                if (lastFocusedPosition == RecyclerView.NO_POSITION) {
                    btnType.requestFocus()
                }
                btnType.viewTreeObserver.removeOnGlobalLayoutListener(this)
            }
        })
    }

    private fun updateSelectorText(state: DiscoverFilterState) {
        btnType.text = "Type: ${if (state.type == TYPE_MOVIE) "Movies" else "Series"}"
        btnCatalogue.text = "Catalogue: ${catalogueLabel(state.catalogue)}"
        
        val genreLabel = if (state.genreId == null) GENRE_ALL else currentGenres.firstOrNull { it.id == state.genreId }?.name ?: GENRE_ALL
        btnGenre.text = "Genre: $genreLabel"
        
        btnYear.text = "Year: ${state.year?.toString() ?: YEAR_ALL}"
    }

    private fun catalogueLabel(value: String): String {
        return viewModel.catalogueOptions.firstOrNull { it.value == value }?.label ?: "Popular"
    }

    private fun queueSelectorFocus(selector: TextView) {
        pendingSelectorFocus = selector
        lastFocusedSelector = selector
    }

    private fun focusFirstPosterOrGrid() {
        val firstChild = rvGrid.getChildAt(0)
        if (firstChild != null) {
            firstChild.requestFocus()
        } else {
            rvGrid.requestFocus()
        }
    }

    private fun showDropdown(
        anchor: TextView,
        options: List<FilterOption>,
        selectedValue: Any?,
        onSelected: (FilterOption) -> Unit
    ) {
        // SECURE FOCUS: Dismiss any existing popup immediately to prevent overlapping focus states
        activePopup?.dismiss()

        val linearLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, dp(8))
            clipChildren = false
            clipToPadding = false
        }

        val scrollView = ScrollView(this).apply {
            isScrollbarFadingEnabled = false
            clipChildren = false
            clipToPadding = false
            addView(linearLayout)
        }

        val initialPosition = options.indexOfFirst { it.value == selectedValue }.coerceAtLeast(0)
        var initialViewToFocus: View? = null

        options.forEachIndexed { index, option ->
            val selected = option.value == selectedValue
            val textView = TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(DROPDOWN_ROW_HEIGHT_DP)).apply {
                    setMargins(dp(8), dp(2), dp(8), dp(2)) // Add some margin so scaling doesn't overlap
                }
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), 0, dp(16), 0)
                textSize = 16f
                isFocusable = true
                isFocusableInTouchMode = true
                text = if (selected) "\u2713 ${option.label}" else option.label
                typeface = anchor.typeface // standard btnType fallback
                background = ColorDrawable(Color.TRANSPARENT)
                setTextColor(Color.parseColor("#B3FFFFFF"))
                
                setOnClickListener {
                    // SECURE FOCUS: Safely dismiss popup and synchronously return focus to the anchor button
                    activePopup?.dismiss()
                    anchor.requestFocus()
                    val sanitizedLabel = option.label.replace("\u2713 ", "")
                    onSelected(option.copy(label = sanitizedLabel))
                }
                
                setOnKeyListener { _, keyCode, event ->
                    if (event.action == KeyEvent.ACTION_DOWN) {
                        when (keyCode) {
                            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                                callOnClick()
                                return@setOnKeyListener true
                            }
                            KeyEvent.KEYCODE_DPAD_UP -> {
                                // SECURE FOCUS: If at the top of the list, pressing UP dismisses the menu and safely returns to anchor
                                if (index == 0) {
                                    activePopup?.dismiss()
                                    anchor.requestFocus()
                                    return@setOnKeyListener true
                                }
                            }
                            KeyEvent.KEYCODE_DPAD_DOWN -> {
                                // SECURE FOCUS: If at the bottom, intercept DOWN to prevent focus from falling into the black hole of the underlying layout
                                if (index == options.size - 1) {
                                    return@setOnKeyListener true
                                }
                            }
                            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                                // SECURE FOCUS: Block horizontal navigation completely so focus doesn't mysteriously jump to background elements
                                return@setOnKeyListener true
                            }
                        }
                    }
                    false
                }
                
                setOnFocusChangeListener { view, hasFocus ->
                    if (hasFocus) {
                        view.background = dropdownRowFocusSelector()
                        setTextColor(Color.parseColor("#000000"))
                        setTypeface(typeface, android.graphics.Typeface.BOLD)
                        view.animate().scaleX(1.05f).scaleY(1.05f).translationZ(10f).setDuration(150).start()
                    } else {
                        view.background = ColorDrawable(Color.TRANSPARENT)
                        setTextColor(Color.parseColor("#B3FFFFFF"))
                        setTypeface(typeface, android.graphics.Typeface.NORMAL)
                        view.animate().scaleX(1.0f).scaleY(1.0f).translationZ(0f).setDuration(150).start()
                    }
                }
            }
            linearLayout.addView(textView)
            if (index == initialPosition) initialViewToFocus = textView
        }

        val popupWidth = maxOf(anchor.width, dp(220))
        val popupHeight = minOf(dp(360), options.size * dp(DROPDOWN_ROW_HEIGHT_DP))
        activePopup = PopupWindow(scrollView, popupWidth, popupHeight, true).apply {
            isFocusable = true
            setBackgroundDrawable(ContextCompat.getDrawable(this@DebridDiscoverActivity, R.drawable.bg_dropdown_smooth))
            animationStyle = R.style.DropdownAnimationStyle
            isOutsideTouchable = false
            setOnDismissListener {
                // SECURE FOCUS: Guarantee that no matter how the popup closes (Back button, loss of focus), 
                // the focus shifts back to the specific selector button that spawned it.
                activePopup = null
                anchor.requestFocus()
            }
            showAsDropDown(anchor, 0, dp(6), Gravity.NO_GRAVITY)
        }
        
        // SECURE FOCUS: Post the initial focus request to the layout queue so it triggers *after* the popup window is fully attached to the screen.
        // This eliminates the race condition where focus drops back to the underlying activity while the popup is still rendering.
        scrollView.post {
            initialViewToFocus?.requestFocus()
        }
    }

    private fun onItemClick(item: CatalogItem) {
        if (item.type == TYPE_MOVIE) {
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

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()



    private fun dropdownRowFocusSelector(): StateListDrawable {
        val focused = GradientDrawable().apply {
            setColor(Color.parseColor("#00E5FF"))
            cornerRadius = dp(34).toFloat()
        }
        val transparent = ColorDrawable(Color.TRANSPARENT)
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), focused)
            addState(intArrayOf(android.R.attr.state_focused), focused)
            addState(intArrayOf(android.R.attr.state_selected), focused)
            addState(intArrayOf(android.R.attr.state_activated), focused)
            addState(intArrayOf(), transparent)
        }
    }

    private fun applySelectorFocusStyle(selector: TextView, focused: Boolean) {
        selector.background = GradientDrawable().apply {
            setColor(
                ContextCompat.getColor(
                    this@DebridDiscoverActivity,
                    if (focused) R.color.white_opacity_50 else R.color.white_opacity_10
                )
            )
            setStroke(
                dp(if (focused) 2 else 1),
                ContextCompat.getColor(
                    this@DebridDiscoverActivity,
                    if (focused) R.color.accent_blue else R.color.white_opacity_30
                )
            )
            cornerRadius = dp(16).toFloat()
        }
    }

    data class FilterOption(val label: String, val value: Any?)

    companion object {
        const val EXTRA_TYPE = "extra_type"
        const val EXTRA_GENRE_ID = "extra_genre_id"
        const val EXTRA_CATALOGUE = "extra_catalogue"
        const val EXTRA_LANGUAGE = "extra_language"
        const val EXTRA_REGION = "extra_region"
        const val EXTRA_RELEASE_DATE_GTE = "extra_release_date_gte"
        private const val TYPE_MOVIE = "movie"
        private const val TYPE_SERIES = "series"
        private const val GENRE_ALL = "All Genres"
        private const val YEAR_ALL = "All Years"
        private const val KEY_LAST_FOCUS_POS = "last_focus_pos"
        private const val DROPDOWN_ROW_HEIGHT_DP = 52

        fun createIntent(context: Context, type: String? = null, genreId: Int? = null, catalogue: String? = null): Intent =
            Intent(context, DebridDiscoverActivity::class.java).apply {
                type?.let { putExtra(EXTRA_TYPE, it) }
                genreId?.let { putExtra(EXTRA_GENRE_ID, it) }
                catalogue?.let { putExtra(EXTRA_CATALOGUE, it) }
            }
    }
}
