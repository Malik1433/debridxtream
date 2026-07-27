package com.tvonnet.debridxtreamiptv.features.seriesv2.data.repository

import com.tvonnet.debridxtreamiptv.features.seriesv2.data.dao.EpisodeDaoV2
import com.tvonnet.debridxtreamiptv.features.seriesv2.ui.model.IptvStreamGroup
import com.tvonnet.debridxtreamiptv.features.seriesv2.ui.model.IptvStreamOption
import com.tvonnet.debridxtreamiptv.features.seriesv2.ui.model.StreamLanguage
import com.tvonnet.debridxtreamiptv.features.seriesv2.ui.model.StreamQuality
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * The single thing the aggregator needs from the sync pipeline: make sure a sibling's episodes are
 * in the database, and say whether that succeeded. Narrow on purpose — the aggregator must not be
 * able to trigger a full detail sync, and this is what lets its logic be tested without a provider.
 */
internal interface EpisodeCacheWarmer {
    suspend fun ensureEpisodesCached(seriesId: String): Boolean
}

/**
 * C5: builds the "SELECT STREAM" panel — the same episode offered from every category the show is
 * listed under.
 *
 * **DB-only and effectively instant.** Siblings whose episodes are already cached get a ready
 * `playUrl`; the rest stay unresolved (`playUrl` empty) and are fetched one at a time only when
 * the user actually picks one ([resolveStreamOption]) or when the background sweep runs
 * ([verifyStreamGroups]). It must never fetch all siblings up front: 13+ parallel
 * `get_series_info` calls froze the panel for ~30s and OOM-killed the app, which is why the sweep
 * is gated to [SWEEP_CONCURRENCY].
 *
 * The sweep also **fails open**: a row whose fetch failed (throttling, network) stays in the list
 * unresolved rather than being dropped. Dropping on failure once emptied whole panels during
 * provider throttling; only a *successful* fetch that still lacks the episode removes a row.
 *
 * Bodies moved verbatim from `XtreamSeriesRepositoryV2`.
 */
internal class SeriesStreamAggregator(
    private val episodeDao: EpisodeDaoV2,
    private val categoryDao: com.tvonnet.debridxtreamiptv.data.local.dao.CategoryDao,
    private val siblingFinder: SeriesSiblingFinder,
    private val cacheWarmer: EpisodeCacheWarmer,
    private val streamUrl: (episodeId: String, extension: String?) -> String,
) {
    companion object {
        /** 13+ parallel sibling fetches once OOM-killed the app. */
        private const val SWEEP_CONCURRENCY = 2
    }

    /** Build the grouped stream list for a specific episode of a known series id. */
    suspend fun aggregateEpisodeStreams(
        primarySeriesId: String,
        seasonNumber: Int,
        episodeNumber: Int
    ): List<IptvStreamGroup> = withContext(Dispatchers.IO) {
        val siblings = siblingFinder.find(primarySeriesId)
        android.util.Log.i(
            "SeriesRepoV2",
            "aggregateEpisodeStreams: primary=$primarySeriesId S$seasonNumber E$episodeNumber " +
                "siblings=${siblings.map { "${it.seriesId}:${it.name}~cats=${it.categoryIds}" }}"
        )
        val primaryCats = siblings.firstOrNull { it.seriesId == primarySeriesId }
            ?.categoryIds?.filterNotNull()?.toSet().orEmpty()
        buildStreamGroups(siblings, seasonNumber, episodeNumber, primaryCats)
    }

    /**
     * Same aggregation keyed on a plain display title (e.g. the TMDB name used by the
     * Debrid detail screen) instead of an Xtream series id. DB-only and instant.
     */
    suspend fun aggregateStreamsForTitle(
        title: String,
        seasonNumber: Int,
        episodeNumber: Int,
        yearHint: String? = null
    ): List<IptvStreamGroup> = withContext(Dispatchers.IO) {
        val siblings = siblingFinder.findByTitle(title, yearHint)
        buildStreamGroups(siblings, seasonNumber, episodeNumber, primaryCats = emptySet())
    }

    private suspend fun buildStreamGroups(
        siblings: List<SiblingListing>,
        seasonNumber: Int,
        episodeNumber: Int,
        primaryCats: Set<String>
    ): List<IptvStreamGroup> {
        val order = mutableListOf<String>()
        val streamsByKey = linkedMapOf<String, MutableList<IptvStreamOption>>()
        val categoryByKey = mutableMapOf<String, Pair<String?, String>>()

        for (sib in siblings) {
            // Local lookup only — null just means "not cached yet", not "unavailable".
            val ep = episodeDao.getEpisode(sib.seriesId, seasonNumber, episodeNumber)

            for (catId in sib.categoryIds.distinct()) {
                val categoryName = categoryNameFor(catId)
                val key = catId ?: "other"
                val bucket = streamsByKey.getOrPut(key) {
                    order.add(key)
                    categoryByKey[key] = catId to categoryName
                    mutableListOf()
                }
                val option = streamOption(sib, ep, categoryName, key)
                if (bucket.none { it.sourceSeriesId == option.sourceSeriesId }) bucket.add(option)
            }
        }

        // Primary listing's categories lead the list.
        val sortedKeys = order.sortedByDescending { key -> if (key in primaryCats) 1 else 0 }

        return sortedKeys.mapNotNull { key ->
            val streams = streamsByKey[key]?.sortedByDescending { it.quality.rank }.orEmpty()
            if (streams.isEmpty()) return@mapNotNull null
            val (catId, catName) = categoryByKey[key] ?: (null to "All Series")
            IptvStreamGroup(
                categoryId = catId,
                categoryName = catName,
                categoryTag = SeriesTitleMatching.shortCategoryTag(catName, streams.first().name),
                streams = streams
            )
        }
    }

    private suspend fun categoryNameFor(catId: String?): String =
        catId?.let { categoryDao.getCategoryById(it)?.categoryName }?.takeIf { it.isNotBlank() }
            ?: catId?.let { "Category $it" }
            ?: "All Series"

    /**
     * An uncached sibling still gets a row — with a `pending:` id and a blank `playUrl`, which is
     * the marker [verifyStreamGroups] and [resolveStreamOption] look for.
     */
    private fun streamOption(
        sib: SiblingListing,
        ep: com.tvonnet.debridxtreamiptv.features.seriesv2.data.model.EpisodeEntityV2?,
        categoryName: String,
        key: String,
    ): IptvStreamOption {
        val tag = SeriesTitleMatching.shortCategoryTag(categoryName, sib.name)
        return IptvStreamOption(
            streamId = ep?.episodeId ?: "pending:${sib.seriesId}:$key",
            playUrl = ep?.let { streamUrl(it.episodeId, it.containerExtension) } ?: "",
            containerExtension = ep?.containerExtension,
            sourceSeriesId = sib.seriesId,
            name = (sib.name ?: ep?.title ?: "Stream"),
            quality = StreamQuality.fromName(sib.name ?: ep?.title),
            container = ep?.let { (it.containerExtension ?: "mp4").uppercase() } ?: "",
            languages = StreamLanguage.inferFrom(sib.name ?: ep?.title, tag),
            categoryTag = tag
        )
    }

    /**
     * Background availability sweep: resolve every unresolved option (throttled to
     * [SWEEP_CONCURRENCY] concurrent fetches) and drop the listings that genuinely lack this
     * episode. Returns the cleaned groups; results persist in the DB so later panels are fully
     * resolved instantly.
     */
    suspend fun verifyStreamGroups(
        groups: List<IptvStreamGroup>,
        seasonNumber: Int,
        episodeNumber: Int
    ): List<IptvStreamGroup> = withContext(Dispatchers.IO) {
        val pendingIds = groups.flatMap { it.streams }
            .filter { it.playUrl.isBlank() }
            .map { it.sourceSeriesId }
            .distinct()
        if (pendingIds.isEmpty()) return@withContext groups

        val gate = Semaphore(SWEEP_CONCURRENCY)
        val sweepResults = coroutineScope {
            pendingIds.map { id ->
                async {
                    gate.withPermit {
                        val fetched = cacheWarmer.ensureEpisodesCached(id)
                        id to (fetched to episodeDao.getEpisode(id, seasonNumber, episodeNumber))
                    }
                }
            }.awaitAll().toMap()
        }

        groups.mapNotNull { group ->
            val streams = group.streams.mapNotNull { opt ->
                if (opt.playUrl.isNotBlank()) return@mapNotNull opt
                val (fetchOk, ep) = sweepResults[opt.sourceSeriesId] ?: return@mapNotNull opt
                when {
                    ep != null -> opt.resolvedWith(ep)
                    // Fetched fine, episode definitively absent → drop the dead row.
                    fetchOk -> null
                    // Fetch failed (throttling, network) — fail OPEN: keep the row
                    // unresolved so it can still resolve on click. Dropping here once
                    // emptied whole panels during provider throttling.
                    else -> opt
                }
            }
            if (streams.isEmpty()) null else group.copy(streams = streams)
        }
    }

    /**
     * Resolve a stream option to a playable URL. Already-resolved options pass
     * through; unresolved ones trigger a single episode fetch for that sibling only.
     * Returns null when the sibling listing genuinely lacks this episode.
     */
    suspend fun resolveStreamOption(
        option: IptvStreamOption,
        seasonNumber: Int,
        episodeNumber: Int
    ): IptvStreamOption? = withContext(Dispatchers.IO) {
        if (option.playUrl.isNotBlank()) return@withContext option
        cacheWarmer.ensureEpisodesCached(option.sourceSeriesId)
        val ep = episodeDao.getEpisode(option.sourceSeriesId, seasonNumber, episodeNumber)
            ?: return@withContext null
        option.resolvedWith(ep)
    }

    /**
     * Resolve an IPTV listing's playable URL for a specific episode. Used by the
     * Debrid detail screen when an injected IPTV source is picked.
     * Returns (playUrl, containerExtension, episodeId) or null when unavailable.
     */
    suspend fun resolveIptvPlayUrl(
        sourceSeriesId: String,
        seasonNumber: Int,
        episodeNumber: Int
    ): Triple<String, String?, String>? = withContext(Dispatchers.IO) {
        cacheWarmer.ensureEpisodesCached(sourceSeriesId)
        val ep = episodeDao.getEpisode(sourceSeriesId, seasonNumber, episodeNumber)
            ?: return@withContext null
        Triple(streamUrl(ep.episodeId, ep.containerExtension), ep.containerExtension, ep.episodeId)
    }

    private fun IptvStreamOption.resolvedWith(
        ep: com.tvonnet.debridxtreamiptv.features.seriesv2.data.model.EpisodeEntityV2
    ): IptvStreamOption = copy(
        streamId = ep.episodeId,
        playUrl = streamUrl(ep.episodeId, ep.containerExtension),
        containerExtension = ep.containerExtension,
        container = (ep.containerExtension ?: "mp4").uppercase()
    )
}
