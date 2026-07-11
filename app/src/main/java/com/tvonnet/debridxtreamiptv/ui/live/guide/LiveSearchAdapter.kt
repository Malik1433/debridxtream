package com.tvonnet.debridxtreamiptv.ui.live.guide

import android.graphics.Color
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.tvonnet.debridxtreamiptv.R
import com.tvonnet.debridxtreamiptv.util.GlideUtils

/**
 * Flat results list for the guide's unified search overlay: section headers,
 * category rows and channel rows, built programmatically (no per-item XML).
 */
class LiveSearchAdapter(
    private val onCategory: (GuideCategory) -> Unit,
    private val onChannel: (GuideChannel) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    sealed class Row {
        data class Header(val title: String) : Row()
        data class CategoryRow(val category: GuideCategory) : Row()
        data class ChannelRow(val channel: GuideChannel) : Row()
    }

    private val rows = mutableListOf<Row>()

    fun submit(newRows: List<Row>) {
        rows.clear()
        rows.addAll(newRows)
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = rows.size

    override fun getItemViewType(position: Int): Int = when (rows[position]) {
        is Row.Header -> TYPE_HEADER
        is Row.CategoryRow -> TYPE_CATEGORY
        is Row.ChannelRow -> TYPE_CHANNEL
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val ctx = parent.context
        fun dp(v: Int) = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), ctx.resources.displayMetrics
        ).toInt()

        return when (viewType) {
            TYPE_HEADER -> {
                val tv = TextView(ctx).apply {
                    layoutParams = RecyclerView.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                    setPadding(dp(4), dp(14), dp(4), dp(6))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                    setTypeface(typeface, Typeface.BOLD)
                    letterSpacing = 0.08f
                    setTextColor(ContextCompat.getColor(ctx, R.color.epg_text_secondary))
                }
                HeaderVH(tv)
            }
            else -> {
                val focusColor = ContextCompat.getColor(ctx, R.color.epg_chip_active_bg)
                val row = LinearLayout(ctx).apply {
                    layoutParams = RecyclerView.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(10), dp(10), dp(10), dp(10))
                    isFocusable = true
                    isClickable = true
                    setOnFocusChangeListener { v, has ->
                        v.setBackgroundColor(if (has) focusColor else Color.TRANSPARENT)
                    }
                }
                if (viewType == TYPE_CATEGORY) {
                    val name = TextView(ctx).apply {
                        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                        setTextColor(ContextCompat.getColor(ctx, R.color.text_primary_new))
                        setSingleLine(true)
                    }
                    val icon = TextView(ctx).apply {
                        text = "▣"
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                        setTextColor(ContextCompat.getColor(ctx, R.color.epg_cyan))
                        val lp = LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                        ).apply { marginEnd = dp(12) }
                        layoutParams = lp
                    }
                    row.addView(icon)
                    row.addView(name)
                    CategoryVH(row, name)
                } else {
                    val logo = ImageView(ctx).apply {
                        layoutParams = LinearLayout.LayoutParams(dp(56), dp(36)).apply { marginEnd = dp(12) }
                        scaleType = ImageView.ScaleType.FIT_CENTER
                    }
                    val name = TextView(ctx).apply {
                        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                        setTextColor(ContextCompat.getColor(ctx, R.color.text_primary_new))
                        setSingleLine(true)
                    }
                    row.addView(logo)
                    row.addView(name)
                    ChannelVH(row, logo, name)
                }
            }
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is Row.Header -> (holder as HeaderVH).title.text = row.title
            is Row.CategoryRow -> {
                val vh = holder as CategoryVH
                val c = row.category
                vh.name.text = if (c.count >= 0) "${c.name}  (${c.count})" else c.name
                vh.itemView.setOnClickListener { onCategory(c) }
            }
            is Row.ChannelRow -> {
                val vh = holder as ChannelVH
                val ch = row.channel
                vh.name.text = ch.name
                GlideUtils.loadChannelLogo(vh.logo, ch.logoUrl)
                vh.itemView.setOnClickListener { onChannel(ch) }
            }
        }
    }

    private class HeaderVH(val title: TextView) : RecyclerView.ViewHolder(title)
    private class CategoryVH(view: View, val name: TextView) : RecyclerView.ViewHolder(view)
    private class ChannelVH(view: View, val logo: ImageView, val name: TextView) : RecyclerView.ViewHolder(view)

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_CATEGORY = 1
        private const val TYPE_CHANNEL = 2
    }
}
