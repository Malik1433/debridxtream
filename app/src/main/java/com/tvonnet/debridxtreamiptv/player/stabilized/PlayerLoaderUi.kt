package com.tvonnet.debridxtreamiptv.player.stabilized

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.app.Activity
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.isVisible
import com.bumptech.glide.Glide
import com.tvonnet.debridxtreamiptv.R

/**
 * The Stremio-style cinematic loader (Phase P11): the backdrop/poster behind the
 * settling title with only a subtle progress hint, hiding the technical
 * resolve/retry noise. Stays up until the first video frame renders (STATE_READY)
 * or a terminal failure surfaces.
 *
 * Owns its four views and both animators, so nothing outside has to remember to
 * cancel them — [release] does.
 */
internal class PlayerLoaderUi(private val hideReconnectBanner: () -> Unit) {

    private var overlay: View? = null
    private var titleView: TextView? = null
    private var backdropView: ImageView? = null
    private var barSegment: View? = null

    private var pulse: ObjectAnimator? = null
    private var barGlide: ObjectAnimator? = null

    fun bind(activity: Activity) {
        overlay = activity.findViewById(R.id.layout_debrid_resolving)
        titleView = activity.findViewById(R.id.tv_resolving_status)
        backdropView = activity.findViewById(R.id.iv_resolving_bg)
        barSegment = activity.findViewById(R.id.loader_bar_seg)
    }

    fun isVisible(): Boolean = overlay?.isVisible == true

    fun show(rawTitle: String?, backdropUrl: String?) {
        val view = overlay ?: return
        titleView?.text = cleanLoaderTitle(rawTitle)
        backdropView?.let { iv ->
            if (backdropUrl != null) Glide.with(iv).load(backdropUrl).centerCrop().into(iv)
            else iv.setImageDrawable(null)
        }
        view.isVisible = true
        view.alpha = 1f
        titleView?.let { t ->
            pulse?.cancel()
            t.animate().cancel()
            // Smooth cinematic entrance: fade + gentle settle-in (no jarring zoom).
            t.alpha = 0f; t.scaleX = 0.95f; t.scaleY = 0.95f
            t.animate()
                .alpha(1f).scaleX(1f).scaleY(1f)
                .setDuration(620)
                .setInterpolator(DecelerateInterpolator(1.4f))
                .withEndAction {
                    if (overlay?.isVisible != true) return@withEndAction
                    // Clearly-felt yet smooth "breath": the whole word scales + dims as one
                    // unit. A hardware layer rasterises the text once so scaling is a bitmap
                    // transform — buttery smooth, and the glyphs never re-blur/float apart.
                    t.setLayerType(View.LAYER_TYPE_HARDWARE, null)
                    pulse = ObjectAnimator.ofPropertyValuesHolder(
                        t,
                        PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.05f),
                        PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.05f),
                        PropertyValuesHolder.ofFloat(View.ALPHA, 1f, 0.86f)
                    ).apply {
                        duration = 1700
                        repeatCount = ValueAnimator.INFINITE
                        repeatMode = ValueAnimator.REVERSE
                        interpolator = AccelerateDecelerateInterpolator()
                        start()
                    }
                }
                .start()
        }
        // Clean indeterminate loader: a soft cyan segment sweeps smoothly back and forth
        // WITHIN the track (never off the edges, no jump-back) for a polished, contained look.
        barSegment?.let { seg ->
            barGlide?.cancel()
            seg.translationX = 0f
            seg.post {
                // Travel = track width - segment width, so the segment stays fully inside.
                val track = (seg.parent as? View)?.width ?: return@post
                val travel = (track - seg.width).coerceAtLeast(0).toFloat()
                barGlide = ObjectAnimator.ofFloat(seg, View.TRANSLATION_X, 0f, travel).apply {
                    duration = 1050
                    repeatCount = ValueAnimator.INFINITE
                    repeatMode = ValueAnimator.REVERSE
                    interpolator = AccelerateDecelerateInterpolator()
                    start()
                }
            }
        }
        hideReconnectBanner()
    }

    fun hide() {
        pulse?.cancel(); pulse = null
        barGlide?.cancel(); barGlide = null
        titleView?.let {
            it.animate().cancel()
            it.setLayerType(View.LAYER_TYPE_NONE, null)
            it.scaleX = 1f; it.scaleY = 1f; it.alpha = 1f
        }
        val view = overlay ?: return
        if (!view.isVisible) return
        view.animate().alpha(0f).setDuration(220).withEndAction {
            view.isVisible = false
            view.alpha = 1f
        }.start()
    }

    /** Cancels the infinite animators — call from onDestroy. */
    fun release() {
        pulse?.cancel(); pulse = null
        barGlide?.cancel(); barGlide = null
    }
}
