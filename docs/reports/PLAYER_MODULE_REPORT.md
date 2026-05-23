# Player Module Report
Status: verified
Scope: shared PlayerActivity and PlayerViewModel playback path.

Done:
- Series episode overlay is shared instead of duplicated.
- Episode browser and next-episode control are wired through the existing player spine.
- Global player routing remains source-aware.
- Track selection and source-profile handling now live directly in `PlayerActivity`; `PlayerTrackManager` has been removed.
- Track dialogs are lifecycle-managed and dismissed during player teardown.
- IPTV Series playlist loading now rejects stream-URL fallback values as episode ids before opening the episode browser.

Open:
- Keep remote-control edge cases under the player task docs.
- Continue broader Player regression checks before changing more shared-spine code.

Proof:
- `:app:compileDebugKotlin` passed after the track-dialog lifecycle fix.
- 2026-05-23: `:app:compileDebugKotlin` passed after the IPTV episode-id guard.
- 2026-05-23: `:app:assembleDebug` passed.
- 2026-05-23: install, launch, PID check, and crash scan passed on `192.168.0.84:5555`.
- 2026-05-23: install, launch, PID check, PlayerActivity display, and crash scan passed on `192.168.0.21:5555`.
- 2026-05-23: manual QA passed for IPTV Series and Debrid Series episode browser plus Next Episode behavior.

Next:
- Use this file as the compact canonical summary only.
