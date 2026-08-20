package com.tvonnet.debridxtreamiptv.data.network

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Singleton
import dagger.hilt.android.qualifiers.ApplicationContext
import okio.Okio

enum class NetworkQuality {
    FAST,       // > 20 Mbps
    MODERATE,   // 5-20 Mbps
    SLOW,       // < 5 Mbps
    UNKNOWN
}

@Singleton
class NetworkQualityManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val okHttpClient: OkHttpClient
) {

    companion object {
        // Cloudflare 1MB test file (fast, reliable CDN)
        private const val TEST_URL_1MB = "https://speed.cloudflare.com/__down?bytes=1000000"
        private const val PROBE_BYTES = 200_000L
        private const val PROBE_URL_200KB = "https://speed.cloudflare.com/__down?bytes=200000"
        private const val TAG = "NetworkQualityManager"
    }

    /**
     * A SMALL download from an unrelated host, for "is it my line or their server?".
     *
     * Deliberately not [runSpeedTest]: that pulls a full megabyte, and this runs at the exact
     * moment the customer's stream is already starving. A probe that competes for the bandwidth it
     * is measuring would make the problem it is diagnosing worse. 200KB is enough to tell a working
     * line from a crawling one, which is the only distinction the verdict needs.
     *
     * Bounded twice — by the caller's timeout and by returning null on anything unexpected — so a
     * hanging probe can never hold a verdict, or a coroutine, open.
     *
     * @return throughput in kbps, or null when it could not be measured.
     */
    suspend fun probeControlKbps(): Long? = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(PROBE_URL_200KB).build()
        runCatching {
            val startedAt = System.currentTimeMillis()
            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@runCatching null
                val body = response.body ?: return@runCatching null
                body.source().use { it.readAll(okio.blackholeSink()) }
            }
            val elapsedMs = (System.currentTimeMillis() - startedAt).coerceAtLeast(1)
            // 200_000 bytes = 1_600_000 bits; bits per ms is kbps already.
            (PROBE_BYTES * 8 / elapsedMs)
        }.getOrNull()
    }

    suspend fun runSpeedTest(): NetworkQuality = withContext(Dispatchers.IO) {
        Log.d(TAG, "Starting background speed test...")
        val startTime = System.currentTimeMillis()
        
        val request = Request.Builder()
            .url(TEST_URL_1MB)
            .build()

        try {
            val response = okHttpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.w(TAG, "Speed test failed with code: ${response.code}")
                response.close()
                return@withContext NetworkQuality.UNKNOWN
            }

            val body = response.body
            if (body == null) {
                response.close()
                return@withContext NetworkQuality.UNKNOWN
            }

            // Consume the stream to measure download time
            val source = body.source()
            source.readAll(okio.blackholeSink())
            source.close()
            response.close()

            val endTime = System.currentTimeMillis()
            val durationMs = endTime - startTime
            
            // Avoid division by zero
            val safeDuration = if (durationMs < 10) 10 else durationMs
            
            // Calculate speed in Mbps for 1MB file (8 Megabits)
            // Speed = Data / Time
            // 8 Mb / (ms / 1000) = 8000 / ms
            val speedMbps = 8000.0 / safeDuration
            
            Log.i(TAG, "Speed test result: ${durationMs}ms ~ ${String.format(java.util.Locale.US, "%.2f", speedMbps)} Mbps")

            return@withContext when {
                speedMbps > 20.0 -> NetworkQuality.FAST
                speedMbps > 5.0 -> NetworkQuality.MODERATE
                else -> NetworkQuality.SLOW
            }

        } catch (e: Exception) {
            Log.e(TAG, "Speed test error: ${e.message}")
            return@withContext NetworkQuality.UNKNOWN
        }
    }
}
