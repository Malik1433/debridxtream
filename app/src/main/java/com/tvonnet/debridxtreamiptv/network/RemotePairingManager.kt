package com.tvonnet.debridxtreamiptv.network

import android.util.Log
import com.google.gson.Gson
import com.tvonnet.debridxtreamiptv.data.prefs.CredentialsPreferences
import com.tvonnet.debridxtreamiptv.data.prefs.DebridPreferences
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
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
    
    // Firebase Realtime Database URL (REST API)
    // NOTE: User should ideally replace this with their own Firebase URL
    private val FIREBASE_BASE_URL = "https://debridxtream-default-rtdb.firebaseio.com"

    sealed class PairingState {
        object Idle : PairingState()
        data class Generated(val code: String) : PairingState()
        object Polling : PairingState()
        data class Success(val message: String) : PairingState()
        data class Error(val message: String) : PairingState()
    }

    /**
     * Generates a pairing code and REGISTERS it in Firebase.
     */
    fun generateAndRegisterCode(): String {
        val deviceId = credentialsPreferences.getDeviceId()
        val hash = kotlin.math.abs(deviceId.hashCode())
        val code = hash.toString().padStart(6, '0').takeLast(6)
        
        _pairingState.value = PairingState.Generated(code)
        
        // Register in Firebase so Web Dashboard can validate it
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val registrationPayload = mapOf(
                    "status" to "pairing",
                    "deviceId" to deviceId,
                    "timestamp" to System.currentTimeMillis()
                )
                
                val body = gson.toJson(registrationPayload).toRequestBody("application/json".toMediaType())
                val request = Request.Builder()
                    .url("$FIREBASE_BASE_URL/pairings/$code.json")
                    .put(body)
                    .build()

                val response = okHttpClient.newCall(request).execute()
                if (response.isSuccessful) {
                    Log.d("RemotePairing", "Pairing code $code registered in Firebase")
                } else {
                    Log.e("RemotePairing", "Firebase registration failed: ${response.code}")
                }
            } catch (e: Exception) {
                Log.e("RemotePairing", "Error registering code", e)
            }
        }
        
        return code
    }

    /**
     * Backward compatibility or default method name used in fragments
     */
    fun generatePairingCode(): String = generateAndRegisterCode()

    /**
     * Starts listening (via polling) for a "synced" status in Firebase.
     */
    fun startPolling() {
        // If already polling, don't start another
        if (_pairingState.value is PairingState.Polling) return
        
        val code = (_pairingState.value as? PairingState.Generated)?.code ?: generateAndRegisterCode()
        
        _pairingState.value = PairingState.Polling
        
        pollingJob?.cancel()
        pollingJob = CoroutineScope(Dispatchers.IO).launch {
            while (isActive) {
                try {
                    Log.d("RemotePairing", "Checking Firebase for sync: $code")
                    
                    val request = Request.Builder()
                        .url("$FIREBASE_BASE_URL/pairings/$code.json")
                        .get()
                        .build()

                    val response = okHttpClient.newCall(request).execute()
                    
                    if (response.isSuccessful) {
                        val body = response.body?.string()
                        if (!body.isNullOrBlank() && body != "null") {
                            val data = gson.fromJson(body, Map::class.java)
                            
                            // Check if status transitioned to "synced"
                            if (data["status"] == "synced") {
                                val configStr = gson.toJson(data["config"])
                                val payload = gson.fromJson(configStr, CompanionConfigPayload::class.java)
                                
                                withContext(Dispatchers.Main) {
                                    handleConfigPayload(payload)
                                    // Cleanup Firebase node after successful sync
                                    deletePairingNode(code)
                                    stopPolling()
                                }
                                break
                            }
                        }
                    }
                    
                    // Frequent poll for "real-time" feel (2 seconds)
                    delay(2000)
                    
                } catch (e: Exception) {
                    Log.e("RemotePairing", "Firebase poll error", e)
                    delay(5000) 
                }
            }
        }
    }

    private fun deletePairingNode(code: String) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val request = Request.Builder()
                    .url("$FIREBASE_BASE_URL/pairings/$code.json")
                    .delete()
                    .build()
                okHttpClient.newCall(request).execute()
            } catch (e: Exception) {
                Log.e("RemotePairing", "Failed to delete pairing node", e)
            }
        }
    }

    fun stopPolling() {
        pollingJob?.cancel()
        _pairingState.value = PairingState.Idle
    }

    fun handleConfigPayload(payload: CompanionConfigPayload) {
        payload.iptv?.let { iptv ->
            if (iptv.serverUrl.isNotBlank() && iptv.username.isNotBlank()) {
                credentialsPreferences.saveCredentials(
                    serverUrl = iptv.serverUrl,
                    username = iptv.username,
                    password = iptv.password
                )
            }
        }
        
        payload.debrid?.let { debrid ->
            if (!debrid.token.isNullOrBlank()) {
                debridPreferences.saveRealDebridToken(debrid.token)
            }
            if (!debrid.mediaFusionUrl.isNullOrBlank()) {
                debridPreferences.saveMediaFusionUrl(debrid.mediaFusionUrl)
            }
        }
        
        _pairingState.value = PairingState.Success("Credentials synced remotely!")
    }
}

