package com.tvonnet.debridxtreamiptv.data.debrid.repository

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * Catalog item model for browsing
 */
@Parcelize
data class DebridCatalogItem(
    val id: String,
    val title: String,
    val posterUrl: String?,
    val backdropUrl: String?,
    val type: String,
    val year: String?,
    val rating: String?
) : Parcelable
