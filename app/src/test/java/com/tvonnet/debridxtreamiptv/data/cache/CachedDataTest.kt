package com.tvonnet.debridxtreamiptv.data.cache

import org.junit.Assert.*
import org.junit.Test

/**
 * QA Recommendation #1: Unit Tests for CachedData
 * 
 * Tests for TTL wrapper class functionality
 */
class CachedDataTest {
    
    @Test
    fun `cachedData is fresh when just created`() {
        // Given
        val data = CachedData(data = "test", ttl = 1000L)
        
        // Then
        assertTrue(data.isFresh())
        assertFalse(data.isExpired())
    }
    
    @Test
    fun `cachedData expires after TTL`() {
        // Given
        val data = CachedData(
            data = "test",
            timestamp = System.currentTimeMillis() - 2000L, // 2 seconds ago
            ttl = 1000L // 1 second TTL
        )
        
        // Then
        assertTrue(data.isExpired())
        assertFalse(data.isFresh())
    }
    
    @Test
    fun `cachedData age is calculated correctly`() {
        // Given
        val timestamp = System.currentTimeMillis() - 5000L // 5 seconds ago
        val data = CachedData(data = "test", timestamp = timestamp)
        
        // When
        val age = data.getAge()
        
        // Then
        assertTrue(age >= 5000L)
        assertTrue(age < 6000L) // Allow 1s margin
    }
    
    @Test
    fun `cachedData time until expiry is calculated correctly`() {
        // Given
        val data = CachedData(
            data = "test",
            timestamp = System.currentTimeMillis(),
            ttl = 10000L // 10 seconds
        )
        
        // When
        val timeUntilExpiry = data.getTimeUntilExpiry()
        
        // Then
        assertTrue(timeUntilExpiry > 9000L)
        assertTrue(timeUntilExpiry <= 10000L)
    }
    
    @Test
    fun `cachedData time until expiry is zero when expired`() {
        // Given
        val data = CachedData(
            data = "test",
            timestamp = System.currentTimeMillis() - 2000L,
            ttl = 1000L
        )
        
        // When
        val timeUntilExpiry = data.getTimeUntilExpiry()
        
        // Then
        assertEquals(0L, timeUntilExpiry)
    }
    
    @Test
    fun `cachedData freshness percentage is correct`() {
        // Given
        val now = System.currentTimeMillis()
        val data = CachedData(
            data = "test",
            timestamp = now - 5000L, // 5s ago
            ttl = 10000L // 10s TTL
        )
        
        // When
        val percentage = data.getFreshnessPercentage()
        
        // Then - Should be around 50%
        assertTrue(percentage >= 45)
        assertTrue(percentage <= 55)
    }
    
    @Test
    fun `cachedData freshness percentage is zero when expired`() {
        // Given
        val data = CachedData(
            data = "test",
            timestamp = System.currentTimeMillis() - 2000L,
            ttl = 1000L
        )
        
        // When
        val percentage = data.getFreshnessPercentage()
        
        // Then
        assertEquals(0, percentage)
    }
    
    @Test
    fun `extension function hours works correctly`() {
        // When
        val ttl = 2.hours
        
        // Then
        assertEquals(2 * 60 * 60 * 1000L, ttl)
    }
    
    @Test
    fun `extension function days works correctly`() {
        // When
        val ttl = 3.days
        
        // Then
        assertEquals(3 * 24 * 60 * 60 * 1000L, ttl)
    }
    
    @Test
    fun `extension function minutes works correctly`() {
        // When
        val ttl = 30.minutes
        
        // Then
        assertEquals(30 * 60 * 1000L, ttl)
    }
    
    @Test
    fun `default TTL is 24 hours`() {
        // When
        val data = CachedData(data = "test")
        
        // Then
        assertEquals(CachedData.DEFAULT_TTL, data.ttl)
        assertEquals(24 * 60 * 60 * 1000L, data.ttl)
    }
    
    @Test
    fun `toString provides readable output`() {
        // Given
        val data = CachedData(
            data = "test",
            timestamp = System.currentTimeMillis() - 5000L,
            ttl = 10000L
        )
        
        // When
        val output = data.toString()
        
        // Then
        assertTrue(output.contains("age="))
        assertTrue(output.contains("ttl="))
        assertTrue(output.contains("expired="))
    }
}

