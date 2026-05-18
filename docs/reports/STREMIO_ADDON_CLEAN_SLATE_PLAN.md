# Stremio Addon Clean Slate Plan

## 1. Purpose
- Replace the legacy hardcoded Debrid scraper flow with user-configured Stremio addon `manifest.json` URLs.
- Keep Real-Debrid as the only debrid provider for now.
- Stop duplicate stream searches caused by mixing built-in scrapers, legacy registry URLs, and custom addon URLs.
- Make source fetching faster, easier to debug, and closer to the way Stremio addons expose streams.

## 2. Recommendation
Use a staged clean-slate migration, not a delete-first migration.

The proposed direction is correct, but deleting `SimplifiedPureFireFetcher`, `MediaFusionFetcher`, and the legacy registry flow before a native Stremio manifest path exists would risk leaving the app with no working source provider. The safer plan is:

1. Add the native Stremio addon URL pipeline.
2. Validate it with real movie and series playback.
3. Cut `UnifiedSourceProvider` over to the new pipeline.
4. Remove legacy fetchers and legacy settings only after the new path is proven.

## 3. Target Architecture
- `Settings -> Debrid` exposes one source-management entry: `Manage Custom Stremio Addons`.
- The user stores configured Stremio `manifest.json` URLs.
- The app fetches each manifest, validates supported resources and types, then calls Stremio stream endpoints.
- `UnifiedSourceProvider` uses configured Stremio addons as the single source discovery path after cutover.
- Real-Debrid remains responsible for magnet/infoHash resolution where required.
- Direct HTTP/HTTPS playback URLs from addons bypass Real-Debrid only when classified as direct playable streams.

## 4. Stremio Addon Flow
Expected input:

```text
https://example-addon.example/config-or-token/manifest.json
```

Expected handling:

1. Store the full manifest URL exactly as entered after validation.
2. Fetch and parse the manifest.
3. Confirm the addon supports `stream` resources and relevant catalog types:
   - `movie`
   - `series`
4. Derive stream endpoint base from the manifest URL without losing configured path or token segments.
5. Fetch movie streams using:

```text
{addonBase}/stream/movie/{imdbId}.json
```

6. Fetch series streams using:

```text
{addonBase}/stream/series/{imdbId}:{season}:{episode}.json
```

7. Parse returned streams:
   - `url`: direct or addon-proxied playback candidate.
   - `infoHash`: Real-Debrid candidate.
   - `fileIdx`: optional torrent file index.
   - `name`, `title`, `description`: display and ranking metadata.
   - `behaviorHints.filename`, `videoSize`, `bingeGroup`: quality, dedupe, and episode matching hints.
8. Ignore or mark unsupported stream shapes that cannot be played safely yet, such as `externalUrl`.

## 5. Implementation Phases

### Phase 0: Preflight Audit
- Confirm active Debrid classes, dependency injection bindings, and call sites.
- Confirm how `MovieDetailActivity` and `SeriesDetailActivity` consume resolved sources.
- Confirm current rate-limit and blocked-source handling remains active.
- No code deletion in this phase.

### Phase 1: Native Stremio Addon Models And Fetcher
- Add typed models for Stremio manifests and stream responses.
- Add a dedicated Stremio addon fetcher instead of forcing raw manifests into the old registry model.
- Preserve configured addon base paths and token/config path segments.
- Add focused tests for:
  - manifest URL normalization,
  - movie stream endpoint construction,
  - series stream endpoint construction,
  - `url` stream parsing,
  - `infoHash` stream parsing,
  - missing or unsupported stream resources.

### Phase 2: Preferences And Debrid Settings UI
- Add `stremioAddonUrls` storage in `DebridPreferences`.
- Add `SettingsViewModel` methods to add/remove Stremio addon URLs.
- Replace the Debrid category source-management row with `Manage Custom Stremio Addons`.
- Keep changes strictly inside the Debrid settings area.
- Do not touch IPTV/EPG, Player, Home, Series navigation, Live, or VOD UI.
- Do not log full configured addon URLs because they may contain private tokens.

### Phase 3: Source Provider Cutover
- Update `UnifiedSourceProvider` to use configured Stremio addon URLs for movie and series source discovery.
- Deduplicate streams by stable identifiers:
  - infoHash + file index,
  - direct URL,
  - normalized title + size when stronger IDs are missing.
- Preserve current language-aware ranking for Hindi, German, multi-audio, and English fallback.
- Preserve Real-Debrid availability checks, timeout discipline, and blocked-source auto-skip behavior.

### Phase 4: Playback Routing
- Route `infoHash` and magnet-like streams through Real-Debrid.
- Route clearly direct playable HTTP/HTTPS streams directly to playback.
- Do not send addon playback URLs through Real-Debrid unless they are torrent/magnet candidates.
- Keep typed failure mapping:
  - copyright blocked,
  - legal restriction,
  - unavailable,
  - not cached,
  - rate limited,
  - auth required,
  - network,
  - unknown.
- Auto-try next source only for terminal source failures, not for rate-limit or auth failures.

### Phase 5: Legacy Removal
Only after Phases 1-4 pass build and device QA:

- Remove `SimplifiedPureFireFetcher` from active source discovery.
- Remove `MediaFusionFetcher` from active source discovery.
- Remove legacy registry URL loading.
- Remove old `Manage Scraper Sources` UI.
- Remove unused dependency injection bindings and tests tied only to the legacy flow.
- Keep any useful parsing or ranking code only if it remains active and tested.

### Phase 6: Validation
Required automated validation:

```powershell
.\gradlew.bat clean assembleDebug --no-daemon
```

Required device validation when implementation begins:

```powershell
adb connect 192.168.0.84:5555
adb connect 192.168.0.21:5555
adb -s 192.168.0.84:5555 install -r app\build\outputs\apk\debug\app-debug.apk
adb -s 192.168.0.21:5555 install -r app\build\outputs\apk\debug\app-debug.apk
```

Manual QA checklist:

- Settings -> Debrid shows the new custom Stremio addon management entry.
- Legacy scraper source UI no longer appears after cutover.
- A configured Stremio manifest URL can be added and removed.
- Movie source fetch works from at least one configured addon.
- Series episode source fetch works from at least one configured addon.
- Direct addon playback URL plays without Real-Debrid unrestrict.
- Torrent/infoHash source still resolves through Real-Debrid.
- Copyright-blocked or unavailable source is marked failed and next candidate is tried.
- Rate-limit response does not trigger repeated aggressive retries.
- Logs do not expose full tokenized addon URLs.

## 6. Non-Goals
- No AllDebrid, Premiumize, or other provider work in this phase.
- No Player DPAD behavior changes.
- No Home focus behavior changes.
- No Series skeleton or margin tuning.
- No IPTV/EPG settings changes.
- No claim of Stremio-level parity until real addon, language, and playback QA passes.

## 7. Risks And Controls
- Risk: Some configured manifest URLs include private tokens.
  - Control: redact full URLs in logs and reports.
- Risk: Addon config paths can be lost if base URL is rebuilt incorrectly.
  - Control: derive stream base from the exact manifest URL path.
- Risk: Some addon `url` values are webpage links, not playable video links.
  - Control: classify direct playback conservatively.
- Risk: Deleting legacy fetchers too early can remove all source discovery.
  - Control: add and prove the native Stremio path first.
- Risk: Stremio addon stream shape varies by addon.
  - Control: typed parsing with unsupported-shape handling instead of crashes.
- Risk: Rate-limit can worsen if every addon is queried aggressively.
  - Control: bounded parallelism, caching, dedupe, and no repeated immediate retries on rate-limit.

## 8. Acceptance Criteria
- The app can store and use custom Stremio `manifest.json` URLs.
- Movie and series source fetching works from configured Stremio addons.
- Legacy scraper duplication is removed after the new path is verified.
- Real-Debrid remains the only debrid provider.
- Direct URLs and Real-Debrid torrent/infoHash paths are routed correctly.
- Hindi/German/multi-audio prioritization is preserved where metadata exists.
- Build passes with clean `assembleDebug`.
- Device install and smoke QA pass on requested devices.
- Debrid reports and app pattern files are updated.

## 9. Final Position
The clean-slate direction is good, but it should be executed as a staged cutover.

Build the native Stremio addon pipeline first, prove it with real configured addons and playback, then remove the legacy fetchers and old settings. This gives the clean final system without risking a dead source pipeline during the migration.
