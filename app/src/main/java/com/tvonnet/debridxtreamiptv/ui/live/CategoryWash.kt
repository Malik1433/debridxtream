package com.tvonnet.debridxtreamiptv.ui.live

import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import android.view.View
import androidx.core.content.ContextCompat
import com.tvonnet.debridxtreamiptv.player.stabilized.ChannelAccentWash

/**
 * The category's colour washing across its row — but **only the row the user is on**.
 *
 * Channel rows can all carry their colour at once because a channel list is scanned one row at a
 * time and the colours are weak. A category list is different: thirty-odd rows are on screen
 * together, so colouring every one turns the sidebar into a rainbow, which reads as noise rather
 * than polish. Restricting it to the focused/selected row keeps the colour meaningful — it tells
 * you where you are — and leaves the list calm.
 *
 * Applied as a layer over the row's own background rather than through an extra view: a background
 * is drawn before its children, so the wash sits under the label instead of tinting it.
 */
object CategoryWash {

    /**
     * @param baseBackgroundRes the row's normal background (usually a state selector); it stays
     *   underneath so focus borders and selected states keep working.
     */
    fun apply(row: View, categoryName: String?, highlighted: Boolean, baseBackgroundRes: Int, cornerRadiusDp: Float = 6f) {
        val ctx = row.context
        val base: Drawable? = ContextCompat.getDrawable(ctx, baseBackgroundRes)
        row.background = if (!highlighted || base == null) {
            base
        } else {
            LayerDrawable(
                arrayOf(
                    base,
                    ChannelAccentWash.forRow(
                        color = CategoryAccents.colorFor(categoryName),
                        focused = true,
                        density = ctx.resources.displayMetrics.density,
                        cornerRadiusDp = cornerRadiusDp
                    )
                )
            )
        }
    }
}
