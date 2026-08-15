package com.tvonnet.debridxtreamiptv.ui.browse.phone

import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.flexbox.FlexboxLayout
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.tvonnet.debridxtreamiptv.R
import com.tvonnet.debridxtreamiptv.ui.live.phone.PhoneUi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * What the user can narrow a catalogue by, and what each choice would leave them with.
 *
 * Xtream states neither quality nor language as a field — both live in the title, which is why
 * the options are title patterns rather than columns. That also decides the honest limit here:
 * a count is a real query, so counts are computed for the CURRENT category only (owner decision,
 * 2026-08-15). On the virtual "All" categories, which are 141,525 titles on this owner's
 * playlist, the chips carry no number rather than a number nobody would want to wait for.
 */
class PhoneBrowseFilterSheet(
    private val host: Fragment,
    private val selection: Selection,
    private val categoryName: String?,
    private val countFor: suspend (List<List<String>>) -> Int?,
    private val onApply: (Selection) -> Unit,
) {
    /** The chosen patterns, kept as the two groups the query understands. */
    data class Selection(
        val quality: Set<String> = emptySet(),
        val languages: Set<String> = emptySet(),
    ) {
        /**
         * [categoryName] matters because this provider often states quality on the CATEGORY
         * ("|MULTI| NETFLIX 4K") and never in those titles. Asking each row to also contain "4K"
         * inside a category that IS 4K would return nothing — so a quality the category already
         * declares becomes a no-op rather than a contradiction.
         */
        fun groups(categoryName: String? = null): List<List<String>> {
            val declared = categoryName.orEmpty().uppercase()
            val needed = quality.filterNot { label ->
                QUALITY_PATTERNS[label].orEmpty().any { declared.contains(it) }
            }
            return listOfNotNull(
                needed.flatMap { QUALITY_PATTERNS[it].orEmpty() }.takeIf { it.isNotEmpty() },
                languages.map { "|$it|" }.takeIf { it.isNotEmpty() },
            )
        }

        val activeCount: Int get() = quality.size + languages.size
    }

    private var working = selection
    private var countJob: Job? = null

    fun show(languages: List<String>) {
        val context = host.requireContext()
        val inflater = PhoneUi.unscaled(host, LayoutInflater.from(context))
        val content = inflater.inflate(R.layout.sheet_phone_browse_filters, null)
        val dialog = BottomSheetDialog(context)
        dialog.setContentView(content)

        val qualityBox = content.findViewById<FlexboxLayout>(R.id.phone_bfil_quality)
        val languageBox = content.findViewById<FlexboxLayout>(R.id.phone_bfil_language)
        val apply = content.findViewById<TextView>(R.id.phone_bfil_apply)
        val reset = content.findViewById<TextView>(R.id.phone_bfil_reset)
        val active = content.findViewById<TextView>(R.id.phone_bfil_active)

        fun redraw() {
            active.text = if (working.activeCount == 0) {
                ""
            } else {
                context.getString(R.string.phone_filter_active, working.activeCount)
            }
            reset.isEnabled = working.activeCount > 0
            reset.alpha = if (working.activeCount > 0) 1f else DISABLED_ALPHA

            fillChips(inflater, qualityBox, QUALITY_PATTERNS.keys.toList(), working.quality) { name ->
                working = working.copy(
                    quality = working.quality.toMutableSet().apply {
                        if (!add(name)) remove(name)
                    }
                )
                redraw()
            }
            content.findViewById<View>(R.id.phone_bfil_language_label).isVisible =
                languages.isNotEmpty()
            fillChips(inflater, languageBox, languages, working.languages) { name ->
                working = working.copy(
                    languages = working.languages.toMutableSet().apply {
                        if (!add(name)) remove(name)
                    }
                )
                redraw()
            }

            // The footer count is the same query the grid will run, so the promise on the button
            // and the list behind it cannot disagree.
            apply.text = context.getString(R.string.phone_filter_counting)
            apply.isEnabled = false
            countJob?.cancel()
            countJob = host.viewLifecycleOwner.lifecycleScope.launch {
                val total = countFor(working.groups(categoryName))
                apply.text = when {
                    total == null -> context.getString(R.string.phone_filter_show_all)
                    total == 0 -> context.getString(R.string.phone_filter_show_none)
                    else -> context.getString(R.string.phone_filter_show, "%,d".format(total))
                }
                // Never let the user apply their way into a blank grid.
                apply.isEnabled = total == null || total > 0
                apply.alpha = if (apply.isEnabled) 1f else DISABLED_ALPHA
            }
        }

        apply.setOnClickListener {
            onApply(working)
            dialog.dismiss()
        }
        reset.setOnClickListener {
            working = Selection()
            redraw()
        }
        content.findViewById<View>(R.id.phone_bfil_close).setOnClickListener { dialog.dismiss() }
        dialog.setOnDismissListener { countJob?.cancel() }
        redraw()
        dialog.show()
    }

    private fun fillChips(
        inflater: LayoutInflater,
        box: FlexboxLayout,
        names: List<String>,
        selected: Set<String>,
        onToggle: (String) -> Unit,
    ) {
        box.removeAllViews()
        names.forEach { name ->
            val chip = inflater.inflate(R.layout.item_phone_filter_chip, box, false)
            val on = name in selected
            chip.findViewById<TextView>(R.id.phone_fchip_name).apply {
                text = name
                setTextColor(
                    androidx.core.content.ContextCompat.getColor(
                        context,
                        if (on) R.color.phone_cyan else R.color.phone_text_primary
                    )
                )
            }
            // The per-option count needs one query per chip; that is the part deliberately not
            // built yet (see the class KDoc), so the slot stays empty rather than lying.
            chip.findViewById<TextView>(R.id.phone_fchip_count).isVisible = false
            chip.setBackgroundResource(
                if (on) R.drawable.bg_phone_chip_active else R.drawable.bg_phone_chip
            )
            chip.setOnClickListener { onToggle(name) }
            box.addView(chip)
        }
    }

    companion object {
        private const val DISABLED_ALPHA = 0.42f

        /** What each quality label looks like in a provider's title. */
        private val QUALITY_PATTERNS = linkedMapOf(
            "4K" to listOf("2160", "4K", "UHD"),
            "FHD" to listOf("1080", "FHD"),
            "HD" to listOf("720"),
        )
    }
}
