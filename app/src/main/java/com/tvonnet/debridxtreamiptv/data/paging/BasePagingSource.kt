package com.tvonnet.debridxtreamiptv.data.paging

import android.util.Log
import androidx.paging.PagingSource
import androidx.paging.PagingState
import com.tvonnet.debridxtreamiptv.data.Result
import com.tvonnet.debridxtreamiptv.data.repository.XtreamRepository

/**
 * Base PagingSource to reduce code duplication
 * All PagingSources (Channel, VOD, Series) share the same pagination logic
 * 
 * @param T The type of items to page (XtreamStream, XtreamVodInfo, XtreamSeriesInfo)
 */
abstract class BasePagingSource<T : Any>(
    protected val repository: XtreamRepository,
    protected val categoryId: String
) : PagingSource<Int, T>() {

    private var cachedItems: List<T>? = null

    /**
     * Some screens historically treated network failures as an empty list (soft-fail).
     * Live TV needs real error propagation so UI can show an error state + retry.
     */
    protected open fun treatErrorAsEmpty(): Boolean = true
    
    /**
     * Abstract method to fetch items from API
     * Each subclass implements this to fetch their specific data type
     */
    protected abstract suspend fun fetchFromApi(categoryId: String): Result<List<T>>
    
    /**
     * Abstract method to get the tag name for logging
     */
    protected abstract fun getLogTag(): String
    
    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, T> {
        return try {
            val startIndex = params.key ?: 0
            val loadSize = params.loadSize

            // Fetch items for this category if not cached
            ensureItemsCached()?.let { return it }

            val allItems = cachedItems ?: emptyList()
            
            val safeStart = startIndex.coerceIn(0, allItems.size)
            val endIndex = minOf(safeStart + loadSize, allItems.size)
            val items = if (safeStart < allItems.size) allItems.subList(safeStart, endIndex) else emptyList()
            
            Log.d(getLogTag(), "Load [$safeStart..$endIndex): Returning ${items.size} items (total: ${allItems.size})")
            
            LoadResult.Page(
                data = items,
                prevKey = if (safeStart <= 0) null else maxOf(0, safeStart - loadSize),
                nextKey = if (endIndex < allItems.size) endIndex else null
            )
        } catch (e: Exception) {
            Log.e(getLogTag(), "Load error", e)
            LoadResult.Error(e)
        }
    }

    // Fills cachedItems on first load; a non-null return is the error to propagate from load().
    private suspend fun ensureItemsCached(): LoadResult.Error<Int, T>? {
        if (cachedItems != null) return null
        Log.d(getLogTag(), "Fetching items for category: $categoryId")
        val result = fetchFromApi(categoryId)

        cachedItems = when (result) {
            is Result.Success -> {
                Log.d(getLogTag(), "Fetched ${result.data.size} items")
                result.data
            }
            is Result.Error -> {
                Log.e(getLogTag(), "Error fetching items", result.exception)
                if (treatErrorAsEmpty()) {
                    emptyList()
                } else {
                    return LoadResult.Error(result.exception)
                }
            }
            else -> emptyList()
        }
        return null
    }

    override fun getRefreshKey(state: PagingState<Int, T>): Int? {
        val anchor = state.anchorPosition ?: return null
        val page = state.closestPageToPosition(anchor) ?: return null
        return page.prevKey?.let { it + state.config.pageSize }
            ?: page.nextKey?.let { maxOf(0, it - state.config.pageSize) }
    }
}
