package com.tvonnet.debridxtreamiptv.player.stabilized

import android.graphics.drawable.GradientDrawable
import java.util.Locale

/**
 * C8: how a live channel looks when it has no logo — initials, tile gradient, accent colour and the
 * quality badge.
 *
 * Shared by the OSD's channel bug, the surf drawer's preview strip and three adapters, which all
 * used to reach into `LivePlayerOsdManager`'s companion for it — an inversion, since an adapter has
 * no business depending on the OSD manager.
 *
 * **The colour is derived from the name's hash, and that is the point:** the same channel must get
 * the same tile every time it appears, in the bug, the drawer, the guide and the ON AIR strip. Any
 * change to [TILE_GRADIENTS]' size or order silently re-colours every channel in the app.
 *
 * Moved verbatim from `LivePlayerOsdManager`.
 */
object LiveChannelVisuals {

    // Genre-bg gradients from the design (LOGO_BG); picked by name hash since
    // Xtream streams carry no genre.
    private val TILE_GRADIENTS = listOf(
        intArrayOf(0xFF1A5FB4.toInt(), 0xFF3584E4.toInt()), // news
        intArrayOf(0xFF00CC6A.toInt(), 0xFF00FF88.toInt()), // sports
        intArrayOf(0xFFB8860B.toInt(), 0xFFD4AF37.toInt()), // movies
        intArrayOf(0xFF7C3AED.toInt(), 0xFFA78BFA.toInt()), // ent
        intArrayOf(0xFFE11D74.toInt(), 0xFFFF6BA8.toInt()), // kids
        intArrayOf(0xFFE68A00.toInt(), 0xFFFFAA00.toInt())  // music
    )

    private fun gradientFor(name: String?): IntArray =
        TILE_GRADIENTS[Math.abs(name?.hashCode() ?: 0) % TILE_GRADIENTS.size]

    /** Stable accent color for a category's color-bar (v2 rich surf rows). */
    fun accentColor(name: String?): Int = gradientFor(name)[1]

    /**
     * Up to four letters/digits for the logo placeholder.
     *
     * Provider names routinely carry a `"REGION: Channel"` prefix and separators, so the part before
     * the colon is dropped and the first word with any letter or digit wins — otherwise every
     * channel in a region would show the same two letters.
     */
    fun channelInitials(name: String?): String {
        if (name.isNullOrBlank()) return "TV"
        val cleaned = name.substringAfter(':').trim()
        val word = cleaned.split(' ', '-', '_').firstOrNull { it.any(Char::isLetterOrDigit) } ?: return "TV"
        return word.filter(Char::isLetterOrDigit).take(4).uppercase(Locale.getDefault())
    }

    fun channelTileGradient(name: String?, cornerRadiusPx: Float, strokeColor: Int = 0x14FFFFFF): GradientDrawable {
        return GradientDrawable(GradientDrawable.Orientation.TL_BR, gradientFor(name)).apply {
            cornerRadius = cornerRadiusPx
            setStroke(1, strokeColor)
        }
    }

    /** Dark radial-ish backdrop used behind the watermark while zapping. */
    fun watermarkGradient(name: String?): GradientDrawable {
        val accent = gradientFor(name)[0]
        val dimmed = (0xFF shl 24) or (
            (((accent shr 16 and 0xFF) * 0.28f).toInt() shl 16) or
            (((accent shr 8 and 0xFF) * 0.28f).toInt() shl 8) or
            ((accent and 0xFF) * 0.28f).toInt()
        )
        return GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(dimmed, 0xFF06090E.toInt())
        )
    }

    /**
     * Quality badge read out of the channel name. Order matters: "FHD" and "1080" must be tested
     * before the bare "HD" substring, which they both contain.
     */
    fun detectQuality(name: String?): String? {
        val upper = name?.uppercase(Locale.getDefault()) ?: return null
        return when {
            upper.contains("4K") || upper.contains("UHD") -> "4K UHD"
            upper.contains("FHD") || upper.contains("1080") -> "FHD"
            upper.contains("HD") -> "HD"
            else -> null
        }
    }
}
