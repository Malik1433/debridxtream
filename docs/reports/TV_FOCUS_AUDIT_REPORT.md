# 10-Foot UI Focus Rules Compliance Audit: Player Overlay

This document provides a strict static analysis of `PlayerActivity.kt` and `custom_player_control_view.xml` against the 5 Core Android TV Focus Navigation Rules.

---

### Rule 1: Initial Focus
*When the overlay is summoned via a standard D-pad key, does focus immediately snap to a primary target without getting lost?*

* **COMPLIANT PATTERNS**: 
  `showControllerWithSmartFocus()` specifically checks key intents (LEFT/RIGHT vs CENTER/UP/DOWN) and routes focus intelligently to either the seek bar or the Play/Pause button.
* **NON-COMPLIANT RISKS**: 
  In `PlayerActivity.kt` (Line 510), `PlayerView.ControllerVisibilityListener` blindly requests focus on `exo_progress` via a double-post delay whenever the controller becomes visible. This background listener races against the foreground `showControllerWithSmartFocus()` logic, potentially causing a visual focus flicker between the timeline and the transport buttons.
* **REMEDIATION STEP**: 
  Remove the hardcoded `targetBtn.requestFocus()` from `ControllerVisibilityListener` and rely entirely on the explicit routing inside `showControllerWithSmartFocus()`.

---

### Rule 2: Intent-Based Seeking
*Does pressing LEFT/RIGHT while the overlay is hidden immediately route focus to the seek-bar for instant timeline control?*

* **COMPLIANT PATTERNS**: 
  `showControllerWithSmartFocus()` explicitly intercepts `KeyEvent.KEYCODE_DPAD_LEFT` and `KEYCODE_DPAD_RIGHT`, instantly focusing `exo_progress`, and directly passing the `ACTION_DOWN` and `ACTION_UP` events to it. This flawlessly executes 1-click intent-based scrubbing without trapping the user on the play button.
* **NON-COMPLIANT RISKS**: 
  None observed.
* **REMEDIATION STEP**: 
  N/A (Fully Compliant).

---

### Rule 3: Spatial D-pad Boundaries
*Can focus seamlessly travel between buttons without hitting dead ends or leaking into background layers? Are horizontal/vertical boundaries locked?*

* **COMPLIANT PATTERNS**: 
  The `layout_control_row` explicitly uses `nextFocusDown="@id/layout_control_row"` to create a safe boundary that prevents focus from falling off the bottom of the screen into a black hole.
* **NON-COMPLIANT RISKS**: 
  In `custom_player_control_view.xml`, boundaries are left open on the horizontal edges:
  1. `btn_aspect_ratio` (rightmost utility button) lacks a `nextFocusRight` constraint. Pressing RIGHT here could leak focus off-screen.
  2. `exo_rew` (leftmost transport button) lacks a `nextFocusLeft` constraint. 
  3. `btn_next_episode` has `nextFocusRight="@id/btn_player_subtitles"`. Since `btn_player_subtitles` is in the row *above* the timeline, pressing RIGHT on the bottom row causes a highly disorienting diagonal upward jump.
* **REMEDIATION STEP**: 
  Explicitly define `nextFocusRight="@id/btn_aspect_ratio"` (self-loop) on `btn_aspect_ratio` and `nextFocusLeft="@id/exo_rew"` on `exo_rew`. Change `btn_next_episode`'s `nextFocusRight` to loop back to itself (`@id/btn_next_episode`).

---

### Rule 4: Popup/Dialog Lifecycle Focus Return
*When a dialog is dismissed, is focus forcefully and synchronously returned to the invoking anchor button?*

* **COMPLIANT PATTERNS**: 
  `showManagedTrackDialog()` accurately tracks the active dialog and clears the reference upon dismissal to prevent memory leaks.
* **NON-COMPLIANT RISKS**: 
  `showManagedTrackDialog()` sets an `setOnDismissListener` but fails to request focus back to the triggering buttons (`btn_player_audio` or `btn_player_subtitles`). When the user presses BACK to close the subtitle menu, Android runs a global `focusSearch()`, dropping focus unpredictably onto the timeline or Play button instead of where the user left off.
* **REMEDIATION STEP**: 
  Modify `showManagedTrackDialog` to accept an `anchorView: View` parameter. Inside the `setOnDismissListener`, forcefully call `anchorView.requestFocus()` before clearing the dialog reference.

---

### Rule 5: Fade-Out Clean Up
*When the controller automatically hides, is focus cleanly detached?*

* **COMPLIANT PATTERNS**: 
  ExoPlayer's `PlayerControlView` handles the `GONE` state of its children securely.
* **NON-COMPLIANT RISKS**: 
  There is no explicit `clearFocus()` or fallback defined when `isControllerVisible` becomes false. If a custom overlay view holds focus while fading out, Android dumps focus to the root view, which can cause a brief loss of D-pad input reception until the user presses a direction again.
* **REMEDIATION STEP**: 
  Inside `ControllerVisibilityListener` (line 513), add an `else` branch for when `isControllerVisible == false`, forcefully calling `playerView.requestFocus()` so the underlying active video layer explicitly reclaims key event interception.
