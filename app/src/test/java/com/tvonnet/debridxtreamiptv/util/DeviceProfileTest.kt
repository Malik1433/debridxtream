package com.tvonnet.debridxtreamiptv.util

import android.app.ActivityManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Roadmap B3 — the low-RAM device snapshot that image sizing hangs off.
 *
 * This app ships to 1GB Fire TV sticks where a poster decoded at desktop size is a real OOM source,
 * so every image request asks [DeviceProfile] how big to decode. Two properties matter and neither
 * was covered: the low-RAM verdict is a logical OR of three independent signals (the OS flag, the
 * memory class, the total RAM) so a device only has to trip one of them, and the snapshot is cached
 * because it is read on every bind.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class DeviceProfileTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        resetCachedSnapshot()
    }

    @Test
    fun `the OS low-ram flag alone marks the device as low ram`() {
        configureDevice(lowRamFlag = true, memoryClassMb = 256, totalRamGb = 4.0)

        assertTrue(DeviceProfile.isLowRamDevice(context))
    }

    @Test
    fun `the snapshot is computed once and reused`() {
        val first = DeviceProfile.get(context)
        val second = DeviceProfile.get(context)

        assertSame("this is read on every image bind — recomputing it each time would be the bug", first, second)
    }

    @Test
    fun `a small memory class alone marks the device as low ram`() {
        configureDevice(lowRamFlag = false, memoryClassMb = 128, totalRamGb = 4.0)

        assertTrue(
            "a 128MB heap cap is a low-RAM stick whatever the OS flag says",
            DeviceProfile.isLowRamDevice(context)
        )
    }

    @Test
    fun `little total RAM alone marks the device as low ram`() {
        configureDevice(lowRamFlag = false, memoryClassMb = 256, totalRamGb = 1.0)

        assertTrue(DeviceProfile.isLowRamDevice(context))
    }

    @Test
    fun `a roomy device is not low ram`() {
        configureDevice(lowRamFlag = false, memoryClassMb = 256, totalRamGb = 4.0)

        val snapshot = DeviceProfile.get(context)

        assertFalse(snapshot.isLowRamDevice)
        assertEquals(256, snapshot.memoryClassMb)
        assertTrue("total RAM is reported, not guessed", snapshot.totalRamGb > 3.9)
    }

    @Test
    fun `a low-ram device decodes smaller images everywhere`() {
        configureDevice(lowRamFlag = true, memoryClassMb = 256, totalRamGb = 4.0)

        assertEquals(220, DeviceProfile.channelLogoSize(context))
        assertEquals(320 to 480, DeviceProfile.posterSize(context))
        assertEquals(240 to 360, DeviceProfile.posterRowSize(context))
        assertEquals(120, DeviceProfile.thumbnailSize(context))
    }

    /** Every size must be strictly smaller on the constrained device — no accidental equal pairs. */
    @Test
    fun `the low-ram sizes are actually smaller than the normal ones`() {
        configureDevice(lowRamFlag = true, memoryClassMb = 256, totalRamGb = 4.0)
        val lowLogo = DeviceProfile.channelLogoSize(context)
        val lowPoster = DeviceProfile.posterSize(context)
        val lowThumb = DeviceProfile.thumbnailSize(context)

        configureDevice(lowRamFlag = false, memoryClassMb = 256, totalRamGb = 4.0)
        val normalLogo = DeviceProfile.channelLogoSize(context)
        val normalPoster = DeviceProfile.posterSize(context)
        val normalThumb = DeviceProfile.thumbnailSize(context)

        assertTrue("$lowLogo !< $normalLogo", lowLogo < normalLogo)
        assertTrue(lowPoster.first < normalPoster.first && lowPoster.second < normalPoster.second)
        assertTrue(lowThumb < normalThumb)
    }

    private fun activityManager(): ActivityManager =
        context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager

    /**
     * Robolectric's defaults are a 0-byte `totalMem` and a tiny memory class, which trips the
     * low-RAM verdict for *every* test — so each case states the device it means.
     */
    private fun configureDevice(lowRamFlag: Boolean, memoryClassMb: Int, totalRamGb: Double) {
        resetCachedSnapshot()
        val shadow = shadowOf(activityManager())
        shadow.setIsLowRamDevice(lowRamFlag)
        shadow.setMemoryClass(memoryClassMb)
        shadow.setMemoryInfo(
            ActivityManager.MemoryInfo().apply {
                totalMem = (totalRamGb * 1024 * 1024 * 1024).toLong()
                availMem = totalMem / 2
                lowMemory = false
            }
        )
    }

    /** The snapshot is deliberately cached for the process; tests need a clean one each time. */
    private fun resetCachedSnapshot() {
        DeviceProfile::class.java.getDeclaredField("cachedSnapshot").apply {
            isAccessible = true
            set(DeviceProfile, null)
        }
    }
}
