package com.tvonnet.debridxtreamiptv.utils

import android.util.Log

/**
 * A lightweight focus ownership coordinator for Android TV.
 *
 * Acts as a traffic controller — does NOT decide where focus goes,
 * ONLY controls who can request focus at any given moment.
 *
 * Prevents race conditions when multiple UI components (e.g., PlayerActivity overlays,
 * fragment restore logic, browser panels) compete for focus simultaneously.
 *
 * ## Design Principles:
 * - **Single-owner model**: Only one component can hold focus ownership at a time.
 * - **No threading**: Main-thread only, no synchronization primitives.
 * - **No timeouts**: Ownership is explicitly managed via [release] or [clear].
 * - **No delays**: Never introduces postDelayed or scheduling.
 * - **Crash-safe**: Actions are wrapped in try/catch to prevent focus logic from crashing the app.
 * - **Zero dependencies**: No Context, no Handler, no lifecycle coupling.
 *
 * @see FocusMemoryManager For position-based focus persistence across navigation.
 * @see FocusTrapHelper For D-pad boundary enforcement within containers.
 */
object FocusCoordinator {

    private const val TAG = "FocusCoordinator"

    /** Toggle for debug logging. Set to true only during development. */
    private const val DEBUG = false

    /** The component currently holding focus ownership. Null = free. */
    private var currentOwner: String? = null

    /**
     * Attempts to acquire focus ownership and execute [action].
     *
     * - If no owner holds the lock, ownership is acquired and [action] runs.
     * - If the same [owner] already holds the lock (re-entrant), [action] runs.
     * - If a DIFFERENT owner holds the lock, [action] is **skipped**.
     *
     * @param owner A unique identifier for the requesting component
     *              (e.g., "PLAYER_CONTROLLER", "VOD_RESTORE", "NEXT_EPISODE").
     * @param action The focus-related code to execute (e.g., `view.requestFocus()`).
     */
    fun requestFocus(owner: String, action: () -> Unit) {
        if (currentOwner != null && currentOwner != owner) {
            if (DEBUG) Log.d(TAG, "Blocked: '$owner' (active='$currentOwner')")
            return
        }

        currentOwner = owner
        if (DEBUG) Log.d(TAG, "Acquired: '$owner'")

        try {
            action()
        } catch (e: Exception) {
            if (DEBUG) Log.e(TAG, "Action failed for '$owner'", e)
            currentOwner = null
        }
    }

    /**
     * Releases focus ownership for the given [owner].
     *
     * Only succeeds if [owner] matches the current lock holder.
     * Safe to call multiple times or with a stale owner name.
     *
     * @param owner The component releasing its ownership.
     */
    fun release(owner: String) {
        if (currentOwner == owner) {
            currentOwner = null
            if (DEBUG) Log.d(TAG, "Released: '$owner'")
        }
    }

    /**
     * Forces focus ownership, overriding any current owner.
     *
     * Use sparingly for critical transitions where focus MUST be claimed regardless
     * of current state (e.g., closing a modal overlay that must return focus to the player).
     *
     * @param owner A unique identifier for the forcing component.
     * @param action The focus-related code to execute.
     */
    fun force(owner: String, action: () -> Unit) {
        if (DEBUG && currentOwner != null && currentOwner != owner) {
            Log.d(TAG, "Force-override: '$currentOwner' -> '$owner'")
        }

        currentOwner = owner

        try {
            action()
        } catch (e: Exception) {
            if (DEBUG) Log.e(TAG, "Force action failed for '$owner'", e)
            currentOwner = null
        }
    }

    /**
     * Unconditionally clears ownership. Use as a safety valve when
     * transitioning between screens or on lifecycle destroy.
     */
    fun clear() {
        if (DEBUG && currentOwner != null) Log.d(TAG, "Cleared owner='$currentOwner'")
        currentOwner = null
    }
}
