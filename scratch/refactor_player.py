import sys

file_path = r'e:\running project baxkups\lmsd working but loading issues\debxtrem\app\src\main\java\com\tvonnet\debridxtreamiptv\player\stabilized\PlayerActivity.kt'
with open(file_path, 'r', encoding='utf-8') as f:
    content = f.read()

# Replace Regexes
content = content.replace(
    '    private val subtitleUrlRegex = Regex("https?://\\\\S+", RegexOption.IGNORE_CASE)\n    private val languageCodeRegex = Regex("^[a-z]{2,3}(-[a-z0-9]{2,8})?$", RegexOption.IGNORE_CASE)\n',
    ''
)

# Replace properties
content = content.replace(
    '    private var preferredAudioLanguage: String? = null\n    private var preferredSubtitleLanguage: String? = null\n    private lateinit var episodeBrowserController: EpisodeBrowserController',
    '    private lateinit var trackManager: PlayerTrackManager\n    private lateinit var episodeBrowserController: EpisodeBrowserController'
)

# Replace onCreate assignment
content = content.replace(
    '        preferredAudioLanguage = settingsPreferences.getPreferredAudioLanguage()\n        preferredSubtitleLanguage = settingsPreferences.getPreferredSubtitleLanguage()\n',
    '        trackManager = PlayerTrackManager(this, settingsPreferences)\n'
)

# Replace applyDebridLanguagePreference
content = content.replace(
    '    private fun applyDebridLanguagePreference(language: String) {\n        preferredAudioLanguage = language\n        preferredSubtitleLanguage = language\n        settingsPreferences.savePreferredAudioLanguage(language)\n        settingsPreferences.savePreferredSubtitleLanguage(language)\n    }',
    '    private fun applyDebridLanguagePreference(language: String) {\n        trackManager.applyDebridLanguagePreference(language)\n    }'
)

# Replace initializePlayer parametersBuilder
content = content.replace(
    '            preferredAudioLanguage?.let { parametersBuilder = parametersBuilder.setPreferredAudioLanguage(it) }\n            preferredSubtitleLanguage?.let { parametersBuilder = parametersBuilder.setPreferredTextLanguage(it) }\n',
    '            trackManager.preferredAudioLanguage?.let { parametersBuilder = parametersBuilder.setPreferredAudioLanguage(it) }\n            trackManager.preferredSubtitleLanguage?.let { parametersBuilder = parametersBuilder.setPreferredTextLanguage(it) }\n'
)

# We will handle the large method blocks by slicing out the specific lines or using a more robust replacement.
import re

# buildMediaItem to showSubtitleSelection replacement
old_methods = r"""    private fun buildMediaItem\(url: String\): MediaItem \{
        val subtitleConfigs = buildSubtitleConfigurations\(subtitleEntries\)
.*?
    private fun showSubtitleSelection\(\) \{
.*?
        showManagedTrackDialog\(dialog\)
    \}"""

new_methods = """    private fun buildMediaItem(url: String): MediaItem {
        val subtitleConfigs = trackManager.buildSubtitleConfigurations(subtitleEntries)
        return if (subtitleConfigs.isEmpty()) {
            MediaItem.fromUri(url)
        } else {
            MediaItem.Builder()
                .setUri(url)
                .setSubtitleConfigurations(subtitleConfigs)
                .build()
        }
    }

    private fun showAudioSelection() {
        if (isInPictureInPictureMode) return
        val playerSnapshot = player ?: return
        trackManager.showAudioSelection(playerSnapshot) { dialog ->
            showManagedTrackDialog(dialog)
        }
    }

    private fun showSubtitleSelection() {
        if (isInPictureInPictureMode) return
        val playerSnapshot = player ?: return
        trackManager.showSubtitleSelection(playerSnapshot, subtitleEntries) { dialog ->
            showManagedTrackDialog(dialog)
        }
    }"""

content = re.sub(old_methods, new_methods, content, flags=re.DOTALL)

# captureManualTrackSelection and applyTrackIndexOverrides
old_overrides = r"""    private fun captureManualTrackSelection\(\) \{
        val p = player \?: return; val groups = p\.currentTracks\.groups;.*?if \(mod\) p\.trackSelectionParameters = params\.build\(\)
    \}"""

new_overrides = """    private fun captureManualTrackSelection() {
        val p = player ?: return
        val seriesId = intent.getStringExtra(EXTRA_SERIES_ID) ?: tmdbIdExtra ?: imdbIdExtra
        trackManager.captureManualTrackSelection(p, debridInfoHashExtra, seriesId)
    }

    private fun applyTrackIndexOverrides() {
        val p = player ?: return
        val seriesId = intent.getStringExtra(EXTRA_SERIES_ID) ?: tmdbIdExtra ?: imdbIdExtra
        trackManager.applyTrackIndexOverrides(p, debridInfoHashExtra, seriesId)
    }"""

content = re.sub(old_overrides, new_overrides, content, flags=re.DOTALL)

with open(file_path, 'w', encoding='utf-8') as f:
    f.write(content)
print('Phase 1 done')
