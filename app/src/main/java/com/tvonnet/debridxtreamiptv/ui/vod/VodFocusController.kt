package com.tvonnet.debridxtreamiptv.ui.vod

import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.widget.LinearLayout
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.RecyclerView
import com.tvonnet.debridxtreamiptv.data.model.XtreamCategory
import com.tvonnet.debridxtreamiptv.utils.FocusCoordinator

/** The four views the D-pad map moves focus between. */
class VodGridViews(
    val sidebar: RecyclerView,
    val grid: RecyclerView,
    val sortChips: LinearLayout,
    val searchButton: () -> View?,
)

/**
 * C2: the Movies screen's **D-pad map and focus memory** — the single most regression-prone part
 * of this fragment, so it gets one owner.
 *
 * Two different jobs that are easy to confuse, kept apart deliberately:
 *  - **[restoreFocusIfPossible]** puts focus back where the user left it when they return to the
 *    screen. It is gated on [pendingRestoreFocus], which is only armed in `onPause`/saved state.
 *  - **[reassertGridFocus]** handles a data change *while the user is already in the grid* (a
 *    paging append shifting rows). It refuses to act unless the grid already has focus, because
 *    a background refresh must never yank focus out of the sidebar or the sort chips — CC-1, the
 *    dominant focus defect in this app.
 *
 * Bodies moved verbatim from `VodFragment`; only `this`/field references were rebound.
 */
class VodFocusController(
    private val fragment: Fragment,
    private val views: VodGridViews,
    private val adapter: () -> VodAdapter,
    private val currentCategories: () -> List<XtreamCategory>,
    private val gridColumnCount: () -> Int,
) {
    private val rvCategoriesSidebar get() = views.sidebar
    private val rvMoviesGrid get() = views.grid
    private val llSortChips get() = views.sortChips

    enum class FocusTarget { CATEGORIES, MOVIES }

    var lastFocusTarget: FocusTarget = FocusTarget.MOVIES
        private set

    private var lastFocusedCategoryPosition: Int = RecyclerView.NO_POSITION
    private var lastFocusedMoviePosition: Int = RecyclerView.NO_POSITION
    private var lastFocusedCategoryId: String? = null
    private var lastFocusedMovieId: String? = null
    private var pendingRestoreFocus: Boolean = false

    private var restoreCategoryPosition: Int = RecyclerView.NO_POSITION
    private var restoreMoviePosition: Int = RecyclerView.NO_POSITION
    private var restoreCategoryId: String? = null
    private var restoreMovieId: String? = null
    private var restoreFocusTarget: FocusTarget = FocusTarget.MOVIES

    // ── what the adapters report ─────────────────────────────────────────────

    fun onCategoryFocused(position: Int, categoryId: String?) {
        lastFocusTarget = FocusTarget.CATEGORIES
        lastFocusedCategoryPosition = position
        if (categoryId != null) lastFocusedCategoryId = categoryId
    }

    fun onMovieFocused(position: Int, movieId: String?) {
        lastFocusTarget = FocusTarget.MOVIES
        lastFocusedMoviePosition = position
        if (movieId != null) lastFocusedMovieId = movieId
    }

    // ── lifecycle ────────────────────────────────────────────────────────────

    fun restoreSavedState(state: Bundle) {
        lastFocusedCategoryPosition = state.getInt(STATE_LAST_CATEGORY_POS, RecyclerView.NO_POSITION)
        lastFocusedMoviePosition = state.getInt(STATE_LAST_MOVIE_POS, RecyclerView.NO_POSITION)
        lastFocusedCategoryId = state.getString(STATE_LAST_CATEGORY_ID)
        lastFocusedMovieId = state.getString(STATE_LAST_MOVIE_ID)
        val focusTargetName = state.getString(STATE_LAST_FOCUS_TARGET) ?: FocusTarget.MOVIES.name
        lastFocusTarget = runCatching { FocusTarget.valueOf(focusTargetName) }.getOrDefault(FocusTarget.MOVIES)

        restoreCategoryPosition = lastFocusedCategoryPosition
        restoreMoviePosition = lastFocusedMoviePosition
        restoreCategoryId = lastFocusedCategoryId
        restoreMovieId = lastFocusedMovieId
        restoreFocusTarget = lastFocusTarget
        pendingRestoreFocus =
            lastFocusedCategoryPosition != RecyclerView.NO_POSITION ||
                lastFocusedMoviePosition != RecyclerView.NO_POSITION
    }

    fun saveState(outState: Bundle) {
        outState.putInt(STATE_LAST_CATEGORY_POS, lastFocusedCategoryPosition)
        outState.putInt(STATE_LAST_MOVIE_POS, lastFocusedMoviePosition)
        outState.putString(STATE_LAST_CATEGORY_ID, lastFocusedCategoryId)
        outState.putString(STATE_LAST_MOVIE_ID, lastFocusedMovieId)
        outState.putString(STATE_LAST_FOCUS_TARGET, lastFocusTarget.name)
    }

    /** Snapshot where the user was, and arm the restore for when they come back. */
    fun onPause() {
        FocusCoordinator.release("VOD_RESTORE")

        restoreCategoryPosition = lastFocusedCategoryPosition
        restoreMoviePosition = lastFocusedMoviePosition
        restoreCategoryId = lastFocusedCategoryId
        restoreMovieId = lastFocusedMovieId
        restoreFocusTarget = lastFocusTarget

        pendingRestoreFocus =
            restoreCategoryPosition != RecyclerView.NO_POSITION ||
                restoreMoviePosition != RecyclerView.NO_POSITION
    }

    // ── returning to the screen ──────────────────────────────────────────────

    fun restoreFocusIfPossible() {
        if (!pendingRestoreFocus) return

        FocusCoordinator.requestFocus("VOD_RESTORE") {
            when (restoreFocusTarget) {
                FocusTarget.CATEGORIES -> restoreCategoryFocus()
                FocusTarget.MOVIES -> restoreMovieFocus()
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
                    FocusCoordinator.release("VOD_RESTORE")
                }
            }
        }
    }

    private fun restoreMovieFocus() {
        val count = adapter().itemCount
        if (count <= 0) return

        val position = resolveMovieAdapterPosition()?.coerceIn(0, count - 1) ?: return

        rvMoviesGrid.post {
            if (!fragment.isAdded) return@post
            rvMoviesGrid.scrollToPosition(position)
            rvMoviesGrid.post {
                if (!fragment.isAdded) return@post
                try {
                    val focused = rvMoviesGrid.findViewHolderForAdapterPosition(position)
                        ?.itemView?.requestFocus() == true
                    if (focused) pendingRestoreFocus = false
                } finally {
                    FocusCoordinator.release("VOD_RESTORE")
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

    private fun resolveMovieAdapterPosition(): Int? {
        restoreMovieId?.let { targetId ->
            val snapshot = adapter().snapshot()
            val indexInItems = snapshot.items.indexOfFirst { it.stream_id?.toString() == targetId }
            if (indexInItems >= 0) {
                return snapshot.placeholdersBefore + indexInItems
            }
        }
        return restoreMoviePosition.takeIf { it != RecyclerView.NO_POSITION }
    }

    /**
     * A full adapter refresh shifted rows under the user. Only re-assert focus if they are
     * ALREADY in the grid: CC-1 says a background data change must never pull focus out of the
     * sidebar, the sort chips or search. Returning-to-screen focus is [restoreFocusIfPossible].
     */
    fun reassertGridFocus() {
        if (!rvMoviesGrid.hasFocus()) return
        rvMoviesGrid.post {
            val positionToFocus =
                if (lastFocusedMoviePosition != RecyclerView.NO_POSITION) lastFocusedMoviePosition else 0
            val safePosition = minOf(positionToFocus, adapter().itemCount - 1)

            if (safePosition >= 0) {
                rvMoviesGrid.scrollToPosition(safePosition)
                rvMoviesGrid.post {
                    rvMoviesGrid.findViewHolderForAdapterPosition(safePosition)?.itemView?.requestFocus()
                }
            }
        }
    }

    // ── the D-pad map ────────────────────────────────────────────────────────

    fun setupDpadNavigation() {
        rvMoviesGrid.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false

            val focused = rvMoviesGrid.findFocus() ?: return@setOnKeyListener false
            val holder = rvMoviesGrid.findContainingViewHolder(focused) ?: return@setOnKeyListener false
            val position = holder.bindingAdapterPosition
            val columns = gridColumnCount()

            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> {
                    // Top row → the sort chips above the grid (column toggle removed).
                    if (position < columns) {
                        focusSortChipsRow()
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

        // RIGHT from any sidebar row enters the content column; UP from the TOP row
        // focuses the search pill. The RecyclerView key listener below only fires when the
        // RV itself holds focus (not a row child), so intercept on each ROW view.
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

        // Backup for the (rare) case the RecyclerView itself holds focus.
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
        s.isFocusable = true
        s.isFocusableInTouchMode = true
        if (!s.requestFocus()) s.requestFocus(View.FOCUS_UP)
        // Retry next frame in case layout wasn't settled / focus bounced.
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
        if (rvMoviesGrid.visibility == View.VISIBLE && rvMoviesGrid.childCount > 0) {
            rvMoviesGrid.post {
                if (!fragment.isAdded) return@post
                val target = rvMoviesGrid.getChildAt(0)
                if (target != null && target.requestFocus()) return@post
                rvMoviesGrid.requestFocus()
            }
            return
        }
        if (llSortChips.childCount > 0) {
            llSortChips.post {
                if (!fragment.isAdded) return@post
                val chip = llSortChips.getChildAt(0)
                if (chip != null && chip.requestFocus()) return@post
                rvMoviesGrid.requestFocus()
            }
            return
        }
        rvMoviesGrid.post { if (fragment.isAdded) rvMoviesGrid.requestFocus() }
    }

    // ── Vertical ladder above the grid: sort chips ↔ grid ──────

    fun focusSortChipsRow() {
        val chip = llSortChips.getChildAt(0)
        if (chip != null) chip.post { if (fragment.isAdded) chip.requestFocus() } else focusGridTop()
    }

    /** Return focus to the grid from a control row — the first on-screen card, else the grid itself. */
    fun focusGridTop() {
        rvMoviesGrid.post {
            if (!fragment.isAdded) return@post
            val card = rvMoviesGrid.getChildAt(0)
            if (card != null && card.requestFocus()) return@post
            rvMoviesGrid.requestFocus()
        }
    }

    private companion object {
        const val STATE_LAST_CATEGORY_POS = "vod_last_category_pos"
        const val STATE_LAST_MOVIE_POS = "vod_last_movie_pos"
        const val STATE_LAST_FOCUS_TARGET = "vod_last_focus_target"
        const val STATE_LAST_CATEGORY_ID = "vod_last_category_id"
        const val STATE_LAST_MOVIE_ID = "vod_last_movie_id"
    }
}
