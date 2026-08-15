package com.tvonnet.debridxtreamiptv.ui.detail.phone

import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.tvonnet.debridxtreamiptv.R
import com.tvonnet.debridxtreamiptv.features.seriesv2.ui.StreamPanelState
import com.tvonnet.debridxtreamiptv.features.seriesv2.ui.model.IptvStreamOption
import com.tvonnet.debridxtreamiptv.ui.live.phone.PhoneUi

/**
 * The stream picker for one episode.
 *
 * A sheet rather than a pushed screen: an IPTV episode has a handful of streams, not the sixty a
 * debrid title can have, and a sheet keeps the episode list visible behind it.
 *
 * It lives outside the fragment because that fragment reached the size this project allows with
 * this code inside it, and because a picker that owns its own dialog is easier to reason about
 * than one whose dialog is a field on a screen.
 */
class PhoneEpisodeStreamSheet(
    private val host: Fragment,
    private val onPick: (IptvStreamOption) -> Unit,
) {
    private var sheet: BottomSheetDialog? = null
    private var onDismissedCallback: () -> Unit = {}

    /** Told when the user dismisses the sheet themselves, so the ViewModel can close its panel. */
    fun onDismiss(callback: () -> Unit) {
        onDismissedCallback = callback
    }

    private fun onDismissed() = onDismissedCallback()

    fun dismiss() {
        sheet?.dismiss()
        sheet = null
    }

    fun show(panel: StreamPanelState) {
        val sheet = sheet ?: com.google.android.material.bottomsheet.BottomSheetDialog(
            host.requireContext()
        ).also { dialog ->
            val content = PhoneUi.unscaled(host, host.layoutInflater)
                .inflate(R.layout.sheet_phone_streams, null)
            dialog.setContentView(content)
            dialog.setOnDismissListener { onDismissed() }
            dialog.show()
            sheet = dialog
        }
        val content = sheet.findViewById<View>(R.id.phone_streams_root) ?: return

        content.findViewById<TextView>(R.id.phone_streams_title).text = panel.episodeLabel
        // Two different waits share the spinner line: finding the streams, and — after a row is
        // tapped — resolving the picked one to a playable URL. The second is a network fetch that
        // can take seconds, and while it ran the sheet used to sit frozen with nothing changing,
        // which is exactly "I tapped play and it did nothing for a long time".
        val resolving = panel.resolvingStreamId != null
        content.findViewById<View>(R.id.phone_streams_loading).isVisible =
            panel.loading || resolving
        content.findViewById<TextView>(R.id.phone_streams_loading_label)?.setText(
            if (resolving) R.string.phone_opening_stream else R.string.phone_finding_streams
        )
        content.findViewById<TextView>(R.id.phone_streams_error).apply {
            text = panel.error.orEmpty()
            isVisible = !panel.error.isNullOrBlank() && !panel.loading
        }

        val list = content.findViewById<LinearLayout>(R.id.phone_streams_list)
        list.removeAllViews()
        val inflater = PhoneUi.unscaled(host, host.layoutInflater)
        panel.filteredGroups.flatMap { it.streams }.forEach { option ->
            val row = inflater.inflate(R.layout.item_phone_source, list, false)
            row.findViewById<TextView>(R.id.phone_srow_quality).text = option.quality.label
            row.findViewById<TextView>(R.id.phone_srow_size).text = option.container
            row.findViewById<TextView>(R.id.phone_srow_name).text = option.name
            row.findViewById<TextView>(R.id.phone_srow_provider).text =
                (listOf(option.categoryTag) + option.languages.map { it.code }).joinToString(" · ")
            row.findViewById<TextView>(R.id.phone_srow_state).apply {
                // The tapped row says what it is doing; the rest dim so a second tap is
                // obviously not going to help.
                isVisible = panel.resolvingStreamId == option.streamId
                if (isVisible) {
                    setText(R.string.phone_opening_stream)
                    setTextColor(
                        androidx.core.content.ContextCompat.getColor(
                            host.requireContext(), com.tvonnet.debridxtreamiptv.R.color.phone_cyan
                        )
                    )
                }
            }
            row.alpha = if (resolving && panel.resolvingStreamId != option.streamId) 0.4f else 1f
            row.isEnabled = !resolving
            if (!resolving) row.setOnClickListener { onPick(option) }
            list.addView(row)
        }
    }
}
