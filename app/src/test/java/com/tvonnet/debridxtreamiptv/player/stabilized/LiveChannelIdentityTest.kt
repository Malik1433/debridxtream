package com.tvonnet.debridxtreamiptv.player.stabilized

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Failover is only as safe as this rule. A miss costs a failover that could have worked; a FALSE
 * match hands the customer a different channel and tells them it is theirs, so the negative cases
 * below matter more than the positive ones.
 */
class LiveChannelIdentityTest {

    @Test
    fun `the same channel at different qualities is one channel`() {
        // Straight from the owner's own CRICKET category.
        assertTrue(LiveChannelIdentity.isSameChannel("PK: PTV SPORTS", "PK: PTV SPORTS HD"))
        assertTrue(LiveChannelIdentity.isSameChannel("PK: PTV SPORTS", "PK | PTV Sports FHD"))
        assertTrue(LiveChannelIdentity.isSameChannel("PK: PTV SPORTS HD", "ptv sports 1080p"))
    }

    @Test
    fun `different channels are never merged`() {
        // Both live in the same category and both end in SPORTS.
        assertFalse(LiveChannelIdentity.isSameChannel("PK: PTV SPORTS", "PK: A SPORTS HD"))
        assertFalse(LiveChannelIdentity.isSameChannel("PK | Geo Super HD", "PK: TEN SPORTS"))
    }

    @Test
    fun `a trailing number is part of the channel, not decoration`() {
        // The single most dangerous over-match: one character apart, different channels.
        assertFalse(LiveChannelIdentity.isSameChannel("SKY SPORTS 1", "SKY SPORTS 2"))
        assertFalse(LiveChannelIdentity.isSameChannel("IMAM HUSSAIN TV 1", "IMAM HUSSAIN TV 2"))
        assertTrue(LiveChannelIdentity.isSameChannel("SKY SPORTS 1 HD", "sky sports 1"))
    }

    @Test
    fun `the country prefix is which feed, not which channel`() {
        assertTrue(LiveChannelIdentity.isSameChannel("PK: PTV SPORTS", "UK - PTV Sports"))
        assertEquals("ptv sports", LiveChannelIdentity.key("PK: PTV SPORTS HD"))
    }

    @Test
    fun `language tag blocks come off, as they do everywhere else in this app`() {
        assertTrue(LiveChannelIdentity.isSameChannel("|AR| BeIN Sports 1", "BEIN SPORTS 1 HD"))
    }

    @Test
    fun `a name with nothing left to compare matches nothing`() {
        assertEquals("", LiveChannelIdentity.key("HD"))
        assertEquals("", LiveChannelIdentity.key(null))
        assertEquals("", LiveChannelIdentity.key("   "))
        // Two empties are not a pair.
        assertFalse(LiveChannelIdentity.isSameChannel(null, null))
        assertFalse(LiveChannelIdentity.isSameChannel("HD", "4K"))
    }

    @Test
    fun `punctuation and spacing never decide identity`() {
        assertTrue(LiveChannelIdentity.isSameChannel("PK: PTV-SPORTS", "PK: PTV  Sports"))
        assertTrue(LiveChannelIdentity.isSameChannel("Geo  Super", "GEO SUPER"))
    }
}
