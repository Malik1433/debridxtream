package com.tvonnet.debridxtreamiptv.ui.settings

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

    fun showPreferredAudioLangSelector(current: String) {
        AlertDialog.Builder(context)
            .setTitle("Preferred Audio Language")
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
            .setTitle("App Layout")
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
            .setTitle("Secondary Audio Language")
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
            .setTitle("Select EPG Sync Interval")
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
            .setTitle("Live TV Layout")
            .setSingleChoiceItems(
                SettingsOptionLabels.liveTvStyleLabels,
                SettingsOptionLabels.liveTvStyleIndex(current)
            ) { dialog, which ->
                viewModel.setLiveTvStyle(SettingsOptionLabels.liveTvStyleValues[which])
                dialog.dismiss()
                Toast.makeText(context, "Applies next time you open Live TV", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    fun showEpgZoomSelector(current: String) {
        AlertDialog.Builder(context)
            .setTitle("TV Guide Timeline Zoom")
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
            .setTitle("TV Guide Row Density")
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
                        .setTitle("Remove Stremio Addon?")
                        .setMessage(android.net.Uri.parse(urlToRemove).host ?: "Configured addon")
                        .setPositiveButton("Remove") { d, _ ->
                            viewModel.removeStremioAddonUrl(urlToRemove)
                            Toast.makeText(context, "Stremio addon removed", Toast.LENGTH_SHORT).show()
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
        }

        AlertDialog.Builder(context)
            .setTitle("Add Stremio Addon")
            .setMessage("Paste the full Stremio manifest.json URL:")
            .setView(input)
            .setPositiveButton("Add") { dialog, _ ->
                val url = input.text.toString().trim()
                if (SettingsOptionLabels.isValidStremioManifestUrl(url)) {
                    viewModel.addStremioAddonUrl(url)
                    Toast.makeText(context, "Stremio addon added", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, "Invalid manifest URL", Toast.LENGTH_SHORT).show()
                }
                dialog.dismiss()
            }
            .setNegativeButton("Cancel") { dialog, _ -> dialog.dismiss() }
            .show()
    }

    /** Legacy Real-Debrid raw-magnet fallback — not the account logout. */
    fun showDebridLogoutConfirmation() {
        AlertDialog.Builder(context)
            .setTitle("Log Out of Legacy Real-Debrid Fallback?")
            .setMessage("You will lose access to the raw magnet fallback. Are you sure?")
            .setPositiveButton("Log Out") { dialog, _ ->
                viewModel.logoutDebrid()
                dialog.dismiss()
                Toast.makeText(context, "Logged out of legacy fallback", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel") { dialog, _ -> dialog.dismiss() }
            .show()
    }
}
