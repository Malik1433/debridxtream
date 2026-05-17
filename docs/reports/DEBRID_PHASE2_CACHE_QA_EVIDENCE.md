# Debrid Phase 2 Cache QA Evidence

## Scope
Phase 2 Real-Debrid cache confidence implementation and smoke validation.

## Swarm
- `McClintock`: Real-Debrid API/model/repository audit.
- `Noether`: source picker/filter/sorting/cache badge audit.
- `Ohm`: QA/report-risk audit.

## Validation Commands
- `.\gradlew.bat :app:compileDebugKotlin --offline --console plain`
- `.\gradlew.bat :app:assembleDebug --offline --console plain`
- `adb -s 192.168.0.21:5555 install -r app\build\outputs\apk\debug\app-debug.apk`
- `adb -s 192.168.0.84:5555 install -r app\build\outputs\apk\debug\app-debug.apk`
- `adb shell am start -n com.debridxtream.tv/com.tvonnet.debridxtreamiptv.ui.MainActivity`

## Results
- Compile: PASS.
- Assemble: PASS.
- APK: `app-debug.apk`, `33,670,619` bytes, timestamp `2026-05-17 12:21:29`.
- Install: PASS on `192.168.0.21:5555`.
- Install: PASS on `192.168.0.84:5555`.
- Launch smoke: PASS on both devices.
- Crash scan: no `FATAL EXCEPTION`, `AndroidRuntime`, or app-process crash marker found after launch smoke.
- Package version on both devices: `versionName=2.0.7`, `versionCode=28`.
- App focus after launch:
  - `192.168.0.21:5555`: `com.debridxtream.tv/com.tvonnet.debridxtreamiptv.ui.MainActivity`.
  - `192.168.0.84:5555`: `com.debridxtream.tv/com.tvonnet.debridxtreamiptv.ui.MainActivity`.
- App-process-only sensitive log scan: no hits for Real-Debrid token URL, magnet, unrestrict endpoint, access token, refresh token, full history JSON marker, or direct source URL marker after launch smoke on either device.

## Manual QA Runbook
Full manual checklist:
- `docs/reports/DEBRID_PHASE2_MANUAL_QA_RUNBOOK.md`

## Manual QA Still Required
Functional Debrid QA was not completed in this automated pass. Required manual flow:
- Confirm Real-Debrid auth state.
- Open Debrid movie sources and verify cache badges/filtering.
- Select a verified source and confirm Real-Debrid resolution plus playback start.
- Repeat on a Debrid series episode.
- Verify BACK behavior and resume re-resolution.
- Scan logcat after source selection/playback for token, magnet, info hash/title pairing, unrestricted URL, and full history JSON leakage.

## Status
PARTIAL  Build/install/launch smoke passed; full user-facing Debrid behavior requires manual QA.
