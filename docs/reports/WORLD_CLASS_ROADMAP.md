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
| E4 | Instrumented (on-device) tests | **1** | **≥ 15** covering Live/Player/VOD/Series core flows |
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
- **B0** — **Known flaky test:** `XtreamRepositoryStreamLookupTest > stream lookup handles large cache
  efficiently` asserts wall-clock `< 100ms`. It fails under build load and passes in isolation (seen
  2026-07-26). A timing assertion in a unit test is flaky by construction — replace it with a
  complexity/behavioural assertion (e.g. lookup is index-backed, not a full scan) rather than a stopwatch.
- **B1** — Make running instrumented tests one command. **Do not waste time on
  `connectedDebugAndroidTest`** — on this machine it dies inside Gradle's UTP with
  `Cannot run program "C:\Program Files\Java\bin\java": CreateProcess error=1920`. That is a local
  JDK-path glitch, not a test problem. The route below is **already device-proven** (Room migration
  test → `OK (2 tests)`, 2026-07-21); wrap it in `scripts/run_instrumented.sh`:

  ```bash
  ./gradlew :app:assembleDebugAndroidTest
  ADB="C:/Users/Malik/AppData/Local/Android/Sdk/platform-tools/adb.exe"
  "$ADB" -s 192.168.178.64:5555 install -r app/build/outputs/apk/debug/app-debug.apk
  "$ADB" -s 192.168.178.64:5555 install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
  "$ADB" -s 192.168.178.64:5555 shell am instrument -w -r \
      -e class <fully.qualified.TestClass> \
      com.debridxtream.tv.test/androidx.test.runner.AndroidJUnitRunner
  ```
- **B2** — Instrumented tests for the flows that keep breaking and currently need the owner's eyes.
  Priority order (highest pain first):
  1. **Live fullscreen hand-off** — `LiveSharedPlayer.offer/adopt` round-trip: a player parked on the
     way in is adopted (never a fresh reconnect), and adopted back on return. This is the path that
     produced the reload + freeze incidents and it is currently only checked by eyeballing the TV and
     grepping `LIVE_HANDOFF` logs.
  2. **Channel D-pad focus + quick-jump** (`LiveChannelFocusController`) — up/down movement, number-zap,
     A–Z type-ahead, and *no focus theft on refresh*.
  3. **Resume** — VOD/Series/in-player positions (the `createIntent` `startPositionMs` trap).
  4. **Room migrations** — extend the existing migration test to cover every version bump.
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
| 2026-07-26 | **TIER A COMPLETE** — A2 (PrintStackTrace ×2, `System.gc()`) + A3 (`ImplicitDefaultLocale` ×25) | **305** | 10 | 0 | 1 | *(this)* |

*(Append one row per landed phase. The three numeric columns may never increase.)*
