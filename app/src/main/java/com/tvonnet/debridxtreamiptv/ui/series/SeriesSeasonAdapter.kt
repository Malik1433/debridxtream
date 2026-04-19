package com.tvonnet.debridxtreamiptv.ui.series

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.tvonnet.debridxtreamiptv.R

class SeriesSeasonAdapter(
    private val onSeasonSelected: (SeasonUiModel) -> Unit,
    private val onSeasonFocused: (SeasonUiModel) -> Unit
) : ListAdapter<SeasonUiModel, SeriesSeasonAdapter.SeasonViewHolder>(SeasonDiffCallback()) {
    
    init {
        setHasStableIds(true)
    }

    override fun getItemId(position: Int): Long {
        return getItem(position).key.hashCode().toLong()
    }

    private var selectedKey: String? = null

    fun submitList(list: List<SeasonUiModel>, initialSelectedKey: String?) {
        selectedKey = initialSelectedKey ?: selectedKey
        super.submitList(list)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SeasonViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_season_chip, parent, false)
        return SeasonViewHolder(view)    }

    override fun onBindViewHolder(holder: SeasonViewHolder, position: Int) {
        val item = getItem(position)
        val isSelected = item.key == selectedKey
        holder.bind(item, isSelected)
    }

    fun setSelectedSeason(key: String?) {
        val oldKey = selectedKey
        selectedKey = key
        
        // Only notify if the selection actually changed to avoid unnecessary rebinds
        if (oldKey != key) {
            val currentList = currentList
            val oldIndex = currentList.indexOfFirst { it.key == oldKey }
            val newIndex = currentList.indexOfFirst { it.key == key }
            
            if (oldIndex != -1) notifyItemChanged(oldIndex)
            if (newIndex != -1) notifyItemChanged(newIndex)
        }
    }

    inner class SeasonViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvSeasonName: TextView = itemView.findViewById(R.id.tv_season_name)

        fun bind(item: SeasonUiModel, isSelected: Boolean) {
            tvSeasonName.text = item.displayName
            tvSeasonName.isSelected = isSelected
            itemView.isSelected = isSelected

            itemView.setOnClickListener {
                if (selectedKey != item.key) {
                    setSelectedSeason(item.key)
                }
                onSeasonSelected(item)
            }

            itemView.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) {
                    // Update internal state but DO NOT call notifyItemChanged here
                    // calling notifyItemChanged while focus is moving causes jumps/loss of focus
                    selectedKey = item.key
                    
                    // Manually update view state for immediate feedback without rebind
                    // The previous selected item will lose its 'selected' look when it is eventually rebound
                    // or we can iterate visible views to clear selection if needed, 
                    // but usually focus drawable is enough for navigation feedback.
                    // For now, we rely on the focus state drawable for the "active" look.
                    
                    onSeasonFocused(item)
                }
            }
        }
    }

    class SeasonDiffCallback : DiffUtil.ItemCallback<SeasonUiModel>() {
        override fun areItemsTheSame(oldItem: SeasonUiModel, newItem: SeasonUiModel): Boolean {
            return oldItem.key == newItem.key
        }

        override fun areContentsTheSame(oldItem: SeasonUiModel, newItem: SeasonUiModel): Boolean {
            return oldItem == newItem
        }
    }
}

