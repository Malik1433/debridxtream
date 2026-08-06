package com.tvonnet.debridxtreamiptv.ui.vod

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.tvonnet.debridxtreamiptv.R
import com.tvonnet.debridxtreamiptv.data.model.XtreamCategory

class CategorySidebarAdapter(
    categories: List<XtreamCategory>,
    private val layoutRes: Int = R.layout.item_vod_category,
    private val activeBgRes: Int = R.drawable.bg_vod_category_active,
    private val accentColor: Int = Color.parseColor("#00F0FF"),
    /** Optional per-category count for the sidebar badge; null hides that row's count. */
    private val countProvider: ((String?) -> Int?)? = null,
    private val onCategoryClick: (XtreamCategory) -> Unit
) : RecyclerView.Adapter<CategorySidebarAdapter.ViewHolder>() {

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

    fun setExpanded(expanded: Boolean) {
        // Sidebar is always expanded in new design — no-op
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(layoutRes, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val category = categories[position]
        val isSelected = position == selectedPosition
        val row = ViewHolder.RowBind(
            category = category,
            isSelected = isSelected,
            activeBgRes = activeBgRes,
            accentColor = accentColor,
            count = countProvider?.invoke(category.category_id),
            onItemFocused = onItemFocused
        ) {
            val oldPosition = selectedPosition
            selectedPosition = holder.bindingAdapterPosition
            notifyItemChanged(oldPosition)
            notifyItemChanged(selectedPosition)
            onCategoryClick(category)
        }
        holder.bind(row)
    }

    override fun getItemCount() = categories.size

    fun updateCategories(newCategories: List<XtreamCategory>, selectedCategoryId: String?) {
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

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvCategoryName = itemView.findViewById<TextView>(R.id.tv_category_name)
        private val tvCategoryCount = itemView.findViewById<TextView>(R.id.tv_category_count)
        private val vActiveIndicator = itemView.findViewById<View>(R.id.v_active_indicator)

        /** One row's full render payload — bundled so bind() has a single parameter. */
        data class RowBind(
            val category: XtreamCategory,
            val isSelected: Boolean,
            val activeBgRes: Int,
            val accentColor: Int,
            val count: Int?,
            val onItemFocused: ((Int) -> Unit)?,
            val onClick: () -> Unit
        )

        fun bind(row: RowBind) {
            val (category, isSelected, activeBgRes, accentColor, count, onItemFocused, onClick) = row
            itemView.isFocusable = true
            itemView.isFocusableInTouchMode = itemView.resources.getBoolean(R.bool.ui_uses_dpad_focus)
            itemView.setTag(R.id.tag_category_id, category.category_id)

            // Show the category name AS-IS (default) — do NOT strip provider/country
            // tags like "|AR|", "|PH|", "|MULTI|". The user needs the country code to
            // tell which country a category belongs to.
            tvCategoryName.text = category.category_name?.takeIf { it.isNotBlank() } ?: "Unknown"

            // Count badge — show when a count is available, hide gracefully otherwise.
            if (count != null && count >= 0) {
                tvCategoryCount?.text = count.toString()
                tvCategoryCount?.visibility = View.VISIBLE
            } else {
                tvCategoryCount?.visibility = View.GONE
            }

            // Active indicator
            vActiveIndicator.visibility = if (isSelected) View.VISIBLE else View.INVISIBLE

            // Styling
            if (isSelected) {
                com.tvonnet.debridxtreamiptv.ui.live.CategoryWash.apply(
                    itemView, category.category_name, true, activeBgRes
                )
                tvCategoryName.setTextColor(Color.parseColor("#F1F5F9"))
                tvCategoryCount?.setTextColor(accentColor)
            } else {
                itemView.setBackgroundResource(android.R.color.transparent)
                tvCategoryName.setTextColor(Color.parseColor("#64748B"))
                tvCategoryCount?.setTextColor(Color.parseColor("#3D4F60"))
            }

            itemView.onFocusChangeListener = View.OnFocusChangeListener { v, hasFocus ->
                if (hasFocus) {
                    val pos = bindingAdapterPosition
                    if (pos != RecyclerView.NO_POSITION) {
                        onItemFocused?.invoke(pos)
                    }
                    v.setBackgroundResource(activeBgRes)
                    tvCategoryName.setTextColor(Color.WHITE)
                    tvCategoryCount?.setTextColor(accentColor)
                } else {
                    if (isSelected) {
                        v.setBackgroundResource(activeBgRes)
                        tvCategoryName.setTextColor(Color.parseColor("#F1F5F9"))
                        tvCategoryCount?.setTextColor(accentColor)
                    } else {
                        v.setBackgroundResource(android.R.color.transparent)
                        tvCategoryName.setTextColor(Color.parseColor("#64748B"))
                        tvCategoryCount?.setTextColor(Color.parseColor("#3D4F60"))
                    }
                }
            }

            itemView.setOnClickListener { onClick() }
        }
    }
}
