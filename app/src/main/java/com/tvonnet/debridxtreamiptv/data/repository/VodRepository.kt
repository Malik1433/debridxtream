package com.tvonnet.debridxtreamiptv.data.repository

import android.util.Log
import com.tvonnet.debridxtreamiptv.data.Result
import com.tvonnet.debridxtreamiptv.data.cache.CacheHelper
import com.tvonnet.debridxtreamiptv.data.cache.CacheManager
import com.tvonnet.debridxtreamiptv.data.local.dao.VodDao
import com.tvonnet.debridxtreamiptv.data.local.entity.toVodEntity
import com.tvonnet.debridxtreamiptv.data.local.entity.toXtreamVodInfo
import com.tvonnet.debridxtreamiptv.data.model.IptvCache
import com.tvonnet.debridxtreamiptv.data.model.VodCacheData
import com.tvonnet.debridxtreamiptv.data.model.XtreamCategory
import com.tvonnet.debridxtreamiptv.data.model.XtreamVodInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import retrofit2.HttpException
import java.util.Locale
import kotlin.coroutines.coroutineContext
import kotlin.math.abs

/**
 * Phase 7B-4: the VOD (movies) data domain lifted out of the XtreamRepository god-class. It fetches VOD
 * categories + lazy per-category movie lists (bounded by a fetch semaphore, DB-backed), resolves a
 * movie by id, and aggregates the multi-source picker (getMovieSources) across categories using the
 * pure matching helpers in SourceModels.kt. It reads the live XtreamSession and shares the process-wide
 * CatalogCache (memoryCache, perCategoryVodCache, vodFetchLocks) + CacheHelper. Bodies moved verbatim.
 */
internal class VodRepository(
    private val session: XtreamSession,
    private val cacheManager: CacheManager,
    private val vodDao: VodDao,
    private val catalogCache: CatalogCache,
    private val cacheHelper: CacheHelper
) {
    private val apiService get() = session.apiService
    private val username: String get() = session.username
    private val password: String get() = session.password
    private var memoryCache: IptvCache?
        get() = catalogCache.memoryCache
        set(value) { catalogCache.memoryCache = value }
    private val perCategoryVodCache get() = catalogCache.perCategoryVodCache
    private val vodFetchLocks get() = catalogCache.vodFetchLocks
    private val vodFetchSemaphore = Semaphore(MAX_CONCURRENT_VOD_FETCHES)
    private fun readCache(): IptvCache? = catalogCache.readCache()
    private suspend fun prewarmCache(): IptvCache? = withContext(Dispatchers.IO) { readCache() }

    suspend fun fetchVodCategoriesAndStreams(): Result<VodCacheData> {
        return try {
            val categoriesResponse = apiService?.getVodCategories(username, password)
            val categories = if (categoriesResponse?.isSuccessful == true) {
                categoriesResponse.body() ?: emptyList()
            } else {
                emptyList()
            }
            
            Log.d(TAG, "VOD categories fetched: ${categories.size}")
            
            // Don't fetch ALL streams at login - too slow and memory intensive
            // Instead, fetch streams per category when needed (lazy loading)
            // For now, return empty streams list
            Result.Success(VodCacheData(categories, emptyList()))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch VOD data", e)
            Result.Success(VodCacheData(emptyList(), emptyList())) // Return empty, don't fail
        }
    }

    suspend fun fetchLatestVodStreams(categories: List<XtreamCategory>): List<XtreamVodInfo> {
        val firstCategoryId = categories.firstOrNull()?.category_id ?: return emptyList()
        val result = fetchVodStreamsForCategory(firstCategoryId)
        return when (result) {
            is Result.Success -> {
                result.data
                    .sortedByDescending { it.added?.toLongOrNull() ?: 0L }
                    .take(LATEST_VOD_LIMIT)
            }
            else -> emptyList()
        }
    }
    
    // New method: Fetch VOD streams for specific category (lazy loading)
    suspend fun fetchVodStreamsForCategory(categoryId: String): Result<List<XtreamVodInfo>> {
        coroutineContext.ensureActive()
        perCategoryVodCache[categoryId]?.let { cached ->
            return Result.Success(cached)
        }

        val lock = vodFetchLocks.getOrPut(categoryId) { Mutex() }
        return lock.withLock {
            perCategoryVodCache[categoryId]?.let { cached ->
                return Result.Success(cached)
            }

            // B-8: a fresh synced copy in Room is served as-is — no network refetch, no
            // delete+insert rewrite of an unchanged category. Stale/empty falls through.
            freshDbCopyOrNull(categoryId)?.let { cached ->
                perCategoryVodCache[categoryId] = cached
                Log.d(TAG, "B-8: category $categoryId fresh in DB (${cached.size} movies), skipping refetch")
                return Result.Success(cached)
            }

            val service = apiService ?: return Result.Error(Exception("API service not initialized"))

            return try {
                coroutineContext.ensureActive()
                vodFetchSemaphore.withPermit {
                    Log.d(TAG, "Fetching VOD streams for category: $categoryId")
                    val response = service.getVodStreams(username, password, categoryId = categoryId)
                    if (response.isSuccessful) {
                        val streams = persistFetchedCategory(categoryId, response.body().orEmpty())
                        perCategoryVodCache[categoryId] = streams
                        Log.d(TAG, "VOD streams fetched for category $categoryId: ${streams.size} movies")
                        Result.Success(streams)
                    } else {
                        Log.e(TAG, "Failed to fetch VOD streams: ${response.code()}")
                        Result.Error(HttpException(response))
                    }
                }
            } catch (e: CancellationException) {
                Log.d(TAG, "VOD fetch for category $categoryId cancelled")
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Error fetching VOD streams for category $categoryId", e)
                Result.Error(e)
            }
        }
    }
    
    /**
     * B-8 fresh path: the category's Room copy, but only when it is recent enough
     * ([CategoryFetchFreshness]) and non-empty. Null means "go to the network".
     */
    private suspend fun freshDbCopyOrNull(categoryId: String): List<XtreamVodInfo>? {
        return try {
            val freshness = vodDao.getCategoryFreshness(categoryId)
            if (!CategoryFetchFreshness.isFresh(
                    freshness.rowCount, freshness.newestCachedAt, System.currentTimeMillis()
                )
            ) {
                return null
            }
            vodDao.getMoviesByCategorySync(categoryId)
                .map { it.toXtreamVodInfo() }
                .takeIf { it.isNotEmpty() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "B-8 freshness check failed for category $categoryId, falling through to network", e)
            null
        }
    }

    /**
     * Persists a fetched category — unless the response is empty while Room still holds rows,
     * in which case the rows are kept and served (never let an empty refresh destroy good data,
     * the SY-1 rule). Returns the list callers should treat as the category's content.
     */
    private suspend fun persistFetchedCategory(
        categoryId: String,
        streams: List<XtreamVodInfo>
    ): List<XtreamVodInfo> {
        if (streams.isEmpty() && vodDao.getCategoryFreshness(categoryId).rowCount > 0) {
            Log.w(TAG, "Empty VOD response for category $categoryId, keeping existing rows")
            return vodDao.getMoviesByCategorySync(categoryId).map { it.toXtreamVodInfo() }
        }
        try {
            val entities = streams.map { it.toVodEntity(categoryId) }
            vodDao.replaceMoviesForCategory(categoryId, entities)
            Log.d(TAG, "Saved ${entities.size} movies to DB for category $categoryId (Atomic Replace)")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save movies to DB", e)
        }
        return streams
    }

    suspend fun ensureVodCategories(): List<XtreamCategory> {
        cacheManager?.getCategories("vod")?.let { cached ->
            if (cached.isNotEmpty()) {
                return cached
            }
        }

        val cacheVodCategories = prewarmCache()?.vod?.categories.orEmpty()
        if (cacheVodCategories.isNotEmpty()) {
            try {
                cacheManager?.putCategories(cacheVodCategories, "vod")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to store cached VOD categories into CacheManager", e)
            }
            return cacheVodCategories
        }

        val service = apiService ?: return emptyList()
        return try {
            val response = service.getVodCategories(username, password)
            if (response.isSuccessful) {
                val categories = response.body().orEmpty()
                if (categories.isNotEmpty()) {
                    cacheFetchedVodCategories(categories)
                }
                categories
            } else {
                Log.e(TAG, "Failed to fetch VOD categories: ${response.code()}")
                emptyList()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching VOD categories", e)
            emptyList()
        }
    }

    private suspend fun cacheFetchedVodCategories(categories: List<XtreamCategory>) {
        try {
            cacheManager?.putCategories(categories, "vod")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to persist VOD categories into CacheManager", e)
        }
        updateVodCategoriesCache(categories)
    }

    private suspend fun updateVodCategoriesCache(categories: List<XtreamCategory>) {
        try {
            val existingCache = readCache()
            val existingVodStreams = existingCache?.vod?.streams ?: emptyList()
            val updatedVodData = VodCacheData(
                categories = categories,
                streams = existingVodStreams
            )
            val updatedCache = if (existingCache != null) {
                existingCache.copy(
                    timestamp = System.currentTimeMillis(),
                    vod = updatedVodData
                )
            } else {
                IptvCache(
                    timestamp = System.currentTimeMillis(),
                    live = null,
                    vod = updatedVodData,
                    series = null,
                    epg = null
                )
            }
            memoryCache = updatedCache
            withContext(Dispatchers.IO) { cacheHelper.writeCache(updatedCache) }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to persist VOD categories to cache", e)
        }
    }

    private suspend fun getVodStreamsForCategoryCached(categoryId: String): List<XtreamVodInfo> {
        perCategoryVodCache[categoryId]?.let { return it }
        return when (val result = fetchVodStreamsForCategory(categoryId)) {
            is Result.Success -> result.data.also { perCategoryVodCache[categoryId] = it }
            is Result.Error -> emptyList()
            is Result.Loading -> emptyList()
        }
    }

    private suspend fun findVodById(
        streamId: String,
        hintCategoryId: String?,
        categories: List<XtreamCategory>
    ): XtreamVodInfo? {
        coroutineContext.ensureActive()
        perCategoryVodCache.values.forEach { list ->
            list.find { it.stream_id == streamId }?.let { return it }
        }

        memoryCache?.vod?.streams?.find { it.stream_id == streamId }?.let { return it }

        if (!hintCategoryId.isNullOrBlank()) {
            getVodStreamsForCategoryCached(hintCategoryId).find { it.stream_id == streamId }?.let {
                return it
            }
        }

        for (category in categories) {
            coroutineContext.ensureActive()
            val categoryId = category.category_id ?: continue
            if (categoryId == hintCategoryId) continue
            val streams = getVodStreamsForCategoryCached(categoryId)
            streams.find { it.stream_id == streamId }?.let { return it }
        }
        return null
    }

    suspend fun getMovieSources(
        streamId: String?,
        title: String?,
        primaryCategoryId: String?,
        yearHint: String?
    ): List<MovieSource> {
        val categories = ensureVodCategories()
        if (categories.isEmpty()) return emptyList()

        val categoriesById = categories.associateBy { it.category_id.orEmpty() }
        val candidateOrder = LinkedHashSet<String>()
        primaryCategoryId?.takeIf { it.isNotBlank() }?.let { candidateOrder.add(it) }

        val primaryStream = streamId?.takeIf { it.isNotBlank() }?.let {
            findVodById(it, primaryCategoryId, categories)
        }

        primaryStream?.category_id?.takeIf { !it.isNullOrBlank() }?.let { candidateOrder.add(it) }
        primaryStream?.category_ids?.orEmpty()?.forEach { candidateOrder.add(it.toString()) }

        val normalizedTargetName = normalizeTitle(primaryStream?.name ?: title)
        val targetYear = extractYear(primaryStream?.releaseDate) ?: extractYear(yearHint)

        if (candidateOrder.isEmpty()) {
            categories.mapNotNull { it.category_id }.forEach { candidateOrder.add(it) }
        } else {
            categories.mapNotNull { it.category_id }
                .filterNot { candidateOrder.contains(it) }
                .forEach { candidateOrder.add(it) }
        }

        val sources = LinkedHashMap<String, MovieSource>()
        val comparator = compareBy<MovieSource> { !it.isPrimary }
            .thenBy { it.label.lowercase(Locale.US) }

        var lookedUpCategories = 0
        for (categoryId in candidateOrder) {
            coroutineContext.ensureActive()
            if (lookedUpCategories >= MAX_MOVIE_SOURCE_LOOKUPS && sources.isNotEmpty()) {
                break
            }
            lookedUpCategories++
            if (categoryId.isBlank()) continue
            val streams = getVodStreamsForCategoryCached(categoryId)
            if (streams.isEmpty()) continue
            val category = categoriesById[categoryId]
            for (stream in streams) {
                val candidateId = stream.stream_id ?: continue
                if (sources.containsKey(candidateId)) continue

                val matchesId = streamId != null && streamId == candidateId
                val candidateNormalized = normalizeTitle(stream.name)
                val matchesName = normalizedTargetName != null && normalizedTargetName == candidateNormalized

                if (!matchesId && !matchesName) continue

                if (!matchesId && yearVetoesNameMatch(stream, targetYear)) continue

                val label = buildMovieSourceLabel(category, stream)
                sources[candidateId] = MovieSource(
                    stream = stream,
                    category = category,
                    label = label,
                    isPrimary = matchesId
                )
            }
        }

        if (sources.isEmpty() && primaryStream != null) {
            val category = primaryStream.category_id?.let { categoriesById[it] }
            val label = buildMovieSourceLabel(category, primaryStream)
            val key = primaryStream.stream_id ?: primaryStream.name ?: "primary"
            sources[key] = MovieSource(
                stream = primaryStream,
                category = category,
                label = label,
                isPrimary = true
            )
        }

        return sources.values.sortedWith(comparator)
    }

    /** A name-only match is rejected when both sides carry a year and they differ by more than 1. */
    private fun yearVetoesNameMatch(stream: XtreamVodInfo, targetYear: Int?): Boolean {
        val candidateYear = extractYear(stream.releaseDate)
        return targetYear != null && candidateYear != null && abs(candidateYear - targetYear) > 1
    }

    suspend fun getVodById(streamId: String): XtreamVodInfo? {
        val cache = memoryCache
            ?: cacheHelper.memorySnapshot()?.also { memoryCache = it }
            ?: withContext(Dispatchers.IO) { cacheHelper.readCache() }?.also { memoryCache = it }
        val memoryMatch = cache?.vod?.streams?.find { it.stream_id == streamId }
        if (memoryMatch != null) return memoryMatch

        // Fallback: Check local per-category cache
        perCategoryVodCache.values.flatten().find { it.stream_id == streamId }?.let { return it }

        // Fallback: Check Room
        return vodDao?.getVodById(streamId)?.toXtreamVodInfo()
    }

    suspend fun searchVod(query: String, categoryId: String? = null): List<XtreamVodInfo> {
        return vodDao?.searchMovies(query, categoryId)?.map { it.toXtreamVodInfo() } ?: emptyList()
    }

    private companion object {
        private const val TAG = "XtreamRepository"
        private const val MAX_CONCURRENT_VOD_FETCHES = 4
        private const val MAX_MOVIE_SOURCE_LOOKUPS = 10
        private const val LATEST_VOD_LIMIT = 24
    }
}
