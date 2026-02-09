package com.tvonnet.debridxtreamiptv.features.seriesv2.data.repository

import androidx.paging.ExperimentalPagingApi
import androidx.paging.LoadType
import androidx.paging.PagingState
import androidx.paging.RemoteMediator
import androidx.room.withTransaction
import com.tvonnet.debridxtreamiptv.data.local.AppDatabase
import com.tvonnet.debridxtreamiptv.data.remote.XtreamApiService
import com.tvonnet.debridxtreamiptv.features.seriesv2.data.model.SeriesEntityV2
import retrofit2.HttpException
import java.io.IOException

@OptIn(ExperimentalPagingApi::class)
class SeriesRemoteMediatorV2(
    private val categoryId: String,
    private val username: String,
    private val password: String,
    private val apiService: XtreamApiService,
    private val database: AppDatabase
) : RemoteMediator<Int, SeriesEntityV2>() {

    override suspend fun load(
        loadType: LoadType,
        state: PagingState<Int, SeriesEntityV2>
    ): MediatorResult {
        return try {
            val loadKey = when (loadType) {
                LoadType.REFRESH -> null
                LoadType.PREPEND -> return MediatorResult.Success(endOfPaginationReached = true)
                LoadType.APPEND -> return MediatorResult.Success(endOfPaginationReached = true)
            }

            val response = apiService.getSeries(
                username = username,
                password = password,
                categoryId = categoryId,
                action = "get_series"
            )

            if (response.isSuccessful) {
                val seriesList = response.body() ?: emptyList()

                database.withTransaction {
                    if (loadType == LoadType.REFRESH) {
                        database.seriesDaoV2().clearByCategory(categoryId)
                    }

                    val entities = seriesList.mapIndexed { index, dto ->
                        SeriesEntityV2(
                            seriesId = dto.series_id.toString(),
                            num = dto.num,
                            name = dto.name,
                            title = dto.name, // 'title' not in XtreamSeriesInfo, use name
                            year = dto.releaseDate?.take(4), // 'year' not in XtreamSeriesInfo
                            streamType = "series", // 'stream_type' not in XtreamSeriesInfo
                            cover = dto.cover,
                            plot = dto.plot,
                            cast = dto.cast,
                            director = dto.director,
                            genre = dto.genre,
                            releaseDate = dto.releaseDate,
                            rating = dto.rating,
                            rating5based = dto.rating_5based,
                            backdropPath = dto.backdropPathList, // Use helper property
                            youtubeTrailer = dto.youtube_trailer,
                            episodeRunTime = null, // Not available in list info
                            categoryId = categoryId, // Force current category
                            lastModified = null // Not available in list info
                        )
                    }
                    database.seriesDaoV2().insertAll(entities)
                }

                MediatorResult.Success(endOfPaginationReached = true)
            } else {
                MediatorResult.Error(HttpException(response))
            }
        } catch (e: IOException) {
            MediatorResult.Error(e)
        } catch (e: HttpException) {
            MediatorResult.Error(e)
        }
    }
}
