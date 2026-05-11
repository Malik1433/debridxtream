# Stabilization Phase Report - 2026-05-09

## Status

Result: PARTIAL PASS

Unit/build blockers are cleared. UI/performance code mitigations are in place. A fresh Fire TV install-and-device QA pass is still required before adding new UI features.

## Root Causes

1. DebridResumeEngineTest failures
   - Root cause: tests still expected a 23-hour Real-Debrid expiry, while production resolver behavior intentionally uses a 4-hour TTL to avoid serving dead links.
   - Fix: updated test assertions/comments to the 4-hour contract. Production resolver behavior was not changed.

2. Settings focus/theme switching
   - Root cause: the settings detail list could refresh after the Live TV Layout dialog without restoring focus to the row that opened it.
   - Fix: detail rows now report focus key, Settings restores the Live TV Layout row after dialog dismissal/state update, and invalid saved theme values fall back to default.

3. 1080p clipping
   - Root cause: Home shell sidebar used negative translation during collapsed animation, and Premium Live TV had too little bottom safe area for focused bottom cards.
   - Fix: removed avoidable negative sidebar translations and increased Premium Live TV bottom safe padding.

4. Fire TV lag
   - Root cause: Premium channel cards used heavier glint/scale focus effects and aggressive logo prefetching during list movement.
   - Fix: Premium channel cards use lightweight focus scale, reduced premium logo prefetch distance, and preview playback starts after a 300 ms settle delay with a lightweight placeholder first.

5. Default theme regression risk
   - Root cause: Premium layout selection needed strict fallback so bad preference values could not route Default Live TV into premium layouts.
   - Fix: Live TV theme preference is sanitized to `default` unless it is exactly `premium`.

## Files Changed In This Stabilization Pass

- `app/src/test/java/com/tvonnet/debridxtreamiptv/data/debrid/repository/DebridResumeEngineTest.kt`
- `app/src/main/java/com/tvonnet/debridxtreamiptv/data/prefs/SettingsPreferences.kt`
- `app/src/main/java/com/tvonnet/debridxtreamiptv/ui/settings/SettingsViewModel.kt`
- `app/src/main/java/com/tvonnet/debridxtreamiptv/ui/settings/SettingsFragment.kt`
- `app/src/main/java/com/tvonnet/debridxtreamiptv/ui/settings/adapters/SettingsDetailAdapter.kt`
- `app/src/main/java/com/tvonnet/debridxtreamiptv/ui/live/ChannelPagingAdapter.kt`
- `app/src/main/java/com/tvonnet/debridxtreamiptv/ui/live/LiveFragment.kt`
- `app/src/main/java/com/tvonnet/debridxtreamiptv/ui/live/PreviewPlayerPanel.kt`
- `app/src/main/java/com/tvonnet/debridxtreamiptv/util/FocusEffects.kt`
- `app/src/main/res/layout/cin_view_sidebar.xml`
- `app/src/main/res/layout/fragment_live_premium.xml`
- `app/src/main/res/layout/item_channel_premium.xml`

## Before / After QA

Before:
- `DebridResumeEngineTest`: FAIL, 5 failures around expected 23-hour `expiresAt`.
- Fire TV Premium Live TV: FAIL/RISK, skipped-frame lag observed.
- 1080p: FAIL/RISK, `nav_search` inverted bounds and bottom premium card clipping observed.
- Settings Live TV Layout selector: RISK, focus could be lost after switching.

After:
- `DebridResumeEngineTest`: PASS, 20 tests, 0 failures, 0 errors.
- Full debug unit suite: PASS, 196 tests, 0 failures, 0 errors, 0 skipped.
- Kotlin compile: PASS.
- Fire TV lag: CODE MITIGATED, device re-test still required.
- 1080p clipping: CODE MITIGATED, device screenshot re-test still required.
- Settings theme focus: CODE MITIGATED, device D-pad re-test still required.
- Default Live TV route: CODE MITIGATED by sanitized fallback; device regression re-test still required.

## Build And Test Results

- `:app:testDebugUnitTest --tests "com.tvonnet.debridxtreamiptv.data.debrid.repository.DebridResumeEngineTest"`: PASS
  - Report: 20 tests, 0 failures, 0 errors.
- `:app:compileDebugKotlin --no-daemon`: PASS
- `:app:testDebugUnitTest --no-daemon`: PASS
  - Report: 196 tests, 0 failures, 0 errors, 0 skipped.

## Crash Logs / Focus / Clipping / Lag

- Crash logs: no new unit/build crash. Device logcat was not re-collected after this patch set.
- Focus dead zones: Settings focus-loss path was patched; needs Fire TV D-pad confirmation.
- UI clipping on 1080p: Home sidebar and Premium Live TV bottom-safe-area fixes applied; needs screenshot confirmation.
- Fire TV lag: heavy Premium card focus effect reduced, preview delayed 300 ms, logo prefetch reduced; needs Fire TV scrolling confirmation.

## Gate

Do not add new UI features yet. Next step is a patched APK install on Fire TV and a focused device QA pass for Settings, Live TV navigation/playback, 1080p clipping, and scrolling performance.
