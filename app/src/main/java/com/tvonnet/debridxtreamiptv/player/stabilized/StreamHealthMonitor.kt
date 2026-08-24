package com.tvonnet.debridxtreamiptv.player.stabilized

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
    /** Whether the PROVIDER's own server answers; null if it could not be asked. */
    private val providerProbe: suspend () -> Boolean?,
    private val environment: () -> Environment,
    private val onVerdict: (StreamHealth) -> Unit,
    /**
     * Every non-OK verdict, whether or not it is put on screen — this is what ACTS on the
     * diagnosis (the live failover), and acting must not depend on how often we choose to speak.
     */
    private val onDiagnosis: (StreamHealth) -> Unit = {},
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
    private var alternatesExhausted = false
    private var lastProbeAt = 0L
    private var probing = false
    private var published: StreamHealth = StreamHealth.OK
    private var lastShown: StreamHealth? = null
    private var lastShownAt = 0L
    private var hideJob: Job? = null

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

    /**
     * Every other feed of this channel has been tried and none of them helped, so the verdict
     * stops recommending a switch and says what is actually true.
     */
    fun noteAlternatesExhausted() {
        if (alternatesExhausted) return
        alternatesExhausted = true
        // Re-ask now: the answer has changed even though nothing about the network has.
        published = StreamHealth.OK
        evaluate()
    }

    /** A new channel or a new source is a new question. */
    fun reset() {
        hideJob?.cancel()
        hideJob = null
        lastShown = null
        lastShownAt = 0L
        stallsAt.clear()
        longestStallMs = 0L
        bufferingStartedAt = 0L
        lastHttpCode = null
        alternatesExhausted = false
        publish(StreamHealth.OK)
    }

    private fun prune() {
        val cutoff = now() - WINDOW_MS
        while (stallsAt.isNotEmpty() && stallsAt.first() < cutoff) stallsAt.removeFirst()
        if (stallsAt.isEmpty()) longestStallMs = 0L
    }

    private fun signals(controlKbps: Long?, providerApiOk: Boolean? = null): StreamHealthSignals {
        val env = environment()
        return StreamHealthSignals(
            stalls = stallsAt.size,
            longestStallMs = longestStallMs,
            controlKbps = controlKbps,
            lastHttpCode = lastHttpCode,
            isWifi = env.isWifi,
            wifiRssiDbm = env.wifiRssiDbm,
            providerApiOk = providerApiOk,
            alternatesExhausted = alternatesExhausted,
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
                // Both measurements answer the same question from different sides, so they are
                // taken together: the line, and the provider's own server.
                val control = probe()
                val providerOk = providerProbe()
                publish(diagnoseStreamHealth(signals(control, providerOk)))
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

    /**
     * Say it, then stop saying it.
     *
     * A banner that arrives and never leaves becomes wallpaper — read once, ignored after a
     * minute, and still sitting on the picture an hour later. So a verdict shows for
     * [HEALTH_VISIBLE_MS] and goes; if the trouble is still there [HEALTH_RESHOW_MS] later it
     * says so again. A verdict that CHANGED always shows, because that is news.
     */
    private fun publish(verdict: StreamHealth) {
        if (verdict == published) return
        published = verdict

        if (verdict == StreamHealth.OK) {
            hideJob?.cancel()
            hideJob = null
            onVerdict(StreamHealth.OK)
            return
        }

        // Logged whatever the screen decides: the pill is a sentence for the customer, this line
        // is the evidence behind it, and it is what a support conversation will actually need.
        Log.i(TAG, "stream health: $verdict (stalls=${stallsAt.size}, longest=${longestStallMs}ms, http=$lastHttpCode)")
        onDiagnosis(verdict)

        if (!shouldShowHealth(verdict, lastShown, lastShownAt, now())) return
        lastShown = verdict
        lastShownAt = now()
        onVerdict(verdict)
        hideJob?.cancel()
        hideJob = scope.launch {
            delay(HEALTH_VISIBLE_MS)
            onVerdict(StreamHealth.OK)
        }
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
