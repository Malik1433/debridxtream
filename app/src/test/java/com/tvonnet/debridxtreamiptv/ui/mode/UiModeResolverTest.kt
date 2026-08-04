package com.tvonnet.debridxtreamiptv.ui.mode

import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.content.res.Resources
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M0 (MOBILE_DUAL_MODE_PLAN): the precedence that decides which UI a device gets.
 * Getting this wrong would hand a Fire TV the phone layout — hence a test per rung of
 * the ladder rather than a single happy path.
 */
class UiModeResolverTest {

    private fun context(
        modeType: Int = Configuration.UI_MODE_TYPE_NORMAL,
        leanback: Boolean = false,
        touchscreen: Boolean = true,
    ): Context {
        val uiModeManager = mockk<UiModeManager>()
        every { uiModeManager.currentModeType } returns modeType

        val pm = mockk<PackageManager>()
        every { pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK) } returns leanback
        every { pm.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN) } returns touchscreen

        val configuration = Configuration().apply { uiMode = modeType }
        val resources = mockk<Resources>()
        every { resources.configuration } returns configuration

        val context = mockk<Context>()
        every { context.getSystemService(Context.UI_MODE_SERVICE) } returns uiModeManager
        every { context.packageManager } returns pm
        every { context.resources } returns resources
        return context
    }

    private val fireTv = context(Configuration.UI_MODE_TYPE_TELEVISION, leanback = true, touchscreen = false)
    private val phone = context(Configuration.UI_MODE_TYPE_NORMAL, leanback = false, touchscreen = true)
    private val quietBox = context(Configuration.UI_MODE_TYPE_NORMAL, leanback = true, touchscreen = false)

    // ── detection ──

    @Test
    fun `a television reports itself and gets the TV ui`() {
        assertEquals(AppUiMode.TV, UiModeResolver.detect(fireTv))
    }

    @Test
    fun `a phone gets the mobile ui`() {
        assertEquals(AppUiMode.MOBILE, UiModeResolver.detect(phone))
    }

    @Test
    fun `a box that only declares leanback and no touchscreen is a TV`() {
        assertEquals(AppUiMode.TV, UiModeResolver.detect(quietBox))
    }

    // ── override precedence ──

    @Test
    fun `an explicit override beats detection in both directions`() {
        assertEquals(AppUiMode.MOBILE, UiModeResolver.resolve(fireTv, UiModeResolver.OVERRIDE_MOBILE))
        assertEquals(AppUiMode.TV, UiModeResolver.resolve(phone, UiModeResolver.OVERRIDE_TV))
    }

    @Test
    fun `auto, blank and junk overrides all fall through to detection`() {
        listOf(UiModeResolver.OVERRIDE_AUTO, null, "", "   ", "nonsense").forEach { value ->
            assertEquals("override=$value", AppUiMode.TV, UiModeResolver.resolve(fireTv, value))
            assertEquals("override=$value", AppUiMode.MOBILE, UiModeResolver.resolve(phone, value))
        }
    }

    @Test
    fun `override matching is case-insensitive and trims`() {
        assertEquals(AppUiMode.MOBILE, UiModeResolver.resolve(fireTv, " Mobile "))
        assertEquals(AppUiMode.TV, UiModeResolver.resolve(phone, "TV"))
    }

    // ── the one-time chooser ──

    @Test
    fun `obvious devices are never ambiguous`() {
        assertFalse(UiModeResolver.isAmbiguous(fireTv))
        assertFalse(UiModeResolver.isAmbiguous(phone))
        assertFalse(UiModeResolver.isAmbiguous(quietBox))
    }

    @Test
    fun `contradictory or silent devices are ambiguous and worth asking about`() {
        val touchTv = context(Configuration.UI_MODE_TYPE_NORMAL, leanback = true, touchscreen = true)
        val unknownBox = context(Configuration.UI_MODE_TYPE_NORMAL, leanback = false, touchscreen = false)
        assertTrue(UiModeResolver.isAmbiguous(touchTv))
        assertTrue(UiModeResolver.isAmbiguous(unknownBox))
    }
}
