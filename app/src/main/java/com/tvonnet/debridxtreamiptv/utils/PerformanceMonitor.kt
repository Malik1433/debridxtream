package com.tvonnet.debridxtreamiptv.utils

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Performance Monitor - Week 13: Performance Profiling
 * 
 * Purpose:
 * - Track performance metrics
 * - Monitor memory usage
 * - Log performance issues
 * - Provide performance insights
 * 
 * Features:
 * - Operation timing
 * - Memory usage tracking
 * - Performance warnings
 * - Metrics collection
 */
object PerformanceMonitor {
    
    private const val TAG = "PerformanceMonitor"
    
    // Performance thresholds
    private const val SLOW_OPERATION_THRESHOLD_MS = 500L
    private const val VERY_SLOW_OPERATION_THRESHOLD_MS = 1000L
    private const val MEMORY_WARNING_THRESHOLD_MB = 200L
    
    // Metrics storage
    private val _metrics = MutableStateFlow<PerformanceMetrics>(PerformanceMetrics())
    val metrics: StateFlow<PerformanceMetrics> = _metrics.asStateFlow()
    
    /**
     * Measure operation execution time
     * Returns duration in milliseconds
     */
    fun <T> measureOperation(operationName: String, operation: () -> T): T {
        val startTime = System.currentTimeMillis()
        val result = operation()
        val duration = System.currentTimeMillis() - startTime
        
        // Update metrics
        updateMetrics(operationName, duration)
        
        // Log performance
        logPerformance(operationName, duration)
        
        return result
    }
    
    /**
     * Measure suspend operation execution time
     */
    suspend fun <T> measureSuspendOperation(operationName: String, operation: suspend () -> T): T {
        val startTime = System.currentTimeMillis()
        val result = operation()
        val duration = System.currentTimeMillis() - startTime
        
        // Update metrics
        updateMetrics(operationName, duration)
        
        // Log performance
        logPerformance(operationName, duration)
        
        return result
    }
    
    /**
     * Track memory usage
     */
    fun trackMemory(operationName: String) {
        val runtime = Runtime.getRuntime()
        val usedMemory = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024) // MB
        val maxMemory = runtime.maxMemory() / (1024 * 1024) // MB
        val percentage = (usedMemory * 100) / maxMemory
        
        Log.d(TAG, "Memory [$operationName]: ${usedMemory}MB / ${maxMemory}MB (${percentage}%)")
        
        if (usedMemory > MEMORY_WARNING_THRESHOLD_MB) {
            Log.w(TAG, "⚠️ High memory usage: ${usedMemory}MB")
        }
        
        // Update metrics
        _metrics.value = _metrics.value.copy(
            currentMemoryMB = usedMemory.toInt(),
            maxMemoryMB = maxMemory.toInt(),
            memoryUsagePercent = percentage.toInt()
        )
    }
    
    /**
     * Log performance warning
     */
    private fun logPerformance(operationName: String, duration: Long) {
        when {
            duration >= VERY_SLOW_OPERATION_THRESHOLD_MS -> {
                Log.w(TAG, "⚠️ VERY SLOW: $operationName took ${duration}ms")
            }
            duration >= SLOW_OPERATION_THRESHOLD_MS -> {
                Log.w(TAG, "⚠️ SLOW: $operationName took ${duration}ms")
            }
            else -> {
                Log.d(TAG, "✅ FAST: $operationName took ${duration}ms")
            }
        }
    }
    
    /**
     * Update performance metrics
     */
    private fun updateMetrics(operationName: String, duration: Long) {
        val current = _metrics.value
        val operationCount = current.operationCount + 1
        val totalDuration = current.totalOperationDuration + duration
        val avgDuration = totalDuration / operationCount
        
        val slowOperations = if (duration >= SLOW_OPERATION_THRESHOLD_MS) {
            current.slowOperations + 1
        } else {
            current.slowOperations
        }
        
        _metrics.value = current.copy(
            operationCount = operationCount,
            totalOperationDuration = totalDuration,
            averageOperationDuration = avgDuration,
            slowOperations = slowOperations,
            lastOperation = operationName,
            lastOperationDuration = duration
        )
    }
    
    /**
     * Get performance summary
     */
    fun getSummary(): String {
        val m = _metrics.value
        return """
            Performance Summary:
            - Total Operations: ${m.operationCount}
            - Average Duration: ${m.averageOperationDuration}ms
            - Slow Operations: ${m.slowOperations}
            - Memory Usage: ${m.currentMemoryMB}MB / ${m.maxMemoryMB}MB (${m.memoryUsagePercent}%)
            - Last Operation: ${m.lastOperation} (${m.lastOperationDuration}ms)
        """.trimIndent()
    }
    
    /**
     * Reset metrics
     */
    fun reset() {
        _metrics.value = PerformanceMetrics()
        Log.d(TAG, "Performance metrics reset")
    }
}

/**
 * Performance metrics data class
 */
data class PerformanceMetrics(
    val operationCount: Long = 0,
    val totalOperationDuration: Long = 0,
    val averageOperationDuration: Long = 0,
    val slowOperations: Int = 0,
    val lastOperation: String = "",
    val lastOperationDuration: Long = 0,
    val currentMemoryMB: Int = 0,
    val maxMemoryMB: Int = 0,
    val memoryUsagePercent: Int = 0
)

