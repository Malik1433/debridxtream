package com.tvonnet.debridxtreamiptv.network

import android.util.Log
import com.google.gson.Gson
import com.tvonnet.debridxtreamiptv.data.prefs.CredentialsPreferences
import com.tvonnet.debridxtreamiptv.data.prefs.DebridPreferences
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RemotePairingManager @Inject constructor(
    private val credentialsPreferences: CredentialsPreferences,
    private val debridPreferences: DebridPreferences,
    private val okHttpClient: OkHttpClient
) {
    private val gson = Gson()
    private val _pairingState = MutableStateFlow<PairingState>(PairingState.Idle)
    val pairingState: StateFlow<PairingState> = _pairingState.asStateFlow()

    private var pollingJob: Job? = null
    
    // Placeholder relay server - this should ideally be your own hosted relay
    // For demonstration, we'll use a conceptual endpoint.
    private val RELAY_URL = "https://relay.debridxtream.com/api"

    sealed class PairingState {
        object Idle : PairingState()
        data class Generated(val code: String) : PairingState()
        object Polling : PairingState()
        data class Success(val message: String) : PairingState()
        data class Error(val message: String) : PairingState()
    }

    /**
     * Generates a unique 6-digit alphanumeric pairing code based on the Device ID.
     * Use Math.abs() to ensure we don't get 000000 for negative hashes.
     */
    fun generatePairingCode(): String {
        val deviceId = credentialsPreferences.getDeviceId()
        // Use the absolute value of the hash code and take the last 6 digits for variability
        val hash = kotlin.math.abs(deviceId.hashCode())
        val code = hash.toString().padStart(6, '0').takeLast(6)
        _pairingState.value = PairingState.Generated(code)
        return code
    }

    /**
     * Starts polling the relay server for configuration associated with this device.
     */
    fun startPolling() {
        if (_pairingState.value is PairingState.Polling) return
        
        val deviceId = credentialsPreferences.getDeviceId()
        val code = (_pairingState.value as? PairingState.Generated)?.code ?: generatePairingCode()
        
        _pairingState.value = PairingState.Polling
        
        pollingJob?.cancel()
        pollingJob = CoroutineScope(Dispatchers.IO).launch {
            while (isActive) {
                try {
                    Log.d("RemotePairing", "Polling relay for code: $code")
                    
                    val request = Request.Builder()
                        .url("$RELAY_URL/pair/poll?code=$code")
                        .build()

                    val response = okHttpClient.newCall(request).execute()
                    
                    if (response.isSuccessful) {
                        val body = response.body?.string()
                        if (!body.isNullOrBlank()) {
                            // Check if we got a waiting status or actual config
                            if (body.contains("\"status\":\"waiting\"")) {
                                // Still waiting, do nothing
                            } else {
                                // Try to parse config
                                try {
                                    val payload = gson.fromJson(body, CompanionConfigPayload::class.java)
                                    withContext(Dispatchers.Main) {
                                        handleConfigPayload(payload)
                                        stopPolling() // Stop after success
                                    }
                                } catch (e: Exception) {
                                    Log.e("RemotePairing", "Failed to parse payload", e)
                                }
                            }
                        }
                    } else {
                        Log.w("RemotePairing", "Poll failed: ${response.code}")
                    }
                    
                    // Delay for 5 seconds between polls
                    delay(5000)
                    
                } catch (e: Exception) {
                    Log.e("RemotePairing", "Polling error", e)
                    // Don't stop on network error, just wait and retry
                    delay(10000) 
                }
            }
        }
    }

    fun stopPolling() {
        pollingJob?.cancel()
        _pairingState.value = PairingState.Idle
    }

    /**
     * Directly injects a payload into the app's preferences.
     * This is called when the polling (or a hypothetical push) receives data.
     */
    fun handleConfigPayload(payload: CompanionConfigPayload) {
        // reuse the logic from CompanionConfigServer
        payload.iptv?.let { iptv ->
            val safeServerUrl = CompanionUrlValidator.normalizeSafeHttpUrl(iptv.serverUrl)
            if (iptv.username.isNotBlank() && safeServerUrl != null) {
                credentialsPreferences.saveSyncedCredentials(
                    serverUrl = safeServerUrl,
                    username = iptv.username,
                    password = iptv.password
                )
            }
        }
        
        payload.debrid?.let { debrid ->
            // Real-Debrid was removed (K2); `token` is accepted and ignored for older clients.
            val mediaFusionUrl = debrid.mediaFusionUrl?.trim().orEmpty()
            val safeMediaFusionUrl = CompanionUrlValidator.normalizeSafeHttpUrl(mediaFusionUrl)
            if (safeMediaFusionUrl != null) {
                debridPreferences.saveMediaFusionUrl(safeMediaFusionUrl)
            }
            val stremioUrls = CompanionUrlValidator.normalizeSafeAddonUrls(debrid.stremioAddonUrls)
            if (stremioUrls.isNotEmpty()) {
                debridPreferences.setStremioAddonUrls(stremioUrls)
            }
            val registryUrls = CompanionUrlValidator.normalizeSafeAddonUrls(debrid.addonRegistryUrls)
            if (registryUrls.isNotEmpty()) {
                val existing = debridPreferences.getAddonRegistryUrls()
                existing.forEach { debridPreferences.removeAddonRegistryUrl(it) }
                registryUrls.forEach { debridPreferences.addAddonRegistryUrl(it) }
            }
        }
        
        _pairingState.value = PairingState.Success("Credentials synced remotely!")
    }
}
