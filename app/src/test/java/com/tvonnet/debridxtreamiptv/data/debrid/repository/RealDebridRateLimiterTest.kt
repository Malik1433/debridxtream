package com.tvonnet.debridxtreamiptv.data.debrid.repository

import com.tvonnet.debridxtreamiptv.data.debrid.model.DebridFailureType
import com.tvonnet.debridxtreamiptv.data.debrid.model.DebridResolutionException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RealDebridRateLimiterTest {

    @Test
    fun `rate limit creates immediate global cooldown failure`() {
        val limiter = RealDebridRateLimiter()

        limiter.recordFailure(
            key = "source-1",
            error = DebridResolutionException(
                type = DebridFailureType.RATE_LIMITED,
                message = "rate limited",
                retryAfterSeconds = 1L
            )
        )

        val cooldown = limiter.globalCooldown()

        assertNotNull(cooldown)
        assertEquals(DebridFailureType.RATE_LIMITED, cooldown?.type)
        assertTrue(cooldown?.retryAfterSeconds ?: 0L >= 1L)
    }
}
