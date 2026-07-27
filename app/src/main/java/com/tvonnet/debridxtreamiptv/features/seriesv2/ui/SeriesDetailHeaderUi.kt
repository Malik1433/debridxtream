package com.tvonnet.debridxtreamiptv.features.seriesv2.ui

import android.view.View
import com.tvonnet.debridxtreamiptv.databinding.FragmentSeriesDetailV2Binding
import com.tvonnet.debridxtreamiptv.features.seriesv2.ui.model.SeriesDetailUiState

/**
 * C4: the header block — title, plot, the year · seasons/episodes · genre meta line, the rating
 * badge and the multi-category summary.
 *
 * The separator dots are the fiddly part: each one only shows when the fields on *both* sides of
 * it are visible, and the two feeds that decide that ([bindMetadata] and [bindSeriesTotals])
 * arrive independently — so every path re-runs [updateMetaDots] rather than assuming an order.
 *
 * Bodies moved verbatim from `SeriesDetailFragmentV2`; only `this`/field references were rebound.
 */
class SeriesDetailHeaderUi(
    private val binding: FragmentSeriesDetailV2Binding,
) {
    /** @return the resolved year, which the TMDB lookup uses to disambiguate the show. */
    fun bindMetadata(state: SeriesDetailUiState.Success): String? {
        val s = state.series

        binding.tvTitle.text = s.title ?: s.name ?: binding.tvTitle.text
        binding.tvPlot.text = s.plot?.takeIf { it.isNotBlank() } ?: "No plot available."

        val year = s.year?.takeIf { it.isNotBlank() } ?: s.releaseDate?.take(4)?.takeIf { it.isNotBlank() }
        binding.tvYear.apply {
            text = year; visibility = if (year.isNullOrBlank()) View.GONE else View.VISIBLE
        }
        val genre = s.genre?.takeIf { it.isNotBlank() }
        binding.tvGenre.apply {
            text = genre; visibility = if (genre == null) View.GONE else View.VISIBLE
        }
        bindRating(s.rating?.toDoubleOrNull())
        updateMetaDots()
        return year
    }

    private fun bindRating(rating: Double?) {
        if (rating != null && rating > 0) {
            binding.layoutRating.visibility = View.VISIBLE
            binding.tvRatingPercentage.text = String.format(java.util.Locale.US, "%.1f", rating)
        } else {
            binding.layoutRating.visibility = View.GONE
        }
    }

    fun bindSeriesTotals(seasons: Int, episodes: Int) {
        if (seasons <= 0 && episodes <= 0) {
            binding.tvSeasonEpisodeCount.visibility = View.GONE
        } else {
            binding.tvSeasonEpisodeCount.visibility = View.VISIBLE
            val sLabel = if (seasons == 1) "SEASON" else "SEASONS"
            val eLabel = if (episodes == 1) "EPISODE" else "EPISODES"
            binding.tvSeasonEpisodeCount.text = "$seasons $sLabel · $episodes $eLabel"
        }
        updateMetaDots()
    }

    fun bindSummary(categoryCount: Int) {
        if (categoryCount >= 2) {
            binding.layoutSummary.visibility = View.VISIBLE
            binding.tvSummary.text = "AVAILABLE IN $categoryCount CATEGORIES · MULTI-SOURCE"
        } else {
            binding.layoutSummary.visibility = View.GONE
        }
    }

    private fun updateMetaDots() {
        val yearVisible = binding.tvYear.visibility == View.VISIBLE
        val countVisible = binding.tvSeasonEpisodeCount.visibility == View.VISIBLE
        val genreVisible = binding.tvGenre.visibility == View.VISIBLE
        binding.tvDot1.visibility = if (yearVisible && countVisible) View.VISIBLE else View.GONE
        binding.tvDot2.visibility = if (countVisible && genreVisible) View.VISIBLE else View.GONE
    }
}
