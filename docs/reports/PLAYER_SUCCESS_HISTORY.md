# Player Success History
Status: compacted
Scope: confirmed player wins.

Done:
- Shared player routing and series episode overlay work are verified.
- Track control logic was consolidated into `PlayerActivity` and verified by build.
- Track dialogs are dismissed on player teardown to avoid leaked windows.
- IPTV Series playlist/browser loading now uses a real episode id only, not a stream URL fallback.
- Missing current episode identity now leaves playlist state unselected instead of silently jumping to episode 0.

Open:
- Append only new confirmed wins.

Proof:
- Build and device smoke are already captured in the linked reports.
- 2026-05-23: `compileDebugKotlin`, `assembleDebug`, and launch/crash scans passed on `192.168.0.84:5555` and `192.168.0.21:5555` for the episode-id guard.
- 2026-05-23: user manual QA confirmed IPTV Series and Debrid Series browser/Next Episode working.
- 2026-05-23: build plus both-device install/launch smoke passed after the playlist fallback guard.

Next:
- Keep this file short and state-based.

### DPAD Navigation & Next Episode Logic
- **Date**: 2026-05-23
- **Change**: Fixed isSeriesEpisodePlayback() to explicitly check ContentType.SERIES instead of relying on Debrid status. This resolved the missing 'Next Episode Prompt' for IPTV.
- **Change**: Added explicit playSeriesEpisode(nextEp) inside playNextEpisode() for IPTV to resume auto-play.
- **Change**: Rewrote DPAD controller overrides in PlayerKeyDispatcher.kt so UP navigates to audio/subtitles instead of bottom controls, and DOWN does not erroneously trigger the episode browser while the controller is visible.
- **Change**: Restored missing initLiveZapping() call in Live TV setup to prevent endless 'Loading channels' toast.

### Episode Browser State Integrity
- **Date**: 2026-05-23
- **Change**: Added seriesId, seasonNum, and sourceType into SeriesPlaylistState in PlayerViewModel.kt to verify episode browser identity. PlayerBrowserManager now compares the loaded list identity with the intent's identity, clearing the Singleton playlist state when navigating between different series to avoid displaying stale episodes.
