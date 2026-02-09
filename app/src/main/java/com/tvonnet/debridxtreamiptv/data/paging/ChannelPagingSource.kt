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
                    Result.Success(filtered)
                }
                is Result.Error -> result
                is Result.Loading -> result
            }
        }
        
        return result
    }
    
    override fun getLogTag(): String = "ChannelPagingSource"
}
