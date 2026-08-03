package com.tvonnet.debridxtreamiptv.ui.debrid.stremio

import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.tvonnet.debridxtreamiptv.R
import com.tvonnet.debridxtreamiptv.data.debrid.model.TmdbGenre
import com.tvonnet.debridxtreamiptv.data.debrid.repository.CatalogItem
import com.tvonnet.debridxtreamiptv.ui.debrid.DebridContentItem
import com.tvonnet.debridxtreamiptv.ui.debrid.discover.DebridDiscoverViewModel
import com.tvonnet.debridxtreamiptv.ui.debrid.discover.DiscoverUiState

/** Discover tab: Top 10 (real trending) + mood/filter chrome wired to [DebridDiscoverViewModel]. */
internal class StremioDiscoverSection(
    private val fragment: StremioHomeFragment,
    private val root: View,
    private val vm: DebridDiscoverViewModel,
    private val actions: StremioDebridActions
) {
    private val d = root.resources.displayMetrics.density
    private val ctx = root.context

    private val pillsRow: LinearLayout = root.findViewById(R.id.filterPillsRow)
    private val expandPanel: LinearLayout = root.findViewById(R.id.filterExpandPanel)
    private val tagsRow: LinearLayout = root.findViewById(R.id.activeTagsRow)
    private val gridLabel: TextView = root.findViewById(R.id.discoverGridLabel)
    private val top10Rv: RecyclerView = root.findViewById(R.id.discoverTop10)
    private val gridRv: RecyclerView = root.findViewById(R.id.discoverGrid)

    private val top10Adapter = StremioDiscoverTop10Adapter { it.onClick() }
    private val gridAdapter = StremioRowPosterAdapter { actions.click(it) }

    private var genres: List<TmdbGenre> = emptyList()
    private var expanded = ""
    private var srcLabel = "Popular"; private var regionLabel = ""; private var genreLabel = ""; private var yearLabel = ""

    private val pills = listOf("source", "region", "genre", "year")

    init {
        top10Rv.layoutManager = LinearLayoutManager(ctx)
        top10Rv.adapter = top10Adapter
        top10Rv.itemAnimator = null
        // real multi-row grid; span from screen width (grid sits right of the 140dp Top-10 column)
        val screenW = ctx.resources.displayMetrics.widthPixels
        val span = ((screenW - 245 * d) / (129 * d)).toInt().coerceIn(3, 6)
        gridRv.layoutManager = androidx.recyclerview.widget.GridLayoutManager(ctx, span)
        gridRv.adapter = gridAdapter
        gridRv.itemAnimator = null
        buildPills()
    }

    fun onShown() { gridRv.post { wireFocus() } }

    fun firstFocusable(): View? =
        if (pillsRow.childCount > 0) pillsRow.getChildAt(0) else top10Rv

    /**
     * Deterministic D-pad routing for the two-column Discover layout so focus follows standard rules
     * (pills ⇄ grid, grid ⇄ Top-10) instead of erratic geometry that skipped the pills entirely.
     */
    private fun wireFocus() {
        for (i in 0 until pillsRow.childCount) {
            val p = pillsRow.getChildAt(i)
            if (p.id == View.NO_ID) p.id = View.generateViewId()
            p.nextFocusDownId = gridRv.id   // DOWN off a pill → the poster grid
        }
        val firstPill = pillsRow.getChildAt(0)
        firstPill?.nextFocusLeftId = top10Rv.id       // leftmost pill LEFT → Top 10 list
        gridRv.nextFocusUpId = firstPill?.id ?: View.NO_ID  // grid top row UP → pills
        gridRv.nextFocusLeftId = top10Rv.id           // grid leftmost col LEFT → Top 10 list
        top10Rv.nextFocusRightId = gridRv.id          // Top 10 RIGHT → grid
        top10Rv.nextFocusUpId = R.id.navTabDiscover   // Top 10 top UP → nav
    }

    fun setGenres(list: List<TmdbGenre>) { genres = list }

    fun setTop10(items: List<DebridContentItem>) {
        val colors = intArrayOf(
            0xFFFFAA00.toInt(), 0xFF94A3B8.toInt(), 0xFFCD7F32.toInt(), 0xFF475569.toInt(), 0xFF475569.toInt(),
            0xFF475569.toInt(), 0xFF475569.toInt(), 0xFF475569.toInt(), 0xFF475569.toInt(), 0xFF475569.toInt()
        )
        val rows = items.take(10).mapIndexed { i, item ->
            StremioTop10Row(
                rank = String.format(java.util.Locale.US, "%02d", i + 1), rankColor = colors[i.coerceAtMost(9)],
                title = item.title,
                meta = if (ReleaseInfo.isUpcoming(item.releaseDate)) ReleaseInfo.metaLabel(item.year, item.releaseDate)
                       else listOfNotNull(item.year, item.rating?.let { "⭐$it" }).joinToString(" · "),
                trend = "→", trendColor = 0xFF475569.toInt(),
                posterUrl = item.posterUrl ?: item.backdropUrl,
                upcoming = ReleaseInfo.isUpcoming(item.releaseDate),
                onClick = { actions.click(item) }
            )
        }
        top10Adapter.submit(rows)
    }

    fun onDiscoverState(state: DiscoverUiState) {
        when (state) {
            is DiscoverUiState.Content -> gridAdapter.submit(state.items.map { it.toItem() })
            else -> { /* keep last */ }
        }
    }

    private fun CatalogItem.toItem() = DebridContentItem(
        id = id, title = title, posterUrl = posterUrl, backdropUrl = backdropUrl,
        type = type, year = year, rating = rating, overview = overview, genreIds = genreIds,
        releaseDate = releaseDate
    )

    // ── Filter pills ──
    private fun buildPills() {
        pillsRow.removeAllViews()
        pills.forEach { key ->
            val pill = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                minimumWidth = (70 * d).toInt()
                setPadding((11 * d).toInt(), 0, (11 * d).toInt(), 0)
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, (25 * d).toInt())
                    .apply { marginEnd = (5 * d).toInt() }
                isFocusable = true
                addView(TextView(ctx).apply {
                    textSize = 8f; includeFontPadding = false
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                })
                addView(ImageView(ctx).apply {
                    setImageResource(R.drawable.ic_stremio_chevron_down)
                    layoutParams = LinearLayout.LayoutParams((7 * d).toInt(), (7 * d).toInt())
                })
                setOnClickListener {
                    expanded = if (expanded == key) "" else key
                    renderPills()
                    // Opening a filter → drop focus straight into its options (standard behaviour).
                    if (expanded == key) expandPanel.post {
                        ((expandPanel.getChildAt(0) as? ViewGroup)?.getChildAt(0))?.requestFocus()
                    }
                }
                setOnFocusChangeListener { v, has -> stylePill(v as LinearLayout, key); applyGlow(v, has) }
            }
            pillsRow.addView(pill)
        }
        renderPills()
    }

    /** Cyan background glow for focus (nicer than a ring): bright fill + soft edge + tinted shadow. */
    private fun glowBg(radiusDp: Float) = GradientDrawable().apply {
        cornerRadius = radiusDp * d; setColor(0x4D00F0FF); setStroke((1.5f * d).toInt(), 0xAA00F0FF.toInt())
    }

    private fun applyGlow(v: View, on: Boolean) {
        v.translationZ = if (on) 8f * d else 0f
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            val c = if (on) 0xFF00F0FF.toInt() else 0xFF000000.toInt()
            v.outlineSpotShadowColor = c
            v.outlineAmbientShadowColor = c
        }
    }

    private fun isPillActive(key: String): Boolean = when (key) {
        "source" -> srcLabel.isNotEmpty() && srcLabel != "Popular"
        "region" -> regionLabel.isNotEmpty()
        "genre" -> genreLabel.isNotEmpty()
        else -> yearLabel.isNotEmpty()
    }

    private fun pillRestingBg(active: Boolean, key: String): GradientDrawable = GradientDrawable().apply {
        cornerRadius = 6 * d
        setColor(if (active) 0x1F00F0FF else if (expanded == key) 0x14FFFFFF else 0x0DFFFFFF)
        setStroke((1 * d).toInt(), if (active) 0x7300F0FF else 0x1AFFFFFF)
    }

    private fun stylePill(pill: LinearLayout, key: String) {
        val focused = pill.hasFocus()
        val active = isPillActive(key)
        val label = pill.getChildAt(0) as TextView
        label.text = pillLabel(key)
        val fg = when { focused -> 0xFFFFFFFF.toInt(); active -> 0xFF00F0FF.toInt(); else -> 0xFF94A3B8.toInt() }
        label.setTextColor(fg)
        StremioFonts.apply(label, if (active || focused) R.font.outfit_bold else R.font.outfit_medium)
        (pill.getChildAt(1) as ImageView).setColorFilter(fg)
        pill.background = if (focused) glowBg(6f) else pillRestingBg(active, key)
    }

    private fun pillLabel(key: String) = when (key) {
        "source" -> srcLabel.ifEmpty { "Source" }
        "region" -> regionLabel.ifEmpty { "Region" }
        "genre" -> genreLabel.ifEmpty { "Genre" }
        else -> yearLabel.ifEmpty { "Year" }
    }

    private fun renderPills() {
        for (i in pills.indices) stylePill(pillsRow.getChildAt(i) as LinearLayout, pills[i])
        renderExpandPanel()
        gridLabel.text = listOf(srcLabel, regionLabel, genreLabel).filter { it.isNotEmpty() }
            .joinToString(" · ").ifEmpty { "Trending Everywhere" }.let { if (it == "Popular") "Trending Everywhere" else it }
    }

    private fun renderExpandPanel() {
        expandPanel.removeAllViews()
        // Collapsed: every pill's DOWN goes to the grid.
        for (i in 0 until pillsRow.childCount) pillsRow.getChildAt(i).nextFocusDownId = gridRv.id
        if (expanded.isEmpty()) { expandPanel.isVisible = false; return }
        expandPanel.isVisible = true
        val activePill = pillsRow.getChildAt(pills.indexOf(expanded))
        val flow = com.google.android.flexbox.FlexboxLayout(ctx).apply {
            flexWrap = com.google.android.flexbox.FlexWrap.WRAP
        }
        expandOptions().forEach { (label, onSel) -> flow.addView(buildOptionChip(label, onSel, activePill)) }
        expandPanel.addView(flow)
        // While open, the active pill's DOWN drops into the first option.
        flow.getChildAt(0)?.let {
            if (it.id == View.NO_ID) it.id = View.generateViewId()
            activePill?.nextFocusDownId = it.id
        }
    }

    // The option list for the expanded pill; each entry applies its filter on selection, verbatim.
    private fun expandOptions(): List<Pair<String, () -> Unit>> = when (expanded) {
        "source" -> listOf<Pair<String, () -> Unit>>(
            "Movies" to { srcLabel = "Movies"; vm.setType("movie") },
            "Series" to { srcLabel = "Series"; vm.setType("series") }
        ) + vm.catalogueOptions.map { c -> c.label to { srcLabel = c.label; vm.setCatalogue(c.value) } }
        "region" -> vm.regions.map { r -> r.label to { regionLabel = if (r.code == null) "" else r.label; vm.setCustomFilters(r.code, null, null); vm.reload() } }
        "genre" -> listOf<Pair<String, () -> Unit>>("All" to { genreLabel = ""; vm.setGenre(null) }) +
            genres.map { g -> (g.name ?: "") to { genreLabel = g.name ?: ""; vm.setGenre(g.id) } }
        else -> listOf<Pair<String, () -> Unit>>("All" to { yearLabel = ""; vm.setYear(null) }) +
            vm.yearOptions.filterNotNull().map { y -> y.toString() to { yearLabel = y.toString(); vm.setYear(y) } }
    }

    private fun buildOptionChip(label: String, onSel: () -> Unit, activePill: View?): TextView {
        val restingBg = GradientDrawable().apply { cornerRadius = 4 * d; setColor(0x0FFFFFFF); setStroke((1 * d).toInt(), 0x1AFFFFFF) }
        return TextView(ctx).apply {
            text = label; textSize = 6f; gravity = Gravity.CENTER; includeFontPadding = false
            setTextColor(0xFF94A3B8.toInt())
            setPadding((7 * d).toInt(), 0, (7 * d).toInt(), 0)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, (16 * d).toInt())
                .apply { marginEnd = (4 * d).toInt(); bottomMargin = (4 * d).toInt() }
            isFocusable = true
            background = restingBg
            nextFocusUpId = activePill?.id ?: View.NO_ID   // UP from an option → its pill
            setOnClickListener { onSel(); expanded = ""; renderPills(); activePill?.requestFocus() }
            setOnFocusChangeListener { v, has ->
                (v as TextView).setTextColor(if (has) 0xFFFFFFFF.toInt() else 0xFF94A3B8.toInt())
                v.background = if (has) glowBg(4f) else restingBg
                applyGlow(v, has)
            }
        }
    }
}
