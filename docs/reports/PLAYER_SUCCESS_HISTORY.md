# Player Success History

This document tracks all successful changes and structural improvements applied to the Player module.

## Successful Pattern — Shared Source-Gated Episode Overlay
- **Achievement**: Implemented a unified episode browser that works for both IPTV and Debrid series.
- **Key Implementation**:
    - **Controller Pattern**: Used `EpisodeBrowserController` to isolate UI logic from `PlayerActivity`.
    - **Source Gating**: Guarded `DPAD_DOWN` with `isSeriesEpisodePlayback()` to prevent breaking Live TV zapping.
    - **Reactive Sync**: Observed `seriesPlaylistState` in `PlayerActivity` to keep the browser updated with the current episode and playlist.
    - **Unified Resolution**: Integrated both direct URL playback (IPTV) and re-resolution logic (Debrid) in the selection handler.

## Successful Pattern — Media3 Lifecycle Hardening
- **Achievement**: Resolved surface detachment race conditions and background playback crashes.
- **Key Changes**:
    - Atomic State Transitions: Stop -> Clear -> New Media -> Prepare -> Play.
    - Lifecycle Guards: Added checks in `onUserInteraction` and `dispatchKeyEvent` for activity destruction.

## Successful Pattern - Overlay Owns Keys While Visible
- **Achievement**: Episode browser consumes LEFT/RIGHT/UP/DOWN/OK/BACK while visible before generic player/controller handling runs.
- **Key Implementation**:
    - `PlayerActivity.dispatchKeyEvent()` hides Media3 controls and delegates to `EpisodeBrowserController.handleKeyEvent(event)`.
    - `onUserInteraction()` keeps the Media3 controller hidden while the episode browser is open.
    - Focus and current playback are rendered as separate visual states.

## Successful Pattern - Continue Watching Preserves Player Metadata
- **Achievement**: IPTV Series Continue Watching can relaunch PlayerActivity with the same playlist-critical metadata as the Series detail path.
- **Key Implementation**:
    - Persist `seriesId` in `ContinueWatchingItem`.
    - Pass `seriesId` back into `PlayerActivity.createIntent()` for IPTV episode resumes.
    - Heal older history rows with a local episode-id lookup before launching the player.
