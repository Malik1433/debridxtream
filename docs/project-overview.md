# Project Overview

## DebridXtreamIPTV
**Type:** Android TV Application (Monolith)
**Language:** Kotlin
**Architecture:** MVVM / Clean Architecture

## Executive Summary
DebridXtreamIPTV is a feature-rich IPTV client designed for Android TV. It integrates with Xtream Codes providers to deliver Live TV, VOD (Movies), and Series. It focuses on performance and user experience using a local caching strategy and modern Android components.

## Key Features
- **Live TV**: Categories, Stream Browser, Playback.
- **VOD**: Movies with metadata and playback.
- **Series**: Seasons and Episodes management.
- **EPG**: Electronic Program Guide integration.
- **Search**: Global search across all content types.
- **Favorites**: Local management of favorite content.

## Tech Stack
| Category | Technology |
|Data|Room (SQLite)|
|Network|Retrofit + OkHttp|
|DI|Hilt|
|Async|Coroutines + Flow|
|Player|Media3 (ExoPlayer)|
|UI|ViewBinding + Jetpack Compose|

## Documentation Index
- [Architecture](./architecture.md)
- [Development Guide](./development-guide.md)
- [API Contracts](./api-contracts-app.md)
- [Data Models](./data-models-app.md)
- [Source Tree](./source-tree-analysis.md)
- [UI Components](./ui-component-inventory-app.md)
