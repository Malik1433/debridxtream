package com.tvonnet.debridxtreamiptv.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.commit
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.tvonnet.debridxtreamiptv.R
import com.tvonnet.debridxtreamiptv.data.model.SyncState
import com.tvonnet.debridxtreamiptv.data.model.SyncType
import com.tvonnet.debridxtreamiptv.data.prefs.CredentialsPreferences
import com.tvonnet.debridxtreamiptv.data.repository.XtreamRepository
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.launch

@AndroidEntryPoint
class InitialSyncFragment :
    Fragment(),
    com.tvonnet.debridxtreamiptv.util.PortraitScreen {

    @Inject
    lateinit var repository: XtreamRepository

    private var syncSubtitle: TextView? = null
    private lateinit var syncProgress: ProgressBar
    private lateinit var syncStatus: TextView
    private lateinit var syncPercent: TextView
    private lateinit var countLive: TextView
    private lateinit var countMovies: TextView
    private lateinit var countSeries: TextView
    private lateinit var syncError: TextView
    private lateinit var retryButton: View

    private var hasNavigated = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_initial_sync, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        syncSubtitle = view.findViewById(R.id.tv_sync_subtitle)
        syncProgress = view.findViewById(R.id.sync_progress)
        syncStatus = view.findViewById(R.id.tv_sync_status)
        syncPercent = view.findViewById(R.id.tv_sync_percent)
        countLive = view.findViewById(R.id.tv_count_live)
        countMovies = view.findViewById(R.id.tv_count_movies)
        countSeries = view.findViewById(R.id.tv_count_series)
        syncError = view.findViewById(R.id.tv_sync_error)
        retryButton = view.findViewById(R.id.btn_sync_retry)

        retryButton.setOnClickListener {
            startSync()
        }

        observeSyncProgress()
        startSyncIfNeeded()
    }

    private fun startSyncIfNeeded() {
        val current = repository.syncProgress.value
        if (current.type == SyncType.INITIAL && current.state == SyncState.RUNNING) {
            return
        }
        if (current.type == SyncType.INITIAL && current.state == SyncState.SUCCESS) {
            navigateToHome()
            return
        }
        startSync()
    }

    private fun startSync() {
        syncError.visibility = View.GONE
        retryButton.visibility = View.GONE
        retryButton.isEnabled = false

        viewLifecycleOwner.lifecycleScope.launch {
            if (openExistingLibraryIfSwitched()) return@launch
            val result = repository.syncInitialData()
            if (result.isSuccess) {
                // Option A: the data on disk now belongs to this provider. Recorded on SUCCESS
                // only — a failed sync leaves the disagreement in place, so a retry still knows a
                // switch is outstanding.
                CredentialsPreferences(requireContext().applicationContext).markServerDataSynced()
                navigateToHome()
            } else {
                showError(result.exceptionOrNull()?.message)
            }
        }
    }

    /**
     * A3: the switch itself.
     *
     * Under Option B this method purged the previous provider and every switch paid for a full
     * re-sync. Each provider now owns its own database and preference files, so there is nothing to
     * throw away and usually nothing to fetch — if this device has run this provider before, its
     * library is still here and the right thing to do is open it.
     *
     * @return true when the library was already here and Home has been opened.
     */
    private fun openExistingLibraryIfSwitched(): Boolean {
        val prefs = CredentialsPreferences(requireContext().applicationContext)
        if (!prefs.isServerDataStale()) return false
        showSwitchNotice(prefs)
        if (!repository.hasCache()) return false // never run here — build the library
        prefs.markServerDataSynced()
        navigateToHome()
        return true
    }

    /**
     * Says which provider we moved to, by the name the customer gave it on the portal. Nothing
     * else on this screen would explain why their library just emptied.
     */
    private fun showSwitchNotice(prefs: CredentialsPreferences) {
        if (view == null) return
        val name = prefs.serverLabel.ifBlank { hostOf(prefs.getServerUrl()) }
        if (name.isBlank()) return
        syncSubtitle?.text = getString(R.string.sync_switched_to_server, name)
    }

    private fun hostOf(url: String?): String =
        runCatching { android.net.Uri.parse(url).host }.getOrNull().orEmpty()

    private fun observeSyncProgress() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                repository.syncProgress.collect { progress ->
                    if (progress.type != SyncType.INITIAL) return@collect

                    syncProgress.progress = progress.percent
                    syncPercent.text = "${progress.percent}%"
                    syncStatus.text = progress.stage
                    countLive.text = progress.liveCount.toString()
                    countMovies.text = progress.vodCount.toString()
                    countSeries.text = progress.seriesCount.toString()

                    when (progress.state) {
                        SyncState.ERROR -> showError(progress.errorMessage)
                        SyncState.SUCCESS -> navigateToHome()
                        SyncState.RUNNING -> retryButton.isEnabled = false
                        else -> Unit
                    }
                }
            }
        }
    }

    private fun showError(message: String?) {
        syncStatus.text = syncStatus.context.getString(R.string.c_sync_failed)
        syncError.text = message ?: "Sync failed. Check your connection."
        syncError.visibility = View.VISIBLE
        retryButton.visibility = View.VISIBLE
        retryButton.isEnabled = true
    }

    private fun navigateToHome() {
        if (hasNavigated) return
        hasNavigated = true
        requireActivity().supportFragmentManager.commit {
            replace(R.id.content_container, com.tvonnet.debridxtreamiptv.ui.nav.SectionNavigator.createHomeFragment(requireContext()))
        }
    }
}
