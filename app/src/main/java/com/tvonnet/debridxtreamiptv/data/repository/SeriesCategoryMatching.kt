package com.tvonnet.debridxtreamiptv.data.repository

import com.tvonnet.debridxtreamiptv.data.model.XtreamSeriesInfo

/**
 * Phase 7: the relaxed series↔category match predicate that was copy-pasted verbatim into five
 * fallback filters inside XtreamRepository (all-series cache / disk cache / network fallbacks).
 * Pure function of (series, categoryId): exact string match on category_id, membership in the
 * category_ids list, or a loose integer match. Consolidated here with byte-for-byte identical logic
 * so every call site behaves exactly as before — this only removes duplication.
 */
internal fun XtreamSeriesInfo.matchesCategory(categoryId: String): Boolean {
    val catIdStr = category_id?.trim()
    val targetIdStr = categoryId.trim()
    val stringMatch = catIdStr == targetIdStr
    val listMatch = category_ids?.contains(targetIdStr.toIntOrNull()) == true
    val intMatch = catIdStr?.toIntOrNull() == targetIdStr.toIntOrNull()
    return stringMatch || listMatch || (intMatch && targetIdStr.toIntOrNull() != null)
}
