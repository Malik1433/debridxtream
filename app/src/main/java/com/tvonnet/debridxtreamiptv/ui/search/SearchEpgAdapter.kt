package com.tvonnet.debridxtreamiptv.ui.search

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.tvonnet.debridxtreamiptv.R
import com.tvonnet.debridxtreamiptv.data.local.entity.EpgEntity
import com.tvonnet.debridxtreamiptv.utils.MagneticFocusHelper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Adapter for EPG Search Results
 */
class SearchEpgAdapter(
    private val onProgramClick: (EpgEntity) -> Unit
) : ListAdapter<EpgEntity, SearchEpgAdapter.ViewHolder>(DiffCallback()) {
    
    init {
        setHasStableIds(true)
    }
    
    override fun getItemId(position: Int): Long {
        return getItem(position).id
    }
    
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_search_result, parent, false)
        return ViewHolder(view, onProgramClick)
    }
    
    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    override fun onViewRecycled(holder: ViewHolder) {
        super.onViewRecycled(holder)
        com.tvonnet.debridxtreamiptv.utils.FocusGlintHelper.forceReset(holder.itemView)
    }
    
    class ViewHolder(
        itemView: View,
        private val onProgramClick: (EpgEntity) -> Unit
    ) : RecyclerView.ViewHolder(itemView) {
        
        private val ivIcon: ImageView = itemView.findViewById(R.id.iv_icon)
        private val tvName: TextView = itemView.findViewById(R.id.tv_name)
        private val tvType: TextView = itemView.findViewById(R.id.tv_type)
        private val timeFormatter = SimpleDateFormat("HH:mm", Locale.getDefault())

        init {
            itemView.onFocusChangeListener = View.OnFocusChangeListener { v, hasFocus ->
                v.isSelected = hasFocus
                v.isActivated = hasFocus
            }
            com.tvonnet.debridxtreamiptv.utils.FocusGlintHelper.attach(itemView)
        }
        
        fun bind(program: EpgEntity) {
            tvName.text = program.title ?: "Unknown Program"
            
            // Format time range: 14:00 - 15:30
            val start = timeFormatter.format(Date(program.start))
            val stop = timeFormatter.format(Date(program.stop))
            tvType.text = "$start - $stop"
            
            // Placeholder for EPG items (maybe category icon later)
            ivIcon.setImageResource(R.drawable.ic_live_tv)
            ivIcon.scaleType = ImageView.ScaleType.CENTER_INSIDE
            
            itemView.setOnClickListener {
                onProgramClick(program)
            }
        }
    }
    
    private class DiffCallback : DiffUtil.ItemCallback<EpgEntity>() {
        override fun areItemsTheSame(oldItem: EpgEntity, newItem: EpgEntity): Boolean {
            return oldItem.id == newItem.id
        }
        
        override fun areContentsTheSame(oldItem: EpgEntity, newItem: EpgEntity): Boolean {
            return oldItem == newItem
        }
    }
}
