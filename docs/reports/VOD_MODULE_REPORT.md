# VOD Module Report
Status: canonical/current
Scope: IPTV VOD/movie browsing, detail, playback, and focus behavior.

## Current Active Runtime Flow
- `VodFragment` lists IPTV movies and opens `MovieDetailFragmentV2` for IPTV details.
- IPTV movie playback builds the provider movie stream URL or uses direct source and launches `PlayerActivity` with `ContentType.MOVIE`.
- Debrid/TMDB movie flows use `MovieDetailActivity` for source selection and resolver-backed playback.

## Important Active Files
- `app/src/main/java/com/tvonnet/debridxtreamiptv/ui/vod/VodFragment.kt` - IPTV VOD screen.
- `app/src/main/java/com/tvonnet/debridxtreamiptv/features/vodv2/ui/MovieDetailFragmentV2.kt` - IPTV movie detail/player launch.
- `app/src/main/java/com/tvonnet/debridxtreamiptv/features/vodv2/ui/MovieDetailViewModelV2.kt` - detail metadata enrichment.
- `app/src/main/java/com/tvonnet/debridxtreamiptv/ui/vod/MovieDetailActivity.kt` - Debrid/TMDB movie details and source picker.

## What Must Not Break
- IPTV VOD detail to Player handoff.
- VOD focus and poster/backdrop display.
- Debrid movie path in `MovieDetailActivity` when shared movie files are touched.
- Sensitive stream URL redaction.

## Known Bugs / Open Issues
- Future VOD regressions should be kept in VOD history or task docs.

## Recent Fixes
- Added debug-only diagnostics hooks to the Debrid/TMDB movie source and playback handoff path. `MovieDetailActivity` records safe source-list, source-selection, readiness, direct/resolver launch, provider identity, and redacted playback fields only when the local marker is enabled.
- Implemented Manual Mark Watched/Unwatched actions for Movies. Movie detail screen and VOD grid long-press now support manual toggle. `manual_state` WATCHED/UNWATCHED is respected. Continue Watching exact removal when marked unwatched was verified. Auto watched detection and read-only badges remain working. Season/series bulk watched actions remain pending. Debrid manual watched support remains pending.
- Implemented Phase 3 read-only watched badges for Movies. Added an `ic_check_circle` watched indicator to IPTV/VOD movie cards (`VodAdapter`) and the Movie Detail V2 screen. The badge correctly observes `watched_state` from `WatchedStateRepository`. Manual "Mark Watched" actions are intentionally excluded. Series UI is untouched.
- Phase 2 Automatic Watched Detection for movies is wired through `PlayerHistoryManager`. The movie threshold logic (<5% ignore, 5%-89% track, ≥90% or ≤5m remaining) is fully covered and verified by a JVM unit test (`PlayerHistoryManagerTest`). Manual adb regression checks confirmed playback/navigation remain stable.
- Added Phase 1 movie watched-state storage foundation via canonical `watched_state` Room table, DAO, repository wrapper, and stable watched identity builder. VOD UI, detail actions, Player handoff, and Continue Watching remain unchanged.
- **VOD Focus Restoration Fix**: Completed focus restoration drop fix. Added lifecycle hooks (`onResume`, `onPause`, `onSaveInstanceState`) in `VodFragment.kt` based on the proven `SeriesFragment` pattern. Focus restore now works reliably after returning from detail/player. Manual QA passed on 192.168.0.21. No other modules (PlayerActivity, Debrid, Live, Series, Continue Watching, or Episode Browser) were touched.
- VOD stability and navigation are tracked through canonical module reports.

## Failed Approaches / Avoid
- Do not bind detail buttons without verifying runtime XML visibility.
- Do not treat Debrid direct HTTP streams as normal IPTV unless source identity requires it.
- Do not edit non-runtime detail/layout copies.


## QA Checklist
- `:app:compileDebugKotlin`
- `:app:assembleDebug`
- Manual QA: IPTV VOD list, detail, play, trailer if touched, Back/D-pad focus, Debrid movie regression if shared file touched.

## Last Verified State
- 2026-05-31: Debrid movie diagnostics path verified on `192.168.0.21:5555`. A real movie detail source picker for `Backrooms` wrote marker-enabled JSONL events for `source_list_loaded`, `source_selected`, resolver-backed `playback_launch`, player prepare, track discovery, first frame, and READY. Export via `adb pull` succeeded and the exported JSONL did not contain raw URLs, magnets, long hashes, token params, Real-Debrid URLs, or secret header values.
- 2026-05-30: Manual Mark Watched/Unwatched feature QA passed. Movie detail and VOD grid long-press successfully toggle state. Badges update correctly. Continue Watching exact removal verified. Auto watched detection and playback remain working. No season/series derived watched UI was added yet. Debrid manual watched support remains pending.
- 2026-05-30: Phase 3 read-only Watched Badges for Movies implemented. Badges added to IPTV/VOD movie cards and MovieDetailV2. Code compiles successfully. Phase 3 Movies read-only watched badge verified on device. Watched movie cards show checkmark. VOD movie detail watched indicator verified on device. Series/Episode watched UI remains pending. Manual watched/unwatched actions remain pending. Debrid movie badge verification remains pending unless explicitly tested.
- 2026-05-29: Fixed PlayerActivity BACK button crash for Debrid Movies that caused navigation to return directly to Home instead of `MovieDetailActivity`. The crash was an `UninitializedPropertyAccessException` in `SourceSelectionBottomSheet` because `showSources` was called via `ActivityResultCallback` before `onViewCreated` finished view inflation. Fix safely defers source updating to `onViewCreated` via `pendingSources`. Manual QA confirmed BACK navigation now correctly resumes the movie source picker flow.
