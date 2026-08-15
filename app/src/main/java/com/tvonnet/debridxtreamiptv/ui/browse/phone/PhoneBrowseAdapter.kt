package com.tvonnet.debridxtreamiptv.ui.browse.phone

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.paging.LoadState
import androidx.paging.LoadStateAdapter
import androidx.paging.PagingDataAdapter
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.tvonnet.debridxtreamiptv.R
import com.tvonnet.debridxtreamiptv.data.model.XtreamSeriesInfo
import com.tvonnet.debridxtreamiptv.data.model.XtreamVodInfo
import com.tvonnet.debridxtreamiptv.util.MediaTitleCleaner

/**
 * One card in the Browse grid, in the only shape this screen needs.
 *
 * Movies and series arrive as two different provider models with two different sets of missing
 * fields; translating both into this at the edge means the adapter never asks which it got.
 */
data class PhoneBrowseItem(
    val id: String,
    val title: String,
    val posterUrl: String?,
    val backdropUrl: String?,
    val meta: String,
    /** Parsed from the title — Xtream sends no quality field at all. Null when it says nothing. */
    val quality: String?,
    val containerExtension: String?,
    /** Series only, and only when the counts are genuinely known. */
    val episodeBadge: String? = null,
) {
    companion object {
        fun of(movie: XtreamVodInfo): PhoneBrowseItem {
            val name = movie.name.orEmpty()
            return PhoneBrowseItem(
                id = movie.stream_id.orEmpty(),
                title = MediaTitleCleaner.clean(name),
                posterUrl = movie.stream_icon,
                backdropUrl = null,
                meta = metaLine(
                    year = movie.releaseDate?.take(4),
                    extra = movie.duration,
                    rating = movie.rating,
                ),
                quality = qualityOf(name),
                containerExtension = movie.container_extension,
            )
        }

        fun of(series: XtreamSeriesInfo): PhoneBrowseItem {
            val name = series.name.orEmpty()
            return PhoneBrowseItem(
                id = series.series_id.orEmpty(),
                title = MediaTitleCleaner.clean(name),
                posterUrl = series.cover,
                backdropUrl = series.backdropPathList.firstOrNull(),
                meta = metaLine(
                    year = series.releaseDate?.take(4),
                    extra = series.genre,
                    rating = series.rating,
                ),
                quality = qualityOf(name),
                containerExtension = null,
                // Xtream does not carry season/episode counts in the catalogue call, and the
                // owner ruled out a placeholder: an unknown count shows NO badge (2026-08-15).
                episodeBadge = null,
            )
        }

        /**
         * The card's second line, from whatever this row actually has.
         *
         * The catalogue call carries no runtime and frequently no release date — only a rating —
         * so a line built strictly from year and duration was blank on most cards. It falls
         * through to the rating rather than printing an empty line.
         */
        private fun metaLine(year: String?, extra: String?, rating: String?): String {
            val parts = listOfNotNull(
                year?.takeIf { it.isNotBlank() },
                extra?.takeIf { it.isNotBlank() },
            )
            if (parts.isNotEmpty()) return parts.joinToString(" · ")
            val score = rating?.trim()?.toDoubleOrNull()?.takeIf { it > 0 } ?: return ""
            return "★ %.1f".format(score)
        }

        /**
         * The quality badge, read out of the title — the only place this provider states it.
         * Anything that does not say so plainly gets no badge rather than a guess.
         */
        private fun qualityOf(name: String): String? {
            val upper = name.uppercase()
            return when {
                upper.contains("2160") || upper.contains("4K") || upper.contains("UHD") -> "4K"
                upper.contains("1080") || upper.contains("FHD") -> "FHD"
                upper.contains("720") || Regex("\\bHD\\b").containsMatchIn(upper) -> "HD"
                else -> null
            }
        }
    }
}

/** The poster grid. Paging keeps the list, this only paints it. */
class PhoneBrowseAdapter(
    private val onItem: (PhoneBrowseItem) -> Unit,
    private val onLongPress: (PhoneBrowseItem) -> Unit = {},
) : PagingDataAdapter<PhoneBrowseItem, PhoneBrowseAdapter.CardHolder>(DIFF) {

    class CardHolder(view: View) : RecyclerView.ViewHolder(view)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = CardHolder(
        LayoutInflater.from(parent.context)
            .inflate(R.layout.item_phone_browse_card, parent, false)
    )

    override fun onBindViewHolder(holder: CardHolder, position: Int) {
        val item = getItem(position) ?: return
        val root = holder.itemView
        root.findViewById<TextView>(R.id.phone_card_title).text = item.title
        root.findViewById<TextView>(R.id.phone_card_meta).apply {
            text = item.meta
            isVisible = item.meta.isNotBlank()
        }
        root.findViewById<TextView>(R.id.phone_card_quality).apply {
            text = item.quality.orEmpty()
            isVisible = item.quality != null
        }
        root.findViewById<TextView>(R.id.phone_card_episodes).apply {
            text = item.episodeBadge.orEmpty()
            isVisible = item.episodeBadge != null
        }
        val poster = root.findViewById<ImageView>(R.id.phone_card_poster)
        val art = item.posterUrl?.takeIf { it.isNotBlank() } ?: item.backdropUrl
        if (art.isNullOrBlank()) {
            poster.setImageDrawable(null)
        } else {
            Glide.with(poster).load(art).centerCrop().into(poster)
        }
        root.setOnClickListener { onItem(item) }
        root.setOnLongClickListener {
            root.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
            onLongPress(item)
            true
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<PhoneBrowseItem>() {
            override fun areItemsTheSame(old: PhoneBrowseItem, new: PhoneBrowseItem) =
                old.id == new.id

            override fun areContentsTheSame(old: PhoneBrowseItem, new: PhoneBrowseItem) = old == new
        }
    }
}

/**
 * The bottom of the list, which is three different statements and never a blank gap: a page in
 * flight, the end of the catalogue, or a page that failed while the loaded rows stayed.
 */
class PhoneBrowseFooter(
    private val onRetry: () -> Unit,
) : LoadStateAdapter<PhoneBrowseFooter.FooterHolder>() {

    class FooterHolder(view: View) : RecyclerView.ViewHolder(view)

    override fun onCreateViewHolder(parent: ViewGroup, loadState: LoadState) = FooterHolder(
        LayoutInflater.from(parent.context)
            .inflate(R.layout.item_phone_paging_footer, parent, false)
    )

    override fun onBindViewHolder(holder: FooterHolder, loadState: LoadState) {
        val root = holder.itemView
        val spinner = root.findViewById<View>(R.id.phone_foot_spinner)
        val text = root.findViewById<TextView>(R.id.phone_foot_text)
        val retry = root.findViewById<TextView>(R.id.phone_foot_retry)

        spinner.isVisible = loadState is LoadState.Loading
        retry.isVisible = loadState is LoadState.Error
        retry.setOnClickListener { onRetry() }
        text.text = when {
            loadState is LoadState.Loading -> root.context.getString(R.string.phone_loading_more)
            loadState is LoadState.Error -> root.context.getString(R.string.phone_couldnt_load_more)
            // The end of a catalogue is a statement, not an error: a rule and nothing to do.
            loadState.endOfPaginationReached -> root.context.getString(R.string.phone_end_of_list)
            else -> ""
        }
    }

    /** Three endings, one footer: a page in flight, a page that failed, and the end of the list.
     *  Anything else draws nothing rather than a blank 72dp gap. */
    override fun displayLoadStateAsItem(loadState: LoadState): Boolean =
        loadState is LoadState.Loading ||
            loadState is LoadState.Error ||
            loadState.endOfPaginationReached
}
