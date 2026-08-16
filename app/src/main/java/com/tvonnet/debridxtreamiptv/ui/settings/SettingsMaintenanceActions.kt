package com.tvonnet.debridxtreamiptv.ui.settings

import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.commit
import androidx.lifecycle.lifecycleScope
import com.tvonnet.debridxtreamiptv.R
import com.tvonnet.debridxtreamiptv.data.prefs.CredentialsPreferences
import com.tvonnet.debridxtreamiptv.data.repository.ServerDataReset
import com.tvonnet.debridxtreamiptv.data.repository.XtreamRepository
import com.tvonnet.debridxtreamiptv.ui.LoginFragment
import com.tvonnet.debridxtreamiptv.ui.settings.adapters.SettingItem
import kotlinx.coroutines.launch

/**
 * C1-c: the settings actions that **do something to the app's data**, as opposed to setting a
 * preference — force a catalog refresh, sync EPG, clear the cache, choose Home row categories, and
 * log the account out.
 *
 * These were the riskiest lines in [SettingsFragment] and the least visible: account logout wipes
 * caches, watch history and credentials before navigating away, and it is the only path that can
 * leave the app half-signed-out if the order changes. Grouping them makes that order reviewable in
 * one place.
 *
 * Bodies moved verbatim; only `this`/field references were rebound.
 */
class SettingsMaintenanceActions(
    private val fragment: Fragment,
    private val viewModel: SettingsViewModel,
    private val repository: XtreamRepository,
    // S3: replaces the CacheManager + CacheHelper this class used to take. Both existed only for
    // the logout wipe, and both were half of it — see performAccountLogout.
    private val serverDataReset: ServerDataReset,
) {
    private val context get() = fragment.requireContext()

    /**
     * G4: the row that starts the catalogue refresh, and reports it while it runs.
     *
     * Reported on the row rather than in a Toast because the job takes MINUTES: the old Toast
     * vanished in three seconds and left a screen that looked exactly as it had before the tap, so
     * the only feedback available was tapping again.
     */
    fun refreshRow(title: String, description: String): SettingItem {
        val progress = running ?: return SettingItem.Action(
            key = "refresh_iptv",
            title = title,
            description = description,
            onClick = { refreshIptvData() },
        )
        return SettingItem.Progress(
            key = "refresh_iptv",
            title = title,
            stage = progress,
            percent = percent,
            onCancel = { cancelRefresh() },
        )
    }

    /** Non-null only while a refresh this screen started is running. */
    private var running: String? = null
    private var percent: Int = 0
    private var refreshJob: kotlinx.coroutines.Job? = null

    /**
     * Stops the refresh the customer started.
     *
     * Cancelling the coroutine is enough: the sync's stages are suspending calls inside it, so
     * structured concurrency unwinds them, and a partly-written category is what the next refresh
     * repairs. What must NOT happen is the row staying stuck on a job nobody is running.
     */
    private fun cancelRefresh() {
        refreshJob?.cancel()
        refreshJob = null
        running = null
        percent = 0
        viewModel.refreshUi()
    }

    fun refreshIptvData() {
        Toast.makeText(context, context.getString(R.string.c_refreshing_iptv_data), Toast.LENGTH_SHORT).show()
        refreshJob = fragment.viewLifecycleOwner.lifecycleScope.launch {
            watchProgress()
            try {
                val result = repository.forceRefresh()
                if (result is com.tvonnet.debridxtreamiptv.data.Result.Success) {
                    Toast.makeText(context, context.getString(R.string.c_iptv_data_refreshed_successfully), Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, context.getString(R.string.c_refresh_failed_please_try_again), Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                android.util.Log.e(TAG, "IPTV refresh failed", e)
                Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                running = null
                percent = 0
                viewModel.refreshUi()
            }
        }
    }

    /**
     * Mirrors the repository's own progress onto the row, for as long as this screen is alive.
     *
     * A child of the refresh job on purpose: when the refresh ends or is cancelled, this ends with
     * it and cannot leave the row reporting a job that is over.
     */
    private fun watchProgress() {
        fragment.viewLifecycleOwner.lifecycleScope.launch {
            repository.syncProgress.collect { p ->
                if (p.state != com.tvonnet.debridxtreamiptv.data.model.SyncState.RUNNING) return@collect
                running = "${p.stage.uppercase(java.util.Locale.ROOT)} · ${p.percent}%"
                percent = p.percent
                viewModel.refreshUi()
            }
        }
    }

    fun syncEpgNow() {
        Toast.makeText(context, context.getString(R.string.c_syncing_epg_data), Toast.LENGTH_SHORT).show()
        fragment.viewLifecycleOwner.lifecycleScope.launch {
            try {
                val result = repository.fetchAndSaveEpg()
                if (result is com.tvonnet.debridxtreamiptv.data.Result.Success) {
                    Toast.makeText(context, "EPG synced: ${result.data} programs", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, context.getString(R.string.c_epg_sync_failed_please_try), Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                android.util.Log.e(TAG, "EPG sync failed", e)
                Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    /**
     * G4: the confirmation names the cost and what survives it — see [SettingsConfirmSheet]. The
     * old one asked "are you sure" and left the customer to guess whether this was the button that
     * deletes their library.
     */
    fun showClearCacheDialog() {
        SettingsConfirmSheet.show(
            fragment,
            SettingsConfirmSheet.Spec(
                title = context.getString(R.string.s_confirm_clear_cache_title),
                cost = context.getString(R.string.s_confirm_clear_cache_cost),
                kept = context.getString(R.string.s_confirm_clear_cache_kept),
                confirmLabel = context.getString(R.string.s_confirm_clear_cache_go),
            ),
        ) {
            try {
                context.cacheDir.deleteRecursively()
                Toast.makeText(context, context.getString(R.string.c_cache_cleared), Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Clear cache failed", e)
                Toast.makeText(context, context.getString(R.string.c_failed_to_clear_cache), Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * G4: signing out now clears EVERY server's library on this device, so the confirmation has to
     * say so — and has to say what is safe, or a customer reasonably assumes their account goes
     * with it.
     */
    fun showAccountLogoutConfirmation() {
        SettingsConfirmSheet.show(
            fragment,
            SettingsConfirmSheet.Spec(
                title = context.getString(R.string.s_confirm_sign_out_title),
                cost = context.getString(R.string.s_confirm_sign_out_cost),
                kept = context.getString(R.string.s_confirm_sign_out_kept),
                confirmLabel = context.getString(R.string.s_confirm_sign_out_go),
            ),
        ) { performAccountLogout() }
    }

    /**
     * Order matters: the data goes first on IO, credentials last, and only then do we navigate —
     * leaving early would strand a signed-out session holding a populated cache.
     *
     * S3: the wipe is [ServerDataReset] rather than the four calls that used to be here. Those
     * cleared the memory caches, the channel and category tables, and the watch history — and left
     * behind every movie, series, season, episode, favourite, watched-state row and search this
     * account had accumulated. The next person to sign in on this device inherited all of it.
     */
    private fun performAccountLogout() {
        val appContext = context.applicationContext
        val activity = fragment.requireActivity()
        fragment.viewLifecycleOwner.lifecycleScope.launch {
            runCatching { serverDataReset.purge(ServerDataReset.Scope.ACCOUNT) }
                .onFailure { android.util.Log.e(TAG, "Logout purge failed", it) }
            CredentialsPreferences(appContext).clearCredentials()
            // Written AFTER the credentials are gone: the device now belongs to no provider, so
            // the next sign-in is a change of provider and the switch path runs for it too.
            CredentialsPreferences(appContext).markServerDataSynced()
            viewModel.logoutDebrid()
            navigateToLogin(activity)
        }
    }

    private fun navigateToLogin(activity: FragmentActivity) {
        activity.supportFragmentManager.popBackStack(null, FragmentManager.POP_BACK_STACK_INCLUSIVE)
        activity.supportFragmentManager.commit {
            replace(R.id.content_container, LoginFragment())
        }
    }

    fun showCategorySelector(type: String) {
        if (!fragment.isAdded) return

        val loadingDialog = AlertDialog.Builder(context)
            .setTitle(R.string.c_loading_categories)
            .setView(android.widget.ProgressBar(context).apply { setPadding(40, 40, 40, 40) })
            .setCancelable(false)
            .create()

        loadingDialog.show()

        fragment.viewLifecycleOwner.lifecycleScope.launch {
            try {
                val categories = viewModel.getCategories(type)
                if (loadingDialog.isShowing) {
                    loadingDialog.dismiss()
                }

                if (!fragment.isAdded || fragment.isStateSaved) return@launch

                if (categories.isEmpty()) {
                    Toast.makeText(context, context.getString(R.string.c_no_categories_found_sync_active), Toast.LENGTH_SHORT).show()
                    return@launch
                }

                if (showCategorySheet(type, categories)) return@launch

                // Fix: Create instance and configure WITHOUT using .apply { ... } to avoid context scope confusion
                val dialogFragment = CategorySelectionDialog.newInstance(type, categories)

                dialogFragment.setCallback {
                    if (fragment.isAdded) {
                        Toast.makeText(context, context.getString(R.string.c_home_preferences_saved), Toast.LENGTH_SHORT).show()
                    }
                }

                dialogFragment.show(fragment.childFragmentManager, CategorySelectionDialog.TAG)
            } catch (e: Exception) {
                if (loadingDialog.isShowing) {
                    loadingDialog.dismiss()
                }
                if (fragment.isAdded) {
                    // Log error but avoid showing "not attached" toast if that was the issue
                    val msg = e.message ?: "Unknown error"
                    if (!msg.contains("not been attached yet")) {
                        Toast.makeText(context, "Error: $msg", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    /**
     * G3/G4: the three row choosers, as the phone's bottom sheet.
     *
     * These were the last pickers still opening [CategorySelectionDialog] — a centre panel with its
     * own checkbox list, which is the one shape the phone rulebook does not allow. The sheet is the
     * same one the other eight selectors use, so a customer meets one picker on this phone rather
     * than two. It carries a search field on its own above twelve entries, which matters here more
     * than anywhere: a provider can list hundreds of categories.
     *
     * Television keeps the dialog — it is correct at three metres and D-pad-native — so this
     * returns false there and the caller falls through.
     */
    private fun showCategorySheet(
        type: String,
        categories: List<com.tvonnet.debridxtreamiptv.data.model.XtreamCategory>,
    ): Boolean {
        if (context.resources.getBoolean(R.bool.ui_uses_dpad_focus)) return false
        val options = categories.mapNotNull { category ->
            val id = category.category_id?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            SettingsPickerSheet.Option(id, category.category_name?.takeIf { it.isNotBlank() } ?: id)
        }
        if (options.isEmpty()) return false
        SettingsPickerSheet.multi(
            host = fragment,
            title = context.getString(categoryChooserTitle(type)),
            options = options,
            selected = viewModel.getSelectedCategories(type),
        ) { chosen ->
            viewModel.saveCategories(type, chosen)
            if (fragment.isAdded) {
                Toast.makeText(context, context.getString(R.string.c_home_preferences_saved), Toast.LENGTH_SHORT).show()
            }
        }
        return true
    }

    private fun categoryChooserTitle(type: String): Int = when (type) {
        "movie" -> R.string.s_movie_rows
        "series" -> R.string.s_series_rows
        else -> R.string.s_live_tv_rows
    }

    private companion object {
        private const val TAG = "SettingsFragment"
    }
}
