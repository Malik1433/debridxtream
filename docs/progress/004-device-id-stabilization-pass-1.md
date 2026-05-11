# TASK 004 - Device ID Stabilization Pass 1

Date: 2026-05-10
Device used for verification: Fire TV / Android TV over ADB, `192.168.0.84:5555`
Scope: logout must not delete persistent device identity

## Result

Status: PASS

This pass fixes only the confirmed critical issue from the device code audit: logout no longer clears `device_id` or `sync_code`.

## Files Changed

- `app/src/main/java/com/tvonnet/debridxtreamiptv/data/prefs/CredentialsPreferences.kt`

## Code Change

Before:

```kotlin
fun clearCredentials() {
    prefs.edit().clear().apply()
}
```

After:

```kotlin
fun clearCredentials() {
    prefs.edit().apply {
        remove(KEY_SERVER_URL)
        remove(KEY_USERNAME)
        remove(KEY_PASSWORD)
        remove(KEY_LOGGED_IN)
        apply()
    }
}
```

No credential validation, companion pairing flow, Firestore flow, Login UI, Android ID logic, or uninstall/reinstall behavior was changed.

## Device Verification

### Before Logout

Visible companion setup code before logout:

```text
742775
```

Preference state before logout test:

```xml
<map>
    <string name="password">dummy_pass</string>
    <string name="sync_code">742775</string>
    <string name="server_url">http://example.com</string>
    <string name="username">dummy_user</string>
    <string name="device_id">task004-device-id-001</string>
    <boolean name="logged_in" value="true" />
</map>
```

Note:

- The device already generated `sync_code=742775` through the real companion setup screen.
- `device_id` was seeded to represent an existing installed user because this build has no active UI path that calls `RemotePairingManager.getDeviceId()`.

Evidence:

- `docs/progress/screenshots/004-companion-before-logout.png`
- `docs/progress/004-companion-before-logout.xml`

### After Logout 1

Preference state after logout:

```xml
<map>
    <string name="sync_code">742775</string>
    <string name="device_id">task004-device-id-001</string>
</map>
```

Visible companion setup code after logout:

```text
742775
```

Result:

- `sync_code` survived: YES
- `device_id` survived: YES
- `server_url` cleared: YES
- `username` cleared: YES
- `password` cleared: YES
- `logged_in` cleared: YES

Evidence:

- `docs/progress/screenshots/004-after-logout-1-login.png`
- `docs/progress/004-after-logout-1-login.xml`
- `docs/progress/screenshots/004-companion-after-logout-1.png`
- `docs/progress/004-companion-after-logout-1.xml`

### After Logout 2

Preference state after second login/logout cycle:

```xml
<map>
    <string name="sync_code">742775</string>
    <string name="device_id">task004-device-id-001</string>
</map>
```

Visible companion setup code after second logout:

```text
742775
```

Result:

- `sync_code` survived second logout: YES
- `device_id` survived second logout: YES
- visible device code remained the same: YES

Evidence:

- `docs/progress/screenshots/004-after-logout-2-login.png`
- `docs/progress/004-after-logout-2-login.xml`
- `docs/progress/screenshots/004-companion-after-logout-2.png`
- `docs/progress/004-companion-after-logout-2.xml`

## Build/Test/Install/Launch

### compileDebugKotlin

Command:

```powershell
.\gradlew.bat :app:compileDebugKotlin --no-daemon --console plain
```

Result: PASS

Output:

- `docs/progress/004-compileDebugKotlin-output.txt`

### testDebugUnitTest

First direct run timed out/fell into stale test class failures while run after a parallel Gradle invocation.

Clean rerun command:

```powershell
.\gradlew.bat :app:cleanTestDebugUnitTest :app:testDebugUnitTest --no-daemon --console plain
```

Result: PASS

Output:

- `docs/progress/004-testDebugUnitTest-clean-output.txt`

### assembleDebug

Command:

```powershell
.\gradlew.bat :app:assembleDebug --no-daemon --console plain
```

Result: PASS

Output:

- `docs/progress/004-assembleDebug-output.txt`

### Install

Command:

```powershell
adb -s 192.168.0.84:5555 install -r app/build/outputs/apk/debug/app-debug.apk
```

Result: PASS

Output:

```text
Performing Streamed Install
Success
```

### Launch

Command:

```powershell
adb -s 192.168.0.84:5555 shell am start -n com.debridxtream.tv/com.tvonnet.debridxtreamiptv.ui.MainActivity
```

Result: PASS

Evidence:

- `docs/progress/screenshots/004-launch-after-install.png`
- `docs/progress/004-launch-after-install.xml`

## Match To Project Plan

Does this match project plan: YES

Reason:

- This pass only fixes the confirmed logout identity deletion issue.
- It does not attempt uninstall/reinstall persistence.
- It does not redesign companion pairing.
- It does not add server registration.
- It does not introduce Android ID.
- It does not change visible pairing code behavior.
- It does not touch Login UI or credential validation.

## Remaining For Pass 2

Uninstall/reinstall persistence is still not solved and was intentionally not changed in this pass.
