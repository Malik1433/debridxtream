package com.tvonnet.debridxtreamiptv.player.stabilized

import android.app.Activity
import android.view.View
import android.widget.TextView
import androidx.lifecycle.LifecycleCoroutineScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.tvonnet.debridxtreamiptv.R
import com.tvonnet.debridxtreamiptv.data.model.XtreamStream
import com.tvonnet.debridxtreamiptv.utils.FocusCoordinator
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The full-screen Live channel browser overlay (Phase P19c): its two lists, the
 * left/right focus hand-off between them, and the state collector that fills them.
 *
 * Tuning a channel stays in [PlayerActivity] — the browser only reports the pick.
 */
internal interface ChannelBrowserHost {
    /** The channel currently playing, so the lists can scroll/focus onto it. */
    fun currentStreamId(): String?

    /** The user picked a channel; the screen performs the switch and closes the browser. */
    fun onChannelSelected(stream: XtreamStream)

    /** The overlay just closed — restore focus to the player. */
    fun onBrowserClosed()
}

internal class PlayerChannelBrowser(
    private val activity: Activity,
    private val host: ChannelBrowserHost
) {

    private lateinit var categoryAdapter: BrowserCategoryAdapter
    private lateinit var channelAdapter: BrowserChannelAdapter

    /**
     * Inflates nothing — the overlay lives in the Activity layout; this binds it.
     * Returns false when the layout has no browser (non-live screens).
     */
    fun bind(
        scope: LifecycleCoroutineScope,
        onCategorySelected: (String) -> Unit,
        stateFlow: kotlinx.coroutines.flow.Flow<BrowserUiState>
    ): Boolean {
        val browserView = activity.findViewById<View>(R.id.view_channel_browser) ?: return false
        val rvCategories = browserView.findViewById<RecyclerView>(R.id.rv_browser_categories)
        val rvChannels = browserView.findViewById<RecyclerView>(R.id.rv_browser_channels)
        val pbLoading = browserView.findViewById<View>(R.id.pb_browser_loading)
        val tvEmpty = browserView.findViewById<View>(R.id.tv_browser_empty)
        val tvClock = browserView.findViewById<TextView>(R.id.tv_browser_clock)

        tvClock.text = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date())

        categoryAdapter = BrowserCategoryAdapter(
            onCategoryClick = { category -> category.category_id?.let(onCategorySelected) },
            onRightPressed = { focusCurrentChannel(rvChannels) }
        )
        rvCategories.layoutManager = LinearLayoutManager(activity)
        rvCategories.adapter = categoryAdapter

        channelAdapter = BrowserChannelAdapter(
            onChannelClick = { stream -> host.onChannelSelected(stream) },
            onLeftPressedAtZero = {
                val selectedPos = categoryAdapter.getSelectedPosition().coerceAtLeast(0)
                rvCategories.scrollToPosition(selectedPos)
                rvCategories.post {
                    rvCategories.findViewHolderForAdapterPosition(selectedPos)?.itemView?.requestFocus()
                        ?: rvCategories.requestFocus()
                }
            }
        )
        rvChannels.layoutManager = GridLayoutManager(activity, 4)
        rvChannels.adapter = channelAdapter

        scope.launch {
            var wasVisible = false
            stateFlow.collect { state ->
                render(state, browserView, rvChannels, pbLoading, tvEmpty)
                if (!state.isVisible && wasVisible) host.onBrowserClosed()
                wasVisible = state.isVisible
            }
        }
        return true
    }

    private fun render(
        state: BrowserUiState,
        browserView: View,
        rvChannels: RecyclerView,
        pbLoading: View,
        tvEmpty: View
    ) {
        browserView.visibility = if (state.isVisible) View.VISIBLE else View.GONE
        if (!state.isVisible) return
        categoryAdapter.submitList(state.categories)
        categoryAdapter.setSelectedId(state.selectedCategoryId)
        channelAdapter.submitList(state.channels) { scrollToCurrentChannel(rvChannels, state) }
        pbLoading.visibility = if (state.isLoadingChannels) View.VISIBLE else View.GONE
        tvEmpty.visibility =
            if (!state.isLoadingChannels && state.channels.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun scrollToCurrentChannel(rvChannels: RecyclerView, state: BrowserUiState) {
        val currentId = host.currentStreamId() ?: return
        if (state.channels.isEmpty()) return
        val index = state.channels.indexOfFirst { it.stream_id == currentId }
        if (index == -1) return
        rvChannels.scrollToPosition(index)
        rvChannels.post {
            rvChannels.findViewHolderForAdapterPosition(index)?.itemView?.requestFocus()
        }
    }

    private fun focusCurrentChannel(rvChannels: RecyclerView) {
        if (channelAdapter.itemCount <= 0) return
        rvChannels.post {
            val currentId = host.currentStreamId()
            val targetIndex = if (currentId != null) {
                channelAdapter.currentList.indexOfFirst { it.stream_id == currentId }.coerceAtLeast(0)
            } else 0
            rvChannels.scrollToPosition(targetIndex)
            rvChannels.post {
                rvChannels.findViewHolderForAdapterPosition(targetIndex)?.itemView?.requestFocus()
                    ?: rvChannels.requestFocus()
            }
        }
    }

    companion object {
        /** Focus back to the player when the overlay closes. */
        fun restorePlayerFocus(onFocus: () -> Unit) = FocusCoordinator.force("PLAYER") { onFocus() }
    }
}
