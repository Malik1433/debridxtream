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
import androidx.media3.ui.TrackSelectionDialogBuilder
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
        val dialog = TrackSelectionDialogBuilder(
            context,
            context.getString(R.string.player_audio_title),
            player,
            C.TRACK_TYPE_AUDIO
        )
            .setAllowAdaptiveSelections(false)
            .setAllowMultipleOverrides(false)
            .setTrackNameProvider(DefaultTrackNameProvider(context.resources))
            .build()
        onDialogReady(dialog)
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

        val dialog = TrackSelectionDialogBuilder(
            context,
            context.getString(R.string.player_settings_subtitles),
            player,
            C.TRACK_TYPE_TEXT
        )
            .setShowDisableOption(true)
            .setAllowAdaptiveSelections(false)
            .setAllowMultipleOverrides(false)
            .setTrackNameProvider(DefaultTrackNameProvider(context.resources))
            .build()
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
        var sAIdx = -1
        var sALang: String? = null
        var sTIdx = -1
        var sTLang: String? = null

        var aC = 0
        for (g in groups) {
            if (g.type == C.TRACK_TYPE_AUDIO) {
                for (j in 0 until g.length) {
                    if (g.isTrackSelected(j)) {
                        sAIdx = aC
                        sALang = g.getTrackFormat(j).language
                        break
                    }
                    aC++
                }
            }
            if (sAIdx != -1) break
        }

        var tC = 0
        for (g in groups) {
            if (g.type == C.TRACK_TYPE_TEXT) {
                for (j in 0 until g.length) {
                    if (g.isTrackSelected(j)) {
                        sTIdx = tC
                        sTLang = g.getTrackFormat(j).language
                        break
                    }
                    tC++
                }
            }
            if (sTIdx != -1) break
        }

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
            var aC = 0
            for (g in groups) {
                if (g.type == C.TRACK_TYPE_AUDIO) {
                    for (j in 0 until g.length) {
                        if (aC == tAIdx) {
                            params.addOverride(TrackSelectionOverride(g.mediaTrackGroup, j))
                            mod = true
                            break
                        }
                        aC++
                    }
                }
                if (mod) break
            }
        }

        if (tTIdx != -1) {
            var tC = 0
            var tMod = false
            for (g in groups) {
                if (g.type == C.TRACK_TYPE_TEXT) {
                    for (j in 0 until g.length) {
                        if (tC == tTIdx) {
                            params.addOverride(TrackSelectionOverride(g.mediaTrackGroup, j))
                            mod = true
                            tMod = true
                            break
                        }
                        tC++
                    }
                }
                if (tMod) break
            }
        }

        if (mod) {
            player.trackSelectionParameters = params.build()
        }
    }
}
