# TASK 002 - Login Device QA Pass

Date: 2026-05-10
Device: Fire TV / Android TV over ADB, `192.168.0.21:5555`
Build under test: existing Login Stabilization Pass 1 debug APK, `app/build/outputs/apk/debug/app-debug.apk`

## Summary

Overall result: PARTIAL PASS

The Login Pass 1 build launches cleanly, starts on the login screen after fresh app data, gives initial focus to the server field, supports the expected D-pad path, submits from password Done, rejects blank server input with a visible error, fails invalid manual login without entering Home, and did not crash on search/voice key input.

Remaining QA gaps are companion invalid-credential validation and forced InitialSync retry error handling. Those require a companion payload sender or a controlled valid-login/sync-failure setup, which was not available in this device pass.

## Build, Install, Launch

Status: PARTIAL PASS

- `assembleDebug`: attempted during QA, but the Gradle run timed out after 15 minutes. No app code was changed in this task.
- Install: PASS. Existing Pass 1 APK installed with `adb install -r`.
- Launch: PASS. `MainActivity` launched with `adb shell am start`.
- Fresh data reset: PASS. `pm clear com.debridxtream.tv` returned `Success`.

Evidence:

- Screenshot: `docs/progress/screenshots/002-clean-login-start.png`
- UI hierarchy: `docs/progress/002-clean-login-start.xml`
- Logcat: `docs/progress/002-login-device-qa-logcat.txt`

## 1. App Launch

Status: PASS

- Fresh launch after `pm clear` opened the login screen.
- No startup crash was seen in logcat.
- Server field received initial focus:
  - `com.debridxtream.tv:id/et_server_url`
  - bounds `[1040,314][1744,423]`
  - `focused="true"`
- D-pad focus was visible on the server field.

Screenshot:

- `docs/progress/screenshots/002-clean-login-start.png`

## 2. D-pad Navigation

Status: PASS with layout note

Observed focus path:

| Step | Focused View |
| --- | --- |
| Start | `et_server_url` |
| DPAD_DOWN 1 | `et_username` |
| DPAD_DOWN 2 | `et_password` |
| DPAD_DOWN 3 | `btn_login` |
| DPAD_DOWN 4 | `btn_setup_phone` |
| DPAD_UP 1 | `btn_login` |
| DPAD_UP 2 | `et_password` |

Screenshots:

- `docs/progress/screenshots/002-dpad-start.png`
- `docs/progress/screenshots/002-dpad-down1.png`
- `docs/progress/screenshots/002-dpad-down2.png`
- `docs/progress/screenshots/002-dpad-down3.png`
- `docs/progress/screenshots/002-dpad-down4.png`
- `docs/progress/screenshots/002-dpad-up1.png`
- `docs/progress/screenshots/002-dpad-up2.png`

Focus note:

- No dead zone was observed in the main login focus path.
- The mobile setup button is clipped at the bottom of 1080p layout. It remains focusable, but its bounds end at the screen bottom: `[1029,966][1755,1080]`.

## 3. URL Validation

Status: PARTIAL PASS

Test results:

| Input | Result |
| --- | --- |
| blank | PASS: visible `Server URL is required` error, app stayed on login |
| spaces | NOT EXECUTED: ADB input did not reliably preserve spaces for this field |
| `example.com` | PARTIAL: field accepted input without crash; this pass did not produce a reliable login attempt due ADB input/focus timing |
| `http://example.com` | PASS: input was accepted and login attempted; repository failed cleanly instead of crashing |
| `https://example.com` | NOT EXECUTED in this pass |
| malformed `http://` | PARTIAL/FAIL: app stayed on login and did not crash, but no visible malformed-URL error was exposed in hierarchy |

Evidence:

- Blank error screenshot: `docs/progress/screenshots/002-url-blank-error.png`
- HTTP example screenshot: `docs/progress/screenshots/002-url2-http-example.png`
- Malformed screenshot: `docs/progress/screenshots/002-url2-malformed.png`

Regression risk:

- Malformed URL rejection needs one more focused manual pass with a physical remote or UIAutomator text setter. Current evidence confirms no crash, not that the visible error is correct.

## 4. Login State Protection

Status: PASS

Test:

- Entered dummy server/user/password.
- Pressed login three times rapidly.
- App produced one observed repository failure and returned to the sign-in state.
- No Home navigation occurred.

Observed result:

- `LoginFragment: Login failed: 404`
- UI returned to `SIGN IN`.

Screenshot:

- `docs/progress/screenshots/002-invalid-login-result.png`

Note:

- The backend returned `404` for this dummy test run. Earlier device logs from the same provider showed `401`; both are handled as login failure and did not bypass authentication.

## 5. Companion Flow

Status: PARTIAL PASS

Observed:

- Device previously had pending/synced credentials in app prefs with `logged_in=false`.
- Relaunch promoted the account to authenticated state and entered the app only after the normal credential path ran.
- Screenshot captured after pending credential relaunch: `docs/progress/screenshots/002-pending-creds-relaunch.png`

Not fully verified:

- Invalid companion credentials were not injected through the real companion transport in this pass.
- No separate companion sender was available, so invalid companion bypass testing remains open.

Risk:

- The valid pending-credential path appears functional, but invalid companion payloads still need direct device-companion testing.

## 6. Voice/Search Crash

Status: PASS

Test:

- Pressed `KEYCODE_SEARCH`.
- Pressed `KEYCODE_VOICE_ASSIST`.

Observed:

- No `FATAL EXCEPTION` or `AndroidRuntime` crash for the app.
- Fire TV routed search/voice to system search/voice components.
- App did not crash while on login.

Screenshot:

- `docs/progress/screenshots/002-search-voice-login.png`

## 7. Initial Sync Retry

Status: NOT EXECUTED

Reason:

- A controlled valid account plus forced sync error was not available during this device pass.
- The QA session did not proceed into Login Pass 2 or modify code/data paths to fake sync state.

Required follow-up:

- Use a known valid account and temporarily force a sync endpoint failure or network block.
- Verify retry button disables while sync is active and re-enables only on error.

## Password Done

Status: PASS

Test:

- Entered dummy login fields.
- Kept keyboard active on password.
- Sent Enter/Done action.

Observed:

- Login submitted from password field.
- Invalid account failed cleanly with `Login failed: 404`.
- No crash.

Screenshot:

- `docs/progress/screenshots/002-password-done-submit.png`

## Crash Logs

Status: PASS

Observed:

- No app startup crash.
- No crash from blank validation.
- No crash from invalid login.
- No crash from password Done.
- No crash from search/voice keys.

Log file:

- `docs/progress/002-login-device-qa-logcat.txt`

Known log entries:

- `LoginFragment: Login failed: 404` for dummy invalid login.
- No matching `FATAL EXCEPTION` for `com.debridxtream.tv` in captured QA log.

## Invalid Login Result

Status: PASS

- Invalid dummy credentials failed.
- App remained on login.
- Login button returned to enabled/sign-in state.
- No Home navigation occurred.

Evidence:

- `docs/progress/screenshots/002-invalid-login-result.png`
- `docs/progress/002-invalid-login-result.xml`

## Valid Login Result

Status: PARTIAL PASS

- A pending credential relaunch entered the authenticated app state.
- Credentials are intentionally not recorded in this report.
- A fresh manual valid-account login was not run in this pass because no separate safe test account was provided.

Evidence:

- `docs/progress/screenshots/002-pending-creds-relaunch.png`
- `docs/progress/002-window-pending-creds-relaunch.xml`

## Regression Risks

- Mobile setup button clipping remains visible at 1080p and should be fixed in a later UI-safe pass.
- Malformed URL visible-error behavior needs a cleaner manual retest.
- Companion invalid payload testing is still open.
- InitialSync retry-disable behavior is still open.
- Gradle `assembleDebug` timed out during this QA attempt, although the existing Pass 1 APK installed and launched successfully.

## Final Status

Do not move to Login Pass 2 yet.

Required before closing Login Device QA:

1. Run companion invalid-credential test through the real pairing path.
2. Run InitialSync forced-error retry test.
3. Re-run malformed and `https://example.com` URL tests with reliable text entry.
4. Re-run `assembleDebug` or confirm the prior Pass 1 assembled artifact is acceptable for this QA record.
