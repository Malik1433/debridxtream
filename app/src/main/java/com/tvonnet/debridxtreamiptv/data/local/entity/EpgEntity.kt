package com.tvonnet.debridxtreamiptv.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Week 11: EPG (Electronic Program Guide) Room Entity
 * 
 * Stores program guide information for Live TV channels
 * 
 * Features:
 * - Channel-based program tracking
 * - Time-based queries (current/upcoming programs)
 * - Composite index for efficient queries
 * - Auto-expiry based on stop time
 */
@Entity(
    tableName = "epg",
    indices = [
        Index(value = ["channelId", "start", "stop"]),
        Index(value = ["channelId"]),
        Index(value = ["start"])
    ]
)
data class EpgEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    
    /**
     * Channel ID (from XtreamStream.stream_id)
     */
    val channelId: String,
    
    /**
     * Program start time (Unix timestamp in milliseconds)
     */
    val start: Long,
    
    /**
     * Program stop time (Unix timestamp in milliseconds)
     */
    val stop: Long,
    
    /**
     * Program title
     */
    val title: String?,
    
    /**
     * Program description
     */
    val description: String?,
    
    /**
     * Program category (e.g., "News", "Sports", "Movie")
     */
    val category: String?,
    
    /**
     * When this EPG entry was cached (Unix timestamp in milliseconds)
     * Used for TTL-based cleanup
     */
    val cachedAt: Long = System.currentTimeMillis()
) {
    /**
     * Check if this program is currently playing
     */
    fun isPlaying(): Boolean {
        val now = System.currentTimeMillis()
        return now >= start && now < stop
    }
    
    /**
     * Check if this program is upcoming (starts within next 2 hours)
     */
    fun isUpcoming(): Boolean {
        val now = System.currentTimeMillis()
        val twoHoursFromNow = now + (2 * 60 * 60 * 1000)
        return start > now && start <= twoHoursFromNow
    }
    
    /**
     * Check if this program has already ended
     */
    fun hasEnded(): Boolean {
        return System.currentTimeMillis() >= stop
    }
    
    /**
     * Get program duration in minutes
     */
    fun getDurationMinutes(): Int {
        return ((stop - start) / (60 * 1000)).toInt()
    }
}

/**
 * Collapse a channel's EPG programmes into a strictly non-overlapping timeline.
 *
 * XMLTV feeds routinely contain overlapping programmes for a single channel:
 * exact duplicate `<programme>` entries (re-listed or merged from two sources),
 * or a coarse placeholder block layered under granular ones. The guide grids
 * (EpgGridView and the in-player guide) position every block purely from its
 * (start, stop), so any two overlapping programmes get painted on top of each
 * other — the classic "two different titles ghosted in one cell" symptom.
 *
 * This applies classic interval-scheduling (earliest-stop-first greedy), which
 * keeps the maximum number of programmes while guaranteeing that no two occupy
 * the same instant. Zero-/negative-length intervals are dropped. The result is
 * ordered by start time so callers can render it directly.
 */
fun List<EpgEntity>.deOverlap(): List<EpgEntity> {
    if (size <= 1) return this.filter { it.stop > it.start }
    val sorted = filter { it.stop > it.start }
        .sortedWith(compareBy({ it.stop }, { it.start }))
    val kept = ArrayList<EpgEntity>(sorted.size)
    var lastStop = Long.MIN_VALUE
    for (p in sorted) {
        if (p.start >= lastStop) {
            kept.add(p)
            lastStop = p.stop
        }
    }
    kept.sortBy { it.start }
    return kept
}

