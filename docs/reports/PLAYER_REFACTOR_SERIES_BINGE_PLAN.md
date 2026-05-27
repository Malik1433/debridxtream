# PlayerActivity Refactor Phase 4 Candidate - Series/Binge Mapping

## 1. Context
Phase 1 (History), Phase 3A (Subtitle/Audio) are COMPLETED and VERIFIED.
Phase 2 (Network/Stall) is BLOCKED due to high risk.
This mapping assesses the viability of extracting the "Next Episode Prompt" and "Auto-Play / Binge" logic from `PlayerActivity`.

## 2. Identified Functions in `PlayerActivity`
The following logic handles next episode detection and prompt overlay:
- `setupNextEpisodeViews()`: Initializes overlay views (title, play, cancel).
- `checkNextEpisodePrompt()`: Called every second to evaluate player position against duration thresholds (97% or <45s remaining).
- `showNextEpisodePrompt(nextEp: EpisodeEntityV2)`: Activates the overlay, starts the 15-second countdown timer, hides main controls, coordinates focus.
- `hideNextEpisodePrompt(cancelled: Boolean)`: Dismisses the prompt, cancels timer, restores focus.
- `playNextEpisode()`: The action triggered by the countdown finish or user click. Branches into Debrid vs IPTV execution.
- `nextCheckRunnable` / `nextCheckHandler`: A 1-second interval loop that runs `checkNextEpisodePrompt()` during playback.

## 3. Fields Read/Written
- **UI References:** `nextEpisodeOverlay`, `tvNextEpTitle`, `btnPlayNext`, `btnCancelNext`, `tvCountdownSeconds`.
- **State/Timers:** `isNextPromptVisible`, `nextPromptShownForThisEpisode`, `nextEpisodeTimer` (CountDownTimer).
- **Playback Properties (from Intent/ViewModel):** `contentType`, `playbackSource`, `seasonNumberExtra`, `episodeNumberExtra`, `debridInfoHashExtra`, `debridStreamIdExtra`.

## 4. Existing Managers / Dependencies
- **`SeriesPlaylistManager`**: Holds the list of episodes. `checkNextEpisodePrompt()` reads `viewModel.seriesPlaylistState.value` to find if `hasNext` is true before showing the prompt.
- **`PlayerViewModel`**: `playNextEpisode()` directly calls `viewModel.loadNextDebridEpisode(...)` or `viewModel.getNextEpisode()`.
- **`FocusCoordinator`**: Used to steal/return focus to the Next Episode overlay.

No dedicated prompt/binge manager currently exists for this overlay logic.

## 5. Risk Level: Medium
Extraction is **Medium Risk**.
- **Why it's not Low:** It interacts heavily with Android UI focus, uses custom timers (`CountDownTimer`), a custom Handler loop (`nextCheckRunnable`), and interacts directly with `ExoPlayer`'s `currentPosition`/`duration`. It also coordinates with `PlayerViewModel` to fetch the next Debrid source or IPTV source.
- **Why it's not High:** It does not directly manage the ExoPlayer instance creation, stream errors, Live TV zapping, or network recovery. It is a discrete UI overlay that sits on top of playback.

## 6. Is Extraction Safe Later?
**YES.** Extraction into a `PlayerSeriesBingeManager` or `PlayerNextEpisodeManager` is safe and highly recommended for cleaning up `PlayerActivity`.

## Phase 4B: Extract PlayerNextEpisodeManager Only

### Status
- **VERIFIED / CLOSED**
- `PlayerNextEpisodeManager.kt` created successfully.
- Next Episode prompt state, UI visibility, and countdown timers extracted.
- Callbacks wired to route Play and Cancel actions back to `PlayerActivity`.
- Compilation errors fixed and clean `assembleDebug` passed.
- APK successfully installed on 192.168.0.21.
- Manual QA confirmed Next Episode prompt appears, countdown works, Play Next loads correct episode, Cancel hides prompt without regression.

## 7. Minimal Safe Boundary (If Approved)
Create `PlayerNextEpisodeManager` to hold:
1. The overlay View references.
2. The `nextCheckHandler`, `nextCheckRunnable`, and `nextEpisodeTimer`.
3. The `show/hide` and position-checking logic.

**Delegate Pattern:** 
Instead of the new manager knowing about Debrid vs. IPTV, the manager should simply expose a callback: `onNextEpisodeRequested()`. `PlayerActivity` would implement this callback and retain the actual call to `playNextEpisode() / viewModel.loadNextDebridEpisode()`. This prevents the new manager from needing all the Debrid intent extras.

## 8. Baseline Test Results and Expected Behavior
Baseline testing has been verified for the current Next Episode / Binge logic.

**1. IPTV Series episode near end:**
- Next Episode prompt appears successfully at thresholds (97% or <45s remaining).
- Countdown timer decreases correctly.
- Focus is properly acquired by prompt buttons.
- Play Next starts correct next IPTV episode.
- Dismiss/hide works and returns focus to the player.

**2. Debrid Series episode near end:**
- Next Episode prompt appears if next episode exists in playlist.
- Play Next uses the correct Debrid episode identity.
- Source resolving correctly handles the next video.
- No false "Real-Debrid config missing" errors.
- No stuck loading or resolving states.

**3. Manual playNextEpisode behavior:**
- Correct next episode plays if available.
- No crash or looping prompts if a next episode is absent.

**4. Episode Browser safety:**
- Playlist isolation works (Series A shows A; Series B shows B).

**5. Regression smoke:**
- Debrid Continue Watching same-day resume continues to work.
- Live TV fast zapping works smoothly.
- IPTV movie playback functions as normal.

## 9. Known Limitations
- The countdown logic uses `PlayerActivity` views directly, which causes unnecessary coupling.
- `checkNextEpisodePrompt` runs a `Handler` loop every 1 second indefinitely while playing.
- `playNextEpisode` manually reads internal Activity extras (e.g., `debridInfoHashExtra`) to determine the next action, entangling UI state with Debrid metadata.

## 10. Phase 4B Extraction Readiness
**Phase 4B extraction is SAFE to start.**
The baseline behavior is stable, mapped, and verified. A clear minimal safe boundary exists using a `PlayerNextEpisodeManager` and a delegate callback. 

*Do not start extraction until the user explicitly approves Phase 4B.*
