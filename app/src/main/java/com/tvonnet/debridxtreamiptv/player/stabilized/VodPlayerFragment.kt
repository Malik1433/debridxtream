package com.tvonnet.debridxtreamiptv.player.stabilized

import android.content.Context
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.media.AudioManager
import android.view.View
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.tvonnet.debridxtreamiptv.R
import com.tvonnet.debridxtreamiptv.data.model.ContentType
import dagger.hilt.android.AndroidEntryPoint

/**
 * The VOD (movie / series episode) player screen (P27 fragment split).
 *
 * P28-a moved the VOD-only setup branch of [BasePlayerFragment.onViewCreated] down here as a
 * hook override (behaviour-preserving; a LIVE instance never reaches it). Further P28 steps move
 * the remaining VOD-only bodies — episode browser / up-next prompt / in-player source panel /
 * VOD seek overlay / 2-step BACK — so the base is left with only the shared scaffolding.
 */
@UnstableApi
@AndroidEntryPoint
class VodPlayerFragment : BasePlayerFragment() {

    /** Set only on a touch device; the Activity feeds every touch through it (M9b's hook). */
    private var touchGestures: GestureDetector? = null

    override fun hostTouchEvent(event: MotionEvent) {
        touchGestures?.onTouchEvent(event)
    }

    /**
     * M10: double-tap to skip, the one gesture every phone player has. Left half rewinds, right
     * half goes forward.
     *
     * It sends KEYCODE_MEDIA_REWIND / _FAST_FORWARD rather than seeking directly, so the skip is
     * the SAME one the remote's buttons perform — `onRewind`/`onForward` set the directional
     * SeekParameters first, and that detail is load-bearing: dropping it is what once made seeks
     * snap back to the start. A second seek path would have re-opened that.
     *
     * Single taps are deliberately left alone: media3's own controller already shows and hides
     * the chrome on a tap here, and adding a second handler would fight it.
     */
    private fun attachTouchGesturesIfMobile() {
        if (resources.getBoolean(R.bool.ui_uses_dpad_focus)) return
        if (touchGestures != null) return

        touchGestures = GestureDetector(
            requireContext(),
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onDoubleTap(e: MotionEvent): Boolean {
                    val forward = e.x > playerView.width / 2f
                    sendKey(
                        if (forward) KeyEvent.KEYCODE_MEDIA_FAST_FORWARD
                        else KeyEvent.KEYCODE_MEDIA_REWIND
                    )
                    return true
                }
            }
        )
    }

    private fun sendKey(keyCode: Int) {
        val host = activity ?: return
        host.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        host.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
    }

    override fun setupVodPlayback(streamTitle: String?) {
        playerView.useController = true
        playerView.controllerAutoShow = true
        playerView.controllerHideOnTouch = true
        attachTouchGesturesIfMobile()
        // Black-video-on-episode-switch fix: a mid-stream episode switch calls player.stop()
        // (inside performSeamlessSwitch), which makes PlayerView drop the "shutter" (a black
        // view) over the surface until a new frame renders. On this Amlogic HW path the
        // shutter could stay black even though the new codec was already rendering frames
        // (logcat: "Got First Frame Render" + a resolution change, yet a black screen). Keep
        // the last frame across the reset instead of the black shutter — the new video simply
        // replaces it. VOD only; LIVE has its own zap backdrop.
        playerView.setKeepContentOnPlayerReset(true)
        playerView.setControllerVisibilityListener(object : PlayerView.ControllerVisibilityListener {
            override fun onVisibilityChanged(visibility: Int) {
                isControllerVisible = visibility == View.VISIBLE
                if (!isControllerVisible) {
                    playerView.requestFocus()
                }
            }
        })

        player?.let { updatePlayPauseVisibility(playerView, it.isPlaying, isControllerVisible) }
        bindModernMetadata(streamTitle)
        setupInteractiveAnimations(playerView)
        vodControls.setupSeekOverlay()

        val isSeriesControls = contentType == ContentType.SERIES || contentType == ContentType.EPISODE
        transportButtons.bind(isSeriesControls, isMovie = contentType == ContentType.MOVIE)
        transportButtons.bindVolume(getSystemService(Context.AUDIO_SERVICE) as? AudioManager)
        // VOD Player redesign: explicit horizontal focus chain so play/pause visibility
        // swaps never trap focus (both exo_play & exo_pause share the same L/R links).
        vodControls.wireControlFocus(isSeriesControls)

        nextEpisodeManager = PlayerNextEpisodeManager(requireActivity(), playerView, object : PlayerNextEpisodeManager.Delegate {
            override fun isPlaybackEligibleForPrompt(): Boolean {
                return contentType == ContentType.SERIES || contentType == ContentType.EPISODE
            }
            override fun getPlayerDuration(): Long = player?.duration ?: 0L
            override fun getPlayerCurrentPosition(): Long = player?.currentPosition ?: 0L
            override fun isPlayerPlaying(): Boolean = player?.isPlaying ?: false
            override fun getPlayer(): ExoPlayer? = player
            override fun getSeriesFallbackPosterUrl(): String? =
                posterUrlExtra?.takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }
                    ?: backdropUrlExtra?.takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }
            override fun getNextEpisode(): com.tvonnet.debridxtreamiptv.features.seriesv2.data.model.EpisodeEntityV2? {
                val state = viewModel.seriesPlaylistState.value
                return if (state?.hasNext == true) {
                    state.originalList.getOrNull(state.currentIndex + 1)
                } else null
            }
            override fun onPlayNextEpisodeRequested() {
                seriesController.playNextEpisode()
            }
            override fun onNextEpisodePromptShown(nextEpisode: com.tvonnet.debridxtreamiptv.features.seriesv2.data.model.EpisodeEntityV2) {
                if (playbackSource != PlaybackSource.DEBRID || contentType != ContentType.EPISODE) return
                val currentSeason = seasonNumberExtra ?: return
                val currentEpisode = episodeNumberExtra ?: return
                val tmdbId = debridSeriesLookupId().takeIf { it.isNotBlank() } ?: return
                val targetSeason = nextEpisode.seasonNumber.takeIf { it >= currentSeason } ?: currentSeason
                val targetEpisode = when {
                    nextEpisode.seasonNumber == currentSeason && nextEpisode.episodeNumber > currentEpisode ->
                        nextEpisode.episodeNumber
                    nextEpisode.seasonNumber > currentSeason -> nextEpisode.episodeNumber
                    else -> currentEpisode + 1
                }
                viewModel.preResolveNextDebridEpisode(
                    seriesId = tmdbId,
                    targetSeason = targetSeason,
                    targetEpisode = targetEpisode,
                    seriesTitle = seriesTitleExtra,
                    infoHash = debridInfoHashExtra ?: debridStreamIdExtra,
                    sourceProfile = currentDebridSourceProfile()
                )
            }
        })
        nextEpisodeManager.setupViews()
        seriesController.observeSeriesPlaylistState()
        debridCoordinator.observeDebridResolutionState()
        seriesController.setupEpisodeBrowser()

    }
}
