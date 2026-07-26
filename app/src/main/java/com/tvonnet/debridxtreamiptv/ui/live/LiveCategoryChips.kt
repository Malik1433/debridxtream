package com.tvonnet.debridxtreamiptv.ui.live

import com.tvonnet.debridxtreamiptv.data.model.XtreamCategory
import com.tvonnet.debridxtreamiptv.util.FAVORITES_CATEGORY_ID

/**
 * The **decisions** behind the Live category chip strip, lifted out of [LiveCategoryController]
 * (roadmap B3): which chips exist, which survive a search query, and which one takes focus when the
 * user comes back to the screen.
 *
 * The controller needs a Fragment and a RecyclerView, so none of this could be tested in place —
 * yet all three rules are things a user notices immediately (Favourites missing from the strip,
 * search showing chips it should not, focus landing on the wrong category after returning from
 * fullscreen). Behaviour-preserving: the bodies are the controller's, moved verbatim.
 */
object LiveCategoryChips {

    /**
     * Favourites first, then every provider category. A provider that ships its own "favourites"
     * category must not produce a second chip.
     */
    fun buildDisplayCategories(
        favoritesLabel: String,
        providerCategories: List<XtreamCategory>
    ): List<XtreamCategory> {
        val favorites = XtreamCategory(
            category_id = FAVORITES_CATEGORY_ID,
            category_name = favoritesLabel,
            parent_id = null
        )
        return listOf(favorites) + providerCategories.filterNot { it.category_id == FAVORITES_CATEGORY_ID }
    }

    /**
     * While the search pill has a query the strip becomes a category-search result: only matching
     * names survive, and Favourites drops out — it is not a category you jump to by name.
     * An empty query restores the full list.
     */
    fun applyChipFilter(all: List<XtreamCategory>, query: String): List<XtreamCategory> {
        val q = query.trim()
        if (q.isEmpty()) return all
        return all.filter { cat ->
            cat.category_id != FAVORITES_CATEGORY_ID &&
                cat.category_name?.contains(q, ignoreCase = true) == true
        }
    }

    /**
     * Which chip should hold focus: the selected category, else the last focused one, else the last
     * focused *position* if it is still in range, else the first chip. Returning from fullscreen
     * with a stale position must never throw or land off the end of a shrunken list.
     */
    fun focusPositionFor(
        displayCategories: List<XtreamCategory>,
        selectedCategoryId: String?,
        lastFocusedCategoryId: String?,
        lastFocusedCategoryPosition: Int?
    ): Int? {
        if (displayCategories.isEmpty()) return null
        val targetId = selectedCategoryId ?: lastFocusedCategoryId
        return targetId
            ?.let { id -> displayCategories.indexOfFirst { it.category_id == id } }
            ?.takeIf { it >= 0 }
            ?: lastFocusedCategoryPosition?.takeIf { it in displayCategories.indices }
            ?: 0
    }
}
