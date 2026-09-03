package com.tvonnet.debridxtreamiptv.ui.browse.phone

import android.view.LayoutInflater
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.tvonnet.debridxtreamiptv.R
import com.tvonnet.debridxtreamiptv.data.model.XtreamCategory
import com.tvonnet.debridxtreamiptv.ui.browse.VirtualCategoryNames

/**
 * The two controls pinned above the grid: the category rail and the filter chip.
 *
 * They live here rather than in the fragment because they are pure view work — data in, chips
 * out — and because the screen was one edit past the size this project allows with them inside
 * it. The fragment keeps every decision; this only draws.
 */
class PhoneBrowseChrome(
    private val rail: LinearLayout,
    private val filterChip: TextView,
) {
    /**
     * Selected category first, then playlist order, behind an opener that leads to the searchable
     * sheet — because at 200 categories scrolling is not a retrieval strategy, typing is.
     *
     * The chip layout is Live TV's: Browse's rail IS that rail, and a second layout doing the same
     * job would be a second place to forget a fix.
     */
    /** What the rail is drawing: the catalogue's categories, their counts and the current one. */
    data class Rail(
        val categories: List<XtreamCategory>,
        val counts: Map<String, Int>,
        val selectedId: String?,
    )

    fun renderRail(
        inflater: LayoutInflater,
        data: Rail,
        onOpener: () -> Unit,
        onPick: (String) -> Unit,
    ) {
        val categories = data.categories
        val counts = data.counts
        val selectedId = data.selectedId
        rail.removeAllViews()

        val opener = inflater.inflate(R.layout.item_phone_category_chip, rail, false)
        opener.findViewById<TextView>(R.id.phone_chip_name).text =
            rail.context.getString(R.string.phone_all_prefix) + " " + categories.size
        opener.findViewById<TextView>(R.id.phone_chip_count).text =
            rail.context.getString(R.string.phone_categories).uppercase()
        opener.setOnClickListener { onOpener() }
        rail.addView(opener)

        categories.sortedByDescending { it.category_id == selectedId }
            .take(RAIL_CHIPS)
            .forEach { category ->
                val chip = inflater.inflate(R.layout.item_phone_category_chip, rail, false)
                val selected = category.category_id == selectedId
                chip.findViewById<TextView>(R.id.phone_chip_name).apply {
                    text = VirtualCategoryNames.displayName(context, category)
                    setTextColor(
                        ContextCompat.getColor(
                            context,
                            if (selected) R.color.phone_cyan else R.color.phone_text_primary
                        )
                    )
                }
                chip.findViewById<TextView>(R.id.phone_chip_count).text =
                    counts[category.category_id]?.let { chip.context.getString(R.string.code_titles_count, "%,d".format(it)) }.orEmpty()
                chip.setBackgroundResource(
                    if (selected) R.drawable.bg_phone_chip_active else R.drawable.bg_phone_chip
                )
                chip.setOnClickListener { onPick(category.category_id.orEmpty()) }
                rail.addView(chip)
            }
    }

    /** The chip carries its own state: cyan with a count whenever anything is filtering. */
    fun renderFilterChip(activeCount: Int) {
        val context = filterChip.context
        filterChip.text = if (activeCount == 0) {
            context.getString(R.string.phone_filters)
        } else {
            context.getString(R.string.phone_filters) + "  " + activeCount
        }
        filterChip.setBackgroundResource(
            if (activeCount == 0) R.drawable.bg_phone_chip else R.drawable.bg_phone_chip_active
        )
        filterChip.setTextColor(
            ContextCompat.getColor(
                context,
                if (activeCount == 0) R.color.phone_text_primary else R.color.phone_cyan
            )
        )
    }

    companion object {
        private const val RAIL_CHIPS = 12

        /**
         * The languages a catalogue actually uses, read off the titles already on screen: this
         * provider tags them |EN| |AR| |DE|, and offering one the category does not contain would
         * be a dead end by construction.
         */
        fun languagesIn(titles: List<String>, limit: Int = 8): List<String> {
            val tag = Regex("""\|([A-Z]{2,4})\|""")
            return titles.mapNotNull { tag.find(it)?.groupValues?.getOrNull(1) }
                .groupingBy { it }.eachCount()
                .entries.sortedByDescending { it.value }
                .take(limit)
                .map { it.key }
        }
    }
}
