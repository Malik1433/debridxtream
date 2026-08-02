# Debrid source list: collapse duplicates, make language real, learn what works

## Context

Opening a movie or episode in Debrid can list 100–500 sources. Most are not different files —
the same release is returned by several addons, so **10 real files appear as ~40 rows**. On top of
that, most rows are labelled only `MULTI`, which says "several languages" without saying which, so
a customer looking for Hindi cannot find it.

Three things make this worse than it looks:

1. **`DebridCacheVerifier.kt:37,63` only cache-verifies the first 10 hashes.** With a duplicate-heavy
   list those 10 checks are spent on 4 copies of the same 3 files, so most real files never get a
   cache verdict at all. Collapsing duplicates does not just shorten the list — it restores cache
   accuracy.
2. **The app invents languages.** `LanguageParser.kt:44,72-74` returns `listOf("en")` when the title
   parses to nothing, and `:76-79` adds `"en"` to a MULTI-only result; `MovieSourceAdapter.kt:244`
   substitutes `listOf("multi")` when languages are null. So nearly every row *claims* English, the
   "Unknown" filter option is unreachable, and a MULTI row does not match a Hindi filter
   (`SourceFilterUtils.kt:153-155`). Both directions are wrong.
3. **Language is inside the dedup key** (`SourceHelpers.kt:140-143`), so the same torrent tagged
   differently by two addons is treated as two files. The duplicate problem and the language problem
   are feeding each other.

**Outcome:** one row per real file with a "4 sources" badge; a dead link silently retried via another
addon's copy of the *same* file; languages that are either true or honestly unknown, learned from
playback and shared between users; and addons that prove unreliable quietly sinking down the order.

### Owner decisions (locked)
- Duplicates are **collapsed and counted**, not discarded — the count is a useful reliability signal
  and the alternates are the failover path.
- Addon reliability is **learned silently**. No settings screen, no manual ordering.
- Verified languages are **shared via Firestore**, so the first person to play a release teaches
  everyone. Anonymous auth is enabled and verified working.

---

## P0 — Identity primitive (no behaviour change)

One function answering "which real file is this?", with no call site wired yet.

- **New** `data/debrid/repository/StreamIdentity.kt`: `fileIdentity(stream): FileIdentity` returning
  `Strong(hash, fileIdx)` | `Weak(nameKey, sizeBucket)` | `Unique(objectId)`.
- **Strong** = `normalizeInfoHash(infoHash)` ?: from `magnet` ?: **parsed out of the proxy URL path**.
  Proxy URLs carry `.../<40-hex hash>/<fileIdx>/` — proven by `StremioAddonFetcher.kt:38`
  `NO_FILE_INDEX_REGEX = "/-1(?:/|\?|$)"`. This is the highest-leverage part of the whole plan:
  today `SourceHelpers.kt:113-117` skips dedup entirely for hashless streams, and most of them are
  not really hashless.
- The key contains **nothing else** — not languages, not delivery, not provider, not bingeGroup.
  Those were presentation, never identity.
- **Weak** (only when no hash exists anywhere): normalised release name **and** `sizeBytes` rounded
  to 50 MB, and only when `sizeBytes > 0` and the title is not a placeholder (`"Stremio Stream"`
  `StremioAddonFetcher.kt:199-204`, `"Unknown"` `DynamicAddonFetcher.kt:228`). Otherwise `Unique`.
- Also fix `data/debrid/source/MediaFusionFetcher.kt` to set `extras["providerName"]` — it is the
  only fetcher that doesn't, which makes per-addon keys unstable for P6.

**Why two agreeing signals for Weak:** collapsing two *different* files is far worse than showing two
rows — the customer picks a row, gets the wrong film, and P2 then retries the wrong file twice. The
asymmetry justifies refusing to guess.

**Verify:** `app/src/test/.../StreamIdentityTest.kt` — same torrent from 4 addons → 1 identity;
magnet vs direct URL of one hash → 1; two different-sized 1080p releases → 2; placeholder-titled
sizeless streams → all distinct.

---

## P1 — Collapse into groups, keep the alternates *(do this first)*

- **New** `data/debrid/repository/StreamGrouping.kt`:
  `StreamGroup(identity, primary: AddonStream, alternates: List<AddonStream>)`.
- `SourceHelpers.kt:106` `deduplicateStreams` becomes a thin wrapper over `groupStreamsByFile()`;
  delete `buildStreamVariantKey:140`. Keep the existing signature — `DebridSourceOrchestrator.kt:105`
  calls it on a second, earlier path.
- `data/repository/SourceModels.kt:25` `MovieSource` gains **defaulted** fields so no call site
  churns: `alternates: List<SourceAlternate> = emptyList()` and `addonCount` = `alternates.size + 1`.
  `SourceAlternate` holds provider/sourceType/sourceName/directSource/headers/cacheStatus/identity —
  deliberately **not** a nested `MovieSource` (recursive data class breaks DiffUtil equality and
  doubles list memory).
- Wire at `DebridSourceOrchestrator.kt:156` and `:359`; map in `MovieSourceConversion.kt`.
- `ui/vod/MovieSourceAdapter.kt`: badge showing `addonCount` when > 1.

**Sequencing that matters:** grouping must run **before**
`cacheVerifier.verifyRealDebridCacheStatuses` (`DebridSourceOrchestrator.kt:209`, `:378`) so the
10-hash budget is spent on 10 distinct files.

**Primary within a group:** reliability score (neutral until P6) → existing `metadataScore`
(`SourceHelpers.kt:204`) → direct URL over magnet. That last tiebreak preserves the real distinction
the current code documents at `SourceHelpers.kt:144-147` (direct streams immediately, magnet goes
through the RD chain) without spending a row on it.

**Risk to check:** stream ids carry a `_<index>` suffix (`MovieSourceConversion.kt:94-98`) that
shifts when the list shrinks — confirm `initialSelectedStreamId` / `consumeDebridReturnFocusStreamIds`
still resolve, so D-pad focus does not jump on refresh.

**Do not touch:** `ui/compose/components/SourceListSection.kt` — defined, never called, dead.

**Verify:** unit test over a captured multi-addon fixture (row count drops, no two groups share an
identity); on device, badge counts look plausible and the existing dedup log line at
`SourceHelpers.kt:135` shows the new ratio.

---

## P2 — Silent same-file failover

A dead link should retry the **same file** from another addon before giving up on that file.

- **New** `ui/sources/SourceAlternates.kt` — shared by movies and series.
  `next(source, triedIdx): MovieSource?` returns a copy with directSource/headers/provider swapped
  and the **same** `stream_id`.
- `ui/vod/MovieDebridPlaybackController.kt:86-117`: stop adding `failedStreamId` to
  `failedDebridStreamIds` at `:97` until alternates are exhausted — today one failure blacklists the
  whole file. Mirror in `ui/series/SeriesDebridPlaybackController.kt`.
- Alternates do **not** consume `MAX_AUTO_FALLBACK_ATTEMPTS = 3` (that counts *different files*);
  add `MAX_ALTERNATE_ATTEMPTS = 2`.
- Alternate retries are **silent** — suppress the "Trying next source…" toast for them.

**Verify:** force a 403 on a primary link; the second addon's URL is tried with no toast and the row
stays selected; a genuinely dead file still advances after 2 alternates.

---

## P3 — Stop inventing languages

- `LanguageParser.kt:44,72-74` return `emptyList()` instead of `listOf("en")`; drop the `"en"`
  injection at `:76-79`.
- `MovieSourceAdapter.kt:244`: empty → one neutral `🌐 ?` chip, contentDescription "Language unknown".
  Not `UNK` (reads as a language), not `MULTI` (a lie).
- `SourceFilterUtils.kt:153`: **MULTI must now match any specific language filter** unless verified
  languages (P4) contradict it. Without this, removing the fake `"en"` would make the "English"
  filter *hide* rows it used to show — the list would get worse, not better.
- Scoring, applied in **both** `SourceSorter.score` and `SourceFilterUtils.apply`'s `langScores` —
  the sheet sorts twice (`SourceSelectionBottomSheet.kt:234` then `applyFilters`) and they will
  otherwise fight: verified match 100 > claimed match 80 > MULTI-claimed 60.
- Fix `player/stabilized/PlayerDebridCoordinator.kt:70-88` passing no `preferredAudioLang` (defaults
  `"ALL"`), so the in-player picker orders like the detail-screen one.

**Verify:** extend `SourceSorterTest` and `SourceFilterUtilsCacheSemanticsTest`; manually confirm a
MULTI-heavy title still appears under "Hindi".

---

## P4 — Learn real audio languages (local)

- `player/stabilized/PlayerDiagnosticsFields.kt:80-100` today records only *selected* languages —
  add `availableAudioLanguages(tracks)` for all supported audio groups
  (`Tracks.Group.getTrackFormat(i).language`, lowercase 2-letter, drop null/`und`).
- Capture in `player/stabilized/PlayerEventListener.kt:260` `onTracksChanged`, guarded: debrid
  playback only, non-empty audio groups, **once per session** (it fires repeatedly). Identity =
  `debridInfoHashExtra ?: debridStreamIdExtra` with the `_<idx>` suffix stripped.
- **New** `data/debrid/language/ReleaseLanguageRepository.kt` + entity + DAO.
  Table `release_languages(hash PK, languages, origin, updatedAt)`.
- `data/local/AppDatabase.kt` → **version 15**, `MIGRATION_14_15` in `DatabaseMigrations.kt`
  registered in `getAllMigrations():171-180`. Additive table only, never destructive, plus a
  migration test (project rule).
- Merge at conversion with `languageOrigin = VERIFIED | CLAIMED`; verified chips get a check-mark —
  the visual cue that makes learned data worth trusting.
- All DB work on `Dispatchers.IO`; a lookup miss leaves claimed languages untouched.

**Verify:** migration test 14→15; play a known dual-audio release, reopen the picker, chips read
HI · EN as verified.

---

## P5 — Share verified languages via Firestore

- Collection `release_languages/{hash}`, doc id = lowercase 40-hex infoHash. Opaque; no title, no
  user, nothing personal, nothing in a URL.
- Shape `{ langs: [...], votes: int, updatedAt: serverTimestamp, appVersionCode: int }`.
- Write: `set(merge)` with `arrayUnion` + `increment` — conflict-free and idempotent. **Skipped
  entirely when the local row already holds the same set**, which removes almost all repeat writes.
  Fire-and-forget, nothing awaits it, failures logged at info — the same posture as
  `data/licensing/DeviceIdentity.kt:70-83`.
- Read: batched `whereIn(FieldPath.documentId(), …)` (10 ids max per query), only for the top ~30
  rows, once per list, inside `withTimeoutOrNull(2500)`. A timeout leaves the local cache intact.
- Rules in `admin-panel/firestore.rules`: public read (non-personal, like `app_config`); write
  requires sign-in, a 40-hex doc id, `hasOnly(['langs','votes','updatedAt','appVersionCode'])` and
  `langs.size() <= 12`; delete admin-only.
- **Accepted trade-off:** any signed-in device can pollute a release's list. Bounded by the array cap,
  and this device's own observed languages always outrank the shared set.

**Verify:** rules test; two devices — the second sees verified chips before ever playing.

---

## P6 — Silent per-addon reliability

- **New** `data/debrid/repository/AddonReliabilityStore.kt`, backed by SharedPreferences (per-device,
  disposable, no migration — Room is for data worth migrating).
- Key: `extras["stremioAddonId"]` ?: `extras["providerName"]` ?: `source.name` (P0 made MediaFusion
  stable).
- Record offered / first-frame-success / resolve-failure. **First rendered frame
  (`PlayerEventListener.kt:240`) is the only honest success signal** — a resolved URL proves nothing.
- Score: Laplace `(success+1)/(success+fail+2)`, **neutral 0.5 until ≥5 observations** so one unlucky
  play cannot bury an addon; short cool-off after a failure burst.
- Feeds ordering only as a **tiebreak within the family** in
  `DebridSourceOrchestrator.kt:457-470` — the hardcoded family priority
  (`SourceHelpers.kt:183-192`) stays, so a bad night cannot invert the whole list.

**Verify:** unit tests on the scorer; manually fail one addon repeatedly and watch its rows sink
within its family.

---

## Order and why

**P1 first** — the only phase visible immediately (shorter list, "4 sources"), and it pays for itself
by freeing the 10-hash cache budget. P2 needs P1's alternates. **P3 must follow P1**, because
language currently sits *inside* the dedup key — changing language output first would reshuffle
grouping. P4 before P5 proves the pipeline with zero server blast radius. P6 last: invisible until it
has data, and P1 already consumes it through a neutral-default interface.

One phase per commit; P0 is behaviour-preserving and stays its own commit.

## Constraints that shape the file layout

`ui/vod/MovieDebridSourceController.kt` is **613 lines** and `MovieDebridPlaybackController.kt` is
**556** — at/over the 600 ceiling under a ratchet currently at TOTAL=175 that may only be lowered.
**All new logic lands in new files**: `StreamIdentity.kt`, `StreamGrouping.kt`, `SourceAlternates.kt`,
`ReleaseLanguageRepository.kt`, `AddonReliabilityStore.kt`.

Every network call bounded with `withTimeoutOrNull`; `CancellationException` always rethrown; no
`GlobalScope`; nothing heavy on the main thread; no failed fetch ever overwrites cached languages.

## Verification (end to end)

1. `./gradlew :app:detekt debtRatchet :app:testDebugUnitTest :app:assembleDebug` green after every phase.
2. Room migration test 14→15 (P4) plus the existing migration-chain test.
3. On `.64`: open a duplicate-heavy title — row count drops sharply, badges show counts, and more
   rows now carry a real cache verdict (the visible proof the 10-hash budget was being wasted).
4. Force a link failure — the same file retries silently via another addon before moving on.
5. Play a dual-audio release, reopen the picker — verified language chips.
6. Second device sees those chips without playing (P5).
