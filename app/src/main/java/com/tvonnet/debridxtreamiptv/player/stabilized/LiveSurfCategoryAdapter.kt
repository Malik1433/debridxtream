package com.tvonnet.debridxtreamiptv.player.stabilized

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.imageview.ShapeableImageView
import com.tvonnet.debridxtreamiptv.R
import com.tvonnet.debridxtreamiptv.data.model.XtreamCategory
import com.tvonnet.debridxtreamiptv.util.CategoryFlagResolver

/**
 * Rich category rows for the Live Player v2 side-by-side picker (second-LEFT):
 * flag/icon badge + label + channel count + active chevron. Mirrors surf-row styling.
 */
class LiveSurfCategoryAdapter(
    private val onCategoryClick: (XtreamCategory) -> Unit
) : RecyclerView.Adapter<LiveSurfCategoryAdapter.Holder>() {

    private var categories: List<XtreamCategory> = emptyList()
    private var selectedCategoryId: String? = null
    private var counts: Map<String, Int> = emptyMap()

    fun submit(categories: List<XtreamCategory>, selectedCategoryId: String?, counts: Map<String, Int>) {
        this.categories = categories
        this.selectedCategoryId = selectedCategoryId
        this.counts = counts
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = categories.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_live_surf_category, parent, false)
        return Holder(view)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(categories[position])

    inner class Holder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val accent: View = itemView.findViewById(R.id.cat_accent)
        private val flag: ShapeableImageView = itemView.findViewById(R.id.cat_flag)
        private val abbr: TextView = itemView.findViewById(R.id.cat_abbr)
        private val name: TextView = itemView.findViewById(R.id.cat_name)
        private val count: TextView = itemView.findViewById(R.id.cat_count)
        private val chevron: ImageView = itemView.findViewById(R.id.cat_chevron)

        fun bind(category: XtreamCategory) {
            val ctx = itemView.context
            val isSelected = category.category_id != null && category.category_id == selectedCategoryId
            val rawName = category.category_name ?: "Unknown"

            name.text = rawName

            // FIX 2: show a country flag when the category name maps to one;
            // otherwise fall back to a themed emoji / short text badge — never a
            // per-category hashed color block.
            val flagRes = CategoryFlagResolver.resolveFlagRes(rawName)
            if (flagRes != null) {
                flag.setImageResource(flagRes)
                flag.isVisible = true
                abbr.isVisible = false
            } else {
                flag.isVisible = false
                abbr.isVisible = true
                abbr.text = CategoryFlagResolver.resolveCategoryIcon(rawName)
            }

            val c = category.category_id?.let { counts[it] }
            if (c != null) {
                count.isVisible = true
                count.text = if (c == 1) "1 channel" else "$c channels"
            } else {
                count.isVisible = false
            }

            chevron.isVisible = isSelected
            itemView.isActivated = isSelected
            // Selection cue only (FIX 2 — no more per-category color identity).
            accent.setBackgroundResource(
                if (isSelected) R.color.neon_cyan else android.R.color.transparent
            )
            name.setTextColor(
                ContextCompat.getColor(ctx, if (isSelected) R.color.neon_cyan else R.color.live_osd_text_primary)
            )

            itemView.setOnClickListener { onCategoryClick(category) }
        }
    }
}
