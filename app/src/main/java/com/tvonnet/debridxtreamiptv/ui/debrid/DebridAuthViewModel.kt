package com.tvonnet.debridxtreamiptv.ui.debrid

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tvonnet.debridxtreamiptv.data.debrid.repository.DebridAccountRepository
import com.tvonnet.debridxtreamiptv.data.onSuccess
import com.tvonnet.debridxtreamiptv.data.onFailure
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for Real-Debrid device-code authentication
 */
@HiltViewModel
class DebridAuthViewModel @Inject constructor(
    private val debridAccountRepo: DebridAccountRepository
) : ViewModel() {
    
    private val _authState = MutableStateFlow<DebridAuthState>(DebridAuthState.Idle)
    val authState: StateFlow<DebridAuthState> = _authState.asStateFlow()
    
    private var pollingJob: Job? = null
    private var deviceCode: String? = null
    
    fun startAuth() {
        viewModelScope.launch {
            android.util.Log.d("DebridAuth", "Starting authentication...")
            _authState.value = DebridAuthState.FetchingCode
            
            // Get device code
            debridAccountRepo.getDeviceCode().onSuccess { response ->
                android.util.Log.d(
                    "DebridAuth",
                    "Device code received: userCode=${maskCode(response.userCode)}, deviceCode=${maskCode(response.deviceCode)}"
                )
                deviceCode = response.deviceCode
                _authState.value = DebridAuthState.ShowingCode(
                    deviceCode = response.userCode,
                    verificationUrl = response.verificationUrl
                )
                android.util.Log.d("DebridAuth", "State updated to ShowingCode")
                
                // Start polling for token
                startPolling(response.deviceCode, response.interval)
            }.onFailure { error ->
                android.util.Log.e("DebridAuth", "Failed to get device code: ${error.message}", error)
                _authState.value = DebridAuthState.Error(
                    error.message ?: "Failed to get device code"
                )
            }
        }
    }
    
    private fun startPolling(deviceCode: String, intervalSeconds: Int) {
        android.util.Log.d("DebridAuth", "Starting polling for deviceCode: ${deviceCode.take(4)}..., interval: ${intervalSeconds}s")
        pollingJob?.cancel()
        pollingJob = viewModelScope.launch {
            var attempts = 0
            val maxAttempts = 60 // 10 minutes at 10s intervals

            android.util.Log.i("DebridAuth", "Polling started, max attempts: $maxAttempts, interval: ${intervalSeconds}s")

            while (attempts < maxAttempts) {
                delay(intervalSeconds * 1000L)
                attempts++

                android.util.Log.d("DebridAuth", "Polling attempt $attempts/$maxAttempts for deviceCode: ${deviceCode.take(4)}...")

                debridAccountRepo.getToken(deviceCode).onSuccess { tokenResponse ->
                    android.util.Log.i("DebridAuth", "Authentication successful after $attempts attempts!")
                    android.util.Log.d("DebridAuth", "Auth state saved, updating state to Success")
                    _authState.value = DebridAuthState.Success
                    pollingJob?.cancel()
                    return@launch
                }.onFailure { error ->
                    android.util.Log.w("DebridAuth", "Polling attempt $attempts failed: ${error.message}")

                    // Continue polling unless it's a permanent error
                    if (error.message?.contains("denied", ignoreCase = true) == true) {
                        android.util.Log.e("DebridAuth", "Authorization denied by user")
                        _authState.value = DebridAuthState.Error("Authorization denied")
                        pollingJob?.cancel()
                        return@launch
                    } else if (error.message?.contains("expired", ignoreCase = true) == true) {
                        android.util.Log.e("DebridAuth", "Device code expired")
                        _authState.value = DebridAuthState.Error("Device code expired. Please try again.")
                        pollingJob?.cancel()
                        return@launch
                    }
                    // For other errors, continue polling
                }
            }

            // Timeout
            android.util.Log.e("DebridAuth", "Polling timeout after $maxAttempts attempts")
            _authState.value = DebridAuthState.Error("Authorization timeout. Please try again.")
        }
    }
    
    fun cancelAuth() {
        android.util.Log.d("DebridAuth", "Authentication cancelled by user")
        pollingJob?.cancel()
        deviceCode = null
        android.util.Log.d("DebridAuth", "Authentication cleanup completed")
    }
    
    override fun onCleared() {
        android.util.Log.d("DebridAuth", "ViewModel being cleared, cleaning up resources")
        super.onCleared()
        cancelAuth()
    }

    private fun maskCode(value: String?): String {
        val trimmed = value?.trim().orEmpty()
        if (trimmed.isEmpty()) return "n/a"
        return when {
            trimmed.length <= 2 -> "**"
            else -> trimmed.take(2) + "***"
        }
    }
}

/**
 * Auth UI states
 */
sealed class DebridAuthState {
    object Idle : DebridAuthState()
    object FetchingCode : DebridAuthState()
    data class ShowingCode(
        val deviceCode: String,
        val verificationUrl: String
    ) : DebridAuthState()
    object Success : DebridAuthState()
    data class Error(val message: String) : DebridAuthState()
}
