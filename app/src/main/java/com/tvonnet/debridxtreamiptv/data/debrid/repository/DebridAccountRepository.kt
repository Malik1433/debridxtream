package com.tvonnet.debridxtreamiptv.data.debrid.repository

import android.util.Log
import com.tvonnet.debridxtreamiptv.data.Result
import com.tvonnet.debridxtreamiptv.data.debrid.model.RealDebridAuthState
import com.tvonnet.debridxtreamiptv.data.debrid.model.RealDebridCredentialsResponse
import com.tvonnet.debridxtreamiptv.data.debrid.model.RealDebridDeviceCodeResponse
import com.tvonnet.debridxtreamiptv.data.debrid.model.RealDebridUserResponse
import com.tvonnet.debridxtreamiptv.data.debrid.source.RealDebridRemoteDataSource
import com.tvonnet.debridxtreamiptv.data.prefs.DebridPreferences
import javax.inject.Inject
import javax.inject.Singleton

import com.tvonnet.debridxtreamiptv.data.debrid.api.RealDebridAuthInterceptor
import kotlinx.coroutines.runBlocking

/**
 * Coordinates Real-Debrid authentication lifecycle, caching secure tokens in DebridPreferences.
 */
@Singleton
class DebridAccountRepository @Inject constructor(
    private val preferences: DebridPreferences,
    private val remote: RealDebridRemoteDataSource
) : RealDebridAuthInterceptor.TokenRefresher {

    override fun refreshTokenBlocking(): String? {
        return runBlocking {
            when (val result = refreshAuthState(force = true)) {
                is Result.Success -> result.data.accessToken
                else -> null
            }
        }
    }

    fun getCachedAuthState(): RealDebridAuthState? = preferences.getAuthState()

    fun clearAuthState() {
        preferences.clearAuthState()
    }

    suspend fun requestDeviceCode(clientId: String): Result<RealDebridDeviceCodeResponse> {
        return remote.requestDeviceCode(clientId)
    }

    suspend fun pollDeviceCredentials(
        clientId: String,
        deviceCode: String
    ): Result<RealDebridCredentialsResponse> {
        return remote.pollDeviceCredentials(clientId, deviceCode)
    }

    suspend fun exchangeDeviceCode(
        credentials: RealDebridCredentialsResponse,
        deviceCode: String
    ): Result<RealDebridAuthState> {
        Log.d("DebridRepo", "Exchanging device code for token, deviceCode: ${deviceCode.take(4)}...")
        val result = remote.exchangeDeviceCodeForToken(credentials, deviceCode)
        if (result is Result.Success) {
            Log.d("DebridRepo", "Token exchange successful, saving auth state")
            preferences.saveAuthState(result.data)
            Log.i("DebridRepo", "Auth state saved successfully")
        } else {
            Log.e("DebridRepo", "Token exchange failed", (result as? Result.Error)?.exception)
        }
        return result
    }

    suspend fun refreshAuthStateIfNeeded(
        bufferMillis: Long = DEFAULT_REFRESH_BUFFER_MILLIS
    ): Result<RealDebridAuthState> {
        return refreshAuthState(force = false, bufferMillis = bufferMillis)
    }

    suspend fun refreshAuthState(
        force: Boolean,
        bufferMillis: Long = DEFAULT_REFRESH_BUFFER_MILLIS
    ): Result<RealDebridAuthState> {
        val current = preferences.getAuthState()
        if (current == null) {
            Log.e("DebridRepo", "No auth state available for refresh")
            return Result.Error(IllegalStateException("No Real-Debrid auth state available"))
        }

        Log.d("DebridRepo", "Checking auth state expiration, expires at: ${current.expiresAt}")
        val shouldRefresh = force || current.isExpired() || current.willExpireWithin(bufferMillis)
        if (!shouldRefresh) {
            Log.d("DebridRepo", "Auth state still valid, no refresh needed")
            return Result.Success(current)
        }

        if (current.refreshToken.isBlank()) {
            Log.e("DebridRepo", "Missing refresh token, cannot refresh auth state")
            return Result.Error(IllegalStateException("Missing Real-Debrid refresh token"))
        }

        Log.i(
            "DebridRepo",
            if (force) "Forcing auth refresh" else "Auth state needs refresh"
        )
        val refreshed = remote.refreshAccessToken(current)
        if (refreshed is Result.Success) {
            Log.d("DebridRepo", "Token refresh successful, saving new auth state")
            preferences.saveAuthState(refreshed.data)
            Log.i("DebridRepo", "Auth state refreshed successfully, new expires at: ${refreshed.data.expiresAt}")
        } else {
            Log.e("DebridRepo", "Token refresh failed", (refreshed as? Result.Error)?.exception)
        }
        return refreshed
    }

    suspend fun fetchUserProfile(): Result<RealDebridUserResponse> {
        return remote.fetchUserInfo()
    }

    /**
     * Convenience methods for ViewModel usage
     */
    suspend fun getDeviceCode(): Result<DeviceCodeInfo> {
        Log.d("DebridRepo", "Getting device code for authentication")
        val clientId = preferences.getRealDebridClientId()
        Log.d("DebridRepo", "Using clientId: ${clientId.take(4)}...")

        return when (val result = requestDeviceCode(clientId)) {
            is Result.Success -> {
                Log.d("DebridRepo", "Device code request successful, creating DeviceCodeInfo")
                Result.Success(
                    DeviceCodeInfo(
                        deviceCode = result.data.deviceCode,
                        userCode = result.data.userCode, // Short code for user (e.g., "J45KBGK6")
                        verificationUrl = result.data.verificationUrl, // Always use simple URL
                        interval = result.data.interval,
                        expiresIn = result.data.expiresIn
                    )
                )
            }
            is Result.Error -> {
                Log.e("DebridRepo", "Failed to get device code", result.exception)
                Result.Error(result.exception)
            }
            else -> {
                Log.e("DebridRepo", "Unknown error occurred while getting device code")
                Result.Error(Exception("Unknown error"))
            }
        }
    }

    suspend fun getToken(deviceCode: String): Result<TokenInfo> {
        Log.d("DebridRepo", "Getting token for deviceCode: ${deviceCode.take(4)}...")
        val clientId = preferences.getRealDebridClientId()

        Log.d("DebridRepo", "Polling device credentials")
        val credentials = when (val result = pollDeviceCredentials(clientId, deviceCode)) {
            is Result.Success -> {
                Log.d("DebridRepo", "Device credentials received successfully")
                result.data
            }
            is Result.Error -> {
                Log.e("DebridRepo", "Failed to poll device credentials", result.exception)
                return Result.Error(result.exception)
            }
            else -> {
                Log.e("DebridRepo", "Unknown error occurred while polling device credentials")
                return Result.Error(Exception("Unknown error"))
            }
        }

        Log.d("DebridRepo", "Exchanging device code for token")
        return when (val authResult = exchangeDeviceCode(credentials, deviceCode)) {
            is Result.Success -> {
                Log.d("DebridRepo", "Token exchange successful, creating TokenInfo")
                Result.Success(
                    TokenInfo(
                        accessToken = authResult.data.accessToken,
                        refreshToken = authResult.data.refreshToken
                    )
                )
            }
            is Result.Error -> {
                Log.e("DebridRepo", "Token exchange failed", authResult.exception)
                Result.Error(authResult.exception)
            }
            else -> {
                Log.e("DebridRepo", "Unknown error occurred during token exchange")
                Result.Error(Exception("Unknown error"))
            }
        }
    }

    suspend fun loginWithStaticToken(token: String): Result<Unit> {
        Log.d("DebridRepo", "Logging in with static token")
        preferences.saveRealDebridToken(token)
        return Result.Success(Unit)
    }

    suspend fun getUser(): Result<RealDebridUserResponse> {
        return fetchUserProfile()
    }

    companion object {
        private const val DEFAULT_REFRESH_BUFFER_MILLIS = 5 * 60 * 1000L // 5 minutes
    }
}

/**
 * Simplified device code info
 */
data class DeviceCodeInfo(
    val deviceCode: String,
    val userCode: String,
    val verificationUrl: String,
    val interval: Int,
    val expiresIn: Int
)

/**
 * Simplified token info
 */
data class TokenInfo(
    val accessToken: String,
    val refreshToken: String
)
