package com.tvonnet.debridxtreamiptv.player.stabilized

import android.os.Handler

/**
 * The channel-zap debounce (LP-B-1), extracted in Phase P18.
 *
 * Surfing must FEEL instant, so the OSD advances on every keypress — but the real
 * stream connect is coalesced to the LAST target: one commit, [debounceMs] after the
 * user stops pressing. Without this, holding the D-pad opened a connection per step
 * and hammered a max_connections=1 provider.
 *
 * Scope note: the commit itself (reconnect-budget reset, channel meta, EPG observe,
 * performSeamlessSwitch) stays in [PlayerActivity] — this only decides WHEN it runs.
 */
internal class PlayerZapDebouncer(
    private val handler: Handler,
    private val debounceMs: Long,
    private val onCommit: (ZapChannel) -> Unit
) {

    private var pendingTarget: ZapChannel? = null

    private val commitRunnable = Runnable {
        val target = pendingTarget ?: return@Runnable
        pendingTarget = null
        onCommit(target)
    }

    /** Replaces any pending target and restarts the window. */
    fun schedule(target: ZapChannel) {
        pendingTarget = target
        handler.removeCallbacks(commitRunnable)
        handler.postDelayed(commitRunnable, debounceMs)
    }

    /**
     * Drops a pending zap. Called at the top of every real tune so a late commit
     * cannot fire on top of the tune that is already happening, and from onStop.
     */
    fun cancel() {
        handler.removeCallbacks(commitRunnable)
        pendingTarget = null
    }

    /** Visible for tests / diagnostics: is a commit still queued? */
    fun hasPending(): Boolean = pendingTarget != null
}
