package com.tvonnet.debridxtreamiptv.ui.debrid.stremio

import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.flexbox.FlexboxLayout
import com.tvonnet.debridxtreamiptv.R
import com.tvonnet.debridxtreamiptv.data.debrid.repository.AddonCatalogRepository
import com.tvonnet.debridxtreamiptv.data.onSuccess
import com.tvonnet.debridxtreamiptv.ui.debrid.DebridContentItem
import com.tvonnet.debridxtreamiptv.ui.home.HomeUiState
import kotlinx.coroutines.launch

/** Full-screen search overlay — searches the Debrid catalog (TMDB) via AddonCatalogRepository. */
internal class StremioSearchOverlay(
    private val fragment: StremioHomeFragment,
    private val root: View,
    private val catalogRepo: AddonCatalogRepository,
    private val actions: StremioDebridActions
) {
    private val d = root.resources.displayMetrics.density
    private val ctx = root.context

    private val overlay: View = root.findViewById(R.id.searchOverlay)
    private val input: EditText = root.findViewById(R.id.search_input)
    private val clearBtn: View = root.findViewById(R.id.search_clear)
    private val noQuery: View = root.findViewById(R.id.search_noquery)
    private val resultsWrap: View = root.findViewById(R.id.search_results_wrap)
    private val resultsLabel: TextView = root.findViewById(R.id.search_results_label)
    private val resultsRv: RecyclerView = root.findViewById(R.id.search_results)
    private val recentCol: LinearLayout = root.findViewById(R.id.search_recent)
    private val trendingBox: FlexboxLayout = root.findViewById(R.id.search_trending)
    private val categoriesBox: FlexboxLayout = root.findViewById(R.id.search_categories)

    private val adapter = StremioSearchResultAdapter()
    private val handler = Handler(Looper.getMainLooper())
    private var searchToken = 0

    init {
        // overlay padding is 60dp each side; card 80dp + 7dp gap. Compute from screen width so it
        // is correct even while the overlay is GONE (width 0) at construction time.
        val screenW = ctx.resources.displayMetrics.widthPixels
        val span = ((screenW - 120 * d) / (87 * d)).toInt().coerceAtLeast(1)
        resultsRv.layoutManager = GridLayoutManager(ctx, span)
        resultsRv.adapter = adapter
        resultsRv.itemAnimator = null
        buildRecent()
        buildTrending()
        buildCategories()

        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { onQuery(s?.toString().orEmpty()) }
        })
        input.setOnKeyListener { _, keyCode, e ->
            if (e.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when (keyCode) {
                KeyEvent.KEYCODE_BACK -> { close(); true }
                // DOWN off the input jumps into the results grid (dismissing the keyboard) so the
                // remote lands on the found title instead of a background home poster.
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    if (resultsWrap.isVisible && adapter.itemCount > 0) { focusFirstResult(); true } else false
                }
                else -> false
            }
        }
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                if (resultsWrap.isVisible && adapter.itemCount > 0) focusFirstResult()
                true
            } else false
        }
        clearBtn.setOnClickListener { input.setText(""); input.requestFocus() }
        root.findViewById<View>(R.id.search_close).setOnClickListener { close() }
    }

    fun open() {
        overlay.isVisible = true
        overlay.alpha = 0f
        overlay.translationY = -12 * d
        overlay.animate().alpha(1f).translationY(0f).setDuration(200).start()
        // Block D-pad from reaching the (now-hidden) home hero/rows/nav behind the overlay, or the
        // remote silently focuses invisible background posters instead of the search results.
        setBackgroundFocusable(false)
        input.setText("")
        input.requestFocus()
        handler.postDelayed({
            (ctx.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
                ?.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
        }, 80)
    }

    fun close() {
        (ctx.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
            ?.hideSoftInputFromWindow(input.windowToken, 0)
        overlay.isVisible = false
        input.setText("")
        setBackgroundFocusable(true)
        // Return D-pad focus to the search bar so the remote isn't stranded on a GONE view.
        root.findViewById<View>(R.id.searchBar)?.requestFocus()
    }

    /** Focus the first result card, dismissing the soft keyboard first. */
    private fun focusFirstResult() {
        (ctx.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
            ?.hideSoftInputFromWindow(input.windowToken, 0)
        resultsRv.post { (resultsRv.getChildAt(0) ?: resultsRv).requestFocus() }
    }

    /** Toggle focusability of the background chrome (hero / rows / nav) while the overlay is open. */
    private fun setBackgroundFocusable(enabled: Boolean) {
        val mode = if (enabled) ViewGroup.FOCUS_AFTER_DESCENDANTS else ViewGroup.FOCUS_BLOCK_DESCENDANTS
        listOf(R.id.heroBanner, R.id.content_host, R.id.top_nav_bar).forEach { id ->
            (root.findViewById<View>(id) as? ViewGroup)?.descendantFocusability = mode
        }
    }

    fun isOpen(): Boolean = overlay.isVisible
    fun bind(state: HomeUiState) {}

    private fun onQuery(q: String) {
        clearBtn.isVisible = q.isNotEmpty()
        if (q.isBlank()) {
            noQuery.isVisible = true
            resultsWrap.isVisible = false
            return
        }
        noQuery.isVisible = false
        resultsWrap.isVisible = true
        val token = ++searchToken
        handler.removeCallbacksAndMessages("search")
        handler.postDelayed({ runSearch(q, token) }, 250)
    }

    private fun runSearch(q: String, token: Int) {
        fragment.viewLifecycleOwner.lifecycleScope.launch {
            // Debrid catalog search = TMDB (movies + TV), NOT the IPTV/Xtream VOD catalog.
            var items = emptyList<com.tvonnet.debridxtreamiptv.data.debrid.repository.CatalogItem>()
            catalogRepo.searchContent(q).onSuccess { items = it }
            if (token != searchToken) return@launch
            val results = items.take(8).map { c ->
                val item = DebridContentItem(
                    id = c.id, title = c.title, posterUrl = c.posterUrl, backdropUrl = c.backdropUrl,
                    type = c.type, year = c.year, rating = c.rating, overview = c.overview, genreIds = c.genreIds,
                    releaseDate = c.releaseDate
                )
                StremioSearchResult(
                    title = c.title,
                    meta = if (ReleaseInfo.isUpcoming(c.releaseDate)) ReleaseInfo.metaLabel(c.year, c.releaseDate)
                           else meta(c.year, c.rating),
                    type = if (c.type == "series") "SERIES" else "MOVIE",
                    typeBg = if (c.type == "series") 0xFF7C3AED.toInt() else 0xFF0077FF.toInt(),
                    posterUrl = c.posterUrl ?: c.backdropUrl,
                    onClick = { close(); actions.click(item) }
                )
            }
            resultsLabel.text = "RESULTS FOR \"${q.uppercase()}\" — ${results.size} FOUND"
            adapter.submit(results)
        }
    }

    private fun meta(release: String?, rating: String?): String {
        val year = release?.takeIf { it.length >= 4 }?.substring(0, 4)
        val star = rating?.takeIf { it.isNotBlank() }?.let { "⭐$it" }
        return listOfNotNull(year, star).joinToString(" · ")
    }

    // ── static no-query content ──
    private fun buildRecent() {
        recentCol.removeAllViews()
        listOf("Dune Part Two", "Breaking Bad", "Christopher Nolan", "Horror 4K", "Anime 2025").forEach { q ->
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                background = ctx.getDrawable(R.drawable.stremio_recent_item)
                setPadding((8 * d).toInt(), 0, (8 * d).toInt(), 0)
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (24 * d).toInt())
                    .apply { bottomMargin = (2 * d).toInt() }
                isFocusable = true
                addView(ImageView(ctx).apply {
                    setImageResource(R.drawable.ic_stremio_search); setColorFilter(0xFF475569.toInt())
                    layoutParams = LinearLayout.LayoutParams((7 * d).toInt(), (7 * d).toInt())
                        .apply { marginEnd = (7 * d).toInt() }
                })
                addView(TextView(ctx).apply {
                    text = q; textSize = 7.5f; setTextColor(0xFF94A3B8.toInt()); includeFontPadding = false
                    StremioFonts.apply(this, R.font.inter_regular)
                })
                setOnClickListener { input.setText(q); input.setSelection(q.length) }
            }
            recentCol.addView(row)
        }
    }

    private fun buildTrending() {
        trendingBox.removeAllViews()
        val data = listOf(
            "Severance S2" to "01", "Alien Earth" to "02", "Dune Part Two" to "03", "Nosferatu 2024" to "04",
            "The Bear" to "05", "Oppenheimer 4K" to "06", "Fallout Series" to "07", "Poor Things" to "08",
            "Slow Horses" to "09", "The Substance" to "10"
        )
        data.forEach { (q, rank) ->
            val rankColor = when (rank) {
                "01", "02" -> 0xFF00F0FF.toInt(); "03", "04" -> 0xFF0077FF.toInt(); else -> 0xFF475569.toInt()
            }
            val chip = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                background = ctx.getDrawable(R.drawable.stremio_trending_chip)
                setPadding((8 * d).toInt(), 0, (8 * d).toInt(), 0)
                layoutParams = FlexboxLayout.LayoutParams(FlexboxLayout.LayoutParams.WRAP_CONTENT, (19 * d).toInt())
                    .apply { setMargins(0, 0, (5 * d).toInt(), (5 * d).toInt()) }
                isFocusable = true
                addView(TextView(ctx).apply {
                    text = rank; textSize = 5f; setTextColor(rankColor); includeFontPadding = false
                    typeface = android.graphics.Typeface.create(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD)
                    setPadding(0, 0, (4 * d).toInt(), 0)
                })
                addView(TextView(ctx).apply {
                    text = q; textSize = 7f; setTextColor(0xFF94A3B8.toInt()); includeFontPadding = false
                })
                setOnClickListener { input.setText(q); input.setSelection(q.length) }
            }
            trendingBox.addView(chip)
        }
    }

    private fun buildCategories() {
        categoriesBox.removeAllViews()
        val cats = listOf(
            Triple("🎬", "Action", intArrayOf(0x1AFF6400, 0x40FF6400)),
            Triple("👻", "Horror", intArrayOf(0x1A7C00C8, 0x407C00C8)),
            Triple("🚀", "Sci-Fi", intArrayOf(0x1A0077FF, 0x400077FF)),
            Triple("😂", "Comedy", intArrayOf(0x1AFFC800, 0x40FFC800)),
            Triple("💔", "Drama", intArrayOf(0x1AFF3264, 0x40FF3264)),
            Triple("📺", "Series", intArrayOf(0x1400F0FF, 0x3300F0FF))
        )
        cats.forEach { (emoji, name, colors) ->
            val chip = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                background = GradientDrawable().apply {
                    cornerRadius = 5 * d; setColor(colors[0]); setStroke((1 * d).toInt(), colors[1])
                }
                setPadding((9 * d).toInt(), 0, (9 * d).toInt(), 0)
                layoutParams = FlexboxLayout.LayoutParams(FlexboxLayout.LayoutParams.WRAP_CONTENT, (22 * d).toInt())
                    .apply { setMargins(0, 0, (5 * d).toInt(), (5 * d).toInt()) }
                isFocusable = true
                addView(TextView(ctx).apply { text = emoji; textSize = 8f; setPadding(0, 0, (4 * d).toInt(), 0) })
                addView(TextView(ctx).apply {
                    text = name; textSize = 7f; setTextColor(0xFFF1F5F9.toInt()); includeFontPadding = false
                    StremioFonts.apply(this, R.font.outfit_semibold)
                })
                setOnClickListener { input.setText(name); input.setSelection(name.length) }
            }
            categoriesBox.addView(chip)
        }
    }
}
