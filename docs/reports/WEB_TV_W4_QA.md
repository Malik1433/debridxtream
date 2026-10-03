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

---

# Round 5 — the answers to round 4, checked on the TV (2026-10-03)

Branch @ `7ecaaeb7`, **136 tests pass**, packaged and installed. Report only. Rulebook: TV, Samsung.

| Round 4 item | Round 5 result |
|---|---|
| **R1** category/chip/rail opens on OK, focus moves in | ✅ PASS |
| **R2** AVPlay draws in the right place | ✅ PASS — correct in the preview box **and** fullscreen |
| **R3** Home recent channel plays at once | ⚠️ **PARTIAL** — it does reach fullscreen, but shows the list first and jumps |
| **R4** Trailer | ✅ PASS — the Samsung YouTube app opens on the trailer |
| **R5** film survives a network cut | ✅ PASS for a ~10 s cut: reconnected and resumed. The >1 min case is **deferred** |
| **R6** the app is slow | ✅ **now measured** — and the cause is identified |
| **R7** picture freezes with audio running | ✅ **now measurable, and it reproduced** |
| Round 4's untested search/settings items | ✅ all PASS (owner) |

---

## ⭐ R6 — measured, and it is the hero

The GREEN readout, same Home, three states:

| State | fps | p95 | janky | worst | DOM | img | heap |
|---|---|---|---|---|---|---|---|
| Home top, **hero on screen** | **24** | 67 ms | 9–18 % | **963 ms** | 369 | 40 | 82 MB |
| Hero on screen (another moment) | 41 | 17 ms | 2 % | 733 ms | 372 | 40 | 82 MB |
| **Scrolled down, hero off screen** | **43** | 33 ms | 5 % | **383 ms** | 372 | 40 | 82 MB |

**DOM, images and heap are identical in all three** — 372 / 40 / 82 MB. The only variable is whether
the hero is on screen, and that alone **halves the frame rate** (24 → 43) and **halves the worst
frame** (963 → 383 ms).

So: **the hero is the cost.** ⚠️ And this kills my own earlier hypothesis — round 4 suggested looking
at DOM node counts and whether the virtual grid keeps 69,536 items small. It does; DOM is 369. That
line in round 4 should not be worked on.

⚠️ **But the hero is not the whole story.** With the hero gone it is still **43 fps, not 60**, and
`worst 383 ms` is still a visible hitch. Two separate costs: the hero's, and a baseline one underneath
it. Fixing the hero and stopping would leave the app at 43 fps and look like a failed fix.

For reference, CLAUDE.md's own jank gate for the Android app is **p95 ≤ 60 ms, janky ≤ 30 %**. Home
with the hero breaches the p95 half of it (67 ms); `worst 963 ms` — a frame of nearly one second — is
what the owner feels as *"focus movement is very slow"*.

## ⭐ R7 — the freeze reproduced, and it is NOT starvation

```
11:53:18  picture: FROZEN - no frame drawn for 2 s while the position moved 2.4 s
mpegts.js · pos 11.8 s · buffer 45.3 s · speed 1.00x · stalls 0/3 min · stops 15
```

The position advanced 2.4 s and **not one frame was drawn**. That is exactly why the old meter was
blind: it judges a stall from the position, and the position was healthy. `pictureWatch` now catches
it — the instrument works.

⭐ **The decisive number is `buffer 45.3 s`.** There were forty-five seconds of data in hand when the
picture froze, so this is **not** the network and not the cushion. The data arrived; the decoder or
the renderer did not put it on screen. Anything that adds buffer, patience or slow-fill will do
nothing here — the fix lives in the player, not the policy.

⚠️ `stalls 0/3 min` next to `stops 15`: the interruption meter still reports a healthy session during
all this, because it too counts stalls from the position. Whatever is decided about the freeze, the
meter has to learn about the picture as well, or the numbers will keep saying the session was fine.

Two more things in the same capture:

**R7b — `first picture on … after 1070129 ms`** (17.8 minutes). Nonsense; almost certainly measured
from an older load rather than this one. Worth fixing early because the first-picture number is what
these QA rounds are being judged on.

**R7c — the player flaps on a zap.** `11:53:06 AVPlay → mpegts.js → 11:53:08 AVPlay →
11:53:13 mpegts.js` — four switches in seven seconds, each one tearing down and opening a player, on
an account with `max_connections=1`.

## R3 — partial

The card does end up playing fullscreen, but the list appears first and the app then jumps into
fullscreen by itself. It should open straight into fullscreen; the intermediate screen is the defect.

## R4 — passes, but going back kills the app

⚠️ **New, and a Samsung checklist item:** returning from the YouTube app **closes DX Play**. Samsung's
own requirements say an app must survive being backgrounded and resume where it was. Round 3 saw the
good version of this (TV off → on returned to the same screen), so this is specific to launching
another app.

## R5 — half done

A ~10 s cut: the reconnect worked and the film carried on. **The >1 min case was not run** — the owner
cannot take the network down (it is the same network), and ⚠️ **I cannot either**: `sdb shell` is
silent on this retail TV so no command can be run on it, and cutting the router would take my own sdb
link down with it, leaving nobody watching. It stays open.

⚠️ **And the reconnect is a safety net, not the cure.** In round 4 a film died at 5:51 with nobody
touching anything. Why a connection drops unprompted is still **unknown**. Three candidates, each with
a different fix: the account's `max_connections=1` (another device touching the provider kills this
stream — the likeliest), the TV being on **Wi-Fi**, or the provider closing idle connections. They can
be told apart: note whether any other device was on the provider when it next dies, and try the same
film with the TV on a **LAN cable**.

## Round 4's untested items — all PASS

SPACE / DEL / CLEAR · trending chips · BACK from results → keys → leaves · Recent Searches after
opening a result · preset scope from Movies / Series · Account panel Up-scroll · Resume Last Channel
on/off · Change PIN · Manage-on-phone QR.

---

## Where this leaves it

Design and navigation are done. What is open is all playback and frame rate, and for the first time
all of it is **measured** rather than described:

1. **R7** — frames are not drawn although the data is there (`buffer 45.3 s`). Player-side, not policy.
2. **R6** — the hero halves the frame rate; a separate baseline cost keeps it at 43 fps without it.
3. **R7b / R7c** — a nonsense first-picture number, and four player switches per zap.
4. **R4b** — coming back from YouTube closes the app.
5. **R3** — the recent-channel card flashes the list before going fullscreen.
6. **R5** — why a stream drops on its own is still unknown, and the >1 min case is unrun.

## Answer from the online agent — round 5 (2026-10-03)

One commit per item, so each can be measured on its own.

| | Commit | What changed |
|---|---|---|
| **R6 · hero** | `649899c` | The full-screen `blur(24px)` is gone. A title without a backdrop gets `SoftBackdrop`: the poster drawn **once** into a 48×18 canvas and stretched by CSS — bilinear scaling is the blur, no filter runs per frame. The hero image and its scrim are their own compositor layers, so focus moving over them never repaints them. Detail pages use the same. |
| **R6 · baseline** | `99223f6` | The cost under the hero (43 fps without it): the Home page, every row strip and the Live chips are their own layers (`will-change: transform`), so a scroll moves a texture instead of repainting the posters under it; the cards' focus glow no longer **animates** a 36 px shadow blur over ~10 frames — it appears at once, the scale still animates. ⚠️ Two hypotheses, two commits: the round-5 table re-measured tells which one paid. |
| **R7** | `2278dd8` | The freeze is now acted on. When PictureWatch reports it, the engine **counts an interruption** (the meter no longer calls the session healthy), shows buffering, **nudges the decoder** (a seek 0.1 s ahead inside the buffer — no new provider connection), and **reopens the channel** if the picture is still frozen 3 s later. A running clock is no longer "playing" while frozen. MSE only — AVPlay still exposes no frame count. |
| **R7b** | `2278dd8` | The first-picture line is timed from **this load**; a reconnect is logged as `picture back … after N ms`. The 1070129 ms was a reconnect ~17 min into the channel, timed from the original zap. |
| **R7c** | — | **No change, and why.** On a zap the new channel starts on mpegts.js (its codecs are unknown until the stream is parsed), and moves to AVPlay the moment its audio is known not to play there: AVPlay → mpegts.js → AVPlay is two switches **by design**, one provider connection at a time. A third switch back to mpegts.js happens only on a new URL — another zap. If it happened on ONE channel, send the full `player:` / `live:` lines of that zap; that would be a real bug. |
| **R3** | `b859198` | Started from a Recent card or Search, Live **mounts in full screen**: sampled every 30 ms in the headless smoke, the list is never visible. BACK returns to Home / Search. |
| **R4b** | `f2beb02` | **Measure first**, as R6/R7 were. The app keeps its own record in storage that survives being killed — `boot`, `hidden`, `visible`, `unload`, `youtube: launching / launched / no app`, `exit (BACK / dialog)` — and GREEN shows the last six lines. A `boot` right after `hidden`, with no `unload` or `exit` between, means **the TV killed it** (memory); an `exit` means we closed it. |
| **R5** | `cad29f0` | Every film connection failure goes into the same record with the time and position and whether it reconnected or gave up — so the next drop can be set against what else used the provider at that moment, and LAN against Wi-Fi. The >1 min cut stays open: neither of us can take the network down safely. |

---

# Round 6 — the answers to round 5, checked on the TV (2026-10-03)

Branch from `568069a6` through `9e71b7ea`, rebuilt and reinstalled after each. **142 tests pass.**
Report only. Rulebook: TV, on the Samsung.

| Round 5 item | Round 6 result |
|---|---|
| **R4 / R4b** trailer | ✅ **CLOSED** — plays **inside the app**, "perfect" (owner), after the hosting deploy |
| **R6** the app is slow | ⚠️ **better, not fixed — but the cause is now identified** |
| **R7** picture freezes | not re-captured this round |
| **R5** streams drop by themselves | ⚠️ still happening; the lifecycle line is now on record |
| **R3** recent-channel card | not re-checked this round |

---

## ⭐ R6 — it is JavaScript, not paint

The readout now separates the two. Same build, two screens:

| | fps | p95 | janky | worst | **JS long (per 5 s)** | DOM | img | heap |
|---|---|---|---|---|---|---|---|---|
| **Home**, hero on screen | 44 | 67 ms | 11 % | 317 ms | **9 tasks / 958 ms** | 394 | 42 | 54 MB |
| **Movie detail** | 55 | 17 ms | 4 % | 83 ms | **4 tasks / 264 ms** | 131 | 19 | 54 MB |

**Home blocks the main thread for 958 ms out of every 5 s — one second in five — across nine
separate long tasks.** The detail page does 264 ms, roughly a quarter of that. That is the whole
difference, and it is what the owner feels as *"focus movement is very slow"*: when the thread is
blocked, a key press has nowhere to go until it comes back.

⚠️ **This corrects what I wrote in round 5 and earlier in round 6.** I argued the cost had to be
**paint** — gradients, blur, posters — because the DOM was small. The DOM *is* small; the cause is
still JavaScript. Work on the CSS would have missed it. **Find the nine long tasks Home runs every
five seconds**; do not go to the stylesheet first.

**What the three attempted fixes actually did**, measured across rounds (Home, hero on screen):

| | round 5 | `649899c` + `99223f6` | `e0c3cd2` | now |
|---|---|---|---|---|
| fps | 24 | 35 | 36 | **44** |
| p95 | 67 ms | 50 ms | 30–67 ms | 67 ms |
| worst | 963 ms | 533 ms | 800+ ms | **317 ms** |
| heap | 82 MB | 54 MB | 45 MB | 54 MB |

Real progress — the worst frame is a third of what it was, and fps has nearly doubled — but Home is
**44 fps where the same TV gives 55–60 on a detail page**, so it is not finished. The remaining gap is
the JS long figure above.

## R4 / R4b — closed, and the owner's own argument is why

Round 5 left this split between "a bug" and "a Tizen limit". The owner cut through both:

> *"No other app does it this way — Netflix doesn't, none of them do. They all play trailers inside
> their own app."*

That is the standard, and it is right. So the question was never how to come back from YouTube; it
was that the app should not leave. `777b4dc` does that with a hosted frame page
(`admin-panel/trailer.html`), which needed a hosting deploy to exist — **done this round with the
owner's approval**, hosting only, one file, rules and functions untouched (the config ignores them in
any case), and verified by fetching the page back (HTTP 200, real content).

Result on the TV: **the trailer plays inside the app, "perfect".** No YouTube app, so no return
problem, and no `video player configuration error` — the frame page gives the embed the page origin
that a packaged app running from `file://` does not have.

⚠️ The page is public and carries no secret: it validates the video id, uses YouTube's official embed
and downloads nothing. Read in full before it was deployed.

## R5 — the drop is real, and now it has a log line

```
18:38:01  vod: PLAYER_ERROR_CONNECTION_FAILED at 1:37 - reconnect 1/5
```

A film dropped **by itself at 1:37** and the retry began. So this is not an artefact of the earlier
test; it keeps happening. **Why remains unknown** — the three candidates from round 5 stand
(`max_connections=1`, Wi-Fi, the provider), and the way to tell them apart is still the way to tell
them apart.

⭐ Separately: **"Reconnecting" did not appear once in 30 minutes** of Live on the `3406a90a` build.
Recorded as *did not reproduce*, **not** as fixed — the earlier occurrences were real, nothing was
found that explains them, and a quiet half-hour may just mean no other device touched the provider.

## Lifecycle, for the record

```
18:02:30 hidden   18:06:24 visible      (backgrounded and came back - it did not die)
18:34:05 unload   18:34:19 boot         (the reinstall)
```

## Open going into round 7

1. **R6** — the nine long JS tasks per 5 s on Home. Everything else about Home is fine.
2. **R5** — why a stream drops unprompted, still unknown; and the >1 min network case, which neither
   the owner nor I can run (sdb shell is silent on this retail TV, and cutting the router takes my own
   link with it).
3. **R7** — the frozen-picture case was not re-captured this round.
4. **R3** — the recent-channel card flashing the list was not re-checked.

---

# Round 7 — measured by the agent, not read off the screen (2026-10-03)

Branch @ `34db35c7`, **142 tests pass**, built and installed. Report only.

## ⭐ First: how this round was measured, and why it matters

The owner stopped the round and said the right thing:

> *"Find a way you can test and see this yourself — these details are hard for me, I cannot give you
> exact information, and that way we can go in the wrong direction."*

He was right, and it had already happened twice: numbers read off a photograph sent me after **paint**
when the cause was JavaScript, and after **DOM size** when the DOM was never the problem.

There is no debugger on this TV — `sdb shell` is silent, no inspector port, and the Tizen CLI has no
debug command. So instead **the TV now posts its own measurements to the PC**: a small probe injected
into the *built package only* (`build-tizen/`, never the repo — `git status` stayed clean), POSTing
every 5 s to a collector on the LAN. It measures independently of the app's own HUD: `PerformanceObserver`
for long tasks, rAF for frames, DOM/img counts, heap. It sends numbers only — no credentials, no URLs,
no content — and the clean build goes back on the TV when QA ends.

⚠️ **The first thing it found was that the HUD was part of the problem it was reporting.** Home sitting
still read *44 fps / 958 ms of long tasks* on the HUD; the probe, with the HUD closed, reads
**60 fps and zero long tasks** on the same build, with heap at **23 MB** instead of 54. Some of what
rounds 5 and 6 measured was the measuring.

## ⭐ R6 — what is actually slow

| What | fps | worst frame | JS long tasks (5 s) | of which NOT key-triggered | DOM |
|---|---|---|---|---|---|
| **Any screen, untouched** | **60** | 17 ms | **0** | 0 | — |
| **Detail page**, moving focus | **60** | 17–33 ms | **0** | 0 | 129 |
| **Home**, moving focus along a row | 35–52 | 350–817 ms | 5–17 / 0.5–1.6 s | **0** | 396 |
| **Opening Movies** (grid build) | 29–38 | 667–1200 ms | **97 / 11.4 s** | 10.8 s | 258–471 |

Three separate facts, and they point in different directions:

1. **Nothing runs on its own.** Idle is 60 fps with zero long tasks, on every screen. No timer, no
   rotation, no background refresh is costing anything. That closes a suspicion carried since round 5.
2. **The focus system is not at fault.** On a detail page, moving focus costs **zero** long tasks.
   If the shared focus code were expensive it would show there too.
3. **Home's own per-key work is expensive**, and `idle 0` says it is *caused by the key press*:
   100–800 ms of JavaScript for a single focus move. An 820 ms task is why a press feels dead.

⚠️ And the heaviest finding is not Home at all: **opening the Movies grid ran 97 long tasks totalling
11.4 seconds**, 10.8 s of it not key-triggered — screen construction. The proof it really blocks: the
probe's own 5-second timer could not fire, and the gap between two samples came out at **17 s**.

So the work left is: **the per-key work on Home**, and **the one-off build of a big screen**. Not
paint, not DOM size, not the focus system, not a background timer.

`f7c06e8` (the forced layout removed on every focus move) made a real difference — the owner's words
were *"it's a lot better now, only slightly slow"*, and the numbers agree.

## R3, R5, R7 — not re-checked this round

The round went into the measurement problem, which was the right use of it. The recent-channel card,
the unprompted VOD drop and the frozen picture stand where round 6 left them.

## For the next round

The probe is the useful thing to keep. It turns "the app feels slow" into a table, it needs nothing
from the owner but normal use, and it cannot be fooled by the HUD. ⚠️ It is injected into the built
package only — if someone rebuilds without it, the numbers go back to being read off a television.
