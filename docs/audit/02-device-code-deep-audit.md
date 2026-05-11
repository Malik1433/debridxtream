# Device Code Deep Audit

## Scope

Audit target: device ID, sync code, companion pairing code, and pairing-related persistence.

Business rule:

One physical device should retain one persistent device code across app reinstall, logout/login, app update, and credential changes, unless there is a factory reset, Android ID change, or explicit admin reset.

No app code was changed for this audit.

## Files Inspected

- `app/src/main/java/com/tvonnet/debridxtreamiptv/data/prefs/CredentialsPreferences.kt`
- `app/src/main/java/com/tvonnet/debridxtreamiptv/ui/companion/CompanionSetupActivity.kt`
- `app/src/main/java/com/tvonnet/debridxtreamiptv/network/RemotePairingManager.kt`
- `app/src/main/java/com/tvonnet/debridxtreamiptv/network/CompanionConfigServer.kt`
- `app/src/main/java/com/tvonnet/debridxtreamiptv/ui/settings/SettingsFragment.kt`
- `app/src/main/AndroidManifest.xml`
- `app/src/main/res/layout/activity_companion_setup.xml`
- `app/src/main/res/values/strings.xml`

## Current Device Identity Flow

There are currently two separate pairing-code concepts.

### 1. Companion setup sync code

Used by `CompanionSetupActivity`.

Flow:

1. `CompanionSetupActivity.onCreate()` calls `getPersistentCode()`.
2. `getPersistentCode()` reads `CredentialsPreferences.getSyncCode()`.
3. If no code exists, it generates a random 6-digit numeric code using `Random()`.
4. It stores that code with `CredentialsPreferences.saveSyncCode(code)`.
5. The code is shown in `tv_device_code`.
6. The QR URL is built from `companion_setup_url` plus `?code=<deviceCode>`.
7. The activity writes/listens to Firestore document `device_codes/{deviceCode}`.
8. On completion, it saves incoming IPTV credentials with `saveSyncedCredentials(...)`.

Current storage:

```kotlin
const val PREFS_NAME = "iptv_credentials"
const val KEY_SYNC_CODE = "sync_code"
```

### 2. RemotePairingManager pairing code

Used by `RemotePairingManager`, but no active caller was found in the inspected code.

Flow:

1. `generatePairingCode()` calls `credentialsPreferences.getDeviceId()`.
2. `getDeviceId()` creates a UUID with `UUID.randomUUID()` on first access.
3. `generatePairingCode()` hashes that UUID with Kotlin `hashCode()`.
4. It renders the last 6 digits as a pairing code.
5. Polling uses hardcoded relay URL `https://relay.debridxtream.com/api`.

Current storage:

```kotlin
const val KEY_DEVICE_ID = "device_id"
```

## Reinstall Behavior

Current result: FAIL against the business rule.

The manifest has:

```xml
android:allowBackup="false"
```

Implications:

- App update or `adb install -r`: app data normally remains, so `sync_code` and `device_id` remain.
- Logout/login: current logout calls `CredentialsPreferences.clearCredentials()`, which clears the whole `iptv_credentials` file. This deletes `server_url`, username, password, `logged_in`, `sync_code`, and `device_id`.
- Credential changes: normal credential save does not clear IDs, but logout does.
- `pm clear`: deletes app data, so both codes are lost.
- Uninstall/reinstall: app data is removed and backup restore is disabled, so both codes are lost.
- Factory reset: app data is lost, expected by the business rule.

Current implementation does not use `Settings.Secure.ANDROID_ID`, hardware-backed keys, server-side device claim records, or Android backup restore for device identity.

## Direct Answers

Is device code random?

- Companion setup code: yes, random 6-digit numeric code.
- Remote pairing code: derived from a random UUID stored in app prefs.

Is it tied to app storage only?

- Yes. Both `sync_code` and `device_id` live in app `SharedPreferences`.

Does uninstall create a new code?

- Yes. Because data is removed and `allowBackup=false`, uninstall/reinstall creates a new sync code/device ID.

Is it stable per hardware?

- No. It is stable only while app private data remains intact.

Is it privacy-safe?

- Partially. A random app-scoped UUID is privacy safer than raw hardware identifiers, but the 6-digit public code is low entropy and reused. It should be treated as a pairing code, not a long-term subscription identity.

Is it secure enough for subscription/client systems?

- No. A 6-digit random or UUID-hash-derived code stored only in app prefs is not enough for subscription/device entitlement control.

## Critical Flaws

### Critical: logout deletes device identity

File: `CredentialsPreferences.kt`

Function:

```kotlin
fun clearCredentials() {
    prefs.edit().clear().apply()
}
```

Caller:

```kotlin
SettingsFragment.performAccountLogout()
```

Current behavior:

- Logout clears the entire `iptv_credentials` SharedPreferences file.

Problem:

- This deletes `sync_code` and `device_id` along with credentials.

Risk:

- The same physical device can appear as a new device after logout/login.
- Subscription systems cannot reliably enforce one device per customer.

Safe recommendation:

- Split identity storage from credential storage.
- Logout should clear credentials and auth/session state only, not persistent device identity.

What NOT to touch:

- Do not change credential keys yet unless a migration is approved.
- Do not delete existing `sync_code` or `device_id` during migration.

### Critical: uninstall/reinstall creates a new identity

File: `AndroidManifest.xml`

Current behavior:

- `android:allowBackup="false"` disables backup restore.
- Device identity is only in app-private prefs.

Problem:

- Android removes app-private data on uninstall.
- Reinstall starts with no `sync_code` or `device_id`.

Risk:

- Reinstall bypasses device binding.
- Customer support and subscription systems see duplicate devices.

Safe recommendation:

- Use server-side device registration keyed by an app-generated install key plus a privacy-safe device signal such as app-scoped Android ID.
- For reinstall continuity, use an approved restore channel or server-side re-claim flow.

What NOT to touch:

- Do not enable blanket Android backup for credentials.
- Do not back up passwords or tokens.

### High: companion sync code and RemotePairingManager code are separate systems

Files:

- `CompanionSetupActivity.kt`
- `RemotePairingManager.kt`

Current behavior:

- Companion UI uses random `sync_code`.
- `RemotePairingManager` uses UUID-derived hash code.
- No active caller for `RemotePairingManager` was found.

Problem:

- There is no single authoritative device identity.
- A user can see one code while another manager computes another.

Risk:

- Pairing bugs, inconsistent support records, and future entitlement drift.

Safe recommendation:

- Define one canonical `device_identity_id` and one separate short-lived `pairing_code`.
- Make all pairing paths consume the same identity service.

What NOT to touch:

- Do not remove unused managers until call sites and planned companion architecture are confirmed.

### High: 6-digit code is reused as a persistent pairing handle

File: `CompanionSetupActivity.kt`

Current behavior:

- A 6-digit code is generated once and reused from `sync_code`.
- Firestore document path is `device_codes/{deviceCode}`.

Problem:

- 6 digits has only 1,000,000 combinations.
- Persistent reuse increases collision and guessing risk.

Risk:

- Accidental collision between devices.
- Unauthorized credential injection if Firestore rules or companion validation are weak.

Safe recommendation:

- Use a short-lived pairing code for UI entry.
- Bind pairing code server-side to a high-entropy persistent device ID.
- Expire pairing codes quickly and rotate after successful pairing.

What NOT to touch:

- Do not use the 6-digit code as the subscription identity.

### High: `UUID.randomUUID()` identity is not hardware-stable

File: `CredentialsPreferences.kt`

Function:

```kotlin
fun getDeviceId(): String
```

Current behavior:

- Generates `UUID.randomUUID()` on first access.
- Stores it in app prefs.

Problem:

- This is an install-scoped ID, not a device-scoped ID.

Risk:

- Reinstall, data clear, or logout can create a new ID.

Safe recommendation:

- Keep random UUID as an app instance ID only.
- Combine with server-side registration and a privacy-safe device fingerprint signal.

What NOT to touch:

- Do not replace it directly with raw serial, MAC, IMEI, or other restricted hardware identifiers.

### Medium: CompanionConfigServer is present but not wired

File: `CompanionConfigServer.kt`

Current behavior:

- Defines a local Ktor server on port `8085`.
- Saves credentials with `saveSyncedCredentials`.
- Has validation logic, but `saveConfiguration()` does not call it.
- No `start()` call was found.

Problem:

- It appears dead or future code.
- Its behavior may drift from the Firestore companion path.

Risk:

- Future developers may assume it protects pairing when it does not currently run.

Safe recommendation:

- Either formally wire it into the identity architecture or mark it as unused.

What NOT to touch:

- Do not rely on this server for current production pairing unless startup and network reachability are confirmed.

## Recommended World-Class Persistent Device Identity Architecture

Use three separate concepts:

1. Persistent device identity
2. Short-lived pairing code
3. Credentials/session state

Recommended model:

- `device_identity_id`: high-entropy UUID generated once and stored in an identity-specific store.
- `install_instance_id`: random UUID for analytics/debug, allowed to change on reinstall.
- `pairing_code`: 6-8 character short-lived code, server-generated or server-registered, expires in minutes.
- `device_claim`: server-side record binding customer/subscription to device identity.
- `credentials`: separate storage, removable on logout.

Storage recommendation:

- Store identity separately from credentials, for example `device_identity` preferences.
- Never clear identity on logout.
- Never store credentials in the same clear-all container as identity.
- Do not enable full backup for credential prefs.

Reinstall strategy:

- Android does not provide a perfect privacy-safe hardware ID that survives all reinstalls without tradeoffs.
- For subscription-grade persistence, use a server-side re-claim flow:
  - compute an app-scoped device signal from `Settings.Secure.ANDROID_ID`;
  - register first install with backend;
  - on reinstall, backend can identify likely same device if Android ID is stable for the signing key/user/device;
  - require account/admin confirmation for ambiguous matches.

Security recommendation:

- Pairing code should be temporary and single-use.
- Firestore/relay documents should require:
  - expiration timestamp;
  - one-time completion;
  - device identity binding;
  - rate limiting;
  - authenticated companion write path or signed pairing payload.

Privacy recommendation:

- Avoid raw hardware identifiers.
- Do not use MAC, serial, IMEI, or advertising ID.
- If Android ID is used, hash it with app/backend salt and document reset behavior.

## Migration Strategy

Recommended staged migration:

1. Add an identity store separate from `iptv_credentials`.
2. On first launch after migration:
   - read existing `device_id`;
   - read existing `sync_code`;
   - copy them into the new identity store if present;
   - do not delete old keys immediately.
3. Change logout to clear credential/session keys only.
4. Introduce server-side device registration:
   - send identity UUID;
   - send hashed Android ID signal if approved;
   - receive stable backend `device_record_id`.
5. Replace persistent 6-digit sync code with short-lived pairing code.
6. Keep compatibility path for old `sync_code` until all active clients migrate.
7. Add explicit admin reset that clears identity store and asks backend to revoke/reissue device claim.

## What Not To Break

- Do not break existing credential validation from companion sync.
- Do not mark companion credentials `logged_in=true` before login validation.
- Do not change existing credential keys during this audit pass.
- Do not clear device identity on logout.
- Do not use the 6-digit visible code as the permanent subscription identity.
- Do not enable full Android backup for credential secrets.
- Do not expose raw Android ID or hardware identifiers to companion clients.
- Do not remove Firestore pairing until replacement companion flow is ready.

## Findings Table

| Severity | Area | Current Behavior | Risk | Recommendation |
| --- | --- | --- | --- | --- |
| Critical | Logout | `clearCredentials()` clears all prefs | Device identity lost on logout | Split identity from credentials |
| Critical | Reinstall | Identity stored only in app prefs, backup disabled | New code after uninstall/reinstall | Add server-side persistent device registration |
| High | Pairing code | Random 6-digit code reused | Collision/guessing risk | Short-lived single-use pairing code |
| High | Identity | UUID is install-scoped | Not stable per hardware | Treat as install ID, add backend identity |
| High | Architecture | Firestore code and RemotePairingManager code differ | Inconsistent pairing behavior | One canonical identity service |
| Medium | CompanionConfigServer | Present but not wired | False assumptions/dead code drift | Wire or mark unused |

## Final Assessment

Current implementation does not satisfy the business rule.

The strongest confirmed flaw is that logout deletes the device identity because `sync_code` and `device_id` are stored inside the same SharedPreferences file that `clearCredentials()` wipes. Uninstall/reinstall also creates a new identity because the ID is app-data-only and backup is disabled.

Do not implement yet unless approved. The safe next step is a dedicated Device Identity Stabilization Pass that separates identity from credentials and introduces a migration path.
