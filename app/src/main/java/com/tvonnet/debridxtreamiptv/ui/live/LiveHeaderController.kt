package com.tvonnet.debridxtreamiptv.ui.live

import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.tvonnet.debridxtreamiptv.R
import com.tvonnet.debridxtreamiptv.util.FAVORITES_CATEGORY_ID
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * LF-1: the top-bar **header + clock** concern lifted out of [LiveFragment] — the once-a-minute
 * header clock and the "<CATEGORY> CHANNELS" / "N LIVE" title+count row. Self-contained: owns the
 * three header TextViews + the minute-tick job + the top-bar time formatter. Reads the live favourites
 * count (which stays in the Fragment) via [favoritesCount].
 *
 * Constructed fresh in `onViewCreated` (with the freshly-bound views); the clock runs on the
 * fragment's `viewLifecycleOwner` scope so it auto-cancels on view destroy — [cancel] is the explicit
 * belt-and-suspenders the Fragment already had. Behaviour byte-identical to the old private methods;
 * only `this`/field refs were rebound to [fragment], the passed views and [favoritesCount].
 */
class LiveHeaderController(
    private val fragment: Fragment,
    private val tvHeaderCategory: TextView?,
    private val tvHeaderChannelCount: TextView?,
    private val tvHeaderClock: TextView?,
    private val favoritesCount: () -> Int,
) {
    private val topBarTimeFormatter = SimpleDateFormat("HH:mm", Locale.getDefault())
    private var clockJob: Job? = null

    fun startHeaderClock() {
        clockJob?.cancel()
        clockJob = fragment.viewLifecycleOwner.lifecycleScope.launch {
            // Phase 4: only tick while the fragment is at least STARTED — the clock was updating
            // the (invisible) header text every minute even while STOPPED. repeatOnLifecycle
            // cancels the loop on STOP and restarts it (with a fresh time) on re-START.
            fragment.viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (isActive) {
                    tvHeaderClock?.text = topBarTimeFormatter.format(Date())
                    delay(millisUntilNextMinute())
                }
            }
        }
    }

    private fun millisUntilNextMinute(): Long {
        val now = System.currentTimeMillis()
        val remainder = now % 60_000L
        return if (remainder == 0L) 60_000L else 60_000L - remainder
    }

    /** v2 list header: "<CATEGORY> CHANNELS" plus an "N LIVE" pill. */
    fun updateHeaderInfo(state: LiveUiState) {
        if (state.selectedCategoryId == FAVORITES_CATEGORY_ID) {
            tvHeaderCategory?.text =
                fragment.getString(R.string.livev2_category_channels_format, fragment.getString(R.string.favorites))
            tvHeaderChannelCount?.text = fragment.getString(R.string.livev2_channel_count_format, favoritesCount())
            return
        }

        val selectedCategory = state.categories.firstOrNull { it.category_id == state.selectedCategoryId }
        val categoryName = selectedCategory?.category_name?.takeIf { it.isNotBlank() }
            ?: fragment.getString(R.string.live_category_placeholder)
        tvHeaderCategory?.text = fragment.getString(R.string.livev2_category_channels_format, categoryName)

        val selectedId = state.selectedCategoryId
        val knownCount = selectedId?.let { id -> state.categoryChannelCounts[id] }
        tvHeaderChannelCount?.text = when {
            knownCount != null -> fragment.getString(R.string.livev2_channel_count_format, knownCount)
            else -> fragment.getString(R.string.live_channel_count_placeholder)
        }
    }

    /** Cancel the minute-tick (belt-and-suspenders; the viewLifecycleOwner scope also cancels it). */
    fun cancel() {
        clockJob?.cancel()
        clockJob = null
    }
}
