# Login Page Deep Audit

## Files Inspected

- `app/src/main/java/com/tvonnet/debridxtreamiptv/ui/LoginFragment.kt`
- `app/src/main/java/com/tvonnet/debridxtreamiptv/ui/MainActivity.kt`
- `app/src/main/java/com/tvonnet/debridxtreamiptv/ui/InitialSyncFragment.kt`
- `app/src/main/java/com/tvonnet/debridxtreamiptv/data/prefs/CredentialsPreferences.kt`
- `app/src/main/java/com/tvonnet/debridxtreamiptv/data/repository/XtreamRepository.kt`
- `app/src/main/java/com/tvonnet/debridxtreamiptv/data/remote/XtreamApiService.kt`
- `app/src/main/java/com/tvonnet/debridxtreamiptv/data/remote/XtreamRetrofitClient.kt`
- `app/src/main/java/com/tvonnet/debridxtreamiptv/data/model/XtreamModels.kt`
- `app/src/main/java/com/tvonnet/debridxtreamiptv/network/CompanionConfigServer.kt`
- `app/src/main/java/com/tvonnet/debridxtreamiptv/network/RemotePairingManager.kt`
- `app/src/main/java/com/tvonnet/debridxtreamiptv/ui/companion/CompanionSetupActivity.kt`
- `app/src/main/res/layout/fragment_login.xml`
- `app/src/main/res/layout/fragment_initial_sync.xml`
- `app/src/main/res/layout/activity_main.xml`
- `app/src/main/res/values/strings.xml`
- `app/src/main/AndroidManifest.xml`
- `app/src/main/res/xml/network_security_config.xml`

No `LoginActivity` is present. Login is hosted by `MainActivity` with `LoginFragment`.

## Login Flow Map

1. `MainActivity.onCreate()` reads `CredentialsPreferences.isLoggedIn()`.
2. If `logged_in=false`, `MainActivity` loads `LoginFragment` into `R.id.content_container`.
3. `LoginFragment.onViewCreated()` binds server URL, username, password, login button, mobile setup button, progress bar, focus listeners, and entrance animations.
4. `LoginFragment.onResume()` calls `checkAutoSyncCredentials()`.
5. If synced credentials exist and `logged_in=false`, `LoginFragment` fills the fields, writes `GlobalConfig`, and calls `performLogin()`.
6. Manual login calls `validateInputs()`, then `performLogin(server, username, password)`.
7. `performLogin()` disables the sign-in button, shows the spinner, initializes `XtreamRepository`, then calls `repository.login(username, password)`.
8. On `Result.Success`, credentials are saved with `logged_in=true`, `GlobalConfig` is written, and `InitialSyncFragment` replaces the login fragment.
9. `InitialSyncFragment.startSyncIfNeeded()` starts `repository.syncInitialData()` unless initial sync is already running or already successful.
10. On sync success, `InitialSyncFragment` replaces itself with `HomeFragment`.
11. On future app launches, `MainActivity` sees `logged_in=true`, initializes the repository from saved credentials, writes `GlobalConfig`, and navigates directly to `HomeFragment`.

## UI Layout Audit

- The login layout is a fixed horizontal split screen: 45% branding panel and 55% form panel.
- The right panel uses a centered `ScrollView`, but its height is `wrap_content` with `layout_gravity="center"`, so focus scrolling on TV is not guaranteed if scaled text, overscan, or small-height devices push controls beyond the visible area.
- Login field spacing is generally reasonable for 1080p landscape, but the combination of 56dp horizontal margin, 32dp card padding, fixed 56dp buttons, progress bar below the secondary button, and focus scaling can create clipping risk on lower-height or accessibility-scaled displays.
- Some user-visible text is hardcoded in layout XML instead of `strings.xml`: `"Enter your IPTV provider credentials"` and `"OR"` in `fragment_login.xml`.
- The mobile setup button uses `@drawable/ic_search`, which does not match the QR/mobile setup action.
- There is no default DNS/server value in XML or Kotlin.
- There is no remember-me checkbox or toggle in XML or Kotlin.

## Focus & Android TV Remote Audit

- `fragment_login.xml` defines `nextFocusDown` for the vertical path server -> username -> password -> sign in -> mobile setup.
- No `nextFocusUp`, `nextFocusLeft`, or `nextFocusRight` is defined, so reverse navigation and lateral movement depend on platform heuristics.
- `setupFocusListeners()` scales focused fields/buttons to 1.03 and raises `translationZ`, but the animated view can draw outside its parent bounds.
- Focus listeners are attached directly to `TextInputEditText` children, not the surrounding `TextInputLayout`; outline/end-icon focus behavior may feel inconsistent on TV.
- The password visibility end icon is inside `TextInputLayout`, but no explicit TV focus route to the icon is defined.
- `MainActivity.dispatchKeyEvent()` handles search/voice keys globally. Login loads before voice manager initialization in the non-logged-in branch, so search/voice key handling on the login page may touch an uninitialized `voiceSearchManager`.
- No initial focus request is made for `et_server_url` or the primary login action.
- No IME action listener handles password `actionDone`, so pressing Done on a soft keyboard may not submit login.

## Credential Storage Audit

- Credentials are stored in plain `SharedPreferences` named `iptv_credentials`.
- Stored values include server URL, username, password, logged-in flag, sync code, and persistent device ID.
- `saveCredentials()` sets `logged_in=true` immediately after the current caller decides login succeeded.
- `saveSyncedCredentials()` sets `logged_in=false`, allowing `LoginFragment` to validate synced credentials before navigation.
- `RemotePairingManager.handleConfigPayload()` and `CompanionConfigServer.saveConfiguration()` call `saveCredentials()` directly, bypassing `LoginFragment`'s validation gate and marking the user as logged in.
- There is no encryption, keystore use, token wrapping, or redaction layer for IPTV password storage.

## API/Auth Audit

- `XtreamApiService.login()` calls `player_api.php` with username, password, and `action=login`.
- `XtreamRepository.login()` treats any successful HTTP response with a non-null body as authenticated.
- `XtreamLoginResponse` exposes `user_info.auth` and `user_info.status`, but manual login does not inspect them.
- `CompanionConfigServer.validateIptvCredentials()` does inspect `status == "Active"` or `auth == 1`, creating inconsistent auth semantics across entry points.
- `XtreamRepository.initialize()` normalizes the server URL by appending `/`, creates Retrofit, starts EPG parser, and starts memory monitoring.
- Retrofit requires a valid Retrofit base URL. Inputs like `example.com`, whitespace-padded invalid URLs, or unsupported schemes are only caught after repository initialization throws.
- OkHttp timeouts are set to 30 seconds for connect/read/write. There is no login-specific retry or shorter TV-friendly timeout.
- Debug HTTP logging uses `BASIC`, which avoids query values in normal request-line logs, but app logs still include server and username.

## Navigation Audit

- `MainActivity` routes unauthenticated users to `LoginFragment`.
- Authenticated users route directly to `HomeFragment`, not `InitialSyncFragment`, even if credentials are present but initial sync was never completed.
- Successful login routes to `InitialSyncFragment` using a plain fragment replace with no back stack.
- `InitialSyncFragment` routes to `HomeFragment` on sync success and guards duplicate navigation with `hasNavigated`.
- Logout from `SettingsFragment` clears credentials and replaces the content container with `LoginFragment`.
- There is no single shared navigation/auth state owner, so login state, repository state, and navigation are distributed across multiple classes.

## Error/Loading State Audit

- Login loading state only disables `btnLogin`; server/username/password fields and the mobile setup button remain enabled.
- Failed login reenables `btnLogin`; successful login leaves it disabled while navigating.
- Errors are shown as `Toast`, which is transient and not ideal for TV users or accessibility.
- Field validation errors are placed on `TextInputEditText.error`, not `TextInputLayout.error`, so Material styling and screen reader behavior may be inconsistent.
- `performLogin()` calls `progressBar.visibility = GONE` both before navigation and in `finally`.
- Auto-login can call `performLogin()` every `onResume()` while synced credentials remain present and `logged_in=false`.
- Initial sync shows inline error text and a retry button, but retry can be clicked repeatedly while a sync job is already running.

## Security & Privacy Audit

- `AndroidManifest.xml` sets `android:usesCleartextTraffic="true"`.
- `network_security_config.xml` permits cleartext traffic globally and trusts user-installed certificates globally.
- IPTV username/password are sent as query parameters on every Xtream request.
- IPTV credentials are embedded in playback URLs for live, VOD, and series streams elsewhere in the repository and UI code.
- `LoginFragment.performLogin()` logs `server` and `username`.
- `SearchFragment` and companion server code also log credential-adjacent values.
- `GlobalConfig` stores base URL, username, and password in process-wide mutable static state.
- No certificate pinning, credential redaction policy, or encrypted credential storage is present for IPTV credentials.

## Crash Risks

- `MainActivity.dispatchKeyEvent()` can call `startVoiceSearch()` while `voiceSearchManager` is uninitialized on the unauthenticated login path.
- `LoginFragment.checkAutoSyncCredentials()` can trigger duplicate concurrent login attempts on repeated resume while `logged_in=false`.
- Invalid server URL input can make `Retrofit.Builder.baseUrl()` throw. `XtreamRepository.initialize()` catches it and returns `apiService=null`, preventing a crash, but the user receives only `"API service not initialized"`.
- `LoginFragment` uses `Toast.makeText(context, ...)`; if the fragment is detached during an async completion, `context` may be null. Android accepts nullable context poorly in practice and this should not be relied on.
- `InitialSyncFragment.startSync()` allows retry clicks without disabling the retry button after launch, risking overlapping UI actions even though repository sync is mutex-protected.
- App-wide cleartext and user CA trust increase MITM exposure rather than app crashes, but they make auth failures harder to diagnose.

## Dead/Duplicate Code

- No `LoginActivity` exists.
- Login/auth validation is duplicated and inconsistent between `LoginFragment.performLogin()`, `XtreamRepository.login()`, `CompanionConfigServer.validateIptvCredentials()`, `CompanionConfigServer.saveConfiguration()`, `RemotePairingManager.handleConfigPayload()`, and `CompanionSetupActivity.handleConfigurationReceived()`.
- `LoginFragment.llTitleContainer` is assigned but not used after binding.
- `RecognizerIntent`, `MemoryManager`, `LiveFragment`, `VodFragment`, and `SeriesFragment` imports in `MainActivity` appear unused in the inspected file.
- `XtreamRetrofitClient` imports `XtreamEpisodeDetail` and `EpisodeDetailJsonAdapter`, but the adapter registration is commented out.
- `strings.xml` includes `login_enter_manually`, but the inspected login layout does not use it.

## Findings Table

| Severity | File name | Exact location or function | Current behavior | Problem | Risk | Safe recommendation | What NOT to touch |
|---|---|---|---|---|---|---|---|
| Critical | `XtreamRepository.kt` | `login()`, lines 174-184 | Any 2xx response with a non-null body returns success. | It ignores `user_info.auth`, `user_info.status`, expiry, and provider message. | Disabled/expired/wrong accounts can be stored as logged in and sent to initial sync. | Align manual login with companion validation and require active/authenticated user info. | Do not change Xtream endpoint names or response models broadly. |
| Critical | `CredentialsPreferences.kt`, `RemotePairingManager.kt`, `CompanionConfigServer.kt` | `saveCredentials()`, lines 9-16; direct calls at `RemotePairingManager.kt` 120-129 and `CompanionConfigServer.kt` 199-209 | Some companion paths save credentials with `logged_in=true`. | They bypass LoginFragment validation and can skip the intended auto-login route. | App may boot directly to Home with unvalidated or failed credentials. | Route all companion credentials through one validation path or save as synced credentials until verified. | Do not remove companion pairing feature. |
| Critical | `MainActivity.kt` | `dispatchKeyEvent()` / `startVoiceSearch()` with login branch lines 61-69 and voice init lines 99-100 | Unauthenticated branch returns before `initializeVoiceSearch()`. | Search/voice key can access uninitialized `lateinit var voiceSearchManager`. | Crash on login screen from remote voice/search key. | Guard `voiceSearchManager` initialization or ignore voice/search keys until initialized. | Do not redesign voice search navigation. |
| High | `CredentialsPreferences.kt` | lines 7-16 and 23-30 | Server, username, and password are stored in plain SharedPreferences. | No encryption or keystore-backed protection for credentials. | Device compromise or backup/debug extraction exposes IPTV credentials. | Move credentials to encrypted storage or wrap password with Android Keystore-backed crypto. | Do not change preference keys until migration is planned. |
| High | `AndroidManifest.xml`, `network_security_config.xml` | manifest lines 29-30; network config lines 2-8 | Cleartext is globally enabled and user CAs are globally trusted. | Credentials are often sent in query params and may travel over HTTP. | MITM and credential disclosure risk. | Restrict cleartext/user CAs by build type or domain policy; prefer HTTPS when provider supports it. | Do not block all legacy IPTV HTTP servers without a compatibility plan. |
| High | `LoginFragment.kt` | `checkAutoSyncCredentials()`, lines 95-116 | Auto-login runs on every resume when synced credentials exist and logged_in is false. | No in-flight guard or consumed state. | Duplicate login attempts, repeated toasts, repeated network calls. | Add a local/session in-flight guard and consume or mark attempted synced credentials. | Do not remove auto-sync behavior. |
| High | `LoginFragment.kt` | `performLogin()`, lines 230-281 | Login disables only the sign-in button. | Inputs and companion setup remain interactive during auth. | Users can mutate fields or launch companion setup mid-login, causing confusing state. | Disable all login inputs/actions during login, restore on failure. | Do not redesign the visual layout. |
| High | `LoginFragment.kt` | `performLogin()`, line 234 | Logs server URL and username. | Credential-adjacent data is written to logs. | Privacy leakage in device logs and QA captures. | Redact server/user logs or log only non-sensitive state. | Do not remove useful error logging entirely. |
| Medium | `LoginFragment.kt`, `XtreamRepository.kt` | validation lines 302-323; initialize lines 143-163 | Server validation checks only non-empty. | Invalid or schemeless URLs are accepted until Retrofit creation fails. | Poor error messages and failed login for common input forms. | Validate URL shape and normalize scheme with clear user feedback. | Do not assume one hardcoded provider URL. |
| Medium | `LoginFragment.kt` | `validateInputs()`, lines 307-319 | Errors are set directly on edit texts. | Material `TextInputLayout` error state is not used. | Inconsistent UI, weaker accessibility announcement, possible visual mismatch. | Use `TextInputLayout.error` for each field. | Do not change field IDs unless updating binding code. |
| Medium | `fragment_login.xml` | lines 173-248 | Inputs define down navigation only. | No explicit up/left/right focus graph, no initial focus. | D-pad navigation can be inconsistent across TV devices. | Add explicit focus directions and request initial focus after view creation. | Do not remove existing down order. |
| Medium | `fragment_login.xml`, `LoginFragment.kt` | password layout lines 217-248; focus setup lines 122-139 | Password toggle is enabled, but focus route is not explicit. | TV users may not reliably reach or understand the end icon. | Password visibility may be inaccessible by remote. | Provide explicit focus behavior/content description for the toggle or a TV-friendly reveal action. | Do not remove password masking by default. |
| Medium | `LoginFragment.kt` | `onLoginClick()` / password `imeOptions`, lines 213-220 and XML line 247 | Keyboard Done is declared but not handled. | Soft keyboard users may press Done and nothing submits. | Friction on Android TV keyboard/mobile-like input. | Add editor action handling for password Done. | Do not change input types casually. |
| Medium | `MainActivity.kt` | logged-in branch lines 81-93 | Logged-in users go straight to Home. | Existing credentials are not revalidated and initial sync completion is not checked. | Stale or invalid credentials produce downstream failures after launch. | Add a lightweight auth/sync gate or repository validation policy. | Do not force full sync every launch. |
| Medium | `InitialSyncFragment.kt` | `startSync()`, lines 78-89 | Retry remains clickable while sync starts. | Multiple retry taps can queue UI actions. | Confusing repeated progress/error state. | Disable retry while sync is running and reenable on error. | Do not remove repository `syncMutex`. |
| Medium | `fragment_login.xml` | right panel/card lines 102-116 and progress lines 327-337 | Centered `wrap_content` scroll area with hidden scrollbars. | On scaled fonts/overscan, bottom controls can clip or be hard to discover. | 1080p TV layout risk, especially with accessibility font scale. | Test at 1080p and high font scale; use full-height scroll container or constrained card sizing. | Do not redesign split-screen unless explicitly approved. |
| Medium | `XtreamRetrofitClient.kt` | timeouts lines 72-74 | Login uses 30 second connect/read/write timeouts and no retry policy. | One failed provider call can leave TV user waiting too long. | Perceived hang and repeated manual attempts. | Consider shorter login timeout and clearer timeout-specific error. | Do not change streaming client timeouts globally without measuring playback. |
| Low | `fragment_login.xml` | hardcoded strings lines 136 and 292 | Two visible strings are hardcoded. | Localization/resource consistency issue. | Build/lint warnings and harder translation. | Move strings to `strings.xml`. | Do not change wording without product approval. |
| Low | `fragment_login.xml` | mobile setup button lines 306-325 | Uses `ic_search` for mobile/QR setup. | Icon does not match action. | Minor UX confusion. | Use QR/mobile/link icon already in design system if present. | Do not alter companion setup route. |
| Low | `LoginFragment.kt` | field `llTitleContainer`, line 41 and binding line 72 | Field is assigned but unused. | Dead field noise. | Low maintenance cost. | Remove only during an approved cleanup pass. | Do not refactor during audit. |
| Low | `MainActivity.kt`, `XtreamRetrofitClient.kt`, `strings.xml` | unused imports/strings | Several inspected symbols appear unused. | Dead code/resource clutter. | Minor build hygiene risk. | Clean in a separate approved cleanup. | Do not touch unrelated modules during login fix. |

## Safe Fix Plan

1. Normalize auth semantics first: make the manual login path validate `user_info.auth/status` the same way companion validation does.
2. Add an in-flight login guard and disable all login actions while auth is running.
3. Fix the `MainActivity` voice/search key crash by guarding uninitialized voice manager on the login route.
4. Add URL validation/normalization with clear field errors before repository initialization.
5. Improve TV input handling with explicit focus up/down routes, initial focus, password Done action, and a reachable password visibility affordance.
6. Move credentials to encrypted storage with a migration path from existing `iptv_credentials` keys.
7. Split network security policy by build type or provider compatibility mode instead of globally allowing cleartext/user CAs.
8. Tighten initial sync retry/loading behavior and route stale logged-in credentials through a lightweight validation/sync gate.
9. Clean hardcoded strings, unused fields/imports, and icon mismatch only after behavioral fixes are approved.

## What Not To Touch

- Do not redesign the split-screen login UI without approval.
- Do not remove mobile/QR companion setup.
- Do not hardcode a provider DNS/default server unless explicitly requested.
- Do not change stream URL formats or Xtream endpoint action names during login cleanup.
- Do not modify Home, Live, VOD, Series, Debrid, or Player modules except where they directly consume login credentials and only after approval.
- Do not rename preference keys without a migration plan.
- Do not disable HTTP provider support globally without a compatibility decision.
- Do not refactor repository caching/sync internals as part of the first login fix.

## Next Recommended Step

Approve a focused login stabilization pass that fixes only auth validation, duplicate login prevention, loading/input lockout, URL validation, and the login-screen voice/search crash. Security storage and network policy should follow as a separate migration because they affect persisted user data and provider compatibility.
