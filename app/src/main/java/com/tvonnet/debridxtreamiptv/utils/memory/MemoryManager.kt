package com.tvonnet.debridxtreamiptv.utils.memory

import android.app.ActivityManager
import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max
import kotlin.math.min

/**
 * Memory Manager for Android TV IPTV Application
 *
 * Provides real-time memory monitoring, pressure detection, and adaptive cleanup
 * Optimized for TV hardware with limited memory and 60fps performance requirements
 */
class MemoryManager private constructor(private val context: Context) {

    companion object {
        private const val TAG = "MemoryManager"

        // Memory thresholds (percentage of available memory)
        private const val MEMORY_PRESSURE_LOW = 0.60f      // 60% usage
        private const val MEMORY_PRESSURE_WARNING = 0.75f  // 75% usage
        private const val MEMORY_PRESSURE_CRITICAL = 0.90f // 90% usage

        // TV-specific limits
        private const val TV_TARGET_MEMORY_MB = 150         // Target memory usage for TV
        private const val TV_CRITICAL_MEMORY_MB = 200       // Critical memory threshold
        private const val MAX_BUFFER_SIZE_BYTES = 15 * 1024 * 1024 // 15MB for TV hardware

        // Monitoring intervals
        private const val MONITOR_INTERVAL_MS = 1000L      // 1 second for real-time monitoring
        private const val CLEANUP_DELAY_MS = 500L          // Delay before aggressive cleanup

        @Volatile
        private var INSTANCE: MemoryManager? = null

        fun getInstance(context: Context): MemoryManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: MemoryManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    private val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    private val memoryInfo = ActivityManager.MemoryInfo()

    // Memory pressure tracking
    private val memoryPressureLevel = MemoryPressure.NONE
    private val lastGcTime = AtomicLong(0)
    private val memoryHistory = ConcurrentHashMap<String, Long>()

    // Cleanup callbacks
    // Phase 3: CopyOnWriteArraySet so add/remove (e.g. from a fragment's onDestroyView) is safe
    // while the memory-pressure loop iterates the set — the plain mutableSetOf risked a
    // ConcurrentModificationException / corrupted iteration.
    private val cleanupCallbacks = java.util.concurrent.CopyOnWriteArraySet<MemoryPressureCallback>()
    private val emergencyCallbacks = java.util.concurrent.CopyOnWriteArraySet<EmergencyCleanupCallback>()

    // Coroutine scope for monitoring
    private val monitoringScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var monitoringJob: Job? = null

    // Memory pressure levels
    enum class MemoryPressure {
        NONE,       // < 60% usage
        LOW,        // 60-74% usage
        WARNING,    // 75-89% usage
        CRITICAL    // >= 90% usage
    }

    // Callback interfaces
    interface MemoryPressureCallback {
        suspend fun onMemoryPressure(level: MemoryPressure)
    }

    interface EmergencyCleanupCallback {
        suspend fun onEmergencyCleanup()
    }

    /**
     * Start real-time memory monitoring
     */
    fun startMonitoring() {
        if (monitoringJob?.isActive == true) return

        monitoringJob = monitoringScope.launch {
            while (isActive) {
                try {
                    val pressure = checkMemoryPressure()

                    // Log memory state periodically (every 30 seconds)
                    if (System.currentTimeMillis() % 30000 < MONITOR_INTERVAL_MS) {
                        logMemoryState(pressure)
                    }

                    // Trigger callbacks based on pressure level
                    when (pressure) {
                        MemoryPressure.CRITICAL -> {
                            triggerEmergencyCleanup()
                            delay(CLEANUP_DELAY_MS)
                        }
                        MemoryPressure.WARNING -> {
                            triggerPressureCallbacks(pressure)
                        }
                        MemoryPressure.LOW -> {
                            triggerPressureCallbacks(pressure)
                        }
                        MemoryPressure.NONE -> {
                            // Normal operation
                        }
                    }

                    delay(MONITOR_INTERVAL_MS)

                } catch (e: Exception) {
                    Log.e(TAG, "Memory monitoring error", e)
                    delay(MONITOR_INTERVAL_MS * 5) // Wait longer on error
                }
            }
        }

        Log.i(TAG, "Memory monitoring started")
    }

    /**
     * Stop memory monitoring
     */
    fun stopMonitoring() {
        monitoringJob?.cancel()
        monitoringJob = null
        Log.i(TAG, "Memory monitoring stopped")
    }

    /**
     * Check current memory pressure
     */
    fun checkMemoryPressure(): MemoryPressure {
        activityManager.getMemoryInfo(memoryInfo)

        val totalMemory = memoryInfo.totalMem
        val availableMemory = memoryInfo.availMem
        val usedMemory = totalMemory - availableMemory
        val memoryUsageRatio = if (totalMemory > 0) usedMemory.toFloat() / totalMemory else 0f

        return when {
            memoryUsageRatio >= MEMORY_PRESSURE_CRITICAL -> MemoryPressure.CRITICAL
            memoryUsageRatio >= MEMORY_PRESSURE_WARNING -> MemoryPressure.WARNING
            memoryUsageRatio >= MEMORY_PRESSURE_LOW -> MemoryPressure.LOW
            else -> MemoryPressure.NONE
        }
    }

    /**
     * App-heap pressure — the ratio of the app's used Dalvik heap to its heap limit.
     *
     * This is the signal that actually predicts OutOfMemoryError, and it is
     * deliberately separate from [checkMemoryPressure], which measures SYSTEM memory
     * (availMem/totalMem). On Android TV the OS keeps system memory ~85-95% utilised
     * as a normal steady state, so system pressure reads CRITICAL almost constantly.
     * Using that to abort app work was truncating EPG syncs mid-parse (channels late
     * in the feed ended up with no guide data). Gate abort/continue decisions on this
     * app-heap signal instead; only genuine heap exhaustion should stop a parse.
     */
    fun appHeapPressure(): MemoryPressure {
        val runtime = Runtime.getRuntime()
        val maxHeap = runtime.maxMemory()
        if (maxHeap <= 0L) return MemoryPressure.NONE
        val usedHeap = runtime.totalMemory() - runtime.freeMemory()
        val ratio = usedHeap.toFloat() / maxHeap
        return when {
            ratio >= MEMORY_PRESSURE_CRITICAL -> MemoryPressure.CRITICAL
            ratio >= MEMORY_PRESSURE_WARNING -> MemoryPressure.WARNING
            ratio >= MEMORY_PRESSURE_LOW -> MemoryPressure.LOW
            else -> MemoryPressure.NONE
        }
    }

    /**
     * Get current memory usage in MB
     */
    fun getCurrentMemoryUsage(): MemoryUsage {
        activityManager.getMemoryInfo(memoryInfo)

        val totalMemoryMB = memoryInfo.totalMem / (1024 * 1024)
        val availableMemoryMB = memoryInfo.availMem / (1024 * 1024)
        val usedMemoryMB = totalMemoryMB - availableMemoryMB

        return MemoryUsage(
            totalMemoryMB = totalMemoryMB,
            usedMemoryMB = usedMemoryMB,
            availableMemoryMB = availableMemoryMB,
            pressureLevel = checkMemoryPressure()
        )
    }

    /**
     * Check if it's safe to allocate additional memory
     */
    fun canAllocateMemory(requestedMB: Int): Boolean {
        val usage = getCurrentMemoryUsage()
        val projectedUsage = usage.usedMemoryMB + requestedMB

        return projectedUsage < TV_CRITICAL_MEMORY_MB && usage.pressureLevel != MemoryPressure.CRITICAL
    }

    /**
     * Get optimal buffer size for TV hardware based on current memory state
     */
    fun getOptimalBufferSize(): Int {
        val pressure = checkMemoryPressure()

        return when (pressure) {
            MemoryPressure.CRITICAL -> 2 * 1024      // 2KB
            MemoryPressure.WARNING -> 4 * 1024       // 4KB
            MemoryPressure.LOW -> 8 * 1024          // 8KB
            MemoryPressure.NONE -> 16 * 1024        // 16KB
        }
    }

    /**
     * Get optimal file reading chunk size for streaming operations
     */
    fun getOptimalChunkSize(): Int {
        val pressure = checkMemoryPressure()

        return when (pressure) {
            MemoryPressure.CRITICAL -> 4 * 1024      // 4KB chunks
            MemoryPressure.WARNING -> 8 * 1024       // 8KB chunks
            MemoryPressure.LOW -> 16 * 1024          // 16KB chunks
            MemoryPressure.NONE -> 32 * 1024         // 32KB chunks
        }
    }

    /**
     * Get maximum safe parsing size for XML/JSON content
     */
    fun getMaxParsingSize(): Long {
        val pressure = checkMemoryPressure()

        return when (pressure) {
            MemoryPressure.CRITICAL -> 10 * 1024 * 1024L  // 10MB
            MemoryPressure.WARNING -> 25 * 1024 * 1024L   // 25MB
            MemoryPressure.LOW -> 50 * 1024 * 1024L       // 50MB
            MemoryPressure.NONE -> 100 * 1024 * 1024L     // 100MB
        }
    }

    /**
     * Register memory pressure callback
     */
    fun addMemoryPressureCallback(callback: MemoryPressureCallback) {
        cleanupCallbacks.add(callback)
    }

    /**
     * Register emergency cleanup callback
     */
    fun addEmergencyCleanupCallback(callback: EmergencyCleanupCallback) {
        emergencyCallbacks.add(callback)
    }

    /**
     * Remove memory pressure callback
     */
    fun removeMemoryPressureCallback(callback: MemoryPressureCallback) {
        cleanupCallbacks.remove(callback)
    }

    /**
     * Remove emergency cleanup callback
     */
    fun removeEmergencyCleanupCallback(callback: EmergencyCleanupCallback) {
        emergencyCallbacks.remove(callback)
    }

    /**
     * Request garbage collection with cooldown.
     *
     * An explicit `System.gc()` is normally an anti-pattern, and it is only a *hint* to the runtime.
     * It is kept deliberately here: this app runs on 1GB Fire TV sticks and this is called from the
     * OOM/heap-pressure recovery paths (e.g. [com.tvonnet.debridxtreamiptv.data.epg.EpgParser] after a
     * multi-MB XMLTV parse blows up), where nudging the collector before retrying measurably reduces
     * repeat OOMs. The 5s cooldown stops it becoming a hot loop. Suppressed at the call site rather
     * than hidden in the detekt baseline so the justification travels with the code.
     */
    @Suppress("ExplicitGarbageCollectionCall")
    fun requestGarbageCollection() {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastGcTime.get() > 5000) { // 5 second cooldown
            System.gc()
            lastGcTime.set(currentTime)
            Log.d(TAG, "Manual garbage collection requested")
        }
    }

    /**
     * Track memory usage for a specific component
     */
    fun trackMemoryUsage(component: String, memoryUsage: Long) {
        memoryHistory[component] = memoryUsage
    }

    /**
     * Get memory usage history for all components
     */
    fun getMemoryHistory(): Map<String, Long> {
        return memoryHistory.toMap()
    }

    /**
     * Trigger emergency cleanup
     */
    private suspend fun triggerEmergencyCleanup() {
        Log.w(TAG, "EMERGENCY: Memory critical, triggering cleanup")

        emergencyCallbacks.forEach { callback ->
            try {
                callback.onEmergencyCleanup()
            } catch (e: Exception) {
                Log.e(TAG, "Emergency cleanup callback failed", e)
            }
        }

        // Force garbage collection
        requestGarbageCollection()

        // Clear memory history
        memoryHistory.clear()
    }

    /**
     * Trigger pressure-level callbacks
     */
    private suspend fun triggerPressureCallbacks(pressure: MemoryPressure) {
        cleanupCallbacks.forEach { callback ->
            try {
                callback.onMemoryPressure(pressure)
            } catch (e: Exception) {
                Log.e(TAG, "Memory pressure callback failed", e)
            }
        }
    }

    /**
     * Log current memory state for debugging
     */
    private fun logMemoryState(pressure: MemoryPressure) {
        val usage = getCurrentMemoryUsage()
        Log.d(TAG, "Memory: ${usage.usedMemoryMB}MB/${usage.totalMemoryMB}MB (${usage.pressureLevel})")
    }

    /**
     * Memory usage data class
     */
    data class MemoryUsage(
        val totalMemoryMB: Long,
        val usedMemoryMB: Long,
        val availableMemoryMB: Long,
        val pressureLevel: MemoryPressure
    )

    /**
     * Cleanup when MemoryManager is no longer needed
     */
    fun cleanup() {
        stopMonitoring()
        cleanupCallbacks.clear()
        emergencyCallbacks.clear()
        memoryHistory.clear()
    }
}