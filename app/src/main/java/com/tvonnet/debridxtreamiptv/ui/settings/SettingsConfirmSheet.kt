package com.tvonnet.debridxtreamiptv.ui.settings

import android.view.LayoutInflater
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.tvonnet.debridxtreamiptv.R
import com.tvonnet.debridxtreamiptv.ui.live.phone.PhoneUi

/**
 * "Are you sure?", replaced by something worth reading (G4).
 *
 * The design's rule: state the exact cost, then name what is KEPT. A bare "are you sure" tells
 * somebody nothing they did not already know — the useful question is what they lose and what
 * survives, and only the second half lets them answer it without fear.
 *
 * It matters more here than it did. Sign Out now clears everything this account left on the
 * device, including the other servers' libraries; the old dialog said none of that.
 *
 * Phone gets the sheet; television keeps the dialog, with the same two sentences.
 */
object SettingsConfirmSheet {

    /**
     * @param cost what this takes away, in plain words.
     * @param kept what survives it — omit only when nothing does.
     * @param confirmLabel the verb on the red button, never "OK": a button that names the action is
     *   the last chance to notice it is the wrong one.
     */
    fun show(
        host: Fragment,
        spec: Spec,
        onConfirm: () -> Unit,
    ) {
        val context = host.requireContext()
        if (context.resources.getBoolean(R.bool.ui_uses_dpad_focus)) {
            AlertDialog.Builder(context)
                .setTitle(spec.title)
                .setMessage(listOfNotNull(spec.cost, spec.kept).joinToString("\n\n"))
                .setPositiveButton(spec.confirmLabel) { d, _ -> d.dismiss(); onConfirm() }
                .setNegativeButton(R.string.cancel) { d, _ -> d.dismiss() }
                .show()
            return
        }

        val inflater = PhoneUi.unscaled(host, LayoutInflater.from(context))
        val content = inflater.inflate(R.layout.sheet_settings_confirm, null)
        val dialog = BottomSheetDialog(context)
        dialog.setContentView(content)

        content.findViewById<TextView>(R.id.settings_confirm_title).text = spec.title
        content.findViewById<TextView>(R.id.settings_confirm_cost).text = spec.cost
        content.findViewById<TextView>(R.id.settings_confirm_kept).apply {
            text = spec.kept.orEmpty()
            androidx.core.view.ViewCompat.setImportantForAccessibility(
                this,
                if (spec.kept.isNullOrBlank()) {
                    androidx.core.view.ViewCompat.IMPORTANT_FOR_ACCESSIBILITY_NO
                } else {
                    androidx.core.view.ViewCompat.IMPORTANT_FOR_ACCESSIBILITY_AUTO
                }
            )
            visibility = if (spec.kept.isNullOrBlank()) android.view.View.GONE else android.view.View.VISIBLE
        }
        content.findViewById<TextView>(R.id.settings_confirm_go).apply {
            text = spec.confirmLabel
            setOnClickListener {
                dialog.dismiss()
                onConfirm()
            }
        }
        content.findViewById<TextView>(R.id.settings_confirm_cancel).setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    /** What a confirmation has to say. Grouped so the call sites read as sentences, not arguments. */
    data class Spec(
        val title: String,
        val cost: String,
        val kept: String?,
        val confirmLabel: String,
    )
}
