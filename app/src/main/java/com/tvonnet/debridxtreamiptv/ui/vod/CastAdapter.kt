package com.tvonnet.debridxtreamiptv.ui.vod

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.tvonnet.debridxtreamiptv.R
import com.tvonnet.debridxtreamiptv.data.debrid.model.TmdbCast
import com.tvonnet.debridxtreamiptv.data.debrid.model.TmdbImageUrl
import com.tvonnet.debridxtreamiptv.databinding.ItemCastMemberBinding

/**
 * Adapter for displaying cast members in Movie Detail screen
 */
class CastAdapter : ListAdapter<TmdbCast, CastAdapter.CastViewHolder>(CastDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CastViewHolder {
        val binding = ItemCastMemberBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return CastViewHolder(binding)
    }

    override fun onBindViewHolder(holder: CastViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class CastViewHolder(private val binding: ItemCastMemberBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(cast: TmdbCast) {
            binding.tvName.text = cast.name
            binding.tvCharacter.text = cast.character

            val profileUrl = TmdbImageUrl.getProfileUrl(cast.profilePath)

            Glide.with(binding.ivProfile.context)
                .load(profileUrl)
                .placeholder(R.drawable.ic_person_placeholder)
                .error(R.drawable.ic_person_placeholder)
                .transition(DrawableTransitionOptions.withCrossFade())
                .circleCrop()
                .into(binding.ivProfile)
        }
    }

    class CastDiffCallback : DiffUtil.ItemCallback<TmdbCast>() {
        override fun areItemsTheSame(oldItem: TmdbCast, newItem: TmdbCast): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: TmdbCast, newItem: TmdbCast): Boolean {
            return oldItem == newItem
        }
    }
}
