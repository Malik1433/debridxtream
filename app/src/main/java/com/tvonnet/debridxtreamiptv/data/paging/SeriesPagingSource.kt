package com.tvonnet.debridxtreamiptv.data.paging

import com.tvonnet.debridxtreamiptv.data.Result
import com.tvonnet.debridxtreamiptv.data.model.XtreamSeriesInfo
import com.tvonnet.debridxtreamiptv.data.repository.XtreamRepository

/**
 * PagingSource for TV Series
 * Uses BasePagingSource to reduce code duplication
 */
class SeriesPagingSource(
    repository: XtreamRepository,
    categoryId: String
) : BasePagingSource<XtreamSeriesInfo>(repository, categoryId) {
    
    override suspend fun fetchFromApi(categoryId: String): Result<List<XtreamSeriesInfo>> {
        return repository.fetchSeriesForCategory(categoryId)
    }
    
    override fun getLogTag(): String = "SeriesPagingSource"
}

