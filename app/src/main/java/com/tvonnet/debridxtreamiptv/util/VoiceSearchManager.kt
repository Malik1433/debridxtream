package com.tvonnet.debridxtreamiptv.util

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.*

/**
 * Phase 3.2: Advanced TV Features - Voice Search Manager
 *
 * Provides voice search functionality using Google Speech Recognition API.
 * Handles permission requests, recognition lifecycle, and error states.
 * Designed specifically for Android TV remote control with microphone button.
 */
class VoiceSearchManager private constructor(private val context: Context) {

    companion object {
        private const val TAG = "VoiceSearchManager"
        private const val REQUEST_RECORD_AUDIO_PERMISSION = 100

        @Volatile
        private var INSTANCE: VoiceSearchManager? = null

        fun getInstance(context: Context): VoiceSearchManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: VoiceSearchManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    private var speechRecognizer: SpeechRecognizer? = null
    private var speechRecognizerIntent: Intent? = null
    private var isListening = false

    private val _voiceSearchState = MutableStateFlow<VoiceSearchState>(VoiceSearchState.Idle)
    val voiceSearchState: StateFlow<VoiceSearchState> = _voiceSearchState

    init {
        initializeSpeechRecognizer()
    }

    private fun initializeSpeechRecognizer() {
        if (!hasSpeechRecognitionCapability()) {
            Log.w(TAG, "Device does not support speech recognition")
            _voiceSearchState.value = VoiceSearchState.Error("Speech recognition not supported on this device")
            return
        }

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "RECORD_AUDIO permission not granted")
            _voiceSearchState.value = VoiceSearchState.Error("Microphone permission required for voice search")
            return
        }

        try {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)
            speechRecognizerIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true) // Better for TV
                putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            }

            speechRecognizer?.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    Log.d(TAG, "Ready for speech")
                    _voiceSearchState.value = VoiceSearchState.Ready
                }

                override fun onBeginningOfSpeech() {
                    Log.d(TAG, "Beginning of speech detected")
                    _voiceSearchState.value = VoiceSearchState.Listening(0f)
                }

                override fun onRmsChanged(rmsdB: Float) {
                    // Volume feedback - could be used for UI visualization
                    _voiceSearchState.value = VoiceSearchState.Listening(rmsdB)
                }

                override fun onBufferReceived(buffer: ByteArray?) {
                    // Audio buffer received
                }

                override fun onEndOfSpeech() {
                    Log.d(TAG, "End of speech detected")
                    isListening = false
                }

                override fun onError(error: Int) {
                    isListening = false
                    val errorMessage = getErrorMessage(error)
                    Log.e(TAG, "Speech recognition error: $error - $errorMessage")
                    _voiceSearchState.value = VoiceSearchState.Error(errorMessage)
                }

                override fun onResults(results: Bundle?) {
                    isListening = false
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    if (!matches.isNullOrEmpty()) {
                        Log.d(TAG, "Speech recognition results: ${matches.joinToString()}")
                        _voiceSearchState.value = VoiceSearchState.Success(matches)
                    } else {
                        Log.w(TAG, "No speech recognition results")
                        _voiceSearchState.value = VoiceSearchState.Error("No speech detected. Please try again.")
                    }
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    if (!matches.isNullOrEmpty()) {
                        val partialText = matches.first()
                        _voiceSearchState.value = VoiceSearchState.Partial(partialText)
                    }
                }

                override fun onEvent(eventType: Int, params: Bundle?) {
                    // Additional events
                }
            })

            Log.d(TAG, "Speech recognizer initialized successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize speech recognizer", e)
            _voiceSearchState.value = VoiceSearchState.Error("Failed to initialize voice search: ${e.message}")
        }
    }

    fun startVoiceSearch(): Boolean {
        if (!hasSpeechRecognitionCapability()) {
            showToast("Voice search not supported on this device")
            return false
        }

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            showToast("Microphone permission required for voice search")
            return false
        }

        if (isListening) {
            Log.w(TAG, "Already listening for speech")
            return true
        }

        val recognizer = speechRecognizer ?: return false
        val intent = speechRecognizerIntent ?: return false

        try {
            isListening = true
            recognizer.startListening(intent)
            Log.d(TAG, "Started listening for speech")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start speech recognition", e)
            isListening = false
            _voiceSearchState.value = VoiceSearchState.Error("Failed to start voice search")
            return false
        }
    }

    fun stopVoiceSearch() {
        if (isListening) {
            speechRecognizer?.stopListening()
            isListening = false
            Log.d(TAG, "Stopped listening for speech")
            _voiceSearchState.value = VoiceSearchState.Idle
        }
    }

    fun destroy() {
        stopVoiceSearch()
        speechRecognizer?.destroy()
        speechRecognizer = null
        speechRecognizerIntent = null
        Log.d(TAG, "Voice search manager destroyed")
    }

    private fun hasSpeechRecognitionCapability(): Boolean {
        return try {
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_MICROPHONE) &&
            SpeechRecognizer.isRecognitionAvailable(context)
        } catch (e: Exception) {
            Log.e(TAG, "Error checking speech recognition capability", e)
            false
        }
    }

    private fun getErrorMessage(errorCode: Int): String {
        return when (errorCode) {
            SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
            SpeechRecognizer.ERROR_CLIENT -> "Client side error"
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission required"
            SpeechRecognizer.ERROR_NETWORK -> "Network error"
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout"
            SpeechRecognizer.ERROR_NO_MATCH -> "No speech detected"
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Voice search is busy"
            SpeechRecognizer.ERROR_SERVER -> "Server error"
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech detected within timeout"
            else -> "Unknown voice search error"
        }
    }

    private fun showToast(message: String) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }

    /**
     * Check if microphone permission is granted and request if needed
     */
    fun checkAndRequestPermission(activity: Activity): Boolean {
        return if (ContextCompat.checkSelfPermission(activity, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED) {
            true
        } else {
            ActivityCompat.requestPermissions(
                activity,
                arrayOf(Manifest.permission.RECORD_AUDIO),
                REQUEST_RECORD_AUDIO_PERMISSION
            )
            false
        }
    }

    /**
     * Handle permission request result
     */
    fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ): Boolean {
        if (requestCode == REQUEST_RECORD_AUDIO_PERMISSION) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                // Permission granted, reinitialize
                initializeSpeechRecognizer()
                return true
            } else {
                _voiceSearchState.value = VoiceSearchState.Error("Microphone permission denied")
                return false
            }
        }
        return false
    }
}

/**
 * Voice search state sealed class representing different states of voice recognition
 */
sealed class VoiceSearchState {
    object Idle : VoiceSearchState()
    object Ready : VoiceSearchState()
    data class Listening(val volume: Float = 0f) : VoiceSearchState()
    data class Partial(val text: String) : VoiceSearchState()
    data class Success(val results: List<String>) : VoiceSearchState()
    data class Error(val message: String) : VoiceSearchState()
}