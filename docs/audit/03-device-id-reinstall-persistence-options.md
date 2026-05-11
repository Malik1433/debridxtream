# Device ID Reinstall Persistence Options

Date: 2026-05-10
Scope: audit only. No app code changes.

## Current App Context

Current identity state:

- `device_id` is generated with `UUID.randomUUID()` in `CredentialsPreferences.getDeviceId()`.
- `sync_code` is generated as a random 6-digit code in `CompanionSetupActivity`.
- Both values are stored in `SharedPreferences` file `iptv_credentials`.
- TASK 004 fixed logout so `device_id` and `sync_code` survive logout.
- `AndroidManifest.xml` currently has `android:allowBackup="false"`.

Current reinstall behavior:

- App update: identity survives because app data remains.
- Logout/login: identity now survives after TASK 004.
- Uninstall/reinstall: identity does not survive because app-private data is removed and backup is disabled.
- Factory reset: identity does not survive, which matches the stated business rule.

## Option 1 - Android ID Hashed Identity

Summary:

Use `Settings.Secure.ANDROID_ID`, hash it with an app/backend salt, and treat the hash as a privacy-safe device signal.

Android behavior:

- Official Android docs describe `ANDROID_ID` on Android 8.0+ as unique to the combination of app signing key, user, and device.
- This means it can remain stable for the same app signing key on the same device/user after reinstall.

Evaluation:

| Area | Rating | Notes |
| --- | --- | --- |
| Fire TV compatibility | Good | Fire OS is Android-based and commonly exposes `Settings.Secure.ANDROID_ID`, but must be tested across Fire TV models. |
| Android TV compatibility | Good | Standard Android TV should support it. |
| Privacy risk | Medium | Lower if hashed and never exposed raw; still a stable identifier. |
| Security risk | Medium | Can be spoofed on rooted/custom systems; should not be the only entitlement proof. |
| Subscription/device-limit reliability | Good | Better than random app UUID for reinstall continuity. |
| Reinstall behavior | Good | Expected to survive reinstall on same signing key/user/device. |
| Factory reset behavior | Good | Expected to change after factory reset, matching business rule. |
| Implementation complexity | Low/Medium | Read Android ID, hash with salt, migrate backend matching. |

Strengths:

- Strong practical fit for Android TV and Fire TV.
- No credential backup needed.
- Avoids raw hardware identifiers.

Weaknesses:

- Signing-key scoped. A debug build, release build, or changed signing key may produce a different value.
- User/profile scoped. Different Android user profile can produce a different identity.
- Should not be treated as cryptographic proof of possession.

## Option 2 - Server-Side Device Registration

Summary:

Create a backend `device_record_id` for every physical device. The client sends privacy-safe signals, and the backend manages claims, limits, resets, and support actions.

Recommended signals:

- Hashed Android ID.
- App-generated install UUID.
- App version/build channel.
- Device model/manufacturer/OS family as weak metadata.
- User/account/subscription ID after login.

Evaluation:

| Area | Rating | Notes |
| --- | --- | --- |
| Fire TV compatibility | Excellent | Server logic is platform independent once the app can send signals. |
| Android TV compatibility | Excellent | Same model works across Android TV devices. |
| Privacy risk | Low/Medium | Good if raw IDs are never stored and reset/admin policy is documented. |
| Security risk | Low/Medium | Strongest option when paired with signed API calls and server-side limits. |
| Subscription/device-limit reliability | Excellent | Backend can enforce device slots and admin resets. |
| Reinstall behavior | Excellent | Backend can re-link same physical device using hashed Android ID. |
| Factory reset behavior | Good | Android ID usually changes; backend should treat as new unless admin reclaims. |
| Implementation complexity | Medium/High | Requires backend endpoints, migration, support tooling, and edge-case policy. |

Strengths:

- Best foundation for subscriptions and device limits.
- Handles reinstall, support reset, account transfer, and fraud controls.
- Avoids using visible pairing code as identity.

Weaknesses:

- Requires backend work.
- Requires careful privacy policy and support/admin flows.

## Option 3 - Android Auto Backup Selective Identity Backup

Summary:

Enable Android Auto Backup and include only identity preferences while excluding credentials and tokens.

Android behavior:

- Android Auto Backup can back up shared preferences.
- Android backup rules can include/exclude files for cloud backup and device transfer.
- Current app has `android:allowBackup="false"`, so no Auto Backup restore is currently expected.

Evaluation:

| Area | Rating | Notes |
| --- | --- | --- |
| Fire TV compatibility | Weak/Uncertain | Fire TV may not provide Google Auto Backup behavior consistently. |
| Android TV compatibility | Medium | Better on Google Android TV than Fire TV, but account/backup settings matter. |
| Privacy risk | Medium | Identity may move through cloud backup. Never include credentials. |
| Security risk | Medium | Backup restore can copy identity to replacement device if rules are too broad. |
| Subscription/device-limit reliability | Weak/Medium | Device identity may follow account restore rather than physical hardware. |
| Reinstall behavior | Medium | Works only when backup/restore is available and enabled. |
| Factory reset behavior | Problematic | Restore after factory reset could bring back identity, which may violate reset expectations. |
| Implementation complexity | Medium | Requires backup rules and careful exclusion of secrets. |

Strengths:

- Can preserve app-generated UUID after reinstall on some devices.
- Simple compared to a full backend.

Weaknesses:

- Poor fit for Fire TV.
- Can restore identity onto a different physical device.
- Dangerous if credential prefs are accidentally included.
- Current app would need backup policy changes.

Verdict:

- Not recommended as the primary device identity strategy for this app.
- Could be considered only for non-sensitive convenience state, not subscription identity.

## Option 4 - Account-Based Re-Claim Flow

Summary:

When reinstall creates a new local identity, the user signs in and the server offers to re-claim an existing device slot after verification.

Evaluation:

| Area | Rating | Notes |
| --- | --- | --- |
| Fire TV compatibility | Excellent | Works anywhere the app can log in and call backend. |
| Android TV compatibility | Excellent | Platform independent. |
| Privacy risk | Low | Tied to account action, not hidden tracking. |
| Security risk | Low/Medium | Strong if confirmation, rate limits, and admin controls exist. |
| Subscription/device-limit reliability | Good | Reliable with backend policy; less automatic than hashed Android ID. |
| Reinstall behavior | Good | User can recover slot after reinstall. |
| Factory reset behavior | Good | Can require explicit re-claim/admin action after reset. |
| Implementation complexity | Medium | Needs backend records and UI/support flow. |

Strengths:

- Privacy-friendly and user-visible.
- Good support tool for legitimate device replacement or reinstall.
- Works when Android ID changes or is unavailable.

Weaknesses:

- Not fully automatic.
- Needs account/backend model.

## Option 5 - Existing Random UUID Only

Summary:

Keep the current app-generated UUID stored in app-private SharedPreferences.

Evaluation:

| Area | Rating | Notes |
| --- | --- | --- |
| Fire TV compatibility | Excellent while app data remains | No platform dependency. |
| Android TV compatibility | Excellent while app data remains | No platform dependency. |
| Privacy risk | Low | Random app-scoped ID is privacy safe. |
| Security risk | Medium | Easy to reset by clearing app data or reinstalling. |
| Subscription/device-limit reliability | Poor | Reinstall creates a new identity. |
| Reinstall behavior | Fail | Does not survive uninstall/reinstall. |
| Factory reset behavior | Good | Changes after reset. |
| Implementation complexity | Low | Already implemented. |

Strengths:

- Simple.
- Privacy-safe.
- Works for app-session identity and logout persistence after TASK 004.

Weaknesses:

- Fails the reinstall requirement.
- Not enough for subscription/device limits.

## Comparison Matrix

| Option | Fire TV | Android TV | Privacy | Security | Device Limits | Reinstall | Factory Reset | Complexity |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| Hashed Android ID | Good | Good | Medium | Medium | Good | Good | Good | Low/Medium |
| Server registration | Excellent | Excellent | Low/Medium | Low/Medium | Excellent | Excellent | Good | Medium/High |
| Selective Auto Backup | Weak/Uncertain | Medium | Medium | Medium | Weak/Medium | Medium | Problematic | Medium |
| Account re-claim | Excellent | Excellent | Low | Low/Medium | Good | Good | Good | Medium |
| Random UUID only | Excellent until data loss | Excellent until data loss | Low | Medium | Poor | Fail | Good | Low |

## Recommended World-Class Approach

Best approach for this app:

TiviMate-grade device stability with privacy-safe server-side registration using hashed Android ID as a device signal, plus account/admin re-claim.

Recommended architecture:

1. Keep local random `device_id` as `install_instance_id`, not as the permanent subscription identity.
2. Add a privacy-safe `device_signal_hash`:
   - read `Settings.Secure.ANDROID_ID`;
   - combine with backend/app salt or HMAC;
   - never log or expose raw Android ID;
   - send only the hash to backend.
3. Backend creates a canonical `device_record_id`.
4. Backend binds `device_record_id` to subscription/account device slots.
5. On reinstall:
   - app generates a new local install UUID;
   - app recomputes the same Android ID hash if same device/user/signing key;
   - backend matches existing device record;
   - visible pairing code can stay stable or be reissued based on backend state.
6. On factory reset:
   - Android ID is expected to change;
   - backend treats it as a new device unless admin/account re-claim is explicitly approved.
7. On ambiguous cases:
   - use account-based re-claim flow;
   - require user/admin confirmation before consuming or transferring device slot.

What this gives:

- Stable across normal uninstall/reinstall.
- Stable across app updates.
- Stable across logout/login.
- Privacy safer than raw serial/MAC/IMEI.
- Strong enough for subscription/device-limit systems when enforced server-side.
- Clear support path for device replacement, factory reset, and signing-key changes.

## Why Not Auto Backup As Primary

Selective backup is tempting, but it is not the right core identity for this app.

Reasons:

- Fire TV backup behavior is not reliable enough for a commercial device-limit model.
- Backup can restore identity to a different physical device.
- Backup restore after factory reset may conflict with the business rule.
- Any mistake in backup rules could leak credential/session material.

Use backup only if later needed for non-sensitive convenience settings.

## Migration Strategy

Recommended staged migration:

1. Keep TASK 004 behavior: logout must preserve existing `device_id` and `sync_code`.
2. Add separate identity storage in a future pass:
   - `identity_prefs`;
   - `install_instance_id`;
   - `device_record_id`;
   - `device_signal_hash_version`;
   - existing `sync_code` migrated without deletion.
3. On first launch after migration:
   - copy old `device_id` to `install_instance_id` if present;
   - copy old `sync_code` if present;
   - compute hashed Android ID;
   - register or reconcile with backend.
4. Backend returns canonical `device_record_id`.
5. Keep old visible pairing code behavior until companion flow migration is approved.
6. Add admin reset:
   - revoke device record server-side;
   - clear identity prefs only after explicit admin action;
   - never run as part of normal logout.

## What Not To Break

- Do not clear `device_id` or `sync_code` on logout.
- Do not use raw Android ID in logs, QR payloads, Firestore document IDs, or support screens.
- Do not use the 6-digit visible pairing code as the permanent subscription identity.
- Do not enable blanket Android backup for credentials.
- Do not make reinstall persistence depend only on SharedPreferences.
- Do not change Firestore pairing until a separate companion migration is approved.
- Do not change credential validation or Login UI in the identity pass.

## Final Recommendation

Implement Pass 2 as design-first backend identity stabilization:

- Primary: server-side device registration.
- Device signal: salted/HMAC hashed Android ID.
- Local ID: random install UUID retained as an install/session helper.
- Recovery: account/admin re-claim flow.
- Avoid: Auto Backup as the source of truth.

This is the best balance for TiviMate-grade stability, Android TV/Fire TV compatibility, privacy safety, and subscription/device-limit reliability.

## Sources

- Android Developers, `Settings.Secure.ANDROID_ID`: https://developer.android.com/reference/android/provider/Settings.Secure
- Android Developers, Android 8.0 behavior changes for Android ID scoping: https://developer.android.com/about/versions/oreo/behavior-changes
- Android Developers, Auto Backup and include/exclude backup rules: https://developer.android.com/identity/data/autobackup
- Android Developers, Android 12 backup/data extraction behavior: https://developer.android.com/about/versions/12/behavior-changes-12
