package com.tvonnet.debridxtreamiptv.data.prefs

import java.util.Locale

/**
 * S4: refuses to hand back a stored stream URL that belongs to a DIFFERENT IPTV provider.
 *
 * Continue Watching and Recent Live store the full playable URL, and an Xtream URL carries the
 * host, the username and the password inside it. After a change of provider that URL is not merely
 * stale — if the previous subscription is still live it keeps working, so the customer's device
 * quietly goes on streaming from the server they moved away from, using credentials they may have
 * since replaced. That is worse than a broken row, because nothing about it looks wrong.
 *
 * [ServerDataReset] already clears both lists on a switch. This is the second lock: applied on the
 * READ, it covers a purge step that failed, a build where a new list is added and nobody remembers
 * to clear it, and the window between the credentials changing and the purge running.
 *
 * Deliberately permissive in two cases, because a guard that hides good rows is its own bug:
 *  - we do not know the current provider yet (the session has not been built this launch),
 *  - the entry is a debrid one, whose host is a debrid CDN and has nothing to do with the provider.
 */
object ProviderUrlGuard {

    /** Value of `ContinueWatchingItem.source` for debrid rows. */
    private const val SOURCE_DEBRID = "debrid"

    /**
     * @param source the entry's source, or null for a list that is IPTV-only.
     * @return false ONLY when this is an IPTV entry whose host is known to differ from the
     *   provider the app is currently pointed at.
     */
    fun belongsToCurrentProvider(
        streamUrl: String?,
        source: String?,
        currentBaseUrl: String = com.tvonnet.debridxtreamiptv.util.GlobalConfig.baseUrl,
    ): Boolean {
        if (source.equals(SOURCE_DEBRID, ignoreCase = true)) return true
        val entryHost = hostOf(streamUrl)
        if (entryHost.isEmpty()) return true // nothing to compare; the row is judged elsewhere
        val currentHost = hostOf(currentBaseUrl)
        if (currentHost.isEmpty()) return true // provider unknown this launch — never hide on a guess
        return entryHost == currentHost
    }

    /**
     * host[:port], lower-cased. Hand-parsed for the same reason [ServerIdentity] is: this must stay
     * a plain unit-testable object, and the two have to agree on what "the same server" means.
     */
    private fun hostOf(url: String?): String {
        val raw = url?.trim().orEmpty()
        if (raw.isEmpty()) return ""
        val afterScheme = raw.substringAfter("://", raw)
        return afterScheme.substringBefore('/')
            .substringBefore('?')
            .substringBefore('#')
            .substringAfterLast('@')
            .lowercase(Locale.ROOT)
    }
}
