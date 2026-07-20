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

        /**
         * The active player, used to schedule an EXACT next-episode trigger at the 87%/-30s point via
         * [androidx.media3.exoplayer.ExoPlayer.createMessage] instead of a 1Hz poll. Returning null
         * falls back to the legacy per-second poll.
         */
        fun getPlayer(): androidx.media3.exoplayer.ExoPlayer? = null

        /** Series poster/backdrop, shown in the prompt when the next episode has no thumbnail. */
        fun getSeriesFallbackPosterUrl(): String? = null
    }

    private var nextEpisodeOverlay: View? = null
    private var ivNextEpThumb: android.widget.ImageView? = null
    private var tvNextEpTitle: TextView? = null
    private var tvCountdownSeconds: TextView? = null
    private var btnPlayNext: View? = null
    private var btnCancelNext: View? = null

    private var nextEpisodeTimer: CountDownTimer? = null
    private var isNextPromptVisible = false
    private var nextPromptShownForThisEpisode = false
    
    val isPromptVisible: Boolean get() = isNextPromptVisible

    // Legacy fallback poll — used ONLY when the delegate can't hand us the player (getPlayer()==null).
    private val nextCheckHandler = Handler(Looper.getMainLooper())
    private val nextCheckRunnable = object : Runnable {
        override fun run() {
            checkNextEpisodePrompt()
            nextCheckHandler.postDelayed(this, 1000)
        }
    }

    // Exact-trigger path: an ExoPlayer message scheduled at the 87%/-30s position replaces the poll.
    private var listenerPlayer: androidx.media3.exoplayer.ExoPlayer? = null
    private var scheduledTrigger: androidx.media3.exoplayer.PlayerMessage? = null
    private val playerListener = object : androidx.media3.common.Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                androidx.media3.common.Player.STATE_READY -> scheduleNextEpisodeTrigger()
                androidx.media3.common.Player.STATE_ENDED -> {
                    // Missed-trigger safety net (e.g. a seek consumed the scheduled message): the
                    // scheduled 87%/-30s message is the PRIMARY path — this only covers the rare miss.
                    if (delegate.isPlaybackEligibleForPrompt() &&
                        !nextPromptShownForThisEpisode && !isNextPromptVisible
                    ) {
                        delegate.getNextEpisode()?.let { showNextEpisodePrompt(it) }
                    }
                }
            }
        }
        override fun onMediaItemTransition(
            mediaItem: androidx.media3.common.MediaItem?,
            reason: Int
        ) {
            scheduleNextEpisodeTrigger()
        }
    }

    fun setupViews() {
        if (nextEpisodeOverlay != null) return
        val overlay = activity.findViewById<View>(R.id.view_next_episode_prompt) ?: return
        nextEpisodeOverlay = overlay
        ivNextEpThumb = overlay.findViewById(R.id.iv_next_episode_thumb)
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
        if (delegate.getPlayer() != null) {
            rebindPlayer()
        } else {
            // No player handle — keep the legacy 1Hz poll so the prompt still works.
            nextCheckHandler.post(nextCheckRunnable)
        }
    }

    /**
     * Bind (or re-bind) the trigger listener to the CURRENT player. Must be called every time the
     * player is (re)built — [PlayerActivity.initializePlayer] creates a fresh ExoPlayer per episode
     * and per retry, and the scheduled message lives on a specific player instance.
     */
    fun rebindPlayer() {
        val p = delegate.getPlayer() ?: return
        if (p === listenerPlayer) {
            scheduleNextEpisodeTrigger()
            return
        }
        cancelScheduledTrigger()
        listenerPlayer?.removeListener(playerListener)
        listenerPlayer = p
        p.addListener(playerListener)
        scheduleNextEpisodeTrigger() // in case the player is already READY
    }

    fun onStop() {
        nextCheckHandler.removeCallbacks(nextCheckRunnable)
        cancelScheduledTrigger()
        listenerPlayer?.removeListener(playerListener)
        listenerPlayer = null
        nextEpisodeTimer?.cancel()
    }

    /**
     * Schedule a single ExoPlayer message to fire at the earlier of 87%-position or 30s-before-end —
     * exactly the OR condition the old poll used — so the 30s auto-play countdown and the debrid
     * pre-resolve lead are preserved, without any per-second polling.
     */
    private fun scheduleNextEpisodeTrigger() {
        val p = listenerPlayer ?: return
        cancelScheduledTrigger()
        if (!delegate.isPlaybackEligibleForPrompt()) return
        if (nextPromptShownForThisEpisode || isNextPromptVisible) return
        val duration = p.duration
        if (duration <= 0L || duration == androidx.media3.common.C.TIME_UNSET) return // live / unknown

        val percentPos = (duration * 0.87).toLong()
        val remainingPos = duration - COUNTDOWN_MS
        val triggerPos = maxOf(0L, minOf(percentPos, remainingPos)).coerceAtMost(duration - 1)

        // Already past the trigger (e.g. resumed near the end) — prompt now instead of scheduling.
        if (p.currentPosition >= triggerPos) {
            checkNextEpisodePrompt()
            return
        }

        scheduledTrigger = p.createMessage { _, _ -> checkNextEpisodePrompt() }
            .setLooper(Looper.getMainLooper())
            .setPosition(triggerPos)
            .setDeleteAfterDelivery(true)
        scheduledTrigger?.send()
    }

    private fun cancelScheduledTrigger() {
        try {
            scheduledTrigger?.cancel()
        } catch (_: Exception) {
        }
        scheduledTrigger = null
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
        ivNextEpThumb?.let { iv ->
            val imageUrl = nextEp.thumbnail
                ?.takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }
                ?: delegate.getSeriesFallbackPosterUrl()
                    ?.takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }
            if (imageUrl == null) {
                com.bumptech.glide.Glide.with(iv).clear(iv)
                iv.setImageResource(R.drawable.placeholder_banner)
            } else {
                com.bumptech.glide.Glide.with(iv)
                    .load(imageUrl)
                    .placeholder(R.drawable.placeholder_banner)
                    .error(R.drawable.placeholder_banner)
                    .transition(com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions.withCrossFade())
                    .into(iv)
            }
        }
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
