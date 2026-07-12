package com.tvonnet.debridxtreamiptv.player.stabilized

import android.app.Activity
import android.os.CountDownTimer
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.media3.ui.PlayerView
import com.tvonnet.debridxtreamiptv.R
import com.tvonnet.debridxtreamiptv.features.seriesv2.data.model.EpisodeEntityV2
import com.tvonnet.debridxtreamiptv.utils.FocusCoordinator

class PlayerNextEpisodeManager(
    private val activity: Activity,
    private val playerView: PlayerView,
    private val delegate: Delegate
) {
    companion object {
        /** VOD Player redesign: auto-play countdown window (design = 30s). */
        private const val COUNTDOWN_MS = 30000L
    }

    interface Delegate {
        fun isPlaybackEligibleForPrompt(): Boolean
        fun getPlayerDuration(): Long
        fun getPlayerCurrentPosition(): Long
        fun isPlayerPlaying(): Boolean
        fun getNextEpisode(): EpisodeEntityV2?
        fun onPlayNextEpisodeRequested()

        /** Fired once when the countdown prompt appears — the 15s window for pre-resolving. */
        fun onNextEpisodePromptShown(nextEpisode: EpisodeEntityV2) {}
    }

    private var nextEpisodeOverlay: View? = null
    private var tvNextEpTitle: TextView? = null
    private var tvCountdownSeconds: TextView? = null
    private var btnPlayNext: View? = null
    private var btnCancelNext: View? = null

    private var nextEpisodeTimer: CountDownTimer? = null
    private var isNextPromptVisible = false
    private var nextPromptShownForThisEpisode = false
    
    val isPromptVisible: Boolean get() = isNextPromptVisible

    private val nextCheckHandler = Handler(Looper.getMainLooper())
    private val nextCheckRunnable = object : Runnable {
        override fun run() {
            checkNextEpisodePrompt()
            nextCheckHandler.postDelayed(this, 1000)
        }
    }

    fun setupViews() {
        if (nextEpisodeOverlay != null) return
        val overlay = activity.findViewById<View>(R.id.view_next_episode_prompt) ?: return
        nextEpisodeOverlay = overlay
        tvNextEpTitle = overlay.findViewById(R.id.tv_next_episode_title)
        tvCountdownSeconds = overlay.findViewById(R.id.tv_countdown_seconds)
        btnPlayNext = overlay.findViewById(R.id.btn_play_now)
        btnCancelNext = overlay.findViewById(R.id.btn_cancel)

        btnPlayNext?.setOnClickListener {
            playNextEpisode()
        }
        btnCancelNext?.setOnClickListener {
            hideNextEpisodePrompt(cancelled = true)
        }
    }

    fun onStart() {
        nextCheckHandler.post(nextCheckRunnable)
    }

    fun onStop() {
        nextCheckHandler.removeCallbacks(nextCheckRunnable)
        nextEpisodeTimer?.cancel()
    }

    fun resetState() {
        nextPromptShownForThisEpisode = false
        hideNextEpisodePrompt(cancelled = false)
    }

    fun hidePrompt() {
        hideNextEpisodePrompt(cancelled = false)
    }

    private fun checkNextEpisodePrompt() {
        if (!delegate.isPlaybackEligibleForPrompt()) return
        if (!delegate.isPlayerPlaying()) return
        
        val duration = delegate.getPlayerDuration()
        val position = delegate.getPlayerCurrentPosition()
        if (duration <= 0L) return

        val remaining = duration - position
        val percentThresholdMs = (duration * 0.87).toLong()
        val timeThresholdMs = 30000L
        
        val showPrompt = position >= percentThresholdMs || remaining < timeThresholdMs
        
        if (showPrompt && !nextPromptShownForThisEpisode && !isNextPromptVisible) {
            val nextEp = delegate.getNextEpisode()
            if (nextEp != null) {
                showNextEpisodePrompt(nextEp)
            }
        }
    }

    private fun showNextEpisodePrompt(nextEp: EpisodeEntityV2) {
        if (isNextPromptVisible) return
        isNextPromptVisible = true
        nextPromptShownForThisEpisode = true 
        
        nextEpisodeOverlay?.isVisible = true
        tvNextEpTitle?.text = nextEp.title ?: "Episode ${nextEp.episodeNumber}"
        delegate.onNextEpisodePromptShown(nextEp)
        
        nextEpisodeTimer?.cancel()
        nextEpisodeTimer = object : CountDownTimer(COUNTDOWN_MS, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                val sec = (millisUntilFinished / 1000).toInt()
                tvCountdownSeconds?.text = sec.toString()
                val progress = ((COUNTDOWN_MS - millisUntilFinished) / (COUNTDOWN_MS / 100.0)).toInt()
                nextEpisodeOverlay?.findViewById<ProgressBar>(R.id.progress_countdown)?.progress = progress
            }
            override fun onFinish() {
                playNextEpisode()
            }
        }.start()
        
        playerView.hideController()
        playerView.isFocusable = false
        
        FocusCoordinator.requestFocus("PLAYER_NEXT_EP") {
            btnPlayNext?.post { 
                btnPlayNext?.post {
                    try {
                        btnPlayNext?.requestFocus()
                    } catch (e: Exception) {
                    }
                }
            }
        }
    }

    private fun hideNextEpisodePrompt(cancelled: Boolean) {
        isNextPromptVisible = false
        nextEpisodeOverlay?.isVisible = false
        nextEpisodeTimer?.cancel()
        
        playerView.isFocusable = true
        FocusCoordinator.release("PLAYER_NEXT_EP")
        
        if (cancelled) {
            playerView.showController()
            playerView.requestFocus()
        }
    }

    private fun playNextEpisode() {
        nextEpisodeTimer?.cancel()
        hideNextEpisodePrompt(cancelled = false)
        delegate.onPlayNextEpisodeRequested()
    }
}
