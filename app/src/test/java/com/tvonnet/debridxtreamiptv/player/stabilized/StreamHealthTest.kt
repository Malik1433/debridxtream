package com.tvonnet.debridxtreamiptv.player.stabilized

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The rule that tells a customer WHY playback keeps stopping. Every case here is one the app used
 * to answer with a spinner and nothing else.
 */
class StreamHealthTest {

    private fun signals(
        stalls: Int = 0,
        longestStallMs: Long = 0,
        controlKbps: Long? = null,
        lastHttpCode: Int? = null,
        isWifi: Boolean = true,
        wifiRssiDbm: Int? = -50,
        providerApiOk: Boolean? = null,
        isReconnecting: Boolean = false,
    ) = StreamHealthSignals(
        stalls, longestStallMs, controlKbps, lastHttpCode, isWifi, wifiRssiDbm,
        providerApiOk, isReconnecting,
    )

    @Test
    fun `a healthy stream says nothing`() {
        assertEquals(StreamHealth.OK, diagnoseStreamHealth(signals(stalls = 0, controlKbps = 50_000)))
    }

    @Test
    fun `one bad moment is not a pattern`() {
        // Two stalls happen on any network. The banner must not cry wolf.
        assertEquals(
            StreamHealth.OK,
            diagnoseStreamHealth(signals(stalls = 2, longestStallMs = 3_000, controlKbps = 50_000)),
        )
    }

    @Test
    fun `a fast line and a provider that answers means this CHANNEL is the problem`() {
        // The server is up — it just answered its own API — so the fault is this feed, and
        // another feed of the same channel is worth trying.
        assertEquals(
            StreamHealth.CHANNEL_SLOW,
            diagnoseStreamHealth(signals(stalls = 3, controlKbps = 50_000, providerApiOk = true)),
        )
    }

    @Test
    fun `a fast line and a provider that will not answer blames the server`() {
        assertEquals(
            StreamHealth.SERVER_SLOW,
            diagnoseStreamHealth(signals(stalls = 3, controlKbps = 50_000, providerApiOk = false)),
        )
    }

    @Test
    fun `an unmeasured server is never blamed`() {
        // Accusing a whole server takes evidence; without it we say the thing we can stand behind.
        assertEquals(
            StreamHealth.CHANNEL_SLOW,
            diagnoseStreamHealth(signals(stalls = 3, controlKbps = 50_000, providerApiOk = null)),
        )
    }

    @Test
    fun `one long freeze is a pattern on its own`() {
        assertEquals(
            StreamHealth.CHANNEL_SLOW,
            diagnoseStreamHealth(signals(stalls = 1, longestStallMs = 12_000, controlKbps = 50_000)),
        )
    }

    @Test
    fun `stalling while everything is slow blames the line`() {
        assertEquals(
            StreamHealth.LOCAL_NETWORK_SLOW,
            diagnoseStreamHealth(
                signals(stalls = 4, controlKbps = 900, isWifi = false, wifiRssiDbm = null)
            ),
        )
    }

    @Test
    fun `a weak wifi signal is named, because that is the half the customer can fix`() {
        assertEquals(
            StreamHealth.WIFI_WEAK,
            diagnoseStreamHealth(signals(stalls = 4, controlKbps = 900, wifiRssiDbm = -82)),
        )
    }

    @Test
    fun `a strong signal on a slow line is the line, not the wifi`() {
        assertEquals(
            StreamHealth.LOCAL_NETWORK_SLOW,
            diagnoseStreamHealth(signals(stalls = 4, controlKbps = 900, wifiRssiDbm = -45)),
        )
    }

    @Test
    fun `what the provider said outranks anything we infer`() {
        // 429 with a fast line and no stalls at all is still a rate limit.
        assertEquals(
            StreamHealth.PROVIDER_RATE_LIMITED,
            diagnoseStreamHealth(signals(lastHttpCode = 429, controlKbps = 50_000)),
        )
        assertEquals(
            StreamHealth.PROVIDER_CONNECTION_LIMIT,
            diagnoseStreamHealth(signals(lastHttpCode = 403, controlKbps = 50_000)),
        )
    }

    @Test
    fun `no probe means no verdict`() {
        // Guessing the cause is worse than staying quiet: the customer would go and reset a router
        // that was never the problem.
        assertEquals(
            StreamHealth.OK,
            diagnoseStreamHealth(signals(stalls = 5, controlKbps = null)),
        )
    }

    @Test
    fun `a mediocre line is left unexplained rather than blamed`() {
        assertEquals(
            StreamHealth.OK,
            diagnoseStreamHealth(signals(stalls = 5, controlKbps = 4_500)),
        )
    }

    @Test
    fun `the reconnect banner is not talked over`() {
        assertEquals(
            StreamHealth.OK,
            diagnoseStreamHealth(signals(stalls = 6, controlKbps = 50_000, isReconnecting = true)),
        )
    }
}
