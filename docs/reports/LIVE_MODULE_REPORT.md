# LiveTV Module Report

## 1. Scope
- LiveTV section audit and remediation for `LiveFragment`, `LiveViewModel`, `PreviewPlayerPanel`, `PlayerActivity` live handoff, and live URL construction helpers.
- Goal: reduce state drift, remove hardcoded live playback assumptions, and improve fullscreen handoff stability without touching unrelated modules.

## 2. Files Inspected
- `app/src/main/java/com/tvonnet/debridxtreamiptv/ui/live/LiveFragment.kt`
- `app/src/main/java/com/tvonnet/debridxtreamiptv/ui/live/LiveViewModel.kt`
- `app/src/main/java/com/tvonnet/debridxtreamiptv/ui/live/PreviewPlayerPanel.kt`
- `app/src/main/java/com/tvonnet/debridxtreamiptv/data/model/XtreamModels.kt`
- `app/src/main/java/com/tvonnet/debridxtreamiptv/data/repository/XtreamRepository.kt`
- `app/src/main/java/com/tvonnet/debridxtreamiptv/player/stabilized/PlayerActivity.kt`
- `app/src/main/java/com/tvonnet/debridxtreamiptv/ui/home/HomeFavoriteMapper.kt`
- `app/src/main/java/com/tvonnet/debridxtreamiptv/ui/search/SearchFragment.kt`
- `app/src/test/java/com/tvonnet/debridxtreamiptv/ui/live/LiveViewModelTest.kt`
- `app/src/test/java/com/tvonnet/debridxtreamiptv/repository/XtreamRepositoryStreamLookupTest.kt`

## 3. Audit Findings
- Live channel loading state was split between ViewModel state and Paging3 adapter state.
- `LiveViewModel.loadChannelsForCategory()` set `isLoadingChannels = true` and then cleared it before Paging actually settled.
- Live fullscreen handoff built `liveChannelIds` from the currently loaded adapter snapshot only, which can be incomplete for large categories.
- Multiple live playback callsites hardcoded `.ts` even though the stream model already carries `container_extension`.
- The code already had a cached-live lookup path in `XtreamRepository`; it just was not used for fullscreen channel-id recovery.
- Manual QA later exposed two UX regressions: channel-list focus could bounce back to categories, and fullscreen return could restore stale live history when the user zapped channels in fullscreen.
- Root cause for channel-list focus bounce: the category RIGHT handler requested focus on `rvChannels`, but that RecyclerView is intentionally not focusable; TV focus therefore fell back to the category rail instead of a channel card.
- Follow-up root cause for fast channel-list DOWN bounce: once a channel card had focus, rapid DPAD repeats still flowed through native RecyclerView focus-search. During paging/layout gaps, Android could pick a side candidate outside the channel list, especially the category rail or preview controls.
- Root cause for stale mini-player after fullscreen zapping: `LiveFragment` launched `PlayerActivity` without an activity result and then inferred the final channel from recent-history storage. That storage is asynchronous/lifecycle-dependent and can still point to the original fullscreen channel.
- Root cause for "guide unavailable" / empty EPG drift: `EpgCache` treated cached `(null, null)` responses as fresh hits for five minutes, which blocked late XMLTV syncs and short-EPG retries from repopulating visible guide data.
- Secondary root cause for missing EPG on newly visible channels: the cache only refreshed after a direct bind/request path. Fast paging could expose rows before their EPG had been warmed, so the preview panel could sit on an empty state longer than necessary.
- Root cause for settings-side EPG sync crash: the manual sync button was launching both WorkManager immediate sync and a direct repository sync at the same time, allowing two EPG jobs to race through the same repository/parser/database path.

## 4. Implementation Summary
- Added a shared live URL helper in `XtreamModels.kt`.
- Updated Live, Search, Home favorite mapping, and Player live zapping callsites to use shared live URL construction instead of hardcoded `.ts`.
- Added `LiveEvent.ChannelLoadStateChanged` so adapter load state can drive `isLoadingChannels`.
- Kept `isLoadingChannels = true` on category selection and let Paging load state clear it.
- Added cached live-stream lookup fallback for fullscreen handoff so `liveChannelIds` is more complete.
- Reordered LiveFragment resume focus restoration so global focus memory cannot override Live channel restoration.
- Changed PlayerActivity live history recording so live TV updates the recent-channel entry on every save point instead of freezing after the first fullscreen capture.
- Replaced the focus target for category RIGHT and fullscreen-return restore with a child-channel-card focus helper that scrolls, waits for layout, retries briefly, and only then unblocks category focus.
- Added channel-card-level DPAD UP/DOWN handling so fast repeats advance a pending target adapter position and do not fall back to native focus-search outside the Live channel list.
- Added a LiveTV-only activity-result return contract from `PlayerActivity` to `LiveFragment` carrying the final channel id, title, logo, EPG id, category id, and exact current playback URL.
- Updated `PreviewPlayerPanel` to accept an optional resolved stream URL so mini-player resume can use the same URL that was active in fullscreen instead of rebuilding a stale/default path.
- Changed `EpgCache` so empty/null EPG lookups are no longer treated as a fresh five-minute hit. Positive results are cached; empty results now remain eligible for later refresh.
- Added visible-channel EPG warmup in `LiveFragment` so the first rows in the active channel list preload guide data after Paging settles.
- Simplified the Settings "Sync EPG Now" action to a single repository sync path and added a repository mutex so manual and scheduled EPG syncs cannot overlap and crash the app.

## 5. Validation
- `:app:compileDebugKotlin`: PASS
- `:app:testDebugUnitTest --tests com.tvonnet.debridxtreamiptv.ui.live.LiveViewModelTest --tests com.tvonnet.debridxtreamiptv.repository.XtreamRepositoryStreamLookupTest`: PASS
- `:app:assembleDebug`: PASS
- `:app:compileDebugKotlin --offline --no-daemon --console=plain`: PASS
- `:app:assembleDebug --offline --no-daemon --console=plain`: PASS
- `:app:compileDebugKotlin --offline --no-daemon --console=plain --stacktrace`: PASS after channel-card key handler.
- `:app:assembleDebug --offline --no-daemon --console=plain`: PASS after channel-card key handler.
- `:app:compileDebugKotlin -Dkotlin.compiler.execution.strategy=in-process :app:assembleDebug --offline --no-daemon --console=plain --stacktrace --max-workers=1`: PASS after EPG cache/warmup fix.
- `:app:compileDebugKotlin -Dkotlin.compiler.execution.strategy=in-process`: PASS after manual EPG sync serialization fix.
- `:app:assembleDebug --offline --no-daemon --console=plain --stacktrace --max-workers=1`: PASS after manual EPG sync serialization fix.
- Reinstall after EPG fix on `192.168.0.84:5555`: PASS.
- Reinstall after EPG fix on `192.168.0.21:5555`: PASS.
- Reinstall after manual EPG sync crash fix on `192.168.0.84:5555`: PASS.
- Reinstall after manual EPG sync crash fix on `192.168.0.21:5555`: PASS.
- `clean assembleDebug`: timed out before completion, so plain `assembleDebug` was used as the fallback final build check.
- Fresh install and launch smoke on `192.168.0.84:5555`: PASS
- Reinstall and launch smoke after activity-result/focus fix on `192.168.0.84:5555`: PASS
- Reinstall after fast-DOWN focus fix on `192.168.0.84:5555`: PASS
- ADB Live focus stress on `192.168.0.84:5555`: entered Live channel list from categories, sent 20 rapid DPAD_DOWN events at ~35ms spacing, and UIAutomator showed focus remained on a channel card (`|UEFA| CANAL+ LIVE 3 HD |FR|`) rather than the category rail or preview controls.
- Log scan after clean launch on `192.168.0.84:5555`: no app crash observed.
- Log scan after fast-DOWN focus test on `192.168.0.84:5555`: no `FATAL EXCEPTION` / `AndroidRuntime` crash observed.

## 6. Risks / Warnings
- There is no Android TV instrumented Live smoke suite yet.
- Manual QA is still useful for long-press/held-remote feel because ADB key events approximate repeated DPAD input but do not perfectly match every remote repeat cadence.

## 7. Final Status
PARTIAL
- The Live module now uses shared URL construction, load-state synchronization, cached fullscreen handoff IDs, explicit fullscreen return state, child-card focus restore, deterministic channel-card UP/DOWN handling, negative-cache-safe EPG refresh, and visible-channel EPG warmup.
- Build/install passed on both `192.168.0.84:5555` and `192.168.0.21:5555`; final user-facing PASS still depends on a manual Live EPG playback/guide check with the remote.
