---
name: debrid-stream-resolver
description: API logic for Real-Debrid/AllDebrid, handling magnet links, torrent caching, and file selection.
version: 2.0
---

# Debrid & Torrent Logic

## 1. The "Cached" Check
Before showing links to the user (Stremio style):
1. Take the magnet hash.
2. Hit the `/torrents/instantAvailability` endpoint.
3. **Filter:** Only show files that are 100% cached. Do not make the user wait for downloads.

## 2. File Priority
When a torrent has multiple files (e.g., a Season Pack):
* Prioritize video files (`.mkv`, `.mp4`).
* Filter out sample files (size < 50MB).
* Sort by resolution: 4K (2160p) > 1080p > 720p.

## 3. Security & Account
* **Token Refresh:** Check token validity on app launch.
* **IP Warning:** Real-Debrid blocks multi-IP usage. Ensure the app warns the user if they try to use mobile data + WiFi simultaneously.