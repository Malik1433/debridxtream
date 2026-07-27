package com.tvonnet.debridxtreamiptv.player.stabilized

import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.isVisible
import com.tvonnet.debridxtreamiptv.util.GlideUtils

/**
 * Logo-forward channel branding for the Live rows (surf drawer, TV Guide, ON AIR).
 *
 * Most provider logos are transparent PNGs, so a grey tile behind one reads as a box drawn *around*
 * the logo. Here the tile is a **fallback, not a frame**: it carries the initials when there is no
 * usable logo, and disappears the moment a real image renders so the logo sits directly on the row.
 *
 * The ordering matters and is why this is shared rather than repeated per adapter:
 *  - the tile + initials stay up **while loading**, so a slow logo never leaves an empty hole;
 *  - they come back on failure, including Glide's recently-failed short-circuit — a broken URL must
 *    fall back to initials, not to a blank box;
 *  - every row is **reset first**, because these are RecyclerView holders: without it a recycled row
 *    keeps the previous channel's logo until the new one loads.
 */
internal object LiveLogoBinder {

    /**
     * @param tile the background/placeholder view (may be the same view that hosts [image])
     * @param image where the real logo renders; hidden whenever there is none
     * @param initials the fallback text shown with the tile
     */
    fun bind(tile: View, image: ImageView, initials: TextView, name: String?, logoUrl: String?) {
        initials.text = LiveChannelVisuals.channelInitials(name)

        // Reset to the fallback first — recycled rows otherwise show the previous channel.
        showFallback(tile, image, initials)

        if (logoUrl.isNullOrBlank()) return

        GlideUtils.loadChannelLogo(image, logoUrl) { loaded ->
            if (loaded) {
                image.isVisible = true
                initials.isVisible = false
                // Drop the tile so the logo sits on the row itself, not inside a box.
                tile.background = null
            } else {
                showFallback(tile, image, initials)
            }
        }
    }

    private fun showFallback(tile: View, image: ImageView, initials: TextView) {
        image.isVisible = false
        image.setImageDrawable(null)
        initials.isVisible = true
        tile.setBackgroundResource(com.tvonnet.debridxtreamiptv.R.drawable.bg_live_channel_logo_tile)
    }
}
