---
name: media-metadata-enricher
description: Logic for matching raw filenames to TMDB/Trakt APIs to fetch Posters, Backdrops, and Cast info.
version: 1.0
---

# Metadata & Enrichment Logic

## 1. The Matching Algorithm
When parsing a VOD filename (e.g., `Spider-Man.No.Way.Home.2021.4K.mkv`):
1. **Clean:** Remove release group tags (e.g., `RARBG`, `H265`, `AC3`).
2. **Extract:** Isolate Title + Year using Regex.
3. **Query:** Search TMDB API with `&year=2021` to ensure accuracy.

## 2. Caching Strategy (Critical)
* **Don't hit API every scroll:** Store the `tmdb_id` and `poster_path` in your local Room Database mapped to the playlist item ID.
* **Update Frequency:** Only refresh metadata if the entry is older than 7 days (to catch updated ratings/episode counts).

## 3. Fanart & UI Polish
* **Backdrop:** When a user focuses on a movie card, load the `backdrop_path` image into the main background with a 50% dark overlay (Scrim).
* **Cast:** Display the top 5 cast members horizontally below the description.

## 4. Trakt Integration (Optional)
* If the user authorizes Trakt, sync their "Watched Status."
* If they finish a movie, mark it as `seen` on Trakt immediately via background job.