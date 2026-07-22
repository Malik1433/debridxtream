package com.tvonnet.debridxtreamiptv.player.stabilized

import android.media.AudioManager
import android.view.View
import android.widget.ImageButton
import android.widget.SeekBar
import androidx.core.view.isVisible
import androidx.media3.ui.PlayerView
import com.tvonnet.debridxtreamiptv.R

/**
 * Wires the VOD transport controls to the screen's actions (Phase P19b).
 *
 * Pure wiring: which button does what, which ones are visible for series vs movies,
 * and the volume slider/mute pair. Every action itself stays in [PlayerActivity].
 */
internal interface TransportActions {
    fun onAudioSelection()
    fun onSubtitleSelection()
    fun onAspectRatio()
    fun onPlay()
    fun onPause()
    fun onRewind()
    fun onForward()
    fun onNextEpisode()
    fun onPreviousEpisode()
    fun onEpisodes()
    fun onSources()
    fun isControllerVisible(): Boolean
}

internal class PlayerTransportButtons(
    private val playerView: PlayerView,
    private val actions: TransportActions
) {

    fun bind(isSeriesControls: Boolean, isMovie: Boolean) {
        playerView.findViewById<View>(R.id.btn_player_audio)?.setOnClickListener { actions.onAudioSelection() }
        playerView.findViewById<View>(R.id.btn_player_language)?.setOnClickListener { actions.onAudioSelection() }
        playerView.findViewById<View>(R.id.btn_player_subtitles)?.setOnClickListener { actions.onSubtitleSelection() }
        playerView.findViewById<View>(R.id.btn_aspect_ratio)?.setOnClickListener { actions.onAspectRatio() }

        playerView.findViewById<View>(R.id.exo_play)?.setOnClickListener {
            actions.onPlay()
            updatePlayPauseVisibility(playerView, true, actions.isControllerVisible())
        }
        playerView.findViewById<View>(R.id.exo_pause)?.setOnClickListener {
            actions.onPause()
            updatePlayPauseVisibility(playerView, false, actions.isControllerVisible())
        }
        playerView.findViewById<View>(R.id.exo_rew)?.setOnClickListener { actions.onRewind() }
        playerView.findViewById<View>(R.id.exo_ffwd)?.setOnClickListener { actions.onForward() }

        // Series-only controls: NEXT EP / PREV EP / EPISODES.
        val btnNext = playerView.findViewById<View>(R.id.btn_next_episode)
        val btnPrev = playerView.findViewById<View>(R.id.btn_prev_episode)
        val btnEpisodes = playerView.findViewById<View>(R.id.btn_episodes)
        btnNext?.isVisible = isSeriesControls
        btnPrev?.isVisible = isSeriesControls
        btnEpisodes?.isVisible = isSeriesControls
        if (isSeriesControls) {
            btnNext?.setOnClickListener { actions.onNextEpisode() }
            btnPrev?.setOnClickListener { actions.onPreviousEpisode() }
            btnEpisodes?.setOnClickListener {
                playerView.hideController()
                actions.onEpisodes()
            }
        }

        // Movies: an in-player "Sources" button to switch source/language mid-playback
        // (e.g. Hindi -> German) without going back to the detail screen.
        val btnSources = playerView.findViewById<View>(R.id.btn_player_sources)
        btnSources?.isVisible = isMovie
        if (isMovie) {
            btnSources?.setOnClickListener {
                playerView.hideController()
                actions.onSources()
            }
        }
    }

    /** Volume slider + mute toggle, both driven by the system STREAM_MUSIC level. */
    fun bindVolume(audioManager: AudioManager?) {
        val volumeProgress = playerView.findViewById<SeekBar>(R.id.volume_progress) ?: return
        val btnVolume = playerView.findViewById<ImageButton>(R.id.btn_player_volume)
        if (audioManager == null) return

        val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        volumeProgress.max = maxVolume
        volumeProgress.progress = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)

        volumeProgress.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, progress, AudioManager.FLAG_SHOW_UI)
                updateVolumeIcon(progress, maxVolume, btnVolume)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        btnVolume?.setOnClickListener {
            val isMuted = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) == 0
            val target = if (isMuted) 7.coerceAtMost(maxVolume) else 0
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, target, AudioManager.FLAG_SHOW_UI)
            volumeProgress.progress = target
            updateVolumeIcon(target, maxVolume, btnVolume)
        }
    }
}
