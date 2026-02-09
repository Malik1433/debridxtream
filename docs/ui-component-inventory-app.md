# UI Component Inventory

## Structure
The UI is organized by feature in `com.tvonnet.debridxtreamiptv.ui`.

## Component Catalog

### Core
- **MainActivity**: Single Activity host for the application.
- **LoginFragment**: Entry point for user authentication (Xtream Credentials).
- **HomeShellFragment**: Main dashboard container.

### Live TV (`ui.live`)
- **LiveFragment**: Live TV channel browser.
- **LivePlayerFragment**: Playback interface for live streams.
- **CategoriesFragment**: Category selection.

### VOD - Movies (`ui.vod`)
- **VodFragment**: Movie browser (Grid).
- **VodDetailsFragment**: Movie metadata/actions.
- **VodPlayerFragment**: Movie playback.

### Series (`ui.series`)
- **SeriesFragment**: Series browser.
- **SeriesDetailsFragment**: Seasons/Episodes list.
- **EpisodePlayerFragment**: Episode playback.

### EPG (`ui.epg`)
- **EpgFragment**: Grid guide view.
- **EpgDetailsBottomSheet**: Program info.

### Search (`ui.search`)
- **SearchFragment**: Global search interface (Live/VOD/Series).
- **SearchResultsAdapter**: Handling mixed results.

### Settings (`ui.settings`)
- **SettingsFragment**: App configuration (Parental control, Player settings, Stream format).

### Compose (`ui.compose`)
- Contains experimental/hybrid Compose components (likely verified in `build.gradle` dependencies).

## Patterns
- **MVVM**: usage of ViewModels for every Fragment.
- **ViewBinding**: Primary method for XML layout inflation.
- **Navigation Component**: Likely used for routing between Fragments (implied by Single Activity).
