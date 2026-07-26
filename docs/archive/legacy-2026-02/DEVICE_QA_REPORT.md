# Device QA Report - Fire TV

Date: 2026-05-09
Device: Fire TV at 192.168.0.21:5555
APK: app/build/outputs/apk/debug/app-debug.apk
Install result: PASS - adb install -r returned Success

## Build / Install

- PASS: `.\gradlew.bat :app:assembleDebug --no-daemon`
- PASS: APK installed on Fire TV with `adb install -r`
- PASS: App launched into `MainActivity`

## 1. Settings

FAIL

- Open Settings > Visuals: PASS
- Live TV Layout row visible: PASS
- Current persisted value shown: PASS - `Premium Cinematic Samsung Tizen`
- Switch Default -> Premium: NOT VERIFIED
- Switch Premium -> Default: NOT VERIFIED
- Focus returns to Live TV Layout row: FAIL
- Restart app and confirm selected theme persists: PARTIAL - Premium persisted from prior run, but switch flow could not be completed in this pass.

Notes:
- D-pad RIGHT from the Visuals category did not enter the details pane.
- Focus stayed on the left Visuals category, so the Live TV Layout selector could not be opened by D-pad.
- Screenshot/XML evidence:
  - `qa_device_visuals.png`
  - `qa_device_visuals.xml`
  - `qa_device_layout_dialog.png`
  - `qa_device_layout_dialog.xml`

## 2. Premium Live TV

PARTIAL FAIL

- Open Live TV with Premium theme: PASS
- Categories load: PASS
- Favorites category visible: PASS
- Empty EPG fallback does not crash: PASS - `Guide info unavailable` displayed.
- Sidebar -> channel grid: PASS
- Channel grid -> preview panel: PASS
- Preview panel -> channel grid: PASS
- DPAD_LEFT / RIGHT / UP / DOWN: PARTIAL PASS - focus moved safely in tested paths.
- Back button behavior: PARTIAL - Back from Settings returned safely to Home; full Live TV back flow not considered passing because other blockers remain.
- Focus dead zones: FAIL in Settings; no Live TV dead zone observed during tested Live TV paths.

Notes:
- Live TV focus moved across sidebar, grid, and preview favorite control.
- After fast navigation, focus remained valid and app stayed in `MainActivity`.
- Screenshot/XML evidence:
  - `qa_device_live_attempt.png`
  - `qa_device_live_attempt.xml`
  - `qa_device_live_grid.png`
  - `qa_device_live_grid.xml`
  - `qa_device_live_preview.png`
  - `qa_device_live_preview.xml`
  - `qa_device_after_scroll.png`
  - `qa_device_after_scroll.xml`

## 3. Playback

FAIL

- Press OK on premium channel card: PASS
- Existing `PlayerActivity` opens: FAIL
- IPTV stream plays in `PlayerActivity`: NOT VERIFIED
- Back from `PlayerActivity` restores Live TV focus: NOT VERIFIED

Notes:
- Pressing OK on a focused premium channel card updated the preview panel but did not open `PlayerActivity`.
- Pressing OK again still stayed in `MainActivity`.
- Activity check after OK:
  - `mResumedActivity: com.debridxtream.tv/com.tvonnet.debridxtreamiptv.ui.MainActivity`
- Screenshot/XML evidence:
  - `qa_device_after_ok.xml`

## 4. 1080p Clipping

FAIL

- Home sidebar screenshot captured: PASS
- Premium Live TV top captured: PASS
- Premium Live TV bottom channel cards captured: PASS
- No clipped or inverted bounds: FAIL

Findings:
- Home sidebar still has inverted `nav_search` bounds:
  - `nav_search` bounds `[36,994][564,916]`
  - top value is greater than bottom value.
- Home divider also has inverted bounds:
  - `[72,952][528,916]`
- Premium Live TV channel list shows clipped/inverted child bounds while scrolling:
  - example logo container bounds `[752,343][888,310]`
- Premium bottom channel cards are clipped at the bottom edge:
  - bottom card content ends at `1008`, with badge/text compressed near the bottom.

Screenshot/XML evidence:
- `qa_device_current.png`
- `qa_device_current.xml`
- `qa_device_after_settings_back.png`
- `qa_device_after_settings_back.xml`
- `qa_device_live_bottom_cards.png`
- `qa_device_live_dpad_after.xml`
- `qa_device_after_scroll.xml`

## 5. Performance

PARTIAL PASS

- Scroll channels/categories quickly for about 60 seconds: PASS
- App crash during scroll: PASS - no crash observed.
- Severe skipped-frame warnings: PASS - filtered logcat had 0 matches for `Skipped` / `Choreographer`.
- Preview starts only after focus settles: NOT FULLY VERIFIED from logs.
- Major lag on Fire TV: PASS by logcat; no severe skipped-frame warnings captured.

Logcat:
- `qa_device_scroll_logcat_notes.txt`
- Filtered match count: 0 for `FATAL EXCEPTION`, `AndroidRuntime`, `ANR`, `Skipped`, `Choreographer`, `PlayerActivity`, `LiveFragment`, `ExoPlayer`, `Preview`, `BufferQueue`.

## 6. Regression / Default Theme

NOT FULLY VERIFIED ON DEVICE

- Default Live TV unchanged: NOT VERIFIED in this device pass because Settings selector focus blocked theme switching.
- Default theme does not use premium item layouts: NOT VERIFIED on device.
- Default playback route intact: NOT VERIFIED on device.
- No crash if premium layout missing optional view: NOT VERIFIED on device.
- No crash after playlist refresh: NOT VERIFIED on device.

## Overall Result

FAIL

Blocking failures:
- Settings Visuals focus does not enter the Live TV Layout selector by D-pad.
- Home 1080p sidebar still has inverted/clipped `nav_search` bounds.
- Premium Live TV still has clipping/inverted bounds during scroll.
- OK on premium channel card does not open `PlayerActivity`.

No crash logs were captured during this pass.
