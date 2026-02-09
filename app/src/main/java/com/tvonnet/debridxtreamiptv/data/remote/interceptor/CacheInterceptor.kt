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
                    .header("Cache-Control", "public, max-age=${DEFAULT_CACHE_MINUTES * 60}")
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


