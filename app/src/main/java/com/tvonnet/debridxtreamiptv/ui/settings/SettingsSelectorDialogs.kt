package com.tvonnet.debridxtreamiptv.ui.settings

import com.tvonnet.debridxtreamiptv.R

import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment

/**
 * C1-b: every **chooser** on the settings screen — the single-choice pickers, the Stremio addon
 * manage/add dialogs, and the legacy Real-Debrid logout confirmation.
 *
 * All of it is the same shape (build an AlertDialog, write the chosen value to [viewModel],
 * dismiss), which is why it dominated [SettingsFragment] by line count while carrying almost no
 * decision. What little decision there is — which row starts checked, which URLs are acceptable —
 * lives in [SettingsOptionLabels] and is unit-tested there.
 *
 * Bodies moved verbatim; only `this`/field references were rebound to [fragment] and [viewModel].
 */
class SettingsSelectorDialogs(
    private val fragment: Fragment,
    private val viewModel: SettingsViewModel,
) {
    private val context get() = fragment.requireContext()

    /**
     * The app's own language. `AppCompatDelegate` is used rather than the platform API on
     * purpose: it carries per-app locales back to older Androids, so a Fire TV — which has no
     * system per-app language screen at all — can change language here too. AppCompat persists
     * the choice itself and recreates the visible activities, so nothing else has to store it.
     */
    fun showAppLanguageSelector(current: String) {
        AlertDialog.Builder(context)
            .setTitle(R.string.c_app_language)
            .setSingleChoiceItems(
                SettingsOptionLabels.appLanguageLabels,
                SettingsOptionLabels.appLanguageIndex(current)
            ) { dialog, which ->
                val tag = SettingsOptionLabels.appLanguageValues[which]
                dialog.dismiss()
                androidx.appcompat.app.AppCompatDelegate.setApplicationLocales(
                    if (tag == "system") {
                        androidx.core.os.LocaleListCompat.getEmptyLocaleList()
                    } else {
                        androidx.core.os.LocaleListCompat.forLanguageTags(tag)
                    }
                )
            }
            .show()
    }

    fun showPreferredAudioLangSelector(current: String) {
        AlertDialog.Builder(context)
            .setTitle(R.string.c_preferred_audio_language)
            .setSingleChoiceItems(
                SettingsOptionLabels.audioLangLabels,
                SettingsOptionLabels.audioLangIndex(current)
            ) { dialog, which ->
                viewModel.setPreferredAudioLang(SettingsOptionLabels.audioLangValues[which])
                dialog.dismiss()
            }
            .show()
    }

    /**
     * M0: TV vs mobile layout. Changing it restarts the app's UI, so the dialog says so
     * rather than leaving the user on a half-switched screen.
     */
    fun showUiModeSelector(current: String) {
        AlertDialog.Builder(context)
            .setTitle(R.string.c_app_layout)
            .setSingleChoiceItems(
                SettingsOptionLabels.uiModeLabels,
                SettingsOptionLabels.uiModeIndex(current)
            ) { dialog, which ->
                viewModel.setUiMode(SettingsOptionLabels.uiModeValues[which])
                dialog.dismiss()
            }
            .show()
    }

    /** H9: the fallback priority — used when the primary language is not in a source. */
    fun showPreferredAudioLang2Selector(current: String) {
        AlertDialog.Builder(context)
            .setTitle(R.string.c_secondary_audio_language)
            .setSingleChoiceItems(
                SettingsOptionLabels.audioLang2Labels,
                SettingsOptionLabels.audioLang2Index(current)
            ) { dialog, which ->
                viewModel.setPreferredAudioLang2(SettingsOptionLabels.audioLang2Values[which])
                dialog.dismiss()
            }
            .show()
    }

    fun showEpgIntervalSelector(current: String) {
        AlertDialog.Builder(context)
            .setTitle(R.string.c_select_epg_sync_interval)
            .setSingleChoiceItems(
                SettingsOptionLabels.epgIntervalLabels,
                SettingsOptionLabels.epgIntervalIndex(current)
            ) { dialog, which ->
                viewModel.setEpgSyncInterval(SettingsOptionLabels.epgIntervalValues[which])
                dialog.dismiss()
            }
            .show()
    }

    fun showLiveTvStyleSelector(current: String) {
        AlertDialog.Builder(context)
            .setTitle(R.string.c_live_tv_layout)
            .setSingleChoiceItems(
                SettingsOptionLabels.liveTvStyleLabels,
                SettingsOptionLabels.liveTvStyleIndex(current)
            ) { dialog, which ->
                viewModel.setLiveTvStyle(SettingsOptionLabels.liveTvStyleValues[which])
                dialog.dismiss()
                Toast.makeText(context, context.getString(R.string.c_applies_next_time_you_open), Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    fun showEpgZoomSelector(current: String) {
        AlertDialog.Builder(context)
            .setTitle(R.string.c_tv_guide_timeline_zoom)
            .setSingleChoiceItems(
                SettingsOptionLabels.epgZoomLabels,
                SettingsOptionLabels.epgZoomIndex(current)
            ) { dialog, which ->
                viewModel.setEpgTimelineZoom(SettingsOptionLabels.epgZoomValues[which])
                dialog.dismiss()
            }
            .show()
    }

    fun showEpgDensitySelector(current: String) {
        AlertDialog.Builder(context)
            .setTitle(R.string.c_tv_guide_row_density)
            .setSingleChoiceItems(
                SettingsOptionLabels.epgDensityLabels,
                SettingsOptionLabels.epgDensityIndex(current)
            ) { dialog, which ->
                viewModel.setEpgRowDensity(SettingsOptionLabels.epgDensityValues[which])
                dialog.dismiss()
            }
            .show()
    }

    // ── Stremio addons (the primary addon path) ──────────────────────────────

    fun showManageStremioAddonsDialog(currentUrls: Set<String>) {
        val urls = currentUrls.toMutableList()
        val buildItems = {
            if (urls.isEmpty()) {
                arrayOf("No Stremio addons added yet.\nTap '+ Add Addon' below to add one.")
            } else {
                urls.mapIndexed { index, url ->
                    val shortName = try {
                        val uri = android.net.Uri.parse(url)
                        val host = uri.host ?: "Stremio addon"
                        val path = uri.pathSegments.takeLast(2).joinToString("/")
                        "${index + 1}. $host/$path"
                    } catch (_: Exception) {
                        "${index + 1}. ${url.takeLast(60)}"
                    }
                    shortName
                }.toTypedArray()
            }
        }

        AlertDialog.Builder(context)
            .setTitle("Stremio Addons (${urls.size})")
            .setItems(buildItems()) { _, which ->
                if (urls.isNotEmpty() && which < urls.size) {
                    val urlToRemove = urls[which]
                    AlertDialog.Builder(context)
                        .setTitle(R.string.c_remove_stremio_addon)
                        .setMessage(android.net.Uri.parse(urlToRemove).host ?: "Configured addon")
                        .setPositiveButton("Remove") { d, _ ->
                            viewModel.removeStremioAddonUrl(urlToRemove)
                            Toast.makeText(context, context.getString(R.string.c_stremio_addon_removed), Toast.LENGTH_SHORT).show()
                            d.dismiss()
                        }
                        .setNegativeButton("Cancel") { d, _ -> d.dismiss() }
                        .show()
                }
            }
            .setPositiveButton("+ Add Addon") { dialog, _ ->
                dialog.dismiss()
                showAddStremioAddonInput()
            }
            .setNegativeButton("Close") { dialog, _ -> dialog.dismiss() }
            .show()
    }

    private fun showAddStremioAddonInput() {
        val input = android.widget.EditText(context).apply {
            setHint("https://addon.example/config/manifest.json")
            setTextColor(android.graphics.Color.WHITE)
            setHintTextColor(android.graphics.Color.GRAY)
            setPadding(40, 30, 40, 30)
            // M14: a URL is ONE line. Left to grow, a pasted manifest URL wrapped to five or six
            // lines, the dialog outgrew a 411dp-tall landscape screen, and "Add" ended up below
            // the fold with nothing to scroll — the action was simply unreachable. Single-line +
            // horizontal scrolling means the dialog height cannot depend on what was pasted.
            setSingleLine()
            setHorizontallyScrolling(true)
            inputType = android.text.InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE
        }

        AlertDialog.Builder(context)
            .setTitle(R.string.c_add_stremio_addon)
            .setMessage(R.string.c_paste_the_full_stremio_manifest)
            // Belt and braces: even single-line, the title + message + field can exceed a short
            // landscape screen once fontScale is applied, and a dialog does not scroll by itself.
            .setView(android.widget.ScrollView(context).apply { addView(input) })
            .setPositiveButton("Add") { dialog, _ ->
                val url = input.text.toString().trim()
                if (SettingsOptionLabels.isValidStremioManifestUrl(url)) {
                    viewModel.addStremioAddonUrl(url)
                    Toast.makeText(context, context.getString(R.string.c_stremio_addon_added), Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, context.getString(R.string.c_invalid_manifest_url), Toast.LENGTH_SHORT).show()
                }
                dialog.dismiss()
            }
            .setNegativeButton("Cancel") { dialog, _ -> dialog.dismiss() }
            .show()
    }

    /** Legacy Real-Debrid raw-magnet fallback — not the account logout. */
    fun showDebridLogoutConfirmation() {
        AlertDialog.Builder(context)
            .setTitle(R.string.c_log_out_of_legacy_real)
            .setMessage(R.string.c_you_will_lose_access_to)
            .setPositiveButton("Log Out") { dialog, _ ->
                viewModel.logoutDebrid()
                dialog.dismiss()
                Toast.makeText(context, context.getString(R.string.c_logged_out_of_legacy_fallback), Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel") { dialog, _ -> dialog.dismiss() }
            .show()
    }
}
