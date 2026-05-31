# Playback Diagnostics Analysis - 2026-05-31

## Scope
- Device: `192.168.0.21:5555`
- Pulled folder: `artifacts/playback-diagnostics-analysis-20260531-145957`
- Input: 39 debug JSONL session files from `playback-diagnostics`
- Analysis is read-only. No runtime code or canonical module report was changed.

## 1. Session Overview
- 39 sessions total.
- 30 sessions contain `source_selected` and `playback_launch`.
- 9 short lifecycle-only sessions contain `session_started`, `release_player`, and `session_finished` without a selected source.
- 18 selected-source sessions reached `first_frame_rendered`.
- 22 total first-frame events were recorded because some successful sessions rendered again after a later state transition.
- 11 sessions recorded `player_error`.
- 6 sessions recorded `retry_triggered`.
- 2 sessions recorded `buffer_timeout`.
- 9 sessions recorded `terminal_failure`.
- No session recorded `return_to_sources`; post-failure navigation is not proven by this capture set.

Readiness activity was high because source-list refresh probes were included:

| Readiness result | Count |
|---|---:|
| Redirect accepted as ready: HTTP `302` | 339 |
| Redirect accepted as ready: HTTP `307` | 88 |
| Rejected as not ready: HTTP `405` | 114 |
| Total | 541 |

## 2. Success Cases
18 selected-source sessions reached first frame and `READY`.

| Provider / path | Selected cache label | Successful sessions |
|---|---|---:|
| AIOStreams / ElfHosted direct | `DIRECT_STREAM` | 6 |
| AIOStreams / ElfHosted direct | `NOT_CACHED` | 2 |
| AIOStreams / ElfHosted resolver-backed | `NOT_CACHED` | 4 |
| StremThru direct | `DIRECT_STREAM` | 5 |
| StremThru resolver-backed | `DIRECT_STREAM` | 1 |

Evidence:
- Both AIOStreams and StremThru can reach first frame.
- Direct AIO playback is not globally broken.
- Selected `NOT_CACHED` rows can still play; that label does not mean terminal playback failure.
- Track discovery was present on successful sessions. Observed examples included audio/text profiles such as `2 audio / 3 text`, `4 audio / 5 text`, and `9 audio / 46 text`.

## 3. Failure Cases
All observed source-level failures were on AIOStreams / ElfHosted.

| Failure pattern | Sessions | Evidence |
|---|---:|---|
| Direct terminal HTTP `404` | 7 | Direct AIO player GET failed with `404`; `terminal_failure` followed immediately without generic retry storm. |
| Direct buffering timeout | 2 | Direct AIO remained buffering until timeout. One retried once then later failed `404`; one ended without a second timeout in the same session. |
| Direct HTTP `500` retry loop | 3 | Same direct AIO context repeatedly failed with HTTP `500`. One session recorded 6 errors, 5 retries, then terminal failure. Two sessions were interrupted after 3 and 2 retries. |
| Resolver-backed AIO terminal failure | 1 | A movie row launched resolver-backed playback, emitted one unknown player error, retried once, then terminated as `FAILED`. |

Representative event chains:

```text
AIO direct timeout:
source_selected -> playback_launch(direct) -> initialize
-> buffer_timeout -> retry_triggered -> initialize
```

```text
AIO direct dead link:
source_selected -> playback_launch(direct) -> initialize
-> player_error(HTTP_404) -> terminal_failure
```

```text
AIO direct HTTP 500 amplification:
source_selected -> playback_launch(direct) -> initialize
-> player_error(HTTP_500/UNKNOWN) -> retry
-> initialize -> player_error(HTTP_500/UNKNOWN) -> retry
... repeated to retry 5 -> terminal_failure
```

## 4. Pattern Clusters
### Cluster 1: Redirect readiness is weaker than playback readiness
Confirmed mixed/upstream-sensitive behavior.

- Readiness probes accept non-error `302` and `307` redirects.
- Actual playback GETs can later fail with `404` or `500`.
- The capture proves a redirect-ready preflight is not authoritative proof that the downstream media URL will play.
- This is likely upstream URL volatility or downstream proxy failure, amplified by a shallow redirect-only preflight.

### Cluster 2: Direct AIO HTTP `500` failures still trigger generic retry amplification
Confirmed app-side retry policy gap.

- Direct `404` is terminal and fast-fails correctly.
- Direct `500` is not terminal. It is recorded as `UNKNOWN` and enters the generic retry branch.
- One same-context AIO direct attempt repeated 5 retries before terminal failure.
- The existing same-context timeout guard does not cover the `onPlayerError` HTTP `500` path.

### Cluster 3: Cache labels do not predict playback outcome
Confirmed classification observation, not a cache regression.

- Source-list totals were dominated by `DIRECT_STREAM` (`9470`) with `NOT_CACHED` also present (`104`).
- Selected `NOT_CACHED` rows reached first frame in both direct and resolver-backed flows.
- No selected `VERIFIED_CACHED` source exists in this capture set.
- Cached RD regression cannot be confirmed or ruled out from these sessions.

## 5. Confirmed vs Likely vs Uncertain
### Confirmed app-side
- `PlayerActivity` fast-fails direct HTTP `404`, but direct HTTP `500` still falls through to generic retry handling.
- Same direct AIO HTTP `500` context can be retried up to the configured maximum of 5.
- Repeated old 25-second timeout loops were not reproduced: only two timeout events exist, with no repeated timeout chain in one session.
- Direct AIO playback uses the `stremio` UA profile in the captured direct error cases, so the prior readiness/playback header mismatch is not the cause of these failures.

### Likely upstream / proxy
- Direct AIO links that pass redirect-based readiness but later return `404` are likely dead or expired downstream links.
- Direct AIO links returning repeated `500` are likely failing at the addon/proxy server. The app-side bug is retry amplification, not proof that the app caused the upstream `500`.

### Uncertain / needs proof
- No selected `VERIFIED_CACHED` RD source was captured.
- No `return_to_sources` event was captured after terminal failure, so secondary navigation behavior remains unverified.
- Nine lifecycle-only sessions cannot be classified as playback failures.
- The resolver-backed AIO movie terminal failure needs a dedicated reproduction before changing resolver behavior.

## 6. Next Exact Fix Target
Inspect only:

1. `app/src/main/java/com/tvonnet/debridxtreamiptv/player/stabilized/PlayerActivity.kt`
   - Review `handlePlaybackError()` and `isTerminalDirectHttpPlaybackError()`.
   - Add a narrow direct-addon/proxy same-URL/same-header guard for repeated HTTP `500` player errors, or classify the repeated unchanged context terminal after one retry.
   - Do not rewrite global retry policy.

2. `app/src/main/java/com/tvonnet/debridxtreamiptv/data/debrid/repository/DebridPlaybackRepository.kt`
   - Review redirect readiness confidence in `isReadyAddonProxyResponse()`.
   - Determine whether a bounded redirect-chain validation can distinguish redirect-ready from downstream-dead without consuming media payloads.

Do not touch cache labeling, source picker UI, or resolver-backed RD behavior until a `VERIFIED_CACHED` reproduction is recorded.

