package com.tvonnet.debridxtreamiptv.player.stabilized

/**
 * Why playback keeps stopping — the customer's question, answered from measurement.
 *
 * "Buffering" is one symptom with several causes, and the customer cannot tell them apart: a weak
 * Wi-Fi signal, a slow line, a provider that is throttling them, and a channel the server simply
 * cannot feed all look identical from the sofa. So they blame the app, and the only person who can
 * actually fix it — them, their router, or their provider — never finds out.
 */
enum class StreamHealth {
    /** Nothing worth saying: either playback is fine, or there is not enough evidence yet. */
    OK,

    /** The line itself is slow right now — an unrelated host is slow too. */
    LOCAL_NETWORK_SLOW,

    /** Slow AND the Wi-Fi signal is weak, which is the cause far more often than the ISP is. */
    WIFI_WEAK,

    /** The line is fine and this stream still cannot keep up: the provider's server. */
    SERVER_SLOW,

    /** HTTP 429 — the provider is deliberately holding this device back. */
    PROVIDER_RATE_LIMITED,

    /** HTTP 403 on a live stream — almost always the account's max_connections. */
    PROVIDER_CONNECTION_LIMIT,
}

/**
 * Everything the verdict is allowed to use. One object so the rule stays a pure function of
 * measurements, testable without a player, a network or a device.
 */
data class StreamHealthSignals(
    /** Stalls counted inside the rolling window. */
    val stalls: Int,
    /** The longest single stall in that window. A 12s freeze matters even if it happened once. */
    val longestStallMs: Long,
    /** Throughput to an UNRELATED host, measured just now. Null when the probe has not answered. */
    val controlKbps: Long?,
    /** The last HTTP status this stream returned, if it returned one. */
    val lastHttpCode: Int?,
    val isWifi: Boolean,
    /** Wi-Fi signal in dBm; null off Wi-Fi. Weaker than about -70 is where video starts to suffer. */
    val wifiRssiDbm: Int?,
    /** True while the reconnect banner is already explaining itself. */
    val isReconnecting: Boolean,
)

/** Two stalls can be a bad moment; three in a minute is a pattern. */
private const val STALL_COUNT_THRESHOLD = 3

/** …and one long freeze is a pattern on its own. */
private const val LONG_STALL_MS = 10_000L

/** Below this, an unrelated host is struggling too, so the line is the problem. */
private const val CONTROL_SLOW_KBPS = 3_000L

/** Above this, the line can carry any stream this app plays, so a starved stream is not its fault. */
private const val CONTROL_HEALTHY_KBPS = 6_000L

/** Where Wi-Fi stops being able to hold a video bitrate reliably. */
private const val WEAK_RSSI_DBM = -70

/**
 * The whole rule, in one pure function.
 *
 * ⭐ **The verdict rests on the STALLS, not on the stream's measured throughput.** That is
 * deliberate and it is the part that is easy to get wrong: a live stream is not a download, so its
 * throughput equals its own bitrate no matter how fast the line is. A healthy 3 Mbps channel
 * measures 3 Mbps on a 500 Mbps connection, and "3 Mbps" therefore says nothing about whether the
 * stream is starved. What proves starvation is the buffer running dry — a stall. So: stalls are the
 * evidence that something is wrong, and the CONTROL probe against an unrelated host is what decides
 * whose fault it is.
 *
 * Ordered by certainty. An HTTP status the provider sent us is a fact; a throughput comparison is
 * an inference; and when neither is available the answer is [StreamHealth.OK], because telling a
 * customer the wrong cause is worse than telling them nothing.
 */
fun diagnoseStreamHealth(signals: StreamHealthSignals): StreamHealth {
    // The reconnect banner is already speaking. One screen, one answer.
    if (signals.isReconnecting) return StreamHealth.OK

    // What the provider told us outright, regardless of how much has stalled.
    when (signals.lastHttpCode) {
        HTTP_RATE_LIMITED -> return StreamHealth.PROVIDER_RATE_LIMITED
        HTTP_FORBIDDEN -> return StreamHealth.PROVIDER_CONNECTION_LIMIT
    }

    if (!isStruggling(signals)) return StreamHealth.OK

    val control = signals.controlKbps ?: return StreamHealth.OK // no probe, no verdict
    return when {
        control < CONTROL_SLOW_KBPS -> localCause(signals)
        control >= CONTROL_HEALTHY_KBPS -> StreamHealth.SERVER_SLOW
        // Between the two thresholds nothing can be said honestly: the line is mediocre and the
        // stream might legitimately need more than it has.
        else -> StreamHealth.OK
    }
}

private fun isStruggling(signals: StreamHealthSignals): Boolean =
    signals.stalls >= STALL_COUNT_THRESHOLD || signals.longestStallMs >= LONG_STALL_MS

/**
 * A slow line has two very different causes, and the customer can only fix one of them today.
 * Naming the Wi-Fi when the signal is weak is worth more than naming "the internet".
 */
private fun localCause(signals: StreamHealthSignals): StreamHealth {
    val rssi = signals.wifiRssiDbm
    return if (signals.isWifi && rssi != null && rssi <= WEAK_RSSI_DBM) {
        StreamHealth.WIFI_WEAK
    } else {
        StreamHealth.LOCAL_NETWORK_SLOW
    }
}

private const val HTTP_FORBIDDEN = 403
private const val HTTP_RATE_LIMITED = 429
