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
| E3 | `SwallowedException` (errors silently hidden) | **26** | **0** |
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
- *Exit:* baseline 359 → ~305, E3 = 0.

### Tier B — the test net *(the multiplier)*
- **B1** — Fix the instrumented-test runner path (`connectedDebugAndroidTest` fails with a local JDK
  glitch; the working route is `assembleDebugAndroidTest` + `adb install` + `am instrument`, already
  proven for the Room migration test). Script it so it is one command.
- **B2** — Instrumented tests for the flows that keep breaking and currently need the owner's eyes:
  Live browse → preview → fullscreen hand-off → return restore; channel D-pad focus + quick-jump;
  VOD/Series resume; Room migrations. Target E4 ≥ 15.
- **B3** — Unit tests for every collaborator extracted so far (Live LF-1..LF-7, Movie/Series detail).
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

*(Append one row per landed phase. The three numeric columns may never increase.)*
