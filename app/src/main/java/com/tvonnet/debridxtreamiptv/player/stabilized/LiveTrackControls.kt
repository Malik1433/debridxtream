package com.tvonnet.debridxtreamiptv.player.stabilized

import android.content.Context
import android.widget.TextView
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.tvonnet.debridxtreamiptv.R
import java.util.Locale

/**
 * C8: the three cycling OSD controls — subtitles, audio track and aspect ratio.
 *
 * Each press moves to the next option and reports what it landed on, because on a TV there is no
 * menu to read back: the toast *is* the feedback. So every path here ends by writing the button
 * label **and** showing a toast, even the no-op ones (pressing AUDIO on a single-track stream still
 * says "AUDIO · ORIGINAL" rather than appearing dead).
 *
 * Two rules worth keeping:
 *  - **Subtitles cycle through OFF**, audio does not. Text tracks wrap to a disabled state
 *    (`setTrackTypeDisabled`) so a viewer can always get rid of them; audio wraps modulo the track
 *    count, because no-audio is never a state anyone wants to land on by accident.
 *  - **Only supported tracks are offered.** Filtering on `isSupported` keeps the cycle from
 *    selecting a codec this device cannot decode, which would stall playback rather than fail
 *    visibly.
 *
 * Bodies moved verbatim from `LivePlayerOsdManager`.
 */
internal class LiveTrackControls(
    private val playerView: PlayerView,
    private val playerProvider: () -> Player?,
    private val labels: Labels,
    private val host: Host,
) {
    /** The three button labels this writes back to; they always travel together. */
    class Labels(val cc: TextView, val audio: TextView, val aspect: TextView)

    /** The OSD reactions a control press triggers. */
    interface Host {
        fun showToast(message: String)
        fun resetOsdTimer()
    }

    private val context: Context get() = playerView.context

    companion object {
        val ASPECT_LABELS = listOf("Fit", "Fill", "Stretch", "Zoom")
        val ASPECT_MODES = listOf(
            AspectRatioFrameLayout.RESIZE_MODE_FIT,
            AspectRatioFrameLayout.RESIZE_MODE_FILL,
            AspectRatioFrameLayout.RESIZE_MODE_FIXED_WIDTH,
            AspectRatioFrameLayout.RESIZE_MODE_ZOOM
        )
    }

    private var aspectIndex = 0

    /** Initial labels, before the user has cycled anything. */
    fun applyInitialLabels() {
        labels.cc.text = context.getString(R.string.live_osd_off)
        labels.audio.text = context.getString(R.string.live_osd_original)
        labels.aspect.text = ASPECT_LABELS[0]
    }

    fun cycleCc() {
        val player = playerProvider() ?: return
        val groups = player.currentTracks.groups.filter {
            it.type == C.TRACK_TYPE_TEXT && it.isSupported
        }
        val params = player.trackSelectionParameters
        val currentOverride = params.overrides.values.firstOrNull { it.type == C.TRACK_TYPE_TEXT }
        val currentIdx = if (params.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT) || groups.isEmpty()) {
            -1
        } else {
            groups.indexOfFirst { it.mediaTrackGroup == currentOverride?.mediaTrackGroup }
        }
        val nextIdx = if (groups.isEmpty()) -1 else {
            val n = currentIdx + 1
            if (n >= groups.size) -1 else n
        }
        val builder = params.buildUpon().clearOverridesOfType(C.TRACK_TYPE_TEXT)
        val label: String
        if (nextIdx < 0) {
            builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            label = context.getString(R.string.live_osd_off)
        } else {
            builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            builder.addOverride(TrackSelectionOverride(groups[nextIdx].mediaTrackGroup, 0))
            label = trackLabel(groups[nextIdx].getTrackFormat(0).language, groups[nextIdx].getTrackFormat(0).label, nextIdx)
        }
        player.trackSelectionParameters = builder.build()
        labels.cc.text = label
        host.showToast("SUBTITLES · " + label.uppercase(Locale.getDefault()))
        host.resetOsdTimer()
    }

    fun cycleAudio() {
        val player = playerProvider() ?: return
        val groups = player.currentTracks.groups.filter {
            it.type == C.TRACK_TYPE_AUDIO && it.isSupported
        }
        if (groups.isEmpty()) {
            host.showToast("AUDIO · " + context.getString(R.string.live_osd_original).uppercase(Locale.getDefault()))
            return
        }
        val params = player.trackSelectionParameters
        val currentOverride = params.overrides.values.firstOrNull { it.type == C.TRACK_TYPE_AUDIO }
        val currentIdx = groups.indexOfFirst { it.mediaTrackGroup == currentOverride?.mediaTrackGroup }
            .let { if (it < 0) groups.indexOfFirst { g -> g.isSelected } else it }
            .coerceAtLeast(0)
        val nextIdx = (currentIdx + 1) % groups.size
        player.trackSelectionParameters = params.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
            .addOverride(TrackSelectionOverride(groups[nextIdx].mediaTrackGroup, 0))
            .build()
        val format = groups[nextIdx].getTrackFormat(0)
        val label = when {
            format.channelCount >= 6 -> "Dolby 5.1"
            else -> trackLabel(format.language, format.label, nextIdx)
        }
        labels.audio.text = label
        host.showToast("AUDIO · " + label.uppercase(Locale.getDefault()))
        host.resetOsdTimer()
    }

    fun cycleAspect() {
        aspectIndex = (aspectIndex + 1) % ASPECT_LABELS.size
        playerView.resizeMode = ASPECT_MODES[aspectIndex]
        labels.aspect.text = ASPECT_LABELS[aspectIndex]
        host.showToast("ASPECT · " + ASPECT_LABELS[aspectIndex].uppercase(Locale.getDefault()))
        host.resetOsdTimer()
    }

    private fun trackLabel(language: String?, label: String?, index: Int): String {
        return label
            ?: language?.let { Locale(it).displayLanguage.replaceFirstChar { c -> c.uppercase() } }
            ?: "Track ${index + 1}"
    }
}
