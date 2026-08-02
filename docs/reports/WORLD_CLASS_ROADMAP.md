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
| E1 | detekt suppressions (`config/detekt/detekt-baseline.xml`) | **298** | **0** |
| E2 | `LargeClass` violations | **7** | **0** |
| E3 | `SwallowedException` (errors silently hidden) | **0 ✅ MET** | **0** |
| E4 | Instrumented (on-device) tests — **counted as test methods**, `run_instrumented.sh` reports it | **17 ✅ MET** (3 classes) | **≥ 15** covering Live/Player/VOD/Series core flows |
| E5 | Unit test files | **81 ✅ MET** | ≥ 80, with every new collaborator tested |
| E6 | Crash-free playback landmines re-verified after each phase | ad hoc | scripted device checklist |
| E7 | Open P1 findings from the 2026-07-19 audits | **0 ✅ MET** (D1: 11 of 14 were already fixed, 3 done) | 0 |
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

### Tier B — the test net *(the multiplier)* — ✅ **COMPLETE 2026-07-26**
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
  3. **Resume** — ✅ **DONE 2026-07-26.** 24 unit tests over the two places resume actually breaks:
     - *Identity* (`WatchHistoryKeyTest`, 11): the same show from Xtream ("EN| Breaking Bad 4K",
       numeric series id) and from a Debrid addon (clean title, TMDB id) must collapse to ONE
       Continue-Watching key, or the user sees it twice and resumes the wrong copy. Plus the
       fallback ladder (title → seriesId → tmdb → imdb → contentId), the year-strip guard that
       keeps films literally named "1917"/"2012", and the one-shot removal suppression.
     - *The store* (`WatchHistoryPreferencesTest`, +6 → 8): overwrite-in-place and re-ordering,
       cross-source merge end-to-end, the 20-item cap, the movie resume-bar lookup (contentId /
       tmdb / imdb / provider-tagged title, and it must ignore a same-named series), and a corrupt
       store reading as empty **without wiping** the stored value.
     - *The trap itself* (`PlayerResumeIntentTest`, 5): a position survives `createIntent`; absent
       means start-from-zero; an episode carries its series identity with it; a Debrid resume
       carries the hash/magnet/file-index a re-resolve needs; a live launch carries the hand-off
       flag and no position. *Honest limit:* a test cannot see which **call site** forgot the
       argument — only that the transport works.
  4. **Room migrations** — ✅ **DONE 2026-07-26.**
     - On device (`MigrationTest`, 2 → 4): a populated v14 database reopened through the production
       migration container **keeps watch progress and favourites** (the `fallbackToDestructiveMigration`
       fear, made concrete); and a database stamped below the v4 floor **throws instead of being
       silently wiped**.
     - On the JVM (`DatabaseMigrationChainTest`, 4): every migration is a single step, the chain has
       no gaps or duplicates, the floor is v4, and — the one that matters for the next bump — the
       chain must reach the version Room's **exported schema** says the database ships at. Bump
       `@Database(version = 15)` without writing *and registering* `MIGRATION_14_15` and this goes
       red in milliseconds, long before a device is involved. (`@Database` is BINARY-retained so it
       cannot be read reflectively; the exported schema JSON is generated from it and cannot drift.)
  5. **Retained-player memory behaviour (the "OOM soak")** — the T2.1 retained player keeps a paused
     ExoPlayer (codec + buffers + socket) alive across `onStop` for VOD/Debrid so Home→return is
     instant; on a 1GB Fire TV that is exactly how an app gets OOM-killed. This cannot be a pure
     function — it only shows up across a *duration* and three collaborating places
     (`BasePlayerFragment.onStop` retained branch, `onHostTrimMemory`, and the `!isResolvingDebrid`
     play-restore).

     ✅ **MOSTLY DONE 2026-07-26.** The premise above ("this cannot be a pure function") was only
     two-thirds true. The three collaborating places were three *decisions*, so they were lifted
     into `PlayerRetentionPolicy` (a pure object, behaviour-identical — `BasePlayerFragment` now
     delegates) and pinned by `PlayerRetentionPolicyTest`, **12 unit tests**: LIVE always releases
     at `onStop` (a retained live socket holds the only slot on `max_connections=1`); VOD/EPISODE
     are retained; a finishing session releases; `TRIM_MEMORY_BACKGROUND`+ ends the retention while
     routine `UI_HIDDEN`/`RUNNING_CRITICAL` must not (that would make every Home→return a cold
     reconnect); trim never touches LIVE, which was already released; an in-flight Debrid re-resolve
     owns playback, so resume neither cold-starts nor un-pauses under it; and the two resume paths
     are proven mutually exclusive across every flag combination.

     ⏳ **What is still genuinely device-only:** the *duration* question — does the heap stay flat
     across hours of background/foreground churn. That is a soak, not a decision, and **Tier G1 is
     the intended answer** (the diagnostics recorder already samples memory; the analyzer turns a
     pulled JSONL into a growth curve). Do not re-plan it as a unit test.
  - **Caveat to design around:** the Live surface is secure, so a test cannot screenshot it. Assert on
    *state* (which player instance is attached, focus position, adopted-vs-fresh) rather than pixels.
  - Target E4 ≥ 15.
- **B3** — ✅ **DONE 2026-07-26.** Unit tests for the extracted collaborators — **but by testing what
  they decide, not by instantiating them.**

  Most of LF-1..LF-7 is view wiring (a Fragment, a RecyclerView, an EditText, the FocusCoordinator
  gate); a "unit test" of that is a tautology dressed as coverage. What *does* break is the logic
  buried inside, so three pure decision objects were extracted verbatim (the controllers delegate,
  no behaviour change) and pinned:
  - `LiveChannelQuickJump` (from LF-6) — 16 tests: D-pad steps counted from the **pending** row so a
    held DOWN is not swallowed, ends absorb instead of wrapping, number-zap buffering (leading zero
    ignored, a 5th digit restarts), 1-based channel → 0-based position, and A–Z type-ahead that
    advances on a repeat press and wraps. Documents the real quirk that a bare "4K " prefix is part
    of the name (only *piped* tags are stripped), so "4K Sky" type-aheads under K.
  - `LiveCategoryChips` (from LF-4) — 13 tests: Favourites first and never duplicated by a
    provider's own favourites category, search filtering that drops Favourites, and the
    focus-target ladder (selected → last focused → remembered position if still in range → first),
    which is what makes returning from fullscreen land on the right chip.
  - `LiveZapList` (from LF-3) — 6 tests: the fullscreen channel-up/down order is the provider's
    cached order, adapter-only channels are appended not dropped, and the launching channel is
    always present (a zap list missing it makes the first press jump elsewhere).

  Detail-page collaborators: the source logic they own was already covered (`SourceSorterTest`,
  `SourceFilterUtilsCacheSemanticsTest`, `TrailerValueParserTest`); the rest is view binding.

  The same pass covered the shared pure code these all sit on, none of which had a test:
  `MediaTitleCleaner` (8), `EpgEntity.deOverlap` — the guide's overlap invariant (8),
  `EpgEntity` now/next/ended window (5), `SensitiveLogRedactor` (11 — this is what stands between
  provider credentials and a Tier G upload), `CategoryFlagResolver` (9), icon-URL resolution +
  missing-image detection (7), the Xtream stream-URL builders (6), `ContentEnricher` (8),
  `WatchedIdentityBuilder` (12 — including that URLs, magnets, tokens and info-hashes must never
  become a database key), and `DeviceProfile` (7).

  **Found and fixed while writing them:** `CategoryFlagResolver.resolveCategoryIcon` returned a
  blank badge for a blank category name — `firstOrNull` on a blank string yields `""`, not null, so
  the `?: "#"` fallback never fired, breaking the function's one documented promise.
- *Exit:* ✅ E4 = 17 (≥ 15), ✅ E5 = 80 (≥ 80). Refactors now have a net under them.

### Tier C — the remaining god-classes *(safe once B exists)*
Ordered by risk, lowest first. Same playbook as the completed LiveFragment work (verbatim relocation,
one domain per commit).

| Phase | File | Lines | Risk |
|---|---|---:|---|
| ~~C1~~ ✅ | `SettingsFragment.kt` | ~~787~~ **301** | done 2026-07-26 |
| ~~C2~~ ✅ | `VodFragment.kt` | ~~1301~~ **625** | done 2026-07-27 |
| ~~C3~~ ✅ | `SeriesFragment.kt` | ~~1096~~ **563** | done 2026-07-27 |
| ~~C4~~ ✅ | `SeriesDetailFragmentV2.kt` | ~~843~~ **311** | done 2026-07-27 |
| ~~C5~~ ✅ | `XtreamSeriesRepositoryV2.kt` | ~~927~~ **239** | done 2026-07-27 |
| ~~C6~~ ✅ | `DebridPlaybackRepository.kt` | ~~801~~ **88** | done 2026-07-27 |
| ~~C7~~ ✅ | `LiveTvGuideFragment.kt` | ~~866~~ **500** | done 2026-07-27 |
| C8 | `LivePlayerOsdManager.kt` | 1174 → **965** (in progress) | high (player) |
| ~~C9~~ ❌ | `BasePlayerFragment.kt` | 1491 — **WON'T DO**, decision below | high (player) |
| C10 | `PlayerViewModel.kt` | 1556 | high (player) |



### ❌ C9 `BasePlayerFragment` — WON'T DO (owner decision, 2026-07-27)

Not deferred, not forgotten: **decided against**, so it stops being an open item.

`docs/reports/PLAYER_REFACTOR_PLAN.md` already carries a brace-matched measurement of this file (an
earlier estimate was wrong because it measured the gap between one `fun` and the next, counting the
field declarations *between* methods as method bodies). The corrected reality:

- **~684 lines are method bodies**; the other **~800 are not** — imports (80), 21 collaborator
  `by lazy` + 61 `by session::` state delegates + ~15 view fields (**~215 lines, immovable** — about
  twenty sibling collaborators read them back as `activity.<field>`), plus the companion, host
  bridges, class scaffold and kdoc.
- Only two large bodies exist — `onViewCreated` (132) and `initializePlayer` (100) — and both are
  orchestration cores that cannot leave.
- Extracting **all three** remaining leaf clusters lands the base at **~1250**, not 600. Two of those
  three touch landmines: direct-debrid/addon-proxy (debrid-resume) and display/codec (black video).

So the ceiling is not reachable without either rewriting ~20 collaborators to stop depending on the
host, or shattering the orchestration into artificial micro-collaborators — a readability *loss* in
the most dangerous file in the app.

**Decision:** leave `BasePlayerFragment` as it is. Its `LargeClass` entry stays baselined and the
ratchet keeps it from growing. The player refactor's real wins already shipped and are
device-verified: `PlayerActivity` 3539 → 201 and the fragment split (1620 → 1491 + `LivePlayerFragment`
145 + `VodPlayerFragment` 117). Effort goes to **C10 (`PlayerViewModel`)** instead, which is a
ViewModel and therefore has none of the immovable-view-field problem.


**C1 done 2026-07-26.** `SettingsFragment` 790 → **301** lines (−62%), out of `LargeClass`:
- `SettingsOptionLabels` (pure, 9 unit tests) — the value/label vocabulary and the per-selector
  defaults, which are *not* uniform: audio language and engine fall back to the first option, EPG
  interval / zoom / density to the second. Also the Stremio manifest-URL rule (https or stremio://,
  must contain manifest.json) — plain http is refused so addon traffic is not sent in the clear.
- `SettingsSelectorDialogs` — every chooser plus the Stremio addon manage/add dialogs and the legacy
  Real-Debrid logout.
- `SettingsMaintenanceActions` — the actions that touch app data: force refresh, EPG sync, clear
  cache, Home category selection, and account logout (whose *order* — caches and history on IO,
  credentials last, navigate only after — is the part worth having in one reviewable place).

⚠️ **Dead code deleted, not relocated:** the whole scraper-registry cluster
(`showRegistrySelector`, `showCustomRegistryInput`, `showManageScraperSourcesDialog`,
`showAddScraperSourceInput`, `getRegistryName` — ~130 lines) had **no caller**; the only registry UI
reachable today is the Stremio addon list. **Nothing user-facing is lost:** registry URLs are still
configured through the phone-companion pairing flow (`CompanionConfigServer`,
`RemotePairingManager` → `DebridPreferences.add/removeAddonRegistryUrl`), which is presumably why
the TV-side dialogs were abandoned. Only `SettingsViewModel`'s three wrapper methods are now
unreferenced — left in place, flagged here. The deleted UI is in git history at `1bd1c45`.

*Baseline effect:* 305 → **303** (LargeClass 10 → 9). The second removed entry was a stale
`ComplexCondition` on `LivePlaybackLoadErrorPolicy` that no longer fires at all — the regeneration
surfaced it. Ceilings lowered to match in the same commit.


**C1-follow-up (owner-requested, 2026-07-26): Settings made real and readable.**

*Reality audit — four controls did nothing at all.* Their preferences were written and never read:
- **Player Engine** (ExoPlayer / VLC / MX) — there is no VLC or MX integration; the app always
  builds an ExoPlayer.
- **Tunneling Mode (AFR)** — tunneling is on by default and only ever disabled *per session* by the
  freeze/stall/debrid recovery paths (`disableTunnelingForSession`). The pref was never consulted.
- **Card Animation** — no reader anywhere.
- **Auto-Play Next Episode** — removed as a *control*, but note the **feature runs regardless**:
  auto-advance and the next-episode prompt are not gated by any preference. If a real switch is
  wanted, that is a small change in the player (`endedActionFor` + `PlayerNextEpisodeManager`), not
  in Settings. Flagged rather than silently wired.

*Structure — grouped the way a viewer looks for things:* `PLAYBACK` (audio language, smart audio
fallback) · `LIVE_TV` (layout, resume last channel, guide zoom/density/genre colours, guide
auto-update + interval + update now) · `HOME` · `ADDONS` · `DATA` (refresh catalog, clear cache) ·
`ABOUT` · `ACCOUNT`. EPG sync moved next to the guide it feeds; catalog refresh sits with storage.
`Account` is now a real panel (signed-in user + provider host + Sign Out) instead of a rail entry
that fired a destructive dialog the moment it was selected. The duplicated version footer is gone —
version lives in the rail header and in About.

*Readability — the screen was built at design-px/2, giving 5–8sp text and 32dp rows.* That is
unreadable at TV distance, so the whole screen was rescaled together (bumping only the font would
clip the rows): body **19sp**, category titles **18sp**, secondary **12–16sp**, detail rows
**74dp**, category rows **54dp**, icon tiles **34–38dp**, and the toggle pill 26×14 → 56×30dp with
the adapter's hard-coded dot geometry moved to named constants that track the layout. All seven
categories now fit on a 1080p panel without scrolling. Device-verified with screenshots on `.64`.


**Dialog theming (owner-requested, 2026-07-26): every AlertDialog now matches the app.**
The Preferred-Audio picker rendered as the platform's light-grey sheet with black text on a
near-black UI, because `AppTheme` never declared a dialog theme. Fixed once, globally, rather than
per screen:
- `Theme.DebridXtream.Dialog.Alert` (+ a framework-parented twin) wired into `AppTheme` via
  `alertDialogTheme` / `android:alertDialogTheme` / `dialogTheme`. Surface is the existing
  `bg_dialog_cinematic` glass the player menus already use, accent `neon_cyan`, text `#F1F5F9`,
  and 10-foot sizes (title 22sp, list items 18sp, buttons 17sp) — the platform defaults are
  phone-sized.
- **The theme alone was not enough.** Nine files built dialogs with the *framework*
  `android.app.AlertDialog`, which on Fire OS keeps the vendor's own light styling (the
  "SUBSCRIPTION EXPIRING SOON" sheet was still grey with a gold button after the theme landed).
  All nine now use `androidx.appcompat.app.AlertDialog`, so the app has exactly one dialog look and
  one place to change it: Home, Favorites, VOD, Live guide, Series/Episode adapters, the episode
  browser and the updater.
- Device-verified on `.64`: both the audio picker and the expiry warning render as dark glass with
  a cyan accent.


**Instant-apply everywhere in Settings (owner-requested, 2026-07-27).** Every control in Settings
writes on change — toggles and pickers already did; `CategorySelectionDialog` was the one exception,
with Save/Cancel buttons sitting *below* a list that can run to hundreds of provider categories. To
tick one box the user had to walk the whole list down to Save. It now writes each tick straight to
`HomePreferences`, keeps a live "N selected" count in the title, and BACK is the way out
(`setCancelable(true)`). Home is notified once on dismiss rather than after every tick.
Recycling bug fixed in passing: the row's `OnCheckedChangeListener` was not detached before rebind,
so `isChecked` on a recycled view fired a toggle for whichever category previously occupied it.
The list is also TV-sized now (18sp rows, cyan boxes, a visible focus ring — it had none).
Device-verified on `.64`: tick two → title reads 2 selected → BACK → reopen → still ticked.


**C2 done 2026-07-27.** `VodFragment` 1301 → **625** lines, out of `LargeClass`. Split by what each
part is responsible for, bodies moved verbatim:
- `VodAdapter.kt` — the card adapter/diff/view-holder were already top-level classes just sharing
  the fragment's file; they are the grid's *cards*, not the screen.
- `VodFocusController` (+ `VodGridViews`) — the D-pad map and focus memory, the most
  regression-prone part of this screen. Keeps the two easily-confused jobs apart in code:
  `restoreFocusIfPossible` (came back to the screen, armed only from `onPause`/saved state) versus
  `reassertGridFocus` (data changed *under* a user already in the grid, and refuses to act
  otherwise — CC-1).
- `VodListStateUi` (+ `VodListStateViews`) — skeleton / empty / Retry. The subtle rules now live in
  one place: a skeleton may only replace an EMPTY grid, and "empty" needs the ViewModel *and* all
  three Paging load states to agree, or the screen flashes "No movies" mid-load.
- `VodOverlayController` — favourite/watched/progress badges and the long-press menu, including the
  two performance rules that were learned the hard way (B-12 lifecycle-scoped collectors, B-7
  payload rebinds instead of `notifyDataSetChanged`).

*Ratchet:* baseline 303 → **301**, LargeClass 9 → **8**, ceilings lowered in the same commit. One of
the two removed entries is a real improvement rather than a relocation: splitting
`restoreFocusIfPossible` into per-target helpers dropped it below the complexity threshold. Four
entries were re-keyed by the file move (`VodViewHolder.bind` ×3, `updateListLoadingAndEmptyStates`).
Two genuinely-new `LongParameterList` violations appeared on the new constructors and were **fixed,
not baselined**, by grouping the view references into `VodGridViews` / `VodListStateViews`.


**C3 done 2026-07-27.** `SeriesFragment` 1096 → **563** lines, out of `LargeClass`. Same three-way
split as C2, because it is the same screen shape:
- `SeriesFocusController` (+ `SeriesGridViews`) — D-pad map and focus memory. Carries the one rule
  Movies does not need: placeholders are off, so past the last *loaded* row there is no focusable
  card and a plain DOWN escapes into the sidebar (the "focus jumps to category on fast scroll"
  bug). DOWN is consumed at the edge and turned into a scroll nudge, intercepted on the **card**
  because the RecyclerView's own key listener never fires while a row child holds focus.
- `SeriesListStateUi` (+ `SeriesListStateViews`) — skeleton / empty / Retry, plus the debug-only
  raw error text.
- `SeriesGridExtras` — the favourite toggle, the episode-count cache and the debounced backdrop,
  including B-5 (refresh the cache but do **not** notify the adapter: the old
  `notifyDataSetChanged` restarted every Glide poster load to update a count the card never
  renders).

*Ratchet:* baseline 301 → **298**, LargeClass 8 → **7**. Three of the four removed entries are real
improvements, not relocations — splitting `setupDpadNavigation` into per-surface installers and
`restoreFocusIfPossible` into per-target helpers dropped both under the complexity threshold, and
`LargeClass` is gone. Only `updateListLoadingAndEmptyStates` was re-keyed by the move. No new
violations.

*Noted, not acted on:* Movies and Series are now near-identical screens with near-identical
collaborators. Unifying them is a design change, not a relocation, so it stays out of Tier C.


**C4 done 2026-07-27.** `SeriesDetailFragmentV2` 843 → **311** lines (−63%), out of `LargeClass`.
Split by *surface*, since a detail page is a stack of independent panes rather than one grid:
- `SeriesDetailHeaderUi` — title, plot, the year · seasons/episodes · genre meta line, rating badge
  and the multi-category summary. The separator dots are the reason it is one owner: each dot
  depends on the visibility of the fields on *both* sides of it, and the two feeds that decide that
  (`bindMetadata`, `bindSeriesTotals`) arrive independently, so every path re-runs `updateMetaDots`.
- `SeriesDetailActions` — the Play · Trailer · Favourite · Season row and the season popup. Grouped
  by what the user can *do*, because all four share one concern: D-pad focus. The scale-only focus
  animation exists so it never fights the disabled-Trailer alpha.
- `SeriesEpisodesStripUi` — the strip, its adapter, focus memory, and the **grace window**: Room
  paging reports "empty" instantly on a cold open while `get_series_info` is still on the wire, so
  an empty strip may only be called empty after 12s. That single rule is what separates "No
  episodes" from "still loading" and is now in one testable place.
- `SeriesStreamPanelUi` — the SELECT STREAM panel: slide-in, filter chips, and the two focus rules
  (background made unfocusable while open so D-pad focus cannot escape behind the scrim; a chip
  toggle rebuilds through `updatePreservingFocus` so the user keeps their row — only the initial
  open moves focus).
- `SeriesDetailEnrichment` — the TMDB TV pass (trailer, cast, creator, runtime, age rating) plus
  IMDb/RT via OMDb, including the title cleanup that makes TMDB search work at all.
- `SeriesDetailPage` — the page-scoped facts the collaborators share. `trailer` is the only
  genuinely shared mutable state on this screen (arguments → provider → TMDB, last writer wins),
  so it lives here instead of each writer keeping a copy.

*Ratchet:* baseline 298 → **294**, LargeClass 7 → **6**. **All four** removed entries are real
improvements rather than relocations — `LargeClass` is gone, `bindMetadata` dropped under the
complexity threshold once the rating branch moved out, and both `renderStreamPanel` and
`showSeasonPopup` dropped under `LongMethod` once the list-rendering and per-row builders were
split out. Nothing was re-keyed by the move and no new violation was added.

*Dead code deleted, not relocated:* two private fields, `seriesPlot` and `trailerLookupStarted`,
were written and never read anywhere. Nothing user-facing is lost — the plot is bound straight to
`tvPlot`, and the TMDB pass is already guarded by its own `enrichStarted` flag.

*Equivalent-guard note:* `updatePlayButton`'s stale-result guard compared against `activeEpisode`,
which the strip's focus callback had just set to the same episode. It now compares against the id
the button was labelled with — identical behaviour, minus a cross-collaborator read.


**C5 done 2026-07-27.** `XtreamSeriesRepositoryV2` 927 → **239** lines (−74%), out of `LargeClass`.
First *data-layer* phase, so the C2–C4 UI playbooks do not apply — it is a thin facade over four
collaborators, public API unchanged:
- `SeriesTitleMatching` (pure object, **17 unit tests**) — the name rules that decide which provider
  listings are the same show. They fail in both directions with visible consequences: too strict and
  the SELECT STREAM panel shows one source where five exist, too loose and it offers episodes of a
  different programme. Both halves are pinned (what must merge, what must stay apart), including the
  three quirks that exist for a reason: dash-only prefix stripping so "FBI: Most Wanted" keeps its
  subtitle, required whitespace after the dash so "9-1-1" survives, and ±1 year tolerance because
  regional first-air years routinely differ by one.
- `SeriesSiblingFinder` — the two-stage catalog match (wide SQL `LIKE` pool → normalized-title
  equality → year guard), sourced from the **legacy** `series_v2` table because that is the one
  browse and search actually populate.
- `SeriesEpisodeSync` — the network → DB ladder: `get_series_info` → episodes-only fallbacks →
  sibling adoption, every stage bounded, an empty fetch never overwriting populated episodes, and
  the parent row always written before its foreign-keyed episodes.
- `SeriesStreamAggregator` — the multi-category panel: DB-only and instant, resolution deferred to
  click or to a sweep gated at 2 concurrent fetches, and **fail-open** (a failed fetch keeps the row;
  only a *successful* fetch that still lacks the episode drops it).

*Design change worth noting:* the aggregator now depends on a one-method `EpisodeCacheWarmer`
instead of the whole sync class. That is interface segregation with a purpose — the aggregator must
not be able to trigger a full detail sync, and it is what makes the pipeline testable without a
provider.

*Ratchet:* baseline 294 → **288**, LargeClass 6 → **5**. All six removed entries are real wins:
`LargeClass` is gone, `getSeriesById` dropped under both `LongMethod` and complexity once the
response branches became named methods, `shortCategoryTag` and `buildStreamGroups` dropped under
complexity, and `adoptSiblingEpisodes` dropped under `NestedBlockDepth` once the per-sibling attempt
became its own function. No new violations, nothing re-keyed.

*Behaviour preserved deliberately, not accidentally:* the primary-success branch used to `return`
even when the re-read row was null, so it never fell through to "no data available". A plain
nullable return would have silently changed that, so the sync reports a `SeriesSyncOutcome`
(`Resolved` vs `Unresolved`) instead.

*Device verification (`.64`, 2026-07-27):* this app's UI is not adb-driveable (confirmed again —
`input keyevent` is a no-op on the custom TV views), so C5 was verified with a **new instrumented
test**, `SeriesStreamAggregationTest` (15 tests, real Room DB, seeded catalog): sibling grouping of
decorated variants, the different-year exclusion, primary-category ordering, pending rows for
uncached siblings, the fail-open sweep, and click-time resolution. **Mutation-checked** — flipping
the fail-open branch to drop failed rows turns the suite red. On-device suite is now **32 tests**
(was 17); app launch-stable, zero exceptions in logcat.

✅ *Followed up 2026-07-27 (`518124f`), owner-approved:* `getSeriesById`'s outer
`catch (e: Exception)` swallowed `CancellationException`, so leaving the detail page ran the whole
recovery ladder for a screen nobody was looking at. Fixed, together with the two sites in
`SeriesEpisodeSync` that had the identical bug — the other three already rethrew, which is what made
the outer swallow defeat their intent. `withTimeoutOrNull` absorbs its own timeout before any of
these catches see it, so no timeout became a failure. `XtreamSeriesRepositoryV2RecoveryTest` pins the
half that is reliably observable (catch **ordering**: ordinary failures must still reach the
fallbacks) and is mutation-checked.


**C6 done 2026-07-27.** `DebridPlaybackRepository` 801 → **88** lines (−89%), out of `LargeClass`.
Second data-layer phase; the file held two unrelated jobs that are now separate:
- `DebridLinkResolver` — magnet/infoHash → playable URL: add-magnet → poll → select file →
  unrestrict, plus the 15-minute resolution cache, the rate limiter and the one-shot re-add for when
  Real-Debrid remembers an earlier partial file selection.
- `AddonProxyReadinessProbe` — "will this proxy URL actually play right now", answered
  READY / UNCERTAIN / TERMINAL. The three-way answer is the point and must never collapse to a
  boolean: TERMINAL means advance to the next source, **UNCERTAIN means try anyway** (a probe that
  could not complete says nothing about the stream, and treating it as failure would drop working
  sources on every network hiccup).
- `DebridUrlRules` (pure, **13 unit tests**) — proxy detection, the terminal-status set, the
  redirect-error markers, direct-stream detection and source-key identity.
- `TorrentFileMatcher` (pure, **17 unit tests**) — which file in a season pack is the wanted
  episode, and which resolved link to play.

*Why those two got tests first:* they carry the fixes from the 2026-07-16 empty-sources audit, and
each has a failure mode that is invisible until a user hits it. The error-marker list is now pinned
as data — missing one spelling hands the player an error page to buffer on forever, which is
exactly the "picked a source, got an endless spinner" symptom. So are A2 (`.m4v` must count as video
in *both* `isDirectStreamUrl` and `isVideoFile`, or a `.m4v`-only torrent stalls in
`waiting_files_selection`), A3 (an unmatched hinted torrent falls back to the largest selected video
instead of failing with "episode not found"), and the rule that the source key is the **full**
string so two magnets sharing a prefix cannot collapse onto one rate-limit entry.

*Ratchet:* baseline 288 → **280**, LargeClass 5 → **4**. All eight removed entries are real wins,
none re-keyed: `LargeClass` gone; `resolveDebridUrl` dropped under `LongMethod`,
`CyclomaticComplexMethod` and `NestedBlockDepth` once the preflight became named helpers and the
poll loop became `pollUntilReady` + `onTorrentDownloaded`; `getAddonProxyPlaybackReadiness` dropped
under `LongMethod` once diagnostics became one `record(...)`; and `isDirectStreamUrl`,
`isAddonProxyErrorTarget` and `scoreEpisodeMatch` all dropped under complexity by becoming
table-driven or split by pattern family. Three fresh `LongParameterList` hits were **fixed, not
baselined** (`ProbeSubject` and `TorrentContext` group the fields that always travel together).

⚠ *Dead code deleted, not relocated:* `isAddonProxyPlaybackReady` had no caller anywhere. It was a
wrapper that collapsed the three-way readiness to a Boolean — precisely the distinction the rest of
the code depends on — so it is gone rather than carried forward. `getAddonProxyPlaybackReadiness` is
what every caller already uses.

✅ *Gap found while writing the tests, fixed 2026-07-27 (`344a184`), owner-approved:* the
sample/trailer/extras exclusion ran on `substringAfterLast('/')`, so it only saw the **filename** —
`extras/Show.S01E02.mkv` scored 100 and, because ties break on size, a large featurette could
outrank the real episode. The folder is now checked too, matched as a whole **path segment** and
never as a substring: "Trailer Park Boys" is a real show and a substring test would zero out every
file in it.

*Device verification (`.64`):* full instrumented suite **OK (32 tests)**, app launch-stable, zero
exceptions. Trap worth remembering — the first run showed 5 `FocusPreservingListUpdateTest` failures
("timed out waiting: rows should be laid out") that had nothing to do with the change: the TV had
gone to sleep (`mWakefulness=Asleep`). `input keyevent KEYCODE_WAKEUP` first, then re-run.


**C7 done 2026-07-27.** `LiveTvGuideFragment` 866 → **500** lines (−42%), out of `LargeClass`.
Back to UI, and the first phase since C1 whose verification genuinely needed the owner's eyes:
- `GuideChipsController` — category chips + day tabs. Built once, then only re-styled, because the
  state flow re-emits on every category/day change and rebuilding would destroy focus mid-navigation.
  Holds the two TV-visible details: explicit `nextFocusLeft/RightId` wiring (inside a
  `HorizontalScrollView` an off-screen chip otherwise needs **two** D-pad presses) and the single
  sliding highlight pill, animated by translation+scale around a top-left pivot rather than by
  rewriting `layoutParams` every frame.
- `GuideDetailPanel` — driven purely by **focus**, never by selection. Moving through the grid
  repaints it without touching playback; that separation is what makes this a guide and not a zapper.
- `GuideSearchOverlay` — the in-flight job is cancelled per keystroke (otherwise a fast typist has
  searches racing and the slowest wins), and the dialog gets a fixed window size (the results list
  has `weight=1`, so an unbounded window overflows off-screen instead of scrolling).
- `GuideVideoBridge` — the one video surface, **transformed** between tile and fullscreen, never
  re-parented, so the `TextureView` keeps rendering live through the whole grow.

*The step ORDER stayed in the fragment on purpose.* Black/frozen video on this screen has always
come from ordering, so it must read top to bottom in one place; the bridge only owns geometry.

*Two relocation traps caught:* `parkAtTile`'s retry takes a `stillParked` lambda because the original
re-checked `!isFullscreen` — dropping it would let a late layout retry yank a **fullscreen** video
back to tile size. And the first extraction of `buildPlayerIntent` re-read `CredentialsPreferences`,
which would have substituted an empty string for the value the caller had already null-checked.

*Ratchet:* baseline 280 → **276**, LargeClass 4 → **3**. All four entries gone — `LargeClass`,
`buildChipsIfNeeded`, `updateDetail`, and `observeState`, the last by splitting the auto-select /
resume block into `autoSelectInitialChannel` + `resumeChannelBeyondList` +
`restoreGridFocusIfAppropriate`. No new violations.

*Owner device QA on `.64` — both halves walked and confirmed:* chips advance one per press with the
highlight sliding, search overlay scrolls and lands the picked channel in the grid, day tabs keep
focus (CC-1), the detail panel tracks grid focus, LEFT/UP returns to the selected chip; and
fullscreen grows from the tile, zapping works, BACK shrinks back **live**, the grid selection follows
the channel zapped to — no black, no frozen frame, no re-buffer. Zero app errors in logcat.
*Honest limit:* `Log.d` is suppressed on this device, so the logcat evidence is "no errors", not a
trace of the hand-off — that part rests on the owner's eyes.

*Intended as two commits (C7a/C7b), landed as one:* both halves ended up in the same file, and
reconstructing a C7a-only state would have meant committing an intermediate that was never built —
defeating the bisectability the split was for.

- *Exit:* E2 = 0; each new collaborator < 600 lines and unit-tested.

### ✅ Tier D — finish the open audit findings

#### ✅ D1 DONE 2026-07-29 — and the headline is that the list was stale

Filed as "12 open P1s". Checked against the code one by one rather than trusted: **11 of 14 were
already fixed** — by Tier A/B/C, by earlier batches, or (ST-5) the same day. Three were genuinely
open and were done here:

| P1 | Was | Commit |
|---|---|---|
| **SY-3** | The whole-catalog series fallback ran up to three full fetches with **no timeout at all**, on the interactive path — ~90s worst case | `65318ed` |
| **IMG-6** | The focus glint allocated a gradient, two arrays and two parsed colours **per animation frame**, on every D-pad move | `e35ffd5` |
| **B-3** | "Most Episodes" ordered by two **correlated** subqueries per row, recomputed for the whole table on every page | `de11a3e` |

Already fixed, verified in code: ST-3 (`@Synchronized` idempotency), ST-4 (12h cached expiry), ST-5
(`e9e2ca9`), SY-1 (all-empty guard), SY-2 (per-stage timeouts), B-1 (indices), B-2 (`added_ts`), B-4,
B-5 (payload rebind), IMG-4 (chained transform), IMG-5, IMG-7.

**The lesson, and it repeated in D2: an audit list ages. Verify each item against the code before
working it — otherwise you "fix" what is already fixed and miss what moved.**

#### ✅ D2 DONE 2026-07-31 — a decision on every deferred item

Same story: of the eight, **four were already resolved** and had simply never been closed out.

| Item | Finding | Decision |
|---|---|---|
| **ST-5** — full home reload on every `onResume` | Fixed 2026-07-29 (`e9e2ca9`) — it was the home-screen flicker | **CLOSED, done** |
| **P15** — stall monitor | Done `245f116`, device-QA'd | **CLOSED, done** |
| **P16** — error recovery | Done `d6c1f59`, device-QA'd | **CLOSED, done** |
| **P19** — `initializePlayer` + `onCreate` | Its target moved into `BasePlayerFragment`, and that is exactly the C9 **won't-do** the owner recorded (`5e3a357`). Doing P19 would reopen a closed decision | **CLOSED, superseded by C9** |
| **B-8** — a category open always delete+inserts the whole category, even unchanged | ✅ **DONE 2026-08-02 (`b63a8eb`)** — 6h freshness gate (`CategoryFetchFreshness`) serves a fresh category from Room for Movies + Series (Live already gated via CacheManager); empty-response wipe also closed | **CLOSED, done** |
| **R1** — RD `instantAvailability` is deprecated → the cache check may be blind | Path exists in code but is not exercised: no Real-Debrid token is in use on this installation | **WON'T DO — owner decision 2026-08-02.** The app's debrid flow runs through Stremio addons (StremThru), not a direct RD token, so the RD cache-verification path this item wanted to overhaul is dead weight, not a defect. Do not resurrect; if RD-token use ever starts, re-open from this row |
| **R2** — only the first N hashes are verified, the rest show UNKNOWN and are selectable | `MAX_CACHE_VERIFICATION_HASHES = 10` — caps a path that is not exercised (see R1) | **WON'T DO — closed with R1**, same decision, same re-open condition |
| **Licence hardening ×2** | Plans are written and current | **DEFERRED, with the blockers named** — see below |

**R1/R2 closure (2026-08-02).** These sat "one live RD request away" — but the owner confirmed the
premise was wrong: **no Real-Debrid token is used at all**; debrid runs through Stremio addons. The
live-request blocker was therefore unanswerable by design, and the overhaul it gated has no user. Both
recorded as won't-do above so no future session re-diagnoses "the cache check may be blind" as a bug.

**Licence hardening — ⚠ this block is superseded (kept for history).** Both items closed after D2 was
written: the offline-token shipped instead as the **online-only gate** (`bc6824f`), and device_codes
encryption was superseded by the §7 accounts plan, completed through **U8b** (`4590840`, credentials
banned from `device_codes` by Firestore rule). The only licence item still open is **release signing**
(owner-run, `scripts/setup_release_signing.sh`). Original blocker notes:
1. *Offline-token (anti-piracy):* needed the project's first Cloud Function → Firebase Blaze.
2. *device_codes credential encryption (privacy):* touched the live pairing flow end to end.

- *Exit:* met — every deferred item above now carries an explicit written decision.

### Tier E — the residual lint mass + the gate
- **E1p** — `CyclomaticComplexMethod` 130 / `LongMethod` 78 / `NestedBlockDepth` 25 / `ComplexCondition`
  25 / `LongParameterList` 31: most of these dissolve as Tier C lands; the rest are fixed file-by-file.
- ~~**E2p**~~ ✅ **done 2026-07-27** — The **ratchet gate** is now automatic. Two front-ends over the
  one ledger (`config/detekt/debt-ledger.txt`):
  - `./gradlew :app:check` → the `debtRatchet` task (root `build.gradle`), implemented natively so it
    needs no shell and works on Windows/CI. Deliberately **not** a wrapper around the bash script:
    `bash` on this Windows box resolves to WSL's launcher, not Git Bash, so shelling out would have
    been a silent trap.
  - A **pre-commit hook** (`scripts/githooks/pre-commit`, installed by `./scripts/install_git_hooks.sh`
    via `core.hooksPath` so it stays versioned) running repo hygiene + the ratchet. Read-only, well
    under a second — no compile, no Gradle daemon, so committing stays instant.

  **Verified by breaking it, not by reading it.** A fake `<ID>` added to the baseline (the exact shape
  of "regenerate to silence a new violation") made *both* front-ends fail with
  `TOTAL = 281, ceiling is 280` **and** blocked a real `git commit` — HEAD did not move. A planted
  `printStackTrace()` was caught by both too, which matters because that is the check covering
  detekt's own blind spot. Both then went green again after the revert.
- *Exit:* E1 = 0, E9 in place.

### Pending device QA — status (kept honest, not "pending forever")

| Item | Status |
|---|---|
| Series final-episode END (T1.5 ghost-advance + prompt) | ✅ **CLOSED by test** (`PlayerEndedActionTest`, 7 green) — see `74710de` |
| Live 429-storm soak (`max_connections=1`) | ✅ **CLOSED by test** (`LiveRetryDelayTest`, 7 green): fail-fast codes never retry, 429 always cools off ≥20s and honours a clamped `Retry-After` |
| Retained-player background OOM soak | 🟡 **Half closed by test (B2.5).** The *decisions* — release-on-stop, the trim bound, the resolve-aware resume — are now `PlayerRetentionPolicy` + `PlayerRetentionPolicyTest` (12 green). Only the *duration* half is still device-only, and **Tier G1 owns it** (diagnostics memory samples → growth curve), not a manual soak. |
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

| 2026-07-26 | **B2.3-B2.5** - resume identity/store/intent (24 unit), non-destructive migrations + next-bump guard (4 instr + 4 unit), retained-player policy extracted + pinned (12 unit) | 305 | 10 | 0 | **17** | `46c2799` `12758ea` `240b828` |

| 2026-07-26 | **B3 — TIER B COMPLETE** — 3 pure decision objects out of LF-3/4/6 + tests (35), plus 81 tests over the untested shared pure code; fixed a blank category badge | 305 | 10 | 0 | 17 | `267d5ae` |

| 2026-07-26 | **C1** — `SettingsFragment` 790 → 301 (−62%), out of `LargeClass`; 3 collaborators + 9 unit tests; ~130 lines of dead scraper-registry UI deleted | **303** | **9** | 0 | 17 | `1d03aba` |

| 2026-07-27 | **C2** — `VodFragment` 1301 → 625 (−52%), out of `LargeClass`; 4 collaborators (adapter, focus, list-state, overlays); 2 new LongParameterList fixed not baselined | **301** | **8** | 0 | 17 | `0307344` |

| 2026-07-27 | **C3** — `SeriesFragment` 1096 → 563 (−49%), out of `LargeClass`; 3 collaborators; 3 of 4 dropped baseline entries were real complexity wins, not relocations | **298** | **7** | 0 | 17 | `e7db659` |

| 2026-07-27 | **C4** — `SeriesDetailFragmentV2` 843 → 311 (−63%), out of `LargeClass`; 5 collaborators + a shared page-state holder; all 4 dropped baseline entries were real complexity wins, not relocations | **294** | **6** | 0 | 17 | `e0fcbd6` |

| 2026-07-27 | **C5** — `XtreamSeriesRepositoryV2` 927 → 239 (−74%), out of `LargeClass`; 4 collaborators; all 6 dropped baseline entries were real complexity wins; +17 unit +15 instrumented tests | **288** | **5** | 0 | 32 | `5adb363` |

| 2026-07-27 | **C6** — `DebridPlaybackRepository` 801 → 88 (−89%), out of `LargeClass`; 4 collaborators; all 8 dropped baseline entries were real wins, 3 new LongParameterList fixed not baselined; +30 unit tests | **280** | **4** | 0 | 32 | `7ecfbc2` |

| 2026-07-27 | **C7** — `LiveTvGuideFragment` 866 → 500 (−42%), out of `LargeClass`; 4 collaborators; all 4 dropped baseline entries were real wins; owner device-QA'd both halves | **276** | **3** | 0 | 32 | `fad00a3` |
| 2026-07-27 | **C8** — `LivePlayerOsdManager` 1174 → 584 (−50%), out of `LargeClass`; guide overlay / surf drawer / track controls split out, `LiveChannelVisuals` pure + 10 tests; a `Callbacks` interface fixed the new `LongParameterList` rather than baselining it | **272** | **2** | 0 | 32 | `21a2fdf` + `b574d02` |
| 2026-07-27 | **C9** — `BasePlayerFragment`: **WON'T DO**, owner decision after measuring. Its bulk is lifecycle + key routing + the launch contract, which do not separate into collaborators that can stand alone. Recorded rather than left as silent debt — this is why `LargeClass` ends at 1, not 0 | **272** | **2** | 0 | 32 | `5e3a357` |
| 2026-07-28 | **C10** — `PlayerViewModel` 1556 → 643 (−59%), out of `LargeClass`; 4 steps: pure decisions (`SeriesTmdbMatching` +16, `DebridSourceScoring` +14) → `PlayerEpgController` → `PlayerZapController` → `PlayerDebridResolver` + `XRayMetadataLoader`. **Tier C complete.** | **267** | **1** | 0 | 32 | `85102eb` + `6eb7bec` + `8dc8d73` + `89163ed` |
| 2026-07-29 | **D1** — the 3 P1s that were genuinely still open: SY-3 (unbounded whole-catalog fallback, ~90s worst case, on the interactive path), IMG-6 (per-frame gradient allocation on every D-pad move), B-3 (correlated COUNTs recomputed for the whole table per page). The other 11 of 14 were already fixed — the list was stale | **267** | **1** | 0 | 35 | `65318ed` + `e35ffd5` + `de11a3e` |
| 2026-07-31 | **D2** — an explicit decision on all 8 deferred items. 4 were already resolved and never closed out (ST-5, P15, P16, and P19 which the C9 won't-do supersedes); B-8 kept as its own phase; R1/R2 blocked on one live Real-Debrid check that is the owner's to authorise; licence hardening deferred with its blockers named (Blaze, owner-present pairing QA). **Tier D complete.** | **267** | **1** | 0 | 35 | (docs) |
| 2026-08-02 | **R1/R2 → WON'T DO** — owner confirmed no Real-Debrid token is in use (debrid runs via Stremio addons), so the RD cache-verification overhaul has no user; both closed to stop repeat re-diagnosis. Same pass: stale statuses reconciled (U8b/P27-P28/L1-L4 etc. were done but never closed out — the D1 lesson, a third time) | **267** | **1** | 0 | 35 | (docs) |
| 2026-08-02 | **Phase 7.5** — `fallbackToDestructiveMigration` removed from the production DB builder: an unhandled version now fails loudly instead of silently wiping watch progress + favourites. Gate was already in place (migrations 4→14, exported v14 schema, MigrationTest both halves). MigrationTest OK (4) on device; app then opened the real 153MB v14 DB clean | **267** | **1** | 0 | 39 | `7766e1a` |
| 2026-08-02 | **B-8** — category open TTL gate: a category whose newest Room row is <6h old is served from the DB (Movies + Series; Live was already gated), no network refetch, no delete+insert of unchanged data; empty-response wipe closed with the SY-1 rule. New pure `CategoryFetchFreshness` (+7 unit tests) + instrumented `CategoryFreshnessDaoTest` (+5); on-device suite OK (43); new LongMethod/complexity hits fixed by extraction, not baselined | **267** | **1** | 0 | 43 | `b63a8eb` |

*(Append one row per landed phase. The three numeric columns may never increase.)*

**Tier C is complete at LargeClass = 1.** The remaining one is `BasePlayerFragment`, by the C9
decision above — not a backlog item. Do not "finish" Tier C by reopening it.
