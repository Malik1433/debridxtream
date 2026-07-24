package com.tvonnet.debridxtreamiptv.player.stabilized

import android.app.Dialog
import android.view.View
import android.widget.TextView
import com.tvonnet.debridxtreamiptv.R

/**
 * The audio / subtitle / aspect-ratio track pickers (extra Stage-1 extraction,
 * fragment-split prep). Owner chose to shrink PlayerActivity further before the
 * fragment split, so this cohesive dialog-UI cluster comes out on its own.
 *
 * Moved verbatim out of [PlayerActivity]: the three pickers, the resize cycler, the
 * aspect-label update, and the single managed-dialog slot ([showManagedTrackDialog] /
 * [dismissActiveTrackDialog]) that keeps exactly one track dialog alive and restores
 * D-pad focus to its anchor button on dismiss. Bodies are byte-identical.
 */
internal class PlayerTrackSelectionUi(
    private val activity: PlayerActivity,
    private val session: PlayerSessionState,
) {

    private var activeTrackDialog: Dialog? = null

    private val player get() = activity.player
    private val playerView get() = activity.playerView
    private val trackManager get() = activity.trackManager
    private val subtitleEntries: List<String> by session::subtitleEntries
    private val isInPictureInPictureMode get() = activity.isInPictureInPictureMode
    private fun showToast(message: String) = activity.showToast(message)

    fun showAudioSelection() {
        if (isInPictureInPictureMode) return
        val playerSnapshot = player ?: return
        trackManager.showAudioSelection(playerSnapshot) { dialog ->
            showManagedTrackDialog(dialog, R.id.btn_player_audio)
        }
    }

    fun showSubtitleSelection() {
        if (isInPictureInPictureMode) return
        val playerSnapshot = player ?: return
        trackManager.showSubtitleSelection(playerSnapshot, subtitleEntries) { dialog ->
            showManagedTrackDialog(dialog, R.id.btn_player_subtitles)
        }
    }

    fun showAspectSelection() {
        if (isInPictureInPictureMode) return
        val modes = listOf(
            androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT to "Fit",
            androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FILL to "Stretch",
            androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM to "Zoom",
            androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIXED_WIDTH to "Fixed Width",
            androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIXED_HEIGHT to "Fixed Height"
        )
        val labels = modes.map { it.second }
        val current = modes.indexOfFirst { it.first == playerView.resizeMode }.coerceAtLeast(0)
        lateinit var dialog: androidx.appcompat.app.AlertDialog
        val adapter = TrackSelectionAdapter(labels, current) { which ->
            val mode = modes[which].first
            playerView.resizeMode = mode
            updateAspectLabel(mode)
            dialog.dismiss()
        }
        dialog = androidx.appcompat.app.AlertDialog.Builder(activity, R.style.Theme_DebridXtream_CinematicDialog)
            .setTitle("Aspect Ratio")
            .setAdapter(adapter, adapter.asDialogClickListener())
            .create()
        showManagedTrackDialog(dialog, R.id.btn_aspect_ratio)
    }

    private fun updateAspectLabel(mode: Int) {
        val label = when (mode) {
            androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FILL -> "FILL"
            androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> "ZOOM"
            androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIXED_WIDTH -> "W·FIT"
            androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIXED_HEIGHT -> "H·FIT"
            else -> "FIT"
        }
        playerView.findViewById<TextView>(R.id.tv_aspect_label)?.text = label
    }

    fun cycleResizeMode() {
        val nextMode = when (playerView.resizeMode) {
            androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FILL
            androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FILL -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIXED_WIDTH
            androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIXED_WIDTH -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIXED_HEIGHT
            else -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
        }
        playerView.resizeMode = nextMode
        showToast("Resize Mode: ${when(nextMode) {
            androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT -> "Fit"
            androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FILL -> "Stretch"
            androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> "Zoom"
            androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIXED_WIDTH -> "Fixed Width"
            androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIXED_HEIGHT -> "Fixed Height"
            else -> "Fit"
        }}")
    }

    private fun showManagedTrackDialog(dialog: Dialog, anchorViewId: Int? = null) {
        dismissActiveTrackDialog()
        activeTrackDialog = dialog
        dialog.setOnDismissListener {
            if (activeTrackDialog === dialog) {
                activeTrackDialog = null
            }
            anchorViewId?.let { id ->
                playerView.findViewById<View>(id)?.requestFocus()
            }
        }
        dialog.show()
    }

    fun dismissActiveTrackDialog() {
        activeTrackDialog?.setOnDismissListener(null)
        activeTrackDialog?.dismiss()
        activeTrackDialog = null
    }
}
