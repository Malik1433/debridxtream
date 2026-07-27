package com.tvonnet.debridxtreamiptv.features.seriesv2.ui

import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.tvonnet.debridxtreamiptv.databinding.FragmentSeriesDetailV2Binding
import com.tvonnet.debridxtreamiptv.features.seriesv2.ui.model.IptvStreamGroup
import com.tvonnet.debridxtreamiptv.features.seriesv2.ui.model.IptvStreamOption
import com.tvonnet.debridxtreamiptv.utils.updatePreservingFocus

/**
 * C4: the "SELECT STREAM" side panel — its adapter, slide-in/out animation, filter chips,
 * and the focus rules that make it usable with a D-pad.
 *
 * Two focus behaviours live here and are the reason this is one collaborator rather than a
 * pile of view code in the fragment:
 *  - while the panel is open the background action row and the episodes strip are made
 *    **unfocusable**, so D-pad focus cannot escape behind the scrim;
 *  - a filter-chip toggle rebuilds the list, so the rebuild goes through
 *    [updatePreservingFocus] (CC-1) — the user keeps their row instead of being thrown out of
 *    the list. Only the *initial* open moves focus, via `pendingStreamFocus`.
 *
 * Bodies moved verbatim from `SeriesDetailFragmentV2`; only `this`/field references were rebound.
 */
class SeriesStreamPanelUi(
    private val binding: FragmentSeriesDetailV2Binding,
    private val viewModel: SeriesDetailViewModelV2,
    private val onStreamSelected: (IptvStreamOption) -> Unit,
) {
    private var streamAdapter: StreamPanelAdapter? = null
    private var pendingStreamFocus = false

    fun setup() {
        streamAdapter = StreamPanelAdapter(onStreamClick = { option -> onStreamSelected(option) })
        binding.rvStreams.adapter = streamAdapter

        binding.viewScrim.setOnClickListener { viewModel.closeStreamPanel() }
        binding.chipQuality.setOnClickListener { viewModel.cycleFilter(FilterKey.QUALITY) }
        binding.chipLanguage.setOnClickListener { viewModel.cycleFilter(FilterKey.LANGUAGE) }
        binding.chipContainer.setOnClickListener { viewModel.cycleFilter(FilterKey.CONTAINER) }
        binding.chipQuality.isFocusable = true
        binding.chipLanguage.isFocusable = true
        binding.chipContainer.isFocusable = true

        // Panel starts off-screen (to the right)
        binding.panelStreams.visibility = View.GONE
        binding.viewScrim.visibility = View.GONE
    }

    fun render(state: StreamPanelState) {
        val panel = binding.panelStreams
        val scrim = binding.viewScrim

        if (!state.visible) {
            if (panel.visibility == View.VISIBLE) {
                panel.animate().translationX(panel.width.toFloat()).setDuration(200).withEndAction {
                    panel.visibility = View.GONE
                    panel.translationX = 0f
                }.start()
                scrim.animate().alpha(0f).setDuration(200).withEndAction { scrim.visibility = View.GONE }.start()
            }
            setBackgroundInteractable(true)
            return
        }

        setBackgroundInteractable(false)
        if (panel.visibility != View.VISIBLE) {
            pendingStreamFocus = true
            panel.visibility = View.VISIBLE
            panel.post {
                panel.translationX = panel.width.toFloat()
                panel.animate().translationX(0f).setDuration(260).start()
            }
            scrim.visibility = View.VISIBLE
            scrim.alpha = 0f
            scrim.animate().alpha(1f).setDuration(200).start()
        }

        binding.tvEpisodeLabel.text = state.episodeLabel
        styleChip(binding.chipQuality, "Quality", state.filters.quality)
        styleChip(binding.chipLanguage, "Language", state.filters.language)
        styleChip(binding.chipContainer, "Format", state.filters.container)

        binding.pbStreams.visibility = if (state.loading) View.VISIBLE else View.GONE

        val groups = state.filteredGroups
        val streamCount = state.filteredStreamCount
        binding.tvScanLine.text = "$streamCount ${if (streamCount == 1) "stream" else "streams"} across " +
            "${groups.size} ${if (groups.size == 1) "category" else "categories"}"

        renderStreamList(state, groups)
    }

    private fun renderStreamList(
        state: StreamPanelState,
        groups: List<IptvStreamGroup>
    ) {
        when {
            state.loading -> {
                binding.tvNoStreams.visibility = View.GONE
                streamAdapter?.submit(emptyList(), null)
            }
            groups.isEmpty() -> {
                binding.tvNoStreams.visibility = View.VISIBLE
                binding.tvNoStreams.text = state.error ?: "NO STREAMS MATCH CURRENT FILTERS"
                streamAdapter?.submit(emptyList(), null)
            }
            else -> {
                binding.tvNoStreams.visibility = View.GONE
                // CC-1: a filter-chip toggle rebuilds the list via notifyDataSetChanged; if the
                // user was inside the stream list, keep their row (or nearest surviving one)
                // instead of dropping focus and forcing them to re-navigate. The initial
                // panel-open focus is still handled explicitly via pendingStreamFocus below.
                binding.rvStreams.updatePreservingFocus {
                    streamAdapter?.submit(groups, state.resolvingStreamId)
                }
                if (pendingStreamFocus) {
                    pendingStreamFocus = false
                    binding.rvStreams.post {
                        val pos = streamAdapter?.firstRowPosition() ?: -1
                        if (pos >= 0) binding.rvStreams.findViewHolderForAdapterPosition(pos)?.itemView?.requestFocus()
                    }
                }
            }
        }
    }

    private fun styleChip(tv: TextView, label: String, value: String) {
        val active = value != "All"
        tv.text = "$label: $value"
        tv.setTextColor(if (active) 0xFF00F0FF.toInt() else 0xFF94A3B8.toInt())
        val fill = if (active) 0x1400F0FF else 0x08FFFFFF
        val stroke = if (active) 0x4000F0FF else 0x12FFFFFF
        tv.background = GradientDrawable().apply {
            setColor(fill)
            cornerRadius = dp(4).toFloat()
            setStroke(dp(1), stroke)
        }
    }

    /** Prevent background focus escaping while the stream panel is open. */
    private fun setBackgroundInteractable(enabled: Boolean) {
        binding.btnPlay.isFocusable = enabled
        binding.btnTrailer.isFocusable = enabled
        binding.btnFavorite.isFocusable = enabled
        binding.btnSeasonSelector.isFocusable = enabled
        binding.rvEpisodes.descendantFocusability =
            if (enabled) ViewGroup.FOCUS_AFTER_DESCENDANTS else ViewGroup.FOCUS_BLOCK_DESCENDANTS
    }

    private fun dp(v: Int): Int = (v * binding.root.resources.displayMetrics.density).toInt()
}
