package com.tvonnet.debridxtreamiptv.features.seriesv2.ui

import android.view.View
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.tvonnet.debridxtreamiptv.data.debrid.model.TmdbTvShowDetails
import com.tvonnet.debridxtreamiptv.data.debrid.source.TmdbRemoteDataSource
import com.tvonnet.debridxtreamiptv.databinding.FragmentSeriesDetailV2Binding
import com.tvonnet.debridxtreamiptv.ui.trailer.TrailerValueParser
import kotlinx.coroutines.launch

/**
 * C4: everything the Xtream listing does *not* give us — the TMDB TV lookup (trailer, cast,
 * creator, episode runtime, age rating) and the IMDb / Rotten Tomatoes pass via OMDb.
 *
 * It runs **at most once** per page and is deliberately independent of `get_series_info`, so a
 * dead listing with no episodes still gets a filled-in header.
 *
 * The title cleanup is the fragile part and is why this is its own collaborator: provider names
 * carry `|...|` tags plus a leading short-code prefix ("PH - ", "EN | ") that the pipe-cleaner
 * leaves behind, and either one makes TMDB search return nothing.
 *
 * Bodies moved verbatim from `SeriesDetailFragmentV2`; only `this`/field references were rebound.
 */
class SeriesDetailEnrichment(
    private val binding: FragmentSeriesDetailV2Binding,
    private val lifecycleOwner: LifecycleOwner,
    private val tmdbRemoteDataSource: TmdbRemoteDataSource,
    private val page: SeriesDetailPage,
    private val onTrailerResolved: () -> Unit,
) {
    private val castAdapter by lazy { com.tvonnet.debridxtreamiptv.ui.vod.CastAdapter() }
    private var enrichStarted = false

    fun setupCredits() {
        binding.rvCast.layoutManager =
            LinearLayoutManager(binding.root.context, LinearLayoutManager.HORIZONTAL, false)
        binding.rvCast.adapter = castAdapter
    }

    /**
     * Full TMDB TV enrichment (parity with the movie page): trailer + cast + creator +
     * runtime + age rating, plus IMDb/RottenTomatoes via OMDb. Runs at most once.
     */
    fun enrichFromTmdb(title: String?, year: String?) {
        if (enrichStarted) return
        val raw = title?.trim().orEmpty()
        if (raw.isEmpty()) return
        enrichStarted = true
        // Strip |...| tags, then a leading short-code prefix like "PH - ", "SOM - ",
        // "EN | " which the pipe-cleaner leaves behind and which breaks TMDB search.
        val base = com.tvonnet.debridxtreamiptv.util.MediaTitleCleaner.clean(raw).ifBlank { raw }
        val cleanTitle = base
            .replace(Regex("^[A-Za-z]{2,4}\\s*[-–—|:]\\s*"), "")
            .trim()
            .ifBlank { base }
        lifecycleOwner.lifecycleScope.launch {
            val details = runCatching { fetchTmdbTvDetails(cleanTitle, year) }.getOrNull() ?: return@launch
            if (!page.isViewAlive()) return@launch
            bindTmdbEnrichment(details)
        }
    }

    private suspend fun fetchTmdbTvDetails(
        title: String,
        year: String?
    ): TmdbTvShowDetails? {
        val search = tmdbRemoteDataSource.searchTvShows(query = title)
        val results = (search as? com.tvonnet.debridxtreamiptv.data.Result.Success)?.data?.results ?: return null
        if (results.isEmpty()) return null
        val best = results.firstOrNull { it.name.equals(title, true) && (year == null || it.firstAirDate?.take(4) == year) }
            ?: results.firstOrNull { it.name.equals(title, true) }
            ?: results.firstOrNull { year != null && it.firstAirDate?.take(4) == year }
            ?: results.first()
        val tvId = best.id ?: return null
        return (tmdbRemoteDataSource.getSeriesDetails(tvId) as? com.tvonnet.debridxtreamiptv.data.Result.Success)?.data
    }

    private fun bindTmdbEnrichment(d: TmdbTvShowDetails) {
        bindTrailer(d)
        bindCast(d)
        bindCreator(d)
        bindRuntimeAndCertification(d)

        // IMDb / Rotten Tomatoes via OMDb.
        d.externalIds?.imdbId?.takeIf { it.isNotBlank() }?.let { loadExternalRatings(it) }
    }

    /** Trailer (only if the provider didn't supply one). */
    private fun bindTrailer(d: TmdbTvShowDetails) {
        if (TrailerValueParser.parse(page.trailer) != null) return
        val key = d.videos?.results
            ?.asSequence()
            ?.filter { it.site.equals("YouTube", true) && !it.key.isNullOrBlank() }
            ?.sortedWith(
                compareByDescending<com.tvonnet.debridxtreamiptv.data.debrid.model.TmdbVideo> { it.type.equals("Trailer", true) }
                    .thenByDescending { it.official == true }
            )
            ?.firstOrNull()?.key
        if (!key.isNullOrBlank()) {
            page.trailer = key
            onTrailerResolved()
        }
    }

    /** Cast (top-billed 5, with photos). */
    private fun bindCast(d: TmdbTvShowDetails) {
        val cast = d.credits?.cast?.sortedBy { it.order ?: 99 }?.take(5).orEmpty()
        if (cast.isNotEmpty()) {
            castAdapter.submitList(cast)
            binding.layoutCast.visibility = View.VISIBLE
            binding.layoutCredits.visibility = View.VISIBLE
        }
    }

    /** Creator / Director. */
    private fun bindCreator(d: TmdbTvShowDetails) {
        val creator = d.createdBy?.firstOrNull { !it.name.isNullOrBlank() }?.name
            ?: d.credits?.crew?.firstOrNull { it.job.equals("Director", true) }?.name
        if (!creator.isNullOrBlank()) {
            binding.tvDirector.text = creator
            binding.layoutDirector.visibility = View.VISIBLE
            binding.layoutCredits.visibility = View.VISIBLE
        }
    }

    private fun bindRuntimeAndCertification(d: TmdbTvShowDetails) {
        // Episode runtime.
        val runtime = d.episodeRunTime?.firstOrNull { it > 0 }
        if (runtime != null) {
            binding.tvRuntime.text = "${runtime}M"
            binding.tvRuntime.visibility = View.VISIBLE
        }

        // Age / content rating (US → GB → any).
        val cert = d.contentRatings?.results?.let { r ->
            r.firstOrNull { it.countryCode.equals("US", true) }?.rating
                ?: r.firstOrNull { it.countryCode.equals("GB", true) }?.rating
                ?: r.firstOrNull { !it.rating.isNullOrBlank() }?.rating
        }?.takeIf { it.isNotBlank() }
        if (cert != null) {
            binding.tvAgeRating.text = cert
            binding.tvAgeRating.visibility = View.VISIBLE
        }
    }

    private fun loadExternalRatings(imdbId: String) {
        lifecycleOwner.lifecycleScope.launch {
            val ratings = com.tvonnet.debridxtreamiptv.data.omdb.OmdbClient.fetchRatings(imdbId) ?: return@launch
            if (!page.isViewAlive()) return@launch
            ratings.imdbRating?.let {
                binding.tvImdbRating.text = it
                binding.badgeImdb.visibility = View.VISIBLE
            }
            ratings.rottenTomatoes?.let {
                binding.tvRtRating.text = it
                binding.badgeRt.visibility = View.VISIBLE
            }
            if (binding.tvAgeRating.visibility != View.VISIBLE && !ratings.rated.isNullOrBlank()) {
                binding.tvAgeRating.text = ratings.rated
                binding.tvAgeRating.visibility = View.VISIBLE
            }
        }
    }
}
