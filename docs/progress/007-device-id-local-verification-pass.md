# TASK 007 - Device ID Local Verification Pass

## Result

PASS

No app code was changed in this pass.

## Devices Verified

- `192.168.0.21:5555`
- `192.168.0.84:5555`

## Baseline Identity Values

### 192.168.0.21

- visible `sync_code`: `034270`
- `legacy_sync_code`: `034270`
- `device_signal_hash`: `012d8c04b649a223d83e6d2d89e72275e0f0c014a2b0e20bc8fde15bbff77c35`
- `device_signal_hash_version`: `1`
- `install_instance_id`: `e8e39824-74e2-4ced-a25b-f8def00498b8`
- starting session state: logged in

### 192.168.0.84

- visible `sync_code`: `687478`
- `legacy_sync_code`: `687478`
- `device_signal_hash`: `67035f08001953225fdf045968cdaf9e2983c9968c665bc90e7176227bc8d7f8`
- `device_signal_hash_version`: `1`
- `install_instance_id`: `04ff245f-677c-48d5-b57b-c86943af15a0`
- starting session state: not logged in

## Logout Preservation

Logout was verified on `192.168.0.21`, because that device had an active logged-in session.

After logout:

- credentials were cleared
- `logged_in` was removed
- visible `sync_code` remained `034270`
- `legacy_sync_code` remained `034270`
- `device_signal_hash` remained `012d8c04b649a223d83e6d2d89e72275e0f0c014a2b0e20bc8fde15bbff77c35`
- `install_instance_id` remained `e8e39824-74e2-4ced-a25b-f8def00498b8`

Result: PASS

## App Update install -r Preservation

Command used on both devices:

```text
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### 192.168.0.21

After `install -r` and launch:

- visible `sync_code`: unchanged, `034270`
- `legacy_sync_code`: unchanged, `034270`
- `device_signal_hash`: unchanged
- `install_instance_id`: unchanged

Result: PASS

### 192.168.0.84

After `install -r` and launch:

- visible `sync_code`: unchanged, `687478`
- `legacy_sync_code`: unchanged, `687478`
- `device_signal_hash`: unchanged
- `install_instance_id`: unchanged

Result: PASS

## Cross-Device Difference Check

The two devices have different values:

- `sync_code`: `034270` vs `687478`
- `device_signal_hash`: different
- `install_instance_id`: different

Result: PASS

## Raw Android ID Log Check

Raw Android ID values were read only for verification comparison and were not copied into this report.

Logcat checks:

- `192.168.0.21`: exact raw Android ID matches in logcat: `0`
- `192.168.0.84`: exact raw Android ID matches in logcat: `0`

Related text matches were Android framework warnings mentioning the setting name `android_id`; they did not include the raw Android ID value.

Result: PASS

## Commands / Verification Performed

- `adb devices`
- `adb shell run-as com.debridxtream.tv cat .../shared_prefs/iptv_credentials.xml`
- `adb shell run-as com.debridxtream.tv cat .../shared_prefs/identity_prefs.xml`
- `adb shell am force-stop com.debridxtream.tv`
- `adb shell am start -n com.debridxtream.tv/com.tvonnet.debridxtreamiptv.ui.MainActivity --ez OPEN_SETTINGS true`
- logout through real app UI on `192.168.0.21`
- `adb install -r app/build/outputs/apk/debug/app-debug.apk`
- `adb shell logcat -d` with exact raw Android ID match checks

## Notes

- `.21` is now logged out because the logout verification used the real app UI.
- `.84` remained in its existing not-logged-in state.
- No uninstall/reinstall persistence was attempted.
- No backend registration was attempted.
- No companion pairing behavior was changed.

## Next Recommended Step

Return to the remaining Login UI issue: 1080p mobile setup button clipping.
