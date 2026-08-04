package com.tvonnet.debridxtreamiptv.ui.sources

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * H6: the pure halves of the reliability store — the Laplace score with its
 * minimum-evidence gate, and the family normalization both record and lookup use.
 * (The SharedPreferences plumbing is exercised on-device.)
 */
class AddonReliabilityStoreTest {

    @Test
    fun `score is neutral until five observations`() {
        assertEquals(0.5, AddonReliabilityStore.scoreOf(0, 0), 0.0001)
        assertEquals(0.5, AddonReliabilityStore.scoreOf(4, 0), 0.0001)
        assertEquals(0.5, AddonReliabilityStore.scoreOf(0, 4), 0.0001)
        assertEquals(0.5, AddonReliabilityStore.scoreOf(2, 2), 0.0001)
    }

    @Test
    fun `score is Laplace smoothed once evidence exists`() {
        // 5 successes, 0 failures: (5+1)/(5+0+2)
        assertEquals(6.0 / 7.0, AddonReliabilityStore.scoreOf(5, 0), 0.0001)
        // 0 successes, 5 failures: (0+1)/(0+5+2)
        assertEquals(1.0 / 7.0, AddonReliabilityStore.scoreOf(0, 5), 0.0001)
        // Mixed evidence lands between the extremes.
        val mixed = AddonReliabilityStore.scoreOf(6, 4)
        assertTrue(mixed > 1.0 / 7.0 && mixed < 6.0 / 7.0)
    }

    @Test
    fun `a reliable family outranks a flaky one and both straddle neutral`() {
        val reliable = AddonReliabilityStore.scoreOf(20, 2)
        val flaky = AddonReliabilityStore.scoreOf(3, 12)
        assertTrue(reliable > 0.5)
        assertTrue(flaky < 0.5)
        assertTrue(reliable > flaky)
    }

    @Test
    fun `family is the label head before any colon, case-folded`() {
        assertEquals("stremthru torz", AddonReliabilityStore.familyOf("StremThru Torz: Movie.Name.2026"))
        assertEquals("stremthru torz", AddonReliabilityStore.familyOf("stremthru torz"))
        assertEquals("aiostreams | elfhosted", AddonReliabilityStore.familyOf("AIOStreams | ElfHosted: x"))
        assertNull(AddonReliabilityStore.familyOf(null))
        assertNull(AddonReliabilityStore.familyOf("   "))
        assertNull(AddonReliabilityStore.familyOf(": payload-only"))
    }

    @Test
    fun `unknown family scores neutral`() {
        assertEquals(0.5, AddonReliabilityStore.scoreFor("never-seen-addon-family"), 0.0001)
        assertEquals(0.5, AddonReliabilityStore.scoreFor(null), 0.0001)
    }
}
