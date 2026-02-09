# Android TV IPTV App - Development Reference

## 📱 Project Overview

This document provides a complete reference for building a **native Android TV IPTV app in Kotlin** based on the web prototype structure.

**Target Device:** Android TV Boxes  
**Language:** Kotlin  
**UI Framework:** Jetpack Compose (recommended for TV)  
**Architecture:** MVVM with Repository Pattern

---

## 🎯 App Structure & Screens

### Screen 1: Main Landing Screen
```
┌─────────────────┬──────────────────────────┬────────────────────────┐
│                 │                          │                        │
│   SIDEBAR       │   CHANNEL LIST           │   PLAYER PREVIEW       │
│   (25% width)   │   (35% width)            │   (40% width)          │
│                 │                          │                        │
│  🇮🇹 Italy      │  ┌─────────────────────┐ │  ┌──────────────────┐  │
│  🇺🇦 Ukraine    │  │ 📺 Nat Geo Wild HD  │ │  │   THUMBNAIL      │  │
│  🇧🇷 Brazil     │  │ +8.2M Views [HD][E] │ │  │                  │  │
│  🇩🇪 Germany    │  └─────────────────────┘ │  │                  │  │
│  🇺🇸 USA        │                          │  │  Now Playing ↓   │  │
│  🇫🇷 France     │  ┌─────────────────────┐ │  │  [PLAY] [VOL]    │  │
│  🇵🇹 Portugal   │  │ 📺 Disney Channel   │ │  └──────────────────┘  │
│  🇿🇦 South Afr  │  │ 850K Views [4K][E]$ │ │                        │
│  🇨🇳 China      │  └─────────────────────┘ │                        │
│                 │                          │                        │
│ Focus: YELLOW   │  Focus: YELLOW GLOW      │  (Responsive Panel)    │
│ Ring            │  (UP/DOWN D-pad nav)     │                        │
│                 │                          │                        │
└─────────────────┴──────────────────────────┴────────────────────────┘
```

---

## 📊 Data Models

### Country Model
```kotlin
data class Country(
    val id: String,           // "italy", "usa", "brazil", etc.
    val name: String,         // "Italy", "United States", etc.
    val flag: String,         // "🇮🇹", "🇺🇸", etc. (emoji)
    val channels: Int         // 52, 147, 64, etc.
)
```

**Countries List (9 total):**
- Italy: 52 channels
- Ukraine: 38 channels
- Brazil: 64 channels
- Germany: 45 channels
- United States: 147 channels
- France: 41 channels
- Portugal: 33 channels
- South Africa: 29 channels
- China: 156 channels

### Show/Channel Model
```kotlin
data class Show(
    val id: String,                    // "1", "2", "3", etc.
    val name: String,                  // "Nat Geo Wild HD", "Disney Channel"
    val channel: String,               // "National Geographic", "The Walt Disney Company"
    val logo: String,                  // Display text: "NAT GEO WILD HD"
    val views: String,                 // "+8.2M", "850K", "12.5M"
    val quality: List<String>,         // ["HD", "EPG"] or ["4K", "EPG", "$"]
    val isPremium: Boolean? = null,    // For premium channels (marked with $)
    val favorite: Boolean? = null,     // Marked with ⭐ star
    val description: String? = null,   // "Explore the wild world..."
    val currentlyPlaying: String? = null, // "Animals and Nature"
    val progress: Int? = null,         // 0-100 (video progress %)
    val thumbnail: String? = null      // Unsplash image URLs
)
```

**Channels (8 total):**
1. **Nat Geo Wild HD** - Views: +8.2M, Quality: [HD, EPG]
2. **Disney Channel** - Views: 850K, Quality: [4K, EPG, $], Favorite: ⭐
3. **HBO Family** - Views: 1.7M, Quality: [HD, EPG]
4. **Netflix Original** - Views: 12.5M, Quality: [4K, HD, EPG], Favorite: ⭐
5. **Discovery Channel** - Views: 3.2M, Quality: [HD, EPG]
6. **BBC News** - Views: 5.4M, Quality: [HD]
7. **ESPN Sports** - Views: 7.8M, Quality: [4K, EPG], Favorite: ⭐
8. **Fashion TV HD** - Views: 2.1M, Quality: [HD, EPG]

---

## 🎮 Navigation System

### Remote Control Mapping

| Button | Action |
|--------|--------|
| ⬅️ Left | Navigate between panels (Sidebar ↔ Channels ↔ Player) |
| ➡️ Right | Navigate between panels (Sidebar ↔ Channels ↔ Player) |
| ⬆️ Up | Scroll up in current panel (Countries or Channels) |
| ⬇️ Down | Scroll down in current panel (Countries or Channels) |
| ✓ Enter/Select | Play channel / Select country |
| ⏮️ Back | Exit or go back (optional) |

### Focus States

**Three Focus Zones:**
1. **Sidebar (Countries)** - Yellow ring when focused
2. **Content Area (Channels)** - Yellow glow when focused
3. **Player Panel** - Can be navigated to with left/right arrows

**Visual Indicators:**
- **Selected:** Yellow glow effect + ring (4-6dp stroke)
- **Hovered:** Slight brightness increase
- **Default:** Subtle border

---

## 🎨 Design System

### Color Palette (Dark Theme)

| Token | HSL Value | Usage |
|-------|-----------|-------|
| Primary | hsl(222.2 47.4% 11.2%) | Primary brand color |
| Primary Foreground | hsl(210 40% 98%) | Text on primary |
| Secondary | hsl(217.2 32.6% 17.5%) | Secondary elements |
| Background | hsl(220 13% 7%) | App background |
| Foreground | hsl(210 40% 98%) | Primary text |
| Card | hsl(217.2 32.6% 17.5%) | Card backgrounds |
| Border | hsl(217.2 32.6% 17.5%) | Borders |
| Accent (Focus) | hsl(47.9 100% 50.4%) | **Yellow focus indicator** |
| Destructive | hsl(0 84.2% 60.2%) | Errors/warnings |

### Quality Badge Colors

| Quality | Background | Text Color |
|---------|-----------|-----------|
| 4K | Red-900/80 (rgba) | Red-100 |
| HD | Blue-900/80 (rgba) | Blue-100 |
| EPG | Slate-700/80 (rgba) | Slate-100 |
| $ (Premium) | Yellow-900/80 (rgba) | Yellow-100 |

### Typography

| Element | Font Size | Font Weight | Notes |
|---------|-----------|-------------|-------|
| Channel Name | 28sp | Bold (700) | Main title in card |
| Views Text | 18sp | Normal (400) | Secondary text |
| Country Name | 18sp | Bold (700) | In sidebar |
| Channel Count | 14sp | Normal (400) | Subtitle in sidebar |
| Badge Text | 14sp | Bold (700) | Quality badges |
| Instruction Text | 16sp | Normal (400) | Navigation hints |

### Spacing & Sizes

| Component | Size | Notes |
|-----------|------|-------|
| Channel Card Height | 144dp | Large TV-friendly size |
| Channel Icon | 64dp | Large emoji icon |
| Country Card Padding | 24dp (6 * 4dp) | Generous padding for TV |
| Gap Between Cards | 16dp (4 * 4dp) | Space-y-4 equivalent |
| Border Radius (Card) | 16dp | rounded-2xl equivalent |
| Focus Ring Stroke | 4-6dp | Yellow outline |
| Play Button | 128dp × 128dp | Large tap target |
| Control Buttons | 64dp × 64dp | Volume, Settings |

### Shadows

| Type | Value | Usage |
|------|-------|-------|
| Card Hover | 0 10px 15px -3px rgba(0,0,0,0.3) | On card hover |
| Focus Glow | 0 0 40px rgba(250, 204, 21, 0.5) | Yellow focus ring |
| Button Hover | 0 20px 25px rgba(59, 130, 246, 0.3) | Blue on play button |

---

## 🏗️ Android TV Implementation Guide

### 1. Project Setup

**Build.gradle (Project)**
```gradle
buildscript {
    ext {
        compose_version = '1.5.0'
        kotlin_version = '1.9.0'
    }
}
```

**Build.gradle (App)**
```gradle
android {
    compileSdk 34
    
    defaultConfig {
        applicationId "com.example.iptvapp"
        minSdk 21  // Android TV support
        targetSdk 34
    }
}

dependencies {
    // Jetpack Compose
    implementation "androidx.compose.ui:ui:$compose_version"
    implementation "androidx.compose.material3:material3:1.1.0"
    implementation "androidx.compose.foundation:foundation:$compose_version"
    implementation "androidx.tv.compose:tv-foundation:1.0.0-alpha10"
    implementation "androidx.tv.compose:tv-material:1.0.0-alpha10"
    
    // Navigation
    implementation "androidx.navigation:navigation-compose:2.7.0"
    
    // Lifecycle
    implementation "androidx.lifecycle:lifecycle-runtime-ktx:2.6.1"
    implementation "androidx.lifecycle:lifecycle-viewmodel-compose:2.6.1"
    
    // Coil for image loading
    implementation "io.coil-kt:coil-compose:2.4.0"
}
```

### 2. Main Activity Setup

```kotlin
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.tv.compose.ExperimentalTvMaterial3Api

@OptIn(ExperimentalTvMaterial3Api::class)
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            IptvAppTheme {
                IPTVApp()
            }
        }
    }
}
```

### 3. Theme Configuration

```kotlin
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun IptvAppTheme(content: @Composable () -> Unit) {
    val colorScheme = TvColorScheme(
        primary = Color(0xFF1C2846),      // Primary color
        onPrimary = Color(0xFFFAFBFE),    // Text on primary
        secondary = Color(0xFF2D3F52),    // Secondary
        onSecondary = Color(0xFFFAFBFE),
        background = Color(0xFF0E131E),   // Background
        onBackground = Color(0xFFFAFBFE),
        surface = Color(0xFF1A2332),      // Card background
        onSurface = Color(0xFFFAFBFE),
        tertiary = Color(0xFFCACA00)      // Yellow focus indicator
    )
    
    TvMaterial3Theme(
        colorScheme = colorScheme,
        content = content
    )
}
```

### 4. Data Layer (Repository Pattern)

```kotlin
// Models
data class CountryEntity(
    val id: String,
    val name: String,
    val flag: String,
    val channels: Int
)

data class ShowEntity(
    val id: String,
    val name: String,
    val channel: String,
    val logo: String,
    val views: String,
    val quality: List<String>,
    val isPremium: Boolean = false,
    val favorite: Boolean = false,
    val description: String = "",
    val currentlyPlaying: String = "",
    val progress: Int = 0,
    val thumbnail: String = ""
)

// Repository Interface
interface IptvRepository {
    suspend fun getCountries(): List<CountryEntity>
    suspend fun getShows(countryId: String): List<ShowEntity>
    suspend fun getShow(showId: String): ShowEntity?
    suspend fun toggleFavorite(showId: String): Boolean
}

// Repository Implementation
class IptvRepositoryImpl : IptvRepository {
    override suspend fun getCountries(): List<CountryEntity> {
        // Return hardcoded or load from API
        return listOf(
            CountryEntity("italy", "Italy", "🇮🇹", 52),
            CountryEntity("ukraine", "Ukraine", "🇺🇦", 38),
            CountryEntity("brazil", "Brazil", "🇧🇷", 64),
            // ... more countries
        )
    }
    
    override suspend fun getShows(countryId: String): List<ShowEntity> {
        // Return channels for country
        return listOf(
            ShowEntity(
                id = "1",
                name = "Nat Geo Wild HD",
                channel = "National Geographic",
                logo = "NAT GEO WILD HD",
                views = "+8.2M",
                quality = listOf("HD", "EPG"),
                description = "Explore the wild world with stunning wildlife documentaries",
                currentlyPlaying = "Animals and Nature",
                progress = 65,
                thumbnail = "https://images.unsplash.com/photo-1507003211169-0a1dd7228f2d?w=800&h=600&fit=crop"
            ),
            // ... more shows
        )
    }
}
```

### 5. ViewModel

```kotlin
@HiltViewModel
class IptvViewModel @Inject constructor(
    private val repository: IptvRepository
) : ViewModel() {
    
    private val _countries = MutableStateFlow<List<CountryEntity>>(emptyList())
    val countries: StateFlow<List<CountryEntity>> = _countries
    
    private val _shows = MutableStateFlow<List<ShowEntity>>(emptyList())
    val shows: StateFlow<List<ShowEntity>> = _shows
    
    private val _selectedCountry = MutableStateFlow<String>("usa")
    val selectedCountry: StateFlow<String> = _selectedCountry
    
    private val _selectedShow = MutableStateFlow<ShowEntity?>(null)
    val selectedShow: StateFlow<ShowEntity?> = _selectedShow
    
    private val _focusZone = MutableStateFlow<FocusZone>(FocusZone.CONTENT)
    val focusZone: StateFlow<FocusZone> = _focusZone
    
    init {
        loadCountries()
    }
    
    private fun loadCountries() {
        viewModelScope.launch {
            _countries.value = repository.getCountries()
            loadShows("usa")
        }
    }
    
    fun selectCountry(countryId: String) {
        _selectedCountry.value = countryId
        loadShows(countryId)
    }
    
    fun loadShows(countryId: String) {
        viewModelScope.launch {
            _shows.value = repository.getShows(countryId)
            if (_shows.value.isNotEmpty()) {
                _selectedShow.value = _shows.value[0]
            }
        }
    }
    
    fun selectShow(show: ShowEntity) {
        _selectedShow.value = show
    }
    
    fun changeFocusZone(zone: FocusZone) {
        _focusZone.value = zone
    }
}

enum class FocusZone {
    SIDEBAR, CONTENT, PLAYER
}
```

### 6. Main Screen Composable

```kotlin
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun IPTVApp(viewModel: IptvViewModel = hiltViewModel()) {
    val countries by viewModel.countries.collectAsState()
    val shows by viewModel.shows.collectAsState()
    val selectedShow by viewModel.selectedShow.collectAsState()
    val focusZone by viewModel.focusZone.collectAsState()
    
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0E131E))
    ) {
        Row(
            modifier = Modifier.fillMaxSize()
        ) {
            // Sidebar - 25%
            SidebarSection(
                countries = countries,
                focusZone = focusZone,
                onCountrySelect = viewModel::selectCountry,
                onFocusChange = { viewModel.changeFocusZone(FocusZone.SIDEBAR) },
                modifier = Modifier.weight(0.25f)
            )
            
            // Content - 35%
            ContentSection(
                shows = shows,
                focusZone = focusZone,
                onShowSelect = viewModel::selectShow,
                onFocusChange = { viewModel.changeFocusZone(FocusZone.CONTENT) },
                modifier = Modifier.weight(0.35f)
            )
            
            // Player - 40%
            PlayerSection(
                show = selectedShow,
                focusZone = focusZone,
                onFocusChange = { viewModel.changeFocusZone(FocusZone.PLAYER) },
                modifier = Modifier.weight(0.40f)
            )
        }
    }
}
```

### 7. Sidebar Composable

```kotlin
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun SidebarSection(
    countries: List<CountryEntity>,
    focusZone: FocusZone,
    onCountrySelect: (String) -> Unit,
    onFocusChange: () -> Unit,
    modifier: Modifier = Modifier
) {
    val focusRequester = remember { FocusRequester() }
    
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF1A2332))
            .border(
                width = if (focusZone == FocusZone.SIDEBAR) 4.dp else 1.dp,
                color = if (focusZone == FocusZone.SIDEBAR) Color(0xFFCACA00) else Color(0xFF2D3F52)
            )
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
                .focusRequester(focusRequester)
                .onFocusChanged {
                    if (it.hasFocus) onFocusChange()
                },
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            items(countries.size) { index ->
                CountryCard(
                    country = countries[index],
                    onSelect = onCountrySelect,
                    isFocused = focusZone == FocusZone.SIDEBAR
                )
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun CountryCard(
    country: CountryEntity,
    onSelect: (String) -> Unit,
    isFocused: Boolean,
    modifier: Modifier = Modifier
) {
    val focusRequester = remember { FocusRequester() }
    
    TvCard(
        onClick = { onSelect(country.id) },
        modifier = modifier
            .size(width = 180.dp, height = 200.dp)
            .focusRequester(focusRequester)
            .onKeyEvent { keyEvent ->
                if (keyEvent.type == KeyEventType.KeyDown && keyEvent.key == Key.Enter) {
                    onSelect(country.id)
                    true
                } else false
            }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = country.flag,
                fontSize = 56.sp,
                modifier = Modifier.padding(bottom = 12.dp)
            )
            Text(
                text = country.name,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFFFAFBFE)
            )
            Text(
                text = "${country.channels} channels",
                fontSize = 14.sp,
                color = Color(0xFF9DB3C4),
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}
```

### 8. Channel Card Composable

```kotlin
@Optim(ExperimentalTvMaterial3Api::class)
@Composable
fun ChannelCard(
    show: ShowEntity,
    onSelect: (ShowEntity) -> Unit,
    isFocused: Boolean,
    modifier: Modifier = Modifier
) {
    TvCard(
        onClick = { onSelect(show) },
        modifier = modifier
            .fillMaxWidth()
            .height(144.dp)
            .border(
                width = if (isFocused) 4.dp else 2.dp,
                color = if (isFocused) Color(0xFFCACA00) else Color(0xFF2D3F52),
                shape = RoundedCornerShape(16.dp)
            )
            .padding(if (isFocused) 4.dp else 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Left section - Icon + Info
            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(24.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "📺",
                    fontSize = 56.sp
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = show.name,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFFAFBFE),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "${show.views} Views",
                        fontSize = 16.sp,
                        color = Color(0xFF9DB3C4)
                    )
                }
            }
            
            // Right section - Badges + Star
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                show.quality.forEach { quality ->
                    QualityBadge(quality)
                }
                if (show.favorite) {
                    Icon(
                        painter = painterResource(id = android.R.drawable.ic_dialog_info), // Or custom star
                        contentDescription = "Favorite",
                        tint = Color(0xFFCACA00),
                        modifier = Modifier.size(28.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun QualityBadge(quality: String) {
    val (bgColor, textColor) = when (quality) {
        "4K" -> Color(0xFF64220A) to Color(0xFFFFBBA3)
        "HD" -> Color(0xFF1E3C5B) to Color(0xFFA3D5FF)
        "EPG" -> Color(0xFF2D3F52) to Color(0xFFC0C0D5)
        "$" -> Color(0xFF664D00) to Color(0xFFFFD699)
        else -> Color(0xFF2D3F52) to Color(0xFFFAFBFE)
    }
    
    Box(
        modifier = Modifier
            .background(bgColor, shape = RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Text(
            text = quality,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            color = textColor
        )
    }
}
```

### 9. Player Panel Composable

```kotlin
@Composable
fun PlayerSection(
    show: ShowEntity?,
    focusZone: FocusZone,
    onFocusChange: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF0E131E))
            .border(
                width = if (focusZone == FocusZone.PLAYER) 4.dp else 1.dp,
                color = if (focusZone == FocusZone.PLAYER) Color(0xFFCACA00) else Color(0xFF2D3F52)
            )
    ) {
        if (show == null) {
            Text(
                text = "Select a channel to preview",
                modifier = Modifier.align(Alignment.Center),
                fontSize = 24.sp,
                color = Color(0xFF9DB3C4)
            )
        } else {
            Column(modifier = Modifier.fillMaxSize()) {
                // Video Thumbnail
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .background(Color.Black)
                ) {
                    // Load image with Coil
                    AsyncImage(
                        model = show.thumbnail,
                        contentDescription = show.name,
                        modifier = Modifier
                            .fillMaxSize()
                            .alpha(0.6f),
                        contentScale = ContentScale.Crop
                    )
                    
                    // Play button
                    Button(
                        onClick = { /* Play action */ },
                        modifier = Modifier
                            .size(128.dp)
                            .align(Alignment.Center)
                    ) {
                        Icon(
                            painter = painterResource(id = android.R.drawable.ic_media_play),
                            contentDescription = "Play",
                            modifier = Modifier.size(64.dp)
                        )
                    }
                }
                
                // Info section
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF1A2332))
                        .padding(32.dp)
                ) {
                    Text(
                        text = show.currentlyPlaying ?: show.name,
                        fontSize = 36.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFFAFBFE)
                    )
                    Text(
                        text = show.description,
                        fontSize = 16.sp,
                        color = Color(0xFF9DB3C4),
                        modifier = Modifier.padding(top = 16.dp)
                    )
                }
                
                // Controls
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF1A2332))
                        .padding(32.dp),
                    horizontalArrangement = Arrangement.spacedBy(20.dp)
                ) {
                    Button(onClick = { /* Play */ }, modifier = Modifier.size(64.dp)) {
                        Icon(
                            painter = painterResource(id = android.R.drawable.ic_media_play),
                            contentDescription = "Play"
                        )
                    }
                    Button(onClick = { /* Volume */ }, modifier = Modifier.size(64.dp)) {
                        Icon(
                            painter = painterResource(id = android.R.drawable.ic_media_pause),
                            contentDescription = "Volume"
                        )
                    }
                }
            }
        }
    }
}
```

### 10. Remote Control Key Handling

```kotlin
@Composable
fun rememberKeyEventHandler(
    viewModel: IptvViewModel,
    shows: List<ShowEntity>
): (KeyEvent) -> Boolean = { keyEvent ->
    when {
        keyEvent.type == KeyEventType.KeyDown && keyEvent.key == Key.DirectionLeft -> {
            viewModel.changeFocusZone(
                when (viewModel.focusZone.value) {
                    FocusZone.CONTENT -> FocusZone.SIDEBAR
                    FocusZone.PLAYER -> FocusZone.CONTENT
                    else -> viewModel.focusZone.value
                }
            )
            true
        }
        keyEvent.type == KeyEventType.KeyDown && keyEvent.key == Key.DirectionRight -> {
            viewModel.changeFocusZone(
                when (viewModel.focusZone.value) {
                    FocusZone.SIDEBAR -> FocusZone.CONTENT
                    FocusZone.CONTENT -> FocusZone.PLAYER
                    else -> viewModel.focusZone.value
                }
            )
            true
        }
        keyEvent.type == KeyEventType.KeyDown && keyEvent.key == Key.DirectionUp -> {
            // Handle up navigation in LazyColumn
            true
        }
        keyEvent.type == KeyEventType.KeyDown && keyEvent.key == Key.DirectionDown -> {
            // Handle down navigation in LazyColumn
            true
        }
        keyEvent.type == KeyEventType.KeyDown && keyEvent.key == Key.Enter -> {
            // Handle selection
            true
        }
        else -> false
    }
}
```

### 11. AndroidManifest.xml

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    package="com.example.iptvapp">

    <!-- Android TV Features -->
    <uses-feature
        android:name="android.hardware.touchscreen"
        android:required="false" />
    <uses-feature
        android:name="android.software.leanback"
        android:required="false" />

    <application>
        <activity
            android:name=".MainActivity"
            android:exported="true"
            android:theme="@style/Theme.IptvApp">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
                <category android:name="android.intent.category.LEANBACK_LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
</manifest>
```

---

## 📚 File Structure (Kotlin)

```
app/
├── src/
│   ├── main/
│   │   ├── java/com/example/iptvapp/
│   │   │   ├── MainActivity.kt
│   │   │   ├── IptvApp.kt
│   │   │   ├── data/
│   │   │   │   ├── model/
│   │   │   │   │   ├── CountryEntity.kt
│   │   │   │   │   ├── ShowEntity.kt
│   │   │   │   │   └── FocusZone.kt
│   │   │   │   ├── repository/
│   │   │   │   │   ├── IptvRepository.kt
│   │   │   │   │   └── IptvRepositoryImpl.kt
│   │   │   │   └── di/
│   │   │   │       └── Module.kt
│   │   │   ├── ui/
│   │   │   │   ├── theme/
│   │   │   │   │   ├── Color.kt
│   │   │   │   │   ├── Theme.kt
│   │   │   │   │   └── Type.kt
│   │   │   │   ├── screens/
│   │   │   │   │   └── MainScreen.kt
│   │   │   │   └── components/
│   │   │   │       ├── SidebarSection.kt
│   │   │   │       ├── ContentSection.kt
│   │   │   │       ├── PlayerSection.kt
│   │   │   │       ├── CountryCard.kt
│   │   │   │       ├── ChannelCard.kt
│   │   │   │       ├── QualityBadge.kt
│   │   │   │       └── PlayerControls.kt
│   │   │   ├── viewmodel/
│   │   │   │   └── IptvViewModel.kt
│   │   │   └── util/
│   │   │       ├── Navigation.kt
│   │   │       └── KeyEventHandler.kt
│   │   └── res/
│   │       ├── drawable/
│   │       ├── mipmap/
│   │       └── values/
│   │           ├── colors.xml
│   │           ├── strings.xml
│   │           └── themes.xml
│   └── test/
└── build.gradle
```

---

## 🔌 Integration Points

### Loading Real Channel Data

**Option 1: Local JSON**
```kotlin
// Load from assets/channels.json
val channelsJson = context.assets.open("channels.json").bufferedReader().use { it.readText() }
val channels = Json.decodeFromString<List<ShowEntity>>(channelsJson)
```

**Option 2: Remote API**
```kotlin
interface IptvApi {
    @GET("/api/countries")
    suspend fun getCountries(): List<CountryEntity>
    
    @GET("/api/shows/{countryId}")
    suspend fun getShows(@Path("countryId") countryId: String): List<ShowEntity>
    
    @GET("/api/stream/{showId}")
    suspend fun getStreamUrl(@Path("showId") showId: String): StreamUrlResponse
}
```

**Option 3: Database (Room)**
```kotlin
@Database(entities = [CountryEntity::class, ShowEntity::class], version = 1)
abstract class IptvDatabase : RoomDatabase() {
    abstract fun countryDao(): CountryDao
    abstract fun showDao(): ShowDao
}
```

---

## 📺 TV-Specific Features

### 1. D-Pad Navigation
- Use `FocusRequester` and focus management
- Implement `onKeyEvent` for arrow key handling
- Provide visual focus indicators (yellow glow)

### 2. Large Touch Targets
- Minimum 48dp for interactive elements
- 64dp recommended for TV remotes
- Cards: 144dp height for comfortable navigation

### 3. No Small Text
- Minimum 16sp font size
- 20-24sp for important text
- 28-36sp for titles

### 4. Color Contrast
- Use HSL values for consistent theming
- Maintain WCAG AA contrast ratio
- Yellow focus indicator for clarity

### 5. Remote Control Optimization
- No hover states (use focus instead)
- Clear selection indicators
- Minimize required button presses

---

## 🚀 Development Timeline

1. **Week 1:** Project setup, data models, repository pattern
2. **Week 2:** ViewModel, theme setup, basic UI
3. **Week 3:** Sidebar & country selection
4. **Week 4:** Channel list & card UI
5. **Week 5:** Player panel & video integration
6. **Week 6:** Remote control navigation & key handling
7. **Week 7:** Testing, optimization, bug fixes
8. **Week 8:** Deployment & APK signing

---

## 🎯 Key Takeaways

✅ **Use Jetpack Compose** for modern TV UI  
✅ **Implement focus-based navigation** (not touch)  
✅ **Make everything large** (buttons, text, cards)  
✅ **Use yellow for focus indicators** (not blue)  
✅ **Test on actual TV hardware** early and often  
✅ **Follow Android TV guidelines** from Google  
✅ **Handle remote control keys** explicitly  
✅ **Optimize for 16:9 aspect ratio**  

---

## 📖 References

- [Android TV Documentation](https://developer.android.com/tv)
- [Jetpack Compose for TV](https://developer.android.com/tv/ui)
- [Kotlin Documentation](https://kotlinlang.org/docs/)
- [Jetpack Compose Material3](https://developer.android.com/jetpack/androidx/releases/compose-material3)

---

**This reference guide contains all necessary information to build a native Kotlin Android TV app matching the web prototype.**
