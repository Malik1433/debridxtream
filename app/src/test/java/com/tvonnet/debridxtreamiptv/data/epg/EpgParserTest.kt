package com.tvonnet.debridxtreamiptv.data.epg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class EpgParserTest {

    @Test
    fun testParseTimestampWithNoOffset() {
        val timestamp = "20231105143000"
        val parsed = EpgParser.parseTimestamp(timestamp)
        
        val expectedCal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            set(2023, Calendar.NOVEMBER, 5, 14, 30, 0)
            set(Calendar.MILLISECOND, 0)
        }
        assertEquals(expectedCal.timeInMillis, parsed)
    }

    @Test
    fun testParseTimestampWithPositiveOffsetSpace() {
        val timestamp = "20231105143000 +0200"
        val parsed = EpgParser.parseTimestamp(timestamp)
        
        val expectedCal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            set(2023, Calendar.NOVEMBER, 5, 12, 30, 0)
            set(Calendar.MILLISECOND, 0)
        }
        assertEquals(expectedCal.timeInMillis, parsed)
    }

    @Test
    fun testParseTimestampWithPositiveOffsetColon() {
        val timestamp = "20231105143000 +02:00"
        val parsed = EpgParser.parseTimestamp(timestamp)
        
        val expectedCal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            set(2023, Calendar.NOVEMBER, 5, 12, 30, 0)
            set(Calendar.MILLISECOND, 0)
        }
        assertEquals(expectedCal.timeInMillis, parsed)
    }

    @Test
    fun testParseTimestampWithNegativeOffset() {
        val timestamp = "20231105143000 -0500"
        val parsed = EpgParser.parseTimestamp(timestamp)
        
        val expectedCal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            set(2023, Calendar.NOVEMBER, 5, 19, 30, 0)
            set(Calendar.MILLISECOND, 0)
        }
        assertEquals(expectedCal.timeInMillis, parsed)
    }

    @Test
    fun testParseTimestampInvalidReturnsSentinel() {
        // Malformed/absent timestamps must NOT fabricate 'now' — doing so injected bogus
        // "now-airing" programmes into the guide. They return the -1L sentinel; the parser
        // then skips (invalid start) or repairs (invalid stop) the programme instead of
        // placing it at the current time.
        assertEquals(-1L, EpgParser.parseTimestamp(null))
        assertEquals(-1L, EpgParser.parseTimestamp("202311"))
    }
}
