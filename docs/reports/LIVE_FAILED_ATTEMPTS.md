# LiveTV Failed Attempts

## Things Not To Repeat
- Do not clear `isLoadingChannels` inside `LiveViewModel.loadChannelsForCategory()` before Paging finishes.
- Do not build Live playback URLs with hardcoded `.ts` when `container_extension` exists.
- Do not build fullscreen `liveChannelIds` from adapter snapshot only when a cached full list is available.
- Do not leave Live playback callsites with separate URL-construction logic.
- Do not treat a timed-out `clean assembleDebug` as a failure if a plain `assembleDebug` and targeted tests already passed.
- Do not let `FocusMemoryManager.restoreFocus()` override the Live channel restore path during fullscreen return.
- Do not freeze Recent Live history after the first fullscreen launch if the user changes channel while still in fullscreen.
- Do not request focus on `rvChannels` when the RecyclerView is intentionally `isFocusable = false`; focus a bound channel child instead.
- Do not restore the fullscreen-return mini-player from recent live history as the primary source. History can lag behind Activity return and restore the original channel after fullscreen zapping.
- Do not consume fullscreen-return state before the target channel item has had a chance to bind and receive focus.
- Do not rely on native RecyclerView focus-search for rapid DPAD_UP/DOWN inside the Live channel list. Paged layouts can temporarily miss the next child and send focus to categories or preview controls.
- Do not attach the Live channel-list UP/DOWN guard only to `rvChannels`; the focused child item must also consume those key events.
- Do not cache `(null, null)` EPG responses as if they were real guide data. Empty lookups should stay eligible for later refresh.
- Do not wait only for a direct bind/request path to populate visible Live EPG rows. Warm the first visible channels once Paging settles.
- Do not launch WorkManager immediate EPG sync and a direct repository sync from the same settings click. That creates a race on the same parser/database path.
- Do not let manual and scheduled EPG fetches overlap without a mutex or single-flight guard.

## Notes
- The old pattern was functional on small lists, but it drifted on larger categories and produced fragile handoff behavior.
- Live device QA beyond install/launch still needs a manual playback run to validate the new handoff behavior end-to-end.
- ADB key stress is useful for regression checks, but held remote repeat should still be manually checked for feel.
- EPG preview state still needs a manual remote-session confirmation after the build/install pass because guide timing depends on live provider data.
- Settings-side manual EPG sync should be verified again after the duplicate-path fix, because the crash was caused by two concurrent sync flows rather than a single parser failure.
