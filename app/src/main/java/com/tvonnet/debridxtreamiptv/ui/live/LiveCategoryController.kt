package com.tvonnet.debridxtreamiptv.ui.live

import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.RecyclerView
import com.tvonnet.debridxtreamiptv.R
import com.tvonnet.debridxtreamiptv.data.model.XtreamCategory
import com.tvonnet.debridxtreamiptv.util.FAVORITES_CATEGORY_ID

/**
 * LF-4: the **category list + chip strip + category-focus** concern lifted out of [LiveFragment]. Owns
 * the horizontal category chip strip ([rvCategories]) — building the "Favourites + all provider
 * categories" list, filtering it while the search pill has a query, wiring the [SidebarCategoryAdapter],
 * and the category-side D-pad focus (select/scroll-into-view, the focus-strip entry point, and the
 * return-from-fullscreen block/unblock).
 *
 * Owns: [sidebarCategoryAdapter], [displayCategories], [categoryAdapterSet] (read back by the Fragment's
 * `restoreInitialFocusIfNeeded`), the search-driven [categoryChipQuery], and the focus-block flag.
 * Reaches back to the Fragment (which keeps the widely-shared render spine + favourites count) through
 * callbacks: [favoritesCount] (LF-5), [lastUiState], [onBeforeCategorySelect] (collapse the LF-2 search
 * pill on switch), and the render-overlay hooks [onShowEmptyState] / [onShowLoading]. The channel-grid
 * focus path (LF-6, still in the Fragment) pokes [unblockCategoryFocusAfterRestore]; `onResume` pokes
 * [blockCategoryFocusForReturnRestore]; the LF-2 search controller pokes [onChipQueryChanged] /
 * [chipQueryIsNotEmpty]. Behaviour byte-identical to the old private methods; only `this`/field refs
 * were rebound.
 */
class LiveCategoryController(
    private val fragment: Fragment,
    private val rvCategories: RecyclerView,
    private val viewModel: LiveViewModel,
    private val favoritesCount: () -> Int,
    private val lastUiState: () -> LiveUiState?,
    private val onBeforeCategorySelect: () -> Unit,
    private val onShowEmptyState: (String) -> Unit,
    private val onShowLoading: (String) -> Unit,
) {
    private var sidebarCategoryAdapter: SidebarCategoryAdapter? = null
    private var categoryChipQuery: String = ""
    private var categoriesFocusBlockedForRestore = false

    var displayCategories: List<XtreamCategory> = emptyList()
        private set
    var categoryAdapterSet = false
        private set

    private companion object {
        // Mirror of the Fragment's channel-focus retry tuning (shared with LF-6's focusChannelItem).
        const val CHANNEL_FOCUS_RETRY_COUNT = 3
        const val CHANNEL_FOCUS_RETRY_DELAY_MS = 50L
    }

    /**
     * Favourites first, then every provider category. v2 shows them all in the chip strip — the
     * search pill filters channels now, not this list.
     */
    private fun buildDisplayCategories(state: LiveUiState): List<XtreamCategory> {
        val favorites = XtreamCategory(
            category_id = FAVORITES_CATEGORY_ID,
            category_name = fragment.getString(R.string.favorites),
            parent_id = null
        )
        val filtered = state.categories.filterNot { it.category_id == FAVORITES_CATEGORY_ID }
        return listOf(favorites) + filtered
    }

    /**
     * While the search pill has a query, keep only categories whose name matches it so the
     * chip strip becomes a category-search result. Favorites is dropped from the filtered
     * view (it isn't a real category to jump into by name). Empty query → the full list.
     */
    private fun applyCategoryChipFilter(all: List<XtreamCategory>): List<XtreamCategory> {
        val q = categoryChipQuery.trim()
        if (q.isEmpty()) return all
        return all.filter { cat ->
            cat.category_id != FAVORITES_CATEGORY_ID &&
                cat.category_name?.contains(q, ignoreCase = true) == true
        }
    }

    /** The LF-2 search pill changed its query: re-filter the chip strip immediately. */
    fun onChipQueryChanged(query: String) {
        categoryChipQuery = query
        refreshCategoryChips()
    }

    fun chipQueryIsNotEmpty(): Boolean = categoryChipQuery.isNotEmpty()

    /** Re-filter the chip strip when the search query changes (channels are handled separately). */
    private fun refreshCategoryChips() {
        if (categoryAdapterSet) {
            updateCategoryList(lastUiState() ?: viewModel.uiState.value)
        }
    }

    fun updateCategoryList(state: LiveUiState) {
        val categories = applyCategoryChipFilter(buildDisplayCategories(state))
        val listChanged = categories != displayCategories
        if (listChanged) {
            displayCategories = categories
        }
        val counts = state.categoryChannelCounts + (FAVORITES_CATEGORY_ID to favoritesCount())

        if (categories.isNotEmpty() && !categoryAdapterSet) {
            try {
                val categoryAdapter = SidebarCategoryAdapter(
                    categories = categories,
                    channelCounts = counts,
                    initialSelectedCategoryId = state.selectedCategoryId,
                    onCategoryClick = { categoryId ->
                        handleCategoryClick(categoryId)
                    },
                    onCategoryFocused = { categoryId, position ->
                        viewModel.onEvent(LiveEvent.RememberCategoryFocus(categoryId, position))
                    }
                )

                rvCategories.adapter = categoryAdapter
                sidebarCategoryAdapter = categoryAdapter
                categoryAdapterSet = true
                android.util.Log.d(
                    "LiveFragment",
                    "Category adapter set successfully with ${categories.size} categories"
                )
            } catch (e: Exception) {
                android.util.Log.e("LiveFragment", "Error setting category adapter", e)
                onShowEmptyState("Error loading categories: ${e.message}")
            }
        } else if (categoryAdapterSet && listChanged) {
            sidebarCategoryAdapter?.updateCategories(categories, state.selectedCategoryId)
        } else if (categories.isEmpty() && state.isLoadingCategories && state.error == null) {
            onShowLoading(fragment.getString(R.string.live_loading_categories))
        }

        if (categoryAdapterSet) {
            sidebarCategoryAdapter?.setSelectedCategory(state.selectedCategoryId)
            sidebarCategoryAdapter?.updateChannelCounts(counts)
        }
    }

    private fun handleCategoryClick(categoryId: String) {
        // Switching category clears any active channel search, as in the design.
        onBeforeCategorySelect()
        viewModel.onEvent(LiveEvent.SelectCategory(categoryId))
    }

    /** LF-5 favourites bus updates the per-category channel counts on the chip strip. */
    fun updateChannelCounts(counts: Map<String, Int>) {
        sidebarCategoryAdapter?.updateChannelCounts(counts)
    }

    fun blockCategoryFocusForReturnRestore() {
        if (categoriesFocusBlockedForRestore) return
        categoriesFocusBlockedForRestore = true
        rvCategories.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        rvCategories.isFocusable = false
    }

    fun unblockCategoryFocusAfterRestore() {
        if (!categoriesFocusBlockedForRestore) return
        categoriesFocusBlockedForRestore = false
        rvCategories.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        rvCategories.isFocusable = false
        rvCategories.isFocusableInTouchMode = false
    }

    fun focusSelectedCategoryItem(attempt: Int = 0): Boolean {
        if (!fragment.isAdded || displayCategories.isEmpty()) return false
        val state = viewModel.uiState.value
        val targetId = state.selectedCategoryId ?: state.lastFocusedCategoryId
        val targetPosition = targetId
            ?.let { id -> displayCategories.indexOfFirst { it.category_id == id } }
            ?.takeIf { it >= 0 }
            ?: state.lastFocusedCategoryPosition
                ?.takeIf { it in displayCategories.indices }
            ?: 0

        rvCategories.scrollToPosition(targetPosition)
        rvCategories.postDelayed({
            if (!fragment.isAdded) return@postDelayed
            try {
                val itemView = rvCategories.findViewHolderForAdapterPosition(targetPosition)?.itemView
                val focused = itemView?.requestFocus() == true
                if (!focused && attempt < CHANNEL_FOCUS_RETRY_COUNT) {
                    focusSelectedCategoryItem(attempt + 1)
                }
            } catch (e: Exception) {
                android.util.Log.e("LiveFragment", "Error requesting category focus", e)
            }
        }, CHANNEL_FOCUS_RETRY_DELAY_MS)
        return true
    }

    fun focusCategoryStrip(): Boolean {
        rvCategories.post { focusSelectedCategoryItem() }
        return true
    }

    fun isFirstCategoryFocused(): Boolean {
        val focused = rvCategories.findFocus() ?: return false
        val holder = rvCategories.findContainingViewHolder(focused) ?: return false
        return holder.bindingAdapterPosition == 0
    }

    /** onDestroyView teardown: drop the adapter reference + reset the category state. */
    fun clear() {
        sidebarCategoryAdapter = null
        categoryAdapterSet = false
        displayCategories = emptyList()
    }
}
