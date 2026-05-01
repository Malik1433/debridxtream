package com.tvonnet.debridxtreamiptv.features.seriesv2.data.repository

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import com.tvonnet.debridxtreamiptv.data.Result
import com.tvonnet.debridxtreamiptv.data.remote.XtreamResponseParser
import com.tvonnet.debridxtreamiptv.data.remote.XtreamApiService
import com.tvonnet.debridxtreamiptv.data.repository.XtreamRepository
import com.tvonnet.debridxtreamiptv.features.seriesv2.data.dao.EpisodeDaoV2
import com.tvonnet.debridxtreamiptv.features.seriesv2.data.dao.SeriesDaoV2
import com.tvonnet.debridxtreamiptv.features.seriesv2.data.model.EpisodeEntityV2
import com.tvonnet.debridxtreamiptv.features.seriesv2.data.model.SeriesEntityV2
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class XtreamSeriesRepositoryV2 @Inject constructor(
    private val legacyRepository: XtreamRepository,
    private val seriesDao: SeriesDaoV2,
    private val episodeDao: EpisodeDaoV2,
    private val database: com.tvonnet.debridxtreamiptv.data.local.AppDatabase
) {

    // Helper to get service safely from legacy repo
    private val apiService: XtreamApiService
        get() = legacyRepository.getApiService() ?: throw IllegalStateException("API Not Initialized. Please login first.")

    fun getStreamUrl(episodeId: String, extension: String?): String {
        val server = legacyRepository.getServerUrl().trimEnd('/')
        val user = legacyRepository.getUsername()
        val pass = legacyRepository.getPassword()
        val ext = extension ?: "mp4" // Default to mp4 if null
        return "$server/series/$user/$pass/$episodeId.$ext"
    }

    fun getSeriesById(seriesId: String): Flow<Result<SeriesEntityV2>> = flow {
         val username = legacyRepository.getUsername()
         val password = legacyRepository.getPassword()
         
         android.util.Log.d("SeriesRepoV2", "getSeriesById: Fetching for ID: $seriesId")
         
         // 1. Try Local Cache (Offline First)
         val localSeries = seriesDao.getSeriesById(seriesId)
         if (localSeries != null) {
             android.util.Log.d("SeriesRepoV2", "getSeriesById: Cache HIT. Episodes: ${episodeDao.getCountForSeries(seriesId)}")
             emit(Result.Success(localSeries))
         } else {
             android.util.Log.d("SeriesRepoV2", "getSeriesById: Cache MISS")
         }

         // 2. Network Fetch Attempt
        try {
            android.util.Log.d("SeriesRepoV2", "getSeriesById: Starting Network Request...")
            val response = apiService.getSeriesInfo(username, password, seriesId = seriesId)
            android.util.Log.d("SeriesRepoV2", "getSeriesById: Network Response Code: ${response.code()}")
            
            if (response.isSuccessful) {
                val body = response.body()
                val info = body?.info
                val episodesMap = body?.episodes
                
                android.util.Log.d("SeriesRepoV2", "getSeriesById: Body Info: ${if(info!=null) "Found" else "NULL"}")
                android.util.Log.d("SeriesRepoV2", "getSeriesById: Episodes Map Size: ${episodesMap?.size ?: 0}")
                
                if (info != null) {
                    processSeriesInfoResponse(seriesId, info, episodesMap)

                    if (episodesMap.isNullOrEmpty()) {
                        android.util.Log.w("SeriesRepoV2", "getSeriesById: Primary info missing episodes. Attempting fallback fetch...")
                        fetchEpisodesFallback(seriesId, username, password)
                    }

                    // Re-fetch updated local series to emit standard result
                    val updatedSeries = seriesDao.getSeriesById(seriesId)
                    if (updatedSeries != null) emit(Result.Success(updatedSeries))
                    return@flow
                }
            } else if (response.code() == 404) {
                 android.util.Log.w("SeriesRepoV2", "getSeriesById: 404 on get_series_info. Attempting fallback to get_show_episodes...")
                 // Fallback: Fetch Episodes Only
                 fetchEpisodesFallback(seriesId, username, password)
                 
                 // Return local data if we have it (now updated with episodes)
                 val updatedSeries = seriesDao.getSeriesById(seriesId)
                 if (updatedSeries != null) {
                     emit(Result.Success(updatedSeries))
                     return@flow
                 }
            } else {
                 android.util.Log.e("SeriesRepoV2", "getSeriesById: Request Failed: ${response.message()}")
            }
        } catch (e: Exception) {
            android.util.Log.e("SeriesRepoV2", "getSeriesById: Exception: ${e.message}", e)
            if (localSeries != null) {
                return@flow
            }

            // Providers often return mixed JSON shapes that break get_series_info parsing.
            // Attempt episodes-only fallbacks before giving up.
            android.util.Log.w("SeriesRepoV2", "getSeriesById: Network exception, attempting episodes fallback...")
            fetchEpisodesFallback(seriesId, username, password)
            val updatedSeries = seriesDao.getSeriesById(seriesId)
            if (updatedSeries != null) {
                emit(Result.Success(updatedSeries))
                return@flow
            }
        }

        // 3. Final Fallback
        if (localSeries == null) {
             android.util.Log.e("SeriesRepoV2", "getSeriesById: Failure - No data available.")
             emit(Result.Error(Exception("Failed to fetch series details and no local data available")))
        }
    }.flowOn(Dispatchers.IO)
    
    private suspend fun processSeriesInfoResponse(seriesId: String, info: com.tvonnet.debridxtreamiptv.data.model.XtreamSeriesDetailInfo, episodesMap: Map<String, List<com.tvonnet.debridxtreamiptv.data.model.XtreamEpisodeInfo>>?) {
        // Map API -> Entity
        // Handle backdrop_path which can be String or List<String>
        val backdropPathList = when (info.backdrop_path) {
            is List<*> -> info.backdrop_path.filterIsInstance<String>().filter { it.isNotEmpty() }
            is String -> if (info.backdrop_path.isNotEmpty()) listOf(info.backdrop_path) else emptyList()
            else -> emptyList()
        }
        
        val seriesEntity = SeriesEntityV2(
            seriesId = seriesId,
            num = null, 
            name = info.name,
            title = info.name, 
            year = null, 
            streamType = "series",
            cover = info.cover,
            plot = info.plot,
            cast = info.cast,
            director = info.director,
            genre = info.genre,
            releaseDate = info.releaseDate,
            rating = info.rating,
            rating5based = try { info.rating_5based?.toDouble() } catch(e: Exception) { null },
            backdropPath = backdropPathList,
            youtubeTrailer = info.youtube_trailer,
            episodeRunTime = null, 
            categoryId = null, 
            lastModified = null 
        )
        
        // Map Episodes
        val episodeEntities = mapEpisodes(seriesId, episodesMap)
        
        android.util.Log.d("SeriesRepoV2", "processSeriesInfoResponse: Mapped ${episodeEntities.size} episodes.")
        // #region agent log
        android.util.Log.d("SeriesRepoV2", "processSeriesInfoResponse: EpisodesMap size=${episodesMap?.size ?: 0}, EpisodeEntities size=${episodeEntities.size}")
        if (episodeEntities.isEmpty() && !episodesMap.isNullOrEmpty()) {
            android.util.Log.w("SeriesRepoV2", "processSeriesInfoResponse: WARNING - EpisodesMap not empty but episodeEntities is empty!")
        }
        // #endregion

        // Save to DB (Atomic Transaction)
        database.withTransaction {
            seriesDao.insertAll(listOf(seriesEntity))

            val existingCount = episodeDao.getCountForSeries(seriesId)
            val shouldOverwriteEpisodes = episodeEntities.isNotEmpty() || existingCount == 0

            if (shouldOverwriteEpisodes) {
                episodeDao.clearForSeries(seriesId)
                if (episodeEntities.isNotEmpty()) {
                    // #region agent log
                    android.util.Log.d("SeriesRepoV2", "processSeriesInfoResponse: Inserting ${episodeEntities.size} episodes to DB")
                    // #endregion
                    episodeDao.insertAll(episodeEntities)
                    // #region agent log
                    val verifyCount = episodeDao.getCountForSeries(seriesId)
                    android.util.Log.d("SeriesRepoV2", "processSeriesInfoResponse: Verified DB count after insert=$verifyCount")
                    // #endregion
                } else {
                    // Keep existing episodes; do not insert empties.
                    // #region agent log
                    android.util.Log.w("SeriesRepoV2", "processSeriesInfoResponse: EpisodeEntities empty, not inserting")
                    // #endregion
                }
            } else {
                android.util.Log.w(
                    "SeriesRepoV2",
                    "processSeriesInfoResponse: Skipping episode overwrite (new=0, existing=$existingCount)"
                )
            }
        }
        android.util.Log.d("SeriesRepoV2", "processSeriesInfoResponse: Saved to DB.")
    }
    
    private suspend fun fetchEpisodesFallback(seriesId: String, username: String, password: String) {
        // Strategy 1: Try get_series with series_id (Some providers return header + episodes here)
        try {
            android.util.Log.d("SeriesRepoV2", "fetchEpisodesFallback: Attempting Strategy 1 (get_series)...")
            val response = apiService.getSeries(username, password, seriesId = seriesId)
            if (response.isSuccessful && !response.body().isNullOrEmpty()) {
                val seriesInfo = response.body()!!.first()
                val episodesMap = seriesInfo.episodes
                
                if (!episodesMap.isNullOrEmpty()) {
                    android.util.Log.d("SeriesRepoV2", "fetchEpisodesFallback: Strategy 1 Success! Found ${episodesMap.size} seasons.")
                    val episodeEntities = mapEpisodes(seriesId, episodesMap)
                    saveToDb(seriesId, episodeEntities)
                    return
                } else {
                    android.util.Log.w("SeriesRepoV2", "fetchEpisodesFallback: Strategy 1 returned Series info but NO episodes.")
                }
            } else {
                 android.util.Log.w("SeriesRepoV2", "fetchEpisodesFallback: Strategy 1 Failed (Code ${response.code()})")
            }
        } catch(e: Exception) {
             android.util.Log.e("SeriesRepoV2", "fetchEpisodesFallback: Strategy 1 Error", e)
        }
        
        // Strategy 2: Try get_show_episodes (Standard episode endpoint)
        try {
            android.util.Log.d("SeriesRepoV2", "fetchEpisodesFallback: Attempting Strategy 2 (get_show_episodes)...")
            val response = apiService.getSeriesEpisodes(username, password, seriesId = seriesId)
            if (response.isSuccessful) {
                val episodesMap = XtreamResponseParser.parseEpisodes(response.body())
                if (episodesMap.isNotEmpty()) {
                    val episodeEntities = mapEpisodes(seriesId, episodesMap)
                    android.util.Log.d("SeriesRepoV2", "fetchEpisodesFallback: Strategy 2 Success! Recovered ${episodeEntities.size} episodes.")
                    saveToDb(seriesId, episodeEntities)
                } else {
                    android.util.Log.w("SeriesRepoV2", "fetchEpisodesFallback: Strategy 2 returned empty episodes.")
                }
            } else {
                android.util.Log.e("SeriesRepoV2", "fetchEpisodesFallback: Failed with code ${response.code()}")
            }
        } catch (e: Exception) {
             android.util.Log.e("SeriesRepoV2", "fetchEpisodesFallback: Error", e)
        }
    }

    private suspend fun saveToDb(seriesId: String, episodeEntities: List<EpisodeEntityV2>) {
        database.withTransaction {
            // DO NOT clear series info, just update episodes
            val existingCount = episodeDao.getCountForSeries(seriesId)
            val shouldOverwriteEpisodes = episodeEntities.isNotEmpty() || existingCount == 0

            if (shouldOverwriteEpisodes) {
                episodeDao.clearForSeries(seriesId)
                if (episodeEntities.isNotEmpty()) {
                    episodeDao.insertAll(episodeEntities)
                }
            } else {
                android.util.Log.w(
                    "SeriesRepoV2",
                    "saveToDb: Skipping episode overwrite (new=0, existing=$existingCount)"
                )
            }
            
            // Ensure stub exists
            if (seriesDao.getSeriesById(seriesId) == null) {
                 val stub = SeriesEntityV2(
                    seriesId = seriesId,
                    num = null,
                    name = "Series $seriesId",
                    title = "Series $seriesId",
                    year = null,
                    streamType = "series",
                    cover = null,
                    plot = null,
                    cast = null,
                    director = null,
                    genre = null,
                    releaseDate = null,
                    rating = null,
                    rating5based = null,
                    youtubeTrailer = null,
                    episodeRunTime = null,
                    categoryId = null,
                    lastModified = (System.currentTimeMillis()/1000).toString()
                 )
                 seriesDao.insertAll(listOf(stub))
            }
        }
    }
    
    private fun mapEpisodes(seriesId: String, episodesMap: Map<String, List<com.tvonnet.debridxtreamiptv.data.model.XtreamEpisodeInfo>>?): List<EpisodeEntityV2> {
        val episodeEntities = mutableListOf<EpisodeEntityV2>()
        episodesMap?.forEach { (seasonNumStr, episodesList) ->
            val seasonFromKey = seasonNumStr.toIntOrNull()
            episodesList.forEach { epInfo ->
                val episodeIdRaw = epInfo.id?.toString()?.trim()
                if (episodeIdRaw.isNullOrEmpty() || episodeIdRaw.equals("null", ignoreCase = true)) return@forEach

                val seasonNum = seasonFromKey
                    ?: epInfo.season?.takeIf { it > 0 }
                    ?: 0

                val durationSecsRaw = epInfo.duration_secs ?: epInfo.info?.duration_secs
                val durationLong = durationSecsRaw?.toLongOrNull() ?: 0L

                episodeEntities.add(
                    EpisodeEntityV2(
                        episodeId = episodeIdRaw,
                        seriesId = seriesId,
                        seasonNumber = seasonNum,
                        episodeNumber = epInfo.episode_num ?: 0,
                        title = epInfo.title,
                        containerExtension = epInfo.container_extension,
                        streamType = "series",
                        durationSecs = durationSecsRaw,
                        added = epInfo.added,
                        customSid = epInfo.custom_sid,
                        directSource = epInfo.direct_source,
                        thumbnail = epInfo.info?.cover ?: epInfo.thumbnail,
                        plot = epInfo.info?.plot,
                        cast = epInfo.info?.cast,
                        director = epInfo.info?.director,
                        genre = epInfo.info?.genre,
                        releaseDate = epInfo.info?.releaseDate,
                        rating = epInfo.info?.rating,
                        duration = durationLong
                    )
                )
            }
        }
        return episodeEntities
    }


    fun getPagedEpisodes(seriesId: String): Flow<PagingData<EpisodeEntityV2>> {
        // #region agent log
        android.util.Log.d("SeriesRepoV2", "getPagedEpisodes: Called for seriesId=$seriesId")
        val episodeCount = kotlinx.coroutines.runBlocking { episodeDao.getCountForSeries(seriesId) }
        android.util.Log.d("SeriesRepoV2", "getPagedEpisodes: Database count=$episodeCount for seriesId=$seriesId")
        // #endregion
        return Pager(
            config = PagingConfig(
                pageSize = 20,
                enablePlaceholders = true
            ),
            pagingSourceFactory = { 
                // #region agent log
                android.util.Log.d("SeriesRepoV2", "getPagedEpisodes: Creating PagingSource for seriesId=$seriesId")
                // #endregion
                episodeDao.pagingSource(seriesId) 
            }
        ).flow
    }

    fun getPagedEpisodesForSeason(seriesId: String, seasonNum: Int): Flow<PagingData<EpisodeEntityV2>> {
        // #region agent log
        android.util.Log.d("SeriesRepoV2", "getPagedEpisodesForSeason: Called for seriesId=$seriesId, seasonNum=$seasonNum")
        val episodeCount = kotlinx.coroutines.runBlocking { episodeDao.getCountForSeries(seriesId) }
        android.util.Log.d("SeriesRepoV2", "getPagedEpisodesForSeason: Database count=$episodeCount for seriesId=$seriesId")
        // #endregion
        return Pager(
            config = PagingConfig(
                pageSize = 20,
                enablePlaceholders = true
            ),
            pagingSourceFactory = { 
                // #region agent log
                android.util.Log.d("SeriesRepoV2", "getPagedEpisodesForSeason: Creating PagingSource for seriesId=$seriesId, seasonNum=$seasonNum")
                // #endregion
                episodeDao.pagingSourceForSeason(seriesId, seasonNum) 
            }
        ).flow
    }

    fun getSeasons(seriesId: String): Flow<List<Int>> {
        return episodeDao.getSeasonsForSeries(seriesId)
    }

    suspend fun getSeasonEpisodes(seriesId: String, seasonNum: Int): List<EpisodeEntityV2> {
        return episodeDao.getEpisodesForSeasonList(seriesId, seasonNum)
    }

    suspend fun getEpisodeById(episodeId: String): EpisodeEntityV2? {
        return episodeDao.getEpisodeById(episodeId)
    }

    suspend fun getAllEpisodesForSeries(seriesId: String): List<EpisodeEntityV2> {
        return episodeDao.getEpisodesForSeriesList(seriesId)
    }

    /**
     * Get Paged Series for a Category.
     */
    @OptIn(androidx.paging.ExperimentalPagingApi::class)
    fun getPagedSeries(categoryId: String): Flow<PagingData<SeriesEntityV2>> {
        return Pager(
            config = PagingConfig(
                pageSize = 50,
                enablePlaceholders = true
            ),
            remoteMediator = SeriesRemoteMediatorV2(
                categoryId = categoryId,
                username = legacyRepository.getUsername(),
                password = legacyRepository.getPassword(),
                apiService = apiService,
                database = database
            ),
            pagingSourceFactory = { seriesDao.pagingSource(categoryId) }
        ).flow
    }
}
