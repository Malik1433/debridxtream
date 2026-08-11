package com.tvonnet.debridxtreamiptv.ui.series

import android.view.View
import android.view.ViewGroup
import android.view.animation.Animation
import android.view.animation.TranslateAnimation
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.paging.CombinedLoadStates
import androidx.paging.LoadState
import androidx.recyclerview.widget.RecyclerView
import com.tvonnet.debridxtreamiptv.BuildConfig
import com.tvonnet.debridxtreamiptv.R

/**
 * The views this paints. Looked up lazily because the fragment nulls them in `onDestroyView` —
 * holding hard references here would outlive the view hierarchy.
 */
class SeriesListStateViews(
    val grid: RecyclerView,
    val loading: () -> ViewGroup?,
    val empty: () -> View?,
    val message: () -> TextView?,
    val hint: () -> TextView?,
    val retry: () -> View?,
)

/**
 * C3: what the Series grid shows when it has **nothing to show** — the skeleton, the empty state,
 * the Retry affordance and the load-error toast. Mirror of `VodListStateUi`.
 *
 * Same two rules that are easy to get wrong: a skeleton may only replace an EMPTY grid (a category
 * switch or a background sync must never blank content already on screen), and "empty" needs the
 * ViewModel *and* all three Paging load states to agree, or the screen flashes "No series"
 * mid-load. A failure shows Retry rather than claiming the category is empty — and the raw error
 * text is only surfaced in debug builds.
 *
 * Bodies moved verbatim from `SeriesFragment`; only `this`/field references were rebound.
 */
class SeriesListStateUi(
    private val fragment: Fragment,
    private val views: SeriesListStateViews,
    private val itemCount: () -> Int,
    private val isLoadingFromViewModel: () -> Boolean,
    /** Runs when a refresh has settled — the fragment restores focus and re-counts the header. */
    private val onRefreshSettled: () -> Unit,
) {
    private val rvSeriesGrid get() = views.grid

    /** True when the last load failed: the empty view then offers Retry instead of "no series". */
    var hasLoadError: Boolean = false

    private var shimmerRunning = false

    fun updateListLoadingAndEmptyStates(loadState: CombinedLoadStates, isSwitching: Boolean = false) {
        val itemCount = itemCount()

        val isPagingRefreshLoading =
            loadState.refresh is LoadState.Loading ||
                loadState.source.refresh is LoadState.Loading ||
                loadState.mediator?.refresh is LoadState.Loading

        val shouldShowLoading = (isLoadingFromViewModel() || isPagingRefreshLoading)
        showLoadingState(shouldShowLoading, itemCount > 0)

        val isListEmpty =
            !isLoadingFromViewModel() &&
                !isSwitching &&
                loadState.refresh is LoadState.NotLoading &&
                loadState.source.refresh is LoadState.NotLoading &&
                (loadState.mediator?.refresh == null || loadState.mediator!!.refresh is LoadState.NotLoading) &&
                itemCount == 0 &&
                !isPagingRefreshLoading
        showEmptyState(isListEmpty)

        applyLoadError(firstLoadError(loadState), itemCount)

        if (!isPagingRefreshLoading && loadState.refresh is LoadState.NotLoading) {
            onRefreshSettled()
        }
    }

    // Any error across the paging surfaces counts; source errors are checked first, as before.
    private fun firstLoadError(loadState: CombinedLoadStates): LoadState.Error? =
        loadState.source.refresh as? LoadState.Error
            ?: loadState.source.append as? LoadState.Error
            ?: loadState.source.prepend as? LoadState.Error
            ?: loadState.refresh as? LoadState.Error
            ?: loadState.mediator?.refresh as? LoadState.Error

    private fun applyLoadError(errorState: LoadState.Error?, itemCount: Int) {
        if (errorState != null) {
            hasLoadError = true
            Toast.makeText(fragment.context, displayErrorMessage(errorState.error), Toast.LENGTH_LONG).show()
            // Re-evaluate the empty view so an error with 0 items surfaces Retry.
            if (itemCount == 0) showEmptyState(true)
        } else if (itemCount > 0) {
            hasLoadError = false
        }
    }

    /** Raw exception text is a debug affordance; users get one sentence they can act on. */
    fun displayErrorMessage(error: Throwable): String {
        return if (BuildConfig.DEBUG) {
            "Error: ${error.message}"
        } else {
            "Unable to load series. Please try again."
        }
    }

    fun showEmptyState(show: Boolean) {
        views.empty()?.visibility = if (show) View.VISIBLE else View.GONE
        if (show && itemCount() == 0) {
            rvSeriesGrid.visibility = View.GONE
        } else {
            rvSeriesGrid.visibility = View.VISIBLE
        }
        if (show) {
            if (hasLoadError) {
                views.message()?.text = fragment.getString(R.string.c_couldn_t_load_series)
                views.hint()?.visibility = View.VISIBLE
                views.hint()?.text = fragment.getString(R.string.c_check_your_connection_and_try)
                views.retry()?.visibility = View.VISIBLE
                views.retry()?.post { if (fragment.isAdded) views.retry()?.requestFocus() }
            } else {
                views.message()?.text = fragment.getString(R.string.ui_no_series_available)
                views.hint()?.visibility = View.GONE
                views.retry()?.visibility = View.GONE
            }
        } else {
            views.retry()?.visibility = View.GONE
        }
    }

    fun showLoadingState(show: Boolean, keepContent: Boolean = false) {
        val container = views.loading() ?: return
        // Full-screen skeleton ONLY when the grid has nothing to show. When content is
        // already on screen (category switch / background sync), never blank or dim it —
        // the sync finishes silently and Paging swaps the rows in place.
        if (show && !keepContent) {
            container.visibility = View.VISIBLE
            container.alpha = 1.0f
            rvSeriesGrid.alpha = 0f
            rvSeriesGrid.visibility = View.GONE
            startShimmerAnimations(container)
        } else {
            stopShimmerAnimations(container)
            container.visibility = View.GONE
            rvSeriesGrid.visibility = View.VISIBLE
            rvSeriesGrid.alpha = 1.0f
        }
    }

    private fun startShimmerAnimations(container: ViewGroup) {
        if (shimmerRunning) return
        shimmerRunning = true
        for (i in 0 until container.childCount) {
            val shimmer = container.getChildAt(i)?.findViewById<View>(R.id.v_shimmer) ?: continue
            val anim = TranslateAnimation(
                Animation.RELATIVE_TO_PARENT, -1.0f,
                Animation.RELATIVE_TO_PARENT, 1.0f,
                Animation.RELATIVE_TO_PARENT, 0f,
                Animation.RELATIVE_TO_PARENT, 0f
            ).apply {
                duration = 1200L + (i * 80L)
                repeatCount = Animation.INFINITE
                repeatMode = Animation.RESTART
                interpolator = android.view.animation.LinearInterpolator()
            }
            shimmer.startAnimation(anim)
        }
    }

    private fun stopShimmerAnimations(container: ViewGroup) {
        if (!shimmerRunning) return
        shimmerRunning = false
        for (i in 0 until container.childCount) {
            container.getChildAt(i)?.findViewById<View>(R.id.v_shimmer)?.clearAnimation()
        }
    }
}
