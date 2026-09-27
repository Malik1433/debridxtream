package com.tvonnet.debridxtreamiptv.player.stabilized

/**
 * Live anti-buffer the viewer does not notice (owner, 2026-09-27). A live `.ts` stream arrives at
 * real-time speed, so the player only ever holds the few seconds the server sent at connect, and
 * any hiccup at the provider shows on screen. Two quiet levers build a cushion instead of asking
 * the viewer anything:
 *
 *  - [LiveRebufferPatience]: once a channel has stalled more than once in a short while, the
 *    player waits for a bigger buffer before playing again - one slightly longer pause instead of
 *    a string of short ones.
 *  - [LiveSlowFillPolicy]: while the cushion is thin, play a touch slower than real time; the
 *    difference piles up as buffer, and at 0.97x nobody hears it.
 *
 * Both are pure; the clock is injected. See LivePatienceLoadControl / LiveSlowFill for the wiring.
 */
internal class LiveRebufferPatience(private val nowMs: () -> Long) {

    private val stallTimesMs = ArrayDeque<Long>()
    private var waitingSinceMs = NOT_WAITING
    private var streamKey: Any? = null
    private var startedOnce = false

    /** Called with the stall count and the buffer asked for, when a stall asks for more than usual. */
    @Volatile var onPatient: ((stallsInWindow: Int, targetMs: Long) -> Unit)? = null

    /** A different stream (a zap): the old channel's stalls say nothing about this one. */
    @Synchronized
    fun onStream(key: Any?) {
        if (key == streamKey) return
        streamKey = key
        reset()
    }

    @Synchronized
    fun reset() {
        stallTimesMs.clear()
        waitingSinceMs = NOT_WAITING
        startedOnce = false
    }

    /**
     * Any "may I start?" on a stream that already played once is a stall, not only Media3's own
     * rebuffer. Fire TV QA (2026-09-27): after a network drop the app's reconnect RE-PREPARES the
     * player, Media3 reports that start as a first load (rebuffering=false), and the stall count
     * never moved. The very first start of a stream is the only one that is not a stall.
     *
     * @param rebuffering Media3's own flag; [baseSaysGo] the player's own threshold answer.
     */
    @Synchronized
    fun shouldStart(bufferedMs: Long, rebuffering: Boolean, baseSaysGo: Boolean): Boolean {
        if (!rebuffering && !startedOnce) {
            if (baseSaysGo) startedOnce = true
            return baseSaysGo
        }
        startedOnce = true
        return shouldResume(bufferedMs, baseSaysGo)
    }

    /** True while a rebuffer is being held for more than the player's own threshold. */
    @Synchronized
    fun isWaitingFor(bufferedMs: Long): Boolean =
        waitingSinceMs != NOT_WAITING && bufferedMs < targetMs() && !waitedTooLong(nowMs())

    /**
     * One rebuffering "may I play?" check.
     * @param baseSaysGo the player's own answer (its usual 2-3 s rebuffer threshold).
     */
    @Synchronized
    fun shouldResume(bufferedMs: Long, baseSaysGo: Boolean): Boolean {
        val now = nowMs()
        if (waitingSinceMs == NOT_WAITING) {
            waitingSinceMs = now
            stallTimesMs.addLast(now)
            while (stallTimesMs.isNotEmpty() && now - stallTimesMs.first() > STALL_WINDOW_MS) stallTimesMs.removeFirst()
            targetMs().takeIf { it > 0 }?.let { onPatient?.invoke(stallTimesMs.size, it) }
        }
        val target = targetMs()
        val go = if (target <= 0L || waitedTooLong(now)) baseSaysGo else baseSaysGo && bufferedMs >= target
        if (go) waitingSinceMs = NOT_WAITING
        return go
    }

    /** The buffer to hold before resuming; 0 = the player's own threshold. */
    @Synchronized
    fun targetMs(): Long = when {
        stallTimesMs.size >= 3 -> THIRD_STALL_TARGET_MS
        stallTimesMs.size == 2 -> SECOND_STALL_TARGET_MS
        else -> 0L
    }

    /** Never hold a picture forever: a feed that cannot fill the cushion plays on what it has. */
    private fun waitedTooLong(now: Long) = waitingSinceMs != NOT_WAITING && now - waitingSinceMs >= MAX_WAIT_MS

    companion object {
        const val STALL_WINDOW_MS = 3 * 60_000L
        const val SECOND_STALL_TARGET_MS = 5_000L
        const val THIRD_STALL_TARGET_MS = 8_000L
        const val MAX_WAIT_MS = 10_000L
        private const val NOT_WAITING = Long.MIN_VALUE
    }
}

/** When to play a little slower than real time so the buffer grows (hysteresis, pure). */
internal class LiveSlowFillPolicy {
    var slow = false
        private set

    fun speedFor(bufferedMs: Long): Float {
        slow = if (slow) bufferedMs < FULL_MS else bufferedMs < LOW_MS
        return if (slow) SLOW_SPEED else NORMAL_SPEED
    }

    fun reset() {
        slow = false
    }

    companion object {
        const val SLOW_SPEED = 0.97f
        const val NORMAL_SPEED = 1f
        /** Below this much buffer, start filling. */
        const val LOW_MS = 5_000L
        /** Filled: back to real time. */
        const val FULL_MS = 8_000L
    }
}
