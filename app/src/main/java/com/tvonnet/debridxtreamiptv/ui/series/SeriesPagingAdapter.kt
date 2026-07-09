package com.tvonnet.debridxtreamiptv.ui.series

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.paging.PagingDataAdapter
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.tvonnet.debridxtreamiptv.R
import com.tvonnet.debridxtreamiptv.data.model.XtreamSeriesInfo
import java.util.Locale

/**
 * Paging3 adapter for Series.
 * Card: item_series_card.xml — poster with an overlaid glass footer (title + ★ rating · year),
 * a NEW badge (year >= 2024) and a focus play-overlay. No seasons/episodes counts (the Xtream
 * list API has none), so that badge/segment is intentionally absent.
 */
class SeriesPagingAdapter(
    private val onSeriesClick: (XtreamSeriesInfo, android.view.View) -> Unit,
    private val favoriteChecker: ((String) -> Boolean)? = null,
    private val onSeriesLongClick: ((XtreamSeriesInfo) -> Unit)? = null,
    /** Returns (seasonCount, episodeCount) for a series_id, or null when unknown. */
    private val countsProvider: ((String) -> Pair<Int, Int>?)? = null
) : PagingDataAdapter<XtreamSeriesInfo, SeriesPagingViewHolder>(SERIES_COMPARATOR) {

    var onItemFocused: ((position: Int) -> Unit)? = null

    init {
        stateRestorationPolicy = StateRestorationPolicy.PREVENT_WHEN_EMPTY
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SeriesPagingViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_series_card, parent, false)
        return SeriesPagingViewHolder(view)
    }

    override fun onBindViewHolder(holder: SeriesPagingViewHolder, position: Int) {
        val series = getItem(position)
        if (series != null) {
            val isFavorite = series.series_id?.let { favoriteChecker?.invoke(it) } ?: false
            val counts = series.series_id?.let { countsProvider?.invoke(it) }
            holder.bind(series, isFavorite, counts, onItemFocused, onSeriesClick, onSeriesLongClick)
        }
    }

    override fun onViewRecycled(holder: SeriesPagingViewHolder) {
        super.onViewRecycled(holder)
        holder.clearAnimations()
    }

    companion object {
        private val SERIES_COMPARATOR = object : DiffUtil.ItemCallback<XtreamSeriesInfo>() {
            override fun areItemsTheSame(oldItem: XtreamSeriesInfo, newItem: XtreamSeriesInfo): Boolean {
                return oldItem.series_id == newItem.series_id
            }

            override fun areContentsTheSame(oldItem: XtreamSeriesInfo, newItem: XtreamSeriesInfo): Boolean {
                return oldItem == newItem
            }
        }
    }
}

class SeriesPagingViewHolder(itemView: android.view.View) : RecyclerView.ViewHolder(itemView) {
    private val tvTitle = itemView.findViewById<TextView>(R.id.tv_series_title)
    private val tvYear = itemView.findViewById<TextView>(R.id.tv_series_year)
    private val tvRating = itemView.findViewById<TextView>(R.id.tv_series_rating)
    private val tvStar = itemView.findViewById<TextView>(R.id.tv_star)
    private val flNewBadge = itemView.findViewById<View>(R.id.fl_new_badge)
    private val ivPoster = itemView.findViewById<ImageView>(R.id.iv_series_poster)
    private val ivFavoriteIndicator = itemView.findViewById<ImageView>(R.id.iv_favorite_indicator)
    private val flPlayOverlay = itemView.findViewById<View>(R.id.fl_play_overlay)
    private val glowFocus = itemView.findViewById<View>(R.id.glow_focus)
    private val tvMetaDot = itemView.findViewById<TextView>(R.id.tv_meta_dot)

    init {
        // Same soft bloom as the movie cards, purple: rgba(167,139,250,0.35)
        com.tvonnet.debridxtreamiptv.util.FocusGlow.attachSelector(glowFocus, color = GLOW_PURPLE)
    }

    fun bind(
        series: XtreamSeriesInfo,
        isFavorite: Boolean,
        counts: Pair<Int, Int>?,
        onItemFocused: ((Int) -> Unit)?,
        onClick: (XtreamSeriesInfo, android.view.View) -> Unit,
        onLongClick: ((XtreamSeriesInfo) -> Unit)? = null
    ) {
        itemView.isFocusable = true
        itemView.isFocusableInTouchMode = true
        itemView.setTag(R.id.tag_series_id, series.series_id)

        tvTitle.text = com.tvonnet.debridxtreamiptv.util.MediaTitleCleaner.clean(series.name)
            .ifBlank { "Unknown Series" }

        // NEW badge (top-left, purple): release year >= 2024
        val releaseYear = series.releaseDate?.take(4)?.toIntOrNull()
        flNewBadge?.visibility = if (releaseYear != null && releaseYear >= 2024) View.VISIBLE else View.GONE

        // Rating: prefer the dedicated 0–5 field (rating_5based); fall back to the
        // 0–10 `rating` string (normalized to /5). When genuinely absent, HIDE the
        // ★ + rating segment rather than forcing a placeholder like "4.0" / "N/A".
        val ratingStars: Float? = run {
            val five = series.rating_5based?.toFloat()
            if (five != null && five > 0f) return@run five.coerceIn(0f, 5f)
            val raw = series.rating?.trim()?.replace(",", ".")?.toFloatOrNull()
            if (raw != null && raw > 0f) {
                (if (raw > 5f) raw / 2f else raw).coerceIn(0f, 5f)
            } else null
        }
        if (ratingStars != null) {
            tvRating.text = String.format(Locale.US, "%.1f", ratingStars)
            tvRating.visibility = View.VISIBLE
            tvStar?.visibility = View.VISIBLE
        } else {
            tvRating.visibility = View.GONE
            tvStar?.visibility = View.GONE
        }

        // Year — hide with the separator when absent (avoids dangling dots)
        val yearText = series.releaseDate?.take(4)?.takeIf { it.isNotBlank() }
        tvYear.text = yearText ?: ""
        tvYear.visibility = if (yearText != null) View.VISIBLE else View.GONE
        // Separator only shows when BOTH a rating and a year are present.
        tvMetaDot?.visibility =
            if (ratingStars != null && yearText != null) View.VISIBLE else View.GONE

        // Favorite
        ivFavoriteIndicator?.visibility = if (isFavorite) View.VISIBLE else View.GONE

        // Play overlay hidden until focused
        flPlayOverlay?.alpha = 0f

        // Poster
        val resolved = com.tvonnet.debridxtreamiptv.util.GlobalConfig.resolveIconUrl(series.cover)
        if (!resolved.isNullOrBlank()) {
            com.tvonnet.debridxtreamiptv.util.GlideUtils.loadMoviePoster(ivPoster, resolved, useRoundedCorners = true)
        } else {
            ivPoster.setImageResource(R.drawable.tv_card_placeholder)
        }

        // Shared-element transition name (used by the detail screen)
        ivPoster.transitionName = "series_poster_${series.series_id}"

        itemView.setOnClickListener { onClick(series, ivPoster) }
        itemView.setOnLongClickListener {
            onLongClick?.invoke(series)
            true
        }

        // Focus: identical to movie cards — shared cinematic focus util + z-lift.
        // Glow bloom is driven by duplicateParentState + FocusGlow selector.
        itemView.setOnFocusChangeListener { v, hasFocus ->
            com.tvonnet.debridxtreamiptv.util.FocusEffects.applyCinematicFocus(v, hasFocus, scale = 1.06f)
            // Play overlay fades in on focus (design: opacity 0 → 1).
            flPlayOverlay?.animate()?.cancel()
            flPlayOverlay?.animate()?.alpha(if (hasFocus) 1f else 0f)?.setDuration(160)?.start()
            if (hasFocus) {
                v.z = 20f
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) {
                    onItemFocused?.invoke(pos)
                }
                tvTitle.isSelected = true
            } else {
                v.z = 0f
                tvTitle.isSelected = false
            }
        }
    }

    fun clearAnimations() {
        itemView.animate()?.cancel()
        itemView.scaleX = 1.0f
        itemView.scaleY = 1.0f
        itemView.z = 0f
        flPlayOverlay?.animate()?.cancel()
        flPlayOverlay?.alpha = 0f
    }

    private companion object {
        // rgba(167,139,250,0.35)
        const val GLOW_PURPLE = 0x59A78BFA
    }
}

// ═══ SERIES GENRE PILL ADAPTER (purple mirror of VodGenrePillAdapter) ═══

class SeriesGenrePillAdapter(
    private val genres: List<String>,
    private val onGenreSelected: (String) -> Unit
) : RecyclerView.Adapter<SeriesGenrePillAdapter.ViewHolder>() {

    private var selectedPosition = 0

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_series_genre_pill, parent, false)
        return ViewHolder(view as TextView)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val genre = genres[position]
        val isActive = position == selectedPosition
        holder.bind(genre, isActive) {
            val old = selectedPosition
            selectedPosition = holder.bindingAdapterPosition
            notifyItemChanged(old)
            notifyItemChanged(selectedPosition)
            onGenreSelected(genre)
        }
    }

    override fun getItemCount() = genres.size

    class ViewHolder(private val tv: TextView) : RecyclerView.ViewHolder(tv) {
        fun bind(genre: String, isActive: Boolean, onClick: () -> Unit) {
            tv.text = genre
            if (isActive) {
                tv.setBackgroundResource(R.drawable.bg_series_genre_pill_active)
                tv.setTextColor(Color.parseColor("#A78BFA"))
            } else {
                tv.setBackgroundResource(R.drawable.bg_vod_genre_pill_default)
                tv.setTextColor(Color.parseColor("#64748B"))
            }
            tv.setOnClickListener { onClick() }
            tv.setOnFocusChangeListener { v, hasFocus ->
                if (hasFocus) {
                    (v as TextView).setBackgroundResource(R.drawable.bg_series_genre_pill_active)
                    (v as TextView).setTextColor(Color.parseColor("#A78BFA"))
                } else if (!isActive) {
                    (v as TextView).setBackgroundResource(R.drawable.bg_vod_genre_pill_default)
                    (v as TextView).setTextColor(Color.parseColor("#64748B"))
                }
            }
        }
    }
}
