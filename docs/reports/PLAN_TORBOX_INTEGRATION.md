# Plan: TorBox integration (native account + usenet/SABnzbd) — 2026-07-16

## STATUS: DROPPED (2026-07-16)
Owner's call: StremThru/other addons already use TorBox's API on the backend and already surface
both torrent AND usenet `[TB]` results that play. A native TorBox resolver would be mostly
redundant — the placeholder/reconnect issues are already fixed (filter + fast-fail), and direct
API can't make uncached content playable. Not worth the effort. Revisit ONLY if the owner drops
addons for a "paste TorBox token" setup, or the StremThru proxy becomes unreliable. Rest of this
doc kept for reference.

---


## Goal
Make **TorBox** a first-class playback backend in the app — a peer to Real-Debrid — so the
owner can connect their own TorBox account and play TorBox-cached content, including
**usenet** (the "SABnzbd" ask). Today TorBox can only *add* a magnet and poll status; it
cannot return a playable URL and is never called.

## What already exists (verified)
- `data/debrid/api/TorBoxApiService.kt` — only `torrents/createtorrent` (POST magnet) +
  `torrents/mylist` (status). Base `https://api.torbox.app/`, Bearer-token interceptor in
  `di/DebridModule.kt`.
- `data/debrid/repository/TorBoxSourceActionRepository.kt` — enqueue magnet → status
  (QUEUED/CACHING/READY/FAILED), request pacer. **No callers anywhere → dead code.**
- `data/prefs/TorBoxPreferences.kt` — secure token store. **No UI to set the token.**
- Real-Debrid is the only resolver wired into `PlaybackResolver` / `DebridPlaybackRepository`.

## Gaps to close
1. No settings UI → users can't enter a TorBox token.
2. No `checkcached` (instant availability) endpoint.
3. No `requestdl` (get a playable/stream link) endpoint → TorBox sources can't play.
4. No usenet endpoints (`usenet/createusenetdownload`, `usenet/mylist`, `usenet/requestdl`).
5. Not wired into `PlaybackResolver`, source-list cache badges, or the picker.

## Proposed phases (each ships + device-verifies independently)

### Phase 0 — Connect a TorBox account (small, safe, unblocks everything)
- Add a TorBox card to Settings (mirror the RD settings card): paste API token → `saveToken`,
  "Test connection" (call `mylist`/user endpoint), clear.
- No playback change yet; just makes the token configurable.

### Phase 1 — TorBox as a torrent resolver (the core)
- Extend `TorBoxApiService`: `GET torrents/checkcached?hash=…&format=list` and
  `GET torrents/requestdl?torrent_id=…&file_id=…` (returns a direct CDN link).
- New `TorBoxPlaybackRepository` (mirror `DebridPlaybackRepository`'s resolve contract):
  checkcached → if cached, createtorrent (if needed) → pick video file → requestdl → return a
  playable https URL; typed failures for uncached/dead so the fast-fail + picker flow already
  built applies.
- Wire into `PlaybackResolver`: when the user has a TorBox token, resolve TorBox-eligible
  sources via TorBox (in parallel with / as fallback to RD; prefer whichever is cached).
- Bonus: TorBox `checkcached` is a **working availability signal** — it can also fix the
  audit's dead-`instantAvailability` cache-badge problem for TorBox-backed rows.

### Phase 2 — Usenet (the "SABnzbd" part)
- Add `usenet/createusenetdownload`, `usenet/mylist`, `usenet/requestdl` and a parallel resolve
  path in `TorBoxPlaybackRepository`.
- Source of NZBs: the StremThru/Debridio `[TB]` results already include usenet-backed entries;
  route those through the usenet path. (No self-hosted SABnzbd needed — TorBox does the usenet
  fetching in the cloud.)
- *Optional stretch:* real self-hosted **SABnzbd** support (Settings field for SABnzbd URL +
  API key, POST NZB, poll `queue`/`history`, play the finished file over LAN). Heavier and
  LAN-only; recommend deferring unless the owner specifically wants self-hosted.

### Phase 3 — Picker/UX polish
- The picker already renders a `TB` badge (`MovieSourceAdapter`). Show TorBox cached vs uncached;
  when both RD + TorBox are configured, prefer a cached source on either.
- Diagnostics events for TorBox resolve (fingerprinted, no creds).

## Owner's answers (2026-07-16) → refined direction
- **Torrents**: keep the existing addon `[TB]` proxy links (no native torrent resolver).
- **Usenet**: TorBox **cloud** usenet, feeding **NZB** with the "server + username TorBox provides".
- So the work is **usenet-first**, not the torrent resolver.

## IMPORTANT dependency to resolve first
TorBox's cloud usenet is driven by the **TorBox API with your account token** — there is no
token-less usenet path. Two possible shapes of "server + username TorBox provides":
- **(A) TorBox API token** → app calls `usenet/createusenetdownload` (POST an NZB or a usenet
  id) → TorBox fetches it in their cloud → `usenet/requestdl` returns a playable link. Clean,
  cloud-only, no on-device downloader. This still needs the **token in Settings** (Phase 0).
- **(B) Raw NNTP creds** (usenet **server address + username + password**, SABnzbd-style) →
  needs an on-device usenet downloader to pull + assemble the NZB, then play the file. Heavy,
  and really the "self-hosted SABnzbd" shape.

And in both cases: **where do the NZB files come from?** — an NZB file the owner supplies, a
usenet indexer (URL + API key), or a usenet-capable addon that returns usenet ids.

## Revised phases (usenet-first)
- **Phase 0** — Settings: a "TorBox" card to save the API **token** (needed for cloud usenet)
  and, if going route (B), fields for the usenet **server / username / password**.
- **Phase 1** — Usenet source input: pick where NZBs come from (indexer creds, or usenet-capable
  addon results). Decide with the owner.
- **Phase 2** — Resolve: `usenet/createusenetdownload` → poll `usenet/mylist` → `usenet/requestdl`
  → playable URL; typed failures reuse the fast-fail + picker flow.
- **Phase 3** — Picker/UX: `TB` badge, usenet rows, cached indicator.

## DECISION LOCKED (2026-07-16): Route A — TorBox API token
Owner: since the cloud **API** does the same job as the raw NNTP creds, drop the heavy
on-device usenet/SABnzbd path entirely. Use the **TorBox API token** (the NNTP creds screenshot
is NOT needed). This keeps the app streaming + the code close to the Real-Debrid pattern.

### Revised, simpler plan
- **Phase 0** — Settings: "TorBox" card to paste + save the **API token** (secure prefs already
  exist in `TorBoxPreferences`), "Test connection" against a TorBox user endpoint.
- **Phase 1 (core)** — Native TorBox resolver via the token: extend `TorBoxApiService` with
  `torrents/checkcached` + `torrents/requestdl`; a `TorBoxPlaybackRepository` mirrors
  `DebridPlaybackRepository` (checkcached → createtorrent-if-needed → pick file → requestdl →
  playable https URL, typed failures). Wire into `PlaybackResolver`.
  - **Reliability win:** the addon `[TB]` results we fought earlier are TorBox behind a StremThru
    *proxy* (hence the 405/"No Matching File"). Resolving those hashes **directly** via the
    TorBox API (with the owner's token) should be far more reliable than the proxy URL.
- **Phase 2 (optional)** — Usenet via the **cloud API** (`usenet/createusenetdownload` /
  `mylist` / `requestdl`) IF/when a usenet content source is decided. Not on-device.

### Only remaining detail (resolve at implementation time)
For usenet specifically, where the NZB/usenet id comes from (TorBox search, an indexer, or
addon-provided usenet ids). Torrents don't need this — they resolve from the infoHash the addon
results already carry. So Phase 0 + Phase 1 need no further input; usenet content-source is a
Phase-2 detail.

### Security
The usenet NNTP username/password shown in the owner's screenshot are NOT stored or used by the
app (Route B dropped). Only the TorBox API token is stored, in the existing secure prefs.
