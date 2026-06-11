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
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.ListView
import android.widget.PopupWindow
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
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

    private var selectedType = TYPE_MOVIE
    private var selectedCatalogue = AddonCatalogRepository.CATALOGUE_POPULAR
    private var selectedGenreId: Int? = null
    private var selectedGenreLabel = GENRE_ALL
    private var selectedYear: Int? = null
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

        intent.getStringExtra(EXTRA_TYPE)?.let { selectedType = it }
        selectedGenreId = intent.getIntExtra(EXTRA_GENRE_ID, -1).takeIf { it != -1 }

        initViews()
        setupGrid()
        setupSelectors()

        if (intent.hasExtra(EXTRA_TYPE) || intent.hasExtra(EXTRA_GENRE_ID)) {
            viewModel.setType(selectedType)
            viewModel.setGenre(selectedGenreId)
        }

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
                if (hasFocus) lastFocusedSelector = textView
            }
            selector.setOnKeyListener { view, keyCode, event ->
                if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                    focusFirstPosterOrGrid()
                    return@setOnKeyListener true
                }
                false
            }
        }

        btnType.setOnClickListener {
            showDropdown(
                anchor = btnType,
                options = listOf(FilterOption("Movies", TYPE_MOVIE), FilterOption("Series", TYPE_SERIES)),
                selectedValue = selectedType
            ) { option ->
                if (selectedType != option.value) {
                    val type = option.value as String
                    selectedType = type
                    selectedGenreId = null
                    selectedGenreLabel = GENRE_ALL
                    queueSelectorFocus(btnType)
                    viewModel.setType(type)
                }
                updateSelectorText()
            }
        }

        btnCatalogue.setOnClickListener {
            showDropdown(
                anchor = btnCatalogue,
                options = viewModel.catalogueOptions.map { FilterOption(it.label, it.value) },
                selectedValue = selectedCatalogue
            ) { option ->
                if (selectedCatalogue != option.value) {
                    val catalogue = option.value as String
                    selectedCatalogue = catalogue
                    queueSelectorFocus(btnCatalogue)
                    viewModel.setCatalogue(catalogue)
                }
                updateSelectorText()
            }
        }

        btnGenre.setOnClickListener {
            val options = buildList {
                add(FilterOption(GENRE_ALL, null))
                currentGenres.forEach { genre ->
                    val id = genre.id ?: return@forEach
                    add(FilterOption(genre.name ?: id.toString(), id))
                }
            }
            showDropdown(anchor = btnGenre, options = options, selectedValue = selectedGenreId) { option ->
                if (selectedGenreId != option.value) {
                    selectedGenreId = option.value as Int?
                    selectedGenreLabel = option.label
                    queueSelectorFocus(btnGenre)
                    viewModel.setGenre(selectedGenreId)
                }
                updateSelectorText()
            }
        }

        btnYear.setOnClickListener {
            val options = viewModel.yearOptions.map { year ->
                FilterOption(year?.toString() ?: YEAR_ALL, year)
            }
            showDropdown(anchor = btnYear, options = options, selectedValue = selectedYear) { option ->
                if (selectedYear != option.value) {
                    selectedYear = option.value as Int?
                    queueSelectorFocus(btnYear)
                    viewModel.setYear(selectedYear)
                }
                updateSelectorText()
            }
        }

        updateSelectorText()
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            viewModel.genres.collect { genres ->
                currentGenres = genres
                val selectedGenre = genres.firstOrNull { it.id == selectedGenreId }
                if (selectedGenreId != null && selectedGenre == null) {
                    selectedGenreId = null
                    selectedGenreLabel = GENRE_ALL
                } else if (selectedGenre != null) {
                    selectedGenreLabel = selectedGenre.name ?: GENRE_ALL
                }
                updateSelectorText()
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

    private fun updateSelectorText() {
        btnType.text = "Type: ${if (selectedType == TYPE_MOVIE) "Movies" else "Series"}"
        btnCatalogue.text = "Catalogue: ${catalogueLabel(selectedCatalogue)}"
        btnGenre.text = "Genre: $selectedGenreLabel"
        btnYear.text = "Year: ${selectedYear?.toString() ?: YEAR_ALL}"
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
        activePopup?.dismiss()

        val listView = ListView(this).apply {
            divider = null
            isFocusable = true
            isFocusableInTouchMode = true
            choiceMode = ListView.CHOICE_MODE_SINGLE
            selector = dropdownRowFocusSelector()
            setDrawSelectorOnTop(false)
            adapter = DropdownAdapter(options, selectedValue) { position ->
                activePopup?.dismiss()
                anchor.post { anchor.requestFocus() }
                val option = options[position]
                val sanitizedLabel = option.label.replace("\u2713 ", "")
                onSelected(option.copy(label = sanitizedLabel))
            }
            // Keep as fallback just in case
            setOnItemClickListener { _, _, position, _ ->
                activePopup?.dismiss()
                anchor.post { anchor.requestFocus() }
                val option = options[position]
                val sanitizedLabel = option.label.replace("\u2713 ", "")
                onSelected(option.copy(label = sanitizedLabel))
            }
            setOnKeyListener { _, keyCode, event ->
                if (event.action == KeyEvent.ACTION_DOWN) {
                    when (keyCode) {
                        KeyEvent.KEYCODE_BACK -> {
                            activePopup?.dismiss()
                            return@setOnKeyListener true
                        }
                        KeyEvent.KEYCODE_DPAD_LEFT,
                        KeyEvent.KEYCODE_DPAD_RIGHT -> return@setOnKeyListener true
                        KeyEvent.KEYCODE_DPAD_CENTER,
                        KeyEvent.KEYCODE_ENTER,
                        KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                            val position = selectedItemPosition
                            if (position in options.indices) {
                                activePopup?.dismiss()
                                anchor.post { anchor.requestFocus() }
                                val option = options[position]
                                val sanitizedLabel = option.label.replace("\u2713 ", "")
                                onSelected(option.copy(label = sanitizedLabel))
                                return@setOnKeyListener true
                            }
                        }
                    }
                }
                false
            }
        }

        val initialPosition = options.indexOfFirst { it.value == selectedValue }.coerceAtLeast(0)
        listView.setItemChecked(initialPosition, true)
        listView.setSelection(initialPosition)

        val popupWidth = maxOf(anchor.width, dp(220))
        val popupHeight = minOf(dp(360), options.size * dp(DROPDOWN_ROW_HEIGHT_DP))
        activePopup = PopupWindow(listView, popupWidth, popupHeight, true).apply {
            isFocusable = true
            setBackgroundDrawable(ContextCompat.getDrawable(this@DebridDiscoverActivity, R.drawable.bg_dropdown_smooth))
            animationStyle = R.style.DropdownAnimationStyle
            isOutsideTouchable = false
            setOnDismissListener {
                activePopup = null
                anchor.post { anchor.requestFocus() }
            }
            showAsDropDown(anchor, 0, dp(6), Gravity.NO_GRAVITY)
        }
        listView.post {
            listView.setItemChecked(initialPosition, true)
            listView.setSelection(initialPosition)
            listView.requestFocus()
            listView.post {
                listView.setSelection(initialPosition)
            }
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

    private inner class DropdownAdapter(
        private val options: List<FilterOption>,
        private val selectedValue: Any?,
        private val onItemClicked: (Int) -> Unit
    ) : BaseAdapter() {
        override fun getCount(): Int = options.size
        override fun getItem(position: Int): FilterOption = options[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val context = parent?.context ?: this@DebridDiscoverActivity
            val textView = (convertView as? TextView) ?: TextView(context).apply {
                layoutParams = AbsListView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dp(DROPDOWN_ROW_HEIGHT_DP)
                )
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), 0, dp(16), 0)
                textSize = 16f
                isFocusable = true
                isFocusableInTouchMode = false
                background = ColorDrawable(Color.TRANSPARENT)
                
                val textStates = arrayOf(
                    intArrayOf(android.R.attr.state_pressed),
                    intArrayOf(android.R.attr.state_focused),
                    intArrayOf(android.R.attr.state_selected),
                    intArrayOf(android.R.attr.state_activated),
                    intArrayOf()
                )
                val textColors = intArrayOf(
                    Color.parseColor("#FFFFFF"),
                    Color.parseColor("#FFFFFF"),
                    Color.parseColor("#FFFFFF"),
                    Color.parseColor("#FFFFFF"),
                    Color.parseColor("#B3FFFFFF")
                )
                setTextColor(ColorStateList(textStates, textColors))
            }

            val option = options[position]
            val selected = option.value == selectedValue
            textView.text = if (selected) "\u2713 ${option.label}" else option.label
            textView.typeface = btnType.typeface // Reusing safe Jakarta typeface from audit pass

            textView.setOnClickListener {
                onItemClicked(position)
            }

            return textView
        }
    }



    private fun dropdownRowFocusSelector(): StateListDrawable {
        val focused = GradientDrawable().apply {
            setColor(ContextCompat.getColor(this@DebridDiscoverActivity, R.color.white_opacity_50))
            setStroke(dp(2), ContextCompat.getColor(this@DebridDiscoverActivity, R.color.accent_blue))
            cornerRadius = dp(8).toFloat()
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
        private const val TYPE_MOVIE = "movie"
        private const val TYPE_SERIES = "series"
        private const val GENRE_ALL = "All Genres"
        private const val YEAR_ALL = "All Years"
        private const val KEY_LAST_FOCUS_POS = "last_focus_pos"
        private const val DROPDOWN_ROW_HEIGHT_DP = 52

        fun createIntent(context: Context, type: String? = null, genreId: Int? = null): Intent =
            Intent(context, DebridDiscoverActivity::class.java).apply {
                type?.let { putExtra(EXTRA_TYPE, it) }
                genreId?.let { putExtra(EXTRA_GENRE_ID, it) }
            }
    }
}
