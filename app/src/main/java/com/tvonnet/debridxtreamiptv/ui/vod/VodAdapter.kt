package com.tvonnet.debridxtreamiptv.ui.vod

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.tvonnet.debridxtreamiptv.R
import com.tvonnet.debridxtreamiptv.data.model.XtreamVodInfo
import java.util.Locale

// ═══ VOD ADAPTER ═══
// Moved out of VodFragment.kt (C2) unchanged: these are the movie grid's cards, not the screen.

class VodAdapter(
    private val onMovieClick: (XtreamVodInfo) -> Unit,
    private val favoriteChecker: ((String) -> Boolean)? = null,
    private val watchedChecker: ((String) -> Boolean)? = null,
    private val progressChecker: ((String) -> Float?)? = null,
    private val onMovieLongClick: ((XtreamVodInfo) -> Unit)? = null
) : androidx.paging.PagingDataAdapter<XtreamVodInfo, VodViewHolder>(VodDiffCallback()) {

    var onItemFocused: ((position: Int) -> Unit)? = null

    init {
        stateRestorationPolicy = StateRestorationPolicy.PREVENT_WHEN_EMPTY
    }

    companion object {
        /** Payload for a badge/progress-only rebind (skips the poster reload). */
        val PAYLOAD_OVERLAYS = Any()
    }

    private fun overlayState(movie: XtreamVodInfo): Triple<Boolean, Boolean, Float?> {
        val sid = movie.stream_id?.toString()
        val isFavorite = sid?.let { favoriteChecker?.invoke(it) } ?: false
        val isWatched = sid?.let { watchedChecker?.invoke(it) } ?: false
        val progress = sid?.let { progressChecker?.invoke(it) }
        return Triple(isFavorite, isWatched, progress)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VodViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_movie_card, parent, false)
        return VodViewHolder(view)
    }

    override fun onBindViewHolder(holder: VodViewHolder, position: Int) {
        val movie = getItem(position)
        if (movie != null) {
            val (isFavorite, isWatched, progress) = overlayState(movie)
            holder.bind(movie, isFavorite, isWatched, progress, onItemFocused, onMovieClick, onMovieLongClick)
        }
    }

    override fun onBindViewHolder(holder: VodViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.contains(PAYLOAD_OVERLAYS)) {
            val movie = getItem(position) ?: return
            val (isFavorite, isWatched, progress) = overlayState(movie)
            holder.bindOverlays(isFavorite, isWatched, progress) // no poster reload
            return
        }
        super.onBindViewHolder(holder, position, payloads)
    }
}

class VodDiffCallback : androidx.recyclerview.widget.DiffUtil.ItemCallback<XtreamVodInfo>() {
    override fun areItemsTheSame(oldItem: XtreamVodInfo, newItem: XtreamVodInfo): Boolean {
        return oldItem.stream_id == newItem.stream_id
    }
    override fun areContentsTheSame(oldItem: XtreamVodInfo, newItem: XtreamVodInfo): Boolean {
        return oldItem == newItem
    }
}

class VodViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
    private val tvMovieTitle = itemView.findViewById<TextView>(R.id.tv_movie_title)
    private val tvQualityValue = itemView.findViewById<TextView>(R.id.tv_quality_value)
    private val ivMoviePoster = itemView.findViewById<ImageView>(R.id.iv_movie_poster)
    private val ivFavoriteIndicator = itemView.findViewById<ImageView>(R.id.iv_favorite_indicator)
    private val flWatchedBadge = itemView.findViewById<View>(R.id.fl_watched_badge)
    private val flProgressBar = itemView.findViewById<View>(R.id.fl_progress_bar)
    private val progressBarFill = itemView.findViewById<View>(R.id.progress_bar_fill)
    private val glowFocus = itemView.findViewById<View>(R.id.glow_focus)
    private val llQualityBadge = itemView.findViewById<View>(R.id.ll_quality_badge)

    init {
        // Identical to home/top-10 cards: default-corner cyan bloom via duplicateParentState.
        com.tvonnet.debridxtreamiptv.util.FocusGlow.attachSelector(glowFocus)
    }

    /** Badge/progress overlays only — used by the payload rebind (no poster reload). */
    fun bindOverlays(isFavorite: Boolean, isWatched: Boolean, progress: Float?) {
        ivFavoriteIndicator?.visibility = if (isFavorite) View.VISIBLE else View.GONE
        flWatchedBadge?.visibility = if (isWatched) View.VISIBLE else View.GONE
        // Progress strip: only for in-progress (unwatched) movies.
        val frac = progress?.takeIf { it > 0f && !isWatched }
        if (frac != null) {
            flProgressBar?.visibility = View.VISIBLE
            progressBarFill?.post {
                val parent = flProgressBar?.width ?: 0
                progressBarFill.layoutParams = progressBarFill.layoutParams.apply {
                    width = (parent * frac).toInt()
                }
            }
        } else {
            flProgressBar?.visibility = View.GONE
        }
    }

    fun bind(
        movie: XtreamVodInfo,
        isFavorite: Boolean,
        isWatched: Boolean,
        progress: Float?,
        onItemFocused: ((Int) -> Unit)?,
        onClick: (XtreamVodInfo) -> Unit,
        onLongClick: ((XtreamVodInfo) -> Unit)? = null
    ) {
        itemView.isFocusable = true
        itemView.isFocusableInTouchMode = true
        itemView.setTag(R.id.tag_vod_id, movie.stream_id?.toString())

        var cleanName = movie.name?.trim() ?: "Unknown Movie"

        // Extract language tags
        val langRegex = Regex("""(?i)[\|\[\(]?\s*(MULTI|EN|FR|IT|ES|DE|RU|TR|AR|NL|PT|PL)\s*[\]\)]?$""")
        val langMatch = langRegex.find(cleanName)
        if (langMatch != null) {
            cleanName = cleanName.replace(langMatch.value, "").trim()
            if (cleanName.endsWith("|") || cleanName.endsWith("-")) cleanName = cleanName.dropLast(1).trim()
        }

        // Extract quality
        val qualityRegex = Regex("""(?i)[\|\[\(]?\s*(4K|UHD|1080p|720p|FHD|HD)\s*[\]\)]?$""")
        val qMatch = qualityRegex.find(cleanName)
        var qualityBadge = "HD"
        if (qMatch != null) {
            val qExtracted = qMatch.groupValues[1].uppercase(Locale.ROOT)
            qualityBadge = when {
                qExtracted.contains("4K") || qExtracted.contains("UHD") -> "4K"
                qExtracted.contains("1080") || qExtracted.contains("FHD") -> "FHD"
                else -> "HD"
            }
            cleanName = cleanName.replace(qMatch.value, "").trim()
            if (cleanName.endsWith("|") || cleanName.endsWith("-")) cleanName = cleanName.dropLast(1).trim()
        } else {
            val nameLower = cleanName.lowercase(Locale.ROOT)
            if (nameLower.contains("4k") || nameLower.contains("uhd")) qualityBadge = "4K"
            else if (nameLower.contains("1080p") || nameLower.contains("fhd")) qualityBadge = "FHD"
        }

        // Shared cleanup for leading/embedded provider tags + escapes (after quality
        // extraction above, which strips trailing quality tokens and sets the badge).
        cleanName = com.tvonnet.debridxtreamiptv.util.MediaTitleCleaner.clean(cleanName)
            .ifBlank { "Unknown Movie" }
        tvMovieTitle.text = cleanName

        // Quality badge: 4K ONLY — an "HD" pill on every card is pure noise.
        val is4K = qualityBadge == "4K"
        llQualityBadge?.visibility = if (is4K) View.VISIBLE else View.GONE
        if (is4K) {
            tvQualityValue.text = "4K"
            tvQualityValue.setTextColor(Color.parseColor("#FFAA00"))
        }

        // Favorite / watched / progress overlays (shared with the payload rebind).
        bindOverlays(isFavorite, isWatched, progress)

        // Poster
        val resolved = com.tvonnet.debridxtreamiptv.util.GlobalConfig.resolveIconUrl(movie.stream_icon)
        if (!resolved.isNullOrBlank()) {
            com.tvonnet.debridxtreamiptv.util.GlideUtils.loadMoviePoster(ivMoviePoster, resolved, useRoundedCorners = true)
        } else {
            ivMoviePoster.setImageResource(R.drawable.tv_card_placeholder)
        }

        itemView.setOnClickListener { onClick(movie) }
        itemView.setOnLongClickListener {
            onLongClick?.invoke(movie)
            true
        }

        // Focus: identical to home cards — shared cinematic focus util + z-lift.
        // Glow bloom is driven by duplicateParentState + FocusGlow selector.
        itemView.setOnFocusChangeListener { v, hasFocus ->
            com.tvonnet.debridxtreamiptv.util.FocusEffects.applyCinematicFocus(v, hasFocus, scale = 1.06f)
            if (hasFocus) {
                v.z = 20f
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) {
                    onItemFocused?.invoke(pos)
                }
                tvMovieTitle.isSelected = true
            } else {
                v.z = 0f
                tvMovieTitle.isSelected = false
            }
        }
    }
}

