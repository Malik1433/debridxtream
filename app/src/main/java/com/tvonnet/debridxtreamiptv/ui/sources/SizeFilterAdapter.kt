package com.tvonnet.debridxtreamiptv.ui.sources

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.tvonnet.debridxtreamiptv.databinding.ItemFilterChipBinding

class SizeFilterAdapter(
    private val onOptionSelected: (SizeFilterOption) -> Unit
) : ListAdapter<SizeFilterOption, SizeFilterAdapter.SizeFilterViewHolder>(DiffCallback) {

    private var selectedOption: SizeFilterOption? = null

    fun updateSelection(option: SizeFilterOption?) {
        selectedOption = option
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SizeFilterViewHolder {
        val binding = ItemFilterChipBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return SizeFilterViewHolder(binding)
    }

    override fun onBindViewHolder(holder: SizeFilterViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class SizeFilterViewHolder(
        private val binding: ItemFilterChipBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(option: SizeFilterOption) {
            binding.tvFilterLabel.text = option.label
            val isSelected = option == selectedOption
            binding.root.isSelected = isSelected

            binding.root.setOnClickListener {
                onOptionSelected(option)
            }

            binding.root.setOnFocusChangeListener { _, hasFocus ->
                binding.root.isSelected = hasFocus || isSelected
            }
        }
    }

    companion object {
        private val DiffCallback = object : DiffUtil.ItemCallback<SizeFilterOption>() {
            override fun areItemsTheSame(oldItem: SizeFilterOption, newItem: SizeFilterOption): Boolean {
                return oldItem.label == newItem.label
            }

            override fun areContentsTheSame(oldItem: SizeFilterOption, newItem: SizeFilterOption): Boolean {
                return oldItem == newItem
            }
        }
    }
}
