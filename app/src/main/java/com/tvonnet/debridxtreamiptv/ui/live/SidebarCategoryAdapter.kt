package com.tvonnet.debridxtreamiptv.ui.live

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.tvonnet.debridxtreamiptv.R
import com.tvonnet.debridxtreamiptv.data.model.XtreamCategory

/**
 * Categories for the Live TV v2 header strip: a horizontal row of chips, each a label plus a
 * channel-count badge. (This replaced the old vertical sidebar rows with flags/icons; the
 * adapter API is unchanged so LiveFragment's selection and focus-memory keep working.)
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
            .inflate(R.layout.item_livev2_category_chip, parent, false)
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
    private val tvCategoryName = itemView.findViewById<TextView>(R.id.tv_chip_label)
    private val tvChannelCount = itemView.findViewById<TextView>(R.id.tv_chip_count)
    private val accentDot = itemView.findViewById<View>(R.id.view_chip_accent)

    fun bind(
        category: XtreamCategory,
        channelCount: Int?,
        isSelected: Boolean,
        onClick: (String?) -> Unit
    ) {
        val context = itemView.context
        val categoryName = category.category_name?.takeIf { it.isNotBlank() }
            ?: context.getString(R.string.live_category_placeholder)

        tvCategoryName.text = categoryName
        // Chips show the bare count; a dash until the count for this category is known.
        tvChannelCount.text = channelCount?.toString() ?: context.getString(R.string.livev2_no_guide_dash)

        // Per-category accent dot for fast visual scanning. Selected chips go full cyan (matches
        // the label/count colour), so the dot doesn't fight the selected treatment.
        val accent = if (isSelected) {
            ContextCompat.getColor(context, R.color.stremio_cyan)
        } else {
            CategoryAccents.colorFor(categoryName)
        }
        accentDot.backgroundTintList = android.content.res.ColorStateList.valueOf(accent)

        // The chip background selector carries the selected/focused states; the label and count
        // only need their colours flipped.
        itemView.isSelected = isSelected
        CategoryWash.apply(
            itemView, categoryName, isSelected, R.drawable.livev2_chip_bg, cornerRadiusDp = 15f
        )
        tvCategoryName.setTextColor(
            ContextCompat.getColor(
                context,
                if (isSelected) R.color.stremio_cyan else R.color.stremio_text_secondary
            )
        )
        tvChannelCount.setTextColor(
            ContextCompat.getColor(
                context,
                if (isSelected) R.color.stremio_cyan else R.color.stremio_text_muted
            )
        )

        itemView.contentDescription = context.getString(
            R.string.live_sidebar_item_description,
            categoryName,
            channelCount?.toString().orEmpty()
        )
        itemView.setOnClickListener { onClick(category.category_id) }
    }

}
