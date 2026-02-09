package com.tvonnet.debridxtreamiptv.ui.epg

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.tvonnet.debridxtreamiptv.R
import com.tvonnet.debridxtreamiptv.data.local.entity.EpgEntity
import java.text.SimpleDateFormat
import java.util.*

/**
 * Week 11: EPG Adapter
 * 
 * Displays program guide timeline items
 */
class EpgAdapter(
    private val onProgramClick: (EpgEntity) -> Unit
) : ListAdapter<EpgEntity, EpgAdapter.EpgViewHolder>(EpgDiffCallback()) {
    
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): EpgViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_epg_program, parent, false)
        return EpgViewHolder(view, onProgramClick)
    }
    
    override fun onBindViewHolder(holder: EpgViewHolder, position: Int) {
        holder.bind(getItem(position))
    }
    
    class EpgViewHolder(
        itemView: View,
        private val onProgramClick: (EpgEntity) -> Unit
    ) : RecyclerView.ViewHolder(itemView) {
        
        private val titleText: TextView = itemView.findViewById(R.id.text_program_title)
        private val timeText: TextView = itemView.findViewById(R.id.text_program_time)
        private val descriptionText: TextView = itemView.findViewById(R.id.text_program_description)
        private val statusIndicator: View = itemView.findViewById(R.id.view_program_status)
        
        private val timeFormatter = SimpleDateFormat("HH:mm", Locale.getDefault())
        
        fun bind(program: EpgEntity) {
            titleText.text = program.title ?: "Unknown Program"
            
            val startTime = timeFormatter.format(Date(program.start))
            val endTime = timeFormatter.format(Date(program.stop))
            val duration = program.getDurationMinutes()
            timeText.text = "$startTime - $endTime ($duration min)"
            
            descriptionText.text = program.description ?: ""
            descriptionText.visibility = if (program.description.isNullOrEmpty()) {
                View.GONE
            } else {
                View.VISIBLE
            }
            
            // Show status indicator (green = playing, yellow = upcoming)
            when {
                program.isPlaying() -> {
                    statusIndicator.setBackgroundResource(R.color.epg_playing)
                }
                program.isUpcoming() -> {
                    statusIndicator.setBackgroundResource(R.color.epg_upcoming)
                }
                else -> {
                    statusIndicator.visibility = View.GONE
                }
            }
            
            itemView.setOnClickListener {
                onProgramClick(program)
            }
            
            // TV focus handling
            itemView.isFocusable = true
            itemView.isFocusableInTouchMode = true
        }
    }
    
    class EpgDiffCallback : DiffUtil.ItemCallback<EpgEntity>() {
        override fun areItemsTheSame(oldItem: EpgEntity, newItem: EpgEntity): Boolean {
            return oldItem.id == newItem.id
        }
        
        override fun areContentsTheSame(oldItem: EpgEntity, newItem: EpgEntity): Boolean {
            return oldItem == newItem
        }
    }
}

