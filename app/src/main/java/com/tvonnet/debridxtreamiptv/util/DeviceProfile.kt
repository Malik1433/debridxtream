package com.tvonnet.debridxtreamiptv.util

import android.app.ActivityManager
import android.content.Context

/**
 * Device capability snapshot for performance tuning on low-RAM TV devices.
 */
object DeviceProfile {

    private const val LOW_RAM_TOTAL_GB = 1.5
    private const val LOW_RAM_MEMORY_CLASS_MB = 192

    data class Snapshot(
        val isLowRamDevice: Boolean,
        val memoryClassMb: Int,
        val totalRamGb: Double
    )

    @Volatile
    private var cachedSnapshot: Snapshot? = null

    fun get(context: Context): Snapshot {
        val existing = cachedSnapshot
        if (existing != null) return existing

        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memoryInfo = ActivityManager.MemoryInfo().also { activityManager.getMemoryInfo(it) }
        val totalRamGb = memoryInfo.totalMem / (1024.0 * 1024.0 * 1024.0)
        val memoryClassMb = activityManager.memoryClass
        val isLowRamDevice = activityManager.isLowRamDevice ||
            memoryClassMb <= LOW_RAM_MEMORY_CLASS_MB ||
            totalRamGb <= LOW_RAM_TOTAL_GB

        val snapshot = Snapshot(isLowRamDevice, memoryClassMb, totalRamGb)
        cachedSnapshot = snapshot
        return snapshot
    }

    fun isLowRamDevice(context: Context): Boolean = get(context).isLowRamDevice

    fun channelLogoSize(context: Context): Int = if (isLowRamDevice(context)) 220 else 300

    fun posterSize(context: Context): Pair<Int, Int> =
        if (isLowRamDevice(context)) 320 to 480 else 400 to 600

    fun posterRowSize(context: Context): Pair<Int, Int> =
        if (isLowRamDevice(context)) 240 to 360 else 300 to 450

    fun thumbnailSize(context: Context): Int = if (isLowRamDevice(context)) 120 else 150
}
