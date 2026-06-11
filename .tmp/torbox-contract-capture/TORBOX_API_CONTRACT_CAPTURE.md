# TorBox API Contract Capture

Date: 2026-06-01

## Test Input

- Safe payload: official Ubuntu 24.04 desktop release torrent.
- Authentication: temporary TorBox API key supplied manually for this capture.
- Raw token, raw magnet, torrent hash, account IDs, resource IDs, and private paths are not stored in the saved samples.

## Captured Calls

1. `POST https://api.torbox.app/v1/api/torrents/createtorrent`
   - Authentication: `Authorization: Bearer <api-key>`
   - Body: multipart form field `magnet`
   - Sanitized sample: `createtorrent.sanitized.json`

2. `GET https://api.torbox.app/v1/api/torrents/mylist?id=<torrent-id>&bypass_cache=true`
   - Authentication: `Authorization: Bearer <api-key>`
   - Sanitized sample: `mylist.sanitized.json`

## Confirmed Contract

- Created torrent ID field: `data.torrent_id`
- Status lookup ID field: `data.id`
- Authoritative runtime status field: `data.download_state`
- Observed cached-ready response:
  - `data.download_state = "cached"`
  - `data.cached = true`
  - `data.download_present = true`
  - `data.download_finished = true`
  - `data.progress = 1`
- Enqueue response for this cached payload:
  - `success = true`
  - `detail = "Found Cached Torrent. Using Cached Torrent."`

## Safe Initial Mapping

- `READY`: `download_state == "cached"` with `cached == true`, `download_present == true`, and `download_finished == true`.
- `QUEUED`, `CACHING`, and `FAILED`: not proven by this single cached sample.

## Official State Reference

The official TorBox docs list these `download_state` values for `mylist`:

- `downloading`
- `uploading`
- `stalled (no seeds)`
- `paused`
- `completed`
- `cached`
- `metaDL`
- `checkingResumeData`

The docs explicitly state: do not use `completed` for download completion status. The real capture confirms `cached` as the ready cached-delivery state for this test item.

## Unresolved Before Runtime Status Mapping

- Capture one safe non-cached torrent to confirm queued/downloading fields and transitions.
- Capture one controlled failed or rejected request to confirm terminal error payload shape.
- Confirm whether `download_state == "completed"` is distinct from cache-ready delivery and whether it is safe to surface as `READY`.
- Confirm terminal failure values for removed, rejected, stalled, or errored torrents.
- Confirm the intended `controltorrent.operation` value before deleting temporary test items through the API.
