package com.tvonnet.debridxtreamiptv.data.remote.interceptor

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.util.Log
import okhttp3.CacheControl
import okhttp3.Interceptor
import okhttp3.Response
import java.util.concurrent.TimeUnit

/**
 * Phase 1.4: CacheInterceptor Simplification
 *
 * Simplified and robust HTTP caching interceptor that:
 * - Provides safe offline support
 * - Reduces complexity and crash potential
 * - Uses simple cache strategies
 * - Has proper error handling
 *
 * Cache Strategy:
 * - Online: Respect server cache headers, fallback to 5 minutes
 * - Offline: Use stale cache up to 24 hours
 */
class CacheInterceptor(
    private val context: Context
) : Interceptor {

    companion object {
        private const val TAG = "CacheInterceptor"
        private const val DEFAULT_CACHE_MINUTES = 5
        private const val OFFLINE_CACHE_HOURS = 24
    }

    private val connectivityManager by lazy {
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()

        // Phase 5 (#19): never serve the credential-bearing login or the time-sensitive EPG
        // endpoints from the 5-minute cache. Xtream carries username/password in the query on
        // every request and the login response body echoes them; EPG must also stay fresh.
        if (shouldBypassCache(request.url.toString())) {
            return chain.proceed(request)
        }

        try {
            val isOnline = isNetworkAvailable()

            // Simple cache strategy based on network status
            val cacheControl = if (isOnline) {
                // Online: Respect server headers or use default 5-minute cache
                CacheControl.Builder()
                    .maxAge(DEFAULT_CACHE_MINUTES, TimeUnit.MINUTES)
                    .build()
            } else {
                // Offline: Use stale cache up to 24 hours
                CacheControl.Builder()
                    .maxStale(OFFLINE_CACHE_HOURS, TimeUnit.HOURS)
                    .onlyIfCached()
                    .build()
            }

            val cacheRequest = request.newBuilder()
                .cacheControl(cacheControl)
                .build()

            val response = chain.proceed(cacheRequest)

            // Only add cache headers for successful responses
            if (response.isSuccessful) {
                return response.newBuilder()
                    .removeHeader("Pragma")
                    .header("Cache-Control", "private, max-age=${DEFAULT_CACHE_MINUTES * 60}")
                    .build()
            }

            return response

        } catch (e: Exception) {
            Log.w(TAG, "Cache request failed, trying without cache: ${request.url}", e)

            // Fallback: try request without cache
            return try {
                chain.proceed(request)
            } catch (fallbackException: Exception) {
                Log.e(TAG, "Request failed completely: ${request.url}", fallbackException)
                throw fallbackException
            }
        }
    }

    /**
     * Bypass the cache for requests we must never serve stale or store with credentials: the
     * Xtream login (no `action` — its body echoes username/password) and the time-sensitive EPG
     * endpoints. Everything else (VOD/series/live LISTS) may still use the short cache.
     */
    private fun shouldBypassCache(url: String): Boolean {
        val action = Regex("[?&]action=([^&]*)").find(url)?.groupValues?.getOrNull(1)
        if (action.isNullOrBlank()) return true
        return action == "get_short_epg" || action == "get_simple_data_table"
    }

    /**
     * Simplified network availability check with error handling
     */
    private fun isNetworkAvailable(): Boolean {
        return try {
            val cm = connectivityManager ?: return false

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val network = cm.activeNetwork ?: return false
                val capabilities = cm.getNetworkCapabilities(network) ?: return false
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            } else {
                @Suppress("DEPRECATION")
                val networkInfo = cm.activeNetworkInfo
                networkInfo?.isConnected == true
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error checking network availability", e)
            // Assume network is available on error to avoid false negatives
            true
        }
    }
}


