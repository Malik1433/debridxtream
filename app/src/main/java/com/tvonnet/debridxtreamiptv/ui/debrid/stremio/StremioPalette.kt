package com.tvonnet.debridxtreamiptv.ui.debrid.stremio

import kotlin.math.abs

/** The exact dark placeholder gradient bases used across "Stremio Home.dc.html". */
internal object StremioPalette {
    val bases = intArrayOf(
        0xFF1A2744.toInt(), 0xFF251635.toInt(), 0xFF102438.toInt(), 0xFF1C1030.toInt(),
        0xFF0D2035.toInt(), 0xFF1E1535.toInt(), 0xFF13243A.toInt(), 0xFF1A1020.toInt(),
        0xFF0E2235.toInt(), 0xFF1E1430.toInt(), 0xFF0A001A.toInt(), 0xFF2A1040.toInt()
    )

    fun forIndex(i: Int): Int = bases[((i % bases.size) + bases.size) % bases.size]

    fun forKey(key: String): Int = bases[abs(key.hashCode()) % bases.size]
}
