# Kotlin Android TV App - Implementation Guide

Complete guide for implementing the IPTV app design system in your Kotlin Android project.

---

## 📦 Files You Need

Copy these files to your Kotlin project:

1. **KOTLIN_COLORS.kt** → `app/src/main/java/com/example/iptvapp/ui/theme/Color.kt`
2. **KOTLIN_THEME.kt** → `app/src/main/java/com/example/iptvapp/ui/theme/Theme.kt`
3. **KOTLIN_COMPONENTS.kt** → `app/src/main/java/com/example/iptvapp/ui/components/Components.kt`
4. **KOTLIN_DATA_MODELS.kt** → `app/src/main/java/com/example/iptvapp/data/model/Models.kt`
5. **DESIGN_SYSTEM.md** → Keep in project root for reference

---

## 🚀 Quick Start (5 Steps)

### Step 1: Add Dependencies to build.gradle

```gradle
dependencies {
    // Jetpack Compose
    implementation "androidx.compose.ui:ui:1.5.0"
    implementation "androidx.compose.material3:material3:1.1.0"
    implementation "androidx.compose.foundation:foundation:1.5.0"
    
    // TV Support
    implementation "androidx.tv.compose:tv-foundation:1.0.0-alpha10"
    implementation "androidx.tv.compose:tv-material:1.0.0-alpha10"
    
    // Image Loading
    implementation "io.coil-kt:coil-compose:2.4.0"
    
    // Navigation
    implementation "androidx.navigation:navigation-compose:2.7.0"
    
    // ViewModel & StateFlow
    implementation "androidx.lifecycle:lifecycle-viewmodel-compose:2.6.1"
    
    // Kotlin Coroutines
    implementation "org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.1"
}
```

### Step 2: Copy the Files

```
app/src/main/java/com/example/iptvapp/
├── ui/
│   ├── theme/
│   │   ├── Color.kt           (from KOTLIN_COLORS.kt)
│   │   ├── Theme.kt           (from KOTLIN_THEME.kt)
│   │   └── Type.kt            (optional, for additional typography)
│   └── components/
│       └── Components.kt       (from KOTLIN_COMPONENTS.kt)
├── data/
│   └── model/
│       └── Models.kt          (from KOTLIN_DATA_MODELS.kt)
└── MainActivity.kt
```

### Step 3: Update MainActivity

```kotlin
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import com.example.iptvapp.ui.theme.IPTVAppTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            IPTVAppTheme {
                // Your app screens here
                MainScreen()
            }
        }
    }
}
```

### Step 4: Create ViewModel

```kotlin
package com.example.iptvapp.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import com.example.iptvapp.data.model.*

class IPTVViewModel : ViewModel() {
    private val _countries = MutableStateFlow<List<Country>>(emptyList())
    val countries: StateFlow<List<Country>> = _countries
    
    private val _shows = MutableStateFlow<List<Show>>(emptyList())
    val shows: StateFlow<List<Show>> = _shows
    
    private val _selectedCountry = MutableStateFlow("usa")
    val selectedCountry: StateFlow<String> = _selectedCountry
    
    private val _selectedShow = MutableStateFlow<Show?>(null)
    val selectedShow: StateFlow<Show?> = _selectedShow
    
    private val _focusZone = MutableStateFlow(FocusZone.CONTENT)
    val focusZone: StateFlow<FocusZone> = _focusZone
    
    private val _settings = MutableStateFlow(UserSettings())
    val settings: StateFlow<UserSettings> = _settings
    
    init {\n        loadCountries()\n    }\n    \n    private fun loadCountries() {\n        viewModelScope.launch {\n            _countries.value = CountryData.countries\n            loadShows("usa")\n        }\n    }\n    \n    fun selectCountry(countryId: String) {\n        _selectedCountry.value = countryId\n        loadShows(countryId)\n    }\n    \n    private fun loadShows(countryId: String) {\n        viewModelScope.launch {\n            _shows.value = ShowData.shows\n            if (_shows.value.isNotEmpty()) {\n                _selectedShow.value = _shows.value[0]\n            }\n        }\n    }\n    \n    fun selectShow(show: Show) {\n        _selectedShow.value = show\n    }\n    \n    fun changeFocusZone(zone: FocusZone) {\n        _focusZone.value = zone\n    }\n    \n    fun updateSettings(newSettings: UserSettings) {\n        _settings.value = newSettings\n    }\n}\n```

### Step 5: Create Main Screen

```kotlin
package com.example.iptvapp.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable\nimport androidx.compose.runtime.collectAsState\nimport androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.iptvapp.ui.theme.Background
import com.example.iptvapp.viewmodel.IPTVViewModel
import com.example.iptvapp.ui.components.*
import com.example.iptvapp.data.model.FocusZone

@Composable
fun MainScreen(\n    viewModel: IPTVViewModel = viewModel()\n) {\n    val countries by viewModel.countries.collectAsState()\n    val shows by viewModel.shows.collectAsState()\n    val selectedShow by viewModel.selectedShow.collectAsState()\n    val focusZone by viewModel.focusZone.collectAsState()\n    val selectedCountry by viewModel.selectedCountry.collectAsState()\n    \n    Box(\n        modifier = Modifier\n            .fillMaxSize()\n            .background(Background)\n    ) {\n        Row(\n            modifier = Modifier.fillMaxSize()\n        ) {\n            // Sidebar (25%)\n            Box(\n                modifier = Modifier\n                    .fillMaxHeight()\n                    .fillMaxWidth(0.25f)\n                    .background(Color(0xFF0F172A))\n            ) {\n                SidebarContent(\n                    countries = countries,\n                    selectedCountry = selectedCountry,\n                    isFocused = focusZone == FocusZone.SIDEBAR,\n                    onSelectCountry = viewModel::selectCountry,\n                    onFocusChange = { viewModel.changeFocusZone(FocusZone.SIDEBAR) }\n                )\n            }\n            \n            // Content Area (35%)\n            Box(\n                modifier = Modifier\n                    .fillMaxHeight()\n                    .fillMaxWidth(0.35f / 0.75f)\n                    .background(Color(0xFF1A2332))\n            ) {\n                ContentAreaScreen(\n                    shows = shows,\n                    selectedShow = selectedShow,\n                    isFocused = focusZone == FocusZone.CONTENT,\n                    onSelectShow = viewModel::selectShow,\n                    onFocusChange = { viewModel.changeFocusZone(FocusZone.CONTENT) }\n                )\n            }\n            \n            // Player (40%)\n            Box(\n                modifier = Modifier\n                    .fillMaxHeight()\n                    .weight(1f)\n                    .background(Color(0xFF0E131E))\n            ) {\n                PlayerScreen(\n                    show = selectedShow,\n                    isFocused = focusZone == FocusZone.PLAYER,\n                    onFocusChange = { viewModel.changeFocusZone(FocusZone.PLAYER) }\n                )\n            }\n        }\n    }\n}\n\n@Composable\nfun SidebarContent(\n    countries: List<Country>,\n    selectedCountry: String,\n    isFocused: Boolean,\n    onSelectCountry: (String) -> Unit,\n    onFocusChange: () -> Unit\n) {\n    LazyColumn(\n        modifier = Modifier\n            .fillMaxSize()\n            .padding(16.dp),\n        verticalArrangement = Arrangement.spacedBy(16.dp)\n    ) {\n        items(countries.size) { index ->\n            CountryCard(\n                country = countries[index],\n                isSelected = countries[index].id == selectedCountry,\n                isFocused = isFocused && index == 0,  // Simplified\n                onSelect = onSelectCountry\n            )\n        }\n    }\n}\n\n@Composable\nfun ContentAreaScreen(\n    shows: List<Show>,\n    selectedShow: Show?,\n    isFocused: Boolean,\n    onSelectShow: (Show) -> Unit,\n    onFocusChange: () -> Unit\n) {\n    Column(\n        modifier = Modifier.fillMaxSize()\n    ) {\n        Text(\n            text = \"Live Channels\",\n            fontSize = 24.sp,\n            fontWeight = FontWeight.Bold,\n            color = Foreground,\n            modifier = Modifier.padding(16.dp)\n        )\n        \n        ChannelList(\n            shows = shows,\n            focusedIndex = shows.indexOfFirst { it.id == selectedShow?.id }.coerceAtLeast(0),\n            onSelect = onSelectShow\n        )\n    }\n}\n\n@Composable\nfun PlayerScreen(\n    show: Show?,\n    isFocused: Boolean,\n    onFocusChange: () -> Unit\n) {\n    if (show == null) {\n        Box(\n            modifier = Modifier\n                .fillMaxSize()\n                .background(Background),\n            contentAlignment = Alignment.Center\n        ) {\n            Text(\n                text = \"Select a channel to preview\",\n                color = Slate400,\n                fontSize = 24.sp\n            )\n        }\n    } else {\n        Column(\n            modifier = Modifier.fillMaxSize()\n        ) {\n            // Thumbnail\n            if (show.thumbnail.isNotEmpty()) {\n                AsyncImage(\n                    model = show.thumbnail,\n                    contentDescription = show.name,\n                    modifier = Modifier\n                        .fillMaxWidth()\n                        .height(300.dp),\n                    contentScale = ContentScale.Crop\n                )\n            }\n            \n            // Info\n            Column(\n                modifier = Modifier\n                    .fillMaxSize()\n                    .padding(32.dp)\n            ) {\n                Text(\n                    text = show.currentlyPlaying.ifEmpty { show.name },\n                    fontSize = 28.sp,\n                    fontWeight = FontWeight.Bold,\n                    color = Foreground\n                )\n                Text(\n                    text = show.description,\n                    fontSize = 16.sp,\n                    color = Slate400,\n                    modifier = Modifier.padding(top = 16.dp)\n                )\n            }\n        }\n    }\n}\n```

---

## 🎨 Using Colors in Your Code

```kotlin
// Direct color usage\nText(\n    text = \"Channel Name\",\n    color = Foreground,           // #FAFBFE\n    modifier = Modifier\n        .background(Card)          // #1A2332\n        .border(1.dp, Border)      // #1E2D3D\n)\n\n// Conditional colors\nBox(\n    modifier = Modifier.background(\n        color = if (isFocused) AccentYellow else Slate800\n    )\n)\n\n// Quality badge\nQualityBadge(quality = \"4K\")  // Auto-colors based on type\n```

---

## 🎮 Remote Navigation Implementation

```kotlin
@Composable\nfun MainScreenWithNavigation() {\n    val viewModel: IPTVViewModel = viewModel()\n    val focusZone by viewModel.focusZone.collectAsState()\n    \n    LaunchedEffect(Unit) {\n        val keyEventDispatcher = object : KeyEventHandler {\n            override fun onKeyDown(keyCode: Int, keyEvent: KeyEvent): Boolean {\n                return when (keyCode) {\n                    KeyEvent.KEYCODE_DPAD_LEFT -> {\n                        when (focusZone) {\n                            FocusZone.CONTENT -> viewModel.changeFocusZone(FocusZone.SIDEBAR)\n                            FocusZone.PLAYER -> viewModel.changeFocusZone(FocusZone.CONTENT)\n                            else -> false\n                        }\n                        true\n                    }\n                    KeyEvent.KEYCODE_DPAD_RIGHT -> {\n                        when (focusZone) {\n                            FocusZone.SIDEBAR -> viewModel.changeFocusZone(FocusZone.CONTENT)\n                            FocusZone.CONTENT -> viewModel.changeFocusZone(FocusZone.PLAYER)\n                            else -> false\n                        }\n                        true\n                    }\n                    else -> false\n                }\n            }\n        }\n    }\n}\n```

---

## 📐 Spacing & Layout Tips

Always use the spacing system:

```kotlin\n// ✅ CORRECT - Using spacing system\nColumn(\n    modifier = Modifier.padding(LocalSpacing.current.lg),  // 16.dp\n    verticalArrangement = Arrangement.spacedBy(LocalSpacing.current.md)  // 12.dp\n) {\n    // Content\n}\n\n// ❌ WRONG - Hardcoded values\nColumn(\n    modifier = Modifier.padding(15.dp),\n    verticalArrangement = Arrangement.spacedBy(13.dp)\n) {\n    // Content\n}\n```

---

## 🎯 Focus Management Best Practices

1. **Always provide focus indicators** - Yellow (#CACA00) ring with 4dp width
2. **Use FocusZone enum** - Track which section has focus
3. **Support all remote keys** - D-pad, Enter, Back
4. **No hover states on TV** - Use focus instead
5. **Make touch targets 48dp minimum** - 64dp recommended

---

## 📱 Testing on Android TV

```bash
# Connect to Android TV emulator or device
adb connect <device-ip>:5555

# Install app
./gradlew installDebug

# Test D-pad navigation
adb shell input keyevent KEYCODE_DPAD_LEFT
adb shell input keyevent KEYCODE_DPAD_RIGHT
adb shell input keyevent KEYCODE_DPAD_UP
adb shell input keyevent KEYCODE_DPAD_DOWN
adb shell input keyevent KEYCODE_ENTER
```

---

## 🔍 Debugging Focus Issues

```kotlin
// Add this composable to see focus zones
@Composable\nfun DebugFocusOverlay(focusZone: FocusZone) {\n    Box(\n        modifier = Modifier\n            .fillMaxSize()\n            .border(4.dp, AccentYellow)\n    )\n    Text(\n        text = "Focus: $focusZone\",\n        modifier = Modifier.padding(16.dp),\n        color = AccentYellow\n    )\n}\n```

---

## 📊 Performance Optimization

1. **Use LazyColumn for lists** - Never use Column for large lists
2. **Memoize composables** - Use @Composable efficiently
3. **StateFlow for state** - Avoid mutable state
4. **Coil for images** - Efficient image loading
5. **Profile with Compose Inspector** - Android Studio > Layout Inspector

---

## ✅ Implementation Checklist

- [ ] Copy all Kotlin files to your project
- [ ] Add Compose dependencies to build.gradle
- [ ] Update MainActivity to use IPTVAppTheme
- [ ] Create ViewModel with StateFlow
- [ ] Build main screens (Sidebar, Content, Player)
- [ ] Implement remote key navigation
- [ ] Test on Android TV emulator
- [ ] Verify colors match design
- [ ] Test focus indicators
- [ ] Test all remote buttons
- [ ] Optimize image loading
- [ ] Check text readability at 10ft
- [ ] Verify WCAG contrast ratios
- [ ] Profile performance
- [ ] Deploy to TV hardware

---

## 🎓 Next Steps

1. **Read DESIGN_SYSTEM.md** - Understand all design tokens
2. **Study Component.kt** - See how components are built
3. **Review MainActivity** - See how to structure your app
4. **Test on TV hardware** - Check real-world appearance
5. **Iterate with feedback** - Refine based on user testing

---

## 💡 Common Issues & Solutions

### Issue: Focus ring not visible
**Solution:** Ensure AccentYellow (#CACA00) is used for focus, with 4dp border width

### Issue: Text too small on TV
**Solution:** Use minimum 16sp, 20sp for body, 28sp+ for headings

### Issue: Remote D-pad not working
**Solution:** Override onKeyDown in Activity, call viewModel functions

### Issue: Images loading slowly
**Solution:** Use Coil with proper cache configuration and image sizing

### Issue: App crashes on TV
**Solution:** Test on actual TV hardware early, not just emulator

---

## 📚 Resources

- [Android TV Developer Guide](https://developer.android.com/tv)
- [Jetpack Compose TV](https://developer.android.com/jetpack/androidx/releases/tv)
- [Compose Material3](https://m3.material.io/)
- [Kotlin Documentation](https://kotlinlang.org/docs/)

---

## 🤝 Support

For issues or questions:
1. Check DESIGN_SYSTEM.md for color/spacing specs
2. Review component examples in KOTLIN_COMPONENTS.kt
3. Test on actual Android TV hardware
4. Consult Android TV documentation
5. Use Android Studio Layout Inspector for debugging

---

**Good luck with your IPTV app! 🚀📺**
"
}
