package com.tvonnet.debridxtreamiptv.player.stabilized

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Roadmap C8 — the logo placeholder and quality badge shared by the channel bug, the surf drawer's
 * preview strip, the TV Guide rows and the ON AIR strip.
 *
 * Worth pinning because the same channel has to look identical in all four places, and because the
 * quality rules read a *substring* of a free-text provider name — the order of those checks is the
 * whole correctness of the badge.
 */
class LiveChannelVisualsTest {

    // ── initials ───────────────────────────────────────────────────────────────

    @Test
    fun `initials come from the first word that has letters or digits`() {
        assertEquals("SKY", LiveChannelVisuals.channelInitials("Sky Sports"))
        assertEquals("BBC1", LiveChannelVisuals.channelInitials("BBC1 HD"))
    }

    @Test
    fun `a region prefix is dropped so channels in one region do not all look alike`() {
        // Providers name channels "UK: Sky Sports"; keeping the prefix would render every UK
        // channel as "UK".
        assertEquals("SKY", LiveChannelVisuals.channelInitials("UK: Sky Sports"))
        assertEquals("ESPN", LiveChannelVisuals.channelInitials("US: ESPN 2"))
    }

    @Test
    fun `separators are treated as word breaks and punctuation is stripped`() {
        assertEquals("FOX", LiveChannelVisuals.channelInitials("FOX-News"))
        assertEquals("TF1", LiveChannelVisuals.channelInitials("TF1_HD"))
    }

    @Test
    fun `initials are capped at four characters`() {
        assertEquals("DISC", LiveChannelVisuals.channelInitials("Discovery Channel"))
    }

    @Test
    fun `a nameless or symbol-only channel still gets a placeholder`() {
        assertEquals("TV", LiveChannelVisuals.channelInitials(null))
        assertEquals("TV", LiveChannelVisuals.channelInitials("   "))
        assertEquals("TV", LiveChannelVisuals.channelInitials("+++"))
    }

    // ── quality badge ──────────────────────────────────────────────────────────

    @Test
    fun `4K and UHD both read as 4K UHD`() {
        assertEquals("4K UHD", LiveChannelVisuals.detectQuality("Sky Sports 4K"))
        assertEquals("4K UHD", LiveChannelVisuals.detectQuality("Sky Sports UHD"))
        assertEquals("4K UHD", LiveChannelVisuals.detectQuality("sky sports uhd"))
    }

    @Test
    fun `FHD and 1080 are tested before the bare HD they both contain`() {
        // "FHD" contains "HD"; if the checks ran the other way round every FHD channel
        // would be badged HD.
        assertEquals("FHD", LiveChannelVisuals.detectQuality("Canal+ FHD"))
        assertEquals("FHD", LiveChannelVisuals.detectQuality("Canal+ 1080p"))
        assertEquals("HD", LiveChannelVisuals.detectQuality("Canal+ HD"))
    }

    @Test
    fun `a channel with no quality marker gets no badge rather than a default one`() {
        assertNull(LiveChannelVisuals.detectQuality("Sky Sports"))
        assertNull(LiveChannelVisuals.detectQuality(null))
    }

    // ── colour stability ───────────────────────────────────────────────────────

    @Test
    fun `the accent colour is stable for a name, so one channel looks the same everywhere`() {
        val first = LiveChannelVisuals.accentColor("Sky Sports")
        repeat(5) { assertEquals(first, LiveChannelVisuals.accentColor("Sky Sports")) }
    }

    @Test
    fun `a null name resolves to a colour instead of throwing`() {
        // Hashing null must not blow up on a channel the provider failed to name.
        LiveChannelVisuals.accentColor(null)
        LiveChannelVisuals.watermarkGradient(null)
    }
}
