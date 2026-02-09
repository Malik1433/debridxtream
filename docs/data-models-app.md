# Data Models - Room Database Schema

## Overview
The application uses **Room** for local caching of IPTV content to ensure performance and offline capability.
**Database:** `AppDatabase`

## Entities

### Channels (`channels`)
Caches Live TV channels.
- **PK:** `streamId` (String)
- **Fields:** `name`, `categoryId`, `streamIcon`, `streamType` (live/vod/series)
- **Metadata:** `added` (Long), `cachedAt` (TTL), `isFavorite` (Boolean)

### Categories (`categories`)
Caches groupings for Live, VOD, and Series.
- **PK:** `categoryId`
- **Fields:** `name`, `parentId`, `type` (live/vod/series)

### VOD (`vods`)
Caches Movie entries.
- **PK:** `streamId`
- **Fields:** `name`, `categoryId`, `containerExtension`, `rating`

### Series (`series`)
Caches Series entries.
- **PK:** `seriesId`
- **Fields:** `name`, `categoryId`, `cover`, `plot`, `cast`, `genre`

### Seasons (`seasons`)
- **PK:** Composite/ID
- **Fields:** `seriesId`, `seasonNumber`, `name`

### Episodes (`episodes`)
- **PK:** `id`
- **Fields:** `seriesId`, `seasonId`, `title`, `duration`, `containerExtension`

### EPG (`epg_data`)
Caches Electronic Program Guide entries.
- **PK:** `id` (or composite channelId + startTime)
- **Fields:** `channelId`, `start`, `end`, `title`, `description`

### Search History (`search_history`)
- **PK:** `id` (AutoGenerate)
- **Fields:** `query`, `timestamp`

### Favorites (`favorites`)
- **PK:** `streamId`
- **Fields:** `name`, `type`, `addedAt`

## DAOs
- `ChannelDao`: Access/Search live channels.
- `VodDao`: Access VOD movies.
- `SeriesDao`: Access Series/Seasons/Episodes.
- `CategoryDao`: Manage categories.
- `EpgDao`: Query programs by channel/time.
- `SearchHistoryDao`: Recent searches.
