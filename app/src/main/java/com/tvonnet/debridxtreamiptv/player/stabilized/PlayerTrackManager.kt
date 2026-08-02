package com.tvonnet.debridxtreamiptv.player.stabilized

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.ui.DefaultTrackNameProvider
import com.tvonnet.debridxtreamiptv.R
import com.tvonnet.debridxtreamiptv.data.prefs.SettingsPreferences
import java.util.Locale

class PlayerTrackManager(
    private val context: Context,
    private val settingsPreferences: SettingsPreferences
) {
    private val subtitleUrlRegex = Regex("https?://\\S+", RegexOption.IGNORE_CASE)
    private val languageCodeRegex = Regex("^[a-z]{2,3}(-[a-z0-9]{2,8})?$", RegexOption.IGNORE_CASE)

    var preferredSubtitleLanguage: String? = null
        private set
    var preferredAudioLanguage: String? = null
        private set

    init {
        preferredSubtitleLanguage = settingsPreferences.getPreferredSubtitleLanguage()
        preferredAudioLanguage = settingsPreferences.getPreferredAudioLanguage()
    }

    private data class ParsedSubtitle(val url: String, val language: String?)
    private data class TrackDialogOption(
        val label: String,
        val disabled: Boolean = false,
        val override: TrackSelectionOverride? = null
    )

    fun buildSubtitleConfigurations(entries: List<String>): List<MediaItem.SubtitleConfiguration> {
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

    fun showAudioSelection(player: Player, onDialogReady: (android.app.Dialog) -> Unit) {
        showSinglePressTrackSelection(
            player = player,
            title = context.getString(R.string.player_audio_title),
            trackType = C.TRACK_TYPE_AUDIO,
            showDisableOption = false,
            onDialogReady = onDialogReady
        )
    }

    fun showSubtitleSelection(
        player: Player,
        subtitleEntries: List<String>,
        onDialogReady: (android.app.Dialog) -> Unit
    ) {
        val hasTextTracks = player.currentTracks.groups.any { group ->
            group.type == C.TRACK_TYPE_TEXT && group.isSupported
        }
        if (!hasTextTracks) {
            val message = if (subtitleEntries.isNotEmpty()) {
                context.getString(R.string.player_subtitles_loading)
            } else {
                context.getString(R.string.player_subtitles_none)
            }
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            return
        }

        showSinglePressTrackSelection(
            player = player,
            title = context.getString(R.string.player_settings_subtitles),
            trackType = C.TRACK_TYPE_TEXT,
            showDisableOption = true,
            onDialogReady = onDialogReady
        )
    }

    private fun showSinglePressTrackSelection(
        player: Player,
        title: String,
        trackType: Int,
        showDisableOption: Boolean,
        onDialogReady: (android.app.Dialog) -> Unit
    ) {
        val nameProvider = DefaultTrackNameProvider(context.resources)
        val options = mutableListOf<TrackDialogOption>()
        if (showDisableOption) {
            options += TrackDialogOption(context.getString(androidx.media3.ui.R.string.exo_track_selection_none), disabled = true)
        }
        options += TrackDialogOption(context.getString(androidx.media3.ui.R.string.exo_track_selection_auto))

        player.currentTracks.groups
            .filter { it.type == trackType }
            .forEach { group ->
                for (trackIndex in 0 until group.length) {
                    if (!group.isTrackSupported(trackIndex)) continue
                    options += TrackDialogOption(
                        label = nameProvider.getTrackName(group.getTrackFormat(trackIndex)),
                        override = TrackSelectionOverride(group.mediaTrackGroup, trackIndex)
                    )
                }
            }

        val parameters = player.trackSelectionParameters
        val selectedIndex = when {
            showDisableOption && parameters.disabledTrackTypes.contains(trackType) -> 0
            else -> options.indexOfFirst { option ->
                val override = option.override ?: return@indexOfFirst false
                parameters.overrides[override.mediaTrackGroup]?.trackIndices == override.trackIndices
            }.takeIf { it >= 0 } ?: if (showDisableOption) 1 else 0
        }

        lateinit var dialog: androidx.appcompat.app.AlertDialog
        val adapter = TrackSelectionAdapter(options.map { it.label }, selectedIndex) { which ->
            if (!player.isCommandAvailable(Player.COMMAND_SET_TRACK_SELECTION_PARAMETERS)) {
                dialog.dismiss()
                return@TrackSelectionAdapter
            }
            val option = options[which]
            val builder = player.trackSelectionParameters.buildUpon()
            
            // 1. Set explicit disable state map
            builder.setTrackTypeDisabled(trackType, option.disabled)
            
            // 2. Clear old state to wipe engine cache
            builder.clearOverridesOfType(trackType)
            
            if (option.override != null) {
                // 3. Inject explicit track override
                builder.addOverride(option.override)
                
                // 4. Force inject matching high-level language preferences to bypass selector ignore traps
                val trackFormat = option.override.mediaTrackGroup.getFormat(0)
                val trackLanguage = trackFormat.language
                
                if (!trackLanguage.isNullOrEmpty()) {
                    if (trackType == androidx.media3.common.C.TRACK_TYPE_AUDIO) {
                        builder.setPreferredAudioLanguage(trackLanguage)
                    } else if (trackType == androidx.media3.common.C.TRACK_TYPE_TEXT) {
                        builder.setPreferredTextLanguage(trackLanguage)
                    }
                }
            } else {
                // Clean language fallbacks if AUTO mode is toggled
                if (trackType == androidx.media3.common.C.TRACK_TYPE_AUDIO) {
                    builder.setPreferredAudioLanguage(null)
                } else if (trackType == androidx.media3.common.C.TRACK_TYPE_TEXT) {
                    builder.setPreferredTextLanguage(null)
                }
            }
            
            // 5. Atomic re-assignment to Media3 pipeline
            player.trackSelectionParameters = builder.build()
            android.util.Log.d("MEDIA3_BUG", "Post-assign overrides: " + player.trackSelectionParameters.overrides.size)

            // 6. Force hardware decoder flush to break Android TV audio sink lock
            if (player.isCurrentMediaItemDynamic) {
                // Live streams: quick play/pause toggle to force renderer re-evaluation
                player.playWhenReady = false
                player.playWhenReady = true
                android.util.Log.d("MEDIA3_BUG", "Live stream: play/pause toggle flush applied")
            } else {
                // VOD: seek-to-current to force decoder re-init without visible disruption
                val currentPos = player.currentPosition
                player.seekTo(currentPos)
                android.util.Log.d("MEDIA3_BUG", "VOD: seekTo($currentPos) flush applied")
            }

            dialog.dismiss()
        }
        dialog = androidx.appcompat.app.AlertDialog.Builder(context, R.style.Theme_DebridXtream_CinematicDialog)
            .setTitle(title)
            .setAdapter(adapter, adapter.asDialogClickListener())
            .create()
        onDialogReady(dialog)
    }

    fun applyDebridLanguagePreference(language: String) {
        preferredAudioLanguage = language
        preferredSubtitleLanguage = language
        settingsPreferences.savePreferredAudioLanguage(language)
        settingsPreferences.savePreferredSubtitleLanguage(language)
    }

    fun captureManualTrackSelection(
        player: Player,
        debridInfoHashExtra: String?,
        seriesId: String?
    ) {
        val groups = player.currentTracks.groups
        val (sAIdx, sALang) = findSelectedTrack(groups, C.TRACK_TYPE_AUDIO)
        val (sTIdx, sTLang) = findSelectedTrack(groups, C.TRACK_TYPE_TEXT)

        if (sALang != null) {
            preferredAudioLanguage = sALang
            settingsPreferences.savePreferredAudioLanguage(sALang)
        }
        if (sTLang != null) {
            preferredSubtitleLanguage = sTLang
            settingsPreferences.savePreferredSubtitleLanguage(sTLang)
        }

        settingsPreferences.saveLastTrackSelection(debridInfoHashExtra, sAIdx, sTIdx)
        if (seriesId != null) {
            settingsPreferences.saveSeriesTrackPreference(seriesId, sAIdx, sTIdx)
        }
    }

    fun applyTrackIndexOverrides(
        player: Player,
        debridInfoHashExtra: String?,
        seriesId: String?
    ) {
        val lastHash = settingsPreferences.getLastSelectionHash()
        val currentHash = debridInfoHashExtra

        var tAIdx = -1
        var tTIdx = -1

        if (lastHash != null && currentHash != null && lastHash.equals(currentHash, ignoreCase = true)) {
            tAIdx = settingsPreferences.getLastAudioIndex()
            tTIdx = settingsPreferences.getLastTextIndex()
        } else if (seriesId != null) {
            tAIdx = settingsPreferences.getSeriesAudioIndex(seriesId)
            tTIdx = settingsPreferences.getSeriesTextIndex(seriesId)
        }

        if (tAIdx == -1 && tTIdx == -1) return

        val groups = player.currentTracks.groups
        var params = player.trackSelectionParameters.buildUpon()
        var mod = false

        if (tAIdx != -1) {
            if (addTrackOverrideAt(groups, C.TRACK_TYPE_AUDIO, tAIdx, params)) mod = true
        }

        if (tTIdx != -1) {
            if (addTrackOverrideAt(groups, C.TRACK_TYPE_TEXT, tTIdx, params)) mod = true
        }

        if (mod) {
            player.trackSelectionParameters = params.build()
        }
    }

    // Walks the type's tracks in flat index order (the same order the capture above counts in)
    // and returns the selected track's flat index + language, or -1 to null when none is selected.
    private fun findSelectedTrack(
        groups: List<androidx.media3.common.Tracks.Group>,
        trackType: Int
    ): Pair<Int, String?> {
        var count = 0
        for (g in groups) {
            if (g.type != trackType) continue
            for (j in 0 until g.length) {
                if (g.isTrackSelected(j)) return count to g.getTrackFormat(j).language
                count++
            }
        }
        return -1 to null
    }

    // Adds an override for the track at the given flat index of that type; true when one was added.
    private fun addTrackOverrideAt(
        groups: List<androidx.media3.common.Tracks.Group>,
        trackType: Int,
        targetIndex: Int,
        params: androidx.media3.common.TrackSelectionParameters.Builder
    ): Boolean {
        var count = 0
        for (g in groups) {
            if (g.type != trackType) continue
            for (j in 0 until g.length) {
                if (count == targetIndex) {
                    params.addOverride(TrackSelectionOverride(g.mediaTrackGroup, j))
                    return true
                }
                count++
            }
        }
        return false
    }
}
