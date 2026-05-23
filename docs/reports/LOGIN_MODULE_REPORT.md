# Login Module Report
Status: canonical/placeholder
Scope: login/session, InitialSync, companion setup entry, and device identity.

## Current Active Runtime Flow
- `MainActivity` checks `CredentialsPreferences.isLoggedIn()`.
- Logged-out users see `LoginFragment`; successful login saves credentials and opens `InitialSyncFragment`.
- Companion setup can save synced credentials, then Login validates before marking logged in.

## Important Active Files
- `app/src/main/java/com/tvonnet/debridxtreamiptv/ui/MainActivity.kt` - startup/session gate.
- `app/src/main/java/com/tvonnet/debridxtreamiptv/ui/LoginFragment.kt` - login UI and validation.
- `app/src/main/java/com/tvonnet/debridxtreamiptv/ui/InitialSyncFragment.kt` - first content sync.
- `app/src/main/java/com/tvonnet/debridxtreamiptv/data/prefs/CredentialsPreferences.kt` - credential/session storage.
- `app/src/main/java/com/tvonnet/debridxtreamiptv/ui/companion/CompanionSetupActivity.kt` - phone setup entry.

## What Must Not Break
- Logged-out users must land on Login.
- Synced credentials must be validated before `logged_in=true`.
- InitialSync retry/error state must remain usable.
- Device identity must survive logout where documented.

## Known Bugs / Open Issues
- Use `docs/progress/010-login-module-closure-report.md` and `docs/audit/01-login-page-deep-audit.md` for detailed history.

## Recent Fixes
- Login UI modernization and 1080p clipping fixes are archived under `docs/progress`.
- Device ID stabilization passes are archived under `docs/progress`.

## Failed Approaches / Avoid
- Do not delete persistent device identity during ordinary logout.
- Do not mark companion-synced credentials logged in before validation.
- Do not expose raw credential/server errors in user-facing or companion responses.

## QA Checklist
- Fresh launch logged out.
- URL validation.
- Invalid login.
- Valid login to InitialSync.
- Companion setup invalid/valid payload.
- Logout and relaunch behavior.

## Last Verified State
- Placeholder summary based on existing login progress/audit docs; refresh this report during the next Login task.
