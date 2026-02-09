package com.tvonnet.debridxtreamiptv.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.tvonnet.debridxtreamiptv.data.model.XtreamSeasonInfo

@Entity(
    tableName = "seasons",
    foreignKeys = [
        ForeignKey(
            entity = SeriesEntity::class,
            parentColumns = ["seriesId"],
            childColumns = ["seriesId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("seriesId")]
)
data class SeasonEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val seriesId: String,
    val seasonNumber: Int,
    val name: String?,
    val overview: String?,
    val airDate: String?,
    val cover: String?
)

fun XtreamSeasonInfo.toSeasonEntity(seriesId: String): SeasonEntity {
    return SeasonEntity(
        seriesId = seriesId,
        seasonNumber = this.season_number?.toIntOrNull() ?: 0,
        name = this.name,
        overview = this.overview,
        airDate = this.air_date,
        cover = this.cover
    )
}

fun SeasonEntity.toXtreamSeasonInfo(): XtreamSeasonInfo {
    return XtreamSeasonInfo(
        season_number = this.seasonNumber.toString(),
        name = this.name,
        overview = this.overview,
        air_date = this.airDate,
        cover = this.cover
    )
}
