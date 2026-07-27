package com.tvonnet.debridxtreamiptv.data.debrid.repository

import com.tvonnet.debridxtreamiptv.data.debrid.model.RealDebridTorrentFile
import com.tvonnet.debridxtreamiptv.data.debrid.model.RealDebridTorrentInfoResponse

/** Which episode the caller actually wants out of a multi-file torrent. */
data class EpisodeHint(
    val season: Int,
    val episode: Int,
    val title: String?
)

/**
 * C6: picking the right file out of a torrent — pure scoring over filenames.
 *
 * A season pack contains forty files and the provider names them however it likes, so this decides
 * both *which files to select* on Real-Debrid and *which resolved link to play*. Getting it wrong
 * is not subtle: the viewer gets the wrong episode, or a sample clip, or nothing at all.
 *
 * Two behaviours here exist because of shipped bugs and must not be "cleaned up":
 *  - **A3 fallback:** when a hinted torrent has no filename matching SxxEyy (absolute numbering,
 *    unusual naming), [pickVideoLink] falls back to the largest selected video rather than
 *    returning null. Returning null failed the whole resolution with "episode not found" even
 *    though a playable video was sitting right there.
 *  - **A2:** `.m4v` counts as video. It was accepted as a direct stream URL but rejected here, so a
 *    `.m4v`-only torrent sat in `waiting_files_selection` until it timed out.
 *
 * Moved verbatim from `DebridPlaybackRepository`.
 */
object TorrentFileMatcher {

    private data class FileScore(val file: RealDebridTorrentFile, val score: Int)

    private data class LinkScore(val index: Int, val score: Int, val sizeBytes: Long)

    /** File ids to hand to Real-Debrid's select-files call. Empty means "nothing playable". */
    fun selectFileIds(files: List<RealDebridTorrentFile>?, hint: EpisodeHint?): List<Int> {
        val videoFiles = files.orEmpty()
            .filter { it.id != null && isVideoFile(it.path) }

        if (videoFiles.isEmpty()) return emptyList()
        if (hint == null) {
            return videoFiles.mapNotNull { it.id }
        }

        val scoredFiles = videoFiles.map { file ->
            FileScore(file, scoreEpisodeMatch(file.path, hint))
        }
        val bestScore = scoredFiles.maxOf { it.score }
        if (bestScore > 0) {
            val bestCandidates = scoredFiles.filter { it.score == bestScore }
            val best = bestCandidates.maxWithOrNull(
                compareBy<FileScore> { it.file.bytes ?: 0L }
            )
            return best?.file?.id?.let { listOf(it) } ?: emptyList()
        }

        val fallback = videoFiles.maxByOrNull { it.bytes ?: 0L }?.id
        return fallback?.let { listOf(it) } ?: emptyList()
    }

    /** The link, out of the torrent's resolved links, that matches the wanted episode. */
    fun pickVideoLink(torrentInfo: RealDebridTorrentInfoResponse, hint: EpisodeHint?): String? {
        val links = torrentInfo.links ?: return null
        if (hint == null || torrentInfo.files.isNullOrEmpty()) {
            return links.firstOrNull()
        }

        val selectedFiles = torrentInfo.files
            .filter { it.selected == 1 && it.id != null }

        if (selectedFiles.isEmpty()) {
            return links.firstOrNull()
        }

        val scoredLinks = selectedFiles.mapIndexed { index, file ->
            LinkScore(
                index = index,
                score = scoreEpisodeMatch(file.path, hint),
                sizeBytes = file.bytes ?: 0L
            )
        }

        val best = scoredLinks.maxWithOrNull(
            compareBy<LinkScore> { it.score }.thenBy { it.sizeBytes }
        )

        return if (best != null && best.score > 0) {
            links.getOrNull(best.index) ?: links.firstOrNull()
        } else {
            // A3: hinted torrent where no filename matches SxxEyy (absolute numbering,
            // unusual naming). Returning null here failed the WHOLE resolution with
            // "episode not found" even though a playable video exists — fall back to
            // the largest selected video file (single-episode torrents: always right;
            // season packs: best-effort instead of a guaranteed dead-end).
            val largest = scoredLinks.maxByOrNull { it.sizeBytes }
            largest?.let { links.getOrNull(it.index) } ?: links.firstOrNull()
        }
    }

    /**
     * How well a filename matches the wanted episode. 0 = no evidence (and samples/trailers are
     * pinned at 0 so they can never win); higher = a more explicit match.
     */
    fun scoreEpisodeMatch(path: String?, hint: EpisodeHint): Int {
        if (path.isNullOrBlank()) return 0

        val name = path.substringAfterLast('/').lowercase()
        if (name.contains("sample") || name.contains("trailer") || name.contains("extras")) {
            return 0
        }

        return maxOf(
            scoreNumberedPatterns(name, hint.season, hint.episode),
            scoreWordedPatterns(name, hint.season, hint.episode),
            scoreTitleMatch(name, hint.title),
        )
    }

    /** "s01e02", "s01.e02", and the "1x02" / "1x02-03" form. Double episodes count for either. */
    private fun scoreNumberedPatterns(name: String, season: Int, episode: Int): Int {
        var score = 0

        val sxePattern = Regex("\\bs0*(\\d{1,2})\\s*[._ -]*e0*(\\d{1,2})(?:[._ -]*e0*(\\d{1,2}))?\\b")
        sxePattern.findAll(name).forEach { match ->
            val s = match.groupValues[1].toIntOrNull()
            if (s == season) {
                val e1 = match.groupValues[2].toIntOrNull()
                val e2 = match.groupValues.getOrNull(3)?.toIntOrNull()
                if (e1 == episode || e2 == episode) {
                    score = maxOf(score, 100)
                }
            }
        }

        val xPattern = Regex("\\b(\\d{1,2})x(\\d{1,2})(?:-(\\d{1,2}))?\\b")
        xPattern.findAll(name).forEach { match ->
            val s = match.groupValues[1].toIntOrNull()
            val e1 = match.groupValues[2].toIntOrNull()
            val e2 = match.groupValues.getOrNull(3)?.toIntOrNull()
            if (s == season && (e1 == episode || e2 == episode)) {
                score = maxOf(score, 95)
            }
        }

        return score
    }

    /** "Season 1 Episode 2" spelled out, and the weaker episode-only "Ep 2". */
    private fun scoreWordedPatterns(name: String, season: Int, episode: Int): Int {
        val seasonWord = Regex("\\bseason\\s*0*$season\\b")
        val episodeWord = Regex("\\bepisode\\s*0*$episode\\b")
        if (seasonWord.containsMatchIn(name) && episodeWord.containsMatchIn(name)) {
            return 90
        }
        val epOnly = Regex("\\b(ep|episode)\\s*0*$episode\\b")
        if (epOnly.containsMatchIn(name)) {
            return 60
        }
        return 0
    }

    /** Last resort: the episode title's own words appearing in the filename. */
    private fun scoreTitleMatch(name: String, title: String?): Int {
        if (title.isNullOrBlank()) return 0
        val words = title.lowercase()
            .split(Regex("[^a-z0-9]+"))
            .filter { it.length >= 4 }

        if (words.isEmpty()) return 0

        val matches = words.count { name.contains(it) }
        return when {
            matches == words.size -> 50
            matches >= 2 -> 40
            matches == 1 -> 30
            else -> 0
        }
    }

    fun isVideoFile(path: String?): Boolean {
        if (path.isNullOrBlank()) return false
        val lower = path.lowercase()
        return VIDEO_EXTENSIONS.any { lower.endsWith(it) }
    }

    private val VIDEO_EXTENSIONS = listOf(
        ".mkv", ".mp4", ".avi", ".mov", ".ts", ".m2ts", ".wmv", ".flv", ".webm",
        // A2: .m4v was accepted by isDirectStreamUrl but rejected here — a
        // .m4v-only torrent stalled in waiting_files_selection until timeout.
        ".m4v",
    )
}
