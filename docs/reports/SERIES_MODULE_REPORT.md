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
- Shared episode browser and next-episode flow are in place.
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
- Build and device verification already passed for the current Series flow.
