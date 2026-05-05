package com.tvonnet.debridxtreamiptv.player.stabilized

import android.content.Context
import android.util.Log
import android.view.View
import androidx.core.view.isVisible
import androidx.media3.common.Player
import androidx.media3.ui.PlayerView
import com.tvonnet.debridxtreamiptv.R

/**
 * Manages the UI controls and overlays for the playback screen.
 * Extracts UI interaction logic from PlayerActivity to improve modularity.
 */
class PlayerControlsManager(
    private val context: Context,
    private val playerView: PlayerView,
    private var player: Player? = null,
    private val onSettingsClick: () -> Unit = {}
) {

    var isControllerVisible = false
        private set

    private var visibilityListener: ((Boolean) -> Unit)? = null

    init {
        setupVisibilityListener()
        setupClickListeners()
        setupInteractiveAnimations()
    }

    /**
     * Sets a listener for visibility changes.
     */
    fun setVisibilityListener(listener: (Boolean) -> Unit) {
        this.visibilityListener = listener
    }

    /**
     * Updates the player reference for control interactions.
     */
    fun setPlayer(newPlayer: Player?) {
        this.player = newPlayer
    }

    /**
     * Shows the playback controller.
     */
    fun showController() {
        Log.d("PlayerControls", "Controller shown")
        playerView.showController()
        isControllerVisible = true
    }

    /**
     * Hides the playback controller.
     */
    fun hideController() {
        Log.d("PlayerControls", "Controller hidden")
        playerView.hideController()
        isControllerVisible = false
    }

    /**
     * Updates the visibility of custom play/pause buttons if present.
     */
    fun updatePlayPauseVisibility(isPlaying: Boolean) {
        playerView.findViewById<View>(R.id.exo_play)?.isVisible = !isPlaying
        playerView.findViewById<View>(R.id.exo_pause)?.isVisible = isPlaying
        
        if (isControllerVisible) {
            val play = playerView.findViewById<View>(R.id.exo_play)
            val pause = playerView.findViewById<View>(R.id.exo_pause)
            if (isPlaying && play?.isFocused == true) {
                pause?.post { pause.requestFocus() }
            } else if (!isPlaying && pause?.isFocused == true) {
                play?.post { play.requestFocus() }
            }
        }
    }

    private fun setupVisibilityListener() {
        playerView.setControllerVisibilityListener(object : PlayerView.ControllerVisibilityListener {
            override fun onVisibilityChanged(visibility: Int) {
                isControllerVisible = visibility == View.VISIBLE
                
                if (isControllerVisible) {
                    handleControllerFocus()
                }
                
                visibilityListener?.invoke(isControllerVisible)
            }
        })
    }

    private fun handleControllerFocus() {
        com.tvonnet.debridxtreamiptv.utils.FocusCoordinator.requestFocus("PLAYER_CONTROLLER") {
            val playBtn = playerView.findViewById<View>(R.id.exo_play)
            val pauseBtn = playerView.findViewById<View>(R.id.exo_pause)
            
            val targetBtn = if (playBtn?.isVisible == true) playBtn 
                           else if (pauseBtn?.isVisible == true) pauseBtn
                           else playerView.findViewById<View>(R.id.btn_player_shuffle)
            
            targetBtn?.post { 
                targetBtn.post { 
                    try {
                        targetBtn.requestFocus()
                    } finally {
                        com.tvonnet.debridxtreamiptv.utils.FocusCoordinator.release("PLAYER_CONTROLLER")
                    }
                }
            } ?: com.tvonnet.debridxtreamiptv.utils.FocusCoordinator.release("PLAYER_CONTROLLER")
        }
    }

    private fun setupClickListeners() {
        playerView.findViewById<View>(R.id.exo_play)?.setOnClickListener {
            Log.d("PlayerControls", "Play clicked")
            player?.play()
            updatePlayPauseVisibility(true)
        }
        playerView.findViewById<View>(R.id.exo_pause)?.setOnClickListener {
            Log.d("PlayerControls", "Pause clicked")
            player?.pause()
            updatePlayPauseVisibility(false)
        }
        playerView.findViewById<View>(R.id.btn_player_shuffle)?.setOnClickListener {
            onSettingsClick()
        }
    }

    private fun setupInteractiveAnimations() {
        val animationTargets = listOf(
            R.id.btn_player_shuffle, 
            R.id.exo_rew, 
            R.id.exo_play, 
            R.id.exo_pause, 
            R.id.exo_ffwd, 
            R.id.btn_aspect_ratio, 
            R.id.btn_next_episode
        )

        animationTargets.forEach { id ->
            playerView.findViewById<View>(id)?.let { v ->
                v.alpha = 0.7f
                v.scaleX = 1f
                v.scaleY = 1f
                
                v.setOnFocusChangeListener { view, hasFocus ->
                    if (hasFocus) {
                        view.animate().scaleX(1.15f).scaleY(1.15f).alpha(1f).setDuration(200).start()
                        if (view.id == R.id.btn_player_shuffle) {
                            view.animate().rotationBy(90f).setDuration(400).start()
                        }
                    } else {
                        view.animate().scaleX(1f).scaleY(1f).alpha(0.7f).setDuration(200).start()
                    }
                }
                
                v.setOnTouchListener { view, event ->
                    when (event.action) {
                        android.view.MotionEvent.ACTION_DOWN -> {
                            view.animate().scaleX(0.95f).scaleY(0.95f).setDuration(100).start()
                        }
                        android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                            val targetScale = if (view.hasFocus()) 1.15f else 1f
                            view.animate().scaleX(targetScale).scaleY(targetScale).setDuration(100).start()
                        }
                    }
                    false
                }
            }
        }
    }
}
