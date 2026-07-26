# World-Class Roadmap — the single, finishable plan

**Started:** 2026-07-26 · **Status:** ACTIVE · **Owner-approved:** yes

This is the **one** live plan. It starts now and is only "done" when every exit criterion below is
actually met on the device — not when the tasks are written down. Older plans are either finished or
archived (`docs/archive/legacy-2026-02/`); per-area plans (`*_REFACTOR_PLAN.md`) remain valid for their
own detail, and this document tracks them.

> **Why this exists:** individually reasonable "let's defer that" decisions had accumulated into a repo
> with 359 suppressed lint violations, 10 god-classes, 1 instrumented test, and 23 stale root documents
> telling new sessions to do the wrong thing. A quality bar only holds if it is *measured* and *ratcheted*.

---

## 1. Definition of done (exit criteria)

The app is "world-class" for this project's purposes when **all** of these are true and verified:

| # | Criterion | Today | Target |
|---|---|---:|---:|
| E1 | detekt suppressions (`config/detekt/detekt-baseline.xml`) | **359** | **0** |
| E2 | `LargeClass` violations | **10** | **0** |
| E3 | `SwallowedException` (errors silently hidden) | **0 ✅ MET** | **0** |
| E4 | Instrumented (on-device) tests — **counted as test methods**, `run_instrumented.sh` reports it | **15 ✅ MET** (3 classes) | **≥ 15** covering Live/Player/VOD/Series core flows |
| E5 | Unit test files | 59 | ≥ 80, with every new collaborator tested |
| E6 | Crash-free playback landmines re-verified after each phase | ad hoc | scripted device checklist |
| E7 | Open P1 findings from the 2026-07-19 audits | 12 P1 | 0 |
| E8 | Stale/contradictory docs at repo root | 0 ✅ | 0 |
| E9 | CI/pre-commit gate enforcing the ratchet | none | in place |

**The ratchet rule (non-negotiable):** `E1`, `E2`, `E3` may only ever go **down**. A phase that would
raise them is not finished. The only allowed increase is a *relocation* (a baselined item moving files),
and only if the total does not grow — the rule already in `CLAUDE.md`.

---

## 2. Per-phase protocol (how every phase below is executed)

1. One cohesive phase per commit. Never mix a refactor with a behaviour change.
2. Verify **clean**: `./gradlew clean :app:assembleDebug :app:testDebugUnitTest :app:detekt` — all green.
   (Incremental Gradle gives false greens here — see the stale-dex note in `CLAUDE.md`.)
3. Baseline discipline: regenerate only for legitimate relocations; **verify the total did not grow**;
   never baseline a genuinely-new violation (fix it instead).
4. Device: install on `192.168.178.35` / `.64`, launch-stability + logcat clean.
5. Interactive QA (playback/focus/Live) is **owner eyes-on** — the Live surface is secure, so
   `screencap` is black and `adb shell input` is a no-op.
6. Update this file's §5 ledger + the relevant per-area plan. Record durable lessons as memories.
7. Push. One phase per push, so any regression is bisectable.

---

## 3. Order of work, and why

Structure last, safety first. Decomposing god-classes **before** there is a test net is what makes every
phase depend on the owner's eyes and makes sessions slow — so the test net comes early.

### Tier A — stop hiding failures *(small, low risk, immediate value)*
- **A1** — `SwallowedException` ×26: every `catch` that drops the cause gets a log (or a rethrow for
  `CancellationException`, per `CLAUDE.md`'s structured-concurrency rule). Target E3 → 0.
- **A2** — `PrintStackTrace` ×2 → proper logging; `ExplicitGarbageCollectionCall` ×1 → removed.
- **A3** — `ImplicitDefaultLocale` ×25: locale-dependent `String.format`/`toUpperCase` on a TV that ships
  to multiple regions is a real correctness bug, not style.
- ✅ **DONE 2026-07-26.** Baseline 359 → **305**; `SwallowedException`, `PrintStackTrace`,
  `ExplicitGarbageCollectionCall` and `ImplicitDefaultLocale` are all **0** and pinned in the ratchet.
  All four rule ceilings are now zero, so none of these classes of defect can be reintroduced.

### Tier B — the test net *(the multiplier)*
- **B0** — ✅ **DONE 2026-07-26.** The flaky `stream lookup handles large cache efficiently` (wall-clock
  `< 100ms`) is gone. It is replaced by two behavioural tests that freeze what the stopwatch was really
  guarding: `large cache lookup is served from memory, never a re-parse or a Room round-trip` (warm the
  catalog, then delete the cache file **and** drop the process-wide snapshot — any lookup that re-reads
  or re-parses can now only return null) and `lookup miss falls back to Room exactly once`.
  *Note for the record:* the plan guessed "assert the lookup is index-backed". It is not — it is a
  linear `find` over an in-memory list, which is cheap at catalog size. The multi-MB Gson **re-parse**
  was the only thing that ever made this slow, so that is what the test pins. The new test was
  mutation-checked: removing the memoization in `LiveRepository.getLiveStreamById` makes it fail.
- **B1** — ✅ **DONE 2026-07-26** — `scripts/run_instrumented.sh`. Running the on-device suite is now
  one command:

  ```bash
  ./scripts/run_instrumented.sh                        # everything
  ./scripts/run_instrumented.sh MigrationTest          # a class (short name is expanded)
  ./scripts/run_instrumented.sh MigrationTest#migrate  # one method
  ./scripts/run_instrumented.sh -s 192.168.178.35:5555 -n   # other device, skip the build
  ```

  It assembles both APKs, connects/installs, drives `AndroidJUnitRunner` over adb, and — the part
  that matters — **derives a real verdict**, because `am instrument` exits 0 even when tests fail:
  a crash (`shortMsg=`), `FAILURES!!!`, a missing result line, or `OK (0 tests)` (the mistyped-filter
  silent-green trap) all exit non-zero. Output is kept at `app/build/instrumented/last-run.txt`.
  Device-proven the same day: `MigrationTest` → `OK (2 tests)` on `.64`, and both failure paths
  were exercised.

  **Do not waste time on `./gradlew :app:connectedDebugAndroidTest`** — on this machine it dies
  inside Gradle's UTP with `Cannot run program "C:\Program Files\Java\bin\java": CreateProcess
  error=1920`, a local JDK-path glitch rather than a test problem. That is precisely why this
  script exists.
- **B2** — Instrumented tests for the flows that keep breaking and currently need the owner's eyes.
  Priority order (highest pain first):
  1. **Live fullscreen hand-off** — ✅ **DONE 2026-07-26** — `LiveSharedPlayerHandoffTest`, **8 tests
     green on `.64`** — the whole instrumented suite is now `OK (10 tests)`, up from 2. The
     `offer`/`adopt` contract is now frozen: the parked instance is
     handed over *unchanged and un-released* (identity-asserted — a fresh reconnect fails the test);
     a mismatched url is refused **without orphaning** the player; the no-arg return-path adopt takes
     whatever is parked; the cover frame **survives adopt** and is one-shot (the rejected LP-C-4
     black-flash regression); replacing a parked player releases the old one (never two provider
     sockets) while re-offering the *same* instance must not; and an offer nobody adopts is killed by
     the 8s failsafe so audio cannot keep playing behind the UI. Release is observed via ExoPlayer's
     own `AnalyticsListener.onPlayerReleased`, not inferred.
     Mutation-checked with three injected regressions (clear-frame-on-adopt, drop the
     `player !== p` guard, null the player on url mismatch) — all three turned the suite red.
     **Lesson:** the first version of the test *silently passed* the middle mutant, because
     `onPlayerReleased` is posted to the main looper and a bare "is it released?" read races it.
     Negative assertions ("still alive") must wait out the callback window — see `assertStillAlive`.
  2. **D-pad focus** — ✅ **DONE 2026-07-26.** Split into the two things that actually break:
     - *No focus theft on refresh* (CC-1, the dominant defect class): `FocusPreservingListUpdateTest`,
       **5 instrumented tests** on a real window (`FocusTestActivity`, debug source set). Pins: a
       refresh while the user is outside the list moves nothing; focus follows the **item** (stable
       id) when a refresh reorders; the focused item vanishing falls back to the nearest surviving
       row; an emptied list invents no focus; a full rebind keeps the item focused.
     - *The focus-ownership gate*: `FocusCoordinatorTest`, 9 unit tests — a second owner is blocked,
       the same owner is re-entrant, a stale `release` cannot unlock someone else, `force` overrides,
       and a **throwing action never strands the lock** (nothing here times out, so a stranded lock
       would freeze focus for the whole session).

     ⚠️ **These tests found a real bug and it is fixed in the same commit.** `updatePreservingFocus`
     did its restore in a bare `post {}`, which for the common synchronous `notifyDataSetChanged()`
     runs *before* the layout pass: the removed row was still attached, the "focus is already inside"
     guard read true, the helper bailed — and the framework then dropped focus to row 0. The
     documented "nearest surviving row" fallback therefore never fired, i.e. the helper did not
     prevent the very jump it exists for. Now the restore runs on `OneShotPreDrawListener` (after
     layout) and the blanket guard is replaced by "is focus already on the *target* item?", so
     RecyclerView's own recovery is respected when it is right and corrected when it is wrong.
     Affects the 6 call sites (Series streams/episodes, Live OSD surf list ×2, Live EPG strip,
     Search results) — **worth an eyes-on pass next time the owner is at the TV.**

     *Not covered:* number-zap / A–Z type-ahead in `LiveChannelFocusController` still need the Live
     DI graph to instantiate; the pure decisions inside it are a B3 extraction candidate.
  3. **Resume** — VOD/Series/in-player positions (the `createIntent` `startPositionMs` trap).
  4. **Room migrations** — extend the existing migration test to cover every version bump.
  5. **Retained-player memory behaviour (the "OOM soak")** — the T2.1 retained player keeps a paused
     ExoPlayer (codec + buffers + socket) alive across `onStop` for VOD/Debrid so Home→return is
     instant; on a 1GB Fire TV that is exactly how an app gets OOM-killed. This cannot be a pure
     function — it only shows up across a *duration* and three collaborating places
     (`BasePlayerFragment.onStop` retained branch, `onHostTrimMemory`, and the `!isResolvingDebrid`
     play-restore). Make it an instrumented test that drives the lifecycle instead of a manual soak:
     - background→foreground the player ~30× and assert the player is retained (not re-created) and
       heap does not grow monotonically across cycles;
     - fire `onHostTrimMemory(TRIM_MEMORY_BACKGROUND)` and assert the retained player IS released
       (and that LIVE was already released at `onStop`, for `max_connections=1`);
     - assert `retryHandler`/`timeoutHandler` are purged on the retained path — a backgrounded error
       must never re-init playback **with audio**.
     Until that lands this is the ONE genuinely device-only item (see the status table below).
  - **Caveat to design around:** the Live surface is secure, so a test cannot screenshot it. Assert on
    *state* (which player instance is attached, focus position, adopted-vs-fresh) rather than pixels.
  - Target E4 ≥ 15.
- **B3** — Unit tests for every collaborator extracted so far: Live LF-1..LF-7
  (`LiveHeaderController`, `LiveSearchController`, `LiveEpgPreviewController`, `LiveCategoryController`,
  `LiveFavoritesController`, `LiveChannelFocusController`, `LivePlaybackLauncher`) and the Movie/Series
  detail collaborators. These were extracted verbatim with **no tests written**, which is precisely why
  Tier C cannot start first.
- *Exit:* E4 ≥ 15, E5 ≥ 80, and a refactor can be trusted without a full manual pass.

### Tier C — the remaining god-classes *(safe once B exists)*
Ordered by risk, lowest first. Same playbook as the completed LiveFragment work (verbatim relocation,
one domain per commit).

| Phase | File | Lines | Risk |
|---|---|---:|---|
| C1 | `SettingsFragment.kt` | 787 | low |
| C2 | `VodFragment.kt` | 1301 | low-med |
| C3 | `SeriesFragment.kt` | 1096 | low-med |
| C4 | `SeriesDetailFragmentV2.kt` | 843 | med |
| C5 | `XtreamSeriesRepositoryV2.kt` | 925 | med |
| C6 | `DebridPlaybackRepository.kt` | 801 | med |
| C7 | `LiveTvGuideFragment.kt` | 866 | med-high (guide playback hand-off) |
| C8 | `LivePlayerOsdManager.kt` | 1174 | high (player) |
| C9 | `BasePlayerFragment.kt` | 1481 | high (player) |
| C10 | `PlayerViewModel.kt` | 1555 | high (player) |

- *Exit:* E2 = 0; each new collaborator < 600 lines and unit-tested.

### Tier D — finish the open audit findings
- **D1** — 12 open P1s from the 2026-07-19 playback/loading audits.
- **D2** — Deferred items with a decision recorded (keep or drop, not silently pending):
  loading `B-8` (TTL-gate per-category refetch), `ST-5`; debrid cache-status overhaul `R1/R2`;
  player `P15/P16/P19`; licence hardening ×2.
- *Exit:* E7 = 0, and every remaining "deferred" has an explicit written decision.

### Tier E — the residual lint mass + the gate
- **E1p** — `CyclomaticComplexMethod` 130 / `LongMethod` 78 / `NestedBlockDepth` 25 / `ComplexCondition`
  25 / `LongParameterList` 31: most of these dissolve as Tier C lands; the rest are fixed file-by-file.
- **E2p** — The **ratchet gate**: `scripts/check_debt_ratchet.sh` + a pre-commit/CI hook that fails when
  the baseline total exceeds `config/detekt/debt-ledger.txt`. This is what makes "ignore it" impossible.
- *Exit:* E1 = 0, E9 in place.

### Pending device QA — status (kept honest, not "pending forever")

| Item | Status |
|---|---|
| Series final-episode END (T1.5 ghost-advance + prompt) | ✅ **CLOSED by test** (`PlayerEndedActionTest`, 7 green) — see `74710de` |
| Live 429-storm soak (`max_connections=1`) | ✅ **CLOSED by test** (`LiveRetryDelayTest`, 7 green): fail-fast codes never retry, 429 always cools off ≥20s and honours a clamped `Retry-After` |
| Retained-player background OOM soak | ⏳ **Genuinely still device-only.** It is a *duration* test (hours of background/foreground churn under memory pressure); the logic is spread across `onStop` handler purge, `onTrimMemory` release and the `!isResolvingDebrid` play-restore guard, which need Robolectric/instrumented lifecycle driving — a **B2 candidate**, not something a pure function can freeze. |
| Install/verify on device `.35` | ⏳ Blocked: `.35` is powered off / off-network (connect times out). Owner action. |

**Rule learned here:** when a QA item cannot be driven on the device, first ask whether the *decision*
can be extracted into a pure function and frozen by a test. Two of the four above closed that way —
permanently, and better than a one-off manual pass. Only claim "device-only" when it really is.

### Tier G — observability: let the app tell us what it did *(owner-requested 2026-07-26)*

> *"app chale to jo bhi error ya crash ho, app ka mukammal behaviour record kare; kuch din baad wo
> record nikaal kar dekhein app ne kahan kya kiya, aur jo ghalat ho use fix karein — aur ye real users
> ke liye bhi."*

**Most of this already exists — the gap is reach, not invention.** Do not rebuild it:

| Piece | Today | Gap |
|---|---|---|
| `debug/PlaybackDiagnosticsRecorder` | Rich JSONL session log, **41 call sites**, per-event schema, salted hashing of identifiers | `isEnabled()` = `BuildConfig.DEBUG` **and** a `.enabled` marker file → dev-only, and the file must be pulled by adb |
| `FirebaseCrashlytics` | Wired in `GlobalCrashHandler` (crashes) + `PlaybackQoeTracker` (QoE) — **already reaches real users** | Carries crashes/metrics, not the behavioural timeline |
| `SensitiveLogRedactor` | Redacts stream URLs/credentials | Must be applied to anything uploaded |

- **G1 — make it usable for OUR debugging (low risk, do first).** Keep the DEBUG gate, add: size cap +
  rotation (a 3-hour session must not fill the stick), a `finishSession` summary event, and
  `scripts/analyze_diagnostics.sh` that turns a pulled JSONL into a readable timeline + anomaly list
  (repeated reconnects, 429s, stalls, memory growth). **This also answers the OOM soak** — the memory
  samples land in the same log.
- **G2 — reach release builds, opt-in.** Route a *summary* (not the raw timeline) into Crashlytics
  custom keys + non-fatals, which already ship. Sampled, redacted, bounded.
- **G3 — real-user telemetry. NEEDS OWNER DECISIONS, do not start without them:** an explicit
  **consent** screen (this app holds provider credentials — silent behavioural upload is not
  acceptable and may be unlawful), what is collected vs never collected, sampling rate, retention
  period, and where it lands (Firebase is already in the project for licensing). Cheap and honest
  first step: a Settings toggle "Send diagnostics", default OFF, plus a "Share diagnostics" button
  that lets a user hand over one session file on request.

*Sequencing note:* G1 pays for itself immediately (it is how we would diagnose the next "why did it
reload?" without a live session). G3 is a product/legal decision, not a coding task.

### Tier F — owner-facing quality pass
- **F1** — The scripted device QA checklist (E6) covering the known landmines: `.ts` freeze, tunneling,
  AudioTrack first-frame, `max_connections=1` hand-off, D-pad focus theft, EPG correctness.
- **F2** — The outstanding owner QA reminder: series **final-episode END** (ghost-advance + next-episode
  prompt), still unverified.

---

## 4. Known landmines — never regress these

Carried from hard-won incidents; every phase must respect them.

- `max_connections=1`: the Live fullscreen path hands the **running player** over (`LiveSharedPlayer`);
  never re-introduce release-then-reconnect. The zoom bridge must never be `visibility=gone`.
- Never re-add `TsExtractor` `DETECT_ACCESS_UNITS` / `NON_IDR` flags; never set `LiveConfiguration` on raw TS.
- No heavy work on the main thread (multi-MB parses, prefs, DB); bound every network call with a timeout.
- Room migrations are additive and tested; `fallbackToDestructiveMigration` must never ship.
- TV focus: nothing may steal focus on data refresh; every control must be D-pad reachable.

---

## 5. Ledger — updated every phase

| Date | Phase | Baseline | LargeClass | Swallowed | Instr. tests | Commit |
|---|---|---:|---:|---:|---:|---|
| 2026-07-26 | Baseline measured (start) | 359 | 10 | 26 | 1 | `f429283` |
| 2026-07-26 | **A1 (part 1)** — 7 files de-swallowed; found+fixed 3 real `\${…}` escaped-interpolation bugs | **352** | 10 | **19** | 1 | `977b267` |
| 2026-07-26 | **A1 COMPLETE** — remaining 29 sites across 20 files; **E3 met** | **333** | 10 | **0** ✅ | 1 | `4683429` |
| 2026-07-26 | **TIER A COMPLETE** — A2 (PrintStackTrace ×2, `System.gc()`) + A3 (`ImplicitDefaultLocale` ×25) | **305** | 10 | 0 | 1 | `78f1bfe` |
| 2026-07-26 | **B0 + B1** — flaky stopwatch test replaced by 2 behavioural tests (mutation-checked); `scripts/run_instrumented.sh` makes the on-device suite one command | 305 | 10 | 0 | 1 | `f1e2a07` |
| 2026-07-26 | **B2.1** — `LiveSharedPlayerHandoffTest`: the Live fullscreen hand-off contract, 8 tests green on `.64`, 3 injected regressions caught | 305 | 10 | 0 | **10** | `90c84ed` |
| 2026-07-26 | **B2.2** — focus net: 5 instrumented (`FocusPreservingListUpdateTest`) + 9 unit (`FocusCoordinatorTest`); **found + fixed** the `post{}`-before-layout bug that made the CC-1 fallback dead code | 305 | 10 | 0 | **15 ✅** | `0c6644e` |

*(Append one row per landed phase. The three numeric columns may never increase.)*
