# TASK 002B - Login Device QA Gap Closure

Date: 2026-05-10
Device: Fire TV / Android TV over ADB, `192.168.0.21:5555`
Build under test: Login Stabilization Pass 1 debug build
Final result: PARTIAL PASS

No app code changes were made.

## 1. Build Verification

Status: PASS

Command:

```powershell
.\gradlew.bat :app:assembleDebug --no-daemon --console plain
```

Result:

- First sandboxed attempt failed before build because the Gradle wrapper could not download with `java.net.SocketException: Permission denied: getsockopt`.
- Re-run with approved Gradle/network path passed.
- Final Gradle result: `BUILD SUCCESSFUL in 2m 32s`
- Output file: `docs/progress/002b-assembleDebug-output.txt`

Last successful Gradle output:

```text
> Task :app:assembleDebug UP-TO-DATE
Kotlin build report is written to file:///D:/cursor%20working/debxtrem/build/reports/kotlin-build/DebridXtreamIPTV-build-2026-05-10-02-58-02-0.txt
BUILD SUCCESSFUL in 2m 32s
45 actionable tasks: 1 executed, 44 up-to-date
EXIT_CODE=0
```

## 2. URL Validation Retest

Status: PARTIAL PASS

Input was driven with ADB field taps, text input, keyboard dismissal, D-pad/Done, and UI hierarchy capture. ADB text entry was reliable for simple inputs and malformed URL, but remained unreliable for one `https://` submission pass.

| Case | Result | Evidence |
| --- | --- | --- |
| spaces only | PASS: visible `Server URL is required`; no crash | `docs/progress/screenshots/002b-urlfinal-spaces-only.png`, `docs/progress/002b-urlfinal-spaces-only.xml` |
| `example.com` | PASS: accepted as normalized HTTP path and failed cleanly as login failure; no crash | `docs/progress/screenshots/002b-urltap-example-com.png`, `docs/progress/002b-urltap-example-com-logcat.txt` |
| `https://example.com` | PARTIAL: field accepted cleanly and app stayed stable, but ADB submission was inconsistent; no crash observed | `docs/progress/screenshots/002b-urltap-https-example-com.png`, `docs/progress/002b-urltap-https-example-com.xml` |
| malformed `http://` | PASS: visible `Enter a valid server URL`; no crash | `docs/progress/screenshots/002b-urltap-malformed-http.png`, `docs/progress/002b-urltap-malformed-http.xml` |

Observed clean failure for `example.com`:

```text
LoginFragment: Login failed: 404
java.lang.Exception: Login failed: 404
```

Notes:

- Spaces-only validation confirmed that trimmed whitespace is rejected.
- Malformed `http://` confirmed visible URL error.
- `https://example.com` should be retested once with physical remote or instrumentation text setter because ADB input focus mixed text between fields in one retry.

## 3. Companion Invalid Credentials

Status: PASS

Method:

- Opened the actual `CompanionSetupActivity` from the login screen.
- Captured pairing code from the device UI.
- Sent invalid IPTV credentials through the same Firestore `device_codes/{code}` document shape used by the companion flow.
- Waited for the app snapshot listener to receive the payload.

Evidence:

- Pairing screen: `docs/progress/screenshots/002b-companion-setup-code.png`
- After invalid payload: `docs/progress/screenshots/002b-companion-invalid-after-payload.png`
- Logcat: `docs/progress/002b-companion-invalid-logcat.txt`

Observed behavior:

- App returned from companion setup to login.
- `LoginFragment` detected synced credentials:

```text
LoginFragment: Auto-sync credentials detected! Triggering login...
LoginFragment: Login failed: 404
```

- Login fields were populated with the synced invalid credentials.
- App stayed on LoginFragment and did not enter Home.
- Device preferences confirmed `logged_in=false`:

```xml
<boolean name="logged_in" value="false" />
```

Conclusion:

- Invalid companion credentials are saved as synced/pending credentials.
- They validate through the normal LoginFragment route.
- They do not bypass login.

## 4. InitialSync Retry

Status: NOT COMPLETED

Reason:

- This build only routes to `InitialSyncFragment` after a successful manual/auto login.
- Forcing sync failure while using valid credentials requires a controlled network/API failure after login succeeds.
- Device-level network blocking over Wi-Fi ADB risked disconnecting the test device.
- No code changes, fake repositories, debug toggles, or security/network policy edits were allowed.

Code behavior confirmed by inspection only:

- `InitialSyncFragment.startSync()` hides retry and sets `retryButton.isEnabled = false` before sync.
- `showError()` makes retry visible and sets `retryButton.isEnabled = true`.
- `observeSyncProgress()` also disables retry while `SyncState.RUNNING`.

Evidence:

- Source inspected: `app/src/main/java/com/tvonnet/debridxtreamiptv/ui/InitialSyncFragment.kt`

Conclusion:

- Runtime forced-error retry QA remains open.
- This is the main reason the final result is PARTIAL PASS.

## 5. 1080p Clipping Note

Status: PASS as captured issue

No fix was made.

Observed:

- Mobile setup button remains focusable but clipped at the bottom edge on 1920x1080.
- Captured focused bounds: `[1029,966][1755,1080]`

Evidence:

- Screenshot: `docs/progress/screenshots/002b-mobile-setup-clipping-1080p.png`
- UI hierarchy: `docs/progress/002b-mobile-setup-clipping-1080p.xml`

Keep this as a Login UI Pass issue.

## Crash Check

Status: PASS

Across this 002B pass:

- No startup crash.
- No URL validation crash.
- No companion invalid-payload crash.
- No observed app `FATAL EXCEPTION`.

## Remaining Gap

Only one required item remains unclosed:

- Runtime InitialSync retry behavior under a forced sync error with valid credentials.

Recommended safe next step:

- Run this one test with either a controlled test provider account plus network proxy/DNS failure that does not drop ADB, or add a temporary QA-only sync failure switch in a later approved test build.

Do not move to Login Pass 2 until the InitialSync retry device test is completed or explicitly waived.
