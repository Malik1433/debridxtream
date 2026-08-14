package com.tvonnet.debridxtreamiptv.ui.live.phone

import android.view.View
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import com.tvonnet.debridxtreamiptv.R
import com.tvonnet.debridxtreamiptv.data.local.entity.EpgEntity
import com.tvonnet.debridxtreamiptv.data.model.XtreamStream
import com.tvonnet.debridxtreamiptv.ui.live.PreviewPlayerPanel
import okhttp3.OkHttpClient

/**
 * The docked player — the handoff's central decision, kept as its own collaborator.
 *
 * Live TV on a phone is a zapping loop, so the first tap on a channel starts video HERE, in a
 * 16:9 dock pinned under the app bar, with the channel list still scrolling underneath. Nothing
 * is "selected" first and no back press is spent per hop. Fullscreen stays deliberate: the video
 * surface, the expand button, or rotating the handset.
 *
 * Playback itself is [PreviewPlayerPanel], the same collaborator the TV preview uses — the tuned
 * live player, the AudioTrack-releasing error handling and the fullscreen hand-off were expensive
 * to get right and must not be reimplemented per form factor. This class owns only where the dock
 * sits, when it is visible, and what is playing in it.
 */
class PhoneLiveDock(
    private val root: View,
    okHttpClient: OkHttpClient,
    lifecycle: Lifecycle,
    private val onFullscreen: (XtreamStream) -> Unit,
    private val onFavourite: (XtreamStream) -> Unit,
    /** Fired whenever the dock opens or closes, so the screen around it can re-read its state. */
    private val onChanged: () -> Unit,
) {
    /** Null while nothing is docked — which is also what makes the resume card the right thing. */
    var playingStreamId: String? = null
        private set

    private var panel: PreviewPlayerPanel? = PreviewPlayerPanel(
        context = root.context,
        view = root.findViewById(R.id.phone_live_dock),
        okHttpClient = okHttpClient,
        onFullscreenClick = { stream -> onFullscreen(stream) },
        onFavoriteClick = { stream -> onFavourite(stream) },
    ).also { lifecycle.addObserver(it) }

    init {
        // Both routes to fullscreen, per the handoff: the surface for people who tap the picture,
        // the button for people who look for a button.
        root.findViewById<View>(R.id.phone_dock_tap)?.setOnClickListener {
            panel?.getCurrentStream()?.let { onFullscreen(it) }
        }
    }

    fun play(channel: XtreamStream, epg: Pair<EpgEntity?, EpgEntity?>) {
        root.findViewById<View>(R.id.phone_live_dock)?.isVisible = true
        playingStreamId = channel.stream_id
        panel?.play(channel)
        bindProgramme(epg.first)
        onChanged()
    }

    /**
     * The programme line, written here rather than by [PreviewPlayerPanel.updateEpg].
     *
     * The panel composes the TV wording ("Now playing: X") and puts the start and end times in
     * two separate views the 10-foot layout has. This dock has one line for all of it and the
     * design words it as a time range, so it is composed here. Playback stays the panel's job.
     */
    private fun bindProgramme(now: EpgEntity?) {
        val line = root.findViewById<android.widget.TextView>(R.id.tv_preview_description)
        val bar = root.findViewById<android.widget.ProgressBar>(R.id.preview_progress_bar)
        if (now == null) {
            line?.text = root.context.getString(R.string.phone_no_guide_data)
            bar?.progress = 0
            return
        }
        line?.text = root.context.getString(
            R.string.phone_dock_programme,
            CLOCK.format(java.util.Date(now.start)),
            CLOCK.format(java.util.Date(now.stop)),
            now.title.orEmpty(),
        )
        val span = (now.stop - now.start).coerceAtLeast(1)
        val elapsed = (System.currentTimeMillis() - now.start).coerceIn(0, span)
        bar?.max = 100
        bar?.progress = ((elapsed * 100) / span).toInt()
    }

    /**
     * BACK collapses the dock before it leaves Live TV — a docked player is a state the user
     * entered on purpose, so it is one they can back out of. Returns false when there is nothing
     * docked, and BACK means what it usually means.
     */
    fun collapse(): Boolean {
        if (playingStreamId == null) return false
        playingStreamId = null
        panel?.releasePlayer()
        root.findViewById<View>(R.id.phone_live_dock)?.isVisible = false
        onChanged()
        return true
    }

    fun currentStream(): XtreamStream? = panel?.getCurrentStream()

    fun release() {
        panel?.releasePlayer()
        panel = null
    }

    private companion object {
        /** ROOT and 24h — the dock line sits beside other mono readouts. */
        val CLOCK = java.text.SimpleDateFormat("HH:mm", java.util.Locale.ROOT)
    }
}
