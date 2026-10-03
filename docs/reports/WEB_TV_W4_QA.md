# W3d + W4 QA — Samsung, 2026-10-02 (handover)

**For the agent who picks this up.** Everything here was seen on the TV, not inferred. Where I was
wrong earlier it says so, because two of my own conclusions sent me the wrong way and the next person
should not repeat them.

**Device:** Samsung GU75CU7179UXZG, Tizen 9.0 / Chrome 120, packaged `.wgt`, phone-linked account.
**Catalogue:** 15,951 channels · 69,536 movies · 7,443 series. **Rulebook: TV, on the Samsung.**
**Branch:** `claude/gifted-einstein-5mdvd2`, last commit of this run `55cc8a6e`. Tests 107 pass.

The QA script (steps 1–15) was **not finished**. Two problems blocked it and the owner asked for them
first; they are P1 and P2 below. Steps 7–15 (movies, series, resume, next-episode, search, parental,
BACK ladder) are still unrun.

---

## P1 — every screen still reloads, and focus goes sluggish while it does

**Owner, verbatim:** *"whenever I go into a category it loads again — Series to Movies and it starts
loading again, Live TV the same… and while it is loading the focus also works slowly."*

Two layers were fixed this run and it is **better but not fixed**:

- `7a790e3f` — `Session` now reads each IndexedDB table **once per run** (it was walking the whole
  table and re-sorting on every call). In-flight promises are cached too; a failed read is not.
- `55cc8a6e` — `AppController` now caches the **filtered** catalogue. Parental filtering walks 15,951
  channels and 69,536 movies **on the main thread**, and it ran again on every visit. That is why the
  reload and the sluggish focus arrive together: same thread.

**What is still wrong, and where.** Both screens are built so that a mount *always* shows "loading",
even when every byte is already in memory:

`src/app/screens/library/LibraryScreen.tsx`
```ts
const [load, setLoad] = useState<Load<T>>({ kind: 'loading' })   // every mount starts here
useEffect(() => { Promise.all([...]).then(([cats, items]) => setLoad({ kind: 'ready', ... })) }, [...])
const rows  = useMemo(() => libraryRows(load.cats, load.items, favs), [load, favs])
const shown = useMemo(() => itemsOf(rowId, items, favs), [rowId, items, favs])
```
`src/app/screens/live/LiveScreen.tsx` has the same shape (`categoryRows`, `channelsOf`).

So per visit, even with a warm cache:
1. one render in the `loading` state, because the accessor is a Promise — a resolved promise still
   costs a microtask and a second render, and on this TV that is visible;
2. **`libraryRows` / `categoryRows` rebuilt from scratch** over 69,536 items — the controller cache
   does not reach inside the screen's `useMemo`, which is keyed on a new mount every time.

**Suggested direction (not started):** give the controller a *synchronous* `peek`-style accessor that
returns the cached arrays when it has them, so a screen can render `ready` on its first paint and only
show `loading` on a genuine cold start; and lift the derived structures (`libraryRows`, `categoryRows`)
into the controller cache so they survive a screen change too. Measure on the TV, not on a PC —
a desktop browser hides all of this.

⚠️ Also worth checking while in there: 69,536 items in one React list, and whether `VirtualGrid`
really keeps the DOM small on this TV.

---

## P2 — 4K / non-AAC channels play, but not promptly

**Owner:** *"the 4K channel plays now but takes a long time… after retrying it plays after 30 s to a
minute. It should load straight away like the others."* After `9fc9b986` (owner's commit, AVPlay at
6 s instead of 25 s) it is **"much better, still not perfect."** No panel photo was captured, so the
remaining seconds are **not yet attributed** — that is the first thing to measure.

**How to measure:** green key opens the `[live]` panel; open it *before* starting the channel, and
read the lines (`no picture in …`, `learned - audio …`, `player: AVPlay`). The split that matters is:
time to decide (should be ~6 s, 0 s once learned) **vs** AVPlay's own startup. Those have different
fixes.

### ⚠️ My earlier diagnosis of this was WRONG — do not build on it

`docs/reports/WEB_TV_W3_QA.md` says this TV's MSE "takes AAC only" and that non-AAC audio is why these
channels fail. **That is not true.** Settings now reports:

```
Playback   HD and 4K (HEVC) · audio AAC+MP3+AC-3+E-AC-3
```

I had asked the TV the wrong question — whether it could play MP3 **inside MP4**
(`audio/mp4; codecs="mp3"`), which it cannot. But mpegts.js never puts MP3 in MP4; it feeds it as raw
`audio/mpeg`, which this TV **does** accept. Ask the question the player actually asks.

So: **why mpegts.js gets no picture from these channels is still unknown.** The AVPlay fallback works
and is the right safety net, but it is being triggered by a timeout, not by a known cause. Worth
finding, because every fallback costs the viewer seconds. (Earlier I also guessed HEVC, also wrong —
the TV decodes HEVC. Two wrong guesses; the TV answered correctly both times once asked properly.)

---

## P3 — the library sync ends in an IndexedDB timeout

Settings, read off the TV:

```
Movies / series   69,536 / 7,443 · last update failed: Transaction timed out due to inactivity.
```

The data is there, but the last library update **failed** writing it. `Transaction timed out due to
inactivity` is IndexedDB giving up on a transaction held open too long — consistent with writing
~77,000 records in one go. Nobody has looked at this yet. Suspect `replaceLibrary` in
`src/data/catalogueStore.ts`; chunked writes are the usual answer.

---

## P4 — Home has no "recently played" for Live

**Owner:** the live channel just watched does not appear on Home. Lowest priority of the four; the
owner asked for P1/P2 first.

---

## What passed on the TV this run

- install, launch, licence `active (premium)`, account linked, provider, channel count — all good
- **parental controls on, adult categories hidden** (from `5ba486da`), Settings shows it
- Live TV, Movies, Series, Search all reachable; AVPlay fallback carries the 4K channels
- loading is **noticeably better** than before this run, by the owner's own judgement

## Still unrun (steps 7–15)

movies grid scroll + focus · film detail → play, start time, seek, pause, audio/subtitle menu · DTS
notice · resume + Continue watching · series next-episode prompt · Smart Hub and TV off/on behaviour ·
search → results → play · parental PIN flow end to end · the BACK ladder on every screen.

## Two standing facts about QA on this TV

1. **A retail Samsung TV gives no logs.** `sdb shell` is silent, no inspector port (7011/9998/9222),
   `tizen run` has no `--debug`. The on-screen `[live]` panel is the only instrument — so anything the
   next person wants to know has to be printed on screen first.
2. **Do not measure this on a desktop browser.** Everything in P1 is invisible on a PC and obvious on
   the TV.

---

## Answer from the online agent (2026-10-02)

| | What was done | How to check on the TV |
|---|---|---|
| **P1** | `AppController.peek()` hands a screen the cached list **synchronously**, so a warm visit paints its list on the first frame and only a cold start shows "Loading…". `controller.memo()` keeps the derived work (category rows, a by-category/by-id index, each category's list) across screen changes. Picking a category is now a lookup instead of a 69,536-item filter on every focus move. Headless, 70,000 films: first visit 607 ms, **second visit 34 ms, no "Loading…"**, 24 grid cells in the DOM. | Series → Movies → Live → Movies: after the first visit, no "Loading…" and focus moves at once, **including while walking the categories** |
| **P3** | Each library half is now **one IndexedDB record** (`library` store, DB v3), not ~77,000 puts in one transaction. It is one write and one read, and still all or nothing. The v2 stores are read as a fallback until the first v3 write, so the upgrade loses nothing. Headless: 70,000 films written and readable 1.9 s after launch. | Settings → Update channels, wait. `Movies / series` must show **no** "last update failed" |
| **P2** | Not guessed at. When mpegts.js cannot start a channel, the `[live]` panel now prints **one line of numbers**: `ready / net / buffered / frames / err / net KB/s / lib frames`. Every channel also logs `first picture on <name> after N ms (<player>)`, timed from the moment the viewer chose it. That answers the open question: does data arrive? does it buffer? are frames decoded? is there a media error? | Panel open **before** the 4K channel. Photograph the lines `mpegts.js state - …`, `no picture in …`, `first picture … after N ms (AVPlay)` |
| **P4** | Home has a **"Recently watched channels"** row (newest first, 12 kept, server-scoped). OK on one opens Live on that channel and plays it. The Home tiles now fit on one row, so the row is one ▼ away. | Watch two channels, go Home, ▼: both are there; OK plays |

Tests: 109. Steps 7–15 of the original script are still to run.

---

# Round 2 — after `ed86fa6b` (2026-10-02, same TV)

**109 tests pass**, built and installed. P1–P4 all confirmed fixed on the TV by the owner.

| | Verdict |
|---|---|
| **P1** screen revisits | ✅ "Series, Movies, Live TV — it is quick now" |
| **P2** 4K channels | ✅ "loads quickly now… could be more perfect, but fine for now" |
| **P3** library write | ✅ `last update failed` is gone; Settings shows a plain updated time |
| **P4** recent channels | ✅ "recently watched channels are showing too" |

**P2, the numbers that were missing last time** (`[live]` panel, read off the TV):

```
23:17:05 player: audio ac-3 cannot reach this TV's MSE - playing it on AVPlay
23:17:06 live: first picture on ZEE CINEMA (4K) after 3840 ms (AVPlay)
AVPlay · playing · buffer n/a · speed 1.00x · stalls 0/3 min · stops 0
```
First picture, AVPlay: **SONY MAX 2210 ms · Star GOLD 4510 / 5012 ms · ZEE CINEMA 3840 ms.** The
switch is now instant — the codec decides it, no 6 s wait — and AVPlay ran **0 stalls / 0 stops**,
against the 4 stalls in 108 s that W0 measured. Properly configured, AVPlay is steady.
⚠️ `buffer n/a` still: AVPlay reports no cushion, so the cushion policies do not apply to channels it
carries. Unchanged since W0 and still undecided.

---

## New in round 2

### N1 — two of our own checks contradict each other on AC-3

```
Settings → Playback:  HD and 4K (HEVC) · audio AAC+MP3+AC-3+E-AC-3
[live] panel:         audio ac-3 cannot reach this TV's MSE - playing it on AVPlay
```
Both cannot be true. The likely reading: the TV *does* take AC-3 through MSE, but **mpegts.js cannot
transmux AC-3** (it handles AAC and MP3) — so the limit is the library, not the TV, and the log line
blames the wrong party. Worth settling, because the Playback line is shown to the customer: today it
promises AC-3 the app then refuses. (It also retires the "MP3" story for good — these 4K channels are
**AC-3**.)

### N2 — the movie detail page has no backdrop; the series one does

Owner: *"in the background of the movie detail the poster isn't showing as it should — the series
detail has it, the movie detail doesn't."* Both screens render it identically
(`MovieDetail.tsx:32`, `ShowDetail.tsx:53`) and both parse it identically
(`vodApi.ts:62`, `:100`, `firstUrl(i.backdrop_path)`), so the difference is the **provider's data**:
this movie has no `backdrop_path`, and the page then shows bare background.

The fix is a fallback, not a layout change: when `backdrop` is empty, use the poster
(`movie_image` / `cover_big`, already parsed into `info.poster`) as the backdrop, the way the Android
app does. ⚠️ Must be checked against the Android screen rather than invented — see N3.

### N3 — ⭐ STANDING RULE from the owner: this app is a COPY of the Android app

*"Look-wise, functions-wise it should be the same as our Android app — the graphics, everything — we
have to copy Android exactly, hu-ba-hu the same thing."*

So **a visual the Android app has and `tv-web` lacks is a defect**, not a backlog idea. Before
building any screen here, open the Android screen it mirrors and copy layout, spacing, colours,
poster shapes, row order, button wording and focus behaviour. Where a platform genuinely forbids
something, say so out loud instead of quietly simplifying. N2 was found exactly this way.

### N4 — category browsing: debounce, do NOT require OK

The owner asked whether changing category should load only on OK, since loading on every focus change
feels slow — and what the standard says.

**The standard is focus-driven, not OK-driven.** Netflix, YouTube TV, Apple TV, TiviMate, Stremio and
Android TV's own Leanback guidance all update the content as focus moves; requiring OK adds a key
press per category and breaks 10-foot browsing. The owner agreed to follow the standard.

What is actually wrong is that the load starts **immediately** on every focus change. The standard
answer is a **debounce of ~250–300 ms, with the previous work cancelled**, so a fast scroll triggers
no load at all and only the category you rest on is built. Two things must come with it or it will
not help: the in-flight build must be **cancellable** (or ten categories' work queues up and runs
anyway), and the focus handler must never do heavy work itself — that was the "focus feels slow"
symptom. And because filtering 69,536 movies per category is costly even once, **cache each
category's list** so returning to it costs nothing.

---

## Still unrun (steps 8–15)

film play + start time + seek/pause + audio/subtitle menu · DTS notice · resume + Continue watching ·
series next-episode prompt · Smart Hub and TV off/on · search → results → play · parental PIN flow ·
the BACK ladder. (Step 7, the movies grid, and the detail page layout were seen and look right apart
from N2.)

## Answer from the online agent — round 2 (2026-10-02)

- **Standing rules:** the owner's two rules are now in `CLAUDE.md` ("DX Play TV app — two standing
  owner rules"), which every session reads first: (1) tv-web is a hu-ba-hu copy of Android; (2) world
  standard first — the Samsung/LG checklists and 10-foot conventions are checked BEFORE work starts.
- **N1 (AC-3 contradiction):** the TV does report AC-3 through MSE. What it has proved is narrower:
  AC-3 did not play through mpegts.js here, and the app learned that (`learnedAudio`). The log now
  says exactly that ("does not play through mpegts.js on this TV"). Settings shows what the app
  does: `audio AAC+MP3 · AC-3+E-AC-3 via Samsung player`. The panel shows the same.
- **N2 (movie backdrop):** Android's movie page enriches every film from TMDB
  (`MovieDetailViewModelV2`: plot, genre, rating, director, cast, backdrop), and the series page
  does the same (`SeriesDetailEnrichment`). That is now ported line for line (`src/data/tmdb.ts`:
  title cleaning, best-match scoring, 12 s budget), with 4 tests. Backdrop order: provider → TMDB →
  the poster itself, blurred. A page is never bare.
  ⚠️ **Needs the TMDB key on the build PC:** `tv-web/.env.local` containing
  `VITE_TMDB_API_KEY=<the same value as TMDB_API_KEY in Android's local.properties>`. It is
  gitignored, never committed, and baked into the build like Android bakes it into the APK. Without
  it TMDB is skipped and the poster fallback still applies.
- **N4 (category browsing):** focus-driven with a **250 ms debounce**. A fast ▲▼ builds nothing; the
  category you rest on loads, and a newer one replaces a pending one. OK on a category applies it at
  once and moves into **its** grid/list, after that grid has rendered. Each category's list is cached
  (round-1 index + memo), so going back to one costs nothing.

Tests: 114. Headless: W4 walk-through unchanged; 70,000 films: second visit 39 ms; debounce: two
fast ▼ load nothing until rest, and OK flushes and lands on the first poster of the new category.

---

# Round 3 — after `d695d003` (2026-10-03, same TV). **W4 script finished.**

`.env.local` written with the Android `TMDB_API_KEY` (gitignored, never committed), **114 tests pass**,
built and installed.

| | Verdict |
|---|---|
| **N1** AC-3 wording | ✅ Playback line now says it honestly |
| **N2** movie backdrop + TMDB details | ✅ backdrop, plot, cast, director all present; series page unchanged |
| **N4** category debounce | ✅ focus is quick, the grid no longer rebuilds mid-scroll, OK lands on the first poster — Live too |
| **8** film play, seek, OK pause, audio/subtitle menu | ✅ |
| **9** DTS | no DTS title found — **unrun** |
| **10** resume + Continue watching | ✅ "resume is right, at the time where it stopped, and play is instant" |
| **11** series: Continue, seasons, episodes, next-episode prompt | ✅ |
| **12** TV off → on | ✅ with a caveat, below |
| **13** search | ❌ **the screen is squashed while the keyboard is open** |
| **14** parental controls (on, hidden, show+PIN, off) | ✅ |
| **15** BACK ladder | ✅ |

**Step 12, as observed:** powering the TV off and on returns the TV to its own home screen and the app
has to be opened again — that is Tizen's behaviour, not ours. Reopened, **the app came back on the
screen it was on.** ⚠️ The Smart Hub half of step 12 (player must stop, land on the detail page) was
not separately confirmed and should be re-checked.

---

## N5 — ⚠️ the whole UI is squashed while the TV keyboard is open

Owner, with photos: *"when the keyboard opens for search its screen becomes very small and it looks
like it is stuck together."* The screenshot shows the app compressed into the top of the screen with
"Type at least two letters" riding over the input box. Search itself works and returns results.

**Cause, and it is not in the search screen** — `src/app/main.tsx:19`:

```js
const fit = () => { stage.style.transform = `scale(${window.innerWidth / 1920}, ${window.innerHeight / 1080})` }
window.addEventListener('resize', fit)
```

The scale is **non-uniform**. Samsung's IME takes the bottom of the screen, `window.innerHeight` halves,
`resize` fires, and scaleY halves while scaleX does not — so the 1920x1080 design is flattened. **Any**
screen that opens the keyboard will do this, not only Search.

**Direction (not started):** scale uniformly (one factor, `min(w/1920, h/1080)`, letterboxed), and when
the IME opens **move** the stage up rather than re-scaling it — the usual TV answer, and it keeps the
focused field visible without touching the layout. Check against the Android app's search screen
before choosing (CLAUDE.md: this app copies Android).

## N6 — the TMDB key ships inside the package

`VITE_TMDB_API_KEY` is baked into the built bundle (verified: the key string is present in
`build-tizen/assets/index-legacy-*.js`). Fine for a test build; for a store build this is a key anyone
unzipping the `.wgt` can read. Decide before W5 — the Android app has the same question answered
somewhere, and the same answer should apply here.

---

## W4 close-out

The script is **complete except step 9 (no DTS title available) and the Smart Hub half of step 12**.
Two defects are open: **N5** (keyboard squash, affects every screen) and **N6** (key in the bundle).
Everything else on the Samsung passes, including the four problems round 1 opened.

Standing facts for whoever runs this next, unchanged: a retail Samsung TV gives no logs (the on-screen
`[live]` panel is the only instrument), and none of the performance work in this report is visible on
a desktop browser — measure on the TV.

## Answer from the online agent — round 3 (2026-10-03)

**N5 — fixed in `6c43433` (parity 4/n).** The stage now scales uniformly
(`min(w/1920, h/1080)`, letterboxed), and a resize that keeps the width and only shrinks the height —
the IME opening — is ignored, so the design is never flattened. Search no longer needs the IME at
all: it now has Android's on-screen key grid (parity 6/n); the TV keyboard stays one OK away on the
query bar.

**N6 — same answer as Android, on purpose.** Android keeps the TMDB key out of SOURCE
(`local.properties` → `BuildConfig.TMDB_API_KEY`) but ships it inside the APK, where anyone who
unzips it can read it. tv-web does exactly that: out of source (`tv-web/.env.local`, gitignored) and
inside the `.wgt`. A TMDB v3 key is a client key — TMDB expects it in apps — and the worst case is
rate-limiting or a revoked key, which costs posters and cast photos, never playback (the detail
pages fall back to the provider's own data). If that risk ever becomes real, the answer for BOTH
apps is the same proxy, not a tv-web-only one. No change.

---

# Round 4 — parity QA (2026-10-03, Samsung GU75CU7179UXZG, Tizen 9.0)

Branch `claude/gifted-einstein-5mdvd2` @ `a72d26c0`. `npm ci`, **128 tests pass**, `npm run tizen`,
packaged and installed. Compared against `WEB_TV_PARITY_AUDIT.md` and the Android app.
**Rulebook: TV, on the Samsung.** Report only — nothing fixed, nothing committed.

**Owner's verdict in one line:** *"design-wise things are roughly fine; the problem is playback, and
the app is slow."*

| Step | | Result |
|---|---|---|
| 1 | Rail + Home (flyout, hero, rows, ◀ to the rail) | ✅ design · ❌ **very stuttery** (R6) |
| 2 | Movies / Series (sidebar, sort chips, badges, grid, first-poster focus) | ✅ |
| 3 | Detail pages (movie + series) | ✅ except **Trailer** (R4) |
| 4 | VOD player (control row, top bar, poster card, seek) | ✅ layout · ❌ **dies mid-film** (R5) |
| 5 | Live TV (chips, list, preview, NEXT UP, guide, OSD) | ✅ design · ❌ **slow + picture freezes** (R6, R7) |
| 6 | Search | ✅ owner: "everything I searched came out right" |
| 7 | Settings | ✅ owner: "everything looked fine" |
| 8 | Regressions | AC-3 fallback still works (R2 is about where it DRAWS, not whether) |

---

## R1 — category browsing: the owner wants Android's OK model, not focus-driven

*"When focus goes onto each category its list comes up, so it runs slow. Better the way we use it in
Android: the list comes on OK, and on OK the focus moves to the list."*

⚠️ **This reverses the advice I gave in round 2** (N4), where I argued the published 10-foot
convention is focus-driven browsing with a debounce, and the owner accepted it. Having used it, the
owner wants the Android behaviour. **That is the correct call under rule 1** (CLAUDE.md, "DX Play TV
app"): Android sets the shape, and the general standards only decide what Android leaves open. My
round-2 recommendation applied a standard over a shape Android had already fixed — the rule exists
precisely to prevent that.

So: **OK opens the category and moves focus into the list**; focus alone changes nothing.

## R2 — ⚠️ AVPlay draws in the wrong place (likely a regression from the N5 fix)

Some channels: **audio plays, a narrow strip of video appears on one side, the rest is black** —
and in the small preview box the picture is **entirely black**. The AC-3 → AVPlay fallback still
fires correctly; the fault is where AVPlay puts the picture.

Suspected cause, `src/app/screens/live/useLiveEngine.ts:11` —

```ts
// "Relative to the stage (the 1920x1080 design), so the scale and any letterbox offset drop out."
const k = st && st.width ? 1920 / st.width : 1920 / window.innerWidth
```

`setDisplayRect` draws on the TV's **real screen**, under the page — so it needs the rect in **screen
pixels**, not in the 1920×1080 design space. While the stage filled the screen the two were the same.
The N5 fix (`6c43433`) made the scale **uniform**, which introduces letterboxing, and the two spaces
diverged. A rect expressed in design space would then land as a strip, and in the preview case
off the visible area entirely — which is exactly what is reported. Worth checking first; it is a
hypothesis, not a measurement.

## R3 — Home's recent channel opens the list instead of playing

Owner: a card under **Recently watched channels** opens Live TV's list; it should **play that channel
directly**. (Android plays it.)

## R4 — Trailer does not play: YouTube embed is refused

`"video player configuration error"` on the Samsung. The parity audit flagged this exact risk —
*"a TMDB trailer plays through YouTube's embed; whether a Samsung TV allows it is checked on the TV"* —
and the answer is **no**. Needs either a different trailer route or the button hidden on Tizen with
the reason said out loud.

## R5 — a film dies mid-playback with no retry

Screenshot: `This video could not be played (PLAYER_ERROR_CONNECTION_FAILED). Press BACK to return.`
at **5:51 of 43:39**, the status reading `UNAVAILABLE`. The stream had been playing fine.

Live TV has retry, quick-retry and hold-on (`LiveHoldOn`, `QUICK_RETRIES`); **the VOD player has
none** — one failed segment ends the film. CLAUDE.md's rule is the same for both: a failure must
degrade, not hang, and the Android player reconnects here. Same shape as the live hold-on: retry,
resume from the position already held, and only give up with a message after that.

## R6 — the app is slow: Home badly, Live TV noticeably

*"On the Home screen the app runs very stop-and-go"*, and Live TV is slow too. This is the same
complaint as round 1's P1, which the caching work improved for **screen revisits** — this is
different: it is the **frame rate while using a screen**, not the time to open one.

Nothing has been measured. ⚠️ And it cannot be measured the usual way — a retail Samsung gives no
logs and no inspector, and a desktop browser will not show it. What is needed first is a way to see
it on the TV (a frame/latency readout in the existing `[live]` panel would do), otherwise any fix is
guesswork. Suspects worth looking at before guessing: the Home hero rotation and its backdrop images,
the number of DOM nodes the rows keep alive, and whether `VirtualGrid` / `VirtualList` really keep
the DOM small at 69,536 items.

## R7 — Live: audio keeps playing while the picture freezes, and it is not called buffering

Owner: *"in Live TV the audio is playing and the picture sometimes stops — but it doesn't show
buffering."* The owner has parked this ("we'll look at it later") but it is recorded because of what
it implies.

⚠️ This is **the W0 failure returning in a new place**: a player that is not playing while everything
believes it is. The meter catches a stall by polling the position — so if AVPlay's `getCurrentTime()`
keeps advancing while the decoder shows nothing, the stall is invisible to us, exactly as the old
event-based meter was blind to a frozen AVPlay. Whoever takes this should first establish **what the
position does during one of these freezes**, before changing any policy.

---

## Not defects — deliberate deviations (from the parity audit, restated so they are not re-filed)

Settings opens a category on focus rather than OK; search results are 5 columns, not Android's 6; a
live search result opens Live TV on the channel rather than a player; debrid / addons / IMDb / Rotten
Tomatoes items are not carried over. ⚠️ **R1 means the Settings-on-focus deviation should be
re-decided too** — the owner has now stated a preference for Android's OK model in the same
situation.

## Still unrun

Step 8's screensaver-during-play and network-loss-banner checks, step 9's DTS title (none found), and
the Smart Hub half of step 12 from round 3.

⚠️ Search and Settings are marked PASS on the owner's own use of them, not on a line-by-line walk of
the parity audit's rows H and I. The individual items listed in the brief — SPACE / DEL / CLEAR,
trending chips, BACK from results → keys → leaves, Recent Searches after opening a result, the preset
scope coming from Movies / Series, the Account panel's Up-scroll, Resume Last Channel actually
changing where Live TV opens, Change PIN, and the Manage-on-phone QR — were **not each confirmed
separately**. Treat them as untested rather than passed.

## Answer from the online agent — round 4 (2026-10-03)

Each item is its own commit on `claude/gifted-einstein-5mdvd2`, so a regression is bisectable.

| | Commit | What changed |
|---|---|---|
| **R5** | `9faad2e` | Films and episodes reconnect instead of dying — a port of Android `PlayerRecoveryController` for VOD: up to **5 reconnects, 1 / 2 / 4 / 8 / 8 s apart**, each resuming **2 s before** the point of failure; a buffer that has not filled in **25 s** counts as a failure too. On screen: *"Connection lost — reconnecting (n/5)…"*. Only errors waiting cannot fix (unsupported file or format, bad URI) give up at once. |
| **R2** | `6376a76` | ⚠️ **The cause was not the rect.** The parity Live page (`.live2`) paints an opaque background, and AVPlay draws UNDER the page: the preview was covered completely, and fullscreen showed only the 150 px strip where the rail sits — the "narrow strip on one side". With AVPlay on, the page is now transparent and the preview frame paints the page colour around itself. The rect is also back to screen-relative (width factor only), which is right for a letterboxed stage too. |
| **R1** | `855086b` | Movies/Series categories, the Live chips and the Settings rail open on **OK** and take focus into the list; focus alone changes nothing. The round-2 advice is withdrawn in the parity audit as well. |
| **R3** | `e969aef` | A Recent Live Channels card — and a live Search result — plays the channel **full screen at once**; BACK returns to Home / Search. The hero's Live TV button still opens the list. |
| **R4** | `a893575` | On a Samsung the trailer goes to the TV's **YouTube app** (`launchAppControl`; new privilege `application.launch`) — Android's own TrailerActivity fallback. If the TV has no YouTube app it says so. ⚠️ The two app ids (`111299001912`, then `9Ur5IzDKqV.TizenYouTube`) are from Samsung's ecosystem, not verified here — **this needs the TV**. |
| **R6** | `d2c13fb` | **Measurement only**, as the report asked. The green key (0 on VIDAA) now shows, on every screen: fps · p95 frame · janky % (>33 ms) · worst frame · DOM nodes · images · JS heap. No speed fix yet — the next round brings numbers. First suspect to test with it: the Home hero's **full-screen `blur(24px)`** over a poster when a title has no backdrop (`styles.css:255`; the detail pages use the same at :231 / :378). TV GPUs are known to be slow at large blurs. |
| **R7** | `d2c13fb` | **Measurement only.** Live now logs `picture: FROZEN — no frame drawn for 2 s while the position moved N s`, and `picture: drawing again after N s`, from the decoded-frame count against the position. Works on mpegts.js only: AVPlay exposes no frame count, so on an AVPlay channel a freeze is still invisible to us — say which player was carrying the picture when it happens. |
