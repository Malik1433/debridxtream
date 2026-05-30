# Player Refactor Phase: EPG Overlay & Live Browser Extraction Plan

## Status
**BLOCKED** / **NOT APPROVED FOR IMPLEMENTATION**

## Context
As part of the God File Cleanup of `PlayerActivity.kt`, several distinct responsibilities have been successfully extracted (History, Subtitles/Audio, Next Episode, Buffer Config). The EPG Overlay and Live TV Browser logic remains heavily embedded in `PlayerActivity` and is a candidate for future extraction.

## Mapping of Existing Logic in PlayerActivity

The EPG Overlay and Live Browser logic currently relies heavily on UI state, timers, and ViewModel interactions within `PlayerActivity`.

### 1. View References
The following views are explicitly bound and manipulated directly inside `PlayerActivity`:
- `epgOverlay` (Container)
- `imgChannelLogo`, `tvChannelNumber`, `tvChannelName` (Channel Meta)
- `tvNowTitle`, `tvNowTime`, `progressNow` (Current Program)
- `tvEpgStatus` (Sync Status)
- `cardNext1`, `cardNext2`, `cardNext3` (Upcoming Programs)
- `tvNowCardTitle`, `tvNowCardTime` (Current Program Card)

### 2. Core Functions
- `setupOverlayViews()`: Initializes the view references and calls `bindChannelMeta` and `renderOverlay` if state exists.
- `observeOverlayState()`: Observes `viewModel.overlayState` (StateFlow) and triggers `renderOverlay(it)`.
- `observeZapState()`: Observes `viewModel.zapState` and updates `tvChannelNumber`.
- `renderOverlay(state: PlayerOverlayUiState)`: Updates text, progress bars, and visibility for the current and upcoming EPG programs. Handles `isSyncing` and `epgAvailable` states.
- `bindNextSlot(...)`: Helper to bind data to the upcoming program cards.
- `bindChannelMeta(...)`: Binds the channel name and logo.

### 3. State & Visibility Management
- `EpgOverlayMode` (HIDDEN, COMPACT, EXPANDED) dictates the UI mode.
- `showEpgOverlay(mode, pinned)` and `hideEpgOverlay()` manage transitions.
- `maybeAutoHideOverlay()` uses Handlers to hide the overlay after a timeout.

### 4. Dependencies & Coupling
- **Coupling with ViewModel**: Relies heavily on `PlayerViewModel` for `overlayState` and `zapState`.
- **Coupling with Player Lifecycle**: Reacts to channel zapping, which requires coordination with ExoPlayer buffer/switch logic (e.g., seamless switching).

## Risk Assessment
**Extraction Risk: HIGH**

### Why is it High Risk?
1. **Deep UI Coupling**: The EPG overlay logic requires direct access to many views and view states.
2. **Timing/Lifecycle Dependencies**: Overlay visibility is tied to Handlers (`maybeAutoHideOverlay`), channel zapping, and error states. If the extraction gets the timing wrong, the overlay might get stuck or hide prematurely.
3. **Live TV Sensitivity**: Live TV users rely heavily on the EPG and zapping speed. Any regression in overlay performance or state synchronization is highly visible.

## Recommendation
**DO NOT EXTRACT YET.**

### Prerequisites for Future Extraction:
Before extracting this logic into a `PlayerEpgOverlayManager` or `PlayerLiveBrowserManager`, the following should be considered:
1. Ensure `Network/Stall` and `Playback Core` are fully stabilized and independent.
2. The overlay logic should be refactored into a passive View Controller pattern where the Activity passes a reference to a root View, and the Manager sets up its own internal view bindings (e.g., using ViewBinding).
3. The Manager will need a robust way to observe the `PlayerViewModel` states without memory leaks or lifecycle mismatches.

For now, the logic should remain in `PlayerActivity` until the easier core extractions (like Network/Stall monitoring) are completed and proven stable.
