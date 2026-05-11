# TASK 013B  Home Focus Device QA Verification

## 1. Summary
Home-specific QA was completed on the tester Fire TV device at `192.168.0.21:5555`.

The app reached authenticated Home state. Startup focus, content-to-sidebar left-boundary behavior, vertical row movement, hero button routing, Back behavior, rapid D-pad stability, and crash review were acceptable in this pass.

One HIGH issue remains: DPAD_RIGHT from the sidebar enters Home content, but it does not reliably restore the last remembered content card. It returned to the hero button after leaving the first movie card, and later returned to the movies row after leaving the first series card.

## 2. Device Used
- Device ID: `192.168.0.21:5555`
- Manufacturer: `Amazon`
- Model: `AFTMM`
- Android / Fire OS base version: `7.1.2`
- App package: `com.debridxtream.tv`
- Activity launched: `com.debridxtream.tv/com.tvonnet.debridxtreamiptv.ui.MainActivity`

## 3. Build/Install/Launch Result
| Step | Command | Result | Notes |
|------|---------|--------|-------|
| Device check | `adb devices` | PASS | `192.168.0.21:5555` and `192.168.0.84:5555` were connected; tester device `192.168.0.21:5555` was used. |
| Build | `.\gradlew.bat assembleDebug --no-daemon --console plain` | PASS | Build completed with `BUILD SUCCESSFUL`. |
| Install | `adb -s 192.168.0.21:5555 install -r app\build\outputs\apk\debug\app-debug.apk` | PASS | Install returned `Success`. |
| Launch via monkey | `adb -s 192.168.0.21:5555 shell monkey -p com.debridxtream.tv 1` | PARTIAL | Returned nonzero with Fire TV `SYS_KEYS` message. |
| Explicit launch | `adb -s 192.168.0.21:5555 shell am start -n com.debridxtream.tv/com.tvonnet.debridxtreamiptv.ui.MainActivity` | PASS | MainActivity started successfully. |

## 4. Authenticated Home State
Home was reached successfully. The first captured hierarchy showed the Home sidebar, hero area, and Trending Movies row. Focus was visible on the first Trending Movies card:

`com.debridxtream.tv:id/container`, bounds `[208,620][577,1080]`, inside `rv_top_10_movies`.

No login credential entry was required on this tester device.

## 5. QA Checklist Results
| Area | Result | Evidence | Notes |
|------|--------|----------|-------|
| Startup focus | PASS | `docs/reports/task013b_start.xml`, `docs/reports/task013b_start.png` | Home opened with visible focus on the first Trending Movies card. Focus was not lost after initial render. |
| DPAD_RIGHT sidebar to content | FAIL | `docs/reports/task013b_right_from_sidebar.xml`, `docs/reports/task013b_right_from_sidebar_after_series.xml` | From Home sidebar after first movie focus, DPAD_RIGHT focused `btn_hero_watch` instead of the remembered movie card. After first series focus, DPAD_RIGHT returned to the movies row instead of the remembered series card. |
| DPAD_LEFT content to sidebar | PASS | `docs/reports/task013b_left_from_first_movie.xml`, `docs/reports/task013b_left_from_first_series.xml` | First movie and first series card DPAD_LEFT returned to the sidebar Home item. |
| DPAD_UP/DOWN vertical movement | PASS | `docs/reports/task013b_down_from_hero.xml`, `docs/reports/task013b_down_from_movie.xml`, `docs/reports/task013b_up_from_series.xml`, `docs/reports/task013b_up_from_movie.xml`, `docs/reports/task013b_down_from_series_boundary.xml` | Hero down moved to movies, movie down moved to series, series up moved to movies, movie up moved to hero, and series down did not lose focus. |
| Sidebar active/focused state | PASS | `docs/reports/task013b_sidebar_focus_other.xml` | Moving focus from Home to another sidebar item did not move the active selection indicator; Home remained active while Home screen was displayed. |
| Hero buttons | PASS | `docs/reports/task013b_hero_play_click.xml`, `docs/reports/task013b_hero_more_click.xml` | PLAY NOW and MORE INFO both routed to the existing MovieDetailActivity surface. They were not dead focus traps. |
| Back behavior | PASS | `docs/reports/task013b_back_from_detail.xml` | Back from MovieDetailActivity returned to Home with focus on `btn_hero_watch`. |
| Rapid D-pad stability | PASS | `docs/reports/task013b_after_rapid_dpad.xml`, `docs/reports/task013b_after_rapid_dpad.png` | Rapid mixed D-pad input did not crash and did not leave Home without a focused element. |
| Top-reset behavior | PARTIAL | `docs/reports/task013b_right_from_sidebar_after_series.xml` | No full Home reload was observed, but sidebar-to-content restoration returned to the movies row after series focus, which is a content-position reset from the user's last row. |
| Crash/logcat review | PASS | Logcat reviewed with `FATAL EXCEPTION`, `AndroidRuntime`, `NullPointerException`, `IllegalStateException`, and `ANR in com.debridxtream.tv` filters. | No app crash signatures were found during the QA window. Fire TV/Alexa package warnings and ActivityManager duplicate-finish warnings were present but did not crash Home. |

## 6. Bugs Found
| ID | Severity | Behavior | Steps | Expected | Actual | Evidence | Recommended Next Action |
|----|----------|----------|-------|----------|--------|----------|-------------------------|
| HOME-QA-013B-001 | HIGH | DPAD_RIGHT from sidebar does not reliably restore the last remembered Home content focus. | Start on first movie card, press DPAD_LEFT to sidebar, then DPAD_RIGHT. Later focus first series card, press DPAD_LEFT to sidebar, then DPAD_RIGHT. | Focus returns to the last remembered content card/row when valid. | First case returned to hero `PLAY NOW`; second case returned to the movies row instead of the series row. | `task013b_right_from_sidebar.xml`, `task013b_right_from_sidebar_after_series.xml` | Create TASK 013C to fix sidebar-to-content focus restoration priority and row identity handling. |

## 7. Evidence Files
- `docs/reports/task013b_start.xml`
- `docs/reports/task013b_start.png`
- `docs/reports/task013b_left_from_first_movie.xml`
- `docs/reports/task013b_left_from_first_movie.png`
- `docs/reports/task013b_right_from_sidebar.xml`
- `docs/reports/task013b_down_from_hero.xml`
- `docs/reports/task013b_second_movie.xml`
- `docs/reports/task013b_left_from_second_movie.xml`
- `docs/reports/task013b_down_from_movie.xml`
- `docs/reports/task013b_up_from_series.xml`
- `docs/reports/task013b_up_from_movie.xml`
- `docs/reports/task013b_down_from_series_boundary.xml`
- `docs/reports/task013b_left_from_first_series.xml`
- `docs/reports/task013b_right_from_sidebar_after_series.xml`
- `docs/reports/task013b_left_from_second_series.xml`
- `docs/reports/task013b_sidebar_focus_other.xml`
- `docs/reports/task013b_right_from_other_sidebar.xml`
- `docs/reports/task013b_hero_play_click.xml`
- `docs/reports/task013b_hero_more_click.xml`
- `docs/reports/task013b_back_from_detail.xml`
- `docs/reports/task013b_after_rapid_dpad.xml`
- `docs/reports/task013b_after_rapid_dpad.png`

## 8. Decision
TASK 013 needs Fix Pass 2 before TASK 014.

## 9. Recommended Next Task
TASK 013C  Home Focus Fix Pass 2

