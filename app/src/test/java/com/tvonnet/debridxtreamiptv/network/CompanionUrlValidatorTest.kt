package com.tvonnet.debridxtreamiptv.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CompanionUrlValidatorTest {
    @Test
    fun `accepts http and https urls with host`() {
        assertTrue(CompanionUrlValidator.isSafeHttpUrl("https://example.com/manifest.json"))
        assertTrue(CompanionUrlValidator.isSafeHttpUrl("http://192.168.0.84:8085/"))
    }

    @Test
    fun `rejects unsafe schemes and credentialed urls`() {
        assertFalse(CompanionUrlValidator.isSafeHttpUrl("javascript:alert(1)"))
        assertFalse(CompanionUrlValidator.isSafeHttpUrl("file:///etc/passwd"))
        assertFalse(CompanionUrlValidator.isSafeHttpUrl("https://user:pass@example.com/manifest.json"))
    }

    @Test
    fun `normalizes stremio scheme to https manifest url`() {
        assertEquals(
            "https://example.com/abc/manifest.json",
            CompanionUrlValidator.normalizeSafeAddonUrl("stremio://example.com/abc/manifest.json/")
        )
    }

    @Test
    fun `normalizes only safe urls from collection`() {
        val normalized = CompanionUrlValidator.normalizeSafeAddonUrls(
            listOf(
                " https://example.com/manifest.json ",
                "javascript:alert(1)",
                "https://example.com/manifest.json/"
            )
        )

        assertEquals(listOf("https://example.com/manifest.json"), normalized)
    }

    @Test
    fun `returns null for blank or invalid url`() {
        assertNull(CompanionUrlValidator.normalizeSafeHttpUrl(""))
        assertNull(CompanionUrlValidator.normalizeSafeHttpUrl("not a url"))
    }
}
