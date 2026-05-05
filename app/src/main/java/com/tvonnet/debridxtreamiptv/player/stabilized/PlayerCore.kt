package com.tvonnet.debridxtreamiptv.player.stabilized

import android.content.Context
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.ui.PlayerView
import com.tvonnet.debridxtreamiptv.data.model.ContentType
import com.tvonnet.debridxtreamiptv.data.prefs.SettingsPreferences
import okhttp3.OkHttpClient

/**
 * Interface to communicate player events back to the Activity/UI.
 */
interface PlayerCoreListener {
    fun onReady()
    fun onError(error: Exception)
    fun onBuffering(isBuffering: Boolean)
    fun onPlaybackEnded()
    fun onPlaybackStateChanged(state: Int)
    fun onTracksChanged()
}

/**
 * PlayerCore encapsulates the ExoPlayer instance and its lifecycle.
 */
@OptIn(UnstableApi::class)
class PlayerCore(
    private val context: Context,
    private val okHttpClient: OkHttpClient,
    private val settingsPreferences: SettingsPreferences,
    private val listener: PlayerCoreListener
) {
    private var exoPlayer: ExoPlayer? = null
    private var trackSelector: DefaultTrackSelector? = null
    
    val player: Player? get() = exoPlayer
    
    var playbackSource: PlaybackSource = PlaybackSource.IPTV

    fun initializeAndPlay(
        mediaItem: MediaItem,
        isLive: Boolean,
        contentType: ContentType,
        startPositionMs: Long = 0L
    ) {
        Log.d("PLAYER_CORE", "initialize")
        if (exoPlayer != null) {
            release()
        }

        try {
            trackSelector = DefaultTrackSelector(context)
            
            val loadControl = DefaultLoadControl.Builder()
                .setBufferDurationsMs(
                    30000, // min
                    60000, // max
                    1500,  // buffer for playback
                    3000   // buffer for rebuffering
                )
                .build()

            exoPlayer = ExoPlayer.Builder(context)
                .setTrackSelector(trackSelector!!)
                .setLoadControl(loadControl)
                .setAudioAttributes(AudioAttributes.DEFAULT, true)
                .build().apply {
                    addListener(object : Player.Listener {
                        override fun onPlaybackStateChanged(state: Int) {
                            listener.onPlaybackStateChanged(state)
                            when (state) {
                                Player.STATE_READY -> listener.onReady()
                                Player.STATE_BUFFERING -> listener.onBuffering(true)
                                Player.STATE_ENDED -> listener.onPlaybackEnded()
                                else -> listener.onBuffering(false)
                            }
                        }

                        override fun onPlayerError(error: PlaybackException) {
                            listener.onError(error)
                        }

                        override fun onTracksChanged(tracks: Tracks) {
                            listener.onTracksChanged()
                        }
                    })
                }

            exoPlayer?.setMediaItem(mediaItem)
            
            if (startPositionMs > 0L) {
                exoPlayer?.seekTo(startPositionMs)
            }
            
            exoPlayer?.prepare()
            exoPlayer?.playWhenReady = true
            
        } catch (e: Exception) {
            listener.onError(e)
        }
    }

    fun applyTrackIndexOverrides(currentHash: String?, seriesId: String?) {
        val p = exoPlayer ?: return
        
        var tAIdx = -1
        var tTIdx = -1
        
        val lastHash = settingsPreferences.getLastSelectionHash()
        if (lastHash != null && currentHash != null && lastHash.equals(currentHash, ignoreCase = true)) {
            tAIdx = settingsPreferences.getLastAudioIndex()
            tTIdx = settingsPreferences.getLastTextIndex()
        } else if (seriesId != null) {
            tAIdx = settingsPreferences.getSeriesAudioIndex(seriesId)
            tTIdx = settingsPreferences.getSeriesTextIndex(seriesId)
        }
        
        if (tAIdx == -1 && tTIdx == -1) return
        
        val groups = p.currentTracks.groups
        val params = p.trackSelectionParameters.buildUpon()
        var mod = false
        
        if (tAIdx != -1) {
            var aC = 0
            for (g in groups) {
                if (g.type == C.TRACK_TYPE_AUDIO) {
                    for (j in 0 until g.length) {
                        if (aC == tAIdx) {
                            params.addOverride(TrackSelectionOverride(g.mediaTrackGroup, j))
                            mod = true
                            break
                        }
                        aC++
                    }
                }
                if (mod) break
            }
        }
        
        if (tTIdx != -1) {
            var tC = 0
            var tMod = false
            for (g in groups) {
                if (g.type == C.TRACK_TYPE_TEXT) {
                    for (j in 0 until g.length) {
                        if (tC == tTIdx) {
                            params.addOverride(TrackSelectionOverride(g.mediaTrackGroup, j))
                            mod = true
                            tMod = true
                            break
                        }
                        tC++
                    }
                }
                if (tMod) break
            }
        }
        
        if (mod) {
            p.trackSelectionParameters = params.build()
            Log.d("PlayerCore", "Applied track overrides: Audio=$tAIdx, Text=$tTIdx")
        }
    }

    fun play() {
        exoPlayer?.play()
    }

    fun pause() {
        exoPlayer?.pause()
    }

    fun stop() {
        exoPlayer?.stop()
    }

    fun attach(playerView: PlayerView) {
        Log.d("PLAYER_CORE", "attach")
        Log.d("VIDEO_FIX", "Player attached. playerView=${playerView != null}")
        playerView.player = exoPlayer
    }

    fun detach(playerView: PlayerView) {
        playerView.player = null
    }

    fun release() {
        Log.d("PLAYER_CORE", "release")
        exoPlayer?.release()
        exoPlayer = null
        trackSelector = null
    }
}
