package com.tvonnet.debridxtreamiptv.ui.live.guide

import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.content.Intent
import androidx.activity.result.contract.ActivityResultContracts
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
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
import com.tvonnet.debridxtreamiptv.player.stabilized.LiveSharedPlayer
import com.tvonnet.debridxtreamiptv.player.stabilized.PlayerActivity
import com.tvonnet.debridxtreamiptv.ui.live.PreviewPlayerPanel
import com.tvonnet.debridxtreamiptv.util.GlobalConfig
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.Date
import javax.inject.Inject

@AndroidEntryPoint
class LiveTvGuideFragment : Fragment() {

    private companion object {
        /** Cap on waiting for our own surface to render before shrinking anyway. */
        const val FIRST_FRAME_TIMEOUT_MS = 800L
    }

    private var _binding: FragmentLiveTvGuideBinding? = null
    private val binding get() = _binding!!

    // Playback-tuned client (longer read timeout); only used to build the preview
    // player's DataSource below.
    @Inject
    @javax.inject.Named("playback")
    lateinit var okHttpClient: OkHttpClient

    private val viewModel: LiveTvGuideViewModel by viewModels()

    private var previewPanel: PreviewPlayerPanel? = null
    private val previewHandler = Handler(Looper.getMainLooper())
    private var pendingPreview: Runnable? = null
    private var lastPreviewStreamId: String? = null

    private var videoBridge: GuideVideoBridge? = null
    private var chips: GuideChipsController? = null
    private var detailPanel: GuideDetailPanel? = null
    private var searchOverlay: GuideSearchOverlay? = null

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

    /** Guards the resume "beyond-cap" fetch so repeated state emissions don't re-launch it. */
    private var resumeInFlight = false

    /** Shared prefs accessor (SharedPreferences is process-cached; just avoid re-allocating). */
    private val settingsPrefs by lazy { SettingsPreferences(requireContext()) }

    /** Receives the live-return extras + the shared player when fullscreen exits. */
    private val livePlayerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result -> onReturnedFromPlayer(result.data) }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentLiveTvGuideBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        buildCollaborators()
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
            onFavoriteClick = { Toast.makeText(requireContext(), requireContext().getString(R.string.section_favorites), Toast.LENGTH_SHORT).show() }
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
        binding.root.post { if (_binding != null && !isFullscreen) videoBridge?.parkAtTile { !isFullscreen } }

        binding.epgGrid.listener = object : EpgGridView.Listener {
            override fun onFocusChanged(channel: GuideChannel, program: GuideProgram?) = updateDetail(channel, program)
            // Standard TV pattern: focus browses, first OK selects (mini preview
            // tunes), OK again on the playing channel goes fullscreen.
            override fun onProgramSelected(channel: GuideChannel, program: GuideProgram?) = selectOrFullscreen(channel)
            override fun onChannelSelected(channel: GuideChannel) = selectOrFullscreen(channel)
            override fun onExitLeft() = focusSelectedChip()
            override fun onExitTop() = focusSelectedChip()
        }

        binding.btnJumpNow.setOnClickListener { binding.epgGrid.scrollToNow(true) }
        binding.btnPrimary.setOnClickListener {
            Toast.makeText(requireContext(), binding.btnPrimary.text, Toast.LENGTH_SHORT).show()
        }
        // M18: the button is a stub (its click is the Toast above), and at 1.6x the fixed info
        // block overflows and clips it anyway — on a phone it is hidden outright. TV keeps it.
        binding.btnPrimary.isVisible = resources.getBoolean(R.bool.epg_shows_primary_btn)

        observeState()
        startClock()
    }

    private fun applyGridConfig() {
        val prefs = settingsPrefs
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

    private fun buildCollaborators() {
        val b = binding
        videoBridge = GuideVideoBridge(b, previewHandler) { _binding != null }
        detailPanel = GuideDetailPanel(this, b)
        chips = GuideChipsController(
            fragment = this,
            binding = b,
            onCategorySelected = { id -> viewModel.selectCategory(id) },
            onDaySelected = { index -> viewModel.selectDay(index) },
            onSearchClicked = { searchOverlay?.show() },
        )
        searchOverlay = GuideSearchOverlay(
            fragment = this,
            viewModel = viewModel,
            onCategoryPicked = { cat -> viewModel.selectCategory(cat.id) },
            onChannelPicked = { channel, matches -> onSearchChannelPicked(channel, matches) },
        )
    }

    /** Leaving the grid left/up lands on the chip for the category currently being shown. */
    private fun focusSelectedChip() {
        chips?.focusSelectedChip(viewModel.uiState.value.selectedCategoryId)
    }

    private fun observeState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    binding.loading.visibility = if (state.isLoading) View.VISIBLE else View.GONE
                    binding.empty.visibility = if (!state.isLoading && state.channels.isEmpty()) View.VISIBLE else View.GONE
                    binding.empty.text = state.error ?: "No channels found."

                    chips?.bind(state)

                    binding.tvChannelCount.text = getString(R.string.f_channel_count_short, state.channels.size)
                    binding.btnJumpNow.visibility = if (state.isToday && state.channels.isNotEmpty()) View.VISIBLE else View.GONE

                    if (!state.isLoading) {
                        binding.epgGrid.setData(state.channels, state.windowStartMs, state.windowSpanMinutes, state.nowMs)
                        // Re-apply the selection carried back from fullscreen zapping
                        // now that the grid actually has its data.
                        pendingGridFocusStreamId?.let { id ->
                            binding.epgGrid.focusChannel(id)
                            pendingGridFocusStreamId = null
                        }
                        autoSelectInitialChannel(state)
                        restoreGridFocusIfAppropriate(state)
                    }
                }
            }
        }
    }

    /**
     * Something should always be playing when the guide opens: auto-select once (like TiviMate).
     * If "Resume Last Channel" is on, start on the last-watched channel instead.
     *
     * Every path here passes `persist = false` — an auto-select must never overwrite the user's
     * real last channel, or the setting would remember whatever the guide happened to land on.
     */
    private fun autoSelectInitialChannel(state: GuideUiState) {
        if (previewChannel != null || state.channels.isEmpty() || isFullscreen) return

        val lastId = settingsPrefs.getLastLiveStreamId()
        val resume = settingsPrefs.isResumeLastLiveEnabled() && lastId != null
        val inList = if (resume) state.channels.firstOrNull { it.streamId == lastId } else null

        when {
            inList != null -> {
                selectForPreview(inList, persist = false)
                binding.epgGrid.focusChannel(inList.streamId)
            }
            resume -> resumeChannelBeyondList(lastId!!, state.channels.first())
            else -> selectForPreview(state.channels.first(), persist = false)
        }
    }

    /**
     * The saved channel isn't in the loaded list (e.g. beyond the "All" cap) — fetch it from the
     * full index once and play it in the mini; the grid stays on the first tile.
     * [resumeInFlight] guards it, because this state block re-emits repeatedly.
     */
    private fun resumeChannelBeyondList(lastId: String, fallback: GuideChannel) {
        if (resumeInFlight) return
        resumeInFlight = true
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val ch = viewModel.getChannelById(lastId)
                if (previewChannel == null) selectForPreview(ch ?: fallback, persist = false)
            } finally {
                resumeInFlight = false
            }
        }
    }

    /**
     * CC-1: the state block re-emits when the user selects a day tab or category chip. Only pull
     * focus into the grid on first populate (nothing focused yet) or when the grid already holds
     * focus — never yank it away from the chips / day tabs the user is navigating.
     */
    private fun restoreGridFocusIfAppropriate(state: GuideUiState) {
        if (state.channels.isEmpty()) return
        val cf = binding.root.findFocus()
        if (cf == null || cf === binding.epgGrid) binding.epgGrid.requestFocus()
    }

    // ── Detail panel ──────────────────────────────────────
    private fun updateDetail(channel: GuideChannel, program: GuideProgram?) {
        currentChannel = channel
        detailPanel?.update(channel, program)
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
    private fun selectForPreview(channel: GuideChannel, persist: Boolean = true) {
        pendingPreview?.let { previewHandler.removeCallbacks(it) }
        previewChannel = channel
        lastPreviewStreamId = channel.streamId
        binding.epgGrid.setPlayingChannel(channel.streamId)
        previewPanel?.play(channel.stream)
        previewPanel?.setVolume(1f)
        // Remember DELIBERATE picks so "Resume Last Channel" can restore them.
        // Skip auto-selects (persist=false) so they never overwrite the real last channel.
        if (persist) channel.streamId.takeIf { it.isNotBlank() }?.let {
            settingsPrefs.setLastLiveStreamId(it)
            settingsPrefs.setLastLiveCategoryId(viewModel.uiState.value.selectedCategoryId)
        }
    }

    // ── Unified search overlay (categories + channels) ─────────────────────

    /** A search hit shows its matches in the grid, selected, so OK on the tile goes fullscreen. */
    private fun onSearchChannelPicked(channel: GuideChannel, matches: List<GuideChannel>) {
        pendingGridFocusStreamId = channel.streamId
        selectForPreview(channel)
        viewModel.showChannels(matches)
        binding.epgGrid.focusChannel(channel.streamId)
    }

    // ── In-place fullscreen (grows the preview, keeps the same player) ─────────────────
    // Geometry, the cover snapshot and the first-frame wait live in [GuideVideoBridge]; the
    // ORDER of the steps below is the part that has historically caused black/frozen video, so it
    // stays here where it can be read top to bottom.

    /**
     * Fullscreen = water-drop grow of the live preview, then hand the RUNNING
     * player to PlayerActivity (full Live TV UI: EPG overlay, details, zapping).
     * The stream never reconnects in either direction.
     */
    private fun enterFullscreenFor(channel: GuideChannel) {
        if (isFullscreen) return
        ensureTunedTo(channel)
        isFullscreen = true
        pendingPreview?.let { previewHandler.removeCallbacks(it) }
        fullscreenReturnFocus = binding.root.findFocus()

        // The bridge already sits on the tile RENDERING LIVE — just grow it.
        // A TextureView keeps rendering through View transforms, so the whole
        // grow shows live video: no snapshot, no surface switch, no freeze.
        videoBridge?.parkAtTile { !isFullscreen }
        videoBridge?.growToFullscreen { handOffToPlayerActivity(channel) }
    }

    /** Tune the preview to [channel] if it is not already the one playing. */
    private fun ensureTunedTo(channel: GuideChannel) {
        val id = channel.stream.stream_id
        if (id != null && id != lastPreviewStreamId) {
            pendingPreview?.let { previewHandler.removeCallbacks(it) }
            lastPreviewStreamId = id
            previewPanel?.play(channel.stream)
            previewPanel?.setVolume(1f)
        }
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
            videoBridge?.parkAtTile { !isFullscreen }
            isFullscreen = false
            return
        }
        val streamUrl = stream.toLiveStreamUrl(serverUrl, username, password)
        // Hand the running player over WITH a snapshot of its latest frame
        // (still rendering in the mini), so PlayerActivity can cover its own
        // surface warm-up — the one real switch stays invisible.
        val handOffFrame = videoBridge?.capturePreviewFrame()
        previewPanel?.detachPlayer()?.let { LiveSharedPlayer.offer(it, streamUrl, stream.stream_id, handOffFrame) }
        livePlayerLauncher.launch(buildPlayerIntent(channel, streamUrl, serverUrl))
        requireActivity().overridePendingTransition(0, 0)
    }

    private fun buildPlayerIntent(channel: GuideChannel, streamUrl: String, serverUrl: String): Intent {
        val stream = channel.stream
        return PlayerActivity.createIntent(
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
            recoverWithoutSharedPlayer(channel)
            return
        }
        // The bridge is still fullscreen from the way in. Cover it with the
        // exact frame PlayerActivity was showing, re-attach the player UNDER
        // the cover (the only surface switch on the way back), and once our
        // surface renders, reveal the live video and shrink it — live — onto
        // the tile. No frozen video, no black.
        videoBridge?.showFullscreenContainer()
        videoBridge?.showCover(returnFrame)
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

        videoBridge?.onceFirstFrame(player, FIRST_FRAME_TIMEOUT_MS) {
            if (_binding == null) return@onceFirstFrame
            // Live frames are flowing again: reveal them and shrink live.
            videoBridge?.fadeOutCover()
            videoBridge?.shrinkToTile {
                isFullscreen = false
                restoreFocusAfterFullscreen()
            }
        }
    }

    /** No shared player came back (error / cold exit) — fresh preview instead. */
    private fun recoverWithoutSharedPlayer(channel: GuideChannel?) {
        videoBridge?.parkAtTile { !isFullscreen }
        isFullscreen = false
        lastPreviewStreamId = null
        channel?.let {
            updateDetail(it, it.currentProgram(System.currentTimeMillis()))
            selectForPreview(it)
            binding.epgGrid.focusChannel(it.streamId)
            pendingGridFocusStreamId = it.streamId
        }
        restoreFocusAfterFullscreen()
    }

    private fun restoreFocusAfterFullscreen() {
        (fullscreenReturnFocus?.takeIf { it.isAttachedToWindow } ?: binding.epgGrid).requestFocus()
    }

    private fun startClock() {
        viewLifecycleOwner.lifecycleScope.launch {
            // Phase 4: only tick while STARTED — this updated the clock every 20s even while the
            // guide was STOPPED. repeatOnLifecycle cancels on STOP, restarts (fresh time) on re-START.
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (isActive) {
                    binding.tvClock.text = detailPanel?.formatNow().orEmpty()
                    kotlinx.coroutines.delay(20_000)
                }
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
        videoBridge = null
        chips = null
        detailPanel = null
        searchOverlay = null
        _binding = null
    }
}
