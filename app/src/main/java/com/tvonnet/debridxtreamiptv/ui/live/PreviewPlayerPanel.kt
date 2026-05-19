package com.tvonnet.debridxtreamiptv.ui.live

import android.content.Context
import android.view.View
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import okhttp3.OkHttpClient
import com.tvonnet.debridxtreamiptv.R
import com.tvonnet.debridxtreamiptv.data.local.entity.EpgEntity
import com.tvonnet.debridxtreamiptv.data.model.XtreamStream
import com.tvonnet.debridxtreamiptv.data.model.toLiveStreamUrl
import com.tvonnet.debridxtreamiptv.data.prefs.CredentialsPreferences
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
    
    // UI Elements
    private val tvPreviewTitle: TextView? = view.findViewById(R.id.tv_preview_title)
    private val tvPreviewDescription: TextView? = view.findViewById(R.id.tv_preview_description)
    private val previewProgressBar: ProgressBar? = view.findViewById(R.id.preview_progress_bar)
    private val tvPreviewStartTime: TextView? = view.findViewById(R.id.tv_current_time_preview)
    private val tvPreviewEndTime: TextView? = view.findViewById(R.id.tv_total_time_preview)
    private val btnPreviewNext: TextView? = view.findViewById(R.id.btn_epg_next)
    private val btnFullscreen: ImageView? = view.findViewById(R.id.btn_fullscreen)
    private val btnPreviewFavorite: TextView? = view.findViewById(R.id.btn_favorite)

    // State
    private var currentStream: XtreamStream? = null
    private var nextProgram: EpgEntity? = null
    
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

    fun setFavoriteState(isFavorite: Boolean) {
        btnPreviewFavorite?.text = context.getString(
            if (isFavorite) R.string.live_favorite_action_remove else R.string.live_favorite_action_add
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
        
        // Get Credentials
        val credentialsPrefs = CredentialsPreferences(context)
        val serverUrl = credentialsPrefs.getServerUrl() ?: return
        val username = credentialsPrefs.getUsername() ?: return
        val password = credentialsPrefs.getPassword() ?: return
        val streamUrl = streamUrlOverride ?: stream.toLiveStreamUrl(serverUrl, username, password)
        
        // Setup/reuse Player
        val dataSourceFactory = OkHttpDataSource.Factory(okHttpClient)
            .setUserAgent("DebridXtream/1.0 (Linux; Android 10; TV)")
        val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory)
        
        val player = previewPlayer ?: ExoPlayer.Builder(context)
            .setMediaSourceFactory(mediaSourceFactory)
            .build().also { created ->
            created.volume = 0f // Muted by default
            previewPlayer = created
            previewPlayerView?.player = created
        }
        player.setMediaItem(MediaItem.fromUri(streamUrl))
        player.prepare()
        player.playWhenReady = true
        player.play()
        
        // Update UI Text
        updateStreamInfo(stream)
        updatePlaceholder(context.getString(R.string.player_epg_syncing))
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
    
    private fun updateNextProgramButton() {
        val next = nextProgram
        if (next == null) {
            btnPreviewNext?.visibility = View.GONE
            return
        }
        btnPreviewNext?.visibility = View.VISIBLE
        val label = context.getString(
            R.string.live_preview_next_program,
            next.title ?: context.getString(R.string.epg_no_info),
            "${formatTime(next.start)} - ${formatTime(next.stop)}"
        )
        btnPreviewNext?.text = label
    }

    private fun updateStreamInfo(stream: XtreamStream) {
        tvPreviewTitle?.text = stream.name ?: context.getString(R.string.player_epg_channel_unknown)
        tvPreviewDescription?.text = context.getString(
            R.string.live_preview_now_playing,
            stream.name ?: context.getString(R.string.player_epg_channel_unknown)
        )
        
        // Disable favorite button until state is known (caller should set it)
        btnPreviewFavorite?.isEnabled = false
        btnPreviewFavorite?.text = context.getString(R.string.live_favorite_action_add)
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
        tvPreviewTitle?.text = context.getString(R.string.live_preview_title_placeholder)
        tvPreviewDescription?.text = context.getString(R.string.live_preview_description_placeholder)
        previewProgressBar?.isVisible = false
        val placeholderTime = context.getString(R.string.live_time_placeholder)
        tvPreviewStartTime?.text = placeholderTime
        tvPreviewEndTime?.text = placeholderTime
        btnPreviewNext?.visibility = View.GONE
        nextProgram = null
    }
    
    private fun formatTime(timestamp: Long): String = previewTimeFormatter.format(Date(timestamp))

    fun releasePlayer() {
        previewPlayer?.release()
        previewPlayer = null
        previewPlayerView?.player = null
    }
    
    fun getCurrentStream(): XtreamStream? = currentStream
    
    fun isPlaying(): Boolean = previewPlayer?.isPlaying == true
    
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
}
