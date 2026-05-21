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

## Successful Pattern - Source Identity Separate From Resolver Eligibility
- **Achievement**: Direct Stremio/AIOStreams Debrid streams can show Debrid in player controls/history without being sent through app-side Real-Debrid resolver logic.
- **Key Implementation**:
    - Keep `PlaybackSource.DEBRID` for player identity.
    - Use a dedicated direct Debrid playback extra to disable resolver, Debrid playlist load, retry re-resolution, timeout re-resolution, and Debrid next-episode resolver paths.
    - Do not let direct Debrid series fall through to IPTV playlist/API loading.

## Successful Pattern - Direct Debrid Source Continuity
- **Achievement**: Direct Stremio/AIOStreams Debrid episode switching uses the shared episode browser and preserves selected source profile.
- **Key Implementation**:
    - Load direct Debrid series episodes through the existing Debrid/TMDB playlist state.
    - Carry provider, source type/name, language, quality, stream id, Stremio binge group, and file index through player intents and Continue Watching.
    - Rank next-episode candidates by same source family/provider and language before generic cache/quality/seeders fallback.
    - Fresh-resolve direct Debrid Continue Watching entries from stable metadata instead of trusting old direct addon URLs.

## Successful Pattern - State-Aware Series Next Control
- **Achievement**: Added a visible `Next Episode` control to the shared player controller without creating a second player surface.
- **Key Implementation**:
    - Reused the existing `btn_next_episode` in `custom_player_control_view.xml`.
    - Gated visibility, focusability, and click handling on `SeriesPlaylistState.hasNext`.
    - Kept non-series playback free of the control and avoided guessed episode jumps when playlist state is not ready.

## Successful Pattern - Single Runtime Controller File
- **Achievement**: Confirmed the player controller has one runtime XML path and no shadowed source-set duplicate.
- **Key Implementation**:
    - `activity_player.xml` points directly at `custom_player_control_view.xml`.
    - No alternate `custom_player_control_view.xml` or `activity_player.xml` exists under another `app/src` source set.
    - Visual restyles must target the single runtime controller file, not documentation-only design notes.

## Successful Pattern - Direct Audio and Subtitle Track Buttons
- **Achievement**: Restored direct audio and subtitle controls on the shared player controller instead of forcing an intermediate chooser dialog.
- **Key Implementation**:
    - Wired separate runtime buttons to `showAudioSelection()` and `showSubtitleSelection()`.
    - Kept the existing `TrackSelectionDialogBuilder` flows for audio and subtitles.
    - Removed the combined `showPlayerSettings()` chooser so track access stays one tap away.

## Successful Pattern - Separate Debrid Language Source Picker
- **Achievement**: Added a dedicated language button for Debrid playback without reintroducing the old combined chooser.
- **Key Implementation**:
    - Used `debridLanguagesExtra` as the source-language list.
    - For Debrid movies, the selected language refreshes the source profile and re-picks a matching source.
    - Kept audio and subtitle buttons on their direct track-selection flows.

## Successful Pattern - Straight Controller Seek Bar
- **Achievement**: Flattened the player seek control to match the rest of the controller surface while preserving Media3 TimeBar behavior.
- **Key Implementation**:
    - Kept the custom `TimeBar` integration for sync and scrubbing.
    - Removed the animated wave path and rendered a straight played segment instead.
    - Matched the seek bar height and button glass styling to the compact overlay.
