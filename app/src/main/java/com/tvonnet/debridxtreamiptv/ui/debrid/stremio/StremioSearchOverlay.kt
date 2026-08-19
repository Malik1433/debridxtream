package com.tvonnet.debridxtreamiptv.ui.debrid.stremio

import android.animation.ValueAnimator
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.flexbox.FlexboxLayout
import com.tvonnet.debridxtreamiptv.R
import com.tvonnet.debridxtreamiptv.data.debrid.repository.AddonCatalogRepository
import com.tvonnet.debridxtreamiptv.data.debrid.repository.CatalogItem
import com.tvonnet.debridxtreamiptv.data.onSuccess
import com.tvonnet.debridxtreamiptv.ui.debrid.DebridContentItem
import com.tvonnet.debridxtreamiptv.ui.home.HomeUiState
import kotlinx.coroutines.launch
import com.tvonnet.debridxtreamiptv.ui.debrid.DebridItemType

/**
 * Full-screen Search page — rebuilt to "Search Page.dc.html".
 *
 * 10-foot layout: an on-screen key grid (left) drives the query; results render as a poster grid
 * (right). Scope chips filter All / Movies / Series (Debrid TMDB catalog via [catalogRepo]) or
 * Live (real Xtream channels via the fragment's repository, played through the nav router).
 */
internal class StremioSearchOverlay(
    private val fragment: StremioHomeFragment,
    private val root: View,
    private val catalogRepo: AddonCatalogRepository,
    private val actions: StremioDebridActions
) {
    private val d = root.resources.displayMetrics.density
    private val ctx = root.context

    private val overlay: View = root.findViewById(R.id.searchOverlay)
    private val queryBar: LinearLayout = root.findViewById(R.id.search_query_bar)
    private val hintTv: TextView = root.findViewById(R.id.search_hint)
    private val queryTv: TextView = root.findViewById(R.id.search_query)
    private val caret: View = root.findViewById(R.id.search_caret)
    private val countTv: TextView = root.findViewById(R.id.search_count)
    private val scopesBox: LinearLayout = root.findViewById(R.id.search_scopes)
    // The remote's keyboard. Absent on the phone, which has one of its own.
    private val keysBox: LinearLayout? = root.findViewById(R.id.search_keys)
    private val actionsBox: LinearLayout? = root.findViewById(R.id.search_actions)
    private val trendingWrap: View = root.findViewById(R.id.search_trending_wrap)
    private val trendingBox: FlexboxLayout = root.findViewById(R.id.search_trending)
    private val resultsAccent: View = root.findViewById(R.id.search_results_accent)
    private val resultsTitle: TextView = root.findViewById(R.id.search_results_title)
    private val resultsRv: RecyclerView = root.findViewById(R.id.search_results)
    private val noResults: View = root.findViewById(R.id.search_noresults)
    private val noResultsTitle: TextView = root.findViewById(R.id.search_noresults_title)

    private val isPhone = !root.resources.getBoolean(R.bool.ui_uses_dpad_focus)

    /** The phone's native field; [StremioSearchInput.isPresent] is false on the television. */
    private val input = StremioSearchInput(
        root,
        onQuery = { typed -> query = typed.take(32); onQueryChanged() },
        onBack = { close() },
    )

    private val adapter = StremioSearchResultAdapter()
    private val handler = Handler(Looper.getMainLooper())
    private var searchToken = 0

    private var query = ""
    private var scope = "all"
    private var firstKey: View? = null
    private val scopeChips = ArrayList<TextView>()
    private var popularCache: List<StremioSearchResult>? = null

    private val KEYS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789".toCharArray()
    private val SCOPES = listOf(
        "all" to "All", "movie" to "Movies", "series" to "Series", "live" to "Live"
    )
    private val TRENDING = listOf("Dune", "The Boys", "Nightfall", "Berlin", "Sicario", "Fallout")

    init {
        queryBar.background = roundRect(6f, 0xB30C111B.toInt(), 0x14FFFFFF, 1)
        // A phone reads its results; a television browses them. Same adapter either way — the
        // row and the poster card carry the same ids in their own layout folders.
        resultsRv.layoutManager =
            if (isPhone) LinearLayoutManager(ctx) else GridLayoutManager(ctx, computeSpan())
        resultsRv.adapter = adapter
        resultsRv.itemAnimator = null

        buildScopes()
        if (!isPhone) {
            buildKeys()
            buildActions()
            startCaretBlink()
        }
        buildTrending()
        updateQueryUi()
    }

    private fun computeSpan(): Int {
        val screenW = ctx.resources.displayMetrics.widthPixels
        val chromeDp = (28 + 226 + 20 + 32) // body pad + keyboard + gap + end pad
        val avail = screenW - chromeDp * d
        return (avail / (95 * d)).toInt().coerceAtLeast(2)
    }

    // ── open / close ──
    fun open() {
        overlay.isVisible = true
        overlay.alpha = 0f
        overlay.translationY = -12 * d
        overlay.animate().alpha(1f).translationY(0f).setDuration(200).start()
        setBackgroundFocusable(false)
        query = ""
        scope = "all"
        restyleScopes()
        updateQueryUi()
        runSearch(query, ++searchToken)
        if (isPhone) input.setText("") else overlay.post { (firstKey ?: overlay).requestFocus() }
        if (isPhone) input.focusAndShowKeyboard()
    }

    fun close() {
        input.hideKeyboard()
        overlay.isVisible = false
        query = ""
        setBackgroundFocusable(true)
        root.findViewById<View>(R.id.searchBar)?.requestFocus()
    }

    fun isOpen(): Boolean = overlay.isVisible
    fun bind(state: HomeUiState) {}

    /** Toggle focusability of the background chrome (hero / rows / nav) while the overlay is open. */
    private fun setBackgroundFocusable(enabled: Boolean) {
        val mode = if (enabled) ViewGroup.FOCUS_AFTER_DESCENDANTS else ViewGroup.FOCUS_BLOCK_DESCENDANTS
        listOf(R.id.heroBanner, R.id.content_host, R.id.top_nav_bar).forEach { id ->
            (root.findViewById<View>(id) as? ViewGroup)?.descendantFocusability = mode
        }
    }

    // ── query editing ──
    private fun appendChar(c: Char) { if (query.length < 32) { query += c; onQueryChanged() } }
    private fun onSpace() { if (query.length < 32 && query.isNotEmpty()) { query += " "; onQueryChanged() } }
    private fun onDel() { if (query.isNotEmpty()) { query = query.dropLast(1); onQueryChanged() } }
    private fun onClear() { if (query.isNotEmpty()) { query = ""; onQueryChanged() } }
    private fun pickTerm(term: String) {
        // On the phone the term goes INTO the field: a suggestion the customer cannot then edit
        // is a dead end, and the field is where they would look for it.
        if (isPhone) { input.setText(term); return }
        query = term.uppercase().take(32)
        onQueryChanged()
    }

    private fun onQueryChanged() {
        updateQueryUi()
        val token = ++searchToken
        handler.removeCallbacksAndMessages("search")
        handler.postDelayed({ runSearch(query, token) }, 250)
    }

    private fun updateQueryUi() {
        // The phone's EditText draws its own text, hint and caret; there is nothing to mirror.
        if (isPhone) return
        val has = query.isNotEmpty()
        hintTv.isVisible = !has
        queryTv.isVisible = has
        queryTv.text = query
        queryBar.background =
            if (has) roundRect(6f, 0xB30C111B.toInt(), 0x5900F0FF, 1)
            else roundRect(6f, 0xB30C111B.toInt(), 0x14FFFFFF, 1)
    }

    // ── search ──
    private fun runSearch(q: String, token: Int) {
        val query = q.trim()
        if (query.isBlank()) {
            trendingWrap.isVisible = true
            resultsAccent.setBackgroundResource(R.drawable.stremio_search_accent_purple)
            resultsTitle.text = resultsTitle.context.getString(R.string.ui_popular_right_now)
            countTv.text = ""
            noResults.isVisible = false
            loadPopular(token)
            return
        }
        trendingWrap.isVisible = false
        resultsAccent.setBackgroundResource(R.drawable.stremio_search_accent_cyan)
        resultsTitle.text = "Results · \"$query\""
        fragment.viewLifecycleOwner.lifecycleScope.launch {
            val results = if (scope == "live") searchLive(query) else searchCatalog(query)
            if (token != searchToken) return@launch
            countTv.text = countTv.context.getString(R.string.f_results_caps, results.size)
            adapter.submit(results)
            noResults.isVisible = results.isEmpty()
            noResultsTitle.text = "No matches for \"${query.uppercase()}\""
            resultsRv.scrollToPosition(0)
        }
    }

    private suspend fun searchCatalog(q: String): List<StremioSearchResult> {
        var items = emptyList<CatalogItem>()
        catalogRepo.searchContent(q).onSuccess { items = it }
        val filtered = items.filter { scope == "all" || it.type == scope }
        return filtered.take(24).map { catalogResult(it) }
    }

    private suspend fun searchLive(q: String): List<StremioSearchResult> {
        val streams = runCatching { fragment.repository.searchLive(q) }.getOrDefault(emptyList())
        return streams.take(24).map { s ->
            StremioSearchResult(
                title = s.name.orEmpty(),
                sub = s.currentProgramTitle?.takeIf { it.isNotBlank() } ?: "Live channel",
                typeLabel = "LIVE", typeColor = LIVE_C, typeBg = LIVE_BG, typeBorder = LIVE_BD,
                isLive = true, posterUrl = s.stream_icon,
                onClick = {
                    close()
                    fragment.router.launchLiveStream(
                        streamId = s.stream_id, fallbackTitle = s.name,
                        fallbackLogo = s.stream_icon, fallbackUrl = null
                    )
                }
            )
        }
    }

    private fun loadPopular(token: Int) {
        popularCache?.let { adapter.submit(it); noResults.isVisible = false; return }
        fragment.viewLifecycleOwner.lifecycleScope.launch {
            val out = ArrayList<CatalogItem>()
            catalogRepo.getTrendingMovies().onSuccess { out += it }
            catalogRepo.getTrendingSeries().onSuccess { out += it }
            val mapped = out.take(24).map { catalogResult(it) }
            popularCache = mapped
            if (token != searchToken) return@launch
            adapter.submit(mapped)
            noResults.isVisible = mapped.isEmpty()
        }
    }

    private fun catalogResult(c: CatalogItem): StremioSearchResult {
        val item = DebridContentItem(
            id = c.id, title = c.title, posterUrl = c.posterUrl, backdropUrl = c.backdropUrl,
            type = c.type, year = c.year, rating = c.rating, overview = c.overview,
            genreIds = c.genreIds, releaseDate = c.releaseDate
        )
        val series = DebridItemType.isSeries(c.type)
        return StremioSearchResult(
            title = c.title,
            sub = if (ReleaseInfo.isUpcoming(c.releaseDate)) ReleaseInfo.metaLabel(c.year, c.releaseDate)
                  else meta(c.year, c.rating),
            typeLabel = if (series) "SERIES" else "MOVIE",
            typeColor = if (series) SERIES_C else MOVIE_C,
            typeBg = if (series) SERIES_BG else MOVIE_BG,
            typeBorder = if (series) SERIES_BD else MOVIE_BD,
            isLive = false, posterUrl = c.posterUrl ?: c.backdropUrl,
            onClick = { close(); actions.click(item) }
        )
    }

    private fun meta(release: String?, rating: String?): String {
        val year = release?.takeIf { it.length >= 4 }?.substring(0, 4)
        // TMDB hands these over as 7.725. One decimal is what a rating means to a reader; three
        // is a database field leaking onto the screen (the hero had the same defect).
        val star = rating?.takeIf { it.isNotBlank() }?.let { raw ->
            val n = raw.toDoubleOrNull()
            "⭐" + if (n != null) String.format(java.util.Locale.US, "%.1f", n) else raw
        }
        return listOfNotNull(year, star).joinToString(" · ")
    }

    // ── keyboard / chips builders ──
    /**
     * The chips are the same four filters on both devices; only the viewing distance differs.
     * The television spreads them across a fixed strip at 6.5sp — correct at three metres and
     * illegible in the hand — while the phone gives each one its own 12sp pill with a real
     * touch target and lets the row scroll.
     */
    private data class ScopeMetrics(
        val textSize: Float,
        val width: Int,
        val weight: Float,
        val gap: Int,
        val padH: Int,
    )

    private fun scopeMetrics(): ScopeMetrics = if (isPhone) {
        ScopeMetrics(12f, LinearLayout.LayoutParams.WRAP_CONTENT, 0f, (6 * d).toInt(), (14 * d).toInt())
    } else {
        ScopeMetrics(6.5f, 0, 1f, (5 * d).toInt(), 0)
    }

    private fun buildScopes() {
        scopesBox.removeAllViews()
        scopeChips.clear()
        val m = scopeMetrics()
        SCOPES.forEachIndexed { i, (key, label) ->
            val chip = TextView(ctx).apply {
                text = label
                textSize = m.textSize
                gravity = Gravity.CENTER
                includeFontPadding = false
                if (m.padH > 0) setPadding(m.padH, 0, m.padH, 0)
                tag = key
                isFocusable = true
                StremioFonts.apply(this, R.font.outfit_semibold)
                setOnClickListener { selectScope(key) }
                setOnFocusChangeListener { v, has -> styleScope(v as TextView, has) }
            }
            val lp = LinearLayout.LayoutParams(m.width, LinearLayout.LayoutParams.MATCH_PARENT, m.weight)
            if (i < SCOPES.size - 1) lp.marginEnd = m.gap
            scopesBox.addView(chip, lp)
            scopeChips.add(chip)
            styleScope(chip, false)
        }
    }

    private fun selectScope(key: String) {
        if (scope == key) return
        scope = key
        restyleScopes()
        onQueryChanged()
    }

    private fun restyleScopes() = scopeChips.forEach { styleScope(it, it.isFocused) }

    private fun styleScope(tv: TextView, focused: Boolean) {
        val on = tv.tag == scope
        val r = if (isPhone) 16f else 4.5f
        // A phone has no focus to show, so the SELECTED chip is filled rather than merely outlined
        // — on a touchscreen the only question a chip answers is "is this the one".
        when {
            on && isPhone -> { tv.background = roundRect(r, 0xFF00F0FF.toInt(), 0xFF00F0FF.toInt(), 1); tv.setTextColor(0xFF04121A.toInt()) }
            focused -> { tv.background = roundRect(r, 0x1A00F0FF, 0xFF00F0FF.toInt(), 1); tv.setTextColor(0xFF00F0FF.toInt()) }
            on -> { tv.background = roundRect(r, 0x1A00F0FF, 0x5900F0FF, 1); tv.setTextColor(0xFF00F0FF.toInt()) }
            else -> { tv.background = roundRect(r, 0x08FFFFFF, 0x24FFFFFF, 1); tv.setTextColor(0xFF94A3B8.toInt()) }
        }
    }

    private fun buildKeys() {
        val keysBox = keysBox ?: return
        keysBox.removeAllViews()
        val rowH = (34 * d).toInt()
        KEYS.toList().chunked(6).forEachIndexed { rowIdx, rowChars ->
            val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
            rowChars.forEachIndexed { colIdx, ch ->
                val key = TextView(ctx).apply {
                    text = ch.toString()
                    textSize = 10.5f
                    gravity = Gravity.CENTER
                    includeFontPadding = false
                    isFocusable = true
                    StremioFonts.apply(this, R.font.outfit_semibold)
                    setOnClickListener { appendChar(ch) }
                    setOnFocusChangeListener { v, has -> styleKey(v as TextView, has) }
                }
                styleKey(key, false)
                if (rowIdx == 0 && colIdx == 0) firstKey = key
                val lp = LinearLayout.LayoutParams(0, rowH, 1f)
                if (colIdx < 5) lp.marginEnd = (5 * d).toInt()
                row.addView(key, lp)
            }
            val rowLp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, rowH)
            if (rowIdx < 5) rowLp.bottomMargin = (5 * d).toInt()
            keysBox.addView(row, rowLp)
        }
    }

    private fun styleKey(tv: TextView, focused: Boolean) {
        if (focused) {
            tv.background = gradientRect(5f, 0xFF0077FF.toInt(), 0xFF00F0FF.toInt(), 0xFF00F0FF.toInt())
            tv.setTextColor(0xFF041014.toInt())
            tv.scaleX = 1.08f; tv.scaleY = 1.08f; tv.z = 6f
        } else {
            tv.background = roundRect(5f, 0x08FFFFFF, 0x0FFFFFFF, 1)
            tv.setTextColor(0xFFCBD5E1.toInt())
            tv.scaleX = 1f; tv.scaleY = 1f; tv.z = 0f
        }
    }

    private fun buildActions() {
        val actionsBox = actionsBox ?: return
        actionsBox.removeAllViews()
        val items = listOf(
            Triple("SPACE", 2f, 0xFF00F0FF.toInt()),
            Triple("DEL", 1f, 0xFFFFAA00.toInt()),
            Triple("CLEAR", 1f, 0xFFFF3366.toInt())
        )
        items.forEachIndexed { i, (label, weight, accent) ->
            val btn = TextView(ctx).apply {
                text = label
                textSize = 7.5f
                gravity = Gravity.CENTER
                includeFontPadding = false
                letterSpacing = 0.06f
                isFocusable = true
                StremioFonts.apply(this, R.font.outfit_semibold)
                setOnClickListener {
                    when (label) { "SPACE" -> onSpace(); "DEL" -> onDel(); else -> onClear() }
                }
                setOnFocusChangeListener { v, has -> styleAction(v as TextView, has, accent) }
            }
            styleAction(btn, false, accent)
            val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, weight)
            if (i < items.size - 1) lp.marginEnd = (5 * d).toInt()
            actionsBox.addView(btn, lp)
        }
    }

    private fun styleAction(tv: TextView, focused: Boolean, accent: Int) {
        if (focused) {
            val tint = (accent and 0x00FFFFFF) or 0x14000000 // accent @ ~8% alpha
            tv.background = roundRect(5f, tint, accent, 1)
            tv.setTextColor(accent)
        } else {
            tv.background = roundRect(5f, 0x08FFFFFF, 0x0FFFFFFF, 1)
            tv.setTextColor(0xFF94A3B8.toInt())
        }
    }

    private fun buildTrending() {
        trendingBox.removeAllViews()
        TRENDING.forEach { term ->
            val chip = TextView(ctx).apply {
                text = term
                textSize = if (isPhone) 13f else 8f
                gravity = Gravity.CENTER_VERTICAL
                includeFontPadding = false
                val padH = (if (isPhone) 16 else 10) * d
                setPadding(padH.toInt(), 0, padH.toInt(), 0)
                isFocusable = true
                StremioFonts.apply(this, R.font.inter_regular)
                setOnClickListener { pickTerm(term) }
                setOnFocusChangeListener { v, has -> styleTrending(v as TextView, has) }
            }
            styleTrending(chip, false)
            // 24dp is a chip you point a remote at; a finger needs something it can actually hit.
            val h = if (isPhone) (44 * d).toInt() else (24 * d).toInt()
            val lp = FlexboxLayout.LayoutParams(FlexboxLayout.LayoutParams.WRAP_CONTENT, h)
                .apply { setMargins(0, 0, (8 * d).toInt(), (8 * d).toInt()) }
            trendingBox.addView(chip, lp)
        }
    }

    private fun styleTrending(tv: TextView, focused: Boolean) {
        val r = if (isPhone) 22f else 12f
        if (focused) {
            tv.background = roundRect(r, 0x0F00F0FF, 0xFF00F0FF.toInt(), 1)
            tv.setTextColor(0xFF00F0FF.toInt())
        } else {
            tv.background = roundRect(r, 0xB30C111B.toInt(), 0x24FFFFFF, 1)
            tv.setTextColor(0xFFCBD5E1.toInt())
        }
    }

    private fun startCaretBlink() {
        val animator = ValueAnimator.ofFloat(1f, 0f).apply {
            duration = 550
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            addUpdateListener { caret.alpha = it.animatedValue as Float }
        }
        // Phase 3: tie the infinite caret animator (and any pending search callbacks) to the
        // view's attach state so they're cancelled when the overlay's view is destroyed.
        // Previously the animator held `caret` forever (INFINITE, no reference to cancel),
        // leaking the view hierarchy for the app's lifetime.
        caret.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                if (!animator.isStarted) animator.start()
            }
            override fun onViewDetachedFromWindow(v: View) {
                animator.cancel()
                handler.removeCallbacksAndMessages(null)
            }
        })
        animator.start()
    }

    // ── drawable helpers ──
    private fun roundRect(radiusDp: Float, fill: Int, strokeColor: Int, strokeDp: Int): Drawable =
        GradientDrawable().apply {
            cornerRadius = radiusDp * d
            setColor(fill)
            if (strokeDp > 0) setStroke((strokeDp * d).toInt(), strokeColor)
        }

    private fun gradientRect(radiusDp: Float, start: Int, end: Int, strokeColor: Int): Drawable =
        GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(start, end)).apply {
            cornerRadius = radiusDp * d
            setStroke((1 * d).toInt(), strokeColor)
        }

    companion object {
        private const val MOVIE_C = 0xFF00F0FF.toInt(); private const val MOVIE_BG = 0x2400F0FF; private const val MOVIE_BD = 0x4D00F0FF
        private const val SERIES_C = 0xFFA78BFA.toInt(); private const val SERIES_BG = 0x337C3AED; private const val SERIES_BD = 0x597C3AED
        private const val LIVE_C = 0xFF00FF88.toInt(); private const val LIVE_BG = 0x2400FF88; private const val LIVE_BD = 0x4D00FF88
    }
}
