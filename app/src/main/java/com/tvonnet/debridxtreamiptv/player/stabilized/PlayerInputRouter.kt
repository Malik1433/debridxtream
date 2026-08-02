package com.tvonnet.debridxtreamiptv.player.stabilized

import android.util.Log
import android.view.KeyEvent
import com.tvonnet.debridxtreamiptv.BuildConfig
import com.tvonnet.debridxtreamiptv.data.model.ContentType

/**
 * The D-pad / media-key routing shell (Phase C6 of the route to ≤600).
 *
 * `dispatchKeyEvent` / `onKeyLongPress` are Activity overrides that call
 * `super`, so they can't move wholesale — but their BODIES can. The Activity keeps
 * one-line overrides that delegate here and fall back to `super` when this router
 * returns `null`:
 *
 *     override fun dispatchKeyEvent(event) = inputRouter.dispatchKeyEvent(event) ?: super.dispatchKeyEvent(event)
 *
 * So the single behaviour-preserving transform vs. the old body is: every
 * `return super.dispatchKeyEvent(event)` (pass-through) became `return null`, and
 * the Activity wrapper makes the `super` call. `return true` (consumed) is unchanged.
 * The classification rules themselves already live in `PlayerKeyRouting.kt` (P17).
 */
internal class PlayerInputRouter(
    private val activity: BasePlayerFragment,
    private val session: PlayerSessionState,
) {

    private val viewModel get() = activity.viewModel
    private val playerView get() = activity.playerView
    private val liveOsd get() = activity.liveOsd
    private val liveTuner get() = activity.liveTuner
    private val epgOverlayUi get() = activity.epgOverlayUi
    private val isControllerVisible get() = activity.isControllerVisible
    private val isInPictureInPictureMode get() = activity.isInPictureInPictureMode

    private var episodeBrowserController: EpisodeBrowserController
        get() = activity.episodeBrowserController
        set(value) { activity.episodeBrowserController = value }

    // ── screen state (S1) ──
    private val contentType: ContentType? by session::contentType
    private val contentId: String? by session::contentId
    private val liveCategoryId: String? by session::liveCategoryId
    private var backHideArmed: Boolean by session::backHideArmed

    // ── forwarders to input collaborators kept on the Activity ──
    private fun supportsPictureInPicture(): Boolean = activity.supportsPictureInPicture()
    private fun enterPictureInPictureModeInternal() = activity.enterPictureInPictureModeInternal()
    private fun performBackExit() = activity.performBackExit()
    private fun showControllerWithSmartFocus(sourceEvent: KeyEvent? = null) =
        activity.showControllerWithSmartFocus(sourceEvent)
    private fun showSubtitleSelection() = activity.showSubtitleSelection()
    private fun hideEpgOverlay() = activity.hideEpgOverlay()
    private fun showEpgOverlay(mode: EpgOverlayMode, pinned: Boolean) = activity.showEpgOverlay(mode, pinned)
    private fun toggleEpgOverlayPinned() = activity.toggleEpgOverlayPinned()

    fun dispatchKeyEvent(event: KeyEvent): Boolean? {
        // LP-D-6: per-key logging only in debug builds (was unconditional).
        if (BuildConfig.DEBUG) {
            Log.d("PlayerActivity", "dispatchKeyEvent: code=${event.keyCode}, action=${event.action}")
        }

        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            // In-player TV Guide: BACK closes the grid instead of exiting.
            if (liveOsd?.isGuideOpen == true) {
                if (event.action == KeyEvent.ACTION_UP) liveOsd?.handleGuideBack()
                return true
            }
            // Surf drawer: BACK steps categories→channels→close; else exits (spec §8).
            if (liveOsd?.isDrawerOpen == true) {
                if (event.action == KeyEvent.ACTION_UP) liveOsd?.handleDrawerBack()
                return true
            }
            // VOD Player redesign: BACK cancels the "Up Next" auto-play prompt instead of exiting.
            if (activity.isNextEpisodePromptVisible()) {
                if (event.action == KeyEvent.ACTION_UP) activity.hideNextEpisodePromptIfInitialized()
                return true
            }
            // The episode browser isn't handled by onBackPressedDispatcher, so dismiss it here.
            if (activity.isEpisodeBrowserVisible()) {
                if (event.action == KeyEvent.ACTION_UP) episodeBrowserController.hide()
                return true
            }
            // Long-press BACK -> PiP (if supported).
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount > 0 && supportsPictureInPicture()) {
                enterPictureInPictureModeInternal()
                return true
            }
            // VOD (movie/episode): consume BACK here — media3's controller would otherwise swallow it
            // so onBackPressedDispatcher never runs (the "BACK doesn't exit" bug). 1st press hides the
            // controls, 2nd runs the real exit (records history, sets manualExit).
            if (contentType == ContentType.MOVIE || contentType == ContentType.EPISODE) {
                return handleVodBack(event)
            }
            // LIVE_TV and anything else: fall through to onBackPressedDispatcher, which handles the
            // channel browser, EPG, and the shared Live TV player hand-off + manualExit / history.
        }

        if (event.action == KeyEvent.ACTION_DOWN) {
            return handleActionDownKey(event)
        }
        return null
    }

    private fun handleVodBack(event: KeyEvent): Boolean? {
        if (event.action == KeyEvent.ACTION_UP) {
            when (vodBackAction(isInPictureInPictureMode, isControllerVisible, backHideArmed)) {
                VodBackAction.PASS_THROUGH -> return null
                VodBackAction.HIDE_CONTROLLER -> {
                    playerView.hideController()
                    backHideArmed = true
                }
                VodBackAction.EXIT -> performBackExit()
            }
        }
        return true
    }

    private fun handleActionDownKey(event: KeyEvent): Boolean? {
            if (activity.isEpisodeBrowserVisible()) return consumeEpisodeBrowserKey(event)

            // VOD Player redesign: the episodes list opens ONLY via the EPISODES button now
            // (DPAD_DOWN no longer auto-opens it).

            if (maybeShowVodController(event)) return true

            // Cinematic live OSD routing (spec §8): ◀ drawer, ▶/OK controls row.
            // Zap keys fall through to the branches below.
            if (contentType == ContentType.LIVE_TV && !viewModel.browserState.value.isVisible) {
                liveOsd?.let { osd -> if (osd.handleKeyEvent(event)) return true }
            }

            return when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> handleDpadLeftDown()
                KeyEvent.KEYCODE_CAPTIONS -> handleCaptionsDown()
                KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_PAGE_UP,
                KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.KEYCODE_PAGE_DOWN,
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> handleZapKeyDown(event)
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER ->
                    handleCenterDown()
                KeyEvent.KEYCODE_INFO -> handleInfoDown()
                KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_GUIDE -> handleMenuOrGuideDown()
                else -> null
            }
    }

    // VOD Player redesign: coverflow panel. Delegate UP/DOWN to the list; LEFT/BACK
    // close it — restore transport controls when it closes. While it stays open, consume
    // every key so an unhandled one can't fall through and flash the controller underneath.
    private fun consumeEpisodeBrowserKey(event: KeyEvent): Boolean {
        playerView.hideController()
        episodeBrowserController.handleKeyEvent(event)
        if (!episodeBrowserController.isVisible()) {
            showControllerWithSmartFocus()
        }
        return true
    }

    // Standard Controller triggers: a trigger key pops the hidden VOD transport controller.
    private fun maybeShowVodController(event: KeyEvent): Boolean {
        val controllerCanAppear = contentType != ContentType.LIVE_TV && playerView.useController
        if (!controllerCanAppear || activity.isNextEpisodePromptVisible() || playerView.isControllerFullyVisible) {
            return false
        }
        if (!isControllerTriggerKey(event.keyCode)) return false
        showControllerWithSmartFocus(event)
        return true
    }

    private fun handleDpadLeftDown(): Boolean? {
        if (contentType != ContentType.LIVE_TV || liveOsd != null) return null
        if (viewModel.browserState.value.isVisible) return null
        viewModel.toggleBrowser(true, viewModel.zapState.value?.categoryId ?: liveCategoryId, contentId)
        return true
    }

    private fun handleCaptionsDown(): Boolean? {
        if (contentType == ContentType.LIVE_TV) return null
        showSubtitleSelection()
        return true
    }

    private fun handleZapKeyDown(event: KeyEvent): Boolean? {
        val direction = zapDirectionFor(event.keyCode) ?: return null
        val canZap = canZapNow(
            action = event.action,
            repeatCount = event.repeatCount,
            contentType = contentType,
            surfaces = OpenPlayerSurfaces(
                isBrowserVisible = viewModel.browserState.value.isVisible,
                isSurfDrawerOpen = liveOsd?.isDrawerOpen == true,
                // Guide owns the screen while open — never zap the channel underneath it.
                isGuideOpen = liveOsd?.isGuideOpen == true
            )
        )
        if (!canZap) return null
        liveTuner.zapChannel(direction = direction)
        return true
    }

    private fun handleCenterDown(): Boolean? {
        if (contentType != ContentType.LIVE_TV) return null
        if (viewModel.browserState.value.isVisible || liveOsd != null) return null
        if (epgOverlayUi.mode != EpgOverlayMode.HIDDEN && !epgOverlayUi.isPinned) {
            hideEpgOverlay()
        } else {
            showEpgOverlay(EpgOverlayMode.COMPACT, pinned = false)
        }
        return true
    }

    private fun handleInfoDown(): Boolean {
        if (contentType == ContentType.LIVE_TV) {
            liveOsd?.showOsd(focus = true) ?: toggleEpgOverlayPinned()
        } else {
            backHideArmed = false; playerView.showController(); playerView.requestFocus()
        }
        return true
    }

    private fun handleMenuOrGuideDown(): Boolean? {
        if (contentType == ContentType.LIVE_TV) {
            liveOsd?.let {
                viewModel.toggleBrowser(true, viewModel.zapState.value?.categoryId ?: liveCategoryId, contentId)
            } ?: toggleEpgOverlayPinned()
            return true
        }
        if (playerView.isControllerFullyVisible) { showSubtitleSelection(); return true }
        return null
    }

    fun onKeyLongPress(keyCode: Int, event: KeyEvent): Boolean? {
        if (contentType == ContentType.LIVE_TV) { when (keyCode) { KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> { liveOsd?.showOsd(focus = true) ?: toggleEpgOverlayPinned(); return true } } }
        return null
    }
}
