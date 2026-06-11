# Series Module Report
Status: canonical/current
Scope: IPTV Series list/detail, episode identity, player handoff, and episode browser.

## Current Active Runtime Flow
- `SeriesFragment` lists IPTV series and opens `SeriesDetailFragmentV2`.
- `SeriesDetailFragmentV2` launches `PlayerActivity` for IPTV episodes with episode id, series id, season, episode number, and titles.
- Debrid series uses `SeriesDetailActivity` and source selection before Player handoff.
- In-player episode lists are shared through `PlayerBrowserManager`, `EpisodeBrowserController`, and `SeriesPlaylistManager`.

## Important Active Files
- `app/src/main/java/com/tvonnet/debridxtreamiptv/ui/series/SeriesFragment.kt` - IPTV Series screen.
- `app/src/main/java/com/tvonnet/debridxtreamiptv/features/seriesv2/ui/SeriesDetailFragmentV2.kt` - IPTV Series detail/player launch.
- `app/src/main/java/com/tvonnet/debridxtreamiptv/features/seriesv2/ui/SeriesDetailViewModelV2.kt` - detail navigation events.
- `app/src/main/java/com/tvonnet/debridxtreamiptv/ui/series/SeriesDetailActivity.kt` - Debrid series detail/source picker.
- `app/src/main/java/com/tvonnet/debridxtreamiptv/player/stabilized/SeriesPlaylistManager.kt` - episode playlist state.

## What Must Not Break
- `EXTRA_SERIES_ID` on series player launch.
- Episode id must remain distinct from playback stream URL.
- Episode Browser and Next Episode must use playlist state, not guessed episode numbers.
- Existing Series focus/category behavior.

## Known Bugs / Open Issues
- Future playback/focus regressions should be tracked in Series history files.

## Recent Fixes
- Polished watched badge contrast for Series episode cards. Episode watched indicators now share the same high-contrast `bg_watched_badge` style used by VOD movie cards: dark overlay, white check icon, cyan accent outline, fixed 32dp sizing, subtle elevation, and passive non-focusable/non-clickable behavior. Episode watched logic, manual toggle behavior, derived season/series state, playback, and Episode Browser behavior were unchanged.
- Added debug-only diagnostics hooks to the Debrid series episode source/playback path. `SeriesDetailActivity` now records safe source-list, source-selection, direct/resolver playback-launch fields when marker-enabled, without changing series source loading behavior, cache labels, PlayerActivity routing, or source picker UI.
- Debrid series/AIO episode source reliability was tightened in the active series path. `UnifiedSourceProvider.kt` now keeps same-infoHash variants distinct when language, provider/source name, source type, file index, or binge group differ, and `SeriesDetailActivity.kt` no longer silently falls back to episode 1 when source loading metadata is missing. Manual QA on `Spider-Noir` verified episode 1 and episode 3 source lists remain episode-specific (`S01E01` vs `S01E03`), source counts differ (`314` vs `293`), and `DIRECT` labels did not regress. Build and install passed on `192.168.0.21:5555`.
- Series player poster handoff now prefers the portrait series cover over episode thumbnails. IPTV V2 no longer launches the player with a blank poster when `episode.thumbnail` is missing, and legacy/Debrid series no longer prefer landscape episode stills that crop poorly in the controller poster slot.
- Implemented Series-Level Derived Watched State. Utilizes background Flow aggregation combining total episodes and watched counts in `SeriesDetailViewModelV2` and dynamically updates the series header banner in `SeriesDetailFragmentV2`. Handles Watched/In-Progress/Unwatched states with verified zero UI stutter.
- Implemented Season Derived Watched State. Utilizes background SQLite GROUP BY Flow aggregation, combined in SeriesDetailViewModelV2, and efficiently applied via SeasonsAdapterV2 with DiffUtil. Handles Watched/In-Progress/Unwatched states with verified smooth D-pad performance.
- Implemented Manual Mark Watched/Unwatched actions for Series episodes. IPTV/V2 Series episode long-press supports manual toggle. `manual_state` WATCHED/UNWATCHED is respected. Continue Watching exact removal was verified to safely remove only the targeted episode. Auto watched detection and read-only badges remain working. Series-level derived watched UI/actions remain pending. Debrid manual watched support remains pending.
- Fixed Episode Browser manual episode switch watched-state gap in `PlayerActivity`. The previous episode is now correctly marked watched when the user manually switches to another episode using the in-player Episode Browser. Auto Next Episode and manual BACK watched paths remain working.
- Phase 2 Automatic Watched Detection for episodes is wired through `PlayerHistoryManager`. The episode threshold logic (<5% ignore, 5%-89% track, ≥90% or ≤3m remaining) is fully covered and verified by a JVM unit test (`PlayerHistoryManagerTest`). NOTE: Full real-world UI testing of mid-progress episode watched thresholds was not manually verified due to adb remote unreliability. UI badges, manual mark watched/unwatched actions, and derived season/series watched UI states are NOT yet implemented.
- Added Phase 1 episode watched-state storage foundation via canonical `watched_state` Room table, DAO, repository wrapper, and stable watched identity builder. Series detail, Episode Browser, Player handoff, legacy `EpisodeEntity`, and V2 episode storage remain unchanged.
- Shared episode browser and next-episode flow are in place.
- Playlist state no longer falls back to episode 0 when identity is missing.
- IPTV Series playlist loading rejects stream URL fallback as episode id.

## Failed Approaches / Avoid
- Do not repeat skeleton DP margin tuning.
- Do not omit series id or episode identity extras.
- Do not guess continuity from current episode number alone.
- Do not block UI state emission on long-running provider collection.
- Playlist state no longer falls back to episode 0 when identity is missing.
- IPTV Series playlist loading rejects stream URL fallback as episode id.

## Failed Approaches / Avoid
- Do not repeat skeleton DP margin tuning.
- Do not omit series id or episode identity extras.
- Do not guess continuity from current episode number alone.
- Do not block UI state emission on long-running provider collection.

## QA Checklist
- `:app:compileDebugKotlin`
- `:app:assembleDebug`
- Manual QA: IPTV Series detail to Player, Episode Browser, Next Episode, Back/D-pad behavior, Debrid Series regression if touched.

## Last Verified State
- 2026-06-08: Watched badge contrast polish verified on test device `192.168.178.35:5555` after successful install. Series episode watched indicators use the same high-contrast dark/cyan/white badge style as VOD movie cards, remain passive and fixed-size, and no watched-state logic, derived season/series state, playback, Episode Browser, or focus routing behavior was changed. `:app:assembleDebug --no-daemon --console plain` passed after stopping an unresponsive Gradle daemon and rerunning.
- 2026-05-31: Playback Diagnostics Recorder series hooks compiled and installed as part of the debug tooling pass. Build passed (`:app:compileDebugKotlin`, `:app:assembleDebug`) and APK installed on `192.168.0.21:5555`. Runtime smoke verified the shared recorder/player event path and redaction on a Debrid movie source-picker playback; dedicated Debrid series source-selection QA remains pending for the next manual diagnostics session.
- 2026-05-31: Debrid series source reliability fix verified on `192.168.0.21:5555`. `SeriesDetailActivity` now uses the selected episode's actual season/episode metadata for Debrid source loading, and `UnifiedSourceProvider` preserves distinct same-hash variants when episode language/source/file-index metadata differs. `:app:compileDebugKotlin` and `:app:assembleDebug` passed, the APK was installed on the device, and QA on `Spider-Noir` confirmed `E1` and `E3` resolve to different source lists with matching episode-specific labels and unchanged `DIRECT` badges. No player, cache-label, or UI redesign changes were introduced.
- 2026-05-31: Series controller poster handoff fix built and deployed. `SeriesDetailFragmentV2.kt` now prefers the fragment series poster argument over nullable episode thumbnails; `SeriesDetailActivity.kt` now prefers `seriesCover` over landscape episode stills for IPTV legacy and Debrid series launches. `:app:compileDebugKotlin` and `:app:assembleDebug` passed; APK installed on `192.168.0.84:5555` and `192.168.0.21:5555`. Manual IPTV series and Debrid series poster confirmation remains pending.
- 2026-05-30: Series-Level Derived Watched State feature QA passed. The main series banner instantly reactively updates to "Watched" or "In-Progress" when episodes are watched or toggled. Smooth D-pad scrolling and zero UI stutter confirmed.
- 2026-05-30: Season Derived Watched State feature QA passed. Season pills correctly display "Watched" badge for completely watched seasons and "In-Progress" badge for partially watched seasons. Smooth D-pad scrolling confirmed with no main-thread blocks. Debrid manual watched support remains pending.
- 2026-05-30: Manual Mark Watched/Unwatched feature QA passed. IPTV/V2 Series episode long-press successfully toggles state. Badges update correctly. Continue Watching exact removal verified. Auto watched detection, Episode Browser manual switch watched save, and Auto Next Episode watched save still work.
- 2026-05-29: Fixed PlayerActivity BACK button crash for Debrid Series that caused navigation to return directly to Home instead of `SeriesDetailActivity`. The crash was an `UninitializedPropertyAccessException` in `SourceSelectionBottomSheet` because `showSources` was called via `ActivityResultCallback` before `onViewCreated` finished view inflation. Fix safely defers source updating to `onViewCreated` via `pendingSources`. Manual QA confirmed BACK navigation now correctly resumes the episode source picker flow.
