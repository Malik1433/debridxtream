package com.tvonnet.debridxtreamiptv.data.debrid.model

/**
 * User configurable row definition for the Debrid hub experience.
 */
data class DebridRowConfig(
    val id: String,
    val title: String,
    val addonId: String? = null,
    val query: String? = null,
    val language: String? = null,
    val imdbType: String? = null,
    val contentType: DebridContentType = DebridContentType.MIXED,
    val enabled: Boolean = true,
    val sortOrder: Int = 0
)

enum class DebridContentType {
    MOVIE,
    SERIES,
    MIXED
}


