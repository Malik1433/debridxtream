package com.tvonnet.debridxtreamiptv.utils.error

import android.content.Context
import android.util.Log
import com.tvonnet.debridxtreamiptv.data.Result
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Error Manager - Handles error reporting and retry logic
 */
object ErrorManagerTemp {

    private const val TAG = "ErrorManager"
    private var instance: ErrorManagerTemp? = null
    private lateinit var context: Context
    private val retryCounts = ConcurrentHashMap<String, AtomicInteger>()
    private const val MAX_RETRIES = 3

    fun getInstance(context: Context): ErrorManagerTemp {
        if (instance == null) {
            this.context = context
        }
        return this
    }

    suspend fun <T> handleOperation(
        operation: String,
        operationType: String = "GENERAL",
        block: suspend () -> T
    ): Result<T> {
        return try {
            val result = block()
            resetRetryCount(operation)
            Result.Success(result)
        } catch (exception: Exception) {
            reportError(operation, exception)
            Result.Error(exception)
        }
    }

    suspend fun handleError(
        operation: String,
        operationType: String,
        error: Exception,
        retryBlock: (suspend () -> Unit)? = null
    ): Result<Nothing> {
        reportError(operation, error)
        return Result.Error(error)
    }

    fun reportError(operation: String, throwable: Throwable) {
        Log.e(TAG, "Error in $operation: ${throwable.message}", throwable)
        // In a real app, you might send this to Crashlytics
    }

    fun shouldRetry(operation: String): Boolean {
        val count = retryCounts.getOrPut(operation) { AtomicInteger(0) }
        return if (count.get() < MAX_RETRIES) {
            count.incrementAndGet()
            true
        } else {
            false
        }
    }

    fun resetRetryCount(operation: String) {
        retryCounts.remove(operation)
    }

    fun getErrorStatistics(): Map<String, Long> = emptyMap() // Placeholder for stats

    fun clearHistory() {
        retryCounts.clear()
    }
}