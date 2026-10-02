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
