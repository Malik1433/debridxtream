package com.tvonnet.debridxtreamiptv.player.stabilized

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.view.isVisible
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

    /** Which half a vertical drag started in — brightness on the left, volume on the right. */
    private var dragSide: DragSide = DragSide.NONE
    private var levelBadge: TextView? = null
    private val badgeHider = Handler(Looper.getMainLooper())

    /** Accumulated fraction of the range this gesture has travelled, and where it started from. */
    private var dragTravel = 0f
    private var volumeAtDragStart = -1
    private var brightnessAtDragStart = -1f

    private enum class DragSide { NONE, BRIGHTNESS, VOLUME }

    private companion object {
        /** Half a screen of travel covers the whole range. */
        const val DRAG_RANGE_FACTOR = 2f
        const val BADGE_LINGER_MS = 700L

        /** Matches setSeekForwardIncrementMs/setSeekBackIncrementMs in PlayerEngineFactory —
         *  the badge must state the step the player actually takes, not a second opinion. */
        const val SEEK_STEP_SECONDS = 10
    }

    override fun hostTouchEvent(event: MotionEvent) {
        touchGestures?.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> resetDragState()
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                resetDragState()
                hideLevelBadgeSoon()
            }
        }
    }

    private fun resetDragState() {
        dragSide = DragSide.NONE
        dragTravel = 0f
        volumeAtDragStart = -1
        brightnessAtDragStart = -1f
    }

    /**
     * M10b: the two adjustments every phone player has and this one had none of — brightness by
     * dragging the left half, volume by dragging the right half.
     *
     * Seeking is NOT added here on purpose. media3's DefaultTimeBar already scrubs on touch, and
     * the app already listens to it (PlayerVodControlsUi's OnScrubListener) — a second seek path
     * over the video would fight the one that exists and is already device-tested.
     *
     * The badge is built in code rather than added to the player layout, because that layout is
     * shared with TV: nothing new can appear there by accident.
     */
    private fun handleVerticalDrag(start: MotionEvent, distanceY: Float): Boolean {
        val width = playerView.width.takeIf { it > 0 } ?: return false
        val height = playerView.height.takeIf { it > 0 } ?: return false
        if (dragSide == DragSide.NONE) {
            dragSide = if (start.x < width / 2f) DragSide.BRIGHTNESS else DragSide.VOLUME
        }
        // Travel is accumulated across the whole gesture, not applied per event — see adjustVolume.
        // Half a screen of travel covers the full range, which keeps small corrections possible.
        dragTravel += distanceY / height * DRAG_RANGE_FACTOR
        return when (dragSide) {
            DragSide.BRIGHTNESS -> adjustBrightness(dragTravel)
            DragSide.VOLUME -> adjustVolume(dragTravel)
            DragSide.NONE -> false
        }
    }

    private fun adjustBrightness(travel: Float): Boolean {
        val window = activity?.window ?: return false
        val attrs = window.attributes
        // A window that has never been set reports -1 ("follow the system"); start from half so the
        // first drag moves from somewhere sensible instead of jumping.
        if (brightnessAtDragStart < 0f) {
            brightnessAtDragStart = attrs.screenBrightness.takeIf { it >= 0f } ?: 0.5f
        }
        val next = (brightnessAtDragStart + travel).coerceIn(0.01f, 1f)
        attrs.screenBrightness = next
        window.attributes = attrs
        showLevelBadge("☀ ${(next * 100).toInt()}%")
        return true
    }

    /**
     * Volume is a small integer (0..15 here), so it MUST be computed from where the drag started
     * plus the total travel. Applying each scroll delta to the current value instead loses every
     * one of them to the truncation — a 15-step scale means one delta is worth ~0.15 of a step,
     * `toInt()` throws it away, and the gesture does nothing at all. That is exactly what the
     * first version did on the emulator: swipe after swipe, the volume never moved.
     */
    private fun adjustVolume(travel: Float): Boolean {
        val audio = context?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return false
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).takeIf { it > 0 } ?: return false
        val start = volumeAtDragStart.takeIf { it >= 0 }
            ?: audio.getStreamVolume(AudioManager.STREAM_MUSIC).also { volumeAtDragStart = it }
        val next = Math.round(start + travel * max).coerceIn(0, max)
        if (next != audio.getStreamVolume(AudioManager.STREAM_MUSIC)) {
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, next, 0)
        }
        showLevelBadge("🔊 ${(next * 100 / max)}%")
        return true
    }

    private fun showLevelBadge(text: String) {
        val badge = levelBadge ?: createLevelBadge() ?: return
        badge.text = text
        badge.isVisible = true
        badgeHider.removeCallbacksAndMessages(null)
    }

    private fun hideLevelBadgeSoon() {
        badgeHider.removeCallbacksAndMessages(null)
        badgeHider.postDelayed({ levelBadge?.isVisible = false }, BADGE_LINGER_MS)
    }

    private fun createLevelBadge(): TextView? {
        val parent = playerView.parent as? ViewGroup ?: return null
        val badge = TextView(requireContext()).apply {
            // Drawn in code too — a new drawable resource would be visible to the TV build, and
            // this pill exists only for the phone.
            background = GradientDrawable().apply {
                cornerRadius = 12 * resources.displayMetrics.density
                setColor(0xCC000000.toInt())
            }
            setTextColor(android.graphics.Color.WHITE)
            textSize = 16f
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, pad / 2)
            isVisible = false
        }
        parent.addView(
            badge,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.CENTER }
        )
        levelBadge = badge
        return badge
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
                    // The skip worked but said nothing, so on a long shot it read as "nothing
                    // happened" and people double-tapped again. Every phone player answers the
                    // gesture; this reuses the same pill the volume and brightness drags use, so
                    // there is one feedback style rather than a second one invented for seeking.
                    showLevelBadge(
                        getString(
                            if (forward) R.string.c_seek_forward_badge else R.string.c_seek_back_badge,
                            SEEK_STEP_SECONDS
                        )
                    )
                    hideLevelBadgeSoon()
                    return true
                }

                override fun onScroll(
                    e1: MotionEvent?,
                    e2: MotionEvent,
                    distanceX: Float,
                    distanceY: Float,
                ): Boolean {
                    val start = e1 ?: return false
                    // Vertical only: a horizontal drag belongs to the seek bar, which handles its
                    // own touch, and stealing it here would make scrubbing unreliable.
                    if (kotlin.math.abs(distanceY) <= kotlin.math.abs(distanceX)) return false
                    return handleVerticalDrag(start, distanceY)
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
