# Live Module Report
Status: canonical/current
Scope: Live TV list, preview, focus, fullscreen return, and zapping.

## Current Active Runtime Flow
- `LiveFragment` shows categories and paged channels with a preview player.
- First channel click plays preview; fullscreen launches `PlayerActivity` with Live metadata, category id, ordered channel ids, and base server URL.
- `PlayerActivity` initializes Live zapping through `PlayerViewModel.initLiveZapping()`.
- On Player exit, explicit Live return extras restore preview and focus in `LiveFragment`.

## Important Active Files
- `app/src/main/java/com/tvonnet/debridxtreamiptv/ui/live/LiveFragment.kt` - Live screen, preview, fullscreen launch/result.
- `app/src/main/java/com/tvonnet/debridxtreamiptv/ui/live/LiveViewModel.kt` - category, paging, focus, preview state.
- `app/src/main/java/com/tvonnet/debridxtreamiptv/ui/live/ChannelPagingAdapter.kt` - channel list adapter and item key routing.
- `app/src/main/java/com/tvonnet/debridxtreamiptv/ui/live/PreviewPlayerPanel.kt` - preview playback.
- `app/src/main/java/com/tvonnet/debridxtreamiptv/player/stabilized/PlayerBrowserManager.kt` - fullscreen channel browser and zapping.

## What Must Not Break
- Live `DPAD_DOWN` and channel zapping behavior.
- Shared live URL helper / container extension preservation.
- Explicit fullscreen return contract.
- Paging load-state source of truth.
- Child-targeted focus restore for TV lists.

## Known Bugs / Open Issues
- Future focus/loading regressions should be kept in Live history files.

## Recent Fixes
- Load-state source of truth documented.
- Live URL helper and return contract preserved.
- Player-side Live TV EPG zapping initialization re-enabled.
- Fast Live TV zapping race condition causing video/metadata mismatch was fixed using `zapRequestId` and `streamId` as version tokens.
- Channel list focus no longer returns to the category RecyclerView container. Category/sidebar return paths now target the selected category child item, while channel items keep DPAD_UP/DOWN routing.

## Failed Approaches / Avoid
- Do not hardcode `.ts` stream URLs.
- Do not build fullscreen handoff lists from adapter snapshot only when cached streams exist.
- Do not use watch history as primary fullscreen return signal.
- Do not call `requestFocus()` on non-focusable channel RecyclerView containers.
- Do not make Live RecyclerView containers focusable to solve TV focus issues; keep focus on bound child item views.

## QA Checklist
- `:app:compileDebugKotlin`
- `:app:assembleDebug`
- Manual QA: category selection, channel paging, preview, fullscreen launch, zapping, return preview/focus.

## Last Verified State
- 2026-05-23: `:app:assembleDebug` passed after Live focus fix.
- 2026-05-23: Debug APK installed and `MainActivity` launched on `192.168.0.21:5555` and `192.168.0.84:5555`.
- 2026-05-23: Manual TV D-pad checks still required for category navigation, channel up/down movement, fullscreen return focus, and fast zapping.
