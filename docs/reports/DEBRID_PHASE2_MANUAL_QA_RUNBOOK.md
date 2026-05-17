# Debrid Phase 2 Manual QA Runbook

## Purpose
Verify Real-Debrid cache confidence behavior after Phase 2 on both required devices.

## Devices
- `192.168.0.21:5555`
- `192.168.0.84:5555`

## Setup Commands
Use the Android SDK `adb.exe` path if `adb` is not on PATH:

```powershell
$adb = "C:\Users\Malik\AppData\Local\Android\Sdk\platform-tools\adb.exe"
& $adb devices
```

Start clean log capture before each device run:

```powershell
$device = "192.168.0.21:5555"
& $adb -s $device logcat -c
& $adb -s $device shell am force-stop com.debridxtream.tv
& $adb -s $device shell am start -n com.debridxtream.tv/com.tvonnet.debridxtreamiptv.ui.MainActivity
```

Repeat with:

```powershell
$device = "192.168.0.84:5555"
```

## Manual Flow Per Device
1. Confirm app opens on MainActivity without crash.
2. Navigate to Debrid.
3. Confirm Real-Debrid auth state.
4. Open a Debrid movie detail screen.
5. Open source picker.
6. Confirm cache badges:
   - `VERIFIED` means Real-Debrid instant availability confirmed cached.
   - `DIRECT` means direct playable stream/readiness path.
   - `UNKNOWN` means cache could not be verified.
   - `UNCACHED` means Real-Debrid or readiness check reported not cached.
7. Turn on cached-only filter.
8. Confirm cached-only list shows `VERIFIED` sources only.
9. Select a `VERIFIED` source.
10. Confirm Real-Debrid resolution completes and playback starts.
11. Press BACK and confirm expected return behavior.
12. Reopen the same item from Continue Watching and confirm resume re-resolves instead of trusting an old unrestricted URL.
13. Repeat source picker and playback flow for one Debrid series episode.

## Post-Run Log Checks
After manual playback, run this per device:

```powershell
$device = "192.168.0.21:5555"
$appPid = (& $adb -s $device shell pidof com.debridxtream.tv).Trim()
& $adb -s $device logcat -d --pid $appPid -t 5000 |
  Select-String -Pattern "FATAL EXCEPTION|AndroidRuntime|realdebrid=|magnet:\?xt=|unrestrict/link|access_token|refresh_token|historyJson|direct_source=http|http.*download"
```

Expected result: no app-process hits.

Repeat with:

```powershell
$device = "192.168.0.84:5555"
```

## Pass Criteria
- Both devices open app without crash.
- Real-Debrid auth state is valid or sign-in flow completes.
- Movie source picker displays correct cache confidence badges.
- Cached-only filter shows only `VERIFIED` sources.
- `VERIFIED` movie source resolves and starts playback.
- Debrid series episode source resolves and starts playback.
- BACK behavior returns cleanly without breaking player/source picker state.
- Continue Watching resume performs fresh Debrid resolution.
- App-process log scan has no crash or sensitive-string hits.

## Status
Pending manual execution.
