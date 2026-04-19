package com.tvonnet.debridxtreamiptv.features.seriesv2.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.tvonnet.debridxtreamiptv.R

class SeasonsAdapterV2(
    private val onSeasonClick: (Int) -> Unit
) : ListAdapter<Int, SeasonsAdapterV2.SeasonViewHolder>(SeasonDiffCallback()) {

    var selectedSeason: Int? = null

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SeasonViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_season_pill_v2, parent, false)
        return SeasonViewHolder(view)
    }

    override fun onBindViewHolder(holder: SeasonViewHolder, position: Int) {
        val seasonNum = getItem(position)
        holder.bind(seasonNum, seasonNum == selectedSeason)
    }

    inner class SeasonViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvName: TextView = itemView.findViewById(R.id.tv_season_name)

        fun bind(seasonNum: Int, isSelected: Boolean) {
            tvName.text = if (seasonNum == 0) "Specials" else "Season $seasonNum"
            tvName.isSelected = isSelected
            
            // TV Focus Visuals
            // TV Focus Visuals - Scaling and Z-layering
            itemView.setOnFocusChangeListener { v, hasFocus ->
                v.z = if (hasFocus) 10f else 0f
                v.animate()
                    .scaleX(if (hasFocus) 1.1f else 1.0f)
                    .scaleY(if (hasFocus) 1.1f else 1.0f)
                    .duration = 200
            }
            
            itemView.setOnClickListener {
                if (selectedSeason != seasonNum) {
                    val oldSelected = selectedSeason
                    selectedSeason = seasonNum
                    onSeasonClick(seasonNum)
                    
                    // Notify changes to refresh UI selection state
                    // Ideally we use notifyItemChanged, but finding index is tricky with just Int list?
                    // ListAdapter has access to current list.
                    currentList.indexOf(oldSelected).takeIf { it != -1 }?.let { notifyItemChanged(it) }
                    currentList.indexOf(seasonNum).takeIf { it != -1 }?.let { notifyItemChanged(it) }
                }
            }
        }
    }

    class SeasonDiffCallback : DiffUtil.ItemCallback<Int>() {
        override fun areItemsTheSame(oldItem: Int, newItem: Int): Boolean = oldItem == newItem
        override fun areContentsTheSame(oldItem: Int, newItem: Int): Boolean = oldItem == newItem
    }
}
