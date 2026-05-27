# Phase 3: PlayerSubtitleAudioManager Refactor Plan

## 1. Current Subtitle/Audio Flow in PlayerActivity
The current subtitle and audio logic handles external subtitle URL parsing, language preferences, track memory (saving the last manually selected track), and displaying ExoPlayer's track selection dialogs.

### Subtitle Injection
- `subtitleEntries` are extracted from the Intent.
- `buildMediaItem()` uses `buildSubtitleConfigurations()` to parse these entries (regex matching URLs and languages).
- `parseSubtitleEntry()`, `extractSubtitleLanguage()`, and `guessSubtitleMimeType()` handle formatting.

### Initialization & Preferences
- `preferredAudioLanguage` and `preferredSubtitleLanguage` are read from `SettingsPreferences`.
- During `initializePlayer()`, a `DefaultTrackSelector` is configured with these preferred languages.
- `isSoftwareAudioEnabled` dictates the `DefaultRenderersFactory` extension mode.

### Track Memory & Overrides
- In `onTracksChanged()`, `applyTrackIndexOverrides()` checks if there is a saved audio/text track index for the current hash/series, and applies a `TrackSelectionOverride` if found.
- `captureManualTrackSelection()` listens for track parameter changes and saves the explicitly chosen indices to `SettingsPreferences`.

### Dialogs & UI
- Click listeners on `btn_player_audio` and `btn_player_subtitles` trigger `showAudioSelection()` and `showSubtitleSelection()`.
- These use ExoPlayer's `TrackSelectionDialogBuilder` to display track lists.
- `showLanguageSelection()` makes an asynchronous call to `viewModel.getDebridLanguageOptions` to fetch and apply default language configurations for Debrid content.

## 2. Extraction Boundary Mapping

### Functions
- `buildSubtitleConfigurations(entries: List<String>)`
- `parseSubtitleEntry()`, `extractSubtitleLanguage()`, `normalizeLanguageCode()`, `guessSubtitleMimeType()`
- `showAudioSelection()`, `showSubtitleSelection()`, `showLanguageSelection()`, `applyDebridLanguagePreference()`
- `applyTrackIndexOverrides()`
- `captureManualTrackSelection()`

### Field Dependencies
- `subtitleEntries: List<String>`
- `preferredSubtitleLanguage`, `preferredAudioLanguage`
- `hasAppliedIndexOverride: Boolean`
- `settingsPreferences`

### Player / UI Dependencies
- **ExoPlayer:** `player.currentTracks`, `player.trackSelectionParameters`, `TrackSelectionDialogBuilder`.
- **Initialization:** Must interact with `DefaultTrackSelector` and `MediaItem.Builder` during `initializePlayer()`.
- **ViewModel:** Uses `viewModel.getDebridLanguageOptions()` for Debrid specific fetching.

## 3. Existing Helper Status
- **PlayerTrackManager.kt:** Does not exist in the current architecture. All logic is embedded directly within `PlayerActivity`.

## 4. Extraction Risk Level
**MEDIUM.**
While the logic is sprawling, it is largely stateless relative to the player's network/playback lifecycle. The primary integration points are tightly defined:
1. Providing `SubtitleConfiguration` list to `MediaItem.Builder`.
2. Configuring `DefaultTrackSelector` before player build.
3. Attaching to `Player.Listener` for `onTracksChanged`.
4. Triggering UI dialogs on button clicks.

Unlike Phase 2 (Network/Stall), extracting track management does not risk infinite player-rebuild loops or critical stream disruption.

## 5. Required Baseline Tests (Before/After Extraction)
1. **External Subtitle Parsing:** Launch a Debrid stream with external subtitle URLs. Verify they appear and render.
2. **Embedded Track Selection:** Play a multi-track MKV. Verify both Audio and Subtitle dialogs populate correctly.
3. **Language Preference Auto-Select:** Set preferred language to French. Play a stream with French tracks. Verify auto-selection.
4. **Track Memory Save/Restore:** Manually select Audio Track 2. Exit and resume the video. Verify Audio Track 2 is auto-selected upon return.
5. **Software Audio Toggle:** Toggle Software Audio setting and verify it applies correctly to the renderer factory.

## 6. Safe Extraction Implementation Strategy (If Approved)
- Create `PlayerTrackManager.kt`.
- Inject `PlayerActivity` context and `SettingsPreferences`.
- Expose `fun buildSubtitleConfigs(intentSubtitles)` to be called during `buildMediaItem`.
- Expose `fun configureTrackSelector(builder: DefaultTrackSelector.Parameters.Builder)` to be called during player initialization.
- Expose `fun attachPlayer(player)` to bind the listener for `onTracksChanged`.
- Move the dialog builder functions (`showAudioSelection`, `showSubtitleSelection`) to the manager.

## 7. Recommendation
**PROCEED LATER (Phase 3+).**
This is a viable, medium-risk cleanup target. It cleanly encapsulates a distinct domain (Track/Subtitle management) and removes ~150 lines of configuration logic from `PlayerActivity`. It should be scheduled for extraction only after user approval.
