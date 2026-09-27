package com.tvonnet.debridxtreamiptv.data.prefs

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * C1: the provider's time zone lives in that provider's sync_prefs file - the file ServerDataReset
 * clears on a server switch (XtreamRepository.clearCatalogFilesAndSyncStamps). If it moved anywhere
 * else, a switch could pair the old provider's zone with the new provider's streams.
 */
@RunWith(RobolectricTestRunner::class)
class ServerClockTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `a real zone is remembered, junk is ignored`() {
        ServerClock.remember(context, "Europe/Amsterdam")
        assertEquals("Europe/Amsterdam", ServerClock.zone(context)?.id)
        ServerClock.remember(context, "Not/AZone")
        assertEquals("Europe/Amsterdam", ServerClock.zone(context)?.id)
        assertNull(ServerClock.parse(""))
        assertNull(ServerClock.parse("Not/AZone"))
        assertEquals("GMT", ServerClock.parse("GMT")?.id)
    }

    @Test
    fun `clearing the provider's sync prefs forgets the zone`() {
        ServerClock.remember(context, "Europe/Amsterdam")
        ServerScopedPrefs.open(context, "sync_prefs", ServerScopedPrefs.activeFingerprint(context))
            .edit().clear().commit()
        assertNull(ServerClock.zone(context))
    }
}
