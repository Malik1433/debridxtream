package com.tvonnet.debridxtreamiptv.ui.search

import android.animation.ValueAnimator
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.flexbox.FlexboxLayout
import com.tvonnet.debridxtreamiptv.R
import com.tvonnet.debridxtreamiptv.data.model.ContentType
import com.tvonnet.debridxtreamiptv.data.model.XtreamSeriesInfo
import com.tvonnet.debridxtreamiptv.data.model.XtreamStream
import com.tvonnet.debridxtreamiptv.data.model.XtreamVodInfo
import com.tvonnet.debridxtreamiptv.data.model.toLiveStreamUrl
import com.tvonnet.debridxtreamiptv.data.prefs.CredentialsPreferences
import com.tvonnet.debridxtreamiptv.features.seriesv2.ui.SeriesDetailFragmentV2
import com.tvonnet.debridxtreamiptv.features.vodv2.ui.MovieDetailFragmentV2
import com.tvonnet.debridxtreamiptv.player.stabilized.PlayerActivity
import com.tvonnet.debridxtreamiptv.ui.debrid.stremio.StremioFonts
import com.tvonnet.debridxtreamiptv.ui.debrid.stremio.StremioSearchResult
import com.tvonnet.debridxtreamiptv.ui.debrid.stremio.StremioSearchResultAdapter
import com.tvonnet.debridxtreamiptv.util.GlobalConfig
import com.tvonnet.debridxtreamiptv.utils.isFocusInsideThis
import com.tvonnet.debridxtreamiptv.utils.updatePreservingFocus
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

/**
 * Main-home Search screen — rebuilt to "Search Page.dc.html" (shares the design with the Debrid
 * search overlay). On-screen key grid drives the query; results render as a poster grid filtered by
 * the All / Movies / Series / Live scope chips, wired to [SearchViewModel] (IPTV live / VOD / series).
 */
@AndroidEntryPoint
class SearchFragment :
    Fragment(),
    com.tvonnet.debridxtreamiptv.util.PortraitScreen {

    private val viewModel: SearchViewModel by viewModels()

    private lateinit var credentialsPrefs: CredentialsPreferences
    private val handler = Handler(Looper.getMainLooper())
    private var d = 2f

    private lateinit var queryBar: LinearLayout
    private lateinit var hintTv: TextView
    private lateinit var queryTv: TextView
    private lateinit var caret: View
    private lateinit var countTv: TextView
    private lateinit var scopesBox: LinearLayout
    private lateinit var keysBox: LinearLayout
    private lateinit var actionsBox: LinearLayout
    private lateinit var trendingWrap: View
    private lateinit var trendingLabel: TextView
    private lateinit var trendingBox: FlexboxLayout
    private lateinit var resultsHeader: View
    private lateinit var resultsTitle: TextView
    private lateinit var resultsRv: RecyclerView
    private lateinit var noResults: View
    private lateinit var noResultsTitle: TextView

    private val adapter = StremioSearchResultAdapter()
    private var query = ""

    /** A phone types with its own keyboard into a real field; a TV walks an on-screen key grid. */
    private val usesNativeInput: Boolean by lazy { !resources.getBoolean(R.bool.ui_uses_dpad_focus) }
    private var lastRenderedQuery: String? = null
    private var scope = "all"
    private var firstKey: View? = null
    private val scopeChips = ArrayList<TextView>()
    private var lastState: SearchUiState? = null

    private val KEYS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789".toCharArray()
    /** Same three columns as Browse, so a result looks like the card it will open. */
    private val PHONE_COLUMNS = 3
    private val SCOPES = listOf(
        "all" to "All", "movie" to "Movies", "series" to "Series", "live" to "Live"
    )
    private val TRENDING = listOf("News", "Sports", "Action", "Kids", "Movies 4K", "Documentary")

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_search, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        d = resources.displayMetrics.density
        credentialsPrefs = CredentialsPreferences(requireContext())

        queryBar = view.findViewById(R.id.search_query_bar)
        hintTv = view.findViewById(R.id.search_hint)
        queryTv = view.findViewById(R.id.search_query)
        caret = view.findViewById(R.id.search_caret)
        countTv = view.findViewById(R.id.search_count)
        scopesBox = view.findViewById(R.id.search_scopes)
        keysBox = view.findViewById(R.id.search_keys)
        actionsBox = view.findViewById(R.id.search_actions)
        trendingWrap = view.findViewById(R.id.search_trending_wrap)
        trendingLabel = view.findViewById(R.id.search_trending_label)
        trendingBox = view.findViewById(R.id.search_trending)
        resultsHeader = view.findViewById(R.id.search_results_header)
        resultsTitle = view.findViewById(R.id.search_results_title)
        resultsRv = view.findViewById(R.id.search_results)
        noResults = view.findViewById(R.id.search_noresults)
        noResultsTitle = view.findViewById(R.id.search_noresults_title)

        if (!usesNativeInput) queryBar.background = roundRect(6f, 0xB30C111B.toInt(), 0x14FFFFFF, 1)
        resultsRv.layoutManager = GridLayoutManager(requireContext(), computeSpan())
        resultsRv.adapter = adapter
        resultsRv.itemAnimator = null

        buildScopes()
        buildKeys()
        setUpNativeKeyboardOnTouch(view)
        buildActions()
        buildTrending()
        startCaretBlink()

        buildPhoneNav(view)
        applyScopeArgs()
        observeState()
        updateQueryUi()

        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner, object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    val focus = view.findFocus()
                    if (focus != null && isDescendantOf(focus, resultsRv)) {
                        (firstKey ?: keysBox).requestFocus()
                    } else {
                        isEnabled = false
                        requireActivity().onBackPressedDispatcher.onBackPressed()
                    }
                }
            }
        )

        // voice query from MainActivity
        arguments?.getString("voice_query")?.takeIf { it.isNotEmpty() }?.let { setVoiceQuery(it) }
        view.post { (firstKey ?: scopesBox).requestFocus() }
    }

    /** Restrict the default scope when opened from the Series / Movies screens. */
    private fun applyScopeArgs() {
        val args = arguments ?: return
        when {
            args.getBoolean("series_scope", false) -> {
                scope = "series"
                viewModel.setSeriesScope(args.getString("category_id"), true)
            }
            args.getBoolean("vod_scope", false) -> {
                scope = "movie"
                viewModel.setVodScope(args.getString("category_id"), true)
            }
        }
        args.getString("category_name")?.takeIf { it.isNotBlank() }?.let {
            hintTv.text = getString(R.string.search_in_scope, it)
        }
        restyleScopes()
    }

    /** Called by MainActivity for Fire TV voice search. */
    fun setVoiceQuery(voice: String) {
        setQuery(if (usesNativeInput) voice else voice.uppercase())
    }

    /**
     * How many result columns fit.
     *
     * The television subtracts its left rail and its key grid from the width; a phone has neither
     * and hands the whole 411dp to the results, which is three columns — the same three Browse
     * uses, so a result and a catalogue card are recognisably the same object.
     */
    private fun computeSpan(): Int {
        if (usesNativeInput) return PHONE_COLUMNS
        val screenW = resources.displayMetrics.widthPixels
        val chromeDp = (28 + 226 + 20 + 32)
        val avail = screenW - chromeDp * d
        return (avail / (95 * d)).toInt().coerceAtLeast(2)
    }

    // ── query editing ──
    private fun appendChar(c: Char) { if (query.length < 32) { query += c; onQueryChanged() } }
    private fun onSpace() { if (query.length < 32 && query.isNotEmpty()) { query += " "; onQueryChanged() } }
    private fun onDel() { if (query.isNotEmpty()) { query = query.dropLast(1); onQueryChanged() } }
    private fun onClear() { if (query.isNotEmpty()) { query = ""; onQueryChanged() } }
    /**
     * Anything that sets the query from OUTSIDE the typing path — a trending chip, a voice result —
     * has to go through the input field when there is one, or the field and the query drift apart
     * and the user sees results for a word the box does not show.
     *
     * Not upper-cased on a phone: the grid only ever produced capitals, so the TV's query was
     * capitals and the design assumed it. A phone types what the user typed.
     */
    private fun setQuery(value: String) {
        val v = value.take(32)
        val input = queryTv as? android.widget.EditText
        if (usesNativeInput && input != null) {
            input.setText(v)
            input.setSelection(v.length)
        } else {
            query = v
            onQueryChanged()
        }
    }

    private fun pickTerm(term: String) =
        setQuery(if (usesNativeInput) term else term.uppercase())

    private fun onQueryChanged() {
        updateQueryUi()
        viewModel.onEvent(SearchEvent.QueryChanged(query.trim()))
        if (query.isBlank()) render(lastState)
    }

    private fun updateQueryUi() {
        val has = query.isNotEmpty()
        // With a real input field the field OWNS its text and its visibility. Writing back here on
        // every keystroke reset the EditText and put the caret at position 0, so each new character
        // landed at the START and the word came out backwards; hiding it while empty also left
        // nothing to tap to begin with. On TV the query is a display and these lines are correct.
        if (!usesNativeInput) {
            hintTv.isVisible = !has
            queryTv.isVisible = has
            queryTv.text = query
        }
        queryBar.background =
            if (has) roundRect(6f, 0xB30C111B.toInt(), 0x5900F0FF, 1)
            else roundRect(6f, 0xB30C111B.toInt(), 0x14FFFFFF, 1)
        trendingWrap.isVisible = !has
        resultsHeader.isVisible = has
        if (!has) { countTv.text = ""; noResults.isVisible = false; adapter.submit(emptyList()) }
        else resultsTitle.text = "Results · \"${query.trim()}\""
    }

    // ── state rendering ──
    private fun observeState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.uiState.collect { state ->
                lastState = state
                // reflect recent searches into the trending chips while idle
                if (query.isBlank()) buildTrending(state.recentSearches)
                render(state)
            }
        }
    }

    private fun render(state: SearchUiState?) {
        if (query.isBlank()) {
            trendingWrap.isVisible = true
            resultsHeader.isVisible = false
            resultsRv.updatePreservingFocus { adapter.submit(emptyList()) }
            lastRenderedQuery = null
            noResults.isVisible = false
            countTv.text = ""
            return
        }
        state ?: return
        val items = scopedResults(state)
        countTv.text = "${items.size} RESULTS"
        // The 3 result sources (VOD/series/live) stream in as separate emits for the SAME
        // query. Only reset scroll-to-top on a genuinely NEW query, and never while the user
        // is already browsing the results — updatePreservingFocus keeps their spot otherwise.
        val queryChanged = query != lastRenderedQuery
        lastRenderedQuery = query
        resultsRv.updatePreservingFocus { adapter.submit(items) }
        noResults.isVisible = items.isEmpty() && !state.isSearching
        noResultsTitle.text = "No matches for \"${query.trim().uppercase()}\""
        if (queryChanged && items.isNotEmpty() && !resultsRv.isFocusInsideThis()) {
            resultsRv.scrollToPosition(0)
        }
    }

    private fun scopedResults(state: SearchUiState): List<StremioSearchResult> = when (scope) {
        "movie" -> state.vodResults.map { vodCard(it) }
        "series" -> state.seriesResults.map { seriesCard(it) }
        "live" -> state.liveResults.map { liveCard(it) }
        else -> state.vodResults.map { vodCard(it) } +
            state.seriesResults.map { seriesCard(it) } +
            state.liveResults.map { liveCard(it) }
    }.take(60)

    private fun vodCard(vod: XtreamVodInfo) = StremioSearchResult(
        title = vod.name.orEmpty(),
        sub = year(vod.releaseDate) ?: vod.rating?.let { "⭐$it" } ?: "MOVIE",
        typeLabel = "MOVIE", typeColor = MOVIE_C, typeBg = MOVIE_BG, typeBorder = MOVIE_BD,
        isLive = false, posterUrl = GlobalConfig.resolveIconUrl(vod.stream_icon) ?: vod.cover,
        onClick = { openVodDetail(vod) }
    )

    private fun seriesCard(s: XtreamSeriesInfo) = StremioSearchResult(
        title = s.name.orEmpty(),
        sub = year(s.releaseDate) ?: s.genre?.takeIf { it.isNotBlank() } ?: "SERIES",
        typeLabel = "SERIES", typeColor = SERIES_C, typeBg = SERIES_BG, typeBorder = SERIES_BD,
        isLive = false, posterUrl = s.cover,
        onClick = { openSeriesDetail(s) }
    )

    private fun liveCard(st: XtreamStream) = StremioSearchResult(
        title = st.name.orEmpty(),
        sub = st.currentProgramTitle?.takeIf { it.isNotBlank() } ?: "Live channel",
        typeLabel = "LIVE", typeColor = LIVE_C, typeBg = LIVE_BG, typeBorder = LIVE_BD,
        isLive = true, posterUrl = GlobalConfig.resolveIconUrl(st.stream_icon),
        onClick = { playLiveStream(st) }
    )

    private fun year(release: String?): String? =
        release?.takeIf { it.length >= 4 }?.substring(0, 4)?.takeIf { it.all(Char::isDigit) }

    // ── result navigation (unchanged behavior) ──
    private fun openVodDetail(vod: XtreamVodInfo) {
        val fragment = com.tvonnet.debridxtreamiptv.ui.detail.phone.DetailScreens.movie(requireContext(), Bundle().apply {
            putString("stream_id", vod.stream_id?.toString())
            putString("title", vod.name)
            putString("backdrop_url", vod.cover ?: vod.stream_icon)
            putString("poster_url", vod.stream_icon)
            putString("plot", vod.plot)
            putString("year", vod.releaseDate)
            putString("genre", vod.genre)
            putString("rating", vod.rating)
            putString("container_ext", vod.container_extension ?: "mp4")
            putString("direct_source", vod.direct_source)
            putString("trailer", vod.youtube_trailer)
        })
        parentFragmentManager.beginTransaction()
            .replace(R.id.content_container, fragment).addToBackStack(null).commit()
    }

    private fun openSeriesDetail(series: XtreamSeriesInfo) {
        val fragment = com.tvonnet.debridxtreamiptv.ui.detail.phone.DetailScreens.series(
            requireContext(),
            Bundle().apply {
                putString("series_id", series.series_id)
                putString("title", series.name)
                putString("backdrop_url", series.cover)
                putString("poster_url", series.cover)
                putString("trailer", series.youtube_trailer)
            }
        )
        parentFragmentManager.beginTransaction()
            .replace(R.id.content_container, fragment).addToBackStack(null).commit()
    }

    private fun playLiveStream(stream: XtreamStream) {
        val serverUrl = credentialsPrefs.getServerUrl() ?: return
        val username = credentialsPrefs.getUsername() ?: return
        val password = credentialsPrefs.getPassword() ?: return
        val streamUrl = stream.toLiveStreamUrl(serverUrl, username, password)
        val intent = PlayerActivity.createIntent(
            context = requireContext(), streamUrl = streamUrl,
            title = stream.name ?: getString(R.string.player_epg_channel_unknown),
            channelName = stream.name,
            channelLogo = GlobalConfig.resolveIconUrl(stream.stream_icon),
            epgChannelId = stream.epg_channel_id?.takeIf { it.isNotBlank() } ?: stream.stream_id?.toString(),
            contentId = stream.stream_id ?: streamUrl,
            contentType = ContentType.LIVE_TV,
            posterUrl = GlobalConfig.resolveIconUrl(stream.stream_icon)
        )
        startActivity(intent)
    }

    /**
     * Search is one of the five tabs, so on a phone it carries the bar its siblings carry — and
     * Back leaves the screen rather than unwinding a query one keystroke at a time.
     */
    private fun buildPhoneNav(root: View) {
        if (!usesNativeInput) return
        com.tvonnet.debridxtreamiptv.ui.live.phone.PhoneBottomNav.build(
            root.findViewById(com.tvonnet.debridxtreamiptv.R.id.search_bottom_nav),
            com.tvonnet.debridxtreamiptv.ui.live.phone.PhoneUi.unscaled(this, layoutInflater),
            com.tvonnet.debridxtreamiptv.ui.nav.SectionNavigator.SECTION_SEARCH,
        ) { section ->
            parentFragmentManager.popBackStack(
                null, androidx.fragment.app.FragmentManager.POP_BACK_STACK_INCLUSIVE
            )
            com.tvonnet.debridxtreamiptv.ui.nav.SectionNavigator.navigate(this, section)
        }
    }

    /**
     * On a PHONE, type with the phone's own keyboard.
     *
     * This screen was built for a remote: a grid of on-screen keys you walk to with a D-pad. On a
     * touchscreen that is the wrong instrument entirely — it wastes half the width, offers no
     * autocorrect, no dictionary, no paste, and no language the user has installed. The phone
     * rulebook says the native soft keyboard, never an on-screen D-pad one.
     *
     * The grid stays for the TV, where it IS the right instrument and there is no soft keyboard
     * worth using. So this hides the grid on touch, turns the query display into a real input, and
     * routes what is typed through the SAME `query` field the keys write to — one source of truth,
     * so filtering, the caret and the results behave identically either way.
     */
    private fun setUpNativeKeyboardOnTouch(root: View) {
        if (!usesNativeInput) return

        // The on-screen grid and the blinking caret are both D-pad affordances. A real input field
        // brings its own caret, and drawing a second one beside it is exactly the invented
        // behaviour a phone should not have.
        keysBox.visibility = View.GONE
        caret.visibility = View.GONE

        val input = queryTv as? android.widget.EditText ?: return
        // The field is always present and always the thing you type into — no show/hide dance.
        input.visibility = View.VISIBLE
        input.isFocusableInTouchMode = true
        hintTv.visibility = View.GONE
        input.hint = hintTv.text          // one hint, the field's own, as any search box has
        input.layoutParams = input.layoutParams.apply { width = ViewGroup.LayoutParams.MATCH_PARENT }
        input.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun afterTextChanged(s: android.text.Editable?) {
                val typed = s?.toString().orEmpty()
                if (typed == query) return
                query = typed
                onQueryChanged()
            }
        })

        // Tapping anywhere on the bar starts typing, which is what a search box does.
        queryBar.setOnClickListener { focusInput(input) }
        root.post { if (query.isEmpty()) focusInput(input) }
    }

    private fun focusInput(input: android.widget.EditText) {
        input.requestFocus()
        input.setSelection(input.text?.length ?: 0)
        (requireContext().getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
            as? android.view.inputmethod.InputMethodManager)
            ?.showSoftInput(input, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
    }

    // ── keyboard / chips builders ──
    private fun buildScopes() {
        scopesBox.removeAllViews()
        scopeChips.clear()
        SCOPES.forEachIndexed { i, (key, label) ->
            val chip = TextView(requireContext()).apply {
                // 6.5sp is a 10-foot size read from three metres; in the hand it wraps "Movies"
                // onto two lines and is barely legible. The phone rulebook floors body text at
                // 12sp and interactive targets at 48dp.
                text = label
                textSize = if (usesNativeInput) 12.5f else 6.5f
                gravity = Gravity.CENTER
                includeFontPadding = false
                if (usesNativeInput) {
                    setPadding((14 * d).toInt(), 0, (14 * d).toInt(), 0)
                }
                tag = key; isFocusable = true
                StremioFonts.apply(this, R.font.outfit_semibold)
                setOnClickListener { selectScope(key) }
                setOnFocusChangeListener { v, has -> styleScope(v as TextView, has) }
            }
            // The television splits the rail into equal columns; a phone lets each chip be as
            // wide as its word and scrolls the row, so nothing is ever hyphenated.
            val lp = if (usesNativeInput) {
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, (48 * d).toInt()
                )
            } else {
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
            }
            if (i < SCOPES.size - 1) lp.marginEnd = ((if (usesNativeInput) 8 else 5) * d).toInt()
            scopesBox.addView(chip, lp)
            scopeChips.add(chip)
            styleScope(chip, false)
        }
    }

    private fun selectScope(key: String) {
        if (scope == key) return
        scope = key
        restyleScopes()
        render(lastState)
    }

    private fun restyleScopes() = scopeChips.forEach { styleScope(it, it.isFocused) }

    private fun styleScope(tv: TextView, focused: Boolean) {
        val on = tv.tag == scope
        when {
            focused -> { tv.background = roundRect(4.5f, 0x1A00F0FF, 0xFF00F0FF.toInt(), 1); tv.setTextColor(0xFF00F0FF.toInt()) }
            on -> { tv.background = roundRect(4.5f, 0x1A00F0FF, 0x5900F0FF, 1); tv.setTextColor(0xFF00F0FF.toInt()) }
            else -> { tv.background = roundRect(4.5f, 0x08FFFFFF, 0x0FFFFFFF, 1); tv.setTextColor(0xFF94A3B8.toInt()) }
        }
    }

    private fun buildKeys() {
        keysBox.removeAllViews()
        val rowH = (34 * d).toInt()
        KEYS.toList().chunked(6).forEachIndexed { rowIdx, rowChars ->
            val row = LinearLayout(requireContext()).apply { orientation = LinearLayout.HORIZONTAL }
            rowChars.forEachIndexed { colIdx, ch ->
                val key = TextView(requireContext()).apply {
                    text = ch.toString(); textSize = 10.5f; gravity = Gravity.CENTER; includeFontPadding = false
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
            tv.setTextColor(0xFF041014.toInt()); tv.scaleX = 1.08f; tv.scaleY = 1.08f; tv.z = 6f
        } else {
            tv.background = roundRect(5f, 0x08FFFFFF, 0x0FFFFFFF, 1)
            tv.setTextColor(0xFFCBD5E1.toInt()); tv.scaleX = 1f; tv.scaleY = 1f; tv.z = 0f
        }
    }

    private fun buildActions() {
        actionsBox.removeAllViews()
        val items = listOf(
            Triple("SPACE", 2f, 0xFF00F0FF.toInt()),
            Triple("DEL", 1f, 0xFFFFAA00.toInt()),
            Triple("CLEAR", 1f, 0xFFFF3366.toInt())
        )
        items.forEachIndexed { i, (label, weight, accent) ->
            val btn = TextView(requireContext()).apply {
                text = label; textSize = 7.5f; gravity = Gravity.CENTER; includeFontPadding = false
                letterSpacing = 0.06f; isFocusable = true
                StremioFonts.apply(this, R.font.outfit_semibold)
                setOnClickListener { when (label) { "SPACE" -> onSpace(); "DEL" -> onDel(); else -> onClear() } }
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
            val tint = (accent and 0x00FFFFFF) or 0x14000000
            tv.background = roundRect(5f, tint, accent, 1); tv.setTextColor(accent)
        } else {
            tv.background = roundRect(5f, 0x08FFFFFF, 0x0FFFFFFF, 1); tv.setTextColor(0xFF94A3B8.toInt())
        }
    }

    private fun buildTrending(recent: List<String> = emptyList()) {
        val terms = recent.take(8).ifEmpty { TRENDING }
        trendingLabel.text = if (recent.isNotEmpty()) "Recent Searches" else "Trending Searches"
        trendingBox.removeAllViews()
        terms.forEach { term ->
            val chip = TextView(requireContext()).apply {
                text = term; textSize = 8f; gravity = Gravity.CENTER_VERTICAL; includeFontPadding = false
                setPadding((10 * d).toInt(), 0, (10 * d).toInt(), 0)
                isFocusable = true
                StremioFonts.apply(this, R.font.inter_regular)
                setOnClickListener { pickTerm(term) }
                setOnFocusChangeListener { v, has -> styleTrending(v as TextView, has) }
            }
            styleTrending(chip, false)
            val lp = FlexboxLayout.LayoutParams(FlexboxLayout.LayoutParams.WRAP_CONTENT, (24 * d).toInt())
                .apply { setMargins(0, 0, (6 * d).toInt(), (6 * d).toInt()) }
            trendingBox.addView(chip, lp)
        }
    }

    private fun styleTrending(tv: TextView, focused: Boolean) {
        if (focused) {
            tv.background = roundRect(12f, 0x0F00F0FF, 0xFF00F0FF.toInt(), 1); tv.setTextColor(0xFF00F0FF.toInt())
        } else {
            tv.background = roundRect(12f, 0xB30C111B.toInt(), 0x14FFFFFF, 1); tv.setTextColor(0xFFCBD5E1.toInt())
        }
    }

    private fun startCaretBlink() {
        // A real input field draws its own caret. Blinking a second one next to it is decoration
        // pretending to be a cursor, and on a phone the two disagree the moment you move the real
        // one. This animation is the TV's.
        if (usesNativeInput) return
        ValueAnimator.ofFloat(1f, 0f).apply {
            duration = 550
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            addUpdateListener { caret.alpha = it.animatedValue as Float }
            start()
        }
    }

    private fun isDescendantOf(v: View, ancestor: View): Boolean {
        var cur: View? = v
        while (cur != null) { if (cur === ancestor) return true; cur = cur.parent as? View }
        return false
    }

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
        private const val TAG = "SearchFragment"
        private const val MOVIE_C = 0xFF00F0FF.toInt(); private const val MOVIE_BG = 0x2400F0FF; private const val MOVIE_BD = 0x4D00F0FF
        private const val SERIES_C = 0xFFA78BFA.toInt(); private const val SERIES_BG = 0x337C3AED; private const val SERIES_BD = 0x597C3AED
        private const val LIVE_C = 0xFF00FF88.toInt(); private const val LIVE_BG = 0x2400FF88; private const val LIVE_BD = 0x4D00FF88
    }
}
