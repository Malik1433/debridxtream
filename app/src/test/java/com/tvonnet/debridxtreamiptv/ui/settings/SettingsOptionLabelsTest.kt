package com.tvonnet.debridxtreamiptv.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tier C1 — the settings option vocabulary extracted out of `SettingsFragment`.
 *
 * Every selector on that screen answers two questions from a stored string: which radio row starts
 * checked, and what does the summary line read. Each one carried its own default, and they are not
 * the same default — audio language and player engine fall back to the FIRST option, while EPG
 * interval, timeline zoom and row density fall back to the SECOND (the "standard" middle). Getting
 * one wrong shows the user a setting they do not have, so the defaults are pinned individually.
 */
class SettingsOptionLabelsTest {

    @Test
    fun `every value list lines up with its label list`() {
        assertEquals(SettingsOptionLabels.audioLangValues.size, SettingsOptionLabels.audioLangLabels.size)
        assertEquals(SettingsOptionLabels.engineValues.size, SettingsOptionLabels.engineLabels.size)
        assertEquals(SettingsOptionLabels.epgIntervalValues.size, SettingsOptionLabels.epgIntervalLabels.size)
        assertEquals(SettingsOptionLabels.liveTvStyleValues.size, SettingsOptionLabels.liveTvStyleLabels.size)
        assertEquals(SettingsOptionLabels.epgZoomValues.size, SettingsOptionLabels.epgZoomLabels.size)
        assertEquals(SettingsOptionLabels.epgDensityValues.size, SettingsOptionLabels.epgDensityLabels.size)
    }

    @Test
    fun `audio language matches case-insensitively and defaults to English`() {
        assertEquals("Hindi (HI)", SettingsOptionLabels.audioLangName("hi"))
        assertEquals("No preference (ALL)", SettingsOptionLabels.audioLangName("ALL"))
        assertEquals("English (EN)", SettingsOptionLabels.audioLangName("klingon"))
        assertEquals(0, SettingsOptionLabels.audioLangIndex(""))
    }

    @Test
    fun `player engine defaults to the first option`() {
        assertEquals("VLC Player", SettingsOptionLabels.engineName("vlc"))
        assertEquals(2, SettingsOptionLabels.engineIndex("mx"))
        assertEquals(0, SettingsOptionLabels.engineIndex("something-else"))
    }

    /**
     * Deliberate quirk kept from the original: an unrecognised engine *summarises* as the bare
     * "ExoPlayer" while the picker checks "ExoPlayer (Default)". Recorded so a future tidy-up is a
     * decision rather than an accident.
     */
    @Test
    fun `an unknown engine summarises differently from how it is checked`() {
        assertEquals("ExoPlayer", SettingsOptionLabels.engineName("garbage"))
        assertEquals("ExoPlayer (Default)", SettingsOptionLabels.engineLabels[SettingsOptionLabels.engineIndex("garbage")])
    }

    @Test
    fun `EPG interval defaults to six hours, not three`() {
        assertEquals(0, SettingsOptionLabels.epgIntervalIndex("3"))
        assertEquals(3, SettingsOptionLabels.epgIntervalIndex("24"))
        assertEquals("an unknown interval must check 'Every 6 hours'", 1, SettingsOptionLabels.epgIntervalIndex("7"))
        assertEquals("Every 12 hours", SettingsOptionLabels.epgIntervalName("12"))
    }

    @Test
    fun `live TV layout defaults to the new guide`() {
        assertEquals("Classic (3-column)", SettingsOptionLabels.liveTvStyleName("classic"))
        assertEquals("New EPG Guide", SettingsOptionLabels.liveTvStyleName("nonsense"))
    }

    @Test
    fun `guide zoom and density both default to standard`() {
        assertEquals("Compact", SettingsOptionLabels.epgZoomName("compact"))
        assertEquals("Standard", SettingsOptionLabels.epgZoomName("nonsense"))
        assertEquals(1, SettingsOptionLabels.epgZoomIndex("nonsense"))

        assertEquals("Roomy", SettingsOptionLabels.epgDensityName("roomy"))
        assertEquals("Standard", SettingsOptionLabels.epgDensityName("nonsense"))
        assertEquals(1, SettingsOptionLabels.epgDensityIndex("nonsense"))
    }

    // ── addon manifest validation ────────────────────────────────────────────

    @Test
    fun `a valid stremio manifest url is accepted`() {
        assertTrue(SettingsOptionLabels.isValidStremioManifestUrl("https://addon.example/config/manifest.json"))
        assertTrue(SettingsOptionLabels.isValidStremioManifestUrl("stremio://addon.example/manifest.json"))
        assertTrue("surrounding whitespace is the normal paste", SettingsOptionLabels.isValidStremioManifestUrl("  https://a.test/manifest.json  "))
        assertTrue("scheme and path are matched case-insensitively", SettingsOptionLabels.isValidStremioManifestUrl("HTTPS://A.TEST/MANIFEST.JSON"))
    }

    /** Plain http would send the user's addon traffic in the clear. */
    @Test
    fun `an insecure or incomplete url is rejected`() {
        assertFalse(SettingsOptionLabels.isValidStremioManifestUrl("http://addon.example/manifest.json"))
        assertFalse(SettingsOptionLabels.isValidStremioManifestUrl("https://addon.example/"))
        assertFalse(SettingsOptionLabels.isValidStremioManifestUrl("addon.example/manifest.json"))
        assertFalse(SettingsOptionLabels.isValidStremioManifestUrl(""))
    }
}
