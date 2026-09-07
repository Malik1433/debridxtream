package com.tvonnet.debridxtreamiptv.data.prefs

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Diagnostics consent is tri-state, and the third state is the point.
 *
 * G3 shipped this as a boolean defaulted ON, which made it a SETTING rather than consent: nothing
 * distinguished "yes" from "never asked". These pin the rule that closes that — until the question
 * has been answered, collection is off no matter what the stored value says, on a fresh install and
 * on a device that updated into the build alike.
 */
@RunWith(RobolectricTestRunner::class)
class DiagnosticsConsentStateTest {

    private lateinit var context: Context
    private lateinit var prefs: SettingsPreferences

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("iptv_settings", Context.MODE_PRIVATE).edit().clear().commit()
        prefs = SettingsPreferences(context)
    }

    @Test
    fun `a fresh install has not been asked, so nothing is collected`() {
        assertFalse("unasked is not consent", prefs.hasAnsweredDiagnostics())
        assertFalse("and it must not collect", prefs.isDiagnosticsEnabled())
    }

    @Test
    fun `a device that already had the old default ON is still not consented`() {
        // Exactly what an updating device looks like: the G3 value present, the question never put.
        context.getSharedPreferences("iptv_settings", Context.MODE_PRIVATE)
            .edit().putBoolean("diagnostics_enabled", true).commit()

        assertFalse(prefs.hasAnsweredDiagnostics())
        assertFalse(
            "the existing base is asked too - a consent screen only new installs see is not consent",
            prefs.isDiagnosticsEnabled()
        )
    }

    @Test
    fun `saying yes turns it on and is remembered`() {
        prefs.saveDiagnosticsEnabled(true)
        assertTrue(prefs.hasAnsweredDiagnostics())
        assertTrue(prefs.isDiagnosticsEnabled())
    }

    @Test
    fun `saying no is an answer too - it must never be asked again`() {
        prefs.saveDiagnosticsEnabled(false)
        assertTrue("answered, so the prompt is done", prefs.hasAnsweredDiagnostics())
        assertFalse(prefs.isDiagnosticsEnabled())
    }

    @Test
    fun `the Settings row can still change the answer either way`() {
        prefs.saveDiagnosticsEnabled(true)
        assertTrue(prefs.isDiagnosticsEnabled())

        prefs.saveDiagnosticsEnabled(false)
        assertFalse(prefs.isDiagnosticsEnabled())
        assertTrue("turning it off does not un-ask the question", prefs.hasAnsweredDiagnostics())
    }
}
