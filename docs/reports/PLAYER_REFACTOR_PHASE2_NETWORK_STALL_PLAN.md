# Phase 2: PlayerNetworkStallManager Refactor Plan

## 1. Current Network/Stall Flow in PlayerActivity
The current network recovery, timeout, and stall detection logic handles all playback types (Live, VOD, Series, Debrid) and is heavily intertwined with player lifecycle and re-initialization.

### Network Reconnect
- `registerNetworkCallback()` listens for network availability.
- On reconnect, `attemptNetworkRecovery()` checks if the player is stuck in `STATE_IDLE` or `STATE_BUFFERING`. 
- If recovery is needed, it saves `startPositionMs`, releases the player, and calls `initializePlayer(currentUrl)`.

### Buffering Timeout
- `onPlaybackStateChanged` detects `STATE_BUFFERING` and posts `timeoutRunnable`.
- If buffering exceeds `resolveTimeoutMs`, `handleTimeout()` triggers.
- Recovery involves either Debrid re-resolution (`viewModel.reResolveDebridUrl`) or a generic retry (save position, release player, `initializePlayer` after delay).

### Stall Detection
- `startStallMonitor()` posts a 5000ms loop to `stallRunnable`.
- `checkForStall()` checks if `player.currentPosition` has advanced past `lastBoundPosition`.
- If stuck beyond `STALL_THRESHOLD_MS` (or `LOW_RAM_STALL_THRESHOLD_MS`), it increments `stallStrikeCount`.
- Upon reaching strike limits, it throws a fake `PlaybackException` (ERROR_CODE_REMOTE_ERROR) to trigger `handlePlaybackError()`.

### Error Handling
- `handlePlaybackError()` processes all ExoPlayer errors and fake stall exceptions.
- It handles Live TV edge cases (`ERROR_CODE_BEHIND_LIVE_WINDOW` -> seekToDefaultPosition).
- It tracks `retryCount` (max 3).
- It either triggers Debrid re-resolution or posts a delayed `initializePlayer()` retry.
- On terminal failure, calls `handleTerminalPlaybackFailure()`.

## 2. Extraction Boundary Mapping

### Functions
- `registerNetworkCallback()`
- `unregisterNetworkCallback()`
- `attemptNetworkRecovery(reason: String)`
- `checkForStall()`
- `startStallMonitor()`
- `stopStallMonitor()`
- `handleTimeout()`
- `handlePlaybackError(error: PlaybackException)`
- `resolveTimeoutMs(url: String): Long`

### Field Dependencies
- `connectivityManager`, `networkCallback`, `networkAvailable`
- `stallHandler`, `stallRunnable`, `stallStrikeCount`, `lastBoundPosition`, `lastProgressCheckMs`
- `timeoutHandler`, `timeoutRunnable`, `lastBufferingStartMs`
- `retryCount`
- Constants: `maxRetries`, `STALL_THRESHOLD_MS`, `LOW_RAM_STALL_THRESHOLD_MS`, `LOW_RAM_STALL_STRIKES`, `NETWORK_RECOVERY_BUFFER_MS`

### Player State Read/Write
- **Reads:** `player`, `player.playbackState`, `player.currentPosition`, `player.playWhenReady`, `currentUrl`, `contentType`, `isResolvingDebrid`, `directDebridPlayback`, `canUseDebridResolver()`, Debrid extras.
- **Writes:** `startPositionMs`, `isSwitching`, `isResolvingDebrid`, `player` (releases and nullifies).

### UI / External Calls
- `showToast()`
- `handleTerminalPlaybackFailure()`
- `initializePlayer(url: String)`
- `viewModel.reResolveDebridUrl(...)`

### Lifecycle Dependencies
- `onStart()` -> `registerNetworkCallback()`
- `onResume()` -> `startStallMonitor()`
- `onPause()` -> `stopStallMonitor()`
- `onStop()` -> `unregisterNetworkCallback()`, `stallHandler`, `timeoutHandler`

## 3. Do-Not-Break List
- **Live TV Recovery:** Must preserve `ERROR_CODE_BEHIND_LIVE_WINDOW` catch and resume.
- **Debrid Re-resolution:** Must preserve `canUseDebridResolver()` check and trigger `viewModel.reResolveDebridUrl` on retries instead of blindly re-initializing stale URLs.
- **VOD Position Restore:** Must accurately capture `startPositionMs` before tearing down the player during recovery.
- **Low-RAM Sensitivity:** Must respect `LOW_RAM_STALL_THRESHOLD_MS` device profiles.
- **Infinite Loops:** `retryCount` state must accurately reset on `STATE_READY` to prevent infinite initialization loops.

## 4. Extraction Risk Level
**HIGH.**
The stall and network logic tightly couples with core activity capabilities: releasing the ExoPlayer instance, mutating `startPositionMs`, calling `initializePlayer`, and orchestrating Debrid state with `PlayerViewModel`. Extracting this requires significant callback interfaces or exposing risky mutable state (`player`, `startPositionMs`, `isResolvingDebrid`) to the manager. 

## 5. Required Baseline Tests (Before Extraction)
1. **Network Drop Test:** Turn off WiFi during VOD playback, wait 10s, turn on WiFi. Expect auto-reconnect and resume.
2. **Stall Test:** Block streaming port to trigger `checkForStall()`. Expect fake exception and UI retry toast.
3. **Debrid Re-resolve Test:** Simulate an expired Debrid link failure. Expect `reResolveDebridUrl` branch instead of standard retry.
4. **Live TV Catch-up:** Simulate `BehindLiveWindowException`. Expect seamless snap to live edge.

## 6. Decision & Recommendation
**DO NOT PROCEED with full extraction.**
Due to the high risk of breaking Debrid re-resolution loops and ExoPlayer lifecycle management, it is recommended to keep `handlePlaybackError` and `initializePlayer` inside `PlayerActivity`.

### Status: BLOCKED / NOT APPROVED
- **Reason:** High coupling and high regression risk.
- **Rule:** Recovery/retry logic must remain in `PlayerActivity` for now. Do not expose internal `PlayerActivity` mutable state just to force extraction. Do not create callback-heavy manager that controls player teardown/rebuild.

**Alternative minimal extraction (Phase 2B Optional):**
Extract *only* the stall detection logic (timers, threshold math, `lastBoundPosition` tracking) into `PlayerStallDetector`, which simply invokes an `onStallDetected()` callback back to the activity, rather than moving the entire network/recovery/retry logic into a manager.
- It must not call `initializePlayer()`.
- It must not access ExoPlayer directly.
- It must not access Debrid resolver.
- It must not update UI.
- It must not own retry/recovery logic.
