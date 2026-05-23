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
