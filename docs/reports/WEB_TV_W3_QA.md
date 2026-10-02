# W3 QA — Live TV (Samsung, 2026-10-01)

**Build:** branch `claude/gifted-einstein-5mdvd2`, from `ee439cb4`, plus the three fixes in this
commit. **Device:** Samsung GU75CU7179UXZG, Tizen 9.0 / Chrome 120, packaged `.wgt`, phone-linked
account, 15,951 channels. **Rulebook checked against: TV (10-foot), on the Samsung.**
VIDAA was not run (owner's call, same reason as W2: not a store target, and linking it spends one of
the account's three device slots). LG has no device yet.

## Results

| Step | | Result |
|---|---|---|
| a | sidebar → right lands on the first provider category, not Favourites | ✅ |
| b | 30+ rows down and back up: focus always visible; categories change the list | ✅ |
| c | OK → preview, "Connecting to …" pill, gone on first picture | ✅ **3–5 s** |
| d | fullscreen, 10 zaps on ▲▼ and CH+/−: pill + OSD each time | ✅ **never showed the wrong channel's name or poster** |
| e | OK toggles the OSD; BACK → the list on the channel now playing; BACK → Home | ✅ |
| f | yellow key → star, appears under Favourites, survives a restart | ✅ |
| i | a dead channel says so and BACK still works | ❌ → ✅ after the fix below |
| a/b | focus after opening Live TV | ❌ → ✅ after the fix below |
| g | VIDAA numpad in fullscreen | not run |
| h | 20 min soak, `[live]` lines, 30–60 s network cut | **not run — see "What QA cannot see"** |

## The three defects found, and what was wrong

**1. Opening Live TV could kill the remote.** Owner: *"first you have to take the focus to Home, then
focus starts working."* Reproduced in a desktop browser: open Live TV **while the 17,704-channel
catalogue is still loading**, press right, and focus is lost completely — every later key does
nothing. The screens only re-checked focus when they re-rendered, and a key press that goes nowhere
causes no render, so the recovery never ran. Now every key press is followed by one check: focus goes
back where it was, or to this screen's own sidebar item. *TV rulebook: focus is always visible and
the remote is never dead.*
⚠️ Worth knowing for the next person: norigin leaves focus resting on a **container** (`sidebar` /
`content`). That key exists, so a naive `doesFocusableExist` check reports everything is fine while
nothing inside is focused. The first attempt at this fix failed for exactly that reason.

**2. "Connecting…" had no end.** `if (!this.started) { …; return }` — an error became `failed`, but a
provider that sends *nothing* produced neither error nor picture, so the pill stayed for ever. That
is the hang CLAUDE.md forbids ("bound every network call… a timeout must degrade, never hang"). Now
bounded at `CONNECT_TIMEOUT_MS = 25_000` (a good channel here takes 3–5 s): the stream is stopped and
the screen says **"This channel is not available (…)"**. Two tests cover it, including that a merely
slow channel is not given up on.

**3. A failure that would not say why.** The message now names what the stream carries, read from
mpegts.js's `MEDIA_INFO`. That is what turned the next item from a guess into a fact.

> ⚠️ **CORRECTION (2026-10-02): the mechanism below is WRONG.** This TV's MSE *does* take MP3,
> AC-3 and E-AC-3 — Settings now reports `audio AAC+MP3+AC-3+E-AC-3`. I had asked whether it could
> play MP3 **inside MP4**, which it cannot, but mpegts.js feeds MP3 as raw `audio/mpeg`, which this TV
> accepts. Why mpegts.js gets no picture from those channels is still unknown; the AVPlay fallback is
> triggered by a timeout, not by a known cause. See `WEB_TV_W4_QA.md` P2.

## ⭐ The real finding: this player cannot play every channel

The owner's 4K channels play on the Fire TV but not here. With the above, the TV answered:

```
This channel is not available (avc1.640028 / mp3 did not play)
Settings → Playback: HD and 4K (HEVC) · audio AAC
```

So: **video is fine** (plain H.264 High@L4.0; the TV even reports HEVC support, so two earlier guesses
of mine — HEVC, then AC-3 — were both wrong). **The audio is the wall.** mpegts.js must repackage the
TS audio into MP4 for MSE, and this TV's MSE accepts **AAC only, not MP3**. With the audio track
refused, mpegts.js stops producing output entirely — no picture, no error. The Fire TV plays the same
channel because ExoPlayer takes raw TS and never needs the MP4 wrapper.

⚠️ **This is a class of channels, not a handful.** Any channel whose audio is not AAC — MP3, AC-3,
E-AC-3 — fails this way on Samsung. How many of the 15,951 that is has not been measured.

**Recommended fix: fall back to AVPlay on Tizen** when mpegts.js cannot start. AVPlay plays raw TS
natively and needs no MP4 wrapper, so MP3/AC-3 are not its problem. `AvplayAdapter` was kept at W0
for this kind of day.

⚠️ **It is not free, and W0 measured the cost.** On the same TV, same channel, AVPlay gave
*first picture 0.4 s · played 13 s · 4 stops (94.8 s) · buffer ahead **n/a***, against mpegts.js's
*664 s · 1 stop (0.1 s) · 8.5 s*. Two things must be solved before AVPlay can carry a channel:
1. **live `.ts` buffering parameters** (`setStreamingProperty` / `setBufferingParam`) — W0 ran it on
   defaults, which is most of why it stalled;
2. **a cushion number.** `LiveCushionPolicy`, slow-fill and hold-on are all written against buffered
   milliseconds, and AVPlay reported none. Either find the AVPlay call that gives it, or those
   policies do not apply to AVPlay-played channels and that has to be a stated decision.

A sensible shape: keep mpegts.js as the default, try AVPlay only for a channel mpegts.js failed to
start, and tell the viewer which one is playing.

## What QA cannot see (step h)

**A retail Samsung TV gives no logs.** `sdb shell` returns nothing, and no inspector port
(7011 / 9998 / 9222) is open — `tizen run` has no `--debug`. So the `[live]` lines the engine writes
(`live cushion: stall N…`, retries, the interruption summary) **cannot be read off the TV at all**.

Step h was therefore not run. To do it properly, the app needs a small on-screen debug panel that
prints the `[live]` lines — the same trick W0's proof page used — behind a key. Without that, a 20-min
soak and the 30–60 s network cut can only be judged by eye: did the pill appear, did the picture come
back. That is worth doing even by eye, but it produces no numbers.

## Open

- AVPlay fallback for non-AAC audio (above) — **the one that blocks real use**
- step h: on-screen `[live]` panel, then the 20-min soak and the network cut
- VIDAA step g (numpad zap in fullscreen); LG everything
- how many of the 15,951 channels are non-AAC — currently unknown
