package com.tvonnet.debridxtreamiptv.ui.live

/**
 * The channel list fullscreen zaps through, lifted out of [LivePlaybackLauncher] (roadmap B3).
 *
 * Channel up/down in the player walks this list, so its ORDER is the user-visible behaviour: it has
 * to match the category as the provider lists it, not the order the paging adapter happened to have
 * loaded. Hence cached-first, with whatever the adapter has already paged in appended, and the
 * channel actually being launched guaranteed to be in it — a zap list that omits the current
 * channel would make the first press jump somewhere unrelated.
 *
 * Behaviour-preserving: the body is the launcher's merge, moved verbatim.
 */
object LiveZapList {

    fun merge(
        cachedIds: List<String>,
        snapshotIds: List<String>,
        currentStreamId: String?
    ): ArrayList<String> {
        val merged = linkedSetOf<String>()
        cachedIds.forEach { merged.add(it) }
        snapshotIds.forEach { merged.add(it) }
        currentStreamId?.let { merged.add(it) }
        return ArrayList(merged)
    }
}
