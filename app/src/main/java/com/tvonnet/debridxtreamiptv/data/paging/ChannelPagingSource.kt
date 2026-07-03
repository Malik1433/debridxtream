package com.tvonnet.debridxtreamiptv.data.paging

import com.tvonnet.debridxtreamiptv.data.Result
import com.tvonnet.debridxtreamiptv.data.model.XtreamStream
import com.tvonnet.debridxtreamiptv.data.repository.XtreamRepository

/**
 * PagingSource for Live TV channels
 * Uses BasePagingSource to reduce code duplication
 * Supports search filtering by channel name or number
 */
class ChannelPagingSource(
    repository: XtreamRepository,
    categoryId: String,
    private val searchQuery: String = ""
) : BasePagingSource<XtreamStream>(repository, categoryId) {

    override fun treatErrorAsEmpty(): Boolean = false
    
    override suspend fun fetchFromApi(categoryId: String): Result<List<XtreamStream>> {
        val result = repository.fetchLiveStreamsForCategoryStrict(categoryId)

        // Filter by search query if provided
        if (searchQuery.isNotBlank()) {
            return when (result) {
                is Result.Success -> {
                    val filtered = result.data.filter { stream ->
                        // Search by channel name (case-insensitive)
                        stream.name?.contains(searchQuery, ignoreCase = true) == true ||
                        // Search by channel number
                        stream.num?.toString()?.contains(searchQuery) == true
                    }
                    Result.Success(mergeWithGlobalMatches(filtered))
                }
                is Result.Error -> {
                    // Category fetch failed but the global index may still answer.
                    val globalMatches = globalSearchMatches()
                    if (globalMatches.isNotEmpty()) Result.Success(globalMatches) else result
                }
                is Result.Loading -> result
            }
        }

        return result
    }

    /**
     * Search must also cover channels from categories the user never opened —
     * the search index keeps the full channel list in Room, so merge those
     * global name matches after the current category's matches (category
     * first to preserve familiar ordering; duplicates removed by stream id).
     */
    private suspend fun mergeWithGlobalMatches(categoryMatches: List<XtreamStream>): List<XtreamStream> {
        val globalMatches = globalSearchMatches()
        if (globalMatches.isEmpty()) return categoryMatches
        return (categoryMatches + globalMatches).distinctBy { it.stream_id ?: "${it.num}_${it.name}" }
    }

    private suspend fun globalSearchMatches(): List<XtreamStream> {
        return try {
            repository.searchLive(searchQuery)
        } catch (e: Exception) {
            emptyList()
        }
    }
    
    override fun getLogTag(): String = "ChannelPagingSource"
}
