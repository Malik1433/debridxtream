# Source Tree Analysis

## Project Root: `app` (Mobile/TV Application)

### `src/main/java/com/tvonnet/debridxtreamiptv/`
The core Kotlin source code.

#### `data/` - Data Layer
- **`local/`**: Room Database implementation.
  - `entity/`: Database tables (Channels, VODs, etc).
  - `dao/`: Data Access Objects.
- **`remote/`**: Network Layer.
  - `XtreamApiService.kt`: Retrofit interface for Xtream Codes API.
- **`repository/`**: Repositories mediating between Local and Remote data.
- **`model/`**: Shared data classes.

#### `ui/` - Presentation Layer
- **`base/`**: Base classes (BaseFragment, BaseViewModel).
- **`home/`**: Dashboard logic.
- **`live/`**: Live TV features.
- **`vod/`**: Movie features.
- **`series/`**: TV Series features.
- **`search/`**: Search functionality.
- **`settings/`**: User preferences.
- **`compose/`**: Jetpack Compose UI components.

#### `di/` - Dependency Injection
- Hilt modules for providing Network, Database, and Repository instances.

#### `player/`
- Custom player logic wrapping `Media3` / `ExoPlayer`.

#### `util/` & `utils/`
- Extension functions, constants, and helpers.

### `src/main/res/`
- **`layout/`**: XML Layouts for Activities/Fragments.
- **`values/`**: Strings, Colors, Themes.
- **`drawable/`**: Icons and assets.

### `src/test/` & `src/androidTest/`
- Unit and UI tests (Robolectric, JUnit).
