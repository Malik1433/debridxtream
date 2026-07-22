package com.tvonnet.debridxtreamiptv.player.stabilized

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Freezes the exit-result contract extracted in P12. These extras are what the
 * Live guide and the debrid source picker read on the way back, so a renamed or
 * dropped key is a silent broken hand-off.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PlayerExitResultsTest {

    @Test
    fun `live return intent carries the full channel identity`() {
        val intent = buildLiveReturnIntent(
            LiveReturnChannel(
                channelId = "1234",
                channelName = "|WC| BEIN SPORTS 2 HD FR",
                channelLogoUrl = "http://host/logo.png",
                epgChannelId = "bein2.fr",
                streamUrl = "http://host/live/1234.ts",
                categoryId = "42"
            )
        )

        assertEquals("1234", intent.getStringExtra(PlayerActivity.EXTRA_LIVE_RETURN_CHANNEL_ID))
        assertEquals("|WC| BEIN SPORTS 2 HD FR", intent.getStringExtra(PlayerActivity.EXTRA_LIVE_RETURN_CHANNEL_NAME))
        assertEquals("http://host/logo.png", intent.getStringExtra(PlayerActivity.EXTRA_LIVE_RETURN_CHANNEL_LOGO))
        assertEquals("bein2.fr", intent.getStringExtra(PlayerActivity.EXTRA_LIVE_RETURN_EPG_CHANNEL_ID))
        assertEquals("http://host/live/1234.ts", intent.getStringExtra(PlayerActivity.EXTRA_LIVE_RETURN_STREAM_URL))
        assertEquals("42", intent.getStringExtra(PlayerActivity.EXTRA_LIVE_RETURN_CATEGORY_ID))
    }

    @Test
    fun `live return intent tolerates the optional fields being absent`() {
        val intent = buildLiveReturnIntent(
            LiveReturnChannel(
                channelId = "1234",
                channelName = null,
                channelLogoUrl = null,
                epgChannelId = null,
                streamUrl = null,
                categoryId = null
            )
        )

        assertEquals("1234", intent.getStringExtra(PlayerActivity.EXTRA_LIVE_RETURN_CHANNEL_ID))
        assertNull(intent.getStringExtra(PlayerActivity.EXTRA_LIVE_RETURN_CHANNEL_NAME))
        assertNull(intent.getStringExtra(PlayerActivity.EXTRA_LIVE_RETURN_CATEGORY_ID))
    }

    @Test
    fun `plain return-to-sources carries no failure context`() {
        val intent = buildReturnToSourcesIntent()

        assertTrue(intent.getBooleanExtra(PlayerActivity.EXTRA_RETURN_TO_SOURCES, false))
        assertNull(intent.getStringExtra(PlayerActivity.EXTRA_FAILED_STREAM_ID))
        assertNull(intent.getStringExtra(PlayerActivity.EXTRA_FAIL_REASON))
        assertFalse(intent.getBooleanExtra(PlayerActivity.EXTRA_AUTO_PLAY_NEXT, false))
    }

    @Test
    fun `failed-source return carries the id the picker must skip`() {
        val intent = buildFailedSourceReturnIntent(
            failedStreamId = "aabbccdd_3",
            reason = "http_403",
            autoPlayNext = true
        )

        assertTrue(intent.getBooleanExtra(PlayerActivity.EXTRA_RETURN_TO_SOURCES, false))
        assertEquals("aabbccdd_3", intent.getStringExtra(PlayerActivity.EXTRA_FAILED_STREAM_ID))
        assertEquals("http_403", intent.getStringExtra(PlayerActivity.EXTRA_FAIL_REASON))
        assertTrue(intent.getBooleanExtra(PlayerActivity.EXTRA_AUTO_PLAY_NEXT, false))
    }
}
