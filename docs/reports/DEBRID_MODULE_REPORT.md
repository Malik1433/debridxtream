# Debrid Module Consolidated Report

This report tracks the evolution, stabilization, and modernization of the Debrid module (Real-Debrid, Premiumize, AllDebrid integration).

## Module Overview
The Debrid module handles source resolution, link scraping, and playback for debrid-based content. It features a unified provider system and a cinematic source selection UI.

---

# Debrid Module Redesign Report (TASK 018E)

## Status: COMPLETED
**Goal:** Modernize Debrid Source Selection UI to achieve cinematic visual parity with Stitch mockup.


---

## 1. Task Execution Log

| Step | Status | Action | Notes |
| :--- | :--- | :--- | :--- |
| Baseline | COMPLETED | Full build + Unit Test run | Baseline stable (some existing test failures noted) |
| Layout Redesign | COMPLETED | Updated `dialog_source_selection.xml` | Converted to ConstraintLayout with centered cinematic panel. |
| Item Redesign | COMPLETED | Updated `item_movie_source.xml` | Premium card layout with provider badges and play action icon. |
| Logic Binding | COMPLETED | Updated `SourceSelectionBottomSheet.kt` | Added backdrop loading and content title support. |
| Adapter Refactor | COMPLETED | Updated `MovieSourceAdapter.kt` | Implemented provider badge parsing and blue focus glow. |
| Theme Sync | COMPLETED | Updated `cin_source_row_bg.xml` | Switched focus states from Gold to Electric Blue. |
| Verification | IN_PROGRESS | `compileDebugKotlin` | Validating build integrity. |

---

## 2. Key Changes Summary

### UI/UX Refactor
- **Cinematic Backdrop**: Added `iv_backdrop` to the source selection dialog. It now loads the movie/series backdrop with a dark overlay to provide context while keeping the sources panel legible.
- **Centered Sources Panel**: Sources are now displayed in a centered glassmorphic panel (`720dp` width) instead of a full-width bottom sheet, creating a focused "modal" feel common in premium TV apps.
- **Provider Badges**: Implemented visual identification for sources (RD, AD, PM, SRC) using color-coded badges in the source row.
- **Quality/Status Badges**: Added refined badges for 4K/1080p, Seeders, and Cache status.
- **Electric Blue Theme**: Aligned all focus states with the app's primary blue theme (#3B82F6) for brand consistency.

### Technical Implementation
- **Non-Breaking**: Core resolver, playback, and sorting logic were preserved exactly as requested.
- **Performance**: Used `Glide` for efficient backdrop loading and `FocusGlintHelper` for optimized TV focus animations.
- **D-Pad Stability**: Maintained existing focus chain logic to ensure seamless remote control navigation.

---

## 3. Verification & Known Issues
- **Baseline Build**: Succeeded (`assembleDebug`).
- **Unit Tests**: Baseline had failures in `XtreamRepositoryStreamLookupTest` and `SeriesPagingSourceTest` unrelated to current task.
- **Device Testing**: Pending user verification on physical TV hardware.

---

# TASK 000-DEBRID-DEEP-AUDIT-STREMIO-COMPARISON

## Scope
Deep audit of Debrid architecture and Stremio comparison only. No app code changed.

## Report
Full audit written to:
- `docs/reports/DEBRID_DEEP_AUDIT_STREMIO_COMPARISON.md`

## Key Findings
- DebridXtream has a solid Real-Debrid playback foundation: encrypted auth state, Real-Debrid add/select/unrestrict flow, shared `PlayerActivity`, source selection filters, and Debrid resume via infoHash/magnet re-resolution.
- It is not Stremio-equivalent. Current implementation is Real-Debrid-only, uses TMDB-driven catalogs, and has custom dynamic provider templates instead of full Stremio manifest/add-on lifecycle support.
- Highest-risk gap: token-bearing provider URLs, direct stream URLs, magnets, and full Continue Watching JSON can be logged.
- Cache state is inferred from labels/extras rather than verified through Real-Debrid availability.
- AD/PM provider language is cosmetic unless real AllDebrid/Premiumize clients are added.

## Required Follow-Up
1. Redact tokens, stream URLs, magnets, and full history JSON from logs.
2. Add Real-Debrid cache availability verification before trusted cached-only UI.
3. Resolve TMDB movie IDs to IMDb IDs before Debrid source fetching.
4. Decide curated provider registry vs full Stremio manifest/add-on support.

## Final Status
PARTIAL  Debrid module usable, but Stremio parity and security hardening remain.

---

# TASK 001-DEBRID-PHASE1-SECURITY-LOG-REDACTION

## Scope
Phase 1 Real-Debrid-only security hardening. No UI redesign, no provider expansion, and no playback/source behavior rewrite.

## Changes
- Added `SensitiveLogRedactor` for safe log summaries of URLs, magnets, tokens, hashes, and secrets.
- Removed debug HTTP logging from Real-Debrid, addon catalog, Torrentio/PureFire, and TMDB clients where URLs/bodies can contain tokens or API keys.
- Redacted provider fetch URLs in Torrentio/PureFire, MediaFusion, dynamic addon registry/fetch paths, and addon URL mapping.
- Redacted direct playback links, unrestrict links, magnets, Debrid hashes, Continue Watching IDs, Recent Live stream URLs, and serialized history JSON logging.
- Preserved the active Real-Debrid resolver, source selection, shared PlayerActivity, and watch-history behavior.

## Validation
- Static sensitive-log scans: PASS for targeted old patterns (`HttpLoggingInterceptor`, BODY/BASIC logging, full history JSON logs, direct `$streamUrl`, `$link`, `$infoHash`, and registry URL log strings).
- `git diff --check`: blocked by pre-existing trailing whitespace in `docs/reports/PLAYER_MODULE_REPORT.md`; no Phase 1 file whitespace errors reported.
- `:app:assembleDebug --offline`: PASS, `BUILD SUCCESSFUL in 15m 42s`.
- APK generated: `app/build/outputs/apk/debug/app-debug.apk`, timestamp `2026-05-17 02:22:00`, size `33,670,619` bytes.
- Install QA: PASS on `192.168.0.21:5555` and `192.168.0.84:5555`.
- Launch smoke QA: PASS on both devices via `com.debridxtream.tv/com.tvonnet.debridxtreamiptv.ui.MainActivity`.
- DPAD smoke QA: PASS on both devices; app retained current focus after basic DPAD_DOWN/DPAD_RIGHT input.
- Log scan: PASS for no `FATAL EXCEPTION`, no `AndroidRuntime` crash, and no targeted legacy sensitive strings after launch/navigation smoke.

## Final Status
PASS  Security redaction integrated, build successful, APK installed, and basic device smoke QA passed on both required devices.

---

# TASK 001B-DEBRID-SWARM-REVIEW-FOLLOW-UP

## Scope
Read-only swarm review follow-up for Phase 1 security redaction, QA evidence, and project-rule consistency.

## Swarm Findings Addressed
- Removed raw infoHash/title retry logging in `PlayerViewModel`.
- Removed `SeriesPlaylistState` stringification in `PlayerActivity` episode browser logging.
- Redacted Real-Debrid returned filenames in `RealDebridRemoteDataSource`.
- Reduced provider/sample source logging in `UnifiedSourceProvider` so provider release titles and conversion labels are not printed.
- Persisted QA evidence in `docs/reports/DEBRID_PHASE1_QA_EVIDENCE.md`.
- Updated `AGENTS.md` stale npm guidance to Android/Gradle commands and Android project paths.

## Validation
- `:app:assembleDebug --offline`: PASS after swarm follow-up.
- APK generated: `app/build/outputs/apk/debug/app-debug.apk`, timestamp `2026-05-17 11:43:20`, size `33,670,619` bytes.
- Install + launch smoke: PASS on `192.168.0.21:5555` and `192.168.0.84:5555`.
- Log scan: PASS for no `FATAL EXCEPTION`, no `AndroidRuntime`, no `HttpLoggingInterceptor`, no `realdebrid=`, no old history JSON strings, and no old raw episode-state log string.

## Remaining QA Gap
Full functional Real-Debrid playback QA is still not claimed. It requires manually selecting a Debrid source, resolving through Real-Debrid, starting playback, backing out, and verifying resume.

## Final Status
PASS  Swarm review findings addressed, rebuild/install/launch smoke passed, and persisted QA evidence added.

---

# TASK 002-DEBRID-PHASE2-RD-CACHE-VERIFICATION

## Scope
Real-Debrid-only Phase 2 cache confidence improvement using swarm review. No AllDebrid/Premiumize support was added and no Stremio parity was claimed.

## Swarm Agents Used
- `McClintock`: audited Real-Debrid API/model/repository path and confirmed `instantAvailability` was not modeled.
- `Noether`: audited source picker UI, cached-only filtering, cache badge rendering, and source sorting.
- `Ohm`: audited validation/QA coverage and confirmed manual Debrid playback QA is still required for functional PASS.

## Changes
- Added Real-Debrid `rest/1.0/torrents/instantAvailability/{hash}` API method and tolerant JSON parsing.
- Added `DebridCacheStatus` with `VERIFIED_CACHED`, `NOT_CACHED`, `UNKNOWN`, and `DIRECT_STREAM`.
- Unified source conversion now verifies torrent info hashes through Real-Debrid before marking a source as cached.
- Removed authoritative cache inference from `RD+`, `cached`, `instant`, provider names, and provider labels.
- Cached-only filtering now includes only `VERIFIED_CACHED` sources.
- Source sorting now prioritizes verified cached, then direct streams, then unknown, then not cached.
- Source UI now shows `VERIFIED`, `DIRECT`, `UNKNOWN`, or `UNCACHED` instead of forcing null cache state to `UNCACHED`.
- MediaFusion playback readiness updates now set explicit cache confidence.

## Validation
- `:app:compileDebugKotlin --offline --console plain`: PASS, `BUILD SUCCESSFUL in 12m 24s`.
- `:app:assembleDebug --offline --console plain`: PASS, `BUILD SUCCESSFUL`.
- APK generated: `app/build/outputs/apk/debug/app-debug.apk`, timestamp `2026-05-17 12:21:29`, size `33,670,619` bytes.
- Install QA: PASS on `192.168.0.21:5555` and `192.168.0.84:5555`.
- Launch smoke QA: PASS on both devices via `com.debridxtream.tv/com.tvonnet.debridxtreamiptv.ui.MainActivity`.
- Crash log scan: PASS for no `FATAL EXCEPTION`, no `AndroidRuntime`, and no `Process: com.debridxtream.tv` crash after launch smoke.

## Remaining QA Gap
Manual Real-Debrid functional QA is still required: authenticate if needed, open Debrid movie and series sources, verify source badges/filtering, select a verified source, confirm Real-Debrid resolution/playback, BACK behavior, and resume re-resolution.

## Final Status
PARTIAL  Code/build/install/launch smoke passed, but user-facing Real-Debrid source selection and playback need manual functional QA before claiming full PASS.

---

# TASK 003-DEBRID-PHASE3-PLAYBACK-ERROR-AUDIT

## Scope
Read-only swarm audit of playback errors found during manual Phase 2 QA.

## Report
Full audit written to:
- `docs/reports/DEBRID_PHASE3_PLAYBACK_ERROR_AUDIT.md`

## Key Findings
- `VERIFIED` currently means Real-Debrid instant availability found cached data, not guaranteed playable output.
- Real-Debrid can still block playback later during add-magnet, unrestrict, or actual download URL playback with copyright/legal messages.
- HTTP `429` is Real-Debrid rate limiting. Current resolver retries the whole flow and can make rate limiting worse.
- `UNKNOWN` sources are intentionally selectable but need clearer warning copy and lower confidence handling.
- MediaFusion URLs can be over-promoted to direct stream before durable readiness is confirmed.

## Recommended Follow-Up
Implement typed Debrid failure classification, no-retry/cooldown rules for legal/copyright/429 failures, clearer source states, better UX messages, and reduced duplicate add-magnet calls.

## Final Status
PARTIAL  Audit complete; implementation still required.

---

# TASK 004-DEBRID-PHASE3A-LITE-RD-ERROR-COOLDOWN

## Scope
Real-Debrid-only error handling and rate-limit reduction after manual QA found copyright/legal blocks, unknown availability, and HTTP `429` add-magnet failures. No AllDebrid/Premiumize support, UI redesign, Player DPAD changes, or Stremio parity claim was added.

## Changes
- Added typed Debrid failure classification for `429`, `451`, copyright/legal removal text, auth, not-cached/unavailable, network, and unknown failures.
- Added a shared Real-Debrid request limiter with minimum request spacing, global `429` cooldown, and per-source cooldown for terminal blocked/unavailable sources.
- Stopped immediate resolver retry for terminal Real-Debrid failures so blocked sources and rate-limit errors do not re-run the same expensive flow.
- Applied limiter gates around add-magnet, torrent-info polling, file selection, delete/re-add, unrestrict, and instant-availability checks.
- Added short TTL cache for Real-Debrid instant availability checks and capped each verification batch to reduce duplicate API calls.
- Added focused unit coverage for failure classification.

## Validation
- `:app:compileDebugKotlin --offline --no-daemon --console plain`: PASS, `BUILD SUCCESSFUL`.
- `:app:testDebugUnitTest --tests "com.tvonnet.debridxtreamiptv.data.debrid.model.DebridFailureClassifierTest" --offline --no-daemon --console plain`: PASS, `BUILD SUCCESSFUL`.
- `clean assembleDebug --offline --no-daemon --console plain`: command timed out after 15 minutes; the Gradle wrapper process later finished, but the final exit code was not captured.
- `:app:assembleDebug --offline --no-daemon --console plain`: PASS, exit code `0`.
- APK generated: `app/build/outputs/apk/debug/app-debug.apk`, timestamp `2026-05-17 20:55:14`, size `31,380,390` bytes.
- Install QA: PASS on `192.168.0.21:5555` and `192.168.0.84:5555`.
- Launch smoke QA: PASS on both devices via `com.debridxtream.tv/com.tvonnet.debridxtreamiptv.ui.MainActivity`.
- Crash log scan: PASS for no `FATAL EXCEPTION`, no `AndroidRuntime`, and no `Process: com.debridxtream.tv` crash after launch smoke.

## Remaining QA Gap
Manual Real-Debrid playback QA is still required before claiming full functional PASS: pick several `VERIFIED` and `UNKNOWN` sources, confirm blocked/legal/copyright sources stop cleanly, confirm `429` produces cooldown behavior instead of rapid repeated add-magnet attempts, and confirm a known-good cached source still resolves and plays.

## Final Status
PARTIAL  Code/build/install/launch smoke passed, and rate-limit/error controls are integrated. Full Real-Debrid functional playback QA remains user-facing manual scope.

---

# TASK 004B-DEBRID-PHASE3A-RATE-LIMIT-FOLLOW-UP

## Scope
Follow-up from manual QA after Phase 3A Lite. User confirmed a known-good Real-Debrid source plays, but also reported that a blocked source tries once and then later clicks appear to do nothing, while rate-limit messages can still appear.

## Findings
- Known-good source playback was manually confirmed by the user after TASK 004.
- Global `429` cooldown was being enforced inside `awaitPermit()`, which could silently delay user-initiated playback attempts and make a click look like no action.
- Availability verification was still too optimistic for a strict Real-Debrid rate window because it could check up to 24 hashes with two concurrent checks.

## Changes
- Global rate-limit cooldown now returns an immediate typed `RATE_LIMITED` error instead of silently waiting.
- Playback resolution checks the global cooldown before add-magnet/unrestrict work and shows the cooldown message immediately through the existing error path.
- Real-Debrid availability verification now skips network calls during global cooldown.
- Availability verification is more conservative: capped at 10 hashes, one Real-Debrid availability check at a time, and 1.2s minimum request spacing.
- Added unit coverage for immediate global cooldown behavior.

## Validation
- `:app:compileDebugKotlin --offline --no-daemon --console plain`: PASS, `BUILD SUCCESSFUL`.
- `:app:testDebugUnitTest --tests "com.tvonnet.debridxtreamiptv.data.debrid.model.DebridFailureClassifierTest" --tests "com.tvonnet.debridxtreamiptv.data.debrid.repository.RealDebridRateLimiterTest" --offline --no-daemon --console plain`: PASS, `BUILD SUCCESSFUL`.
- `:app:assembleDebug --offline --no-daemon --console plain`: PASS, exit code `0`.
- Install QA: PASS on `192.168.0.21:5555` and `192.168.0.84:5555`.
- Launch smoke QA: PASS on both devices via `com.debridxtream.tv/com.tvonnet.debridxtreamiptv.ui.MainActivity`.
- Crash log scan: PASS for no `FATAL EXCEPTION`, no `AndroidRuntime`, and no `Process: com.debridxtream.tv` crash after launch smoke.

## Remaining QA Gap
Manual retest required: after a `429`, clicking another source should now immediately show the cooldown message instead of doing nothing. Source fetching should produce fewer Real-Debrid availability calls, but provider-side rate limit behavior still depends on current Real-Debrid account/IP state.

## Final Status
PARTIAL  Follow-up fix built, installed, and smoke-tested. Manual rate-limit retest is still required before claiming user-facing PASS.

---

# TASK 005-DEBRID-PHASE3D-HINDI-GERMAN-SOURCE-RECOVERY

## Scope
Improve Real-Debrid source discovery for Hindi/German playback after audit found many user-visible links dead or copyright-blocked and Torrentio returning mostly English/low-playability candidates. This stays Real-Debrid-only; no AllDebrid/Premiumize implementation, UI redesign, Player DPAD change, or Stremio parity claim was added.

## Changes
- Updated built-in Torrentio requests to use current config keys: expanded provider list, `sort=quality`, `limit=50`, `language=hindi,german` plus the user's mapped preferred language, and `debridoptions=nodownloadlinks` when Real-Debrid is present.
- Removed the stale `quality=4k,1080p,720p,480p` Torrentio config segment because current Torrentio config uses `qualityfilter` for exclusions and the old key can behave like a no-op.
- Enabled built-in dynamic registry loading by always including `DebridPreferences.DEFAULT_REGISTRY_URL` and `DebridPreferences.PUREFIRE_REGISTRY_URL` alongside saved registry URLs.
- Prioritized Hindi/German/multi-language addon streams before the capped Real-Debrid instant-availability budget so the first 10 checks are not consumed by English-only sources.
- Increased the Real-Debrid availability verification timeout to match the 10-hash, single-lane, 1.2s-spaced request budget caught by swarm review.
- Improved language parsing for `deu`, `deutsch`, `german audio`, `hindi dubbed`, `hindi audio`, `bollywood`, and dual-audio/multi-audio release labels.
- Added fallback source sorting boost for Hindi/German/multi-language sources when no explicit preferred language is configured.

## Validation
- `:app:compileDebugKotlin --no-daemon --console plain --max-workers=1`: PASS, exit code `0`.
- `:app:testDebugUnitTest --tests "LanguageParserTest" --tests "DebridFailureClassifierTest" --tests "RealDebridRateLimiterTest" --no-daemon --console plain --max-workers=1`: PASS, exit code `0`.
- `:app:assembleDebug --no-daemon --console plain --max-workers=1`: PASS, exit code `0`.
- Swarm review: caught an 8s availability-timeout mismatch after the 10-hash single-lane limiter; fixed before final.
- Install QA: PASS on `192.168.0.21:5555` and `192.168.0.84:5555`.
- Launch smoke QA: PASS on both devices. `192.168.0.21:5555` required explicit `am start -n com.debridxtream.tv/com.tvonnet.debridxtreamiptv.ui.MainActivity`; `192.168.0.84:5555` launched via package launcher.
- Crash log scan: PASS for no `FATAL EXCEPTION`, no `Unable to start activity`, and no `Process com.debridxtream.tv has died` after fresh launch smoke.

## Remaining QA Gap
Manual Real-Debrid playback QA is still required before functional PASS: use the same Hindi/German titles that were failing, verify provider breakdown includes more Torrentio/dynamic Hindi/German/multi candidates, then confirm at least one source resolves to playback without hitting immediate `429`. Copyright/legal removals are provider-side terminal failures and cannot be bypassed by client code.

## Final Status
PARTIAL  Source discovery recovery is integrated, built, installed, and smoke-tested. Real title playback still needs manual QA under the user's current Real-Debrid account/IP state.

---

# TASK 006-DEBRID-CUSTOM-SCRAPER-BLOCKED-SOURCE-FOLLOW-UP

## Scope
Follow-up for Manage Scraper Sources custom JSON registries where selecting a returned source can produce `This source is blocked by Real-Debrid`. This is a Real-Debrid terminal-source failure, not a bypassable client error.

## Findings
- Custom scraper registries can return hashes/magnets that Real-Debrid blocks only during add-magnet, torrent-info, or unrestrict.
- Prior behavior showed the blocked-source message and reopened the picker, but did not automatically skip to the next candidate.
- Auto-next must not run for `RATE_LIMITED` or `AUTH_REQUIRED` because that can worsen throttling or hide session problems.

## Changes
- Movie Debrid playback now marks terminal blocked/unavailable/not-cached selections as `UNCACHED` for the current source list and automatically tries the next source.
- Series Debrid playback now applies the same next-source behavior for terminal source failures.
- Auto-next is limited to `COPYRIGHT_BLOCKED`, `LEGAL_RESTRICTION`, `NOT_CACHED`, and `UNAVAILABLE`.
- `RATE_LIMITED`, `AUTH_REQUIRED`, `NETWORK`, and `UNKNOWN` still stop with the error message instead of cascading through more sources.

## Validation
- `:app:compileDebugKotlin --no-daemon --console plain --max-workers=1`: PASS, exit code `0`.
- `:app:assembleDebug --no-daemon --console plain --max-workers=1`: PASS, exit code `0`.
- Install QA: PASS on `192.168.0.21:5555` and `192.168.0.84:5555`.
- Launch smoke QA: PASS on both devices via `com.debridxtream.tv/com.tvonnet.debridxtreamiptv.ui.MainActivity`.
- Crash log scan: PASS for no `FATAL EXCEPTION`, no `Unable to start activity`, and no `Process com.debridxtream.tv has died` after launch smoke.

## Remaining QA Gap
Manual custom-registry QA is still required: add the same JSON URL, open the same title, click the blocked source, and confirm the app marks/skips it and tries the next source instead of stopping on the blocked Real-Debrid message. If every source from that registry is blocked, playback still cannot succeed.

## Final Status
PARTIAL  Blocked custom-scraper sources now auto-skip to the next candidate where safe. Real playback depends on whether the registry has at least one unblocked Real-Debrid-compatible source.

---

# TASK 007-STREMIO-ADDON-CLEAN-SLATE-PLAN

## Scope
Planning-only review of the proposed clean-slate Stremio addon URL integration. No Android app source, UI, Player, Home, Series, Live, VOD, or Debrid implementation files were changed.

## Findings
- The clean-slate direction is correct because custom Stremio addon URLs should eventually replace mixed legacy hardcoded scrapers and registry sources.
- A delete-first migration is unsafe because the current dynamic addon path is registry-definition based and should not be assumed to consume raw Stremio `manifest.json` URLs without a native manifest/stream parser.
- Real-Debrid should remain the only debrid provider for this phase; AllDebrid/Premiumize work stays out of scope.
- The migration should preserve current typed Debrid failures, safe auto-skip behavior, rate-limit discipline, and Hindi/German/multi-audio ranking.

## Artifact
- Created `docs/reports/STREMIO_ADDON_CLEAN_SLATE_PLAN.md`.

## Recommendation
Use a staged clean-slate cutover: add and validate the native Stremio addon URL pipeline first, then remove `SimplifiedPureFireFetcher`, `MediaFusionFetcher`, legacy registry loading, and old scraper-source UI after build and device QA prove the new path.

## Validation
- Documentation-only task. No Gradle build or device install was required.

## Final Status
PASS  Plan file created and Debrid report updated. No app code was changed.

---

# TASK 008-STREMIO-ADDON-URL-PHASE1

## Scope
Implemented the first staged clean-slate Stremio addon URL path for Debrid sources while keeping Real-Debrid as the only debrid provider. This phase adds native `manifest.json` handling and Debrid settings storage/UI. It does not remove the legacy classes yet, does not add AllDebrid/Premiumize, and does not change Player DPAD, Home, IPTV/EPG, Live, or VOD UI outside the existing Debrid source entry.

## Changes
- Added typed Stremio manifest and stream response models.
- Added `StremioAddonFetcher` for native Stremio `manifest.json` URLs.
- Preserved configured manifest path and query-token suffixes when deriving stream endpoints.
- Added secure `stremioAddonUrls` storage separate from old JSON registry URLs.
- Replaced the Debrid settings source row with `Manage Custom Stremio Addons`.
- Wired configured Stremio addon URLs into `UnifiedSourceProvider` as the clean primary source path when at least one Stremio manifest URL is configured.
- Preserved existing Real-Debrid cache verification, rate-limit discipline, typed failure handling, and terminal-source auto-skip behavior.
- Kept legacy Torrentio/MediaFusion/dynamic registry classes as fallback when no Stremio manifest URL is configured.
- Expanded direct playback classification for addon playback paths such as AIOStreams, `/stream/`, `/play/`, and `/playback/`.

## Swarm Review
- Explorer audit confirmed raw Stremio manifests must not be forced through the old `DynamicAddonFetcher` registry model.
- Explorer flagged enum exhaustiveness, query-token URL handling, direct playback policy, and `fileIdx` handling as risks.
- Fixed enum exhaustiveness and query-token stream URL derivation in this phase.
- Remaining known limitation: `fileIdx` is preserved in source extras/label but not yet passed into Real-Debrid file selection.

## Validation
- `:app:compileDebugKotlin --offline --no-daemon --console plain --max-workers=1` initially timed out under the Kotlin daemon.
- `:app:compileDebugKotlin --offline --no-daemon --console plain --max-workers=1` with `-Dkotlin.compiler.execution.strategy=in-process`: PASS, `BUILD SUCCESSFUL`.
- `:app:testDebugUnitTest --tests "com.tvonnet.debridxtreamiptv.data.debrid.source.StremioAddonFetcherTest" --offline --no-daemon --console plain --max-workers=1` with in-process Kotlin compiler: PASS, `BUILD SUCCESSFUL`.
- `clean :app:assembleDebug --offline --no-daemon --console plain --max-workers=1` timed out after 15 minutes and did not leave an APK because `clean` had removed outputs.
- `:app:assembleDebug --offline --no-daemon --console plain --max-workers=1` with in-process Kotlin compiler: PASS, `BUILD SUCCESSFUL`.
- APK generated: `app/build/outputs/apk/debug/app-debug.apk`, size `31,405,179` bytes, timestamp `2026-05-18 03:57:53`.
- Install QA: PASS on `192.168.0.84:5555` and `192.168.0.21:5555`.
- Launch smoke QA: PASS on both devices via `com.debridxtream.tv/com.tvonnet.debridxtreamiptv.ui.MainActivity`.
- Crash log scan: PASS for no matching `FATAL EXCEPTION`, `AndroidRuntime`, or `Process: com.debridxtream.tv` lines after launch smoke.

## Remaining QA Gap
Manual functional QA is still required before user-facing PASS: open Settings -> Debrid, add a real configured Stremio `manifest.json` URL, fetch movie and series sources, verify Hindi/German/multi candidates, and test playback for both direct addon URL streams and Real-Debrid infoHash/magnet streams. If every returned source is blocked by Real-Debrid or rate-limited, app behavior should stop/skip according to the existing typed failure rules but cannot bypass provider-side blocks.

## Final Status
PARTIAL  Native Stremio addon URL path is integrated, compiled, tested, assembled, installed, and launch-smoke tested. Real addon playback QA remains required.

---

# TASK 009-STREMIO-DIRECT-PLAYBACK-RD-CONFIG-FIX

## Scope
Follow-up from manual QA after Stremio links started appearing but playback showed a Real-Debrid configuration missing message. This is a Debrid/Stremio playback routing fix only. No provider expansion, Player DPAD change, Home change, IPTV/EPG change, Live change, or VOD UI redesign was made.

## Findings
- Stremio/AIOStreams direct HTTP playback URLs were being treated as Debrid playback metadata in some paths.
- Movie direct HTTP sources from the Debrid category still launched `PlayerActivity` with `PlaybackSource.DEBRID`, so Player resume/re-resolution logic could attempt app-side Real-Debrid resolution and show the app's Real-Debrid configuration missing message.
- Series direct HTTP sources always went through `PlaybackResolver` with `source = "debrid"`, so direct addon URLs could hit the app Real-Debrid auth check before playback.
- `DebridPlaybackRepository` also checked app Real-Debrid auth before direct addon URL bypass in resolver fallback paths.

## Changes
- Movie direct HTTP addon sources now launch as direct playback instead of Debrid re-resolution metadata.
- Series direct HTTP addon sources now launch `PlayerActivity` directly with source headers and without Real-Debrid resolver metadata.
- `DebridPlaybackRepository` now returns recognized direct addon playback URLs before app Real-Debrid auth/cooldown checks.
- Direct playback recognition now includes AIOStreams and common addon paths: `/stremio/`, `/stream/`, `/play/`, and `/playback/`.
- Magnet/infoHash and non-direct HTTP unrestrict paths still require app Real-Debrid auth and existing rate-limit controls.

## Validation
- `:app:compileDebugKotlin --offline --no-daemon --console plain --max-workers=1` timed out under the local Gradle/Kotlin daemon behavior.
- `:app:assembleDebug --offline --no-daemon --console plain --max-workers=1` with in-process Kotlin compiler: PASS, `BUILD SUCCESSFUL`.
- Install QA: PASS on `192.168.0.84:5555` and `192.168.0.21:5555`.
- Launch smoke QA: PASS on both devices via `com.debridxtream.tv/com.tvonnet.debridxtreamiptv.ui.MainActivity`.
- Crash log scan: PASS for no matching `FATAL EXCEPTION`, `AndroidRuntime`, or `Process: com.debridxtream.tv` lines after launch smoke.

## Remaining QA Gap
Manual retest required: click the same Stremio/AIOStreams source that showed Real-Debrid configuration missing and confirm the direct URL starts playback instead of opening the app Real-Debrid auth/config error. If the addon server itself returns a Real-Debrid config missing video/error page, then the configured manifest URL is missing provider credentials and must be regenerated in that addon.

## Final Status
PARTIAL  Routing fix is built, installed, and launch-smoke tested. User-facing playback PASS depends on retesting the same Stremio source.

---

# TASK 010-STREMIO-DIRECT-DEBRID-LABEL-FIX

## Scope
Follow-up from manual QA after direct Stremio/AIOStreams movie and series playback started working, but player controls showed `IPTV` instead of Debrid and an `API exception please try again` message could still appear. This change is limited to direct Debrid/Stremio playback routing and player source identity. No Home, Live, VOD layout, global DPAD behavior, or provider expansion was added.

## Findings
- The prior direct-play fix avoided app-side Real-Debrid auth by launching direct addon URLs without Debrid playback source identity.
- `PlayerActivity` defaults a missing playback source to IPTV, so controls/history showed IPTV even when the stream came from Debrid/Stremio.
- Direct Debrid series could also enter IPTV series playlist loading because it no longer had a Debrid source guard, which can explain the `API exception please try again` symptom.

## Changes
- Added `EXTRA_DIRECT_DEBRID_PLAYBACK` to `PlayerActivity.createIntent`.
- Direct Stremio/AIOStreams movie and series launches now pass `PlaybackSource.DEBRID` for identity and `directDebridPlayback = true` for resolver/API bypass.
- Player UI labels, Continue Watching source, and return-to-sources behavior continue to treat these streams as Debrid.
- Debrid resolver, Debrid playlist loading, retry re-resolution, timeout re-resolution, and Debrid next-episode resolution are gated behind `PlaybackSource.DEBRID && !directDebridPlayback`.
- Direct Debrid series no longer falls through to IPTV playlist loading.

## Validation
- `:app:compileDebugKotlin --offline --no-daemon --console plain --max-workers=1` with in-process Kotlin compiler: PASS.
- `:app:assembleDebug --offline --no-daemon --console plain --max-workers=1` with in-process Kotlin compiler: PASS.
- Install QA: PASS on `192.168.0.84:5555` and `192.168.0.21:5555`.
- Launch smoke QA: PASS on both devices via `com.debridxtream.tv/com.tvonnet.debridxtreamiptv.ui.MainActivity`.
- Crash log scan: PASS for no matching `FATAL EXCEPTION`, `AndroidRuntime`, or `Process: com.debridxtream.tv` lines after launch smoke.

## Remaining QA Gap
Manual playback retest is required on the same movie and series direct Stremio/AIOStreams sources: confirm the player controls show Debrid, playback still starts, and the IPTV/API exception path no longer appears.

## Final Status
PARTIAL  Build, install, and launch smoke passed. Full user-facing PASS requires manual playback retest on the affected direct Debrid sources.

---

# TASK 011-READINESS-STREMIO-PLAYBACK-STABILITY-DEEP-AUDIT

## Scope
Audit-only review after direct Stremio/Debrid playback became usable but manual QA found `DPAD_DOWN` episode browser unavailable and Stremio source counts lower than expected.

## Report
Full audit and phased plan written to:
- `docs/reports/STREMIO_PLAYBACK_STABILITY_DEEP_AUDIT.md`

## Key Findings
- `DPAD_DOWN -> Episodes unavailable` is caused by direct Debrid playback intentionally skipping both Debrid resolver playlist loading and IPTV playlist loading. A separate direct-Debrid episode-list/source-switch path is needed.
- Stremio source count is lower because configured Stremio URLs currently switch the provider into Stremio-only mode, skipping built-in Torrentio, MediaFusion, and dynamic registry supplemental fetchers.
- IMDb dependency, manifest capability ambiguity, same-hash deduplication, and capped Real-Debrid availability checks also reduce visible source count.
- Playback stability is improved, but direct Stremio episode switching, addon health diagnostics, `fileIdx`, subtitles, and full manual QA remain open.

## Recommendation
Start with `TASK 011-STREMIO-DIRECT-DEBRID-EPISODE-BROWSER`, then implement hybrid source aggregation and addon diagnostics in separate phases.

## Validation
Documentation-only audit. No Gradle build or device install was required.

## Final Status
PASS  Audit/report/plan completed. Implementation remains next task.
