package com.tvonnet.debridxtreamiptv.ui.settings.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.tvonnet.debridxtreamiptv.databinding.ItemSettingsSelectionBinding
import com.tvonnet.debridxtreamiptv.databinding.ItemSettingsToggleBinding

sealed class SettingItem(val id: String) {
    data class Toggle(
        val key: String,
        val title: String,
        val description: String,
        val isChecked: Boolean,
        val onToggle: (Boolean) -> Unit
    ) : SettingItem(key)

    data class Selection(
        val key: String,
        val title: String,
        val currentValue: String,
        val onClick: () -> Unit
    ) : SettingItem(key)
    
    data class Action(
        val key: String,
        val title: String,
        val description: String,
        val onClick: () -> Unit
    ) : SettingItem(key)
}

class SettingsDetailAdapter : ListAdapter<SettingItem, RecyclerView.ViewHolder>(SettingDiffCallback()) {

    companion object {
        private const val TYPE_TOGGLE = 1
        private const val TYPE_SELECTION = 2
        private const val TYPE_ACTION = 3
    }

    override fun getItemViewType(position: Int): Int {
        return when (getItem(position)) {
            is SettingItem.Toggle -> TYPE_TOGGLE
            is SettingItem.Selection -> TYPE_SELECTION
            is SettingItem.Action -> TYPE_SELECTION // Reuse selection layout for simple actions for now
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_TOGGLE -> ToggleViewHolder(ItemSettingsToggleBinding.inflate(inflater, parent, false))
            TYPE_SELECTION -> SelectionViewHolder(ItemSettingsSelectionBinding.inflate(inflater, parent, false))
            else -> throw IllegalArgumentException("Invalid view type")
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val item = getItem(position)
        when (holder) {
            is ToggleViewHolder -> holder.bind(item as SettingItem.Toggle)
            is SelectionViewHolder -> {
               if (item is SettingItem.Selection) holder.bindSelection(item)
               else if (item is SettingItem.Action) holder.bindAction(item)
            }
        }
    }

    class ToggleViewHolder(private val binding: ItemSettingsToggleBinding) :
        RecyclerView.ViewHolder(binding.root) {
        
        fun bind(item: SettingItem.Toggle) {
            binding.tvTitle.text = item.title
            binding.tvDescription.text = item.description
            binding.switchToggle.isChecked = item.isChecked
            
            // Handle click on the entire item to toggle switch
            binding.root.setOnClickListener {
                val newState = !item.isChecked
                binding.switchToggle.isChecked = newState
                item.onToggle(newState)
            }
            
            // Disable direct interaction with switch to prevent double events?
            // Actually, we usually want the switch to be non-clickable and let the container handle it
            // defined in xml: android:clickable="false"
        }
    }

    class SelectionViewHolder(private val binding: ItemSettingsSelectionBinding) :
        RecyclerView.ViewHolder(binding.root) {
        
        fun bindSelection(item: SettingItem.Selection) {
            binding.tvTitle.text = item.title
            binding.tvStatus.text = item.currentValue
            // For selection, description is essentially the status/current value
            
            binding.root.setOnClickListener {
                item.onClick()
            }
        }

        fun bindAction(item: SettingItem.Action) {
             binding.tvTitle.text = item.title
             binding.tvStatus.text = item.description
             
             binding.root.setOnClickListener {
                 item.onClick()
             }
        }
    }
}

class SettingDiffCallback : DiffUtil.ItemCallback<SettingItem>() {
    override fun areItemsTheSame(oldItem: SettingItem, newItem: SettingItem): Boolean {
        return oldItem.id == newItem.id
    }

    override fun areContentsTheSame(oldItem: SettingItem, newItem: SettingItem): Boolean {
        return oldItem == newItem
    }
}
