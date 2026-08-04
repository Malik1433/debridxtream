package com.tvonnet.debridxtreamiptv.ui.sources

import android.content.Context
import android.content.SharedPreferences
import java.util.concurrent.ConcurrentHashMap

/**
 * H6: which addon FAMILY actually delivers a first frame when its link is played.
 *
 * Persistent cousin of [SessionSourcePreference]: counts per provider family survive
 * restarts (SharedPreferences), because a flaky addon is flaky next week too. The score
 * is a Laplace-smoothed success rate `(s+1)/(s+f+2)` and stays NEUTRAL until a family
 * has [MIN_OBSERVATIONS] — a single lucky or unlucky playback must not reorder anything.
 *
 * Deliberately used ONLY as the same-file tiebreak in `groupStreamsByFile` (which addon's
 * copy becomes the row) — it never re-ranks different files, and it never hides a row.
 *
 * Success = the player rendered a first frame for the source. Failure = the source died
 * TERMINALLY before ever producing a frame. Mid-playback deaths are not counted: a link
 * that played 90 minutes and stalled is a network story, not an addon story.
 */
object AddonReliabilityStore {

    private const val PREFS_NAME = "addon_reliability"
    private const val MIN_OBSERVATIONS = 5
    private const val NEUTRAL = 0.5
    // Halve both counts past this total so old history can be outgrown by new behaviour.
    private const val DECAY_AT_TOTAL = 200

    /** family -> [successes, failures]; the array is replaced, never mutated in place. */
    private val counts = ConcurrentHashMap<String, IntArray>()

    @Volatile
    private var prefs: SharedPreferences? = null

    /** Idempotent; call off the main thread (loads the prefs file). */
    fun init(context: Context) {
        if (prefs != null) return
        synchronized(this) {
            if (prefs != null) return
            val loaded = context.applicationContext
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            loaded.all.forEach { (family, value) ->
                parseCounts(value as? String)?.let { counts[family] = it }
            }
            prefs = loaded
        }
    }

    fun recordSuccess(context: Context, providerLabel: String?) = record(context, providerLabel, success = true)

    fun recordFailure(context: Context, providerLabel: String?) = record(context, providerLabel, success = false)

    /** Laplace score for the stream's provider family; NEUTRAL until enough evidence. */
    fun scoreFor(providerLabel: String?): Double {
        val family = familyOf(providerLabel) ?: return NEUTRAL
        val c = counts[family] ?: return NEUTRAL
        return scoreOf(c[0], c[1])
    }

    internal fun scoreOf(successes: Int, failures: Int): Double {
        if (successes + failures < MIN_OBSERVATIONS) return NEUTRAL
        return (successes + 1.0) / (successes + failures + 2.0)
    }

    /**
     * "StremThru Torz: Movie.Name…" and "StremThru Torz" are the same FAMILY — the label's
     * head before any ':' payload, case-folded. Null for blank labels (never recorded).
     */
    internal fun familyOf(providerLabel: String?): String? =
        providerLabel?.substringBefore(':')?.trim()?.lowercase()?.takeIf { it.isNotBlank() }

    private fun record(context: Context, providerLabel: String?, success: Boolean) {
        val family = familyOf(providerLabel) ?: return
        init(context)
        val next = counts.compute(family) { _, old ->
            var s = (old?.get(0) ?: 0) + if (success) 1 else 0
            var f = (old?.get(1) ?: 0) + if (success) 0 else 1
            if (s + f > DECAY_AT_TOTAL) { s /= 2; f /= 2 }
            intArrayOf(s, f)
        }
        if (next != null) {
            prefs?.edit()?.putString(family, "${next[0]},${next[1]}")?.apply()
        }
    }

    private fun parseCounts(value: String?): IntArray? {
        val parts = value?.split(',') ?: return null
        if (parts.size != 2) return null
        val s = parts[0].toIntOrNull() ?: return null
        val f = parts[1].toIntOrNull() ?: return null
        return intArrayOf(s, f)
    }
}
