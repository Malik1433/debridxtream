package com.tvonnet.debridxtreamiptv.player.stabilized

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.tvonnet.debridxtreamiptv.R
import com.tvonnet.debridxtreamiptv.data.model.XtreamCategory
import java.util.Locale

/**
 * Rich category rows for the Live Player v2 side-by-side picker (second-LEFT):
 * icon badge + label + channel count + active chevron. Mirrors surf-row styling.
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
        private val icon: FrameLayout = itemView.findViewById(R.id.cat_icon)
        private val abbr: TextView = itemView.findViewById(R.id.cat_abbr)
        private val name: TextView = itemView.findViewById(R.id.cat_name)
        private val count: TextView = itemView.findViewById(R.id.cat_count)
        private val chevron: ImageView = itemView.findViewById(R.id.cat_chevron)

        fun bind(category: XtreamCategory) {
            val ctx = itemView.context
            val density = ctx.resources.displayMetrics.density
            val isSelected = category.category_id != null && category.category_id == selectedCategoryId
            val rawName = category.category_name ?: "Unknown"

            name.text = rawName
            abbr.text = deriveCode(rawName)
            icon.background = LivePlayerOsdManager.channelTileGradient(rawName, 6.5f * density)
            accent.setBackgroundColor(LivePlayerOsdManager.accentColor(rawName))

            val c = category.category_id?.let { counts[it] }
            if (c != null) {
                count.isVisible = true
                count.text = if (c == 1) "1 channel" else "$c channels"
            } else {
                count.isVisible = false
            }

            chevron.isVisible = isSelected
            itemView.isActivated = isSelected
            name.setTextColor(
                ContextCompat.getColor(ctx, if (isSelected) R.color.neon_cyan else R.color.live_osd_text_primary)
            )

            itemView.setOnClickListener { onCategoryClick(category) }
        }
    }

    companion object {
        private val CODE_REGEX = Regex(
            """(?i)[\|\[\(\s]*(MULTI|EN|FR|IT|ES|DE|RU|TR|AR|NL|PT|PL|UK|US|IN|PK|CA|AU|BR)[\|\]\)\s]*"""
        )

        /** Short badge code, same heuristic as the legacy BrowserCategoryAdapter. */
        fun deriveCode(rawName: String): String {
            CODE_REGEX.find(rawName)?.groupValues?.get(1)?.let { return it.uppercase(Locale.ROOT) }
            return when {
                rawName.contains("Favorite", true) -> "FAV"
                rawName.contains("Netflix", true) -> "NFLX"
                rawName.contains("Amazon", true) -> "PRME"
                rawName.contains("Disney", true) -> "DSNY"
                else -> {
                    val words = rawName.split(" ", "|", "/", "-").filter { it.isNotBlank() }
                    if (words.size >= 2) {
                        words.take(3).mapNotNull { it.firstOrNull()?.uppercaseChar() }.joinToString("")
                    } else {
                        rawName.take(4).uppercase(Locale.ROOT).trim()
                    }
                }
            }
        }
    }
}
