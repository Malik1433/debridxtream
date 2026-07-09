package com.tvonnet.debridxtreamiptv.ui.series

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import androidx.fragment.app.DialogFragment
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.WindowManager
import com.tvonnet.debridxtreamiptv.R
import com.tvonnet.debridxtreamiptv.data.repository.DebridCacheStatus
import com.tvonnet.debridxtreamiptv.data.repository.MovieSource
import com.tvonnet.debridxtreamiptv.ui.sources.SizeFilterAdapter
import com.tvonnet.debridxtreamiptv.ui.sources.SizeFilterOption
import com.tvonnet.debridxtreamiptv.ui.sources.SourceFilterState
import com.tvonnet.debridxtreamiptv.ui.sources.SourceFilterUtils
import com.tvonnet.debridxtreamiptv.ui.sources.SourceSorter
import com.tvonnet.debridxtreamiptv.ui.vod.MovieSourceAdapter

class SourceSelectionBottomSheet(
    private val onSourceSelected: (MovieSource, List<String>) -> Unit,
    private val onStateChanged: ((SourceFilterState) -> Unit)? = null,
    initialState: SourceFilterState = SourceFilterState(),
    initialSelectedStreamId: String? = null,
    initialReturnFocusStreamIds: List<String> = emptyList(),
    private val contentTitle: String? = null,
    private val backdropUrl: String? = null,
    private val preferredAudioLang: String = "ALL"
) : DialogFragment() {

    private lateinit var tvTitle: TextView
    private lateinit var tvSourceCount: TextView
    private lateinit var chipLangPriority: View
    private lateinit var tvLangPriority: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var tvStatus: TextView
    private lateinit var rvSources: RecyclerView
    private lateinit var sourcesAdapter: MovieSourceAdapter
    private lateinit var layoutSourceFilters: View
    private lateinit var chipQuality: TextView
    private lateinit var chipLanguage: TextView
    private lateinit var chipType: TextView

    private var allSources: List<MovieSource> = emptyList()
    private var displayedSources: List<MovieSource> = emptyList()
    private var filterState: SourceFilterState = initialState
    private var dynamicLanguageOptions: Array<String> = arrayOf("All")
    private var dynamicTypeOptions: Array<String> = arrayOf("All")
    private var isLoading: Boolean = true
    private var statusMessage: String? = null
    private var pendingSources: List<MovieSource>? = null
    private var selectedStreamId: String? = initialSelectedStreamId
    private var focusedStreamId: String? = initialSelectedStreamId
    private var pendingReturnFocusStreamIds: List<String> = initialReturnFocusStreamIds
    private var needsFocusRestore: Boolean = true
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NORMAL, android.R.style.Theme_Translucent_NoTitleBar)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.dialog_source_selection, container, false)
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.apply {
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setGravity(Gravity.END)
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            decorView.setPadding(0, 0, 0, 0)
            addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
            setWindowAnimations(R.style.SourcePanelAnimation)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        tvTitle = view.findViewById(R.id.tv_dialog_title)
        tvSourceCount = view.findViewById(R.id.tv_source_count)
        chipLangPriority = view.findViewById(R.id.chip_lang_priority)
        tvLangPriority = view.findViewById(R.id.tv_lang_priority)
        progressBar = view.findViewById(R.id.progress_bar)
        tvStatus = view.findViewById(R.id.tv_status)
        rvSources = view.findViewById(R.id.rv_sources)
        layoutSourceFilters = view.findViewById(R.id.layout_source_filters)
        chipQuality = view.findViewById(R.id.chip_quality)
        chipLanguage = view.findViewById(R.id.chip_language)
        chipType = view.findViewById(R.id.chip_type)

        setupAdapters()
        setupContentInfo()
        updateUi()
        pendingSources?.let { sources ->
            pendingSources = null
            showSources(sources)
        }
    }

    private fun setupContentInfo() {
        tvTitle.text = contentTitle.orEmpty()
        tvTitle.visibility = if (contentTitle.isNullOrBlank()) View.GONE else View.VISIBLE

        // Language priority chip — only when a specific language is preferred.
        if (hasLanguagePriority()) {
            tvLangPriority.text = "${languagePriorityCode()} PRIORITY"
            chipLangPriority.visibility = View.VISIBLE
        } else {
            chipLangPriority.visibility = View.GONE
        }
    }

    private fun hasLanguagePriority(): Boolean {
        val code = preferredAudioLang.trim()
        return code.isNotEmpty() && !code.equals("ALL", ignoreCase = true)
    }

    private fun languagePriorityCode(): String = preferredAudioLang.trim().uppercase()

    private fun setupAdapters() {
        // Sources Adapter
        sourcesAdapter = MovieSourceAdapter(
            onSourceFocused = { source ->
                focusedStreamId = source.stream.stream_id
            },
            onSourceClicked = { source ->
                val selectedId = source.stream.stream_id
                selectedStreamId = selectedId
                val adjacentStreamIds = selectedId?.let { streamId ->
                    buildAdjacentReturnFocusStreamIds(
                        displayedSources.mapNotNull { it.stream.stream_id },
                        streamId
                    )
                }.orEmpty()
                onSourceSelected(source, adjacentStreamIds)
                dismiss()
            },
            onNavigateUpFromFirstRow = { focusTopControl() }
        )
        rvSources.layoutManager = LinearLayoutManager(context)
        rvSources.setHasFixedSize(true)
        rvSources.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        rvSources.adapter = sourcesAdapter

        setupChips()
    }

    private fun setupChips() {
        chipQuality.setOnClickListener {
            showFilterDropdown(chipQuality, SourceFilterUtils.QUALITY_OPTIONS, filterState.preferredQuality ?: "All") { selected ->
                setFilterStateAndApply(filterState.copy(preferredQuality = selected.takeIf { it != "All" }))
            }
        }

        chipLanguage.setOnClickListener {
            showFilterDropdown(chipLanguage, dynamicLanguageOptions, filterState.preferredLanguage ?: "All") { selected ->
                setFilterStateAndApply(filterState.copy(preferredLanguage = selected.takeIf { it != "All" }))
            }
        }

        chipType.setOnClickListener {
            refreshDynamicTypeOptions()
            showFilterDropdown(chipType, dynamicTypeOptions, filterState.preferredType ?: "All") { selected ->
                setFilterStateAndApply(filterState.copy(preferredType = selected.takeIf { it != "All" }))
            }
        }
    }

    private fun showFilterDropdown(anchor: View, options: Array<String>, currentSelection: String, onSelected: (String) -> Unit) {
        val context = context ?: return
        val listPopupWindow = android.widget.ListPopupWindow(context)
        listPopupWindow.anchorView = anchor
        listPopupWindow.width = (260 * resources.displayMetrics.density).toInt()
        listPopupWindow.isModal = true

        val adapter = object : android.widget.ArrayAdapter<String>(context, android.R.layout.simple_list_item_1, options) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = super.getView(position, convertView, parent) as TextView
                val item = getItem(position)
                if (item == currentSelection) {
                    view.text = "$item ✓"
                } else {
                    view.text = item
                }
                return view
            }
        }

        listPopupWindow.setAdapter(adapter)
        listPopupWindow.setOnItemClickListener { _, _, position, _ ->
            onSelected(options[position])
            listPopupWindow.dismiss()
        }
        
        listPopupWindow.show()
    }

    fun showLoading(message: String = "Loading sources...") {
        isLoading = true
        statusMessage = message
        if (isAdded) {
            updateUi()
        }
    }

    fun showSources(newSources: List<MovieSource>) {
        if (view == null) {
            pendingSources = newSources
            return
        }

        isLoading = false
        // Sort by preferred-language score BEFORE any filters are applied so the
        // ordering is stable regardless of the in-panel filter chips.
        allSources = SourceSorter.sort(newSources, preferredAudioLang)

        if (allSources.isEmpty()) {
            displayedSources = emptyList()
            focusedStreamId = null
            statusMessage = getString(R.string.movie_detail_sources_empty)
            dynamicTypeOptions = arrayOf("All")
            layoutSourceFilters.visibility = View.GONE
            updateUi()
            return
        }

        val previousState = filterState
        dynamicLanguageOptions = extractDynamicLanguageOptions(allSources)
        
        val currentLang = filterState.preferredLanguage
        if (currentLang != null && currentLang != "All" && !dynamicLanguageOptions.contains(currentLang)) {
            filterState = filterState.copy(preferredLanguage = null)
        }

        refreshDynamicTypeOptions()
        if (filterState != previousState) {
            onStateChanged?.invoke(filterState)
        }
        updateFilterOptions()
        applyFilters()
    }

    private fun extractDynamicLanguageOptions(sources: List<MovieSource>): Array<String> {
        val languages = mutableSetOf<String>()
        var hasUnknown = false
        sources.forEach { source ->
            val langs = source.languages ?: emptyList()
            if (langs.isEmpty()) {
                hasUnknown = true
            } else {
                langs.forEach { lang ->
                    languages.add(lang.lowercase().trim())
                }
            }
        }

        val mappedLangs = languages.map { SourceFilterUtils.mapLanguageCodeToName(it) }.toMutableSet()

        val finalOptions = mutableListOf("All")
        if (mappedLangs.contains("Multi")) {
            finalOptions.add("Multi")
            mappedLangs.remove("Multi")
        }

        val sortedLangs = mappedLangs.sorted().toMutableList()
        finalOptions.addAll(sortedLangs)

        if (hasUnknown) {
            finalOptions.add("Unknown")
        }
        return finalOptions.toTypedArray()
    }

    // Removed mapLanguageCodeToName, now using SourceFilterUtils.mapLanguageCodeToName

    private fun setFilterStateAndApply(newState: SourceFilterState) {
        val languageChanged = filterState.preferredLanguage != newState.preferredLanguage
        filterState = newState
        if (languageChanged) {
            selectedStreamId = null
        }
        needsFocusRestore = true
        refreshDynamicTypeOptions()
        onStateChanged?.invoke(filterState)
        updateFilterOptions()
        applyFilters()
    }

    private fun refreshDynamicTypeOptions() {
        val preTypeSources = SourceFilterUtils.filter(allSources, filterState.copy(preferredType = null))
        dynamicTypeOptions = buildDynamicTypeOptions(preTypeSources)

        val selectedType = filterState.preferredType
        if (selectedType != null && !dynamicTypeOptions.contains(selectedType)) {
            filterState = filterState.copy(preferredType = null)
        }
    }

    private fun buildDynamicTypeOptions(sources: List<MovieSource>): Array<String> {
        // Filter by actual source names (addon / provider / IPTV) so users can
        // isolate any single source; "RD Cached" stays as a cross-source shortcut.
        val options = mutableListOf("All")
        if (sources.any { it.cacheStatus == DebridCacheStatus.VERIFIED_CACHED }) {
            options.add("RD Cached")
        }
        sources.map { SourceFilterUtils.sourceDisplayName(it) }
            .distinct()
            .sortedBy { it.lowercase() }
            .forEach { options.add(it) }
        return options.toTypedArray()
    }

    private fun updateFilterOptions() {
        chipQuality.text = "Quality: ${filterState.preferredQuality ?: "All"} ▼"
        chipLanguage.text = "Language: ${filterState.preferredLanguage ?: "All"} ▼"
        chipType.text = "Source: ${filterState.preferredType ?: "All"} ▼"
        
        layoutSourceFilters.visibility = View.VISIBLE
    }

    private fun applyFilters() {
        // Order-preserving filter keeps the SourceSorter ranking intact.
        displayedSources = SourceFilterUtils.filter(allSources, filterState)

        statusMessage = if (displayedSources.isEmpty()) getString(R.string.source_filters_empty) else null
        if (isAdded) {
            updateUi()
        }
    }

    fun showError(message: String) {
        isLoading = false
        allSources = emptyList()
        displayedSources = emptyList()
        dynamicTypeOptions = arrayOf("All")
        focusedStreamId = null
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
            tvSourceCount.visibility = View.GONE
        } else {
            progressBar.visibility = View.GONE
            if (displayedSources.isNotEmpty()) {
                tvStatus.visibility = View.GONE
                rvSources.visibility = View.VISIBLE
                tvSourceCount.visibility = View.VISIBLE
                val cachedCount = displayedSources.count {
                    it.cacheStatus == DebridCacheStatus.VERIFIED_CACHED ||
                        it.cacheStatus == DebridCacheStatus.DIRECT_STREAM
                }
                tvSourceCount.text = if (hasLanguagePriority()) {
                    "${displayedSources.size} sources · sorted by ${languagePriorityCode()} priority"
                } else {
                    "${displayedSources.size} sources found · $cachedCount cached"
                }
                // First cached source after sorting = BEST PICK.
                val bestStreamId = displayedSources
                    .firstOrNull { SourceSorter.isCached(it) }
                    ?.stream?.stream_id
                sourcesAdapter.updateBestPick(bestStreamId)
                sourcesAdapter.updateSelection(selectedStreamId, notify = false)
                sourcesAdapter.submitList(displayedSources) {
                    sourcesAdapter.updateSelection(selectedStreamId)
                    val visibleStreamIds = displayedSources.mapNotNull { it.stream.stream_id }
                    val shouldRestoreFocus = needsFocusRestore || (
                        rvSources.hasFocus() &&
                            focusedStreamId != null &&
                            !visibleStreamIds.contains(focusedStreamId)
                        )
                    if (displayedSources.isNotEmpty() && shouldRestoreFocus) {
                        val targetStreamId = resolveRestoreFocusStreamId(
                            visibleStreamIds = visibleStreamIds,
                            focusedStreamId = focusedStreamId,
                            returnFocusStreamIds = pendingReturnFocusStreamIds,
                            selectedStreamId = selectedStreamId
                        )
                        val targetIndex = targetStreamId
                            ?.let { streamId ->
                                displayedSources.indexOfFirst { it.stream.stream_id == streamId }
                            }
                            ?.takeIf { it >= 0 }
                            ?: 0
                        pendingReturnFocusStreamIds = emptyList()
                        needsFocusRestore = false
                        rvSources.scrollToPosition(targetIndex)
                        rvSources.post {
                            val holder = rvSources.findViewHolderForAdapterPosition(targetIndex)
                            if (holder != null) {
                                holder.itemView.requestFocus()
                            } else {
                                focusTopControl()
                            }
                        }
                    }
                }
            } else {
                tvStatus.visibility = View.VISIBLE
                tvStatus.text = statusMessage ?: getString(R.string.source_filters_empty)
                rvSources.visibility = View.GONE
                tvSourceCount.visibility = View.GONE
                needsFocusRestore = false
                rvSources.post {
                    focusEmptyState()
                }
            }
        }
    }

    private fun focusTopControl(): Boolean {
        val topControl = sequenceOf(chipQuality, chipLanguage, chipType)
            .firstOrNull { it.visibility == View.VISIBLE && it.isFocusable && it.isEnabled }
            ?: tvStatus.takeIf { it.visibility == View.VISIBLE && it.isFocusable && it.isEnabled }
        return topControl?.requestFocus() == true
    }

    private fun focusEmptyState() {
        if (layoutSourceFilters.visibility == View.VISIBLE && focusTopControl()) {
            return
        }
        if (tvStatus.visibility == View.VISIBLE) {
            tvStatus.requestFocus()
        }
    }

    companion object {
        const val TAG = "SourceSelectionBottomSheet"

        internal fun buildAdjacentReturnFocusStreamIds(
            visibleStreamIds: List<String>,
            selectedStreamId: String
        ): List<String> {
            val selectedIndex = visibleStreamIds.indexOf(selectedStreamId)
            if (selectedIndex < 0) return emptyList()
            return listOfNotNull(
                visibleStreamIds.getOrNull(selectedIndex + 1),
                visibleStreamIds.getOrNull(selectedIndex - 1)
            ).distinct()
        }

        internal fun resolveReturnFocusStreamId(
            visibleStreamIds: List<String>,
            returnFocusStreamIds: List<String>,
            selectedStreamId: String?
        ): String? {
            return returnFocusStreamIds.firstOrNull { visibleStreamIds.contains(it) }
                ?: selectedStreamId?.takeIf { visibleStreamIds.contains(it) }
                ?: visibleStreamIds.firstOrNull()
        }

        internal fun resolveRestoreFocusStreamId(
            visibleStreamIds: List<String>,
            focusedStreamId: String?,
            returnFocusStreamIds: List<String>,
            selectedStreamId: String?
        ): String? {
            return focusedStreamId?.takeIf { visibleStreamIds.contains(it) }
                ?: resolveReturnFocusStreamId(
                    visibleStreamIds = visibleStreamIds,
                    returnFocusStreamIds = returnFocusStreamIds,
                    selectedStreamId = selectedStreamId
                )
        }
    }
}
