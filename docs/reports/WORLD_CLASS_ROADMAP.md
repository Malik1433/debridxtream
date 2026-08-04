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

### Tier H — the debrid source list: fewer rows, honest languages *(owner-requested 2026-08-03)*

Full plan with file:line evidence for every claim: **`docs/reports/SOURCE_LIST_QUALITY_PLAN.md`**.
The outline below is the executable summary. **Start after E1p.**

**The problem.** Opening a title in Debrid lists 100–500 sources, but most are not different files:
the same release comes back from several addons, so ~10 real files appear as ~40 rows. And most rows
say only `MULTI`, which never says *which* languages — so a customer looking for Hindi cannot find it.

Three findings from the exploration pass change the shape of the fix:

1. **`DebridCacheVerifier.kt:37,63` only cache-verifies the first 10 hashes.** On a duplicate-heavy
   list those 10 checks are spent on 4 copies of 3 files, so most real files never get a cache
   verdict. Collapsing duplicates restores cache accuracy — a benefit nobody asked for.
2. **Streams with no `infoHash` are skipped by dedup entirely** (`SourceHelpers.kt:113-117`) — and the
   hash is usually sitting in the proxy URL path (`.../<40-hex>/<fileIdx>/`, cf.
   `StremioAddonFetcher.kt:38`). Recovering it is the single highest-leverage change here.
3. **Language is *inside* the dedup key** (`SourceHelpers.kt:140-143`), so the same torrent tagged
   differently by two addons splits into two rows. The duplicate problem and the language problem
   have been feeding each other — which is why the order below is not negotiable.

**Owner decisions (locked, 2026-08-03).** Duplicates are **collapsed and counted** ("4 sources"), not
discarded — the count is a reliability signal and the alternates are the failover path. Addon
reliability is **learned silently**, no settings UI. Verified languages are **shared via Firestore**,
so the first person to play a release teaches everyone.

- **H0** — `StreamIdentity.kt`: one "which real file is this?" function. Hash from infoHash ▸ magnet ▸
  **URL path**. Nothing else in the key — languages/delivery/provider were presentation, never
  identity. A weak name+size fallback only when both agree and the title is not a placeholder,
  because collapsing two *different* files is far worse than showing two rows. Behaviour-preserving.
- **H1** — `StreamGrouping.kt`: one row per file + retained alternates; `addonCount` badge. Must run
  **before** `verifyRealDebridCacheStatuses` so the 10-hash budget buys 10 distinct files. Watch the
  `_<idx>` stream-id suffix (`MovieSourceConversion.kt:94-98`) — D-pad focus must not jump.
- **H2** — `SourceAlternates.kt`: a dead link silently retries the **same file** from another addon
  before the file is blacklisted (today one failure blacklists it at
  `MovieDebridPlaybackController.kt:97`). Own budget, separate from `MAX_AUTO_FALLBACK_ATTEMPTS`.
- **H3** — stop inventing languages: `LanguageParser.kt:44,72-79` returns `emptyList()` instead of
  `listOf("en")`; unknown renders as one neutral chip, not a lie. **`MULTI` must then match any
  language filter** — otherwise removing the fake "en" would *hide* rows the English filter used to
  show. Scoring must change in **both** sorters (the sheet sorts twice) or they fight.
- **H4** — learn real audio languages at `PlayerEventListener.kt:260 onTracksChanged`, keyed by
  infoHash; Room **v15** additive table + migration test. Verified chips get a check-mark.
- **H5** — share them via `release_languages/{hash}` (opaque id, no PII), `arrayUnion` + `increment`,
  fire-and-forget in `DeviceIdentity`'s posture; bounded read that never overwrites cached data.
- **H6** — silent per-addon reliability in SharedPreferences. **First rendered frame is the only
  honest success signal.** Neutral until ≥5 observations; a tiebreak *within* the family only, so a
  bad night cannot invert the list.

**Order matters:** H1 first (the only immediately visible win, and it frees the cache budget) → H2
(needs H1's alternates) → H3 (must follow H1, language is inside the dedup key today) → H4 → H5 → H6.

**File-layout constraint:** `MovieDebridSourceController.kt` (613 lines) and
`MovieDebridPlaybackController.kt` (556) are at/over the 600 ceiling — **all new logic goes in new
files**, never into those two.

---

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
| 2026-08-02 | **fix(focus)** — two D-pad drops found by the remote QA walk: detail→BACK recreated the browse view and lost the focus controller's memory (fragment-level snapshot now re-feeds it, Vod + Series); Settings opened with nothing focused (initial rail focus + sidebar-close retries). Device-verified via uiautomator | **267** | **1** | 0 | 43 | `d9bc12a` |
| 2026-08-02 | **E1p batch 1 — ComplexCondition ELIMINATED and pinned at 0.** All 24 entries named into predicates/vals/marker lists (verbatim, behaviour-preserving); 2 CyclomaticComplexMethod fell out for real; a new LongMethod hit fixed by extraction not baselined. Player files: pure predicate extractions only, landmines untouched; live playback re-verified on .64. Baseline diff 26 deletions / 0 additions | **241** | **1** | 0 | 43 | `b4b475c` |
| 2026-08-02 | **E1p batch 2a — 7 LongParameterList data clumps** (function-level only): SyncProgress passed whole (12 call sites), ProgressSnapshot/WatchedProgressStamp in PlayerHistoryManager, RectF bounds for EpgGridView.drawProgram (reused member, no draw-loop alloc), buildStreamUrl split, 2 dead params deleted in DebridSourceOrchestrator, test helper defaulted. Diff 7 deletions / 0 additions; device sanity: guide render + live play + exit clean. **Remaining LPL = 22: ~7 DI/Hilt ctors (needs a policy decision — bundling injected deps is fake cohesion; option: rule ignoreAnnotated Inject/Provides), ~11 collaborator ctors (holder/Callbacks pattern), 3 ViewHolder binds, 1 diagnosticsSourceFields (the P14 nine-field debrid profile clump — owner-gated)** | **234** | **1** | 0 | 43 | `ec77eee` |

| 2026-08-02 | **E1p — DI exemption (owner decision):** `LongParameterList` now ignores `@Inject`/`@Provides` — the framework handing a class its collaborators is not a design smell, and bundling injected deps would be fake cohesion. Exactly the 6 DI sites left the baseline; the rule still fires everywhere else. Remaining LPL = 16 real targets (collaborator ctors + ViewHolder binds + the owner-gated diagnostics clump) | **228** | **1** | 0 | 43 | `d98922b` |

| 2026-08-02 | **E1p batch 3 — 6 NestedBlockDepth leaf sites** (QrGenerator, EpgParser ×2, XtreamResponseParser, trackDiagnosticsFields, LiveSearchAdapter per-type creators); a LongMethod + a CyclomaticComplexMethod fell out for real with the ViewHolder split. Player-path depth entries deliberately deferred to their own session (control flow near landmines). Diff 8 deletions / 0 additions; both TVs on this build. Kapt trap recorded: raw [brackets] in KDoc break stub generation | **220** | **1** | 0 | 43 | `b9ef8a6` |

| 2026-08-02 | **E1p batch 4 — 8 NestedBlockDepth data-layer sites** (SeriesRepository ×4, VodRepository ×2, LiveRepository, BasePagingSource): verbatim inner-block extractions — category-persist blocks, L2 preload, DB saves, the 404 fallback branch, detail persist + fallback-episodes parse, year-veto predicate, paging cache-fill. Two pure detail helpers placed file-level (not in-class) because SeriesRepository sits right at the LargeClass ceiling — detekt caught the overshoot mid-batch and the placement fixed it without baselining. Diff 8 deletions / 0 additions; device sanity on .64: launch + guide + live play (steady ~10 Mbps) PASS. **NestedBlockDepth remaining = 9, all player-path** | **212** | **1** | 0 | 43 | `c957c73` |

| 2026-08-02 | **E1p batch 5 — NestedBlockDepth ELIMINATED and pinned at 0** (the last 9, all player-path): PlayerEventListener READY/ENDED split + live-ENDED reconnect helper, PlayerInputRouter VOD-BACK + ACTION_DOWN blocks, PlayerRecoveryController watchdog-extension + timeout-retry step, PlayerTrackManager shared track-scan helpers, PlayerSeriesController browser-loading trigger, PlaybackResolver per-attempt outcome, HomeNavigationRouter CW openers (EPISODE/SERIES arms were byte-identical → one helper). Landmine discipline held: bodies verbatim, watchdog/reconnect ordering untouched. Baseline 20 del / 4 add — the 4 are documented relocations of already-baselined complexity into the extracted names (improve in a later, separate commit); CCM+LM fell for real on 5 other functions. Device: VOD debrid resolve + resume + 4K HEVC + BACK exit, live play + zap PASS on .64 | **196** | **1** | 0 | 43 | `7df2dde` |

| 2026-08-02 | **E1p batch 6 — the 3 ViewHolder-bind LongParameterLists** via data-class bundling (the rule exempts data classes by design): CategorySidebarAdapter `RowBind`, VodAdapter `VodOverlays` (replaces the Triple end to end), LiveNavRail adapter-constant `Callbacks` bundled once. Baseline 5 del / 2 add — the 2 are VodViewHolder.bind's pre-existing LongMethod+CCM re-keyed by the new signature, body unchanged. Device: Movies browse sidebar + counts + grid + card focus PASS on .64. **Remaining LPL = 13: 12 collaborator ctors + the owner-gated diagnostics clump** | **193** | **1** | 0 | 43 | `7a0d70f` |

| 2026-08-02 | **E1p batch 7 — all 12 collaborator-ctor LongParameterLists** via cohesive data-class bundles (Deps/Views/Accessors/Callbacks; the rule exempts data classes) + in-class `private val x = bundle.x` re-plumb so method bodies stayed byte-identical; only the 4 construction sites (XtreamRepository, LiveFragment, MovieDetailActivity, SeriesDetailActivity) changed shape. SyncManager, 5 Live controllers, 3 Movie + 3 Series detail collaborators. Baseline 12 del / 0 add; **LongParameterList = 1 (the owner-gated diagnosticsSourceFields clump) and PINNED**. Device: full Movie debrid flow (sources sheet → resume → 4K HEVC) + classic live play PASS on .64; Series controllers same mechanical pattern, unit suite green — 30s owner spot-check of a Debrid series detail recommended on next use | **181** | **1** | 0 | 43 | `b6b0783` |

| 2026-08-02 | **E1p batch 8 — the 4 documented relocations from batches 5/6 honestly improved** (the promised separate commit): PlayerEventListener.onStateEnded → VOD mid-stream resume helper + pure `endedMidStream` (+3 tests); PlayerInputRouter.handleActionDownKey → 8 per-key handlers, null pass-through contract intact; PlayerRecoveryController.retryAfterBufferTimeout → re-resolve escalation + engine-rebuild helpers, bodies verbatim; VodViewHolder.bind → pure `parseVodCardText` (+11 tests, the ends-in-a-lang-code quirk frozen as a documented test). Baseline diff 6 del / 0 add. Device sanity .64: launch + live browse/preview + fullscreen hand-off + CHANNEL_UP zap (seamless switch, new first frame) PASS — the zap walk exercised the refactored router end-to-end. **Relocation debt = 0; remaining mass = CCM 103 + LM 64 + TMF 4 + Spread 2 + the 2 pinned singles** | **175** | **1** | 0 | 43 | `c0ad6a5` |

| 2026-08-03 | **E1p batch 9 — 10 parser/leaf CCM+LM sites, no player-path code.** When-chains that were lookup tables became DATA with the original branch order kept: CategoryFlagResolver country/icon marker lists + `prefixCountryCode`; LanguageParser label map; DebridFailureClassifier → ordered `FAILURE_RULES` (C6 lesson honoured: marker spellings are data — add, never prune; only rate-limit carries Retry-After). EpgParser: `parseStream` → createStreamingParser/runParseLoop/handleEvent/handleStartTag/emitProgramme (heap-pressure degradation verbatim), StrippingReader → queueTagStart/handleProcessingInstruction (plain-char hot path untouched). **New trap learned: detekt CCM counts continue/break jumps** (read() measured 19 vs 13 by plain McCabe). All 4 files already unit-tested; suites + full suite green. Baseline diff 10 del / 0 add. Device: launch + Live browse (categories/flags) + preview→fullscreen play (~14 Mbps) PASS on .64. **Remaining: CCM 97 + LM 60 + TMF 4 + Spread 2 + the 2 pinned singles** | **165** | **1** | 0 | 43 | `e3873cd` |

| 2026-08-03 | **E1p batch 10 — 9 utils/conversion/index CCM+LM sites**, no player-path code. SourceFilterUtils: language parse → code map, filter → per-stage helpers, apply → computeScores + lead-comparator pick (chain order byte-identical); MovieSourceConversion: error markers → data, per-stream body → convertOneStream + 6 helpers with a `ConvertedStreamFacts` data-class bundle (no @Suppress — the batch-6/7 pattern), isDirectStreamUrl → marker/extension lists (C6 ".m4v both lists" invariant noted); SearchIndexManager: the 3 copy-pasted stage bodies now share fetchOrEmpty/fetchCategoryOrNull/indexPerCategory (log strings + pass rule byte-identical). Baseline diff 9 del / 0 add; full suite green. Device: debrid CW resume ran the refactored conversion end-to-end (85/85 sources, 100%) + played PASS on .64. Process trap: a here-string commit message with quotes broke and spawned zero-byte junk — hygiene hook caught it; use `git commit -F <file>` for long messages. **Remaining: CCM 89 + LM 59 + TMF 4 + Spread 2 + the 2 pinned singles** | **156** | **1** | 0 | 43 | `5ba1950` |

| 2026-08-03 | **E1p batch 11 — all 12 adapter-bind CCM+LM entries** (BrowserAdapters ×2, ContinueWatchingAdapter, CinEpisodeAdapter, EpisodesAdapterV2, ChannelPagingAdapter.bindHorizontalCard). Pure decisions extracted (`categorySidebarCode`, `browserChannelBadges`, `liveChannelBadgeFlags`); the CW quick-actions bubble bind split into content/card-listeners/chip-listeners/focus helpers with closure vars promoted to holder fields (same per-bind reset lifecycle); the duplicated 3-state sidebar styling collapsed into one `applyStyle`. Bodies verbatim, bubble semantics + no-glint NOTE untouched. Baseline diff 12 del / 0 add; full suite green. Device: home CW cards + Series detail episode strip bound clean on .64. **Remaining: CCM 83 + LM 53 + TMF 4 + Spread 2 + the 2 pinned singles** | **144** | **1** | 0 | 43 | `f19664c` |

| 2026-08-03 | **E1p batch 12 — 9 Home focus/key/nav sites.** HomeFocusManager: initial-focus gate + target chain split (owner "fresh open → hero Play" rule intact), snapshot capture's 4 copy-pasted branches → contentAreaOf/snapshotIn/stableIdAt, both post-layout restore halves named; HomeKeyRoutingManager: per-area when-chains → `RAILS_ABOVE`/`RAILS_BELOW` ordered data + focusFirstPopulatedRail; HomeNavigationRouter: CW resume click → `CwResumeFacts` bundle + buildCwResumeIntent (startPositionMs + all debrid extras verbatim), launchLiveStream → buildLiveIntent. Baseline diff 9 del / 0 add; full suite green. Device: hero initial focus + hero↔rail D-pad + debrid CW direct resume (RESUME_DECISION true → first frame) PASS on .64. **Remaining: CCM 75 + LM 52 + TMF 4 + Spread 2 + the 2 pinned singles** | **135** | **1** | 0 | 43 | `46d1a00` |

| 2026-08-03 | **E1p batch 13 — 10 source-picker UI sites.** MovieSourceAdapter (bind split, provider when-chain → `PROVIDER_BADGE_RULES` ordered data, flag map, row key-nav helper); SourceListSection Compose (chip state → remembered `SourceChipFilters` holder, chips/rows/item-sections as small composables, language map); SourceHelpers.isDebridContent (5 pattern lists → file-level data with the removed-2-char-patterns NOTE kept, 2 predicates); SourceSelectionBottomSheet.updateUi → 3 state helpers + restoreListFocusIfNeeded. Baseline diff 10 del / 0 add; full suite green. Device: SELECT SOURCE sheet rendered end-to-end via the refactored paths (badges/flags/pills/BEST + EN-priority header), row focus stable, BACK clean — on .64. **Remaining: CCM 70 + LM 47 + TMF 4 + Spread 2 + the 2 pinned singles** | **125** | **1** | 0 | 43 | `22c07f3` |

| 2026-08-03 | **E1p batch 14 — 7 Stremio Debrid-home CCM sites.** StremioNavigationRouter got the batch-12 twin treatment (CwResumeFacts + buildCwResumeIntent, openCwMovie/SeriesDetail split, buildLiveIntent); StremioHomeFragment.selectNav → hero-visibility/tab-styling/nav-DOWN helpers + wireContentFocus split; StremioDiscoverSection pills → isPillActive/pillRestingBg + expandOptions/buildOptionChip. Baseline diff 7 del / 0 add; full suite green; app stability verified on .64 (no crashes). Stremio home's interactive tab/pill walk not remotely drivable this session — **30s owner spot-check of the DEBRID section recommended** (batch-7 precedent). **Remaining: CCM 63 + LM 47 + TMF 4 + Spread 2 + the 2 pinned singles** | **118** | **1** | 0 | 43 | `39fdb70` |

| 2026-08-03 | **E1p batch 15 — 10 EPG + focus-utils sites.** EpgSyncWorker.doWork (cancellation checkpoints + never-retry policy intact), EpgSyncManager.fetchAndSaveEpg → 4 stages with the **generational replace byte-identical** (EpgGenerationalReplaceTest green), EpgCache.warmNowNext → DB-warm/short-EPG stages, EpgGridView per-direction movers, WavySeekBar's twin scrub branches → one `scrubBy` (seek-snap fix ordering kept), FocusPreservingListUpdate pre-draw restore named (B2.2 timing untouched), FocusTrapHelper extremes when → predicates, LanguageFilterAdapter flag map. Baseline diff 10 del / 0 add; full suite green. Device: guide open + preview → fullscreen live play (~14-16 Mbps) PASS on .64. **Remaining: CCM 55 + LM 45 + TMF 4 + Spread 2 + the 2 pinned singles** | **108** | **1** | 0 | 43 | `f17c68a` |

| 2026-08-03 | **E1p batch 16 — DebridFragment + rows adapter (4 sites).** updateHero → passive-hero + per-badge binders; restoreFocusIfPossible → id→index→first-card ladder named + flattened; DebridItemViewHolder.bind → subtitle/poster/focus-animation helpers (16:9 CW sizing + IMG-1/IMG-5 Glide flags in place). Mid-batch detekt caught 2 overshoots (a CCM-15 helper, an NBD fire) — **fixed by further splitting, not baselined**. Baseline diff 4 del / 0 add; full suite green; launch stability PASS on .64. **Remaining: CCM 52 + LM 44 + TMF 4 + Spread 2 + the 2 pinned singles** | **104** | **1** | 0 | 43 | `38a43a58` |

| 2026-08-03 | **E1p batch 17 — 7 Debrid activity/viewmodel sites; the ledger crosses under 100.** SeeAll's 18-row discovery when-chain → `DiscoveryQuery` data map (defaults mirror the repo signature so omitted params behave identically); DebridDiscoverActivity dropdown/selectors decomposed with every SECURE-FOCUS rule verbatim; DebridViewModel.loadCatalog → `applyCatalogResult` (per-branch version re-check + the failed-refresh-never-destroys-good-data rule intact); the two onCreate state-collectors named. Baseline diff 7 del / 0 add; full suite green; launch stability PASS on .64. **Remaining: CCM 49 + LM 40 + TMF 4 + Spread 2 + the 2 pinned singles — the bulk of what's left is player-path, deliberately saved for last** | **97** | **1** | 0 | 43 | `94d296a0` |

| 2026-08-03 | **E1p batch 18 — 12 licensing/update/companion/addon infra sites.** LicenseManager.start → 4 named stages (fail-open enforce switch, create-only-if-absent doc, revocation handling, type-robust snapshot sync + integrity re-seal — the isFromCache online-gate stamp stays inline where ordering matters); UpdateManager download pipeline (an NBD overshoot flattened, not baselined); Companion sync/applier/server/setup (PIN lockout + local-network gate byte-identical); Stremio/Dynamic fetchers (the device-verified "No Matching File" drop guard kept); AddonCatalogRepository.getDiscoveryContent → `DiscoverParams` bundle + per-type arms. Baseline diff 12 del / 0 add; full suite green. Device: launch + home content reached, **license gate not locked** on .64. **Remaining: CCM 41 + LM 36 + TMF 4 + Spread 2 + the 2 pinned singles** | **85** | **1** | 0 | 43 | `d9780225` |

| 2026-08-03 | **E1p batch 19 — 9 home/search/sync/repository sites.** HomeViewModel.loadHomeData split into Phase-1/Phase-2 builders + row/enrich helpers with `LocalHistory`/`HomeCreds` bundles — **the ST-2 two-phase contract and the F2/F10 "timeout keeps the Phase-1 snapshot" rule are unchanged**; HomeHeroManager backdrop/buttons/glow split; SearchViewModel per-scope searches; SyncManager progress reporters + **SY-1 all-empty guard preserved exactly** (never overwrite a populated cache); SeriesRepository B-8 TTL gate + response arms; VodRepository candidate order + `MovieMatchCriteria` bundle (no @Suppress — LPL stays pinned at 1). Baseline diff 9 del / 0 add; full suite green. Device: fresh launch ran Phase 1 → Phase 2 (10 TMDB movies + 10 series logged), hero/CW/rows rendered, focus on btn_hero_watch. **Remaining: CCM 37 + LM 31 + TMF 4 + Spread 2 + the 2 pinned singles — from here it is player-path + detail screens only** | **76** | **1** | 0 | 43 | `215dda2b` |

| 2026-08-03 | **E1p batch 20 — 10 detail/browse UI sites** (no playback-engine code). SeriesSeasonUi popup row + season-progress reader (Triple → `SeasonProgress` holder, two-source precedence + CC-1 focus restore intact); MovieDetailActivity metadata/back-hint/type/plot binders + initViews → view-refs / similar-row / controllers (×3); VodFragment observer → `renderVodState`; VodListStateUi error extraction. Two mid-batch overshoots split further, **neither baselined**. Baseline diff 10 del / 0 add; full suite green. Device on .64: movie detail full metadata row, series detail season button + watched counter, Movies browse sidebar/counts/header — all PASS. **Not verified: the season popup's interactive open** — every series listing the provider returned was dead (pre-existing `getSeriesById` array-vs-object + 10s fallback), so no season had episodes; that popup was already on the C4 owner-eyes-on list. **Remaining: CCM 32 + LM 26 + TMF 4 + Spread 2 + the 2 pinned singles** | **66** | **1** | 0 | 43 | `2a060ba1` |

| 2026-08-03 | **E1p batch 21 — the last 8 non-player sites; everything outside the player path is now clear.** MainActivity licence-gate + EPG-refresh split (gate order and the ST-7 head start unchanged); SettingsFragment `updateDetails` → `itemsFor` + one builder per category (7 arms, byte-identical rows); FavoritesFragment per-type play; MovieDetailFragmentV2 enrichment/arg-binding split (the "only write what the state carries" rule kept); MovieDetailViewModelV2 candidate scoring; MovieInfoSection Compose split; SeriesDetailActivity collaborator wiring. `itemsFor` was still 155 lines after the first split — **split per category, not baselined**. Baseline diff 8 del / 0 add; full suite green. Device on .64: licence gate passed + home rendered, Settings showed all 7 categories with Playback and Home Screen panels rendering their exact rows. **Remaining: CCM 30 + LM 20 + TMF 4 + Spread 2 + the 2 pinned singles — 100% player-path (BasePlayerFragment, PlayerActivity/ViewModel/TrackManager/Recovery/Series/Stall/Debrid, LiveFragment, Movie/Series debrid controllers, DebridSourceOrchestrator). Those need real playback QA per batch, not launch-stability** | **58** | **1** | 0 | 43 | `f6568b4b` |

| 2026-08-03 | **E1p batch 22 — 9 Tier-1 player-adjacent sites (rendering/metadata, no playback state machine).** PlayerMetadataBinder → title/subtitle builders; PlayerLiveEpgRenderer → status/now-row binders; PlayerHistoryManager.saveProgressSnapshot → shared `isUntrackableDuration`/`isWatchedThresholdFor` (duplicate when-block also left the exit path); PlayerHelpers.resolveMediaMimeType → ordered `MIME_RULES` data table (branch order byte-identical, PlayerHelpersTest guards); PlayerViewModel.findBestTmdbTvMatch → `bestTvCandidate` (batch-21 twin); TrailerActivity iframe HTML → top-level template const (**first helper re-fired LongMethod — split further, not baselined**); SeriesFragment/SeriesListStateUi got the batch-20 Vod twin treatment; XtreamRepositoryTest mock builders. Baseline diff 9 del / 0 add; full suite green. Device QA on .64 — real playback: Live fullscreen (~8 Mbps 1080p first frame) + CHANNEL_UP zap (seamless switch + new first frame 720p) + clean BACK; **Debrid CW resume RESUME_PATH=DIRECT, 45s play, progress persisted 9:04→10:12**; Series browse sidebar/counts/sort/category-switch; Trailer opened via detail button through the new template, no fallback loop. Logcat clean. **Remaining: CCM 23 + LM 18 + TMF 4 + Spread 2 + the 2 pinned singles** | **49** | **1** | 0 | 43 | `1fc7b143` |

| 2026-08-03 | **E1p batch 23 — Tier 1 rest + the batch-19 leftovers (8 sites); Tier 1 is now clear.** PlayerActivity.createIntent → private `LaunchExtras` data-class bundle + grouped Intent extension helpers (public signature + every put byte-identical, no @Suppress); LiveNavRail.setExpanded → 3 animation helpers; LiveEpgPreviewController.maybeRestorePreviewFromState → buildRestoredStream + shouldSyncHistory with the **LiveSharedPlayer.isParked() defer kept FIRST** (ordering-sensitive reload-race fix); LiveTvGuideViewModel.loadChannels → category/EPG-window stages; SpatialNavigationEngine → ViewBox + orthogonal/distance predicates; SyncManager.syncContent → per-type fetch stages + report helpers, **SY-1 all-empty guard + SY-2 stage timeouts byte-identical** (first trim still fired LM — split further, not baselined); VodRepository.getMovieSources loop + primary-fallback split; UnifiedSourceProvider.getMovieSources log/filter extraction. Baseline diff 8 del / 0 add; full suite green. Device QA on .64 — real playback: guide 18327-ch catalog + tile→fullscreen adopt→BACK adopt-return (same codec); **classic LiveFragment walked by flipping live_tv_style to classic (restored after)** — preview first frame, fullscreen handoff gap 25ms, BACK "ADOPTED back (no reload)"; debrid CW resume through the new createIntent bundle (DIRECT, first frame, progress 10:12→10:52 persisted); Series grid D-pad clean. Not remotely exercisable: rail expand animation, full provider re-sync (verbatim + unit-suite covered). **Remaining: CCM 17 + LM 16 + TMF 4 + Spread 2 + the 2 pinned singles — all Tier 2 (debrid controllers) + Tier 3 (player landmines)** | **41** | **1** | 0 | 43 | `c8175472` |

| 2026-08-03 | **E1p batch 24 — Tier 2 movie-side debrid controllers (5 methods / 7 entries).** MovieDebridPlaybackController.playMovie → route predicates + `XtreamUrlFacts`/`MoviePlaybackFlags`/`IptvMovieLaunch` bundles + readiness gate (creds fetch stays BEFORE the debrid branch); playDebridMovie → direct/resolver splits sharing ONE `launchDebridMovie(DebridMovieLaunch)` — the plan carries exactly what each path put in the intent, error-marker/failed-id handling add-only, resume-play-then-repair intact; MovieDebridSourceController picker → sheet-builder/cached/load splits + shared diagnostics record; refreshMediaFusionCacheStatus → `probeAddonProxyReadiness` (Semaphore-4 verbatim) + `applyReadinessUpdates` (UNCERTAIN never downgrades); PlayerDebridCoordinator.switchToMovieSource → route + identity + per-route arms (**first pass still CCM-15 — split further, not baselined**; the tunneled-black-video `startOrSwitchToResolvedUrl` untouched). Baseline diff 7 del / 0 add; full suite green. Device QA on .64: Watch Now → SELECT SOURCE sheet (112 sources, EN-priority, BEST) → picked 4K source → Resume 10:52 → first frame 3840x1600 tunneled HEVC ~15 Mbps, clean exit. **Not remotely exercisable: in-player Sources-panel switch (secure surface) — owner spot-check candidate.** **Remaining: CCM 13 + LM 13 + TMF 4 + Spread 2 + the 2 pinned singles** | **34** | **1** | 0 | 43 | `91d5daf8` |

| 2026-08-03 | **E1p batch 25 — Tier 2 series-side debrid + the orchestrator (5 methods / 9 entries); Tier 2 is now clear.** SeriesDebridPlaybackController.playDebridEpisode → the batch-24 movie twin (direct/resolver splits + ONE `launchDebridEpisode(DebridEpisodeLaunch)`; per-path headers/extras byte-identical); fetchAndShowDebridSources → sheet-builder / cached / load / IPTV-fallback splits with the 4 copy-pasted stale-request guards collapsed into `isStaleSourceRequest` (show-then-verify order kept); refreshMediaFusionCacheStatus → probe + apply (UNCERTAIN never downgrades); DebridSourceOrchestrator both discovery methods → `tryScoped*Resume` fast-paths + `fetchAll*ProviderStreams` parallel fan-outs (per-provider fail-open catches verbatim) + breakdown/no-sources log helpers + `EpisodeQuery` bundle + `resolveSeriesImdbId`. Baseline diff 9 del / 0 add; full suite green. Device QA on .64 — real playback: **CW "Lioness - S01E01" resume ran the refactored orchestrator live ("Scoped resume fetch hit (episode): provider=STREMIO, streams=202"), first frame 1080p, ~5-6 Mbps, clean exit.** Series-detail sheet still not walkable (pre-existing dead series listings, C4 owner-eyes-on). QA note: home CW-row uiautomator focus dumps went stale — screenshots are the reliable check there. **Remaining: CCM 8 + LM 9 + TMF 4 + Spread 2 + the 2 pinned singles — ONLY Tier 3 landmines left (BasePlayerFragment, PlayerRecovery/Track/Stall/Series/InputRouter, LiveFragment, PreviewPlayerPanel)** | **25** | **1** | 0 | 43 | `3d003c1f` |

| 2026-08-03 | **E1p batch 26 — Tier 3a: Track/Series/Stall (3 methods / 4 entries), pure extraction, no reordering.** PlayerTrackManager.showSinglePressTrackSelection → options/index builders + `applyTrackChoice` with the 6 numbered Android-TV apply steps verbatim and their order documented load-bearing; PlayerSeriesController.playNextEpisode → `advanceDebridEpisode` + pure `debridNextTarget` decision (endedActionFor template); PlayerStallMonitor.checkVideoRenderProgress → `recoverLiveFreeze`/`recoverVodFreeze` (tick order, live re-prepare caps, tunneling-off first strike all byte-identical). Baseline diff 4 del / 0 add; full suite green. Device QA on .64: debrid CW resume + 75s steady playback with the refactored stall monitor ticking (zero false freeze recoveries), clean exit. Track dialog + auto-advance not remotely drivable (secure surface). *(Correction to the batch-25 row's remaining-split: it was CCM 10 + LM 7.)* **Remaining: CCM 7 + LM 6 + TMF 4 + Spread 2 + the 2 pinned singles — BasePlayerFragment ×3, LiveFragment ×3, PlayerRecoveryController, PlayerInputRouter, PreviewPlayerPanel** | **21** | **1** | 0 | 43 | `2b53a7ba` |

| 2026-08-03 | **E1p batch 27 — Tier 3b: Recovery/InputRouter/PreviewPanel (3 methods / 4 entries), pure extraction, order preserved.** handlePlaybackError → ordered stages (audio-sink HAL recovery, the three fail-fast HTTP branches, 429 cool-off, retryOrFail with the aggregate budget → direct-refresh → resolver-re-resolve chain byte-identical; **repairable-expired-link stays computed BEFORE the terminal branches** — the resume-play-then-repair fix untouched); dispatchKeyEvent → `overlayBackResult` for the five overlay/PiP BACK consumers, **with the VOD-back branch kept in place so handleVodBack's null PASS_THROUGH never falls into ACTION_DOWN routing** (null-contract exact); PreviewPlayerPanel.play → `buildTunedPreviewPlayer` (LP-ADOPT tuned engine verbatim). Baseline diff 4 del / 0 add; full suite green. Device QA on .64 (classic flipped via pref, restored): preview built by the NEW builder (720p first frame) → fullscreen adopt gap 24ms → CHANNEL_UP zap through the refactored router (seamless switch + 1080p HEVC first frame) → BACK "ADOPTED back (no reload)". Error-recovery stages fire only on real errors — moved verbatim. **Remaining: CCM 5 + LM 4 + TMF 4 + Spread 2 + the 2 pinned singles — only BasePlayerFragment ×3 entries + LiveFragment ×5 entries** | **17** | **1** | 0 | 43 | `45532eba` |

| 2026-08-03 | **E1p batch 28 — Tier 3c: LiveFragment (3 methods / 5 entries).** onViewCreated → contiguous phase helpers in the original order (zoom prewarm stays before the launcher); observeViewModel → 4 collector helpers (repeatOnLifecycle gating + view-lifetime paging + rethrown cancellations byte-identical); restoreInitialFocusIfNeeded → file-level `categoryPositionFor` + top-level `LiveCategoryFocusRestorer` (double-post + FocusCoordinator verbatim, latch via hook). **Two mid-batch overshoots fixed, not baselined: the split pushed LiveFragment over LargeClass-600 (643 SLOC) → the D-pad topology moved to a top-level `LiveListKeyRouting` collaborator (Hooks bundle) and the restorer went top-level → 589 SLOC, LargeClass stays pinned at 1; the combined key-routing helper itself hit CCM-15 → solved by the same extraction.** Baseline diff 5 del / 0 add; full suite green. Device QA on .64 (classic via pref flip, restored): browse with REMEMBERED category chip highlighted (new restorer live), EPG now-titles, chip-strip DOWN through the new router, preview 720p first frame, fullscreen adopt gap 35ms, BACK "ADOPTED back (no reload)". **Remaining: CCM 2 + LM 2 (all BasePlayerFragment) + TMF 4 + Spread 2 + the 2 pinned singles** | **12** | **1** | 0 | 43 | `eb849b90` |

| 2026-08-03 | **E1p batch 29 — BasePlayerFragment, the FINAL Tier-3 landmine batch; CyclomaticComplexMethod and LongMethod are now ZERO codebase-wide.** initializePlayer → `coldStartPlayer` (watchdog-arm-before-engine-build order documented load-bearing; the LP-ADOPT check + post-init wiring + catch stay byte-identical) + `attachDebugListener`; onViewCreated → bindPlayerChrome / `DebridResumeFacts` / applyLaunchChrome / loadSeriesPlaylistIfNeeded / **routeInitialPlayback (the four resume arms in their exact order — resume-play-then-repair untouched)** / registerBackHandler; refreshDirectDebridSourceFromMetadata → dispatchDirectDebridRefresh. BasePlayerFragment's LargeClass + TMF entries remain the two intentional C9 residues. Baseline diff 4 del / 0 add; full suite green. Device QA on .64, both engines: debrid CW resume (DIRECT → scoped hit → coldStart → first frame), the no-sources fallback branch live (graceful detail-page return, no crash), and an IPTV Xtream movie resume (first frame 1920x800, steady, clean exit). **Remaining 8 = TooManyFunctions 4 + SpreadOperator 2 (touch only if they fall for free) + the 2 pinned singles (LargeClass BasePlayerFragment, LPL diagnosticsSourceFields). The E1p player-path run is COMPLETE: 267 → 8.** | **8** | **1** | 0 | 43 | `1b16b72c` |

| 2026-08-04 | **Tier H batch 1 — H0 `StreamIdentity` (`e82f4583`) + H1 `StreamGrouping` (`662afe6b`); the source list collapses to one row per real file.** H0: Strong (infoHash ▸ magnet ▸ **URL-path parse** with fileIdx-normalised-to-0) / Weak (name AND 50MB-bucket, placeholders excluded) / Unique; MediaFusion gets a stable `providerName`; 11 tests. H1: `groupStreamsByFile` (primary = reliability-hook → metadataScore → direct-over-magnet), `deduplicateStreams` a thin wrapper, grouping runs BEFORE the 10-hash cache verifier, **the Stremio fetch pre-dedups removed — those copies ARE the alternates** (they were being silently eaten); `SourceAlternate`/`addonCount` defaulted onto MovieSource; provider chip "×N"; 7 tests. Device QA on .64: **"H1 grouping: 109 streams → 74 files (35 with alternates)"** live, detail summary "74 SOURCES · 74 CACHED" (every row now verdicted — was 112 rows with the budget wasted on copies), playback through the grouped list PASS (4K first frame; a mid-switch AudioTrack burst was recovered live by batch-27's `recoverAudioSinkFailure`). Ledger unchanged at 8. **Next: H2 silent same-file failover (needs these alternates) → H3 honest languages.** | **8** | **1** | 0 | 43 | `662afe6b` |

*(Append one row per landed phase. The three numeric columns may never increase.)*

**Tier C is complete at LargeClass = 1.** The remaining one is `BasePlayerFragment`, by the C9
decision above — not a backlog item. Do not "finish" Tier C by reopening it.
