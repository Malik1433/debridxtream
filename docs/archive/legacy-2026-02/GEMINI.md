# Gemini Code Assistant Context

This document provides a comprehensive overview of the **DebridXtreamIPTV** project to guide the Gemini code assistant.

## 1. Project Overview

**DebridXtreamIPTV** is a feature-rich IPTV (Internet Protocol Television) and VOD (Video on Demand) player designed for the Android TV platform. It is built using modern Android development practices and leverages the Xtream Codes API for content delivery.

The application allows users to log in with their Xtream Codes provider credentials to access live TV channels, movies, and TV series. It features a sophisticated, performance-oriented architecture designed to handle large amounts of data efficiently.

### Key Features:
-   **Content Streaming:** Live TV, VOD, and TV Series playback.
-   **Xtream Codes Integration:** Authenticates and fetches content using the Xtream Codes API.
-   **EPG (Electronic Program Guide):** Fetches, parses, and displays EPG data, with support for background synchronization.
-   **High-Performance UI:** Utilizes `Paging3` for smooth scrolling through thousands of items and a multi-level caching strategy for near-instant loading.
-   **Modern Architecture:** Built on an MVVM (Model-View-ViewModel) pattern with Hilt for dependency injection.
-   **Core Functionality:** Includes global search, a favorites system, and user-configurable settings.

---

## 2. Architecture and Technology

The project is written entirely in **Kotlin** and follows Google-recommended best practices for modern Android development.

-   **Architecture:**
    -   **MVVM (Model-View-ViewModel):** Separates UI from business logic.
    -   **Repository Pattern:** Provides a single source of truth for data, abstracting away data sources (network, cache, database).
    -   **Dependency Injection:** Uses **Hilt** to manage dependencies and promote modularity and testability.
    -   **Reactive UI:** Employs **Kotlin Coroutines** and **StateFlow** for managing asynchronous operations and UI state.

-   **Technology Stack:**
    -   **Core:** Kotlin, AndroidX Libraries (Activity, Fragment, Lifecycle).
    -   **UI:** ViewBinding, RecyclerView, ConstraintLayout, Material Design Components.
    -   **Networking:** **Retrofit** for type-safe HTTP requests to the Xtream Codes API, with **OkHttp** for interceptors and caching.
    -   **Data Persistence:**
        -   **Room:** For robust local database storage (caching IPTV data, EPG, favorites).
        -   **SharedPreferences:** For user settings and credentials.
    -   **Asynchronous Processing:**
        -   **Kotlin Coroutines:** For managing background threads and async tasks.
        -   **WorkManager:** For deferrable background tasks like periodic EPG synchronization.
    -   **Performance:**
        -   **Paging 3:** For efficiently loading and displaying large datasets in RecyclerView.
        -   **Multi-Level Caching:** A custom caching strategy (Memory -> Room DB -> HTTP Cache -> Network).
    -   **Media Playback:** **Media3 (ExoPlayer)** for robust video playback.
    -   **Image Loading:** **Glide** for efficient loading and caching of images (e.g., channel logos, movie posters).
    -   **Testing:** JUnit, MockK, Turbine, Robolectric.

---

## 3. Building and Running

The project is managed and built using Gradle.

### Common Commands

Use the `gradlew` wrapper for all commands.

-   **Clean and Build Debug APK:**
    ```bash
    ./gradlew clean assembleDebug
    ```

-   **Run Unit Tests:**
    ```bash
    ./gradlew test
    ```

-   **Install Debug APK on a Connected Device:**
    *First, ensure the device is connected via ADB (e.g., `adb connect <device_ip>`).*
    ```bash
    ./gradlew installDebug
    ```
    *Alternatively, use adb directly:*
    ```bash
    adb install -r app/build/outputs/apk/debug/app-debug.apk
    ```

-   **Launch the App:**
    ```bash
    adb shell am start -n com.tvonnet.debridxtreamiptv/.ui.MainActivity
    ```

-   **View Logs:**
    *It's helpful to filter logs by a specific tag used in the project.*
    ```bash
    adb logcat | grep -E "DebridXtream|Performance"
    ```

---

## 4. Development Conventions

The codebase demonstrates a strong adherence to modern, high-quality development practices.

-   **State Management:** UI state is exposed from ViewModels using `StateFlow`, and UI components observe this flow to update themselves. User actions are sent to the ViewModel as events.
-   **Error Handling:** Network and data operations are wrapped in a `Result` class, and the UI provides graceful fallbacks and error messages.
-   **Code Style:** Standard Kotlin coding conventions are followed.
-   **Documentation:** The project contains extensive internal and external documentation, including detailed weekly progress reports, implementation roadmaps, and technical release notes. This documentation is a valuable source of context.
-   **Modularity:** Features are organized into their own UI packages (e.g., `ui.live`, `ui.vod`, `ui.search`), each with its own Fragment and ViewModel.
-   **Testing:** The setup indicates a commitment to unit testing ViewModels and repositories.
