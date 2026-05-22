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
    fun testParseTimestampInvalidGracefulFallback() {
        val now = System.currentTimeMillis()
        val parsedNull = EpgParser.parseTimestamp(null)
        val parsedShort = EpgParser.parseTimestamp("202311")
        
        // Should fall back to current time, allow a small margin for execution time
        assertTrue(parsedNull >= now - 5000)
        assertTrue(parsedShort >= now - 5000)
    }
}
