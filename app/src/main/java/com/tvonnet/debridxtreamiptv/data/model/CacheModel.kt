package com.tvonnet.debridxtreamiptv.data.model

data class IptvCache(
    val timestamp: Long,
    val live: LiveCacheData?,
    val vod: VodCacheData?,
    val series: SeriesCacheData?,
    val epg: Map<String, List<EpgProgram>>?
)

data class LiveCacheData(
    val categories: List<XtreamCategory>,
    val streams: List<XtreamStream>
)

data class VodCacheData(
    val categories: List<XtreamCategory>,
    val streams: List<XtreamVodInfo>
)

data class SeriesCacheData(
    val categories: List<XtreamCategory>,
    val streams: List<XtreamSeriesInfo>
)

