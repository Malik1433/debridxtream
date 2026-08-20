package com.tvonnet.debridxtreamiptv.player.stabilized

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.tvonnet.debridxtreamiptv.data.model.ContentType
import kotlinx.coroutines.launch

/**
 * The remaining lifecycle-scoped viewModel→UI subscriptions (Phase C5 of the route
 * to ≤600). Most collectors already moved out with their feature (the Live-OSD
 * collectors in C2, the debrid-resolution collector in C3, the series-playlist
 * collector in C4); what's left is the overlay/zap/X-Ray wiring, gathered here so
 * `onCreate`/`setup*` no longer inline `repeatOnLifecycle` blocks.
 *
 * The sinks these feed — `renderOverlay` (the legacy EPG overlay renderer, wired to
 * ~15 view fields), the X-Ray controller, the channel-number view — stay on the
 * Activity; this binder only owns the flow subscriptions.
 */
internal class PlayerViewModelBinder(
    private val activity: BasePlayerFragment,
    private val session: PlayerSessionState,
) {

    private val viewModel get() = activity.viewModel
    private val lifecycleScope get() = activity.lifecycleScope
    private val liveOsd get() = activity.liveOsd
    private val tvChannelNumber get() = activity.tvChannelNumber

    private val contentId: String? by session::contentId
    private val contentType: ContentType? by session::contentType

    private fun renderOverlay(state: PlayerOverlayUiState) = activity.renderOverlay(state)

    fun observeOverlayState() {
        lifecycleScope.launch {
            activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.overlayState.collect {
                    if (it.streamId != null && it.streamId != contentId) return@collect
                    renderOverlay(it)
                }
            }
        }
    }

    fun observeZapState() {
        lifecycleScope.launch {
            activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.zapState.collect { state ->
                    if (contentType != ContentType.LIVE_TV) return@collect
                    val number = state?.let { (it.index + 1).coerceAtLeast(1) } ?: 1
                    tvChannelNumber?.text = number.toString()
                    liveOsd?.setChannelNumber(state?.let { it.index + 1 })
                    state?.let {
                        liveOsd?.setZapState(it.categoryId, it.categoryName, it.channels, it.index)
                        // The category the player is on NOW. It used to be read once from the
                        // launch intent and never updated, so a customer who changed category in
                        // here was handed back to the list still showing the one they left from.
                        activity.liveCategoryId = it.categoryId
                        viewModel.refreshSurfEpg()
                    }
                }
            }
        }
    }

}
