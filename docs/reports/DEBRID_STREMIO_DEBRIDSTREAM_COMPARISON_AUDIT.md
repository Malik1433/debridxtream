# Debrid / Stremio / Debrid Stream Comparison Audit
Status: audit-only, no runtime changes
Date: 2026-05-27
Scope: current Debrid section, source discovery, playback, resume, and DMM fit before implementation

## 1. Current Debrid Architecture Summary

The current Debrid area is already split into three runtime layers:

- Debrid home / browse layer:
  - `DebridFragment` renders a TMDB-style browse hub with sidebar navigation, Continue Watching, My Library, trending rows, and region/provider discovery rows.
  - `DebridViewModel` builds that catalog from local app data plus TMDB discovery/search and cached Continue Watching state.
  - `DebridAuthFragment` handles Real-Debrid device-code login plus a hidden manual token fallback.

- Debrid detail / source lookup layer:
  - `MovieDetailActivity`, `SeriesDetailActivity`, and `SourceSelectionBottomSheet` own source discovery, filtering, and source choice.
  - `UnifiedSourceProvider` aggregates multiple provider families, including Stremio manifest URLs, dynamic registry addons, MediaFusion, and the current PureFire/Torrentio-style paths.

- Shared playback / resume layer:
  - `PlaybackResolver` is the central resolution gate.
  - `DebridPlaybackRepository` resolves magnets/infoHashes through Real-Debrid and preflights direct addon proxy URLs.
  - `PlayerActivity` is the single playback host for IPTV, Live, and Debrid.
  - `WatchHistoryPreferences` and `PlayerHistoryManager` store Continue Watching state with durable identity fields, not just URLs.

What the app does well today:

- It avoids duplicate player activities.
- It preserves Debrid identity in `PlayerActivity` instead of downgrading direct addon playback to IPTV.
- It already carries source profile metadata through source picker, player intent, and history.
- It already supports separate Stremio manifest URL storage instead of mixing manifests into registry definitions.
- It already uses fresh-resolution behavior for Debrid resume rather than trusting old unrestricted URLs.

What is still missing:

- No explicit DMM surface exists.
- No Stremio catalog parser exists for Debrid home rows.
- No DMM-specific library/catalog surface exists.
- Source grouping is still provider-text driven rather than a first-class provider section model.
- Some source and playback behavior is still duplicated across Movie and Series detail screens.

## 2. Stremio-Style Behavior Comparison

Stremio-style behavior is addon-first:

- A manifest is the canonical addon contract.
- Streams are fetched from addon endpoints on demand.
- Catalog endpoints are first-class and drive browse surfaces.
- Direct addon playback is distinct from Real-Debrid hash/magnet resolution.
- Stream identity is usually tied to addon metadata, not to a local app-specific library object.

Current app alignment:

- The app already supports Stremio manifest URLs in `DebridPreferences`.
- `UnifiedSourceProvider` already fetches Stremio movie and episode streams.
- `SourceSelectionBottomSheet` already shows quality, language, cache state, and provider labels.
- Direct addon URLs are already treated as direct playback when they are fresh.

Current gaps versus Stremio:

- The app does not parse generic Stremio catalogs into Debrid home rows.
- The app does not expose catalog resources as a first-class browse model.
- Manifest-backed addon streams exist, but catalog-backed library browsing does not.
- The app still mixes addon-style streams with TMDB browse rows in the Debrid home section.
- Provider grouping is not cleanly surfaced as "addon family" versus "library family".

Practical takeaway:

- The app is already closer to Stremio stream behavior than to Stremio catalog behavior.
- DMM can fit the stream side first because that matches the existing implementation path.

## 3. Debrid Stream-Style Behavior Comparison

Debrid Stream-style behavior is library/resume-first:

- Stable title identity matters more than one-shot stream URLs.
- Resume is expected to work from durable metadata.
- A library surface is part of the primary UX, not an afterthought.
- Provider/source profile continuity matters across next/resume actions.

Current app alignment:

- Continue Watching is already durable and metadata-driven.
- `ContinueWatchingItem` stores TMDB/IMDb, series identity, season/episode, source profile, quality, languages, stream id, binge group, file index, and expiry.
- `WatchHistoryPreferences` dedupes by durable identity rather than by raw URL alone.
- `PlayerActivity` and `PlayerViewModel` already carry source profile metadata forward for re-resolution and next-episode continuity.

Current gaps versus Debrid Stream:

- There is no dedicated "My Debrid Library / DMM" surface.
- The existing Debrid home is still a mixed browse hub, not a clean library-first shell.
- The only library-like items are local favorites and Continue Watching, not external DMM library entries.
- Catalog browsing is TMDB-native, not addon/library-native.
- DMM items would need explicit catalog parsing before the UX can feel library-first.

Practical takeaway:

- The app already has the resume foundation Debrid Stream needs.
- It does not yet have the library surface Debrid Stream would expect.

## 4. DMM Integration Recommendation

Recommendation: do not implement DMM as a default blended provider yet.

Best structure:

1. Phase 2: add DMM as an opt-in stream provider inside the existing source picker.
2. Phase 3: add a separate "My Debrid Library / DMM" surface only after catalog parsing exists.
3. Phase 4: expand catalog/search once DMM catalog behavior is proven.

Why this order is best:

- The app already has a safe stream-provider path for Stremio-style manifests.
- The app does not yet have a generic Stremio catalog ingestion layer.
- A library surface without catalog parsing would be fake structure.
- Adding DMM to source picker first limits risk and reuses existing source/profile rules.
- DMM direct URLs should not be treated as durable resume identity.

Decision:

- Implement DMM now: no.
- First DMM mode, when work starts: opt-in stream provider in source picker.
- DMM library surface should wait until catalog parsing and identity rules are proven.

## 5. Missing Features List

- Generic Stremio catalog parsing for Debrid browse rows.
- DMM catalog ingestion for movie/show library surfaces.
- Explicit DMM source/provider section in the picker.
- Provider-family grouping in the picker UI.
- A clean separation between "browse catalog" and "source lookup" for Debrid.
- A DMM-specific library row or section with stable identity mapping.
- Stronger provider-level error summaries for addon API exceptions.
- Better surfacing for direct addon freshness versus durable history identity.
- More explicit fallback rules for provider failure, terminal failure, and source exhaustion.

## 6. Existing Bugs / Reliability Gaps

Confirmed or still-open issues from current reports and source:

- MediaFusion and AIO addon sources can intermittently throw API exceptions during Debrid source resolution.
- StremThru is currently the more reliable direct addon path.
- Debrid Continue Watching resume is still intermittent.
- Expired/null `expiresAt` resume can still stall after initial playback start in the current open issue set.
- Direct addon and proxy source handling still requires careful freshness checks.
- Source picker failure handling is split across Movie and Series detail activities.
- Some direct addon sources still depend on proxy readiness checks that can return error videos instead of playable content.
- The Debrid home section is still a browse hub, not a dedicated library surface.

Current mitigations that are already in place:

- Stale Debrid history URLs are not trusted as durable playback identity.
- Direct addon URLs are treated as fresh only for the current action.
- `PlaybackResolver` ignores stored Debrid URLs and prefers metadata resolution.
- `DebridPlaybackRepository` classifies terminal failures and rate limits.
- `PlayerActivity` can redirect terminal failures back to detail screens instead of looping the same source.

## 7. Architecture Risks

High-risk areas:

- `PlayerActivity` is still the main coupling point for playback, retry, return-to-sources, source profile continuity, and terminal failure routing.
- `UnifiedSourceProvider` contains provider-specific heuristics and string matching for source families.
- `MovieDetailActivity` and `SeriesDetailActivity` duplicate a lot of Debrid playback branching.
- `PlaybackResolver` relies on a string source discriminator and a direct-http passthrough switch.
- `UnifiedSourceProvider.convertAddonStreamsToMovieSources()` synthesizes stream ids in some fallback cases, which is not durable enough for library identity.
- `contentId`, `stream_id`, `tmdbId`, and `infoHash` are still overloaded across different branches.

What must not be touched casually:

- `PlayerActivity` coupling and intent contract.
- Existing Debrid playback behavior.
- Direct addon freshness rules.
- Continue Watching identity rules.
- Shared source picker implementation.
- The single player host model.

## 8. UX Gaps

Debrid home / section:

- Looks more like a TMDB browse hub with Debrid flavor than a dedicated addon/library center.
- "Library", "Movies", and "Series" sidebar items are still visually present but not fully realized as distinct Debrid modes.
- There is no DMM-specific home entry or library surface.

Source picker:

- The picker is functional, but still mostly a flat list with filters rather than a provider-sectioned model.
- Provider labels are present, but provider families are not visually grouped.
- The user cannot clearly distinguish "source lookup" from "library items" by UI structure.

Playback / failure:

- Error handling is better than before, but still not fully professional at the provider-family level.
- Terminal failures can still feel like "try again" if the source-specific reason is not clear enough.
- Short/bad sources are handled, but the UX still depends on backend readiness checks and generic toast text.

Android TV specifics:

- Focus restoration in Debrid browse is good.
- Source picker auto-focus is good.
- Back behavior is mostly correct.
- The missing piece is not navigation mechanics; it is structural clarity and provider/layer separation.

## 9. Prioritized Roadmap

### Phase 0: Fix reliability blockers

- Stabilize MediaFusion/AIO API exception handling.
- Keep terminal failures separate from retryable failures.
- Verify direct addon freshness checks and error-video detection.
- Keep expired-history resume from replaying stale URLs.
- Confirm continue-watching resume is stable before adding new provider complexity.

### Phase 1: Clean source picker behavior

- Make provider-family grouping more explicit.
- Preserve provider/source/language continuity more consistently.
- Improve fallback behavior when a source fails.
- Keep bad short sources from re-entering the same failed path.
- Keep return-to-sources behavior stable and predictable.

### Phase 2: Add DMM as opt-in stream provider

- Use the existing Stremio-manifest path as the integration entry point.
- Keep DMM opt-in, not default blended.
- Treat DMM direct URLs as fresh playback only, not durable history identity.
- Reuse the current source picker and player intent contract.

### Phase 3: Add DMM / My Debrid Library surface

- Add only after catalog parsing exists.
- Map catalog items to stable app identities.
- Keep DMM library separate from source picker semantics.
- Do not create a second duplicated Debrid system.

### Phase 4: Catalog / search improvements

- Add generic Stremio catalog parsing if needed.
- Unify browse and addon catalog concepts carefully.
- Avoid making Debrid browse feel like a clone of the source picker.

## 10. Clear Decision

Decision: do not implement DMM yet.

What must be fixed first:

- Debrid source reliability, especially intermittent addon exceptions.
- Continue Watching resume stability.
- Explicit separation between stable library identity and fresh direct URLs.
- Cleaner source grouping and fallback behavior.
- Catalog parsing support if a library surface is planned.

If DMM work starts after that:

- Start with opt-in stream-provider mode.
- Do not start with a library surface.
- Do not make DMM the default blended provider.
- Do not store DMM direct playback URLs as resume identity.

## Bottom Line

The current Debrid system is strongest as a shared playback and source-resolution spine. It already has enough structure to support DMM as an opt-in stream source, but it does not yet have the catalog/librarization layer required for a professional DMM library surface. The correct professional order is reliability first, picker cleanup second, DMM stream provider third, DMM library surface only after catalog support.
