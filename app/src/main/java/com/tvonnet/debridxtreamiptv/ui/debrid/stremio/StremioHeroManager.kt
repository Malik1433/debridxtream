package com.tvonnet.debridxtreamiptv.ui.debrid.stremio

import android.animation.ObjectAnimator
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.Animation
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.tvonnet.debridxtreamiptv.R
import com.tvonnet.debridxtreamiptv.util.MediaTitleCleaner

/** A hero slide (source-agnostic). */
internal data class StremioHeroSlide(
    val title: String,
    val rating: String?,
    val meta: String,
    val desc: String?,
    val backdropUrl: String?,
    val onPlay: () -> Unit
)

/**
 * Hero banner: 5-item carousel (6s auto-advance, crossfade only), Ken-Burns backdrop, pulsing label
 * dot, dot indicators. Staggered fade-in only on manual prev/next/dot changes.
 */
internal class StremioHeroManager(
    private val fragment: StremioHomeFragment,
    private val root: View
) {
    private val labels = arrayOf("FEATURED TODAY", "TOP SERIES", "NOW IN 4K", "NEW RELEASE", "TRENDING")

    private val backdrop: ImageView = root.findViewById(R.id.iv_hero_background)
    private val pulseDot: View = root.findViewById(R.id.hero_pulse_dot)
    private val labelView: TextView = root.findViewById(R.id.hero_label)
    private val titleView: TextView = root.findViewById(R.id.heroTitle)
    private val ratingView: TextView = root.findViewById(R.id.hero_rating)
    private val metaView: TextView = root.findViewById(R.id.hero_meta)
    private val badge4k: TextView = root.findViewById(R.id.hero_badge_4k)
    private val badgeDolby: TextView = root.findViewById(R.id.hero_badge_dolby)
    private val genresRow: LinearLayout = root.findViewById(R.id.hero_genres_row)
    private val descView: TextView = root.findViewById(R.id.hero_description)
    private val dots: LinearLayout = root.findViewById(R.id.hero_dots)
    private val content: LinearLayout = root.findViewById(R.id.heroContentContainer)

    private var items: List<StremioHeroSlide> = emptyList()
    private var currentKey: List<String> = emptyList()
    private var index = 0

    private val handler = Handler(Looper.getMainLooper())
    private val advance = object : Runnable {
        override fun run() {
            if (items.size > 1) {
                index = (index + 1) % items.size
                apply(animated = false)
            }
            handler.postDelayed(this, 6_000L)
        }
    }
    private var kenBurns: ObjectAnimator? = null

    fun setup() {
        startPulse()
        startKenBurns()
        // hero_prev/hero_next are decorative (non-focusable on TV); stepping is via Play-btn L/R.
        root.findViewById<View>(R.id.heroPlayBtn).setOnClickListener { items.getOrNull(index)?.onPlay?.invoke() }
        root.findViewById<View>(R.id.heroTrailerBtn).setOnClickListener { items.getOrNull(index)?.onPlay?.invoke() }
    }

    fun setSlides(slides: List<StremioHeroSlide>) {
        val next = slides.take(5)
        // Compare by content signature — StremioHeroSlide holds an onPlay lambda, so data-class
        // equality is always false and would reset the carousel/timer on every emission.
        val key = next.map { "${it.title}|${it.backdropUrl}" }
        if (key == currentKey) return
        currentKey = key
        items = next
        index = 0
        buildDots()
        content.visibility = if (items.isEmpty()) View.INVISIBLE else View.VISIBLE
        if (items.isNotEmpty()) apply(animated = false)
        restartTimer()
    }

    fun onResumeTimer() { if (items.size > 1) restartTimer() }
    fun onPauseTimer() { handler.removeCallbacks(advance) }

    /** LEFT/RIGHT while a hero button is focused. */
    fun manualStep(dir: Int) = step(dir)

    private fun step(dir: Int) {
        if (items.isEmpty()) return
        index = (index + dir + items.size) % items.size
        apply(animated = true)
        restartTimer()
    }

    private fun restartTimer() {
        handler.removeCallbacks(advance)
        if (items.size > 1) handler.postDelayed(advance, 6_000L)
    }

    private fun apply(animated: Boolean) {
        val item = items.getOrNull(index) ?: return
        labelView.text = labels[index % labels.size]
        titleView.text = MediaTitleCleaner.clean(item.title)
        val hasRating = !item.rating.isNullOrBlank() && item.rating != "0" && item.rating != "0.0"
        ratingView.visibility = if (hasRating) View.VISIBLE else View.GONE
        if (hasRating) ratingView.text = "⭐ ${item.rating}"
        metaView.text = item.meta
        badge4k.visibility = View.GONE
        badgeDolby.visibility = View.GONE
        genresRow.visibility = View.GONE
        descView.text = item.desc ?: "Experience high-quality streaming on DebridXtream."

        Glide.with(fragment).load(item.backdropUrl)
            // IMG-3: same hero-vs-cache-thrash fix as HomeHeroManager.
            .format(com.bumptech.glide.load.DecodeFormat.PREFER_RGB_565)
            .override(960, 540)
            .transition(DrawableTransitionOptions.withCrossFade(500)).into(backdrop)

        updateDots()
        if (animated) staggerIn()
    }

    private fun staggerIn() {
        val targets = listOf<View?>(
            root.findViewById(R.id.hero_label_row), titleView,
            root.findViewById(R.id.hero_badges_row), genresRow, descView,
            content.getChildAt(content.childCount - 1)
        )
        val delays = longArrayOf(0, 60, 100, 130, 160, 200)
        val density = root.resources.displayMetrics.density
        targets.forEachIndexed { i, v ->
            v ?: return@forEachIndexed
            v.animate().cancel()
            v.alpha = 0f
            v.translationY = 7f * density
            v.animate().alpha(1f).translationY(0f)
                .setStartDelay(delays.getOrElse(i) { 0 }).setDuration(500)
                .setInterpolator(DecelerateInterpolator()).start()
        }
    }

    private fun buildDots() {
        dots.removeAllViews()
        val density = root.resources.displayMetrics.density
        for (i in items.indices) {
            val dot = View(root.context)
            dot.layoutParams = LinearLayout.LayoutParams((4 * density).toInt(), (2 * density).toInt())
                .apply { marginEnd = (4 * density).toInt() }
            dot.setBackgroundResource(R.drawable.stremio_hero_dot)
            dot.isFocusable = false  // decorative indicator; hero stepping is via Play-btn L/R
            dots.addView(dot)
        }
        updateDots()
    }

    private fun updateDots() {
        val density = root.resources.displayMetrics.density
        for (i in 0 until dots.childCount) {
            val dot = dots.getChildAt(i)
            dot.layoutParams = dot.layoutParams.apply { width = ((if (i == index) 12f else 4f) * density).toInt() }
            dot.setBackgroundResource(if (i == index) R.drawable.stremio_hero_dot_active else R.drawable.stremio_hero_dot)
        }
    }

    private fun startPulse() {
        pulseDot.startAnimation(android.view.animation.AlphaAnimation(0.4f, 1f).apply {
            duration = 1000; repeatCount = Animation.INFINITE; repeatMode = Animation.REVERSE
        })
    }

    private fun startKenBurns() {
        backdrop.post {
            // Anchor the zoom to the BOTTOM edge so the hero/content boundary stays fixed while the
            // image "breathes" (center pivot moved the bottom edge → a gap that grew/shrank each cycle).
            backdrop.pivotX = backdrop.width / 2f
            backdrop.pivotY = backdrop.height.toFloat()
            kenBurns = ObjectAnimator.ofPropertyValuesHolder(
                backdrop,
                android.animation.PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.06f),
                android.animation.PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.06f)
            ).apply {
                duration = 18_000L
                repeatCount = ObjectAnimator.INFINITE
                repeatMode = ObjectAnimator.REVERSE
                interpolator = AccelerateDecelerateInterpolator()
                start()
            }
        }
    }

    fun cleanup() {
        handler.removeCallbacks(advance)
        kenBurns?.cancel()
        pulseDot.clearAnimation()
    }
}
