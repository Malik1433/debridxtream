package com.tvonnet.debridxtreamiptv.ui.trailer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TrailerValueParserTest {

    @Test
    fun `parse accepts plain youtube id`() {
        val result = TrailerValueParser.parse("dQw4w9WgXcQ")

        assertEquals("dQw4w9WgXcQ", result?.youtubeId)
    }

    @Test
    fun `parse extracts id from youtube watch url`() {
        val result = TrailerValueParser.parse("https://www.youtube.com/watch?v=dQw4w9WgXcQ")

        assertEquals("dQw4w9WgXcQ", result?.youtubeId)
    }

    @Test
    fun `parse extracts id from youtu be url`() {
        val result = TrailerValueParser.parse("https://youtu.be/dQw4w9WgXcQ")

        assertEquals("dQw4w9WgXcQ", result?.youtubeId)
    }

    @Test
    fun `parse extracts id from embed url`() {
        val result = TrailerValueParser.parse("https://www.youtube.com/embed/dQw4w9WgXcQ")

        assertEquals("dQw4w9WgXcQ", result?.youtubeId)
    }

    @Test
    fun `parse rejects non youtube url`() {
        val result = TrailerValueParser.parse("https://example.com/trailer.mp4")

        assertNull(result)
    }

    @Test
    fun `parse rejects blank input`() {
        val result = TrailerValueParser.parse("   ")

        assertNull(result)
    }
}
