# Debrid-Agnostic Playback Stability Strategy

Date: 2026-05-31

Scope: architecture mapping only. No runtime code changes.

## Evidence Baseline

The latest playback diagnostics show that direct playback is a valid path, not a
fallback to disable:

- AIO and StreamThru direct sources both reached first frame in manual tests.
- Some direct links are genuinely dead upstream and should fail fast.
- Some non-verified-cached sources still play successfully.
- Selected-source diagnostics did not prove any `VERIFIED_CACHED` playback case.
- A direct HTTP 500 source can still be retried repeatedly with the same context.
- Readiness currently treats a non-error redirect as ready without validating the
  bounded redirect chain or proving that playback reaches first frame.

The current source model still combines concepts that must be independent:

- `DebridCacheStatus.DIRECT_STREAM` is converted to the legacy `isCached = true`.
- Picker filters use the RD-specific wording `RD Cached`.
- Instant availability verification is implemented as
  `verifyRealDebridCacheStatuses(...)`.
- Failure classifier messages are RD-specific.

## Reliability Model

Represent four independent dimensions. Do not overload a cache flag.

```kotlin
data class SourceReliability(
    val service: DebridServiceId?,
    val transport: SourceTransport,
    val state: ReliabilityState,
    val proof: ReliabilityProof,
    val lastOutcome: PlaybackOutcome? = null
)
```

### Service Badge

`DebridServiceId` is identity only:

- `REAL_DEBRID`
- `TORBOX`
- `ALL_DEBRID`
- `PREMIUMIZE`
- `OTHER(id)`

The service badge must not imply cached status or playback quality.

### Transport

`SourceTransport` describes how playback starts:

- `DIRECT_PROXY`
- `RESOLVABLE_HASH`
- `MAGNET`
- `UNRESTRICTED_URL`
- `UNKNOWN`

### Reliability State

`ReliabilityState` is service-neutral:

- `VERIFIED_CACHED`: authoritative service instant-availability proof exists.
- `DIRECT`: direct playback path; this does not claim cache proof.
- `UNVERIFIED`: no authoritative proof and no current failure evidence.
- `RECENTLY_FAILED`: transient same-context failure; demote temporarily.
- `REMOVED`: content removal, legal block, or known unavailable source.
- `TERMINAL_FAILED`: source cannot be retried in the current session.

Use `ReliabilityProof` to retain evidence strength:

- `INSTANT_AVAILABILITY`
- `PROBE_ADVISORY`
- `FIRST_FRAME`
- `PLAYBACK_ERROR`
- `NONE`

Only `INSTANT_AVAILABILITY` may promote a row to `VERIFIED_CACHED`.
`PROBE_ADVISORY` may allow launch but is not playback proof. `FIRST_FRAME` is the
strongest runtime-health success signal.

## Normalized Outcomes

Use one normalized taxonomy across services and addon/proxy providers:

| Outcome | Example evidence | Retry policy |
| --- | --- | --- |
| `CONTENT_REMOVED` | copyright/removal response | remove or quarantine source |
| `RIGHTS_BLOCKED` | HTTP 451 or service equivalent | terminal for source |
| `HTTP_NOT_FOUND` | HTTP 404 | terminal for source |
| `HTTP_GONE` | HTTP 410 | terminal for source |
| `AUTH_REQUIRED` | HTTP 401/403 when classified as account auth | stop cascade; request account action |
| `RATE_LIMITED` | HTTP 429 or service equivalent | stop cascade; apply cooldown |
| `UPSTREAM_SERVER_ERROR` | HTTP 500-599 | one unchanged-context retry, then quarantine |
| `READINESS_FALSE_POSITIVE` | probe passed, playback failed before first frame | demote probe strategy and source |
| `STARTUP_TIMEOUT_NO_FIRST_FRAME` | buffering timeout before first frame | one unchanged-context retry, then quarantine |
| `PLAYBACK_STALL_AFTER_FIRST_FRAME` | progress stops after playback began | separate recovery policy |
| `RESOLUTION_FAILED` | resolve/unrestrict path failed | classify with service adapter |
| `SUBTITLE_ATTACH_FAILED` | subtitle load or attach failed | non-fatal; keep video playing |
| `FIRST_FRAME_SUCCESS` | renderer first-frame event | record positive health |

HTTP status alone is not always enough. For example, 401/403 can mean account
auth, addon token failure, or upstream access denial. The service capability and
provider context must decide whether to stop the whole cascade or quarantine one
source.

## Service Capabilities

Introduce a service adapter contract before adding Torbox-specific behavior:

```kotlin
data class DebridServiceCapabilities(
    val service: DebridServiceId,
    val supportsInstantAvailability: Boolean,
    val supportsDirectProxyPlayback: Boolean,
    val supportsReadinessProbe: Boolean,
    val supportsResolve: Boolean,
    val preferredHeaderProfile: HeaderProfile,
    val probeStrategy: ProbeStrategy,
    val errorClassifier: DebridErrorClassifier
)
```

`HeaderProfile` should be a normalized label such as `STREMIO_DEFAULT`,
`SERVICE_DEFAULT`, or `SOURCE_SUPPLIED`; never persist raw credential-bearing
headers. `ProbeStrategy` should define whether the service/provider allows HEAD,
bounded redirect validation, or a small range GET.

The existing RD instant-availability implementation should move behind a
service adapter. Torbox and future services can then add their own availability,
resolve, and error mappings without changing picker or player semantics.

## Runtime Health Cache

Add a small local-only TTL health repository. Keep an in-memory session index and
persist bounded recent reputation for ranking. Never persist raw URLs, magnets,
info hashes, tokens, credentials, or full headers.

Key:

```text
service
provider/addon
host
path-shape
source-type
effective-header-profile
source-fingerprint
```

Safe derived fields:

- lowercase URL host only
- normalized path shape such as `/stream/{id}` or `/playback/{id}`
- keyed hash/fingerprint of the complete source identity
- normalized header-profile label
- booleans such as `hasUserAgent`, `hasReferer`, `hasAuthorization`

Value:

```text
lastOutcome
lastFailureAt
failureCount
sameContextFailureCount
lastFirstFrameAt
quarantineUntil
sessionTerminal
```

Suggested initial TTLs:

- removed, 404, 410, 451: terminal for session; persist demotion for 24 hours
- repeated same-context 500: one retry; demote for 15 minutes
- repeated no-first-frame timeout: one retry; demote for 15 minutes
- auth or rate limit: service/provider cooldown, not row-by-row auto-skip
- first frame: clear transient failure streak and record recent success
- subtitle failure: no source quarantine

## Safer Probe Model

Readiness is an advisory gate, not a guarantee:

1. Select capability-controlled probe strategy.
2. Apply the same normalized effective headers used by playback.
3. Treat HEAD success as advisory only.
4. Validate redirects for a bounded one or two hops.
5. Reject known error targets and error content types at every hop.
6. Where capability permits, use a small range GET to catch strict proxy
   endpoints that answer HEAD optimistically.
7. Record `PROBE_ADVISORY`; record `FIRST_FRAME_SUCCESS` only from the player.
8. If probe passed but playback fails before first frame, record
   `READINESS_FALSE_POSITIVE` and demote that exact context.

## Quarantine And Candidate Fallback

Do not retry or auto-play known-bad same-session contexts.

Initial behavior should be conservative:

- terminal per-source outcomes return to the picker with that row demoted or
  hidden and the next healthy candidate highlighted
- repeated unchanged-context 500 and startup timeout permit one retry, then
  return to the picker
- auth, rate-limit, network-wide, and unknown failures stop candidate cascading
- subtitle failures keep playback active and surface a non-fatal message
- ranking considers health after reliability state but before seed count
- language, provider, file index, and binge-group variants remain distinct

Auto-highlight is safer than auto-play for the first implementation. Existing
auto-play-next behavior can be retained only for proven terminal per-source
resolver failures until the health cache supplies a session quarantine set.

## UI Wording

Keep service and reliability visible as separate pills:

| Current wording | Replace with |
| --- | --- |
| `RD Cached` filter | `Cached verified` |
| `VERIFIED` badge | `CACHED VERIFIED` |
| `DIRECT` badge | `DIRECT LINK` |
| `UNCACHED` badge | `UNVERIFIED` |
| `UNKNOWN` badge | `UNVERIFIED` |
| failed row | `RECENTLY FAILED` |
| removed row | `REMOVED` |
| terminal row | `UNAVAILABLE` |

Show service identity independently: `RD`, `TB`, `AD`, `PM`, or `SRC`.
Do not label a playable direct link as uncached merely because instant
availability was not proved.

## Phased Implementation

Each phase should build and receive focused device QA before the next phase.

### Phase 1: Contain Same-Context Player Amplification

Goal: stop repeated direct HTTP 500 retries without changing source routing.

Files:

- `app/src/main/java/com/tvonnet/debridxtreamiptv/player/stabilized/PlayerActivity.kt`

Change shape:

- reuse the existing direct addon/proxy timeout context key for HTTP 500-599
- allow one unchanged-context retry, then terminal return to picker
- keep 401/403/404/410/429/451 fast-fail behavior intact

### Phase 2: Add Service Capabilities And Structured Probe Evidence

Goal: make readiness service-neutral and distinguish advisory probe success from
cache proof and first-frame proof.

Files:

- new `app/src/main/java/com/tvonnet/debridxtreamiptv/data/debrid/model/DebridServiceCapabilities.kt`
- `app/src/main/java/com/tvonnet/debridxtreamiptv/data/debrid/repository/DebridPlaybackRepository.kt`
- `app/src/main/java/com/tvonnet/debridxtreamiptv/ui/vod/MovieDetailActivity.kt`
- `app/src/main/java/com/tvonnet/debridxtreamiptv/ui/series/SeriesDetailActivity.kt`

Change shape:

- replace boolean probe result with structured advisory result
- validate a bounded redirect chain
- optionally add capability-controlled small range GET
- preserve the exact effective playback headers

### Phase 3: Separate Identity, Transport, Reliability, And UI Wording

Goal: remove cache/direct semantic coupling without changing playback behavior.

Files:

- new `app/src/main/java/com/tvonnet/debridxtreamiptv/data/debrid/model/SourceReliability.kt`
- `app/src/main/java/com/tvonnet/debridxtreamiptv/data/repository/XtreamRepository.kt`
- `app/src/main/java/com/tvonnet/debridxtreamiptv/data/debrid/repository/UnifiedSourceProvider.kt`
- `app/src/main/java/com/tvonnet/debridxtreamiptv/ui/sources/SourceFilterUtils.kt`
- `app/src/main/java/com/tvonnet/debridxtreamiptv/ui/vod/MovieSourceAdapter.kt`

Follow-up UI-only file if the active picker still renders old dynamic text:

- `app/src/main/java/com/tvonnet/debridxtreamiptv/ui/series/SourceSelectionBottomSheet.kt`
- `app/src/main/java/com/tvonnet/debridxtreamiptv/ui/compose/components/SourceListSection.kt`

Change shape:

- stop treating direct as cached in new logic
- keep legacy fields temporarily for compatibility
- add Torbox badge mapping through structured service identity, not label guessing
- replace RD-specific filter wording

### Phase 4: Add Local Runtime Health Reputation

Goal: rank and quarantine exact failing contexts locally.

Files:

- new `app/src/main/java/com/tvonnet/debridxtreamiptv/data/debrid/repository/SourceHealthRepository.kt`
- `app/src/main/java/com/tvonnet/debridxtreamiptv/player/stabilized/PlayerActivity.kt`
- `app/src/main/java/com/tvonnet/debridxtreamiptv/data/debrid/repository/DebridPlaybackRepository.kt`
- `app/src/main/java/com/tvonnet/debridxtreamiptv/data/debrid/repository/UnifiedSourceProvider.kt`

Change shape:

- record first frame, terminal status, repeated 500, and startup timeout
- rank recently successful contexts above unknown contexts
- demote or quarantine exact recently failing contexts
- persist only redacted derived keys and bounded TTL data

### Phase 5: Return-To-Picker Next Candidate

Goal: avoid replaying quarantined rows while keeping user control.

Files:

- `app/src/main/java/com/tvonnet/debridxtreamiptv/player/stabilized/PlayerActivity.kt`
- `app/src/main/java/com/tvonnet/debridxtreamiptv/ui/vod/MovieDetailActivity.kt`
- `app/src/main/java/com/tvonnet/debridxtreamiptv/ui/series/SeriesDetailActivity.kt`
- `app/src/main/java/com/tvonnet/debridxtreamiptv/ui/series/SourceSelectionBottomSheet.kt`

Change shape:

- return a redacted stable failure fingerprint and normalized outcome
- exclude session-quarantined candidates
- highlight the next healthy row
- do not auto-cascade on auth, rate-limit, network, or unknown outcomes

### Phase 6: Add Service Adapters Incrementally

Goal: move RD-specific availability and resolution behind service adapters, then
add Torbox without changing picker/player contracts.

Files for the first extraction:

- new `app/src/main/java/com/tvonnet/debridxtreamiptv/data/debrid/repository/DebridServiceAdapter.kt`
- new `app/src/main/java/com/tvonnet/debridxtreamiptv/data/debrid/repository/RealDebridServiceAdapter.kt`
- `app/src/main/java/com/tvonnet/debridxtreamiptv/data/debrid/repository/UnifiedSourceProvider.kt`
- `app/src/main/java/com/tvonnet/debridxtreamiptv/data/debrid/repository/DebridPlaybackRepository.kt`
- `app/src/main/java/com/tvonnet/debridxtreamiptv/data/debrid/model/DebridFailure.kt`

Add Torbox as a separate follow-up adapter after RD parity QA.

## Cross-Service QA Matrix

Run the same matrix for RD, Torbox, and each future service adapter:

| Case | Expected proof |
| --- | --- |
| working direct movie | probe advisory if supported; player first frame; no retry |
| working direct series episode | correct episode; player first frame; no retry |
| verified cached source | authoritative instant availability; resolve; first frame |
| non-verified but playable direct source | `DIRECT LINK`, not `CACHED VERIFIED`; first frame |
| dead 404/410 source | one terminal classification; no storm |
| removed/451 source | removed or rights-blocked classification; quarantine |
| repeated direct 500 | at most one unchanged-context retry; picker return |
| no-first-frame timeout | at most one unchanged-context retry; picker return |
| header-sensitive source | same normalized profile in probe and player GET |
| subtitle case | video reaches first frame; subtitle attaches or fails non-fatally |
| auth failure | no source cascade; service action message |
| rate limit | provider/service cooldown; no row-by-row retries |

## Recommended Next Fix

Start with Phase 1 only: add the narrow direct addon/proxy same-context 500-599
guard in `PlayerActivity.kt`. Diagnostics already prove this amplification path,
and the fix does not require changing provider identity, cache semantics, probe
behavior, picker UI, or service adapters.
