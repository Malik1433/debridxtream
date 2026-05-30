# Debrid DMM Integration Plan

Date: 2026-05-27
Mode: Research + Mapping Only
Runtime code changes: none

## Recommendation

Proceed, but do not add Debrid Media Manager (DMM) as a default blended provider yet.

Safest path:
1. Treat DMM Cast as a user-configured Stremio addon for title-level stream lookup.
2. Treat DMM catalogs as a separate Debrid library surface, for example "My Debrid Library / DMM", only after adding explicit Stremio catalog parsing.
3. Do not persist DMM direct playback URLs as resume identity. Persist stable title/source metadata and refresh the DMM stream endpoint before playback.

DMM should not replace or modify MediaFusion, AIO, Comet, StremThru, Real-Debrid resolver playback, or Continue Watching.

## Research Sources

- DMM repository README: https://github.com/debridmediamanager/debrid-media-manager
- DMM Stremio manifest source: https://raw.githubusercontent.com/debridmediamanager/debrid-media-manager/main/src/pages/api/stremio/%5Buserid%5D/manifest.json.ts
- DMM Stremio stream endpoint source: https://raw.githubusercontent.com/debridmediamanager/debrid-media-manager/main/src/pages/api/stremio/%5Buserid%5D/stream/%5BmediaType%5D/%5Bimdbid%5D.ts
- DMM Stremio catalog endpoint source: https://raw.githubusercontent.com/debridmediamanager/debrid-media-manager/main/src/pages/api/stremio/%5Buserid%5D/catalog/movie/casted-movies.json.ts
- DMM Stremio play endpoint source: https://raw.githubusercontent.com/debridmediamanager/debrid-media-manager/main/src/pages/api/stremio/%5Buserid%5D/play/%5Blink%5D.ts
- DMM data model source: https://raw.githubusercontent.com/debridmediamanager/debrid-media-manager/main/prisma/schema.prisma

## Current Debrid Provider Architecture Summary

The app already has two separate Debrid surfaces:

- Debrid discovery/catalog UI:
  - `AddonCatalogRepository.kt`
  - `DebridViewModel.kt`
  - TMDB trending/discovery/search rows
  - local app favorites as "My Library"
  - local Continue Watching from `WatchHistoryPreferences`

- Debrid source lookup/playback:
  - `UnifiedSourceProvider.kt`
  - `StremioAddonFetcher.kt`
  - `AddonModels.kt`
  - `AddonMappers.kt`
  - `MovieDetailActivity.kt`
  - `SeriesDetailActivity.kt`
  - `PlaybackResolver.kt`
  - `DebridPlaybackRepository.kt`
  - `PlayerActivity.kt`

For source lookup, `UnifiedSourceProvider` can already use configured Stremio addon manifest URLs from `DebridPreferences.getStremioAddonUrls()`. `StremioAddonFetcher` normalizes `stremio://` to `https://`, fetches `manifest.json`, verifies stream support, and calls:

- `stream/movie/{imdbId}.json`
- `stream/series/{imdbId}:{season}:{episode}.json`

It maps returned Stremio streams into `AddonStream` using fields such as `url`, `infoHash`, `magnet`, `fileIdx`, `behaviorHints.bingeGroup`, `behaviorHints.proxyHeaders`, headers, size, quality, and language metadata.

Playback then branches:

- Hash/magnet sources go through `PlaybackResolver` and `DebridPlaybackRepository` for Real-Debrid resolution.
- Direct HTTP/addon proxy sources are treated as Debrid playback identity with direct-source guards and headers.
- Resume/CW must not trust stale direct URLs; stable metadata must be used to refresh or resolve again.

## Does The App Already Support Generic Stremio Addon Manifest URLs?

Yes, for stream lookup.

Current support exists in:

- `DebridPreferences.kt`: stores `pref_stremio_addon_urls`, normalizes `stremio://` to `https://`, and exposes add/remove/set/get APIs.
- `SettingsFragment.kt` / `SettingsViewModel.kt`: exposes "manage Stremio addons".
- `CompanionSetupActivity.kt`, `CompanionConfigServer.kt`, and `RemotePairingManager.kt`: sync companion-configured Stremio addon URLs.
- `StremioAddonFetcher.kt`: fetches manifest and stream endpoints.
- `UnifiedSourceProvider.kt`: uses configured Stremio addon URLs for movie and episode sources.

The current support is stream-centric. It does not currently parse Stremio catalog rows into the Debrid home/library surface.

## Can DMM Be Consumed Through A Stremio Addon?

Yes, with limits.

DMM provides a Stremio addon named `DMM Cast for Real-Debrid`. Its manifest declares:

- stream resource for `movie` and `series`
- catalog resources for casted movies, casted shows, and other library items
- a user-scoped path shape under `/api/stremio/{userid}/...`

The DMM stream endpoint accepts IMDb IDs:

- movie: `/api/stremio/{userid}/stream/movie/{imdbid}`
- series: `/api/stremio/{userid}/stream/series/{imdbid}:{season}:{episode}`

This matches the app's existing `StremioAddonFetcher` request model, assuming the user provides a valid DMM Stremio manifest URL and the title has an IMDb ID.

## Does DMM Expose Catalogs/Streams Compatible With Existing Parser?

Streams: mostly yes.

DMM stream responses return Stremio `streams`. For casted items, DMM returns stream entries with `url` values pointing to DMM playback endpoints or stored direct URLs. The existing parser can read `url`, `name`, `title`, and `behaviorHints.bingeGroup`.

Catalogs: no current app support.

DMM exposes Stremio catalog endpoints, but the app's Debrid home/library rows do not currently parse generic Stremio `catalog/*/*.json` responses. `AddonCatalogRepository` currently builds Debrid catalog rows from TMDB discovery/search, local favorites, and local Continue Watching. Adding DMM library rows requires explicit catalog parsing and mapping, not only source-provider registration.

Important compatibility gap: DMM streams appear to return playable URLs rather than hash/magnet identity in the Stremio stream response. That makes DMM easy to launch but risky for durable resume unless playback refreshes the DMM endpoint before using a URL.

## Integration Classification

A) Normal source search addon: safe only as an opt-in configured Stremio addon.

DMM can participate in source picker results for a specific movie/episode when a user has configured the DMM manifest URL. Do not make it a default global provider because:

- It is user/profile-scoped.
- Results are cast/library-dependent.
- It can add "Cast a file" external actions that are not direct playback.
- DMM results can duplicate or confuse other addon sources.

B) Debrid library provider: recommended for DMM catalog/library browsing.

DMM's strongest fit is a separate "My Debrid Library / DMM" row or section because DMM is centered around casted/library content. This should be added through a dedicated Stremio catalog reader that consumes DMM catalog endpoints and maps IMDb IDs to the app's detail screens.

C) Real-Debrid library shortcut: possible but too narrow.

DMM itself stores cast profile data and resolves through its own DMM/RD endpoints. Treating it as only a Real-Debrid shortcut would miss DMM's casted catalog, shared/other streams, and profile behavior.

D) Separate "My Debrid Library / DMM" section: recommended.

This avoids source picker confusion and keeps DMM's library semantics separate from normal provider scraping.

## Auth Needed

Likely requirements by integration mode:

- Source lookup via DMM Stremio addon:
  - A DMM Stremio manifest/config URL containing the DMM user/profile identifier.
  - DMM-side Real-Debrid authorization must already be valid.
  - The app's local Real-Debrid token is not enough by itself to query a DMM Cast profile unless DMM has been configured.

- Direct Real-Debrid resolver path:
  - App Real-Debrid token.
  - Stable `infoHash` or `magnet`.
  - This is the current safest path for durable playback, but DMM stream responses may not provide hash/magnet.

- DMM library section:
  - Same DMM Stremio manifest/config URL.
  - Catalog endpoints are user-scoped by DMM user ID.
  - If DMM authorization expires, DMM endpoints can fail or return update/reauth entries.

Do not log DMM user IDs if they become sensitive in practice, and never log tokens, stream URLs, RD links, magnets, hashes, usernames, or passwords.

## Playback Identifiers Needed

Stable identifiers to prefer:

- `tmdbId`: app detail/navigation identity when available.
- `imdbId`: required by DMM Stremio stream and catalog endpoints.
- `seasonNumber` and `episodeNumber`: required for DMM series stream lookup.
- provider profile: DMM manifest URL/profile key or stable provider ID.
- `bingeGroup`: useful for grouping source picker rows, not sufficient as durable identity.
- `infoHash` / `magnet`: preferred if DMM ever exposes them.
- `fileId` / link token: only if DMM exposes a stable non-secret file/link identity and playback can refresh it.

Identifiers to avoid as durable identity:

- direct DMM playback URL
- DMM redirect URL
- unrestricted Real-Debrid download URL
- any URL containing auth/session/token material

## Proposed Playback Flow

### Source Picker Flow

1. User opens movie/episode detail.
2. App resolves title to IMDb ID.
3. `UnifiedSourceProvider` calls configured DMM Stremio stream endpoint through `StremioAddonFetcher`.
4. DMM stream rows are normalized as Debrid sources with provider label such as `DMM Cast`.
5. Source picker displays DMM rows separately from MediaFusion/AIO/Comet/StremThru rows.
6. On source select:
   - If hash/magnet exists, use existing `PlaybackResolver` and `DebridPlaybackRepository`.
   - If only direct DMM URL exists, launch as fresh direct Debrid playback with source metadata and headers.
7. `PlayerActivity` receives `PlaybackSource.DEBRID` and DMM provider/source profile metadata.

### Library Flow

1. User enables a DMM library section with a DMM manifest URL.
2. New catalog reader fetches DMM manifest and DMM catalog endpoints.
3. Catalog metas are mapped to Debrid content rows using IMDb ID and display metadata.
4. Selecting an item opens existing movie/series detail screens.
5. Detail screens fetch fresh sources from DMM stream endpoint before playback.

Do not play directly from catalog rows unless the app has refreshed a source immediately before launch.

## Continue Watching Rules

Continue Watching for DMM must follow the existing stale-direct-url rules:

- Persist stable title identity:
  - `contentType`
  - `tmdbId` when available
  - `imdbId`
  - season/episode for episodes
  - display metadata
  - provider/source profile, for example DMM manifest/profile key
- Persist stable playback identity if available:
  - `infoHash`
  - `magnet`
  - file/link identifier only if it can be refreshed safely
- Do not persist DMM direct URLs as durable resume identity.
- On resume, refresh the DMM stream endpoint or use hash/magnet through the resolver before playback.
- If DMM only returns direct URLs, treat stored direct URL as expired and require a fresh DMM source lookup before playback.
- If DMM auth/session expired, route back to detail/source picker with a clear failure state rather than silently falling back to stale URL playback.

## How To Avoid Expired Direct URL Issues

- Store provider profile plus title identity, not the final media URL.
- For source picker playback, treat DMM direct URLs as fresh only for the current click.
- For Continue Watching, call DMM stream endpoint again using IMDb ID and episode coordinates.
- Match refreshed source by stable DMM grouping if available (`bingeGroup`, provider label, title, file/link ID), not by URL.
- If no stable match exists, return to source picker and ask the user to choose again.
- Keep `allowDirectHttpPassthrough=false` for stale history paths unless the URL has just been refreshed in the same user action.

## Existing Code That Can Be Reused Safely

- `DebridPreferences.kt`: generic Stremio addon URL storage.
- `StremioAddonFetcher.kt`: DMM manifest and stream endpoint fetch for title-level source lookup.
- `AddonModels.kt`: Stremio stream response parsing.
- `UnifiedSourceProvider.kt`: source picker aggregation and conversion into `MovieSource`.
- `MovieDetailActivity.kt`: Debrid movie source picker and DMM direct/resolver-backed launch path.
- `SeriesDetailActivity.kt`: Debrid series source picker and source failure return path.
- `PlaybackResolver.kt`: hash/magnet resolver path.
- `DebridPlaybackRepository.kt`: RD resolution and direct URL passthrough rules.
- `PlayerActivity.kt`: Debrid playback identity, short bad-source guard, source picker return path.
- `WatchHistoryPreferences.kt`: Continue Watching persistence, with DMM-specific stable metadata only.

## Files Likely Needed Later

Provider config only:

- `app/src/main/java/com/tvonnet/debridxtreamiptv/data/prefs/DebridPreferences.kt`
- `app/src/main/java/com/tvonnet/debridxtreamiptv/ui/settings/SettingsFragment.kt`
- `app/src/main/java/com/tvonnet/debridxtreamiptv/ui/settings/SettingsViewModel.kt`
- `app/src/main/java/com/tvonnet/debridxtreamiptv/network/CompanionConfigServer.kt`
- `app/src/main/java/com/tvonnet/debridxtreamiptv/network/CompanionSetupActivity.kt`
- `app/src/main/java/com/tvonnet/debridxtreamiptv/network/RemotePairingManager.kt`

DMM source lookup:

- `app/src/main/java/com/tvonnet/debridxtreamiptv/data/debrid/source/StremioAddonFetcher.kt`
- `app/src/main/java/com/tvonnet/debridxtreamiptv/data/debrid/model/AddonModels.kt`
- `app/src/main/java/com/tvonnet/debridxtreamiptv/data/debrid/repository/UnifiedSourceProvider.kt`
- `app/src/main/java/com/tvonnet/debridxtreamiptv/ui/activities/MovieDetailActivity.kt`
- `app/src/main/java/com/tvonnet/debridxtreamiptv/ui/activities/SeriesDetailActivity.kt`

DMM library/catalog:

- `app/src/main/java/com/tvonnet/debridxtreamiptv/data/debrid/api/AddonCatalogService.kt`
- `app/src/main/java/com/tvonnet/debridxtreamiptv/data/debrid/repository/AddonCatalogRepository.kt`
- `app/src/main/java/com/tvonnet/debridxtreamiptv/data/debrid/repository/DebridCatalogItem.kt`
- `app/src/main/java/com/tvonnet/debridxtreamiptv/ui/debrid/DebridViewModel.kt`
- Debrid row/adapter classes only if a new row type or action state is required.

Playback/CW only if DMM-specific resume identity is added:

- `app/src/main/java/com/tvonnet/debridxtreamiptv/data/prefs/WatchHistoryPreferences.kt`
- `app/src/main/java/com/tvonnet/debridxtreamiptv/data/debrid/repository/PlaybackResolver.kt`
- `app/src/main/java/com/tvonnet/debridxtreamiptv/data/debrid/repository/DebridPlaybackRepository.kt`
- `app/src/main/java/com/tvonnet/debridxtreamiptv/ui/activities/PlayerActivity.kt`

## Risks

### Expired URLs

DMM stream and play endpoints can return redirect/direct URLs that are not durable. Persisting those as Continue Watching identity would recreate stale-direct-url failures.

### Auth/Session

DMM Cast stores Real-Debrid credentials/profile server-side for the user-scoped Stremio addon. If DMM auth expires, stream/play endpoints can fail even when the app's local RD token is valid.

### Provider API Changes

DMM is an external app. Manifest shape, catalog IDs, stream response fields, profile/token behavior, or play endpoints can change independently of this app.

### Duplicate Results

DMM can overlap with MediaFusion, AIO, Comet, StremThru, and Real-Debrid-backed resolver sources. DMM rows should be labeled and deduped by provider/profile/title metadata without removing existing providers.

### Source Picker Confusion

DMM can return a "Cast a file" external action as a stream option. The app should not present external cast/setup actions as playable sources unless explicitly supported.

### Catalog Identity

DMM catalogs expose IMDb-based Stremio metas. The app detail screens currently prefer TMDB IDs for navigation. A DMM library phase must include IMDb-to-TMDB mapping or a detail route that can safely resolve by IMDb ID.

### Direct URL Headers

If DMM returns headers or proxy requirements, the source picker and PlayerActivity path must preserve them without logging sensitive values.

## Phase Plan

### Phase 0: Mapping Only

Status: this document.

No runtime code changes. Confirmed the app already supports generic Stremio stream manifests but not generic Stremio catalogs.

### Phase 1: Add Provider Config Only

Goal: make DMM opt-in without changing playback behavior.

- Allow user to add a DMM Stremio manifest/config URL through the existing Stremio addon URL system.
- Validate only URL shape and manifest capabilities.
- Do not register DMM as a built-in default provider.
- Do not alter MediaFusion/AIO/Comet/StremThru behavior.

### Phase 2: Read DMM Catalog/Library

Goal: add a separate "My Debrid Library / DMM" row or section.

- Add generic Stremio catalog parsing.
- Read DMM catalog IDs from manifest rather than hardcoding where feasible.
- Map IMDb IDs to existing movie/series detail screens.
- Keep DMM library separate from normal source search rows.

### Phase 3: Playback Integration

Goal: play DMM library/source results through existing PlayerActivity safely.

- Refresh DMM stream endpoint immediately before playback.
- Use hash/magnet resolver path when available.
- Use direct DMM URL only as fresh direct Debrid playback.
- Preserve provider label/profile metadata and headers.
- Filter non-playable external cast/setup options.

### Phase 4: Continue Watching QA

Goal: prove DMM resume does not reuse stale URLs.

- Save DMM CW entries with stable title/provider metadata.
- Resume by refreshing DMM stream endpoint or resolving hash/magnet.
- Confirm stale direct URL is not reused.
- QA on both TVs for DMM, existing Debrid providers, IPTV VOD/series, and Live TV zapping.

## Recommended Future QA

- Add DMM manifest URL through settings/companion config.
- Open a DMM-supported movie with IMDb ID and confirm source picker rows appear.
- Select a DMM direct stream and verify playback starts.
- Confirm "Cast a file" external action is not treated as playable media.
- Resume DMM Continue Watching after URL expiry and verify the app refreshes the DMM endpoint.
- Compare DMM source picker with MediaFusion, AIO, Comet, and StremThru to confirm no provider regression.
- Verify series episode lookup uses `tt...:season:episode` and opens the correct episode.
- Verify no logs contain DMM URLs, RD links, hashes, magnets, tokens, usernames, or passwords.

## Final Position

Proceed with DMM integration only as an opt-in provider/library plan.

The app can already consume DMM as a Stremio stream addon for title-level source lookup, but DMM's library/catalog behavior should be implemented as a separate Debrid library surface. The highest-risk area is not initial playback; it is stale direct URL reuse in Continue Watching. Any future implementation must refresh DMM streams before playback and keep durable identity based on metadata, not final playback URLs.
