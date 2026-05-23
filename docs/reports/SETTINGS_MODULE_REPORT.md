# Settings Module Report
Status: canonical/placeholder
Scope: Settings, companion configuration, Debrid/Stremio setup, and EPG sync controls.

## Current Active Runtime Flow
- Home and Main navigation can open `SettingsFragment`.
- Settings owns user-facing preferences, companion setup routes, Stremio/Debrid configuration entrypoints, and EPG sync controls.
- Companion/security details are tracked in companion and security reports.

## Important Active Files
- `app/src/main/java/com/tvonnet/debridxtreamiptv/ui/settings/SettingsFragment.kt` - settings UI.
- `app/src/main/java/com/tvonnet/debridxtreamiptv/data/prefs/SettingsPreferences.kt` - settings storage.
- `app/src/main/java/com/tvonnet/debridxtreamiptv/ui/settings/MediaFusionConfigActivity.kt` - Stremio/Addons config surface.
- `app/src/main/java/com/tvonnet/debridxtreamiptv/ui/companion/CompanionSetupActivity.kt` - phone companion setup.
- `app/src/main/java/com/tvonnet/debridxtreamiptv/worker/EpgSyncScheduler.kt` - EPG sync scheduling.

## What Must Not Break
- Existing companion setup path.
- Stremio-first addon labels and canonical payload shape.
- Hidden legacy auth fallback behavior.
- Manual EPG sync must stay single-flight.
- Sensitive config/log redaction.

## Known Bugs / Open Issues
- Companion manual payload QA remains tracked in `COMPANION_SECURITY_MANUAL_QA.md`.

## Recent Fixes
- Companion LAN security and URL validation hardening are documented in security/companion reports.

## Failed Approaches / Avoid
- Do not create a second companion config screen/path.
- Do not leave legacy Real-Debrid/MediaFusion wording as primary if Stremio is the active surface.
- Do not trigger two EPG sync paths from one settings action.

## QA Checklist
- Open Settings from Home.
- Companion setup route.
- Stremio addon config save/validation.
- Debrid auth/config route if touched.
- Manual EPG sync status.

## Last Verified State
- Placeholder summary based on companion/security reports; refresh during the next Settings task.
