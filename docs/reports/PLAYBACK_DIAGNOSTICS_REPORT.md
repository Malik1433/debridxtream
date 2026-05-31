# Playback Diagnostics Report

## 1. Executive Summary
An analysis of 39 playback diagnostic sessions from the target device (`192.168.0.21`) reveals a clear pattern of playback failures tied specifically to the **AIOStreams | ElfHosted** provider. The core issue is that AIOStreams proxy links are being passed to ExoPlayer as `DIRECT_STREAM`, but these links frequently return `HTTP 404` or `HTTP 500` errors, which crashes the player.

## 2. Session Metrics
* **Total Sessions Analyzed:** 39
* **Successful Playbacks (Reached `READY` state):** 18
  * *AIOStreams | ElfHosted:* 12
  * *StremThru Torz:* 6
* **Failed Playbacks:** 21
  * *AIOStreams | ElfHosted:* 21
  * *(No failures recorded for StremThru Torz)*

## 3. Failure Patterns (The "Why")

### HTTP 404 and HTTP 500 from Proxy URLs
In the failed AIOStreams sessions, the logs show the following sequence:
1. `source_selected`: Source is selected (e.g., `[TB⏳] HdHub 2160p` or `[TB⚡] HdHub 1080p`).
2. `playback_launch`: Launched with `playbackSource: "DEBRID"` and `directDebridPlayback: true`.
3. `player_error`: ExoPlayer immediately throws a fatal error because the URL (hosted at `aiostreams.elfhosted.com`) responds with an HTTP error.
   * **Example 1:** `reasonCode: "HTTP_404"`, `httpStatusCode: 404`, `errorCode: 2004`.
   * **Example 2:** `reasonCode: "UNKNOWN"`, `httpStatusCode: 500`, `errorCode: 2004`.
4. **Retries:** The player's retry mechanism triggers up to 3 times, but consistently receives the same 404/500 HTTP errors from the ElfHosted server.

### User Aborts (Short Sessions)
Several of the 21 failures are ultra-short sessions (~2100 bytes) where `release_player` is called with `releaseReason: "on_stop"` just milliseconds after `session_started`. This strongly suggests the user is manually backing out of the player activity rapidly, likely due to successive 404/500 crashes on AIOStreams sources.

## 4. Success Patterns

### Resolver-Backed Successes
When observing successful AIOStreams playbacks (e.g., `session-20260531-124926-adc4083a.jsonl`), the logs show a completely different flow:
1. The app performs a long series of `readiness_check_started` / `readiness_check_finished` events against the `aiostreams.elfhosted.com` URLs.
2. Many of these checks return `httpStatusCode: 405` (`NOT_READY`).
3. Eventually, the check returns `httpStatusCode: 302` or `307` (`READY`).
4. The `playback_launch` is then triggered with `directDebridPlayback: false` and `launchPath: "resolver_backed"`.
5. ExoPlayer successfully reaches the `READY` state.

## 5. Next Exact Fix Target
Based strictly on the diagnostic evidence, the player is behaving correctly, but it is being fed unstable proxy links.

**Target for Fix:** The logic responsible for categorizing AIOStreams proxy links (likely in the **Debrid Module's** source parsing or URL mapping layer). 
Currently, it aggressively treats these proxy links as `DIRECT_STREAM` (`directDebridPlayback: true`), bypassing the resolver and passing them directly to ExoPlayer. To fix this, AIOStreams proxy links must be subjected to the `readiness_check` flow or handled by the resolver to prevent ExoPlayer from directly absorbing upstream HTTP 404/500 proxy errors.
