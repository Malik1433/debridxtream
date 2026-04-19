package com.tvonnet.debridxtreamiptv.ui.sources

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.tvonnet.debridxtreamiptv.databinding.ItemFilterChipBinding

class FilterChipAdapter<T : Any>(
    private val idProvider: (T) -> String,
    private val labelProvider: (T) -> String,
    private val onOptionSelected: (T) -> Unit
) : ListAdapter<T, FilterChipAdapter<T>.FilterChipViewHolder>(
    object : DiffUtil.ItemCallback<T>() {
        override fun areItemsTheSame(oldItem: T, newItem: T): Boolean {
            return idProvider(oldItem) == idProvider(newItem)
        }

        override fun areContentsTheSame(oldItem: T, newItem: T): Boolean {
            return oldItem == newItem
        }
    }
) {

    private var selectedId: String? = null

    init {
        setHasStableIds(true)
    }

    override fun getItemId(position: Int): Long {
        return idProvider(getItem(position)).hashCode().toLong()
    }

    fun updateSelection(option: T?) {
        val oldId = selectedId
        val newId = option?.let(idProvider)
        if (oldId == newId) return
        
        selectedId = newId
        
        for (i in 0 until itemCount) {
            val item = getItem(i)
            val itemId = idProvider(item)
            if (itemId == oldId || itemId == newId) {
                notifyItemChanged(i)
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FilterChipViewHolder {
        val binding = ItemFilterChipBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return FilterChipViewHolder(binding)
    }

    override fun onBindViewHolder(holder: FilterChipViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    override fun onViewRecycled(holder: FilterChipViewHolder) {
        super.onViewRecycled(holder)
        com.tvonnet.debridxtreamiptv.utils.FocusGlintHelper.forceReset(holder.itemView)
    }

    inner class FilterChipViewHolder(
        private val binding: ItemFilterChipBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(option: T) {
            binding.tvFilterLabel.text = labelProvider(option)
            val optionId = idProvider(option)
            val isSelected = optionId == selectedId
            binding.root.isSelected = isSelected

            binding.root.setOnClickListener {
                onOptionSelected(option)
            }

            binding.root.setOnFocusChangeListener { _, hasFocus ->
                binding.root.isSelected = hasFocus || isSelected
            }
        }
    }

}
