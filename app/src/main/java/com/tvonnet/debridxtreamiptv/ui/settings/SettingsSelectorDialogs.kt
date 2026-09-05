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
     * G3: one door for every single-choice picker — a bottom sheet on a phone, the dialog it has
     * always been on a television.
     *
     * Routed here rather than at each of the eight call sites so the form-factor question is asked
     * ONCE. Eight copies of the same branch is eight chances for the next picker to be added on the
     * wrong side of it.
     */
    private fun singleChoice(
        titleRes: Int,
        labels: Array<String>,
        checkedIndex: Int,
        onPick: (Int) -> Unit,
    ) {
        if (context.resources.getBoolean(R.bool.ui_uses_dpad_focus)) {
            AlertDialog.Builder(context)
                .setTitle(titleRes)
                .setSingleChoiceItems(labels, checkedIndex) { dialog, which ->
                    dialog.dismiss()
                    onPick(which)
                }
                .show()
            return
        }
        SettingsPickerSheet.single(fragment, context.getString(titleRes), labels, checkedIndex, onPick)
    }


    /**
     * The app's own language. `AppCompatDelegate` is used rather than the platform API on
     * purpose: it carries per-app locales back to older Androids, so a Fire TV — which has no
     * system per-app language screen at all — can change language here too. AppCompat persists
     * the choice itself and recreates the visible activities, so nothing else has to store it.
     */
    fun showAppLanguageSelector(current: String) {
        singleChoice(R.string.c_app_language, SettingsOptionLabels.appLanguageLabels, SettingsOptionLabels.appLanguageIndex(current)) { which ->
                val tag = SettingsOptionLabels.appLanguageValues[which]
                                androidx.appcompat.app.AppCompatDelegate.setApplicationLocales(
                    if (tag == "system") {
                        androidx.core.os.LocaleListCompat.getEmptyLocaleList()
                    } else {
                        androidx.core.os.LocaleListCompat.forLanguageTags(tag)
                    }
                )
        }
    }

    fun showPreferredAudioLangSelector(current: String) {
        singleChoice(R.string.c_preferred_audio_language, SettingsOptionLabels.audioLangLabels, SettingsOptionLabels.audioLangIndex(current)) { which ->
                viewModel.setPreferredAudioLang(SettingsOptionLabels.audioLangValues[which])
        }
    }

    /**
     * M0: TV vs mobile layout. Changing it restarts the app's UI, so the dialog says so
     * rather than leaving the user on a half-switched screen.
     */
    fun showUiModeSelector(current: String) {
        singleChoice(R.string.c_app_layout, SettingsOptionLabels.uiModeLabels, SettingsOptionLabels.uiModeIndex(current)) { which ->
                viewModel.setUiMode(SettingsOptionLabels.uiModeValues[which])
        }
    }

    /** H9: the fallback priority — used when the primary language is not in a source. */
    fun showPreferredAudioLang2Selector(current: String) {
        singleChoice(R.string.c_secondary_audio_language, SettingsOptionLabels.audioLang2Labels, SettingsOptionLabels.audioLang2Index(current)) { which ->
                viewModel.setPreferredAudioLang2(SettingsOptionLabels.audioLang2Values[which])
        }
    }

    fun showEpgIntervalSelector(current: String) {
        singleChoice(R.string.c_select_epg_sync_interval, SettingsOptionLabels.epgIntervalLabels, SettingsOptionLabels.epgIntervalIndex(current)) { which ->
                viewModel.setEpgSyncInterval(SettingsOptionLabels.epgIntervalValues[which])
        }
    }

    fun showLiveTvStyleSelector(current: String) {
        singleChoice(R.string.c_live_tv_layout, SettingsOptionLabels.liveTvStyleLabels, SettingsOptionLabels.liveTvStyleIndex(current)) { which ->
                viewModel.setLiveTvStyle(SettingsOptionLabels.liveTvStyleValues[which])
                                Toast.makeText(context, context.getString(R.string.c_applies_next_time_you_open), Toast.LENGTH_SHORT).show()
        }
    }

    fun showEpgZoomSelector(current: String) {
        singleChoice(R.string.c_tv_guide_timeline_zoom, SettingsOptionLabels.epgZoomLabels, SettingsOptionLabels.epgZoomIndex(current)) { which ->
                viewModel.setEpgTimelineZoom(SettingsOptionLabels.epgZoomValues[which])
        }
    }

    fun showEpgDensitySelector(current: String) {
        singleChoice(R.string.c_tv_guide_row_density, SettingsOptionLabels.epgDensityLabels, SettingsOptionLabels.epgDensityIndex(current)) { which ->
                viewModel.setEpgRowDensity(SettingsOptionLabels.epgDensityValues[which])
        }
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
            .setTitle(context.getString(R.string.f_stremio_addons_count, urls.size))
            .setItems(buildItems()) { _, which ->
                if (urls.isNotEmpty() && which < urls.size) {
                    val urlToRemove = urls[which]
                    AlertDialog.Builder(context)
                        .setTitle(R.string.c_remove_stremio_addon)
                        .setMessage(android.net.Uri.parse(urlToRemove).host ?: context.getString(R.string.c_configured_addon))
                        .setPositiveButton(R.string.c_remove) { d, _ ->
                            viewModel.removeStremioAddonUrl(urlToRemove)
                            Toast.makeText(context, context.getString(R.string.c_stremio_addon_removed), Toast.LENGTH_SHORT).show()
                            d.dismiss()
                        }
                        .setNegativeButton(android.R.string.cancel) { d, _ -> d.dismiss() }
                        .show()
                }
            }
            .setPositiveButton(R.string.c_add_addon) { dialog, _ ->
                dialog.dismiss()
                showAddStremioAddonInput()
            }
            .setNegativeButton(R.string.c_close) { dialog, _ -> dialog.dismiss() }
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
            .setPositiveButton(R.string.c_add) { dialog, _ ->
                val url = input.text.toString().trim()
                if (SettingsOptionLabels.isValidStremioManifestUrl(url)) {
                    viewModel.addStremioAddonUrl(url)
                    Toast.makeText(context, context.getString(R.string.c_stremio_addon_added), Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, context.getString(R.string.c_invalid_manifest_url), Toast.LENGTH_SHORT).show()
                }
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel) { dialog, _ -> dialog.dismiss() }
            .show()
    }

    /** Legacy Real-Debrid raw-magnet fallback — not the account logout. */
    fun showDebridLogoutConfirmation() {
        AlertDialog.Builder(context)
            .setTitle(R.string.c_log_out_of_legacy_real)
            .setMessage(R.string.c_you_will_lose_access_to)
            .setPositiveButton(R.string.c_log_out) { dialog, _ ->
                viewModel.logoutDebrid()
                dialog.dismiss()
                Toast.makeText(context, context.getString(R.string.c_logged_out_of_legacy_fallback), Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(android.R.string.cancel) { dialog, _ -> dialog.dismiss() }
            .show()
    }
}
