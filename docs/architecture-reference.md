# Architecture Documentation

## Executive Summary
**DebridXtreamIPTV** is a modern Android TV application built using Kotlin and Jetpack libraries. It follows the **MVVM (Model-View-ViewModel)** architectural pattern to ensure separation of concerns and testability.

## Architectural Layers

### 1. UI Layer (Presentation)
- **Components**: Fragments, Activities, Composables.
- **Responsibility**: Render UI, handle user input, observe ViewModel state.
- **State Management**: Uses `StateFlow` exposed by ViewModels.
- **Pattern**: Single Activity (`MainActivity`) with Navigation Component.

### 2. Domain/Business Layer
- **ViewModels**: Mediate between UI and Data layers. Transform data for display.
- **Use Cases** (Implicit): Often handled directly within ViewModels or Repository extensions.

### 3. Data Layer
- **Repository**: Single source of truth. Decides whether to fetch from Local Cache or Remote Network.
- **Remote**: `XtreamApiService` (Retrofit) fetching JSON from IPTV providers.
- **Local**: `AppDatabase` (Room) caching content for offline access and performance.

### 4. Dependency Injection
- **Hilt**: Manages dependency graph.
- **Modules**: Located in `di/` package (e.g., `NetworkModule`, `DatabaseModule`).

## Key Decisions
- **Offline First**: Heavy reliance on Room to cache channels and EPG to minimize network calls and loading times.
- **Media3**: Using the latest Google media library for robust playback on TV devices.
- **Hybrid UI**: Migrating from XML to Compose allowing for modern, declarative UI while maintaining legacy stability.

## Data Flow
1. User requests "Live TV".
2. ViewModel requests data from `LiveRepository`.
3. Repository checks `Room` (Local).
   - If present & valid: Emit Local data.
   - If missing/stale: Call `XtreamApiService`, save to `Room`, then emit Local data.
4. UI collects `StateFlow` and updates RecyclerView/LazyColumn.
