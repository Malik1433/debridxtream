package com.tvonnet.debridxtreamiptv.data.repository

import android.util.Log
import com.tvonnet.debridxtreamiptv.data.model.SeriesCategoryState
import com.tvonnet.debridxtreamiptv.data.model.SeriesCategoryStatus
import com.tvonnet.debridxtreamiptv.data.model.XtreamSeriesInfo

/**
 * Which series categories the provider is currently failing on, lifted out of [SeriesRepository]
 * (2026-09-05).
 *
 * One reason to change: **how long we stop asking a category that just failed, and what the user
 * sees while we are not asking.** That is a policy, and it was tangled through a 800-line
 * repository whose other job is fetching lists.
 *
 * The rule it encodes, and why it exists: an Xtream panel routinely answers one category with an
 * error or an empty list while the rest of the account is fine. Hammering it turns one bad
 * category into a stalled screen, so a failure parks it for [FAILURE_COOLDOWN_MS] — and during
 * that window [cachedDuringCooldown] hands back **the last good list we hold**, not an empty one.
 * An empty answer during a cooldown would look exactly like "this category has no content", which
 * is the mistake the whole never-let-a-failed-refresh-destroy-good-data rule exists to prevent.
 * When the cooldown expires the entry is dropped so the next fetch is allowed through.
 *
 * State lives in the shared [CatalogCache], not here, so a purge still clears it in one place.
 * Bodies were moved verbatim; behaviour is frozen by SeriesOutageStatusTest.
 */
internal class SeriesCategoryHealth(private val catalogCache: CatalogCache) {

    private val seriesCategoryStatus get() = catalogCache.seriesCategoryStatus
    private val perCategorySeriesCache get() = catalogCache.perCategorySeriesCache

    fun status(categoryId: String): SeriesCategoryStatus? = seriesCategoryStatus[categoryId]

    /**
     * Non-null when this category is parked: the cached list if we have one, an empty list if we
     * do not. Null means "not parked — go and fetch". Expiring the cooldown is a side effect of
     * asking, which is why this is not a pure predicate.
     */
    fun cachedDuringCooldown(categoryId: String): List<XtreamSeriesInfo>? {
        val status = seriesCategoryStatus[categoryId] ?: return null
        if (status.state != SeriesCategoryState.TEMPORARILY_UNAVAILABLE) {
            return null
        }

        val elapsed = System.currentTimeMillis() - status.lastUpdatedMillis
        if (elapsed < FAILURE_COOLDOWN_MS) {
            val cachedSeries = perCategorySeriesCache[categoryId]
            return if (!cachedSeries.isNullOrEmpty()) {
                Log.d(TAG, "Returning cached series for $categoryId during cooldown (${cachedSeries.size} items)")
                cachedSeries
            } else {
                Log.w(TAG, "Category $categoryId still cooling down and no cached data is available.")
                emptyList()
            }
        }

        // Cooldown expired, allow next fetch attempt.
        seriesCategoryStatus.remove(categoryId)
        return null
    }

    fun markHealthy(categoryId: String) {
        seriesCategoryStatus.remove(categoryId)
    }

    fun update(categoryId: String, state: SeriesCategoryState, message: String?) {
        if (state == SeriesCategoryState.HEALTHY) {
            markHealthy(categoryId)
            return
        }
        seriesCategoryStatus[categoryId] = SeriesCategoryStatus(
            categoryId = categoryId,
            state = state,
            message = message,
            lastUpdatedMillis = System.currentTimeMillis()
        )
    }

    private companion object {
        private const val TAG = "XtreamRepository"
        private const val FAILURE_COOLDOWN_MS = 2 * 60 * 1000L
    }
}
