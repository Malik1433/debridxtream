package com.tvonnet.debridxtreamiptv.player.stabilized

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Watches playback for a PATTERN of stalls and, when it finds one, asks who is at fault.
 *
 * Deliberately knows nothing about Android, the player or the network: the clock, the probe and the
 * device's connection state are all handed in, so the whole decision path is unit-testable and so
 * this class cannot accidentally do I/O on the main thread.
 *
 * The expensive half — the control probe — runs only when the stall pattern says something is
 * actually wrong, and no more than once per [PROBE_COOLDOWN_MS]. A diagnosis that ran on every
 * stutter would be a second network user competing with the stream it is diagnosing.
 */
internal class StreamHealthMonitor(
    private val scope: CoroutineScope,
    private val now: () -> Long,
    /** Throughput to an unrelated host in kbps, or null if it could not be measured. */
    private val probe: suspend () -> Long?,
    private val environment: () -> Environment,
    private val onVerdict: (StreamHealth) -> Unit,
) {

    /** What the device knows about itself at the moment of a stall. */
    data class Environment(
        val isWifi: Boolean,
        val wifiRssiDbm: Int?,
        val isReconnecting: Boolean,
    )

    private val stallsAt = ArrayDeque<Long>()
    private var longestStallMs = 0L
    private var bufferingStartedAt = 0L
    private var lastHttpCode: Int? = null
    private var lastProbeAt = 0L
    private var probing = false
    private var published: StreamHealth = StreamHealth.OK

    /** The player entered BUFFERING. */
    fun onBufferingStarted() {
        if (bufferingStartedAt == 0L) bufferingStartedAt = now()
    }

    /** The player reached READY again — one stall is now measurable. */
    fun onPlaybackResumed() {
        val startedAt = bufferingStartedAt
        bufferingStartedAt = 0L
        if (startedAt == 0L) return

        val stallMs = now() - startedAt
        // A sub-second gap is the player breathing, not a stall the customer noticed.
        if (stallMs < MIN_STALL_MS) return

        stallsAt.addLast(now())
        longestStallMs = maxOf(longestStallMs, stallMs)
        prune()
        evaluate()
    }

    /** The last status this stream's server returned. A fact, so it outranks any inference. */
    fun noteHttpStatus(code: Int?) {
        if (code != null) lastHttpCode = code
    }

    /** A new channel or a new source is a new question. */
    fun reset() {
        stallsAt.clear()
        longestStallMs = 0L
        bufferingStartedAt = 0L
        lastHttpCode = null
        publish(StreamHealth.OK)
    }

    private fun prune() {
        val cutoff = now() - WINDOW_MS
        while (stallsAt.isNotEmpty() && stallsAt.first() < cutoff) stallsAt.removeFirst()
        if (stallsAt.isEmpty()) longestStallMs = 0L
    }

    private fun signals(controlKbps: Long?): StreamHealthSignals {
        val env = environment()
        return StreamHealthSignals(
            stalls = stallsAt.size,
            longestStallMs = longestStallMs,
            controlKbps = controlKbps,
            lastHttpCode = lastHttpCode,
            isWifi = env.isWifi,
            wifiRssiDbm = env.wifiRssiDbm,
            isReconnecting = env.isReconnecting,
        )
    }

    /**
     * Decide with what we already hold; measure only if the answer needs it.
     *
     * The first pass runs WITHOUT a probe, so an HTTP status the provider sent us is acted on
     * immediately — there is nothing to measure when the server has already said 429.
     */
    private fun evaluate() {
        val immediate = diagnoseStreamHealth(signals(controlKbps = null))
        if (immediate != StreamHealth.OK) {
            publish(immediate)
            return
        }
        if (!needsProbe()) return
        lastProbeAt = now()
        probing = true
        scope.launch {
            try {
                publish(diagnoseStreamHealth(signals(controlKbps = probe())))
            } finally {
                probing = false
            }
        }
    }

    private fun needsProbe(): Boolean {
        if (probing) return false
        if (stallsAt.isEmpty()) return false
        if (lastProbeAt != 0L && now() - lastProbeAt < PROBE_COOLDOWN_MS) return false
        // Only worth measuring when the stalls already look like a pattern; diagnoseStreamHealth
        // would return OK below that no matter what the probe said.
        return diagnoseStreamHealth(signals(controlKbps = PROBE_WORTH_IT_PROBE)) != StreamHealth.OK
    }

    private fun publish(verdict: StreamHealth) {
        if (verdict == published) return
        published = verdict
        // Logged as well as shown: the pill is a sentence for the customer, this line is the
        // evidence behind it — and it is what a support conversation will actually need.
        Log.i(TAG, "stream health: $verdict (stalls=${stallsAt.size}, longest=${longestStallMs}ms, http=$lastHttpCode)")
        onVerdict(verdict)
    }

    private companion object {
        const val TAG = "StreamHealth"
        const val WINDOW_MS = 60_000L
        const val MIN_STALL_MS = 700L
        const val PROBE_COOLDOWN_MS = 45_000L

        /**
         * A stand-in "the line is fine" figure used ONLY to ask the rule whether the stall pattern
         * is strong enough to be worth measuring at all. It never reaches a customer-visible
         * verdict — that always comes from the real probe.
         */
        const val PROBE_WORTH_IT_PROBE = 999_999L
    }
}
