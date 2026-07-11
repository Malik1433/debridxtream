package com.tvonnet.debridxtreamiptv.ui.live.guide

import android.animation.ValueAnimator
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.content.Intent
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import androidx.activity.result.contract.ActivityResultContracts
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.tvonnet.debridxtreamiptv.R
import com.tvonnet.debridxtreamiptv.data.model.ContentType
import com.tvonnet.debridxtreamiptv.data.model.XtreamStream
import com.tvonnet.debridxtreamiptv.data.model.toLiveStreamUrl
import com.tvonnet.debridxtreamiptv.data.prefs.CredentialsPreferences
import com.tvonnet.debridxtreamiptv.data.prefs.SettingsPreferences
import com.tvonnet.debridxtreamiptv.databinding.FragmentLiveTvGuideBinding
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.tvonnet.debridxtreamiptv.player.stabilized.LiveSharedPlayer
import com.tvonnet.debridxtreamiptv.player.stabilized.PlayerActivity
import com.tvonnet.debridxtreamiptv.ui.live.PreviewPlayerPanel
import com.tvonnet.debridxtreamiptv.util.GlobalConfig
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

@AndroidEntryPoint
class LiveTvGuideFragment : Fragment() {

    private var _binding: FragmentLiveTvGuideBinding? = null
    private val binding get() = _binding!!

    @Inject
    lateinit var okHttpClient: OkHttpClient

    private val viewModel: LiveTvGuideViewModel by viewModels()

    private var previewPanel: PreviewPlayerPanel? = null
    private val previewHandler = Handler(Looper.getMainLooper())
    private var pendingPreview: Runnable? = null
    private var lastPreviewStreamId: String? = null

    private var highlightAnim: ValueAnimator? = null
    private var highlightShown = false

    private var currentChannel: GuideChannel? = null

    /** The deliberately SELECTED channel playing in the mini preview (OK to change). */
    private var previewChannel: GuideChannel? = null
    private var isFullscreen = false
    private var fullscreenReturnFocus: View? = null

    /**
     * Channel to select in the grid once it (re)loads — set on return from
     * fullscreen, because the grid data arrives asynchronously and an immediate
     * focusChannel() can hit an empty/new grid.
     */
    private var pendingGridFocusStreamId: String? = null

    /** Receives the live-return extras + the shared player when fullscreen exits. */
    private val livePlayerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result -> onReturnedFromPlayer(result.data) }

    private val clockFmt = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val dayLabels = listOf("Today", "Tomorrow", "+2 day", "+3 day")

    private var builtCategoriesSignature: String? = null
    private var dayTabsBuilt = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentLiveTvGuideBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        applyGridConfig()

        // The horizontal scroller must not steal focus; chips themselves are focusable.
        binding.chipsScroll.isFocusable = false
        binding.chipsScroll.isFocusableInTouchMode = false
        // Let the focus "pop" (scale) draw without being clipped.
        binding.chipsScroll.clipChildren = false
        binding.chipsContainer.clipChildren = false

        // Live muted preview (same panel the classic Live screen uses)
        previewPanel = PreviewPlayerPanel(
            requireContext(),
            binding.root,
            okHttpClient,
            onFullscreenClick = { s ->
                (viewModel.uiState.value.channels.firstOrNull { it.streamId == s.stream_id } ?: currentChannel)
                    ?.let { enterFullscreenFor(it) }
            },
            onFavoriteClick = { Toast.makeText(requireContext(), "Favorites", Toast.LENGTH_SHORT).show() }
        )
        viewLifecycleOwner.lifecycle.addObserver(previewPanel!!)

        // Preview tile: OK grows the PLAYING channel into the fullscreen player.
        // The focus ring is drawn on the video bridge (it covers the tile).
        binding.previewTile.setOnClickListener {
            previewChannel?.let { enterFullscreenFor(it) }
                ?: currentChannel?.let { selectForPreview(it) }
        }
        binding.previewTile.setOnFocusChangeListener { _, hasFocus ->
            binding.fullscreenContainer.foreground =
                if (hasFocus) ContextCompat.getDrawable(requireContext(), R.drawable.bg_epg_focus_ring_thick) else null
        }

        // Park the persistent video bridge over the tile once the layout is done.
        binding.root.post { if (_binding != null && !isFullscreen) parkBridgeAtTile() }

        binding.epgGrid.listener = object : EpgGridView.Listener {
            override fun onFocusChanged(channel: GuideChannel, program: GuideProgram?) = updateDetail(channel, program)
            // Standard TV pattern: focus browses, first OK selects (mini preview
            // tunes), OK again on the playing channel goes fullscreen.
            override fun onProgramSelected(channel: GuideChannel, program: GuideProgram?) = selectOrFullscreen(channel)
            override fun onChannelSelected(channel: GuideChannel) = selectOrFullscreen(channel)
            override fun onExitLeft() {
                binding.chipsContainer.getChildAt(selectedChipIndex())?.requestFocus()
            }
            override fun onExitTop() {
                binding.chipsContainer.getChildAt(selectedChipIndex())?.requestFocus()
            }
        }

        binding.btnJumpNow.setOnClickListener { binding.epgGrid.scrollToNow(true) }
        binding.btnPrimary.setOnClickListener {
            Toast.makeText(requireContext(), binding.btnPrimary.text, Toast.LENGTH_SHORT).show()
        }

        observeState()
        startClock()
    }

    private fun applyGridConfig() {
        val prefs = SettingsPreferences(requireContext())
        val rowH = when (prefs.getEpgRowDensity()) {
            SettingsPreferences.DENSITY_COZY -> R.dimen.epg_row_cozy
            SettingsPreferences.DENSITY_ROOMY -> R.dimen.epg_row_roomy
            else -> R.dimen.epg_row_standard
        }
        val ppmDp = when (prefs.getEpgTimelineZoom()) {
            SettingsPreferences.ZOOM_COMPACT -> 3.4f
            SettingsPreferences.ZOOM_WIDE -> 7.6f
            else -> 5.0f
        }
        val ppmPx = ppmDp * resources.displayMetrics.density
        binding.epgGrid.setConfig(resources.getDimensionPixelSize(rowH), ppmPx, prefs.isEpgGenreTintEnabled())
    }

    private fun observeState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    binding.loading.visibility = if (state.isLoading) View.VISIBLE else View.GONE
                    binding.empty.visibility = if (!state.isLoading && state.channels.isEmpty()) View.VISIBLE else View.GONE
                    binding.empty.text = state.error ?: "No channels found."

                    buildChipsIfNeeded(state)
                    buildDayTabsIfNeeded(state)
                    highlightChips(state.selectedCategoryId)
                    highlightDayTabs(state.dayIndex)

                    binding.tvChannelCount.text = "${state.channels.size} CH"
                    binding.btnJumpNow.visibility = if (state.isToday && state.channels.isNotEmpty()) View.VISIBLE else View.GONE

                    if (!state.isLoading) {
                        binding.epgGrid.setData(state.channels, state.windowStartMs, state.windowSpanMinutes, state.nowMs)
                        // Re-apply the selection carried back from fullscreen zapping
                        // now that the grid actually has its data.
                        pendingGridFocusStreamId?.let { id ->
                            binding.epgGrid.focusChannel(id)
                            pendingGridFocusStreamId = null
                        }
                        // Something should always be playing when the guide opens:
                        // auto-select once (like TiviMate). If "Resume Last Channel"
                        // is on and that channel is in the list, start there instead.
                        if (previewChannel == null && state.channels.isNotEmpty() && !isFullscreen) {
                            val prefs = SettingsPreferences(requireContext())
                            val start = if (prefs.isResumeLastLiveEnabled()) {
                                val lastId = prefs.getLastLiveStreamId()
                                state.channels.firstOrNull { it.streamId == lastId } ?: state.channels.first()
                            } else state.channels.first()
                            selectForPreview(start)
                            binding.epgGrid.focusChannel(start.streamId)
                        }
                        if (state.channels.isNotEmpty()) binding.epgGrid.requestFocus()
                    }
                }
            }
        }
    }

    // ── Category chips ──────────────────────────────────────────────────
    private val TAG_SEARCH_CHIP = "__guide_search__"

    private fun buildChipsIfNeeded(state: GuideUiState) {
        val signature = state.categories.joinToString("|") { it.id }
        if (signature == builtCategoriesSignature) return
        builtCategoriesSignature = signature
        binding.chipsContainer.removeAllViews()
        val chips = ArrayList<TextView>()
        // Unified search — always the first chip; OK opens the search overlay.
        val searchChip = TextView(requireContext()).apply {
            id = View.generateViewId()
            text = "🔍  Search"
            textSize = 13f
            setPadding(dp(16), dp(9), dp(16), dp(9))
            isFocusable = true
            isSingleLine = true
            background = chipOutline()
            setTextColor(pillTextColors())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(10) }
            tag = TAG_SEARCH_CHIP
            setOnClickListener { showSearchDialog() }
            setOnFocusChangeListener { v, hasFocus ->
                if (hasFocus) moveHighlightTo(v)
                else v.post { if (binding.chipsContainer.findFocus() == null) hideHighlight() }
            }
        }
        chips.add(searchChip)
        binding.chipsContainer.addView(searchChip)
        state.categories.forEach { cat ->
            val chip = TextView(requireContext()).apply {
                id = View.generateViewId()
                text = if (cat.count >= 0) "${cat.name}  ${cat.count}" else cat.name
                textSize = 13f
                setPadding(dp(16), dp(9), dp(16), dp(9))
                isFocusable = true
                isSingleLine = true
                // Static outline; the visible focus is the single sliding highlight pill.
                background = chipOutline()
                setTextColor(pillTextColors())
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = dp(10) }
                layoutParams = lp
                tag = cat.id
                isSelected = cat.id == state.selectedCategoryId
                setOnClickListener { viewModel.selectCategory(cat.id) }
                // Glide the shared highlight onto this chip; hide it when focus leaves the row.
                setOnFocusChangeListener { v, hasFocus ->
                    if (hasFocus) moveHighlightTo(v)
                    else v.post { if (binding.chipsContainer.findFocus() == null) hideHighlight() }
                }
            }
            chips.add(chip)
            binding.chipsContainer.addView(chip)
        }
        // Explicit L/R focus order → a single D-pad press always lands on the sibling.
        // (A HorizontalScrollView otherwise needs two presses to reveal an off-screen chip.)
        for (i in chips.indices) {
            if (i + 1 < chips.size) chips[i].nextFocusRightId = chips[i + 1].id
            if (i - 1 >= 0) chips[i].nextFocusLeftId = chips[i - 1].id
            chips[i].nextFocusDownId = R.id.epg_grid
        }
    }

    private fun highlightChips(selectedId: String) {
        for (i in 0 until binding.chipsContainer.childCount) {
            val chip = binding.chipsContainer.getChildAt(i) as? TextView ?: continue
            chip.isSelected = chip.tag == selectedId
        }
    }

    private fun selectedChipIndex(): Int {
        val id = viewModel.uiState.value.selectedCategoryId
        for (i in 0 until binding.chipsContainer.childCount) {
            if ((binding.chipsContainer.getChildAt(i) as? TextView)?.tag == id) return i
        }
        return 0
    }

    /** Pill background with distinct focused / selected / default states. */
    private fun pillStateBackground(radius: Float): StateListDrawable {
        fun pill(bg: Int, border: Int) = GradientDrawable().apply {
            cornerRadius = radius
            setColor(color(bg))
            setStroke(dp(2), color(border))
        }
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), pill(R.color.epg_chip_active_bg, R.color.epg_cyan))
            addState(intArrayOf(android.R.attr.state_selected), pill(R.color.epg_chip_active_bg, R.color.epg_chip_active_border))
            addState(intArrayOf(), pill(R.color.epg_chip_bg, R.color.epg_chip_border))
        }
    }

    private fun pillTextColors(): ColorStateList {
        val states = arrayOf(
            intArrayOf(android.R.attr.state_focused),
            intArrayOf(android.R.attr.state_selected),
            intArrayOf()
        )
        val colors = intArrayOf(color(R.color.epg_cyan), color(R.color.epg_cyan), color(R.color.epg_text_secondary))
        return ColorStateList(states, colors)
    }

    /** Static chip outline; the moving highlight provides the focus indicator. */
    private fun chipOutline() = GradientDrawable().apply {
        cornerRadius = dp(20).toFloat()
        setColor(color(R.color.epg_chip_bg))
        setStroke(dp(1), color(R.color.epg_chip_border))
    }

    /**
     * Glide + morph the single highlight pill onto [target]. First appearance snaps in;
     * subsequent moves animate position AND size so it flows from one chip into the next.
     */
    private fun moveHighlightTo(target: View) {
        val hl = binding.chipHighlight
        if (target.width == 0) { target.post { moveHighlightTo(target) }; return }
        val tx = target.left.toFloat()
        val ty = target.top.toFloat()
        val tw = target.width
        val th = target.height
        hl.visibility = View.VISIBLE
        hl.alpha = 1f
        if (!highlightShown) {
            highlightShown = true
            hl.layoutParams = hl.layoutParams.apply { width = tw; height = th }
            hl.translationX = tx
            hl.translationY = ty
            return
        }
        hl.animate().translationX(tx).translationY(ty)
            .setDuration(300).setInterpolator(OvershootInterpolator(0.6f)).start()
        val startW = hl.width
        val startH = hl.height
        highlightAnim?.cancel()
        highlightAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 300
            interpolator = OvershootInterpolator(0.6f)
            addUpdateListener {
                if (_binding == null) return@addUpdateListener
                val f = it.animatedValue as Float
                hl.layoutParams = hl.layoutParams.apply {
                    width = (startW + (tw - startW) * f).toInt()
                    height = (startH + (th - startH) * f).toInt()
                }
            }
            start()
        }
    }

    private fun hideHighlight() {
        highlightShown = false
        highlightAnim?.cancel()
        binding.chipHighlight.animate().alpha(0f).setDuration(150).withEndAction {
            if (_binding != null && binding.chipsContainer.findFocus() == null) {
                binding.chipHighlight.visibility = View.INVISIBLE
            }
        }.start()
    }

    // ── Day tabs ────────────────────────────────────────────────────────
    private fun buildDayTabsIfNeeded(state: GuideUiState) {
        if (dayTabsBuilt) return
        dayTabsBuilt = true
        binding.dayTabs.removeAllViews()
        dayLabels.forEachIndexed { index, label ->
            val tab = TextView(requireContext()).apply {
                text = label
                textSize = 13f
                setPadding(dp(14), dp(7), dp(14), dp(7))
                isFocusable = true
                isSingleLine = true
                background = pillStateBackground(dp(8).toFloat())
                setTextColor(pillTextColors())
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginStart = dp(4) }
                layoutParams = lp
                isSelected = index == state.dayIndex
                setOnClickListener { viewModel.selectDay(index) }
                setOnFocusChangeListener { v, hasFocus ->
                    v.animate().scaleX(if (hasFocus) 1.06f else 1f).scaleY(if (hasFocus) 1.06f else 1f)
                        .setDuration(160).start()
                }
            }
            binding.dayTabs.addView(tab)
        }
    }

    private fun highlightDayTabs(selected: Int) {
        for (i in 0 until binding.dayTabs.childCount) {
            val tab = binding.dayTabs.getChildAt(i) as? TextView ?: continue
            tab.isSelected = i == selected
        }
    }

    // ── Detail panel ────────────────────────────────────────────────────
    private fun updateDetail(channel: GuideChannel, program: GuideProgram?) {
        currentChannel = channel
        val now = System.currentTimeMillis()
        val genreColor = color(channel.genre.colorRes)
        binding.previewTile.background = GradientDrawable().apply {
            cornerRadius = dp(16).toFloat()
            colors = intArrayOf(withAlpha(genreColor, 90), withAlpha(genreColor, 30))
            orientation = GradientDrawable.Orientation.TL_BR
            setStroke(dp(1), withAlpha(color(R.color.epg_cyan), 51))
        }
        binding.tvTileInitials.text = channel.initials()
        binding.tvTileName.text = channel.name

        binding.tvBadgeQuality.visibility = if (channel.quality != null) View.VISIBLE else View.GONE
        binding.tvBadgeQuality.text = channel.quality ?: ""

        if (program == null) {
            binding.tvBadgeLive.visibility = View.GONE
            binding.tvStatus.text = ""
            binding.tvTitle.text = channel.name
            binding.tvMeta.text = channel.genre.label
            binding.tvDesc.text = ""
            binding.progressTrack.visibility = View.GONE
            binding.btnPrimary.text = "Add to Favorites"
            return
        }

        val isNow = program.isNow(now)
        val ended = program.hasEnded(now)
        binding.tvBadgeLive.visibility = if (isNow) View.VISIBLE else View.GONE
        binding.tvStatus.text = when {
            isNow -> "ON AIR NOW"
            ended -> "ENDED"
            else -> "UP NEXT"
        }
        binding.tvStatus.setTextColor(color(when {
            isNow -> R.color.epg_status_onair
            ended -> R.color.epg_status_ended
            else -> R.color.epg_status_upnext
        }))
        binding.tvTitle.text = program.title
        binding.tvMeta.text = "${program.genre.label}   ${clockFmt.format(Date(program.startMs))} - ${clockFmt.format(Date(program.stopMs))}"
        binding.tvDesc.text = program.description ?: ""

        if (isNow) {
            binding.progressTrack.visibility = View.VISIBLE
            val frac = ((now - program.startMs).toFloat() / (program.stopMs - program.startMs)).coerceIn(0f, 1f)
            binding.progressTrack.post {
                val lp = binding.progressFill.layoutParams
                lp.width = (binding.progressTrack.width * frac).toInt()
                binding.progressFill.layoutParams = lp
            }
        } else {
            binding.progressTrack.visibility = View.GONE
        }

        binding.btnPrimary.text = if (ended || isNow) "Add to Favorites" else "Set Reminder"
    }

    /**
     * Standard TV pattern: focus only browses; OK selects. First OK on a
     * channel tunes the mini preview to it; OK again (on the channel that is
     * already previewing) goes fullscreen.
     */
    private fun selectOrFullscreen(channel: GuideChannel) {
        val id = channel.streamId
        if (id.isNotEmpty() && id == lastPreviewStreamId && previewPanel?.getPlayer() != null) {
            enterFullscreenFor(channel)
        } else {
            selectForPreview(channel)
        }
    }

    /** Deliberate selection: tune the mini preview to this channel (with sound). */
    private fun selectForPreview(channel: GuideChannel) {
        pendingPreview?.let { previewHandler.removeCallbacks(it) }
        previewChannel = channel
        lastPreviewStreamId = channel.streamId
        binding.epgGrid.setPlayingChannel(channel.streamId)
        previewPanel?.play(channel.stream)
        previewPanel?.setVolume(1f)
        // Remember what's playing so "Resume Last Channel" can restore it next open.
        channel.streamId.takeIf { it.isNotBlank() }?.let {
            SettingsPreferences(requireContext()).setLastLiveStreamId(it)
        }
    }

    // ── Unified search overlay (categories + channels) ─────────────────────
    private fun showSearchDialog() {
        val ctx = requireContext()
        val dialogView = LayoutInflater.from(ctx).inflate(R.layout.dialog_live_guide_search, null)
        val dialog = android.app.AlertDialog.Builder(ctx).setView(dialogView).create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        val etSearch = dialogView.findViewById<android.widget.EditText>(R.id.et_guide_search)
        val rv = dialogView.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.rv_guide_search_results)
        val empty = dialogView.findViewById<TextView>(R.id.tv_guide_search_empty)

        var lastChannels: List<GuideChannel> = emptyList()
        val adapter = LiveSearchAdapter(
            onCategory = { cat -> dialog.dismiss(); viewModel.selectCategory(cat.id) },
            onChannel = { ch ->
                dialog.dismiss()
                // Show the matched channels in the grid so the picked one is
                // present + selected → OK on its tile goes fullscreen.
                val list = lastChannels.ifEmpty { listOf(ch) }
                pendingGridFocusStreamId = ch.streamId
                selectForPreview(ch)
                viewModel.showChannels(list)
                binding.epgGrid.focusChannel(ch.streamId)
            }
        )
        rv.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(ctx)
        rv.adapter = adapter

        var job: kotlinx.coroutines.Job? = null
        fun rebuild(query: String) {
            job?.cancel()
            val cats = viewModel.filterCategories(query)
            job = viewLifecycleOwner.lifecycleScope.launch {
                val channels = if (query.isBlank()) emptyList() else viewModel.searchChannels(query)
                if (!isActive) return@launch
                lastChannels = channels
                val rows = ArrayList<LiveSearchAdapter.Row>()
                if (cats.isNotEmpty()) {
                    rows.add(LiveSearchAdapter.Row.Header(getString(R.string.live_guide_search_categories)))
                    cats.forEach { rows.add(LiveSearchAdapter.Row.CategoryRow(it)) }
                }
                if (channels.isNotEmpty()) {
                    rows.add(LiveSearchAdapter.Row.Header(getString(R.string.live_guide_search_channels)))
                    channels.forEach { rows.add(LiveSearchAdapter.Row.ChannelRow(it)) }
                }
                adapter.submit(rows)
                val isEmpty = rows.isEmpty()
                empty.visibility = if (isEmpty && query.isNotBlank()) View.VISIBLE else View.GONE
                rv.visibility = if (isEmpty) View.GONE else View.VISIBLE
            }
        }

        etSearch.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) { rebuild(s?.toString().orEmpty()) }
        })

        etSearch.requestFocus()
        etSearch.postDelayed({
            (ctx.getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
                as? android.view.inputmethod.InputMethodManager)
                ?.showSoftInput(etSearch, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        }, 100)

        rebuild("") // open on the full category list; typing filters + finds channels
        dialog.show()
    }

    // ── Seamless transition helpers (never show black between surfaces) ─────

    /** Snapshot the last rendered frame of a TextureView-based PlayerView. */
    private fun captureFrame(pv: androidx.media3.ui.PlayerView): android.graphics.Bitmap? =
        runCatching { (pv.videoSurfaceView as? android.view.TextureView)?.getBitmap() }.getOrNull()

    /**
     * Run [action] once the player renders its first frame on the CURRENT
     * surface — with a timeout fallback so the UI can never get stuck.
     */
    private fun onceFirstFrame(player: ExoPlayer, timeoutMs: Long, action: () -> Unit) {
        var done = false
        val listener = object : Player.Listener {
            override fun onRenderedFirstFrame() {
                if (done) return
                done = true
                player.removeListener(this)
                action()
            }
        }
        player.addListener(listener)
        previewHandler.postDelayed({
            if (!done) {
                done = true
                player.removeListener(listener)
                action()
            }
        }, timeoutMs)
    }

    /** Show the hand-off frame snapshot over the bridge video. */
    private fun showBridgeCover(bitmap: android.graphics.Bitmap?) {
        if (bitmap == null) return
        binding.fullscreenCover.setImageBitmap(bitmap)
        binding.fullscreenCover.alpha = 1f
        binding.fullscreenCover.visibility = View.VISIBLE
    }

    // ── In-place fullscreen (grows the preview, keeps the same player) ──────
    /** Returns [left, top, width, height] of the preview tile relative to the fragment root. */
    private fun tileRectInRoot(): FloatArray {
        val tileLoc = IntArray(2); binding.previewTile.getLocationInWindow(tileLoc)
        val rootLoc = IntArray(2); binding.root.getLocationInWindow(rootLoc)
        return floatArrayOf(
            (tileLoc[0] - rootLoc[0]).toFloat(),
            (tileLoc[1] - rootLoc[1]).toFloat(),
            binding.previewTile.width.toFloat().coerceAtLeast(1f),
            binding.previewTile.height.toFloat().coerceAtLeast(1f)
        )
    }

    /**
     * Fullscreen = water-drop grow of the live preview, then hand the RUNNING
     * player to PlayerActivity (full Live TV UI: EPG overlay, details, zapping).
     * The stream never reconnects in either direction.
     */
    private fun enterFullscreenFor(channel: GuideChannel) {
        if (isFullscreen) return
        val stream = channel.stream
        val id = stream.stream_id
        if (id != null && id != lastPreviewStreamId) {
            pendingPreview?.let { previewHandler.removeCallbacks(it) }
            lastPreviewStreamId = id
            previewPanel?.play(stream)
            previewPanel?.setVolume(1f)
        }
        isFullscreen = true
        pendingPreview?.let { previewHandler.removeCallbacks(it) }
        fullscreenReturnFocus = binding.root.findFocus()

        // The bridge already sits on the tile RENDERING LIVE — just grow it.
        // A TextureView keeps rendering through View transforms, so the whole
        // grow shows live video: no snapshot, no surface switch, no freeze.
        parkBridgeAtTile()
        binding.fullscreenContainer.animate()
            .scaleX(1f).scaleY(1f).translationX(0f).translationY(0f)
            .setDuration(360).setInterpolator(OvershootInterpolator(0.5f))
            .withEndAction {
                if (_binding == null) return@withEndAction
                handOffToPlayerActivity(channel)
            }
            .start()
    }

    /**
     * Grow finished: park the running preview player in [LiveSharedPlayer] and
     * launch PlayerActivity, which adopts it (no reconnect) and provides the
     * full Live TV UI. The fullscreen bridge stays visible so the return
     * transition starts from a fullscreen frame.
     */
    private fun handOffToPlayerActivity(channel: GuideChannel) {
        val stream = channel.stream
        val creds = CredentialsPreferences(requireContext())
        val serverUrl = creds.getServerUrl()
        val username = creds.getUsername()
        val password = creds.getPassword()
        if (serverUrl == null || username == null || password == null) {
            parkBridgeAtTile()
            isFullscreen = false
            return
        }
        val streamUrl = stream.toLiveStreamUrl(serverUrl, username, password)
        // Hand the running player over WITH a snapshot of its latest frame
        // (still rendering in the mini), so PlayerActivity can cover its own
        // surface warm-up — the one real switch stays invisible.
        val handOffFrame = captureFrame(binding.previewPlayerView)
        previewPanel?.detachPlayer()?.let { LiveSharedPlayer.offer(it, streamUrl, stream.stream_id, handOffFrame) }
        val intent = PlayerActivity.createIntent(
            context = requireContext(),
            streamUrl = streamUrl,
            title = stream.name ?: "Live",
            channelName = stream.name,
            channelLogo = GlobalConfig.resolveIconUrl(stream.stream_icon),
            epgChannelId = stream.epg_channel_id?.takeIf { it.isNotBlank() } ?: stream.stream_id,
            contentId = stream.stream_id ?: streamUrl,
            contentType = ContentType.LIVE_TV,
            posterUrl = GlobalConfig.resolveIconUrl(stream.stream_icon),
            liveCategoryId = stream.category_id ?: viewModel.uiState.value.selectedCategoryId,
            // Channel list for up/down zapping inside the player.
            liveChannelIds = ArrayList(viewModel.uiState.value.channels.mapNotNull { it.stream.stream_id })
                .takeIf { it.isNotEmpty() },
            baseServerUrl = serverUrl,
            sharedLivePlayer = true
        )
        livePlayerLauncher.launch(intent)
        requireActivity().overridePendingTransition(0, 0)
    }

    /** BACK from fullscreen: adopt the player back and shrink it into the mini tile. */
    private fun onReturnedFromPlayer(data: Intent?) {
        if (_binding == null) {
            LiveSharedPlayer.release("guide_view_gone")
            return
        }
        val returnedId = data?.getStringExtra(PlayerActivity.EXTRA_LIVE_RETURN_CHANNEL_ID)
        val channel = viewModel.uiState.value.channels.firstOrNull { it.streamId == returnedId }
            ?: currentChannel
        val returnFrame = LiveSharedPlayer.takeFrame()
        val player = LiveSharedPlayer.adopt()
        if (player == null || channel == null) {
            // No shared player came back (error / cold exit) — fresh preview instead.
            parkBridgeAtTile()
            isFullscreen = false
            lastPreviewStreamId = null
            channel?.let {
                updateDetail(it, it.currentProgram(System.currentTimeMillis()))
                selectForPreview(it)
                binding.epgGrid.focusChannel(it.streamId)
                pendingGridFocusStreamId = it.streamId
            }
            (fullscreenReturnFocus?.takeIf { it.isAttachedToWindow } ?: binding.epgGrid).requestFocus()
            return
        }
        // The bridge is still fullscreen from the way in. Cover it with the
        // exact frame PlayerActivity was showing, re-attach the player UNDER
        // the cover (the only surface switch on the way back), and once our
        // surface renders, reveal the live video and shrink it — live — onto
        // the tile. No frozen video, no black.
        binding.fullscreenContainer.visibility = View.VISIBLE
        showBridgeCover(returnFrame)
        previewPanel?.adoptPlayer(player, channel.stream)
        previewChannel = channel
        lastPreviewStreamId = channel.streamId
        binding.epgGrid.setPlayingChannel(channel.streamId)
        updateDetail(channel, channel.currentProgram(System.currentTimeMillis()))
        // If the user zapped to another channel in fullscreen, move the grid
        // selection there so OK doesn't re-tune the previous channel — both now
        // and whenever the grid data (re)arrives.
        binding.epgGrid.focusChannel(channel.streamId)
        pendingGridFocusStreamId = channel.streamId

        onceFirstFrame(player, 800) {
            if (_binding == null) return@onceFirstFrame
            // Live frames are flowing again: reveal them and shrink live.
            binding.fullscreenCover.animate().alpha(0f).setDuration(150).withEndAction {
                _binding?.let {
                    binding.fullscreenCover.visibility = View.GONE
                    binding.fullscreenCover.setImageDrawable(null)
                    binding.fullscreenCover.alpha = 1f
                }
            }.start()
            val root = binding.root
            val r = tileRectInRoot()
            binding.fullscreenContainer.animate()
                .scaleX(r[2] / root.width.toFloat()).scaleY(r[3] / root.height.toFloat())
                .translationX(r[0]).translationY(r[1])
                .setDuration(320).setInterpolator(DecelerateInterpolator())
                .withEndAction {
                    if (_binding == null) return@withEndAction
                    isFullscreen = false
                    (fullscreenReturnFocus?.takeIf { it.isAttachedToWindow } ?: binding.epgGrid).requestFocus()
                }
                .start()
        }
    }

    /**
     * Park the persistent video bridge exactly over the preview tile (its
     * "mini" rest state) and drop any cover snapshot. Retries once layout is
     * done; never touches [isFullscreen] (callers own that flag).
     */
    private fun parkBridgeAtTile() {
        val fc = binding.fullscreenContainer
        val root = binding.root
        if (root.width == 0 || binding.previewTile.width == 0) {
            root.post { if (_binding != null && !isFullscreen) parkBridgeAtTile() }
            return
        }
        val r = tileRectInRoot()
        fc.animate().cancel()
        fc.pivotX = 0f
        fc.pivotY = 0f
        fc.scaleX = r[2] / root.width.toFloat()
        fc.scaleY = r[3] / root.height.toFloat()
        fc.translationX = r[0]
        fc.translationY = r[1]
        fc.visibility = View.VISIBLE
        binding.fullscreenCover.visibility = View.GONE
        binding.fullscreenCover.setImageDrawable(null)
        binding.fullscreenCover.alpha = 1f
    }

    private fun startClock() {
        viewLifecycleOwner.lifecycleScope.launch {
            while (isActive) {
                binding.tvClock.text = clockFmt.format(Date())
                kotlinx.coroutines.delay(20_000)
            }
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun color(res: Int) = ContextCompat.getColor(requireContext(), res)
    private fun withAlpha(c: Int, a: Int) = android.graphics.Color.argb(
        a, android.graphics.Color.red(c), android.graphics.Color.green(c), android.graphics.Color.blue(c)
    )

    override fun onDestroyView() {
        super.onDestroyView()
        pendingPreview?.let { previewHandler.removeCallbacks(it) }
        pendingPreview = null
        previewPanel?.releasePlayer()
        previewPanel = null
        _binding = null
    }
}
