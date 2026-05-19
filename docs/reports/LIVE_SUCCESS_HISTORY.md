# LiveTV Success History

## Current Wins
- Shared live URL helper now centralizes stream URL construction from `container_extension`.
- Live paging load state now updates ViewModel loading state instead of clearing early.
- Fullscreen handoff now prefers cached live stream IDs when available.
- Live search and favorite mapping now use shared live URL construction.
- `:app:compileDebugKotlin`, targeted Live unit tests, and `:app:assembleDebug` all passed after the Live change set.
- Fresh install and launch smoke passed on `192.168.0.84:5555`.
- Live resume now records the current live channel on every save point, so zapped fullscreen channels can be restored more accurately.
- Live resume no longer lets global focus memory override the channel-list restore path during fullscreen return.
- Live fullscreen now returns the final zapped channel through an Activity Result payload instead of relying on recent-history timing.
- Live channel focus restore now targets the actual channel item view, with short layout retries, instead of requesting focus on the non-focusable RecyclerView container.
- Mini-player resume can reuse the exact fullscreen playback URL returned by `PlayerActivity`.
- Live channel cards now own DPAD UP/DOWN during focus, using a pending adapter-position target so fast repeated DOWN stays inside the channel list.
- ADB stress on `192.168.0.84:5555` kept focus on a channel card after 20 rapid DPAD_DOWN events.
- EPG cache now ignores empty/null responses as fresh hits, so late XMLTV syncs and short-EPG retries can repopulate guide data.
- Live channel list warmup now preloads the first visible channel EPG rows after Paging settles.
- Manual EPG sync now uses a single repository path instead of firing WorkManager immediate sync and direct sync together.
- `XtreamRepository.fetchAndSaveEpg()` now serializes overlapping sync calls with a mutex, so scheduled and manual EPG refreshes cannot race each other.
- `:app:assembleDebug` passed after the EPG cache/warmup fix, and the APK was reinstalled successfully on both `192.168.0.84:5555` and `192.168.0.21:5555`.

## Keep Reusing
- Use cached live streams for category-level recovery when you need the full list.
- Use Paging3 load state as the source of truth for in-flight channel loading.
- Use the live model helper for URL construction rather than retyping playback paths.
- Use explicit Activity Result contracts for cross-Activity UI state handoff; do not use watch history as an IPC mechanism.
- For TV lists, move focus to a focusable child view, not the RecyclerView container.
- For paged Android TV lists, attach boundary/navigation key handling to the focused item view, not only to the RecyclerView parent.
- Warm visible Live EPG rows after Paging settles instead of waiting for the first focus bind to request guide data.
