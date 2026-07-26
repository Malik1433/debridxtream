package com.tvonnet.debridxtreamiptv.ui.live

import android.view.KeyEvent
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * LF-6: the **channel-grid focus + quick-jump** concern lifted out of [LiveFragment] — the app's most
 * D-pad-sensitive path. Owns focusing/scrolling a channel row into view (with the retry dance and the
 * `FocusCoordinator` gate), the D-pad key handling on a focused channel item, and the two quick-jump
 * modes (number-zap + A–Z type-ahead) with their HUD.
 *
 * Owns the transient focus/quick-jump state ([pendingChannelFocusPosition], the quick-jump buffer/job,
 * the HUD [tvQuickJump]). It does NOT own the shared `didRestoreFocusForThisView` flag (that stays in the
 * Fragment — it also gates category-focus restore and is reset by the lifecycle), reaching it through
 * [isFocusRestored] / [markFocusRestored]. Directional escapes off the grid go back to the Fragment /
 * siblings via [onFocusNavRail] (rail), [onFocusCategoryStrip] (LF-4 chip strip), [onFocusWatch] (the
 * fullscreen button) and [onUnblockCategoryFocus] (LF-4). Behaviour byte-identical to the old private
 * methods; only `this`/field refs were rebound.
 */
class LiveChannelFocusController(
    private val fragment: Fragment,
    private val viewModel: LiveViewModel,
    private val rvChannels: RecyclerView,
    private val channelPagingAdapter: ChannelPagingAdapter,
    private val tvQuickJump: TextView?,
    private val onFocusNavRail: () -> Boolean,
    private val onFocusCategoryStrip: () -> Boolean,
    private val onFocusWatch: () -> Boolean,
    private val onUnblockCategoryFocus: () -> Unit,
    private val isFocusRestored: () -> Boolean,
    private val markFocusRestored: () -> Unit,
) {
    private var pendingChannelFocusPosition: Int? = null
    private val quickJumpBuffer = StringBuilder()
    private var quickJumpJob: Job? = null

    private companion object {
        const val CHANNEL_FOCUS_RETRY_COUNT = 3
        const val CHANNEL_FOCUS_RETRY_DELAY_MS = 50L
        // Quick-jump timings: commit a typed number after a pause; flash the type-ahead letter.
        const val QUICK_JUMP_COMMIT_MS = 900L
        const val QUICK_JUMP_LETTER_MS = 700L
    }

    fun restoreFocusGrid() {
        if (isFocusRestored()) return
        val state = viewModel.uiState.value
        val position = state.lastFocusedChannelPosition ?: 0
        focusChannelItem(position, markRestored = true)
    }

    fun focusChannelItem(
        position: Int,
        markRestored: Boolean = false,
        attempt: Int = 0
    ) {
        if (!fragment.isAdded || channelPagingAdapter.itemCount <= 0) return
        val safePosition = position.coerceIn(0, channelPagingAdapter.itemCount - 1)
        com.tvonnet.debridxtreamiptv.utils.FocusCoordinator.requestFocus("LIVE_GRID") {
            rvChannels.post {
                if (!fragment.isAdded) {
                    com.tvonnet.debridxtreamiptv.utils.FocusCoordinator.release("LIVE_GRID")
                    return@post
                }
                rvChannels.scrollToPosition(safePosition)
                rvChannels.postDelayed({
                    if (!fragment.isAdded) {
                        com.tvonnet.debridxtreamiptv.utils.FocusCoordinator.release("LIVE_GRID")
                        return@postDelayed
                    }
                    try {
                        val itemView = rvChannels.findViewHolderForAdapterPosition(safePosition)?.itemView
                        val focused = itemView?.requestFocus() == true
                        if (focused) {
                            pendingChannelFocusPosition = null
                            if (markRestored) markFocusRestored()
                            onUnblockCategoryFocus()
                        } else if (attempt < CHANNEL_FOCUS_RETRY_COUNT) {
                            focusChannelItem(safePosition, markRestored, attempt + 1)
                        } else {
                            pendingChannelFocusPosition = null
                            onUnblockCategoryFocus()
                        }
                    } finally {
                        com.tvonnet.debridxtreamiptv.utils.FocusCoordinator.release("LIVE_GRID")
                    }
                }, CHANNEL_FOCUS_RETRY_DELAY_MS)
            }
        }
    }

    fun moveChannelFocusBy(delta: Int): Boolean {
        val itemCount = channelPagingAdapter.itemCount
        if (itemCount <= 0) return true

        val currentPosition = currentFocusedChannelPosition()
            ?: viewModel.uiState.value.lastFocusedChannelPosition
            ?: (rvChannels.layoutManager as? LinearLayoutManager)?.findFirstVisibleItemPosition()
            ?: 0
        if (currentPosition == RecyclerView.NO_POSITION) return true

        val basePosition = pendingChannelFocusPosition ?: currentPosition
        val targetPosition = LiveChannelQuickJump.targetFor(basePosition, delta, itemCount) ?: return true

        pendingChannelFocusPosition = targetPosition
        focusChannelItem(targetPosition)
        return true
    }

    fun handleChannelItemKey(position: Int, keyCode: Int, event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return false
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> {
                if (position == 0) {
                    pendingChannelFocusPosition = null
                    onFocusCategoryStrip()
                } else {
                    moveChannelFocusFrom(position, delta = -1)
                }
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> moveChannelFocusFrom(position, delta = 1)
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                pendingChannelFocusPosition = null
                onFocusNavRail()
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> onFocusWatch()
            in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 ->
                onQuickJumpDigit(position, keyCode - KeyEvent.KEYCODE_0)
            in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z ->
                onQuickJumpLetter(position, ('a' + (keyCode - KeyEvent.KEYCODE_A)))
            else -> false
        }
    }

    // ── Quick-jump: number-zap + A–Z type-ahead ──────────────────────────────
    // Both need a remote/keyboard with number or letter keys; on a plain D-pad they're inert.

    /** Digits accumulate into a channel number; after a short pause we jump to that row. */
    private fun onQuickJumpDigit(fromPosition: Int, digit: Int): Boolean {
        val itemCount = channelPagingAdapter.itemCount
        if (itemCount <= 0) return true
        val next = LiveChannelQuickJump.appendDigit(quickJumpBuffer.toString(), digit) ?: return true
        quickJumpBuffer.setLength(0)
        quickJumpBuffer.append(next)
        showQuickJumpHud(quickJumpBuffer.toString())
        quickJumpJob?.cancel()
        quickJumpJob = fragment.viewLifecycleOwner.lifecycleScope.launch {
            delay(QUICK_JUMP_COMMIT_MS)
            commitNumberJump()
        }
        return true
    }

    private fun commitNumberJump() {
        val number = quickJumpBuffer.toString().toIntOrNull()
        quickJumpBuffer.setLength(0)
        hideQuickJumpHud()
        if (number == null) return
        val target = LiveChannelQuickJump.positionForNumber(number, channelPagingAdapter.itemCount) ?: return
        pendingChannelFocusPosition = target
        focusChannelItem(target)
    }

    /** Type-ahead: jump to the next channel whose cleaned name starts with [letter], wrapping. */
    private fun onQuickJumpLetter(fromPosition: Int, letter: Char): Boolean {
        // A letter ends any pending number entry.
        quickJumpJob?.cancel()
        quickJumpBuffer.setLength(0)

        val items = channelPagingAdapter.snapshot().items
        if (items.isEmpty()) return true
        // Scan forward from the row after the current one, wrapping around.
        val idx = LiveChannelQuickJump.nextIndexStartingWith(items.map { it.name }, fromPosition, letter)
            ?: return true

        showQuickJumpHud(letter.uppercase())
        quickJumpJob?.cancel()
        quickJumpJob = fragment.viewLifecycleOwner.lifecycleScope.launch {
            delay(QUICK_JUMP_LETTER_MS)
            hideQuickJumpHud()
        }
        pendingChannelFocusPosition = idx
        focusChannelItem(idx)
        return true
    }

    private fun showQuickJumpHud(text: String) {
        tvQuickJump?.apply {
            this.text = text
            isVisible = true
        }
    }

    private fun hideQuickJumpHud() {
        tvQuickJump?.isVisible = false
    }

    private fun moveChannelFocusFrom(position: Int, delta: Int): Boolean {
        val itemCount = channelPagingAdapter.itemCount
        if (itemCount <= 0) return true

        val basePosition = pendingChannelFocusPosition ?: position
        val targetPosition = LiveChannelQuickJump.targetFor(basePosition, delta, itemCount) ?: return true

        pendingChannelFocusPosition = targetPosition
        focusChannelItem(targetPosition)
        return true
    }

    private fun currentFocusedChannelPosition(): Int? {
        val focused = rvChannels.findFocus() ?: return null
        val holder = rvChannels.findContainingViewHolder(focused) ?: return null
        return holder.bindingAdapterPosition.takeIf { it != RecyclerView.NO_POSITION }
    }

    fun isFirstChannelFocused(): Boolean = currentFocusedChannelPosition() == 0

    fun restoreChannelFocusIfNeeded() {
        if (isFocusRestored()) return
        val state = viewModel.uiState.value
        if (state.lastFocusArea != LiveFocusArea.CHANNELS) return

        val targetStreamId = state.lastFocusedChannelId ?: state.lastPlayedChannelId ?: return
        val index = channelPagingAdapter.snapshot().items.indexOfFirst { it.stream_id == targetStreamId }
        if (index < 0) return

        focusChannelItem(index, markRestored = true)
    }

    /** onDestroyView teardown: cancel the quick-jump job + clear its buffer/HUD. */
    fun clear() {
        quickJumpJob?.cancel()
        quickJumpJob = null
        quickJumpBuffer.setLength(0)
        tvQuickJump?.isVisible = false
    }
}
