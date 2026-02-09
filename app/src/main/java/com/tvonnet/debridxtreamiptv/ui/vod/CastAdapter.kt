package com.tvonnet.debridxtreamiptv.ui.vod

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.tvonnet.debridxtreamiptv.R
import com.tvonnet.debridxtreamiptv.data.debrid.model.TmdbCast
import com.tvonnet.debridxtreamiptv.data.debrid.model.TmdbImageUrl

/**
 * Adapter for displaying cast members in Movie/Series detail screens.
 */
class CastAdapter : RecyclerView.Adapter<CastAdapter.CastViewHolder>() {

    private var castList: List<TmdbCast> = emptyList()

    fun submitList(newList: List<TmdbCast>) {
        // Take top 10 cast members for performance and clean UI
        castList = newList.take(10)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CastViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_cast_member, parent, false)
        return CastViewHolder(view)
    }

    override fun onBindViewHolder(holder: CastViewHolder, position: Int) {
        holder.bind(castList[position])
    }

    override fun getItemCount(): Int = castList.size

    class CastViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val ivPhoto: ImageView = itemView.findViewById(R.id.iv_actor_photo)
        private val tvName: TextView = itemView.findViewById(R.id.tv_actor_name)
        private val tvCharacter: TextView = itemView.findViewById(R.id.tv_character_name)

        fun bind(cast: TmdbCast) {
            tvName.text = cast.name
            tvCharacter.text = cast.character
            
            val profileUrl = TmdbImageUrl.getProfileUrl(cast.profilePath)
            Glide.with(itemView.context)
                .load(profileUrl)
                .placeholder(R.drawable.ic_person)
                .error(R.drawable.ic_person)
                .circleCrop()
                .into(ivPhoto)
        }
    }
}
