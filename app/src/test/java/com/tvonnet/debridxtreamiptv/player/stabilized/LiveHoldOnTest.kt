package com.tvonnet.debridxtreamiptv.player.stabilized

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Match mode: a Live channel keeps being retried while it is only overloaded, and not forever. */
class LiveHoldOnTest {

    private var now = 1_000_000L
    private val hold = LiveHoldOn { now }

    private fun next(httpCode: Int? = null, notNetwork: Boolean = false) = hold.nextRetryDelayMs(httpCode, notNetwork)

    @Test
    fun `backoff grows then stays at 30 s`() {
        assertEquals(5_000L, next())
        assertEquals(10_000L, next(httpCode = 503))
        assertEquals(20_000L, next(httpCode = 403))
        assertEquals(30_000L, next())
        assertEquals(30_000L, next())
    }

    @Test
    fun `a recovered channel starts the next outage from the short pause`() {
        next(); next(); next()
        hold.onRecovered()
        assertEquals(5_000L, next())
    }

    @Test
    fun `gone accounts and channels are not waited on`() {
        LiveHoldOn.GIVE_UP_HTTP_CODES.forEach { assertNull("HTTP $it", next(httpCode = it)) }
    }

    @Test
    fun `a failure that is not the network's is not waited on`() {
        assertNull(next(notNetwork = true))
    }

    @Test
    fun `it gives up after 30 minutes of continuous failure`() {
        assertEquals(5_000L, next())
        now += LiveHoldOn.MAX_HOLD_MS
        assertEquals(10_000L, next())
        now += 1
        assertNull(next())
    }

    @Test
    fun `the 30 minutes restart after a recovery`() {
        next()
        now += LiveHoldOn.MAX_HOLD_MS + 1
        hold.onRecovered()
        assertEquals(5_000L, next())
    }
}
