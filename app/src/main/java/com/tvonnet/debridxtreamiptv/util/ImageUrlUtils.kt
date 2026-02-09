package com.tvonnet.debridxtreamiptv.util

fun isMissingImageUrl(url: String?): Boolean {
    if (url.isNullOrBlank()) return true
    val trimmed = url.trim()
    return trimmed.equals("null", ignoreCase = true) || trimmed == "[]" || trimmed == "{}"
}
