package com.tvonnet.debridxtreamiptv.features.seriesv2.data.repository

import com.tvonnet.debridxtreamiptv.data.local.entity.SeriesEntity
import com.tvonnet.debridxtreamiptv.features.seriesv2.data.dao.SeriesDaoV2
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** One listing of a show in the provider's catalog, with the categories it appears under. */
internal data class SiblingListing(
    val seriesId: String,
    val name: String?,
    val categoryIds: List<String?>
)

/**
 * C5: finds every listing of the same show across the provider's catalog.
 *
 * Siblings come from the **legacy `series_v2` table**, not the V2 one — that is the table category
 * browsing and the global search index actually populate, whereas the V2 table only ever holds the
 * series the user has opened.
 *
 * The two-stage match is the part worth keeping together: a SQL `LIKE` on a punctuation-free
 * fragment casts a deliberately wide net (raw names contain it verbatim across variants), then the
 * normalized-title equality drops lookalikes ("Title: Origins" ≠ "Title") and the year check keeps
 * "Nemesis (2024)" from merging with "Nemesis (2026)". Loosening either stage alone silently
 * re-merges different shows.
 *
 * Bodies moved verbatim from `XtreamSeriesRepositoryV2`.
 */
internal class SeriesSiblingFinder(
    private val legacySeriesDao: com.tvonnet.debridxtreamiptv.data.local.dao.SeriesDao,
    private val seriesDao: SeriesDaoV2,
) {

    /** Find every listing of the same show across the legacy catalog. */
    suspend fun find(primarySeriesId: String): List<SiblingListing> {
        val legacyPrimary = legacySeriesDao.getSeriesById(primarySeriesId)
        val primaryName = (legacyPrimary?.name?.trim()
            ?: seriesDao.getSeriesById(primarySeriesId)?.name?.trim()).orEmpty()

        val candidates = withCompatibleYear(matchByName(primaryName), SeriesTitleMatching.yearOf(primaryName))
        val all = toSiblings((candidates + listOfNotNull(legacyPrimary)).distinctBy { it.seriesId })

        return all.ifEmpty {
            // Legacy table empty (e.g. fresh install, detail opened via deep link):
            // fall back to the primary series alone from the V2 table.
            val v2 = seriesDao.getSeriesById(primarySeriesId) ?: return@ifEmpty emptyList()
            listOf(SiblingListing(v2.seriesId, v2.name, listOf(v2.categoryId)))
        }
    }

    /**
     * Same match keyed on a plain display title (e.g. the TMDB name used by the Debrid detail
     * screen) instead of an Xtream series id. Has no primary row to fall back on, so an unmatched
     * title yields nothing rather than a one-element list.
     */
    suspend fun findByTitle(title: String, yearHint: String?): List<SiblingListing> {
        val primaryYear = SeriesTitleMatching.yearOf(title) ?: yearHint?.take(4)?.toIntOrNull()
        val nameMatched = matchByName(title)
        val siblings = withCompatibleYear(nameMatched, primaryYear)
        android.util.Log.i(
            "SeriesRepoV2",
            "findByTitle: title=$title year=$primaryYear " +
                "nameMatched=${nameMatched.size} afterYearGuard=${siblings.size}"
        )
        return toSiblings(siblings)
    }

    /**
     * The LIKE pool casts a wide net with a punctuation-free fragment of the title (raw names
     * contain it verbatim across variants); the normalized equality check then drops lookalikes
     * such as "Title: Origins" vs "Title". Returns empty for titles too short to match safely.
     */
    private suspend fun matchByName(name: String): List<SeriesEntity> {
        val core = SeriesTitleMatching.normalizeCoreTitle(name)
        val likeToken = SeriesTitleMatching.likeSearchToken(name)
        if (core.length < 4 || likeToken.length < 4) return emptyList()
        return legacySeriesDao.findSeriesByNameLike(likeToken)
            .filter { SeriesTitleMatching.normalizeCoreTitle(it.name.orEmpty()) == core }
    }

    /** Keeps "Nemesis (2024)" from merging with "Nemesis (2026)". */
    private fun withCompatibleYear(rows: List<SeriesEntity>, primaryYear: Int?): List<SeriesEntity> =
        rows.filter {
            SeriesTitleMatching.yearsCompatible(primaryYear, SeriesTitleMatching.yearOf(it.name.orEmpty()))
        }

    private fun toSiblings(rows: List<SeriesEntity>): List<SiblingListing> = rows.map { row ->
        val cats: List<String?> = row.category_ids
            .filter { it.isNotBlank() }
            .ifEmpty { listOf(row.categoryId) }
        SiblingListing(row.seriesId, row.name, cats)
    }

    /** Number of distinct category listings this show appears in (for the hero summary). */
    suspend fun countCategoryListings(seriesId: String): Int = withContext(Dispatchers.IO) {
        val distinct = find(seriesId)
            .flatMap { it.categoryIds }
            .filterNotNull()
            .toSet().size
        maxOf(1, distinct)
    }
}
