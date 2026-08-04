package com.tvonnet.debridxtreamiptv.ui.sources

import com.tvonnet.debridxtreamiptv.data.debrid.repository.toLegacyCachedFlag
import com.tvonnet.debridxtreamiptv.data.repository.MovieSource

/**
 * H2: silent same-file failover (SOURCE_LIST_QUALITY_PLAN P2), shared by the movie and
 * series playback controllers.
 *
 * A dead link should retry the SAME file from another addon's copy before the file is
 * given up on — today one failure blacklists the whole file even when three other addons
 * still offer it. Alternate retries are deliberately SILENT (no toast) and have their own
 * small budget ([MAX_ALTERNATE_ATTEMPTS]) separate from MAX_AUTO_FALLBACK_ATTEMPTS,
 * which counts hops between DIFFERENT files.
 */
object SourceAlternates {

    /** Same-file retries per failure, before the file is declared dead. */
    const val MAX_ALTERNATE_ATTEMPTS = 2

    /**
     * The [triedCount]-th playable alternate of [source], as a playable copy: delivery
     * fields (directSource/headers/provider/sourceType/sourceName/cache) swapped in,
     * the stream identity — including `stream_id` — kept, so selection/focus/blacklists
     * still refer to the same file. Null when the budget or the copies run out.
     */
    fun next(source: MovieSource, triedCount: Int): MovieSource? {
        if (triedCount >= MAX_ALTERNATE_ATTEMPTS) return null
        val playable = source.alternates.filter { !it.directSource.isNullOrBlank() }
        val alternate = playable.getOrNull(triedCount) ?: return null
        return source.copy(
            stream = source.stream.copy(direct_source = alternate.directSource),
            provider = alternate.provider,
            sourceType = alternate.sourceType,
            sourceName = alternate.sourceName,
            headers = alternate.headers,
            cacheStatus = alternate.cacheStatus,
            isCached = alternate.cacheStatus.toLegacyCachedFlag(),
        )
    }
}

/**
 * Per-controller failover state: which file we are retrying and how many copies were
 * tried. Kept out of the (at-ceiling) playback controllers — they only wire it.
 */
class AlternateFailover {

    private var attempts = 0
    private var streamId: String? = null

    /**
     * The same-file alternate to try next for [failedStreamId], or null when there is
     * none / the budget is spent — the caller then blacklists the file and falls back
     * to its normal different-file advance.
     */
    fun nextFor(failedStreamId: String, sources: List<MovieSource>): MovieSource? {
        val source = sources.firstOrNull { it.stream.stream_id == failedStreamId } ?: run {
            reset()
            return null
        }
        val tried = if (streamId == failedStreamId) attempts else 0
        val next = SourceAlternates.next(source, tried)
        if (next == null) {
            reset()
            return null
        }
        streamId = failedStreamId
        attempts = tried + 1
        return next
    }

    fun reset() {
        attempts = 0
        streamId = null
    }
}
