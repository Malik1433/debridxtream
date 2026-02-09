package com.tvonnet.debridxtreamiptv.ui.series

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.tvonnet.debridxtreamiptv.R
import com.tvonnet.debridxtreamiptv.data.repository.MovieSource
import com.tvonnet.debridxtreamiptv.ui.sources.SizeFilterAdapter
import com.tvonnet.debridxtreamiptv.ui.sources.SizeFilterOption
import com.tvonnet.debridxtreamiptv.ui.sources.SourceFilterState
import com.tvonnet.debridxtreamiptv.ui.sources.SourceFilterUtils
import com.tvonnet.debridxtreamiptv.ui.vod.MovieSourceAdapter

class SourceSelectionBottomSheet(
    private val onSourceSelected: (MovieSource) -> Unit,
    private val onStateChanged: ((SourceFilterState) -> Unit)? = null,
    initialState: SourceFilterState = SourceFilterState(),
    initialSelectedStreamId: String? = null
) : BottomSheetDialogFragment() {

    private lateinit var tvTitle: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var tvStatus: TextView
    private lateinit var rvSources: RecyclerView
    private lateinit var sourcesAdapter: MovieSourceAdapter
    private lateinit var layoutSourceFilters: View
    private lateinit var btnCachedOnly: TextView
    private lateinit var rvSizeFilters: RecyclerView
    private lateinit var rvLanguageFilters: RecyclerView
    private lateinit var languageAdapter: LanguageFilterAdapter
    private lateinit var sizeFilterAdapter: SizeFilterAdapter

    private var allSources: List<MovieSource> = emptyList()
    private var displayedSources: List<MovieSource> = emptyList()
    private var preferredLanguage: String? = initialState.preferredLanguage
    private var selectedSizeOption: SizeFilterOption =
        SourceFilterUtils.SIZE_OPTIONS.firstOrNull { it.maxSizeBytes == initialState.maxSizeBytes }
            ?: SourceFilterUtils.SIZE_OPTIONS.first()
    private var filterState: SourceFilterState = initialState
    private var isLoading: Boolean = true
    private var statusMessage: String? = null
    private var pendingSources: List<MovieSource>? = null
    private var selectedStreamId: String? = initialSelectedStreamId

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.dialog_source_selection, container, false)
    }

    override fun onStart() {
        super.onStart()
        val dialog = dialog as? com.google.android.material.bottomsheet.BottomSheetDialog
        dialog?.findViewById<android.widget.FrameLayout>(com.google.android.material.R.id.design_bottom_sheet)?.let { bottomSheet ->
            val behavior = com.google.android.material.bottomsheet.BottomSheetBehavior.from(bottomSheet)
            behavior.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
            behavior.skipCollapsed = true
            
            val layoutParams = bottomSheet.layoutParams
            layoutParams.height = ViewGroup.LayoutParams.MATCH_PARENT
            bottomSheet.layoutParams = layoutParams
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        tvTitle = view.findViewById(R.id.tv_dialog_title)
        progressBar = view.findViewById(R.id.progress_bar)
        tvStatus = view.findViewById(R.id.tv_status)
        rvSources = view.findViewById(R.id.rv_sources)
        layoutSourceFilters = view.findViewById(R.id.layout_source_filters)
        btnCachedOnly = view.findViewById(R.id.btn_cached_only)
        rvSizeFilters = view.findViewById(R.id.rv_size_filters)
        rvLanguageFilters = view.findViewById(R.id.rv_language_filters)

        setupAdapters()
        updateUi()
        pendingSources?.let { sources ->
            pendingSources = null
            showSources(sources)
        }
    }

    private fun setupAdapters() {
        // Sources Adapter
        sourcesAdapter = MovieSourceAdapter(
            onSourceFocused = { /* Optional: handle focus changes if needed */ },
            onSourceClicked = { source ->
                selectedStreamId = source.stream.stream_id
                onSourceSelected(source)
                dismiss()
            }
        )
        rvSources.layoutManager = LinearLayoutManager(context)
        rvSources.setHasFixedSize(true)
        rvSources.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        rvSources.adapter = sourcesAdapter

        sizeFilterAdapter = SizeFilterAdapter { option ->
            selectedSizeOption = option
            sizeFilterAdapter.updateSelection(option)
            filterState = filterState.copy(maxSizeBytes = option.maxSizeBytes)
            onStateChanged?.invoke(filterState)
            applyFilters()
        }
        rvSizeFilters.layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
        rvSizeFilters.setHasFixedSize(true)
        rvSizeFilters.adapter = sizeFilterAdapter

        btnCachedOnly.setOnClickListener {
            filterState = filterState.copy(cachedOnly = !filterState.cachedOnly)
            btnCachedOnly.isSelected = filterState.cachedOnly
            onStateChanged?.invoke(filterState)
            applyFilters()
        }
        btnCachedOnly.setOnFocusChangeListener { _, hasFocus ->
            btnCachedOnly.isSelected = hasFocus || filterState.cachedOnly
        }

        // Language Filter Adapter
        languageAdapter = LanguageFilterAdapter { language ->
            if (preferredLanguage == language) {
                preferredLanguage = null // Deselect
            } else {
                preferredLanguage = language
            }
            filterState = filterState.copy(preferredLanguage = preferredLanguage)
            onStateChanged?.invoke(filterState)
            languageAdapter.updateSelection(preferredLanguage)
            applyFilters()
        }
        rvLanguageFilters.layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
        rvLanguageFilters.setHasFixedSize(true)
        rvLanguageFilters.adapter = languageAdapter
    }

    fun showLoading(message: String = "Loading sources...") {
        isLoading = true
        statusMessage = message
        if (isAdded) {
            updateUi()
        }
    }

    fun showSources(newSources: List<MovieSource>) {
        isLoading = false
        allSources = newSources
        if (!this::languageAdapter.isInitialized || !this::sizeFilterAdapter.isInitialized) {
            pendingSources = newSources
            return
        }

        if (allSources.isEmpty()) {
            displayedSources = emptyList()
            statusMessage = getString(R.string.movie_detail_sources_empty)
            layoutSourceFilters.visibility = View.GONE
            updateUi()
            return
        }

        updateFilterOptions()
        applyFilters()
    }

    private fun updateFilterOptions() {
        val languages = allSources.flatMap { it.languages ?: listOf("multi") }
            .distinct()
            .sorted()

        val showLanguages = languages.size > 1
        rvLanguageFilters.visibility = if (showLanguages) View.VISIBLE else View.GONE
        if (showLanguages) {
            languageAdapter.submitList(languages)
            if (preferredLanguage != null && languages.none { it.equals(preferredLanguage, ignoreCase = true) }) {
                preferredLanguage = null
                filterState = filterState.copy(preferredLanguage = null)
                onStateChanged?.invoke(filterState)
            }
            languageAdapter.updateSelection(preferredLanguage)
        } else {
            if (preferredLanguage != null) {
                preferredLanguage = null
                filterState = filterState.copy(preferredLanguage = null)
                onStateChanged?.invoke(filterState)
            }
            languageAdapter.updateSelection(null)
            languageAdapter.submitList(emptyList())
        }

        val showSizes = allSources.any { it.sizeBytes != null }
        rvSizeFilters.visibility = if (showSizes) View.VISIBLE else View.GONE
        if (showSizes) {
            selectedSizeOption =
                SourceFilterUtils.SIZE_OPTIONS.firstOrNull { it.maxSizeBytes == filterState.maxSizeBytes }
                    ?: SourceFilterUtils.SIZE_OPTIONS.first()
            sizeFilterAdapter.submitList(SourceFilterUtils.SIZE_OPTIONS)
            sizeFilterAdapter.updateSelection(selectedSizeOption)
        } else {
            if (filterState.maxSizeBytes != null) {
                filterState = filterState.copy(maxSizeBytes = null)
                onStateChanged?.invoke(filterState)
            }
            selectedSizeOption = SourceFilterUtils.SIZE_OPTIONS.first()
            sizeFilterAdapter.updateSelection(null)
            sizeFilterAdapter.submitList(emptyList())
        }

        val showCached = allSources.any { it.isCached != null }
        btnCachedOnly.visibility = if (showCached) View.VISIBLE else View.GONE
        if (!showCached) {
            if (filterState.cachedOnly) {
                filterState = filterState.copy(cachedOnly = false)
                onStateChanged?.invoke(filterState)
            }
            btnCachedOnly.isSelected = false
        } else {
            btnCachedOnly.isSelected = filterState.cachedOnly
        }

        layoutSourceFilters.visibility = if (showCached || showSizes || showLanguages) View.VISIBLE else View.GONE
    }

    private fun applyFilters() {
        displayedSources = SourceFilterUtils.apply(allSources, filterState)

        statusMessage = if (displayedSources.isEmpty()) getString(R.string.source_filters_empty) else null
        if (isAdded) {
            updateUi()
        }
    }

    fun showError(message: String) {
        isLoading = false
        allSources = emptyList()
        displayedSources = emptyList()
        statusMessage = message
        if (isAdded) {
            updateUi()
        }
    }

    private fun updateUi() {
        if (isLoading) {
            progressBar.visibility = View.VISIBLE
            tvStatus.visibility = View.VISIBLE
            tvStatus.text = statusMessage
            rvSources.visibility = View.GONE
            layoutSourceFilters.visibility = View.GONE
        } else {
            progressBar.visibility = View.GONE
            if (displayedSources.isNotEmpty()) {
                tvStatus.visibility = View.GONE
                rvSources.visibility = View.VISIBLE
                sourcesAdapter.updateSelection(selectedStreamId, notify = false)
                sourcesAdapter.submitList(displayedSources) {
                    sourcesAdapter.updateSelection(selectedStreamId)
                    if (displayedSources.isNotEmpty()) {
                        val targetIndex = selectedStreamId
                            ?.let { id -> displayedSources.indexOfFirst { it.stream.stream_id == id } }
                            ?.takeIf { it >= 0 }
                            ?: 0
                        rvSources.scrollToPosition(targetIndex)
                        rvSources.post {
                            val holder = rvSources.findViewHolderForAdapterPosition(targetIndex)
                            if (holder != null) {
                                holder.itemView.requestFocus()
                            } else {
                                rvSources.requestFocus()
                            }
                        }
                    }
                }
            } else {
                tvStatus.visibility = View.VISIBLE
                tvStatus.text = statusMessage ?: getString(R.string.source_filters_empty)
                rvSources.visibility = View.GONE
            }
        }
    }

    companion object {
        const val TAG = "SourceSelectionBottomSheet"
    }
}
