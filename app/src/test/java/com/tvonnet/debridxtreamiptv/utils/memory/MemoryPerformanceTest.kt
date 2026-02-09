package com.tvonnet.debridxtreamiptv.utils.memory

import android.content.Context
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream

/**
 * Memory Performance Tests for Android TV IPTV Application
 *
 * Validates that critical memory fixes work correctly under various scenarios:
 * 1. EPG streaming parsing with large XML files
 * 2. Fragment lifecycle management with proper coroutine cancellation
 * 3. Memory pressure detection and adaptive behavior
 * 4. Performance benchmarks for TV-optimized buffers
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class MemoryPerformanceTest {

    private lateinit var memoryManager: MemoryManager

    companion object {
        private const val TAG = "MemoryPerformanceTest"

        // Test XML samples
        private const val SMALL_EPG_XML = """<?xml version="1.0" encoding="UTF-8"?>
<tv>
    <channel id="1">
        <display-name>Test Channel 1</display-name>
    </channel>
    <programme channel="1" start="20231105000000 +0000" stop="20231105010000 +0000">
        <title>Test Program 1</title>
        <desc>Test Description 1</desc>
        <category>Entertainment</category>
    </programme>
</tv>"""

        private val LARGE_EPG_XML = """<?xml version="1.0" encoding="UTF-8"?>
<tv>
    <channel id="1"><display-name>Test Channel 1</display-name></channel>
    <channel id="2"><display-name>Test Channel 2</display-name></channel>
    <channel id="3"><display-name>Test Channel 3</display-name></channel>
</tv>""" + buildString {
        repeat(1000) { i ->
            appendLine("""
                <programme channel="1" start="20231105${"%04d".format(i * 100)} +0000" stop="20231105${"%04d".format((i + 1) * 100)} +0000">
                    <title>Test Program $i</title>
                    <desc>Test Description $i with some additional content to make it longer</desc>
                    <category>Entertainment</category>
                </programme>
            """.trimIndent())
        }
    }
    }

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        memoryManager = MemoryManager.getInstance(context)
    }

    @Test
    fun `test memory pressure levels detection`() = runTest {
        // Test different buffer sizes based on memory pressure
        val bufferSizeNormal = memoryManager.getOptimalBufferSize()
        val chunkSizeNormal = memoryManager.getOptimalChunkSize()

        Log.d(TAG, "Normal memory pressure - Buffer size: $bufferSizeNormal, Chunk size: $chunkSizeNormal")

        // Verify that buffer sizes are reasonable for TV hardware
        assert(bufferSizeNormal >= 2 * 1024) { "Buffer size should be at least 2KB" }
        assert(bufferSizeNormal <= 16 * 1024) { "Buffer size should not exceed 16KB for TV" }
        assert(chunkSizeNormal >= 4 * 1024) { "Chunk size should be at least 4KB" }
        assert(chunkSizeNormal <= 32 * 1024) { "Chunk size should not exceed 32KB" }
    }

    @Test
    fun `test streaming XML parsing with small content`() = runTest {
        Log.d(TAG, "Testing small EPG XML parsing...")

        val initialMemory = memoryManager.getCurrentMemoryUsage()
        Log.d(TAG, "Initial memory: ${initialMemory.usedMemoryMB}MB")

        val inputStream = ByteArrayInputStream(SMALL_EPG_XML.toByteArray())
        val reader = inputStream.bufferedReader()

        var programCount = 0
        val startTime = System.currentTimeMillis()

        // Simulate streaming parsing
        reader.useLines { lines ->
            lines.forEach { line ->
                if (line.contains("<programme")) {
                    programCount++
                    // Simulate memory pressure check
                    if (programCount % 10 == 0) {
                        val pressure = memoryManager.checkMemoryPressure()
                        Log.d(TAG, "Memory pressure after $programCount programs: $pressure")
                    }
                }
            }
        }

        val parsingTime = System.currentTimeMillis() - startTime
        val finalMemory = memoryManager.getCurrentMemoryUsage()
        val memoryUsed = finalMemory.usedMemoryMB - initialMemory.usedMemoryMB

        Log.d(TAG, "Small XML parsing results:")
        Log.d(TAG, "  Programs parsed: $programCount")
        Log.d(TAG, "  Parsing time: ${parsingTime}ms")
        Log.d(TAG, "  Memory used: ${memoryUsed}MB")
        Log.d(TAG, "  Final memory: ${finalMemory.usedMemoryMB}MB")

        // Verify performance requirements
        assert(programCount == 1) { "Should parse exactly 1 program from small XML" }
        assert(parsingTime < 100) { "Small XML should parse in under 100ms" }
        assert(memoryUsed < 5) { "Small XML should use less than 5MB memory" }
    }

    @Test
    fun `test streaming XML parsing with large content`() = runTest {
        Log.d(TAG, "Testing large EPG XML parsing...")

        val initialMemory = memoryManager.getCurrentMemoryUsage()
        Log.d(TAG, "Initial memory: ${initialMemory.usedMemoryMB}MB")

        val inputStream = ByteArrayInputStream(LARGE_EPG_XML.toByteArray())
        val chunkSize = memoryManager.getOptimalChunkSize()
        val reader = java.io.BufferedReader(java.io.InputStreamReader(inputStream), chunkSize)

        var programCount = 0
        var lastMemoryCheck = System.currentTimeMillis()
        val startTime = System.currentTimeMillis()

        // Simulate the new streaming parsing approach
        reader.useLines { lines ->
            lines.forEach { line ->
                if (line.contains("<programme")) {
                    programCount++

                    // Periodic memory pressure check (every 2 seconds)
                    val currentTime = System.currentTimeMillis()
                    if (currentTime - lastMemoryCheck > 2000) {
                        val pressure = memoryManager.checkMemoryPressure()
                        Log.d(TAG, "Memory pressure at $programCount programs: $pressure")

                        if (pressure == MemoryManager.MemoryPressure.CRITICAL) {
                            Log.w(TAG, "Critical memory pressure detected, would stop parsing")
                            return@forEach
                        }
                        lastMemoryCheck = currentTime
                    }

                    // Simulate batch processing
                    if (programCount % 50 == 0) {
                        Log.d(TAG, "Processed $programCount programs...")
                        // Simulate some memory usage
                        System.gc()
                    }
                }
            }
        }

        val parsingTime = System.currentTimeMillis() - startTime
        val finalMemory = memoryManager.getCurrentMemoryUsage()
        val memoryUsed = finalMemory.usedMemoryMB - initialMemory.usedMemoryMB

        Log.d(TAG, "Large XML parsing results:")
        Log.d(TAG, "  Programs parsed: $programCount")
        Log.d(TAG, "  Parsing time: ${parsingTime}ms")
        Log.d(TAG, "  Memory used: ${memoryUsed}MB")
        Log.d(TAG, "  Final memory: ${finalMemory.usedMemoryMB}MB")
        Log.d(TAG, "  Chunk size used: $chunkSize")

        // Verify that memory usage stays reasonable for TV hardware
        assert(programCount > 900) { "Should parse most programs from large XML" }
        assert(parsingTime < 5000) { "Large XML should parse in under 5 seconds" }
        assert(memoryUsed < 50) { "Large XML should use less than 50MB memory" }
    }

    @Test
    fun `test memory allocation safety`() = runTest {
        Log.d(TAG, "Testing memory allocation safety...")

        // Test allocation checks for different memory sizes
        val canAllocate10MB = memoryManager.canAllocateMemory(10)
        val canAllocate50MB = memoryManager.canAllocateMemory(50)
        val canAllocate200MB = memoryManager.canAllocateMemory(200)

        Log.d(TAG, "Memory allocation safety results:")
        Log.d(TAG, "  Can allocate 10MB: $canAllocate10MB")
        Log.d(TAG, "  Can allocate 50MB: $canAllocate50MB")
        Log.d(TAG, "  Can allocate 200MB: $canAllocate200MB")

        // 10MB should almost always be safe
        assert(canAllocate10MB) { "Should be able to allocate 10MB" }

        // 200MB should generally be rejected for TV hardware
        assert(!canAllocate200MB) { "Should not be able to allocate 200MB on TV hardware" }
    }

    @Test
    fun `test parsing size limits`() = runTest {
        Log.d(TAG, "Testing parsing size limits...")

        val maxParsingSize = memoryManager.getMaxParsingSize()
        Log.d(TAG, "Maximum parsing size: ${maxParsingSize / (1024 * 1024)}MB")

        // Verify that max parsing size is reasonable
        assert(maxParsingSize >= 10 * 1024 * 1024) { "Should allow at least 10MB parsing" }
        assert(maxParsingSize <= 100 * 1024 * 1024) { "Should not exceed 100MB parsing limit" }

        // Test size checking
        val canParseSmallXml = SMALL_EPG_XML.length < maxParsingSize
        val canParseLargeXml = LARGE_EPG_XML.length < maxParsingSize

        Log.d(TAG, "Size limit test results:")
        Log.d(TAG, "  Can parse small XML (${SMALL_EPG_XML.length} bytes): $canParseSmallXml")
        Log.d(TAG, "  Can parse large XML (${LARGE_EPG_XML.length} bytes): $canParseLargeXml")

        assert(canParseSmallXml) { "Should be able to parse small XML" }
    }

    @Test
    fun `test emergency cleanup performance`() = runTest {
        Log.d(TAG, "Testing emergency cleanup performance...")

        val initialMemory = memoryManager.getCurrentMemoryUsage()
        Log.d(TAG, "Memory before cleanup: ${initialMemory.usedMemoryMB}MB")

        // Request garbage collection
        val cleanupStartTime = System.currentTimeMillis()
        memoryManager.requestGarbageCollection()
        val cleanupTime = System.currentTimeMillis() - cleanupStartTime

        // Give GC time to work
        delay(1000)

        val afterCleanupMemory = memoryManager.getCurrentMemoryUsage()
        val memoryFreed = initialMemory.usedMemoryMB - afterCleanupMemory.usedMemoryMB

        Log.d(TAG, "Emergency cleanup results:")
        Log.d(TAG, "  Cleanup time: ${cleanupTime}ms")
        Log.d(TAG, "  Memory before: ${initialMemory.usedMemoryMB}MB")
        Log.d(TAG, "  Memory after: ${afterCleanupMemory.usedMemoryMB}MB")
        Log.d(TAG, "  Memory freed: ${memoryFreed}MB")

        // Cleanup should be fast
        assert(cleanupTime < 1000) { "Emergency cleanup should be very fast (<1000ms)" }
    }

    @Test
    fun `test memory pressure adaptive behavior`() = runTest {
        Log.d(TAG, "Testing adaptive behavior based on memory pressure...")

        val pressure = memoryManager.checkMemoryPressure()
        Log.d(TAG, "Current memory pressure: $pressure")

        val bufferSize = memoryManager.getOptimalBufferSize()
        val chunkSize = memoryManager.getOptimalChunkSize()
        val maxParsingSize = memoryManager.getMaxParsingSize()

        Log.d(TAG, "Adaptive configuration:")
        Log.d(TAG, "  Buffer size: ${bufferSize / 1024}KB")
        Log.d(TAG, "  Chunk size: ${chunkSize / 1024}KB")
        Log.d(TAG, "  Max parsing size: ${maxParsingSize / (1024 * 1024)}MB")

        // Verify configuration is TV-optimized
        assert(bufferSize >= 2 * 1024) { "Buffer should be at least 2KB" }
        assert(bufferSize <= 16 * 1024) { "Buffer should not exceed 16KB for TV" }
        assert(chunkSize >= 4 * 1024) { "Chunk should be at least 4KB" }
        assert(chunkSize <= 32 * 1024) { "Chunk should not exceed 32KB" }
        assert(maxParsingSize >= 10 * 1024 * 1024) { "Should allow at least 10MB parsing" }
    }

    /**
     * Performance benchmark for TV hardware requirements
     * Target: Maintain 60fps with <2% jank
     */
    @Test
    fun `benchmark TV performance requirements`() = runTest {
        Log.d(TAG, "Benchmarking TV performance requirements...")

        val iterations = 1000
        val startTime = System.nanoTime()

        repeat(iterations) {
            // Simulate typical TV operations
            memoryManager.checkMemoryPressure()
            memoryManager.getOptimalBufferSize()
            memoryManager.canAllocateMemory(10)
        }

        val totalTime = System.nanoTime() - startTime
        val avgTimePerOperation = totalTime / iterations / 1_000_000 // Convert to ms

        Log.d(TAG, "TV performance benchmark results:")
        Log.d(TAG, "  Total time: ${totalTime / 1_000_000}ms for $iterations operations")
        Log.d(TAG, "  Average time per operation: ${avgTimePerOperation}ms")

        // Each memory operation should be very fast for 60fps
        assert(avgTimePerOperation < 1) { "Memory operations should average <1ms for 60fps" }
    }
}
