package com.tvonnet.debridxtreamiptv.data.prefs

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TorBoxPreferences @Inject constructor(
    @ApplicationContext context: Context
) {
    private val sharedPreferences by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    fun getToken(): String? {
        return sharedPreferences.getString(KEY_TOKEN, null)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    }

    fun saveToken(token: String) {
        val normalized = token.trim()
        if (normalized.isEmpty()) {
            clearToken()
            return
        }
        sharedPreferences.edit().putString(KEY_TOKEN, normalized).apply()
    }

    fun clearToken() {
        sharedPreferences.edit().remove(KEY_TOKEN).apply()
    }

    private companion object {
        const val PREFS_NAME = "torbox_secure_prefs"
        const val KEY_TOKEN = "torbox_api_token"
    }
}
