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

    var onItemFocused: ((position: Int) -> Unit)? = null

    
    init {
        setHasStableIds(true)
    }

    override fun getItemId(position: Int): Long {
        val categoryId = categories[position].category_id
        return categoryId?.hashCode()?.toLong() ?: position.toLong()
    }
    
    private var categories: List<XtreamCategory> = categories
    private var selectedPosition = 0
    private var isExpanded = false

    fun setExpanded(expanded: Boolean) {
        if (isExpanded == expanded) return
        isExpanded = expanded
        notifyItemRangeChanged(0, itemCount)
    }
    
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CategorySidebarViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_category_sidebar, parent, false)
        return CategorySidebarViewHolder(view)
    }
    
    override fun onBindViewHolder(holder: CategorySidebarViewHolder, position: Int) {
        val category = categories[position]
        val isSelected = position == selectedPosition
        holder.bind(category, isSelected, isExpanded, onItemFocused) {
            // Update selected position
            val oldPosition = selectedPosition
            selectedPosition = holder.bindingAdapterPosition
            notifyItemChanged(oldPosition)
            notifyItemChanged(selectedPosition)
            
            // Notify callback
            onCategoryClick(category)
        }
    }
    
    override fun getItemCount() = categories.size

    // setExpanded removed as sidebar is always expanded now

    
    fun updateCategories(newCategories: List<XtreamCategory>, selectedCategoryId: String?) {
// ... (omitted diff callback for brevity but keeping logic)
        val diffCallback = object : androidx.recyclerview.widget.DiffUtil.Callback() {
            override fun getOldListSize() = categories.size
            override fun getNewListSize() = newCategories.size
            override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                return categories[oldItemPosition].category_id == newCategories[newItemPosition].category_id
            }
            override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                return categories[oldItemPosition] == newCategories[newItemPosition]
            }
        }
        val diffResult = androidx.recyclerview.widget.DiffUtil.calculateDiff(diffCallback)
        
        categories = newCategories
        selectedPosition = selectedCategoryId
            ?.let { id -> categories.indexOfFirst { it.category_id == id } }
            ?.takeIf { it >= 0 }
            ?: 0
            
        diffResult.dispatchUpdatesTo(this)
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
    private val tvCategoryCode = itemView.findViewById<TextView>(R.id.tv_category_code)
    private val ivCategoryIcon = itemView.findViewById<android.widget.ImageView>(R.id.iv_category_icon)
    private val vActiveIndicator = itemView.findViewById<View>(R.id.v_active_indicator)
    
    fun bind(category: XtreamCategory, isSelected: Boolean, isExpanded: Boolean, onItemFocused: ((Int) -> Unit)?, onClick: () -> Unit) {
        itemView.isFocusable = true
        itemView.isFocusableInTouchMode = true
        itemView.setTag(R.id.tag_category_id, category.category_id)
        
        val rawName = category.category_name ?: "Unknown"
        
        // --- Code Parsing Logic ---
        // 1. Check for pipe pattern |EN|
        val codeRegex = Regex("""(?i)[\|\[\(\s]*(MULTI|EN|FR|IT|ES|DE|RU|TR|AR|NL|PT|PL|UK|US|IN|PK|CA|AU|BR)[\|\]\)\s]*""")
        val match = codeRegex.find(rawName)
        val parsedCode = match?.groupValues?.get(1)?.uppercase(java.util.Locale.ROOT)
        
        val finalCode = when {
            parsedCode != null -> parsedCode
            rawName.contains("Favorite", true) -> "FAV"
            rawName.contains("Netflix", true) -> "NFLX"
            rawName.contains("Amazon", true) -> "PRME"
            rawName.contains("Disney", true) -> "DSNY"
            else -> {
                // Initials fallback: "World Cup Replay" -> "WCR"
                val words = rawName.split(" ", "|", "/", "-").filter { it.isNotBlank() }
                if (words.size >= 2) {
                    words.take(3).mapNotNull { it.firstOrNull()?.uppercaseChar() }.joinToString("")
                } else {
                    rawName.take(3).uppercase(java.util.Locale.ROOT).trim()
                }
            }
        }
        
        tvCategoryCode.text = finalCode
        tvCategoryCode.visibility = View.VISIBLE

        if (isExpanded) {
            // Expanded Mode: Code + Name + Icon
            val titleCasedName = rawName.split(" ").joinToString(" ") { word ->
                word.lowercase().replaceFirstChar { it.uppercase() }
            }
            tvCategoryName.text = titleCasedName
            tvCategoryName.visibility = View.VISIBLE
            ivCategoryIcon.visibility = View.GONE // Keep it clean for now, only code + name
        } else {
            // Collapsed Mode: Code only
            tvCategoryName.visibility = View.GONE
            ivCategoryIcon.visibility = View.GONE
        }
        
        // Map common names to icons
        val lowerName = rawName.lowercase()
        val iconRes = when {
            lowerName.contains("netflix") -> R.drawable.ic_movie
            lowerName.contains("amazon") || lowerName.contains("prime") -> R.drawable.ic_movie
            lowerName.contains("favorite") || lowerName.contains("favourite") -> R.drawable.ic_favorite
            lowerName.contains("action") -> R.drawable.ic_movie
            lowerName.contains("comedy") -> R.drawable.ic_movie
            lowerName.contains("horror") -> R.drawable.ic_movie
            lowerName.contains("kids") || lowerName.contains("family") -> R.drawable.ic_movie
            else -> R.drawable.ic_movie
        }
        ivCategoryIcon.setImageResource(iconRes)
        
        // Active indicator visibility
        vActiveIndicator.visibility = if (isSelected) View.VISIBLE else View.INVISIBLE
        
        // Base styling based on selection
        if (isSelected) {
            // We only show selection background if NOT focused, otherwise focus background takes over
            if (!itemView.hasFocus()) {
                itemView.setBackgroundResource(R.color.sidebar_emerald_soft_bg)
            }
        } else {
            if (!itemView.hasFocus()) {
                itemView.setBackgroundResource(android.R.color.transparent)
            }
        }
        
        itemView.onFocusChangeListener = View.OnFocusChangeListener { v, hasFocus ->
            if (hasFocus) {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) {
                    onItemFocused?.invoke(pos)
                }
                
                v.setBackgroundResource(R.drawable.bg_sidebar_item_focused_glass)
                tvCategoryName.setTextColor(android.graphics.Color.WHITE)
                tvCategoryCode.setTextColor(android.graphics.Color.WHITE)
                tvCategoryName.setTypeface(tvCategoryName.typeface, android.graphics.Typeface.BOLD)
                tvCategoryCode.setTypeface(tvCategoryCode.typeface, android.graphics.Typeface.BOLD)
                tvCategoryName.setShadowLayer(15f, 0f, 0f, android.graphics.Color.parseColor("#4000E5FF"))
                ivCategoryIcon.setColorFilter(android.graphics.Color.WHITE)
                ivCategoryIcon.animate().scaleX(1.15f).scaleY(1.15f).setDuration(200).start()
                tvCategoryName.isSelected = true
            } else {
                if (isSelected) {
                    v.setBackgroundResource(R.color.sidebar_emerald_soft_bg)
                } else {
                    v.setBackgroundResource(android.R.color.transparent)
                }
                
                val textColor = if (isSelected) android.graphics.Color.WHITE else android.graphics.Color.parseColor("#B3FFFFFF")
                tvCategoryName.setTextColor(textColor)
                tvCategoryCode.setTextColor(if (isSelected) android.graphics.Color.WHITE else android.graphics.Color.parseColor("#00E5FF"))
                tvCategoryName.typeface = android.graphics.Typeface.create("sans-serif-medium", if (isSelected) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
                tvCategoryCode.typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD)
                tvCategoryName.setShadowLayer(0f, 0f, 0f, android.graphics.Color.TRANSPARENT)
                
                ivCategoryIcon.setColorFilter(textColor)
                ivCategoryIcon.animate().scaleX(1.0f).scaleY(1.0f).setDuration(200).start()
                tvCategoryName.isSelected = false
            }
        }
        
        itemView.setOnClickListener {
            onClick()
        }
    }
}
