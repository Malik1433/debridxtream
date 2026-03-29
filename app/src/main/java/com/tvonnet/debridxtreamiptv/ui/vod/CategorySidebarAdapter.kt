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
    private val ivCategoryIcon = itemView.findViewById<android.widget.ImageView>(R.id.iv_category_icon)
    
    fun bind(category: XtreamCategory, isSelected: Boolean, onClick: () -> Unit) {
        itemView.setTag(R.id.tag_category_id, category.category_id)
        
        val rawName = category.category_name ?: "Unknown"
        // Convert to Title Case
        val titleCasedName = rawName.split(" ").joinToString(" ") { word ->
            word.lowercase().replaceFirstChar { it.uppercase() }
        }
        tvCategoryName.text = titleCasedName
        
        // Map common names to icons
        val lowerName = rawName.lowercase()
        val iconRes = when {
            lowerName.contains("netflix") -> R.drawable.ic_movie // Replace with custom if available
            lowerName.contains("amazon") || lowerName.contains("prime") -> R.drawable.ic_movie
            lowerName.contains("favorite") || lowerName.contains("favourite") -> R.drawable.ic_favorite
            lowerName.contains("action") -> R.drawable.ic_movie
            lowerName.contains("comedy") -> R.drawable.ic_movie
            lowerName.contains("horror") -> R.drawable.ic_movie
            lowerName.contains("kids") || lowerName.contains("family") -> R.drawable.ic_movie
            else -> R.drawable.ic_movie
        }
        ivCategoryIcon.setImageResource(iconRes)
        
        val activeIndicator = itemView.findViewById<View>(R.id.v_active_indicator)
        
        // Base styling based on selection
        if (isSelected) {
            activeIndicator.visibility = View.VISIBLE
            // We only show selection background if NOT focused, otherwise focus background takes over
            if (!itemView.hasFocus()) {
                itemView.setBackgroundResource(R.color.primary_10_percent)
            }
        } else {
            activeIndicator.visibility = View.INVISIBLE
            if (!itemView.hasFocus()) {
                itemView.setBackgroundResource(android.R.color.transparent)
            }
        }
        
        val previousListener = itemView.onFocusChangeListener
        itemView.onFocusChangeListener = View.OnFocusChangeListener { v, hasFocus ->
            previousListener?.onFocusChange(v, hasFocus)
            if (hasFocus) {
                // FocusGlintHelper.attach(v) // Removed as per user request to avoid glint bugs
                v.setBackgroundResource(R.drawable.bg_sidebar_item_focused_glass)
                tvCategoryName.setTextColor(android.graphics.Color.WHITE)
                tvCategoryName.setTypeface(tvCategoryName.typeface, android.graphics.Typeface.BOLD)
                // XML stateListAnimator handles scaleX/Y and translationZ
                tvCategoryName.setShadowLayer(15f, 0f, 0f, android.graphics.Color.parseColor("#4000D4FF"))
                ivCategoryIcon.setColorFilter(android.graphics.Color.WHITE)
                tvCategoryName.isSelected = true
            } else {
                if (isSelected) {
                    v.setBackgroundResource(R.color.primary_10_percent)
                } else {
                    v.setBackgroundResource(android.R.color.transparent)
                }
                
                tvCategoryName.setTextColor(android.graphics.Color.parseColor("#B3FFFFFF"))
                tvCategoryName.setTypeface(android.graphics.Typeface.create("sans-serif-condensed", android.graphics.Typeface.NORMAL), android.graphics.Typeface.NORMAL)
                // XML stateListAnimator handles scaleX/Y and translationZ
                tvCategoryName.setShadowLayer(0f, 0f, 0f, android.graphics.Color.TRANSPARENT)
                
                ivCategoryIcon.setColorFilter(android.graphics.Color.parseColor("#B3FFFFFF"))
                tvCategoryName.isSelected = false
            }
        }
        
        itemView.setOnClickListener {
            onClick()
        }
    }
}
