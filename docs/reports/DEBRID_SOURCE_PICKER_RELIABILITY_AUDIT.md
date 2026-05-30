# Debrid Source Picker Reliability Audit
Status: diagnosis only, no runtime changes
Date: 2026-05-27
Scope: Debrid source picker, provider failure handling, fallback, and return behavior

## Current Source Picker Flow

The current flow is:

1. User opens Debrid movie or series detail.
2. Detail screen loads source candidates through `UnifiedSourceProvider`.
3. Candidates are shown in `SourceSelectionBottomSheet`.
4. User selects a source.
5. Movie flow:
   - direct HTTP/addon proxy sources may be preflight-checked before playback
   - magnet/infoHash sources go through `PlaybackResolver` and `DebridPlaybackRepository`
6. Series flow:
   - direct HTTP/addon proxy sources may launch directly
   - magnet/infoHash sources go through `PlaybackResolver` and `DebridPlaybackRepository`
7. On playback failure, `PlayerActivity` can return to the owning detail screen with source-failure extras, or redirect terminal failures back to detail.

## Provider Failure Handling Map

### UnifiedSourceProvider

- Individual provider fetches are usually isolated with `try/catch` inside their own async blocks.
- `MediaFusionFetcher` is wrapped in a safe catch and returns `emptyList()` on failure.
- The configured Stremio addon path catches each manifest fetch independently and returns `emptyList()` per failed addon.
- Dynamic registry addon fetches also catch per-definition errors and continue.
- The outer `getMovieSources()` / `getSeriesEpisodeSources()` methods still have a global `try/catch`, so any exception that escapes later merge/filter/convert steps can abort the whole source request.

### PlaybackResolver

- Debrid source resolution is metadata-driven and only succeeds when hash/magnet or a valid direct passthrough is available.
- Terminal RD failures are surfaced as typed `ResolutionResult.Error`.
- Missing metadata produces `RefreshRequired`.
- Non-Debrid URLs are passed through if present.

### DebridPlaybackRepository

- Direct addon proxy URLs are checked with `isAddonProxyPlaybackReady()`.
- Real-Debrid resolution classifies auth, rate-limit, and terminal failures.
- Direct addon URLs can be passed through only when permitted.

### Detail screens

- `MovieDetailActivity` and `SeriesDetailActivity` both preserve the source-failure result contract from `PlayerActivity`.
- Both can auto-advance to the next source for selected failure types.
- Both can fall back to reopening the source picker.

## MediaFusion / AIO Exception Path

Observed behavior:

- MediaFusion fetch failures are caught in `UnifiedSourceProvider` at the provider call site.
- MediaFusion proxy playback errors are detected in `MovieDetailActivity` and `SeriesDetailActivity` through addon readiness checks.
- AIOStreams-style direct URLs are treated as addon proxy URLs in the readiness check path.
- When the readiness check fails in the movie flow, the source is marked not cached and the picker is reopened.
- In the series flow, cached state can be updated, but the direct HTTP branch does not use the same playback-time readiness gate as the movie flow.

Conclusion:

- MediaFusion/AIO failures are partially isolated, but not uniformly handled.
- The source provider side is resilient to a single provider throwing.
- The playback side is not equally strict between movie and series.

## Source Failure Return Behavior

### Auto-skip behavior

- Both movie and series detail flows support auto-skip to the next source for terminal source failures.
- Auto-skip is limited to the current filtered source list.
- If there is no next source, the user is returned to the picker.

### Return to picker behavior

- Movie flow returns to the picker when:
  - the current source is not ready after addon proxy preflight
  - the selected source resolves with `RefreshRequired`
  - no next source exists
  - source loading fails
- Series flow returns to the picker when:
  - the current source resolves with `RefreshRequired`
  - no next source exists
  - source loading fails

### Terminal playback failure behavior

- `PlayerActivity` can redirect exhausted Debrid failures back to the owning detail screen.
- The detail screen receives `EXTRA_OPENED_FROM_PLAYBACK_FAILURE` and `EXTRA_FAIL_REASON`.
- The detail screen then lets the user retry, or use the source picker path again.

## Provider Family Separation

The picker does not cleanly separate provider families.

What exists:

- One flat list of sources.
- Text labels that include provider names.
- Filters for language, size, and cached-only state.
- Selected-source highlighting.

What does not exist:

- A provider-grouped sectioned layout.
- Explicit family headers such as MediaFusion, Stremio, Comet, StremThru, or DMM.
- Visual grouping that makes addon families obvious at a glance.
- Separate retry/fallback rules presented by provider family.

Conclusion:

- The picker is functionally usable, but structurally flat.
- It relies on item labels instead of family sections.

## Duplication Between Movie and Series

There is substantial duplication in the two detail screens:

- Both maintain cached sources.
- Both maintain selected stream id state.
- Both maintain filter state.
- Both perform auto-next failure handling.
- Both show the same bottom-sheet picker.
- Both have `notifyDebridFailure()` variants with nearly identical behavior.
- Both use the same `isAddonProxyPlaybackUrl()` logic.
- Both map source failures back to the picker when the current path cannot continue.

Notable difference:

- Movie flow preflights addon proxy playback before launching.
- Series flow launches direct HTTP addon sources immediately and only uses the direct resolve branch for non-direct sources.

That makes series reliability weaker for direct addon/proxy sources than movie reliability.

## Ranked Reliability Issues

### 1. Movie/series mismatch in addon proxy readiness gating

- Movie and series do not handle addon proxy readiness the same way.
- Movie preflights readiness before playback.
- Series direct HTTP branch does not.
- This can produce inconsistent behavior for the same provider family.

### 2. Flat source picker hides provider-family failure context

- The picker shows provider names in labels, but not as grouped families.
- Users cannot easily tell whether one family is failing versus the whole title having no sources.

### 3. Outer source-fetch exception can still wipe the whole request

- Individual fetchers are isolated, but later merge/filter/convert steps still run under one outer try/catch.
- A malformed stream or a conversion exception can turn a partial success into an empty result.

### 4. Retry / return behavior is split across detail, resolver, repository, and player

- Failures are handled in multiple places.
- This is workable, but it makes the user experience inconsistent when the same failure type is reached through different paths.

### 5. Provider failure reason is not uniformly surfaced

- Toasts often preserve the message.
- The bottom sheet itself mostly shows generic empty/error states.
- Redirected detail screens do not visibly surface the failure reason in a dedicated UI element.

### 6. Short/bad source handling is only partially centralized

- Proxy error-video detection exists in the repository preflight.
- The picker itself does not explain whether a source was removed because it was short, proxy-only, or terminally blocked.

## Recommended First Fix

Smallest high-value reliability fix:

- Make the movie and series source-failure return contract consistent for addon proxy/direct playback readiness.
- In practice, this means the same readiness gate and fallback decision should be applied before playback in both screens, especially for MediaFusion/AIO-style proxy URLs.

Why this first:

- It directly addresses the most visible reliability gap.
- It does not require a new provider system.
- It does not require DMM.
- It reduces the chance of a bad source opening the player and then bouncing back.
- It narrows the difference between movie and series behavior without changing the broader architecture.

## Exact Files For The First Fix

Primary files:

- `app/src/main/java/com/tvonnet/debridxtreamiptv/ui/vod/MovieDetailActivity.kt`
- `app/src/main/java/com/tvonnet/debridxtreamiptv/ui/series/SeriesDetailActivity.kt`

Support files to inspect during that fix:

- `app/src/main/java/com/tvonnet/debridxtreamiptv/data/debrid/repository/DebridPlaybackRepository.kt`
- `app/src/main/java/com/tvonnet/debridxtreamiptv/data/debrid/repository/UnifiedSourceProvider.kt`
- `app/src/main/java/com/tvonnet/debridxtreamiptv/ui/series/SourceSelectionBottomSheet.kt`
- `app/src/main/java/com/tvonnet/debridxtreamiptv/data/debrid/repository/PlaybackResolver.kt`

Player context only, if needed for source-return semantics:

- `app/src/main/java/com/tvonnet/debridxtreamiptv/player/stabilized/PlayerActivity.kt`

## QA Plan

After any future reliability fix, validate in this order:

1. Debrid movie source picker opens and retains the selected source.
2. Debrid series source picker opens and retains the selected episode/source.
3. MediaFusion source loads and a proxy readiness failure returns to the picker cleanly.
4. AIOStreams source behaves the same as MediaFusion for readiness/fallback.
5. A resolved magnet/infoHash source still goes through Real-Debrid playback.
6. A direct addon URL still plays as direct Debrid playback.
7. A terminal source failure returns to the owning detail screen with a visible recovery path.
8. Continue Watching resume still re-resolves instead of replaying stale direct URLs.

Recommended runtime verification later:

- `:app:compileDebugKotlin`
- `:app:assembleDebug`
- Manual QA on a Debrid movie and a Debrid series source picker run
- Manual QA of at least one MediaFusion-style proxy source and one direct addon source

## Bottom Line

The current source picker is resilient enough to keep sibling providers alive during normal fetch failures, but it is not consistent enough at playback-time fallback, especially between movie and series. The smallest first reliability fix is to unify the addon proxy readiness and fallback path in the two detail screens, then use that as the base for later provider-family grouping work.
