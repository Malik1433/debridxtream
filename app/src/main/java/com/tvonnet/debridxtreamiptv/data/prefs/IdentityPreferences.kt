package com.tvonnet.debridxtreamiptv.data.prefs

import android.content.Context
import android.content.SharedPreferences
import android.provider.Settings
import java.security.MessageDigest
import java.util.UUID

class IdentityPreferences(private val context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun migrateFromLegacy(deviceId: String?, syncCode: String?) {
        prefs.edit().apply {
            if (!deviceId.isNullOrBlank() && !prefs.contains(KEY_INSTALL_INSTANCE_ID)) {
                putString(KEY_INSTALL_INSTANCE_ID, deviceId)
            }
            if (!syncCode.isNullOrBlank() && !prefs.contains(KEY_LEGACY_SYNC_CODE)) {
                putString(KEY_LEGACY_SYNC_CODE, syncCode)
            }
            apply()
        }
        getOrCreateInstallInstanceId()
        ensureDeviceSignalHash()
    }

    fun getOrCreateInstallInstanceId(): String {
        val existing = prefs.getString(KEY_INSTALL_INSTANCE_ID, null)
        if (!existing.isNullOrBlank()) return existing

        val newId = UUID.randomUUID().toString()
        prefs.edit().putString(KEY_INSTALL_INSTANCE_ID, newId).apply()
        ensureDeviceSignalHash()
        return newId
    }

    fun getLegacySyncCode(): String? {
        return prefs.getString(KEY_LEGACY_SYNC_CODE, null)
    }

    fun saveLegacySyncCode(code: String) {
        prefs.edit().putString(KEY_LEGACY_SYNC_CODE, code).apply()
        ensureDeviceSignalHash()
    }

    fun getDeviceSignalHash(): String? {
        ensureDeviceSignalHash()
        return prefs.getString(KEY_DEVICE_SIGNAL_HASH, null)
    }

    fun getDeviceSignalHashVersion(): Int {
        ensureDeviceSignalHash()
        return prefs.getInt(KEY_DEVICE_SIGNAL_HASH_VERSION, 0)
    }

    private fun ensureDeviceSignalHash() {
        val existingVersion = prefs.getInt(KEY_DEVICE_SIGNAL_HASH_VERSION, 0)
        val existingHash = prefs.getString(KEY_DEVICE_SIGNAL_HASH, null)
        if (existingVersion == DEVICE_SIGNAL_HASH_VERSION && !existingHash.isNullOrBlank()) {
            return
        }

        val androidId = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ANDROID_ID
        )

        if (androidId.isNullOrBlank()) {
            return
        }

        prefs.edit().apply {
            putString(KEY_DEVICE_SIGNAL_HASH, sha256("$DEVICE_SIGNAL_SALT:$androidId"))
            putInt(KEY_DEVICE_SIGNAL_HASH_VERSION, DEVICE_SIGNAL_HASH_VERSION)
            apply()
        }
    }

    private fun sha256(value: String): String {
        val bytes = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val PREFS_NAME = "identity_prefs"
        const val KEY_INSTALL_INSTANCE_ID = "install_instance_id"
        const val KEY_LEGACY_SYNC_CODE = "legacy_sync_code"
        const val KEY_DEVICE_SIGNAL_HASH = "device_signal_hash"
        const val KEY_DEVICE_SIGNAL_HASH_VERSION = "device_signal_hash_version"

        private const val DEVICE_SIGNAL_HASH_VERSION = 1
        private const val DEVICE_SIGNAL_SALT = "debridxtream-device-signal-v1"
    }
}
