package com.tvonnet.debridxtreamiptv.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The guard window that stops a confirmation being answered by the press that opened it.
 *
 * The view work is a two-liner (disable the button, focus the safe one); the part worth pinning is
 * the rule, because the numbers are the whole argument: long enough to outlast a key's DOWN→UP,
 * short enough that nobody meets a dead button.
 */
class DeliberateDialogTest {

    @Test
    fun `the press that opened the dialog cannot answer it`() {
        // A remote's DOWN and UP are milliseconds apart - that is the gap being defended.
        assertTrue(DeliberateDialog.isTooSoon(shownAtMs = 1_000, nowMs = 1_000))
        assertTrue(DeliberateDialog.isTooSoon(shownAtMs = 1_000, nowMs = 1_060))
        assertTrue("even a slow repeat lands inside the window", DeliberateDialog.isTooSoon(1_000, 1_500))
    }

    @Test
    fun `a deliberate answer is never blocked`() {
        assertFalse(DeliberateDialog.isTooSoon(shownAtMs = 1_000, nowMs = 1_000 + DeliberateDialog.GUARD_MS))
        assertFalse("reading the sentence takes far longer than the guard", DeliberateDialog.isTooSoon(1_000, 4_000))
    }

    @Test
    fun `the window is short enough not to be felt and long enough to matter`() {
        assertTrue("shorter than this and a carried-over press gets through", DeliberateDialog.GUARD_MS >= 500)
        assertTrue("longer than this and it reads as a broken button", DeliberateDialog.GUARD_MS <= 1_000)
    }

    @Test
    fun `a delayed callback that arrives late still enables, never disables`() {
        // postDelayed can fire late on a paused window; the re-check must pass, not re-block.
        assertFalse(DeliberateDialog.isTooSoon(shownAtMs = 0, nowMs = 30_000))
    }
}
