package com.tvonnet.debridxtreamiptv.player.stabilized

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.TimeZone

/** C1: the timeshift URL shape the C0 network check proved against the provider. */
class CatchupUrlBuilderTest {

    private val amsterdam = TimeZone.getTimeZone("Europe/Amsterdam")

    // 2026-09-27 11:10:30 UTC = 13:10 in Amsterdam (CEST, UTC+2).
    private val start = 1_790_507_430_000L

    @Test
    fun `start is named in the provider's time zone, to the minute`() {
        assertEquals("2026-09-27:13-10", CatchupUrlBuilder.startParam(start, amsterdam))
        assertEquals("2026-09-27:11-10", CatchupUrlBuilder.startParam(start, TimeZone.getTimeZone("UTC")))
    }

    @Test
    fun `url follows the timeshift path`() {
        assertEquals(
            "http://prov.example/timeshift/u/p/30/2026-09-27:13-10/4242.ts",
            CatchupUrlBuilder.url(CatchupUrlBuilder.Account("http://prov.example/", "u", "p"), "4242", start, 30, amsterdam)
        )
    }

    @Test
    fun `duration is never below one minute`() {
        val url = CatchupUrlBuilder.url(CatchupUrlBuilder.Account("http://prov.example", "u", "p"), "1", start, 0, amsterdam)
        assertEquals("http://prov.example/timeshift/u/p/1/2026-09-27:13-10/1.ts", url)
    }
}
