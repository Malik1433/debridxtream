# Android TV Project Audit - 2026-05-09

Scope: static source audit, Gradle verification, and Android TV testing strategy review for the current working tree. I used the installed Android testing skills as the audit baseline: JVM unit tests first, device/instrumented tests for TV focus/playback behavior, stable selectors, Gradle Managed Devices for CI, and isolated state for UI tests.

## Executive Summary

The project is a single-module Android TV app (`:app`) with a mature feature set: Xtream IPTV, VOD, Series V1/V2, Real-Debrid/MediaFusion, Room caching, WorkManager sync, Media3 playback, an embedded companion configuration server, and a mixed XML/ViewBinding plus Compose UI stack.

Current build health is mixed. `:app:compileDebugKotlin` passes, but `:app:testDebugUnitTest` fails with 5 failing tests out of 196. The failures are not random infrastructure failures; they expose a contract mismatch in Debrid link expiration behavior. Production code now generates a 4-hour Debrid URL expiry, while the test suite still expects roughly 23 hours.

The highest-risk areas are security around local-network companion setup and cleartext traffic, stale/failing test contracts, absent Android TV instrumented/focus tests, Room destructive migration fallback, and release hardening being disabled.

## Project Map

- Root Gradle project: `DebridXtreamIPTV`
- Android module: `:app`
- Namespace: `com.tvonnet.debridxtreamiptv`
- Application ID: `com.debridxtream.tv`
- Main launcher: `com.tvonnet.debridxtreamiptv.ui.MainActivity`
- Source files under app main Java/Kotlin tree: 235
- JVM test files: 29
- JUnit test methods discovered by `@Test`: 196
- Instrumented test source set: absent (`app/src/androidTest` does not exist)

Core subsystems:

- `data/`: legacy repository, Room, Xtream Retrofit client, cache layer, Debrid integration.
- `features/seriesv2/`: V2 sidecar Series engine with Paging/Room separation.
- `player/stabilized/`: Media3 player, EPG overlay, live zapping, next episode behavior.
- `network/`: companion pairing/config server using Ktor CIO.
- `ui/`: TV-first fragments/activities with many explicit focus paths.

## Verification Results

Ran:

- `.\gradlew.bat :app:testDebugUnitTest --no-daemon` - failed.
  - Result: 196 tests completed, 5 failed.
  - Failing class: `com.tvonnet.debridxtreamiptv.data.debrid.repository.DebridResumeEngineTest`.
  - Report path: `app/build/reports/tests/testDebugUnitTest/index.html`.

- `.\gradlew.bat :app:compileDebugKotlin --no-daemon` - passed.
  - Result: `BUILD SUCCESSFUL`.
  - 21 actionable tasks: 1 executed, 20 up-to-date.

Failing tests:

- `resume after 24 hours - triggers fresh resolution with new expiresAt`
- `resume after 48 hours - triggers fresh resolution successfully`
- `fresh resolution generates expiresAt approximately 23 hours from now`
- `full lifecycle - play, save, wait 24h, resume, re-resolve, save again`
- `series episode lifecycle - play S01E01, wait 48h, resume S01E01`

Root cause: [PlaybackResolver.kt](../app/src/main/java/com/tvonnet/debridxtreamiptv/data/debrid/repository/PlaybackResolver.kt) generates `newExpiresAt = now + 4h` for Debrid links. [DebridResumeEngineTest.kt](../app/src/test/java/com/tvonnet/debridxtreamiptv/data/debrid/repository/DebridResumeEngineTest.kt) still asserts roughly 23h. The code comment says 23h caused dead Debrid links to be served for too long, so the tests likely need to be updated to the new 4h contract unless the product requirement is actually 23h.

## Highest Priority Findings

### P0 - Unit Test Gate Is Red

Evidence:

- `:app:testDebugUnitTest` failed with 5 Debrid resume tests.
- [PlaybackResolver.kt](../app/src/main/java/com/tvonnet/debridxtreamiptv/data/debrid/repository/PlaybackResolver.kt) intentionally uses a 4-hour expiry.
- [DebridResumeEngineTest.kt](../app/src/test/java/com/tvonnet/debridxtreamiptv/data/debrid/repository/DebridResumeEngineTest.kt) still encodes a 23-hour expiry expectation.

Impact: PR/release confidence is compromised. The failure is meaningful because it documents a changed playback contract, not just an assertion typo.

Recommendation: Decide the intended Debrid TTL contract. If 4h is correct, update tests and test names/comments to 4h with a small tolerance. If 23h is still the product contract, revert or parameterize the resolver TTL and add coverage for Real-Debrid dead-link recovery.

### P0 - Companion Config Server Accepts Local-Network Writes Too Broadly

Evidence:

- [CompanionConfigServer.kt](../app/src/main/java/com/tvonnet/debridxtreamiptv/network/CompanionConfigServer.kt) binds to `0.0.0.0` on port 8085.
- It installs CORS with `anyHost()`.
- `/api/config` accepts IPTV credentials, Real-Debrid token, and MediaFusion URL, then persists them.

Impact: Any device on the same network can likely attempt configuration writes while the server is running. Validation checks IPTV credentials, but it does not appear to authenticate the caller with a pairing secret, one-time token, origin restriction, or request signature. This is high risk because the endpoint can overwrite sensitive account configuration.

Recommendation: Require a short-lived pairing code or device-bound nonce on every write; reject unauthenticated requests before parsing/storing payloads; bind only while pairing UI is active; consider binding to a specific local interface if feasible; remove `anyHost()` in favor of a narrow companion origin model where possible.

### P1 - Cleartext Traffic Is Globally Enabled

Evidence:

- [AndroidManifest.xml](../app/src/main/AndroidManifest.xml) sets `android:usesCleartextTraffic="true"`.
- [network_security_config.xml](../app/src/main/res/xml/network_security_config.xml) permits cleartext in `base-config`.
- IPTV credentials are commonly embedded in Xtream URLs.

Impact: The entire app can use HTTP, not only user-supplied IPTV hosts. This expands credential/token exposure risk and weakens defaults for companion/debrid/catalog traffic.

Recommendation: Restrict cleartext to explicit, user-configured IPTV host handling if HTTP Xtream providers must be supported. Keep Real-Debrid, TMDB, registries, Firebase, companion pages, and addon catalogs HTTPS-only. Add runtime warnings for HTTP provider URLs and never log full stream URLs containing username/password.

### P1 - IPTV Credentials Are Stored in Plain SharedPreferences

Evidence:

- [CredentialsPreferences.kt](../app/src/main/java/com/tvonnet/debridxtreamiptv/data/prefs/CredentialsPreferences.kt) stores server URL, username, and password in normal `SharedPreferences`.
- [DebridPreferences.kt](../app/src/main/java/com/tvonnet/debridxtreamiptv/data/prefs/DebridPreferences.kt) already uses `EncryptedSharedPreferences` for Debrid tokens.

Impact: The app already has a secure storage pattern, but IPTV credentials do not use it. This creates an avoidable inconsistency for sensitive data.

Recommendation: Move IPTV credentials to encrypted storage using the same pattern as `DebridPreferences`. Provide a one-time migration from `iptv_credentials` to encrypted prefs, then clear the old entries.

### P1 - Debug Real-Debrid Logging Can Expose Sensitive Response Data

Evidence:

- [RealDebridServiceFactory.kt](../app/src/main/java/com/tvonnet/debridxtreamiptv/data/debrid/api/RealDebridServiceFactory.kt) uses `HttpLoggingInterceptor.Level.BODY` in debug builds.

Impact: Debug logs may include URLs, token-related data, or response bodies from Real-Debrid flows. On Android TV/dev devices, logs often get shared during troubleshooting.

Recommendation: Downgrade to `BASIC` or redact `Authorization`, `access_token`, `refresh_token`, magnet links, unrestricted links, and full stream URLs. Prefer explicit structured debug events over full body logging.

### P1 - Room Has Migrations but Still Falls Back to Destructive Migration

Evidence:

- [AppDatabase.kt](../app/src/main/java/com/tvonnet/debridxtreamiptv/data/local/AppDatabase.kt) is version 10.
- [AppModule.kt](../app/src/main/java/com/tvonnet/debridxtreamiptv/di/AppModule.kt) adds migrations and then calls `.fallbackToDestructiveMigration()`.

Impact: Any missing migration path can wipe local cache, favorites, history, watch state, and Series V2 data. For a TV app, silent data loss is especially painful because remote input makes recovery slow.

Recommendation: Remove destructive fallback for release builds. If it is useful during development, gate it by `BuildConfig.DEBUG`. Add migration tests that create old schemas and migrate through version 10.

### P1 - No Android TV Instrumented Test Layer

Evidence:

- `app/src/androidTest` does not exist.
- `app/build.gradle` has Espresso core only, but no Compose UI test dependency, UIAutomator, orchestrator, managed devices, or TV-focused test tasks.
- The app has extensive DPAD/focus code and XML `nextFocus*` paths.

Impact: The riskiest Android TV behavior is not covered: DPAD navigation, focus restoration, login/companion flows, player overlay controls, live zapping, back behavior, and next episode prompts. JVM tests cannot catch these regressions.

Recommendation: Add a small instrumented smoke suite first:

- Main launch and login screen focus lands on the expected control.
- Sidebar DPAD navigation across Home/Live/VOD/Series/Settings.
- Player DPAD center/up/down/back behavior with a fake stream URL.
- Debrid source dialog focus and filter chips.
- Companion setup screen cannot be configured without a valid pairing token.

Use UIAutomator for system-level DPAD assertions and Espresso/Compose testing for app-owned views. Add AndroidX Test Orchestrator and disable animations in Gradle test options.

## Additional Findings

### Release Hardening Is Disabled

Evidence:

- `release { minifyEnabled false }` in [app/build.gradle](../app/build.gradle).
- `lint { abortOnError false; checkReleaseBuilds false }` in [app/build.gradle](../app/build.gradle).

Impact: Release builds skip shrinking/obfuscation and do not block on lint. This is acceptable during heavy development but should not be the long-term release posture for an app handling credentials and tokens.

Recommendation: Create a staged hardening plan: make lint fatal for security/correctness checks first, then enable R8 in release with keep rules for Hilt, Room, Retrofit/Gson models, Firebase, Ktor, and Media3.

### Build Logic Is Functional but Not Scalable

Evidence:

- Root `build.gradle` uses legacy `buildscript` classpaths.
- Dependencies are hardcoded in [app/build.gradle](../app/build.gradle).
- No version catalog or convention plugins.

Impact: This is manageable for one module, but the file is already large and mixes app config, feature dependencies, test dependencies, Firebase, Ktor, Media3, Compose, and legacy comments.

Recommendation: Do not refactor build logic mid-feature. When stable, introduce `gradle/libs.versions.toml` first, then consider a small `build-logic` convention plugin only if new modules are added.

### Compose/Test Dependency Versions Are Inconsistent

Evidence:

- Kotlin plugin is 1.9.25.
- Compose compiler extension is 1.5.15.
- Compose BOM is 2024.02.01.
- `activity-compose` is 1.8.2 while `activity-ktx` is 1.9.3.
- `kotlinx-coroutines-test` is 1.7.3 while runtime is 1.9.0.

Impact: The current compile passes, but test/runtime behavior can diverge. Coroutines tests especially should track runtime closer to avoid scheduler behavior differences.

Recommendation: Align test libraries with runtime where possible. Upgrade Compose BOM/activity-compose together in a dedicated dependency PR with `testDebugUnitTest` and a device smoke pass.

### Documentation Has Encoding Damage and Some Drift

Evidence:

- README and architecture docs contain mojibake characters.
- `docs/development-guide.md` says min API 26, while `app/build.gradle` sets minSdk 21.
- README is more authoritative and correctly notes minSdk 21.

Impact: Onboarding and AI-agent handoffs can pick up wrong constraints.

Recommendation: Treat README, AGENTS.md, CLAUDE.md, and docs/architecture.md as canonical. Fix encoding and update `development-guide.md` min SDK/tooling details.

## Strengths

- Clear app identity notes: namespace and applicationId intentionally differ.
- Hilt is used consistently for major dependencies.
- Room schema history has explicit migration files through version 10.
- Debrid token storage uses encrypted preferences.
- Media3 has a dedicated player area with explicit recovery and focus handling.
- Series V2 is intentionally isolated behind a feature flag, matching the architecture doc.
- JVM test count is substantial for a project of this size.

## Recommended Test Plan

Short term:

- Fix Debrid TTL tests so `:app:testDebugUnitTest` is green.
- Add focused unit tests for `PlaybackResolver` with an injectable clock and configurable TTL.
- Add migration tests for Room 4 -> 10 and current latest -> next.
- Add tests for companion server auth once pairing-token enforcement exists.

Android TV device layer:

- Add `app/src/androidTest`.
- Add UIAutomator for DPAD remote navigation.
- Add Espresso for view assertions where stable IDs exist.
- Add Compose UI testing only for Compose-owned screens.
- Add `testOptions { animationsDisabled = true }`.
- Add AndroidX Test Orchestrator for isolation.

CI:

- PR gate: `:app:compileDebugKotlin`, `:app:testDebugUnitTest`.
- Nightly/release gate: add one Android TV or generic ATD managed device smoke suite.
- Upload `app/build/reports/tests/`, logcat, and screenshots on failures.

## Priority Backlog

1. Fix Debrid TTL test contract and restore green unit tests.
2. Add authenticated pairing token to `CompanionConfigServer`.
3. Encrypt IPTV credential storage and migrate old prefs.
4. Restrict cleartext network policy.
5. Add first Android TV DPAD instrumented smoke tests.
6. Remove destructive Room migration fallback from release builds.
7. Reduce/redact debug logging of Real-Debrid and stream URL data.
8. Add migration tests and release lint/security gates.
9. Align dependency/test versions in a dedicated maintenance change.
10. Clean documentation drift and mojibake.

