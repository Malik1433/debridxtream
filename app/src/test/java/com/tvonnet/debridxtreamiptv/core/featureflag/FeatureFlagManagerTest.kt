package com.tvonnet.debridxtreamiptv.core.featureflag

import android.content.Context
import android.content.SharedPreferences
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class FeatureFlagManagerTest {

    private lateinit var context: Context
    private lateinit var sharedPreferences: SharedPreferences
    private lateinit var editor: SharedPreferences.Editor

    @Before
    fun setUp() {
        context = mockk(relaxed = true)
        sharedPreferences = mockk(relaxed = true)
        editor = mockk(relaxed = true)

        every { context.getSharedPreferences(any(), any()) } returns sharedPreferences
        every { sharedPreferences.edit() } returns editor
        every { editor.putBoolean(any(), any()) } returns editor
        
        // Reset the singleton state (if possible via reflection or testing design) 
        // For this simple object, we will simulate init flow.
    }

    @Test
    fun `isSeriesV2Enabled returns false by default when not initialized`() {
        // Given not initialized
        // Note: In a real singleton, state persists. We assume fresh start or robust test isolation.
        
        // When accessing property
        val isEnabled = FeatureFlagManager.isSeriesV2Enabled

        // Then default is false (Safe Mode)
        assertFalse("Should be false by default", isEnabled)
    }

    @Test
    fun `init loads value from SharedPreferences`() {
        // Given prefs return true
        every { sharedPreferences.getBoolean("enable_series_v2_engine", false) } returns true

        // When initialized
        FeatureFlagManager.init(context)

        // Then memory cache is updated
        assertTrue("Should load true from prefs", FeatureFlagManager.isSeriesV2Enabled)
    }
    
    @Test
    fun `init loads false from SharedPreferences`() {
        // Given prefs return false
        every { sharedPreferences.getBoolean("enable_series_v2_engine", false) } returns false

        // When initialized
        FeatureFlagManager.init(context)

        // Then memory cache is updated
        assertFalse("Should load false from prefs", FeatureFlagManager.isSeriesV2Enabled)
    }

    @Test
    fun `setSeriesV2Enabled updates memory and persistence`() {
        // Given initialized
        FeatureFlagManager.init(context)

        // When setting to true
        FeatureFlagManager.setSeriesV2Enabled(true)

        // Then memory is true
        assertTrue(FeatureFlagManager.isSeriesV2Enabled)

        // And prefs are updated
        verify { editor.putBoolean("enable_series_v2_engine", true) }
        verify { editor.apply() }
    }
}
