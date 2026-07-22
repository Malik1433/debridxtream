package com.tvonnet.debridxtreamiptv.player.stabilized

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.widget.TextView
import androidx.core.view.isVisible
import com.tvonnet.debridxtreamiptv.data.prefs.SettingsPreferences
import com.tvonnet.debridxtreamiptv.data.network.NetworkQuality
import java.util.Locale

/**
 * The shared reconnect budget, its "RECONNECTING…" banner and the session-adaptive
 * network-quality reclassification (Phase P10).
 *
 * Scope note: this holds the *accounting and the indicator* only. Every recovery
 * subsystem (error taxonomy, stall recovery, live re-prepare, debrid re-resolution)
 * stays in [PlayerActivity] — Phase 2 of the refactor plan (PlayerNetworkStallManager)
 * is still BLOCKED, and the watchdog/network-callback logic is bound to it.
 */
/**
 * Everything the banner needs from the screen it lives on. Implemented by
 * [PlayerActivity] so the manager never reaches into the Activity itself.
 */
internal interface ReconnectBannerHost {
    fun bannerView(): View?
    fun bannerTextView(): TextView?
    fun bannerLabel(attempt: Int, max: Int): String
    /** While the cinematic loader is up the screen stays clean — no reconnect noise. */
    fun isLoaderVisible(): Boolean
    fun isGone(): Boolean
}

internal class PlayerReconnectManager(
    private val handler: Handler,
    private val host: ReconnectBannerHost,
    private val settingsPreferences: SettingsPreferences
) {

    companion object {
        private const val TAG = "PlayerActivity"
        const val MAX_RECONNECTS_PER_WINDOW = 4
        private const val RECONNECT_WINDOW_MS = 60_000L
        private const val RECONNECT_BANNER_DELAY_MS = 1200L
        private const val NETWORK_QUALITY_UPDATE_INTERVAL_MS = 60_000L
    }

    // Every recovery subsystem shares this budget, so a stream that is dead in a way
    // no subsystem can fix cannot loop forever: at most MAX_RECONNECTS_PER_WINDOW
    // attempts inside a rolling RECONNECT_WINDOW_MS.
    private val attemptTimestamps: ArrayDeque<Long> = ArrayDeque()

    private var lastNetworkQualityUpdateMs = 0L
    private var bannerPending = false

    private val bannerShowRunnable = Runnable {
        bannerPending = false
        if (!host.isGone()) showBannerNow()
    }

    /**
     * Consult the aggregate reconnect budget BEFORE any recovery subsystem retries.
     * Returns true and records the attempt (also refreshing the "Reconnecting…"
     * banner) when we are still under [MAX_RECONNECTS_PER_WINDOW] within the rolling
     * window; returns false when the budget is exhausted, in which case the caller
     * must route to terminal failure handling. The subsystem's own inner cap still
     * applies on top of this.
     */
    fun canAttemptReconnect(): Boolean {
        val now = SystemClock.elapsedRealtime()
        while (attemptTimestamps.isNotEmpty() &&
            now - attemptTimestamps.first() > RECONNECT_WINDOW_MS
        ) {
            attemptTimestamps.removeFirst()
        }
        if (attemptTimestamps.size >= MAX_RECONNECTS_PER_WINDOW) {
            Log.i(
                TAG,
                "Reconnect budget exhausted (${attemptTimestamps.size}/$MAX_RECONNECTS_PER_WINDOW in ${RECONNECT_WINDOW_MS}ms) — giving up"
            )
            return false
        }
        attemptTimestamps.addLast(now)
        Log.i(TAG, "Reconnect attempt ${attemptTimestamps.size}/$MAX_RECONNECTS_PER_WINDOW")
        showBanner()
        return true
    }

    /** Clear the budget on a genuinely-successful frame/READY or a user zap. */
    fun resetBudget() {
        attemptTimestamps.clear()
    }

    /**
     * Reclassify the saved network quality from ExoPlayer's passive bandwidth
     * estimate on a rebuffer→READY transition, debounced to at most once per
     * NETWORK_QUALITY_UPDATE_INTERVAL_MS. Thresholds mirror NetworkQualityManager
     * (FAST >20Mbps, MODERATE 5-20Mbps, SLOW <5Mbps). The next player build/zap
     * picks up the new sizing; no live re-config and — critically — no extra network
     * requests (WAF-safe).
     */
    fun maybeUpdateNetworkQuality(estimateBps: Long?) {
        if (estimateBps == null || estimateBps <= 0L) return
        val now = SystemClock.elapsedRealtime()
        if (lastNetworkQualityUpdateMs != 0L &&
            now - lastNetworkQualityUpdateMs < NETWORK_QUALITY_UPDATE_INTERVAL_MS
        ) return
        lastNetworkQualityUpdateMs = now
        val mbps = estimateBps / 1_000_000.0
        val quality = when {
            mbps > 20.0 -> NetworkQuality.FAST
            mbps > 5.0 -> NetworkQuality.MODERATE
            else -> NetworkQuality.SLOW
        }
        if (quality.name != settingsPreferences.getNetworkQuality()) {
            settingsPreferences.saveNetworkQuality(quality.name)
            Log.i(
                TAG,
                "Session network quality updated to ${quality.name} (~${String.format(Locale.US, "%.1f", mbps)} Mbps)"
            )
        }
    }

    /**
     * Delayed presentation: quick re-prepares recover behind the last rendered frame
     * in well under a second — flashing the pill for those makes the app FEEL flakier
     * than it is. Only surface the banner when the outage persists past
     * RECONNECT_BANNER_DELAY_MS; [hideBanner] cancels a still-pending show.
     */
    fun showBanner() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post { showBanner() }
            return
        }
        val view = host.bannerView() ?: return
        if (view.isVisible) {
            // Already showing — just refresh the attempt count in place.
            host.bannerTextView()?.text = host.bannerLabel(attemptTimestamps.size.coerceAtLeast(1), MAX_RECONNECTS_PER_WINDOW)
            return
        }
        if (!bannerPending) {
            bannerPending = true
            handler.postDelayed(bannerShowRunnable, RECONNECT_BANNER_DELAY_MS)
        }
    }

    private fun showBannerNow() {
        // While the cinematic loader is up, keep the screen clean (no reconnect noise).
        if (host.isLoaderVisible()) return
        val view = host.bannerView() ?: return
        host.bannerTextView()?.text = host.bannerLabel(attemptTimestamps.size.coerceAtLeast(1), MAX_RECONNECTS_PER_WINDOW)
        if (!view.isVisible) {
            view.alpha = 0f
            view.isVisible = true
            view.animate().alpha(1f).setDuration(200).start()
        }
    }

    /**
     * Drops a still-pending banner show without animating anything — for teardown /
     * background-retention paths that just purge handlers.
     */
    fun cancelPendingBanner() {
        handler.removeCallbacks(bannerShowRunnable)
        bannerPending = false
    }

    fun hideBanner() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post { hideBanner() }
            return
        }
        handler.removeCallbacks(bannerShowRunnable)
        bannerPending = false
        val view = host.bannerView() ?: return
        if (!view.isVisible) return
        view.animate().alpha(0f).setDuration(180).withEndAction { view.isVisible = false }.start()
    }
}
