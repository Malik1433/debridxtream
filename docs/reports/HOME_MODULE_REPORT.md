# Home Module Report

## Purpose
Canonical Home module report for agent tasks.

## Existing Detailed History
Historical Home findings and QA evidence are currently in:
- `docs/reports/HOME_SCREEN_REPORT.md`
- `docs/reports/TASK_011_HOME_SCREEN_DEEP_AUDIT.md`
- `docs/reports/TASK_012_HOME_CRITICAL_STABILITY_FIXES_PASS_1.md`
- `docs/reports/TASK_013_HOME_FOCUS_RESTORATION_AND_SIDEBAR_BEHAVIOR.md`
- `docs/reports/TASK_013B_HOME_FOCUS_DEVICE_QA_VERIFICATION.md`
- `docs/reports/TASK_013C_HOME_FOCUS_FIX_PASS_2.md`
- `docs/reports/TASK_014_HOME_ADAPTER_DATA_STABILITY.md`

## Standing Rules
- Preserve Home row order, focus restoration, and sidebar return behavior.
- Do not duplicate Home adapters, layouts, routes, or controllers.
- Document any row/focus change here and in the Home success or failed history.

## Current Status
- Successfully refactored and audited `HomeFragment` and all focus/routing managers.
- `HomeFragment.kt` is under 300 lines (295 lines).
- All managers (`HomeFocusManager.kt`, `HomeKeyRoutingManager.kt`, `HomeNavigationRouter.kt`, `HomeSidebarManager.kt`, `HomeHeroManager.kt`) are under 500 lines.
- Complete cleanup of adapters and listener cleanup is now guarded in `onDestroyView()` so partially initialized managers do not crash fragment teardown.
- Verified with clean `assembleDebug` and all unit tests passing.

## Current Task / Verification
- Audited `HomeFragment` and separated D-pad key routing into `HomeKeyRoutingManager.kt`.
- Relocated 15 focus/navigation state properties from `HomeFragment.kt` to `HomeFocusManager.kt`.
- Cleaned up fragment view bindings and adapters in `onDestroyView()` to prevent memory leaks on screen exit.
- Guarded manager cleanup in `onDestroyView()` so partial initialization cannot trigger late `lateinit` crashes during teardown.
- Verified zero regressions in lateral navigation, focus restoration, and sidebar transitions on the real TV device at `192.168.0.21:5555`.
  - **APK Installation:** Successfully installed `app-debug.apk` onto `192.168.0.21:5555`.
  - **Initial Focus:** MainActivity launched successfully, and focus correctly landed on the Rank 1 item in the "Trending Movies" row.
  - **Sidebar Navigation:** Sent D-pad Left (`keyevent 21`); verified focus transitioned to the "Home" item on the navigation sidebar and the sidebar width expanded from 160 to 520, exposing all navigation labels.
  - **Sidebar Collapse:** Sent D-pad Right (`keyevent 22`); verified focus correctly restored to the Rank 1 item in the "Trending Movies" row and the sidebar collapsed back to a compact width of 160.
  - **Security Smoke:** `CompanionConfigServer` CORS now uses runtime LAN host allowlisting instead of `anyHost()`, build passed, and launch smoke on `192.168.0.84:5555` remained stable.


