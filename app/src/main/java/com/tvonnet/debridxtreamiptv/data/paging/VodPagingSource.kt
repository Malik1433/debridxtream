package com.tvonnet.debridxtreamiptv.data.paging

import com.tvonnet.debridxtreamiptv.data.Result
import com.tvonnet.debridxtreamiptv.data.model.XtreamVodInfo
import com.tvonnet.debridxtreamiptv.data.repository.XtreamRepository

/**
 * PagingSource for VOD (Movies)
 * Uses BasePagingSource to reduce code duplication
 */
class VodPagingSource(
    repository: XtreamRepository,
    categoryId: String
) : BasePagingSource<XtreamVodInfo>(repository, categoryId) {
    
    override suspend fun fetchFromApi(categoryId: String): Result<List<XtreamVodInfo>> {
        return repository.fetchVodStreamsForCategory(categoryId)
    }
    
    override fun getLogTag(): String = "VodPagingSource"
}

