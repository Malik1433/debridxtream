package com.tvonnet.debridxtreamiptv.ui.search

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.tvonnet.debridxtreamiptv.R

/**
 * Week 9: Recent Searches Adapter
 * Shows horizontal list of recent search queries
 */
class RecentSearchesAdapter(
    private val onSearchClick: (String) -> Unit
) : ListAdapter<String, RecentSearchesAdapter.ViewHolder>(DiffCallback()) {
    
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_recent_search, parent, false)
        return ViewHolder(view, onSearchClick)
    }
    
    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }
    
    class ViewHolder(
        itemView: View,
        private val onSearchClick: (String) -> Unit
    ) : RecyclerView.ViewHolder(itemView) {
        
        private val tvQuery: TextView = itemView.findViewById(R.id.tv_query)
        
        fun bind(query: String) {
            tvQuery.text = query
            
            itemView.setOnClickListener {
                onSearchClick(query)
            }
        }
    }
    
    private class DiffCallback : DiffUtil.ItemCallback<String>() {
        override fun areItemsTheSame(oldItem: String, newItem: String): Boolean {
            return oldItem == newItem
        }
        
        override fun areContentsTheSame(oldItem: String, newItem: String): Boolean {
            return oldItem == newItem
        }
    }
}

