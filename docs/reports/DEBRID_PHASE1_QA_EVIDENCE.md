# Debrid Phase 1 QA Evidence

## Task
`TASK 001-DEBRID-PHASE1-SECURITY-LOG-REDACTION`

## Date
2026-05-17

## Build Evidence
- Command: `./gradlew.bat :app:assembleDebug --offline --console plain`
- Result: PASS
- Initial Gradle output: `BUILD SUCCESSFUL in 15m 42s`
- Follow-up swarm build result: PASS
- APK: `app/build/outputs/apk/debug/app-debug.apk`
- APK timestamp after swarm follow-up: `2026-05-17 11:43:20`
- APK size: `33,670,619` bytes

## Device Install Evidence
- `192.168.0.21:5555`: `adb install -r app/build/outputs/apk/debug/app-debug.apk` -> `Success`
- `192.168.0.84:5555`: `adb install -r app/build/outputs/apk/debug/app-debug.apk` -> `Success`

## Launch Smoke Evidence
- Package: `com.debridxtream.tv`
- Launcher activity: `com.tvonnet.debridxtreamiptv.ui.MainActivity`
- `192.168.0.21:5555`: `am start -W` returned `Status: ok`, `Activity: com.debridxtream.tv/com.tvonnet.debridxtreamiptv.ui.MainActivity`, `ThisTime: 2957`, PID `30870`.
- `192.168.0.84:5555`: `am start -W` returned `Status: ok`, `Activity: com.debridxtream.tv/com.tvonnet.debridxtreamiptv.ui.MainActivity`, `ThisTime: 4747`, PID `10907`.

## DPAD Smoke Evidence
- Input used on both devices: five `DPAD_DOWN` events and one `DPAD_RIGHT` event.
- `192.168.0.21:5555`: `dumpsys window windows` showed `mCurrentFocus=Window{... com.debridxtream.tv/com.tvonnet.debridxtreamiptv.ui.MainActivity}`.
- `192.168.0.84:5555`: `dumpsys window windows` showed `mCurrentFocus=Window{... com.debridxtream.tv/com.tvonnet.debridxtreamiptv.ui.MainActivity}`.

## Log Scan Evidence
Logcat scan after launch/navigation found no matches for:
- `FATAL EXCEPTION`
- `AndroidRuntime`
- `HttpLoggingInterceptor`
- `realdebrid=`
- `Reading Continue Watching JSON`
- `Reading Recent Live JSON`
- `Mapping URL: Original`

## Swarm Follow-Up Evidence
- Security reviewer found remaining raw/sensitive-ish logs in `PlayerViewModel`, `PlayerActivity`, `RealDebridRemoteDataSource`, and `UnifiedSourceProvider`.
- Follow-up patch redacted infoHash/magnet retry logs, removed playlist state stringification, redacted Real-Debrid filenames, and removed provider release/source title logs.
- Follow-up static scan found no matches for `Sample sources`, `state=$state`, `Converted: $label`, raw `filename=${response.filename}`, or `HttpLoggingInterceptor` in app source.
- Follow-up install/launch smoke after rebuild:
  - `192.168.0.21:5555`: install `Success`, `am start -W` `Status: ok`, `ThisTime: 2967`, PID `11849`, focus retained on `MainActivity`.
  - `192.168.0.84:5555`: install `Success`, `am start -W` `Status: ok`, `ThisTime: 7473`, PID `22877`, focus retained on `MainActivity`.

## Scope Limit
This evidence is build/install/launch/DPAD/log smoke QA. It is not a full functional Real-Debrid playback QA pass because no persisted evidence was captured for selecting a Debrid source, resolving through Real-Debrid, playing video, backing out, and resuming.
