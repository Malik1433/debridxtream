package com.tvonnet.debridxtreamiptv.ui.live.guide

import android.view.LayoutInflater
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.tvonnet.debridxtreamiptv.R
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * C7: the guide's unified search — categories and channels in one list, opened from the first chip.
 *
 * Two things here are load-bearing rather than cosmetic:
 *  - **the in-flight job is cancelled on every keystroke.** Channel search hits the catalog, so
 *    without this a fast typist has several searches racing and the slowest one wins, painting
 *    results for a query that is no longer on screen.
 *  - **the dialog window is given a fixed size.** The results `RecyclerView` has `weight=1`, so with
 *    a wrap-content window it has no bounded height: it grows past the screen instead of scrolling,
 *    and D-pad focus walks straight off the bottom.
 *
 * Bodies moved verbatim from `LiveTvGuideFragment`.
 */
internal class GuideSearchOverlay(
    private val fragment: Fragment,
    private val viewModel: LiveTvGuideViewModel,
    private val onCategoryPicked: (GuideCategory) -> Unit,
    private val onChannelPicked: (picked: GuideChannel, matches: List<GuideChannel>) -> Unit,
) {
    private companion object {
        const val DIALOG_WIDTH_DP = 860
        const val DIALOG_HEIGHT_DP = 660
        const val SOFT_INPUT_DELAY_MS = 100L
    }

    fun show() {
        val ctx = fragment.requireContext()
        val dialogView = LayoutInflater.from(ctx).inflate(R.layout.dialog_live_guide_search, null)
        val dialog = androidx.appcompat.app.AlertDialog.Builder(ctx).setView(dialogView).create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        val etSearch = dialogView.findViewById<android.widget.EditText>(R.id.et_guide_search)
        val rv = dialogView.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.rv_guide_search_results)
        val empty = dialogView.findViewById<TextView>(R.id.tv_guide_search_empty)

        var lastChannels: List<GuideChannel> = emptyList()
        val adapter = LiveSearchAdapter(
            onCategory = { cat -> dialog.dismiss(); onCategoryPicked(cat) },
            onChannel = { ch ->
                dialog.dismiss()
                // Show the matched channels in the grid so the picked one is
                // present + selected → OK on its tile goes fullscreen.
                onChannelPicked(ch, lastChannels.ifEmpty { listOf(ch) })
            }
        )
        rv.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(ctx)
        rv.adapter = adapter

        var job: kotlinx.coroutines.Job? = null
        fun rebuild(query: String) {
            job?.cancel()
            val cats = viewModel.filterCategories(query)
            job = fragment.viewLifecycleOwner.lifecycleScope.launch {
                val channels = if (query.isBlank()) emptyList() else viewModel.searchChannels(query)
                if (!isActive) return@launch
                lastChannels = channels
                val rows = buildRows(cats, channels)
                adapter.submit(rows)
                val isEmpty = rows.isEmpty()
                empty.visibility = if (isEmpty && query.isNotBlank()) android.view.View.VISIBLE else android.view.View.GONE
                rv.visibility = if (isEmpty) android.view.View.GONE else android.view.View.VISIBLE
            }
        }

        etSearch.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) { rebuild(s?.toString().orEmpty()) }
        })

        etSearch.requestFocus()
        etSearch.postDelayed({
            (ctx.getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
                as? android.view.inputmethod.InputMethodManager)
                ?.showSoftInput(etSearch, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        }, SOFT_INPUT_DELAY_MS)

        rebuild("") // open on the full category list; typing filters + finds channels
        dialog.show()
        // Fixed window size so the RecyclerView (weight=1) gets a bounded height
        // and scrolls with focus instead of overflowing the dialog off-screen.
        dialog.window?.setLayout(dp(DIALOG_WIDTH_DP), dp(DIALOG_HEIGHT_DP))
    }

    private fun buildRows(
        cats: List<GuideCategory>,
        channels: List<GuideChannel>
    ): List<LiveSearchAdapter.Row> {
        val rows = ArrayList<LiveSearchAdapter.Row>()
        if (cats.isNotEmpty()) {
            rows.add(LiveSearchAdapter.Row.Header(fragment.getString(R.string.live_guide_search_categories)))
            cats.forEach { rows.add(LiveSearchAdapter.Row.CategoryRow(it)) }
        }
        if (channels.isNotEmpty()) {
            rows.add(LiveSearchAdapter.Row.Header(fragment.getString(R.string.live_guide_search_channels)))
            channels.forEach { rows.add(LiveSearchAdapter.Row.ChannelRow(it)) }
        }
        return rows
    }

    private fun dp(v: Int) = (v * fragment.resources.displayMetrics.density).toInt()
}
