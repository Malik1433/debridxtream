package com.tvonnet.debridxtreamiptv.player.stabilized

/**
 * "Match mode" (2026-09-27): a Live channel does not give up while the viewer is still on it.
 *
 * On a big match night the provider's servers are overloaded for minutes at a time. The old path
 * spent two retries and every other feed of the channel inside a minute, then showed "Channel
 * stream is broken at the provider" and closed the player - exactly when the viewer most wanted it
 * to keep trying. Now, once retries and alternate feeds are exhausted, the channel is tried again
 * after a growing pause ([BACKOFF_MS]), round after round, until it plays or [MAX_HOLD_MS] of
 * continuous failure has passed.
 *
 * It still gives up at once when waiting cannot help: the account or the channel is gone
 * ([GIVE_UP_HTTP_CODES]) or the failure is not the network's (a wedged audio route, a player that
 * cannot even initialise). Rate limits are already spaced by the recovery code, and the backoff here
 * keeps a single-connection account from being hammered.
 *
 * Pure policy with an injected clock; the player screen does the scheduling.
 */
internal class LiveHoldOn(private val nowMs: () -> Long) {

    private var attempts = 0
    private var holdingSinceMs = NOT_HOLDING

    /** Delay before trying the channel again, or null to give up and show the error. */
    fun nextRetryDelayMs(httpCode: Int?, notANetworkFailure: Boolean): Long? {
        if (notANetworkFailure || httpCode in GIVE_UP_HTTP_CODES) return null
        val now = nowMs()
        if (holdingSinceMs == NOT_HOLDING) holdingSinceMs = now
        if (now - holdingSinceMs > MAX_HOLD_MS) return null
        return BACKOFF_MS[attempts.coerceAtMost(BACKOFF_MS.lastIndex)].also { attempts++ }
    }

    /** A frame rendered: the channel is back, so the next outage starts from the short pause. */
    fun onRecovered() {
        attempts = 0
        holdingSinceMs = NOT_HOLDING
    }

    companion object {
        val BACKOFF_MS = longArrayOf(5_000L, 10_000L, 20_000L, 30_000L)
        const val MAX_HOLD_MS = 30 * 60_000L
        /** Account refused, channel removed, or legally blocked: no amount of waiting fixes these. */
        val GIVE_UP_HTTP_CODES = setOf(401, 404, 410, 451)
        private const val NOT_HOLDING = Long.MIN_VALUE
    }
}
