package com.tvonnet.debridxtreamiptv.util

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Roadmap B3 — poster/logo URL resolution, which has caused visible breakage before.
 *
 * Providers hand back a mix of absolute URLs, root-relative paths, bare filenames and junk
 * (`"null"`, `"[]"`). Continue-Watching entries are *healed* through [GlobalConfig.resolveIconUrl]
 * on every read, so a change here silently blanks every stored poster. The subtle rule worth
 * pinning: when the base URL is not known yet, a relative path is returned **raw** rather than
 * null, so it can be resolved once login completes instead of being lost.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class IconUrlResolutionTest {

    private var savedBaseUrl: String = ""

    @Before
    fun setUp() {
        savedBaseUrl = GlobalConfig.baseUrl
    }

    @After
    fun tearDown() {
        GlobalConfig.baseUrl = savedBaseUrl
    }

    @Test
    fun `an absolute url is passed through`() {
        GlobalConfig.baseUrl = "http://provider.test:8080"

        assertEquals(
            "http://cdn.example.com/logo.png",
            GlobalConfig.resolveIconUrl("http://cdn.example.com/logo.png")
        )
    }

    @Test
    fun `a relative path is joined to the base exactly once`() {
        GlobalConfig.baseUrl = "http://provider.test:8080/"

        assertEquals("http://provider.test:8080/images/logo.png", GlobalConfig.resolveIconUrl("/images/logo.png"))
        assertEquals("http://provider.test:8080/logo.png", GlobalConfig.resolveIconUrl("logo.png"))
    }

    /** The rule that matters: an unresolvable path is kept, not discarded. */
    @Test
    fun `a relative path survives an unknown base so it can resolve after login`() {
        GlobalConfig.baseUrl = ""

        assertEquals(
            "returning null here would permanently blank stored posters",
            "logo.png",
            GlobalConfig.resolveIconUrl("logo.png")
        )
    }

    @Test
    fun `embedded whitespace from the provider is stripped`() {
        GlobalConfig.baseUrl = "http://provider.test:8080"

        assertEquals("http://provider.test:8080/logo.png", GlobalConfig.resolveIconUrl(" logo\n.png "))
    }

    @Test
    fun `blank input resolves to nothing`() {
        GlobalConfig.baseUrl = "http://provider.test:8080"

        assertNull(GlobalConfig.resolveIconUrl(null))
        assertNull(GlobalConfig.resolveIconUrl("   "))
    }

    // ── the "is there even an image" check ───────────────────────────────────

    @Test
    fun `provider junk counts as a missing image`() {
        assertTrue(isMissingImageUrl(null))
        assertTrue(isMissingImageUrl(""))
        assertTrue(isMissingImageUrl("   "))
        assertTrue("providers literally send the string null", isMissingImageUrl("null"))
        assertTrue(isMissingImageUrl("NULL"))
        assertTrue("and empty json containers", isMissingImageUrl("[]"))
        assertTrue(isMissingImageUrl("{}"))
    }

    @Test
    fun `a real url is not missing`() {
        assertFalse(isMissingImageUrl("http://cdn.example.com/logo.png"))
        assertFalse(isMissingImageUrl("logo.png"))
    }
}
