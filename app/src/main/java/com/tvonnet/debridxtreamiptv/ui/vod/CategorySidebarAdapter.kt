package com.tvonnet.debridxtreamiptv.ui.vod

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.tvonnet.debridxtreamiptv.R
import com.tvonnet.debridxtreamiptv.data.model.XtreamCategory

class CategorySidebarAdapter(
    categories: List<XtreamCategory>,
    private val onCategoryClick: (XtreamCategory) -> Unit
) : RecyclerView.Adapter<CategorySidebarViewHolder>() {
    
    private var categories: List<XtreamCategory> = categories
    private var selectedPosition = 0
    
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CategorySidebarViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_category_sidebar, parent, false)
        return CategorySidebarViewHolder(view)
    }
    
    override fun onBindViewHolder(holder: CategorySidebarViewHolder, position: Int) {
        val category = categories[position]
        val isSelected = position == selectedPosition
        holder.bind(category, isSelected) {
            // Update selected position
            val oldPosition = selectedPosition
            selectedPosition = position
            notifyItemChanged(oldPosition)
            notifyItemChanged(selectedPosition)
            
            // Notify callback
            onCategoryClick(category)
        }
    }
    
    override fun getItemCount() = categories.size
    
    fun updateCategories(newCategories: List<XtreamCategory>, selectedCategoryId: String?) {
        categories = newCategories
        selectedPosition = selectedCategoryId
            ?.let { id -> categories.indexOfFirst { it.category_id == id } }
            ?.takeIf { it >= 0 }
            ?: 0
        notifyDataSetChanged()
    }

    fun setSelectedById(categoryId: String?) {
        val index = categoryId?.let { id -> categories.indexOfFirst { it.category_id == id } } ?: -1
        if (index >= 0) {
            setSelected(index)
        }
    }

    fun setSelected(position: Int) {
        if (categories.isEmpty()) {
            selectedPosition = 0
            return
        }
        val clampedPosition = position.coerceIn(0, categories.lastIndex)
        if (clampedPosition == selectedPosition) return
        val oldPosition = selectedPosition
        selectedPosition = clampedPosition
        if (oldPosition in categories.indices) notifyItemChanged(oldPosition)
        if (selectedPosition in categories.indices) notifyItemChanged(selectedPosition)
    }
}

class CategorySidebarViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
    private val tvCategoryName = itemView.findViewById<TextView>(R.id.tv_category_name)
    
    fun bind(category: XtreamCategory, isSelected: Boolean, onClick: () -> Unit) {
        itemView.setTag(R.id.tag_category_id, category.category_id)
        tvCategoryName.text = category.category_name ?: "Unknown Category"
        
        // Update selected state
        itemView.isSelected = isSelected
        val activeIndicator = itemView.findViewById<View>(R.id.v_active_indicator)
        
        // Update styling based on selection
        if (isSelected) {
            tvCategoryName.setTextColor(itemView.context.getColor(android.R.color.white))
            tvCategoryName.alpha = 1.0f
            tvCategoryName.setTypeface(null, android.graphics.Typeface.BOLD)
            activeIndicator.visibility = View.VISIBLE
            itemView.setBackgroundResource(R.color.primary_10_percent) // Need to ensure this color exists or use a drawable
            activeIndicator.alpha = 1f
        } else {
            tvCategoryName.setTextColor(itemView.context.getColor(android.R.color.white))
            tvCategoryName.alpha = 0.6f
            tvCategoryName.setTypeface(null, android.graphics.Typeface.NORMAL)
            activeIndicator.visibility = View.INVISIBLE
            itemView.setBackgroundResource(android.R.color.transparent)
            activeIndicator.alpha = 0f
        }
        
        itemView.setOnClickListener {
            onClick()
        }
    }
}
