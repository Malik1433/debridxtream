package com.tvonnet.debridxtreamiptv.data.repository

import android.util.Log
import com.tvonnet.debridxtreamiptv.data.Result
import com.tvonnet.debridxtreamiptv.data.cache.CacheHelper
import com.tvonnet.debridxtreamiptv.data.model.XtreamSeriesInfo
import com.tvonnet.debridxtreamiptv.data.remote.XtreamApiService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import retrofit2.HttpException
import retrofit2.Response

/**
 * The whole-catalog series fallback: what to do when a category the user just opened comes back 404
 * or empty and the only way to fill the screen is to ask for everything and filter client-side.
 *
 * **SY-3 — this is the bounded version.** It runs up to three full-catalog fetches in sequence, and
 * none of them used to have a timeout, so on a slow or half-dead provider the interactive path could
 * hold the screen for the sum of three raw socket timeouts (~90s measured). Both callers are
 * user-facing. Now the whole sequence shares one budget, and a timeout degrades to the on-disk
 * catalog instead of hanging — stale titles beat an empty screen, and beat a minute of nothing.
 *
 * It lives outside [SeriesRepository] rather than in it: that class was already 780 lines and one
 * statement under the `LargeClass` ceiling, and the roadmap's own rule for this domain is that a
 * collaborator which is too big gets split further, not padded. Bounding a call is not a reason to
 * tip a class over — so the responsibility moved out with the fix.
 */
internal class SeriesFallbackCatalog(private val cacheHelper: CacheHelper) {

    /**
     * @return every series the provider will admit to, or the last good on-disk copy. The caller
     *   owns the in-memory cache; this owns the network attempt and the disk fallback.
     */
    suspend fun fetchAll(
        api: XtreamApiService,
        username: String,
        password: String
    ): Result<List<XtreamSeriesInfo>> {
        val outcome = withTimeoutOrNull(FETCH_TIMEOUT_MS) {
            tryStrategies(api, username, password)
        }

        if (outcome is Result.Success) {
            cacheHelper.writeAllSeries(outcome.data)
            Log.d(TAG, "Fetched ${outcome.data.size} total series for fallback (and cached to disk)")
            return outcome
        }

        cacheHelper.readAllSeries()?.takeIf { it.isNotEmpty() }?.let { cached ->
            val why = if (outcome == null) "timed out" else "failed"
            Log.w(TAG, "All-series fetch $why — serving ${cached.size} from disk cache")
            return Result.Success(cached)
        }

        return outcome as? Result.Error
            ?: Result.Error(Exception("Timed out fetching all series after ${FETCH_TIMEOUT_MS}ms"))
    }

    /**
     * The three shapes providers accept for "give me every series". They were three near-identical
     * copies; as a list they are data, and adding a fourth spelling is a one-line change.
     */
    private suspend fun tryStrategies(
        api: XtreamApiService,
        username: String,
        password: String
    ): Result<List<XtreamSeriesInfo>> {
        var lastResponse: Response<List<XtreamSeriesInfo>>? = null
        try {
            for (categoryId in STRATEGIES) {
                val response = api.getSeries(username, password, categoryId = categoryId)
                lastResponse = response
                val seriesList = response.body().orEmpty()
                if (response.isSuccessful && seriesList.isNotEmpty()) return Result.Success(seriesList)
                Log.d(TAG, "All-series strategy categoryId=$categoryId gave ${seriesList.size} (code ${response.code()})")
            }
        } catch (e: CancellationException) {
            throw e   // the timeout itself, or the caller going away — never swallow it
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching all series for fallback", e)
            return Result.Error(e)
        }
        Log.e(TAG, "Failed to fetch all series (all strategies empty). Last code: ${lastResponse?.code()}")
        return lastResponse?.let { Result.Error(HttpException(it)) }
            ?: Result.Error(Exception("No all-series strategy produced a response"))
    }

    private companion object {
        const val TAG = "SeriesFallbackCatalog"

        /**
         * Total budget for all three strategies together, not per call. Generous enough for a slow
         * provider to answer, far short of the ~90s three raw socket timeouts could add up to while
         * the user waits on the category they just opened.
         */
        const val FETCH_TIMEOUT_MS = 20_000L

        /** No category_id, an empty one, then "0". */
        val STRATEGIES = listOf(null, "", "0")
    }
}
