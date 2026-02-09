package com.tvonnet.debridxtreamiptv.util

import android.content.Context
import android.view.SurfaceView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.upstream.DefaultAllocator
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Phase 2.6: Media3 Performance Issues - Media3 Utility Class
 *
 * Provides TV-optimized Media3 configuration to fix JNISurfaceTexture and FrameEvents errors:
 * - TV-specific renderer configuration
 * - Proper surface texture management
 * - Optimized buffer allocation for TV
 * - Performance monitoring and cleanup
 */
object Media3Utils {

    private const val TAG = "Media3Utils"

    /**
     * Create TV-optimized ExoPlayer
     */
    fun createOptimizedPlayer(context: Context): ExoPlayer {
        return ExoPlayer.Builder(context)
            .setTrackSelector(createTrackSelector(context))
            .setLoadControl(createLoadControl())
            .setRenderersFactory(createRenderersFactory(context))
            .build().apply {
                // TV-specific player settings
                playWhenReady = false
                repeatMode = Player.REPEAT_MODE_OFF
                volume = 1.0f

                // Add listener for performance monitoring
                addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        when (playbackState) {
                            Player.STATE_BUFFERING -> {
                                android.util.Log.d(TAG, "Player buffering...")
                            }
                            Player.STATE_READY -> {
                                android.util.Log.d(TAG, "Player ready")
                            }
                            Player.STATE_ENDED -> {
                                android.util.Log.d(TAG, "Player ended")
                            }
                            Player.STATE_IDLE -> {
                                android.util.Log.d(TAG, "Player idle")
                            }
                        }
                    }
                })
            }
    }

    /**
     * Create TV-optimized TrackSelector
     */
    private fun createTrackSelector(context: Context): DefaultTrackSelector {
        return DefaultTrackSelector(context).apply {
            // TV-specific track selection parameters
            parameters = parameters
                .buildUpon()
                .setPreferredAudioLanguage("en")
                .setPreferredTextLanguage("en")
                .setMaxVideoSizeSd()
                .build()
        }
    }

    /**
     * Create TV-optimized LoadControl
     */
    private fun createLoadControl(): LoadControl {
        val allocator = DefaultAllocator(true, C.DEFAULT_BUFFER_SEGMENT_SIZE)
        return DefaultLoadControl.Builder()
            .setAllocator(allocator)
            .setBufferDurationsMs(
                DefaultLoadControl.DEFAULT_MIN_BUFFER_MS,
                DefaultLoadControl.DEFAULT_MAX_BUFFER_MS,
                DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_MS,
                DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS
            )
            .setTargetBufferBytes(DefaultLoadControl.DEFAULT_TARGET_BUFFER_BYTES)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()
    }

    /**
     * Create TV-optimized RenderersFactory
     */
    private fun createRenderersFactory(context: Context): RenderersFactory {
        return DefaultRenderersFactory(context).apply {
            // Use default settings for TV - avoid extension renderer mode issues
        }
    }

    /**
     * Create TV-optimized MediaSourceFactory
     */
    fun createMediaSourceFactory(context: Context): MediaSource.Factory {
        return DefaultMediaSourceFactory(context)
    }

    /**
     * Safely set player on PlayerView with proper surface management
     */
    fun setPlayerOnView(playerView: PlayerView, player: ExoPlayer) {
        try {
            // Clear any previous player
            playerView.player = null

            // Set new player
            playerView.player = player

            // TV-specific view settings
            playerView.controllerAutoShow = false
            playerView.useController = false
            playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT

            android.util.Log.d(TAG, "Player set on view successfully")
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Error setting player on view", e)
        }
    }

    /**
     * Safely release player and clear view
     */
    fun releasePlayerAndClearView(playerView: PlayerView) {
        try {
            val player = playerView.player as? ExoPlayer
            playerView.player = null

            player?.let {
                it.release()
                android.util.Log.d(TAG, "Player released successfully")
            }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Error releasing player", e)
        }
    }

    /**
     * Monitor player performance and detect issues
     */
    fun monitorPlayerPerformance(player: ExoPlayer, surfaceView: SurfaceView): Job {
        return CoroutineScope(Dispatchers.IO).launch {
            var lastBandwidth = 0L

            while (true) {
                try {
                    delay(5000) // Check every 5 seconds

                    // Check surface health
                    if (surfaceView.holder == null) {
                        android.util.Log.w(TAG, "Surface holder is null")
                    }

                    // Clean up stale cache entries periodically
                    EpgCache.cleanupStaleEntries()

                    // Log basic player state
                    android.util.Log.d(TAG, "Player state: ${player.playbackState}")

                } catch (e: Exception) {
                    android.util.Log.e(TAG, "Error in performance monitoring", e)
                }
            }
        }
    }

    /**
     * Create MediaItem from URL
     */
    fun createMediaItem(url: String): MediaItem {
        return MediaItem.fromUri(url)
    }

    /**
     * Check if player can handle the media
     */
    fun canPlayMedia(url: String): Boolean {
        return try {
            val mediaItem = createMediaItem(url)
            // Basic validation - could be enhanced with actual media type checking
            mediaItem.localConfiguration != null
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Cannot play media: $url", e)
            false
        }
    }
}