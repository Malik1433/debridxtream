package com.tvonnet.debridxtreamiptv.player.stabilized

import android.os.Handler
import android.view.View
import android.widget.TextView
import com.tvonnet.debridxtreamiptv.data.prefs.SettingsPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import io.mockk.mockk
import io.mockk.verify
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Freezes the shared reconnect budget extracted in P10 — the ceiling that stops a
 * dead stream from chaining error-retry + ENDED-reconnect + freeze-re-prepare into a
 * burst against a WAF / max_connections=1 provider.
 *
 * Banner rendering is not exercised here (no views); the host reports "gone" so the
 * delayed show is never presented.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PlayerReconnectBudgetTest {

    private class NoUiHost : ReconnectBannerHost {
        override fun bannerView(): View? = null
        override fun bannerTextView(): TextView? = null
        override fun bannerLabel(attempt: Int, max: Int): String = "$attempt/$max"
        override fun isLoaderVisible(): Boolean = false
        override fun isGone(): Boolean = true
    }

    private fun manager(): PlayerReconnectManager = PlayerReconnectManager(
        handler = Handler(android.os.Looper.getMainLooper()),
        host = NoUiHost(),
        settingsPreferences = mockk<SettingsPreferences>(relaxed = true)
    )

    @Test
    fun `allows exactly the window budget then refuses`() {
        val reconnect = manager()

        repeat(PlayerReconnectManager.MAX_RECONNECTS_PER_WINDOW) { attempt ->
            assertTrue("attempt ${attempt + 1} should be allowed", reconnect.canAttemptReconnect())
        }
        assertFalse("budget must be exhausted", reconnect.canAttemptReconnect())
        assertFalse("and stay exhausted", reconnect.canAttemptReconnect())
    }

    @Test
    fun `a successful frame resets the budget`() {
        val reconnect = manager()
        repeat(PlayerReconnectManager.MAX_RECONNECTS_PER_WINDOW) { reconnect.canAttemptReconnect() }
        assertFalse(reconnect.canAttemptReconnect())

        reconnect.resetBudget()

        assertTrue("a fresh window starts after a good frame / user zap", reconnect.canAttemptReconnect())
    }

    @Test
    fun `network quality is ignored without a usable estimate`() {
        val prefs = mockk<SettingsPreferences>(relaxed = true)
        val reconnect = PlayerReconnectManager(
            handler = Handler(android.os.Looper.getMainLooper()),
            host = NoUiHost(),
            settingsPreferences = prefs
        )

        reconnect.maybeUpdateNetworkQuality(null)
        reconnect.maybeUpdateNetworkQuality(0L)
        reconnect.maybeUpdateNetworkQuality(-1L)

        // Nothing written: no usable estimate means no reclassification.
        verify(exactly = 0) { prefs.saveNetworkQuality(any()) }
    }

    @Test
    fun `budget size is the documented ceiling`() {
        assertEquals(4, PlayerReconnectManager.MAX_RECONNECTS_PER_WINDOW)
    }
}
