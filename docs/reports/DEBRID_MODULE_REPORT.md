# Debrid Module Report
Status: canonical/partial
Scope: Debrid source selection, resolution, playback, and provider setup.

## Current Active Runtime Flow
- Debrid browse/search opens `MovieDetailActivity` or `SeriesDetailActivity` with Debrid flags/metadata.
- Source picker uses unified source discovery and preserves provider/source profile metadata.
- Resolver-backed Debrid playback resolves magnet/infoHash through `PlaybackResolver` before `PlayerActivity`.
- Direct HTTP/addon playback keeps `PlaybackSource.DEBRID` identity but uses direct-play guards to skip app-side Real-Debrid resolver calls.
- Resume should fresh-resolve durable metadata instead of trusting expired direct URLs.

## Important Active Files
- `app/src/main/java/com/tvonnet/debridxtreamiptv/ui/debrid/DebridFragment.kt` - Debrid home/browse/resume entry.
- `app/src/main/java/com/tvonnet/debridxtreamiptv/ui/vod/MovieDetailActivity.kt` - Debrid movie details, source picker, resolver handoff.
- `app/src/main/java/com/tvonnet/debridxtreamiptv/ui/series/SeriesDetailActivity.kt` - Debrid series episode source picker and player handoff.
- `app/src/main/java/com/tvonnet/debridxtreamiptv/player/stabilized/DebridResolutionManager.kt` - player-side Debrid refresh/next episode resolution.
- `app/src/main/java/com/tvonnet/debridxtreamiptv/data/debrid/repository/PlaybackResolver.kt` - Debrid playback resolver contract.
- `app/src/main/java/com/tvonnet/debridxtreamiptv/ui/series/SourceSelectionBottomSheet.kt` - shared Debrid source picker UI.

## What Must Not Break
- Source picker filtering and selected source continuity.
- Resolver path for magnet/infoHash Real-Debrid sources.
- Direct HTTP/addon path and headers.
- Real-Debrid auth/config path.
- Debrid identity in Player controls/history; do not downgrade to IPTV.
- Sensitive token, provider URL, magnet, infoHash, and direct stream redaction.

## Known Bugs / Open Issues
- Full Stremio parity is not implemented.
- Provider-cache verification still needs manual QA where relevant.
- Direct addon and direct Debrid edge cases stay in task-specific docs.

## Recent Fixes
- Debrid Continue Watching resume now blocks stale/null-expiry saved direct URLs from being treated as safe playback and refreshes from durable hash/magnet or source-profile metadata instead.
- Resolved Debrid playback "Real Debrid config missing" regression caused by Player/Episode Browser changes.
- Companion security hardening for LAN access, PIN validation, and safer URL handling.
- Debrid source profile metadata is preserved into Player intents and Continue Watching where supported.
- Direct Debrid playback identity guard is documented in app patterns.

## Failed Approaches / Avoid
- Do not infer cache readiness from labels such as `RD+`, `cached`, or provider names.
- Do not retry/auto-next on rate limits, auth failures, network failures, or unknown failures.
- Do not put Stremio `manifest.json` URLs into legacy scraper registry storage.
- Do not send direct addon HTTP URLs through app-side Real-Debrid auth.
- Do not trust expired direct addon URLs for resume.

## QA Checklist
- `:app:compileDebugKotlin`
- `:app:assembleDebug`
- Manual QA: Debrid movie source picker, Debrid series source picker, direct addon URL, resolver-backed magnet/infoHash, resume, failure handling.

## Last Verified State
- 2026-05-24: `:app:compileDebugKotlin` and `:app:assembleDebug` passed after the Continue Watching resume branch fix. Debug APK installed and `MainActivity` launched on `192.168.0.21:5555` and `192.168.0.84:5555`; app PIDs confirmed. Manual Debrid movie/series resume, expired/null `expiresAt`, direct addon, resolver-backed hash/magnet, and source picker regression QA still required.
- 2026-05-24 follow-up: expired/null `expiresAt` Debrid movie Continue Watching resume passed on `192.168.0.21:5555`. Existing null-expiry Debrid history item fresh-resolved through `PlaybackResolver` and started playback without stale URL replay or false Real-Debrid config missing.
- Recent build and device verification passed for the companion/security hardening pass.
- Module remains partial because full Stremio/provider parity and some cache verification need manual QA.
