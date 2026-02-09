---
name: high-perf-playlist-manager
description: Logic for parsing massive M3U/M3U8 playlists and processing XMLTV EPGs without blocking the UI.
version: 2.0
---

# High-Performance Data Handling

## 1. Parsing Strategy
* **Never** parse an M3U file on the Main Thread.
* **Stream Parsing:** Do not load the whole file into RAM. Read line-by-line using InputStreams.
* **Regex vs String Split:** Use optimized String splitting over Regex for M3U tags (`#EXTINF`) to increase parsing speed by 300%.

## 2. Database (Room/SQLite)
* Use `Batch Inserts` (transactions) for channels. Insert 500 channels per chunk.
* **Indexing:** Create indices on `group_title` and `channel_name` for instant search results.

## 3. EPG Logic (The "Tivimate" Touch)
* **Fuzzy Matching:** If EPG ID is missing, match Channel Name to EPG Name using "Levenshtein Distance" (allow 80% similarity).
* **Retention:** Automatically prune EPG data older than 2 days to keep the database small.

## 4. Logo Caching
* Use Glide or Coil image loaders.
* Set a generic "placeholder" icon instantly while the real logo loads.