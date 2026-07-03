package com.tvonnet.debridxtreamiptv.ui.vod

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.bitmap.RoundedCorners
import com.tvonnet.debridxtreamiptv.R
import com.tvonnet.debridxtreamiptv.data.debrid.model.TmdbImageUrl
import com.tvonnet.debridxtreamiptv.data.debrid.model.TmdbMovie

class SimilarMoviesAdapter(
    private val onMovieClicked: (TmdbMovie) -> Unit
) : ListAdapter<TmdbMovie, SimilarMoviesAdapter.ViewHolder>(DiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_similar_movie, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val ivPoster: ImageView = itemView.findViewById(R.id.iv_similar_poster)
        private val tvTitle: TextView = itemView.findViewById(R.id.tv_similar_title)

        fun bind(movie: TmdbMovie) {
            val year = movie.releaseDate?.take(4) ?: ""
            val rating = movie.voteAverage?.let { String.format("%.1f", it) } ?: ""
            tvTitle.text = buildString {
                if (year.isNotBlank()) append(year)
                if (rating.isNotBlank()) {
                    if (isNotBlank()) append(" · ")
                    append("★ $rating")
                }
            }

            val posterUrl = TmdbImageUrl.getPosterUrl(movie.posterPath)
            Glide.with(ivPoster.context)
                .load(posterUrl)
                .transform(RoundedCorners(12))
                .placeholder(R.drawable.cin_detail_similar_poster)
                .into(ivPoster)

            itemView.setOnClickListener { onMovieClicked(movie) }
            com.tvonnet.debridxtreamiptv.utils.FocusGlintHelper.attach(itemView)
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<TmdbMovie>() {
        override fun areItemsTheSame(oldItem: TmdbMovie, newItem: TmdbMovie) = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: TmdbMovie, newItem: TmdbMovie) = oldItem == newItem
    }
}
