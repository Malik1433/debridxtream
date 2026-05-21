# Player Failed Attempts

This document tracks unsuccessful approaches in the player module. **Do not repeat these patterns.**

## Risk Pattern — Global Player Key Changes
- **Warning**: Do not change `DPAD_DOWN`, `OK`, `LEFT`, or `RIGHT` globally in `PlayerActivity`. 
- **Reason**: `PlayerActivity` is shared by Live TV, VOD, and Series. Global changes break specialized navigation (e.g., Live Zapping).

## Risk Pattern — Duplicate Episode Systems
- **Warning**: Do not create separate IPTV and Debrid episode overlay systems. 
- **Reason**: Maintenance overhead and inconsistent UX. Use one shared overlay with source-specific data providers.

## TASK 029 — Endless Loading Regression
- **Failure**: Episode browser opened but stayed on spinner for IPTV series.
- **Root Cause**: `EXTRA_SERIES_ID` was omitted in `PlayerActivity.createIntent` for IPTV sources, and `PlayerViewModel` lacked a network fallback fetch when local database episodes were empty.
- **Lesson**: Always verify that all required metadata (ID, Type, Season) is passed through the Intent when launching shared activities.

## Failed Pattern  Blocking Flow Collect Before UI State Emit
`loadSeriesPlaylist()` blocked on provider/repository collection before emitting UI state. This caused the episode browser to wait forever and made debugging look like a UI/key issue.

## Risk Pattern - Browser Keys Leaking To Media3 Controller
If the episode browser is visible but LEFT/RIGHT/OK/BACK are allowed to continue into generic player handling, the Media3 controller can open behind the browser or seek unexpectedly. Browser-visible keys must be consumed by `EpisodeBrowserController` first.

## Failed Pattern - Exposing Next Without Playlist State
Do not expose a series `Next Episode` control as an always-active action or guess the next episode by incrementing the current number when playlist state has not confirmed `hasNext`. The control must stay tied to `SeriesPlaylistState` so it does not advertise a dead or wrong action.

## Failed Pattern - Continue Watching Without Series Metadata
Launching an IPTV episode from Continue Watching with only the episode id is not enough for player overlays. Episode browser and next episode need the parent `seriesId`, season number, episode number, and title metadata just like the Series detail launch path.

## Failed Pattern - Using IPTV Identity To Bypass Debrid Resolver
Direct Debrid/Stremio streams must not be launched with null/IPTV playback source just to avoid app-side Real-Debrid re-resolution. That makes player controls and Continue Watching show IPTV and can route direct Debrid series into IPTV playlist/API loading. Keep Debrid identity and gate resolver eligibility separately.

## Failed Pattern - Direct Debrid Without Playlist/Profile Metadata
Direct Debrid/Stremio playback cannot skip both Debrid playlist loading and IPTV playlist loading, or the shared episode browser will show unavailable. It also cannot rely only on the current infoHash/direct URL for next episode and resume. Use the existing Debrid/TMDB playlist state and carry the selected source profile so browser selection, Next, auto-next, and Continue Watching can fresh-resolve a matching provider/language source.

## Failed Pattern - Assuming a Duplicate Player Layout Exists
Do not keep chasing a second `custom_player_control_view.xml` or `activity_player.xml` when the repo has only one runtime player controller path.
Reason: This codebase uses a single `PlayerActivity` host and a single controller layout in `app/src/main/res/layout`. Audit the actual runtime file before restyling or explaining why a change is not visible.

## Failed Pattern - Hiding Track Selection Behind a Combined Chooser
Do not collapse audio and subtitle access into a single player settings dialog when the user needs direct track control.
Reason: The chooser adds one more click path and hides the track-specific actions the controller already knows how to handle directly.

## Failed Pattern - Treating Track Lists as Source Language Lists
Do not assume the audio/text track dialog is the same thing as the Debrid source language picker.
Reason: Track dialogs only expose the current media's available tracks; the source language picker must come from Debrid source metadata and can re-pick a different source.

## Failed Pattern - Leaving Decorative Wave Motion In the Seek Bar
Do not keep the animated wave path in the player progress bar when the rest of the controller is using a flat glass style.
Reason: The wave draws attention away from the controls and breaks visual consistency on TV.
