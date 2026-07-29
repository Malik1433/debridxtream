package com.tvonnet.debridxtreamiptv.player.stabilized

import android.view.MotionEvent
import android.view.View
import android.widget.ImageButton
import androidx.core.view.isVisible
import androidx.media3.ui.PlayerView
import com.tvonnet.debridxtreamiptv.R

/**
 * Small transport-control chrome helpers lifted out of [PlayerActivity] (Phase P11).
 *
 * Kept separate from [PlayerLoaderUi]: the loader is the pre-playback overlay, these
 * three touch the media3 controller itself. Each takes the views it needs, so none of
 * them reads Activity state.
 */

/**
 * Swaps the play/pause buttons. Focus is moved to the incoming button BEFORE the
 * outgoing one is hidden — hiding a focused view lets the framework hand focus to
 * whatever it likes, which is the old focus-hijacking bug.
 */
internal fun updatePlayPauseVisibility(
    playerView: PlayerView,
    isPlaying: Boolean,
    isControllerVisible: Boolean
) {
    val play = playerView.findViewById<View>(R.id.exo_play)
    val pause = playerView.findViewById<View>(R.id.exo_pause)

    if (isControllerVisible) {
        if (isPlaying && play?.isFocused == true) {
            pause?.isVisible = true
            pause?.requestFocus()
        } else if (!isPlaying && pause?.isFocused == true) {
            play?.isVisible = true
            play?.requestFocus()
        }
    }

    play?.isVisible = !isPlaying
    pause?.isVisible = isPlaying
}

/** Focus/press scale + fade feedback on every transport control. */
internal fun setupInteractiveAnimations(playerView: PlayerView) {
    listOf(
        R.id.exo_rew,
        R.id.exo_play,
        R.id.exo_pause,
        R.id.exo_ffwd,
        R.id.btn_aspect_ratio,
        R.id.btn_next_episode,
        R.id.btn_player_audio,
        R.id.btn_player_language,
        R.id.btn_player_subtitles,
        R.id.btn_player_volume,
        R.id.volume_progress
    ).forEach { id ->
        playerView.findViewById<View>(id)?.let { v ->
            v.alpha = 0.7f; v.scaleX = 1f; v.scaleY = 1f
            v.setOnFocusChangeListener { view, f ->
                if (f) {
                    view.animate().scaleX(1.15f).scaleY(1.15f).alpha(1f).setDuration(200).start()
                } else {
                    view.animate().scaleX(1f).scaleY(1f).alpha(0.7f).setDuration(200).start()
                }
            }
            v.setOnTouchListener { view, e ->
                when (e.action) {
                    MotionEvent.ACTION_DOWN ->
                        view.animate().scaleX(0.95f).scaleY(0.95f).setDuration(100).start()
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                        view.animate()
                            .scaleX(if (view.hasFocus()) 1.15f else 1f)
                            .scaleY(if (view.hasFocus()) 1.15f else 1f)
                            .setDuration(100).start()
                }
                false
            }
        }
    }
}

internal fun updateVolumeIcon(progress: Int, maxVolume: Int, btnVolume: ImageButton?) {
    if (btnVolume == null) return
    if (progress == 0) {
        btnVolume.setImageResource(R.drawable.ic_player_volume_off)
    } else {
        btnVolume.setImageResource(R.drawable.ic_player_volume)
    }
}
