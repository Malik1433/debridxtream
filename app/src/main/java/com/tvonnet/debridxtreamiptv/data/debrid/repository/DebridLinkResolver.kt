package com.tvonnet.debridxtreamiptv.data.debrid.repository

import com.tvonnet.debridxtreamiptv.data.Result
import com.tvonnet.debridxtreamiptv.data.Result.Error
import com.tvonnet.debridxtreamiptv.data.Result.Success
import com.tvonnet.debridxtreamiptv.util.SensitiveLogRedactor

/**
 * An addon source → a URL the player can open.
 *
 * **K2, 2026-09-05: this used to be the Real-Debrid magnet chain** — add-magnet, poll until the
 * torrent is ready, pick the wanted file, unrestrict, with a rate limiter and cooldowns around it.
 * Real-Debrid is gone (owner decision), and removing that chain is not the loss it sounds like:
 *
 *  - The addons in use — StremThru and Debridio — are debrid PROXIES. They do the resolving on
 *    their side and hand back a plain HTTP link, which is what [DebridUrlRules.isDirectStreamUrl]
 *    recognises and what [resolve] returns straight through. That is the only path a play has
 *    taken on this account for a long time.
 *  - Every other branch was already dead **at runtime**, not just unused: the old code called
 *    `checkAuth()` before any Real-Debrid work, and with no stored token that returned
 *    `NotAuthenticatedException("Real-Debrid configuration missing")`. A magnet could not be
 *    resolved yesterday either — it just failed with a message about a service the product no
 *    longer has.
 *
 * So what changed for a user is the wording of a failure that already happened, and what changed
 * for the code is ~360 lines and nine files. If an addon ever starts handing back magnets or
 * `.torrent` links instead of direct ones, this returns a clear error rather than pretending.
 */
internal class DebridLinkResolver {

    /**
     * @param allowDirectStreamUrlPassthrough false when replaying a STORED url (a resume), because
     *   the stored link may have expired. There is no re-resolver behind it any more, so this now
     *   says so instead of failing on a missing Real-Debrid session. The resume contract is
     *   unchanged: play the link you hold, and repair only after the player rejects it — by asking
     *   the addon again, which is a fresh source list, not a call from here.
     */
    @Suppress("LongParameterList", "UNUSED_PARAMETER") // mirrors the repository API its callers use
    suspend fun resolve(
        infoHash: String?,
        magnet: String?,
        seasonNumber: Int?,
        episodeNumber: Int?,
        episodeTitle: String?,
        allowDirectStreamUrlPassthrough: Boolean,
        bypassCache: Boolean
    ): Result<String> {
        if (infoHash.isNullOrBlank() && magnet.isNullOrBlank()) {
            return Error(IllegalArgumentException("No source URL provided"))
        }

        val directStreamUrl = directStreamUrlOf(magnet)
            ?: return Error(
                UnsupportedOperationException(
                    "This source is not a direct addon link. Pick another source."
                )
            )

        if (!allowDirectStreamUrlPassthrough) {
            return Error(
                IllegalStateException("Stored link needs a fresh source list before it can be replayed")
            )
        }

        android.util.Log.d(
            "DebridPlayback",
            "Direct addon URL, passthrough: ${SensitiveLogRedactor.describeUrl(directStreamUrl)}"
        )
        return Success(directStreamUrl)
    }

    /** An addon URL that is already playable, minus `.torrent` files, which nothing can open. */
    private fun directStreamUrlOf(magnet: String?): String? = magnet?.takeIf {
        DebridUrlRules.isDirectStreamUrl(it) && !it.endsWith(".torrent", ignoreCase = true)
    }
}
