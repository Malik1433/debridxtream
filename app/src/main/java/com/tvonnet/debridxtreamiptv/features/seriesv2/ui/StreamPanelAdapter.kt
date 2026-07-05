package com.tvonnet.debridxtreamiptv.features.seriesv2.ui

import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.tvonnet.debridxtreamiptv.databinding.ItemIptvStreamGroupBinding
import com.tvonnet.debridxtreamiptv.databinding.ItemIptvStreamRowBinding
import com.tvonnet.debridxtreamiptv.features.seriesv2.ui.model.IptvStreamGroup
import com.tvonnet.debridxtreamiptv.features.seriesv2.ui.model.IptvStreamOption
import com.tvonnet.debridxtreamiptv.features.seriesv2.ui.model.StreamQuality

/**
 * Renders the grouped "SELECT STREAM" list: a category header followed by its
 * stream rows, flattened into a single RecyclerView with two view types.
 */
class StreamPanelAdapter(
    private val onStreamClick: (IptvStreamOption) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private sealed interface Item {
        data class Header(val group: IptvStreamGroup) : Item
        data class Row(val option: IptvStreamOption) : Item
    }

    private var items: List<Item> = emptyList()
    private var selectedStreamId: String? = null

    fun submit(groups: List<IptvStreamGroup>, selectedId: String?) {
        val newItems = buildList {
            groups.forEach { g ->
                add(Item.Header(g))
                g.streams.forEach { add(Item.Row(it)) }
            }
        }
        val prevSelected = selectedStreamId
        selectedStreamId = selectedId
        if (newItems != items) {
            items = newItems
            notifyDataSetChanged()
        } else if (prevSelected != selectedId) {
            // Selection-only change: rebind just the affected rows so D-pad focus
            // survives (a full reload would drop it mid-resolve).
            items.forEachIndexed { i, item ->
                if (item is Item.Row &&
                    (item.option.streamId == prevSelected || item.option.streamId == selectedId)
                ) notifyItemChanged(i)
            }
        }
    }

    override fun getItemCount(): Int = items.size

    override fun getItemViewType(position: Int): Int =
        if (items[position] is Item.Header) TYPE_HEADER else TYPE_ROW

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_HEADER) {
            HeaderVH(ItemIptvStreamGroupBinding.inflate(inflater, parent, false))
        } else {
            RowVH(ItemIptvStreamRowBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = items[position]) {
            is Item.Header -> (holder as HeaderVH).bind(item.group)
            is Item.Row -> (holder as RowVH).bind(item.option)
        }
    }

    /** Position of the first stream row (for initial focus), or -1. */
    fun firstRowPosition(): Int = items.indexOfFirst { it is Item.Row }

    inner class HeaderVH(private val b: ItemIptvStreamGroupBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(g: IptvStreamGroup) {
            val style = tagStyle(g.categoryTag)
            b.tvCatTag.text = g.categoryTag
            b.tvCatTag.setTextColor(style.color)
            b.tvCatTag.background = roundedStroke(style.bg, style.border, dp(3f))
            b.tvCatName.text = g.categoryName
            val n = g.streams.size
            b.tvCatCount.text = "$n ${if (n == 1) "STREAM" else "STREAMS"}"
        }
    }

    inner class RowVH(private val b: ItemIptvStreamRowBinding) : RecyclerView.ViewHolder(b.root) {
        init {
            b.root.setOnClickListener {
                (items.getOrNull(bindingAdapterPosition) as? Item.Row)?.let { onStreamClick(it.option) }
            }
        }

        fun bind(option: IptvStreamOption) {
            val ctx = b.root.context

            // Provider chip = category/language tag with its gradient
            val g = tagGradient(option.categoryTag)
            b.tvProvider.text = option.categoryTag
            b.tvProvider.background = gradientPill(g.first, g.second, dp(5f))
            b.tvProvider.setTextColor(0xFF0E0D15.toInt())

            // Language chips
            b.layoutLangs.removeAllViews()
            option.languages.take(2).forEach { lang ->
                b.layoutLangs.addView(langChip(ctx, "${lang.flag} ${lang.code}"))
            }

            b.tvName.text = option.name

            // Quality badge
            b.tvQuality.text = option.quality.label
            when (option.quality) {
                StreamQuality.UHD_4K -> {
                    b.tvQuality.background = gradientPill(0xFFB8860B.toInt(), 0xFFD4AF37.toInt(), dp(4f))
                    b.tvQuality.setTextColor(0xFF000000.toInt())
                }
                StreamQuality.FHD_1080 -> {
                    b.tvQuality.background = gradientPill(0xFF1A5FB4.toInt(), 0xFF3584E4.toInt(), dp(4f))
                    b.tvQuality.setTextColor(0xFFFFFFFF.toInt())
                }
                else -> {
                    b.tvQuality.background = solidPill(0x14FFFFFF, dp(4f))
                    b.tvQuality.setTextColor(0xFFFFFFFF.toInt())
                }
            }

            // Container badge (unknown until the stream is resolved)
            if (option.container.isBlank()) {
                b.tvContainer.visibility = View.GONE
            } else {
                b.tvContainer.visibility = View.VISIBLE
                b.tvContainer.text = option.container
                val containerColor = when (option.container.uppercase()) {
                    "MP4" -> 0x2400F0FF
                    "MKV" -> 0x387C3AED
                    else -> 0x14FFFFFF
                }
                b.tvContainer.background = solidPill(containerColor, dp(4f))
            }

            val isSelected = option.streamId == selectedStreamId
            b.root.isSelected = isSelected
            b.ivPlay.setColorFilter(if (isSelected) 0xFF00F0FF.toInt() else 0xFF64748B.toInt())
        }
    }

    // ── styling helpers ────────────────────────────────────────────────────────

    private data class TagStyle(val color: Int, val bg: Int, val border: Int)

    private fun tagStyle(tag: String): TagStyle = when (tag.uppercase()) {
        "MULTI" -> TagStyle(0xFF00F0FF.toInt(), 0x1F00F0FF, 0x5200F0FF)
        "EN" -> TagStyle(0xFF3584E4.toInt(), 0x293584E4, 0x613584E4)
        "AR" -> TagStyle(0xFF00FF88.toInt(), 0x1F00FF88, 0x4D00FF88)
        "FR" -> TagStyle(0xFFA78BFA.toInt(), 0x29A78BFA, 0x61A78BFA)
        "HI" -> TagStyle(0xFFFFAA00.toInt(), 0x1FFFAA00, 0x4DFFAA00)
        else -> TagStyle(0xFF94A3B8.toInt(), 0x0DFFFFFF, 0x1FFFFFFF)
    }

    private fun tagGradient(tag: String): Pair<Int, Int> = when (tag.uppercase()) {
        "MULTI" -> 0xFF00B8D4.toInt() to 0xFF00F0FF.toInt()
        "EN" -> 0xFF7C3AED.toInt() to 0xFFA78BFA.toInt()
        "AR" -> 0xFF00CC6A.toInt() to 0xFF00FF88.toInt()
        "FR" -> 0xFF7C3AED.toInt() to 0xFFA78BFA.toInt()
        "HI" -> 0xFFE68A00.toInt() to 0xFFFFAA00.toInt()
        else -> 0xFF334155.toInt() to 0xFF64748B.toInt()
    }

    private fun gradientPill(start: Int, end: Int, radius: Float): GradientDrawable =
        GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(start, end)).apply {
            cornerRadius = radius
        }

    private fun solidPill(color: Int, radius: Float): GradientDrawable =
        GradientDrawable().apply { setColor(color); cornerRadius = radius }

    private fun roundedStroke(fill: Int, stroke: Int, radius: Float): GradientDrawable =
        GradientDrawable().apply {
            setColor(fill)
            cornerRadius = radius
            setStroke(dp(1f).toInt(), stroke)
        }

    private fun langChip(ctx: android.content.Context, text: String): View {
        return TextView(ctx).apply {
            this.text = text
            setTextColor(0xFF94A3B8.toInt())
            textSize = 5.5f
            typeface = Typeface.MONOSPACE
            gravity = Gravity.CENTER
            setPadding(dp(4f).toInt(), dp(2f).toInt(), dp(4f).toInt(), dp(2f).toInt())
            background = roundedStroke(0x0AFFFFFF, 0x12FFFFFF, dp(3f))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(3f).toInt() }
        }
    }

    private fun dp(v: Float): Float =
        v * android.content.res.Resources.getSystem().displayMetrics.density

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_ROW = 1
    }
}
