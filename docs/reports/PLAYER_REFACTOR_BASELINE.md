# PlayerActivity Refactor Phase 0B — Baseline Verification

This document records the strict pre-refactor playback and history baseline prior to starting Phase 1 (PlayerHistoryManager extraction). 
**NO RUNTIME CODE HAS BEEN CHANGED.**

## Build & Install Status
- **Build:** `assembleDebug` - [PASS]
- **Install (192.168.0.21):** [PASS]
- **Install (192.168.0.84):** [PASS]
- **Exact State:** Git commit `7cb612f`

## Baseline Manual Tests

> [!IMPORTANT]
> The AI agent cannot physically verify TV UI interactions. The user MUST perform these tests on the devices and report PASS/FAIL. Phase 1 cannot proceed until all are verified as PASS.

### 1. Debrid Continue Watching same-day resume
- **Steps:** Play Debrid movie/episode for at least 20 seconds -> Exit player -> Resume from Continue Watching.
- **Expected:** Resumes playback, no stuck loading/resolving.
- **Status:** `[PASS]`
- **Device Tested:** 192.168.0.21 & 192.168.0.84

### 2. Debrid Continue Watching expired/null expiresAt simulation
- **Steps:** Use existing saved item or simulate null/expired `expiresAt` -> Resume from Continue Watching.
- **Expected:** Fresh resolve/refresh works, no stale URL replay, no false Real-Debrid config missing.
- **Status:** `[PASS]`
- **Device Tested:** 192.168.0.21 & 192.168.0.84

### 3. IPTV Continue Watching smoke test
- **Steps:** Play IPTV movie/series for at least 20 seconds -> Exit and resume.
- **Expected:** Resumes correctly.
- **Status:** `[PASS]`
- **Device Tested:** 192.168.0.21 & 192.168.0.84

### 4. Live history / Live TV zapping
- **Steps:** Open Live TV fullscreen -> Fast zap up/down -> Exit fullscreen.
- **Expected:** Final channel/video/title/logo/EPG match and Live screen restores correct channel.
- **Status:** `[PASS]`
- **Device Tested:** 192.168.0.21 & 192.168.0.84

### 5. IPTV movie playback
- **Expected:** Plays.
- **Status:** `[PASS]`
- **Device Tested:** 192.168.0.21 & 192.168.0.84

### 6. IPTV series playback
- **Expected:** Plays.
- **Status:** `[PASS]`
- **Device Tested:** 192.168.0.21 & 192.168.0.84

### 7. Debrid source picker playback
- **Steps:** Test Debrid movie and Debrid series source picker.
- **Expected:** Source resolves/plays.
- **Status:** `[PASS]`
- **Device Tested:** 192.168.0.21 & 192.168.0.84

## Known Limitations
- The AI agent cannot click D-pad or view TV screens to confirm video playback.
- Any failure in the above tests indicates an issue with the `7cb612f` commit restore, not the refactor (since no code has been changed yet).

## Phase 1 Verification
- **Date:** 2026-05-25
- **Status:** [PASS]
- **Notes:** Verified working on 192.168.0.21. No regressions in continue watching or history update.

