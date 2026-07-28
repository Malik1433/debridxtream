package com.tvonnet.debridxtreamiptv.player.stabilized

import android.view.KeyEvent
import com.tvonnet.debridxtreamiptv.data.model.ContentType

/**
 * Pure key-routing decisions for the player (Phase P17).
 *
 * Scope note: `dispatchKeyEvent` itself stays in [PlayerActivity] — it drives the OSD,
 * the episode browser, the guide, PiP and the exit. What moves here are the rules that
 * were written out repeatedly inside it, so each one is stated once and can be tested.
 */

/** Keys that pop the transport controller open when it is hidden. */
internal fun isControllerTriggerKey(keyCode: Int): Boolean = when (keyCode) {
    KeyEvent.KEYCODE_DPAD_LEFT,
    KeyEvent.KEYCODE_DPAD_RIGHT,
    KeyEvent.KEYCODE_DPAD_UP,
    KeyEvent.KEYCODE_DPAD_DOWN,
    KeyEvent.KEYCODE_DPAD_CENTER,
    KeyEvent.KEYCODE_ENTER,
    KeyEvent.KEYCODE_NUMPAD_ENTER,
    KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
    KeyEvent.KEYCODE_MEDIA_PLAY,
    KeyEvent.KEYCODE_MEDIA_PAUSE,
    KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
    KeyEvent.KEYCODE_MEDIA_REWIND,
    KeyEvent.KEYCODE_MENU -> true
    else -> false
}

/** +1 = next channel, -1 = previous, null = not a zap key. */
internal fun zapDirectionFor(keyCode: Int): Int? = when (keyCode) {
    KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_MEDIA_NEXT,
    KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_DPAD_UP -> +1
    KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_MEDIA_PREVIOUS,
    KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_DPAD_DOWN -> -1
    else -> null
}

/**
 * May this key event zap right now?
 *
 * `repeatCount == 0` is the zap-storm filter: a held key auto-repeats, and every repeat
 * used to fire its own tune. Any open surface (channel browser, surf drawer, TV guide)
 * owns the screen, so the channel underneath must not change beneath it.
 */
internal fun canZapNow(
    action: Int,
    repeatCount: Int,
    contentType: ContentType?,
    surfaces: OpenPlayerSurfaces
): Boolean =
    action == KeyEvent.ACTION_DOWN &&
        repeatCount == 0 &&
        contentType == ContentType.LIVE_TV &&
        !surfaces.anyOpen()

/** The surfaces that own the screen while they are up — none may be zapped under. */
internal data class OpenPlayerSurfaces(
    val isBrowserVisible: Boolean = false,
    val isSurfDrawerOpen: Boolean = false,
    val isGuideOpen: Boolean = false
) {
    fun anyOpen(): Boolean = isBrowserVisible || isSurfDrawerOpen || isGuideOpen
}

/**
 * In the Live OSD, does LEFT open the channel drawer — or step focus back along the controls row?
 *
 * It used to open the drawer unconditionally, which made the row a one-way street: you could walk
 * right from the channels button out to audio or aspect, but the first LEFT threw the drawer open
 * instead of stepping back one control, so there was no way to walk back. RIGHT never had that
 * problem because it lets focus travel; this is the same rule stated for the other direction.
 *
 * The drawer still opens in the two cases where it should — from the video itself (no control has
 * focus), and from the leftmost control, where there is nothing further left to reach.
 */
internal fun leftOpensChannelDrawer(
    isOsdVisible: Boolean,
    isControlFocused: Boolean,
    isFirstControlFocused: Boolean
): Boolean = !isOsdVisible || !isControlFocused || isFirstControlFocused

/** What BACK does in a VOD session (movie/episode). */
internal enum class VodBackAction {
    /** In PiP the system handles BACK. */
    PASS_THROUGH,

    /** First press with controls up: hide them. */
    HIDE_CONTROLLER,

    /** Second press (or controls already hidden): the real exit. */
    EXIT
}

/**
 * The two-step BACK: with the controller showing and not yet armed, BACK hides it;
 * after that BACK exits. media3's controller would otherwise swallow BACK entirely,
 * which was the "BACK doesn't exit" bug.
 */
internal fun vodBackAction(
    isInPictureInPictureMode: Boolean,
    isControllerVisible: Boolean,
    backHideArmed: Boolean
): VodBackAction = when {
    isInPictureInPictureMode -> VodBackAction.PASS_THROUGH
    isControllerVisible && !backHideArmed -> VodBackAction.HIDE_CONTROLLER
    else -> VodBackAction.EXIT
}
