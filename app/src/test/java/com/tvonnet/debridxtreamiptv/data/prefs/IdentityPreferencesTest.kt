package com.tvonnet.debridxtreamiptv.data.prefs

import android.content.Context
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class IdentityPreferencesTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences(CredentialsPreferences.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        context.getSharedPreferences(IdentityPreferences.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        Settings.Secure.putString(context.contentResolver, Settings.Secure.ANDROID_ID, "raw-android-id")
    }

    @Test
    fun migratesLegacyDeviceIdAndSyncCodeWithoutDeletingOldKeys() {
        val credentialPrefs = context.getSharedPreferences(CredentialsPreferences.PREFS_NAME, Context.MODE_PRIVATE)
        credentialPrefs.edit()
            .putString(CredentialsPreferences.KEY_DEVICE_ID, "legacy-device-id")
            .putString(CredentialsPreferences.KEY_SYNC_CODE, "123456")
            .apply()

        CredentialsPreferences(context)

        val identityPrefs = context.getSharedPreferences(IdentityPreferences.PREFS_NAME, Context.MODE_PRIVATE)
        assertEquals("legacy-device-id", identityPrefs.getString(IdentityPreferences.KEY_INSTALL_INSTANCE_ID, null))
        assertEquals("123456", identityPrefs.getString(IdentityPreferences.KEY_LEGACY_SYNC_CODE, null))
        assertEquals("legacy-device-id", credentialPrefs.getString(CredentialsPreferences.KEY_DEVICE_ID, null))
        assertEquals("123456", credentialPrefs.getString(CredentialsPreferences.KEY_SYNC_CODE, null))
    }

    @Test
    fun saveSyncCodeKeepsVisibleLegacyCodeAndIdentityMirrorInSync() {
        val prefs = CredentialsPreferences(context)

        prefs.saveSyncCode("654321")

        val identityPrefs = context.getSharedPreferences(IdentityPreferences.PREFS_NAME, Context.MODE_PRIVATE)
        assertEquals("654321", prefs.getSyncCode())
        assertEquals("654321", identityPrefs.getString(IdentityPreferences.KEY_LEGACY_SYNC_CODE, null))
    }

    @Test
    fun createsInstallInstanceIdWhenNoLegacyDeviceIdExists() {
        CredentialsPreferences(context)

        val identityPrefs = context.getSharedPreferences(IdentityPreferences.PREFS_NAME, Context.MODE_PRIVATE)
        val installInstanceId = identityPrefs.getString(IdentityPreferences.KEY_INSTALL_INSTANCE_ID, null)
        assertFalse(installInstanceId.isNullOrBlank())
    }

    @Test
    fun clearCredentialsDoesNotRemoveDeviceIdentityValues() {
        val prefs = CredentialsPreferences(context)
        val deviceId = prefs.getDeviceId()
        prefs.saveSyncCode("111222")
        prefs.saveCredentials("http://example.com", "user", "pass")

        prefs.clearCredentials()

        assertEquals(deviceId, prefs.getDeviceId())
        assertEquals("111222", prefs.getSyncCode())
        assertFalse(prefs.isLoggedIn())
    }

    @Test
    fun storesHashedDeviceSignalOnly() {
        val identityPrefs = IdentityPreferences(context)

        val hash = identityPrefs.getDeviceSignalHash()

        assertEquals(1, identityPrefs.getDeviceSignalHashVersion())
        assertNotEquals("raw-android-id", hash)
        assertEquals(64, hash?.length)
    }
}
