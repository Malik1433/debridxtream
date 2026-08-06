package com.tvonnet.debridxtreamiptv.ui.series

import com.tvonnet.debridxtreamiptv.R

import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.widget.LinearLayout
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.RecyclerView
import com.tvonnet.debridxtreamiptv.data.model.XtreamCategory
import com.tvonnet.debridxtreamiptv.ui.vod.CategorySidebarAdapter
import com.tvonnet.debridxtreamiptv.utils.FocusCoordinator

/** The four views the Series D-pad map moves focus between. */
class SeriesGridViews(
    val sidebar: RecyclerView,
    val grid: RecyclerView,
    val sortChips: LinearLayout,
    val searchButton: () -> View?,
)

/**
 * C3: the Series screen's **D-pad map and focus memory** — the mirror of `VodFocusController`, with
 * the extras this grid needs.
 *
 * Series carries one rule VOD does not: placeholders are off, so past the last *loaded* row there
 * is no focusable card below and a plain DOWN escapes sideways into the sidebar — the "focus jumps
 * to category on fast scroll" bug. DOWN is therefore consumed at the edge and turned into a scroll
 * nudge, on the CARD (the focused view) rather than the RecyclerView, because the RV's own key
 * listener never fires while a row child holds focus.
 *
 * The two restore jobs stay distinct, as in VOD: [restoreFocusIfPossible] (the user came back to
 * the screen) versus [reassertGridFocus] (data moved under a user already in the grid — CC-1).
 *
 * Bodies moved verbatim from `SeriesFragment`; only `this`/field references were rebound.
 */
class SeriesFocusController(
    private val fragment: Fragment,
    private val views: SeriesGridViews,
    private val adapter: () -> SeriesPagingAdapter,
    private val currentCategories: () -> List<XtreamCategory>,
    private val gridColumnCount: () -> Int,
) {
    private val rvCategoriesSidebar get() = views.sidebar
    private val rvSeriesGrid get() = views.grid
    private val llSortChips get() = views.sortChips

    enum class FocusTarget { CATEGORIES, SERIES }

    var lastFocusTarget: FocusTarget = FocusTarget.SERIES
        private set

    private var lastFocusedCategoryPosition: Int = RecyclerView.NO_POSITION
    private var lastFocusedSeriesPosition: Int = RecyclerView.NO_POSITION
    private var lastFocusedCategoryId: String? = null
    private var lastFocusedSeriesId: String? = null
    private var pendingRestoreFocus: Boolean = false

    private var restoreCategoryPosition: Int = RecyclerView.NO_POSITION
    private var restoreSeriesPosition: Int = RecyclerView.NO_POSITION
    private var restoreCategoryId: String? = null
    private var restoreSeriesId: String? = null
    private var restoreFocusTarget: FocusTarget = FocusTarget.SERIES

    // ── what the adapters report ─────────────────────────────────────────────

    fun onCategoryFocused(position: Int, categoryId: String?) {
        lastFocusTarget = FocusTarget.CATEGORIES
        lastFocusedCategoryPosition = position
        if (categoryId != null) lastFocusedCategoryId = categoryId
    }

    fun onSeriesFocused(position: Int, seriesId: String?) {
        lastFocusTarget = FocusTarget.SERIES
        lastFocusedSeriesPosition = position
        if (seriesId != null) lastFocusedSeriesId = seriesId
    }

    // ── lifecycle ────────────────────────────────────────────────────────────

    fun restoreSavedState(state: Bundle) {
        lastFocusedCategoryPosition = state.getInt(STATE_LAST_CATEGORY_POS, RecyclerView.NO_POSITION)
        lastFocusedSeriesPosition = state.getInt(STATE_LAST_SERIES_POS, RecyclerView.NO_POSITION)
        lastFocusedCategoryId = state.getString(STATE_LAST_CATEGORY_ID)
        lastFocusedSeriesId = state.getString(STATE_LAST_SERIES_ID)
        val focusTargetName = state.getString(STATE_LAST_FOCUS_TARGET) ?: FocusTarget.SERIES.name
        lastFocusTarget = runCatching { FocusTarget.valueOf(focusTargetName) }.getOrDefault(FocusTarget.SERIES)

        restoreCategoryPosition = lastFocusedCategoryPosition
        restoreSeriesPosition = lastFocusedSeriesPosition
        restoreCategoryId = lastFocusedCategoryId
        restoreSeriesId = lastFocusedSeriesId
        restoreFocusTarget = lastFocusTarget
        pendingRestoreFocus =
            lastFocusedCategoryPosition != RecyclerView.NO_POSITION ||
                lastFocusedSeriesPosition != RecyclerView.NO_POSITION
    }

    fun saveState(outState: Bundle) {
        outState.putInt(STATE_LAST_CATEGORY_POS, lastFocusedCategoryPosition)
        outState.putInt(STATE_LAST_SERIES_POS, lastFocusedSeriesPosition)
        outState.putString(STATE_LAST_CATEGORY_ID, lastFocusedCategoryId)
        outState.putString(STATE_LAST_SERIES_ID, lastFocusedSeriesId)
        outState.putString(STATE_LAST_FOCUS_TARGET, lastFocusTarget.name)
    }

    /** Snapshot where the user was, and arm the restore for when they come back. */
    fun onPause() {
        FocusCoordinator.release("SERIES_RESTORE")

        restoreCategoryPosition = lastFocusedCategoryPosition
        restoreSeriesPosition = lastFocusedSeriesPosition
        restoreCategoryId = lastFocusedCategoryId
        restoreSeriesId = lastFocusedSeriesId
        restoreFocusTarget = lastFocusTarget

        pendingRestoreFocus =
            restoreCategoryPosition != RecyclerView.NO_POSITION ||
                restoreSeriesPosition != RecyclerView.NO_POSITION
    }

    // ── returning to the screen ──────────────────────────────────────────────

    fun restoreFocusIfPossible() {
        if (!pendingRestoreFocus) return

        FocusCoordinator.requestFocus("SERIES_RESTORE") {
            when (restoreFocusTarget) {
                FocusTarget.CATEGORIES -> restoreCategoryFocus()
                FocusTarget.SERIES -> restoreSeriesFocus()
            }
        }
    }

    private fun restoreCategoryFocus() {
        val count = rvCategoriesSidebar.adapter?.itemCount ?: 0
        if (count <= 0) return

        val adapter = rvCategoriesSidebar.adapter as? CategorySidebarAdapter
        val position = resolveCategoryPosition(adapterCount = count) ?: return

        rvCategoriesSidebar.post {
            if (!fragment.isAdded) return@post
            rvCategoriesSidebar.scrollToPosition(position)
            adapter?.setSelected(position)
            rvCategoriesSidebar.post {
                if (!fragment.isAdded) return@post
                try {
                    val focused = rvCategoriesSidebar.findViewHolderForAdapterPosition(position)
                        ?.itemView?.requestFocus() == true
                    if (focused) pendingRestoreFocus = false
                } finally {
                    FocusCoordinator.release("SERIES_RESTORE")
                }
            }
        }
    }

    private fun restoreSeriesFocus() {
        val count = adapter().itemCount
        if (count <= 0) return

        val position = resolveSeriesAdapterPosition()?.coerceIn(0, count - 1) ?: return

        rvSeriesGrid.post {
            if (!fragment.isAdded) return@post
            rvSeriesGrid.scrollToPosition(position)
            rvSeriesGrid.post {
                if (!fragment.isAdded) return@post
                try {
                    val focused = rvSeriesGrid.findViewHolderForAdapterPosition(position)
                        ?.itemView?.requestFocus() == true
                    if (focused) pendingRestoreFocus = false
                } finally {
                    FocusCoordinator.release("SERIES_RESTORE")
                }
            }
        }
    }

    private fun resolveCategoryPosition(adapterCount: Int): Int? {
        val resolvedPosition = restoreCategoryId
            ?.let { id -> currentCategories().indexOfFirst { it.category_id == id }.takeIf { it >= 0 } }
            ?: restoreCategoryPosition.takeIf { it != RecyclerView.NO_POSITION }
        return resolvedPosition?.coerceIn(0, adapterCount - 1)
    }

    private fun resolveSeriesAdapterPosition(): Int? {
        restoreSeriesId?.let { targetId ->
            val snapshot = adapter().snapshot()
            val indexInItems = snapshot.items.indexOfFirst { it.series_id == targetId }
            if (indexInItems >= 0) {
                return snapshot.placeholdersBefore + indexInItems
            }
        }
        return restoreSeriesPosition.takeIf { it != RecyclerView.NO_POSITION }
    }

    /**
     * CC-1 (refresh-steals-focus): only re-assert grid focus when the user is ALREADY inside the
     * grid (a paging append / cache refresh shifted positions). If focus is on the sidebar / genre
     * pills / search, a background data change must NOT yank it into the grid. Return-to-fragment
     * focus is [restoreFocusIfPossible].
     */
    fun reassertGridFocus() {
        if (!rvSeriesGrid.hasFocus()) return
        rvSeriesGrid.post {
            val positionToFocus =
                if (lastFocusedSeriesPosition != RecyclerView.NO_POSITION) lastFocusedSeriesPosition else 0
            val safePosition = minOf(positionToFocus, adapter().itemCount - 1)

            if (safePosition >= 0) {
                rvSeriesGrid.scrollToPosition(safePosition)
                rvSeriesGrid.post {
                    rvSeriesGrid.findViewHolderForAdapterPosition(safePosition)?.itemView?.requestFocus()
                }
            }
        }
    }

    // ── the D-pad map ────────────────────────────────────────────────────────

    fun setupDpadNavigation() {
        installGridKeyListener()
        installGridCardKeyListeners()
        installSidebarRowKeyListeners()
        installSidebarFallbackKeyListener()
    }

    private fun installGridKeyListener() {
        rvSeriesGrid.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false

            val focused = rvSeriesGrid.findFocus() ?: return@setOnKeyListener false
            val holder = rvSeriesGrid.findContainingViewHolder(focused) ?: return@setOnKeyListener false
            val position = holder.bindingAdapterPosition
            val columns = gridColumnCount()

            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> {
                    // First row: route UP to the sort chips instead of trapping focus
                    if (position < columns) {
                        focusSortChipsRow()
                        true
                    } else false
                }
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    // Placeholders are off, so past the last LOADED row there is no focusable
                    // card below — a plain DOWN then escapes to the sidebar (the "focus jumps
                    // to category on fast scroll" bug). Consume it and nudge a scroll so the
                    // next page loads; focus stays on the current card.
                    val count = adapter().itemCount
                    if (count > 0 && position + columns >= count) {
                        rvSeriesGrid.scrollBy(0, focused.height)
                        true
                    } else false
                }
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    if (position % columns == 0) {
                        rvCategoriesSidebar.post {
                            rvCategoriesSidebar.post { rvCategoriesSidebar.requestFocus() }
                        }
                        true
                    } else false
                }
                else -> false
            }
        }
    }

    /**
     * The RV key listener only fires when the RV ITSELF holds focus — on TV the focused view is
     * the card (a row child), so DOWN/LEFT/UP must be intercepted on each card. Without this, a
     * fast DOWN past the last laid-out row finds no focusable below and escapes to the sidebar.
     */
    private fun installGridCardKeyListeners() {
        rvSeriesGrid.addOnChildAttachStateChangeListener(
            object : RecyclerView.OnChildAttachStateChangeListener {
                override fun onChildViewAttachedToWindow(view: View) {
                    view.setOnKeyListener { _, keyCode, event ->
                        if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                        val position = rvSeriesGrid.getChildAdapterPosition(view)
                        if (position == RecyclerView.NO_POSITION) return@setOnKeyListener false
                        onGridCardKey(view, position, keyCode)
                    }
                }

                override fun onChildViewDetachedFromWindow(view: View) {
                    view.setOnKeyListener(null)
                }
            }
        )
    }

    private fun onGridCardKey(view: View, position: Int, keyCode: Int): Boolean {
        val count = adapter().itemCount
        val columns = gridColumnCount()
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                val belowPos = position + columns
                // No card below (last row) OR the row below isn't laid out yet
                // (fast scroll) → consume, nudge a scroll, keep focus here.
                val belowView = rvSeriesGrid.layoutManager?.findViewByPosition(belowPos)
                if (belowPos >= count || belowView == null) {
                    rvSeriesGrid.scrollBy(0, view.height)
                    true
                } else false
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (position % columns == 0) {
                    rvCategoriesSidebar.post {
                        if (fragment.isAdded) rvCategoriesSidebar.requestFocus()
                    }
                    true
                } else false
            }
            KeyEvent.KEYCODE_DPAD_UP -> {
                if (position < columns) {
                    focusSortChipsRow()
                    true
                } else false
            }
            else -> false
        }
    }

    /**
     * RIGHT from the sidebar must always enter the content column. A plain nextFocusRight
     * dead-ends when the grid is scrolled off-screen, so intercept on each ROW view (which IS the
     * focused view) and jump to the first on-screen content focusable.
     */
    private fun installSidebarRowKeyListeners() {
        rvCategoriesSidebar.addOnChildAttachStateChangeListener(
            object : RecyclerView.OnChildAttachStateChangeListener {
                override fun onChildViewAttachedToWindow(view: View) {
                    view.setOnKeyListener { _, keyCode, event ->
                        if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                        when (keyCode) {
                            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                                focusContentFromSidebar()
                                true
                            }
                            KeyEvent.KEYCODE_DPAD_UP -> {
                                // From the TOP category row, UP goes to the search pill.
                                // Consume it either way so an unhandled UP can't escape the
                                // app (which previously bounced to the launcher).
                                val pos = rvCategoriesSidebar.getChildAdapterPosition(view)
                                if (pos <= 0) {
                                    focusSearchBar()
                                    true
                                } else false
                            }
                            else -> false
                        }
                    }
                }

                override fun onChildViewDetachedFromWindow(view: View) {
                    view.setOnKeyListener(null)
                }
            }
        )
    }

    /** Backup for the (rare) case the RecyclerView itself holds focus. */
    private fun installSidebarFallbackKeyListener() {
        rvCategoriesSidebar.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    focusContentFromSidebar()
                    true
                }
                KeyEvent.KEYCODE_DPAD_LEFT -> true
                else -> false
            }
        }
    }

    /** Move focus to the search pill (its own view is the focus target). */
    fun focusSearchBar() {
        val s = views.searchButton() ?: return
        // Make sure the pill itself is the d-pad focus target.
        s.isFocusable = true
        s.isFocusableInTouchMode = s.resources.getBoolean(R.bool.ui_uses_dpad_focus)

        if (!s.requestFocus()) s.requestFocus(View.FOCUS_UP)

        // Always retry on the next frame — covers both a not-yet-laid-out view AND a
        // focus bounce-back (where the immediate requestFocus succeeds but the list
        // re-claims focus in the same frame).
        s.post {
            if (!fragment.isAdded) return@post
            s.requestFocus() || s.requestFocus(View.FOCUS_UP)
        }
    }

    /**
     * Move focus from the sidebar into the content column, never dead-ending.
     * Priority: an on-screen grid card → the sort chips row → the grid.
     */
    fun focusContentFromSidebar() {
        // 1) An on-screen, laid-out grid card.
        if (rvSeriesGrid.visibility == View.VISIBLE && rvSeriesGrid.childCount > 0) {
            rvSeriesGrid.post {
                if (!fragment.isAdded) return@post
                val target = rvSeriesGrid.getChildAt(0)
                if (target != null && target.requestFocus()) return@post
                rvSeriesGrid.requestFocus()
            }
            return
        }
        // 2) Sort chips row (always present in the header; DOWN goes to the grid).
        if (llSortChips.childCount > 0) {
            llSortChips.post {
                if (!fragment.isAdded) return@post
                val chip = llSortChips.getChildAt(0)
                if (chip != null && chip.requestFocus()) return@post
                rvSeriesGrid.requestFocus()
            }
            return
        }
        // 3) Last resort.
        rvSeriesGrid.post { if (fragment.isAdded) rvSeriesGrid.requestFocus() }
    }

    /** Focus the first sort chip in the merged header row. */
    fun focusSortChipsRow() {
        val chip = llSortChips.getChildAt(0)
        if (chip != null) chip.post { if (fragment.isAdded) chip.requestFocus() } else focusGridTop()
    }

    /** Return focus to the grid from the header — the first on-screen card, else the grid. */
    fun focusGridTop() {
        rvSeriesGrid.post {
            if (!fragment.isAdded) return@post
            val card = rvSeriesGrid.getChildAt(0)
            if (card != null && card.requestFocus()) return@post
            rvSeriesGrid.requestFocus()
        }
    }

    private companion object {
        const val STATE_LAST_CATEGORY_POS = "series_last_category_pos"
        const val STATE_LAST_SERIES_POS = "series_last_series_pos"
        const val STATE_LAST_FOCUS_TARGET = "series_last_focus_target"
        const val STATE_LAST_CATEGORY_ID = "series_last_category_id"
        const val STATE_LAST_SERIES_ID = "series_last_series_id"
    }
}
