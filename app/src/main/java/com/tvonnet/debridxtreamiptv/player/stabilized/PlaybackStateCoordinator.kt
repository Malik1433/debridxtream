package com.tvonnet.debridxtreamiptv.player.stabilized

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * PHASE 3: SHADOW MODE COORDINATOR
 * This class observes the current state of PlayerActivity without controlling it.
 * Used for diagnostic mapping and race condition detection.
 */
enum class PlaybackState {
    IDLE,
    RESOLVING_SOURCE,
    PREPARING_PLAYER,
    PLAYING,
    SWITCHING,
    STALLED,
    ERROR
}

enum class ActionType {
    SWITCH_CHANNEL,
    PLAY_SOURCE,
    RESOLVE_DEBRID,
    STALL_RECOVER,
    ACTION_STALL_RECOVERY,
    ACTION_RESOLUTION_COMPLETE,
    ACTION_NEW_INTENT,
    ACTION_PLAYER_OPERATION
}

class PlaybackStateCoordinator {
    private val _currentState = MutableStateFlow(PlaybackState.IDLE)
    val currentState: StateFlow<PlaybackState> = _currentState.asStateFlow()

    private var lastTransitionTime = 0L
    private var lastSource: String = "NONE"
    
    private var pendingIntent: android.content.Intent? = null

    /**
     * QUEUE SYSTEM
     */
    fun queueIntent(intent: android.content.Intent?) {
        this.pendingIntent = intent
    }

    fun resolveNextIntent(): android.content.Intent? {
        val intent = pendingIntent
        pendingIntent = null
        return intent
    }

    /**
     * CONTROL DECISION ENGINE
     * Determines if an action is safe based on the current state machine.
     */
    fun canExecute(action: ActionType): Boolean {
        val current = _currentState.value
        return when (action) {
            ActionType.SWITCH_CHANNEL, ActionType.ACTION_NEW_INTENT -> {
                // Unsafe if already switching, preparing, or resolving.
                current != PlaybackState.SWITCHING && 
                current != PlaybackState.PREPARING_PLAYER && 
                current != PlaybackState.RESOLVING_SOURCE
            }
            ActionType.PLAY_SOURCE, ActionType.ACTION_PLAYER_OPERATION -> {
                current == PlaybackState.IDLE || current == PlaybackState.ERROR || current == PlaybackState.PLAYING
            }
            ActionType.RESOLVE_DEBRID -> {
                current != PlaybackState.RESOLVING_SOURCE
            }
            ActionType.STALL_RECOVER, ActionType.ACTION_STALL_RECOVERY -> {
                current == PlaybackState.STALLED || current == PlaybackState.PLAYING
            }
            ActionType.ACTION_RESOLUTION_COMPLETE -> {
                // Resolution finished, ready to switch. Safe if was resolving.
                current == PlaybackState.RESOLVING_SOURCE
            }
        }
    }

    /**
     * SHADOW TRANSITION
     * Logs and tracks state changes from external triggers.
     */
    fun transitionTo(newState: PlaybackState, source: String) {
        val oldState = _currentState.value
        val currentTime = System.currentTimeMillis()
        
        // Conflict Detection logic
        if (currentTime - lastTransitionTime < 500 && lastSource != source && oldState != newState) {
            Log.w("STATE_COORDINATOR", "[RACE CONDITION DETECTED] Conflict between $lastSource and $source")
            Log.w("STATE_COORDINATOR", "Current State: $oldState | New State: $newState | Time Delta: ${currentTime - lastTransitionTime}ms")
        }

        if (oldState != newState) {
            _currentState.value = newState
            Log.i("STATE_COORDINATOR", "OldState $oldState -> NewState $newState | Source: $source | TS: $currentTime")
        }

        lastTransitionTime = currentTime
        lastSource = source
    }

    /**
     * SYNC VALIDATION
     * Compares external boolean flags with shadow state.
     */
    fun validateStateSync(isSwitching: Boolean, isResolving: Boolean, exoPlayerState: Int?): String {
        val current = _currentState.value
        val sb = StringBuilder()
        
        if (isSwitching && current != PlaybackState.SWITCHING) {
            sb.append("[MISMATCH] isSwitching=true but Coordinator=$current\n")
        }
        if (isResolving && current != PlaybackState.RESOLVING_SOURCE) {
            sb.append("[MISMATCH] isResolving=true but Coordinator=$current\n")
        }
        
        return if (sb.isEmpty()) "SYNC_OK" else sb.toString()
    }
}
