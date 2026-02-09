---
name: qa-testing-expert
description: Standards for Unit Testing, UI Automation (Espresso/Compose), and Performance Profiling.
version: 1.0
---

# Quality Assurance Standards

## 1. Automated Testing Strategy
* **Unit Tests:** Business logic (parsing playlists, resolving Debrid links) must have 90% coverage using `JUnit5` and `MockK`.
* **UI Tests:** Use `Espresso` (Views) or `ComposeTestRule` to verify D-Pad navigation.
* **Flaky Tests:** Any test that fails randomly must be marked `@FlakyTest` and fixed immediately.

## 2. Performance Profiling
* **LeakCanary:** MUST be enabled in Debug builds to catch memory leaks in Activities/Fragments.
* **Strict Mode:** Enable Android StrictMode to catch disk reads/writes on the main thread (causes UI lag).
* **Frame Metrics:** Log a warning if any frame takes >16ms to render (Drop below 60fps).

## 3. Crash Handling
* **Global Exception Handler:** Never let the app crash to the home screen. Catch uncaught exceptions, log them to a file, and show a polite "Restart App" dialog.