package com.tvonnet.debridxtreamiptv.data.cache

/**
 * Week 8: Cache Expiry/TTL Implementation
 * 
 * Wrapper class for cached data with timestamp and Time-To-Live (TTL).
 * This enables automatic cache expiration and freshness checks.
 * 
 * Usage:
 * ```kotlin
 * val cached = CachedData(data = channels, ttl = 24.hours)
 * if (cached.isExpired()) {
 *     // Fetch fresh data
 * } else {
 *     // Use cached data
 * }
 * ```
 */
data class CachedData<T>(
    val data: T,
    val timestamp: Long = System.currentTimeMillis(),
    val ttl: Long = DEFAULT_TTL
) {
    companion object {
        // Default TTL: 24 hours
        const val DEFAULT_TTL = 24 * 60 * 60 * 1000L // 24 hours in milliseconds
        
        // Common TTL values
        const val TTL_1_HOUR = 60 * 60 * 1000L
        const val TTL_6_HOURS = 6 * 60 * 60 * 1000L
        const val TTL_24_HOURS = 24 * 60 * 60 * 1000L
        const val TTL_7_DAYS = 7 * 24 * 60 * 60 * 1000L
        const val TTL_30_DAYS = 30 * 24 * 60 * 60 * 1000L
    }
    
    /**
     * Check if cached data is expired
     */
    fun isExpired(): Boolean {
        val now = System.currentTimeMillis()
        return (now - timestamp) > ttl
    }
    
    /**
     * Check if cached data is still fresh
     */
    fun isFresh(): Boolean = !isExpired()
    
    /**
     * Get age of cached data in milliseconds
     */
    fun getAge(): Long = System.currentTimeMillis() - timestamp
    
    /**
     * Get remaining time until expiry in milliseconds
     */
    fun getTimeUntilExpiry(): Long {
        val age = getAge()
        return if (age < ttl) {
            ttl - age
        } else {
            0L
        }
    }
    
    /**
     * Get percentage of TTL remaining (0-100)
     */
    fun getFreshnessPercentage(): Int {
        val age = getAge()
        if (age >= ttl) return 0
        return ((ttl - age) * 100 / ttl).toInt()
    }
    
    override fun toString(): String {
        val ageSeconds = getAge() / 1000
        val ttlSeconds = ttl / 1000
        return "CachedData(age=${ageSeconds}s, ttl=${ttlSeconds}s, expired=${isExpired()})"
    }
}

/**
 * Extension functions for easy TTL creation
 */

val Int.hours: Long
    get() = this * 60 * 60 * 1000L

val Int.days: Long
    get() = this * 24 * 60 * 60 * 1000L

val Int.minutes: Long
    get() = this * 60 * 1000L

