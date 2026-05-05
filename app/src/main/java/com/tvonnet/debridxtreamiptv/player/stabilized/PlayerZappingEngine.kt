package com.tvonnet.debridxtreamiptv.player.stabilized

import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer

/**
 * Interface for zapping (channel switching) events.
 */
interface ZappingCallback {
    fun onSwitchStart(url: String)
    fun onSwitchComplete()
    fun onSwitchError(message: String)
    fun buildMediaItem(url: String): MediaItem
    fun resolveTimeoutMs(url: String): Long
    fun onStateTransition(state: PlaybackState, reason: String)
    fun canExecuteSwitch(): Boolean
    
    /**
     * Request a channel move from the data source (ViewModel).
     * Should return the new channel details and update UI metadata.
     */
    fun onZapRequest(direction: Int): ZapChannel?
}

/**
 * PlayerZappingEngine handles the logic for switching channels or media sources.
 * It manages timeouts, state transitions, and debounce protection.
 */
class PlayerZappingEngine(
    private val callback: ZappingCallback,
    private val mainHandler: Handler = Handler(Looper.getMainLooper())
) {
    private var player: ExoPlayer? = null
    private var isSwitchingInternal = false
    private var timeoutMs: Long = 25000L
    private var lastZapTime = 0L
    private val ZAP_DEBOUNCE_MS = 400L
    
    var isExiting = false
    
    private val timeoutRunnable = Runnable {
        handleTimeout()
    }

    /**
     * Updates the player reference.
     */
    fun setPlayer(player: ExoPlayer?) {
        this.player = player
    }

    /**
     * Performs a zap (next/previous channel).
     * Includes debounce protection.
     */
    fun zap(direction: Int) {
        val now = System.currentTimeMillis()
        if (now - lastZapTime < ZAP_DEBOUNCE_MS) {
            Log.v("Zapping", "Zap ignored due to debounce")
            return
        }
        lastZapTime = now

        val target = callback.onZapRequest(direction) ?: return
        switchTo(target.streamUrl)
    }

    /**
     * Performs a seamless switch to a new URL.
     */
    fun switchTo(newUrl: String, startPositionMs: Long = 0L) {
        if (player == null) {
            Log.w("ZAPPING", "Blocked: player null")
            return
        }

        if (isExiting) {
            Log.w("ZAPPING", "Blocked: activity exiting")
            return
        }

        if (!callback.canExecuteSwitch()) {
            Log.w("Zapping", "Switch blocked by coordinator")
            return
        }

        val p = player!!
        Log.d("ZAPPING", "player is null = false")
        
        Log.d("ZAPPING", "switchTo called url=$newUrl")
        Log.d("Zapping", "Switch started to: $newUrl")
        isSwitchingInternal = true
        callback.onSwitchStart(newUrl)

        // Cancel previous timeout
        mainHandler.removeCallbacks(timeoutRunnable)
        
        // Resolve new timeout
        timeoutMs = callback.resolveTimeoutMs(newUrl)
        mainHandler.postDelayed(timeoutRunnable, timeoutMs)

        // Atomic Switch Block
        p.stop()
        p.clearMediaItems()
        
        val mediaItem = callback.buildMediaItem(newUrl)
        p.setMediaItem(mediaItem)
        p.prepare()
        
        if (startPositionMs > 0L) {
            p.seekTo(startPositionMs)
        }
        
        p.playWhenReady = true
        
        callback.onStateTransition(PlaybackState.SWITCHING, "UI_SWITCH")
        Log.i("Zapping", "Seamless switch command sequence complete")
    }

    /**
     * Checks if a switch is currently in progress.
     */
    fun isSwitching(): Boolean = isSwitchingInternal

    /**
     * Notifies the engine that playback has successfully started.
     */
    fun notifyPlaybackStarted() {
        if (isSwitchingInternal) {
            Log.d("Zapping", "Switch completed")
            isSwitchingInternal = false
            mainHandler.removeCallbacks(timeoutRunnable)
            callback.onSwitchComplete()
        }
    }

    /**
     * Handles zapping timeout.
     */
    private fun handleTimeout() {
        if (isSwitchingInternal) {
            Log.w("Zapping", "Switch timeout reached")
            isSwitchingInternal = false
            callback.onSwitchError("Connection timeout")
        }
    }

    /**
     * Releases resources.
     */
    fun release() {
        Log.d("ZAPPING", "release called")
        mainHandler.removeCallbacks(timeoutRunnable)
        player = null
    }
}
