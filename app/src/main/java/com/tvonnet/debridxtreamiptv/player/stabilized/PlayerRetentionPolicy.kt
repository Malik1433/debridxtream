package com.tvonnet.debridxtreamiptv.player.stabilized

import android.content.ComponentCallbacks2
import com.tvonnet.debridxtreamiptv.data.model.ContentType

/**
 * The T2.1 **retained-player** decisions, lifted out of [BasePlayerFragment] so they can be tested.
 *
 * Retention keeps a paused ExoPlayer (codec + buffers + socket) alive across `onStop` for VOD and
 * Debrid, so Home→return resumes instantly instead of a 1–3s cold reconnect. On a 1GB Fire TV that
 * is also exactly how an app gets OOM-killed, so the rules around it are safety rules, not
 * conveniences — and they were previously spread across three call sites with no test between them
 * (roadmap B2.5).
 *
 * Every function here is a pure predicate: same inputs, same answer, no Android state. The parts
 * that genuinely need a device (does the heap actually stay flat across hours of background churn)
 * are answered by the diagnostics memory samples, not by these.
 */
object PlayerRetentionPolicy {

    /**
     * LIVE must always release on stop. On a `max_connections=1` Xtream account a retained live
     * connection holds the account's only slot while the app is backgrounded, and a live stream has
     * no resume value anyway — it re-tunes to the live edge. A finishing session releases too;
     * there is nothing to come back to.
     */
    fun shouldReleaseOnStop(contentType: ContentType?, isFinishing: Boolean): Boolean =
        contentType == ContentType.LIVE_TV || isFinishing

    /**
     * Retention is otherwise unbounded, so memory pressure ends it: release the backgrounded player
     * ourselves rather than waiting for the OS to reclaim the codec mid-hold. LIVE is excluded
     * because it was already released at `onStop`.
     */
    fun shouldReleaseOnTrimMemory(level: Int, hasPlayer: Boolean, contentType: ContentType?): Boolean =
        level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND &&
            hasPlayer &&
            contentType != ContentType.LIVE_TV

    /**
     * Coming back with no player means a cold start — unless a Debrid re-resolve is in flight, in
     * which case the resolver's Success switch owns playback and starting here would race it.
     */
    fun shouldColdStartOnResume(hasPlayer: Boolean, isResolvingDebrid: Boolean): Boolean =
        !hasPlayer && !isResolvingDebrid

    /**
     * Coming back to a RETAINED player: resume only what was playing, and never audibly resume a
     * stale stream underneath an in-flight Debrid re-resolve.
     */
    fun shouldResumePlaybackOnResume(
        hasPlayer: Boolean,
        wasPlayingBeforePause: Boolean,
        isResolvingDebrid: Boolean
    ): Boolean = hasPlayer && wasPlayingBeforePause && !isResolvingDebrid
}
