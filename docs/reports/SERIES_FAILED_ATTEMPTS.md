# IPTV Series Failed Attempts

This document tracks unsuccessful approaches and logic that caused regressions or failed to meet user requirements. **Do not repeat these patterns.**

## TASK 026-FIX-1 — Hardcoded Skeleton Alignment
- **Failure:** Attempted to align skeleton cards by manually guessing `dp` values for margins and widths.
- **Result:** Alignment varied across different screen resolutions and Fire TV versions. The skeleton "jumped" when real data loaded.
- **Lesson:** Never use hardcoded margins for overlay elements meant to match a dynamic grid. Use shared parent containers instead.

## TASK 026-CORRECTION — Out-of-Scope Title Sync
- **Failure:** Implemented synchronized header title to focused series name (Cinema style).
- **Result:** Violated strict scope (Preserve existing header behavior).
- **Lesson:** Only implement requested UI changes. In Series, the header must strictly show the Category Name.

## TASK 026-FIX-3 — Card Dimension Tweak
- **Failure:** Changed `item_series_loading.xml` card width (140dp -> 160dp) to match cards.
- **Result:** Failed to solve the underlying coordinate space mismatch between the root FrameLayout and the nested Grid container.
- **Lesson:** UI "alignment" problems are usually structural (parent/constraint issues), not dimension problems.

## TASK 026-FIX-5 — Incomplete Shimmer Removal
- **Failure:** Skeleton animation still appeared on real devices after "removal".
- **Result:** Code contained redundant alpha reset logic and layering that allowed the old view to persist.
- **Lesson:** Always verify view removal by inspecting the layout inspector or forced UI dumps. Ensure all code paths to the old view are purged.

## TASK 025-AUDIT-FAILED — Legacy XML Configuration Masking
- **Failure:** Overlooking visibility bounds and layout constraints in detail screens (`container_actions` set to `gone` and `0dp`).
- **Result:** Code contained logic (e.g. click listeners for `btn_play` and `btn_favorite`) targeting views that were completely hidden and structurally un-interactable.
- **Lesson:** Always cross-reference Java/Kotlin click listeners with the corresponding XML file's visibility, height, and width attributes. Do not rely solely on the existence of listener registration to assume action buttons are interactive.

