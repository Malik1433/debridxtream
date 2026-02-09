package com.tvonnet.debridxtreamiptv.utils.error

import android.content.Context
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ErrorManagerTest {

    private lateinit var context: Context

    @Before
    fun setup() {
        context = mockk(relaxed = true)
        ErrorManagerTemp.getInstance(context)
        ErrorManagerTemp.clearHistory()
    }

    @Test
    fun `shouldRetry returns true for first 3 attempts`() {
        val operation = "test_operation"
        
        assertTrue(ErrorManagerTemp.shouldRetry(operation)) // 1
        assertTrue(ErrorManagerTemp.shouldRetry(operation)) // 2
        assertTrue(ErrorManagerTemp.shouldRetry(operation)) // 3
        assertFalse(ErrorManagerTemp.shouldRetry(operation)) // 4
    }

    @Test
    fun `resetRetryCount clears retry count`() {
        val operation = "test_operation_reset"
        
        assertTrue(ErrorManagerTemp.shouldRetry(operation))
        assertTrue(ErrorManagerTemp.shouldRetry(operation))
        
        ErrorManagerTemp.resetRetryCount(operation)
        
        assertTrue(ErrorManagerTemp.shouldRetry(operation)) // Should be 1 again
    }
}
