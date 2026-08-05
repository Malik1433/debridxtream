package com.tvonnet.debridxtreamiptv.ui.live

import android.content.Context
import android.view.View
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import okhttp3.OkHttpClient
import com.tvonnet.debridxtreamiptv.R
import com.tvonnet.debridxtreamiptv.data.local.entity.EpgEntity
import com.tvonnet.debridxtreamiptv.data.model.ContentType
import com.tvonnet.debridxtreamiptv.data.model.XtreamStream
import com.tvonnet.debridxtreamiptv.data.model.toLiveStreamUrl
import com.tvonnet.debridxtreamiptv.data.prefs.CredentialsPreferences
import com.tvonnet.debridxtreamiptv.data.prefs.SettingsPreferences
import com.tvonnet.debridxtreamiptv.player.stabilized.LivePlaybackLoadErrorPolicy
import com.tvonnet.debridxtreamiptv.player.stabilized.PlayerBufferConfigFactory
import com.tvonnet.debridxtreamiptv.util.DeviceProfile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Component to handle the Preview Player implementation
 * Decouples player logic from the Fragment
 */
class PreviewPlayerPanel(
    private val context: Context,
    private val view: View,
    private val okHttpClient: OkHttpClient,
    private val onFullscreenClick: (XtreamStream) -> Unit,
    private val onFavoriteClick: (XtreamStream) -> Unit
) : DefaultLifecycleObserver {

    private var previewPlayer: ExoPlayer? = null
    private var previewPlayerView: PlayerView? = view.findViewById(R.id.preview_player_view)
    
    // UI Elements.
    // NOTE: in the v2 layout "Watch" and the favourite button are containers (not an ImageView /
    // TextView), so these are typed as View — findViewById casts, so a narrower type here throws
    // ClassCastException at construction.
    private val tvPreviewTitle: TextView? = view.findViewById(R.id.tv_preview_title)
    private val tvPreviewDescription: TextView? = view.findViewById(R.id.tv_preview_description)
    private val previewProgressBar: ProgressBar? = view.findViewById(R.id.preview_progress_bar)
    private val tvPreviewStartTime: TextView? = view.findViewById(R.id.tv_current_time_preview)
    private val tvPreviewEndTime: TextView? = view.findViewById(R.id.tv_total_time_preview)
    private val btnPreviewNext: TextView? = view.findViewById(R.id.btn_epg_next)
    private val tvNextTime: TextView? = view.findViewById(R.id.tv_next_time)
    private val nextBar: View? = view.findViewById(R.id.livev2_next_bar)
    private val btnFullscreen: View? = view.findViewById(R.id.btn_fullscreen)
    private val btnPreviewFavorite: View? = view.findViewById(R.id.btn_favorite)
    private val ivFavoriteIcon: ImageView? = view.findViewById(R.id.iv_favorite_icon)
    private val badgeQuality: TextView? = view.findViewById(R.id.badge_quality)

    // State
    private var currentStream: XtreamStream? = null
    private var nextProgram: EpgEntity? = null
    private var playbackErrorRetries = 0

    /**
     * Until this existed the preview had no error handling at all: a failed player was left attached,
     * showing a frozen first frame with no message, no retry — and still holding its AudioTrack.
     * That last part is what made it more than cosmetic. AudioFlinger allows 64 tracks per output
     * thread, and once they are gone nothing on the device can start audio until it is restarted; a
     * preview that keeps a slot after it has already failed is spending one for nothing.
     *
     * So the first thing every terminal error does here is give the player back.
     */
    private val playerListener = object : Player.Listener {
        override fun onPlayerError(error: PlaybackException) = handlePlaybackError(error)

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            // A channel that recovers has earned its retries back; otherwise one bad hour would
            // leave the panel permanently one failure away from giving up.
            if (isPlaying) playbackErrorRetries = 0
        }
    }

    private fun handlePlaybackError(error: PlaybackException) {
        val action = PreviewPlaybackErrorPolicy.decide(
            error, playbackErrorRetries, MAX_PLAYBACK_ERROR_RETRIES
        )
        when (action) {
            PreviewPlaybackErrorPolicy.Action.RETRY -> {
                playbackErrorRetries++
                previewPlayer?.prepare()
            }
            PreviewPlaybackErrorPolicy.Action.RELEASE_AUDIO_EXHAUSTED -> {
                releasePlayer()
                updatePlaceholder(context.getString(R.string.live_preview_audio_unavailable))
            }
            PreviewPlaybackErrorPolicy.Action.RELEASE_FAILED -> {
                releasePlayer()
                updatePlaceholder(context.getString(R.string.live_preview_playback_failed))
            }
        }
    }


    // Formatters
    private val previewTimeFormatter = SimpleDateFormat("HH:mm", Locale.getDefault())

    init {
        setupListeners()
        resetState()
    }

    private fun setupListeners() {
        btnFullscreen?.setOnClickListener {
            currentStream?.let { onFullscreenClick(it) }
        }
        
        btnPreviewNext?.setOnClickListener {
            nextProgram?.let { program ->
                val details = listOfNotNull(
                    program.title?.takeIf { it.isNotBlank() },
                    program.description?.takeIf { it.isNotBlank() }
                ).joinToString("\n").ifBlank {
                    context.getString(R.string.player_epg_no_guide)
                }
                Toast.makeText(context, details, Toast.LENGTH_LONG).show()
            } ?: Toast.makeText(context, context.getString(R.string.player_epg_no_guide), Toast.LENGTH_SHORT).show()
        }
        
        btnPreviewFavorite?.setOnClickListener {
            currentStream?.let { onFavoriteClick(it) }
        }
    }

    /** v2's favourite control is an icon: a filled amber star when on, an outline when off. */
    fun setFavoriteState(isFavorite: Boolean) {
        btnPreviewFavorite?.isActivated = isFavorite
        btnPreviewFavorite?.contentDescription = context.getString(
            if (isFavorite) R.string.live_favorite_action_remove else R.string.live_favorite_action_add
        )
        ivFavoriteIcon?.setImageResource(
            if (isFavorite) R.drawable.ic_live_star_filled else R.drawable.ic_live_star
        )
        ivFavoriteIcon?.setColorFilter(
            ContextCompat.getColor(
                context,
                if (isFavorite) R.color.stremio_amber else R.color.stremio_text_secondary
            )
        )
        // Enable button now that state is known
        btnPreviewFavorite?.isEnabled = true
    }

    fun play(stream: XtreamStream, streamUrlOverride: String? = null) {
        // If same stream, just resume.
        if (currentStream?.stream_id == stream.stream_id && previewPlayer != null && streamUrlOverride == null) {
            previewPlayer?.playWhenReady = true
            previewPlayer?.play()
            return
        }

        currentStream = stream
        playbackErrorRetries = 0   // a new channel starts with a full retry budget

        // Get Credentials. These used to be bare `?: return`s — a preview that could not start
        // said nothing at all, to the user or to the log, which is exactly what made the phone's
        // "first tap does nothing" so hard to pin down. Now a miss is at least visible.
        val credentialsPrefs = CredentialsPreferences(context)
        val serverUrl = credentialsPrefs.getServerUrl()
        val username = credentialsPrefs.getUsername()
        val password = credentialsPrefs.getPassword()
        if (serverUrl == null || username == null || password == null) {
            android.util.Log.w(
                "PreviewPlayerPanel",
                "preview cannot start — missing credentials (server=${serverUrl != null} " +
                    "user=${username != null} pass=${password != null})"
            )
            updatePlaceholder(context.getString(R.string.player_epg_syncing))
            return
        }
        android.util.Log.d("PreviewPlayerPanel", "preview play() building player for id=${stream.stream_id}")
        val streamUrl = streamUrlOverride ?: stream.toLiveStreamUrl(serverUrl, username, password)
        
        val player = previewPlayer ?: buildTunedPreviewPlayer(streamUrl)
        player.setMediaItem(MediaItem.fromUri(streamUrl))
        player.prepare()
        player.playWhenReady = true
        player.play()
        
        // Update UI Text
        updateStreamInfo(stream)
        updatePlaceholder(context.getString(R.string.player_epg_syncing))
    }
    
    // Built with the SAME tuned engine as the fullscreen PlayerActivity (LP-ADOPT):
    // ffmpeg software-audio renderer (so EAC3/DTS/AC4 channels have audio), low-RAM
    // LoadControl (OOM byte-cap guard), and the 429 cool-off load-error policy. This
    // lets the guide→fullscreen hand-off ADOPT this already-running instance with
    // ZERO reconnect (one provider connection, no 403 on max_connections=1 accounts)
    // while still being fully tuned.
    private fun buildTunedPreviewPlayer(streamUrl: String): ExoPlayer {
        val dataSourceFactory = OkHttpDataSource.Factory(okHttpClient)
            .setUserAgent("IPTVSmartersPlayer")
        val settings = SettingsPreferences(context)
        val renderersFactory = DefaultRenderersFactory(context)
            .setExtensionRendererMode(
                if (settings.isSoftwareAudioEnabled())
                    DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER
                else
                    DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON
            )
            .setEnableDecoderFallback(true)
        val bufferConfig = PlayerBufferConfigFactory.buildConfig(
            context, settings, ContentType.LIVE_TV, streamUrl, isDebrid = false
        )
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                bufferConfig.minBufferMs, bufferConfig.maxBufferMs,
                bufferConfig.startPlaybackMs, bufferConfig.rebufferPlaybackMs
            )
            .setTargetBufferBytes(PlayerBufferConfigFactory.resolveTargetBufferBytes(context, false))
            .setPrioritizeTimeOverSizeThresholds(!DeviceProfile.isLowRamDevice(context))
            .setBackBuffer(0, /* retainBackBufferFromKeyframe= */ true)
            .build()
        val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory)
            .setLoadErrorHandlingPolicy(LivePlaybackLoadErrorPolicy())
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
            .build()
        return ExoPlayer.Builder(context)
            .setRenderersFactory(renderersFactory)
            .setMediaSourceFactory(mediaSourceFactory)
            .setLoadControl(loadControl)
            // Audible preview: the mini/preview player plays with sound, so request
            // audio focus (ducks other apps) just like fullscreen. PlayerActivity also
            // calls setAudioAttributes(handleAudioFocus=true) when it adopts for fullscreen.
            .setAudioAttributes(audioAttributes, /* handleAudioFocus= */ true)
            .build().also { created ->
                created.volume = 1f // Preview plays with sound
                created.addListener(playerListener)
                previewPlayer = created
                previewPlayerView?.player = created
            }
    }

    fun updateEpg(nowProgram: EpgEntity?, nextProgram: EpgEntity?) {
        this.nextProgram = nextProgram
        
        if (nowProgram == null) {
            updatePlaceholder(context.getString(R.string.live_preview_no_epg))
            updateNextProgramButton()
            return
        }
        
        val nowTitle = nowProgram.title?.takeIf { it.isNotBlank() }
            ?: currentStream?.name
            ?: context.getString(R.string.player_epg_channel_unknown)
            
        val descriptionParts = listOfNotNull(
            context.getString(R.string.live_preview_now_playing, nowTitle),
            nowProgram.description?.takeIf { it.isNotBlank() }
        )
        tvPreviewDescription?.text = descriptionParts.joinToString("\n")

        // Progress Bar
        val duration = (nowProgram.stop - nowProgram.start).coerceAtLeast(1L)
        val elapsed = (System.currentTimeMillis() - nowProgram.start)
            .coerceAtLeast(0)
            .coerceAtMost(duration)
            
        previewProgressBar?.apply {
            max = 1000
            progress = ((elapsed * 1000) / duration).toInt()
            isVisible = true
        }
        
        tvPreviewStartTime?.text = formatTime(nowProgram.start)
        tvPreviewEndTime?.text = formatTime(nowProgram.stop)
        
        updateNextProgramButton()
    }
    
    /**
     * v2's NEXT UP bar carries the start time and the title in separate views (rather than the
     * old single "Next: title (time)" label). The bar itself stays put; without a next programme
     * it just reads as a dash.
     */
    private fun updateNextProgramButton() {
        val next = nextProgram
        nextBar?.visibility = View.VISIBLE
        if (next == null) {
            btnPreviewNext?.text = context.getString(R.string.livev2_no_guide_dash)
            tvNextTime?.text = context.getString(R.string.live_time_placeholder)
            return
        }
        btnPreviewNext?.text = next.title?.takeIf { it.isNotBlank() }
            ?: context.getString(R.string.epg_no_info)
        tvNextTime?.text = formatTime(next.start)
    }

    /** v2's preview carries a 4K/HD chip, derived from the channel name like the list rows do. */
    private fun updateQualityBadge(stream: XtreamStream) {
        val badge = badgeQuality ?: return
        val name = stream.name.orEmpty()
        val is4k = name.contains("4k", ignoreCase = true) || name.contains("uhd", ignoreCase = true)
        val isHd = !is4k && name.contains("hd", ignoreCase = true)
        when {
            is4k -> {
                badge.text = "4K"
                badge.setBackgroundResource(R.drawable.bg_live_badge_quality_4k)
                badge.isVisible = true
            }
            isHd -> {
                badge.text = "HD"
                badge.setBackgroundResource(R.drawable.bg_live_badge_quality_hd)
                badge.isVisible = true
            }
            else -> badge.isVisible = false
        }
    }

    private fun updateStreamInfo(stream: XtreamStream) {
        tvPreviewTitle?.text = stream.name ?: context.getString(R.string.player_epg_channel_unknown)
        updateQualityBadge(stream)
        tvPreviewDescription?.text = context.getString(
            R.string.live_preview_now_playing,
            stream.name ?: context.getString(R.string.player_epg_channel_unknown)
        )
        
        // Disable favorite button until state is known (caller should set it)
        btnPreviewFavorite?.isEnabled = false
    }

    private fun updatePlaceholder(message: String) {
        tvPreviewDescription?.text = message
        previewProgressBar?.isVisible = false
        val placeholderTime = context.getString(R.string.live_time_placeholder)
        tvPreviewStartTime?.text = placeholderTime
        tvPreviewEndTime?.text = placeholderTime
        updateNextProgramButton()
    }

    private fun resetState() {
        badgeQuality?.isVisible = false
        tvPreviewTitle?.text = context.getString(R.string.live_preview_title_placeholder)
        tvPreviewDescription?.text = context.getString(R.string.live_preview_description_placeholder)
        previewProgressBar?.isVisible = false
        val placeholderTime = context.getString(R.string.live_time_placeholder)
        tvPreviewStartTime?.text = placeholderTime
        tvPreviewEndTime?.text = placeholderTime
        nextProgram = null
        updateNextProgramButton()
    }
    
    private fun formatTime(timestamp: Long): String = previewTimeFormatter.format(Date(timestamp))

    fun releasePlayer() {
        previewPlayer?.release()
        previewPlayer = null
        previewPlayerView?.player = null
    }
    
    fun getCurrentStream(): XtreamStream? = currentStream
    
    fun isPlaying(): Boolean = previewPlayer?.isPlaying == true

    /** Preview plays with sound by default; callers can mute (0f) or adjust volume here. */
    fun setVolume(volume: Float) {
        previewPlayer?.volume = volume.coerceIn(0f, 1f)
    }

    /** Exposes the underlying player so callers can seamlessly switch it to a fullscreen view. */
    fun getPlayer(): ExoPlayer? = previewPlayer

    /** Detaches and returns the current player WITHOUT releasing it (shared hand-off). */
    fun detachPlayer(): ExoPlayer? {
        val p = previewPlayer
        // Stop listening to a player we no longer own — otherwise a fullscreen error would make this
        // panel release a player that fullscreen is still using.
        p?.removeListener(playerListener)
        previewPlayer = null
        previewPlayerView?.player = null
        return p
    }

    /**
     * Snapshot the last rendered frame of the preview's TextureView-backed [PlayerView]. Used as the
     * hand-off cover so the fullscreen surface warm-up (and the return adopt) never flashes black.
     * Capture this BEFORE [detachPlayer].
     */
    fun captureFrame(): android.graphics.Bitmap? =
        runCatching { (previewPlayerView?.videoSurfaceView as? android.view.TextureView)?.getBitmap() }.getOrNull()

    /** Adopts an already-playing player (e.g. handed back from the fullscreen session). */
    fun adoptPlayer(p: ExoPlayer, stream: XtreamStream) {
        if (previewPlayer !== p) previewPlayer?.release()
        previewPlayer = p
        // Owned here again, so it gets the same error handling as one this panel built itself.
        p.removeListener(playerListener)   // idempotent: a re-adopt must not double-count errors
        p.addListener(playerListener)
        playbackErrorRetries = 0
        previewPlayerView?.player = p
        currentStream = stream
        p.playWhenReady = true
        updateStreamInfo(stream)
    }
    
    // Lifecycle methods
    override fun onResume(owner: LifecycleOwner) {
        super.onResume(owner)
        previewPlayer?.play()
    }

    override fun onPause(owner: LifecycleOwner) {
        super.onPause(owner)
        previewPlayer?.pause()
    }

    override fun onDestroy(owner: LifecycleOwner) {
        super.onDestroy(owner)
        releasePlayer()
    }

    private companion object {
        /**
         * Bounded on purpose. A preview that retries forever holds its slot forever, which is the
         * behaviour this whole change exists to remove; two attempts cover a transient provider
         * blip without turning a dead channel into a permanent tenant.
         */
        const val MAX_PLAYBACK_ERROR_RETRIES = 2
    }
}
