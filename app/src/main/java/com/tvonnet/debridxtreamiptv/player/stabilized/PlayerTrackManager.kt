package com.tvonnet.debridxtreamiptv.player.stabilized

import android.app.Dialog
import android.net.Uri
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.DefaultTrackNameProvider
import androidx.media3.ui.TrackSelectionDialogBuilder
import com.tvonnet.debridxtreamiptv.R
import com.tvonnet.debridxtreamiptv.data.model.ContentType
import com.tvonnet.debridxtreamiptv.player.stabilized.PlaybackSource
import kotlinx.coroutines.launch
import java.lang.ref.WeakReference
import java.util.Locale

class PlayerTrackManager(activity: PlayerActivity) {
    private val activityRef = WeakReference(activity)
    private var activeDialog: Dialog? = null

    private val subtitleUrlRegex = Regex("https?://\\S+", RegexOption.IGNORE_CASE)
    private val languageCodeRegex = Regex("^[a-z]{2,3}(-[a-z0-9]{2,8})?$", RegexOption.IGNORE_CASE)

    private data class ParsedSubtitle(val url: String, val language: String?)

    fun cleanup() {
        activeDialog?.dismiss()
        activeDialog = null
        activityRef.clear()
    }

    fun buildMediaItem(url: String, subtitleEntries: List<String>): MediaItem {
        val subtitleConfigs = buildSubtitleConfigurations(subtitleEntries)
        return if (subtitleConfigs.isEmpty()) {
            MediaItem.fromUri(url)
        } else {
            MediaItem.Builder()
                .setUri(url)
                .setSubtitleConfigurations(subtitleConfigs)
                .build()
        }
    }

    private fun buildSubtitleConfigurations(entries: List<String>): List<MediaItem.SubtitleConfiguration> {
        if (entries.isEmpty()) return emptyList()
        val configs = mutableListOf<MediaItem.SubtitleConfiguration>()
        for (entry in entries) {
            val parsed = parseSubtitleEntry(entry) ?: continue
            val config = MediaItem.SubtitleConfiguration.Builder(Uri.parse(parsed.url))
                .setMimeType(guessSubtitleMimeType(parsed.url))
                .setLanguage(parsed.language)
                .build()
            configs.add(config)
        }
        return configs.distinctBy { it.uri }
    }

    private fun parseSubtitleEntry(entry: String): ParsedSubtitle? {
        val trimmed = entry.trim()
        if (trimmed.isBlank()) return null
        val urlMatch = subtitleUrlRegex.find(trimmed) ?: return null
        val url = urlMatch.value
        val language = extractSubtitleLanguage(trimmed, url)
        return ParsedSubtitle(url, language)
    }

    private fun extractSubtitleLanguage(raw: String, url: String): String? {
        val stripped = raw.replace(url, " ")
            .replace('|', ' ')
            .replace(':', ' ')
            .replace(',', ' ')
        val token = stripped.trim().split(Regex("\\s+")).firstOrNull()
        val fromToken = normalizeLanguageCode(token)
        if (fromToken != null) return fromToken
        val uri = Uri.parse(url)
        val fromQuery = normalizeLanguageCode(uri.getQueryParameter("lang"))
            ?: normalizeLanguageCode(uri.getQueryParameter("language"))
        return fromQuery
    }

    private fun normalizeLanguageCode(value: String?): String? {
        val cleaned = value
            ?.trim()
            ?.lowercase(Locale.US)
            ?.replace('_', '-') ?: return null
        if (cleaned.isBlank() || !languageCodeRegex.matches(cleaned)) return null
        return cleaned
    }

    private fun guessSubtitleMimeType(url: String): String {
        val normalized = url.substringBefore('?').substringBefore('#').lowercase(Locale.US)
        return when {
            normalized.endsWith(".vtt") -> MimeTypes.TEXT_VTT
            normalized.endsWith(".srt") -> MimeTypes.APPLICATION_SUBRIP
            normalized.endsWith(".ass") || normalized.endsWith(".ssa") -> MimeTypes.TEXT_SSA
            normalized.endsWith(".ttml") || normalized.endsWith(".dfxp") -> MimeTypes.APPLICATION_TTML
            else -> MimeTypes.APPLICATION_SUBRIP
        }
    }

    fun showAudioSelection() {
        val activity = activityRef.get() ?: return
        if (activity.isInPictureInPictureMode) return
        val playerSnapshot = activity.player ?: return
        
        val dialog = TrackSelectionDialogBuilder(
            activity,
            activity.getString(R.string.player_audio_title),
            playerSnapshot,
            C.TRACK_TYPE_AUDIO
        )
            .setAllowAdaptiveSelections(false)
            .setAllowMultipleOverrides(false)
            .setTrackNameProvider(DefaultTrackNameProvider(activity.resources))
            .build()
        activeDialog = dialog
        dialog.show()
    }

    fun showSubtitleSelection(subtitleEntries: List<String>) {
        val activity = activityRef.get() ?: return
        if (activity.isInPictureInPictureMode) return
        val playerSnapshot = activity.player ?: return
        val hasTextTracks = playerSnapshot.currentTracks.groups.any { group ->
            group.type == C.TRACK_TYPE_TEXT && group.isSupported
        }
        if (!hasTextTracks) {
            val message = if (subtitleEntries.isNotEmpty()) {
                activity.getString(R.string.player_subtitles_loading)
            } else {
                activity.getString(R.string.player_subtitles_none)
            }
            Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()
            return
        }

        val dialog = TrackSelectionDialogBuilder(
            activity,
            activity.getString(R.string.player_settings_subtitles),
            playerSnapshot,
            C.TRACK_TYPE_TEXT
        )
            .setShowDisableOption(true)
            .setAllowAdaptiveSelections(false)
            .setAllowMultipleOverrides(false)
            .setTrackNameProvider(DefaultTrackNameProvider(activity.resources))
            .build()
        activeDialog = dialog
        dialog.show()
    }

    fun showLanguageSelection() {
        val activity = activityRef.get() ?: return
        if (activity.isInPictureInPictureMode) return
        activity.lifecycleScope.launch {
            val options = if (activity.playbackSource == PlaybackSource.DEBRID && activity.contentType == ContentType.MOVIE) {
                runCatching {
                    activity.viewModel.getDebridLanguageOptions(
                        streamId = activity.tmdbIdExtra ?: activity.contentId,
                        title = activity.originalTitle,
                        imdbId = activity.imdbIdExtra,
                        sourceProfile = activity.currentDebridSourceProfile()
                    )
                }.getOrElse { emptyList() }
            } else {
                activity.debridLanguagesExtra.orEmpty()
            }

            val distinctOptions = options
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .distinct()

            if (distinctOptions.isEmpty()) {
                Toast.makeText(activity, "No language options available", Toast.LENGTH_SHORT).show()
                return@launch
            }

            val dialog = AlertDialog.Builder(activity)
                .setTitle(R.string.player_language_title)
                .setItems(distinctOptions.toTypedArray()) { dialog, which ->
                    val selected = distinctOptions[which]
                    applyDebridLanguagePreference(selected)
                    dialog.dismiss()
                }
                .create()
            activeDialog = dialog
            dialog.show()
        }
    }

    private fun applyDebridLanguagePreference(language: String) {
        val activity = activityRef.get() ?: return
        activity.preferredAudioLanguage = language
        activity.preferredSubtitleLanguage = language
        activity.settingsPreferences.savePreferredAudioLanguage(language)
        activity.settingsPreferences.savePreferredSubtitleLanguage(language)

        if (activity.playbackSource == PlaybackSource.DEBRID && activity.contentType == ContentType.MOVIE) {
            activity.isResolvingDebrid = true
            val profile = activity.currentDebridSourceProfile()?.copy(languages = listOf(language))
            activity.debridLanguagesExtra = profile?.languages?.let { ArrayList(it) }
            activity.viewModel.refreshDebridMovieSource(
                streamId = activity.tmdbIdExtra ?: activity.contentId,
                title = activity.originalTitle,
                imdbId = activity.imdbIdExtra,
                sourceProfile = profile
            )
        } else {
            Toast.makeText(activity, "Language preference saved", Toast.LENGTH_SHORT).show()
        }
    }

    fun showVideoSelection() {
        val activity = activityRef.get() ?: return
        if (activity.isInPictureInPictureMode) return
        val playerSnapshot = activity.player ?: return
        val hasVideoTracks = playerSnapshot.currentTracks.groups.any { group ->
            group.type == C.TRACK_TYPE_VIDEO && group.isSupported
        }
        if (!hasVideoTracks) {
            Toast.makeText(activity, "No video tracks available", Toast.LENGTH_SHORT).show()
            return
        }

        val dialog = TrackSelectionDialogBuilder(
            activity,
            activity.getString(R.string.player_video_title),
            playerSnapshot,
            C.TRACK_TYPE_VIDEO
        )
            .setAllowAdaptiveSelections(true)
            .setAllowMultipleOverrides(false)
            .setTrackNameProvider(DefaultTrackNameProvider(activity.resources))
            .build()
        activeDialog = dialog
        dialog.show()
    }

    fun cycleResizeMode() {
        val activity = activityRef.get() ?: return
        val playerView = activity.playerView
        val nextMode = when (playerView.resizeMode) {
            AspectRatioFrameLayout.RESIZE_MODE_FIT -> AspectRatioFrameLayout.RESIZE_MODE_FILL
            AspectRatioFrameLayout.RESIZE_MODE_FILL -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> AspectRatioFrameLayout.RESIZE_MODE_FIXED_WIDTH
            AspectRatioFrameLayout.RESIZE_MODE_FIXED_WIDTH -> AspectRatioFrameLayout.RESIZE_MODE_FIXED_HEIGHT
            else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
        }
        playerView.resizeMode = nextMode
        
        val modeName = when(nextMode) {
            AspectRatioFrameLayout.RESIZE_MODE_FIT -> "Fit"
            AspectRatioFrameLayout.RESIZE_MODE_FILL -> "Stretch"
            AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> "Zoom"
            AspectRatioFrameLayout.RESIZE_MODE_FIXED_WIDTH -> "Fixed Width"
            AspectRatioFrameLayout.RESIZE_MODE_FIXED_HEIGHT -> "Fixed Height"
            else -> "Fit"
        }
        Toast.makeText(activity, "Resize Mode: $modeName", Toast.LENGTH_SHORT).show()
    }
}
