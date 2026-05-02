// =====================================================================
// CinematicTheme.kt
// Drop into: app/src/main/java/<your_pkg>/ui/theme/CinematicTheme.kt
//
// Centralized helpers for applying the cinematic theme programmatically.
// Use this from fragments/activities that previously hard-coded the legacy
// gold/black colors.
// =====================================================================
package com.tvonnet.debridxtreamiptv.ui.theme

import android.content.Context
import android.graphics.Color
import android.view.View
import android.widget.TextView
import androidx.annotation.ColorInt
import androidx.core.content.ContextCompat
import com.tvonnet.debridxtreamiptv.R

object CinematicTheme {

    // ---- Color tokens -------------------------------------------------
    @ColorInt fun ink900(ctx: Context)   = ContextCompat.getColor(ctx, R.color.cin_ink_900)
    @ColorInt fun ink800(ctx: Context)   = ContextCompat.getColor(ctx, R.color.cin_ink_800)
    @ColorInt fun ink700(ctx: Context)   = ContextCompat.getColor(ctx, R.color.cin_ink_700)
    @ColorInt fun ink600(ctx: Context)   = ContextCompat.getColor(ctx, R.color.cin_ink_600)

    @ColorInt fun gold500(ctx: Context)  = ContextCompat.getColor(ctx, R.color.cin_gold_500)
    @ColorInt fun gold300(ctx: Context)  = ContextCompat.getColor(ctx, R.color.cin_gold_300)
    @ColorInt fun gold700(ctx: Context)  = ContextCompat.getColor(ctx, R.color.cin_gold_700)
    @ColorInt fun goldGlow(ctx: Context) = ContextCompat.getColor(ctx, R.color.cin_gold_glow_25)

    @ColorInt fun textPrimary(ctx: Context)   = ContextCompat.getColor(ctx, R.color.cin_text_primary)
    @ColorInt fun textSecondary(ctx: Context) = ContextCompat.getColor(ctx, R.color.cin_text_secondary)
    @ColorInt fun textTertiary(ctx: Context)  = ContextCompat.getColor(ctx, R.color.cin_text_tertiary)

    @ColorInt fun liveRed(ctx: Context) = ContextCompat.getColor(ctx, R.color.cin_live_red)

    // ---- Convenience extensions --------------------------------------

    /** Apply the section-header style: bold, secondary, slight letter-spacing. */
    fun TextView.applySectionHeader() {
        setTextColor(textSecondary(context))
        textSize = 18f
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        letterSpacing = 0.04f
        isAllCaps = false
    }

    /** Apply the gold accent color to any text view (titles, hero metadata, etc). */
    fun TextView.applyGoldAccent() {
        setTextColor(gold300(context))
        letterSpacing = 0.18f
        isAllCaps = true
        setTypeface(typeface, android.graphics.Typeface.BOLD)
    }

    /** Lift + glow when a card receives focus. Wire to View.OnFocusChangeListener. */
    fun View.applyFocusLift(onFocus: Boolean) {
        animate()
            .scaleX(if (onFocus) 1.06f else 1.0f)
            .scaleY(if (onFocus) 1.06f else 1.0f)
            .translationZ(if (onFocus) 24f else 0f)
            .setDuration(220)
            .start()
    }
}
