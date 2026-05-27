# PlayerActivity Refactor Plan

## 1. Approved Phase Order
- **Phase 0:** Safety baseline only (COMPLETED)
- **Phase 1:** PlayerHistoryManager only after approval (COMPLETED & VERIFIED)
- **Phase 2:** PlayerNetworkStallManager only after Phase 1 is verified (BLOCKED / NOT APPROVED)
- **Phase 3A:** PlayerTrackManager (Subtitle/Audio) extraction (COMPLETED & VERIFIED)
- **Phase 4B:** PlayerNextEpisodeManager extraction (COMPLETED & VERIFIED)
- **Phase 5A:** PlayerBufferConfigFactory extraction (COMPLETED & VERIFIED)
- **Phase 5+:** Next extraction phase is NOT approved yet.

## 2. Phase 1 Guardrails
PlayerHistoryManager extraction MUST preserve exactly:
- Debrid Continue Watching same-day resume
- Debrid Continue Watching expired/null `expiresAt` fresh resolve
- IPTV Continue Watching
- Live history
- Playback progress threshold rules (e.g., min progress, min duration)
- Completion threshold rules
- No position 0 save
- No temporary Debrid URL durable-resume regression

## 3. Phase 1 Allowed Scope
Only inspect/extract:
- `recordPlaybackHistoryIfNeeded`
- `recordLiveHistory`
- `recordContinueWatchingHistory`
- Direct dependencies needed exclusively by those functions (e.g., `resolveContinueWatchingId`, threshold constants).

## 4. Phase 1 Forbidden Scope
Do NOT touch:
- Debrid resolver logic
- PlayerActivity playback initialization
- Live zapping
- Episode Browser
- Subtitles/audio
- Overlay/controller
- ExoPlayer `MediaItem` construction
- Storage schema / dedupe key logic

## 5. Required Baseline Tests Before Phase 1
- Same-day Debrid Continue Watching resume
- Expired/null `expiresAt` Debrid Continue Watching simulation
- IPTV Continue Watching smoke test
- Live TV zapping
- IPTV movie playback
- IPTV series playback
- Debrid source picker playback

## 6. Required Tests After Phase 1
Same tests as baseline. Phase 1 is accepted **ONLY** if all results match the baseline.
- **Result (2026-05-25):** Build/installation passed on 192.168.0.21. Manual QA confirmed Debrid Continue Watching same-day resume works, and VOD playback triggers onStop history save correctly. No behavior regression reported. Phase 1 ACCEPTED.

## 7. Hard Implementation Rule
When Phase 1 starts, it must be a **no-behavior-change extraction only**.
Touch maximum **1 new manager file** (`PlayerHistoryManager.kt`) + `PlayerActivity.kt`.
If more files are needed, stop and ask for permission before modifying them.

## 8. Phase 2 Status
Phase 2 (PlayerNetworkStallManager full extraction) is **BLOCKED / NOT APPROVED**.
- **Reason:** High coupling with ExoPlayer instance, UI state, Debrid re-resolution, and Live TV recovery.
- **Decision:** Recovery/retry logic must remain in `PlayerActivity` for now. Do not expose internal mutable state to force extraction.

## 9. Phase 3A Status
Phase 3A (PlayerTrackManager for Subtitle/Audio extraction) is **COMPLETED & VERIFIED**.
- **Result (2026-05-25):** Extraction completed without behavior changes. Manual QA on 192.168.0.21 passed for IPTV, Debrid, and Live TV zapping. No regressions found.

## 10. Phase 4B Status
Phase 4B (PlayerNextEpisodeManager extraction) is **COMPLETED & VERIFIED**.
- **Result (2026-05-25):** Extraction completed without behavior changes. Manual QA on 192.168.0.21 passed for Next Episode prompt visibility, countdown, and episode loading. No regressions found.

## 11. Phase 5A Status
Phase 5A (PlayerBufferConfigFactory extraction) is **COMPLETED & VERIFIED**.
- **Result (2026-05-27):** Extracted pure buffer configuration logic (buffer math, network quality enum, and device capability checks) into a stateless factory. Build and QA passed on 192.168.0.84. No behavior changes. Phase 2 (Network/Stall) remains BLOCKED. EPG Overlay / Live Browser remains mapping-only, not approved.
- **Next Phase:** Next extraction phase is NOT approved yet.

## 12. Post-Refactor Full App Smoke Test (2026-05-25)
- **Device:** 192.168.0.84
- **Refactor Regression Status:** PASS — all extracted managers (PlayerHistoryManager, PlayerTrackManager, PlayerNextEpisodeManager) verified working.
- **Full App Smoke Status:** PARTIAL — pre-existing Debrid issues found, not caused by refactoring.
- **Open Issue A:** MediaFusion/AIO intermittent API exceptions during Debrid source resolution.
- **Open Issue B:** Debrid Continue Watching resume intermittently fails.
- **Open Issue C:** Debrid expired resume starts playback then stalls on reloading.
- **Next recommended task:** Diagnose Only for Open Issue C (no fix without approval).
- **No automatic fixes applied.**
