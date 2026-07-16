package com.tvonnet.debridxtreamiptv.ui.live

import android.graphics.Typeface
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.tvonnet.debridxtreamiptv.R
import com.tvonnet.debridxtreamiptv.data.model.XtreamCategory
import com.tvonnet.debridxtreamiptv.util.CategoryFlagResolver

/**
 * Adapter for the vertical sidebar in the Live TV 3-column layout.
 * Shows category icon/flags, titles, and live channel counts.
 */
class SidebarCategoryAdapter(
    categories: List<XtreamCategory>,
    channelCounts: Map<String, Int>,
    initialSelectedCategoryId: String?,
    private val onCategoryClick: (String) -> Unit,
    private val onCategoryFocused: ((String, Int) -> Unit)? = null
) : RecyclerView.Adapter<SidebarCategoryViewHolder>() {
    
    init {
        setHasStableIds(true)
    }

    override fun getItemId(position: Int): Long {
        val categoryId = categories[position].category_id
        return categoryId?.hashCode()?.toLong() ?: position.toLong()
    }

    private val categories: MutableList<XtreamCategory> = categories.toMutableList()
    private var selectedCategoryId: String? = initialSelectedCategoryId
    private var channelCounts: Map<String, Int> = channelCounts

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SidebarCategoryViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_sidebar_category, parent, false)
        return SidebarCategoryViewHolder(view)
    }

    override fun onBindViewHolder(holder: SidebarCategoryViewHolder, position: Int) {
        val category = categories[position]
        val categoryId = category.category_id
        val isSelected = categoryId != null && categoryId == selectedCategoryId
        val count = categoryId?.let { channelCounts[it] }
        holder.bind(category, count, isSelected) { clickedId ->
            val adapterPosition = holder.bindingAdapterPosition
            if (adapterPosition == RecyclerView.NO_POSITION || clickedId.isNullOrBlank()) return@bind
            if (clickedId != selectedCategoryId) {
                val previousId = selectedCategoryId
                selectedCategoryId = clickedId
                previousId?.let { previous ->
                    val previousIndex = categories.indexOfFirst { it.category_id == previous }
                    if (previousIndex != -1) notifyItemChanged(previousIndex)
                }
                notifyItemChanged(adapterPosition)
            }
            onCategoryClick(clickedId)
        }

        holder.itemView.onFocusChangeListener = android.view.View.OnFocusChangeListener { _, hasFocus ->
            if (hasFocus && !categoryId.isNullOrBlank()) {
                onCategoryFocused?.invoke(categoryId, position)
            }
        }
    }

    override fun getItemCount() = categories.size

    fun updateCategories(newCategories: List<XtreamCategory>, selectedCategoryId: String?) {
        val oldSize = categories.size
        val newSize = newCategories.size
        
        categories.clear()
        categories.addAll(newCategories)
        this.selectedCategoryId = selectedCategoryId
        
        if (oldSize == newSize) {
            notifyItemRangeChanged(0, newSize)
        } else {
            notifyDataSetChanged() // Fallback if size changed, but prioritized size match
        }
    }

    fun setSelectedCategory(id: String?) {
        val old = selectedCategoryId
        if (old == id) return

        selectedCategoryId = id

        val oldIndex = if (old != null) categories.indexOfFirst { it.category_id == old } else -1
        val newIndex = if (id != null) categories.indexOfFirst { it.category_id == id } else -1

        if (oldIndex != -1) notifyItemChanged(oldIndex)
        if (newIndex != -1) notifyItemChanged(newIndex)
    }

    fun updateChannelCounts(newCounts: Map<String, Int>) {
        if (channelCounts == newCounts) return
        val oldCounts = channelCounts
        channelCounts = newCounts

        categories.forEachIndexed { index, category ->
            val categoryId = category.category_id ?: return@forEachIndexed
            if (oldCounts[categoryId] != newCounts[categoryId]) {
                notifyItemChanged(index)
            }
        }
    }
}

class SidebarCategoryViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
    private val iconContainer = itemView.findViewById<View>(R.id.category_icon_container)
    private val ivCategoryFlag = itemView.findViewById<ImageView>(R.id.iv_category_flag)
    private val tvCategoryIcon = itemView.findViewById<TextView>(R.id.tv_category_icon)
    private val tvCategoryName = itemView.findViewById<TextView>(R.id.tv_category_name_sidebar)
    private val tvChannelCount = itemView.findViewById<TextView>(R.id.tv_channel_count_sidebar)

    fun bind(
        category: XtreamCategory,
        channelCount: Int?,
        isSelected: Boolean,
        onClick: (String?) -> Unit
    ) {
        val context = itemView.context
        val categoryName = category.category_name?.takeIf { it.isNotBlank() }
            ?: context.getString(R.string.live_category_placeholder)
        val countLabel = when {
            channelCount == null -> context.getString(R.string.live_channel_count_placeholder)
            else -> context.getString(R.string.live_channel_count_format, channelCount)
        }

        val flagRes = CategoryFlagResolver.resolveFlagRes(categoryName)
        if (flagRes != null) {
            ivCategoryFlag.setImageResource(flagRes)
            ivCategoryFlag.visibility = View.VISIBLE
            tvCategoryIcon.visibility = View.GONE
        } else {
            ivCategoryFlag.visibility = View.GONE
            tvCategoryIcon.visibility = View.VISIBLE
            tvCategoryIcon.text = CategoryFlagResolver.resolveCategoryIcon(categoryName)
        }
        tvCategoryName.text = categoryName
        tvChannelCount.text = countLabel

        val selectedTextColor = ContextCompat.getColor(context, R.color.tv_sidebar_text_selected)
        val normalTextColor = ContextCompat.getColor(context, R.color.tv_sidebar_text_normal)
        val iconNormalColor = ContextCompat.getColor(context, R.color.tv_sidebar_text_normal)
        val nameColor = if (isSelected) selectedTextColor else ContextCompat.getColor(context, R.color.tv_text_primary)
        val countColor = if (isSelected) selectedTextColor else normalTextColor

        tvCategoryName.setTextColor(nameColor)
        tvChannelCount.setTextColor(countColor)
        tvCategoryIcon.setTextColor(if (isSelected) selectedTextColor else iconNormalColor)
        tvCategoryName.setTypeface(tvCategoryName.typeface, if (isSelected) Typeface.BOLD else Typeface.NORMAL)
        itemView.isSelected = isSelected

        val resources = context.resources
        val iconSize = resources.getDimensionPixelSize(
            if (isSelected) R.dimen.live_sidebar_icon_size_selected
            else R.dimen.live_sidebar_icon_size
        )
        val iconParams = iconContainer.layoutParams
        if (iconParams.width != iconSize || iconParams.height != iconSize) {
            iconParams.width = iconSize
            iconParams.height = iconSize
            iconContainer.layoutParams = iconParams
        }
        val iconTextSize = if (isSelected) 20f else 18f
        tvCategoryIcon.setTextSize(TypedValue.COMPLEX_UNIT_SP, iconTextSize)

        val nameSize = if (isSelected) 17f else 15f
        val countSize = if (isSelected) 12f else 11f
        tvCategoryName.setTextSize(TypedValue.COMPLEX_UNIT_SP, nameSize)
        tvChannelCount.setTextSize(TypedValue.COMPLEX_UNIT_SP, countSize)

        itemView.contentDescription = context.getString(
            R.string.live_sidebar_item_description,
            categoryName,
            countLabel
        )
        itemView.setOnClickListener { onClick(category.category_id) }

        itemView.isFocusable = true
        itemView.isFocusableInTouchMode = true

        // Premium 1.1x focus scaling with no-clipping
        itemView.setOnFocusChangeListener { v, hasFocus ->
            if (hasFocus) {
                v.animate()
                    .scaleX(1.1f)
                    .scaleY(1.1f)
                    .setDuration(200)
                    .setInterpolator(android.view.animation.OvershootInterpolator())
                    .start()
                v.z = 10f // Bring to front
            } else {
                v.animate()
                    .scaleX(1.0f)
                    .scaleY(1.0f)
                    .setDuration(200)
                    .start()
                v.z = 0f
            }
        }
    }

}
