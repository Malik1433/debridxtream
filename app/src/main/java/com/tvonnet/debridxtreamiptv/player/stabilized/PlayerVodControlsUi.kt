package com.tvonnet.debridxtreamiptv.player.stabilized

import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.View
import androidx.core.view.isVisible
import com.tvonnet.debridxtreamiptv.R
import com.tvonnet.debridxtreamiptv.data.model.ContentType

/**
 * VOD transport-controller focus + the custom seek-bar overlay (extra Stage-1
 * extraction, fragment-split prep).
 *
 * Moved verbatim out of [PlayerActivity]: the 2 Hz seek-overlay refresh loop
 * ([setupSeekOverlay] / [updateSeekOverlay]), the D-pad focus chain across the
 * transport row ([wireControlFocus] — series / movie / episode variants), the
 * DPAD-DOWN seek-nav handler, and [showControllerWithSmartFocus] (shows the
 * controller and lands focus on the seek bar or play/pause). The seek loop is tied
 * to the screen lifecycle, so this exposes [onStart]/[onStop] the Activity forwards.
 * Bodies are byte-identical.
 */
internal class PlayerVodControlsUi(
    private val activity: PlayerActivity,
    private val session: PlayerSessionState,
) {

    private var seekOverlay: VodSeekOverlay? = null
    private val seekOverlayHandler = Handler(Looper.getMainLooper())
    private val seekOverlayRunnable = object : Runnable {
        override fun run() {
            updateSeekOverlay()
            seekOverlayHandler.postDelayed(this, 500)
        }
    }

    private val player get() = activity.player
    private val playerView get() = activity.playerView
    private var backHideArmed: Boolean by session::backHideArmed
    private val contentType: ContentType? by session::contentType

    /** Lifecycle: restart the seek-refresh loop when the screen resumes. */
    fun onStart() {
        if (seekOverlay != null) { seekOverlayHandler.removeCallbacks(seekOverlayRunnable); seekOverlayHandler.post(seekOverlayRunnable) }
    }

    /** Lifecycle: stop the seek-refresh loop when the screen stops. */
    fun onStop() = seekOverlayHandler.removeCallbacks(seekOverlayRunnable)

    private fun updateSeekOverlay() {
        val p = player ?: return
        val dur = p.duration
        if (dur > 0L) {
            seekOverlay?.setProgress(p.currentPosition.toFloat() / dur)
            seekOverlay?.setBuffered(p.bufferedPosition.toFloat() / dur)
        } else {
            seekOverlay?.setProgress(0f)
            seekOverlay?.setBuffered(0f)
        }
    }

    fun setupSeekOverlay() {
        seekOverlay = playerView.findViewById(R.id.vod_seek_overlay)
        // Install the DPAD-DOWN → play/pause seek-nav handler up front so the seek bar never
        // dead-ends onto the GONE exo_play even before the first showControllerWithSmartFocus().
        installControllerSeekNavigation()
        val timeBar = playerView.findViewById<androidx.media3.ui.DefaultTimeBar>(R.id.exo_progress)
        timeBar?.addListener(object : androidx.media3.ui.TimeBar.OnScrubListener {
            override fun onScrubStart(timeBar: androidx.media3.ui.TimeBar, position: Long) {
                seekOverlay?.setFocusedVisual(true)
            }
            override fun onScrubMove(timeBar: androidx.media3.ui.TimeBar, position: Long) {
                val dur = player?.duration ?: 0L
                if (dur > 0L) seekOverlay?.setProgress(position.toFloat() / dur)
            }
            override fun onScrubStop(timeBar: androidx.media3.ui.TimeBar, position: Long, canceled: Boolean) {
                seekOverlay?.setFocusedVisual(false)
                updateSeekOverlay()
            }
        })
        timeBar?.setOnFocusChangeListener { _, focused -> seekOverlay?.setFocusedVisual(focused) }
        seekOverlayHandler.removeCallbacks(seekOverlayRunnable)
        seekOverlayHandler.post(seekOverlayRunnable)
    }

    private fun installControllerSeekNavigation() {
        val seekBar = playerView.findViewById<View>(R.id.exo_progress) ?: return
        seekBar.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN || keyCode != KeyEvent.KEYCODE_DPAD_DOWN) {
                return@setOnKeyListener false
            }
            focusVisiblePlayPause()
            true
        }
    }

    fun wireControlFocus(isSeries: Boolean) {
        fun link(id: Int, leftId: Int, rightId: Int) {
            playerView.findViewById<View>(id)?.apply {
                nextFocusLeftId = leftId
                nextFocusRightId = rightId
            }
        }
        if (isSeries) {
            link(R.id.exo_rew, R.id.exo_rew, R.id.btn_prev_episode)
            link(R.id.btn_prev_episode, R.id.exo_rew, R.id.exo_play)
            link(R.id.exo_play, R.id.btn_prev_episode, R.id.btn_next_episode)
            link(R.id.exo_pause, R.id.btn_prev_episode, R.id.btn_next_episode)
            link(R.id.btn_next_episode, R.id.exo_play, R.id.exo_ffwd)
            link(R.id.exo_ffwd, R.id.btn_next_episode, R.id.btn_episodes)
            link(R.id.btn_episodes, R.id.exo_ffwd, R.id.btn_player_audio)
            link(R.id.btn_player_audio, R.id.btn_episodes, R.id.btn_player_subtitles)
        } else if (contentType == ContentType.MOVIE) {
            // Movies: the Sources button sits between fast-forward and the audio button,
            // so the focus chain must route through it (it was being skipped).
            link(R.id.exo_rew, R.id.exo_rew, R.id.exo_play)
            link(R.id.exo_play, R.id.exo_rew, R.id.exo_ffwd)
            link(R.id.exo_pause, R.id.exo_rew, R.id.exo_ffwd)
            link(R.id.exo_ffwd, R.id.exo_play, R.id.btn_player_sources)
            link(R.id.btn_player_sources, R.id.exo_ffwd, R.id.btn_player_audio)
            link(R.id.btn_player_audio, R.id.btn_player_sources, R.id.btn_player_subtitles)
        } else {
            link(R.id.exo_rew, R.id.exo_rew, R.id.exo_play)
            link(R.id.exo_play, R.id.exo_rew, R.id.exo_ffwd)
            link(R.id.exo_pause, R.id.exo_rew, R.id.exo_ffwd)
            link(R.id.exo_ffwd, R.id.exo_play, R.id.btn_player_audio)
            link(R.id.btn_player_audio, R.id.exo_ffwd, R.id.btn_player_subtitles)
        }
        link(R.id.btn_player_subtitles, R.id.btn_player_audio, R.id.btn_aspect_ratio)
        link(R.id.btn_aspect_ratio, R.id.btn_player_subtitles, R.id.btn_aspect_ratio)
    }

    fun showControllerWithSmartFocus(sourceEvent: KeyEvent? = null) {
        // Explicit user show re-arms the two-step BACK (1st hide, 2nd exit).
        backHideArmed = false
        playerView.showController()
        installControllerSeekNavigation()
        val keyCode = sourceEvent?.keyCode
        val wantsToSeek = keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
        Log.d("PLAYER_SEEK_FOCUS", "show controller focus key=$keyCode wantsToSeek=$wantsToSeek")

        playerView.postDelayed({
            if (wantsToSeek) {
                val seekBar = playerView.findViewById<View>(R.id.exo_progress)
                seekBar?.requestFocus()
                if (sourceEvent?.action == KeyEvent.ACTION_DOWN) {
                    seekBar?.dispatchKeyEvent(sourceEvent)
                    seekBar?.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode ?: KeyEvent.KEYCODE_UNKNOWN))
                }
            } else {
                focusVisiblePlayPause()
            }
        }, 50L)
    }

    private fun focusVisiblePlayPause() {
        val target = playerView.findViewById<View>(R.id.exo_pause)?.takeIf { it.isVisible }
            ?: playerView.findViewById<View>(R.id.exo_play)?.takeIf { it.isVisible }
            ?: playerView.findViewById<View>(R.id.exo_rew)
        target?.requestFocus()
        Log.d("PLAYER_SEEK_FOCUS", "focus transport target=${target?.resources?.getResourceEntryName(target.id)}")
    }
}
