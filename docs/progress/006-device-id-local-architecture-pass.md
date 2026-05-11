# TASK 006 - Device ID Local Architecture Pass

## Result

PASS

This pass prepares a separate local identity preference store without changing companion pairing behavior, Firestore behavior, login/session behavior, Android backup settings, or backend registration.

## Files Changed

- `app/src/main/java/com/tvonnet/debridxtreamiptv/data/prefs/IdentityPreferences.kt`
- `app/src/main/java/com/tvonnet/debridxtreamiptv/data/prefs/CredentialsPreferences.kt`
- `app/src/test/java/com/tvonnet/debridxtreamiptv/data/prefs/IdentityPreferencesTest.kt`

## Behavior Before

- Persistent identity values were stored only in `iptv_credentials`.
- `device_id` was generated directly by `CredentialsPreferences.getDeviceId()`.
- `sync_code` was stored only as `sync_code`.
- There was no separate identity preference file.
- No privacy-safe Android device signal hash existed.

## Behavior After

- New `identity_prefs` store exists.
- New local identity fields are supported:
  - `install_instance_id`
  - `legacy_sync_code`
  - `device_signal_hash`
  - `device_signal_hash_version`
- Existing legacy `device_id` is copied to `install_instance_id` when present.
- Existing legacy `sync_code` is copied to `legacy_sync_code` when present.
- Old keys are not deleted.
- If no legacy `device_id` exists, `install_instance_id` is generated locally.
- `CredentialsPreferences.getDeviceId()` still returns the old `device_id` when present.
- `CredentialsPreferences.getSyncCode()` still returns the old visible `sync_code` first.
- Companion visible code behavior is unchanged.
- Raw Android ID is not stored or logged.

## Device Verification

Connected devices:

- `192.168.0.21:5555`
- `192.168.0.84:5555`

Before update install:

- `192.168.0.21`: existing visible `sync_code=034270`
- `192.168.0.84`: existing visible `sync_code=687478`

After `adb install -r` and launch:

- `192.168.0.21`
  - `legacy_sync_code=034270`
  - `install_instance_id` created
  - `device_signal_hash` stored as a 64-character SHA-256 hash
  - `device_signal_hash_version=1`
  - old `sync_code=034270` still present
- `192.168.0.84`
  - `legacy_sync_code=687478`
  - `install_instance_id` created
  - `device_signal_hash` stored as a 64-character SHA-256 hash
  - `device_signal_hash_version=1`
  - old `sync_code=687478` still present

Launch result:

- `192.168.0.21`: `MainActivity` resumed after explicit `am start`
- `192.168.0.84`: `MainActivity` resumed after explicit `am start`

Note: `monkey` launch on `192.168.0.21` returned `SYS_KEYS has no physical keys`; explicit `am start` worked.

## Logout Preservation

Logout/session clearing still removes only:

- `server_url`
- `username`
- `password`
- `logged_in`

It does not remove:

- old `sync_code`
- old `device_id`
- new `identity_prefs`
- `install_instance_id`
- `legacy_sync_code`
- `device_signal_hash`
- `device_signal_hash_version`

Covered by unit test: `clearCredentialsDoesNotRemoveDeviceIdentityValues`.

## App Update / install -r Preservation

`adb install -r` was run on both connected devices.

Result:

- existing visible `sync_code` survived
- new `identity_prefs` survived after launch
- no app-data wipe occurred
- app launched successfully

## Raw Android ID Handling

- Android ID is read only through `Settings.Secure.ANDROID_ID`.
- The raw value is immediately combined with an app-local salt and hashed with SHA-256.
- Only `device_signal_hash` and `device_signal_hash_version` are stored.
- No raw Android ID is logged.
- No raw Android ID is exposed to UI, companion setup, Firestore, or network code in this pass.

## Tests Added

`IdentityPreferencesTest` covers:

- legacy `device_id` and `sync_code` migration without deleting old keys
- sync-code mirroring into `legacy_sync_code`
- creation of `install_instance_id` when no old `device_id` exists
- logout preserving identity values
- storing only a hash for the device signal

## Build / Test Results

- `.\gradlew.bat :app:compileDebugKotlin --no-daemon --console plain`: PASS
- `.\gradlew.bat :app:testDebugUnitTest --no-daemon --console plain`: PASS
- `.\gradlew.bat :app:assembleDebug --no-daemon --console plain`: PASS
- install on `192.168.0.21:5555`: PASS
- install on `192.168.0.84:5555`: PASS
- launch on `192.168.0.21:5555`: PASS via `am start`
- launch on `192.168.0.84:5555`: PASS via `am start`

## What Was Not Changed

- No backend registration added.
- No Firestore flow changed.
- No companion UI changed.
- No visible pairing/sync code behavior changed.
- No Android backup behavior changed.
- No raw Android ID exposed.
- No login/session behavior changed.
- No uninstall/reinstall persistence attempted.

## Remaining Work

Pass 2 should design and implement server-backed reinstall persistence. This pass only creates the local architecture needed for that later step.
